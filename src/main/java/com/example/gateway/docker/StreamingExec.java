package com.example.gateway.docker;

import com.example.gateway.audit.AuditLogger;
import com.example.gateway.config.GatewayProperties;
import com.example.gateway.error.GatewayException;
import com.example.gateway.web.dto.ExecEvent;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 流式 exec：把容器内命令的 stdout/stderr 逐块推给客户端，最后推 exit/error 事件。
 *
 * <p>线程模型：docker 回调线程只做「有界队列入队」；一个虚拟线程负责发送（SseEmitter 不可并发送）
 * 与心跳。看门狗超时、字节上限、客户端断线都会关闭 docker 回调，避免连接泄漏。
 */
@Component
public class StreamingExec {

    private static final Logger log = LoggerFactory.getLogger(StreamingExec.class);
    private static final int QUEUE_CAPACITY = 4096;

    private final DockerOps docker;
    private final GatewayProperties props;
    private final AuditLogger audit;
    private final ObjectMapper mapper;
    private final com.example.gateway.metrics.GatewayMetrics metrics;

    public StreamingExec(DockerOps docker, GatewayProperties props, AuditLogger audit, ObjectMapper mapper,
                         com.example.gateway.metrics.GatewayMetrics metrics) {
        this.docker = docker;
        this.props = props;
        this.audit = audit;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    private static final class Ctx {
        final DockerNode node;
        final SseEmitter emitter;
        final String containerId;
        final String execId;
        final List<String> cmd;
        final List<String> env;
        final int timeoutSeconds;
        final long deadlineMs;
        final long startNanos = System.nanoTime();
        final BlockingQueue<ExecEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        final AtomicBoolean truncated = new AtomicBoolean(false);
        final AtomicBoolean done = new AtomicBoolean(false);
        /** 我们主动关闭回调（截断/超时），此时 onError 不应报成执行失败。 */
        final AtomicBoolean intentionalStop = new AtomicBoolean(false);
        final AtomicBoolean released = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicLong bytes = new AtomicLong(0);
        volatile ResultCallback<Frame> callback;
        volatile int exitCode = Integer.MIN_VALUE;
        volatile String errorCode;

        Ctx(DockerNode node, SseEmitter emitter, String containerId, String execId,
            List<String> cmd, List<String> env, int timeoutSeconds) {
            this.node = node;
            this.emitter = emitter;
            this.containerId = containerId;
            this.execId = execId;
            this.cmd = cmd;
            this.env = env;
            this.timeoutSeconds = timeoutSeconds;
            this.deadlineMs = System.currentTimeMillis() + timeoutSeconds * 1000L;
        }

        long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
    }

    /**
     * 开始一次流式 exec。方法本身只做前置校验并启动发送线程，立即返回。
     *
     * @throws GatewayException 节点流并发超限
     */
    public void stream(SseEmitter emitter, DockerNode node, String containerId, List<String> cmd,
                       List<String> env, int timeoutSeconds, String requestId, String clientId, String sourceIp) {
        int active = node.activeStreams().incrementAndGet();
        if (active > props.getMaxStreamsPerNode()) {
            node.activeStreams().decrementAndGet();
            throw GatewayException.quotaExceeded(
                    "节点 " + node.id() + " 流式 exec 并发已达上限 " + props.getMaxStreamsPerNode());
        }

        String execId;
        try {
            execId = docker.createExec(node, containerId, cmd, env);
        } catch (RuntimeException e) {
            node.activeStreams().decrementAndGet();
            throw e;
        }

        Ctx ctx = new Ctx(node, emitter, containerId, execId, cmd, env, timeoutSeconds);
        int maxBytes = props.getExecMaxOutputBytes();

        ExecStartResultCallback callback = new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                if (ctx.closed.get() || ctx.truncated.get()) {
                    return;
                }
                String stream = streamName(frame.getStreamType());
                if (stream == null) {
                    return;
                }
                byte[] payload = frame.getPayload();
                int len = payload == null ? 0 : payload.length;
                if (ctx.bytes.addAndGet(len) > maxBytes) {
                    ctx.truncated.set(true);
                    ctx.intentionalStop.set(true);
                    offer(ctx, ExecEvent.truncatedEvent());
                    closeQuietly(this);
                    return;
                }
                String data = new String(payload == null ? new byte[0] : payload, StandardCharsets.UTF_8);
                if (!ctx.queue.offer(ExecEvent.output(stream, data))) {
                    ctx.truncated.set(true);
                    ctx.intentionalStop.set(true);
                    offerForce(ctx, ExecEvent.truncatedEvent());
                    closeQuietly(this);
                }
            }

            @Override
            public void onComplete() {
                ctx.done.set(true);
                ctx.exitCode = docker.inspectExecExitCode(node, execId);
                offerForce(ctx, ExecEvent.exit(ctx.exitCode, ctx.elapsedMs(), ctx.truncated.get()));
            }

            @Override
            public void onError(Throwable throwable) {
                ctx.done.set(true);
                if (ctx.intentionalStop.get()) {
                    // 截断等主动关闭：不报执行失败，补一个 exit 收尾（退出码未知记 -1）
                    offerForce(ctx, ExecEvent.exit(-1, ctx.elapsedMs(), true));
                    return;
                }
                ctx.errorCode = "EXEC_ERROR";
                offerForce(ctx, ExecEvent.error("EXEC_ERROR", safeMessage(throwable), false, ctx.elapsedMs()));
            }
        };
        ctx.callback = callback;

        emitter.onCompletion(() -> releaseOnce(ctx, requestId, clientId, sourceIp));
        emitter.onTimeout(() -> releaseOnce(ctx, requestId, clientId, sourceIp));
        emitter.onError(e -> releaseOnce(ctx, requestId, clientId, sourceIp));

        try {
            docker.streamExec(node, execId, callback);
        } catch (RuntimeException e) {
            closeQuietly(callback);
            node.activeStreams().decrementAndGet();
            throw e;
        }

        Thread.ofVirtual()
                .name("sse-exec-" + shortId(containerId))
                .start(() -> sendLoop(ctx, timeoutSeconds, requestId, clientId, sourceIp));
    }

    private void sendLoop(Ctx ctx, int timeoutSeconds, String requestId, String clientId, String sourceIp) {
        SseEmitter emitter = ctx.emitter;
        long heartbeatMs = props.getStreamHeartbeatMs();
        long lastHeartbeatAt = System.currentTimeMillis();
        try {
            while (!ctx.closed.get()) {
                long now = System.currentTimeMillis();
                if (now > ctx.deadlineMs) {
                    ctx.errorCode = "EXEC_TIMEOUT";
                    ctx.intentionalStop.set(true);
                    send(ctx, ExecEvent.error("EXEC_TIMEOUT",
                            "命令执行超时（" + timeoutSeconds + "s）", true, ctx.elapsedMs()));
                    closeQuietly(ctx.callback);
                    break;
                }
                // 等待时间不超过「心跳间隔」与「剩余超时时间」的较小者，保证超时能准时触发
                long wait = Math.max(1L, Math.min(heartbeatMs, ctx.deadlineMs - now));
                ExecEvent event = ctx.queue.poll(wait, TimeUnit.MILLISECONDS);
                if (event == null) {
                    if (ctx.done.get() && ctx.queue.isEmpty()) {
                        break;
                    }
                    long since = System.currentTimeMillis() - lastHeartbeatAt;
                    if (since >= heartbeatMs) {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                        lastHeartbeatAt = System.currentTimeMillis();
                    }
                    continue;
                }
                send(ctx, event);
                lastHeartbeatAt = System.currentTimeMillis();
                if ("exit".equals(event.type()) || "error".equals(event.type())) {
                    break;
                }
            }
            if (!ctx.closed.get()) {
                emitter.complete();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("SSE 客户端已断开: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("SSE 发送异常: {}", e.getMessage());
        } finally {
            releaseOnce(ctx, requestId, clientId, sourceIp);
        }
    }

    private void send(Ctx ctx, ExecEvent event) throws IOException {
        String json = mapper.writeValueAsString(event);
        ctx.emitter.send(SseEmitter.event().name(event.type()).data(json));
    }

    /** 确保回调关闭、并发额度释放、审计只记一次。 */
    private void releaseOnce(Ctx ctx, String requestId, String clientId, String sourceIp) {
        if (!ctx.released.compareAndSet(false, true)) {
            return;
        }
        ctx.closed.set(true);
        closeQuietly(ctx.callback);
        ctx.node.activeStreams().updateAndGet(v -> v > 0 ? v - 1 : 0);
        String result = ctx.errorCode == null ? "ok" : "error";
        Long exitCode = ctx.exitCode == Integer.MIN_VALUE ? null : (long) ctx.exitCode;
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("stream", true);
        params.put("cmd", ctx.cmd);
        params.put("envKeys", envKeys(ctx.env));
        params.put("timeoutSeconds", ctx.timeoutSeconds);
        params.put("node", ctx.node.id());
        params.put("truncated", ctx.truncated.get());
        if (exitCode != null) {
            params.put("exitCode", exitCode);
        }
        metrics.exec("stream", ctx.node.id(), result, "EXEC_TIMEOUT".equals(ctx.errorCode),
                ctx.truncated.get(), exitCode == null ? null : exitCode.intValue(), ctx.elapsedMs());
        audit.record(new AuditLogger.Event(requestId, clientId, sourceIp, "exec",
                ctx.containerId, null, null, params, result, ctx.errorCode, ctx.elapsedMs()));
    }

    private static void offer(Ctx ctx, ExecEvent event) {
        ctx.queue.offer(event);
    }

    private static void offerForce(Ctx ctx, ExecEvent event) {
        if (!ctx.queue.offer(event)) {
            ctx.queue.poll();
            ctx.queue.offer(event);
        }
    }

    /** 只记录 env 的键名，避免把密钥值写进审计日志。 */
    private static List<String> envKeys(List<String> env) {
        if (env == null || env.isEmpty()) {
            return List.of();
        }
        List<String> keys = new java.util.ArrayList<>(env.size());
        for (String item : env) {
            int eq = item.indexOf('=');
            keys.add(eq < 0 ? item : item.substring(0, eq));
        }
        return keys;
    }

    private static String streamName(StreamType type) {
        if (type == StreamType.STDERR) {
            return "stderr";
        }
        if (type == StreamType.STDOUT || type == StreamType.RAW) {
            return "stdout";
        }
        return null;
    }

    private static void closeQuietly(ResultCallback<?> callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.close();
        } catch (Exception ignored) {
            // 关闭失败不影响主流程
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : String.valueOf(id);
    }
}
