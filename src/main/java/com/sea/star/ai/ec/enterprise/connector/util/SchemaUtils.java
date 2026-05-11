package com.sea.star.ai.ec.enterprise.connector.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * paramSchema 合并工具 (Phase 7+ per-tenant tool schema 用).
 *
 * <p>合并语义: <code>final = template.paramSchema ∪ tenant.customParams</code>,
 * 同名字段租户增量优先覆盖. 任意一侧为空时合并是恒等 (identity), 单路径设计.
 *
 * <p>用途:
 * <ul>
 *   <li>{@code PerTenantToolCallbackProvider} — 给每个 session 算合并后 schema 暴露给 AI</li>
 *   <li>{@code TenantActionConfigService.validateCustomSql} — custom_sql 占位符子集校验
 *       (从 ⊆ template.paramSchema 放宽到 ⊆ merged)</li>
 * </ul>
 */
public final class SchemaUtils {

    private SchemaUtils() {}

    /**
     * 合并两个 paramSchema JSON. 任一侧 null/空 时另一侧原样返回 (恒等).
     * 同名字段冲突时, customParams 端覆盖 templateSchema 端 (租户优先).
     *
     * @return 合并后的 JSON 字符串; 两侧都空时返回 null
     */
    public static String mergeParamSchemas(String templateSchemaJson, String customParamsJson) {
        boolean templateEmpty = isBlank(templateSchemaJson);
        boolean customEmpty = isBlank(customParamsJson);
        if (templateEmpty && customEmpty) return null;
        if (customEmpty) return templateSchemaJson;
        if (templateEmpty) return customParamsJson;
        try {
            JsonNode templateNode = JsonUtils.mapper().readTree(templateSchemaJson);
            JsonNode customNode = JsonUtils.mapper().readTree(customParamsJson);
            if (!templateNode.isObject() || !customNode.isObject()) {
                // 任一侧不是 object 退回 template (保守)
                return templateSchemaJson;
            }
            ObjectNode merged = ((ObjectNode) templateNode).deepCopy();
            Iterator<String> names = customNode.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                merged.set(name, customNode.get(name));  // 租户优先
            }
            return merged.toString();
        } catch (Exception e) {
            // 解析失败退回 template
            return templateSchemaJson;
        }
    }

    /**
     * 提取 paramSchema 顶层字段名集合. 用于子集校验:
     * custom_sql 占位符必须 ⊆ fieldNames(merged).
     *
     * @return LinkedHashSet 保序; null/解析失败返回空集
     */
    public static Set<String> fieldNames(String schemaJson) {
        if (isBlank(schemaJson)) return Set.of();
        try {
            JsonNode node = JsonUtils.mapper().readTree(schemaJson);
            if (!node.isObject()) return Set.of();
            Set<String> names = new LinkedHashSet<>();
            node.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * 校验 customParams 是合法 JSON object (基本格式). 不做深层 JSON Schema 语义校验.
     *
     * @return null/空字符串 视为合法 (字段未配置); 非合法 object JSON 抛 IllegalArgumentException
     */
    public static void assertValidCustomParams(String customParamsJson) {
        if (isBlank(customParamsJson)) return;
        try {
            JsonNode node = JsonUtils.mapper().readTree(customParamsJson);
            if (!node.isObject()) {
                throw new IllegalArgumentException(
                        "customParams 必须是 JSON object, 实际类型: " + node.getNodeType());
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("customParams 不是合法 JSON: " + e.getOriginalMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
