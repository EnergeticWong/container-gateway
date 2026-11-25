# container-gateway 新 Docker 节点接入部署手册

> 适用版本：container-gateway 1.0.0。
> 场景：把一台新的 Docker 主机（下称 worker）加入已有网关，与现有节点组成资源池；若已有多节点，请在 `GATEWAY_NODES` 中**追加**而不是覆盖。
> 约束：网关对外 API 不变；节点通过 Docker TLS API 接入；远程节点工作区使用命名卷。

---

## 0. 部署前必读

1. Docker API 等价于节点 root 权限，**2376 绝对不能暴露公网**，只允许网关 IP 访问。
2. 不要在本机（网关所在服务器）再启动第二个 dockerd 做测试：实测其退出时可能删除默认 docker0，导致本机容器断网。
3. 节点接入前先确认镜像已预拉；网关 GATEWAY_ALLOW_PULL=false 时缺镜像会直接创建失败。
4. 移除节点前必须先清空该节点上的托管容器，否则容器会成为孤儿（网关不再管理、TTL 也回收不到）。
5. worker 必须保留默认 docker0 网桥，不要加 --bridge=none；网关默认 GATEWAY_NETWORK_MODE=bridge，容器依赖默认网桥。

---

## 1. 快速清单

| 步骤 | 操作 | 验收 |
|---|---|---|
| 1 | 确认 worker 规格与系统 | 8C32G、Docker 29.x、cgroup v2、磁盘 ≥100G |
| 2 | 生成 CA / 服务端 / 客户端证书 | 证书 SAN 含 worker IP |
| 3 | 配置 dockerd 监听 2376 + TLS | 网关侧 docker version 成功 |
| 4 | 防火墙放通 | 仅网关 IP 可访问 2376、30000-30999 |
| 5 | 预拉镜像 | sandbox-runtime:2026.1021 等存在 |
| 6 | 网关增加 GATEWAY_NODES | /healthz 显示 w1 UP |
| 7 | 端到端验证 | create/exec/文件/反代/destroy 全通 |
| 8 | 故障演练 | 节点失联后约 5–10s 变 DOWN，创建落到其他节点，故障节点操作返回 503 |

---

## 2. Worker 前置检查

以下命令在 worker-1 上执行。

    # 1) 规格
    nproc
    free -g
    df -h /var/lib/docker 2>/dev/null || df -h /data

    # 2) Docker 版本（需与网关一致，当前 29.3.1）
    docker version --format '{{.Server.Version}}'

    # 3) cgroup v2（必须 cgroup2fs）
    stat -fc %T /sys/fs/cgroup

    # 4) 内核与时间
    uname -r
    timedatectl | grep -E 'System clock synchronized|NTP service'

    # 5) 端口段是否被占用（30000-30999）
    ss -lnt | awk '$4 ~ /:3[0-9]{4}$/ {print}' | head

要求：

- 与网关同架构（x86_64/aarch64 一致）、同 Docker 大版本。
- 磁盘：镜像约 1.2GB，加上 writable layer + 命名卷，建议 ≥100GB 空闲。
- 30000-30999 段独占，不要给其他进程使用（网关在远程节点不做本地端口探测）。
- 节点规格建议与现有节点一致（8C32GB），否则选路只看容器数，会不均。

---

## 3. 生成 TLS 证书

双向 TLS 指网关验证 worker 的身份，worker 也验证网关的身份。名词速览：CA 是签发证书的根证书；SAN 必须包含连接时实际使用的 IP 或域名；`clientAuth` 表示这张证书用于证明网关身份。

在网关机上生成（也可在安全环境生成后分发）。设：

- WORKER_IP=<worker 内网 IP>
- NODE_ID=<节点 ID，例如 w1>
- GATEWAY_IP=<网关内网 IP>

    mkdir -p /etc/container-gateway/tls/$NODE_ID
    cd /etc/container-gateway/tls/$NODE_ID

    # CA
    openssl genrsa -out ca-key.pem 4096
    openssl req -x509 -new -nodes -key ca-key.pem -sha256 -days 3650 \
      -subj "/CN=container-gateway-ca" -out ca.pem

    # 服务端证书（SAN 必须包含 worker 的访问 IP/域名）
    openssl genrsa -out server-key.pem 4096
    openssl req -new -key server-key.pem -subj "/CN=$NODE_ID" -out server.csr
    cat > server-ext.cnf <<EOF
    subjectAltName = DNS:$NODE_ID, IP:$WORKER_IP, IP:127.0.0.1
    extendedKeyUsage = serverAuth
    EOF
    openssl x509 -req -days 3650 -sha256 -in server.csr \
      -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
      -out server-cert.pem -extfile server-ext.cnf

    # 客户端证书（网关使用，必须 clientAuth）
    openssl genrsa -out key.pem 4096
    openssl req -new -key key.pem -subj "/CN=container-gateway" -out client.csr
    cat > client-ext.cnf <<EOF
    extendedKeyUsage = clientAuth
    EOF
    openssl x509 -req -days 3650 -sha256 -in client.csr \
      -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
      -out cert.pem -extfile client-ext.cnf

    chmod 600 ca-key.pem server-key.pem key.pem

分发：

| 文件 | 位置 | 说明 |
|---|---|---|
| ca.pem | worker: /etc/docker/tls/ca.pem | CA |
| server-cert.pem | worker: /etc/docker/tls/server-cert.pem | 服务端证书 |
| server-key.pem | worker: /etc/docker/tls/server-key.pem | 服务端私钥（600） |
| ca.pem | 网关: /etc/container-gateway/tls/w1/ca.pem | 校验 worker |
| cert.pem | 网关: /etc/container-gateway/tls/w1/cert.pem | 客户端证书 |
| key.pem | 网关: /etc/container-gateway/tls/w1/key.pem | 客户端私钥（600） |

> docker-java 固定读取 certPath 下的 ca.pem、cert.pem、key.pem，文件名不要改。`ca-key.pem` 只留在证书生成机，不要复制到 worker 或网关。

---

## 4. 配置 Worker 的 dockerd

### 4.1 先看现有启动方式

    systemctl cat docker

- 如果 ExecStart 已有 -H fd:// 或 -H unix://...，用 systemd drop-in 追加 TCP 监听，避免与 /etc/docker/daemon.json 的 hosts 冲突。
- 如果 daemon.json 已经写了 hosts，则不要再在命令行加 -H。

### 4.2 方案 A：systemd drop-in（推荐）

    mkdir -p /etc/systemd/system/docker.service.d
    cat > /etc/systemd/system/docker.service.d/10-tls.conf <<'EOF'
    [Service]
    ExecStart=
    ExecStart=/usr/bin/dockerd -H fd:// -H tcp://0.0.0.0:2376 \
      --tlsverify \
      --tlscacert=/etc/docker/tls/ca.pem \
      --tlscert=/etc/docker/tls/server-cert.pem \
      --tlskey=/etc/docker/tls/server-key.pem
    EOF
    systemctl daemon-reload
    systemctl restart docker
    systemctl status docker --no-pager | head -20
    ss -lntp | grep 2376

> dockerd 路径以 command -v dockerd 为准；--containerd 参数如果原 unit 有，请保留。
> 只监听 2376、不加 TLS 参数是严重错误，不要把 2375 明文端口开出去。

### 4.3 方案 B：daemon.json（仅在 unit 无 -H 时）

    {
      "hosts": ["unix:///var/run/docker.sock", "tcp://0.0.0.0:2376"],
      "tlsverify": true,
      "tlscacert": "/etc/docker/tls/ca.pem",
      "tlscert": "/etc/docker/tls/server-cert.pem",
      "tlskey": "/etc/docker/tls/server-key.pem"
    }

然后 systemctl restart docker。

### 4.4 从网关侧验证

    docker --tlsverify \
      --tlscacert=/etc/container-gateway/tls/w1/ca.pem \
      --tlscert=/etc/container-gateway/tls/w1/cert.pem \
      --tlskey=/etc/container-gateway/tls/w1/key.pem \
      -H tcp://192.0.2.11:2376 version

能返回 Server 版本即 TLS 正常。网关机没有 docker CLI 时可用：

    curl --cacert /etc/container-gateway/tls/w1/ca.pem \
         --cert /etc/container-gateway/tls/w1/cert.pem \
         --key /etc/container-gateway/tls/w1/key.pem \
         https://192.0.2.11:2376/_ping

---

## 5. 防火墙

只允许网关访问 worker 的 2376 和容器宿主端口段。

### firewalld

    firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=192.0.2.10/32 port port=2376 protocol=tcp accept'
    firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=192.0.2.10/32 port port=30000-30999 protocol=tcp accept'
    firewall-cmd --reload
    firewall-cmd --list-rich-rules

### ufw

    ufw allow from 192.0.2.10 to any port 2376 proto tcp
    ufw allow from 192.0.2.10 to any port 30000:30999 proto tcp
    ufw status numbered

> 如果客户端需要直连 worker 的容器端口（不使用网关反代），再单独对客户端网段放通 30000-30999；否则只放网关。

---

## 6. 预拉镜像

    # 按 GATEWAY_IMAGE_WHITELIST / GATEWAY_PREPULL_IMAGES 准备镜像
    docker pull registry.example.com/container-gateway/sandbox-runtime:2026.1104
    docker tag  registry.example.com/container-gateway/sandbox-runtime:2026.1104 sandbox-runtime:2026.1104

    docker images | grep -E 'sandbox-runtime|nginx|debian|temurin|alpine'

- 网关 GATEWAY_ALLOW_PULL=false，worker 上缺镜像会返回「镜像在节点 w1 上不存在且未开启联网拉取」。
- 如需网关自动拉取，必须在 worker 上配置 registry 登录并给网关开 GATEWAY_ALLOW_PULL=true，不推荐默认开启。
- 镜像约 1.2GB，10 层；每台 worker 各自存一份。

---

## 7. 网关侧接入

### 7.1 放置证书

    install -d -m 700 /etc/container-gateway/tls/w1
    install -m 644 ca.pem   /etc/container-gateway/tls/w1/ca.pem
    install -m 644 cert.pem /etc/container-gateway/tls/w1/cert.pem
    install -m 600 key.pem  /etc/container-gateway/tls/w1/key.pem

### 7.2 修改 /etc/container-gateway.env

当前是单节点（GATEWAY_DOCKER_HOST=unix:///var/run/docker.sock）。增加：

    GATEWAY_NODES="local=unix:///var/run/docker.sock,w1=tcp://192.0.2.11:2376;tlsVerify=true;certPath=/etc/container-gateway/tls/w1;proxyHost=192.0.2.11"

要点：

- 必须显式保留 local=...：一旦设置 GATEWAY_NODES，网关不再回退到 GATEWAY_DOCKER_HOST 单节点模式。
- proxyHost 是网关访问该节点容器宿主端口的地址，填 worker 的内网 IP；网关反代会连 http://proxyHost:hostPort。
- 节点 id 只能含字母数字/下划线/短横线，建议 w1、w2。
- 多个节点用英文逗号分隔。
- 可选 enabled=false 临时禁用节点（重启生效；其上已有容器不会被清理，见第 10 节）。

### 7.3 重启并检查

    systemctl restart container-gateway
    sleep 3
    curl -s http://127.0.0.1:3010/healthz | python3 -m json.tool

期望：

    {
      "status": "UP",
      "nodes": [
        {"id": "local", "status": "UP", "runningContainers": 0},
        {"id": "w1", "status": "UP", "runningContainers": 0}
      ],
      "healthyNodes": 2
    }

日志确认：

    journalctl -u container-gateway -n 50 --no-pager | grep -E '节点|w1|端口池'

---

## 8. 端到端验证

用现有 Token 调 3010。Token 从 /etc/container-gateway.env 读取，勿回显。

    TOKEN=***   # 现有业务 Token
    BASE=http://127.0.0.1:3010/api/v1/containers

    # 1) 创建
    curl -s -X POST $BASE -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
      "image":"sandbox-runtime:2026.1021",
      "cmd":["sleep","300"],
      "cpus":1.0,
      "memoryBytes":1073741824,
      "ttlSeconds":300,
      "name":"node-test-1",
      "meta":{"purpose":"node-onboarding"}
    }' | python3 -m json.tool

检查：

1. 返回 nodeId 是否为 w1（若同时有 local，可能被调度到任一节点；多创建几个或临时禁用 local 验证）。
2. 在 worker 上 docker ps 能看到对应容器。
3. 审计 tail -n 1 /opt/container-gateway/logs/audit.log 中 params.node=w1、workspaceKind=volume。

    CID=<上一步返回的 id>

    # exec
    curl -s -X POST $BASE/$CID/exec -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"cmd":["sh","-c","id; mkdir -p /workspace/out && echo ok > /workspace/out/a.txt"]}'

    # 上传（tar 字节）
    tar -C /tmp -cf - some.txt | curl -s -X POST "$BASE/$CID/files/upload?dest=/workspace/input" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' --data-binary @-

    # 下载 / 列举
    curl -s "$BASE/$CID/files/download?path=/workspace/out/a.txt" -H "Authorization: Bearer $TOKEN" -o /tmp/a.tar
    curl -s "$BASE/$CID/files?path=/workspace/out" -H "Authorization: Bearer $TOKEN"

    # 销毁
    curl -s -X POST $BASE/$CID/destroy -H "Authorization: Bearer $TOKEN"

销毁后确认 worker 无残留卷：

    docker -H tcp://192.0.2.11:2376 --tlsverify \
      --tlscacert=/etc/container-gateway/tls/w1/ca.pem \
      --tlscert=/etc/container-gateway/tls/w1/cert.pem \
      --tlskey=/etc/container-gateway/tls/w1/key.pem \
      volume ls --filter label=com.example.gateway.workspace=true

输出为空即正常。

---

## 9. 故障演练

1. 在 worker 上 systemctl stop docker。
2. 约 5–10 秒内 /healthz 中 w1 变为 DOWN（探测间隔 5s + 探测超时 3s）。
3. 新建容器应自动落到其他健康节点；针对 w1 上已有容器的操作返回 503 NODE_UNAVAILABLE（不再返回 500）。
4. systemctl start docker，约 5 秒内恢复 UP，原容器仍可操作。

> 节点 DOWN 期间其上的容器不会迁移，TTL 回收也会暂停；恢复后继续生效。

---

## 10. 节点下线 / 维护

必须先 drain，再摘节点：

1. 列出该节点所有托管容器：

    curl -s http://127.0.0.1:3010/api/v1/containers -H "Authorization: Bearer $TOKEN" \
     | python3 -c 'import sys,json; [print(c["id"]) for c in json.load(sys.stdin) if c["nodeId"]=="w1"]'

2. 逐个 POST /{id}/destroy，确认卷已清理。
3. 从 GATEWAY_NODES 移除 w1，重启网关。
4. 确认 docker ps 无残留、docker volume ls --filter label=com.example.gateway.workspace=true 为空。

不要直接删配置里节点：其上容器会变成孤儿（网关不再管理，TTL 也不会回收）。

---

## 11. 容量与调度说明

- 每节点上限由 GATEWAY_NODE_MAX_TASKS 控制（当前 30）；加一台 8C32GB 节点约增加 30 个容器容量。
- 选路按「该节点运行中托管容器数」最少者，不看 CPU/内存实际负载，因此建议同规格节点。
- 当前单用户配额已放开：MAX_CONTAINERS=1000、MAX_PER_OWNER=1000，实际容量由节点数与 NODE_MAX_TASKS 决定。
- 限流：RATE_LIMIT_PER_MINUTE=60000、CREATE_RATE_LIMIT_PER_MINUTE=600（单用户）。
- 资源建议：默认 1GB/1 vCPU 适合脚本与 .NET/Node；Chromium/页面渲染建议 2GB、2 vCPU、pids 512。当前模板 `/tmp` 仅 32MiB，浏览器任务依赖 `/workspace` 作为临时目录；如确需更大的 `/tmp`，请先由运维调整 `GATEWAY_TMPFS` 并重测内存预算。
- 远程节点的工作区是命名卷，数据在 worker 本地盘；节点磁盘故障会丢失该节点上的工作区数据。

---

## 12. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| health 中节点 DOWN，日志 x509: certificate signed by unknown authority | 网关 certPath 下 CA 不对/缺文件 | 核对 ca.pem 是否为签发该服务端证书的 CA |
| x509: certificate is valid for X, not 192.0.2.11 | 服务端证书 SAN 缺 IP | 重新签发，SAN 加 IP:192.0.2.11 |
| remote error: tls: bad certificate | 客户端证书缺 clientAuth 或非同一 CA | 重签客户端证书 |
| Connection refused / timeout | 2376 未监听或防火墙未放通 | ss -lntp 查 2376，检查 firewalld/ufw |
| 创建返回「镜像在节点 w1 上不存在」 | 未预拉镜像且 ALLOW_PULL=false | 在 worker docker pull 对应 tag |
| 创建返回 port is already allocated | 30000-30999 被其他进程占用 | 清理占用，或调整网关端口段 |
| 远程文件 API 400/500 | 工作区卷异常或属主不对 | docker volume inspect gw-ws-*；确认镜像有 /bin/sh 且 GATEWAY_RUN_AS_USER 为数字 uid |
| 节点加上了但创建仍落到 local | 选路按容器数，local 更空 | 正常均衡；如需强制验证可临时禁用 local（重启生效） |
| 网关重启后节点配置不生效 | env 文件未加载或语法错误 | 检查 /etc/container-gateway.env 与 systemd EnvironmentFile |
| healthz 显示 docker: DOWN | 所有节点不可达 | 先确认 local unix socket 与 worker 2376 |

---

## 13. 回滚

1. 备份当前 env 和 jar（部署前应已做）。
2. 从 GATEWAY_NODES 移除新节点（先按第 10 节 drain）。
3. 恢复原单节点配置：

    GATEWAY_NODES=
    GATEWAY_DOCKER_HOST=unix:///var/run/docker.sock

4. systemctl restart container-gateway，确认 /healthz 只剩 local 且 UP。
5. 如需回退二进制：替换 /opt/container-gateway/container-gateway.jar 为备份包后重启。

---

## 14. 本次节点接入的验收清单

- [ ] worker Docker 版本 29.x、cgroup v2、磁盘 ≥100G、时间同步正常
- [ ] 2376 仅网关可访问，30000-30999 仅网关（或客户端）可访问
- [ ] 网关侧 docker --tlsverify ... version 成功
- [ ] 镜像已预拉，tag 与白名单一致
- [ ] /healthz 显示 w1 UP，healthyNodes=2
- [ ] 创建容器落点正确，审计 node=w1、workspaceKind=volume
- [ ] exec、文件上传/下载/列举、销毁全通，销毁后无残留卷
- [ ] 停 worker dockerd：节点 DOWN、创建落其他节点；恢复后 UP
- [ ] 节点下线流程演练：先 drain 再移除配置
