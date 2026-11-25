# 文档索引

container-gateway 让调用方通过 HTTP 安全管理 Docker 容器。新用户建议先读 [项目主页](../README.md)，再按需要查看下面的文档。

## 从零开始

- [客户端接入指南](developer-guide.md)：拿到入口地址和访问凭证后，如何创建容器、执行命令、交换文件、访问服务并销毁。
- [部署与运维手册](deployment.md)：如何安装、配置、扩展、排查与监控网关。
- [新 Docker 节点接入](node-onboarding.md)：如何把一台新的 Docker 主机加入已有网关，组成资源池。

## 了解设计背景（历史资料）

> 以下两份文档是 2026-09 的设计与评估快照，部分结论已被后续实现取代。判断当前能力请以上面的接入指南和部署手册为准。

- [Docker 隔离执行方案（历史设计）](design/isolated-execution.md)
- [隔离执行评估与实施计划（历史评估）](design/isolated-execution-assessment.md)

## 验证数据

- [双节点压测报告（2026-11-04）](reports/loadtest-2026-11-04-node-pool.md)：容量、调度分布与吞吐的实测数据。
