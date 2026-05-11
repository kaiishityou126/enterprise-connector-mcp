package com.sea.star.ai.ec.enterprise.connector.service.adapter;

import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * 传入适配器的完整上下文。
 * <p>
 * Phase 6 起多了 {@link #datasource} 字段: BusinessExecutor 按模板的 datasource_name
 * (可被 tenant_action_config 覆盖) 预解析, Adapter 不用自己查。
 * <p>
 * Adapter 从本对象拿所有资源, 不再反查 TenantConfig / Datasource 各种 Service。
 */
@Value
@Builder
public class AdapterRequest {

    TenantConfig tenantConfig;
    ActionTemplate template;

    /** 本次调用选中的数据源 (已经按 dsName 解析好) */
    TenantDatasource datasource;

    /** 已解析的最终 SQL (custom_sql 或 template.sql_template, 未追加 LIMIT) */
    String resolvedSql;

    /** 已解析的最终 API 路径 */
    String resolvedApiPath;

    /** 调用参数 (Key 已通过 ParamValidator 校验; customSqlMode=true 时未校验, 由租户自负) */
    Map<String, Object> params;

    /**
     * 是否走 custom_sql 路径. true 表示 PREMIUM 租户配置了 custom_sql, 此时:
     * 1) ParamValidator 不再用模板 paramSchema 校验入参
     * 2) DatabaseAdapter 用 SQL 自身解析的占位符做 padding, 而不是模板 paramSchema
     * 由 BusinessExecutor.buildRequest 根据 ResolvedContext.isUsingCustomSql() 设置.
     */
    boolean customSqlMode;
}
