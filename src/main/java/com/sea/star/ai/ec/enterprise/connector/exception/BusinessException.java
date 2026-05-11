package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class BusinessException extends BaseException {

    public BusinessException(String message) {
        super(ErrorCode.BIZ_ERROR, message);
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
