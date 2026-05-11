package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class RateLimitExceededException extends BaseException {

    public RateLimitExceededException(String tenantId) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, "请求频率超限: " + tenantId);
    }
}
