package com.example.gateway.web.dto;

/**
 * 容器端口对外访问地址。
 *
 * @param containerPort 容器内端口
 * @param protocol      tcp/udp
 * @param hostPort      宿主机映射端口（从端口池 30000-30999 分配）
 * @param directUrl     直连地址，如 http://192.0.2.43:30001/（调用方网络能路由到宿主地址时可用）
 * @param proxyUrl      经网关反向代理的地址，如 http://&lt;CLB&gt;:3010/api/v1/containers/{id}/proxy/8080/
 * @param nodeId        该端口所在 Docker 节点 id
 */
public record Endpoint(
        int containerPort,
        String protocol,
        int hostPort,
        String directUrl,
        String proxyUrl,
        String nodeId) {
}
