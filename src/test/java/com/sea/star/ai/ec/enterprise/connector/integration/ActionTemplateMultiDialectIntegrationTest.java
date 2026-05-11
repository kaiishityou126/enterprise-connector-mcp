package com.sea.star.ai.ec.enterprise.connector.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import com.sea.star.ai.ec.enterprise.connector.service.BusinessExecutor;
import com.sea.star.ai.ec.enterprise.connector.service.TenantConfigService;
import com.sea.star.ai.ec.enterprise.connector.service.TenantDatasourceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 多方言模板 (Phase 7) 的端到端集成验证.
 *
 * <p>验证项:
 * <ol>
 *   <li>数据库 CHECK 约束接受新枚举值 POSTGRES / MYSQL / SQLSERVER / ORACLE / API</li>
 *   <li>同一 action 不同方言模板可在 action_template 表中共存 (唯一索引按 (action, access_type))</li>
 *   <li>BusinessExecutor 路由按 ds.access_type 匹配 template.access_type, 不一致时拒绝</li>
 *   <li>ActionTemplateService.findAllEnabled 能完整读出所有方言行 (DynamicMcpToolProvider 去重的输入)</li>
 * </ol>
 *
 * <p>注: 不实际执行 SQL (PG 容器跑不动 MySQL 模板), 只测前置解析和校验.
 */
class ActionTemplateMultiDialectIntegrationTest extends AbstractIntegrationTest {

    @Autowired private BusinessExecutor businessExecutor;
    @Autowired private TenantConfigService tenantConfigService;
    @Autowired private TenantDatasourceService tenantDatasourceService;
    @Autowired private ActionTemplateService actionTemplateService;
    @Autowired private TenantActionConfigMapper actionConfigMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM tenant_action_config");
        jdbc.update("DELETE FROM tenant_datasource");
        jdbc.update("DELETE FROM action_template");
        jdbc.update("DELETE FROM tenant_config");
    }

    @Test
    @DisplayName("CHECK 约束接受所有 5 个新枚举值")
    void checkConstraintAcceptsAllDialects() {
        for (AccessType type : AccessType.values()) {
            ActionTemplate tpl = ActionTemplate.builder()
                    .action("probe_" + type.name().toLowerCase())
                    .accessType(type)
                    .name("探测 " + type)
                    .datasourceName("default")
                    .sqlTemplate(type.isDb() ? "SELECT 1" : null)
                    .apiPath(type == AccessType.API ? "/probe" : null)
                    .enabled(true)
                    .build();
            actionTemplateService.create(tpl);
            assertThat(tpl.getTemplateId()).isNotNull();
        }
    }

    @Test
    @DisplayName("同 action 多方言行可共存, findAllEnabled 全部读出")
    void multipleDialectsForSameAction() {
        ActionTemplate pg = baseTpl("queryOrder", AccessType.POSTGRES,
                "SELECT id FROM orders WHERE id=:id LIMIT 1");
        ActionTemplate mysql = baseTpl("queryOrder", AccessType.MYSQL,
                "SELECT id FROM orders WHERE id=:id LIMIT 1");
        actionTemplateService.create(pg);
        actionTemplateService.create(mysql);

        List<ActionTemplate> all = actionTemplateService.findAllEnabled();
        long sameActionCount = all.stream()
                .filter(t -> "queryOrder".equals(t.getAction()))
                .count();
        assertThat(sameActionCount).isEqualTo(2);
    }

    @Test
    @DisplayName("BusinessExecutor 在 ds.access_type 与 template.access_type 不匹配时拒绝")
    void rejectAccessTypeMismatch() {
        // template 是 MYSQL
        ActionTemplate mysqlTpl = baseTpl("queryOrder", AccessType.MYSQL,
                "SELECT id FROM orders WHERE id=:id");
        actionTemplateService.create(mysqlTpl);

        // 租户 ds 是 POSTGRES (与 template 不匹配)
        tenantConfigService.create(TenantConfig.builder()
                .tenantId("biz_a").tenantName("A").tier(TenantTier.STANDARD).enabled(true).build());
        tenantDatasourceService.create(TenantDatasource.builder()
                .tenantId("biz_a").dsName("default").accessType(AccessType.POSTGRES)
                .dbUrl("jdbc:postgresql://nowhere:5432/db")
                .dbDriver("org.postgresql.Driver")
                .enabled(true).build());

        // 授权租户调用 queryOrder, 模板 ID 指向 MYSQL 那行
        actionConfigMapper.insert(TenantActionConfig.builder()
                .tenantId("biz_a").action("queryOrder")
                .templateId(mysqlTpl.getTemplateId())
                .enabled(true).build());

        // 调用应在 step4 (access_type 校验) 失败
        assertThatThrownBy(() -> businessExecutor.execute(
                "biz_a", "queryOrder", Map.of("id", "1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("access_type 不匹配");
    }

    @Test
    @DisplayName("AdminTemplate 写入 SQLSERVER 模板, 同 action 已有 PG 模板时, 元数据一致放行")
    void multiDialectInsertWithConsistentMetadata() {
        ActionTemplate pg = baseTpl("listOrders", AccessType.POSTGRES,
                "SELECT id FROM orders LIMIT 100");
        actionTemplateService.create(pg);

        ActionTemplate sqlserver = baseTpl("listOrders", AccessType.SQLSERVER,
                "SELECT TOP(100) id FROM orders");
        actionTemplateService.create(sqlserver);

        // 两行都能存活, 唯一索引按 (action, access_type) 保证不冲突
        assertThat(actionTemplateService.findAllEnabled()).hasSizeGreaterThanOrEqualTo(2);
    }

    private ActionTemplate baseTpl(String action, AccessType accessType, String sql) {
        return ActionTemplate.builder()
                .action(action)
                .accessType(accessType)
                .name(action)
                .description("多方言测试模板")
                .datasourceName("default")
                .sqlTemplate(sql)
                .paramSchema("{\"id\":\"string\"}")
                .maxRows(100)
                .enabled(true)
                .build();
    }
}
