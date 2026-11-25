package com.example.gateway.web;

import com.example.gateway.auth.TokenAuthFilter;
import com.example.gateway.docker.DockerOps;
import com.example.gateway.error.GatewayException;
import com.example.gateway.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一错误体：{"type":"error","error":{"code","message","requestId"}}
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException e, HttpServletRequest request) {
        if (e.getStatus().is5xxServerError()) {
            log.error("[{}] {} {}", requestId(request), e.getCode(), e.getMessage(), e);
        } else {
            log.info("[{}] {} {}", requestId(request), e.getCode(), e.getMessage());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus());
        if (e.getRetryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()));
        }
        return builder.body(ErrorResponse.of(e.getCode(), e.getMessage(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(message.isEmpty() ? "请求参数非法" : message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException e,
                                                          HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest(message.isEmpty() ? "请求参数非法" : message, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformed(Exception e, HttpServletRequest request) {
        return badRequest("请求体或参数格式错误: " + e.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(GatewayException.NOT_FOUND, "接口不存在", requestId(request)));
    }

    /**
     * 例如对 /logs（produces=text/plain）发了 Accept: application/json。
     * 返回 406 而不是落到 500，便于客户端定位问题。
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException e,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .body(ErrorResponse.of(GatewayException.NOT_ACCEPTABLE,
                        "该接口不支持请求的 Accept 类型，请检查 Accept 头", requestId(request)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(GatewayException.METHOD_NOT_ALLOWED,
                        "不支持的请求方法: " + e.getMethod(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        if (DockerOps.isReadTimeout(e)) {
            log.warn("[{}] Docker 后端响应超时: {}", requestId(request), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of(GatewayException.NODE_UNAVAILABLE,
                            "节点响应超时，请稍后重试", requestId(request)));
        }
        if (DockerOps.isTransportFailure(e)) {
            log.warn("[{}] Docker 节点不可达: {}", requestId(request), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of(GatewayException.NODE_UNAVAILABLE,
                            "节点暂时不可用，请稍后重试", requestId(request)));
        }
        log.error("[{}] 未预期错误", requestId(request), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(GatewayException.DOCKER_ERROR,
                        "网关内部错误: " + e.getClass().getSimpleName(), requestId(request)));
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(GatewayException.VALIDATION_ERROR, message, requestId(request)));
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(TokenAuthFilter.ATTR_REQUEST_ID);
        return id == null ? UUID.randomUUID().toString() : id.toString();
    }
}
