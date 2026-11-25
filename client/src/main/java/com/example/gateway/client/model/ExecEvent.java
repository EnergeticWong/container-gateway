package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * SSE 流式 exec 的事件。
 *
 * <p>{@code type} 取值：
 * <ul>
 *   <li>{@code stdout} / {@code stderr} —— 输出分块，{@code data} 为文本；</li>
 *   <li>{@code truncated} —— 输出超过网关上限，已停止采集；</li>
 *   <li>{@code exit} —— 命令结束，携带 {@code exitCode}/{@code durationMs}/{@code truncated}；</li>
 *   <li>{@code error} —— 执行失败或超时，携带 {@code code}/{@code message}/{@code timedOut}。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecEvent(
        String type,
        String stream,
        String data,
        Integer exitCode,
        Long durationMs,
        Boolean truncated,
        Boolean timedOut,
        String code,
        String message) {

    public boolean isOutput() {
        return "stdout".equals(type) || "stderr".equals(type);
    }

    public boolean isTerminal() {
        return "exit".equals(type) || "error".equals(type);
    }

    public boolean isError() {
        return "error".equals(type);
    }

    public boolean isTruncated() {
        return Boolean.TRUE.equals(truncated) || "truncated".equals(type);
    }
}
