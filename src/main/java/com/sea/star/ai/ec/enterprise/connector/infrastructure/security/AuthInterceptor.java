package com.sea.star.ai.ec.enterprise.connector.infrastructure.security;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.service.security.AuthenticationService;
import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 路径匹配型认证拦截器：根据 URL 前缀选择校验策略。
 * - /mcp 与 /mcp/**   → MCP Token + 可选 X-Tenant-Id 头设 TenantContext
 *                       (streamable-http 单端点是 /mcp 自身, 没有 trailing slash, 必须显式覆盖)
 * - /admin/**          → Admin API Key (X-API-Key)
 * - 以 /purge 结尾     → 在 Admin 校验基础上, 额外校验 X-Purge-Api-Key (Phase 6.4)
 * - 其他              → 放行（含 /actuator/** 健康检查）
 *
 * <p>MCP 请求若携带 {@code X-Tenant-Id} 头, 在通过 token 校验后会设到 {@link TenantContext},
 * 让 PerTenantToolCallbackProvider 在 tools/list 时按租户暴露合并后的 schema. afterCompletion
 * 清 ThreadLocal 防止线程池脏数据.
 *
 * <p>校验失败抛 BaseException，由 GlobalExceptionHandler 统一返回.
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthenticationService authenticationService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String path = request.getRequestURI();
        // streamable-http 单端点是 /mcp (无 trailing slash), 旧 SSE 模式是 /mcp/sse + /mcp/message,
        // 两种都要覆盖, 否则 streamable 切换后端点会绕过认证 + TenantContext 不设
        if (path.equals("/mcp") || path.startsWith("/mcp/")) {
            authenticationService.verifyMcp(request);
            // 可选: X-Tenant-Id 头携带当前 session 租户身份, 让 per-session schema 生效
            String tenantId = request.getHeader(BusinessConstants.MCP_HEADER_TENANT_ID);
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.setCurrentTenant(tenantId.trim());
            }
        } else if (path.startsWith("/admin/")) {
            authenticationService.verifyAdmin(request);
            // 物理删端点叠加二级认证. getRequestURI() 不含 query string, 只用 endsWith 就够.
            if (path.endsWith("/purge")) {
                authenticationService.verifyPurge(request);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 兜底清 TenantContext, 避免 servlet 容器线程池复用时携带上次请求的租户身份
        TenantContext.clear();
    }
}
