package com.example.gateway.audit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.gateway.metrics.GatewayMetrics;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 审计日志：JSON Lines 落盘，记录每一次容器操作的调用方与结果。
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    /** 写失败时不影响业务，只降级为普通日志。 */
    private final ObjectMapper objectMapper;
    private final GatewayMetrics metrics;
    private final Path logPath;
    private volatile boolean fileWritable = false;

    public AuditLogger(ObjectMapper objectMapper, com.example.gateway.config.GatewayProperties props,
                       GatewayMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.logPath = Path.of(props.getAuditLog());
    }

    @PostConstruct
    void init() {
        try {
            Path parent = logPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(logPath)) {
                Files.createFile(logPath);
            }
            fileWritable = Files.isWritable(logPath);
        } catch (Exception e) {
            fileWritable = false;
        }
        if (fileWritable) {
            log.info("审计日志: {}", logPath.toAbsolutePath());
        } else {
            log.warn("审计日志不可写（{}），审计记录将只输出到应用日志", logPath.toAbsolutePath());
        }
    }

    public record Event(
            String requestId,
            String clientId,
            String sourceIp,
            String action,
            String containerId,
            String image,
            List<Integer> hostPorts,
            Object params,
            String result,
            String errorCode,
            Long durationMs) {
    }

    /** 记录成功事件，durationMs 由调用方提供的起始纳秒计算。 */
    public void ok(String requestId, String clientId, String sourceIp, String action,
                   String containerId, String image, List<Integer> hostPorts,
                   Map<String, Object> params, long startNanos) {
        record(new Event(requestId, clientId, sourceIp, action, containerId, image, hostPorts,
                params, "ok", null, elapsedMs(startNanos)));
    }

    /** 记录失败事件；errorCode 为空时统一记为 INTERNAL。 */
    public void error(String requestId, String clientId, String sourceIp, String action,
                      String containerId, String image, List<Integer> hostPorts,
                      Map<String, Object> params, String errorCode, long startNanos) {
        record(new Event(requestId, clientId, sourceIp, action, containerId, image, hostPorts,
                params, "error", errorCode == null ? "INTERNAL" : errorCode, elapsedMs(startNanos)));
    }

    public static long startNanos() {
        return System.nanoTime();
    }

    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public void record(Event event) {
        metrics.recordAudit(event.action(), event.result(), event.errorCode(),
                event.durationMs() == null ? 0L : event.durationMs(), nodeOf(event.params()));
        String line;
        try {
            line = objectMapper.writeValueAsString(new Envelope(Instant.now().toString(), event));
        } catch (Exception e) {
            log.warn("审计事件序列化失败: {}", e.getMessage());
            return;
        }
        if (fileWritable) {
            try {
                Files.writeString(logPath, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            } catch (Exception e) {
                fileWritable = false;
                metrics.auditWriteFailure();
                log.warn("写审计日志失败，后续只输出到应用日志: {}", e.getMessage());
            }
        }
        log.info("AUDIT {}", line);
    }

    /** 审计参数里带 node 的动作（创建/启停/销毁等）按节点归因。 */
    private static String nodeOf(Object params) {
        if (params instanceof Map<?, ?> map) {
            Object node = map.get("node");
            return node == null ? null : node.toString();
        }
        return null;
    }

    private record Envelope(String ts, Event event) {
    }
}
