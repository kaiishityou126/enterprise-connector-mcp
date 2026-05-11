package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class ParamValidationException extends BaseException {

    public ParamValidationException(String message) {
        super(ErrorCode.PARAM_INVALID, message);
    }
}
