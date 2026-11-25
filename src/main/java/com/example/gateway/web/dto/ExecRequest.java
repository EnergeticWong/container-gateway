package com.example.gateway.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 在容器内执行命令。
 */
public record ExecRequest(
        @NotEmpty(message = "cmd 不能为空")
        List<String> cmd,

        Integer timeoutSeconds,

        Map<String, String> env) {
}
