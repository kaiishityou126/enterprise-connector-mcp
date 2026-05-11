package com.sea.star.ai.ec.enterprise.connector.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.exception.ActionNotAuthorizedException;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.exception.DatasourceNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import com.sea.star.ai.ec.enterprise.connector.service.BusinessExecutor;
import com.sea.star.ai.ec.enterprise.connector.service.TenantConfigService;
import com.sea.star.ai.ec.enterprise.connector.service.TenantDatasourceService;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BusinessExecutor 授权链路 + dsName 解析的集成测试 (Phase 6).
 * <p>
 * 重点:
 *   - 未授权的 action → ACTION_NOT_AUTHORIZED
 *   - 授权的 action 但无对应 ds → DATASOURCE_NOT_FOUND
 *   - tenant_action_config.datasource_name_override 正确覆盖 template 默认 ds
 *   - template.access_type 和 datasource.access_type 不一致 → SYSTEM_ERROR
 * <p>
 * 注: 本测试只校验 BusinessExecutor 的"解析 / 校验"前半段, 不实际执行 SQL
 *     (那需要真租户业务库, 成本太高);后半段真实执行在 USAGE_GUIDE 手动走一遍.
 */
class BusinessExecutorAuthorizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BusinessExecutor businessExecutor;

    @Autowired
    private TenantConfigService tenantConfigService;

    @Autowired
    private TenantDatasourceService tenantDatasourceService;

    @Autowired
    private ActionTemplateService actionTemplateService;

    @Autowired
    private TenantActionConfigMapper actionConfigMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Integer templateId;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM tenant_action_config");
        jdbc.update("DELETE FROM tenant_datasource");
        jdbc.update("DELETE FROM action_template");
        jdbc.update("DELETE FROM tenant_config");

        tenantConfigService.create(TenantConfig.builder()
                .tenantId("biz_a").tenantName("A").tier(TenantTier.STANDARD).enabled(true).build());

        ActionTemplate tpl = ActionTemplate.builder()
                .action("queryOrder")
                .accessType(AccessType.POSTGRES)
                .name("查询订单")
                .datasourceName("default")
                .sqlTemplate("SELECT 1")
                .maxRows(10)
                .enabled(true)
                .build();
        actionTemplateService.create(tpl);
        templateId = tpl.getTemplateId();
    }

    @Test
    @DisplayName("未授权的 action → ACTION_NOT_AUTHORIZED")
    void unauthorizedAction() {
        // 有 tenant, 有 template, 有 ds, 但没有 tenant_action_config 授权
        tenantDatasourceService.create(sampleDs("biz_a", "default"));

        assertThatThrownBy(() -> businessExecutor.execute("biz_a", "queryOrder", Map.of()))
                .isInstanceOf(ActionNotAuthorizedException.class);
    }

    @Test
    @DisplayName("授权了但 enabled=false → ACTION_NOT_AUTHORIZED")
    void authorizedButDisabled() {
        tenantDatasourceService.create(sampleDs("biz_a", "default"));
        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("biz_a").action("queryOrder").templateId(templateId)
                .enabled(false) // 临时撤销
                .build());

        assertThatThrownBy(() -> businessExecutor.execute("biz_a", "queryOrder", Map.of()))
                .isInstanceOf(ActionNotAuthorizedException.class);
    }

    @Test
    @DisplayName("授权但对应 ds 不存在 → DATASOURCE_NOT_FOUND")
    void authorizedButNoDatasource() {
        // 有授权但该租户没配任何 datasource
        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("biz_a").action("queryOrder").templateId(templateId)
                .enabled(true).build());

        assertThatThrownBy(() -> businessExecutor.execute("biz_a", "queryOrder", Map.of()))
                .isInstanceOf(DatasourceNotFoundException.class)
                .hasMessageContaining("biz_a")
                .hasMessageContaining("default");
    }

    @Test
    @DisplayName("datasource_name_override 覆盖模板默认 ds_name")
    void overrideResolvesDatasource() {
        // 授权时指定 override 到 "alt"
        tenantDatasourceService.create(sampleDs("biz_a", "alt"));
        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("biz_a").action("queryOrder").templateId(templateId)
                .datasourceNameOverride("alt")
                .enabled(true).build());

        // 应该解析到 alt ds (而不是 template 的 default), 但执行会因 SQL 连 fake jdbc url 失败
        // 我们只验证"解析能找到 ds", 允许最终抛 ADAPTER_DB_ERROR
        assertThatThrownBy(() -> businessExecutor.execute("biz_a", "queryOrder", Map.of()))
                .satisfies(e -> {
                    // 关键: 不应该是 DATASOURCE_NOT_FOUND (说明 override 生效了)
                    assertThat(e).isNotInstanceOf(DatasourceNotFoundException.class);
                });
    }

    @Test
    @DisplayName("template access_type 和 datasource access_type 不匹配 → SYSTEM_ERROR")
    void accessTypeMismatch() {
        // template 是 DB 类, 但 datasource 配成 API 类
        TenantDatasource apiDs = TenantDatasource.builder()
                .tenantId("biz_a").dsName("default")
                .accessType(AccessType.API)  // ← 故意不匹配
                .apiBaseUrl("https://x.example.com")
                .enabled(true).build();
        tenantDatasourceService.create(apiDs);

        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("biz_a").action("queryOrder").templateId(templateId)
                .enabled(true).build());

        assertThatThrownBy(() -> businessExecutor.execute("biz_a", "queryOrder", Map.of()))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.SYSTEM_ERROR)
                .hasMessageContaining("access_type 不匹配");
    }

    @Test
    @DisplayName("租户禁用 → TENANT_DISABLED")
    void tenantDisabled() {
        tenantDatasourceService.create(sampleDs("biz_a", "default"));
        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("biz_a").action("queryOrder").templateId(templateId)
                .enabled(true).build());
        // 禁用租户
        TenantConfig tenant = tenantConfigService.getConfigAllowDisabled("biz_a");
        tenant.setEnabled(false);
        tenantConfigService.update(tenant);

        assertThatThrownBy(() -> businessExecutor.execute("biz_a", "queryOrder", Map.of()))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.TENANT_DISABLED);
    }

    private TenantDatasource sampleDs(String tenantId, String dsName) {
        return TenantDatasource.builder()
                .tenantId(tenantId)
                .dsName(dsName)
                .accessType(AccessType.POSTGRES)
                .dbUrl("jdbc:postgresql://no.such.host:5432/db")
                .dbUsername("u")
                .dbPasswordEnc(dummyEncryptedPassword())
                .enabled(true)
                .build();
    }

    /** 用应用启动的 EncryptionUtils 加密一个占位密码 (让 Manager build 时解密不炸) */
    private String dummyEncryptedPassword() {
        return applicationContextEncryption.encrypt("pwd");
    }

    @Autowired
    private com.sea.star.ai.ec.enterprise.connector.util.EncryptionUtils applicationContextEncryption;
}
