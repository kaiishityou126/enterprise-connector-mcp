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
import lombok.ToString;

/**
 * 租户数据源 (Phase 6 新增). 复合主键 (tenant_id, ds_name).
 *
 * ds_name 是逻辑契约: {@code action_template.datasource_name} 引用它,
 * 每个租户按规范配出约定好的 ds_name (如 "default" / "orders" / "crm_api"),
 * 同一个 action 跨租户打同名 ds, 但物理 URL / 驱动 / 凭证可以完全不同。
 *
 * @ToString 排除 db_password_enc / api_token_enc, 防日志泄漏密文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"dbPasswordEnc", "apiTokenEnc"})
@Table("tenant_datasource")
public class TenantDatasource {

    @Id(keyType = KeyType.None)
    private String tenantId;

    @Id(keyType = KeyType.None)
    private String dsName;

    @Column("access_type")
    private AccessType accessType;

    @Column("db_url")
    private String dbUrl;

    @Column("db_username")
    private String dbUsername;

    @Column("db_password_enc")
    private String dbPasswordEnc;

    @Column("db_driver")
    private String dbDriver;

    @Column("api_base_url")
    private String apiBaseUrl;

    @Column("api_auth_type")
    private String apiAuthType;

    @Column("api_token_enc")
    private String apiTokenEnc;

    @Column("api_headers")
    private String apiHeaders;

    @Column("enabled")
    private Boolean enabled;

    @Column(value = "deleted", isLogicDelete = true)
    private Boolean deleted;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
