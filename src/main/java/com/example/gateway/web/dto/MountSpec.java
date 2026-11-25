package com.example.gateway.web.dto;

/**
 * 客户端声明的 bind mount。
 *
 * <p>仅在 {@code GATEWAY_BIND_MOUNTS_ENABLED=true} 时生效；即便开启，宿主敏感路径
 * （docker.sock、/proc、/sys、/dev、/etc、/root、/var/lib/docker…）仍被强制拒绝。
 */
public record MountSpec(
        String hostPath,
        String containerPath,
        boolean readOnly) {
}
