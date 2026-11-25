package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 命令执行结果。输出超过网关上限（默认 64KiB）会被截断并置 truncated=true。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        long durationMs) {
}
