package com.sea.star.ai.ec.enterprise.connector.infrastructure.scheduler;

import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步任务表清理任务 (Phase 6.4).
 * <p>
 * 每天凌晨 03:00 清理 {@code async_task} 表中:
 *   - status 是终态 (SUCCESS / FAILED / TIMEOUT)
 *   - created_at < now() - retentionDays (默认 30 天, 可通过 sys_dict.async.cleanup_retention_days 改)
 * <p>
 * 分批删除 1000 条一批, 避免单次锁表过久。PENDING/RUNNING 状态的任务不清理
 * (给异步执行器留出处理时间), 靠 timeoutAt 扫描器把它们推入 TIMEOUT 终态后再清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskCleanupJob {

    private static final String DICT_RETENTION_DAYS = "async.cleanup_retention_days";
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES_PER_RUN = 100;  // 每次最多清 10 万条, 防止失控

    private final JdbcTemplate jdbcTemplate;
    private final SysDictService sysDictService;

    /** 允许通过配置禁用本任务 (测试 / 特殊环境) */
    @Value("${connector.async.cleanup.enabled:true}")
    private boolean enabled;

    /**
     * 默认 cron 凌晨 3 点一次, 可通过 application yaml 覆盖:
     * {@code connector.async.cleanup.cron=0 0 3 * * *}
     */
    @Scheduled(cron = "${connector.async.cleanup.cron:0 0 3 * * *}")
    public void cleanup() {
        if (!enabled) {
            log.debug("AsyncTaskCleanupJob 已禁用, 跳过");
            return;
        }
        int retentionDays = sysDictService.getInt(DICT_RETENTION_DAYS, 30);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        int totalDeleted = 0;
        int batches = 0;
        long start = System.currentTimeMillis();
        while (batches < MAX_BATCHES_PER_RUN) {
            int rows = jdbcTemplate.update(
                    // PG 没有原生 LIMIT 在 DELETE 上, 用子查询
                    "DELETE FROM async_task WHERE task_id IN ("
                            + " SELECT task_id FROM async_task"
                            + " WHERE status IN ('SUCCESS','FAILED','TIMEOUT')"
                            + "   AND created_at < ?"
                            + " LIMIT ?"
                            + ")",
                    java.sql.Timestamp.valueOf(cutoff), BATCH_SIZE);
            if (rows == 0) break;
            totalDeleted += rows;
            batches++;
            log.debug("AsyncTaskCleanupJob batch={} deleted={} running_total={}",
                    batches, rows, totalDeleted);
        }

        long cost = System.currentTimeMillis() - start;
        if (totalDeleted > 0) {
            log.info("AsyncTaskCleanupJob 完成: retentionDays={}, cutoff={}, deleted={} (batches={}), cost={}ms",
                    retentionDays, cutoff, totalDeleted, batches, cost);
        } else {
            log.debug("AsyncTaskCleanupJob 完成: 无过期任务可清理 (cutoff={})", cutoff);
        }
        if (batches >= MAX_BATCHES_PER_RUN) {
            log.warn("AsyncTaskCleanupJob 触达单次最大批次上限 {}, 仍有过期任务待下次运行清理",
                    MAX_BATCHES_PER_RUN);
        }
    }
}
