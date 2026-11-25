package com.example.gateway.web.dto;

/**
 * SSE 流式 exec 的事件负载。用于冻结线格式（服务端预序列化，客户端按此解析）。
 *
 * <p>事件名（SSE event 字段）与 {@code type} 一致：
 * <ul>
 *   <li>{@code stdout} / {@code stderr} —— 输出分块，{@code data} 为文本；</li>
 *   <li>{@code truncated} —— 输出超过上限，已停止采集；</li>
 *   <li>{@code exit} —— 命令结束，携带 {@code exitCode}/{@code durationMs}/{@code truncated}；</li>
 *   <li>{@code error} —— 执行失败或超时，携带 {@code code}/{@code message}。</li>
 * </ul>
 */
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

    public static ExecEvent output(String stream, String data) {
        return new ExecEvent(stream, stream, data, null, null, null, null, null, null);
    }

    public static ExecEvent truncatedEvent() {
        return new ExecEvent("truncated", null, null, null, null, true, null, null, null);
    }

    public static ExecEvent exit(int exitCode, long durationMs, boolean truncated) {
        return new ExecEvent("exit", null, null, exitCode, durationMs, truncated, false, null, null);
    }

    public static ExecEvent error(String code, String message, boolean timedOut, long durationMs) {
        return new ExecEvent("error", null, null, null, durationMs, null, timedOut, code, message);
    }
}
