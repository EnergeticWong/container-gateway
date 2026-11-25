package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 创建容器请求。
 *
 * <p>刻意不提供 privileged / networkMode 等字段：这些在网关侧由策略固定，客户端无法影响隔离性。
 * 网络模式、AutoRemove、cap/seccomp 等由网关 {@code GATEWAY_*} 策略统一配置。
 *
 * @param image       镜像，必须在网关白名单内且目标节点已存在
 * @param cmd         启动命令，null 表示用镜像默认
 * @param env         环境变量
 * @param ports       需要对外暴露的容器端口
 * @param mounts      bind mount 声明，仅在网关开启时有效，可空
 * @param memoryBytes 内存上限，默认取网关策略（对齐文档为 256MB），上限取网关策略
 * @param cpus        CPU 上限，默认取网关策略（1.0）
 * @param pidsLimit   进程数上限，默认取网关策略（256）
 * @param ttlSeconds  存活秒数，到期网关自动销毁；默认取网关策略（对齐文档为 300）
 * @param name        容器名，可空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateContainerRequest(
        String image,
        List<String> cmd,
        Map<String, String> env,
        List<PortSpec> ports,
        List<MountSpec> mounts,
        Long memoryBytes,
        Double cpus,
        Long pidsLimit,
        Long ttlSeconds,
        String name) {

    public static Builder builder(String image) {
        return new Builder(image);
    }

    public static final class Builder {
        private final String image;
        private List<String> cmd;
        private Map<String, String> env;
        private List<PortSpec> ports;
        private List<MountSpec> mounts;
        private Long memoryBytes;
        private Double cpus;
        private Long pidsLimit;
        private Long ttlSeconds;
        private String name;

        private Builder(String image) {
            this.image = image;
        }

        public Builder cmd(String... cmd) {
            this.cmd = List.of(cmd);
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public Builder port(int containerPort) {
            this.ports = List.of(new PortSpec(containerPort, "tcp"));
            return this;
        }

        public Builder ports(List<PortSpec> ports) {
            this.ports = ports;
            return this;
        }

        public Builder mounts(List<MountSpec> mounts) {
            this.mounts = mounts;
            return this;
        }

        public Builder memoryMb(long mb) {
            this.memoryBytes = mb * 1024 * 1024;
            return this;
        }

        public Builder cpus(double cpus) {
            this.cpus = cpus;
            return this;
        }

        public Builder pidsLimit(long pidsLimit) {
            this.pidsLimit = pidsLimit;
            return this;
        }

        public Builder ttlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public CreateContainerRequest build() {
            return new CreateContainerRequest(image, cmd, env, ports, mounts, memoryBytes, cpus,
                    pidsLimit, ttlSeconds, name);
        }
    }
}
