package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 容器视图。{@code endpoints} 里的 {proxyUrl} 是与 API 同一个入口的访问地址，
 * 适合仅暴露单一 HTTP 入口的部署；{@code directUrl} 需要调用方网络能路由到宿主地址。
 *
 * <p>标注 {@code ignoreUnknown=true}：网关后续新增字段时旧客户端不会解析失败。
 *
 * @param nodeId 容器所在的 Docker 节点 id（多节点部署时用于定位）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        List<Endpoint> endpoints) {

    public Endpoint endpoint(int containerPort) {
        if (endpoints == null) {
            return null;
        }
        return endpoints.stream()
                .filter(e -> e.containerPort() == containerPort)
                .findFirst()
                .orElse(null);
    }
}
