package com.example.gateway.web;

import com.example.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 对外通告的访问地址（用于把容器的访问 URL 拼给客户端）。
 *
 * <p>经 CLB 访问时 Host 头就是 CLB 的 VIP/域名，因此默认从 Host 头推导，
 * 这样无论客户端走 CLB、内网 IP 还是本机，返回的 URL 都是它自己能连通的。
 */
public record AdvertiseContext(String scheme, String authority) {

    public static AdvertiseContext from(HttpServletRequest request, GatewayProperties props) {
        String scheme = props.getAdvertiseScheme() == null || props.getAdvertiseScheme().isBlank()
                ? "http" : props.getAdvertiseScheme();
        String authority = props.getAdvertiseHost();
        if (authority == null || authority.isBlank()) {
            authority = request.getHeader("Host");
        }
        if (authority == null || authority.isBlank()) {
            authority = request.getLocalAddr() + ":" + request.getLocalPort();
        }
        return new AdvertiseContext(scheme, authority);
    }

    public String baseUrl() {
        return scheme + "://" + authority;
    }
}
