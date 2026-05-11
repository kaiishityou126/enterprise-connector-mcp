package com.sea.star.ai.ec.enterprise.connector.infrastructure.event;

import lombok.Getter;

/**
 * 租户数据源变更事件 (Phase 6 新增).
 * <p>
 * 订阅方:
 *   - TenantDataSourceManager: 淘汰对应 (tenantId, dsName) 的 HikariDataSource 池
 *   - TenantHttpClientManager (预留): 淘汰对应 WebClient
 */
@Getter
public class TenantDatasourceChangedEvent {

    public enum Kind { CREATED, UPDATED, DELETED }

    private final String tenantId;
    private final String dsName;
    private final Kind kind;

    public TenantDatasourceChangedEvent(String tenantId, String dsName, Kind kind) {
        this.tenantId = tenantId;
        this.dsName = dsName;
        this.kind = kind;
    }
}
