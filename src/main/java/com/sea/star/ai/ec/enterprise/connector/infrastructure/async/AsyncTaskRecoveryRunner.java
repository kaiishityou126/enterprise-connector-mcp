package com.sea.star.ai.ec.enterprise.connector.infrastructure.async;

import com.sea.star.ai.ec.enterprise.connector.domain.mapper.AsyncTaskMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AsyncTask;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TaskStatus;
import com.sea.star.ai.ec.enterprise.connector.service.AsyncTaskService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动后扫描 RUNNING / PENDING 的遗留任务：
 *   - RUNNING：说明上次进程崩溃在执行中途，无法恢复，标记 FAILED（带错误原因）
 *   - PENDING：如果 retry_count < max_retries，重新提交执行；否则标 FAILED
 *
 * 由 `connector.async.recovery-on-startup` 控制开关，默认 true。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskRecoveryRunner {

    private final AsyncTaskMapper asyncTaskMapper;
    private final AsyncTaskService asyncTaskService;

    @Value("${connector.async.recovery-on-startup:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!enabled) {
            log.info("异步任务恢复已禁用");
            return;
        }
        // 先处理 RUNNING —— 无法安全恢复执行中途的状态
        List<AsyncTask> runnings = asyncTaskMapper.findByStatusIn(List.of(TaskStatus.RUNNING));
        for (AsyncTask t : runnings) {
            t.setStatus(TaskStatus.FAILED);
            t.setErrorMessage("进程重启，RUNNING 状态无法恢复");
            t.setFinishedAt(LocalDateTime.now());
            asyncTaskMapper.update(t);
            log.warn("恢复：RUNNING 任务标记 FAILED taskId={}", t.getTaskId());
        }

        // 处理 PENDING
        List<AsyncTask> pendings = asyncTaskMapper.findByStatusIn(List.of(TaskStatus.PENDING));
        for (AsyncTask t : pendings) {
            int retryCount = t.getRetryCount() == null ? 0 : t.getRetryCount();
            int maxRetries = t.getMaxRetries() == null ? 3 : t.getMaxRetries();
            if (retryCount >= maxRetries) {
                t.setStatus(TaskStatus.FAILED);
                t.setErrorMessage("进程重启后重试次数已耗尽");
                t.setFinishedAt(LocalDateTime.now());
                asyncTaskMapper.update(t);
                log.warn("恢复：PENDING 任务重试耗尽，标记 FAILED taskId={}", t.getTaskId());
            } else {
                log.info("恢复：重新提交 PENDING 任务 taskId={}", t.getTaskId());
                asyncTaskService.runAsync(t.getTaskId());
            }
        }

        log.info("异步任务恢复完成：RUNNING={} 个已标 FAILED, PENDING={} 个已处理",
                runnings.size(), pendings.size());
    }
}
