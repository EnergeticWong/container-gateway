package com.example.gateway.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 容器视图（网关托管容器的对外表示）。
 *
 * @param nodeId 容器所在的 Docker 节点 id（多节点路由用）
 * @param meta   创建时携带的业务上下文（tenantId/userId/sessionId/runId 等），按原样回显
 */
public record ContainerInfo(
        String id,
        String name,
        String status,
        String image,
        String createdAt,
        String expiresAt,
        Long ttlSeconds,
        Integer exitCode,
        String nodeId,
        List<Endpoint> endpoints,
        Map<String, String> meta) {
}
