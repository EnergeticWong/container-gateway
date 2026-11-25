# cgw-exporter

`container-gateway` 的 Prometheus exporter。1 个 exporter 对应 1 个网关实例，同机部署，Prometheus 主动抓取。

## 工作方式

```
Prometheus --GET /metrics--> cgw-exporter:18092
                                 |  GET http://127.0.0.1:18091/internal/exporter/snapshot
                                 v
                          container-gateway (只读内存快照，不触发 Docker 调用)
```

- 每次抓取只向网关卡取一次快照，所有指标来自同一份数据。
- 上游不可达时：只输出自监控指标 + `cgw_gateway_up 0`，不输出旧值。
- 不解析 `audit.log`，不直连 Docker，不在 exporter 内实现服务发现。

## 构建

```bash
mvn -q package
# 产物：target/cgw-exporter-1.0.0.jar（含依赖，单文件）
```

需要 JDK 21。运行期无其他依赖。

## 运行

```bash
java -jar cgw-exporter-1.0.0.jar --config.file=/etc/cgw-exporter/config.yml
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--config.file=路径` | YAML 配置，缺省 `config.yml` |
| `--listen=host:port` | 覆盖监听地址（默认 `0.0.0.0:18092`） |
| `--check` | 抓一次并打印到 stdout；上游不可达退出码 1，用于部署前验证 |

端点：

| 路径 | 说明 |
|---|---|
| `/metrics` | Prometheus 文本格式 |
| `/healthz` | 最近一次抓取的上游状态（200/503），不做主动探测 |
| `/` | 落地页（版本、上游地址、示例查询） |

## 配置

见 `config/config.yml`：

```yaml
listen: "0.0.0.0:18092"
upstream:
  url: "http://127.0.0.1:18091/internal/exporter/snapshot"
  timeout_ms: 8000
  schema: "cgw.exporter.snapshot/v1"
labels:
  environment: "prod"
```

抓取预算：若请求带 `X-Prometheus-Scrape-Timeout-Seconds`，上游超时取 `min(该值-500ms, timeout_ms)`，最小 200ms。

## 部署

```bash
useradd --system --no-create-home --shell /usr/sbin/nologin cgw
install -m 0755 target/cgw-exporter-1.0.0.jar /usr/local/bin/cgw-exporter
install -d -m 0755 /etc/cgw-exporter
install -m 0644 config/config.yml /etc/cgw-exporter/config.yml
install -m 0644 config/cgw-exporter.service /etc/systemd/system/cgw-exporter.service
systemctl daemon-reload && systemctl enable --now cgw-exporter
```

网络：

- exporter `18092` 只对 Prometheus 网段开放（安全组/防火墙），不要挂 CLB、不要暴露公网。
- 网关快照端点 `18091` 只绑 `127.0.0.1`（网关侧 `GATEWAY_EXPORTER_SNAPSHOT_PORT`，0 表示关闭）。

验证：

```bash
java -jar /usr/local/bin/cgw-exporter --config.file=/etc/cgw-exporter/config.yml --check | head
curl -s http://127.0.0.1:18092/metrics | grep cgw_gateway_up
```

## 指标

### 请求面

| 指标 | 类型 | 标签 |
|---|---|---|
| `cgw_http_requests_total` | counter | action, result, node |
| `cgw_http_request_duration_seconds` | histogram | action, node |
| `cgw_http_request_errors_total` | counter | action, error_code |
| `cgw_auth_failures_total` | counter | reason |
| `cgw_rate_limit_rejections_total` | counter | scope |
| `cgw_quota_rejections_total` | counter | reason |

### Docker / 节点 / 容量

| 指标 | 类型 | 标签 |
|---|---|---|
| `cgw_docker_calls_total` | counter | op, node, result, reason |
| `cgw_docker_call_duration_seconds` | histogram | op, node |
| `cgw_node_up` | gauge | node |
| `cgw_node_probe_duration_seconds` | histogram | node |
| `cgw_node_probe_failures_total` | counter | node, reason |
| `cgw_node_assigned` / `cgw_node_inflight` / `cgw_node_capacity` | gauge | node |
| `cgw_node_docker_running` | gauge | node |
| `cgw_node_active_streams` / `cgw_node_stream_capacity` | gauge | node |
| `cgw_containers_managed` | gauge | — |
| `cgw_port_pool_used` / `cgw_port_pool_capacity` | gauge | node |
| `cgw_port_reclaimed_total` | counter | node |

### exec / 代理 / 文件

| 指标 | 类型 | 标签 |
|---|---|---|
| `cgw_exec_total` | counter | mode, node, result |
| `cgw_exec_duration_seconds` | histogram | mode, node |
| `cgw_exec_timeouts_total` | counter | mode, node |
| `cgw_exec_output_truncated_total` | counter | mode, node |
| `cgw_exec_nonzero_exits_total` | counter | mode, node |
| `cgw_proxy_requests_total` | counter | method, status_class |
| `cgw_proxy_duration_seconds` | histogram | — |
| `cgw_proxy_upstream_errors_total` | counter | reason |
| `cgw_file_transfers_total` | counter | direction, result |
| `cgw_file_transfer_bytes_total` | counter | direction |
| `cgw_file_rejections_total` | counter | reason |

### 任务 / 健康 / 自监控

| 指标 | 类型 | 标签 |
|---|---|---|
| `cgw_job_runs_total` | counter | task, result |
| `cgw_job_duration_seconds` | histogram | task |
| `cgw_job_last_success_timestamp_seconds` | gauge | task |
| `cgw_ready` / `cgw_index_containers` / `cgw_gateway_up` | gauge | — |
| `cgw_audit_write_failures_total` / `cgw_managed_refresh_failures_total` | counter | — |
| `cgw_build_info` | info | version |
| `cgw_exporter_scrapes_total` | counter | result |
| `cgw_exporter_scrape_duration_seconds` | histogram | — |
| `cgw_exporter_snapshot_age_seconds` | gauge | — |
| `cgw_exporter_build_info` | info | version |

## 常用查询

```promql
sum by (action) (rate(cgw_http_requests_total[5m]))
histogram_quantile(0.99, sum by (le,action) (rate(cgw_http_request_duration_seconds_bucket[5m])))
cgw_port_pool_used / cgw_port_pool_capacity
cgw_node_assigned / cgw_node_capacity
rate(cgw_exec_timeouts_total[5m])
```

## 边界

- 不导出宿主 CPU/内存/磁盘/网络（node_exporter）、JVM（JMX Exporter）、容器级资源（默认关闭）。
- 不导出容器 ID、客户端标识、IP、命令与 env。
- 网关重启后累计计数器归零，Prometheus 按 reset 处理；短窗口 `increase()` 可能偏低。
- 网关进程退出时 local/w1 的业务指标一并消失，由 `cgw_gateway_up` 兜底；w1 宿主与 dockerd 指标建议在 w1 本地开启 `dockerd --metrics-addr` 与 node_exporter。
- 上游 schema 变更会使 exporter 拒绝启动（启动探测为 DOWN），需同步升级。

## 测试

```bash
mvn test
```

覆盖：全 family 渲染、上游故障降级、并发抓取、HTTP 端点。
