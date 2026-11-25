package com.example.gateway.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 创建容器请求。
 *
 * <p>刻意不接受 privileged / networkMode / binds / volumesFrom / pid 等字段：
 * HostConfig 完全由服务端构造，客户端无法影响隔离性。
 */
public record CreateContainerRequest(
        @NotBlank(message = "image 不能为空")
        @Size(max = 255, message = "image 过长")
        String image,

        List<String> cmd,

        Map<String, String> env,

        @Valid
        List<PortSpec> ports,

        @Valid
        List<MountSpec> mounts,

        Long memoryBytes,

        Double cpus,

        Long pidsLimit,

        Long ttlSeconds,

        Map<String, String> meta,

        @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}",
                message = "name 只能包含字母数字与 _.-，且不超过 63 字符")
        String name) {

    public List<PortSpec> portsOrEmpty() {
        return ports == null ? List.of() : ports;
    }

    public Map<String, String> metaOrEmpty() {
        return meta == null ? Map.of() : meta;
    }
}
