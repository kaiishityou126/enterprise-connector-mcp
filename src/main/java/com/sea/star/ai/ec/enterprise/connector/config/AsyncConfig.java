package com.sea.star.ai.ec.enterprise.connector.config;

import com.sea.star.ai.ec.enterprise.connector.infrastructure.async.TenantContextTaskDecorator;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * @Async 线程池配置.
 *
 * 要点:
 *   - TaskDecorator 负责传递 ThreadLocal (租户 / traceId) 到工作线程
 *   - CallerRunsPolicy: 队列满时由调用线程执行, 起反压作用, 不丢任务
 *   - setWaitForTasksToCompleteOnShutdown + setAwaitTerminationSeconds
 *     让 Spring 关闭时给进行中任务留出完成时间 (配合 server.shutdown=graceful)
 *
 * 两个独立 Executor:
 *   - {@link #ASYNC_EXECUTOR}    (asyncTaskExecutor)     业务异步任务主池
 *   - {@link #CALLBACK_EXECUTOR} (callbackExecutor)      异步任务完成后回调上游的专用池
 *
 * 为什么拆: 回调走 WebClient.block() + 重试退避, 慢上游会占住 worker 整 12s
 * (指数退避 1s/2s/4s × 最多 3 次). 如果跟业务任务共用一个池, 慢回调会饿死新任务.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String ASYNC_EXECUTOR = "asyncTaskExecutor";
    public static final String CALLBACK_EXECUTOR = "callbackExecutor";

    @Value("${connector.async.thread-pool.core-size:10}")
    private int coreSize;

    @Value("${connector.async.thread-pool.max-size:50}")
    private int maxSize;

    @Value("${connector.async.thread-pool.queue-capacity:200}")
    private int queueCapacity;

    @Value("${connector.async.thread-pool.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    // Callback 专用池 (慢上游不应污染主业务池)
    @Value("${connector.async.callback-pool.core-size:5}")
    private int callbackCoreSize;

    @Value("${connector.async.callback-pool.max-size:20}")
    private int callbackMaxSize;

    @Value("${connector.async.callback-pool.queue-capacity:200}")
    private int callbackQueueCapacity;

    @Bean(name = ASYNC_EXECUTOR)
    public Executor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("async-task-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 回调专用 Executor. 容量小 (core 5 / max 20), 避免慢上游占用过多资源;
     * 队列仍然 200 让短时峰值能缓冲; 被 AsyncTaskService.callbackWithRetry 使用.
     */
    @Bean(name = CALLBACK_EXECUTOR)
    public Executor callbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(callbackCoreSize);
        executor.setMaxPoolSize(callbackMaxSize);
        executor.setQueueCapacity(callbackQueueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("callback-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        // 回调池不走 CallerRunsPolicy: 业务主线程跟回调没关系, 队列满时用 AbortPolicy 丢弃,
        // 上游回调失败由 retry 或下次触发解决 (回调本质是"best-effort" 通知, 不是强一致要求)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
