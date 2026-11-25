package com.example.gateway.bootstrap;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.ContainerIndex;
import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.DockerOps;
import com.example.gateway.docker.NodeRegistry;
import com.example.gateway.policy.ImagePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动任务：重建容器索引 + 按节点预拉基础镜像。
 *
 * <p>预拉失败不影响网关启动（只告警）；镜像应在各节点本地存在，避免任务运行时才拉取造成延迟抖动。
 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final NodeRegistry nodes;
    private final DockerOps docker;
    private final ContainerIndex index;
    private final ImagePolicy imagePolicy;
    private final GatewayProperties props;

    public StartupRunner(NodeRegistry nodes, DockerOps docker, ContainerIndex index,
                         ImagePolicy imagePolicy, GatewayProperties props) {
        this.nodes = nodes;
        this.docker = docker;
        this.index = index;
        this.imagePolicy = imagePolicy;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            index.rebuild();
            log.info("启动完成：容器索引 {} 条，健康节点 {} 个", index.size(), nodes.healthyNodes().size());
        } catch (RuntimeException e) {
            log.warn("启动时重建容器索引失败: {}", e.getMessage());
        }
        prepull();
    }

    private void prepull() {
        List<String> images = DockerOps.parseCsv(props.getPrepullImages());
        if (images.isEmpty()) {
            return;
        }
        for (String image : images) {
            if (!imagePolicy.isWhitelistEmpty() && !imagePolicy.isAllowed(image)) {
                log.warn("预拉镜像 {} 不在白名单内，已跳过（需要创建时会被拒）", image);
                continue;
            }
            for (DockerNode node : nodes.healthyNodes()) {
                try {
                    if (docker.imageExists(node, image)) {
                        log.info("节点 {} 已存在镜像 {}", node.id(), image);
                        continue;
                    }
                    if (props.isAllowPull()) {
                        docker.pullImage(node, image);
                        log.info("节点 {} 预拉镜像完成：{}", node.id(), image);
                    } else {
                        log.warn("节点 {} 缺少镜像 {} 且未开启联网拉取（GATEWAY_ALLOW_PULL=false）",
                                node.id(), image);
                    }
                } catch (RuntimeException e) {
                    log.warn("节点 {} 预拉镜像 {} 失败: {}", node.id(), image, e.getMessage());
                }
            }
        }
    }
}
