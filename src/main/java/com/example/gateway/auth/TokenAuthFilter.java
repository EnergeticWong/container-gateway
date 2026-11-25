package com.example.gateway.auth;

import com.example.gateway.audit.AuditLogger;
import com.example.gateway.config.GatewayProperties;
import com.example.gateway.error.GatewayException;
import com.example.gateway.web.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Bearer Token 鉴权 + 全局限流。鉴权通过后把 clientId / requestId 挂到 request 属性上。
 */
@Component
@Order(1)
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_CLIENT_ID = "gw.clientId";
    public static final String ATTR_REQUEST_ID = "gw.requestId";
    public static final String ATTR_START_NANOS = "gw.startNanos";

    private final TokenRegistry tokens;
    private final RateLimiter rateLimiter;
    private final GatewayProperties props;
    private final ObjectMapper objectMapper;
    private final AuditLogger audit;
    private final com.example.gateway.metrics.GatewayMetrics metrics;

    public TokenAuthFilter(TokenRegistry tokens, RateLimiter rateLimiter,
                           GatewayProperties props, ObjectMapper objectMapper, AuditLogger audit,
                           com.example.gateway.metrics.GatewayMetrics metrics) {
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        request.setAttribute(ATTR_START_NANOS, System.nanoTime());
        response.setHeader("X-Request-Id", requestId);

        if (isPublic(request.getRequestURI())) {
            if (isHealth(request.getRequestURI())) {
                RateLimiter.Result healthLimit = rateLimiter.tryAcquire(
                        "health:" + request.getRemoteAddr(), props.getHealthRateLimitPerMinute());
                if (!healthLimit.allowed()) {
                    metrics.rateLimited("health");
                    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(healthLimit.retryAfterSeconds()));
                    writeError(response, requestId, 429,
                            GatewayException.RATE_LIMITED,
                            "健康检查请求过于频繁，限制 " + props.getHealthRateLimitPerMinute() + " 次/分钟");
                    return;
                }
            }
            chain.doFilter(request, response);
            return;
        }

        String clientId = tokens.resolve(extractBearer(request));
        if (clientId == null) {
            audit.error(requestId, null, clientIp(request), "auth", null, null, null,
                    requestParams(request, HttpServletResponse.SC_UNAUTHORIZED),
                    GatewayException.UNAUTHORIZED, startNanos(request));
            writeError(response, requestId, HttpServletResponse.SC_UNAUTHORIZED,
                    GatewayException.UNAUTHORIZED, "缺少或无效的 Bearer Token");
            return;
        }
        request.setAttribute(ATTR_CLIENT_ID, clientId);

        RateLimiter.Result limit = rateLimiter.tryAcquire("all:" + clientId, props.getRateLimitPerMinute());
        if (!limit.allowed()) {
            audit.error(requestId, clientId, clientIp(request), "rate-limit", null, null, null,
                    requestParams(request, 429), GatewayException.RATE_LIMITED, startNanos(request));
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(limit.retryAfterSeconds()));
            writeError(response, requestId, 429,
                    GatewayException.RATE_LIMITED,
                    "请求过于频繁，限制 " + props.getRateLimitPerMinute() + " 次/分钟");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isPublic(String path) {
        return isHealth(path)
                || path.startsWith("/actuator/")
                || "/error".equals(path);
    }

    private boolean isHealth(String path) {
        return "/healthz".equals(path) || path.startsWith("/healthz/");
    }

    private static long startNanos(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR_START_NANOS);
        return value instanceof Long nanos ? nanos : System.nanoTime();
    }

    private static Map<String, Object> requestParams(HttpServletRequest request, int status) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("method", request.getMethod());
        params.put("path", request.getRequestURI());
        params.put("status", status);
        return params;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return header.substring(prefix.length()).trim();
        }
        return null;
    }

    private void writeError(HttpServletResponse response, String requestId, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(code, message, requestId));
    }
}
