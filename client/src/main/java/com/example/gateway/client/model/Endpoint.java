package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 容器端口的对外访问地址。
 *
 * @param directUrl http://&lt;宿主地址&gt;:&lt;hostPort&gt;/ ，调用方网络能直连宿主时可用
 * @param proxyUrl  经网关反向代理的地址，需带上 Authorization: Bearer &lt;token&gt;
 * @param nodeId    该端口所在 Docker 节点 id
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Endpoint(
        int containerPort,
        String protocol,
        int hostPort,
        String directUrl,
        String proxyUrl,
        String nodeId) {
}
