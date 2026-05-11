package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.integration.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复合主键 (tenant_id, action) 的核心验证 —— 这是从 MP 迁 Flex 的主要动因,
 * 必须证明 Flex 原生支持能按复合键区分查询、更新、删除。
 */
@Transactional
@Rollback
class TenantActionConfigMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantActionConfigMapper mapper;

    @Autowired
    private TenantConfigMapper tenantConfigMapper;

    @Autowired
    private ActionTemplateMapper templateMapper;

    private Integer tpl1;
    private Integer tpl2;

    @BeforeEach
    void setupFkDeps() {
        // Phase 6: tenant_config 只含身份; access_type 迁到 tenant_datasource
        tenantConfigMapper.insert(TenantConfig.builder()
                .tenantId("tac_t1")
                .tenantName("t1")
                .tier(TenantTier.PREMIUM)
                .enabled(true).build());
        tenantConfigMapper.insert(TenantConfig.builder()
                .tenantId("tac_t2")
                .tenantName("t2")
                .tier(TenantTier.STANDARD)
                .enabled(true).build());

        ActionTemplate a1 = ActionTemplate.builder()
                .action("listOrders").accessType(AccessType.POSTGRES).name("list")
                .datasourceName("default").enabled(true).build();
        ActionTemplate a2 = ActionTemplate.builder()
                .action("countOrders").accessType(AccessType.POSTGRES).name("count")
                .datasourceName("default").enabled(true).build();
        templateMapper.insert(a1);
        templateMapper.insert(a2);
        tpl1 = a1.getTemplateId();
        tpl2 = a2.getTemplateId();
    }

    @Test
    @DisplayName("同租户不同 action 不会被 deleteById(tenantId) 误删")
    void compositeKeyDistinctActions() {
        mapper.insert(cfg("tac_t1", "listOrders", tpl1, "SELECT 1"));
        mapper.insert(cfg("tac_t1", "countOrders", tpl2, "SELECT 2"));

        // 按 (tenantId, action) 精确查
        TenantActionConfig list = mapper.findByTenantAndAction("tac_t1", "listOrders");
        TenantActionConfig count = mapper.findByTenantAndAction("tac_t1", "countOrders");
        assertThat(list.getCustomSql()).isEqualTo("SELECT 1");
        assertThat(count.getCustomSql()).isEqualTo("SELECT 2");
    }

    @Test
    @DisplayName("findByTenantId 返回该租户全部 action 配置")
    void findByTenantIdReturnsAll() {
        mapper.insert(cfg("tac_t1", "listOrders", tpl1, "SELECT 1"));
        mapper.insert(cfg("tac_t1", "countOrders", tpl2, "SELECT 2"));
        mapper.insert(cfg("tac_t2", "listOrders", tpl1, "SELECT from t2"));

        List<TenantActionConfig> t1 = mapper.findByTenantId("tac_t1");
        assertThat(t1).hasSize(2);
        assertThat(t1).extracting(TenantActionConfig::getAction)
                .containsExactlyInAnyOrder("listOrders", "countOrders");

        List<TenantActionConfig> t2 = mapper.findByTenantId("tac_t2");
        assertThat(t2).hasSize(1);
        assertThat(t2.get(0).getAction()).isEqualTo("listOrders");
    }

    @Test
    @DisplayName("deleteByTenantAndAction 只删一条, 同租户其他 action 保留")
    void deleteByCompositeKeyOnly() {
        mapper.insert(cfg("tac_t1", "listOrders", tpl1, "a"));
        mapper.insert(cfg("tac_t1", "countOrders", tpl2, "b"));

        int deleted = mapper.deleteByTenantAndAction("tac_t1", "listOrders");
        assertThat(deleted).isEqualTo(1);

        assertThat(mapper.findByTenantAndAction("tac_t1", "listOrders")).isNull();
        assertThat(mapper.findByTenantAndAction("tac_t1", "countOrders")).isNotNull();
    }

    @Test
    @DisplayName("同 action 跨租户不会相互影响 (联合键区分 tenant_id)")
    void sameActionDifferentTenants() {
        mapper.insert(cfg("tac_t1", "listOrders", tpl1, "SELECT 1"));
        mapper.insert(cfg("tac_t2", "listOrders", tpl1, "SELECT 2"));

        TenantActionConfig x = mapper.findByTenantAndAction("tac_t1", "listOrders");
        TenantActionConfig y = mapper.findByTenantAndAction("tac_t2", "listOrders");
        assertThat(x.getCustomSql()).isEqualTo("SELECT 1");
        assertThat(y.getCustomSql()).isEqualTo("SELECT 2");
    }

    @Test
    @DisplayName("update 后 custom_sql 持久化改变")
    void updateCompositeKeyed() {
        mapper.insert(cfg("tac_t1", "listOrders", tpl1, "old"));
        TenantActionConfig one = mapper.findByTenantAndAction("tac_t1", "listOrders");
        one.setCustomSql("new-sql");
        mapper.update(one);

        TenantActionConfig after = mapper.findByTenantAndAction("tac_t1", "listOrders");
        assertThat(after.getCustomSql()).isEqualTo("new-sql");
    }

    @Test
    @DisplayName("custom_params JSONB 字段 round-trip")
    void customParamsJsonb() {
        TenantActionConfig c = cfg("tac_t1", "listOrders", tpl1, null);
        c.setCustomParams("{\"maxRows\":500,\"timeout\":30}");
        mapper.insert(c);

        TenantActionConfig loaded = mapper.findByTenantAndAction("tac_t1", "listOrders");
        assertThat(loaded.getCustomParams()).contains("maxRows").contains("500");
    }

    private TenantActionConfig cfg(String tenantId, String action, Integer tplId, String sql) {
        return TenantActionConfig.builder()
                .tenantId(tenantId)
                .action(action)
                .templateId(tplId)
                .customSql(sql)
                .enabled(true)
                .build();
    }
}
