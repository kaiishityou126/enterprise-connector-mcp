package com.sea.star.ai.ec.enterprise.connector.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.McpToolRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * MCP ToolCallbackProvider 注册入口. 注册的实际是 {@link PerTenantToolCallbackProvider},
 * 每次 MCP {@code tools/list} 调到时按当前 session 的 TenantContext 动态计算合并 schema.
 *
 * <p>Tool 定义来源:
 * <ul>
 *   <li>name = action_template.action (如 queryOrder)</li>
 *   <li>description = action_template.description</li>
 *   <li>inputSchema = (template.param_schema ∪ tenant.custom_params) 外加 MCP 调用层字段
 *       (tenantId/requestId/callbackUrl)</li>
 * </ul>
 *
 * <p>每个 Tool 的 call(jsonArgs) 路由到 {@link McpToolService#call}.
 *
 * <p>缓存: PerTenantToolCallbackProvider 内部 Caffeine 30s TTL,
 * {@link com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TemplateChangedEvent} /
 * {@link com.sea.star.ai.ec.enterprise.connector.infrastructure.event.ActionAuthChangedEvent}
 * 触发时主动失效. Admin 改模板/授权后客户端 30s 内可见.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DynamicMcpToolProvider {

    private static final String CALLER_MCP = "mcp";
    private static final Duration TENANT_CACHE_TTL = Duration.ofSeconds(30);
    private static final long TENANT_CACHE_MAX_SIZE = 10_000L;

    private final ActionTemplateService actionTemplateService;
    private final McpToolService mcpToolService;

    @Bean
    public ToolCallbackProvider actionTemplateToolCallbackProvider(
            TenantActionConfigMapper tenantActionConfigMapper) {
        Cache<String, ToolCallback[]> cache = Caffeine.newBuilder()
                .expireAfterWrite(TENANT_CACHE_TTL)
                .maximumSize(TENANT_CACHE_MAX_SIZE)
                .build();
        log.info("MCP 启动: 注册 PerTenantToolCallbackProvider (per-session schema, TTL={})",
                TENANT_CACHE_TTL);
        return new PerTenantToolCallbackProvider(
                actionTemplateService, tenantActionConfigMapper, mcpToolService, cache);
    }

    /**
     * 按 action 字段去重. 同一 action 在多 access_type (POSTGRES/MYSQL/...) 下各有一条
     * 是多方言适配的预期状态; AI 视角同 action 应只看到一个 tool, 方言透明.
     * 元数据 (name/description/param_schema) 取列表首条, 多行间一致性由
     * {@code AdminTemplateController.assertConsistencyWithSiblings} 强校验保证.
     */
    static List<ToolCallback> buildCallbacks(List<ActionTemplate> templates, McpToolService service) {
        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> seenNames = new java.util.HashSet<>();
        for (ActionTemplate tpl : templates) {
            if (!seenNames.add(tpl.getAction())) {
                log.debug("MCP Tool 多方言模板去重: action={} 保留首条, 忽略 access_type={}",
                        tpl.getAction(), tpl.getAccessType());
                continue;
            }
            // 全局视图: 直接用 template.paramSchema (effectiveSchema=null 表示用 template 自带)
            callbacks.add(new ActionToolCallback(tpl, null, service));
        }
        return callbacks;
    }

    // ------------------------------------------------------------------
    // ToolCallback 实现
    // ------------------------------------------------------------------

    /**
     * @param effectiveParamSchema 已合并 (template.paramSchema ∪ tenant.customParams) 的 schema JSON;
     *                              null 表示用 template.paramSchema 原样 (全局视图路径)
     */
    @RequiredArgsConstructor
    static class ActionToolCallback implements ToolCallback {

        private final ActionTemplate template;
        /** 优先用此处的 schema 构造 inputSchema; null 时回退 template.paramSchema. */
        private final String effectiveParamSchema;
        private final McpToolService mcpToolService;

        @Override
        public ToolDefinition getToolDefinition() {
            String schemaJson = effectiveParamSchema != null
                    ? effectiveParamSchema
                    : template.getParamSchema();
            return DefaultToolDefinition.builder()
                    .name(template.getAction())
                    .description(descriptionOf(template))
                    .inputSchema(buildInputSchema(schemaJson))
                    .build();
        }

        @Override
        public String call(String jsonArgs) {
            Map<String, Object> args = JsonUtils.toMap(jsonArgs);
            if (args == null) args = new HashMap<>();

            String tenantId = popString(args, "tenantId");
            String requestId = popString(args, "requestId");
            String callbackUrl = popString(args, "callbackUrl");

            McpToolRequest request = new McpToolRequest();
            request.setTenantId(tenantId);
            request.setAction(template.getAction());
            request.setParams(args);
            request.setRequestId(requestId);
            request.setCallbackUrl(callbackUrl);

            UnifiedResult result = mcpToolService.call(request, CALLER_MCP);
            return JsonUtils.toJson(result);
        }

        private static String popString(Map<String, Object> args, String key) {
            Object v = args.remove(key);
            return v == null ? null : String.valueOf(v);
        }
    }

    // ------------------------------------------------------------------
    // JSON Schema 构造
    // ------------------------------------------------------------------

    private static String descriptionOf(ActionTemplate t) {
        if (t.getDescription() != null && !t.getDescription().isBlank()) {
            return t.getDescription();
        }
        return "业务操作 " + t.getAction() + " (" + t.getAccessType() + ")";
    }

    /**
     * 把 paramSchema (形如 {"orderId": {...}, "date": {...}}) 包装为标准 MCP JSON Schema
     * (type=object, properties, required). 额外注入 tenantId / requestId / callbackUrl
     * 三个 MCP 调用层字段.
     *
     * @param paramSchemaJson 业务参数 schema (template.paramSchema 或合并后的 schema);
     *                        null/空 时只暴露 MCP 调用层字段
     */
    static String buildInputSchema(String paramSchemaJson) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        // MCP 调用层字段: OpenClaw 调用时必须传 tenantId
        properties.putObject("tenantId")
                .put("type", "string")
                .put("description", "租户 ID");
        required.add("tenantId");

        // 可选幂等 + 回调
        properties.putObject("requestId")
                .put("type", "string")
                .put("description", "请求 ID (幂等去重, 可选)");
        properties.putObject("callbackUrl")
                .put("type", "string")
                .put("description", "异步任务回调 URL (可选, 必须 http/https)");

        Map<String, Object> paramSchema = null;
        if (paramSchemaJson != null && !paramSchemaJson.isBlank()) {
            try {
                paramSchema = JsonUtils.toMap(paramSchemaJson);
            } catch (Exception e) {
                // schema 非法, 忽略; tool 仍可注册, MCP client 看到空 params
                paramSchema = null;
            }
        }
        if (paramSchema != null) {
            for (Map.Entry<String, Object> e : paramSchema.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> rule)) continue;
                ObjectNode prop = properties.putObject(e.getKey());
                copyIfPresent(rule, "type", prop);
                copyIfPresent(rule, "description", prop);
                copyIfPresent(rule, "pattern", prop);
                copyIfPresent(rule, "maxLength", prop);
                copyIfPresent(rule, "minLength", prop);
                if (Boolean.TRUE.equals(rule.get("required"))) {
                    required.add(e.getKey());
                }
            }
        }
        return JsonUtils.toJson(root);
    }

    private static void copyIfPresent(Map<?, ?> rule, String key, ObjectNode target) {
        Object v = rule.get(key);
        if (v == null) return;
        JsonNode node = JsonUtils.mapper().valueToTree(v);
        target.set(key, node);
    }
}
