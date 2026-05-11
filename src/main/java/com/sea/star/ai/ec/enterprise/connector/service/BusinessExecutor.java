package com.sea.star.ai.ec.enterprise.connector.service;

import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AsyncTask;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.exception.ActionNotAuthorizedException;
import com.sea.star.ai.ec.enterprise.connector.exception.BaseException;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.metrics.ConnectorMetrics;
import com.sea.star.ai.ec.enterprise.connector.service.adapter.AdapterRequest;
import com.sea.star.ai.ec.enterprise.connector.service.adapter.BusinessAdapter;
import com.sea.star.ai.ec.enterprise.connector.service.security.SqlWhitelistValidator;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import com.sea.star.ai.ec.enterprise.connector.util.ParamValidator;
import com.sea.star.ai.ec.enterprise.connector.util.TraceIds;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 核心执行器 (Phase 6 起强制授权白名单 + 多数据源路由).
 *
 * 调用流程:
 *   1. 取 TenantConfig (两级缓存, 检查 enabled)
 *   2. 取 TenantActionConfig (必要授权) — 没有行或 enabled=false 抛 ACTION_NOT_AUTHORIZED
 *   3. 按 actionConfig.template_id 取 ActionTemplate (两级缓存)
 *   4. 解析 dsName = actionConfig.datasource_name_override ?? template.datasource_name
 *   5. 取 TenantDatasource(tenantId, dsName) (两级缓存)
 *   6. 参数校验 (ParamValidator 基于 param_schema)
 *   7. 异步路径: template.is_long_running=true → AsyncTaskService.submit, 立即返回 taskId
 *   8. 同步路径: 按 datasource.access_type 选 Adapter, 执行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessExecutor {

    private final TenantConfigService tenantConfigService;
    private final TenantDatasourceService tenantDatasourceService;
    private final ActionTemplateService actionTemplateService;
    private final TenantActionConfigMapper tenantActionConfigMapper;
    private final SqlWhitelistValidator sqlWhitelistValidator;
    private final AsyncTaskService asyncTaskService;
    private final List<BusinessAdapter> adapters;
    private final ConnectorMetrics metrics;

    /**
     * 启动后注册异步任务的执行函数, 避免和 AsyncTaskService 构造器循环依赖.
     */
    @PostConstruct
    void registerAsyncExecutor() {
        asyncTaskService.setExecutionHandler(this::executeForAsyncTask);
    }

    /**
     * 同步执行业务 (HTTP/MCP 请求入口).
     * 模板标记为长任务时自动切换到异步路径, 返回带 taskId 的结果.
     */
    public UnifiedResult execute(String tenantId, String action, Map<String, Object> params) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(action, "action 不能为空");

        Timer.Sample timerSample = metrics.startRequestTimer();
        String statusLabel = "FAILED";
        long start = System.currentTimeMillis();
        try {
            ResolvedContext ctx = resolveContext(tenantId, action);

            // custom_sql 路径完全跳过模板 paramSchema 校验, PREMIUM 租户自负参数责任.
            // 非 custom_sql 路径仍按模板 schema 严校验入参.
            if (!ctx.isUsingCustomSql()) {
                ParamValidator.validate(ctx.template().getParamSchema(), params);
            }

            // 异步路径
            if (Boolean.TRUE.equals(ctx.template().getIsLongRunning())) {
                String taskId = asyncTaskService.submit(tenantId, action, params, null);
                UnifiedResult asyncResult = UnifiedResult.async(taskId);
                asyncResult.setTraceId(TraceIds.currentOrGenerate());
                statusLabel = "ASYNC_SUBMITTED";
                return asyncResult;
            }

            AdapterRequest request = buildRequest(ctx, params, action);
            BusinessAdapter adapter = findAdapter(ctx.datasource().getAccessType());
            UnifiedResult result = adapter.execute(request);
            result.setTraceId(TraceIds.currentOrGenerate());

            long cost = System.currentTimeMillis() - start;
            log.info("业务调用完成 tenantId={}, action={}, ds={}, access={}, cost={}ms",
                    tenantId, action, ctx.datasource().getDsName(),
                    ctx.datasource().getAccessType(), cost);
            statusLabel = result.isSuccess() ? "SUCCESS" : safeCode(result);
            return result;
        } catch (BaseException e) {
            statusLabel = e.getErrorCode().getCode();
            throw e;
        } catch (RuntimeException e) {
            statusLabel = ErrorCode.SYSTEM_ERROR.getCode();
            throw e;
        } finally {
            metrics.stopRequestTimer(timerSample, tenantId, action);
            metrics.incrementRequest(tenantId, action, statusLabel);
        }
    }

    private static String safeCode(UnifiedResult r) {
        return r.getCode() != null ? r.getCode() : ErrorCode.BIZ_ERROR.getCode();
    }

    /**
     * 异步任务的实际执行逻辑 (由 AsyncTaskService.runAsync 回调).
     */
    private UnifiedResult executeForAsyncTask(AsyncTask task) {
        Map<String, Object> params = JsonUtils.toMap(task.getParams());
        ResolvedContext ctx = resolveContext(task.getTenantId(), task.getAction());

        AdapterRequest request = buildRequest(ctx, params, task.getAction());
        BusinessAdapter adapter = findAdapter(ctx.datasource().getAccessType());
        UnifiedResult result = adapter.execute(request);
        result.setTaskId(task.getTaskId());
        return result;
    }

    // -- 以下为私有助手 --

    /**
     * 完整解析调用上下文 (租户 + 授权 + 模板 + 数据源), 任何一步失败抛对应异常.
     */
    private ResolvedContext resolveContext(String tenantId, String action) {
        TenantConfig tenant = tenantConfigService.getConfig(tenantId);

        TenantActionConfig actionConfig = tenantActionConfigMapper.findByTenantAndAction(tenantId, action);
        if (actionConfig == null || Boolean.FALSE.equals(actionConfig.getEnabled())) {
            throw new ActionNotAuthorizedException(tenantId, action);
        }

        ActionTemplate template = actionTemplateService.getById(actionConfig.getTemplateId());

        String dsName = actionConfig.getDatasourceNameOverride() != null
                && !actionConfig.getDatasourceNameOverride().isBlank()
                ? actionConfig.getDatasourceNameOverride()
                : template.getDatasourceName();
        if (dsName == null || dsName.isBlank()) {
            dsName = "default"; // 兜底: 老数据迁移时模板没填 datasource_name
        }

        TenantDatasource datasource = tenantDatasourceService.getDatasource(tenantId, dsName);

        // 校验: template 的 access_type 必须跟 datasource 的一致
        if (template.getAccessType() != datasource.getAccessType()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "模板和数据源 access_type 不匹配: template=" + template.getAccessType()
                            + " datasource=" + datasource.getAccessType()
                            + " (tenantId=" + tenantId + ", action=" + action + ", ds=" + dsName + ")");
        }

        return new ResolvedContext(tenant, actionConfig, template, datasource);
    }

    private AdapterRequest buildRequest(ResolvedContext ctx, Map<String, Object> params, String action) {
        AdapterRequest.AdapterRequestBuilder builder = AdapterRequest.builder()
                .tenantConfig(ctx.tenant())
                .template(ctx.template())
                .datasource(ctx.datasource())
                .params(params)
                .customSqlMode(ctx.isUsingCustomSql());

        AccessType type = ctx.datasource().getAccessType();
        if (type != null && type.isDb()) {
            builder.resolvedSql(resolveSql(ctx.tenant(), ctx.actionConfig(), ctx.template(), action));
        } else {
            builder.resolvedApiPath(resolveApiPath(ctx.actionConfig(), ctx.template(), action));
        }
        return builder.build();
    }

    /**
     * 解析最终执行的 SQL. 双判断 (tier + customSql 字段):
     * <ul>
     *   <li>tier == PREMIUM 且 customSql 非空 → 走 custom_sql (过 SqlWhitelistValidator 严校验)</li>
     *   <li>其他任何组合 → 走 template.sql_template (由 DBA 团队预审)</li>
     * </ul>
     *
     * <p>降级语义: 商户从 PREMIUM 降级到 STANDARD 时, customSql 字段保留不删,
     * 但运行时不再生效, 优雅 fallback 到模板 SQL. 未来重新升级 PREMIUM 时
     * customSql 自动恢复. 写入路径 (TenantActionConfigService.validateCustomSql)
     * 仍只允许 PREMIUM 写入 customSql, 安全边界不变.
     */
    private String resolveSql(TenantConfig tenant, TenantActionConfig actionConfig,
                              ActionTemplate template, String action) {
        boolean usingCustomSql = tenant.getTier() == TenantTier.PREMIUM
                && actionConfig.getCustomSql() != null
                && !actionConfig.getCustomSql().isBlank();

        if (usingCustomSql) {
            sqlWhitelistValidator.validate(actionConfig.getCustomSql(),
                    SqlWhitelistValidator.Source.TENANT_CUSTOM);
            return actionConfig.getCustomSql();
        }

        // 降级提示: 配了 customSql 但 tier 不是 PREMIUM → 字段保留, 不生效, 走模板
        if (actionConfig.getCustomSql() != null && !actionConfig.getCustomSql().isBlank()) {
            log.info("租户 {} action={} 已降级 (tier={}), customSql 配置保留但不生效, 走模板 SQL",
                    tenant.getTenantId(), action, tenant.getTier());
        }

        String sql = template.getSqlTemplate();
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(
                    ErrorCode.TEMPLATE_NOT_FOUND,
                    "模板 " + action + " 未配置 sql_template");
        }
        // 模板 SQL 已在 ActionTemplateService.create/update 写入路径上预校验过 (Source.TEMPLATE),
        // 这里不再重复扫描. 唯一例外是 custom_sql, 已在上方分支严校验.
        return sql;
    }

    private String resolveApiPath(TenantActionConfig actionConfig, ActionTemplate template,
                                   String action) {
        if (actionConfig.getCustomApiPath() != null && !actionConfig.getCustomApiPath().isBlank()) {
            return actionConfig.getCustomApiPath();
        }
        String path = template.getApiPath();
        if (path == null || path.isBlank()) {
            throw new BusinessException(
                    ErrorCode.TEMPLATE_NOT_FOUND,
                    "模板 " + action + " 未配置 api_path");
        }
        return path;
    }

    private BusinessAdapter findAdapter(AccessType accessType) {
        return adapters.stream()
                .filter(a -> a.supports(accessType))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SYSTEM_ERROR,
                        "未找到支持 " + accessType + " 的适配器"));
    }

    /** 承载一次调用全链路解析出的上下文 */
    private record ResolvedContext(
            TenantConfig tenant,
            TenantActionConfig actionConfig,
            ActionTemplate template,
            TenantDatasource datasource) {

        /**
         * 双判断 (tier + customSql 字段) 决定运行时是否走 custom_sql 路径:
         * <ul>
         *   <li>tier == PREMIUM 且 customSql 非空 → true (走自由模式: 跳过 ParamValidator,
         *       padding 从 SQL 解析, customSqlMode 标志为 true)</li>
         *   <li>其他任何组合 → false (走模板路径, 含 PREMIUM 降级到 STANDARD 的场景)</li>
         * </ul>
         * 跟 {@code resolveSql} 的判断条件保持完全一致, 避免行为不一致.
         */
        boolean isUsingCustomSql() {
            return tenant.getTier() == TenantTier.PREMIUM
                    && actionConfig.getCustomSql() != null
                    && !actionConfig.getCustomSql().isBlank();
        }
    }
}
