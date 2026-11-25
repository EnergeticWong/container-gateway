package com.example.gateway.service;

import com.example.gateway.web.dto.CreateContainerRequest;
import com.example.gateway.web.dto.PortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CreateRequestDigestTest {

    private static final long MEM = 268435456L;
    private static final double CPU = 1.0d;
    private static final long PIDS = 256L;
    private static final long TTL = 300L;

    private static String digest(CreateContainerRequest req) {
        return CreateRequestDigest.compute(req, MEM, CPU, PIDS, TTL);
    }

    private static CreateContainerRequest req(String image, List<String> cmd, Map<String, String> env,
                                              List<PortSpec> ports, String name) {
        return new CreateContainerRequest(image, cmd, env, ports, null, MEM, CPU, PIDS, TTL, Map.of(), name);
    }

    @Test
    void envAndPortOrderDoNotChangeDigest() {
        CreateContainerRequest a = req("alpine:3.20",
                List.of("sh", "-c", "echo hi"),
                Map.of("A", "1", "B", "2"),
                List.of(new PortSpec(8080, "tcp"), new PortSpec(9000, "tcp")),
                null);
        // 同内容，env/ports 顺序不同
        CreateContainerRequest b = req("alpine:3.20",
                List.of("sh", "-c", "echo hi"),
                new java.util.LinkedHashMap<>(Map.of("B", "2", "A", "1")),
                List.of(new PortSpec(9000, "tcp"), new PortSpec(8080, "tcp")),
                null);
        assertEquals(digest(a), digest(b));
    }

    @Test
    void cmdOrderIsSignificant() {
        CreateContainerRequest a = req("alpine:3.20", List.of("sh", "-c", "echo hi"), Map.of(), List.of(), null);
        CreateContainerRequest b = req("alpine:3.20", List.of("echo", "hi", "-c"), Map.of(), List.of(), null);
        assertNotEquals(digest(a), digest(b));
    }

    @Test
    void differentImageDifferentDigest() {
        assertNotEquals(
                digest(req("alpine:3.20", List.of("true"), Map.of(), List.of(), null)),
                digest(req("debian:12-slim", List.of("true"), Map.of(), List.of(), null)));
    }

    @Test
    void blankNameIsStableAndDiffersFromExplicitName() {
        CreateContainerRequest nullName = req("alpine:3.20", List.of("true"), Map.of(), List.of(), null);
        CreateContainerRequest blankName = req("alpine:3.20", List.of("true"), Map.of(), List.of(), "  ");
        CreateContainerRequest explicit = req("alpine:3.20", List.of("true"), Map.of(), List.of(), "my-name");
        // 空/空白 name 归一为同一哨兵，且不受服务端生成名影响
        assertEquals(digest(nullName), digest(blankName));
        assertNotEquals(digest(nullName), digest(explicit));
    }

    @Test
    void onlyEffectiveScalarsMatter() {
        // 原始请求省略资源字段，但 effective 值相同 → 摘要相同（默认值等价）
        CreateContainerRequest omitted = new CreateContainerRequest(
                "alpine:3.20", List.of("true"), Map.of(), List.of(), null, null, null, null, null, null, null);
        CreateContainerRequest explicit = new CreateContainerRequest(
                "alpine:3.20", List.of("true"), Map.of(), List.of(), null, MEM, CPU, PIDS, TTL, Map.of(), null);
        assertEquals(CreateRequestDigest.compute(omitted, MEM, CPU, PIDS, TTL),
                CreateRequestDigest.compute(explicit, MEM, CPU, PIDS, TTL));
    }
}
