package com.sea.star.ai.ec.enterprise.connector.config;

import com.sea.star.ai.ec.enterprise.connector.infrastructure.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 安全配置：注册 AuthInterceptor 到 Spring MVC。
 *
 * 路径约定:
 *   /mcp, /mcp/** -> Spring AI MCP starter 的 Streamable HTTP 单端点 (POST /mcp);
 *                    /mcp 自身没有 trailing slash, 必须跟 /mcp/** 一起显式列出,
 *                    否则 AntPathMatcher 不匹配 → 端点会绕过认证. 统一 Bearer Token
 *   /admin/**     -> Admin API, X-API-Key
 *   其它          -> 放行 (健康检查 / Swagger / SpringDoc / 内部回调端点自行鉴权)
 *
 * 不使用 spring-boot-starter-security 的原因: 当前场景只需要简单头部校验, Spring Security
 * 过于重量且对 MCP 这种 JSON-RPC over HTTP 协议没有额外价值。
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // /mcp 单独列出: AntPathMatcher 下 "/mcp/**" 不匹配 "/mcp" 自身 (trailing slash 问题)
                .addPathPatterns("/mcp", "/mcp/**", "/admin/**")
                .excludePathPatterns("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**");
    }
}
