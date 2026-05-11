package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class DatasourceNotFoundException extends BaseException {

    public DatasourceNotFoundException(String tenantId, String dsName) {
        super(ErrorCode.DATASOURCE_NOT_FOUND,
                "租户数据源不存在: " + tenantId + "/" + dsName);
    }

    public DatasourceNotFoundException(String tenantId, String dsName, String message) {
        super(ErrorCode.DATASOURCE_NOT_FOUND, message);
    }
}
