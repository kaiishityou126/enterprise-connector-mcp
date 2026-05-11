package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.integration.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/**
 * TenantConfig CRUD + 软删 + 枚举 + 自动填充 的集成测试 (Phase 6 版本).
 * <p>
 * Phase 6 起 TenantConfig 只含身份 + 租户级策略, 数据源迁到 TenantDatasource,
 * JSONB api_headers / db_url 等字段相关用例迁到 TenantDatasourceMapperIT.
 */
@Transactional
@Rollback
class TenantConfigMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantConfigMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("insert + selectOneById round-trip 正确")
    void insertAndSelect() {
        TenantConfig c = sample("t_insert", TenantTier.STANDARD);
        mapper.insert(c);

        TenantConfig loaded = mapper.selectOneById("t_insert");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTenantId()).isEqualTo("t_insert");
        assertThat(loaded.getTier()).isEqualTo(TenantTier.STANDARD);
        assertThat(loaded.getRateLimitQps()).isEqualTo(10);
    }

    @Test
    @DisplayName("AutoFillListener: insert 时 createdAt/updatedAt 自动填充")
    void autoFillOnInsert() {
        TenantConfig c = sample("t_autofill", TenantTier.PREMIUM);
        assertThat(c.getCreatedAt()).isNull();

        mapper.insert(c);

        TenantConfig loaded = mapper.selectOneById("t_autofill");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("AutoFillListener: update 时 updatedAt 被刷新, createdAt 保留")
    void autoFillOnUpdate() throws InterruptedException {
        TenantConfig c = sample("t_upd", TenantTier.STANDARD);
        mapper.insert(c);
        LocalDateTime originalCreated = mapper.selectOneById("t_upd").getCreatedAt();

        Thread.sleep(20);

        TenantConfig reload = mapper.selectOneById("t_upd");
        reload.setTenantName("new-name");
        mapper.update(reload);

        TenantConfig after = mapper.selectOneById("t_upd");
        assertThat(after.getTenantName()).isEqualTo("new-name");
        assertThat(after.getCreatedAt()).isEqualTo(originalCreated);
        assertThat(after.getUpdatedAt()).isAfterOrEqualTo(originalCreated);
    }

    @Test
    @DisplayName("deleteById 是软删, Flex 自动过滤已软删记录")
    void softDeleteFiltersOut() {
        TenantConfig c = sample("t_del", TenantTier.STANDARD);
        mapper.insert(c);
        assertThat(mapper.selectOneById("t_del")).isNotNull();

        // Flex 对 @Column(isLogicDelete=true) 自动改写 DELETE 为 UPDATE deleted = true
        mapper.deleteById("t_del");

        // 再查应为 null (自动带 WHERE deleted = false)
        assertThat(mapper.selectOneById("t_del")).isNull();

        // 物理上该行还在, deleted 标志已置 true
        Integer physicalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_config WHERE tenant_id = ?", Integer.class, "t_del");
        assertThat(physicalCount).isEqualTo(1);
        Boolean deleted = jdbc.queryForObject(
                "SELECT deleted FROM tenant_config WHERE tenant_id = ?", Boolean.class, "t_del");
        assertThat(deleted).isTrue();
    }

    @Test
    @DisplayName("enum TenantTier.PREMIUM 写入读取保持一致")
    void enumPremiumRoundTrip() {
        TenantConfig c = sample("t_prem", TenantTier.PREMIUM);
        mapper.insert(c);

        TenantConfig loaded = mapper.selectOneById("t_prem");
        assertThat(loaded.getTier()).isEqualTo(TenantTier.PREMIUM);

        String dbValue = jdbc.queryForObject(
                "SELECT tier FROM tenant_config WHERE tenant_id = ?",
                String.class, "t_prem");
        assertThat(dbValue).isEqualTo("PREMIUM");
    }

    @Test
    @DisplayName("不存在的 tenantId 返回 null 而不是抛异常")
    void selectMissingReturnsNull() {
        assertThat(mapper.selectOneById("does-not-exist")).isNull();
    }

    private TenantConfig sample(String id, TenantTier tier) {
        return TenantConfig.builder()
                .tenantId(id)
                .tenantName("tenant-" + id)
                .tier(tier)
                .rateLimitQps(10)
                .enabled(true)
                .build();
    }
}
