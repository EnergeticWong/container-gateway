# 安全说明

请勿在仓库中提交真实凭据、运行时配置或内部网络地址。

- 仓库仅保留 `config/gateway.env.example`（占位符）与 `config/gateway.env.recommended`（容量基线）。
- 真实 Token 由 `scripts/install-gateway.sh` 首次安装时随机生成并写入 `/etc/container-gateway.env`（0600 root:root），该文件不得入库。
- 文档中的主机与地址均使用 `gateway.example.com` / `192.0.2.0/24` 等保留示例值，部署时请替换为实际环境取值。

如发现安全问题，请通过 GitHub 私有漏洞报告渠道联系维护者，不要公开 Issue。
