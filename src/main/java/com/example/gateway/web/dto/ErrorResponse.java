package com.example.gateway.web.dto;

/**
 * 统一错误体：{"type":"error","error":{"code","message","requestId"}}
 */
public record ErrorResponse(String type, Body error) {

    public record Body(String code, String message, String requestId) {
    }

    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse("error", new Body(code, message, requestId));
    }
}
