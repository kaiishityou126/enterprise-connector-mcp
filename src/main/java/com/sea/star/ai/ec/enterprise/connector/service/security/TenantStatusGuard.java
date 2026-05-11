package com.sea.star.ai.ec.enterprise.connector.service.security;

import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.service.TenantConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 租户状态守卫 (Phase 6.2 新增).
 * <p>
 * 集中在"业务请求入口处"校验租户可用性, 避免每个入口
 * (MCP / 异步任务 / Admin 子资源写) 各自调 TenantConfigService 重复写检查。
 * <p>
 * 软删判定由 Flex logic-delete 自动过滤, Guard 额外负责 enabled=false 的拦截。
 * <p>
 * 两种语义:
 * <ul>
 *   <li>{@link #requireActive} 正常业务调用 — 必须存在 + enabled=true, 否则抛异常</li>
 *   <li>{@link #requireExists} 运维操作     — 允许 enabled=false (如启用/恢复 Admin 场景)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TenantStatusGuard {

    private final TenantConfigService tenantConfigService;

    /** 正常业务调用: 租户存在 (未软删) + enabled=true */
    public TenantConfig requireActive(String tenantId) {
        return tenantConfigService.getConfig(tenantId);
    }

    /** 运维操作: 只要求租户存在 (可以 enabled=false) */
    public TenantConfig requireExists(String tenantId) {
        return tenantConfigService.getConfigAllowDisabled(tenantId);
    }
}
