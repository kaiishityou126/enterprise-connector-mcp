package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class TaskNotFoundException extends BaseException {

    public TaskNotFoundException(String taskId) {
        super(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + taskId);
    }
}
