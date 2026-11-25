# container-gateway —— 部署与运维手册

本文面向部署者和运维人员，说明如何安装、配置、扩展与排查网关；如果你只想调用接口，请先读 [客户端接入指南](developer-guide.md)。文中 `gateway.example.com`、`192.0.2.*` 等均为示例地址，实际以部署环境为准。

网关不绑定特定网络位置，可部署在内网、公网或混合网络，并可经直连、反向代理、负载均衡或 VPN 接入。安装脚本默认端口为 **18090**；本文示例使用 **3010**，因此安装命令会显式设置 `GATEWAY_PORT=3010`。

```
[业务客户端：内网 / 公网 / VPN / 专线]
    │  HTTP + Bearer Token
    ▼
[网络入口：直连 / 反向代理 / CLB / VPN] ──(按环境配置 TLS + 源访问控制)──▶ [container-gateway :3010]
  示例：lb-gateway-example / gateway.example.com                /opt/container-gateway
                                                            │
                          ┌─────────────────────────────────┴─────────────────────────────────┐
                          │ unix:///var/run/docker.sock                                      │ tcp://…:2376（双向 TLS）
                          ▼                                                                  ▼
                     [本机 dockerd]  (local)                                            [远程 dockerd]  (w1)
```

**为什么不直接暴露 Docker API**：Docker Engine API 等价宿主 root，且会把节点上既有的基础设施容器（nginx/redis/neo4j/WeKnora…）一并暴露给客户端。本网关只放行白名单操作，并做所有权隔离。

---

## 1. 当前能力与历史变更

**当前已具备的能力**（以下带日期的条目记录能力演进，属于历史背景；当前配置以第 5 节和部署环境为准）：

> **v2 变化（2026-09-23）**：网关支持**多 Docker 节点聚合**、**容器策略/资源/镜像全配置化**、**exec SSE 流式输出**。
> 模板默认：`GATEWAY_AUTO_REMOVE=false`、`GATEWAY_READONLY_ROOTFS=true`、`GATEWAY_RUN_AS_USER=1000:1000`（代码默认分别为 `true` / `false` / 空）。`DEFAULT_MEMORY_BYTES` / `DEFAULT_TTL_SECONDS` 的模板值已在 2026-11-04 基线上调为 `1GiB` / `3600s`（见第 5 节「示例部署实际配置」）。
> 回滚开关：示例部署 `GATEWAY_AUTO_REMOVE=false`，容器退出后可查询；若运维改为 `true`，容器退出即被删除，客户端需自行保存退出码与日志。

> **2026-09-24 回滚「dockerd 远程 API」切换**：2026-09-23 曾尝试让 dockerd 直接占用 3010（`-H tcp://0.0.0.0:3010` + 双向 TLS）以替代网关，让 AgentScope 直连 Docker REST API。**已回滚**，恢复为经 container-gateway 的 HTTP API 方式：
> - 还原 `/etc/sysconfig/docker`（去掉 `-H tcp://0.0.0.0:3010`）、`/etc/docker/daemon.json`（去掉 `tlsverify` 等 TLS 项），重启 docker 释放 3010。
> - 重新 `enable` + `start` container-gateway，3010 由网关持有；docker 仅保留 `unix:///var/run/docker.sock`。
> - 宿主 12 个基础设施容器经 `--live-restore` 无损；当时 `/healthz` 返回 `docker=UP`、`runningContainers=12`。
> - 遗留（未删）：备份目录 `/root/container-gateway/docker-remote-switch-20260923-145656/`、TLS 证书 `/etc/docker/tls/`；原回滚脚本已从仓库移除，如需再次回滚请按本节步骤手工还原。

> **2026-10-09 托管工作区挂载**：为达成「非 root + 只读 RootFS + 文件收发 API」三者并存，新增 `GATEWAY_WORKSPACE_HOST_DIR`。原因是文件 API 依赖 Docker archive，而 archive 写不了 tmpfs、在只读 rootfs 下被直接拒绝，故 `/workspace` 必须是 daemon 可见的可写挂载。网关在本机节点该目录下为每个容器建独立子目录、chown 到 `GATEWAY_RUN_AS_USER`，rw bind 到 `/workspace`，销毁/回收时删除（远程节点在 2026-11-04 改为命名卷，见下）。启用后 `GATEWAY_TMPFS` 必须去掉 `/workspace`（否则启动 fail-fast）；目录须在 systemd `ReadWritePaths` 内。

> **2026-11-03 容量与限流整改**：`destroy` 改为直接 force remove（不再先做 10s 优雅 stop）；所有 429 响应带 `Retry-After`；`/healthz` 的托管容器计数改为 4s 缓存，新增 `/healthz/live`（不访问 Docker）与 `/healthz/ready`（不健康返回 503）；`/healthz*` 按源 IP 限流 240 次/分钟。单节点容器上限、配额与限流同步上调，最终取值见第 5 节「示例部署实际配置」。

> **2026-11-04 双节点与远程工作区卷**：示例部署为 `local`（`unix:///var/run/docker.sock`）+ `w1`（`tcp://…:2376`，双向 TLS）双节点；单节点上限 30、SSE 流 200、Docker 连接池 600（400 条并发 SSE 已压测通过）。工作区改为双后端：本机节点仍在 `GATEWAY_WORKSPACE_HOST_DIR` 下建宿主子目录并 bind；远程节点自动创建命名卷 `gw-ws-<uuid>`（label `com.example.gateway.workspace=true`），容器启动后以 root 执行一次 `chown -R <uid>:<gid> /workspace && chmod 770`。`WorkspaceVolumeReaper` 每 10 分钟回收「无容器挂载且创建超过 10 分钟」的孤儿卷。

| 项 | 值 |
|---|---|
| 网关安装目录 | `/opt/container-gateway`（jar + install.sh + logs/） |
| 配置与密钥 | `/etc/container-gateway.env`（0600 root:root，含 API Token） |
| systemd 单元 | `/etc/systemd/system/container-gateway.service`（已 enabled + running） |
| 监听 | `0.0.0.0:3010`（systemd `ExecStart` 的 `--server.port` 取自 env `GATEWAY_PORT`） |
| **示例公网入口** | **`http://gateway.example.com:3010`** —— 经 CLB 四层转发，**2026-09-22 实测公网已通**（其他环境可改为直连 / 反向代理 / VPN） |
| 审计日志 | `/opt/container-gateway/logs/audit.log` |
| 宿主端口池 | `30000-30999`（每节点一份，自动分配/回收 + 周期对账） |
| 多节点 | **示例部署 2 个**：`local`（`unix:///var/run/docker.sock`）+ `w1`（`tcp://…:2376`，双向 TLS），由 `GATEWAY_NODES` 配置 |
| 工作区 | 本机节点：宿主目录 bind；远程节点：命名卷 `gw-ws-<uuid>`；均挂到 `/workspace` |
| 示例部署配额 / 限流 | `MAX_CONTAINERS=MAX_PER_OWNER=1000`；`RATE_LIMIT_PER_MINUTE=60000`、`CREATE_RATE_LIMIT_PER_MINUTE=600` |
| 示例部署默认 TTL / 内存 | `3600s` / `1GiB`（`DEFAULT_TTL_SECONDS` / `DEFAULT_MEMORY_BYTES`） |
| 流式 exec | `POST /{id}/exec/stream`（SSE，逐块 stdout/stderr + 心跳 + exit 事件） |
| 源码 | 网关 `container-gateway/`，客户端 `client/` |

**接入方式**：网关通过 HTTP 入口对外提供服务，入口可为内网直连、反向代理、负载均衡 / VIP 或 VPN；按网络环境配置 TLS 与来源限制即可。接入前只需从 `/etc/container-gateway.env` 取 Token 并配置允许的来源。

---

## 2. 快速开始

取 Token：

```bash
grep '^GATEWAY_TOKENS=' /etc/container-gateway.env | cut -d= -f2 | cut -d: -f1
```

健康检查（免鉴权）：

```bash
curl http://127.0.0.1:3010/healthz
# {"status":"UP","docker":"UP","nodes":[...],"healthyNodes":2,"managedContainers":0,"maxContainers":1000,
#  "nodeMaxTasks":30,"portPoolUsed":0,"portPoolCapacity":1000,"autoRemove":false,"networkMode":"bridge"}
```

创建 → 查询 → exec → 销毁：

```bash
T=<token>; B=http://127.0.0.1:3010/api/v1/containers

# 创建（返回 id 与访问地址）
curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"image":"nginx:1.27-alpine","name":"my-nginx","ports":[{"containerPort":80}],
       "memoryBytes":134217728,"cpus":0.5,"ttlSeconds":600}' $B | python3 -m json.tool

# 查询
curl -s -H "Authorization: Bearer $T" $B/<id> | python3 -m json.tool

# exec
curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"cmd":["sh","-c","echo hi; nginx -v 2>&1"]}' $B/<id>/exec | python3 -m json.tool

# 经网关代理访问容器里的 HTTP 服务（注意也要带 token）
curl -H "Authorization: Bearer $T" $B/<id>/proxy/80/

# 销毁（force remove + 删卷 + 回收宿主端口）
curl -X POST -H "Authorization: Bearer $T" $B/<id>/destroy
```

---

## 3. REST API

Base：`/api/v1/containers`。除 `/healthz*`、`/actuator/*`、`/error` 外都需要 `Authorization: Bearer <token>`。

每个响应都会带 `X-Request-Id` 响应头，排障时提供给运维；监控快照端点在 `127.0.0.1:18091`，不走下面的 Bearer Token，见第 12 节。

| 方法 路径 | 入参 | 出参 | 状态码 |
|---|---|---|---|
| `POST /` | 见下方「创建字段」 | `ContainerInfo` | 201 |
| `GET /` | — | `ContainerInfo[]`（仅当前 token 归属） | 200 |
| `GET /{id}` | — | `ContainerInfo` | 200 / 403 / 404 / 503 |
| `POST /{id}/start` | — | `{id,status}` | 200 |
| `POST /{id}/stop` | `?timeoutSeconds=10` | `{id,status}` | 200 |
| `POST /{id}/restart` | `?timeoutSeconds=10` | `{id,status}` | 200 |
| `DELETE /{id}` | `?removeVolumes=true` | 无体。**删除**：要求容器已停止，不强制 | 204 / 409 |
| `POST /{id}/destroy` | — | 无体。**销毁**：直接 force remove（SIGKILL）+ 删卷 + 回收端口；容器已消失时仍返回 204 | 204 |
| `POST /{id}/exec` | `{cmd[], timeoutSeconds?, env?}` | `{exitCode,stdout,stderr,truncated,durationMs}` | 200 / 409(超时) |
| `POST /{id}/exec/stream` | 同 exec；请求头 `Accept: text/event-stream` | `text/event-stream`：`stdout`/`stderr`/`truncated`/`exit`/`error` 事件 + 15s 心跳注释帧 | 200（错误以流内 `error` 事件给出） |
| `GET /{id}/logs` | `?tail=200&timestamps=false` | `text/plain`（stdout+stderr 合并，超限截断） | 200 |
| `POST /{id}/files/upload` | 请求体=tar；`?dest=` 可选（须在 `file-upload-dir` 下） | `{dest,fileCount,totalBytes,files[]}` | 201 / 400 / 413 |
| `GET /{id}/files/download` | `?path=`（须在 `file-output-dir` 下） | tar（`application/octet-stream`） | 200 / 404 / 413 |
| `GET /{id}/files` | `?path=`（须在 `file-output-dir` 下） | `[{name,size,type}]` | 200 / 404 / 413 |
| `ANY /{id}/proxy/{容器端口}/**` | 原样转发 | 原样返回 | 透传 |

文件收发最小示例：

```bash
tar cf - ./out | curl --data-binary @- -H "Authorization: Bearer $T" "$B/$CID/files/upload?dest=/workspace/input"
curl -H "Authorization: Bearer $T" "$B/$CID/files/download?path=/workspace/out" -o out.tar
```
| `GET /healthz` | 免鉴权 | 健康 JSON（含 `nodes[]`、`healthyNodes`、`nodeMaxTasks`、`autoRemove`、`networkMode`） | 200 |
| `GET /healthz/live` | 免鉴权 | `{status,check}`，不访问 Docker | 200 |
| `GET /healthz/ready` | 免鉴权 | 同 `/healthz`；不健康返回 503 | 200 / 503 |

### 创建字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `image` | 是 | 必须在白名单内，且本机已存在（默认禁止联网 pull） |
| `cmd` | 否 | 启动命令，缺省用镜像默认 |
| `env` | 否 | `{"KEY":"VALUE"}` |
| `ports` | 否 | `[{"containerPort":80,"protocol":"tcp"}]`，最多 `MAX_PORTS_PER_CONTAINER` 个。**宿主端口由网关分配，不可指定** |
| `mounts` | 否 | `[{"hostPath":"/data/x","containerPath":"/x","readOnly":true}]`，仅在 `GATEWAY_BIND_MOUNTS_ENABLED=true` 时有效；宿主敏感路径强制拒绝 |
| `memoryBytes` | 否 | 默认取 `DEFAULT_MEMORY_BYTES`（示例部署 1GiB，代码默认 256MiB），上限 `MAX_MEMORY_BYTES`，下限 `MIN_MEMORY_BYTES` |
| `cpus` | 否 | 默认取 `DEFAULT_CPUS`（1.0），上限 `MAX_CPUS` |
| `pidsLimit` | 否 | 默认取 `DEFAULT_PIDS`（256），上限 `MAX_PIDS` |
| `ttlSeconds` | 否 | 默认取 `DEFAULT_TTL_SECONDS`（示例部署 3600s，代码默认 300s），上限 `MAX_TTL_SECONDS`，下限 `MIN_TTL_SECONDS`；到期自动销毁 |
| `meta` | 否 | 业务上下文，最多 16 项；key 匹配 `[a-zA-Z0-9_.-]{1,32}`，value ≤256 字符；落 label `com.example.gateway.meta.<key>` 并在 `ContainerInfo.meta` 回显。`GATEWAY_REQUIRE_BUSINESS_CONTEXT=true` 时必填 `tenantId` 与 `userId` |
| `name` | 否 | 缺省自动生成 `gw-<clientId>-<8位随机>` |

创建接口另支持可选请求头 `Idempotency-Key`（≤128 字符，默认保留 600s）：同一 clientId + key 重放返回既有容器（201）；同 key 不同参数返回 409 `IDEMPOTENCY_KEY_REUSED`。

### ContainerInfo

```json
{
  "id": "...", "name": "my-nginx", "status": "running", "image": "nginx:1.27-alpine",
  "createdAt": "2026-09-22T08:39:04.476Z", "expiresAt": "2026-09-22T08:49:04.476Z",
  "ttlSeconds": 600, "exitCode": 0, "nodeId": "local",
  "meta": {"tenantId": "t1", "userId": "u1"},
  "endpoints": [{
    "containerPort": 80, "protocol": "tcp", "hostPort": 30000, "nodeId": "local",
    "directUrl": "http://192.0.2.43:30000/",
    "proxyUrl": "http://<网关>/api/v1/containers/<id>/proxy/80/"
  }]
}
```

`nodeId` 为容器/端口所在的 Docker 节点 id（多节点时用于定位）。`meta` 原样回显创建时提交的业务上下文。新增字段对旧客户端透明（客户端模型标注了 `ignoreUnknown`），但**建议先升级客户端**再升级服务端。

`directUrl` 需要调用方网络能路由到宿主地址；`directUrl` 为 `null` 时说明没配 `GATEWAY_DIRECT_HOST`。
`proxyUrl` 与 API 共用同一个入口，适合仅暴露单一 HTTP 入口的部署（**请求仍需带 Bearer Token**）。

### 错误体与错误码

```json
{"type":"error","error":{"code":"OWNERSHIP_DENIED","message":"...","requestId":"..."}}
```

| code | HTTP | 含义 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 参数非法 |
| `UNAUTHORIZED` | 401 | 缺失/无效 Token |
| `OWNERSHIP_DENIED` | 403 | 容器非本网关托管，或归属其他客户端 |
| `IMAGE_NOT_ALLOWED` | 403 | 镜像不在白名单，或本地不存在且未开 pull |
| `NOT_FOUND` | 404 | 容器/端口/文件路径不存在 |
| `STATE_CONFLICT` | 409 | 状态不允许（如删除运行中的容器、exec 超时） |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 同一 `Idempotency-Key` 用于不同的创建参数 |
| `PAYLOAD_TOO_LARGE` | 413 | 文件上传/下载超过大小上限 |
| `NOT_ACCEPTABLE` / `METHOD_NOT_ALLOWED` | 406 / 405 | 请求头/方法不对 |
| `RATE_LIMITED` / `QUOTA_EXCEEDED` / `PORT_EXHAUSTED` | 429 | 限流 / 配额 / 端口耗尽 |
| `DOCKER_ERROR` | 500 | Docker 操作失败 |
| `DOCKER_UNAVAILABLE` | 503 | 连不上 dockerd |
| `NODE_UNAVAILABLE` | 503 | 节点不可达、不存在或饱和；按退避重试 |

---

## 4. 安全模型

> 名词速览：`capability` 是把 root 权限拆成的小权限；`seccomp` 限制容器可调用的系统操作；`rootfs` 是容器看到的根目录；`tmpfs` 是只存在内存的临时目录。

1. **所有权隔离（最关键）**
   创建时强制写入 label：`com.example.gateway.managed=true`、`owner=<clientId>`、`node`、`createdAt`、`ttlSeconds`、`expiresAt`、`hostPorts`；业务上下文写 `meta.*`，工作区写 `workspaceKind` / `workspaceRef`（本机兼容 `workspaceHostDir`）。
   所有针对已有容器的操作先校验 label：**非 `managed=true` 一律 403**。宿主机原有的 compose 容器（nginx/redis/neo4j/WeKnora…）永远不可能被客户端操作到。
2. **鉴权**：`Authorization: Bearer <token>`，`GATEWAY_TOKENS=<token>:<clientId>[,<token>:<clientId>...]`。内存只存 SHA-256，比对用常量时间比较。
3. **镜像白名单**：前缀匹配、deny-by-default。条目不带 tag 放行整个仓库（`nginx` → `nginx:任意tag`），带 tag 精确匹配。默认 `GATEWAY_ALLOW_PULL=false`，只允许本机已有镜像。
4. **危险参数不可达（安全不变量，永不放开）**：请求体**不接受** `privileged / networkMode / pid / hostNetwork`。
   `privileged=false`、禁用 host pid/net ns、绝不挂 `/var/run/docker.sock` —— 这几项由代码写死，任何配置都改不了。
   HostConfig 其余项由 `GATEWAY_*` 策略驱动：`network-mode`、`auto-remove`、`cap-drop`/`cap-add`、`security-opts`、
   `seccomp-profile`、`readonly-rootfs`、`tmpfs`、`bind-mounts-enabled`。
   默认丢弃 28 个具逃逸/提权价值的 capability（`SYS_ADMIN`、`SYS_PTRACE`、`NET_ADMIN`、`NET_RAW`、`SYS_MODULE`…）；
   **保留 Docker 默认能力集**再丢危险项，而不是 `capDrop=ALL` —— 全丢会导致 nginx 之类镜像缺 `CAP_CHOWN`/`CAP_NET_BIND_SERVICE` 起不来（已实测）。
   `cap-add` 对危险能力有强制黑名单；`bind-mounts-enabled=true` 时仍拒绝 `docker.sock` 与 `/proc`、`/sys`、`/dev`、`/etc`、`/root`、`/var/lib/docker` 等宿主敏感路径。
5. **资源限额**：memory ≤ 4GiB 且禁用 swap（`memorySwap == memory`）、`nanoCpus` ≤ 4、`pidsLimit` ≤ 512。
6. **配额与限流**：单 clientId 最多 `MAX_PER_OWNER` 个容器，全局 `MAX_CONTAINERS`；按 clientId 令牌桶 `RATE_LIMIT_PER_MINUTE`，创建类另有 `CREATE_RATE_LIMIT_PER_MINUTE`。
7. **端口池**：只从 `30000-30999` 分配，避开宿主已用端口与 opensandbox 声明的 `40000-60000`。启动时从「全部容器已映射的宿主端口」重建占用表，重启不丢状态。
8. **审计**：每次生命周期操作写 `logs/audit.log`（JSON Lines，含 `ts/requestId/clientId/sourceIp/action/containerId/image/hostPorts/result/durationMs`）。代理请求量大，不写审计。
9. **传输安全**：网关本体提供 HTTP + Token；生产环境建议由反向代理或负载均衡提供 HTTPS，并在入口侧限制来源。
10. **进程加固**：systemd `ProtectSystem=strict` + `ReadWritePaths=<logs> <GATEWAY_WORKSPACE_HOST_DIR>` + `PrivateTmp=true`。`connect()` 到 docker.sock 属 socket 连接而非文件写，不受只读挂载影响（已实测）。

---

## 5. 配置项（`/etc/container-gateway.env`）

改动后执行 `systemctl restart container-gateway`。

> 下表「默认」指**代码内默认**（`src/main/resources/application.yml`）。`install.sh` 生成的新 env 取模板/基线值，与代码默认不同的条目写成「代码 X / 模板 Y」。**示例部署实际取值见本节「示例部署实际配置」**（2026-11-16 核对）。

### 节点（多节点聚合）

| 变量 | 默认 | 说明 |
|---|---|---|
| `GATEWAY_NODES` | 空（回退单节点） | 逗号分隔；每项 `id=<dockerUri>[;k=v...]`。选项：`proxyHost`、`tlsVerify`、`certPath`、`enabled`。例：`local=unix:///var/run/docker.sock,w1=tcp://192.0.2.11:2376;tlsVerify=true;certPath=/etc/container-gateway/tls/w1;proxyHost=192.0.2.11` |
| `GATEWAY_DOCKER_HOST` | `unix:///var/run/docker.sock` | 兼容旧配置；未配 `GATEWAY_NODES` 时作为唯一节点 `local` |
| `GATEWAY_TLS_CERT_PATH` / `GATEWAY_TLS_VERIFY` | 空 / `false` | 远程 tcp 节点默认证书目录与是否校验 |
| `GATEWAY_NODE_HEALTH_INTERVAL_MS` | `5000` | 节点健康探测间隔 |
| `GATEWAY_NODE_PROBE_TIMEOUT_MS` | `3000` | 单次健康探测的响应超时（毫秒） |
| `GATEWAY_NODE_MAX_TASKS` | `30` | **单节点运行中托管容器上限**（达上限的节点不再被选） |
| `GATEWAY_MAX_STREAMS_PER_NODE` | `50` | 单节点最大 SSE 流数（生产基线 200） |
| `GATEWAY_DOCKER_MAX_CONNECTIONS` | `100` | 每节点 Docker 连接池上限（生产基线 600）；提高流上限时按「流数 x 3」同步提高 |

> 健康检查：`/healthz/live` 不访问 Docker（存活）；`/healthz/ready` 使用 4s 缓存的托管容器计数，不健康返回 503；旧 `/healthz` 路径与响应字段保持兼容，内部同样走缓存。

### 通用

| 变量 | 默认 | 说明 |
|---|---|---|
| `GATEWAY_HOST` / `GATEWAY_PORT` | 代码 `0.0.0.0` / `18090`；模板 `3010` | 监听地址与端口（示例部署 3010） |
| `GATEWAY_TOKENS` | 安装时随机生成 | `<token>:<clientId>[,...]` |
| `GATEWAY_IMAGE_WHITELIST` | 代码空 / 模板 7 个镜像（nginx、debian、temurin、alpine + 3 个 `sandbox-runtime` tag） | 逗号分隔；支持 `repo`、`repo:tag`、`repo@sha256:...`。模板值见「示例部署实际配置」 |
| `GATEWAY_ALLOW_PULL` | `false` | 是否允许联网拉镜像 |
| `GATEWAY_PREPULL_IMAGES` | 代码空 / 模板 `sandbox-runtime:2026.10,2026.1021,2026.1104` | 启动期在各健康节点预拉的基础镜像 |
| `PORT_RANGE_START` / `PORT_RANGE_END` | `30000` / `30999` | 宿主端口池（每节点一份；多节点须是不同宿主） |
| `MAX_CONTAINERS` / `MAX_PER_OWNER` | 代码 `60` / `30`；模板（基线）`1000` / `1000` | 配额（全局 / 单 clientId） |
| `DEFAULT_TTL_SECONDS` / `MAX_TTL_SECONDS` / `MIN_TTL_SECONDS` | 代码 `300` / `86400` / `30`；模板 `3600` / `86400` / `30` | TTL |
| `DEFAULT_MEMORY_BYTES` / `MAX_MEMORY_BYTES` / `MIN_MEMORY_BYTES` | 代码 `268435456` / `4294967296` / `16777216`；模板默认 `1073741824` | 内存 |
| `DEFAULT_CPUS` / `MAX_CPUS` | `1.0` / `4` | CPU |
| `DEFAULT_PIDS` / `MAX_PIDS` | `256` / `512` | 进程数 |
| `MAX_PORTS_PER_CONTAINER` | `16` | 单容器端口映射上限 |
| `RATE_LIMIT_PER_MINUTE` / `CREATE_RATE_LIMIT_PER_MINUTE` | 代码 `2000` / `60`；模板（基线）`60000` / `600` | 限流（429 响应带 `Retry-After`） |
| `HEALTH_RATE_LIMIT_PER_MINUTE` | `240` | 健康检查按源 IP 限流（`/healthz*`；429 带 `Retry-After`） |
| `EXEC_DEFAULT_TIMEOUT_SECONDS` / `EXEC_MAX_TIMEOUT_SECONDS` / `EXEC_MAX_OUTPUT_BYTES` | `30` / `600` / `65536` | exec（含流式） |
| `GATEWAY_STREAM_HEARTBEAT_MS` | `15000` | SSE 心跳间隔 |
| `GATEWAY_PROXY_MAX_BODY_BYTES` / `GATEWAY_PROXY_TIMEOUT_SECONDS` | `8388608` / `120` | 容器访问代理 |
| `TTL_SCAN_INTERVAL_MS` | `60000` | TTL 扫描与索引重建周期；端口池对账由 `NodePortAllocator` 共用同一周期（新建端口有 120s grace，避免误回收） |
| `GATEWAY_AUDIT_LOG` | `/opt/container-gateway/logs/audit.log` | 审计日志路径 |
| `GATEWAY_EXPORTER_SNAPSHOT_PORT` | `18091` | exporter 只读快照端口，仅绑 `127.0.0.1`，`0`=关闭；详见第 12 节 |
| `GATEWAY_PROXY_ENABLED` | `true` | 容器访问代理开关 |
| `GATEWAY_ADVERTISE_HOST` / `GATEWAY_ADVERTISE_SCHEME` | 空 / `http` | 对外通告地址与协议；空则从请求 `Host` 头推导 |
| `GATEWAY_DIRECT_HOST` | 空 | 宿主内网 IP，用于额外返回 `directUrl`；远程节点用其 `proxyHost` |
| `GATEWAY_FILE_TRANSFER_ENABLED` | `true` | 文件收发 API 开关（关闭后相关端点返回 404） |
| `GATEWAY_FILE_ROOT` / `GATEWAY_FILE_UPLOAD_DIR` / `GATEWAY_FILE_OUTPUT_DIR` | `/workspace` / `/workspace/input` / `/workspace/out` | 文件根、上传目标目录、产物目录（上传只能写 upload 目录，下载/列举只能读 output 目录，且都在 root 下） |
| `GATEWAY_FILE_MAX_BYTES` / `GATEWAY_FILE_MAX_TOTAL_BYTES` / `GATEWAY_FILE_MAX_COUNT` | 代码 `33554432` / `67108864` / `64`；模板（示例部署）`104857600` / `209715200` / `512` | 单文件 / 单请求总字节 / tar 条目数上限（100MiB / 200MiB / 512 条） |
| `GATEWAY_CREATE_IDEMPOTENCY_ENABLED` / `GATEWAY_CREATE_IDEMPOTENCY_TTL_SECONDS` | `true` / `600` | 创建幂等开关与保留时长 |
| `GATEWAY_REQUIRE_BUSINESS_CONTEXT` | `false` | 开启后创建必须带 `meta.tenantId` 与 `meta.userId` |

### 容量基线（2026-11-04 压测结论）

上表的**代码默认**是保守值；压测标定后的生产取值单独维护在 `config/gateway.env.recommended`（同目录入库），`install.sh` 首次生成 env 时取这些值，之后每次安装/刷新会逐项比对 `/etc/container-gateway.env`，**不一致只 warn、不覆盖**。

| 变量 | 基线 | 说明 |
|---|---|---|
| `GATEWAY_NODE_MAX_TASKS` | `30` | 单节点运行中托管容器上限（8C/31G 标定） |
| `GATEWAY_MAX_STREAMS_PER_NODE` | `200` | 单节点 SSE 流上限 |
| `GATEWAY_DOCKER_MAX_CONNECTIONS` | `600` | 每节点连接池（200 流占用 200 条，其余留给普通 API） |
| `MAX_CONTAINERS` / `MAX_PER_OWNER` | `1000` / `1000` | 配额（全局 / 单 clientId） |
| `RATE_LIMIT_PER_MINUTE` / `CREATE_RATE_LIMIT_PER_MINUTE` | `60000` / `600` | 限流 |

> 基线只针对容量与限流，不含 Token、节点地址等站点/密钥相关项。节点规格明显不同（CPU 更少、dockerd 更弱）时不要照搬，评估后改基线文件。
> 老环境升级到本版本后，下次执行 `install.sh` 会打印偏离项；按提示手工调整 `/etc/container-gateway.env` 再 restart 即可。

### 示例部署实际配置（2026-11-16 核对 `/etc/container-gateway.env`）

| 项 | 示例部署值 |
|---|---|
| Docker 节点 | `local`（`unix:///var/run/docker.sock`）+ `w1`（`tcp://…:2376`，`tlsVerify=true`，`proxyHost`=w1 内网 IP） |
| 单节点上限 / SSE 流 / 连接池 | `30` / `200` / `600` |
| `MAX_CONTAINERS` / `MAX_PER_OWNER` | `1000` / `1000` |
| 全接口 / 创建 / 健康检查限流 | `60000` / `600` / `240` 次每分钟 |
| 默认 / 最大 / 最小 TTL | `3600` / `86400` / `30` 秒 |
| 默认 / 最大内存 | `1073741824`（1GiB）/ `4294967296`（4GiB） |
| 默认 / 最大 CPU、PID | `1.0` / `4`；`256` / `512` |
| `GATEWAY_AUTO_REMOVE` / `GATEWAY_READONLY_ROOTFS` / `GATEWAY_RUN_AS_USER` | `false` / `true` / `1000:1000` |
| 工作区 | `GATEWAY_WORKSPACE_HOST_DIR=/var/lib/container-gateway/workspaces`（本机 bind / 远程命名卷） |
| `GATEWAY_TMPFS` | `/tmp:rw,size=32m,uid=1000,gid=1000,mode=1777` |
| 镜像白名单 | `nginx:1.27-alpine`、`debian:12-slim`、`eclipse-temurin:21-jre`、`alpine:3.20`、`sandbox-runtime:2026.10` / `2026.1021` / `2026.1104` |
| 预拉镜像 | `sandbox-runtime:2026.10`、`2026.1021`、`2026.1104` |
| 允许联网 pull | `false` |
| 文件收发 | 开启；根/上传/产物目录 `/workspace`、`/workspace/input`、`/workspace/out`；上限 100MiB / 200MiB / 512 条 |
| 创建幂等 | 开启，保留 600s |
| 强制业务上下文 | 否 |

> **容量提醒**：示例部署默认内存 1GiB，而单节点上限 30，若 30 个容器都不显式传 `memoryBytes`，内存配额合计约 30GiB，已接近 8C/31G 节点的可用内存。客户端应显式声明内存；运维扩容前先核对节点 `MemAvailable`。远程节点的工作区命名卷占用该节点本地磁盘，随 destroy/TTL 删除。

**文件收发的容量口径**（2026-11-16 用 `sandbox-runtime:2026.1104` 实测）：

- 容器根文件系统只读、`/tmp` 是 32MiB tmpfs，上传/产物**只落 `/workspace`**：本机节点是宿主 bind（实测与 `/` 同一块 40G 盘，可用约 23G），远程节点是命名卷（w1 实测 xfs 300G、可用 186G，挂载为 `noquota`）。
- 按单包 200MiB 估算上限：本机节点约 117 包、w1 约 950 包。单次 200MiB 上传本身毫无压力，风险在累积。
- 网关**没有每容器磁盘配额**，`MAX_CONTAINERS=1000` 与磁盘容量脱钩；xfs 是 `noquota`，也无法用 `storage-opt size=` 限制。打满节点盘会让该节点上所有容器一起失败，只能靠 TTL/销毁回收 + 磁盘水位监控。
- 网关把整包读进内存（单请求峰值约为包大小的 3~4 倍），单个 196MiB 上传实测 RSS 增量约 690MiB。大包上传并发建议不超过 4~6，或直接串行。

### 容器策略（HostConfig）

| 变量 | 默认 | 说明 |
|---|---|---|
| `GATEWAY_NETWORK_MODE` | `bridge` | `none` / `bridge` / 自定义网络名 |
| `GATEWAY_AUTO_REMOVE` | 代码 `true` / 模板 `false`（示例部署 false） | 容器退出后保留（可事后查询退出码/日志）；由 TTL 回收 + 显式 destroy 释放。设为 `true` 则退出即删 |
| `GATEWAY_CAP_DROP` | 空 | 额外丢弃的能力；空=内置危险清单；支持 `ALL` |
| `GATEWAY_CAP_ADD` | 空 | 额外添加的能力；危险能力被强制忽略 |
| `GATEWAY_SECURITY_OPTS` | `no-new-privileges:true` | securityOpt，逗号分隔 |
| `GATEWAY_SECCOMP_PROFILE` | 空 | 空 / `runtime/default` / `unconfined` / 各节点上存在的路径 |
| `GATEWAY_READONLY_ROOTFS` | 代码 `false` / 模板 `true`（示例部署 true） | 只读根文件系统（配合 tmpfs / 托管工作区提供可写目录） |
| `GATEWAY_RUN_AS_USER` | 代码空 / 模板 `1000:1000`（示例部署） | 容器运行用户 `user[:group]`；留空=镜像默认用户（多为 root） |
| `GATEWAY_WORKSPACE_HOST_DIR` | 代码空（关闭）/ 模板 `/var/lib/container-gateway/workspaces` | 网关托管工作区：启用后每容器独立工作区挂到 `/workspace`。**本机节点**建宿主子目录并 chown 到 `GATEWAY_RUN_AS_USER`、rw bind；**远程节点**自动创建命名卷 `gw-ws-<uuid>`（label `com.example.gateway.workspace=true`），容器启动后以 root 执行一次 `chown -R <uid>:<gid> /workspace && chmod 770`。容器 destroy/TTL 时清理，孤儿卷由 `WorkspaceVolumeReaper` 每 10 分钟回收（无容器挂载且创建超过 10 分钟）。启用后 tmpfs 不得含 `/workspace`；宿主目录须在 systemd `ReadWritePaths` 内（install.sh 自动写入） |
| `GATEWAY_TMPFS` | `/tmp:…` | 条目用 `;` 分隔，条目内 `路径:选项,选项`（选项可含逗号）。tmpfs 计入容器内存 cgroup；**不要放 `/workspace`**（改用 `GATEWAY_WORKSPACE_HOST_DIR`） |
| `GATEWAY_BIND_MOUNTS_ENABLED` | `false` | 是否允许客户端声明 bind mount（仍拒绝宿主敏感路径） |

### 沙箱运行时镜像（预置工具链，避免运行时装依赖）

沙箱以「非 root + 只读 rootfs」运行，运行期 `apt/apk` 装系统包**必然失败**（无写权限），故常用工具链应在**镜像构建期**装好。仓库内 `resources/sandbox/Dockerfile` 提供一个多语言镜像（JDK17+Maven+Python3+pip/venv+Node/npm+gcc+常用工具），本地构建（无需 registry）：

```bash
docker build -f resources/sandbox/Dockerfile -t sandbox-runtime:2026.10 .
# 已放入 GATEWAY_IMAGE_WHITELIST 与 GATEWAY_PREPULL_IMAGES
systemctl restart container-gateway
```

- 新镜像 `sandbox-runtime:2026.1021`（Ubuntu 24.04 + Python3.12/常见文档处理库 + Node22 + Go1.26 + .NET10 + Playwright1.56/Chromium）已放行；原使用手册已移除，重建方式见 `resources/sandbox/Dockerfile.2026.1021`。
- `sandbox-runtime:2026.1104` 已加入白名单与预拉（见「示例部署实际配置」）；使用前确认目标节点已存在该镜像（`GATEWAY_ALLOW_PULL=false`）。
- 创建容器时按需选用 `sandbox-runtime:2026.10` / `2026.1021` / `2026.1104`，运行时零安装。
- 镜像内置 `HOME=/workspace`、`TMPDIR=/workspace`，且 apt/pip/npm/maven 均指向国内镜像（腾讯云/npmmirror）；`~/.m2`、`~/.npm`、`~/.cache`、venv、编译临时文件全部落在可写 `/workspace`。
- 需装“项目自己的依赖”时，装进 `/workspace`：`python3 -m venv /workspace/.venv`、`mvn -Dmaven.repo.local=/workspace/.m2`、`npm --prefix /workspace ...`。
- 构建坑（已规避）：节点无 IPv6 而 Debian 源 DNS 返回 AAAA → 需 `Acquire::ForceIPv4`；`deb.debian.org` 国内拉取仅 ~30KB/s → 换腾讯云源。`TMPDIR` 必须在 `/workspace` 建好后再设，否则 apt postinst 的 `mktemp` 失败。

### 常用运维

```bash
# 改 Token（下发新 token 给客户端）
vi /etc/container-gateway.env      # 修改 GATEWAY_TOKENS
systemctl restart container-gateway

# 放行新镜像（需先在宿主机拉好）
docker pull alpine:3.20
vi /etc/container-gateway.env      # 追加 GATEWAY_IMAGE_WHITELIST
systemctl restart container-gateway

# 看审计
tail -f /opt/container-gateway/logs/audit.log | python3 -m json.tool --json-lines

# 看服务日志
journalctl -u container-gateway -f

# 升级（jar 会自动备份为 .bak-<时间戳>）
cd <repo-root> && mvn -q clean package
GATEWAY_PORT=3010 JAR_SRC=target/container-gateway.jar ./scripts/install-gateway.sh --restart
```

---

## 6. 构建

```bash
# 网关
cd <repo-root> && mvn -q clean package
# 产物 target/container-gateway.jar

# 客户端库
cd client && mvn -q clean package
# target/container-gateway-client.jar        正常依赖包（引入业务工程）
# target/container-gateway-client-1.0.0-all.jar  含依赖，可直接 java -jar 跑 Demo
```

技术栈：Java 21（temurin-21）+ Spring Boot 4.0.8（MVC，虚拟线程）+ `docker-java 3.4.1`（`docker-java-core` + `docker-java-transport-zerodep`，unix socket 支持在 zerodep transport 里）。
注意 Spring Boot 4 用的是 **Jackson 3**（`tools.jackson.*`），网关内部即按 Jackson 3 编写；客户端库为兼容性用 Jackson 2，两者可共存。
2026-10-16 漏洞修复后 `pom.xml` 固定：`jackson-databind 2.21.5`、`commons-io 2.14.0`、`commons-compress 1.26.0`、`guava 32.0.0-jre`、`bcprov/bcpkix/bcutil 1.85`。`commons-compress 1.26.0` 把 `commons-codec` 声明为 optional 但其 tar 写入路径要用到，故显式引入 `commons-codec 1.16.1`，否则文件上传会 `NoClassDefFoundError`。

---

## 7. 端口选择与网络入口

### 7.1 为什么用 3010（复用而不是新申请）

示例环境选用 3010：该 CVM **没有公网 IP**（元数据 `public-ipv4` 为空，出网看到的 `192.0.2.153` 是 VPC NAT 出口），`gateway.example.com` 是 **CLB 的 VIP**。网关本身不要求必须经 CLB，内网可直接监听宿主地址，公网场景也可经反向代理或负载均衡暴露；复用 3010 是为了沿用已放行端口，避免额外端口申请。

既有的 12 个已放行端口逐个核对结果：

| 端口 | 宿主机状态 | 原归属 | 处置 |
|---|---|---|---|
| 443 | 已占用 | voicelab-tls（docker-proxy） | — |
| 80 | 已占用 | WeKnora-frontend（docker-proxy） | — |
| 8082 | 已占用 | WeKnora-mcp（docker-proxy） | — |
| 8083 | 已占用 | voicelab-tls（docker-proxy） | — |
| 3000 | 空闲 | cube-api（E2B REST API） | 避开：AgentScope 应用配置指向 `127.0.0.1:3000` |
| 8089 | 空闲 | cubemaster 控制面 | 备选 |
| **3010** | **空闲** | **cubeops 运维服务** | **✅ 本网关采用** |
| 12088 | 空闲 | cube-webui 控制台 | 备选 |
| 9966 | 空闲 | cubelet 节点代理 | 备选 |
| 9998 | 空闲 | cubelet 节点代理 | 备选 |
| 9999 | 空闲 | cubelet 节点代理 gRPC | 备选 |
| 53 | 空闲 | DNS | 不用：语义冲突，且是 DNS 放大风险面 |

**取用 3010 的代价**：3010 原本留给 CubeSandbox 的 cubeops 运维服务。CubeSandbox 的 systemd 单元当前全部 inactive/dead，因此当前无冲突；**若将来重启 CubeSandbox，cubeops 会因端口被占而启动失败**，届时需二选一（换回 18090 或给 cubeops 另分配端口）。

### 7.2 CLB 侧配置（已生效）

现有 CLB：`lb-gateway-example`，公网 VIP `gateway.example.com`。

| 项 | 值 | 状态 |
|---|---|---|
| 四层 TCP 监听 | `3010 → 后端 192.0.2.43:3010` | ✅ 已生效 |
| 公网连通性 | `curl http://gateway.example.com:3010/healthz` → 200 | ✅ 2026-09-22 实测已通 |
| 健康检查 | 建议 HTTP / 端口 `3010` / 路径 `/healthz` / 期望 200 | 按需配置 |
| 来源限制 | 按部署环境配置（如 CLB 访问控制、云安全组、防火墙或 VPN 策略） | 沿用既有 |

**说明**：3010 是示例环境已放行端口，因此无需新的端口申请；实际部署可按网络环境选择直连、代理或负载均衡入口，并配置相应的 TLS 与来源限制。

**客户端侧自检**（在可访问网关的客户端机器上执行）：

```bash
# 1. 免鉴权健康检查，应先通
curl -s http://gateway.example.com:3010/healthz

# 2. 取 Token（在网关机器上执行）
grep '^GATEWAY_TOKENS=' /etc/container-gateway.env | cut -d= -f2 | cut -d: -f1

# 3. 带 Token 调接口
T=<token>
curl -s -H "Authorization: Bearer $T" http://gateway.example.com:3010/api/v1/containers
```

> **容器端口怎么对外**：`30000-30999` 这 1000 个宿主端口不必在入口侧逐个开放。通常统一用返回的 `proxyUrl`（`http://gateway.example.com:3010/api/v1/containers/<id>/proxy/<端口>/`），与 API 共用同一 HTTP 入口。`proxyUrl` 中的主机名按请求的 `Host` 头推导，经 CLB / 反向代理访问时会自动变为对应 VIP / 域名；只有调用方网络确实能直连宿主地址时，才配置 `GATEWAY_DIRECT_HOST` 使用 `directUrl`。

---

## 8. Java 客户端

依赖（若引入自己的工程）：

```xml
<dependency>
  <groupId>com.example.gateway</groupId>
  <artifactId>container-gateway-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

用法：

```java
try (var gw = new ContainerGatewayClient("http://gateway.example.com:3010", token)) {

    ContainerInfo c = gw.create(CreateContainerRequest.builder("nginx:1.27-alpine")
            .port(80)
            .memoryMb(256)          // 默认 1024
            .cpus(0.5)              // 默认 1.0
            .ttlSeconds(600)        // 默认 3600
            .name("my-nginx")       // 可空
            .build());
    System.out.println(c.id());
    c.endpoints().forEach(e -> System.out.println(e.proxyUrl()));

    ExecResult r = gw.exec(c.id(), ExecRequest.of("sh", "-c", "echo hi").withTimeout(30));
    System.out.println(r.exitCode() + " / " + r.stdout());

    byte[] html = gw.proxyGet(c.id(), 80, "/");     // 经网关取容器 HTTP 服务
    String logs = gw.logs(c.id(), 200);             // 日志

    gw.stop(c.id());                                // 停止
    gw.remove(c.id());                              // 删除（软删，需已停止）
    gw.destroy(c.id());                             // 销毁（force remove + 删卷 + 回收端口）
}
```

方法与网关端点一一对应：`create / get / list / start / stop / restart / remove / destroy / exec / logs / health / proxyUrl / proxyGet`。
（`start/stop/restart` 返回 `StatusView(id,status)`，与网关 `{id,status}` 一致。）
错误统一抛 `ContainerGatewayException`，可用 `getCode()` 分支处理。

跑 Demo（端到端自检）：

```bash
java -jar container-gateway-client-1.0.0-all.jar http://127.0.0.1:3010 <token>
```

---

## 9. 已知限制与后续建议

1. **传输明文**：一/二期为 HTTP + Token。上生产建议加 TLS —— 在网关前端加 nginx/复用 CLB 证书做 TLS 终止，或网关内置自签证书（同时把 `GATEWAY_ADVERTISE_SCHEME` 改成 `https`）。
2. **CLB 上容器端口不直达**：见第 7 节，统一走代理路径。
3. **镜像按 tag 白名单**：恶意同名 tag 仍可通过。加固建议：白名单支持 `image@sha256:...` digest 固定，或在宿主机侧只允许从内部 registry 拉取。
4. **exec 流式已支持**：`POST /{id}/exec/stream`（SSE）逐块推送 stdout/stderr，带 15s 心跳与 `exit` 终态；超时/超限分别以 `EXEC_TIMEOUT` / `truncated` 事件收尾。`GET /{id}/logs?follow=true` 的 SSE 日志仍未实现。
5. **多节点注意**：多节点的端口池使用同一个 `PORT_RANGE_START/END`，因此**每个节点必须是不同宿主**；同一宿主上跑多个 dockerd 不在支持范围。远程 tcp 节点需在节点侧开放 2376 并放行网关→节点的端口段（用于代理直连）。工作区在远程节点上是命名卷 `gw-ws-*`，占用该节点本地磁盘，destroy/TTL 时删除。
6. **节点不可达时的 not-found**：若存在不可达节点，网关无法区分「容器不存在」与「容器在不可达节点上」，此时按容器 id 查询会返回 `503 NODE_UNAVAILABLE`（可重试）；全部节点健康时正常返回 404。
7. **单点（控制面）**：网关仍是单实例。systemd `Restart=on-failure` 可自愈；如需高可用可上双实例 + CLB 多后端（注意端口池需按实例分段）。
8. **AutoRemove 语义**：示例部署 `GATEWAY_AUTO_REMOVE=false`（代码默认 `true`）。关闭时容器退出后保留，可事后 `GET` 查退出码、读日志，再由 `destroy` 或 TTL 回收；显式 `destroy` 幂等返回 204。设为 `true` 则容器退出即消失（随后 `destroy` 对已知容器按 204/404 双重确认）。端口由对账扫描（`TTL_SCAN_INTERVAL_MS`，grace 120s）回收。
9. **无 CORS**：浏览器直连会被同源策略拦；如需 Web 前端接入需额外加 CORS 配置。
10. **默认内存与节点容量**：示例部署默认内存 1GiB、单节点上限 30；若客户端不显式设置 `memoryBytes`，30 个容器合计约 30GiB，接近 8C/31G 节点的可用内存。客户端应显式声明内存，运维按节点 `MemAvailable` 评估扩容。

---

## 10. 变更记录与验证清单

### 10.1 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-22 | 网关开发完成并部署：Spring Boot 4.0.8 + docker-java 3.4.1，`/opt/container-gateway`，systemd 单元 enabled + running |
| 2026-09-22 | 本机全链路冒烟测试通过（见 10.2） |
| 2026-09-22 | 修复 4 个实测缺陷：`capDrop=ALL` 导致镜像起不来、停机后端口映射读不到、端口池 TIME_WAIT 永久占位、406 被误报为 500 |
| 2026-09-22 | 监听端口由 `18090` 改为**复用既有已放行端口 `3010`**（原 cubeops 预留口），避免新增端口申请；systemd 单元与 env 同步更新 |
| 2026-09-22 | **公网验证通过**：经 CLB `gateway.example.com:3010` 已可访问 |
| 2026-09-23 | **v2**：新增多 Docker 节点聚合（`GATEWAY_NODES`，unix/tcp+TLS）、节点健康心跳与按负载选路、容器→节点索引路由、每节点端口池 + 对账回收 |
| 2026-09-23 | **v2**：容器策略/资源/镜像全配置化（network-mode/autoRemove/cap/seccomp/tmpfs/bind-mounts、默认与上下限、`repo@sha256:` 白名单、启动预拉） |
| 2026-09-23 | **v2**：新增 SSE 流式 exec `POST /{id}/exec/stream`；客户端新增 `execStream`；`ContainerInfo`/`Endpoint` 增加 `nodeId` |
| 2026-09-23 | **v2**：默认值对齐文档（AutoRemove=true、256MB、TTL 300s）；修复 AutoRemove 与显式 destroy 竞态导致的 409 |
| 2026-09-23 | 尝试「dockerd 远程 API + 双向 TLS 占用 3010」替代网关（期间网关停用）；备份于 `/root/container-gateway/docker-remote-switch-20260923-145656/` |
| 2026-09-24 | **回滚 dockerd 远程 API 切换，恢复网关 HTTP API 方式**：还原 `sysconfig/docker` 与 `daemon.json`，重启 docker 释放 3010，重启用 container-gateway（3010=网关，docker 仅 unix socket）。宿主 12 容器经 live-restore 无损；TLS 证书保留于 `/etc/docker/tls/` |
| 2026-09-29 | **阶段 B**：文件收发 API（tar 上传/下载/列举）、创建幂等（`Idempotency-Key`）与 `meta` 业务上下文 |
| 2026-10-09 | 新增 `GATEWAY_WORKSPACE_HOST_DIR` 托管工作区，解决「非 root + 只读 rootfs + 文件 API」并存；`sandbox-runtime:2026.10` 本地构建并放行 |
| 2026-10-10 | 阿里云 ECS（Rocky Linux 9.5）同构部署记录（见第 11 节） |
| 2026-10-16 | 依赖漏洞修复：Spring Boot 4.0.8、Jackson 2.21.5、commons-io 2.14.0、commons-compress 1.26.0、guava 32.0.0-jre、BouncyCastle 1.85；显式引入 commons-codec 1.16.1 |
| 2026-11-03 | 容量/限流整改：`destroy` 直接 force remove；429 带 `Retry-After`；`/healthz` 增加 4s 缓存与 `/healthz/live`、`/healthz/ready`；`/healthz*` 按源 IP 限流 |
| 2026-11-04 | 双节点（local + w1）；工作区双后端（本机 bind / 远程命名卷 + 孤儿卷回收）；压测基线入库并接入 `install.sh` 漂移告警 |
| 2026-11-05 | 文档按示例部署配置核对更新（默认 TTL/内存、配额/限流、文件 API、工作区双后端、健康探针） |
| 2026-11-12 | 新增监控：网关只读快照端点 `127.0.0.1:18091`（`GATEWAY_EXPORTER_SNAPSHOT_PORT`）+ `cgw-exporter` sidecar（`18092`）；新增第 12 节 |
| 2026-11-16 | 文件收发上限调整为 100MiB / 200MiB / 512 条（含 install 模板）；补充文件 API 的容量口径（落盘位置、节点磁盘、无配额、内存放大）与 413 触发点 |

### 10.2 本机验证清单（2026-09-22 快照）

> 下表为 2026-09-22 的验证记录。11-04 的限流、节点上限、双节点与 SSE 并发验证见 `docs/reports/` 下的压测报告。

| # | 用例 | 期望 | 结果 |
|---|---|---|---|
| 1 | 无 Token / 错误 Token 访问 | 401 `UNAUTHORIZED` | ✅ |
| 2 | 越白名单镜像创建（`alpine:3.20`） | 403 `IMAGE_NOT_ALLOWED` | ✅ |
| 3 | 超限内存 / 缺 image 参数 | 400 `VALIDATION_ERROR` | ✅ |
| 4 | 创建 nginx 容器 | 201，返回 id + endpoints（hostPort 30000） | ✅ |
| 5 | 所有权 label 写入 | `com.example.gateway.managed/owner/createdAt/ttlSeconds/expiresAt/hostPorts` | ✅ |
| 6 | 宿主直连容器端口 | 200 | ✅ |
| 7 | 经网关代理访问容器（`/proxy/80/`、子路径、无尾斜杠） | 200 | ✅ |
| 8 | `GET /{id}` / `GET /` | 200，含状态与 endpoints | ✅ |
| 9 | exec 正常/非零退出码 | exitCode 0 与 7，stdout/stderr 正确分流 | ✅ |
| 10 | 日志 tail（stdout+stderr 合并） | 200，内容正确 | ✅ |
| 11 | 对宿主 `redis-8.8` 发 destroy / get | 403 `OWNERSHIP_DENIED` | ✅ |
| 12 | 不存在容器 | 404 `NOT_FOUND` | ✅ |
| 13 | 运行中软删 | 409 `STATE_CONFLICT` | ✅ |
| 14 | 停止后软删 | 204，容器消失，端口回收 | ✅ |
| 15 | 销毁（强停+强删+删卷+回收端口） | 204，端口池归零 | ✅ |
| 16 | 重复销毁 | 204（幂等）；非本 Token 或未托管按归属返回 403/404 | ✅ |
| 17 | 未映射端口代理 | 404 | ✅ |
| 18 | TTL 到期自动回收 | 容器被销毁，审计动作 `ttl-reap` | ✅ |
| 19 | 端口池释放后复用 | 可重新分配到同一端口（TIME_WAIT 过后自愈） | ✅ |
| 20 | Accept 不匹配 / 错误方法 | 406 `NOT_ACCEPTABLE` / 405 `METHOD_NOT_ALLOWED` | ✅ |
| 21 | 审计日志 | JSON Lines 落盘，含 create/destroy/ttl-reap | ✅ |
| 22 | systemd 加固 | `ProtectSystem=strict` 下可正常访问 docker.sock、写审计日志 | ✅ |
| 23 | Java 客户端 Demo 端到端 | 健康检查→创建→exec→代理→日志→列表→销毁 全部通过 | ✅ |
| 24 | 多节点（同 socket 双 nodeId）| `/healthz` 列出两节点；创建写 `nodeId=n1`；get/exec/destroy 经索引路由到正确节点 | ✅ |
| 25 | 单节点并发上限（`NODE_MAX_TASKS=1`）| 第 2 个创建返回 503 `NODE_UNAVAILABLE` | ✅ |
| 26 | 节点不可达（坏端点）| 该节点标记 DOWN，不影响健康节点；创建/查询仍走健康节点 | ✅ |
| 27 | AutoRemove：一次性容器退出消失 | 创建返回 `exitCode`；随后 `destroy` 幂等 204 | ✅ |
| 28 | 端口池对账/回收 | destroy 后 `portsUsed` 归零；周期性对账回收漏端口 | ✅ |
| 29 | 策略配置化 | 改 env 即生效；`/healthz` 回显 `autoRemove`/`networkMode` | ✅ |
| 30 | exec SSE 流式 | 分块 stdout/stderr、15s 心跳、`exit` 终态；70000B 输出触发 `truncated`；`timeoutSeconds=2` 准时 `EXEC_TIMEOUT` | ✅ |
| 31 | 客户端 `execStream` | Demo 逐块打印 stdout/stderr 并收到 exit | ✅ |
| 32 | 旧客户端兼容 | 客户端模型已加 `ignoreUnknown`，新增 `nodeId` 不破坏解析（建议先升级客户端再升级服务端） | 部分（未对旧 jar 实测） |

### 10.3 入口连通性验证（在客户端侧执行）

```bash
# 换端口后需先确认公网可达
curl -s http://gateway.example.com:3010/healthz
# {"status":"UP","docker":"UP","nodes":[...],"healthyNodes":2,"managedContainers":0,"maxContainers":1000,
#  "nodeMaxTasks":30,"portPoolUsed":0,"portPoolCapacity":1000,"autoRemove":false,"networkMode":"bridge"}
```

结果：**已通**（2026-09-22）。

### 10.4 相关文件

| 文件 | 说明 |
|---|---|
| `docs/deployment.md` | 本文档（权威记录：API 手册 / 安全模型 / 配置 / 运维 / 端口依据 / 验证清单） |
| `container-gateway/` | 仓库根目录（网关 Maven 工程 + `scripts/install-gateway.sh`） |
| `client/` | Java 客户端库 + Demo |
| `/opt/container-gateway/` | 运行目录（jar / install.sh / logs） |
| `/etc/container-gateway.env` | 配置与 Token（0600） |
| `/etc/systemd/system/container-gateway.service` | systemd 单元 |
| `/opt/container-gateway/logs/audit.log` | 审计日志 |
| `exporter/` | Prometheus exporter 工程（`install.sh` / `config/` / `docs/exporter-plan.md`） |
| `/usr/local/bin/cgw-exporter.jar` | exporter 运行 jar |
| `/etc/cgw-exporter/config.yml` | exporter 配置（监听地址、上游快照地址、全局标签） |
| `/etc/systemd/system/cgw-exporter.service` | exporter systemd 单元 |

---

## 11. 附：新环境部署记录（阿里云 ECS，2026-10-10）

在另一台阿里云 ECS（`secondary-verification-host`，内网 `192.0.2.38`，Rocky Linux 9.5，8C/30G）上完成一次同构部署，用于本机/内网验证。与原生产机（`192.0.2.43`）的差异如下。

### 11.1 环境准备

| 组件 | 版本 / 做法 |
|---|---|
| 操作系统 | Rocky Linux 9.5（systemd PID1，cgroup v2，全权限） |
| Docker Engine | `docker-ce 29.9.0`；Rocky 默认源无 docker，用阿里云镜像源 `mirrors.aliyun.com/docker-ce` 安装并 `enable --now` |
| Java | OpenJDK 21（`java-21-openjdk`）；`install.sh` 自动探测 21+（JAVA_HOME → temurin-21/java-21-openjdk → PATH），建软链非必需 |
| Maven | `maven 3.6.3`；`~/.m2/settings.xml` 配阿里云镜像 |
| 网络 | 官方 `download.docker.com` 与 Docker Hub 直连不通 → Docker 配 `registry-mirrors`（daocloud 等） |

### 11.2 镜像来源改为自建仓库

镜像统一从自建 **Harbor `registry.example.com`** 的 `dev/containergateway/` 拉取（不再本地构建沙箱镜像）：

```
registry.example.com/container-gateway/sandbox-runtime:2026.10
registry.example.com/container-gateway/sandbox-runtime:2026.1021
registry.example.com/container-gateway/sandbox-runtime:2026.1104
registry.example.com/container-gateway/eclipse-temurin:21-jre
registry.example.com/container-gateway/debian:12-slim
registry.example.com/container-gateway/nginx:1.27-alpine
registry.example.com/container-gateway/alpine:3.20
```

`docker login registry.example.com` 后 `docker pull`；为兼容既有短名用法，另 `docker tag` 出短名别名，`GATEWAY_IMAGE_WHITELIST` 同时保留全路径与短名。

### 11.3 部署与配置

```bash
mvn -B clean package -DskipTests                                     # -> target/container-gateway.jar
GATEWAY_PORT=3010 JAR_SRC=target/container-gateway.jar ./scripts/install-gateway.sh   # 生成 systemd 单元与 /etc/container-gateway.env
# 改 /etc/container-gateway.env：
#   GATEWAY_IMAGE_WHITELIST / GATEWAY_PREPULL_IMAGES -> registry.example.com/container-gateway/...
systemctl restart container-gateway
```

### 11.4 验证

- `/healthz` → `status=UP, docker=UP, healthyNodes=1, portPoolCapacity=1000`
- 端到端：create（`sandbox-runtime:2026.10`，非 root `uid=1000`）→ get → exec → 经网关代理访问容器 HTTP → destroy(204)，审计日志 JSON Lines 正常落盘。

> 注：`resources/sandbox/Dockerfile*` 默认使用镜像内的上游 apt / PyPI / Maven 源，不再内置特定厂商镜像地址；公开环境可直接本地构建。如需在特定网络加速，由运维通过基础镜像或构建环境自行配置镜像源。npm 注册表可通过 `npm_config_registry` 覆盖。

---

## 12. 监控与指标（cgw-exporter，2026-11-12）

网关内置**只读快照端点**，同机 `cgw-exporter` 把它翻译成 Prometheus 指标。形态是标准 pull：1 个网关 : 1 个 exporter，无自定时器、无本地 TSDB、不解析 `audit.log`。

```
Prometheus ──GET :18092/metrics (15s)──▶ cgw-exporter ──GET :18091/internal/exporter/snapshot──▶ container-gateway
```

| 端口 | 绑定 | 用途 | 经 CLB |
|---|---|---|---|
| 18091 | `127.0.0.1` | 网关只读快照（`GATEWAY_EXPORTER_SNAPSHOT_PORT`，0=关闭） | 否 |
| 18092 | `0.0.0.0`（默认） | exporter 的 `/metrics`、`/healthz`、`/` | 否，仅对 Prometheus 网段放行 |

### 12.1 部署顺序（重要）

1. **先升级网关**：新版本默认开启 18091，通常不用改 env；确认 `GATEWAY_EXPORTER_SNAPSHOT_PORT` 没有被设成 0。
2. **再装 exporter**：在 `exporter/` 下执行 `./install.sh`。

顺序反了不会坏：exporter 照常运行，只是 `/healthz` 返回 503、指标只剩自监控；网关升级后**下次抓取自动恢复，无需重启 exporter**。2026-11-12 起 `install.sh` 的自检也按这个语义判定（进程起来即算安装成功，上游不可用只告警）。

### 12.2 网关侧确认

```bash
grep GATEWAY_EXPORTER_SNAPSHOT_PORT /etc/container-gateway.env || echo "未配置，使用默认 18091"
systemctl restart container-gateway
curl -s http://127.0.0.1:18091/internal/exporter/snapshot | head -c 200
# {"schema":"cgw.exporter.snapshot/v1","generated_at_ms":...}
ss -lntp | grep 18091     # 应只见 127.0.0.1:18091
```

端口只绑 loopback，不经 3010、不走 TokenAuthFilter；绑定失败只打 WARN，不影响网关启动。快照内容只有计数、容量、探测状态，不含 token/clientId/容器 id。

### 12.3 安装 exporter

```bash
cd exporter
JAVA_HOME=/usr/lib/jvm/temurin-21 mvn -B package    # 产物 target/cgw-exporter-1.0.0.jar（shade，可直接 java -jar）
sudo ./install.sh                                    # 构建/安装/写 systemd/启动/自检
# 常用参数：
#   --listen 192.0.2.43:18092   监听地址（默认 0.0.0.0:18092）
#   --upstream http://127.0.0.1:18091/internal/exporter/snapshot
#   --jar /tmp/cgw-exporter-1.0.0.jar   目标机没有 Maven 时用已构建产物
#   --env prod                     全局标签 environment
#   --no-start / --force-config / --uninstall [--purge]
```

安装内容：`/usr/local/bin/cgw-exporter.jar`、`/etc/cgw-exporter/config.yml`、`/etc/systemd/system/cgw-exporter.service`（`cgw` 系统用户，`MemoryMax=128M`、`CPUQuota=20%`、`NoNewPrivileges`、`ProtectSystem=strict`）。重复执行即升级（替换 jar + 重启）。

注意两点：

- 目标机需要 **JDK 21+**（脚本会校验，`java` 版本不足直接报错）。
- 配置已存在时默认保留；改监听地址/标签用 `--force-config` 重写，或直接编辑 `/etc/cgw-exporter/config.yml` 后 `systemctl restart cgw-exporter`。

### 12.4 验证

```bash
curl -s http://127.0.0.1:18092/healthz                        # {"status":"UP"}；上游不可用时 503 + DOWN
curl -s http://127.0.0.1:18092/metrics | grep -c '^cgw_'      # 空载实测 78 条，随动作/节点增长
curl -s http://127.0.0.1:18092/metrics | grep -E 'cgw_(gateway_up|node_up|ready)'
```

若只看到 `cgw_exporter_*` + `cgw_gateway_up 0`，说明上游没通，见 12.8。

### 12.5 Prometheus 接入

```yaml
# prometheus.yml
scrape_configs:
  - job_name: cgw
    scrape_interval: 15s
    scrape_timeout: 8s
    metrics_path: /metrics
    static_configs:
      - targets: ["192.0.2.43:18092"]
        labels:
          env: "prod"
          role: "container-gateway"
```

- 片段见 `exporter/config/prometheus-scrape.yml`；**其中 targets 是示例部署 IP，换环境必须改**。
- 任务维度标签是 `task` 而不是 `job`：Prometheus 会给所有样本注入 `job="cgw"`，样本自带同名标签会被改名成 `exported_job`，所以任务类指标统一用 `task`。

### 12.6 告警

`exporter/config/alerts.yml` 提供 12 条规则：网关快照不可达、节点 DOWN、创建失败率 > 5%、节点并发/端口池/SSE 水位 > 85%、exec 超时与截断、TTL 任务停滞、审计写盘失败、exporter 抓取失败与变慢。

```yaml
rule_files:
  - /etc/prometheus/rules/cgw-alerts.yml
```

阈值按示例部署基线设（节点 30 并发、端口池 1000、exec 超时治理），调整前先核对 `config/gateway.env.recommended`。

### 12.7 指标速查

| 指标 | 说明 |
|---|---|
| `cgw_gateway_up` / `cgw_ready` / `cgw_node_up{node}` | 快照可达性 / 是否存在健康节点 / 单节点探测结果 |
| `cgw_containers_managed` / `cgw_index_containers` | 托管容器数 / 容器→节点索引条目 |
| `cgw_http_requests_total{action,result,node}`、`cgw_http_request_duration_seconds` | API 请求面；`node` 只有创建/exec 等带节点动作有值，其余为空串 |
| `cgw_docker_calls_total{op,node,result,reason}`、`cgw_docker_call_duration_seconds` | 全部 Docker 调用（传输层拦截，覆盖业务/exec/回收，探活不计入） |
| `cgw_exec_total{mode}` / `cgw_exec_timeouts_total` / `cgw_exec_output_truncated_total` | exec 结果，含 2026-11-05 修复的读超时 |
| `cgw_proxy_requests_total`、`cgw_proxy_upstream_errors_total`、`cgw_file_*` | 反向代理 / 文件收发 |
| `cgw_node_assigned` / `cgw_node_inflight` / `cgw_node_capacity`、`cgw_port_pool_used` | 容量与水位 |
| `cgw_job_runs_total{task,result}` / `cgw_job_last_success_timestamp_seconds{task}` | 后台任务：ttl_reaper、port_reconcile、workspace_volume_reaper、managed_count_refresh |
| `cgw_exporter_scrapes_total` / `cgw_exporter_snapshot_age_seconds` | exporter 自监控 |

完整清单、桶边界与基数预算见 `exporter/README.md` 和 `exporter/docs/exporter-plan.md`。

### 12.8 常见问题

| 现象 | 原因 | 处置 |
|---|---|---|
| `/healthz` 503，指标只剩自监控 | 网关未升级 / 18091 被设 0 / 网关没起 | 核对 `GATEWAY_EXPORTER_SNAPSHOT_PORT`，再 `curl 127.0.0.1:18091/internal/exporter/snapshot` |
| `/metrics` 返回 500 且提示 schema 不匹配 | 网关与 exporter 版本不一致 | 两侧升到同一版本（快照 schema 为 `cgw.exporter.snapshot/v1`） |
| `install.sh` 报「exporter 未响应」 | 18092 被占 / JDK 版本不够 / 配置语法错 | `journalctl -u cgw-exporter -n 50`、`ss -lntp \| grep 18092` |
| 没有 `cgw_http_*` 系列 | 网关刚启动，还没有业务请求 | 发一次带 Token 的 API 调用后再抓 |
| 网关重启后 `rate()` 出现坑 | 计数器归零，属预期 | 用 `increase()`；不跨重启累加（见 PLAN 风险表） |

### 12.9 已知边界

- 网关进程退出时 local/w1 的业务指标一并消失，靠 `cgw_gateway_up == 0` 兜底。
- 不含宿主 CPU/内存/磁盘、JVM、容器级资源：分别由 node_exporter、JMX Exporter、`dockerd --metrics-addr` 负责。
- 指标不含 token、clientId、容器 id、cmd、env 等高基数字段。

### 12.10 卸载

```bash
sudo ./install.sh --uninstall           # 停服务、删 unit 与 jar，保留 /etc/cgw-exporter
sudo ./install.sh --uninstall --purge   # 同时删配置与 cgw 系统用户
```

网关侧不受影响。如不再需要快照端口，把 `/etc/container-gateway.env` 的 `GATEWAY_EXPORTER_SNAPSHOT_PORT` 改成 0 并重启网关。
