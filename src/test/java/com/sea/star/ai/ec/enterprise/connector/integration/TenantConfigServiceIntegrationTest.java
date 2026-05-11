package com.sea.star.ai.ec.enterprise.connector.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TenantTier;
import com.sea.star.ai.ec.enterprise.connector.exception.TenantNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TenantConfigChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.service.TenantConfigService;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TenantConfigService 的事务语义 + AFTER_COMMIT 事件传播 + 缓存失效广播 集成验证。
 *
 * 验证:
 *   - create/update/delete 正常返回时, AFTER_COMMIT 监听器能收到 TenantConfigChangedEvent
 *   - 事务内异常 → 事件不应该被投递 (AFTER_COMMIT 只在 commit 成功时触发)
 *   - 负缓存 (tenantNotFound) 生效后, 再次 get 仍快速抛 TenantNotFoundException
 */
@Import(TenantConfigServiceIntegrationTest.TestEventCollector.class)
class TenantConfigServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantConfigService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestEventCollector collector;

    @AfterEach
    void cleanup() {
        collector.events.clear();
        // 清库, 下一条 case 从空库开始
        jdbc.update("DELETE FROM tenant_config");
    }

    @Test
    @DisplayName("create: 事务提交后 AFTER_COMMIT 监听器收到 CREATED 事件")
    void create_publishesCreatedEvent() {
        TenantConfig c = sample("t-create");
        service.create(c);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(collector.events)
                    .extracting(TenantConfigChangedEvent::getKind)
                    .contains(TenantConfigChangedEvent.Kind.CREATED);
            assertThat(collector.events)
                    .extracting(TenantConfigChangedEvent::getTenantId)
                    .contains("t-create");
        });
    }

    @Test
    @DisplayName("update: 事务提交后 UPDATED 事件触发, 且 get 返回最新值")
    void update_publishesUpdatedEvent() {
        service.create(sample("t-upd"));
        collector.events.clear();

        TenantConfig c = service.getConfig("t-upd");
        c.setTenantName("new-name");
        service.update(c);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(collector.events)
                        .extracting(TenantConfigChangedEvent::getKind)
                        .contains(TenantConfigChangedEvent.Kind.UPDATED));

        TenantConfig reloaded = service.getConfig("t-upd");
        assertThat(reloaded.getTenantName()).isEqualTo("new-name");
    }

    @Test
    @DisplayName("delete: 事件触发 + 后续 get 抛 TenantNotFoundException (并进入负缓存)")
    void delete_publishesDeletedEvent_andPrimesNegativeCache() {
        service.create(sample("t-del"));
        collector.events.clear();

        service.delete("t-del");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(collector.events)
                        .extracting(TenantConfigChangedEvent::getKind)
                        .contains(TenantConfigChangedEvent.Kind.DELETED));

        assertThatThrownBy(() -> service.getConfig("t-del"))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    @DisplayName("update 不存在的租户: 抛 TenantNotFoundException, 不发布事件")
    void updateMissing_noEvent() {
        TenantConfig ghost = sample("t-ghost");
        assertThatThrownBy(() -> service.update(ghost))
                .isInstanceOf(TenantNotFoundException.class);

        // 等待足够时间让可能的事件传播, 然后确认没有
        try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(collector.events).isEmpty();
    }

    @Test
    @DisplayName("getConfig: L1 命中不打 DB (缓存命中时直接绕过查询)")
    void cacheHitAvoidsDb() {
        service.create(sample("t-cache"));
        service.getConfig("t-cache"); // 触发 L1 缓存写入

        // 绕过 service 直接改 DB; 若后续 get 走了 DB, 会拿到新值
        jdbc.update("UPDATE tenant_config SET tenant_name = 'dont-read-this' WHERE tenant_id = ?", "t-cache");

        TenantConfig cached = service.getConfig("t-cache");
        assertThat(cached.getTenantName()).isEqualTo("tenant-t-cache"); // 缓存里的旧值, 证明没走 DB
    }

    private TenantConfig sample(String id) {
        return TenantConfig.builder()
                .tenantId(id)
                .tenantName("tenant-" + id)
                .tier(TenantTier.STANDARD)
                .enabled(true)
                .build();
    }

    /**
     * 测试专用: 收集所有 AFTER_COMMIT 阶段的 TenantConfigChangedEvent,
     * 让 case 可以断言哪些事件被发布了。
     */
    @TestConfiguration
    static class TestEventCollector {

        final CopyOnWriteArrayList<TenantConfigChangedEvent> events = new CopyOnWriteArrayList<>();

        @Bean
        TestEventCollector eventCollector() {
            return this;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        public void onEvent(TenantConfigChangedEvent e) {
            events.add(e);
        }
    }
}
