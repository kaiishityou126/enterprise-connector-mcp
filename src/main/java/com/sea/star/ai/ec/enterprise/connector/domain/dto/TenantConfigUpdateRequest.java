package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新租户请求 (Phase 6): PATCH 语义, 只覆盖非 null 字段.
 * 数据源字段在 PUT /admin/tenants/{id}/datasources/{dsName} 改.
 */
@Data
public class TenantConfigUpdateRequest {

    @Size(max = 100, message = "租户名称最长100字符")
    private String tenantName;

    private TenantTier tier;

    @Min(value = 1, message = "QPS限流最小为1")
    @Max(value = 1000, message = "QPS限流最大为1000")
    private Integer rateLimitQps;

    private Boolean enabled;
}
