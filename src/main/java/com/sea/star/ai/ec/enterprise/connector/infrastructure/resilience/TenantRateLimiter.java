package com.sea.star.ai.ec.enterprise.connector.infrastructure.resilience;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.exception.RateLimitExceededException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TenantConfigChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
import com.sea.star.ai.ec.enterprise.connector.service.TenantConfigService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 按租户的 Resilience4j RateLimiter。
 *
 * 每租户独立限流器：limit = tenant_config.rate_limit_qps 或 sys_dict.resilience.default_qps
 * 刷新周期 1 秒；超限立即抛异常（快速失败，避免请求堆积）。
 *
 * 并发安全：
 *   - 用 Caffeine (maximumSize + expireAfterAccess) 管理 limiter，自动淘汰长期未用的租户
 *   - 淘汰时同步从 Resilience4j Registry 移除，避免内存泄漏
 *   - 通过 Caffeine.get(key, loader) 保证同一 key 的 loader 只执行一次（免竞态）
 */
@Slf4j
@Component
public class TenantRateLimiter {

    private static final String DICT_DEFAULT_QPS = "resilience.default_qps";

    private final TenantConfigService tenantConfigService;
    private final SysDictService sysDictService;
    private final RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();

    /**
     * 存放 limiter 描述符（值本身不直接是 RateLimiter 对象，因为 Resilience4j 的
     * Registry 还需要同步持有；这里只用来触发 accessOrder + 淘汰回调）。
     */
    private final Cache<String, Integer> appliedLimits;

    public TenantRateLimiter(TenantConfigService tenantConfigService,
                              SysDictService sysDictService) {
        this.tenantConfigService = tenantConfigService;
        this.sysDictService = sysDictService;
        this.appliedLimits = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .removalListener((RemovalListener<String, Integer>) (key, val, cause) -> {
                    if (key != null) {
                        registry.remove(key);
                        log.debug("限流器已淘汰 tenantId={}, cause={}", key, cause);
                    }
                })
                .build();
    }

    /**
     * 尝试获取一个令牌；失败抛 RateLimitExceededException（HTTP 429）。
     */
    public void acquire(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        RateLimiter limiter = limiterFor(tenantId);
        if (!limiter.acquirePermission()) {
            throw new RateLimitExceededException(tenantId);
        }
    }

    /**
     * 获取或创建限流器。Caffeine.get(key, loader) 原子化保证 loader 只跑一次。
     * 若期望 QPS 与缓存值不一致（租户升配），invalidate 后重建。
     */
    private RateLimiter limiterFor(String tenantId) {
        int desired = resolveQps(tenantId);
        Integer cached = appliedLimits.getIfPresent(tenantId);
        if (cached != null && cached == desired) {
            return registry.rateLimiter(tenantId);
        }
        // 期望值变化或首次创建：用 Caffeine 的 compute 语义保证同一 key 只构建一次
        appliedLimits.asMap().compute(tenantId, (k, existing) -> {
            if (existing != null && existing == desired) return existing;
            registry.remove(tenantId);
            RateLimiterConfig cfg = RateLimiterConfig.custom()
                    .limitForPeriod(desired)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .timeoutDuration(Duration.ZERO)
                    .build();
            registry.rateLimiter(tenantId, cfg);
            log.info("为租户 {} 初始化/更新限流器: {} QPS", tenantId, desired);
            return desired;
        });
        return registry.rateLimiter(tenantId);
    }

    private int resolveQps(String tenantId) {
        try {
            TenantConfig cfg = tenantConfigService.getConfig(tenantId);
            if (cfg.getRateLimitQps() != null && cfg.getRateLimitQps() > 0) {
                return cfg.getRateLimitQps();
            }
        } catch (Exception ignored) {
            // 租户不存在时下游会抛 TenantNotFoundException，这里先走默认值
        }
        return sysDictService.getInt(DICT_DEFAULT_QPS, 10);
    }

    /**
     * 租户配置更新时强制重建限流器；下次 acquire 自动生效。
     */
    public void evict(String tenantId) {
        appliedLimits.invalidate(tenantId); // 会触发 RemovalListener 同步清 Registry
    }

    /**
     * 监听租户配置变更事件：事务提交后再清理限流器。
     * 避免事务回滚时错误淘汰限流器状态。
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onTenantConfigChanged(TenantConfigChangedEvent event) {
        if (event.getKind() == TenantConfigChangedEvent.Kind.CREATED) return;
        evict(event.getTenantId());
    }
}
