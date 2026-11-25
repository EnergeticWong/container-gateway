# container-gateway 开发手册（对接指南）

> 面向通过 HTTP 或 Java 客户端使用网关的开发者。你只需要一个入口地址和一个访问凭证，就能创建容器、执行命令、交换文件、访问容器里的网站或接口，并在用完后销毁。
> 部署与运维细节见 [`docs/deployment.md`](deployment.md)。文中“示例部署默认”指安装脚本和模板中的取值，实际以部署环境的 `/etc/container-gateway.env` 为准。
>
> 最近核对：2026-11-16。历史文档中的“现网”口径已统一表述为“示例部署默认”。

---

## 目录

1. [这是什么](#1-这是什么)
2. [5 分钟快速开始](#2-5-分钟快速开始)
3. [架构与调用模型](#3-架构与调用模型)
4. [认证、限流与请求标识](#4-认证限流与请求标识)
5. [REST API 参考](#5-rest-api-参考)
6. [数据模型](#6-数据模型)
7. [生命周期语义（务必先读）](#7-生命周期语义务必先读)
8. [exec 详解：同步与流式](#8-exec-详解同步与流式)
9. [访问容器内的 HTTP 服务](#9-访问容器内的-http-服务)
10. [错误处理与重试](#10-错误处理与重试)
11. [Java 客户端库](#11-java-客户端库)
12. [集成最佳实践](#12-集成最佳实践)
13. [与原生 Docker API 的差异](#13-与原生-docker-api-的差异)
14. [排障 FAQ](#14-排障-faq)
15. [附录：默认值与限制](#15-附录默认值与限制)

---

## 1. 这是什么

container-gateway 是一个**受控的 Docker 容器生命周期网关**。调用方通过 HTTP + Bearer Token 操作远端宿主上的 Docker 容器，而无需（也不允许）直接接触 Docker Engine API；调用方可位于内网、公网，或经 VPN / 反向代理接入。

> 术语速查：Token 是访问凭证，相当于账号密码；clientId 是凭证对应的身份，容器按它隔离；tar 是一种打包格式；SSE 是服务器持续推送文本的 HTTP 格式。

**它不是 Docker API 的透明代理**，而是一个白名单网关：

- 只放行「创建 / 起停 / 销毁 / exec / 日志 / 端口代理」等一组固定操作；
- 写死安全不变量（不可请求特权、不可挂 `docker.sock`、不可用 host 网络/pid）；
- 做**所有权隔离**：你只能操作自己创建的容器；宿主上原有容器（nginx/redis/neo4j…）你永远碰不到；
- 做**资源限额**：内存 / CPU / 进程数 / TTL 都有上限。

### 开始前准备：四件事

| 需要 | 说明 |
|---|---|
| **入口地址** | 可为网关直连地址、`http://gateway.example.com:3010`（示例，经反向代理或负载均衡转发）、VPN 内可路由地址；本地自测为 `http://127.0.0.1:3010`。 |
| **访问凭证（Token）** | 形如 48 位随机串，对应一个 `clientId`，你的容器都归属该身份。 |
| **来源放行** | 你的来源 IP / 网络需符合入口侧访问控制（安全组、防火墙、负载均衡访问控制或 VPN 策略）。 |
| **可用镜像** | 镜像必须在网关允许清单内，并已在目标机器预拉；默认不会联网拉取。 |

这四项都找运维确认。

---

## 2. 5 分钟快速开始

### 2.1 curl 版

```bash
T=<你的Token>
B=http://gateway.example.com:3010/api/v1/containers

# 0) 健康检查（免鉴权，先确认通路）
curl -s http://gateway.example.com:3010/healthz | python3 -m json.tool

# 1) 创建（返回 id 与访问地址）
curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"image":"nginx:1.27-alpine","name":"my-nginx","ports":[{"containerPort":80}],
       "memoryBytes":134217728,"cpus":0.5,"ttlSeconds":600}' $B | python3 -m json.tool

# 2) 查询
curl -s -H "Authorization: Bearer $T" $B/<id> | python3 -m json.tool

# 3) 执行命令
curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"cmd":["sh","-c","echo hi; nginx -v 2>&1"]}' $B/<id>/exec | python3 -m json.tool

# 4) 经网关访问容器里的 HTTP 服务（同样要带 Token）
curl -H "Authorization: Bearer $T" $B/<id>/proxy/80/

# 5) 销毁（强停 + 删容器 + 删卷 + 回收端口）
curl -X POST -H "Authorization: Bearer $T" $B/<id>/destroy
```

### 2.2 Java 客户端版

```java
try (var gw = new ContainerGatewayClient("http://gateway.example.com:3010", token)) {

    ContainerInfo c = gw.create(CreateContainerRequest.builder("nginx:1.27-alpine")
            .port(80)
            .memoryMb(128)     // 网关默认 1024（示例部署）
            .cpus(0.5)         // 网关默认 1.0
            .ttlSeconds(600)   // 网关默认 3600（示例部署）
            .name("my-nginx")  // 可空
            .build());
    System.out.println(c.id());
    c.endpoints().forEach(e -> System.out.println(e.proxyUrl()));

    ExecResult r = gw.exec(c.id(), ExecRequest.of("sh", "-c", "echo hi; nginx -v 2>&1"));
    System.out.println(r.exitCode() + " / " + r.stdout());

    byte[] html = gw.proxyGet(c.id(), 80, "/");  // 经网关取容器 HTTP 服务
    String logs = gw.logs(c.id(), 200);          // 日志

    gw.destroy(c.id());                          // 用完即毁
}
```

引入客户端（见 [第 11 节](#11-java-客户端库)）：

```xml
<dependency>
  <groupId>com.example.gateway</groupId>
  <artifactId>container-gateway-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## 3. 架构与调用模型

```
[业务客户端：内网 / 公网 / VPN / 专线]
    │  HTTP + Bearer Token
    ▼
[网络入口：直连 / 反向代理 / CLB / VPN] ──(按需配置 TLS + 源访问控制)──▶ [container-gateway :3010]
                                                                              │  unix:///var/run/docker.sock
                                                                              ▼
                                                                         [目标 dockerd]
```

- **入口唯一**：API、容器 HTTP 代理都走同一个 `:3010`，无需再申请端口。
- **Token = 身份**：一个 Token 对应一个 `clientId`。所有归属判断都基于 clientId。
- **多节点透明**：网关可能聚合了多个 Docker 节点。响应里的 `nodeId` 标明容器落在哪个节点；调用方**无需关心**，网关会自动路由。仅当节点不可达时可能返回 `503 NODE_UNAVAILABLE`。
- **有状态**：网关自身**不持久化**容器状态——状态来自 Docker。TTL 到期由后台任务强制销毁。

---

## 4. 认证、限流与请求标识

### 4.1 认证

- 除 `/healthz*`、`/actuator/*`、`/error` 外，**所有请求**都要带：

  ```
  Authorization: Bearer <token>
  ```

- 缺失或无效 → `401 UNAUTHORIZED`：

  ```json
  {"type":"error","error":{"code":"UNAUTHORIZED","message":"缺少或无效的 Bearer Token","requestId":"..."}}
  ```

### 4.2 请求 ID

- 网关为**每个请求**生成 `requestId`，通过**响应头 `X-Request-Id`** 返回，并写入错误体。
- 排障时把这个 ID 给运维即可定位审计日志。
- 注意：网关**不接受**客户端自定义的 requestId，请以响应头为准。
- 创建接口额外支持**幂等**：可选请求头 `Idempotency-Key`（≤128 字符，默认保留 600s）。同一 clientId + key 重复 `POST /` 会返回既有容器（`201`），**不会重复创建**；同一 key 换了创建参数则返回 `409 IDEMPOTENCY_KEY_REUSED`。用于「创建响应丢失」后的安全重放。

### 4.3 限流与配额

| 维度 | 示例部署 | 超限结果 |
|---|---|---|
| 单 clientId 全接口请求 | 60000 次/分钟 | `429 RATE_LIMITED`（带 `Retry-After`） |
| 单 clientId 创建接口 | 600 次/分钟 | `429 RATE_LIMITED`（带 `Retry-After`） |
| 单 clientId 容器数 | 1000 个 | `429 QUOTA_EXCEEDED` |
| 全局容器数 | 1000 个 | `429 QUOTA_EXCEEDED` |
| 单节点运行中容器 | 30 个（每个节点独立） | `503 NODE_UNAVAILABLE` |
| 宿主端口池耗尽 | 1000 个（每节点） | `429 PORT_EXHAUSTED` |

> 代码内默认更低（`2000/min` / `60/min` / 30 个 / 60 个），示例部署按 2026-11-04 基线取值；以 `/etc/container-gateway.env` 为准。
>
> **注意**：流式 exec（SSE）在建立时也计入「全接口」限流，但**流建立后**的推送不重复计数。

---

## 5. REST API 参考

- **Base path**：`/api/v1/containers`
- **请求体**：`Content-Type: application/json`
- **响应**：业务接口为 JSON；`/logs` 为 `text/plain`；`/exec/stream` 为 `text/event-stream`；`/files/download` 为 `application/octet-stream`
- **错误体**：统一为 `{"type":"error","error":{code,message,requestId}}`

### 5.1 端点总表

| 方法 路径 | 入参 | 出参 | 状态码 |
|---|---|---|---|
| `POST /` | 创建字段（见 6.4） | `ContainerInfo` | `201` |
| `GET /` | — | `ContainerInfo[]`（仅当前 Token 归属） | `200` |
| `GET /{id}` | — | `ContainerInfo` | `200` / `403` / `404` / `503` |
| `POST /{id}/start` | — | `{id,status}` | `200` |
| `POST /{id}/stop` | `?timeoutSeconds=10` | `{id,status}` | `200` |
| `POST /{id}/restart` | `?timeoutSeconds=10` | `{id,status}` | `200` |
| `DELETE /{id}` | `?removeVolumes=true` | 无体 | `204` / `409`（运行中） |
| `POST /{id}/destroy` | — | 无体 | `204`（幂等） |
| `POST /{id}/exec` | `{cmd[],timeoutSeconds?,env?}` | `ExecResult` | `200` / `409`（超时） |
| `POST /{id}/exec/stream` | 同 exec；`Accept: text/event-stream` | SSE 流 | `200` |
| `GET /{id}/logs` | `?tail=200&timestamps=false` | `text/plain` | `200` |
| `POST /{id}/files/upload` | 请求体=tar；`?dest=` 可选 | `FileUploadResult` | `201` / `413` |
| `GET /{id}/files/download` | `?path=` | tar（`application/octet-stream`） | `200` / `404` / `413` |
| `GET /{id}/files` | `?path=` | `FileEntry[]` | `200` / `404` / `413` |
| `ANY /{id}/proxy/{port}/**` | 原样转发 | 原样返回 | 透传 |
| `GET /healthz` | 免鉴权 | 健康 JSON | `200` |
| `GET /healthz/live` | 免鉴权 | 存活 JSON（不访问 Docker） | `200` |
| `GET /healthz/ready` | 免鉴权 | 同 `/healthz`；不健康返回 503 | `200` / `503` |

### 5.2 逐端点说明

#### `POST /` — 创建

- 请求字段见 [6.4](#64-创建请求字段)。`image` 必填。
- **返回 201** 与 `ContainerInfo`（含 `endpoints`）。容器会被**立即启动**。
- 典型错误：`403 IMAGE_NOT_ALLOWED`（不在白名单 / 本地不存在且未开 pull）、`400 VALIDATION_ERROR`（参数越界）、`429 QUOTA_EXCEEDED`。

#### `GET /` — 列表

- 仅返回**当前 Token（clientId）**归属的托管容器，含已停止的。
- 其他客户端的容器、宿主原有容器都不会出现。

#### `GET /{id}` — 查询

- 返回 `ContainerInfo`（`status` 来自 Docker，如 `running`/`exited`）。
- `200` 成功；`403` 容器存在但归属其他凭证或非网关托管；`404` 容器明确不存在；`503` 目标节点不可达、暂时无法判断容器是否存在。

#### `POST /{id}/start|stop|restart`

- 返回轻量 `{id,status}`。
- `stop`/`restart` 的 `timeoutSeconds` 为**优雅停止等待秒数**，默认 10。

#### `DELETE /{id}` — 软删

- **要求容器已停止**，否则 `409 STATE_CONFLICT`（提示改用 destroy）。
- `removeVolumes` 默认 `true`。
- 容器不存在 → `404`。

#### `POST /{id}/destroy` — 销毁（推荐）

- 直接 force remove（SIGKILL）+ 删卷 + **回收宿主端口**（不再先做优雅 stop）。
- **幂等**：容器已被 AutoRemove 删除时也返回 `204`。
- 若目标是宿主原有容器（非托管）→ `403 OWNERSHIP_DENIED`。

#### `GET /healthz` — 健康检查（免鉴权）

```json
{
  "status": "UP",
  "docker": "UP",
  "nodes": [{"id":"local","endpoint":"unix:///var/run/docker.sock","status":"UP",
             "runningContainers":9,"activeStreams":0,"portsUsed":0},
            {"id":"w1","endpoint":"tcp://192.0.2.11:2376","status":"UP",
             "runningContainers":2,"activeStreams":0,"portsUsed":0}],
  "healthyNodes": 2,
  "managedContainers": 0,
  "maxContainers": 1000,
  "nodeMaxTasks": 30,
  "portPoolUsed": 0,
  "portPoolCapacity": 1000,
  "autoRemove": false,
  "networkMode": "bridge"
}
```

> 客户端**不要**用 `/healthz` 里的 `managedContainers` 当作自己的容器数——那是全局值。用 `GET /` 拿自己的。示例中的 `runningContainers` 等计数仅为快照，运行时变化。

#### `POST /{id}/files/upload` — 上传（tar）

- 请求体**即 tar 字节**（`--data-binary`），经 Docker archive 接口解压进容器。
- `dest` 缺省为 `file-upload-dir`；必须落在该目录下。
- 返回 `FileUploadResult`：`{dest, fileCount, totalBytes, files[]}`。

#### `GET /{id}/files/download`（tar）与 `GET /{id}/files`（列举）

- `path` 必填，且必须在 `file-output-dir` 下。
- `download` 返回该路径的 tar 归档（`application/octet-stream`）；`files` 返回条目 JSON 数组 `[{name,size,type}]`。
- 容器内路径不存在 → `404`。**下载/列举必须在销毁容器之前完成。**
- Java 客户端库（1.0.0）目前未封装文件收发方法，需按上面的 HTTP 形式自行调用；客户端库也不支持 `meta` 与 `Idempotency-Key`。

### 5.3 文件收发（阶段 B）

tar 流方式在容器与调用方之间传文件：**上传压到 `file-upload-dir`，下载/列举只读 `file-output-dir`**，二者都必须在 `file-root` 下（默认 `/workspace`）。此能力**默认开启**（`GATEWAY_FILE_TRANSFER_ENABLED`）。

```bash
# 上传：把本地 ./out 目录打成 tar 送到容器 /workspace/input
tar cf - -C ./out . | curl -s -H "Authorization: Bearer $T" \
  --data-binary @- "$B/<id>/files/upload?dest=/workspace/input" | python3 -m json.tool

# 执行后下载产物（tar）
curl -s -H "Authorization: Bearer $T" "$B/<id>/files/download?path=/workspace/out" -o out.tar
tar tf out.tar

# 列举产物条目
curl -s -H "Authorization: Bearer $T" "$B/<id>/files?path=/workspace/out" | python3 -m json.tool
```

**安全约束**：路径必须是容器内绝对路径且位于白名单目录下；拒绝 `..`、绝对越界、符号/硬链接与设备文件。

**大小限制**（示例部署，2026-11-16 核对）：

| 限制 | 示例部署 | 代码默认 | 变量 |
|---|---|---|---|
| 单文件 | 100 MiB | 32 MiB | `GATEWAY_FILE_MAX_BYTES` |
| 单次归档总量 | 200 MiB | 64 MiB | `GATEWAY_FILE_MAX_TOTAL_BYTES` |
| 归档条目数 | 512 | 64 | `GATEWAY_FILE_MAX_COUNT` |

超限一律返回 `413 PAYLOAD_TOO_LARGE`。三个限制都是服务端按**原始字节/条目数**判定，有几条容易踩：

- **单文件上限对上传、下载、列举同时生效**：产物目录里只要有一个文件超过 100 MiB，`download` 和 `files`（列举会先打包整个目录）都会直接 413。要取回大产物，请在容器内先切分成多个 <100 MiB 的文件。
- **总量按 tar 原始字节算**，tar 头/填充也计入：内容正好 200 MiB 打出来的包会略超 200 MiB 而被拒，实际内容上限约 199.7 MiB，留几 MB 余量。
- 上传/下载都是**整包读进网关内存**（单请求峰值约为包大小的 3~4 倍），大包并发会明显挤占网关堆，建议单包控制在几十 MB 以内、或串行发送。
- 文件数上限按 tar 条目计（含目录条目），一个 `./` 目录条目也占一个名额。

> 只读根文件系统下，`/workspace` 必须由运维启用的托管工作区提供：本机节点为宿主目录挂载，远程节点为命名卷。**不要**通过 `GATEWAY_TMPFS` 挂 `/workspace`。若文件 API 返回 400/500，先检查工作区挂载与属主。

---

## 6. 数据模型

### 6.1 `ContainerInfo`

```json
{
  "id": "8f0c...e2",
  "name": "my-nginx",
  "status": "running",
  "image": "nginx:1.27-alpine",
  "createdAt": "2026-09-24T08:39:04.476Z",
  "expiresAt": "2026-09-24T08:49:04.476Z",
  "ttlSeconds": 600,
  "exitCode": 0,
  "nodeId": "local",
  "meta": {"tenantId": "t1", "userId": "u1"},
  "endpoints": [ /* Endpoint[] */ ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 容器 ID（后续所有操作都用它） |
| `name` | string | 容器名 |
| `status` | string | Docker 状态：`running`/`exited`/`created`/`removing`；一次性任务可能为 `removed` |
| `image` | string | 镜像 |
| `createdAt` | ISO-8601 | 创建时间 |
| `expiresAt` | ISO-8601 | 到期时间（TTL 自动销毁的参考时刻） |
| `ttlSeconds` | long | TTL 秒数 |
| `exitCode` | int? | 退出码；运行中或信息不可得时为 `null` |
| `nodeId` | string | 所在 Docker 节点 |
| `meta` | object | 创建时提交的业务上下文，按原样回显（未提交则为空对象） |
| `endpoints` | Endpoint[] | 端口访问地址 |

### 6.2 `Endpoint`

```json
{
  "containerPort": 80,
  "protocol": "tcp",
  "hostPort": 30000,
  "nodeId": "local",
  "directUrl": "http://192.0.2.43:30000/",
  "proxyUrl": "http://gateway.example.com:3010/api/v1/containers/8f0c...e2/proxy/80/"
}
```

| 字段 | 说明 |
|---|---|
| `containerPort` | 容器内端口 |
| `protocol` | `tcp` / `udp`；但 `proxyUrl` / 代理端点仅支持 TCP 端口，UDP 需使用直连地址或其他转发方案 |
| `hostPort` | 宿主映射端口（网关从 `30000-30999` 自动分配，**不可指定**） |
| `directUrl` | 直连地址；**仅当**调用方网络能路由到宿主地址且运维配置了 `GATEWAY_DIRECT_HOST` 时才有值，否则为 `null` |
| `proxyUrl` | 经网关代理的地址，与 API 同一入口；**请求仍需带 Bearer Token** |

> **推荐优先使用 `proxyUrl`**：它与 API 共用同一入口，宿主映射端口无需在入口侧逐个开放。

### 6.3 `StatusView`

```json
{"id":"8f0c...e2","status":"running"}
```

### 6.4 创建请求字段

| 字段 | 必填 | 类型 | 默认 | 约束 |
|---|---|---|---|---|
| `image` | ✅ | string | — | 必须在白名单内，且宿主已有（默认禁止 pull） |
| `cmd` | | string[] | 镜像默认 | 启动命令 |
| `env` | | object | — | `{"KEY":"VALUE"}` |
| `ports` | | PortSpec[] | `[]` | 最多 16 个；**宿主端口由网关分配** |
| `mounts` | | MountSpec[] | `[]` | 仅当运维开启 `GATEWAY_BIND_MOUNTS_ENABLED=true` 时可用，否则 `400` |
| `memoryBytes` | | long | 1073741824（1GiB） | 范围 [16777216, 4294967296] |
| `cpus` | | double | 1.0 | 范围 (0, 4] |
| `pidsLimit` | | long | 256 | 范围 [16, 512] |
| `ttlSeconds` | | long | 3600 | 范围 [30, 86400]，到期自动销毁 |
| `meta` | | object | 空 | 最多 16 项；key 匹配 `[a-zA-Z0-9_.-]{1,32}`，value ≤256 字符；落 label 并在 `ContainerInfo.meta` 回显；`GATEWAY_REQUIRE_BUSINESS_CONTEXT=true` 时必填 `tenantId`、`userId` |
| `name` | | string | 自动生成 `gw-<client>-<8随机>` | 正则 `[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}` |

> 上表「默认」为示例部署 `/etc/container-gateway.env` 取值（2026-11-16 核对）。代码内默认更低（TTL 300s、内存 256MiB、配额 60/30、限流 2000/60），运维可调；以实际 env 为准。

**子结构**

```jsonc
// PortSpec
{ "containerPort": 80, "protocol": "tcp" }   // protocol 可省，默认 tcp；仅 tcp/udp

// MountSpec（默认禁用）
{ "hostPath": "/data/x", "containerPath": "/x", "readOnly": true }
```

> **安全**：请求体**不接受** `privileged` / `networkMode` / `pid` / `hostNetwork` 等字段（发了也会被忽略）。HostConfig 完全由服务端构造，你无法影响隔离性。

---

## 7. 生命周期语义（务必先读）

这一节是**最容易踩坑**的地方。

### 7.1 AutoRemove 语义（示例部署关闭）

示例部署 `GATEWAY_AUTO_REMOVE=false`（代码默认 `true`）：**容器退出后保留**，可以事后查询状态、退出码与日志。这意味着：

- 一次性命令跑完后容器处于 `exited`：`GET /{id}` 返回 `200`，`exitCode` 可用，`GET /{id}/logs` 可读输出。
- 已退出的容器不会自己消失，仍占配额与端口，直到 `destroy` 或 TTL 到期回收；**用完请显式 `destroy`**。
- 对已退出的容器，`DELETE /{id}`（软删，要求已停止）与 `POST /{id}/destroy`（强删）都可用。

> 若运维把 `GATEWAY_AUTO_REMOVE=true`：容器退出即被 Docker 删除，`GET /{id}` → `404`，`destroy` 仍幂等返回 `204`；创建一次性任务时返回值可能退化为 `status:"removed"`、`exitCode=null`。

### 7.2 状态机

```
        create
          │
          ▼
      [running] ──stop──▶ [exited] ──start──▶ [running]
          │                   │
     destroy(强删)        remove(软删,要求已停止)
          │                   │
          ▼                   ▼
        (消失)              (消失)
          ▲
          └── AutoRemove=true 时，退出即自动到这里
```

### 7.3 软删 vs 销毁

| | `DELETE /{id}` (remove) | `POST /{id}/destroy` |
|---|---|---|
| 运行中 | `409`，必须先 stop | 直接 force remove |
| 删卷 | 默认删 | 删 |
| 端口回收 | 回收 | 回收 |
| 幂等 | 否（不存在 → 404） | 是（已消失 → 204） |
| 用途 | 精确控制「先停再删」 | **沙箱用完即毁，首选** |

### 7.4 TTL 不是精确的

`expiresAt` 只是参考时刻。后台回收任务每 60s 扫描一次（`TTL_SCAN_INTERVAL_MS`），TTL 到期后最多约一个扫描周期内被销毁；TTL 本身没有额外 grace（120s grace 只用于端口池对账）。**不要**把 TTL 当作精确计时器；要精确清理请显式 `destroy`。

---

## 8. exec 详解：同步与流式

两种方式：短命令用同步 `POST /{id}/exec`，长命令/需要实时看输出用 `POST /{id}/exec/stream`(SSE)。

> 前提：容器需处于 **running** 状态。

### 8.1 同步 exec

请求：

```json
{"cmd":["sh","-c","echo hi; sleep 1; nginx -v 2>&1"], "timeoutSeconds":30, "env":{"K":"V"}}
```

响应：

```json
{"exitCode":0,"stdout":"hi\n...","stderr":"","truncated":false,"durationMs":1032}
```

- `timeoutSeconds` 范围 `[1, 600]`，默认 30。
- 命令在 `timeoutSeconds` 内未结束：`409 STATE_CONFLICT`，命令可能仍在容器中运行。
- Docker 后端读超时或节点不可达：`503 NODE_UNAVAILABLE`，可按退避重试。
- 输出按 stdout/stderr 分流；**单个流超过 65536 字节会被截断**并置 `truncated=true`。

### 8.2 流式 exec（SSE）

SSE（Server-Sent Events）是一种服务器持续推送文本的 HTTP 格式。客户端必须处理 `error` 和 `exit` 终态事件，收到终态后关闭连接。

请求（注意 `Accept` 头）：

```
POST /api/v1/containers/{id}/exec/stream
Accept: text/event-stream
Content-Type: application/json

{"cmd":["sh","-c","for i in 1 2 3; do echo line-$i; sleep 1; done; echo err 1>&2"]}
```

事件协议（SSE 的 `event:` 名等于事件里的 `type`）：

| event | data 关键字段 | 含义 |
|---|---|---|
| `stdout` | `stream:"stdout"`, `data:"..."` | 标准输出分块 |
| `stderr` | `stream:"stderr"`, `data:"..."` | 标准错误分块 |
| `truncated` | `truncated:true` | 输出超过上限，已停止采集 |
| `exit` | `exitCode`, `durationMs`, `truncated` | 命令结束（**终态**） |
| `error` | `code`, `message`, `timedOut` | 执行失败/超时（**终态**） |

- **心跳**：每 15s 发一个注释帧 `:keepalive`（客户端应忽略）。
- **前置校验失败也走流**：归属校验失败、`cmd` 为空、节点流并发超限等，都以 `error` 事件返回（HTTP 仍是 200），不会用 HTTP 错误码。
- **超时**：到 `timeoutSeconds` 收尾为 `error`，`code="EXEC_TIMEOUT"`, `timedOut=true`。
- **执行异常**：`code="EXEC_ERROR"`。

客户端示例（Java 库已封装解析）：

```java
gw.execStream(id, ExecRequest.of("sh","-c","for i in 1 2 3; do echo $i; sleep 1; done"),
    ev -> {
        if (ev.isOutput())        System.out.print("[" + ev.stream() + "] " + ev.data());
        else if (ev.isError())    System.err.println("错误: " + ev.code() + " " + ev.message());
        else if (ev.isTerminal()) System.out.println("退出码=" + ev.exitCode());
    });
```

### 8.3 日志 `GET /{id}/logs`

```
GET /api/v1/containers/{id}/logs?tail=200&timestamps=false
```

- 返回 `text/plain`，**stdout+stderr 合并**。
- `tail` 会被夹到 `[1, 5000]`；总输出超上限会截断并追加 `...[日志已截断]`。
- 因为 `produces=text/plain`，若你发 `Accept: application/json` 会得到 **`406 NOT_ACCEPTABLE`**。客户端应把 Accept 设为 `text/plain` 或 `*/*`。
- 日志接口不支持 `follow=true`，只读取最近 N 行快照。需要持续观察新执行命令的输出请使用 `exec/stream`；当前网关不提供容器主进程日志的流式跟踪。

---

## 9. 访问容器内的 HTTP 服务

用 `proxyUrl`：

```
ANY  /api/v1/containers/{id}/proxy/{容器端口}/{任意子路径}?查询串
```

- 网关查到你对该容器的归属后，把请求**原样转发**到「该节点 + 该容器端口对应的宿主端口」，并原样返回状态码、头、体。
- **必须带 Bearer Token**（与 API 相同）。
- 限制：请求体上限 8 MiB（超出 → `400`）；超时 120s；端口未映射 → `404 NOT_FOUND`；连不上容器 → `503 NODE_UNAVAILABLE`。
- `proxyUrl` 的主机名优先取运维配置的 `GATEWAY_ADVERTISE_HOST` / `GATEWAY_ADVERTISE_SCHEME`；未配置时按请求 `Host` 推导。若运维关闭 `GATEWAY_PROXY_ENABLED`，代理端点不可用。

```bash
# 取容器里 nginx 首页
curl -H "Authorization: Bearer $T" $B/<id>/proxy/80/

# 带子路径与查询串
curl -H "Authorization: Bearer $T" "$B/<id>/proxy/8080/api/items?page=1"
```

---

## 10. 错误处理与重试

### 10.1 错误体

```json
{"type":"error","error":{"code":"OWNERSHIP_DENIED","message":"...","requestId":"..."}}
```

### 10.2 错误码表与重试建议

| code | HTTP | 含义 | 可重试？ |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | 参数非法 | ❌ 改参数 |
| `UNAUTHORIZED` | 401 | Token 缺失/无效 | ❌ 检查 Token |
| `OWNERSHIP_DENIED` | 403 | 容器非托管或归属他人 | ❌ |
| `IMAGE_NOT_ALLOWED` | 403 | 镜像不在白名单/本地不存在 | ❌ 找运维加白 |
| `NOT_FOUND` | 404 | 容器/端口/文件路径不存在 | ❌ |
| `STATE_CONFLICT` | 409 | 状态不允许（运行中删除、exec 超时） | ⚠️ 视情况 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 同一 `Idempotency-Key` 换了创建参数 | ❌ 换 key 或复用原参数 |
| `PAYLOAD_TOO_LARGE` | 413 | 文件上传/下载超过大小上限 | ❌ 缩小文件 |
| `NOT_ACCEPTABLE` | 406 | Accept 头不对（如 `/logs` 要 `text/plain`） | ❌ 改头 |
| `METHOD_NOT_ALLOWED` | 405 | 方法不对 | ❌ |
| `RATE_LIMITED` | 429 | 限流（响应头带 `Retry-After`） | ✅ 按 `Retry-After` 退避 |
| `QUOTA_EXCEEDED` | 429 | 配额/流并发超限 | ✅ 清理后重试 |
| `PORT_EXHAUSTED` | 429 | 端口池耗尽 | ✅ 退避重试 |
| `DOCKER_ERROR` | 500 | Docker 操作失败 | ⚠️ 少见可重试 |
| `DOCKER_UNAVAILABLE` | 503 | 连不上 dockerd | ✅ 退避重试 |
| `NODE_UNAVAILABLE` | 503 | 目标节点不可达/饱和 | ✅ 退避重试 |

### 10.3 重试要点

- **仅 429 / 503 适合重试**，用指数退避。
- **`destroy` 可安全重试**（幂等）。
- `create` 不带 `Idempotency-Key` 时**不幂等**——重试前先 `GET /` 查是否已建出来，避免重复创建（也会占配额）；带同一个 key 重放是安全的。
- `exec` 超时（409）说明容器里命令仍在跑；重试会再启一次命令，注意副作用。

---

## 11. Java 客户端库

### 11.1 引入

```xml
<dependency>
  <groupId>com.example.gateway</groupId>
  <artifactId>container-gateway-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

- 仅依赖 **JDK 21 HttpClient + Jackson 2**，可直接放进任意 Java 工程（内网或公网均可）。
- 服务端用的是 Spring Boot 4 / Jackson 3，客户端用 Jackson 2，两者互不影响。

### 11.2 方法总表

| 方法 | 对应端点 |
|---|---|
| `create(CreateContainerRequest)` | `POST /` |
| `list()` | `GET /` |
| `get(id)` | `GET /{id}` |
| `start(id)` / `stop(id[,timeout])` / `restart(id[,timeout])` | `POST /{id}/start\|stop\|restart` |
| `remove(id[,removeVolumes])` | `DELETE /{id}` |
| `destroy(id)` | `POST /{id}/destroy` |
| `exec(id, ExecRequest)` | `POST /{id}/exec` |
| `execStream(id, ExecRequest, Consumer<ExecEvent>)` | `POST /{id}/exec/stream` |
| `logs(id, tail[, timestamps])` | `GET /{id}/logs` |
| `health()` | `GET /healthz` |
| `proxyUrl(id, port)` / `proxyGet(id, port, path)` | `/{id}/proxy/{port}/**` |

> 当前 1.0.0 客户端库**未封装**文件收发、`meta`、`Idempotency-Key`；这些能力需按第 5 节的 HTTP 契约自行调用。

### 11.3 异常

所有错误抛 `ContainerGatewayException`：

```java
try {
    gw.create(...);
} catch (ContainerGatewayException e) {
    switch (e.getCode()) {
        case "RATE_LIMITED", "NODE_UNAVAILABLE" -> backoffRetry();
        case "IMAGE_NOT_ALLOWED" -> askOpsToWhitelist();
        default -> log.error("网关错误 {} [{}] {}", e.getCode(), e.getRequestId(), e.getMessage());
    }
}
```

- `getCode()`：稳定错误码（见 10.2）；连接层失败为 `CONNECTION_ERROR` / `INTERRUPTED` / `DECODE_ERROR`（此时 `getHttpStatus()==0`）。
- `getHttpStatus()`、`getMessage()`、`getRequestId()`。

### 11.4 流式 exec 的语义

`execStream` **同步阻塞直到流结束**。事件里的错误（归属失败、执行超时）以 `error` 事件回调，**不抛异常**；只有连接层失败才抛 `ContainerGatewayException`。

### 11.5 端到端自检 Demo

```bash
java -jar container-gateway-client-1.0.0-all.jar http://127.0.0.1:3010 <token>
# 或
GW_BASE_URL=http://gateway.example.com:3010 GW_TOKEN=xxx java -jar container-gateway-client-1.0.0-all.jar
```

覆盖：健康检查 → 创建 → exec → 流式 exec → 代理访问 → 日志 → 列表 → 销毁。

---

## 12. 集成最佳实践

### 12.1 短任务沙箱模式（推荐）

```java
ContainerInfo c = gw.create(CreateContainerRequest.builder("alpine:3.20")
        .cmd("sleep", "3600")     // 让容器常驻，命令通过 exec 下发
        .memoryMb(256).cpus(1.0).ttlSeconds(300)
        .build());
try {
    ExecResult r = gw.exec(c.id(), ExecRequest.of("sh","-c", script).withTimeout(120));
    // 用 r.exitCode()/stdout/stderr
} finally {
    gw.destroy(c.id());          // 无论成败都销毁；TTL 只是兜底
}
```

要点：

- **总是用 `try/finally` 销毁**，不要依赖 TTL（TTL 只有扫描间隔，不精确，且示例部署容器退出后不会自动删除）。
- 容器内**没有网络包管理器也不该联网**（默认禁 pull、沙箱应自包含）；用宿主已预拉的基础镜像。
- 需要长命令/实时输出用 `execStream`。

### 12.2 限额预算

示例部署一个 clientId 最多 **1000 个容器**、**60000 请求/分钟**、**600 次创建/分钟**；单节点另有 30 个运行中容器上限，且默认内存 1GiB（30 个满配约 30GiB，接近节点可用内存），**建议显式设置 `memoryBytes`**。高并发场景：

- 复用「常驻容器 + 多次 exec」比「每任务建/删容器」更省配额与端口；
- 或找运维调高 `MAX_PER_OWNER` / `RATE_LIMIT_PER_MINUTE`（需评估宿主资源）。

### 12.3 幂等与清理

- 应用启动时可 `list()` 一遍，把不属于当前逻辑会话的旧容器 `destroy()` 掉，避免占配额。
- `destroy` 幂等，可放心放进 `finally`。

### 12.4 超时设置经验

- `exec` 的 `timeoutSeconds` 要**大于**命令预期耗时；超时后命令仍在容器里跑。
- 客户端 HTTP 超时也要比服务端 `timeoutSeconds` 更大（Java 库已自动 +10~+60s）。

### 12.5 容器内服务访问

- 用 `proxyUrl`，别用 `hostPort`（CLB 不对外开端口段）。
- 访问代理地址同样要带 Token，注意别把 Token 泄露到前端/日志。

### 12.6 对接前检查清单

- [ ] 已拿到 Token，并确认来源 IP 在 CLB 白名单内
- [ ] 需要的镜像已在白名单、且宿主已预拉（`docker pull` 由运维做）
- [ ] 默认 TTL / 内存 / CPU 满足业务；不够找运维调
- [ ] 代码里对 `destroy` 做了 `finally` 兜底
- [ ] 对 429/503 做了退避重试
- [ ] 用完显式 `destroy`；不要依赖 TTL 或 AutoRemove 清理（示例部署 AutoRemove=false，容器退出后仍占配额与端口）

---

## 13. 与原生 Docker API 的差异

网关是**白名单子集**，不是完整 Docker Engine API 的代理。对照如下，请据此调整既有 docker-py / docker-java 逻辑（如有）。

**已提供等价能力**：create/start/stop/restart/remove/inspect/list、exec（同步+SSE）、logs、端口代理、镜像存在性校验（仅当运维开启 `GATEWAY_ALLOW_PULL=true` 且镜像命中允许清单时才允许受控拉取）、**受控文件收发（tar，见 5.3）**。

**未提供**（原生 Docker 有、网关没有；属有意收敛）：

| 类别 | 缺失能力 |
|---|---|
| 容器 | `stats`（实时 CPU/内存）、`top`、`changes`、`export`、`kill`、`pause`/`unpause`、`rename`、`update`（在线改限额）、`wait`、`attach`、`resize` |
| 镜像 | 列举、`history`、`tag`、删除、`prune`、`load`/`save`、`build`、`commit`、`auth` |
| 网络 | `/networks` CRUD/connect/disconnect/prune（只能靠运维预设 `GATEWAY_NETWORK_MODE`） |
| 卷 | `/volumes` CRUD/prune（仅 `destroy` 时按 `removeVolumes` 删） |
| 系统 | `/info`、`/version`、`/events`、`/system/df` |
| 日志 | `logs?follow=true`（流式追日志） |
| 浏览器 | CORS 响应头（浏览器直接调用会被同源策略拦截，Web 前端需经后端代理） |

**受影响的常见写法**：

- 用 `/info` 做节点负载探测 → 网关不暴露。改用 `/healthz` 的 `nodes[].runningContainers`（只读观测），或依赖网关自身选路。
- 用 `wait` 等容器结束 → 改为轮询 `GET /{id}`，或直接 `exec` 同步等命令结束。
- 用 `stats` 采集资源 → 不可用；只能拿到容器数量级信息。
- 用 `archive`/`cp` 传文件 → 用受控的 `POST /{id}/files/upload` 与 `GET /{id}/files/download`（见 5.3）；小文本仍可经 `exec` 的 stdout 传递。

---

## 14. 排障 FAQ

**Q: 一直 401。**
A: 确认请求头是 `Authorization: Bearer <token>`；确认访问的是需要鉴权的 `/api/v1/...` 路径；`/healthz*` 不需要 Token。

**Q: 403 IMAGE_NOT_ALLOWED。**
A: 镜像不在白名单，或宿主上不存在且默认禁 pull。找运维加白名单并 `docker pull`。

**Q: 创建后马上 `GET` 变 404。**
A: 示例部署 `GATEWAY_AUTO_REMOVE=false`，一次性容器退出后应返回 `200`（`status=exited`）而不是 404。若仍 404，通常是 id 不存在或已被 TTL / `destroy` 回收；403 表示归属其他凭证；503 表示存在不可达节点，稍后重试。只有运维开启 `GATEWAY_AUTO_REMOVE=true` 时才会「退出即 404」。

**Q: exec 返回 409。**
A: 超时（命令仍在跑，见消息），或容器未运行。确认容器 `status=running`，并调大 `timeoutSeconds`。

**Q: `/logs` 返回 406。**
A: 你发了 `Accept: application/json`。改成 `text/plain` 或 `*/*`。

**Q: 文件上传/下载/列举返回 413 PAYLOAD_TOO_LARGE。**
A: 示例部署限制是单文件 100 MiB、单次归档 200 MiB、条目 512（见 5.3）。注意三个容易踩的点：① 单文件上限对下载和列举同样生效，产物里只要有一个文件超 100 MiB 就取不回来，需在容器内切分；② 总量按 tar 原始字节算，内容正好 200 MiB 会因 tar 头开销略超而被拒；③ 文件数含目录条目。

**Q: 代理访问容器返回 503。**
A: 容器所在节点不可达，或容器内服务没在监听该端口。先确认端口映射（`endpoints`）和容器内进程。

**Q: 报 429 RATE_LIMITED，但我调得并不快。**
A: 流式 exec 建立也计一次全局限流；且限流是**按 clientId**，多实例共用同一 Token 会叠加。核对预算或找运维调高。

**Q: 列表里看不到我上一步建的容器。**
A: 可能已被 TTL 回收或已 `destroy`；也可能是别的 Token（clientId）建的——列表只显示本 Token 归属。

---

## 15. 附录：默认值与限制

> 下表为**示例部署** `/etc/container-gateway.env` 取值（2026-11-16 核对）；代码内默认更低（括号内为代码默认），以实际 env 为准。

| 项 | 示例部署 | 可由运维调整的变量 |
|---|---|---|
| 监听端口 | 3010 | `GATEWAY_PORT` |
| 全接口限流 | 60000/min（代码 2000） | `RATE_LIMIT_PER_MINUTE` |
| 创建限流 | 600/min（代码 60） | `CREATE_RATE_LIMIT_PER_MINUTE` |
| 健康检查限流（按源 IP） | 240/min | `HEALTH_RATE_LIMIT_PER_MINUTE` |
| 单 client 容器数 | 1000（代码 30） | `MAX_PER_OWNER` |
| 全局容器数 | 1000（代码 60） | `MAX_CONTAINERS` |
| 单节点运行中容器上限 | 30 | `GATEWAY_NODE_MAX_TASKS` |
| 默认 / 最大 / 最小 TTL | 3600 / 86400 / 30 s（代码 300） | `DEFAULT_TTL_SECONDS` / `MAX_TTL_SECONDS` / `MIN_TTL_SECONDS` |
| 默认 / 最大 / 最小内存 | 1GiB / 4GiB / 16MiB（代码 256MiB） | `DEFAULT_MEMORY_BYTES` / `MAX_MEMORY_BYTES` / `MIN_MEMORY_BYTES` |
| 默认 / 最大 CPU | 1.0 / 4.0 | `DEFAULT_CPUS` / `MAX_CPUS` |
| 默认 / 最大 PID | 256 / 512 | `DEFAULT_PIDS` / `MAX_PIDS` |
| 单容器端口映射上限 | 16 | `MAX_PORTS_PER_CONTAINER` |
| 宿主端口池 | 30000-30999 | `PORT_RANGE_START` / `PORT_RANGE_END` |
| exec 默认 / 最大超时 | 30 / 600 s | `EXEC_DEFAULT_TIMEOUT_SECONDS` / `EXEC_MAX_TIMEOUT_SECONDS` |
| exec 输出上限 | 65536 字节/流 | `EXEC_MAX_OUTPUT_BYTES` |
| SSE 心跳 | 15 s | `GATEWAY_STREAM_HEARTBEAT_MS` |
| 代理体积上限 / 超时 | 8 MiB / 120 s | `GATEWAY_PROXY_MAX_BODY_BYTES` / `GATEWAY_PROXY_TIMEOUT_SECONDS` |
| 日志 tail 上限 | 5000 行 | 固定 |
| AutoRemove | false（代码默认 true） | `GATEWAY_AUTO_REMOVE` |
| 网络模式 | bridge | `GATEWAY_NETWORK_MODE` |
| bind mount | 禁用 | `GATEWAY_BIND_MOUNTS_ENABLED` |
| 镜像白名单 | `nginx:1.27-alpine`、`debian:12-slim`、`eclipse-temurin:21-jre`、`alpine:3.20`、`sandbox-runtime:2026.10` / `2026.1021` / `2026.1104` | `GATEWAY_IMAGE_WHITELIST` |
| 允许联网 pull | false | `GATEWAY_ALLOW_PULL` |
| 单节点流并发上限 | 200（代码 50） | `GATEWAY_MAX_STREAMS_PER_NODE` |
| 文件收发 | 开启 | `GATEWAY_FILE_TRANSFER_ENABLED` |
| 文件根 / 上传 / 产物目录 | `/workspace` / `/workspace/input` / `/workspace/out` | `GATEWAY_FILE_ROOT` / `GATEWAY_FILE_UPLOAD_DIR` / `GATEWAY_FILE_OUTPUT_DIR` |
| 单文件 / 单请求 / 条目数上限 | 100 MiB / 200 MiB / 512（代码 32 MiB / 64 MiB / 64） | `GATEWAY_FILE_MAX_BYTES` / `GATEWAY_FILE_MAX_TOTAL_BYTES` / `GATEWAY_FILE_MAX_COUNT` |
| 创建幂等 | 开启，TTL 600s | `GATEWAY_CREATE_IDEMPOTENCY_ENABLED` / `GATEWAY_CREATE_IDEMPOTENCY_TTL_SECONDS` |
| 强制业务上下文（meta.tenantId/userId） | 否 | `GATEWAY_REQUIRE_BUSINESS_CONTEXT` |
| 容器运行用户 | 1000:1000（代码默认空） | `GATEWAY_RUN_AS_USER` |

**安全不变量（不可配置，客户端无法影响）**：`privileged=false`、禁用 host pid/net ns、绝不挂 `docker.sock`、内存禁用 swap（`memorySwap==memory`）、默认丢弃 28 个危险 capability、默认 `no-new-privileges`。
