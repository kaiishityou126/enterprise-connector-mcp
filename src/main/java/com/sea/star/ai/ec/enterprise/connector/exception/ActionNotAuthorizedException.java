package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

/**
 * 租户未获授权调用某 action.
 * 对应 tenant_action_config 里该行不存在或 enabled=false.
 */
public class ActionNotAuthorizedException extends BaseException {

    public ActionNotAuthorizedException(String tenantId, String action) {
        super(ErrorCode.ACTION_NOT_AUTHORIZED,
                "租户 " + tenantId + " 未获授权调用 action " + action);
    }
}
