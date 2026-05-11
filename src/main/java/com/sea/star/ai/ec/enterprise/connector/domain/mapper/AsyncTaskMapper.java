package com.sea.star.ai.ec.enterprise.connector.domain.mapper;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.AsyncTaskTableDef.ASYNC_TASK;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AsyncTask;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TaskStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    default List<AsyncTask> findByTenantAndStatus(String tenantId, TaskStatus status) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        return selectListByQuery(QueryWrapper.create()
                .where(ASYNC_TASK.TENANT_ID.eq(tenantId))
                .and(ASYNC_TASK.STATUS.eq(status)));
    }

    default List<AsyncTask> findByStatusIn(Collection<TaskStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return List.of();
        List<TaskStatus> nonNull = statuses.stream()
                .filter(Objects::nonNull)
                .toList();
        if (nonNull.isEmpty()) return List.of();
        return selectListByQuery(QueryWrapper.create()
                .where(ASYNC_TASK.STATUS.in(nonNull.toArray())));
    }

    /** 扫描超时任务, LIMIT 1000 分批避免一次性拉过多 */
    default List<AsyncTask> findByStatusAndTimeoutBefore(TaskStatus status,
                                                         LocalDateTime deadline) {
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(deadline, "deadline 不能为空");
        return selectListByQuery(QueryWrapper.create()
                .where(ASYNC_TASK.STATUS.eq(status))
                .and(ASYNC_TASK.TIMEOUT_AT.lt(deadline))
                .orderBy(ASYNC_TASK.TIMEOUT_AT.asc())
                .limit(1000));
    }

    /**
     * PostgreSQL 原生 JSONB 等值比较, 用于防重复提交检查。
     * 只取最近 N 分钟内的 PENDING/RUNNING 首条, 避免 Java 侧拉全量再过滤。
     */
    @Select("SELECT * FROM async_task "
            + "WHERE tenant_id = #{tenantId} "
            + "  AND action = #{action} "
            + "  AND status IN ('PENDING', 'RUNNING') "
            + "  AND created_at > #{since} "
            + "  AND params = CAST(#{paramsJson} AS jsonb) "
            + "ORDER BY created_at DESC LIMIT 1")
    AsyncTask findDuplicateInflight(@Param("tenantId") String tenantId,
                                    @Param("action") String action,
                                    @Param("paramsJson") String paramsJson,
                                    @Param("since") LocalDateTime since);
}
