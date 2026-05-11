package com.sea.star.ai.ec.enterprise.connector.mcp;

import com.sea.star.ai.ec.enterprise.connector.domain.dto.McpToolRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.resilience.TenantRateLimiter;
import com.sea.star.ai.ec.enterprise.connector.service.AuditService;
import com.sea.star.ai.ec.enterprise.connector.service.BusinessExecutor;
import com.sea.star.ai.ec.enterprise.connector.service.IdempotencyService;
import com.sea.star.ai.ec.enterprise.connector.service.security.CallbackUrlValidator;
import com.sea.star.ai.ec.enterprise.connector.service.security.TenantStatusGuard;
import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import com.sea.star.ai.ec.enterprise.connector.util.TraceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MCP Tool 调用的主服务。把前置链路（限流 / 幂等 / 审计 / 租户上下文）
 * 集中在这里，业务执行下沉到 BusinessExecutor。
 *
 * 调用顺序：
 *   1. 校验 callbackUrl（防 SSRF）
 *   2. **租户状态守卫** (TenantStatusGuard, Phase 6.2): 租户不存在 / 已禁用 → 立即拒绝
 *   3. 租户限流（Resilience4j RateLimiter，超限 429）
 *   4. 设置 TenantContext（ThreadLocal，供下游适配器用）
 *   5. 幂等保护（Redis SET NX，相同 requestId 返回元数据缓存）
 *   6. BusinessExecutor.execute 真正执行
 *   7. AuditService 异步写审计
 *
 * 异常链路也走审计 —— 失败一样记录，便于排查恶意调用和线上问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private final BusinessExecutor businessExecutor;
    private final TenantRateLimiter rateLimiter;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final CallbackUrlValidator callbackUrlValidator;
    private final TenantStatusGuard tenantStatusGuard;

    public UnifiedResult call(McpToolRequest request, String callerIdentity) {
        String tenantId = request.getTenantId();
        String action = request.getAction();
        TraceIds.currentOrGenerate();

        callbackUrlValidator.validate(request.getCallbackUrl());
        // Fail-fast: 租户不存在或禁用时不应进入限流 (避免占限流 state)
        tenantStatusGuard.requireActive(tenantId);
        rateLimiter.acquire(tenantId);

        TenantContext.setCurrentTenant(tenantId);
        long start = System.currentTimeMillis();
        UnifiedResult result = null;
        String errCode = null;
        String errMessage = null;
        try {
            result = idempotencyService.execute(
                    tenantId,
                    request.getRequestId(),
                    () -> businessExecutor.execute(tenantId, action, request.getParams()));
            return result;
        } catch (RuntimeException e) {
            errCode = extractCode(e);
            errMessage = e.getMessage();
            throw e;
        } finally {
            int durationMs = (int) (System.currentTimeMillis() - start);
            boolean success;
            String code;
            String message;
            if (result != null && result.isSuccess()) {
                success = true;
                code = result.getCode();
                message = null;
            } else {
                success = false;
                code = errCode;
                message = errMessage;
            }
            auditService.record(tenantId, action, callerIdentity,
                    request.getParams(), success, code, message, durationMs);
            TenantContext.clear();
        }
    }

    private String extractCode(Throwable e) {
        if (e instanceof com.sea.star.ai.ec.enterprise.connector.exception.BaseException base) {
            return base.getErrorCode().getCode();
        }
        return com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode.SYSTEM_ERROR.getCode();
    }
}
