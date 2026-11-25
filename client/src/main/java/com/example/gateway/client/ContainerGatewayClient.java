package com.example.gateway.client;

import com.example.gateway.client.model.ContainerInfo;
import com.example.gateway.client.model.CreateContainerRequest;
import com.example.gateway.client.model.ExecEvent;
import com.example.gateway.client.model.ExecRequest;
import com.example.gateway.client.model.ExecResult;
import com.example.gateway.client.model.StatusView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * container-gateway 的 Java 客户端。
 *
 * <p>只依赖 JDK 21 的 {@link HttpClient} 与 Jackson 2，可直接放入任意 Java 工程（内网或公网均可）。
 *
 * <pre>{@code
 * try (var gw = new ContainerGatewayClient("http://gateway.example.com:18090", token)) {
 *     ContainerInfo c = gw.create(CreateContainerRequest.builder("nginx:1.27-alpine")
 *             .port(80).ttlSeconds(600).memoryMb(256).build());
 *     ExecResult r = gw.exec(c.id(), ExecRequest.of("sh", "-c", "echo hi"));
 *     gw.destroy(c.id());
 * }
 * }</pre>
 */
public final class ContainerGatewayClient implements AutoCloseable {

    private static final String BASE_PATH = "/api/v1/containers";

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /**
     * @param baseUrl 网关地址，如 http://gateway.example.com:18090（不要带结尾斜杠）
     * @param token   Bearer Token
     */
    public ContainerGatewayClient(String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.mapper = new ObjectMapper();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ------------------------------------------------------------------ 生命周期

    /** 创建并启动容器，返回包含访问地址的容器信息。 */
    public ContainerInfo create(CreateContainerRequest request) {
        return sendJson("POST", BASE_PATH, request, new TypeReference<>() {
        }, 20, 60);
    }

    /** 列出当前 token 归属的所有容器（含已停止）。 */
    public List<ContainerInfo> list() {
        return sendJson("GET", BASE_PATH, null, new TypeReference<>() {
        }, 10, 30);
    }

    /** 查询单个容器。 */
    public ContainerInfo get(String id) {
        return sendJson("GET", BASE_PATH + "/" + enc(id), null, new TypeReference<>() {
        }, 10, 30);
    }

    public StatusView start(String id) {
        return sendJson("POST", BASE_PATH + "/" + enc(id) + "/start", null, new TypeReference<>() {
        }, 10, 90);
    }

    public StatusView stop(String id) {
        return stop(id, 10);
    }

    public StatusView stop(String id, int timeoutSeconds) {
        return sendJson("POST", BASE_PATH + "/" + enc(id) + "/stop?timeoutSeconds=" + timeoutSeconds,
                null, new TypeReference<>() {
                }, 10, timeoutSeconds + 30);
    }

    public StatusView restart(String id) {
        return restart(id, 10);
    }

    public StatusView restart(String id, int timeoutSeconds) {
        return sendJson("POST", BASE_PATH + "/" + enc(id) + "/restart?timeoutSeconds=" + timeoutSeconds,
                null, new TypeReference<>() {
                }, 10, timeoutSeconds + 30);
    }

    /** 删除：容器必须已停止；与 destroy 的区别是不会强制停止。 */
    public void remove(String id) {
        remove(id, true);
    }

    public void remove(String id, boolean removeVolumes) {
        send("DELETE", BASE_PATH + "/" + enc(id) + "?removeVolumes=" + removeVolumes,
                null, "application/json", 10, 60);
    }

    /** 销毁：强制停止 + 强制删除 + 删除卷 + 回收宿主端口。沙箱用完即毁用这个。 */
    public void destroy(String id) {
        send("POST", BASE_PATH + "/" + enc(id) + "/destroy", null, "application/json", 10, 90);
    }

    // ------------------------------------------------------------------ exec / logs

    public ExecResult exec(String id, ExecRequest request) {
        int timeout = request.timeoutSeconds() == null ? 35 : request.timeoutSeconds() + 10;
        return sendJson("POST", BASE_PATH + "/" + enc(id) + "/exec", request, new TypeReference<>() {
        }, 10, timeout);
    }

    /**
     * 流式 exec：把容器内命令的 stdout/stderr 逐块回调给 {@code onEvent}，最后回调 exit/error 事件。
     *
     * <p>同步阻塞直到流结束（或超时）。事件里的错误（如归属校验失败、执行超时）以 {@code error}
     * 事件形式给出，不抛异常；连接层失败才抛 {@link ContainerGatewayException}。
     *
     * <pre>{@code
     * gw.execStream(id, ExecRequest.of("sh", "-c", "for i in 1 2 3; do echo $i; sleep 1; done"),
     *     ev -> {
     *         if (ev.isOutput()) System.out.print(ev.data());
     *         else if (ev.isTerminal()) System.out.println("\n[" + ev.type() + "] exit=" + ev.exitCode());
     *     });
     * }</pre>
     */
    public void execStream(String id, ExecRequest request, Consumer<ExecEvent> onEvent) {
        if (request == null || request.cmd() == null || request.cmd().isEmpty()) {
            throw new IllegalArgumentException("cmd 不能为空");
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(request);
        } catch (IOException e) {
            throw new IllegalArgumentException("请求体序列化失败: " + e.getMessage(), e);
        }
        int timeout = request.timeoutSeconds() == null ? 60 : request.timeoutSeconds();
        HttpRequest httpRequest = baseRequest(BASE_PATH + "/" + enc(id) + "/exec/stream")
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json; charset=UTF-8")
                .timeout(Duration.ofSeconds(timeout + 60L))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new ContainerGatewayException(0, "CONNECTION_ERROR",
                    "无法连接网关 " + baseUrl + ": " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerGatewayException(0, "INTERRUPTED", "请求被中断", null);
        }

        try (Stream<String> lines = response.body()) {
            if (response.statusCode() >= 400) {
                throw toException(response.statusCode(), lines.collect(Collectors.joining("\n")));
            }
            String event = null;
            StringBuilder data = null;
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line.isEmpty()) {
                    if (event != null) {
                        onEvent.accept(decodeEvent(event, data == null ? "" : data.toString()));
                    }
                    event = null;
                    data = null;
                    continue;
                }
                if (line.charAt(0) == ':') {
                    continue; // 心跳注释帧
                }
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    String value = line.substring("data:".length());
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (data == null) {
                        data = new StringBuilder();
                    } else {
                        data.append('\n');
                    }
                    data.append(value);
                }
            }
            if (event != null) {
                onEvent.accept(decodeEvent(event, data == null ? "" : data.toString()));
            }
        }
    }

    private ExecEvent decodeEvent(String event, String data) {
        try {
            return mapper.readValue(data, ExecEvent.class);
        } catch (IOException e) {
            throw new ContainerGatewayException(0, "DECODE_ERROR",
                    "解析流事件失败: " + e.getMessage() + "，原始: " + abbreviate(data), null);
        }
    }

    /** 读取容器日志（stdout+stderr 合并）。 */
    public String logs(String id, int tail) {
        return logs(id, tail, false);
    }

    public String logs(String id, int tail, boolean timestamps) {
        return send("GET", BASE_PATH + "/" + enc(id) + "/logs?tail=" + tail
                + "&timestamps=" + timestamps, null, "text/plain", 10, 60);
    }

    /** 健康检查（免鉴权）。 */
    public Map<String, Object> health() {
        String body = send("GET", "/healthz", null, "application/json", 5, 10);
        try {
            return mapper.readValue(body, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new ContainerGatewayException(0, "DECODE_ERROR", "解析响应失败: " + e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------ 容器访问

    /**
     * 容器内 HTTP 服务的访问地址（经网关反向代理，与 API 同一入口）。
     * 注意：用该地址发请求时仍然要带 Authorization: Bearer &lt;token&gt;。
     */
    public String proxyUrl(String id, int containerPort) {
        return baseUrl + BASE_PATH + "/" + enc(id) + "/proxy/" + containerPort + "/";
    }

    /** 经网关代理调用容器内的 HTTP GET，便于程序内直接取容器服务数据。 */
    public byte[] proxyGet(String id, int containerPort, String path) {
        String suffix = (path == null || path.isBlank()) ? "" : path.startsWith("/") ? path : "/" + path;
        HttpRequest request = baseRequest(BASE_PATH + "/" + enc(id) + "/proxy/" + containerPort + suffix)
                .GET()
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<byte[]> response = exchange(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new ContainerGatewayException(response.statusCode(), "PROXY_ERROR",
                    "代理请求返回 " + response.statusCode(), null);
        }
        return response.body();
    }

    // ------------------------------------------------------------------ 内部实现

    private <T> T sendJson(String method, String path, Object body, TypeReference<T> type,
                           int connectTimeoutSec, int requestTimeoutSec) {
        String responseBody = send(method, path, body, "application/json", connectTimeoutSec, requestTimeoutSec);
        try {
            return mapper.readValue(responseBody, type);
        } catch (IOException e) {
            throw new ContainerGatewayException(0, "DECODE_ERROR",
                    "解析响应失败: " + e.getMessage() + "，原始响应: " + abbreviate(responseBody), null);
        }
    }

    private String send(String method, String path, Object body, String accept,
                        int connectTimeoutSec, int requestTimeoutSec) {
        HttpRequest.Builder builder = baseRequest(path)
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(requestTimeoutSec));
        if (body != null) {
            String payload;
            try {
                payload = mapper.writeValueAsString(body);
            } catch (IOException e) {
                throw new IllegalArgumentException("请求体序列化失败: " + e.getMessage(), e);
            }
            builder.header("Content-Type", "application/json; charset=UTF-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = exchange(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 400) {
            throw toException(status, response.body());
        }
        return response.body();
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token);
    }

    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new ContainerGatewayException(0, "CONNECTION_ERROR",
                    "无法连接网关 " + baseUrl + ": " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerGatewayException(0, "INTERRUPTED", "请求被中断", null);
        }
    }

    /** 把网关的统一错误体翻译成异常。 */
    private ContainerGatewayException toException(int status, String body) {
        try {
            Map<String, Object> parsed = mapper.readValue(body, new TypeReference<>() {
            });
            Object error = parsed.get("error");
            if (error instanceof Map<?, ?> map) {
                String code = String.valueOf(map.get("code"));
                String message = String.valueOf(map.get("message"));
                String requestId = map.get("requestId") == null ? null : String.valueOf(map.get("requestId"));
                return new ContainerGatewayException(status, code, message, requestId);
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        return new ContainerGatewayException(status, "HTTP_" + status,
                "网关返回 " + status + ": " + abbreviate(body), null);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static String enc(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    /** HttpClient 无需显式关闭，这里保持 AutoCloseable 以便 try-with-resources 写法。 */
    @Override
    public void close() {
        // JDK HttpClient 在 Java 21 无 close()，连接由 GC 回收
    }
}
