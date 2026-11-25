package com.example.gateway.docker;

import com.example.gateway.error.GatewayException;
import com.github.dockerjava.api.model.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器 → 节点 的路由索引。
 *
 * <p>真源是容器 label {@code com.example.gateway.node}；本类只是内存缓存：
 * <ul>
 *   <li>启动/周期性 {@link #rebuild()} 从各节点列举托管容器重建；</li>
 *   <li>{@link #resolve} 命中缓存直接返回；未命中时<b>有界扇出</b>到各节点 inspect 后回填；</li>
 *   <li>某节点不可达时<b>不</b>擦除其条目，也不把“找不到”误判为 404。</li>
 * </ul>
 */
@Component
public class ContainerIndex {

    private static final Logger log = LoggerFactory.getLogger(ContainerIndex.class);
    private static final int PREFIX_LEN = 12;

    private final NodeRegistry nodes;
    private final DockerOps docker;

    private final Map<String, String> byFullId = new ConcurrentHashMap<>();
    private final Map<String, String> byPrefix = new ConcurrentHashMap<>();

    public ContainerIndex(NodeRegistry nodes, DockerOps docker) {
        this.nodes = nodes;
        this.docker = docker;
    }

    public void register(String containerId, String nodeId) {
        if (containerId == null || nodeId == null) {
            return;
        }
        byFullId.put(containerId, nodeId);
        if (containerId.length() >= PREFIX_LEN) {
            byPrefix.put(containerId.substring(0, PREFIX_LEN), nodeId);
        }
    }

    public void forget(String containerId) {
        if (containerId == null) {
            return;
        }
        byFullId.remove(containerId);
        if (containerId.length() >= PREFIX_LEN) {
            byPrefix.remove(containerId.substring(0, PREFIX_LEN));
        }
    }

    /**
     * 解析容器所在的节点。
     *
     * @throws GatewayException 404 明确不存在；503 无法定位且存在不可达节点
     */
    public DockerNode resolve(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            throw GatewayException.notFound("容器 id 不能为空");
        }
        String nodeId = byFullId.get(idOrPrefix);
        if (nodeId == null && idOrPrefix.length() <= PREFIX_LEN) {
            nodeId = byPrefix.get(idOrPrefix);
        }
        if (nodeId != null) {
            DockerNode node = nodes.get(nodeId);
            if (node != null) {
                return node;
            }
            log.warn("索引中容器 {} 指向的节点 {} 已不存在，重新扇出解析", shortId(idOrPrefix), nodeId);
        }

        // 未命中：有界扇出到各节点 inspect
        boolean sawDownNode = false;
        for (DockerNode node : nodes.all()) {
            if (!node.healthy()) {
                sawDownNode = true;
                continue;
            }
            var found = docker.tryInspectLocal(node, idOrPrefix);
            if (found.isPresent()) {
                register(found.get().getId(), node.id());
                return node;
            }
        }
        if (sawDownNode) {
            throw GatewayException.nodeUnavailable(
                    "无法在可用节点上定位容器 " + shortId(idOrPrefix) + "（存在不可达节点，请稍后重试）");
        }
        throw GatewayException.notFound("容器不存在: " + idOrPrefix);
    }

    /**
     * 从各健康节点重建索引；某个节点不可达时保留其旧条目，避免把在它上面的容器误判为不存在。
     */
    public void rebuild() {
        Set<String> liveNodeIds = new HashSet<>();
        Set<String> liveFullIds = new HashSet<>();
        for (DockerNode node : nodes.all()) {
            if (!node.healthy()) {
                continue;
            }
            try {
                for (Container c : docker.listManaged(node, null)) {
                    String id = c.getId();
                    if (id == null) {
                        continue;
                    }
                    liveNodeIds.add(node.id());
                    liveFullIds.add(id);
                    register(id, node.id());
                }
            } catch (RuntimeException e) {
                log.warn("重建索引时节点 {} 列举容器失败: {}", node.id(), e.getMessage());
            }
        }
        // 清理「所属节点健康但容器已不存在」的条目（例如 AutoRemove 后）
        byFullId.entrySet().removeIf(e -> liveNodeIds.contains(e.getValue()) && !liveFullIds.contains(e.getKey()));
        byPrefix.clear();
        byFullId.forEach((fullId, nodeId) -> {
            if (fullId.length() >= PREFIX_LEN) {
                byPrefix.put(fullId.substring(0, PREFIX_LEN), nodeId);
            }
        });
        if (log.isDebugEnabled()) {
            log.debug("容器索引重建完成：{} 条，覆盖节点 {}", byFullId.size(), liveNodeIds);
        }
    }

    public int size() {
        return byFullId.size();
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : String.valueOf(id);
    }
}
