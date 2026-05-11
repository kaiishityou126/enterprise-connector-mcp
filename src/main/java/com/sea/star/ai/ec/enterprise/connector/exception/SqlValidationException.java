package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class SqlValidationException extends BaseException {

    public SqlValidationException(String message) {
        super(ErrorCode.SQL_INVALID, message);
    }
}
