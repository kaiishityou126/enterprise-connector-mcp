package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TemplateCreateRequest {

    @NotBlank(message = "action 不能为空")
    @Size(max = 50, message = "action 最长50字符")
    private String action;

    @NotNull(message = "接入类型不能为空")
    private AccessType accessType;

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100)
    private String name;

    private String description;

    /** 打哪个逻辑数据源名, 默认 "default"; 引用 tenant_datasource.ds_name */
    @Size(max = 50)
    private String datasourceName;

    private String sqlTemplate;

    @Size(max = 200)
    private String apiPath;

    @Size(max = 10)
    private String apiMethod;

    private String apiBodyTemplate;

    private String paramSchema;

    @Min(value = 1, message = "max_rows 最小为1")
    @Max(value = 10000, message = "max_rows 最大为10000")
    private Integer maxRows;

    private Boolean isLongRunning;

    @Min(value = 1)
    @Max(value = 600)
    private Integer timeoutSeconds;
}
