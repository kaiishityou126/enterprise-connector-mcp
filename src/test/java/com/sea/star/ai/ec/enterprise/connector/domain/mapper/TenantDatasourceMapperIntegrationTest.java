package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.integration.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/**
 * TenantDatasource 复合主键 + JSONB + 软删 + 跨租户同 dsName 的集成测试 (Phase 6).
 */
@Transactional
@Rollback
class TenantDatasourceMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantDatasourceMapper datasourceMapper;

    @Autowired
    private TenantConfigMapper tenantConfigMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setupTenant() {
        tenantConfigMapper.insert(TenantConfig.builder()
                .tenantId("tds_a").tenantName("a").tier(TenantTier.STANDARD).enabled(true).build());
        tenantConfigMapper.insert(TenantConfig.builder()
                .tenantId("tds_b").tenantName("b").tier(TenantTier.PREMIUM).enabled(true).build());
    }

    @Test
    @DisplayName("insert + findByTenantAndDs round-trip")
    void insertAndFind() {
        TenantDatasource ds = sampleDb("tds_a", "orders", "jdbc:postgresql://host/a");
        datasourceMapper.insert(ds);

        TenantDatasource loaded = datasourceMapper.findByTenantAndDs("tds_a", "orders");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTenantId()).isEqualTo("tds_a");
        assertThat(loaded.getDsName()).isEqualTo("orders");
        assertThat(loaded.getDbUrl()).isEqualTo("jdbc:postgresql://host/a");
        assertThat(loaded.getAccessType()).isEqualTo(AccessType.POSTGRES);
    }

    @Test
    @DisplayName("同 dsName 跨租户独立 (tds_a/orders 和 tds_b/orders 互不影响)")
    void sameDsNameAcrossTenants() {
        datasourceMapper.insert(sampleDb("tds_a", "orders", "jdbc:postgresql://a/ordersA"));
        datasourceMapper.insert(sampleDb("tds_b", "orders", "jdbc:mysql://b/ordersB"));

        assertThat(datasourceMapper.findByTenantAndDs("tds_a", "orders").getDbUrl())
                .isEqualTo("jdbc:postgresql://a/ordersA");
        assertThat(datasourceMapper.findByTenantAndDs("tds_b", "orders").getDbUrl())
                .isEqualTo("jdbc:mysql://b/ordersB");
    }

    @Test
    @DisplayName("findByTenantId 返回该租户所有未软删数据源")
    void findByTenantIdFiltersDeleted() {
        datasourceMapper.insert(sampleDb("tds_a", "orders", "u1"));
        datasourceMapper.insert(sampleDb("tds_a", "inventory", "u2"));
        datasourceMapper.insert(sampleDb("tds_a", "crm", "u3"));

        List<TenantDatasource> list = datasourceMapper.findByTenantId("tds_a");
        assertThat(list).hasSize(3);
        assertThat(list).extracting(TenantDatasource::getDsName)
                .containsExactlyInAnyOrder("orders", "inventory", "crm");

        // 软删一条
        datasourceMapper.deleteByTenantAndDs("tds_a", "inventory");
        List<TenantDatasource> afterDelete = datasourceMapper.findByTenantId("tds_a");
        assertThat(afterDelete).hasSize(2);
        assertThat(afterDelete).extracting(TenantDatasource::getDsName)
                .containsExactlyInAnyOrder("orders", "crm");
    }

    @Test
    @DisplayName("deleteByTenantAndDs 是软删 (Flex isLogicDelete), 物理上仍在")
    void softDeleteWorks() {
        datasourceMapper.insert(sampleDb("tds_a", "orders", "u1"));
        int deleted = datasourceMapper.deleteByTenantAndDs("tds_a", "orders");
        assertThat(deleted).isEqualTo(1);

        // Flex 过滤后查不到
        assertThat(datasourceMapper.findByTenantAndDs("tds_a", "orders")).isNull();

        // 物理行还在, deleted=true
        Boolean deletedFlag = jdbc.queryForObject(
                "SELECT deleted FROM tenant_datasource WHERE tenant_id = ? AND ds_name = ?",
                Boolean.class, "tds_a", "orders");
        assertThat(deletedFlag).isTrue();
    }

    @Test
    @DisplayName("API 类型数据源 + JSONB api_headers")
    void apiTypeWithJsonbHeaders() {
        TenantDatasource ds = TenantDatasource.builder()
                .tenantId("tds_a").dsName("crm_api")
                .accessType(AccessType.API)
                .apiBaseUrl("https://crm.example.com")
                .apiAuthType("BEARER")
                .apiTokenEnc("enc:abc")
                .apiHeaders("{\"X-Merchant\":\"A\",\"X-Env\":\"prod\"}")
                .enabled(true).build();
        datasourceMapper.insert(ds);

        TenantDatasource loaded = datasourceMapper.findByTenantAndDs("tds_a", "crm_api");
        assertThat(loaded.getAccessType()).isEqualTo(AccessType.API);
        assertThat(loaded.getApiHeaders()).contains("X-Merchant").contains("X-Env");

        String pgType = jdbc.queryForObject(
                "SELECT pg_typeof(api_headers)::text FROM tenant_datasource WHERE tenant_id = ? AND ds_name = ?",
                String.class, "tds_a", "crm_api");
        assertThat(pgType).isEqualTo("jsonb");
    }

    @Test
    @DisplayName("update 修改字段 (URL/密码)")
    void updateFields() {
        datasourceMapper.insert(sampleDb("tds_a", "orders", "jdbc:old"));
        TenantDatasource reload = datasourceMapper.findByTenantAndDs("tds_a", "orders");
        reload.setDbUrl("jdbc:new");
        reload.setDbPasswordEnc("enc:new");
        datasourceMapper.update(reload);

        TenantDatasource after = datasourceMapper.findByTenantAndDs("tds_a", "orders");
        assertThat(after.getDbUrl()).isEqualTo("jdbc:new");
        assertThat(after.getDbPasswordEnc()).isEqualTo("enc:new");
    }

    private TenantDatasource sampleDb(String tenantId, String dsName, String dbUrl) {
        return TenantDatasource.builder()
                .tenantId(tenantId)
                .dsName(dsName)
                .accessType(AccessType.POSTGRES)
                .dbUrl(dbUrl)
                .dbUsername("u")
                .dbPasswordEnc("enc:" + dsName)
                .enabled(true)
                .build();
    }
}
