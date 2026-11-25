package com.example.gateway.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 单个容器端口映射声明。宿主端口由网关从端口池分配，客户端不能指定。
 */
public record PortSpec(
        @Min(1) @Max(65535) int containerPort,
        String protocol) {

    public String protocolOrDefault() {
        return (protocol == null || protocol.isBlank()) ? "tcp" : protocol.toLowerCase();
    }

    /** 仅支持 tcp/udp，其余拒绝。 */
    public void validate() {
        String p = protocolOrDefault();
        if (!p.equals("tcp") && !p.equals("udp")) {
            throw new IllegalArgumentException("不支持的协议: " + protocol);
        }
    }
}
