# 示例

- Java 客户端 Demo：[`client/src/main/java/com/example/gateway/client/Demo.java`](../client/src/main/java/com/example/gateway/client/Demo.java)
- 客户端库源码：[`client/src/main/java/com/example/gateway/client/`](../client/src/main/java/com/example/gateway/client/)

构建客户端 all-in-one jar 后运行：

```bash
cd client
mvn -q clean package
GW_BASE_URL=http://gateway.example.com:3010 GW_TOKEN=<token> \
  java -jar target/container-gateway-client-1.0.0-all.jar
```

回归与压测脚本位于 [`tools/`](../tools/)，需在已部署网关的节点上运行，并从 `/etc/container-gateway.env` 读取 Token。
