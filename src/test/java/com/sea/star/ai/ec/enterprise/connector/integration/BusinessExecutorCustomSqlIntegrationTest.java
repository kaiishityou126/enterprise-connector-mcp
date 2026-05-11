package com.sea.star.ai.ec.enterprise.connector.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.exception.ParamValidationException;
import com.sea.star.ai.ec.enterprise.connector.exception.SqlValidationException;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import com.sea.star.ai.ec.enterprise.connector.service.BusinessExecutor;
import com.sea.star.ai.ec.enterprise.connector.service.TenantActionConfigService;
import com.sea.star.ai.ec.enterprise.connector.service.TenantConfigService;
import com.sea.star.ai.ec.enterprise.connector.service.TenantDatasourceService;
import com.sea.star.ai.ec.enterprise.connector.util.EncryptionUtils;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PREMIUM 租户 custom_sql "完全自由模式" 端到端校验:
 * <ul>
 *   <li>校验阶段跳过模板 paramSchema (验证 ParamValidator 跳过)</li>
 *   <li>Padding 阶段从 custom_sql 自身解析占位符补 null (验证 padFromSql 生效)</li>
 *   <li>非 PREMIUM 路径仍被拒 (回归保护)</li>
 *   <li>未配置 custom_sql 走模板路径时, 模板 paramSchema 仍生效 (回归保护)</li>
 * </ul>
 */
class BusinessExecutorCustomSqlIntegrationTest extends AbstractIntegrationTest {

    @Autowired private BusinessExecutor businessExecutor;
    @Autowired private TenantConfigService tenantConfigService;
    @Autowired private TenantDatasourceService tenantDatasourceService;
    @Autowired private ActionTemplateService actionTemplateService;
    @Autowired private TenantActionConfigService tenantActionConfigService;
    @Autowired private TenantActionConfigMapper actionConfigMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EncryptionUtils encryptionUtils;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM tenant_action_config");
        jdbc.update("DELETE FROM tenant_datasource");
        jdbc.update("DELETE FROM action_template");
        jdbc.update("DELETE FROM tenant_config");
    }

    @Test
    @DisplayName("场景 A: PREMIUM custom_sql 跳过 ParamValidator (模板有 required, 调用方不传, 仍执行)")
    void premiumCustomSqlSkipsParamValidator() {
        TenantConfig premium = TenantConfig.builder()
                .tenantId("vip").tenantName("VIP").tier(TenantTier.PREMIUM).enabled(true).build();
        tenantConfigService.create(premium);
        tenantDatasourceService.create(realPgDs("vip", "default"));

        ActionTemplate tpl = ActionTemplate.builder()
                .action("queryOrder").accessType(AccessType.POSTGRES)
                .name("查订单").datasourceName("default")
                .sqlTemplate("SELECT :oid AS x")
                .paramSchema("{\"oid\":{\"required\":true,\"type\":\"string\"}}")  // 模板要求 oid 必填
                .enabled(true).build();
        actionTemplateService.create(tpl);

        // PREMIUM 配 custom_sql 不引用 oid, 调用方也不传 oid
        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("vip").action("queryOrder").templateId(tpl.getTemplateId())
                .customSql("SELECT 42 AS answer")
                .enabled(true).build());

        // 如果不跳过 ParamValidator, 会因为 oid 必填而抛 ParamValidationException
        UnifiedResult result = businessExecutor.execute("vip", "queryOrder", Map.of());
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("场景 B: PREMIUM custom_sql 用 :foo 可选过滤, 不传 foo, padding 从 SQL 解析补 null")
    void premiumCustomSqlPaddingFromSql() {
        TenantConfig premium = TenantConfig.builder()
                .tenantId("vip").tenantName("VIP").tier(TenantTier.PREMIUM).enabled(true).build();
        tenantConfigService.create(premium);
        tenantDatasourceService.create(realPgDs("vip", "default"));

        ActionTemplate tpl = ActionTemplate.builder()
                .action("listOrders").accessType(AccessType.POSTGRES)
                .name("列订单").datasourceName("default")
                .sqlTemplate("SELECT 1")  // 模板 SQL 跟 custom_sql 不同, padding 必须按 custom_sql 解析才对
                .paramSchema("{}")        // 模板 schema 空
                .enabled(true).build();
        actionTemplateService.create(tpl);

        // custom_sql 用 :foo 但调用方不传 → padding 必须从 SQL 解析把 foo 补 null
        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("vip").action("listOrders").templateId(tpl.getTemplateId())
                .customSql("SELECT 1 AS id WHERE (:foo::text IS NULL OR :foo::text = 'never')")
                .enabled(true).build());

        UnifiedResult result = businessExecutor.execute("vip", "listOrders", Map.of());
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("场景 C: 非 PREMIUM 配 custom_sql 仍被拒")
    void nonPremiumCustomSqlRejected() {
        tenantConfigService.create(TenantConfig.builder()
                .tenantId("std").tenantName("Std").tier(TenantTier.STANDARD).enabled(true).build());
        tenantDatasourceService.create(realPgDs("std", "default"));

        ActionTemplate tpl = ActionTemplate.builder()
                .action("queryOrder").accessType(AccessType.POSTGRES)
                .name("查订单").datasourceName("default")
                .sqlTemplate("SELECT 1")
                .enabled(true).build();
        actionTemplateService.create(tpl);

        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("std").action("queryOrder").templateId(tpl.getTemplateId())
                .customSql("SELECT 99 AS x")
                .enabled(true).build());

        assertThatThrownBy(() -> businessExecutor.execute("std", "queryOrder", Map.of()))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("非 premium");
    }

    @Test
    @DisplayName("场景 D: 模板路径回归 — 没配 custom_sql 时, 模板 paramSchema 校验仍生效")
    void templatePathParamValidatorStillEnforced() {
        tenantConfigService.create(TenantConfig.builder()
                .tenantId("std").tenantName("Std").tier(TenantTier.STANDARD).enabled(true).build());
        tenantDatasourceService.create(realPgDs("std", "default"));

        ActionTemplate tpl = ActionTemplate.builder()
                .action("queryOrder").accessType(AccessType.POSTGRES)
                .name("查订单").datasourceName("default")
                .sqlTemplate("SELECT :oid AS x")
                .paramSchema("{\"oid\":{\"required\":true,\"type\":\"string\"}}")
                .enabled(true).build();
        actionTemplateService.create(tpl);

        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("std").action("queryOrder").templateId(tpl.getTemplateId())
                .enabled(true).build());

        // 没传 oid, 模板 paramSchema 必填校验应触发
        assertThatThrownBy(() -> businessExecutor.execute("std", "queryOrder", new HashMap<>()))
                .isInstanceOf(ParamValidationException.class);

        // 传了 oid, 模板路径正常执行
        assertThatCode(() -> businessExecutor.execute("std", "queryOrder", Map.of("oid", "ORD-1")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("场景 E: PREMIUM grant custom_sql 引用模板未声明的占位符 → 写入时拒绝")
    void grantRejectsCustomSqlWithUnknownPlaceholder() {
        TenantConfig premium = TenantConfig.builder()
                .tenantId("vip2").tenantName("VIP2").tier(TenantTier.PREMIUM).enabled(true).build();
        tenantConfigService.create(premium);
        tenantDatasourceService.create(realPgDs("vip2", "default"));

        ActionTemplate tpl = ActionTemplate.builder()
                .action("queryOrder").accessType(AccessType.POSTGRES)
                .name("查订单").datasourceName("default")
                .sqlTemplate("SELECT :oid AS x")
                .paramSchema("{\"oid\":{\"type\":\"string\"}}")  // 模板只声明 oid
                .enabled(true).build();
        actionTemplateService.create(tpl);

        // PREMIUM 配 custom_sql 引用 :status (模板没声明) → 写入时拒
        TenantActionConfig spec = TenantActionConfig.builder()
                .templateId(tpl.getTemplateId())
                .customSql("SELECT :oid, :status FROM dual_like")
                .enabled(true).build();

        assertThatThrownBy(() -> tenantActionConfigService.grant("vip2", "queryOrder", spec))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode()
                        == ErrorCode.CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM)
                .hasMessageContaining("status")
                .hasMessageContaining("oid");
    }

    @Test
    @DisplayName("场景 F: PREMIUM grant custom_sql 占位符是模板字段子集 → 放行")
    void grantAcceptsCustomSqlSubsetOfSchema() {
        TenantConfig premium = TenantConfig.builder()
                .tenantId("vip3").tenantName("VIP3").tier(TenantTier.PREMIUM).enabled(true).build();
        tenantConfigService.create(premium);
        tenantDatasourceService.create(realPgDs("vip3", "default"));

        ActionTemplate tpl = ActionTemplate.builder()
                .action("queryOrder").accessType(AccessType.POSTGRES)
                .name("查订单").datasourceName("default")
                .sqlTemplate("SELECT * FROM orders WHERE id = :oid AND status = :status")
                .paramSchema("{\"oid\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"}}")
                .enabled(true).build();
        actionTemplateService.create(tpl);

        // custom_sql 只引用 :oid (是 schema 子集), 加了字面量过滤
        TenantActionConfig spec = TenantActionConfig.builder()
                .templateId(tpl.getTemplateId())
                .customSql("SELECT *, amount * 1.13 AS with_tax FROM orders WHERE id = :oid AND store_id = 5")
                .enabled(true).build();

        assertThatCode(() -> tenantActionConfigService.grant("vip3", "queryOrder", spec))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("场景 G: PREMIUM 配 customSql 后降级 STANDARD → fallback 模板 SQL, customSql 数据保留")
    void downgradeFallbackToTemplate() {
        // 1) 创建 PREMIUM 租户 + 模板 (paramSchema 含 required oid) + customSql
        TenantConfig premium = TenantConfig.builder()
                .tenantId("vipDown").tenantName("VIP→STD").tier(TenantTier.PREMIUM).enabled(true).build();
        tenantConfigService.create(premium);
        tenantDatasourceService.create(realPgDs("vipDown", "default"));

        ActionTemplate tpl = ActionTemplate.builder()
                .action("queryOrder").accessType(AccessType.POSTGRES)
                .name("查订单").datasourceName("default")
                .sqlTemplate("SELECT :oid AS x")
                .paramSchema("{\"oid\":{\"required\":true,\"type\":\"string\"}}")
                .enabled(true).build();
        actionTemplateService.create(tpl);

        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("vipDown").action("queryOrder").templateId(tpl.getTemplateId())
                .customSql("SELECT 42 AS answer")
                .enabled(true).build());

        // 2) PREMIUM 状态: 走 customSql 正常 (回归保护, 不传 oid 也行因为 customSql 不引用 oid)
        UnifiedResult before = businessExecutor.execute("vipDown", "queryOrder", Map.of());
        assertThat(before.isSuccess()).isTrue();

        // 3) 降级 tier 改为 STANDARD (DB 直接 UPDATE)
        jdbc.update("UPDATE tenant_config SET tier = 'STANDARD' WHERE tenant_id = ?", "vipDown");
        // 清两级缓存中的 tenant_config (force re-read)
        jdbc.update("UPDATE tenant_config SET updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ?", "vipDown");
        TenantConfig refreshed = tenantConfigService.getConfigAllowDisabled("vipDown");
        // 缓存可能仍是旧值, 但下次 BusinessExecutor.resolveContext 拿的是经过 service 的, 取决于实现
        // 这里直接通过 service 暴露给业务层验证
        if (refreshed.getTier() == TenantTier.PREMIUM) {
            // 如果有缓存, 主动 update 让 service 失效缓存
            refreshed.setTier(TenantTier.STANDARD);
            tenantConfigService.update(refreshed);
        }

        // 4) 降级后调用, 不传 oid → 模板 schema oid 必填校验触发
        assertThatThrownBy(() -> businessExecutor.execute("vipDown", "queryOrder", new HashMap<>()))
                .isInstanceOf(ParamValidationException.class);

        // 5) 降级后传 oid → 走模板 SQL 正常 (不抛 SqlValidationException, 优雅 fallback)
        assertThatCode(() -> businessExecutor.execute("vipDown", "queryOrder", Map.of("oid", "ORD-2")))
                .doesNotThrowAnyException();

        // 6) DB 中 customSql 字段仍保留 (没被清空)
        String preserved = jdbc.queryForObject(
                "SELECT custom_sql FROM tenant_action_config WHERE tenant_id = ? AND action = ?",
                String.class, "vipDown", "queryOrder");
        assertThat(preserved).isEqualTo("SELECT 42 AS answer");
    }

    /** 指向 testcontainers 的真 PG, 让 BusinessExecutor 真打到 DB. */
    private TenantDatasource realPgDs(String tenantId, String dsName) {
        return TenantDatasource.builder()
                .tenantId(tenantId)
                .dsName(dsName)
                .accessType(AccessType.POSTGRES)
                .dbUrl(POSTGRES.getJdbcUrl() + "?stringtype=unspecified")
                .dbUsername(POSTGRES.getUsername())
                .dbPasswordEnc(encryptionUtils.encrypt(POSTGRES.getPassword()))
                .dbDriver("org.postgresql.Driver")
                .enabled(true)
                .build();
    }
}
