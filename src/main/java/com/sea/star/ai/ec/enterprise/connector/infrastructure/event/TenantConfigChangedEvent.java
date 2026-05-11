package com.sea.star.ai.ec.enterprise.connector.infrastructure.event;

import lombok.Getter;

/**
 * 租户配置变更事件（create / update / delete）。
 *
 * 用事件总线而非直接调用，避免 TenantConfigService 反向依赖 DataSourceManager /
 * RateLimiter 造成的循环依赖。订阅方：
 *   - TenantDataSourceManager：淘汰对应 DataSource
 *   - TenantRateLimiter：淘汰限流器
 */
@Getter
public class TenantConfigChangedEvent {

    public enum Kind { CREATED, UPDATED, DELETED }

    private final String tenantId;
    private final Kind kind;

    public TenantConfigChangedEvent(String tenantId, Kind kind) {
        this.tenantId = tenantId;
        this.kind = kind;
    }
}
