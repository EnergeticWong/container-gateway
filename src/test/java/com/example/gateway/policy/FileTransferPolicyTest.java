package com.example.gateway.policy;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.error.GatewayException;
import com.example.gateway.web.dto.FileEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTransferPolicyTest {

    private FileTransferPolicy policy() {
        GatewayProperties props = new GatewayProperties();
        props.setFileRoot("/workspace");
        props.setFileUploadDir("/workspace/input");
        props.setFileOutputDir("/workspace/out");
        props.setFileMaxBytes(1024L);
        props.setFileMaxTotalBytes(4096L);
        props.setFileMaxCount(4);
        return new FileTransferPolicy(props);
    }

    private static String codeOf(Executable executable) {
        GatewayException e = assertThrows(GatewayException.class, executable::run);
        return e.getCode();
    }

    private interface Executable {
        void run();
    }

    @Test
    void normalizeRejectsRelativeAndTraversal() {
        assertEquals(GatewayException.VALIDATION_ERROR, codeOf(() -> FileTransferPolicy.normalize("workspace/a", "p")));
        assertEquals(GatewayException.VALIDATION_ERROR, codeOf(() -> FileTransferPolicy.normalize("/workspace/../etc", "p")));
        assertEquals(GatewayException.VALIDATION_ERROR, codeOf(() -> FileTransferPolicy.normalize("/workspace/a\\b", "p")));
        assertEquals(GatewayException.VALIDATION_ERROR, codeOf(() -> FileTransferPolicy.normalize("/", "p")));
    }

    @Test
    void normalizeCollapsesSlashes() {
        assertEquals("/workspace/out/a.txt", FileTransferPolicy.normalize("/workspace//out/./a.txt", "p"));
    }

    @Test
    void uploadDirMustStayUnderUploadDir() {
        FileTransferPolicy p = policy();
        assertEquals("/workspace/input", p.requireUploadDir(null));
        assertEquals("/workspace/input/sub", p.requireUploadDir("/workspace/input/sub"));
        assertEquals(GatewayException.VALIDATION_ERROR,
                codeOf(() -> p.requireUploadDir("/workspace/out")));
        assertEquals(GatewayException.VALIDATION_ERROR,
                codeOf(() -> p.requireUploadDir("/etc")));
    }

    @Test
    void outputPathMustStayUnderOutputDir() {
        FileTransferPolicy p = policy();
        assertEquals("/workspace/out/report.txt", p.requireOutputPath("/workspace/out/report.txt"));
        assertEquals(GatewayException.VALIDATION_ERROR,
                codeOf(() -> p.requireOutputPath("/workspace/input/x")));
        assertEquals(GatewayException.VALIDATION_ERROR,
                codeOf(() -> p.requireOutputPath("/workspace/out/../secret")));
    }

    @Test
    void disabledTransferThrowsNotFound() {
        GatewayProperties props = new GatewayProperties();
        props.setFileTransferEnabled(false);
        FileTransferPolicy p = new FileTransferPolicy(props);
        assertEquals(GatewayException.NOT_FOUND, codeOf(() -> p.requireUploadDir(null)));
    }

    @Test
    void readBoundedRejectsOversize() {
        FileTransferPolicy p = policy();
        byte[] big = new byte[5000];
        assertEquals(GatewayException.PAYLOAD_TOO_LARGE,
                codeOf(() -> {
                    try {
                        p.readBounded(new ByteArrayInputStream(big), 4096L, "body");
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
    }

    @Test
    void inspectTarAcceptsRegularFilesAndDirs() {
        FileTransferPolicy p = policy();
        byte[] tar = tarOf(new Entry("dir/", null), new Entry("dir/a.txt", "hello"));
        List<FileEntry> entries = p.inspectTar(tar, "tar");
        assertEquals(2, entries.size());
        assertEquals("dir", entries.get(0).type());
        assertEquals("file", entries.get(1).type());
        assertEquals(5L, entries.get(1).size());
    }

    @Test
    void inspectTarRejectsTraversalEntry() {
        FileTransferPolicy p = policy();
        byte[] tar = tarOf(new Entry("../evil", "x"));
        assertEquals(GatewayException.VALIDATION_ERROR, codeOf(() -> p.inspectTar(tar, "tar")));
    }

    @Test
    void inspectTarRejectsSymlinkEntry() {
        FileTransferPolicy p = policy();
        byte[] tar = symlinkTar("link", "/etc/passwd");
        assertEquals(GatewayException.VALIDATION_ERROR, codeOf(() -> p.inspectTar(tar, "tar")));
    }

    @Test
    void inspectTarRejectsOversizeFile() {
        FileTransferPolicy p = policy();
        byte[] tar = tarOf(new Entry("big.bin", "x".repeat(2048)));
        assertEquals(GatewayException.PAYLOAD_TOO_LARGE, codeOf(() -> p.inspectTar(tar, "tar")));
    }

    @Test
    void inspectTarRejectsTooManyEntries() {
        FileTransferPolicy p = policy();
        byte[] tar = tarOf(new Entry("a", "1"), new Entry("b", "1"),
                new Entry("c", "1"), new Entry("d", "1"), new Entry("e", "1"));
        assertEquals(GatewayException.VALIDATION_ERROR, codeOf(() -> p.inspectTar(tar, "tar")));
    }

    @Test
    void stripLeadingComponentDropsRoot() {
        List<FileEntry> in = List.of(new FileEntry("out/", 0, "dir"), new FileEntry("out/a.txt", 3, "file"));
        List<FileEntry> out = FileTransferPolicy.stripLeadingComponent(in);
        assertEquals(1, out.size());
        assertEquals("a.txt", out.get(0).name());
    }

    // ------------------------------------------------------------------ helpers

    private record Entry(String name, String content) {
    }

    private static byte[] tarOf(Entry... entries) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            for (Entry e : entries) {
                boolean dir = e.content() == null;
                TarArchiveEntry e2 = new TarArchiveEntry(e.name(), dir ? TarConstants.LF_DIR : TarConstants.LF_NORMAL);
                if (!dir) {
                    byte[] data = e.content().getBytes(StandardCharsets.UTF_8);
                    e2.setSize(data.length);
                    tos.putArchiveEntry(e2);
                    tos.write(data);
                } else {
                    tos.putArchiveEntry(e2);
                }
                tos.closeArchiveEntry();
            }
            tos.finish();
            return bos.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static byte[] symlinkTar(String name, String target) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
            TarArchiveEntry link = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
            link.setLinkName(target);
            link.setSize(0);
            tos.putArchiveEntry(link);
            tos.closeArchiveEntry();
            tos.finish();
            return bos.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void tarHelpersAreSane() {
        assertTrue(tarOf(new Entry("a", "x")).length > 0);
    }
}
