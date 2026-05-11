package com.sea.star.ai.ec.enterprise.connector.domain.model;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户身份 + 租户级策略 (QPS 上限等).
 *
 * Phase 6 开始, 数据源信息从本表拆到 {@link TenantDatasource}; 一个租户可以挂多个
 * 数据源 (订单库 / 库存库 / CRM API 等), 按 (tenant_id, ds_name) 寻址。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tenant_config")
public class TenantConfig {

    @Id(keyType = KeyType.None)
    private String tenantId;

    @Column("tenant_name")
    private String tenantName;

    @Column("tier")
    private TenantTier tier;

    @Column("rate_limit_qps")
    private Integer rateLimitQps;

    @Column("enabled")
    private Boolean enabled;

    /** 软删标志, Flex 自动过滤 (false=未删除) */
    @Column(value = "deleted", isLogicDelete = true)
    private Boolean deleted;

    /** AutoFillListener insert 时填 */
    @Column("created_at")
    private LocalDateTime createdAt;

    /** AutoFillListener update 时刷新 */
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
