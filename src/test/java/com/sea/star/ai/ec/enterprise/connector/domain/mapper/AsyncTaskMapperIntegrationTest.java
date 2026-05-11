package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sea.star.ai.ec.enterprise.connector.domain.model.AsyncTask;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TaskStatus;
import com.sea.star.ai.ec.enterprise.connector.integration.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/**
 * AsyncTask CRUD + JSONB 原生等值查询 + 枚举 + 状态切换。
 */
@Transactional
@Rollback
class AsyncTaskMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AsyncTaskMapper mapper;

    @Test
    @DisplayName("insert + selectOneById, status 枚举 round-trip, JSONB params 保留")
    void insertAndSelect() {
        AsyncTask task = sample("task1", "t1", "listOrders", TaskStatus.PENDING,
                "{\"orderId\":\"O001\"}");
        mapper.insert(task);

        AsyncTask loaded = mapper.selectOneById("task1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(loaded.getParams()).contains("orderId");
        assertThat(loaded.getCreatedAt()).isNotNull();   // AutoFill
    }

    @Test
    @DisplayName("findByTenantAndStatus 按复合条件过滤")
    void findByTenantAndStatus() {
        mapper.insert(sample("a", "tA", "x", TaskStatus.PENDING, "{}"));
        mapper.insert(sample("b", "tA", "x", TaskStatus.RUNNING, "{}"));
        mapper.insert(sample("c", "tB", "x", TaskStatus.PENDING, "{}"));

        List<AsyncTask> pendingOfA = mapper.findByTenantAndStatus("tA", TaskStatus.PENDING);
        assertThat(pendingOfA).hasSize(1);
        assertThat(pendingOfA.get(0).getTaskId()).isEqualTo("a");
    }

    @Test
    @DisplayName("findByStatusIn 集合查询 PENDING + RUNNING")
    void findByStatusIn() {
        mapper.insert(sample("a", "t", "x", TaskStatus.PENDING, "{}"));
        mapper.insert(sample("b", "t", "x", TaskStatus.RUNNING, "{}"));
        mapper.insert(sample("c", "t", "x", TaskStatus.SUCCESS, "{}"));
        mapper.insert(sample("d", "t", "x", TaskStatus.FAILED, "{}"));

        List<AsyncTask> active = mapper.findByStatusIn(
                Set.of(TaskStatus.PENDING, TaskStatus.RUNNING));
        assertThat(active).hasSize(2);
        assertThat(active).extracting(AsyncTask::getTaskId)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("findByStatusIn 空/null 集合返回空列表, 不抛异常")
    void findByStatusInEmpty() {
        assertThat(mapper.findByStatusIn(null)).isEmpty();
        assertThat(mapper.findByStatusIn(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("findByStatusAndTimeoutBefore 只返回 timeout_at 早于 deadline 的任务")
    void findByStatusAndTimeoutBefore() {
        LocalDateTime now = LocalDateTime.now();
        AsyncTask expired = sample("exp", "t", "x", TaskStatus.RUNNING, "{}");
        expired.setTimeoutAt(now.minusMinutes(10));
        AsyncTask fresh = sample("ok", "t", "x", TaskStatus.RUNNING, "{}");
        fresh.setTimeoutAt(now.plusMinutes(10));
        mapper.insert(expired);
        mapper.insert(fresh);

        List<AsyncTask> result = mapper.findByStatusAndTimeoutBefore(TaskStatus.RUNNING, now);
        assertThat(result).extracting(AsyncTask::getTaskId).containsExactly("exp");
    }

    @Test
    @DisplayName("findDuplicateInflight: 同租户同 action 同 params 且时间窗口内, 只返回最新一条 inflight")
    void findDuplicateInflight() {
        LocalDateTime now = LocalDateTime.now();

        AsyncTask first = sample("dup1", "t", "listOrders", TaskStatus.PENDING,
                "{\"orderId\":\"O001\"}");
        first.setCreatedAt(now.minusMinutes(2));
        mapper.insert(first);

        AsyncTask second = sample("dup2", "t", "listOrders", TaskStatus.RUNNING,
                "{\"orderId\":\"O001\"}");
        second.setCreatedAt(now.minusMinutes(1));
        mapper.insert(second);

        // 不同 params, 不应被认为重复
        AsyncTask diffParams = sample("other", "t", "listOrders", TaskStatus.PENDING,
                "{\"orderId\":\"O999\"}");
        diffParams.setCreatedAt(now.minusMinutes(1));
        mapper.insert(diffParams);

        AsyncTask hit = mapper.findDuplicateInflight(
                "t", "listOrders", "{\"orderId\":\"O001\"}", now.minusMinutes(10));
        assertThat(hit).isNotNull();
        assertThat(hit.getTaskId()).isEqualTo("dup2"); // 按 created_at DESC 取最新
    }

    @Test
    @DisplayName("findDuplicateInflight: 时间窗口外不返回")
    void findDuplicateOutOfWindow() {
        LocalDateTime now = LocalDateTime.now();
        AsyncTask old = sample("old", "t", "x", TaskStatus.PENDING, "{\"a\":1}");
        old.setCreatedAt(now.minusHours(2));
        mapper.insert(old);

        AsyncTask hit = mapper.findDuplicateInflight(
                "t", "x", "{\"a\":1}", now.minusMinutes(10));
        assertThat(hit).isNull();
    }

    @Test
    @DisplayName("findDuplicateInflight: SUCCESS/FAILED 不算 inflight")
    void findDuplicateIgnoresTerminalStates() {
        LocalDateTime now = LocalDateTime.now();
        AsyncTask done = sample("done", "t", "x", TaskStatus.SUCCESS, "{\"a\":1}");
        done.setCreatedAt(now.minusMinutes(1));
        mapper.insert(done);

        AsyncTask hit = mapper.findDuplicateInflight(
                "t", "x", "{\"a\":1}", now.minusMinutes(10));
        assertThat(hit).isNull();
    }

    @Test
    @DisplayName("update: status 字段切换并持久化")
    void updateStatusTransition() {
        mapper.insert(sample("u1", "t", "x", TaskStatus.PENDING, "{}"));

        AsyncTask reload = mapper.selectOneById("u1");
        reload.setStatus(TaskStatus.SUCCESS);
        reload.setFinishedAt(LocalDateTime.now());
        reload.setResult("{\"ok\":true}");
        mapper.update(reload);

        AsyncTask after = mapper.selectOneById("u1");
        assertThat(after.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(after.getFinishedAt()).isNotNull();
        assertThat(after.getResult()).contains("ok");
    }

    private AsyncTask sample(String taskId, String tenantId, String action,
                              TaskStatus status, String paramsJson) {
        return AsyncTask.builder()
                .taskId(taskId)
                .tenantId(tenantId)
                .action(action)
                .params(paramsJson)
                .status(status)
                .retryCount(0)
                .maxRetries(3)
                .build();
    }
}
