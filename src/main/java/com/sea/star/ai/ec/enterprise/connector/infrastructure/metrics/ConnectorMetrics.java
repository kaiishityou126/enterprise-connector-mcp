package com.sea.star.ai.ec.enterprise.connector.infrastructure.metrics;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TaskStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.function.ToDoubleFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 连接器业务指标工厂，集中管理所有自定义 Micrometer 指标。
 *
 * 设计约束：
 *   - 所有指标名以 `connector.` 前缀，和 Spring Boot / Resilience4j / Micrometer 内置指标区分
 *   - 带 tenant 维度的 tag 不用裸 tenantId（高基数 OOM 风险），统一走 `tenantId` tag 但
 *     依赖上游只在 100 量级的稳定租户集合内标记（max-tenants 上限=100）
 *   - Counter / Timer 按需即时注册（同 name+tags 组合会复用），Gauge 需要提供值回调函数
 *
 * 对应 DEVELOPMENT_PLAN.md §8.2 的指标清单。
 */
@Component
@RequiredArgsConstructor
public class ConnectorMetrics {

    public static final String NS = "connector";

    // ---- metric names ----
    private static final String REQUEST_TOTAL    = NS + ".request.total";
    private static final String REQUEST_DURATION = NS + ".request.duration";
    private static final String CACHE_HIT        = NS + ".cache.hit";
    private static final String CACHE_MISS       = NS + ".cache.miss";
    private static final String DATASOURCE_POOL_SIZE = NS + ".datasource.pool.size";
    private static final String ASYNC_TASK_ACTIVE = NS + ".async.task.active";
    private static final String ASYNC_TASK_TOTAL  = NS + ".async.task.total";

    private final MeterRegistry registry;

    /**
     * 业务请求计数 (tenant × action × status).
     * status 取值: SUCCESS / FAILED / <ErrorCode>
     */
    public void incrementRequest(String tenantId, String action, String status) {
        Counter.builder(REQUEST_TOTAL)
                .tags(Tags.of("tenant", safeTag(tenantId),
                              "action", safeTag(action),
                              "status", safeTag(status)))
                .register(registry)
                .increment();
    }

    /**
     * 业务请求耗时 (tenant × action). 成功/失败都记。
     */
    public Timer.Sample startRequestTimer() {
        return Timer.start(registry);
    }

    public void stopRequestTimer(Timer.Sample sample, String tenantId, String action) {
        sample.stop(Timer.builder(REQUEST_DURATION)
                .tags(Tags.of("tenant", safeTag(tenantId), "action", safeTag(action)))
                .register(registry));
    }

    /**
     * 缓存命中 (level=L1|L2, cache=tenantConfig|actionTemplate|...).
     */
    public void incrementCacheHit(String cacheName, String level) {
        Counter.builder(CACHE_HIT)
                .tags(Tags.of("cache", safeTag(cacheName), "level", safeTag(level)))
                .register(registry)
                .increment();
    }

    public void incrementCacheMiss(String cacheName) {
        Counter.builder(CACHE_MISS)
                .tags(Tags.of("cache", safeTag(cacheName)))
                .register(registry)
                .increment();
    }

    /**
     * 注册 DataSource 池大小 gauge. 调用方需持有一个稳定的 Supplier, 周期采样。
     * 应只注册一次 (由 TenantDataSourceManager 在启动时调一次)。
     */
    public <T> void registerDataSourcePoolGauge(T source, ToDoubleFunction<T> sizeFn) {
        io.micrometer.core.instrument.Gauge.builder(DATASOURCE_POOL_SIZE, source, sizeFn)
                .description("当前租户 HikariCP DataSource 池大小 (租户数, 非连接数)")
                .register(registry);
    }

    /**
     * 异步任务状态 gauge 注册 (PENDING/RUNNING 两个状态按需分别注册).
     * 传 supplier, micrometer 周期采样。
     */
    public <T> void registerAsyncActiveGauge(String statusTag, T source, ToDoubleFunction<T> fn) {
        io.micrometer.core.instrument.Gauge.builder(ASYNC_TASK_ACTIVE, source, fn)
                .tags(Tags.of("status", safeTag(statusTag)))
                .description("当前处于该 status 的异步任务总数")
                .register(registry);
    }

    /**
     * 异步任务生命周期事件计数 (tenant × action × status).
     * status: SUBMITTED / SUCCESS / FAILED / TIMEOUT / RETRY / DUPLICATE
     */
    public void incrementAsyncTask(String tenantId, String action, TaskStatus status) {
        Counter.builder(ASYNC_TASK_TOTAL)
                .tags(Tags.of("tenant", safeTag(tenantId),
                              "action", safeTag(action),
                              "status", status.name()))
                .register(registry)
                .increment();
    }

    public void incrementAsyncTask(String tenantId, String action, String statusLabel) {
        Counter.builder(ASYNC_TASK_TOTAL)
                .tags(Tags.of("tenant", safeTag(tenantId),
                              "action", safeTag(action),
                              "status", safeTag(statusLabel)))
                .register(registry)
                .increment();
    }

    /** null / 空串保护, 防 Prometheus 标签值为空串被拒绝 */
    private static String safeTag(String v) {
        return (v == null || v.isEmpty()) ? "unknown" : v;
    }
}
