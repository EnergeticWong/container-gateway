package com.example.gateway.web;

import com.example.gateway.auth.TokenAuthFilter;
import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.StreamingExec;
import com.example.gateway.error.GatewayException;
import com.example.gateway.service.ContainerService;
import com.example.gateway.service.FileTransferService;
import com.example.gateway.web.dto.ContainerInfo;
import com.example.gateway.web.dto.CreateContainerRequest;
import com.example.gateway.web.dto.ExecEvent;
import com.example.gateway.web.dto.ExecRequest;
import com.example.gateway.web.dto.ExecResult;
import com.example.gateway.web.dto.FileEntry;
import com.example.gateway.web.dto.FileUploadResult;
import com.example.gateway.web.dto.StatusView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 容器生命周期 REST 接口 + 容器访问反向代理 + SSE 流式 exec。
 */
@RestController
@RequestMapping("/api/v1/containers")
public class ContainerController {

    private static final Logger log = LoggerFactory.getLogger(ContainerController.class);

    /** 代理转发时不能原样透传的逐跳头/由 HttpClient 自行决定的头。 */
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "accept-encoding",
            "upgrade", "keep-alive", "proxy-connection", "te", "trailer");

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "connection", "content-length", "transfer-encoding", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade");

    private final ContainerService service;
    private final FileTransferService fileService;
    private final StreamingExec streamingExec;
    private final GatewayProperties props;
    private final ObjectMapper objectMapper;
    private final com.example.gateway.metrics.GatewayMetrics metrics;
    private final HttpClient proxyClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ContainerController(ContainerService service, FileTransferService fileService,
                               StreamingExec streamingExec,
                               GatewayProperties props, ObjectMapper objectMapper,
                               com.example.gateway.metrics.GatewayMetrics metrics) {
        this.service = service;
        this.fileService = fileService;
        this.streamingExec = streamingExec;
        this.props = props;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ 生命周期

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContainerInfo create(@Valid @RequestBody CreateContainerRequest body, HttpServletRequest http) {
        Caller caller = caller(http);
        String idempotencyKey = http.getHeader("Idempotency-Key");
        return service.create(caller.clientId(), caller.requestId(), caller.sourceIp(), body,
                AdvertiseContext.from(http, props), idempotencyKey);
    }

    @GetMapping
    public List<ContainerInfo> list(HttpServletRequest http) {
        Caller caller = caller(http);
        return service.list(caller.clientId(), caller.requestId(), caller.sourceIp(),
                AdvertiseContext.from(http, props));
    }

    @GetMapping("/{id}")
    public ContainerInfo get(@PathVariable String id, HttpServletRequest http) {
        Caller caller = caller(http);
        return service.get(caller.clientId(), caller.requestId(), caller.sourceIp(), id,
                AdvertiseContext.from(http, props));
    }

    @PostMapping("/{id}/start")
    public StatusView start(@PathVariable String id, HttpServletRequest http) {
        Caller caller = caller(http);
        return service.start(caller.clientId(), caller.requestId(), caller.sourceIp(), id);
    }

    @PostMapping("/{id}/stop")
    public StatusView stop(@PathVariable String id,
                           @RequestParam(defaultValue = "10") int timeoutSeconds,
                           HttpServletRequest http) {
        Caller caller = caller(http);
        return service.stop(caller.clientId(), caller.requestId(), caller.sourceIp(), id, timeoutSeconds);
    }

    @PostMapping("/{id}/restart")
    public StatusView restart(@PathVariable String id,
                              @RequestParam(defaultValue = "10") int timeoutSeconds,
                              HttpServletRequest http) {
        Caller caller = caller(http);
        return service.restart(caller.clientId(), caller.requestId(), caller.sourceIp(), id, timeoutSeconds);
    }

    /** 删除：软删，要求已停止。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id,
                       @RequestParam(defaultValue = "true") boolean removeVolumes,
                       HttpServletRequest http) {
        Caller caller = caller(http);
        service.remove(caller.clientId(), caller.requestId(), caller.sourceIp(), id, removeVolumes);
    }

    /** 销毁：强停 + 强制删除 + 删卷 + 回收端口；容器已被 AutoRemove 删除时幂等成功。 */
    @PostMapping("/{id}/destroy")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void destroy(@PathVariable String id, HttpServletRequest http) {
        Caller caller = caller(http);
        service.destroy(caller.clientId(), caller.requestId(), caller.sourceIp(), id);
    }

    // ------------------------------------------------------------------ exec / logs

    @PostMapping("/{id}/exec")
    public ExecResult exec(@PathVariable String id, @Valid @RequestBody ExecRequest body,
                           HttpServletRequest http) {
        Caller caller = caller(http);
        return service.exec(caller.clientId(), caller.requestId(), caller.sourceIp(), id, body);
    }

    /**
     * 流式 exec（SSE）。前置校验失败也以流内 {@code error} 事件返回，保证流式客户端拿到确定结果。
     */
    @PostMapping(value = "/{id}/exec/stream")
    public SseEmitter execStream(@PathVariable String id,
                                 @RequestBody(required = false) ExecRequest body,
                                 HttpServletRequest http) {
        Caller caller = caller(http);
        long emitterTimeoutMs = (props.getExecMaxTimeoutSeconds() + 30L) * 1000L;
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        try {
            if (body == null || body.cmd() == null || body.cmd().isEmpty()) {
                throw GatewayException.badRequest("cmd 不能为空");
            }
            com.example.gateway.docker.ContainerGuard.Owned owned = service.requireForExec(caller.clientId(), id);
            int timeout = service.validateExecTimeout(body.timeoutSeconds());
            streamingExec.stream(emitter, owned.node(), id, body.cmd(), toEnvList(body.env()), timeout,
                    caller.requestId(), caller.clientId(), caller.sourceIp());
        } catch (GatewayException e) {
            emitError(emitter, e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("创建流式 exec 失败: {}", e.getMessage());
            emitError(emitter, GatewayException.DOCKER_ERROR, e.getClass().getSimpleName());
        }
        return emitter;
    }

    private void emitError(SseEmitter emitter, String code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(objectMapper.writeValueAsString(ExecEvent.error(code, message, false, 0L))));
            emitter.complete();
        } catch (IOException | RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    @GetMapping(value = "/{id}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable String id,
                       @RequestParam(defaultValue = "200") int tail,
                       @RequestParam(defaultValue = "false") boolean timestamps,
                       HttpServletRequest http) {
        Caller caller = caller(http);
        int safeTail = Math.max(1, Math.min(tail, 5000));
        return service.logs(caller.clientId(), caller.requestId(), caller.sourceIp(), id,
                safeTail, timestamps);
    }

    // ------------------------------------------------------------------ 文件收发（阶段 B）

    /**
     * 上传 tar 归档到容器上传目录（默认 {@code file-upload-dir}）。
     * 请求体即 tar 字节；服务端校验条目后经 Docker archive 接口解压进容器。
     *
     * <p>{@code dest} 从原始查询串读取而非 {@code @RequestParam}：当 Content-Type 为表单类型时，
     * 容器解析请求参数会消耗掉请求体，导致 tar 变空。这里避免触发参数解析，保持流可读。
     */
    @PostMapping("/{id}/files/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public FileUploadResult uploadFiles(@PathVariable String id,
                                        HttpServletRequest http) throws IOException {
        Caller caller = caller(http);
        String dest = rawQueryParam(http, "dest");
        return fileService.upload(caller.clientId(), caller.requestId(), caller.sourceIp(),
                id, dest, http.getInputStream());
    }

    /** 读取容器产物路径的 tar 归档（默认只允许 {@code file-output-dir} 下）。 */
    @GetMapping(value = "/{id}/files/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> downloadFiles(@PathVariable String id,
                                                @RequestParam String path,
                                                HttpServletRequest http) {
        Caller caller = caller(http);
        byte[] tar = fileService.download(caller.clientId(), caller.requestId(), caller.sourceIp(),
                id, path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(tar);
    }

    /** 列举容器产物目录下的条目。 */
    @GetMapping("/{id}/files")
    public List<FileEntry> listFiles(@PathVariable String id,
                                     @RequestParam String path,
                                     HttpServletRequest http) {
        Caller caller = caller(http);
        return fileService.list(caller.clientId(), caller.requestId(), caller.sourceIp(), id, path);
    }

    // ------------------------------------------------------------------ 容器访问代理

    /**
     * 把请求反向代理到容器映射的宿主端口（按容器所在节点解析目标地址）。
     */
    @RequestMapping(value = {"/{id}/proxy/{port}", "/{id}/proxy/{port}/**"})
    public ResponseEntity<byte[]> proxy(@PathVariable String id, @PathVariable int port,
                                        HttpServletRequest request) throws IOException {
        long proxyStart = System.nanoTime();
        String clientId = (String) request.getAttribute(TokenAuthFilter.ATTR_CLIENT_ID);
        ContainerService.ProxyTarget target = service.resolveProxyTarget(clientId, id, port);

        String suffix = extractSuffix(request, id, port);
        String query = request.getQueryString();
        String path = "http://" + target.node().proxyHost() + ":" + target.hostPort()
                + "/" + suffix + (query == null ? "" : "?" + query);

        String method = request.getMethod().toUpperCase(Locale.ROOT);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(path))
                .timeout(Duration.ofSeconds(props.getProxyTimeoutSeconds()));

        byte[] body = isBodyless(method) ? new byte[0] : readBounded(request.getInputStream());
        builder.method(method, body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (!SKIP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                request.getHeaders(name).asIterator().forEachRemaining(value -> {
                    try {
                        builder.header(name, value);
                    } catch (IllegalArgumentException ignored) {
                        // 受限头（如 Host），跳过
                    }
                });
            }
        });

        try {
            HttpResponse<byte[]> response = proxyClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            metrics.proxy(method, statusClass(response.statusCode()), elapsedMs(proxyStart));
            HttpHeaders headers = new HttpHeaders();
            response.headers().map().forEach((name, values) -> {
                if (!SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    headers.put(name, values);
                }
            });
            return new ResponseEntity<>(response.body(), headers,
                    HttpStatus.valueOf(response.statusCode()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.proxy(method, "error", elapsedMs(proxyStart));
            metrics.proxyUpstreamError("interrupted");
            throw GatewayException.docker("代理请求被中断");
        } catch (IOException e) {
            metrics.proxy(method, "error", elapsedMs(proxyStart));
            metrics.proxyUpstreamError(reasonOf(e));
            log.warn("代理到容器 {}:{}（节点 {}）失败: {}", id, port, target.node().id(), e.getMessage());
            throw GatewayException.nodeUnavailable(
                    "无法连接容器服务 " + port + "（节点 " + target.node().id() + "）: " + e.getMessage(), e);
        }
    }

    private static String statusClass(int status) {
        if (status >= 100 && status < 600) {
            return (status / 100) + "xx";
        }
        return "other";
    }

    private static String reasonOf(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e instanceof java.net.http.HttpTimeoutException || msg.contains("timed out")
                || msg.contains("timeout")) {
            return "timeout";
        }
        return "connect";
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean isBodyless(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    /**
     * 从原始查询串取参数，绕过 {@code getParameter} 触发的请求体解析
     * （表单请求体的解析会消耗 InputStream，使上传的 tar 变空）。
     */
    private static String rawQueryParam(HttpServletRequest request, String name) {
        String qs = request.getQueryString();
        if (qs == null || qs.isEmpty()) {
            return null;
        }
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (name.equals(urlDecode(pair.substring(0, eq)))) {
                return urlDecode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] readBounded(InputStream in) throws IOException {
        int limit = props.getProxyMaxBodyBytes();
        byte[] data = in.readNBytes(limit + 1);
        if (data.length > limit) {
            throw GatewayException.badRequest("请求体超过 " + limit + " 字节上限");
        }
        return data;
    }

    private static String extractSuffix(HttpServletRequest request, String id, int port) {
        String prefix = request.getContextPath() + "/api/v1/containers/" + id + "/proxy/" + port;
        String uri = request.getRequestURI();
        String suffix = uri.length() > prefix.length() ? uri.substring(prefix.length()) : "";
        return suffix.startsWith("/") ? suffix.substring(1) : suffix;
    }

    // ------------------------------------------------------------------ 调用方信息

    private static List<String> toEnvList(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(env.size());
        env.forEach((k, v) -> result.add(k + "=" + (v == null ? "" : v)));
        return result;
    }

    private record Caller(String clientId, String requestId, String sourceIp) {
    }

    private static Caller caller(HttpServletRequest request) {
        String clientId = (String) request.getAttribute(TokenAuthFilter.ATTR_CLIENT_ID);
        String requestId = (String) request.getAttribute(TokenAuthFilter.ATTR_REQUEST_ID);
        return new Caller(clientId,
                requestId == null ? UUID.randomUUID().toString() : requestId,
                clientIp(request));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
