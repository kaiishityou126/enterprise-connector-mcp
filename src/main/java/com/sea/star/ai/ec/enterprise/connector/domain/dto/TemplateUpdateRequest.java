package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TemplateUpdateRequest {

    @Size(max = 100)
    private String name;

    private String description;

    /** 打哪个逻辑数据源名; 不传则不改 */
    @Size(max = 50)
    private String datasourceName;

    private String sqlTemplate;

    @Size(max = 200)
    private String apiPath;

    @Size(max = 10)
    private String apiMethod;

    private String apiBodyTemplate;

    private String paramSchema;

    @Min(value = 1)
    @Max(value = 10000)
    private Integer maxRows;

    private Boolean isLongRunning;

    @Min(value = 1)
    @Max(value = 600)
    private Integer timeoutSeconds;

    private Boolean enabled;
}
