package com.sea.star.ai.ec.enterprise.connector.infrastructure.event;

import org.springframework.context.ApplicationEvent;

/**
 * tenant_action_config 授权变更事件 (grant/update/revoke/grantAllDefaults).
 * <p>
 * 监听方:
 * <ul>
 *   <li>{@code PerTenantToolCallbackProvider} — 清 per-session schema 缓存,
 *       让下次 tools/list 拿到最新授权 + customParams 合并视图</li>
 * </ul>
 * 第一版采用粗粒度 (全部清空) 策略.
 */
public class ActionAuthChangedEvent extends ApplicationEvent {

    /** 受影响的 tenantId (可能为 null = 跨租户批量变更, 全清缓存) */
    private final String tenantId;

    public ActionAuthChangedEvent(Object source, String tenantId) {
        super(source);
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
