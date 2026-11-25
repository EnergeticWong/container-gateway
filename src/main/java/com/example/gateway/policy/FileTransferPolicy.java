package com.example.gateway.policy;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.error.GatewayException;
import com.example.gateway.web.dto.FileEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件收发安全策略：路径白名单、目录穿越防护、tar entry 校验、大小与数量上限。
 *
 * <p>上传只允许写入 {@code file-upload-dir}，下载/列举只允许读取 {@code file-output-dir}，
 * 二者都必须落在 {@code file-root} 下。
 */
@Component
public class FileTransferPolicy {

    private final GatewayProperties props;

    public FileTransferPolicy(GatewayProperties props) {
        this.props = props;
    }

    /** 校验并归一化上传目标目录，必须落在 file-upload-dir 下。 */
    public String requireUploadDir(String requested) {
        requireEnabled();
        String base = normalize(props.getFileUploadDir(), "file-upload-dir");
        String dir = (requested == null || requested.isBlank()) ? base : normalize(requested, "dest");
        requireUnder(base, dir, "上传目录");
        return dir;
    }

    /** 校验并归一化产物路径，必须落在 file-output-dir 下。 */
    public String requireOutputPath(String requested) {
        requireEnabled();
        String base = normalize(props.getFileOutputDir(), "file-output-dir");
        if (requested == null || requested.isBlank()) {
            throw GatewayException.badRequest("path 不能为空");
        }
        String path = normalize(requested, "path");
        requireUnder(base, path, "产物目录");
        return path;
    }

    private void requireEnabled() {
        if (!props.isFileTransferEnabled()) {
            throw GatewayException.notFound("文件收发功能未开启（GATEWAY_FILE_TRANSFER_ENABLED=false）");
        }
    }

    /**
     * 归一化容器内绝对路径：拒绝相对路径、{@code ..}、{@code .}、空段、NUL 与反斜杠，并折叠重复斜杠。
     */
    public static String normalize(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw GatewayException.badRequest(field + " 不能为空");
        }
        String s = raw.trim();
        if (!s.startsWith("/")) {
            throw GatewayException.badRequest(field + " 必须是容器内绝对路径: " + raw);
        }
        if (s.indexOf('\0') >= 0 || s.contains("\\")) {
            throw GatewayException.badRequest(field + " 含非法字符: " + raw);
        }
        List<String> segments = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                throw GatewayException.badRequest(field + " 不允许包含 '..': " + raw);
            }
            segments.add(seg);
        }
        if (segments.isEmpty()) {
            throw GatewayException.badRequest(field + " 不能是根目录: " + raw);
        }
        return "/" + String.join("/", segments);
    }

    private static void requireUnder(String base, String path, String what) {
        if (!path.equals(base) && !path.startsWith(base + "/")) {
            throw GatewayException.badRequest(what + "必须位于 " + base + " 下，实际: " + path);
        }
    }

    /** 有界读取，超限抛 413 而不是把内存打满。 */
    public byte[] readBounded(InputStream in, long limit, String what) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > limit) {
                throw GatewayException.payloadTooLarge(what + "超过 " + limit + " 字节上限");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 校验 tar 归档内容并返回条目清单。
     *
     * <p>拒绝：绝对路径、含 {@code ..}、符号链接/硬链接/设备/FIFO 等非普通条目；限制条目数与单文件/总大小。
     */
    public List<FileEntry> inspectTar(byte[] tar, String what) {
        List<FileEntry> entries = new ArrayList<>();
        long total = 0;
        try (TarArchiveInputStream tin = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry e;
            while ((e = tin.getNextTarEntry()) != null) {
                if (entries.size() >= props.getFileMaxCount()) {
                    throw GatewayException.badRequest(what + "文件数超过上限 " + props.getFileMaxCount());
                }
                validateEntry(e, what);
                total += Math.max(0L, e.getSize());
                if (total > props.getFileMaxTotalBytes()) {
                    throw GatewayException.payloadTooLarge(
                            what + "解压后总大小超过 " + props.getFileMaxTotalBytes() + " 字节上限");
                }
                entries.add(new FileEntry(e.getName(), e.getSize(), e.isDirectory() ? "dir" : "file"));
            }
        } catch (IOException ex) {
            throw GatewayException.badRequest(what + "不是合法的 tar 归档: " + ex.getMessage());
        }
        if (entries.isEmpty()) {
            throw GatewayException.badRequest(what + "为空归档");
        }
        return entries;
    }

    private void validateEntry(TarArchiveEntry e, String what) {
        String name = e.getName();
        if (name == null || name.isBlank()) {
            throw GatewayException.badRequest(what + "含空条目名");
        }
        if (name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw GatewayException.badRequest(what + "含非法条目名: " + name);
        }
        for (String seg : name.split("/")) {
            if ("..".equals(seg)) {
                throw GatewayException.badRequest(what + "含目录穿越条目: " + name);
            }
        }
        if (e.isSymbolicLink() || e.isLink()) {
            throw GatewayException.badRequest(what + "不允许符号链接/硬链接: " + name);
        }
        if (!e.isDirectory() && !e.isFile()) {
            throw GatewayException.badRequest(what + "只允许普通文件与目录: " + name);
        }
        if (!e.isDirectory() && e.getSize() > props.getFileMaxBytes()) {
            throw GatewayException.payloadTooLarge(
                    "文件 " + name + " 超过单文件上限 " + props.getFileMaxBytes() + " 字节");
        }
    }

    /**
     * 去掉 Docker GET archive 附加的顶层目录名（返回 tar 的首个条目是资源自身名字），
     * 并丢弃剥离后为空的条目。
     */
    public static List<FileEntry> stripLeadingComponent(List<FileEntry> entries) {
        List<FileEntry> result = new ArrayList<>(entries.size());
        for (FileEntry e : entries) {
            String name = stripFirst(e.name());
            if (name.isEmpty()) {
                continue;
            }
            result.add(new FileEntry(name, e.size(), e.type()));
        }
        return result;
    }

    private static String stripFirst(String name) {
        int slash = name.indexOf('/');
        return slash < 0 ? "" : name.substring(slash + 1);
    }
}
