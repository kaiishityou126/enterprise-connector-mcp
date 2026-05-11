package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建租户请求 (Phase 6): 只包含租户身份 + 租户级策略.
 * 数据源走 POST /admin/tenants/{id}/datasources 单独配置.
 */
@Data
public class TenantConfigCreateRequest {

    @NotBlank(message = "租户ID不能为空")
    @Size(max = 50, message = "租户ID最长50字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "租户ID只允许字母数字下划线和连字符")
    private String tenantId;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 100, message = "租户名称最长100字符")
    private String tenantName;

    private TenantTier tier;

    @Min(value = 1, message = "QPS限流最小为1")
    @Max(value = 1000, message = "QPS限流最大为1000")
    private Integer rateLimitQps;
}
