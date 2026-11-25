package com.example.gateway.client;

import com.example.gateway.client.model.ContainerInfo;
import com.example.gateway.client.model.CreateContainerRequest;
import com.example.gateway.client.model.ExecRequest;
import com.example.gateway.client.model.ExecResult;

import java.nio.charset.StandardCharsets;

/**
 * 端到端示例：创建 -> 查状态 -> exec -> 经代理访问容器 HTTP -> 销毁。
 *
 * <pre>
 * java -jar container-gateway-client-1.0.0-all.jar http://127.0.0.1:3010 &lt;token&gt;
 * 或
 * GW_BASE_URL=http://gateway.example.com:3010 GW_TOKEN=xxx java -jar container-gateway-client-1.0.0-all.jar
 * </pre>
 */
public final class Demo {

    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : System.getenv("GW_BASE_URL");
        String token = args.length > 1 ? args[1] : System.getenv("GW_TOKEN");

        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            System.err.println("用法: java -jar container-gateway-client-all.jar <baseUrl> <token>");
            System.err.println("  或: GW_BASE_URL=... GW_TOKEN=... java -jar ...");
            System.exit(2);
        }

        try (ContainerGatewayClient gw = new ContainerGatewayClient(baseUrl, token)) {
            System.out.println("== 1. 健康检查 ==");
            System.out.println(gw.health());

            System.out.println("\n== 2. 创建容器 ==");
            ContainerInfo container = gw.create(CreateContainerRequest.builder("nginx:1.27-alpine")
                    .port(80)
                    .memoryMb(128)
                    .cpus(0.5)
                    .ttlSeconds(600)
                    .name("demo-" + System.currentTimeMillis() % 100000)
                    .build());
            System.out.println("id       = " + container.id());
            System.out.println("name     = " + container.name());
            System.out.println("status   = " + container.status());
            System.out.println("expiresAt= " + container.expiresAt());
            container.endpoints().forEach(e -> System.out.println(
                    "endpoint = 容器端口 " + e.containerPort() + " -> 宿主 " + e.hostPort()
                            + "\n           代理 " + e.proxyUrl()
                            + "\n           直连 " + e.directUrl()));

            System.out.println("\n== 3. exec ==");
            ExecResult result = gw.exec(container.id(),
                    ExecRequest.of("sh", "-c", "echo hello-from-sandbox; nginx -v 2>&1; id -u"));
            System.out.println("exitCode = " + result.exitCode());
            System.out.println("stdout   = " + result.stdout().trim());
            System.out.println("stderr   = " + result.stderr().trim());

            System.out.println("\n== 3b. 流式 exec（SSE，逐块输出）==");
            gw.execStream(container.id(),
                    ExecRequest.of("sh", "-c",
                            "for i in 1 2 3; do echo line-$i; sleep 1; done; echo 'to-stderr' 1>&2"),
                    ev -> {
                        if (ev.isOutput()) {
                            System.out.print("[" + ev.stream() + "] " + ev.data());
                        } else if (ev.isTerminal()) {
                            System.out.println("[" + ev.type() + "] exitCode=" + ev.exitCode()
                                    + (ev.message() == null ? "" : " message=" + ev.message()));
                        }
                    });

            System.out.println("\n== 4. 经网关代理访问容器 HTTP 服务 ==");
            byte[] html = gw.proxyGet(container.id(), 80, "/");
            String text = new String(html, StandardCharsets.UTF_8);
            System.out.println("HTTP 200, 响应前 80 字符 = "
                    + text.substring(0, Math.min(80, text.length())).replaceAll("\\s+", " "));

            System.out.println("\n== 5. 容器日志（tail=3）==");
            System.out.println(gw.logs(container.id(), 3).trim());

            System.out.println("\n== 6. 列表 ==");
            gw.list().forEach(c -> System.out.println("  " + c.name() + "  " + c.status()));

            System.out.println("\n== 7. 销毁 ==");
            gw.destroy(container.id());
            System.out.println("已销毁 " + container.id());

            System.out.println("\n完成。");
        } catch (ContainerGatewayException e) {
            System.err.println("网关返回错误: " + e);
            System.exit(1);
        }
    }

    private Demo() {
    }
}
