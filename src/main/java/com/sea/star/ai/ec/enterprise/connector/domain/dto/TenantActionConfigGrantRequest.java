package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * 授权单个 action 给租户 (Phase 6). 路径: POST /admin/tenants/{tid}/actions/{action}/grant
 * <p>
 * templateId 必填: 授权是"tenant 能调 template" 的关联, 必须明确指定用哪个模板。
 * <p>
 * datasourceNameOverride 可选: 覆盖 action_template.datasource_name 的默认值。
 */
@Data
public class TenantActionConfigGrantRequest {

    @NotNull(message = "templateId 不能为空")
    private Integer templateId;

    /** 覆盖模板的 datasource_name; NULL 时使用模板默认值 */
    @Size(max = 50)
    private String datasourceNameOverride;

    /** PREMIUM 租户的自定义 SQL, 服务端 SqlWhitelistValidator 校验 */
    private String customSql;

    @Size(max = 200)
    private String customApiPath;

    /** JSONB */
    private String customParams;

    /** 默认 true; 传 false 则授权记录存在但不生效 (等价未授权) */
    private Boolean enabled;

    public void setCustomParams(String customParams) {
        if (StringUtils.isEmpty(customParams)) {
            this.customParams = null;
        } else {
            this.customParams = customParams;
        }
    }
}
