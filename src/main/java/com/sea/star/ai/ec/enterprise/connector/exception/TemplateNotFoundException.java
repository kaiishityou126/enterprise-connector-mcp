package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;

public class TemplateNotFoundException extends BaseException {

    public TemplateNotFoundException(String action) {
        super(ErrorCode.TEMPLATE_NOT_FOUND, "操作模板不存在: " + action);
    }
}
