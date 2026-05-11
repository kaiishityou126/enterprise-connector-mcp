package com.sea.star.ai.ec.enterprise.connector.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.exception.TenantNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.cache.TwoLevelCacheManager;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TenantConfigChangedEvent;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户配置服务 (Phase 6 精简): 只管租户身份 + 租户级策略.
 * <p>
 * 数据源生命周期和密文解密已迁到 {@link TenantDatasourceService},
 * 本类不再持有 EncryptionUtils。
 * <p>
 * 两级缓存 key: {@code tenantConfig:{tenantId}}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantConfigService {

    private final TenantConfigMapper tenantConfigMapper;
    private final TwoLevelCacheManager cacheManager;
    private final ApplicationEventPublisher eventPublisher;
    /** 给 restore/purge 绕开 Flex logic-delete 自动过滤用 */
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 负缓存：记录"最近查过、确认不存在"的 tenantId，防止恶意请求打爆 DB。
     * 只在本机 L1，短 TTL（1 分钟），不进 Redis —— 其他实例各自维护即可，代价低。
     */
    private final Cache<String, Boolean> notFoundCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    /**
     * 根据租户 ID 获取配置 (业务调用用). 走两级缓存, DB 兜底.
     * 找不到抛 TenantNotFoundException; enabled=false 抛 TENANT_DISABLED.
     */
    public TenantConfig getConfig(String tenantId) {
        TenantConfig config = getConfigAllowDisabled(tenantId);
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw new BusinessException(
                    com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode.TENANT_DISABLED,
                    "租户已禁用: " + tenantId);
        }
        return config;
    }

    /**
     * 查租户配置, 跳过 enabled 检查 (运维/Admin 操作用).
     * 仍然会过滤软删记录 (由 Flex logic-delete 保证), 找不到抛 TenantNotFoundException.
     */
    public TenantConfig getConfigAllowDisabled(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");

        // 负缓存: 命中说明该租户近期已确认不存在, 直接短路
        if (Boolean.TRUE.equals(notFoundCache.getIfPresent(tenantId))) {
            throw new TenantNotFoundException(tenantId);
        }

        TenantConfig config = cacheManager.get(
                BusinessConstants.CACHE_TENANT_CONFIG,
                tenantId,
                TenantConfig.class,
                () -> tenantConfigMapper.selectOneById(tenantId));
        if (config == null) {
            notFoundCache.put(tenantId, Boolean.TRUE);
            throw new TenantNotFoundException(tenantId);
        }
        return config;
    }

    @Transactional
    public void create(TenantConfig config) {
        Objects.requireNonNull(config, "config 不能为空");
        Objects.requireNonNull(config.getTenantId(), "tenantId 不能为空");
        tenantConfigMapper.insert(config);
        cacheManager.put(BusinessConstants.CACHE_TENANT_CONFIG, config.getTenantId(), config);
        notFoundCache.invalidate(config.getTenantId());
        publishInvalidate(config.getTenantId());
        eventPublisher.publishEvent(new TenantConfigChangedEvent(
                config.getTenantId(), TenantConfigChangedEvent.Kind.CREATED));
    }

    @Transactional
    public void update(TenantConfig config) {
        Objects.requireNonNull(config, "config 不能为空");
        Objects.requireNonNull(config.getTenantId(), "tenantId 不能为空");
        int rows = tenantConfigMapper.update(config);
        if (rows == 0) throw new TenantNotFoundException(config.getTenantId());
        // 失效缓存而不是直接 put，确保其他字段与 DB 保持一致
        cacheManager.evict(BusinessConstants.CACHE_TENANT_CONFIG, config.getTenantId());
        publishInvalidate(config.getTenantId());
        eventPublisher.publishEvent(new TenantConfigChangedEvent(
                config.getTenantId(), TenantConfigChangedEvent.Kind.UPDATED));
    }

    /**
     * 软删租户 (Phase 6 起 Flex 自动改写 DELETE 为 UPDATE deleted=true).
     * <p>
     * **不级联**: tenant_datasource / tenant_action_config 保留原样。软删后租户走
     * MCP 业务调用会在 BusinessExecutor.resolveContext 第一步 (getConfig) 因 Flex 自动
     * 过滤 deleted=true 而抛 TenantNotFoundException, 业务被拒 — 不需要动下属配置。
     * <p>
     * restore 时只恢复 tenant_config 本身, 下属配置从未被动过, 自然可用。
     * 硬删除 (purge) 才会级联清理下属。
     */
    @Transactional
    public void delete(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        tenantConfigMapper.deleteById(tenantId);
        cacheManager.evict(BusinessConstants.CACHE_TENANT_CONFIG, tenantId);
        notFoundCache.put(tenantId, Boolean.TRUE);
        publishInvalidate(tenantId);
        eventPublisher.publishEvent(new TenantConfigChangedEvent(
                tenantId, TenantConfigChangedEvent.Kind.DELETED));
    }

    /**
     * 恢复软删的租户 (Admin restore 端点用).
     * <p>
     * **只恢复 tenant_config 自身**。下属 tenant_datasource / tenant_action_config
     * 从来没被动过, 不需要恢复。若某个 ds 之前单独软删了, 保持单独软删状态 (语义独立)。
     */
    @Transactional
    public void restore(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        // JdbcTemplate 绕开 Flex logic-delete 自动过滤 (Mapper 的 update 会加 WHERE deleted=false)
        int rows = jdbcTemplate.update(
                "UPDATE tenant_config SET deleted = FALSE, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ?",
                tenantId);
        if (rows == 0) throw new TenantNotFoundException(tenantId);

        cacheManager.evict(BusinessConstants.CACHE_TENANT_CONFIG, tenantId);
        notFoundCache.invalidate(tenantId);
        publishInvalidate(tenantId);
        eventPublisher.publishEvent(new TenantConfigChangedEvent(
                tenantId, TenantConfigChangedEvent.Kind.UPDATED));
        log.info("Admin 恢复租户 tenantId={}", tenantId);
    }

    /**
     * 物理删除租户 (Admin purge 端点用, 不可恢复).
     * 级联物理删除: tenant_datasource + tenant_action_config (这俩表本来就没软删保护或没必要保留).
     * audit_log 保留作为历史记录, 不跟随清理.
     */
    @Transactional
    public void purge(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        jdbcTemplate.update("DELETE FROM tenant_action_config WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_datasource WHERE tenant_id = ?", tenantId);
        int rows = jdbcTemplate.update("DELETE FROM tenant_config WHERE tenant_id = ?", tenantId);
        if (rows == 0) throw new TenantNotFoundException(tenantId);

        cacheManager.evict(BusinessConstants.CACHE_TENANT_CONFIG, tenantId);
        notFoundCache.put(tenantId, Boolean.TRUE);
        publishInvalidate(tenantId);
        eventPublisher.publishEvent(new TenantConfigChangedEvent(
                tenantId, TenantConfigChangedEvent.Kind.DELETED));
        log.warn("Admin 物理删除租户 tenantId={} (不可恢复)", tenantId);
    }

    /**
     * 强制刷新缓存（管理接口用）。同时清空负缓存，下次请求会重新查 DB。
     */
    public void refresh(String tenantId) {
        cacheManager.evict(BusinessConstants.CACHE_TENANT_CONFIG, tenantId);
        notFoundCache.invalidate(tenantId);
        publishInvalidate(tenantId);
        eventPublisher.publishEvent(new TenantConfigChangedEvent(
                tenantId, TenantConfigChangedEvent.Kind.UPDATED));
    }

    private void publishInvalidate(String tenantId) {
        if (stringRedisTemplate == null) return;
        try {
            String payload = BusinessConstants.CACHE_TENANT_CONFIG + "|" + tenantId;
            stringRedisTemplate.convertAndSend(
                    BusinessConstants.CHANNEL_CACHE_INVALIDATE, payload);
        } catch (Exception e) {
            // 广播失败意味着多实例部署时其他实例的 L1 缓存不会同步失效，直到 TTL 过期才收敛。
            // 需要告警干预，升级为 error 级别
            log.error("发布租户缓存失效消息失败，可能导致多实例数据不一致 tenantId={}", tenantId, e);
        }
    }
}
