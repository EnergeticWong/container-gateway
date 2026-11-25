package com.example.gateway.service;

import com.example.gateway.audit.AuditLogger;
import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.ContainerGuard;
import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.DockerOps;
import com.example.gateway.error.GatewayException;
import com.example.gateway.policy.FileTransferPolicy;
import com.example.gateway.web.dto.ExecResult;
import com.example.gateway.web.dto.FileEntry;
import com.example.gateway.web.dto.FileUploadResult;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 容器文件收发编排：归属校验 → 路径/归档策略校验 → Docker 归档读写 → 审计。
 *
 * <p>上传只写 {@code file-upload-dir}，下载/列举只读 {@code file-output-dir}；
 * 下载与列举返回 tar 字节，由平台侧再落 COS。
 */
@Service
public class FileTransferService {

    public static final String ACTION_FILE_UPLOAD = "file-upload";
    public static final String ACTION_FILE_DOWNLOAD = "file-download";
    public static final String ACTION_FILE_LIST = "file-list";

    private final DockerOps docker;
    private final ContainerGuard guard;
    private final FileTransferPolicy policy;
    private final GatewayProperties props;
    private final AuditLogger audit;

    public FileTransferService(DockerOps docker, ContainerGuard guard, FileTransferPolicy policy,
                               GatewayProperties props, AuditLogger audit) {
        this.docker = docker;
        this.guard = guard;
        this.policy = policy;
        this.props = props;
        this.audit = audit;
    }

    /** 上传 tar 归档到容器上传目录，返回条目汇总。 */
    public FileUploadResult upload(String clientId, String requestId, String sourceIp,
                                   String id, String dest, InputStream body) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        Map<String, Object> auditParams = new java.util.LinkedHashMap<>();
        auditParams.put("dest", dest);
        try {
        ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
        image = imageOf(owned.container());
        auditParams.put("node", owned.node().id());
        String target = policy.requireUploadDir(dest);

        byte[] tar;
        try {
            tar = policy.readBounded(body, props.getFileMaxTotalBytes(), "上传归档");
        } catch (IOException e) {
            throw GatewayException.docker("读取上传归档失败: " + e.getMessage());
        }
        List<FileEntry> entries = policy.inspectTar(tar, "上传归档");

        ensureDir(owned.node(), id, target);
        docker.copyArchiveToContainer(owned.node(), id, target, new ByteArrayInputStream(tar));

        int fileCount = 0;
        long totalBytes = 0L;
        List<String> names = new java.util.ArrayList<>(entries.size());
        for (FileEntry e : entries) {
            names.add(e.name());
            if ("file".equals(e.type())) {
                fileCount++;
                totalBytes += e.size();
            }
        }

        auditParams.put("dest", target);
        auditParams.put("fileCount", fileCount);
        auditParams.put("totalBytes", totalBytes);
        audit.ok(requestId, clientId, sourceIp, ACTION_FILE_UPLOAD, id, image, null, auditParams, startedAt);
        return new FileUploadResult(target, fileCount, totalBytes, names);
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_FILE_UPLOAD, id, image, null, auditParams,
                    errorCode(e), startedAt);
            throw e;
        }
    }

    /** 读取容器产物路径的 tar 归档字节。 */
    public byte[] download(String clientId, String requestId, String sourceIp, String id, String path) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        Map<String, Object> auditParams = new java.util.LinkedHashMap<>();
        auditParams.put("path", path);
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            auditParams.put("node", owned.node().id());
            String target = policy.requireOutputPath(path);
            auditParams.put("path", target);

            byte[] tar;
            try (InputStream in = docker.copyArchiveFromContainer(owned.node(), id, target)) {
                tar = policy.readBounded(in, props.getFileMaxTotalBytes(), "下载归档");
            } catch (IOException e) {
                throw GatewayException.docker("读取容器归档失败: " + e.getMessage());
            }
            policy.inspectTar(tar, "下载归档");

            auditParams.put("bytes", tar.length);
            audit.ok(requestId, clientId, sourceIp, ACTION_FILE_DOWNLOAD, id, image, null, auditParams, startedAt);
            return tar;
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_FILE_DOWNLOAD, id, image, null, auditParams,
                    errorCode(e), startedAt);
            throw e;
        }
    }

    /** 列举容器产物目录下的条目（去掉 Docker 附加的顶层目录名）。 */
    public List<FileEntry> list(String clientId, String requestId, String sourceIp, String id, String path) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        Map<String, Object> auditParams = new java.util.LinkedHashMap<>();
        auditParams.put("path", path);
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            auditParams.put("node", owned.node().id());
            String target = policy.requireOutputPath(path);
            auditParams.put("path", target);

            byte[] tar;
            try (InputStream in = docker.copyArchiveFromContainer(owned.node(), id, target)) {
                tar = policy.readBounded(in, props.getFileMaxTotalBytes(), "列举归档");
            } catch (IOException e) {
                throw GatewayException.docker("读取容器归档失败: " + e.getMessage());
            }
            List<FileEntry> entries = FileTransferPolicy.stripLeadingComponent(
                    policy.inspectTar(tar, "列举归档"));

            auditParams.put("count", entries.size());
            audit.ok(requestId, clientId, sourceIp, ACTION_FILE_LIST, id, image, null, auditParams, startedAt);
            return entries;
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_FILE_LIST, id, image, null, auditParams,
                    errorCode(e), startedAt);
            throw e;
        }
    }

    private static String imageOf(InspectContainerResponse c) {
        return c.getConfig() == null ? null : c.getConfig().getImage();
    }

    private static String errorCode(RuntimeException e) {
        return e instanceof GatewayException ge ? ge.getCode() : "INTERNAL";
    }

    /** Docker PUT archive 要求目标目录已存在，否则 404；上传前先 mkdir -p。 */
    private void ensureDir(DockerNode node, String id, String dir) {
        ExecResult r = docker.exec(node, id, List.of("mkdir", "-p", dir), List.of(),
                props.getExecDefaultTimeoutSeconds(), 4096);
        if (r.exitCode() != 0) {
            throw GatewayException.docker("在容器内创建目录失败: " + dir
                    + (r.stderr().isBlank() ? "" : " (" + r.stderr().trim() + ")"));
        }
    }
}
