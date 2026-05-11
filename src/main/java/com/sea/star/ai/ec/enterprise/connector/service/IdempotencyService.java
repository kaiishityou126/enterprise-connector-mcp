package com.sea.star.ai.ec.enterprise.connector.service;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * MCP 请求级幂等服务（网络抖动导致 OpenClaw 重发相同请求时去重）。
 *
 * 机制：Redis SET NX EX —— 第一次请求占位并执行，
 * TTL 内的相同 requestId 直接返回上次结果。
 *
 * 使用场景：
 *   McpToolService 在入口调用：
 *     return idempotencyService.execute(tenantId, requestId, () -> businessExecutor.execute(...));
 *
 * Redis 不可用时降级：直接调用 supplier，不做幂等（功能优先于一致性）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String DICT_IDEMPOTENT_KEY_TTL = "security.idempotent_key_ttl";

    private final SysDictService sysDictService;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 带幂等保护的执行。
     *
     * @param tenantId   租户 ID（与 requestId 共同构成 key）
     * @param requestId  请求 ID（MCP 协议头传入，调用方保证唯一性）
     * @param supplier   实际业务调用
     */
    public UnifiedResult execute(String tenantId, String requestId,
                                 Supplier<UnifiedResult> supplier) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(supplier, "supplier 不能为空");

        // 缺失 requestId 时不做幂等，直接执行（OpenClaw 应保证传入）
        if (requestId == null || requestId.isBlank()) {
            return supplier.get();
        }

        // Redis 不可用时降级
        if (stringRedisTemplate == null) {
            log.debug("Redis 不可用, 幂等服务降级直接执行 tenantId={}, requestId={}",
                    tenantId, requestId);
            return supplier.get();
        }

        String key = BusinessConstants.REDIS_PREFIX_IDEMPOTENT + tenantId + ":" + requestId;
        long ttl = sysDictService.getLong(DICT_IDEMPOTENT_KEY_TTL, 300);

        // 尝试占位，拿到锁则执行；没拿到则查缓存结果
        Boolean acquired;
        try {
            acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "PROCESSING", Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("Redis 访问失败, 幂等服务降级直接执行 key={}", key, e);
            return supplier.get();
        }

        if (Boolean.TRUE.equals(acquired)) {
            // 首次请求：执行并缓存**元数据**（不缓存 data 字段，避免敏感数据滞留 Redis）
            try {
                UnifiedResult result = supplier.get();
                try {
                    stringRedisTemplate.opsForValue().set(
                            key, JsonUtils.toJson(toMetadata(result)),
                            Duration.ofSeconds(ttl));
                } catch (Exception e) {
                    log.warn("幂等结果写缓存失败 key={}", key, e);
                }
                return result;
            } catch (RuntimeException e) {
                safeDelete(key);
                throw e;
            }
        }

        // 非首次：查缓存
        String cached;
        try {
            cached = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("幂等结果读取失败, 降级执行 key={}", key, e);
            return supplier.get();
        }

        if (cached == null || "PROCESSING".equals(cached)) {
            throw new IllegalStateException("相同 requestId 的请求正在处理中, 请稍后重试");
        }

        log.info("幂等命中, 返回缓存元数据 tenantId={}, requestId={}", tenantId, requestId);
        // 命中的只是元数据（无 data）。同步业务若需要完整数据，客户端应记录第一次的结果。
        // 异步路径下 taskId 是可见的，客户端可以凭 taskId 去查 /admin/tasks/{taskId}。
        return JsonUtils.fromJson(cached, UnifiedResult.class);
    }

    /**
     * 构造只含"身份信息"的结果副本（success/code/message/taskId/timestamp），不含 data。
     * 避免业务查询数据（订单/客户信息等）通过幂等缓存滞留 Redis 被二次读取。
     */
    private UnifiedResult toMetadata(UnifiedResult full) {
        if (full == null) return null;
        return UnifiedResult.builder()
                .success(full.isSuccess())
                .code(full.getCode())
                .message(full.getMessage())
                .taskId(full.getTaskId())
                .traceId(full.getTraceId())
                .timestamp(full.getTimestamp())
                .build();
    }

    private void safeDelete(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("幂等 key 删除失败 key={}", key, e);
        }
    }
}
