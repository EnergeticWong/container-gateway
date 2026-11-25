package com.example.gateway.service;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.DockerOps;
import com.example.gateway.docker.Ownership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkspaceManagerTest {

    private static GatewayProperties props(String hostDir, String runAsUser, String tmpfs) {
        GatewayProperties p = new GatewayProperties();
        p.setWorkspaceHostDir(hostDir);
        p.setRunAsUser(runAsUser);
        p.setTmpfs(tmpfs);
        p.setFileRoot("/workspace");
        return p;
    }

    private static DockerNode node(boolean local) {
        return new DockerNode("local", URI.create("unix:///var/run/docker.sock"),
                "127.0.0.1", local, null);
    }

    private static DockerNode remoteNode() {
        return new DockerNode("w1", URI.create("tcp://10.0.0.11:2376"),
                "10.0.0.11", false, null);
    }

    @Test
    void disabledWhenHostDirBlank() {
        WorkspaceManager m = new WorkspaceManager(props("  ", "1000:1000", ""), mock(DockerOps.class));
        assertFalse(m.enabled());
        assertNull(m.prepare(node(true), "client"));
        m.cleanup("/tmp/whatever"); // 未启用时是 no-op，不应抛
    }

    @Test
    void validateRejectsTmpfsOverFileRoot(@TempDir Path base) {
        WorkspaceManager m = new WorkspaceManager(
                props(base.toString(), "1000:1000", "/tmp:rw;/workspace:rw"), mock(DockerOps.class));
        IllegalStateException e = assertThrows(IllegalStateException.class, m::validate);
        assertTrue(e.getMessage().contains("/workspace"), e.getMessage());
    }

    @Test
    void remotePrepareCreatesVolumeAndCleanupRemovesIt() {
        DockerOps docker = mock(DockerOps.class);
        WorkspaceManager m = new WorkspaceManager(
                props("/var/lib/container-gateway/workspaces", "1000:1000", ""), docker);
        DockerNode remote = remoteNode();

        WorkspaceManager.Workspace ws = m.prepare(remote, "client-a");
        assertNotNull(ws);
        assertEquals(WorkspaceManager.Kind.VOLUME, ws.kind());
        assertTrue(ws.ref().startsWith(WorkspaceManager.VOLUME_PREFIX), ws.ref());

        verify(docker).createVolume(eq(remote), eq(ws.ref()), any(Map.class));

        m.cleanup(remote, ws);
        verify(docker).removeVolume(remote, ws.ref());
    }

    @Test
    void fromLabelsSupportsNewAndLegacyContainers() {
        WorkspaceManager.Workspace volume = WorkspaceManager.fromLabels(Map.of(
                Ownership.WORKSPACE_KIND, "volume",
                Ownership.WORKSPACE_REF, "gw-ws-abc"));
        assertNotNull(volume);
        assertEquals(WorkspaceManager.Kind.VOLUME, volume.kind());

        WorkspaceManager.Workspace legacy = WorkspaceManager.fromLabels(Map.of(
                Ownership.WORKSPACE_HOST_DIR, "/var/lib/container-gateway/workspaces/x"));
        assertNotNull(legacy);
        assertEquals(WorkspaceManager.Kind.BIND, legacy.kind());

        assertNull(WorkspaceManager.fromLabels(Map.of()));
    }

    @Test
    void prepareCreatesChownedDirAndCleanupRemovesIt(@TempDir Path base) throws IOException {
        assumeTrue("root".equals(System.getProperty("user.name")), "chown 需 root");
        WorkspaceManager m = new WorkspaceManager(
                props(base.toString(), "1000:1000", "/tmp:rw"), mock(DockerOps.class));
        m.validate();

        WorkspaceManager.Workspace ws = m.prepare(node(true), "client");
        Path dir = Paths.get(ws.ref());
        assertEquals(WorkspaceManager.Kind.BIND, ws.kind());
        assertTrue(Files.isDirectory(dir), "应创建目录 " + dir);
        assertEquals(base, dir.getParent(), "应是 base 的直接子目录");
        assertEquals(1000, ((Number) Files.getAttribute(dir, "unix:uid")).intValue());
        assertEquals(1000, ((Number) Files.getAttribute(dir, "unix:gid")).intValue());
        assertEquals("rwxrwx---", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));

        m.cleanup(node(true), ws);
        assertFalse(Files.exists(dir), "cleanup 后目录应被删除");
    }

    @Test
    void cleanupIgnoresPathOutsideBase(@TempDir Path base, @TempDir Path other) throws IOException {
        assumeTrue("root".equals(System.getProperty("user.name")), "chown 需 root");
        WorkspaceManager m = new WorkspaceManager(
                props(base.resolve("ws").toString(), "1000:1000", ""), mock(DockerOps.class));
        m.validate();
        Path foreign = other.resolve("keep");
        Files.createDirectory(foreign);

        m.cleanup(foreign.toString());
        assertTrue(Files.exists(foreign), "base 之外的路径不得被删除");

        m.cleanup(base.toString());
        assertTrue(Files.exists(base), "base 自身不得被删除");
    }
}
