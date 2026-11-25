package com.example.gateway.web.dto;

/**
 * 执行结果。stdout/stderr 超过上限会被截断并置 truncated=true。
 */
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        long durationMs) {
}
