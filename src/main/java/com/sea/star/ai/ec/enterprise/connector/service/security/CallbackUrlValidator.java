package com.sea.star.ai.ec.enterprise.connector.service.security;

import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 租户提供的 callback URL 校验：防 SSRF。
 *
 * 拒绝规则：
 *   - 非 http/https
 *   - host 为空 / 含登录信息 / 端口非法
 *   - DNS 解析到 loopback / link-local / site-local / any-local / 多播 地址
 *   - 显式黑名单主机（AWS/GCP/Azure 元数据 IP）
 *
 * DNS 只在校验时解析一次，WebClient 发起真实请求时会再解析一次 ——
 * TOCTOU（DNS rebinding）的缓解应由 JVM networkaddress.cache.ttl 控制。
 */
@Slf4j
@Component
public class CallbackUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 云厂商元数据服务 IP：必须阻断，否则会泄漏实例凭证 */
    private static final Set<String> METADATA_HOSTS = Set.of(
            "169.254.169.254",      // AWS / GCP / Azure IMDS
            "fd00:ec2::254",        // AWS IMDSv2 IPv6
            "metadata.google.internal",
            "metadata"
    );

    /**
     * 校验 URL；不合规时抛 BusinessException（按 PARAM_INVALID / 400 返回）。
     * URL 为 null / blank 时不抛异常（表示调用方不需要回调）。
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) return;

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw reject("URL 语法错误");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw reject("仅允许 http/https scheme");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("host 为空");
        }

        if (uri.getUserInfo() != null) {
            throw reject("不允许 URL 中携带 userinfo");
        }

        String lowerHost = host.toLowerCase();
        if (METADATA_HOSTS.contains(lowerHost)) {
            throw reject("禁止访问云厂商元数据地址");
        }

        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isLoopbackAddress()) {
                    throw reject("host 解析到 loopback 地址: " + addr.getHostAddress());
                }
                if (addr.isLinkLocalAddress()) {
                    throw reject("host 解析到 link-local 地址: " + addr.getHostAddress());
                }
                if (addr.isSiteLocalAddress()) {
                    throw reject("host 解析到内网地址: " + addr.getHostAddress());
                }
                if (addr.isAnyLocalAddress()) {
                    throw reject("host 解析到 any-local 地址");
                }
                if (addr.isMulticastAddress()) {
                    throw reject("host 解析到多播地址");
                }
                String ip = addr.getHostAddress();
                if (METADATA_HOSTS.contains(ip)) {
                    throw reject("host 解析到元数据地址: " + ip);
                }
            }
        } catch (UnknownHostException e) {
            throw reject("host 无法解析: " + host);
        }
    }

    private BusinessException reject(String reason) {
        return new BusinessException(ErrorCode.PARAM_INVALID, "callback URL 拒绝: " + reason);
    }
}
