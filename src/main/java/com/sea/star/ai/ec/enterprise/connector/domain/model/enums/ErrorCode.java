package com.sea.star.ai.ec.enterprise.connector.domain.model.enums;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(200, "SUCCESS", "成功"),

    TENANT_NOT_FOUND(404, "TENANT_NOT_FOUND", "租户不存在"),
    TENANT_DISABLED(403, "TENANT_DISABLED", "租户已禁用"),

    DATASOURCE_NOT_FOUND(404, "DATASOURCE_NOT_FOUND", "租户数据源不存在"),

    TEMPLATE_NOT_FOUND(404, "TEMPLATE_NOT_FOUND", "操作模板不存在"),
    INCONSISTENT_TEMPLATE_FAMILY(400, "INCONSISTENT_TEMPLATE_FAMILY",
            "同 action 多方言模板的 description / param_schema / datasource_name 必须一致"),

    ACTION_NOT_AUTHORIZED(403, "ACTION_NOT_AUTHORIZED", "租户未获授权调用该操作"),

    PARAM_INVALID(400, "PARAM_INVALID", "参数校验失败"),
    PARAM_MISSING(400, "PARAM_MISSING", "必填参数缺失"),

    SQL_INVALID(400, "SQL_INVALID", "SQL 校验未通过"),
    CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM(400, "CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM",
            "custom_sql 引用了模板 paramSchema 未声明的占位符 (AI 看不到, 永远不会传)"),

    TASK_NOT_FOUND(404, "TASK_NOT_FOUND", "任务不存在"),
    TASK_DUPLICATE(409, "TASK_DUPLICATE", "任务重复提交"),

    AUTH_TOKEN_MISSING(401, "AUTH_TOKEN_MISSING", "认证 Token 缺失"),
    AUTH_TOKEN_INVALID(401, "AUTH_TOKEN_INVALID", "认证 Token 无效"),
    AUTH_FORBIDDEN(403, "AUTH_FORBIDDEN", "无权限访问"),

    ADAPTER_DB_ERROR(502, "ADAPTER_DB_ERROR", "数据库查询失败"),
    ADAPTER_API_ERROR(502, "ADAPTER_API_ERROR", "外部 API 调用失败"),
    ADAPTER_TIMEOUT(504, "ADAPTER_TIMEOUT", "上游响应超时"),

    RATE_LIMIT_EXCEEDED(429, "RATE_LIMIT_EXCEEDED", "请求频率超限"),
    SERVICE_DEGRADED(503, "SERVICE_DEGRADED", "服务降级中"),

    BIZ_ERROR(500, "BIZ_ERROR", "业务处理失败"),
    SYSTEM_ERROR(500, "SYSTEM_ERROR", "系统内部错误");

    private final int httpStatus;
    private final String code;
    private final String message;

    ErrorCode(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
