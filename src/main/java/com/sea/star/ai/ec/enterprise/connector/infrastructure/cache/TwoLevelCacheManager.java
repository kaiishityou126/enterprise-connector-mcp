package com.sea.star.ai.ec.enterprise.connector.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.metrics.ConnectorMetrics;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caffeine(L1) + Redis(L2) 两级缓存。
 *
 * 读取路径: L1 命中 → 返回; L1 miss → L2 命中 → 写回 L1 → 返回; L2 miss → Supplier 回源。
 *
 * 写入/失效路径: put/evict 同时操作 L1 + L2，并由调用方（TenantConfigService 等）
 * 通过 Redis Pub/Sub 广播 key 给其他实例清除本地 L1（见 CacheInvalidationListener）。
 *
 * 多个 cacheName（如 tenantConfig / actionTemplate）共享同一个管理器，
 * 内部按 cacheName 维护独立的 Caffeine Cache 实例。
 */
@Slf4j
@Component
public class TwoLevelCacheManager {

    private final Map<String, Cache<String, Object>> localCaches = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private ConnectorMetrics metrics;

    @Value("${connector.cache.caffeine.expire-after-write-minutes:30}")
    private int localExpireMinutes;

    @Value("${connector.cache.caffeine.maximum-size:1000}")
    private int localMaxSize;

    @Value("${connector.cache.redis.ttl-seconds:3600}")
    private long redisTtlSeconds;

    /**
     * 读取，L1 miss 则走 L2，L2 miss 则调用 supplier 回源并写回两级缓存。
     *
     * @param cacheName 缓存名（用于区分不同业务）
     * @param key       业务 key（不含前缀）
     * @param supplier  回源函数；返回 null 不缓存
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String cacheName, String key, Class<T> type, Supplier<T> supplier) {
        Cache<String, Object> local = getOrCreateLocal(cacheName);

        Object l1 = local.getIfPresent(key);
        if (l1 != null) {
            recordHit(cacheName, "L1");
            return (T) l1;
        }

        String redisKey = buildRedisKey(cacheName, key);
        Object l2 = safeRedisGet(redisKey);
        if (l2 != null) {
            local.put(key, l2);
            recordHit(cacheName, "L2");
            return (T) l2;
        }

        recordMiss(cacheName);
        T sourced = supplier.get();
        if (sourced != null) {
            local.put(key, sourced);
            safeRedisPut(redisKey, sourced);
        }
        return sourced;
    }

    private void recordHit(String cacheName, String level) {
        if (metrics != null) metrics.incrementCacheHit(cacheName, level);
    }

    private void recordMiss(String cacheName) {
        if (metrics != null) metrics.incrementCacheMiss(cacheName);
    }

    /**
     * 主动写入（如新增/更新后）。同步写 L1 + L2。
     */
    public void put(String cacheName, String key, Object value) {
        if (value == null) return;
        getOrCreateLocal(cacheName).put(key, value);
        safeRedisPut(buildRedisKey(cacheName, key), value);
    }

    /**
     * 失效单个 key（同步清 L1 + L2）。广播由调用方负责。
     */
    public void evict(String cacheName, String key) {
        getOrCreateLocal(cacheName).invalidate(key);
        safeRedisDelete(buildRedisKey(cacheName, key));
    }

    /**
     * 仅清本地 L1，不动 L2。用于收到 Pub/Sub 失效消息时。
     */
    public void evictLocal(String cacheName, String key) {
        getOrCreateLocal(cacheName).invalidate(key);
    }

    private Cache<String, Object> getOrCreateLocal(String cacheName) {
        return localCaches.computeIfAbsent(cacheName, name -> Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(localExpireMinutes))
                .maximumSize(localMaxSize)
                .build());
    }

    private String buildRedisKey(String cacheName, String key) {
        return cacheName + ":" + key;
    }

    private Object safeRedisGet(String key) {
        if (redisTemplate == null) return null;
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis L2 读取失败 key={}, 降级只用 L1", key, e);
            return null;
        }
    }

    private void safeRedisPut(String key, Object value) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(redisTtlSeconds));
        } catch (Exception e) {
            log.warn("Redis L2 写入失败 key={}, 降级只写 L1", key, e);
        }
    }

    private void safeRedisDelete(String key) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis L2 删除失败 key={}", key, e);
        }
    }

    /**
     * 留给 Pub/Sub 监听器和 Service 层使用的常量入口。
     */
    public static String channelForCacheInvalidate() {
        return BusinessConstants.CHANNEL_CACHE_INVALIDATE;
    }
}
