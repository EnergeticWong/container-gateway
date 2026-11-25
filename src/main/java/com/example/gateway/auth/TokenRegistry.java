package com.example.gateway.auth;

import com.example.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Token 注册表。内存中只保留 token 的 SHA-256，比对使用常量时间比较，避免时序侧信道。
 *
 * <p>配置格式：{@code GATEWAY_TOKENS=<token>:<clientId>[,<token>:<clientId>...]}
 */
@Component
public class TokenRegistry {

    private static final Logger log = LoggerFactory.getLogger(TokenRegistry.class);

    private record Entry(byte[] hash, String clientId) {
    }

    private final List<Entry> entries;

    public TokenRegistry(GatewayProperties props) {
        List<Entry> parsed = new ArrayList<>();
        String raw = props.getTokens();
        if (raw != null && !raw.isBlank()) {
            for (String item : raw.split(",")) {
                String s = item.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int idx = s.lastIndexOf(':');
                if (idx <= 0 || idx == s.length() - 1) {
                    log.warn("忽略非法的 token 配置项（应形如 <token>:<clientId>）");
                    continue;
                }
                String token = s.substring(0, idx);
                String clientId = s.substring(idx + 1);
                parsed.add(new Entry(sha256(token), clientId));
            }
        }
        this.entries = List.copyOf(parsed);
        if (this.entries.isEmpty()) {
            log.error("GATEWAY_TOKENS 为空，所有业务请求都会被拒绝（401）");
        } else {
            log.info("已加载 {} 个客户端 token: {}", this.entries.size(),
                    this.entries.stream().map(Entry::clientId).toList());
        }
    }

    /** @return 匹配到的 clientId，未匹配返回 null */
    public String resolve(String presentedToken) {
        if (presentedToken == null || presentedToken.isEmpty() || entries.isEmpty()) {
            return null;
        }
        byte[] hash = sha256(presentedToken);
        String matched = null;
        // 遍历全部条目做常量时间比较，不提前返回，避免通过耗时推断匹配位置
        for (Entry e : entries) {
            if (MessageDigest.isEqual(e.hash(), hash)) {
                matched = e.clientId();
            }
        }
        return matched;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
