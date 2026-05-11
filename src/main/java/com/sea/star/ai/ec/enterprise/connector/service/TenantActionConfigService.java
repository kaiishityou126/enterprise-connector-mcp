package com.sea.star.ai.ec.enterprise.connector.service;

import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.exception.ActionNotAuthorizedException;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.exception.DatasourceNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.exception.SqlValidationException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.ActionAuthChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.service.security.SqlWhitelistValidator;
import com.sea.star.ai.ec.enterprise.connector.util.SchemaUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户动作授权服务 (Phase 6 新增).
 * <p>
 * tenant_action_config 从 Phase 5 的"可选覆盖"升级为"必要授权白名单":
 *   - grant  = INSERT 一行
 *   - update = UPDATE 已存在的行 (修 override / customSql / enabled)
 *   - revoke = DELETE 一行 (物理删, 撤销就该干净, 审计走 audit_log)
 *   - grantAllDefaults = 批量把所有 enabled 模板都授给某租户
 * <p>
 * 授权时校验:
 *   - 指向的 template 存在且 enabled
 *   - 指向的 datasource 存在 (按 datasourceNameOverride ?? template.datasourceName)
 *   - customSql 非空时必须 PREMIUM 租户, 且过 SqlWhitelistValidator
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantActionConfigService {

    private final TenantActionConfigMapper tenantActionConfigMapper;
    private final TenantConfigService tenantConfigService;
    private final TenantDatasourceService tenantDatasourceService;
    private final ActionTemplateService actionTemplateService;
    private final SqlWhitelistValidator sqlWhitelistValidator;
    private final ApplicationEventPublisher eventPublisher;

    public TenantActionConfig get(String tenantId, String action) {
        TenantActionConfig cfg = tenantActionConfigMapper.findByTenantAndAction(tenantId, action);
        if (cfg == null) throw new ActionNotAuthorizedException(tenantId, action);
        return cfg;
    }

    public List<TenantActionConfig> listByTenant(String tenantId) {
        return tenantActionConfigMapper.findByTenantId(tenantId);
    }

    /**
     * 授权一个 action 给租户. 已存在同 (tenantId, action) 时抛冲突 (请用 update).
     */
    @Transactional
    public TenantActionConfig grant(String tenantId, String action, TenantActionConfig spec) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(spec.getTemplateId(), "templateId 不能为空");

        if (tenantActionConfigMapper.findByTenantAndAction(tenantId, action) != null) {
            throw new BusinessException(ErrorCode.BIZ_ERROR,
                    "重复授权 tenantId=" + tenantId + ", action=" + action + " (请用 PUT 更新, 或先 DELETE 再 grant)");
        }

        TenantConfig tenant = tenantConfigService.getConfigAllowDisabled(tenantId);
        ActionTemplate template = actionTemplateService.getById(spec.getTemplateId());

        // 校验 datasource 存在
        String effectiveDs = spec.getDatasourceNameOverride() != null && !spec.getDatasourceNameOverride().isBlank()
                ? spec.getDatasourceNameOverride()
                : template.getDatasourceName();
        if (effectiveDs == null || effectiveDs.isBlank()) effectiveDs = "default";
        try {
            tenantDatasourceService.getDatasource(tenantId, effectiveDs);
        } catch (DatasourceNotFoundException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "授权失败: 数据源 " + tenantId + "/" + effectiveDs + " 不存在, 请先创建数据源");
        }

        validateCustomParams(spec.getCustomParams());
        validateCustomSql(tenant, spec.getCustomSql(), template, spec.getCustomParams());

        spec.setTenantId(tenantId);
        spec.setAction(action);
        if (spec.getEnabled() == null) spec.setEnabled(Boolean.TRUE);
        tenantActionConfigMapper.insert(spec);

        log.info("Admin 授权 tenantId={} action={} templateId={} ds={}",
                tenantId, action, spec.getTemplateId(), effectiveDs);
        eventPublisher.publishEvent(new ActionAuthChangedEvent(this, tenantId));
        return spec;
    }

    /** 更新已有授权 (customSql / override / enabled). templateId 不能改 */
    @Transactional
    public TenantActionConfig update(String tenantId, String action, TenantActionConfig patch) {
        TenantActionConfig existing = tenantActionConfigMapper.findByTenantAndAction(tenantId, action);
        if (existing == null) throw new ActionNotAuthorizedException(tenantId, action);

        if (!StringUtils.isEmpty(patch.getDatasourceNameOverride())) {
            String ds = patch.getDatasourceNameOverride();
            if (!ds.isBlank()) {
                // 校验目标 ds 存在
                try {
                    tenantDatasourceService.getDatasource(tenantId, ds);
                } catch (DatasourceNotFoundException e) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "更新失败: 数据源 " + tenantId + "/" + ds + " 不存在");
                }
            }
            existing.setDatasourceNameOverride(ds.isBlank() ? null : ds);
        } else {
            existing.setDatasourceNameOverride(null);
        }
        // customParams 先 patch 到 existing, 让后续 validateCustomSql 用合并后视图做子集校验
        if (!StringUtils.isEmpty(patch.getCustomParams())) {
            String trimmed = patch.getCustomParams().isBlank() ? null : patch.getCustomParams();
            validateCustomParams(trimmed);
            existing.setCustomParams(trimmed);
        } else {
            existing.setCustomParams(null);
        }
        if (!StringUtils.isEmpty(patch.getCustomSql())) {
            TenantConfig tenant = tenantConfigService.getConfigAllowDisabled(tenantId);
            ActionTemplate template = actionTemplateService.getById(existing.getTemplateId());
            validateCustomSql(tenant, patch.getCustomSql(), template, existing.getCustomParams());
            existing.setCustomSql(patch.getCustomSql().isBlank() ? null : patch.getCustomSql());
        } else {
            existing.setCustomSql(null);
        }
        if (!StringUtils.isEmpty(patch.getCustomApiPath())) {
            existing.setCustomApiPath(patch.getCustomApiPath().isBlank() ? null : patch.getCustomApiPath());
        } else {
            existing.setCustomApiPath(null);
        }
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());

        // ignoreNulls=false: 让 existing.{customSql/customParams/customApiPath/datasourceNameOverride}
        // 被设为 null 时, DB 真的 UPDATE col=NULL. 默认 update(entity) 是 ignoreNulls=true,
        // 会静默跳过 null 字段, 让"清空"动作丢失. 复合主键 (tenant_id, action) 通过 WHERE
        // 定位不会被覆盖; grantedAt / templateId 写入 existing 原值无变化.
        tenantActionConfigMapper.update(existing, false);
        log.info("Admin 更新授权 tenantId={} action={}", tenantId, action);
        eventPublisher.publishEvent(new ActionAuthChangedEvent(this, tenantId));
        return existing;
    }

    /** 撤销授权 (物理删除) */
    @Transactional
    public void revoke(String tenantId, String action) {
        int rows = tenantActionConfigMapper.deleteByTenantAndAction(tenantId, action);
        if (rows == 0) throw new ActionNotAuthorizedException(tenantId, action);
        log.info("Admin 撤销授权 tenantId={} action={}", tenantId, action);
        eventPublisher.publishEvent(new ActionAuthChangedEvent(this, tenantId));
    }

    /**
     * 批量授权: 把所有 enabled 模板都授给该租户. 已授权的跳过, 不抛错.
     * 对应接口: POST /admin/tenants/{tid}/actions/grant-all-defaults
     *
     * @return 本次新增授权的 action 列表
     */
    @Transactional
    public List<String> grantAllDefaults(String tenantId) {
        // 校验租户存在
        tenantConfigService.getConfigAllowDisabled(tenantId);

        List<ActionTemplate> templates = actionTemplateService.findAllEnabled();
        List<TenantActionConfig> existing = tenantActionConfigMapper.findByTenantId(tenantId);
        java.util.Set<String> alreadyGranted = new java.util.HashSet<>();
        for (TenantActionConfig c : existing) alreadyGranted.add(c.getAction());

        List<String> newlyGranted = new java.util.ArrayList<>();
        for (ActionTemplate t : templates) {
            if (alreadyGranted.contains(t.getAction())) continue;
            // 对应 ds 不存在就跳过, 不抛错 (批量接口应尽量宽容)
            String dsName = (t.getDatasourceName() != null && !t.getDatasourceName().isBlank())
                    ? t.getDatasourceName() : "default";
            try {
                tenantDatasourceService.getDatasource(tenantId, dsName);
            } catch (DatasourceNotFoundException e) {
                log.info("批量授权跳过 tenantId={} action={} ds={} 不存在", tenantId, t.getAction(), dsName);
                continue;
            }
            TenantActionConfig cfg = TenantActionConfig.builder()
                    .tenantId(tenantId)
                    .action(t.getAction())
                    .templateId(t.getTemplateId())
                    .enabled(Boolean.TRUE)
                    .build();
            tenantActionConfigMapper.insert(cfg);
            newlyGranted.add(t.getAction());
        }
        log.info("Admin 批量授权 tenantId={} newly={} skipped={}",
                tenantId, newlyGranted.size(), templates.size() - newlyGranted.size());
        if (!newlyGranted.isEmpty()) {
            eventPublisher.publishEvent(new ActionAuthChangedEvent(this, tenantId));
        }
        return newlyGranted;
    }

    /**
     * customSql 校验, 三道关:
     * <ol>
     *   <li>非 PREMIUM 租户直接拒</li>
     *   <li>SqlWhitelistValidator 严校验 (长度/分号/AST 必须 SELECT/函数黑名单)</li>
     *   <li>占位符子集约束: custom_sql 引用的 :name 必须 ⊆ (模板 paramSchema ∪ tenant.customParams).
     *       两边任意一边声明了, AI 都能传 (PerTenantToolCallbackProvider 暴露合并后视图给 AI).
     *       超集字段 AI 永远看不到, 会被 padding 静默补 null 导致 "等价 NULL" 怪查询. 写入时拦.</li>
     * </ol>
     */
    private void validateCustomSql(TenantConfig tenant, String customSql,
                                   ActionTemplate template, String customParams) {
        if (customSql == null || customSql.isBlank()) return;
        if (tenant.getTier() != TenantTier.PREMIUM) {
            throw new SqlValidationException("非 premium 租户不允许配置 custom_sql");
        }
        sqlWhitelistValidator.validate(customSql, SqlWhitelistValidator.Source.TENANT_CUSTOM);

        Set<String> sqlParams = SqlWhitelistValidator.extractNamedParameters(customSql);
        if (sqlParams.isEmpty()) return;
        // 合并视图: 模板 paramSchema ∪ 租户 customParams (任一侧空时合并是恒等)
        String mergedSchema = SchemaUtils.mergeParamSchemas(template.getParamSchema(), customParams);
        Set<String> mergedFields = SchemaUtils.fieldNames(mergedSchema);
        Set<String> unknown = new LinkedHashSet<>(sqlParams);
        unknown.removeAll(mergedFields);
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM,
                    "custom_sql 引用了 schema 未声明的占位符: " + unknown
                            + " (AI 看不到, 永远不会传, 运行时会被 padding 补 null)。"
                            + " 当前合并字段: " + mergedFields
                            + "; 如需扩参数, 请在 action_template.param_schema 或 tenant_action_config.custom_params 中添加");
        }
    }

    /** 校验 customParams 是合法 JSON object (基本格式), null/空 视为合法 (字段未配). */
    private void validateCustomParams(String customParams) {
        try {
            SchemaUtils.assertValidCustomParams(customParams);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
