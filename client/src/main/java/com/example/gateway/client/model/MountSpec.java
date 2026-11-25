package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * bind mount 声明。仅当网关开启 {@code GATEWAY_BIND_MOUNTS_ENABLED=true} 时有效；
 * 即便开启，宿主敏感路径（docker.sock、/proc、/sys、/dev、/etc、/root…）仍会被网关拒绝。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MountSpec(String hostPath, String containerPath, boolean readOnly) {

    public static MountSpec rw(String hostPath, String containerPath) {
        return new MountSpec(hostPath, containerPath, false);
    }

    public static MountSpec ro(String hostPath, String containerPath) {
        return new MountSpec(hostPath, containerPath, true);
    }
}
