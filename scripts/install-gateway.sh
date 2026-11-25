#!/usr/bin/env bash
#
# container-gateway 安装脚本
#
# 目录布局：
#   APP_HOME   /opt/container-gateway           0755 root:root  安装目录
#   JAR_PATH   $APP_HOME/container-gateway.jar  0644 root:root  应用包
#   LOG_DIR    $APP_HOME/logs                   0750 root:root  日志 + audit.log
#   ENV_FILE   /etc/container-gateway.env       0600 root:root  配置与密钥（含 API Token）
#   UNIT       /etc/systemd/system/container-gateway.service
#   BASELINE   $APP_HOME/container-gateway.env.recommended       容量/限流参数基线（仅告警用）
#
# 参数基线：安装时逐项比对 ENV_FILE 与该基线，不一致只 warn，不覆盖已有配置。
#
# 用法：
#   ./scripts/install-gateway.sh                       # 安装/刷新（不启停服务）
#   ./scripts/install-gateway.sh --restart             # 安装完顺手 restart
#   JAR_SRC=/tmp/container-gateway.jar ./scripts/install-gateway.sh
#   APP_HOME=/opt/xxx ./scripts/install-gateway.sh
#   JAVA_HOME=/opt/jdk-21 ./scripts/install-gateway.sh      # 指定 JDK（默认自动探测 21+）
#
# 幂等：重复执行只刷新 jar/权限/单元，不动已有 env（不会覆盖你改过的 Token 与白名单）。
#
set -euo pipefail

APP_HOME="${APP_HOME:-/opt/container-gateway}"
JAR_PATH="${JAR_PATH:-$APP_HOME/container-gateway.jar}"
LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"
JAR_SRC="${JAR_SRC:-$APP_HOME/container-gateway.jar}"
SERVICE_NAME="${SERVICE_NAME:-container-gateway}"
UNIT_PATH="/etc/systemd/system/${SERVICE_NAME}.service"
ENV_FILE="${ENV_FILE:-/etc/container-gateway.env}"
# 参数基线源文件：仓库内取 config/ 目录，装完会同步到 APP_HOME 供后续比对。
BASELINE_SRC="${BASELINE_FILE:-$(cd "$(dirname "$0")/.." && pwd)/config/gateway.env.recommended}"
BASELINE_DEST="$APP_HOME/container-gateway.env.recommended"

# Java 由下面的 detect_java() 自动探测；显式给出 JAVA_HOME 时优先尝试该路径。
JAVA_HOME="${JAVA_HOME:-}"
JAVA_HOME_HINT="$JAVA_HOME"
JAVA_BIN=""

# 与 env 文件保持一致；单元里也放一份 Environment 默认值，
# 避免 env 被改坏后 ExecStart 里的 ${VAR} 展开成字面量。
GATEWAY_PORT="${GATEWAY_PORT:-18090}"
GATEWAY_HOST="${GATEWAY_HOST:-0.0.0.0}"

APP_HOME_MODE=0755
DATA_DIR_MODE=0750
JAR_MODE=0644
UNIT_MODE=0644
ENV_MODE=0600

RESTART=0
for arg in "$@"; do
  case "$arg" in
    --restart) RESTART=1 ;;
    -h|--help) sed -n '3,21p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '%s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*" >&2; }

# 探测 JDK 21+：宿主机默认 java 可能是 17（如 Rocky 9），不能依赖 PATH 的默认版本。
# 依次尝试 JAVA_HOME、常见 JDK21 路径、/usr/lib/jvm/*21*、PATH 里的 java，取第一个主版本 >= 21 的，
# 并从命中路径推出 JAVA_HOME 写进 systemd 单元（保证 ExecStart 的 java 与 JAVA_HOME 同源）。
java_major() { "$1" -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1; }

# 返回 0 表示命中，命中后 JAVA_BIN / JAVA_HOME 即为最终取值。
try_java() {
  local candidate="$1" major resolved home
  [ -n "$candidate" ] && [ -x "$candidate" ] || return 1
  major="$(java_major "$candidate" 2>/dev/null || true)"
  [ -n "$major" ] || return 1
  JAVA_SEEN="${JAVA_SEEN}${candidate}(${major}) "
  [ "$major" -ge 21 ] 2>/dev/null || return 1
  # 优先保留候选自身的目录（如 /usr/lib/jvm/temurin-21 这类随升级不变的软链），
  # 只有它不像 JDK 目录（无 release 文件，例如 PATH 里的 /usr/bin/java）时才反推真实路径。
  home="${candidate%/bin/java}"
  if [ ! -f "$home/release" ]; then
    resolved="$(readlink -f "$candidate")"
    case "$resolved" in */bin/java) ;; *) return 1 ;; esac
    home="${resolved%/bin/java}"
    [ -f "$home/release" ] || return 1
  fi
  JAVA_HOME="$home"
  JAVA_BIN="$home/bin/java"
}

detect_java() {
  local candidates=() candidate jdk path_java
  JAVA_SEEN=""
  [ -n "$JAVA_HOME_HINT" ] && candidates+=("$JAVA_HOME_HINT/bin/java")
  candidates+=(/usr/lib/jvm/temurin-21/bin/java /usr/lib/jvm/java-21-openjdk/bin/java)
  for jdk in /usr/lib/jvm/*21*/bin/java; do
    [ -x "$jdk" ] && candidates+=("$jdk")
  done
  path_java="$(command -v java 2>/dev/null || true)"
  [ -n "$path_java" ] && candidates+=("$path_java")

  for candidate in ${candidates[@]+"${candidates[@]}"}; do
    try_java "$candidate" && break
  done

  if [ -z "$JAVA_BIN" ]; then
    echo "需要 JDK 21+，未找到可用的 java。已检查: ${JAVA_SEEN:-无}" >&2
    echo "可用 JAVA_HOME=/path/to/jdk-21 指定，或把 JDK21 放到 PATH 前面 / 软链到 /usr/lib/jvm/temurin-21" >&2
    exit 1
  fi
  if [ -n "$JAVA_HOME_HINT" ]; then
    local hint_bin="$JAVA_HOME_HINT/bin/java"
    if [ ! -x "$hint_bin" ]; then
      warn "JAVA_HOME=$JAVA_HOME_HINT 下没有可执行的 bin/java，已改用 $JAVA_HOME"
    elif [ "$(readlink -f "$hint_bin")" != "$(readlink -f "$JAVA_BIN")" ]; then
      warn "JAVA_HOME=$JAVA_HOME_HINT 不是 21+，已改用 $JAVA_HOME"
    fi
  fi
  log "Java         JAVA_HOME -> $JAVA_HOME（$("$JAVA_BIN" -version 2>&1 | head -1)）"
}

# 取 ENV_FILE 中某个 key 的最后一个取值（不存在时输出空串）
env_value() {
  { grep -E "^[[:space:]]*$1=" "$ENV_FILE" 2>/dev/null || true; } | tail -1 | cut -d= -f2-
}

# 与参数基线逐项比对：缺失或取值不同只告警，绝不改动 ENV_FILE。
check_baseline() {
  if [ ! -f "$BASELINE_SRC" ] && [ ! -f "$BASELINE_DEST" ]; then
    warn "未找到参数基线文件（$BASELINE_SRC），跳过基线校验"
    return 0
  fi
  local baseline="$BASELINE_SRC"
  [ -f "$baseline" ] || baseline="$BASELINE_DEST"
  local key expected current drift=0
  while IFS='=' read -r key expected; do
    case "$key" in ''|\#*) continue ;; esac
    current="$(env_value "$key")"
    if [ -z "$current" ]; then
      warn "基线缺失 $key（基线 $expected）：$ENV_FILE 未设置该键，将使用应用默认值"
      drift=1
    elif [ "$current" != "$expected" ]; then
      warn "偏离基线 $key=$current（基线 $expected）"
      drift=1
    fi
  done < "$baseline"
  if [ "$drift" -eq 1 ]; then
    log "参数基线与 $baseline 存在差异；仅提示，不覆盖已有配置，按需手工调整后 restart"
  else
    log "参数基线校验通过（$baseline）"
  fi
}

[ "$(id -u)" -eq 0 ] || { echo "必须以 root 运行" >&2; exit 1; }
detect_java
command -v docker >/dev/null 2>&1 || warn "未找到 docker 命令，请确认 Docker 已安装"

# ---------------- 1. 安装目录 ----------------
install -d -m "$APP_HOME_MODE" -o root -g root "$APP_HOME"

# ---------------- 2. 应用包 ----------------
if [ -f "$JAR_PATH" ] && [ "$JAR_SRC" != "$JAR_PATH" ]; then
  bak="$JAR_PATH.bak-$(date +%Y%m%d-%H%M%S)"
  cp -p "$JAR_PATH" "$bak"
  log "已备份旧应用包 -> $bak"
fi
if [ "$JAR_SRC" != "$JAR_PATH" ]; then
  [ -f "$JAR_SRC" ] || { echo "找不到待安装 jar: $JAR_SRC" >&2; exit 1; }
  install -m "$JAR_MODE" -o root -g root "$JAR_SRC" "$JAR_PATH"
fi
[ -f "$JAR_PATH" ] || { echo "缺少应用包: $JAR_PATH（用 JAR_SRC=... 指定）" >&2; exit 1; }
chmod "$JAR_MODE" "$JAR_PATH"; chown root:root "$JAR_PATH"

# ---------------- 3. 运行时可写目录 ----------------
install -d -m "$DATA_DIR_MODE" -o root -g root "$LOG_DIR"

# ---------------- 4. 配置与密钥 ----------------
gen_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

TOKEN_CREATED=0
if [ ! -f "$ENV_FILE" ]; then
  TOKEN="$(gen_token)"
  cat > "$ENV_FILE" <<EOF
# container-gateway 配置 —— 由 install.sh 生成，请按需修改后 systemctl restart container-gateway
# 监听
GATEWAY_HOST=${GATEWAY_HOST}
GATEWAY_PORT=${GATEWAY_PORT}

# 客户端 Token。格式 <token>:<clientId>，多个用逗号分隔。
# 换 Token 后需 systemctl restart container-gateway。
GATEWAY_TOKENS=${TOKEN}:demo-client

# ---- Docker 节点（多节点聚合）----
# 单节点保持当前一行即可；多节点用英文逗号分隔，每个节点 id=<dockerUri>[;k=v...]
# 远程示例（TLS）：
#   GATEWAY_NODES=local=unix:///var/run/docker.sock,w1=tcp://192.0.2.11:2376;tlsVerify=true;certPath=/etc/container-gateway/tls/w1;proxyHost=192.0.2.11
# 可用选项：proxyHost（网关访问该节点映射端口的地址）、tlsVerify、certPath、enabled
GATEWAY_NODES=local=unix:///var/run/docker.sock
# 兼容旧配置：未配置 GATEWAY_NODES 时作为唯一节点（不要改成 TCP）
GATEWAY_DOCKER_HOST=unix:///var/run/docker.sock
# 远程 tcp 节点的默认证书目录（含 ca.pem/cert.pem/key.pem）与是否校验证书
GATEWAY_TLS_CERT_PATH=
GATEWAY_TLS_VERIFY=false
# 节点健康探测间隔 / 单节点安全并发上限 / 单节点最大 SSE 流数 / 每节点 Docker 连接池
# 流数上限提高时，连接池按「流数 x 3」同步提高（见 config/gateway.env.recommended）
GATEWAY_NODE_HEALTH_INTERVAL_MS=5000
GATEWAY_NODE_MAX_TASKS=30
GATEWAY_MAX_STREAMS_PER_NODE=200
GATEWAY_DOCKER_MAX_CONNECTIONS=600

# 镜像白名单，前缀匹配、deny-by-default；条目不带 tag 表示放行整个仓库。
# 也支持固定 digest：repo@sha256:...
# 这些镜像需已在各节点存在（默认禁止联网拉取）。
GATEWAY_IMAGE_WHITELIST=nginx:1.27-alpine,debian:12-slim,eclipse-temurin:21-jre,alpine:3.20,sandbox-runtime:2026.10,sandbox-runtime:2026.1021,sandbox-runtime:2026.1104
GATEWAY_ALLOW_PULL=false
# 启动期在各健康节点预拉的基础镜像（逗号分隔）。
# sandbox-runtime:2026.10 = 本地构建的多语言沙箱镜像（JDK17+Maven+Python3+Node+工具链），
# 见 resources/sandbox/Dockerfile；运行时零安装（非 root + 只读 rootfs 下无法 apt/pip 装系统包）。
GATEWAY_PREPULL_IMAGES=sandbox-runtime:2026.10,sandbox-runtime:2026.1021,sandbox-runtime:2026.1104

# 宿主端口池（分配给容器的对外端口）
PORT_RANGE_START=30000
PORT_RANGE_END=30999

# 配额与限额（默认值取 2026-11-04 压测基线，见 config/gateway.env.recommended）
MAX_CONTAINERS=1000
MAX_PER_OWNER=1000
DEFAULT_TTL_SECONDS=3600
MAX_TTL_SECONDS=86400
DEFAULT_MEMORY_BYTES=1073741824
MAX_MEMORY_BYTES=4294967296
DEFAULT_CPUS=1.0
MAX_CPUS=4
DEFAULT_PIDS=256
MAX_PIDS=512
MIN_MEMORY_BYTES=16777216
MIN_TTL_SECONDS=30
MAX_PORTS_PER_CONTAINER=16
RATE_LIMIT_PER_MINUTE=60000
CREATE_RATE_LIMIT_PER_MINUTE=600
# 公网健康检查按源 IP 限流（/healthz、/healthz/live、/healthz/ready）
HEALTH_RATE_LIMIT_PER_MINUTE=240

# 容器策略（HostConfig）——换环境只改这里，不用改代码
# 网络模式：none | bridge | 自定义网络名
GATEWAY_NETWORK_MODE=bridge
# 容器退出即自动删除（沙箱短任务模型；端口靠对账回收）。要保留退出后可查询设 false
GATEWAY_AUTO_REMOVE=false
# 额外丢弃的能力（逗号分隔）；留空=内置危险能力清单；支持 ALL
GATEWAY_CAP_DROP=
# 额外添加的能力（逗号分隔）；危险能力会被强制忽略
GATEWAY_CAP_ADD=
GATEWAY_SECURITY_OPTS=no-new-privileges:true
# seccomp profile：留空 | runtime/default | unconfined | 各节点上存在的路径
GATEWAY_SECCOMP_PROFILE=
GATEWAY_READONLY_ROOTFS=true
# 容器运行用户（user[:group]）；留空=镜像默认用户（多为 root）
GATEWAY_RUN_AS_USER=1000:1000
# 网关托管工作区宿主目录：非空启用。每容器在其下建独立子目录、chown 到上面的运行用户，
# rw bind 到 /workspace，destroy/TTL 回收时清理。用于「非 root + 只读 rootfs + 文件 API」并存。
# 须为网关本机目录，并已被 systemd ReadWritePaths 放行（install.sh 会自动写入）。
GATEWAY_WORKSPACE_HOST_DIR=/var/lib/container-gateway/workspaces
# tmpfs：条目用 ';' 分隔，条目内 "路径:选项,选项"（选项可含逗号）
# 注意：tmpfs 计入容器内存 cgroup，总和须远小于容器内存上限；
#       启用 GATEWAY_WORKSPACE_HOST_DIR 后不要再把 /workspace 放进 tmpfs。
GATEWAY_TMPFS=/tmp:rw,size=32m,uid=1000,gid=1000,mode=1777
# 是否允许客户端声明 bind mount（仍强制拒绝 docker.sock 与宿主敏感路径）
GATEWAY_BIND_MOUNTS_ENABLED=false

# exec / 流式 / 代理
EXEC_DEFAULT_TIMEOUT_SECONDS=30
EXEC_MAX_TIMEOUT_SECONDS=600
EXEC_MAX_OUTPUT_BYTES=65536
GATEWAY_STREAM_HEARTBEAT_MS=15000
GATEWAY_PROXY_MAX_BODY_BYTES=8388608
GATEWAY_PROXY_TIMEOUT_SECONDS=120

# TTL 回收扫描间隔
TTL_SCAN_INTERVAL_MS=60000

# 审计日志
GATEWAY_AUDIT_LOG=${LOG_DIR}/audit.log

# ---- 监控（可选）----
# exporter 只读快照端口：仅绑定 127.0.0.1，供同机 cgw-exporter 抓取；0=关闭。
# 不经 CLB、不对外网开放；Prometheus 抓的是 cgw-exporter 的 18092。
GATEWAY_EXPORTER_SNAPSHOT_PORT=18091

# 容器访问代理：调用方通过同一 HTTP 入口访问容器 HTTP 服务（可直连、经反向代理、CLB 或 VPN）
GATEWAY_PROXY_ENABLED=true
# 留空则从请求 Host 头推导（经 CLB 时即 CLB 的 VIP/域名）
GATEWAY_ADVERTISE_HOST=
GATEWAY_ADVERTISE_SCHEME=http
# 宿主机内网 IP，用于额外返回直连 URL；留空则只返回代理 URL
GATEWAY_DIRECT_HOST=

# 文件收发上限（上传/下载 tar 归档，单位字节）
# 单文件 100MiB；单次归档总量 200MiB（压缩包本身与解压后大小都按此判定）；归档条目数 512。
# 注意：当前实现把整个归档读进内存（单请求峰值约 2×总量上限），调大前先评估堆大小与并发。
GATEWAY_FILE_MAX_BYTES=104857600
GATEWAY_FILE_MAX_TOTAL_BYTES=209715200
GATEWAY_FILE_MAX_COUNT=512
EOF
  chmod "$ENV_MODE" "$ENV_FILE"; chown root:root "$ENV_FILE"
  TOKEN_CREATED=1
else
  log "已存在 $ENV_FILE，保持不变"
  for key in GATEWAY_HOST GATEWAY_PORT GATEWAY_TOKENS GATEWAY_IMAGE_WHITELIST GATEWAY_NODES; do
    grep -qE "^[[:space:]]*${key}=" "$ENV_FILE" || warn "$ENV_FILE 缺少 ${key}"
  done
fi

# ---------------- 4b. 托管工作区目录 ----------------
# 与 env 文件保持一致（运维若自定义 GATEWAY_WORKSPACE_HOST_DIR，这里跟随，保证 systemd 可写）
WORKSPACE_HOST_DIR="$(grep -E '^[[:space:]]*GATEWAY_WORKSPACE_HOST_DIR=' "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2-)"
WORKSPACE_HOST_DIR="${WORKSPACE_HOST_DIR:-/var/lib/container-gateway/workspaces}"
install -d -m 0750 -o root -g root "$WORKSPACE_HOST_DIR"
log "托管工作区目录 -> $WORKSPACE_HOST_DIR（已加入 systemd ReadWritePaths）"

# ---------------- 4c. 参数基线比对（只告警，不改配置） ----------------
# 把基线同步到 APP_HOME，便于后续从 APP_HOME 运行 install.sh 时仍能比对。
if [ -f "$BASELINE_SRC" ] && [ "$BASELINE_SRC" != "$BASELINE_DEST" ]; then
  install -m 0644 -o root -g root "$BASELINE_SRC" "$BASELINE_DEST"
fi
check_baseline

# ---------------- 5. systemd 单元 ----------------
if [ -f "$UNIT_PATH" ]; then
  cp -p "$UNIT_PATH" "$UNIT_PATH.bak-$(date +%Y%m%d-%H%M%S)"
fi

cat > "$UNIT_PATH" <<EOF
# 由 $APP_HOME/install.sh 生成，勿手工改；改布局请改脚本后重跑。
[Unit]
Description=Container Gateway (controlled Docker REST proxy)
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
Type=simple
User=root
Group=root
WorkingDirectory=$APP_HOME

Environment="JAVA_HOME=$JAVA_HOME"
Environment="PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
Environment="GATEWAY_HOST=$GATEWAY_HOST"
Environment="GATEWAY_PORT=$GATEWAY_PORT"
EnvironmentFile=-$ENV_FILE

# 用命令行参数显式指定端口/地址：优先级高于任何环境变量，
# 避免宿主机上恰好存在 SERVER_PORT / server.port 之类的变量把端口顶掉。
ExecStart=$JAVA_BIN -jar $JAR_PATH --server.port=\${GATEWAY_PORT} --server.address=\${GATEWAY_HOST}

# 整机只读，仅日志目录可写。
# 注意：connect() 到 /var/run/docker.sock 是 socket 连接、不是文件写，
# 不受 ProtectSystem=strict 影响，因此无需把 sock 加进 ReadWritePaths。
ProtectSystem=strict
ReadWritePaths=$LOG_DIR $WORKSPACE_HOST_DIR
PrivateTmp=true

SuccessExitStatus=143
Restart=on-failure
RestartSec=10
TimeoutStopSec=30
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
chmod "$UNIT_MODE" "$UNIT_PATH"; chown root:root "$UNIT_PATH"

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null 2>&1 || true

# ---------------- 6. 汇总 ----------------
log ""
log "安装目录     APP_HOME  -> $APP_HOME"
log "应用包       JAR_PATH  -> $JAR_PATH"
log "日志目录     LOG_DIR   -> $LOG_DIR   (含 audit.log)"
log "配置文件     ENV_FILE  -> $ENV_FILE"
log "systemd 单元           -> $UNIT_PATH"
log ""
if [ "$TOKEN_CREATED" -eq 1 ]; then
  log "已生成客户端 Token（请妥善保存，交付给接入方）："
  log ""
  log "    $(grep -E '^GATEWAY_TOKENS=' "$ENV_FILE" | cut -d= -f2 | cut -d: -f1)"
  log ""
fi
if command -v systemd-analyze >/dev/null 2>&1; then
  systemd-analyze verify "$UNIT_PATH" 2>&1 | sed 's/^/verify: /' || true
fi
stat -c '%a %U:%G %n' "$APP_HOME" "$JAR_PATH" "$LOG_DIR" "$ENV_FILE" "$UNIT_PATH"

if [ "$RESTART" -eq 1 ]; then
  systemctl restart "$SERVICE_NAME"
  log "已重启 $SERVICE_NAME"
else
  log "未启停服务；生效请执行: systemctl restart $SERVICE_NAME"
fi
