package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class TenantNotFoundException extends BaseException {

    public TenantNotFoundException(String tenantId) {
        super(ErrorCode.TENANT_NOT_FOUND, "租户不存在: " + tenantId);
    }
}
