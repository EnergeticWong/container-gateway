# 隔离执行方案评估与实施计划

> **文档性质**：本文是对《Docker 隔离执行与 COS 产物交付开发方案》的时点评估与实施计划，用于记录当时的判断、偏差与待决事项；它不改变任何运行配置，也不代表当前已上线能力。

> 评估对象：`docs/design/isolated-execution.md`。
> 评估依据：本仓库 `src/`（包路径 `com.example.gateway`）源码与 2026-09-29 的部署状态快照（`/etc/container-gateway.env`、`GET /healthz`）。
> 结论口径：以下结论反映 2026-09-29 的评估时点。文中凡标注“示例部署默认”的取值，均指安装脚本与配置模板在当时示例部署中的默认配置，仅用于说明评估依据；它们不是代码内置默认，也不要求其他环境照此配置。实际部署以目标环境的 `/etc/container-gateway.env` 与当前文档为准。
> 文中“建议新增文件接口”“用 X-Request-Id 做幂等键”等条目已被后续实现调整；当前契约见 [`../developer-guide.md`](../developer-guide.md)。

---

## 0. 结论速览

1. **文档整体质量高**：对 Gateway 能力的负面判断（无文件收发、create 非幂等、Token 只到 clientId、exec 输出 64 KiB 截断、AutoRemove 语义、bridge 默认网络）与源码**逐条吻合**，其"自我设限"的写法是负责任的。
2. **但文档有一条核心前提是错的**：它声称 *"Gateway 服务端代码不在本项目……服务端扩展不能仅通过修改平台配置完成"*。**实际上 Gateway 源码就在本仓库 `/root/container-gateway/src`，且网关依赖的 docker-java 3.4.1 原生具备容器文件读写能力（archive API）。因此文档所谓的"阶段 B 阻塞项"不是外部依赖，可以在本仓库内自行实现。**
3. **可实现性分级**：
   - **阶段 A（小文本隔离执行）— 今天即可落地，无需改动 Gateway。** 本机网关已在 `3010` 运行（`autoRemove=true`、`networkMode=bridge`），白名单镜像 `alpine:3.20 / nginx:1.27-alpine / debian:12-slim / eclipse-temurin:21-jre` 均已预拉。
   - **阶段 B（通用文件产物）— 本仓库可实现，中等工作量。** 给 Gateway 增加受控 file API（基于 docker-java archive），而不是等外部团队。
   - **阶段 B 还有一条"零改动"临时通道**：用现成的 `/{id}/proxy/{port}/**`（支持任意方法、8 MiB/请求）与容器内 HTTP 服务传文件。文档低估了这条路径。
4. **文档没充分暴露的两个真实安全缺口**（源码事实，非设计文字）：
   - 创建容器**没有指定运行用户**（`DockerOps.create` 无 `withUser`）→ 容器内通常以镜像默认用户运行，**多为 root**。
   - **只读 RootFS 当时默认关闭**（`gateway.readonly-rootfs=false`；现模板已默认开启）。
   两条都与文档自己列的"非 root、只读 RootFS"上线目标冲突，需要补齐。

---

## 1. 事实核查表（文档说法 vs 真实代码）

| # | 文档说法 | 代码事实 | 证据 | 判定 |
|---|---|---|---|---|
| 1 | 隔离执行工具尚未上线、不能当已具备能力 | 仓库内确实没有受控执行工具，只有容器生命周期 API | `ContainerController` 全量端点 | ✅ 准确 |
| 2 | **没有 archive/cp、stdin 上传或文件下载接口** | 控制器无任何文件收发映射；对接手册也把 `archive/cp` 列为"未提供" | `ContainerController.java:85-260`；`docs/developer-guide.md` §13 | ✅ 准确 |
| 3 | create 非幂等、不接受客户端 requestId | 无幂等键；requestId 由网关自生成，忽略客户端传入 | `TokenAuthFilter.java:48`；`ContainerService.create` 无去重 | ✅ 准确 |
| 4 | Token 只映射 clientId，无 tenant/user/session/run 隔离 | 鉴权只解析出 clientId，所有归属判断基于它 | `TokenAuthFilter.java:58-64`；`ContainerGuard.java:50-58` | ✅ 准确 |
| 5 | exec 输出单流 65536 字节、可能截断 | `exec-max-output-bytes: 65536`，`BoundedOutputStream` 超限即截断并置 `truncated` | `GatewayProperties.java:96`；`DockerOps.java:703-741` | ✅ 准确 |
| 6 | `AutoRemove=true`，一次性容器会立即消失 | 默认 true，写入 HostConfig | `application.yml:77`；`DockerOps.java:142` | ✅ 准确 |
| 7 | 网络默认 `bridge`，不能宣称已是 `none` | 默认 `bridge`；本机 `/healthz` 实测 `networkMode:"bridge"` | `application.yml:76`；实测 `/healthz` | ✅ 准确 |
| 8 | TTL 有扫描间隔、销毁晚于 `ttlSeconds` | 固定间隔扫描（60s）到点回收；**代码中未见独立 grace 参数**（文档"约 120s grace"未在实现体现） | `TtlReaper.java:35-37` | ✅ 基本准确（grace 描述偏软） |
| 9 | 配额 5 容器 / 50 全局，限流 60、创建 10，端口池 30000-30999 | 与配置一致 | `GatewayProperties.java:57-58,70-71,53-54` | ✅ 准确 |
| 10 | bind mount 由运维开关，应用不传≠服务端禁止 | `bind-mounts-enabled` 默认 false，且有宿主敏感路径黑名单 | `application.yml:84`；`DockerOps.java:104-106,616-639` | ✅ 准确 |
| 11 | 「现有 `GatewaySandbox.exec` 固定以 `sh -c` 包装命令」 | **不成立**：网关 exec 直接 `withCmd(cmd[])`，原样 argv，无 `sh -c` 包装。文档示例里的 `sh -c` 是调用方自己拼的 | `DockerOps.java:408-424,446` | ❌ 与本仓库不符（见 §4） |
| 12 | 镜像白名单默认含 nginx/debian/temurin/alpine | **代码默认是空（deny-all）**；这四个是**部署环境** `/etc/container-gateway.env` 的实际值 | `application.yml:50` vs `/etc/container-gateway.env` | ⚠️ 需区分"代码默认"与"部署值" |
| 13 | Gateway 自身不持久化容器状态 | 索引是内存缓存，真源是容器 label，周期重建 | `ContainerIndex.java:14-23` | ✅ 准确 |
| 14 | 目标含"非 root、只读 RootFS" | **当时现状：无运行用户设置（多为 root）；只读 RootFS 默认 false；现模板已调整** | `DockerOps.java:133-158`（无 `withUser`）；`application.yml:82` | ⚠️ 文档未点破的真实缺口 |
| 15 | `ArtifactStorageBackend` 是 `byte[]` SPI、COS 后端与登记组件已存在 | 平台侧组件，本仓库不可验证 | — | ⚪ 无法在此核实，按文档采信 |
| 16 | Gateway 文件交换需服务端扩展、且服务端代码不可得 | **服务端代码在本仓库，可自行扩展** | `/root/container-gateway/src`；`pom.xml` docker-java 3.4.1 | ❌ 核心前提有误 |

---

## 2. 可实现性评估

### 2.1 阶段 A（受限文本隔离执行）——**立即可实现，零 Gateway 改动**

所需能力当前全部具备：

- create（常驻命令）+ exec + destroy：`ContainerService` / `ContainerController` 已发布。
- 归属与配额、限流、审计：`ContainerGuard` / `RateLimiter` / `AuditLogger` 已就绪。
- 实测运行态：`/healthz` 返回 `status:UP, docker:UP, autoRemove:true, networkMode:bridge, portPoolCapacity:1000`。
- 镜像：`alpine:3.20`、`debian:12-slim`、`eclipse-temurin:21-jre`、`nginx:1.27-alpine` 均在宿主且已加白。

**限制（与文档一致）**：单流 64 KiB；不能读取容器内生成的文件；二进制需 base64 且应限制在 32 KiB 级别。

**唯一环境前置**：若业务要跑 Python 或文档处理，需运维把对应镜像**预拉 + 加白**（当前白名单**不含 Python**；宿主上虽有 `python:3.12-alpine`，但未加白）。

### 2.2 阶段 B（通用文件产物）——**本仓库可实现，三条路线**

| 路线 | 机制 | 改动量 | 优点 | 缺点 |
|---|---|---|---|---|
| **B1 零改动临时通道** | `ANY /{id}/proxy/{port}/**` + 容器内起 HTTP 服务，PUT 上传 / GET 下载，单请求 ≤ 8 MiB | **无** | 今天就能端到端拉通文件交付 | 需在容器内跑 HTTP 服务并映射端口；无路径/哈希校验；8 MiB/请求；语义弱 |
| **B2 受控 file API（推荐）** | 在 Gateway 内新增基于 docker-java `copyArchiveToContainerCmd` / `copyArchiveFromContainerCmd`（tar 流）的受控端点 | 中等 | 归属校验、路径白名单、大小/entry/哈希校验、审计全部在服务端；平台无需在容器内起服务 | 需开发 + 联调 + 安全测试 |
| **B3 容器内 file-agent** | 容器内常驻受控文件代理，平台经 proxy 交互 | 小~中 | 实现快 | 安全边界落在容器侧，**不宜作为信任边界**，不推荐 |

**推荐**：阶段 A 先落地；并行推进 **B2**（因为服务端就在本仓库，不必等外部）；**B1 作为过渡兜底**。

### 2.3 文档中确实成立、无法绕过的阻塞

- **COS 流式大文件**：平台 `ArtifactStorageBackend` 是 `byte[]` SPI，做不到流式；大文件必须由平台侧新增流式存储/登记契约。**文档判断正确。**
- **服务端强多租户隔离**（tenant/user/session/run）：网关 Token→身份模型只到 clientId，要达到服务端强边界必须扩展网关。**文档判断正确。**
- **create 幂等**：需网关侧新增幂等键。**文档判断正确。**

---

## 3. 推荐实施计划

### 3.1 阶段 A 落地清单（平台侧，不动 Gateway）

1. 盘点所有模型/用户可触发的宿主执行入口，默认拒绝未迁移项。
2. 实现 `IsolatedCommandExecutor`（文档 §6 规划签名）：校验 tenant/user/session/run/message → 配额 → create（`cmd=["sleep","3600"]`、短 TTL）→ exec → 输出校验 → `ArtifactStorageBackend.store` → `ArtifactFileRecorder.record` → `finally destroy`。
3. 产物规则：仅当 `truncated=false` 且长度合规才登记；stdout 为明确指定的文本；**默认关闭二进制**。
4. 异常优先级：销毁异常告警但不覆盖原始异常；无原始异常时不得静默宣称已清理。
5. 验收：工具单测（缺上下文拒绝、未授权拒绝、`truncated=true` 不登记、非 0 不重试）+ 编排集成（create 丢响应、429、超时、销毁失败）+ COS 真实链路。

### 3.2 阶段 B 设计（本仓库实现，路线 B2）

**新增端点**（放在 `/api/v1/containers/{id}` 下，复用 `ContainerController` 鉴权与归属校验）：

- `PUT  /api/v1/containers/{id}/files?path=/workspace/input/<name>`（body=原始字节，`Content-Length` 受限）
- `GET  /api/v1/containers/{id}/files?path=/workspace/out/<name>`
- （可选）`GET /api/v1/containers/{id}/files/list?dir=/workspace/out`

**改动点（文件级）**：

| 文件 | 改动 |
|---|---|
| `docker/DockerOps.java` | 新增 `copyToContainer(node, id, path, byte[])` / `copyFromContainer(node, id, path)`，封装 docker-java archive API；异常走既有 `translate` |
| `policy/FileTransferPolicy.java`（新增） | 根目录白名单（如 `/workspace`）、拒绝 `..` 与越界绝对路径、拒绝软/硬链接与非常规文件、单文件/总量/条目数上限、可选 SHA-256 |
| `web/ContainerController.java` | 新增文件端点，复用 `Caller` 与 `service.requireForExec` 式归属校验 |
| `service/ContainerService.java` | 新增 `putFile/getFile`，串 `ContainerGuard.requireOwned` + `rateLimiter` + `audit` |
| `config/GatewayProperties.java` + `application.yml` | 新增 `file-transfer-enabled`（**默认 false**）、`file-root`（默认 `/workspace`）、`file-max-bytes`、`file-max-count`、`file-read-timeout-seconds` |
| `container-gateway-client` | 新增 `putFile` / `getFile` |

**必须同时解决的配套问题**：

- **可写目录与只读 RootFS 冲突（历史问题）**：当时建议用可写 `tmpfs` 挂 `/workspace`；现方案改为托管工作区（本机目录挂载 / 远程命名卷），`GATEWAY_TMPFS` 不得包含 `/workspace`。
- **AutoRemove 竞态**：collect 必须发生在容器销毁**之前**；`destroy` 仍放 `finally`。
- **非 root**：新增 `gateway.run-as-user`（如 `65534:65534`），配合镜像内目录属主。
- **可选 create 幂等**：接受 `X-Request-Id` 作为容器 label + 短期去重表，解决文档 §4 的"create 响应丢失"问题。

**测试**：路径穿越（`../`、绝对路径）、符号链接逃逸、超限截断、跨 owner `get/put`、AutoRemove 竞态、断连与孤儿清理、`file-transfer-enabled=false` 时必须 404/403。

### 3.3 安全加固清单（缺口，建议先于阶段 B 上线）

1. `DockerOps.create` 显式设置非 root 用户（新增配置，默认取安全值）。
2. 生产开启 `GATEWAY_READONLY_ROOTFS=true`，并通过托管工作区提供可写 `/workspace`（当时建议 tmpfs）。
3. 出网策略：`bridge` 仍可出网；不可信代码应改 `GATEWAY_NETWORK_MODE=none` 或经运维验证的 egress 控制（宿主机上已存在 `cube-egress` / `openresources/sandbox/egress` 镜像，可评估）。
4. Token：每环境独立 Token，轮换；长期补 tenant 维度。生产必须 TLS（当前 `gateway-base-url` 为 HTTP 明文）。
5. 为 Python/文档处理预拉并加白**专用镜像**，禁止运行期 `pip install`。

---

## 4. 需要回写文档的偏差

1. **删除**"Gateway 服务端代码不在本项目""服务端扩展不能仅通过修改平台配置完成"——服务端在本仓库，阶段 B 可自研。
2. 修正"现有 exec 固定以 `sh -c` 包装"——网关 exec 是 **argv 直传**，平台可直接传参数数组，反而比文档假设更安全（无需自行拼 shell）。
3. 镜像白名单：区分"代码默认=空（deny-all）"与"部署值=四个基础镜像"，并注明**不含 Python**。
4. 补充当时安全缺口：**未设非 root 运行用户、只读 RootFS 默认关闭**（现模板已默认启用）。
5. TTL 描述：代码无独立 grace 参数，实际销毁延迟 ≈ 扫描间隔（60s）。
6. 补充路线 B1（proxy 文件通道）作为过渡方案，别把它完全排除。

---

## 5. 待拍板项（收敛）

1. 首发是否只接受**小文本**产物？（是→阶段 A 即可；否→必须先做阶段 B）
2. 是否采用**路线 B1（proxy）**作为过渡文件通道，还是直接做 B2？
3. 是否**授权在本仓库实现 Gateway 文件 API（B2）**，以及发布窗口？
4. 出网策略（`bridge` vs `none`/egress）、隔离镜像清单、配额与成本上限。
5. 是否同时补 create 幂等与 tenant 维度（服务端强边界）。

---

## 6. 附：阶段 A 可执行自检（本机，Token 占位）

```bash
B=http://127.0.0.1:3010/api/v1/containers
T=<你的Token>

# 0) 通路
curl -s http://127.0.0.1:3010/healthz

# 1) 常驻容器（不要用一次性命令，否则 AutoRemove 立即删除）
CID=$(curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"image":"alpine:3.20","cmd":["sleep","3600"],"ttlSeconds":120}' $B \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# 2) 执行（argv 直传，非 sh -c）
curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"cmd":["sh","-c","printf hello; echo"],"timeoutSeconds":30}' $B/$CID/exec

# 3) 兜底销毁
curl -s -X POST -H "Authorization: Bearer $T" $B/$CID/destroy -o /dev/null -w '%{http_code}\n'
```

> 说明：第 2 步里的 `sh -c` 是**调用方自己**拼的；网关不做包装。实际命令含空格/特殊字符时，优先传 argv 数组而非拼接 shell 字符串。
