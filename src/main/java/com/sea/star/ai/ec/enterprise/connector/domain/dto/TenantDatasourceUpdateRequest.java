package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * PATCH 语义: 只覆盖非 null 字段. 密文字段传明文, 服务端重新加密.
 */
@Data
@ToString(exclude = {"dbPassword", "apiToken"})
public class TenantDatasourceUpdateRequest {

    private AccessType accessType;

    @Size(max = 500)
    private String dbUrl;

    @Size(max = 100)
    private String dbUsername;

    @Size(max = 500)
    private String dbPassword;

    @Size(max = 100)
    private String dbDriver;

    @Size(max = 500)
    private String apiBaseUrl;

    @Size(max = 20)
    private String apiAuthType;

    private String apiToken;

    private String apiHeaders;

    private Boolean enabled;
}
