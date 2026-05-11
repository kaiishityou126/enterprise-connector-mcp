package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * PATCH 授权配置 (含临时撤销 enabled=false). templateId 不能改 — 想改 template 请先 revoke 再 grant.
 */
@Data
public class TenantActionConfigUpdateRequest {

    @Size(max = 50)
    private String datasourceNameOverride;

    private String customSql;

    @Size(max = 200)
    private String customApiPath;

    /*json*/
    private String customParams;

    private Boolean enabled;

    public void setCustomParams(String customParams) {
        if (StringUtils.isEmpty(customParams)) {
            this.customParams = null;
        } else {
            this.customParams = customParams;
        }
    }
}
