package com.sea.star.ai.ec.enterprise.connector.domain.model;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("action_template")
public class ActionTemplate {

    @Id(keyType = KeyType.Auto)
    private Integer templateId;

    @Column("action")
    private String action;

    @Column("access_type")
    private AccessType accessType;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    /** 打哪个逻辑数据源; 对应 tenant_datasource.ds_name; 默认 "default" */
    @Column("datasource_name")
    private String datasourceName;

    @Column("sql_template")
    private String sqlTemplate;

    @Column("api_path")
    private String apiPath;

    @Column("api_method")
    private String apiMethod;

    @Column("api_body_template")
    private String apiBodyTemplate;

    @Column("param_schema")
    private String paramSchema;

    @Column("max_rows")
    private Integer maxRows;

    @Column("is_long_running")
    private Boolean isLongRunning;

    @Column("timeout_seconds")
    private Integer timeoutSeconds;

    @Column("enabled")
    private Boolean enabled;

    /** 软删标志, Flex 自动过滤 (false=未删除) */
    @Column(value = "deleted", isLogicDelete = true)
    private Boolean deleted;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
