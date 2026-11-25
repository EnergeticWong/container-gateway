# cgw-exporter 开发计划与指标规划

> 目标：为 `container-gateway`（单实例、systemd、端口 3010、双 Docker 节点 local + w1）提供 Prometheus 指标。
> 形态：独立 sidecar 进程，1 个 cgw-exporter 对应 1 个网关实例，同机部署，Prometheus 主动抓取。
> 版本：v1（2026-11-12 定稿）

## 0. 结论与前置决策

| 决策 | 取值 | 理由 |
|---|---|---|
| 采集方式 | **pull（仅抓取时同步采集）** | 符合 Prometheus 官方 exporter 规范；无自定时器、无本地 TSDB |
| 数据来源 | 网关的只读快照 API（`127.0.0.1:18091`） | 请求耗时、exec 截断、配额拒绝等只在网关进程内；不解析 `audit.log`、不在 exporter 内复刻 Docker 逻辑 |
| 部署拓扑 | 1:1 sidecar，同机 | 服务发现交给 Prometheus `static_configs`，exporter 不实现发现 |
| 语言/运行时 | Java 21，无 Spring，JDK HttpServer + Prometheus Java Client 1.4.3 | 宿主已有 JDK 21 与 Maven；依赖已在本地仓库；不引入 Go 工具链 |
| 与 OMP 的关系 | 互不影响 | cgw-exporter 只读网关内存快照，不改变 OMP 上报路径 |

**范围边界**

- 在范围内：网关业务面（请求/容器/exec/代理/文件/后台任务）、local 与 w1 的**依赖健康与容量视角**。
- 不在范围内：w1 宿主资源（node_exporter 负责）、dockerd 原生指标（`dockerd --metrics-addr` 负责）、容器级 CPU/内存（默认关闭，P5 可选）、JVM 指标（JMX Exporter 负责）。
- 已知单点：网关进程退出时，w1 的指标一并消失；由 `cgw_gateway_up == 0` 告警兜底，并建议后续在 w1 开启 dockerd 原生 metrics。

## 1. 架构

```
Prometheus ──GET /metrics (15s)──▶ cgw-exporter:18092
                                      │ GET /internal/exporter/snapshot (只读, 内存快照)
                                      ▼
                               container-gateway:18091 (loopback)
                                      ├─ GatewayMetrics（计数器/直方图聚合）
                                      ├─ NodeRegistry / NodePortAllocator / ManagedCountCache
                                      └─ 快照构造，不触发任何 Docker 调用
```

- exporter 每次 `Collect()` 只发一次上游请求，所有指标来自同一份快照，避免同一次抓取内数据互相矛盾。
- 上游不可达时：只输出自监控指标 + `cgw_gateway_up 0`，**不输出旧值**（让领域指标自然 stale）。

## 2. 快照契约（v1）

`GET http://127.0.0.1:18091/internal/exporter/snapshot`

- 仅监听 loopback，不经 CLB、不走 `TokenAuthFilter`。
- 只读、无副作用、**禁止调用 Docker**（节点字段取内存值，托管数取 4s 缓存）。
- `schema` 不匹配时 exporter 拒绝启动。

```json
{
  "schema": "cgw.exporter.snapshot/v1",
  "generated_at_ms": 1762400000000,
  "gateway": {"version":"1.0.0","ready":true,"managed_containers":12,
              "max_containers":1000,"index_containers":12,
              "audit_write_failures":0,"managed_refresh_failures":0},
  "nodes": [{"id":"local","up":true,"assigned":5,"inflight":1,"capacity":30,
             "docker_running":9,"active_streams":2,"stream_capacity":200,"port_used":7,"port_capacity":1000,
             "probe_duration_seconds":0.012,"last_probe_age_seconds":0.9,"probe_reason":""}],
  "jobs": [{"name":"ttl_reaper","runs":100,"failures":0,
            "last_success_ms":1762399990000,"last_duration_seconds":0.035}],
  "counters": {
    "http_requests": [{"action":"create","result":"ok","node":"local","value":123}],
    "http_errors": [{"action":"create","error_code":"QUOTA_EXCEEDED","value":4}],
    "auth_failures": [{"reason":"invalid","value":2}],
    "rate_limit_rejections": [{"scope":"global","value":1}],
    "quota_rejections": [{"reason":"owner","value":3}],
    "docker_calls": [{"op":"create","node":"local","result":"error","reason":"timeout","value":1}],
    "exec": [{"mode":"stream","node":"local","result":"ok","value":5,
              "timeouts":0,"truncated":1,"nonzero":2}],
    "proxy_requests": [{"method":"GET","status_class":"2xx","value":900}],
    "proxy_errors": [{"reason":"connect","value":1}],
    "file": [{"direction":"upload","result":"ok","reason":"","value":7,"bytes":5242880}],
    "port_reclaimed": [{"node":"local","value":3}]
  },
  "histograms": {
    "http_request_duration_seconds": [
      {"action":"create","node":"local","bounds":[0.01,0.025,0.05,0.1,0.25,0.5,1,2.5,5,10,30],
       "bucket_counts":[1,2,5,9,0,0,0,0,0,0,0,0],"count":17,"sum":12.3}
    ]
  }
}
```

**单位约定**：耗时一律秒（`sum` 用秒），字节一律字节，计数器为累计值。
**桶约定**：`bounds` 为有限上界；`bucket_counts` 长度 = bounds+1，**非累计**（每桶观测数，最后一项为超过最大上界的溢出数）。

## 3. 指标规划

### 3.1 请求面

| 指标 | 类型 | 标签 | HELP |
|---|---|---|---|
| `cgw_http_requests_total` | counter | action, result, node | 网关 API 请求总数（按审计动作与结果），不含 proxy |
| `cgw_http_request_duration_seconds` | histogram | action, node | 网关 API 端到端耗时 |
| `cgw_http_request_errors_total` | counter | action, error_code | 失败请求数，按错误码分类 |
| `cgw_auth_failures_total` | counter | reason | 鉴权失败数（不含 token/client 信息） |
| `cgw_rate_limit_rejections_total` | counter | scope | 限流拒绝数 |
| `cgw_quota_rejections_total` | counter | reason | 配额/容量拒绝数 |

### 3.2 Docker 与节点

| 指标 | 类型 | 标签 |
|---|---|---|
| `cgw_docker_calls_total` | counter | op, node, result, reason |
| `cgw_docker_call_duration_seconds` | histogram | op, node |
| `cgw_node_up` | gauge | node |
| `cgw_node_probe_duration_seconds` | histogram | node |
| `cgw_node_assigned` / `cgw_node_inflight` / `cgw_node_capacity` | gauge | node |
| `cgw_node_docker_running` | gauge | node |
| `cgw_node_active_streams` / `cgw_node_stream_capacity` | gauge | node |
| `cgw_containers_managed` | gauge | — |
| `cgw_port_pool_used` / `cgw_port_pool_capacity` | gauge | node |
| `cgw_port_reclaimed_total` | counter | node |

### 3.3 exec / 代理 / 文件

| 指标 | 类型 | 标签 |
|---|---|---|
| `cgw_exec_total` | counter | mode, node, result |
| `cgw_exec_duration_seconds` | histogram | mode, node |
| `cgw_exec_timeouts_total` / `cgw_exec_output_truncated_total` / `cgw_exec_nonzero_exits_total` | counter | mode, node |
| `cgw_proxy_requests_total` | counter | method, status_class |
| `cgw_proxy_duration_seconds` | histogram | — |
| `cgw_proxy_upstream_errors_total` | counter | reason |
| `cgw_file_transfers_total` | counter | direction, result |
| `cgw_file_transfer_bytes_total` | counter | direction |

### 3.4 任务与健康

| 指标 | 类型 | 标签 |
|---|---|---|
| `cgw_job_runs_total` | counter | task, result |
| `cgw_job_duration_seconds` | histogram | task |
| `cgw_job_last_success_timestamp_seconds` | gauge | task |
| `cgw_ready` / `cgw_index_containers` | gauge | — |
| `cgw_audit_write_failures_total` / `cgw_managed_refresh_failures_total` | counter | — |

### 3.5 Exporter 自监控

`cgw_exporter_build_info`{version}、`cgw_exporter_scrapes_total`{result}、
`cgw_exporter_scrape_duration_seconds`{source}、`cgw_exporter_snapshot_age_seconds`、
`cgw_exporter_upstream_requests_total`{result}、`cgw_gateway_up`。

### 3.6 桶设计

| 指标 | 上界（秒） |
|---|---|
| http_request_duration | .01 .025 .05 .1 .25 .5 1 2.5 5 10 30 |
| docker_call_duration | .01 .05 .1 .25 .5 1 2.5 5 10 30 60 120 |
| exec_duration | .1 .5 1 2.5 5 10 30 60 120 300 600 |
| proxy_duration | .01 .05 .1 .25 .5 1 2.5 5 10 30 60 120 |
| node_probe_duration | .005 .01 .025 .05 .1 .25 .5 1 2.5 5 |
| job_duration | .01 .1 .5 1 5 10 30 60 |

### 3.7 基数预算

目标 < 800 series：请求面 ~200，docker 面 ~350，节点/端口/exec/代理/文件/任务 ~200，自监控 ~20。
高基数字段（container_id、client_id、IP、cmd、env）**一律不出现**；未知枚举值降级为 `OTHER`。

### 3.8 不导出

宿主 CPU/内存/磁盘/网络、JVM 指标、容器级 CPU/内存、任何 Token 或客户端标识。

## 4. 网关侧改动清单

| 文件 | 改动 |
|---|---|
| `metrics/GatewayMetrics.java` | 新增：计数器/直方图聚合，无锁读，快照输出 |
| `metrics/MetricsDockerHttpClient.java` | 新增：包装 `DockerHttpClient`，按 URL 归类 op，记录耗时/结果 |
| `metrics/ExporterSnapshotService.java` | 新增：组装 JSON 快照 |
| `metrics/ExporterSnapshotServer.java` | 新增：`127.0.0.1:18091` HTTP 服务 |
| `docker/NodeRegistry.java` | 包装业务/exec 客户端；探测计时与原因分类；暴露 assigned/inflight |
| `docker/DockerNode.java` | 记录最近一次探测耗时 |
| `audit/AuditLogger.java` | 记录请求指标；写盘失败计数 |
| `docker/StreamingExec.java` | 记录 stream exec 结果 |
| `service/ContainerService.java` | 记录 sync exec 结果（含超时识别） |
| `web/ContainerController.java` | 记录 proxy 指标 |
| `schedule/TtlReaper.java` / `WorkspaceVolumeReaper.java` / `docker/NodePortAllocator.java` / `service/ManagedCountCache.java` | 任务耗时/成功/失败计数 |
| `config/GatewayProperties.java` + `application.yml` | `gateway.exporter-snapshot-port`（默认 18091，0=关闭） |

约束：所有埋点只做原子累加，不做 IO、不打日志；快照端点只读内存。

## 5. exporter 工程

```
exporter/
├── pom.xml
├── src/main/java/com/example/gateway/exporter/
│   ├── Main.java                 # 参数、装配、优雅退出
│   ├── config/ExporterConfig.java
│   ├── client/SnapshotClient.java
│   ├── model/*.java              # Jackson 映射
│   ├── collect/CgwCollector.java # 单次抓取构建全部指标
│   ├── collect/SelfMetrics.java
│   └── web/MetricsHandler.java, LandingPage.java
├── src/test/java/...             # golden / 上游故障 / 并发
├── config/cgw-exporter.service, config.yml, alerts.yml, prometheus-scrape.yml
└── README.md
```

实现约束：

1. `Collect()` 内构造 `MetricSnapshot`，不持有全局指标对象（自监控除外）。
2. 不设置时间戳；`generated_at_ms` 只用于 `snapshot_age_seconds`。
3. 解析 `X-Prometheus-Scrape-Timeout-Seconds`，取 `min(header-1s, 8s)`。
4. 上游失败 → 只发自监控 + `cgw_gateway_up 0`。
5. 固定枚举预初始化 0 值（11 个 action × 2 个 result）。
6. `/` 落地页、`/healthz` 自检、`/metrics` 指标。
7. 标签白名单；未知 action/op/reason 归 `OTHER`。
8. 并发抓取安全：无共享可变状态。

## 6. 阶段与验收

| 阶段 | 交付 | 验收 |
|---|---|---|
| P0 契约 | 本文档 + schema v1 | 字段/单位/桶约定评审通过 |
| P1 网关埋点 | 上表全部改动 | 单测通过；快照不含 Docker 调用；同步 exec 超时可区分 |
| P2 exporter | collector + 服务 + 自监控 + 落地页 | `curl /metrics` 出全部指标；上游断开只剩自监控 |
| P3 测试 | golden、故障注入、并发 | 全绿；序列数 < 800；单次抓取 < 100ms |
| P4 上线 | systemd、抓取、告警、Grafana | 告警可触发；重启后 `rate()` 正常 |
| P5 可选 | 容器级 stats | 默认关闭；CPU 增幅 < 3% |

## 7. 风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 网关重启导致计数器归零 | `rate()` 视作 reset，短窗口 `increase()` 可能偏低 | 文档说明；不做跨重启累加（避免 exporter 有状态） |
| 快照端点被误暴露 | 内部数据外泄 | 只绑 loopback；systemd 加固；不放 CLB |
| 上游 schema 变更 | exporter 解析错乱 | schema 版本硬校验，不匹配拒绝启动 |
| 桶边界变更 | p99 口径漂移 | 边界随快照下发，变更需评审 |
| 网关单点 | w1 指标随网关消失 | `cgw_gateway_up` 告警；建议 w1 开 dockerd 原生 metrics |

## 8. 规范对照

| 规范 | 落地 |
|---|---|
| 一对一 / 同机 | 1 网关实例 : 1 exporter，systemd 同机 |
| 同步抓取、无定时器 | 仅 `Collect()` 内一次上游读 |
| 不设时间戳 | 无 `scrapeTimestampMillis` |
| `Collect()` 内新建 | `MetricSnapshot.builder()` 每次新建 |
| 不重复采集 | 不导宿主/JVM/容器资源 |
| 命名/单位/`_total` | client 自动加 `_total`，耗时 `_seconds`，字节 `_bytes` |
| Counter 不用于可变状态 | 水位/并发/容量全部 Gauge |
| 桶按 SLO | 六组固定边界 |
| 基数控制 | 白名单 + `OTHER` + 禁高基数标签 |
| 预初始化 | 固定 action/result 发 0 |
| 自监控 | 6 个 `cgw_exporter_*` |
| 落地页 | `/` HTML |
| 安全 | 无敏感信息；监听内网；systemd 加固 |
| 并发安全 | 无共享可变状态 + 并发测试 |