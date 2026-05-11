package com.sea.star.ai.ec.enterprise.connector.domain.model;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户动作授权白名单 (Phase 6 起升级为必要授权).
 * <p>
 * 行存在 = 该租户获得该 action 的授权; 不存在 = 调用时抛 ACTION_NOT_AUTHORIZED (403).<br>
 * {@code enabled=false} 等价未授权 (保留配置便于临时撤销后重新启用)。
 * <p>
 * 物理删除策略: 撤销授权就该干净, 历史由 audit_log 负责。
 *
 * 复合主键 (tenant_id, action). Flex {@code selectOneById} 只接单参数, 自定义方法
 * 在 {@link com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper}
 * 里走 QueryWrapper。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tenant_action_config")
public class TenantActionConfig {

    @Id(keyType = KeyType.None)
    private String tenantId;

    @Id(keyType = KeyType.None)
    private String action;

    @Column("template_id")
    private Integer templateId;

    /** 覆盖 action_template.datasource_name; NULL 时使用模板默认值 */
    @Column("datasource_name_override")
    private String datasourceNameOverride;

    @Column("custom_sql")
    private String customSql;

    @Column("custom_api_path")
    private String customApiPath;

    @Column("custom_params")
    private String customParams;

    @Column("enabled")
    private Boolean enabled;

    /** 授权时间 (审计用), AutoFillListener insert 时填 */
    @Column("granted_at")
    private LocalDateTime grantedAt;
}
