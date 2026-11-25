package com.example.gateway.policy;

import com.example.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 镜像白名单策略，前缀匹配、deny-by-default。
 *
 * <p>支持三种条目：
 * <ul>
 *   <li>{@code repo}（不含 tag/digest）—— 放行整个仓库，如 {@code nginx} 放行 {@code nginx:任意tag}；</li>
 *   <li>{@code repo:tag} —— 精确匹配该 tag；</li>
 *   <li>{@code repo@sha256:...} —— 精确匹配 digest（最严谨）。</li>
 * </ul>
 * 镜像仓库含端口（如 {@code registry:5000/nginx}）时按最后一个 {@code /} 之后的 {@code :} 判定 tag。
 */
@Component
public class ImagePolicy {

    private sealed interface Matcher permits Exact, RepoWildcard {
        boolean matches(String image);
    }

    private record Exact(String value) implements Matcher {
        @Override
        public boolean matches(String image) {
            return value.equals(image);
        }
    }

    private record RepoWildcard(String repo) implements Matcher {
        @Override
        public boolean matches(String image) {
            return repo.equals(image) || image.startsWith(repo + ":") || image.startsWith(repo + "@");
        }
    }

    private final GatewayProperties props;

    public ImagePolicy(GatewayProperties props) {
        this.props = props;
    }

    public boolean isWhitelistEmpty() {
        return matchers().isEmpty();
    }

    public boolean isAllowed(String image) {
        if (image == null || image.isBlank()) {
            return false;
        }
        String normalized = image.trim();
        for (Matcher m : matchers()) {
            if (m.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    private List<Matcher> matchers() {
        List<Matcher> result = new ArrayList<>();
        for (String entry : com.example.gateway.docker.DockerOps.parseCsv(props.getImageWhitelist())) {
            if (entry.contains("@")) {
                result.add(new Exact(entry));
                continue;
            }
            int slash = entry.lastIndexOf('/');
            int colon = entry.lastIndexOf(':');
            if (colon > slash) {
                result.add(new Exact(entry));
            } else {
                result.add(new RepoWildcard(entry));
            }
        }
        return result;
    }
}
