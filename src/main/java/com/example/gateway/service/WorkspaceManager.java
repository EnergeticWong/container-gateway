package com.example.gateway.service;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.DockerOps;
import com.example.gateway.docker.Ownership;
import com.example.gateway.error.GatewayException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 网关托管工作区：为每个容器准备独立的可写 {@code /workspace}。
 *
 * <p>本机节点沿用宿主目录 bind（目录由网关创建并 chown）；远程节点使用命名卷
 * {@code gw-ws-<uuid>}（由目标节点的 dockerd 创建），容器销毁/回收时删卷。
 *
 * <p>动机：文件收发 API 底层用 Docker archive，而 archive <b>无法写 tmpfs</b>、在只读
 * rootfs 下也<b>被直接拒绝</b>。因此要同时满足「非 root + 只读 RootFS + 文件 API」，
 * {@code /workspace} 必须是 daemon 可见的可写挂载。
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** 工作区目录权限 0770（owner/group 可读写执行，其他无权限）。 */
    private static final String PERMS = "rwxrwx---";

    /** 远程节点命名卷前缀；清理时只接受该前缀，避免误删其他卷。 */
    public static final String VOLUME_PREFIX = "gw-ws-";

    public enum Kind {
        BIND,
        VOLUME
    }

    /** 一个容器的工作区描述：bind 时为宿主路径，volume 时为卷名。 */
    public record Workspace(Kind kind, String ref) {
        public boolean isVolume() {
            return kind == Kind.VOLUME;
        }
    }

    private final GatewayProperties props;
    private final DockerOps docker;

    public WorkspaceManager(GatewayProperties props, DockerOps docker) {
        this.props = props;
        this.docker = docker;
    }

    /** 是否启用托管工作区挂载。 */
    public boolean enabled() {
        return props.getWorkspaceHostDir() != null && !props.getWorkspaceHostDir().isBlank();
    }

    /**
     * 启动期校验：base 目录可用，且未与 tmpfs 冲突。
     *
     * <p>这两项都是配置错误导致的「运行期才 500/文件 API 坏掉」，故在启动时 fail-fast。
     */
    @PostConstruct
    void validate() {
        if (!enabled()) {
            return;
        }
        String fileRoot = props.getFileRoot();
        if (DockerOps.parseTmpfs(props.getTmpfs()).containsKey(fileRoot)) {
            throw new IllegalStateException(
                    "GATEWAY_WORKSPACE_HOST_DIR 已启用，但 GATEWAY_TMPFS 仍包含 " + fileRoot
                            + "；请从 tmpfs 移除该路径（工作区改由宿主 bind 挂载提供）");
        }
        Path base = baseDir();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法创建工作区宿主目录 " + base
                            + "（是否未加入 systemd ReadWritePaths？）: " + e.getMessage(), e);
        }
        if (!Files.isDirectory(base) || !Files.isWritable(base)) {
            throw new IllegalStateException("工作区宿主目录不可写: " + base);
        }
        log.info("托管工作区挂载已启用：base={} 容器内挂载点={} runAsUser={}（本机 bind / 远程 volume）",
                base, fileRoot, props.getRunAsUser());
    }

    /**
     * 为即将创建的容器准备工作区：本机节点建宿主目录，远程节点建命名卷。
     *
     * @return 未启用时返回 {@code null}
     */
    public Workspace prepare(DockerNode node, String clientId) {
        if (!enabled()) {
            return null;
        }
        if (node.local()) {
            Path dir = baseDir().resolve(UUID.randomUUID().toString());
            try {
                Files.createDirectory(dir);
                applyOwnership(dir);
            } catch (IOException e) {
                throw GatewayException.docker("创建工作区目录失败 " + dir + ": " + e.getMessage());
            }
            return new Workspace(Kind.BIND, dir.toString());
        }

        String name = VOLUME_PREFIX + UUID.randomUUID();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Ownership.WORKSPACE_MANAGED, "true");
        labels.put(Ownership.WORKSPACE_KIND, "volume");
        labels.put(Ownership.CREATED_AT, Instant.now().toString());
        if (clientId != null) {
            labels.put(Ownership.OWNER, clientId);
        }
        try {
            docker.createVolume(node, name, labels);
        } catch (RuntimeException e) {
            throw GatewayException.docker("在节点 " + node.id() + " 创建工作区卷失败: " + e.getMessage());
        }
        return new Workspace(Kind.VOLUME, name);
    }

    /** 把工作区挂到容器内 {@code file-root}（rw）。bind 与命名卷的 Bind 写法一致。 */
    public Bind bind(Workspace workspace) {
        return new Bind(workspace.ref(), new Volume(props.getFileRoot()), AccessMode.rw);
    }

    /** 从容器 label 还原工作区描述（兼容只有 workspaceHostDir 的旧容器）。 */
    public static Workspace fromLabels(Map<String, String> labels) {
        String kind = labels.get(Ownership.WORKSPACE_KIND);
        String ref = labels.get(Ownership.WORKSPACE_REF);
        if ("volume".equalsIgnoreCase(kind) && ref != null && !ref.isBlank()) {
            return new Workspace(Kind.VOLUME, ref);
        }
        if (ref != null && !ref.isBlank()) {
            return new Workspace(Kind.BIND, ref);
        }
        String legacy = labels.get(Ownership.WORKSPACE_HOST_DIR);
        if (legacy != null && !legacy.isBlank()) {
            return new Workspace(Kind.BIND, legacy);
        }
        return null;
    }

    /** 清理工作区：本机目录删除；远程卷删除（幂等）。 */
    public void cleanup(DockerNode node, Workspace workspace) {
        if (workspace == null) {
            return;
        }
        if (workspace.isVolume()) {
            String name = workspace.ref();
            if (!name.startsWith(VOLUME_PREFIX)) {
                log.warn("跳过清理非网关工作区卷: {}", name);
                return;
            }
            try {
                docker.removeVolume(node, name);
                log.info("已删除工作区卷 {}（节点 {}）", name, node.id());
            } catch (RuntimeException e) {
                log.warn("删除工作区卷 {} 失败: {}", name, e.getMessage());
            }
            return;
        }
        cleanupHostDir(workspace.ref());
    }

    /** 兼容旧调用：只清理本机宿主目录。 */
    public void cleanup(String hostDir) {
        cleanupHostDir(hostDir);
    }

    /**
     * 远程命名卷的属主修正：容器以非 root 运行时，卷根目录默认可能是 root:root 0755。
     * 启动后以 root 身份执行一次 chown/chmod（命令固定，不接受外部输入）。
     */
    public void ensureVolumeWritable(DockerNode node, String containerId) {
        if (!enabled()) {
            return;
        }
        String runAs = props.getRunAsUser() == null ? "" : props.getRunAsUser().trim();
        if (runAs.isEmpty()) {
            return;
        }
        String[] parts = runAs.split(":", 2);
        Integer uid = parseNumeric(parts[0]);
        if (uid == null || uid == 0) {
            return;
        }
        int gid = parts.length > 1 && parseNumeric(parts[1]) != null ? parseNumeric(parts[1]) : uid;
        String script = "chown -R " + uid + ":" + gid + " " + props.getFileRoot()
                + " && chmod 770 " + props.getFileRoot();
        var result = docker.execAsUser(node, containerId,
                List.of("sh", "-c", script), List.of(), 10, 4096, "0");
        if (result.exitCode() != 0) {
            throw GatewayException.docker("修正工作区卷属主失败: "
                    + (result.stderr() == null || result.stderr().isBlank()
                    ? "exit " + result.exitCode() : result.stderr().trim()));
        }
    }

    private void cleanupHostDir(String hostDir) {
        if (!enabled() || hostDir == null || hostDir.isBlank()) {
            return;
        }
        Path base = baseDir();
        Path path;
        try {
            path = Paths.get(hostDir).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.warn("工作区路径非法，跳过清理: {}", hostDir);
            return;
        }
        if (!base.equals(path.getParent())) {
            log.warn("跳过清理非托管工作区路径（不是 {} 的直接子目录）: {}", base, path);
            return;
        }
        try {
            if (!Files.exists(path)) {
                return;
            }
            deleteRecursively(path);
            log.info("已清理托管工作区 {}", path);
        } catch (IOException e) {
            log.warn("清理托管工作区 {} 失败: {}", path, e.getMessage());
        }
    }

    /** 按 {@code run-as-user} chown 目录并设权限；留空则只设权限并告警。 */
    private void applyOwnership(Path dir) throws IOException {
        String runAs = props.getRunAsUser() == null ? "" : props.getRunAsUser().trim();
        if (runAs.isEmpty()) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(PERMS));
            log.warn("工作区 {} 未 chown：GATEWAY_RUN_AS_USER 为空，容器默认用户可能无法写入", dir);
            return;
        }
        String[] parts = runAs.split(":", 2);
        Integer uid = parseNumeric(parts[0]);
        if (uid == null) {
            throw GatewayException.badRequest(
                    "GATEWAY_RUN_AS_USER 须为数字 uid[:gid] 才能用于工作区 chown: " + runAs);
        }
        Integer gid = parts.length > 1 ? parseNumeric(parts[1]) : uid;
        if (gid != null) {
            Files.setAttribute(dir, "unix:gid", gid);
        }
        Files.setAttribute(dir, "unix:uid", uid);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(PERMS));
    }

    private Path baseDir() {
        return Paths.get(props.getWorkspaceHostDir().trim()).toAbsolutePath().normalize();
    }

    private static Integer parseNumeric(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || !s.matches("\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : paths) {
            Files.deleteIfExists(p);
        }
    }
}
