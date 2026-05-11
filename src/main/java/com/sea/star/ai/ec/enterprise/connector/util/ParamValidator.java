package com.sea.star.ai.ec.enterprise.connector.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sea.star.ai.ec.enterprise.connector.exception.ParamValidationException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于 action_template.param_schema 的动态参数校验。
 *
 * param_schema 结构示例:
 * {
 *   "orderId": {
 *     "type": "string", "required": true, "maxLength": 64, "pattern": "^[A-Za-z0-9-]+$"
 *   }
 * }
 *
 * 注意：Phase 1 仅搭框架，日期范围、跨字段校验等扩展由 Phase 2 完善。
 */
public final class ParamValidator {

    private ParamValidator() {}

    @SuppressWarnings("unchecked")
    public static void validate(String paramSchemaJson, Map<String, Object> params) {
        if (paramSchemaJson == null || paramSchemaJson.isBlank()) return;

        Map<String, Object> schema = JsonUtils.fromJson(
                paramSchemaJson,
                new TypeReference<Map<String, Object>>() {});
        if (schema == null) return;

        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> rule = (Map<String, Object>) entry.getValue();
            Object value = params == null ? null : params.get(key);

            boolean required = Boolean.TRUE.equals(rule.get("required"));
            if (value == null) {
                if (required) {
                    throw new ParamValidationException(key + ": 必填参数缺失");
                }
                continue;
            }

            String type = (String) rule.get("type");
            if (type != null) checkType(key, value, type);

            Object maxLength = rule.get("maxLength");
            if (maxLength instanceof Number n && value.toString().length() > n.intValue()) {
                throw new ParamValidationException(
                        key + ": 长度超限, 最大 " + n.intValue());
            }

            Object pattern = rule.get("pattern");
            if (pattern instanceof String p && !Pattern.matches(p, value.toString())) {
                throw new ParamValidationException(key + ": 格式不匹配");
            }
        }
    }

    private static void checkType(String key, Object value, String type) {
        switch (type.toLowerCase()) {
            case "string" -> {
                if (!(value instanceof String)) {
                    throw new ParamValidationException(key + ": 类型应为 string");
                }
            }
            case "integer", "int" -> {
                if (!(value instanceof Integer || value instanceof Long)) {
                    throw new ParamValidationException(key + ": 类型应为 integer");
                }
            }
            case "number" -> {
                if (!(value instanceof Number)) {
                    throw new ParamValidationException(key + ": 类型应为 number");
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    throw new ParamValidationException(key + ": 类型应为 boolean");
                }
            }
            default -> { /* 未知类型暂不校验 */ }
        }
    }
}
