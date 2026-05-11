package com.sea.star.ai.ec.enterprise.connector.controller;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.AsyncTaskTableDef.ASYNC_TASK;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.AsyncTaskMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AsyncTask;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TaskStatus;
import com.sea.star.ai.ec.enterprise.connector.exception.TaskNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务查询 API. 只读, 异步任务不支持 Admin 端删除 (按 30 天 TTL 由 AsyncTaskCleanupJob 清理).
 *
 * 路径设计:
 *   GET  /admin/tasks                分页列表 (支持 tenantId/status/action 过滤)
 *   GET  /admin/tasks/{taskId}       查单条任务详情
 */
@RestController
@RequestMapping("/admin/tasks")
@RequiredArgsConstructor
public class AdminTaskController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AsyncTaskMapper asyncTaskMapper;

    @GetMapping
    public UnifiedResult list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safePage = Math.max(1, page);
        int safeSize = Math.clamp((long) size, 1, MAX_PAGE_SIZE);

        QueryWrapper qw = QueryWrapper.create()
                .orderBy(ASYNC_TASK.CREATED_AT.desc());
        if (tenantId != null && !tenantId.isBlank()) qw.where(ASYNC_TASK.TENANT_ID.eq(tenantId));
        if (status != null) qw.where(ASYNC_TASK.STATUS.eq(status));
        if (action != null && !action.isBlank()) qw.where(ASYNC_TASK.ACTION.eq(action));

        Page<AsyncTask> pageResult = asyncTaskMapper.paginate(safePage, safeSize, qw);
        return UnifiedResult.ok(pageResult);
    }

    @GetMapping("/{taskId}")
    public UnifiedResult get(@PathVariable String taskId) {
        AsyncTask task = asyncTaskMapper.selectOneById(taskId);
        if (task == null) throw new TaskNotFoundException(taskId);
        return UnifiedResult.ok(task);
    }
}
