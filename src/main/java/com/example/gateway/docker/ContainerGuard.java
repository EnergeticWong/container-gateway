package com.example.gateway.docker;

import com.example.gateway.error.GatewayException;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.OptionalLong;

/**
 * 所有权校验：所有针对已有容器的操作，必须先过这一关。
 *
 * <p>先经 {@link ContainerIndex} 定位容器所在节点，再校验 label 归属。
 */
@Component
public class ContainerGuard {

    private final DockerOps docker;
    private final ContainerIndex index;

    public ContainerGuard(DockerOps docker, ContainerIndex index) {
        this.docker = docker;
        this.index = index;
    }

    /** 已定位并校验通过的容器。 */
    public record Owned(DockerNode node, InspectContainerResponse container) {
    }

    /**
     * 校验容器存在、由本网关托管、且属于调用方。
     *
     * @throws GatewayException 404 容器不存在；403 非托管容器或归属他人；503 节点不可达
     */
    public Owned requireOwned(String id, String clientId) {
        DockerNode node = index.resolve(id);
        if (!node.healthy()) {
            throw GatewayException.nodeUnavailable(
                    "节点 " + node.id() + " 当前不可用，请稍后重试");
        }
        InspectContainerResponse container;
        try {
            container = docker.inspect(node, id);
        } catch (GatewayException e) {
            if (GatewayException.NOT_FOUND.equals(e.getCode())) {
                // 容器可能已被 AutoRemove 删掉，清理索引后照样返回 404
                index.forget(id);
            }
            throw e;
        }
        Map<String, String> labels = labelsOf(container);

        if (!"true".equals(labels.get(Ownership.MANAGED))) {
            throw GatewayException.ownershipDenied(
                    "容器 " + shortId(id) + " 不是本网关托管的容器，拒绝操作");
        }
        String owner = labels.get(Ownership.OWNER);
        if (owner == null || !owner.equals(clientId)) {
            throw GatewayException.ownershipDenied(
                    "容器 " + shortId(id) + " 归属其他客户端，拒绝操作");
        }
        return new Owned(node, container);
    }

    /** 已确认托管（用于 TTL 回收）：不校验 owner，但仍要求 managed=true。 */
    public static boolean isManaged(InspectContainerResponse container) {
        return "true".equals(labelsOf(container).get(Ownership.MANAGED));
    }

    public static Map<String, String> labelsOf(InspectContainerResponse container) {
        if (container.getConfig() == null || container.getConfig().getLabels() == null) {
            return Map.of();
        }
        return container.getConfig().getLabels();
    }

    public static OptionalLong ttlSecondsOf(InspectContainerResponse container) {
        String raw = labelsOf(container).get(Ownership.TTL_SECONDS);
        if (raw == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : String.valueOf(id);
    }
}
