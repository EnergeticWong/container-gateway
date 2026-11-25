package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 在容器内执行命令。
 *
 * @param cmd            命令与参数，如 List.of("sh", "-c", "ls -l")
 * @param timeoutSeconds 超时，默认 30，上限 600；同步 exec 超时返回 409 STATE_CONFLICT
 * @param env            额外环境变量，可空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecRequest(List<String> cmd, Integer timeoutSeconds, Map<String, String> env) {

    public static ExecRequest of(String... cmd) {
        return new ExecRequest(List.of(cmd), null, null);
    }

    public static ExecRequest of(List<String> cmd) {
        return new ExecRequest(cmd, null, null);
    }

    public ExecRequest withTimeout(int timeoutSeconds) {
        return new ExecRequest(cmd, timeoutSeconds, env);
    }
}
