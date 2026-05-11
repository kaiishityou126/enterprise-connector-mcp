package com.sea.star.ai.ec.enterprise.connector.service;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.AsyncTaskTableDef.ASYNC_TASK;

import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.config.AsyncConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.AsyncTaskMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AsyncTask;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TaskStatus;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.metrics.ConnectorMetrics;
import com.sea.star.ai.ec.enterprise.connector.service.security.CallbackUrlValidator;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import com.sea.star.ai.ec.enterprise.connector.util.TraceIds;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.concurrent.Executor;
import com.sea.star.ai.ec.enterprise.connector.config.AsyncConfig;

/**
 * 异步任务服务：提交、执行、重试、回调、防重复、超时扫描。
 *
 * 任务生命周期：
 *   PENDING → RUNNING → SUCCESS
 *                     → FAILED → 重新 PENDING（retry_count < max_retries）
 *                              → FAILED（超限）
 *                     → TIMEOUT（超时扫描）
 *
 * 关键设计：
 *   - selfProxy 用 @Lazy setter 注入, 拿到的是带 @Async 增强的代理;
 *     避免 ApplicationContext.getBean 方式触发 Spring 3.2+ 的循环引用检测
 *   - 事务 afterCommit 触发 runAsync, 避免 @Async 线程先于事务可见
 *   - callbackUrl 在 submit 阶段过 CallbackUrlValidator 防 SSRF
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskService {

    private static final String DICT_DEFAULT_MAX_RETRIES = "async.default_max_retries";
    private static final String DICT_DEFAULT_TIMEOUT_SECONDS = "async.default_timeout_seconds";
    private static final String DICT_MAX_TASKS_PER_TENANT = "async.max_tasks_per_tenant";
    private static final String DICT_CALLBACK_RETRY_MAX = "async.callback_retry_max";
    private static final String DICT_CALLBACK_RETRY_BACKOFF_MS = "async.callback_retry_backoff_ms";
    private static final String DICT_DEDUP_LOOKBACK_MINUTES = "async.dedup_lookback_minutes";

    private final AsyncTaskMapper asyncTaskMapper;
    private final SysDictService sysDictService;
    private final WebClient.Builder webClientBuilder;
    private final CallbackUrlValidator callbackUrlValidator;
    private final ConnectorMetrics metrics;

    /** 专用回调线程池, 避免慢上游占住主业务线程池的 worker (见 AsyncConfig) */
    @Qualifier(AsyncConfig.CALLBACK_EXECUTOR)
    private final Executor callbackExecutor;

    /**
     * 启动时注册 async.task.active gauge —— 分别按 PENDING / RUNNING 采样当前任务数。
     * Micrometer 周期轮询时调用 countByStatus(status)。
     */
    @PostConstruct
    void registerMetrics() {
        metrics.registerAsyncActiveGauge("PENDING", this, s -> s.countByStatus(TaskStatus.PENDING));
        metrics.registerAsyncActiveGauge("RUNNING", this, s -> s.countByStatus(TaskStatus.RUNNING));
    }

    private long countByStatus(TaskStatus status) {
        try {
            return asyncTaskMapper.selectCountByQuery(QueryWrapper.create()
                    .where(ASYNC_TASK.STATUS.eq(status)));
        } catch (Exception e) {
            // gauge 采样失败不能抛出影响主流程
            return -1;
        }
    }

    /**
     * 自代理：Spring @Async 只对通过代理的外部调用生效, 同类内部直接调用不会异步化。
     * 用 @Lazy setter 注入, Spring 会注入一个代理, 首次调用时才解析真实 bean —— 这样
     * 即使 AsyncTaskService 自引用, 也不会触发 Spring 3.2+ 的循环引用检测 fail-fast。
     */
    private AsyncTaskService selfProxy;

    @Autowired
    void setSelfProxy(@Lazy AsyncTaskService selfProxy) {
        this.selfProxy = selfProxy;
    }

    /**
     * 由 BusinessExecutor.@PostConstruct 注入的执行函数，
     * 避免 BusinessExecutor ↔ AsyncTaskService 构造器循环依赖。
     */
    private Function<AsyncTask, UnifiedResult> executionHandler;

    public void setExecutionHandler(Function<AsyncTask, UnifiedResult> handler) {
        this.executionHandler = handler;
    }

    /**
     * 提交异步任务。
     *
     * @return 已创建（或去重命中的）taskId
     */
    @Transactional
    public String submit(String tenantId, String action, Map<String, Object> params,
                         String callbackUrl) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        callbackUrlValidator.validate(callbackUrl);

        AsyncTask existing = findDuplicate(tenantId, action, params);
        if (existing != null) {
            log.info("命中防重复：返回已有任务 taskId={}", existing.getTaskId());
            metrics.incrementAsyncTask(tenantId, action, "DUPLICATE");
            return existing.getTaskId();
        }

        int inflight = countInflight(tenantId);
        int maxPerTenant = sysDictService.getInt(DICT_MAX_TASKS_PER_TENANT, 5);
        if (inflight >= maxPerTenant) {
            throw new IllegalStateException(
                    "租户 " + tenantId + " 异步任务并发已达上限 " + maxPerTenant);
        }

        int timeoutSeconds = sysDictService.getInt(DICT_DEFAULT_TIMEOUT_SECONDS, 300);
        int maxRetries = sysDictService.getInt(DICT_DEFAULT_MAX_RETRIES, 3);
        AsyncTask task = AsyncTask.builder()
                .taskId(UUID.randomUUID().toString().replace("-", ""))
                .tenantId(tenantId)
                .action(action)
                .params(JsonUtils.toJson(params))
                .status(TaskStatus.PENDING)
                .retryCount(0)
                .maxRetries(maxRetries)
                .createdAt(LocalDateTime.now())
                .timeoutAt(LocalDateTime.now().plusSeconds(timeoutSeconds))
                .callbackUrl(callbackUrl)
                .build();
        asyncTaskMapper.insert(task);
        log.info("异步任务已提交 taskId={}, tenantId={}, action={}",
                task.getTaskId(), tenantId, action);
        metrics.incrementAsyncTask(tenantId, action, "SUBMITTED");

        // 关键：事务提交后再异步触发，避免 @Async 线程先于 insert 可见
        scheduleRunAfterCommit(task.getTaskId());
        return task.getTaskId();
    }

    private void scheduleRunAfterCommit(String taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            selfProxy.runAsync(taskId);
                        }
                    });
        } else {
            // 无活动事务时（直接调用），立即异步
            selfProxy.runAsync(taskId);
        }
    }

    /**
     * 执行异步任务。必须通过 selfProxy 调用才能走 @Async 代理。
     */
    @Async(AsyncConfig.ASYNC_EXECUTOR)
    public void runAsync(String taskId) {
        if (executionHandler == null) {
            log.warn("executionHandler 未初始化，任务推迟 taskId={}（将由后续重试或恢复流程处理）",
                    taskId);
            return;
        }
        AsyncTask task = asyncTaskMapper.selectOneById(taskId);
        if (task == null) {
            log.warn("异步任务执行失败：taskId 不存在 {}", taskId);
            return;
        }
        executeInternal(task);
    }

    private void executeInternal(AsyncTask task) {
        if (TenantContext.getCurrentTenant() == null) {
            TenantContext.setCurrentTenant(task.getTenantId());
        }
        TraceIds.currentOrGenerate();

        updateStatus(task.getTaskId(), TaskStatus.RUNNING,
                t -> t.setStartedAt(LocalDateTime.now()));

        UnifiedResult result = null;
        String errorMessage = null;
        boolean success = false;
        try {
            result = executionHandler.apply(task);
            success = result != null && result.isSuccess();
            if (!success && result != null) {
                errorMessage = result.getMessage();
            }
        } catch (Exception e) {
            log.error("异步任务执行异常 taskId={}", task.getTaskId(), e);
            errorMessage = e.getMessage();
        }

        if (success) {
            markSuccess(task.getTaskId(), result);
            if (task.getCallbackUrl() != null && !task.getCallbackUrl().isBlank()) {
                // 回调提交到独立线程池, 不阻塞主 async-task-* worker.
                // 上游慢 / 超时 / 重试都关在 callback-* 池里, 不影响新任务执行.
                final String taskId = task.getTaskId();
                final String cbUrl = task.getCallbackUrl();
                final UnifiedResult cbResult = result;
                try {
                    callbackExecutor.execute(() -> callbackWithRetry(taskId, cbUrl, cbResult));
                } catch (java.util.concurrent.RejectedExecutionException rex) {
                    // callback 池满 (AbortPolicy), 降级为丢回调; 结果已持久化在 async_task 表
                    log.warn("回调线程池已满, 跳过本次回调 taskId={}, url={}", taskId, cbUrl);
                }
            }
        } else {
            handleFailure(task, errorMessage);
        }
    }

    private void handleFailure(AsyncTask task, String errorMessage) {
        AsyncTask current = asyncTaskMapper.selectOneById(task.getTaskId());
        if (current == null) return;

        int nextRetry = (current.getRetryCount() == null ? 0 : current.getRetryCount()) + 1;
        int maxRetries = current.getMaxRetries() != null
                ? current.getMaxRetries()
                : sysDictService.getInt(DICT_DEFAULT_MAX_RETRIES, 3);

        if (nextRetry < maxRetries) {
            current.setStatus(TaskStatus.PENDING);
            current.setRetryCount(nextRetry);
            current.setErrorMessage(errorMessage);
            asyncTaskMapper.update(current);
            log.warn("任务失败重试 {}/{} taskId={}, error={}",
                    nextRetry, maxRetries, task.getTaskId(), errorMessage);
            metrics.incrementAsyncTask(current.getTenantId(), current.getAction(), "RETRY");
            // 通过 selfProxy 走 @Async 代理，避免自调用在当前 worker 递归执行
            selfProxy.runAsync(task.getTaskId());
        } else {
            current.setStatus(TaskStatus.FAILED);
            current.setFinishedAt(LocalDateTime.now());
            current.setErrorMessage(errorMessage);
            asyncTaskMapper.update(current);
            log.error("任务最终失败 taskId={}, error={}", task.getTaskId(), errorMessage);
            metrics.incrementAsyncTask(current.getTenantId(), current.getAction(), TaskStatus.FAILED);
        }
    }

    private void markSuccess(String taskId, UnifiedResult result) {
        AsyncTask t = asyncTaskMapper.selectOneById(taskId);
        if (t == null) return;
        t.setStatus(TaskStatus.SUCCESS);
        t.setFinishedAt(LocalDateTime.now());
        t.setResult(JsonUtils.toJson(result));
        t.setErrorMessage(null);
        asyncTaskMapper.update(t);
        log.info("任务完成 taskId={}", taskId);
        metrics.incrementAsyncTask(t.getTenantId(), t.getAction(), TaskStatus.SUCCESS);
    }

    private void updateStatus(String taskId, TaskStatus status,
                              java.util.function.Consumer<AsyncTask> mutator) {
        AsyncTask t = asyncTaskMapper.selectOneById(taskId);
        if (t == null) return;
        t.setStatus(status);
        mutator.accept(t);
        asyncTaskMapper.update(t);
    }

    private void callbackWithRetry(String taskId, String callbackUrl, UnifiedResult result) {
        int maxRetry = sysDictService.getInt(DICT_CALLBACK_RETRY_MAX, 3);
        long backoffMs = sysDictService.getLong(DICT_CALLBACK_RETRY_BACKOFF_MS, 1000);
        WebClient client = webClientBuilder.clone().build();

        for (int attempt = 0; attempt < maxRetry; attempt++) {
            try {
                client.post().uri(callbackUrl)
                        .bodyValue(result)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(5))
                        .block();
                log.info("回调成功 taskId={}, url={}, attempt={}", taskId, callbackUrl, attempt + 1);
                return;
            } catch (Exception e) {
                long delay = backoffMs * (1L << attempt);
                log.warn("回调失败 taskId={}, attempt={}/{}, 下次 {}ms 后重试",
                        taskId, attempt + 1, maxRetry, delay, e);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("回调最终失败 taskId={}, url={}, 结果已持久化在 async_task 表",
                taskId, callbackUrl);
    }

    // -- 查询辅助 --

    /**
     * 基于 PostgreSQL JSONB 等值比较的防重复检查（DB 侧过滤，不在 Java 侧遍历）。
     */
    private AsyncTask findDuplicate(String tenantId, String action, Map<String, Object> params) {
        int lookbackMinutes = sysDictService.getInt(DICT_DEDUP_LOOKBACK_MINUTES, 10);
        LocalDateTime since = LocalDateTime.now().minusMinutes(lookbackMinutes);
        String paramsJson = JsonUtils.toJson(params == null ? Map.of() : params);
        return asyncTaskMapper.findDuplicateInflight(tenantId, action, paramsJson, since);
    }

    private int countInflight(String tenantId) {
        return Math.toIntExact(asyncTaskMapper.selectCountByQuery(QueryWrapper.create()
                .where(ASYNC_TASK.TENANT_ID.eq(tenantId))
                .and(ASYNC_TASK.STATUS.in(TaskStatus.PENDING, TaskStatus.RUNNING))));
    }

    // -- 定时任务：超时扫描（分页）--

    /**
     * 每分钟扫描 RUNNING 且超时的任务标记为 TIMEOUT。
     * Mapper 内部 LIMIT 1000 分批拉取，避免一次性加载所有候选行。
     */
    @Scheduled(fixedDelayString = "${connector.async.timeout-scan-interval-ms:60000}")
    public void scanTimeout() {
        LocalDateTime now = LocalDateTime.now();
        int total = 0;
        while (true) {
            List<AsyncTask> batch = asyncTaskMapper.findByStatusAndTimeoutBefore(
                    TaskStatus.RUNNING, now);
            if (batch.isEmpty()) break;
            for (AsyncTask t : batch) {
                t.setStatus(TaskStatus.TIMEOUT);
                t.setFinishedAt(now);
                t.setErrorMessage("执行超时");
                asyncTaskMapper.update(t);
                metrics.incrementAsyncTask(t.getTenantId(), t.getAction(), TaskStatus.TIMEOUT);
            }
            total += batch.size();
            if (batch.size() < 1000) break; // 不足一批说明已扫完
        }
        if (total > 0) {
            log.warn("超时扫描：标记 {} 个任务为 TIMEOUT", total);
        }
    }
}
