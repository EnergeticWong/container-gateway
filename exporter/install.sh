#!/usr/bin/env bash
#
# cgw-exporter 一键部署 / 升级 / 卸载
#
#   ./install.sh                        # 构建并安装，启动服务
#   ./install.sh --listen 192.0.2.5:18092 --upstream http://127.0.0.1:18091/internal/exporter/snapshot
#   ./install.sh --jar /tmp/cgw-exporter-1.0.0.jar   # 使用已构建产物（目标机无 Maven 时）
#   ./install.sh --no-start             # 只安装，不启动
#   ./install.sh --force-config         # 覆盖已有 /etc/cgw-exporter/config.yml
#   ./install.sh --uninstall [--purge]  # 卸载（--purge 同时删除配置与运行用户）
#
# 幂等：重复执行即升级（替换 jar 并重启服务）。
set -euo pipefail

APP="cgw-exporter"
SERVICE="cgw-exporter.service"
RUN_USER="cgw"
INSTALL_DIR="/usr/local/bin"
JAR_PATH="${INSTALL_DIR}/cgw-exporter.jar"
CONFIG_DIR="/etc/cgw-exporter"
CONFIG_FILE="${CONFIG_DIR}/config.yml"
UNIT_FILE="/etc/systemd/system/${SERVICE}"
DEFAULT_LISTEN="0.0.0.0:18092"
DEFAULT_UPSTREAM="http://127.0.0.1:18091/internal/exporter/snapshot"

LISTEN="${DEFAULT_LISTEN}"
UPSTREAM="${DEFAULT_UPSTREAM}"
ENV_LABEL="prod"
JAR_SRC=""
DO_START=1
FORCE_CONFIG=0
UNINSTALL=0
PURGE=0

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

info() { printf '\033[32m[INFO]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[WARN]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    --listen)       LISTEN="${2:?}"; shift 2 ;;
    --upstream)     UPSTREAM="${2:?}"; shift 2 ;;
    --env)          ENV_LABEL="${2:?}"; shift 2 ;;
    --jar)          JAR_SRC="${2:?}"; shift 2 ;;
    --no-start)     DO_START=0; shift ;;
    --force-config) FORCE_CONFIG=1; shift ;;
    --uninstall)    UNINSTALL=1; shift ;;
    --purge)        PURGE=1; shift ;;
    -h|--help)      usage ;;
    *) die "未知参数: $1（--help 查看用法）" ;;
  esac
done

[ "$(id -u)" -eq 0 ] || die "请以 root 执行"

# ---------------------------------------------------------------- 卸载
if [ "$UNINSTALL" -eq 1 ]; then
  info "停止并移除 ${SERVICE}"
  systemctl stop "$SERVICE" 2>/dev/null || true
  systemctl disable "$SERVICE" 2>/dev/null || true
  rm -f "$UNIT_FILE"
  systemctl daemon-reload
  rm -f "$JAR_PATH"
  if [ "$PURGE" -eq 1 ]; then
    rm -rf "$CONFIG_DIR"
    id "$RUN_USER" >/dev/null 2>&1 && userdel "$RUN_USER" 2>/dev/null || true
    info "卸载完成（已删除配置 ${CONFIG_DIR} 与用户 ${RUN_USER}）"
  else
    info "卸载完成（配置保留在 ${CONFIG_DIR}；彻底清理用 --uninstall --purge）"
  fi
  exit 0
fi

# ---------------------------------------------------------------- 前置检查
JAVA_BIN="$(command -v java || true)"
[ -n "$JAVA_BIN" ] || die "未找到 java，请先安装 JDK 21"
JAVA_BIN="$(readlink -f "$JAVA_BIN")"
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)"
[ "${JAVA_MAJOR:-0}" -ge 21 ] || die "需要 JDK 21+，当前: $("$JAVA_BIN" -version 2>&1 | head -1)"
info "Java: $JAVA_BIN"

if [ -z "$JAR_SRC" ]; then
  JAR_SRC="$(ls -1t "$ROOT"/target/cgw-exporter-*.jar 2>/dev/null | grep -v 'original-' | head -1 || true)"
fi
if [ -z "$JAR_SRC" ] || [ ! -f "$JAR_SRC" ]; then
  command -v mvn >/dev/null 2>&1 || die "未找到构建产物，且本机无 mvn；请用 --jar 指定 cgw-exporter-*.jar"
  info "未找到产物，使用 Maven 构建..."
  ( cd "$ROOT" && mvn -q -DskipTests package )
  JAR_SRC="$(ls -1t "$ROOT"/target/cgw-exporter-*.jar | grep -v 'original-' | head -1)"
fi
[ -f "$JAR_SRC" ] || die "构建产物不存在: $JAR_SRC"
info "产物: $JAR_SRC ($(du -h "$JAR_SRC" | cut -f1))"

# 上游联通性只告警，不阻断部署（网关可能稍后才升级）
UP_HOST="$(printf '%s' "$UPSTREAM" | sed -E 's#^https?://([^/:]+).*#\1#')"
UP_PORT="$(printf '%s' "$UPSTREAM" | sed -E 's#^https?://[^/:]+:([0-9]+).*#\1#')"
[ "$UP_PORT" = "$UPSTREAM" ] && UP_PORT=80
if timeout 3 bash -c "cat < /dev/null > /dev/tcp/$UP_HOST/$UP_PORT" 2>/dev/null; then
  info "上游快照端点可达: $UPSTREAM"
else
  warn "上游不可达（$UP_HOST:$UP_PORT）；exporter 会启动，但 /healthz 为 DOWN，指标只有自监控"
  warn "请确认网关已升级并开启 GATEWAY_EXPORTER_SNAPSHOT_PORT（默认 18091）"
fi

# ---------------------------------------------------------------- 用户与目录
if ! id "$RUN_USER" >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin "$RUN_USER"
  info "已创建用户 $RUN_USER"
fi
install -d -m 0755 "$CONFIG_DIR"

# ---------------------------------------------------------------- 配置
if [ -f "$CONFIG_FILE" ] && [ "$FORCE_CONFIG" -eq 0 ]; then
  info "保留已有配置: $CONFIG_FILE（--force-config 可覆盖）"
else
  cat > "$CONFIG_FILE" <<EOF
# cgw-exporter 配置（由 install.sh 生成）
listen: "$LISTEN"

upstream:
  url: "$UPSTREAM"
  timeout_ms: 8000
  schema: "cgw.exporter.snapshot/v1"

labels:
  environment: "$ENV_LABEL"
  service: "container-gateway"
EOF
  chmod 0644 "$CONFIG_FILE"
  info "已写入配置: $CONFIG_FILE"
fi

# ---------------------------------------------------------------- 安装
install -m 0755 "$JAR_SRC" "$JAR_PATH"
info "已安装: $JAR_PATH"

cat > "$UNIT_FILE" <<EOF
[Unit]
Description=cgw-exporter (Prometheus exporter for container-gateway)
Documentation=file:${CONFIG_DIR}/README.md
After=network-online.target container-gateway.service
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
Group=$RUN_USER
ExecStart=$JAVA_BIN -jar $JAR_PATH --config.file=$CONFIG_FILE
Restart=on-failure
RestartSec=5s
TimeoutStopSec=10s
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6
MemoryMax=128M
CPUQuota=20%
LimitNOFILE=4096

[Install]
WantedBy=multi-user.target
EOF
chmod 0644 "$UNIT_FILE"
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null
info "已安装 systemd 单元: $UNIT_FILE"

if [ "$DO_START" -eq 0 ]; then
  info "已跳过启动（--no-start）"
  exit 0
fi

# ---------------------------------------------------------------- 启动与自检
systemctl restart "$SERVICE"

HOST="$(printf '%s' "$LISTEN" | cut -d: -f1)"
PORT="$(printf '%s' "$LISTEN" | rev | cut -d: -f1 | rev)"
[ "$HOST" = "0.0.0.0" ] && HOST="127.0.0.1"
[ "$HOST" = "::" ] && HOST="[::1]"

# 自检分两层：进程是否起来（/healthz 能应答，200 或 503 都算起来），以及上游是否可用。
# 上游不可用不算安装失败——网关可能还没升级到带快照端点的版本；网关恢复后下次抓取自动恢复，无需重启。
CODE=000
for _ in $(seq 1 20); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://$HOST:$PORT/healthz" || true)"
  if [ "$CODE" = "200" ] || [ "$CODE" = "503" ]; then
    break
  fi
  sleep 1
done

echo
systemctl --no-pager --lines=0 status "$SERVICE" | head -5 || true
echo
if [ "$CODE" != "200" ] && [ "$CODE" != "503" ]; then
  warn "exporter 未响应（/healthz 连接失败，HTTP $CODE）。查看日志: journalctl -u $SERVICE -n 50 --no-pager"
  exit 1
fi

SERIES="$(curl -s -m 10 "http://$HOST:$PORT/metrics" | grep -c '^cgw_' || true)"
info "exporter 运行中: http://$HOST:$PORT/metrics（${SERIES:-0} 条 cgw_ 序列）"
if [ "$CODE" = "200" ]; then
  info "上游网关可用（/healthz 200），领域指标齐全"
else
  warn "上游快照不可用（/healthz 503）：exporter 本身正常，但指标只有自监控，cgw_gateway_up=0"
  warn "请确认网关已升级到含 exporter 快照端点的版本，且 GATEWAY_EXPORTER_SNAPSHOT_PORT（默认 18091）没有被设为 0"
  warn "网关恢复后 exporter 会自动恢复，无需重启本服务"
fi

cat <<EOF

Prometheus 抓取配置参考:
  - job_name: cgw
    scrape_interval: 15s
    scrape_timeout: 8s
    static_configs:
      - targets: ["<本机IP>:$PORT"]

可用参数: --listen --upstream --env --jar --no-start --force-config --uninstall [--purge]
EOF
