package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 容器端口声明。宿主端口由网关从端口池分配，客户端不能指定。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortSpec(int containerPort, String protocol) {

    public static PortSpec tcp(int containerPort) {
        return new PortSpec(containerPort, "tcp");
    }

    public static PortSpec udp(int containerPort) {
        return new PortSpec(containerPort, "udp");
    }
}
