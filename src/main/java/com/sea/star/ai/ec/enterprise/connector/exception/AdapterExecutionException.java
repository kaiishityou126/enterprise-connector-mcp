package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class AdapterExecutionException extends BaseException {

    public AdapterExecutionException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public AdapterExecutionException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
