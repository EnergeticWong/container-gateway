package com.example.gateway.docker;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerOpsTmpfsTest {

    @Test
    void oldCommaSeparatedFormatStillWorks() {
        Map<String, String> m = DockerOps.parseTmpfs("/tmp:rw,/data:ro");
        assertEquals("rw", m.get("/tmp"));
        assertEquals("ro", m.get("/data"));
    }

    @Test
    void semicolonFormatKeepsCommaOptions() {
        Map<String, String> m = DockerOps.parseTmpfs(
                "/tmp:rw,size=32m,uid=1000,gid=1000,mode=1777;/workspace:rw,size=128m,uid=1000");
        assertEquals("rw,size=32m,uid=1000,gid=1000,mode=1777", m.get("/tmp"));
        assertEquals("rw,size=128m,uid=1000", m.get("/workspace"));
    }

    @Test
    void singleEntryWithOptionsViaSemicolonFreeIsNotSplit() {
        // 单条目无 ';'：仍走旧逻辑，选项内不含逗号时正常
        Map<String, String> m = DockerOps.parseTmpfs("/run:rw");
        assertEquals("rw", m.get("/run"));
    }

    @Test
    void singleEntryWithCommaOptionsNoSemicolonIsOneEntry() {
        // 单个条目、选项含逗号、无 ';'：不得被误拆成多个挂载点
        Map<String, String> m = DockerOps.parseTmpfs("/tmp:rw,size=32m,uid=1000,gid=1000,mode=1777");
        assertEquals(1, m.size(), m.toString());
        assertEquals("rw,size=32m,uid=1000,gid=1000,mode=1777", m.get("/tmp"));
    }

    @Test
    void blankYieldsEmpty() {
        assertTrue(DockerOps.parseTmpfs(null).isEmpty());
        assertTrue(DockerOps.parseTmpfs("  ").isEmpty());
    }
}
