# 多语言沙箱运行时镜像（本地构建，无需 registry）
#
# 目的：把「Java/Python/Node + 常用工具链」在**构建期**装好，容器运行时**零安装**。
# 因为沙箱以「非 root + 只读 rootfs」运行，运行期 apt/pip 装系统包必然失败（见 dev 反馈）。
#
# 构建（在网关本机节点上）：
#   docker build -t sandbox-runtime:2026.10 /root/container-gateway/sandbox
#
# 与网关注入的运行时约定配合：
#   - gateway 会以 User=1000:1000 运行，并把宿主目录 bind 到 /workspace（可写）
#   - 下面把 HOME / TMPDIR 指向 /workspace，使 ~/.m2、~/.npm、~/.cache、venv、编译临时文件
#     全部落在可写绑定里，绕开只读 rootfs 与 32M 的 /tmp
#
FROM debian:12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# 先建 /workspace 再设 HOME/TMPDIR：apt 的 postinst(ca-certificates 等) 会用 mktemp 依赖 TMPDIR，
# 若此时 /workspace 不存在会直接失败。（运行时该目录会被宿主 bind 覆盖。）
RUN mkdir -p /workspace

ENV HOME=/workspace \
    TMPDIR=/workspace

# 系统工具 + 三语言运行时（JDK 带 javac；maven/pip/npm 便于构建与装项目依赖）
# 默认使用镜像内的上游 apt 源；仅为兼容无 IPv6 出口的环境强制 apt 使用 IPv4。
RUN set -eux; \
    printf 'Acquire::ForceIPv4 "true";\nAcquire::Retries "3";\n' > /etc/apt/apt.conf.d/99force-ipv4; \
    apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget git \
        unzip zip jq less procps \
        build-essential pkg-config \
        openjdk-17-jdk-headless maven \
        python3 python3-pip python3-venv python3-dev \
        nodejs npm \
    && rm -rf /var/lib/apt/lists/*

# pip / Maven 使用各自默认源；如需加速，由构建环境自行配置。
# npm 使用公开 registry，可通过构建参数覆盖。
ENV npm_config_registry=https://registry.npmmirror.com

# 镜像内的 /workspace 仅作占位（运行时会被宿主 bind 覆盖）；预建常见子目录
RUN mkdir -p /workspace/input /workspace/out /workspace/.cache

WORKDIR /workspace

# gateway 创建时会覆盖 cmd；这里给独立运行一个默认值
CMD ["sleep", "infinity"]
