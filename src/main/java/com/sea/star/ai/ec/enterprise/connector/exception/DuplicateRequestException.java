package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class DuplicateRequestException extends BaseException {

    public DuplicateRequestException(String message) {
        super(ErrorCode.TASK_DUPLICATE, message);
    }
}
