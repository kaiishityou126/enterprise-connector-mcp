package com.sea.star.ai.ec.enterprise.connector.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.exception.TemplateNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.ActionAuthChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TemplateChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import com.sea.star.ai.ec.enterprise.connector.util.SchemaUtils;
import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.event.EventListener;

/**
 * Per-session 动态 ToolCallbackProvider (Phase 7+ 方案 Y 拦截器).
 *
 * <p>每次 MCP {@code tools/list} 请求被 Spring AI MCP server 调到 {@link #getToolCallbacks()} 时,
 * 从 {@link TenantContext} 拿当前 session 的租户 ID, 按 (tenantId) 算合并后的 schema 列表返回:
 *
 * <pre>
 *   final_schema = action_template.param_schema  ∪  tenant_action_config.custom_params
 *                  (全局基础)                       (租户增量, 可空)
 * </pre>
 *
 * <p>缓存: Caffeine 30s TTL (写入 admin 接口后业务变更事件主动清, 兜底 30s 自然过期).
 *
 * <p>未认证 / admin 上下文 (tenantId 为 null): 返回全局视图 (按 action 去重的所有模板,
 * schema 取 template.paramSchema 不合并). 这跟原 {@link DynamicMcpToolProvider} 行为一致.
 */
@Slf4j
@RequiredArgsConstructor
public class PerTenantToolCallbackProvider implements ToolCallbackProvider {

    private static final ToolCallback[] EMPTY = new ToolCallback[0];

    private final ActionTemplateService actionTemplateService;
    private final TenantActionConfigMapper tenantActionConfigMapper;
    private final McpToolService mcpToolService;
    /** key=tenantId; value 数组在 30s TTL 内复用. */
    private final Cache<String, ToolCallback[]> tenantCache;

    @Override
    public ToolCallback[] getToolCallbacks() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            // 未认证 / admin 上下文: 退回全局视图. 没有缓存的必要 (admin tools/list 频次低)
            return globalView();
        }
        ToolCallback[] cached = tenantCache.get(tenantId, this::computeForTenant);
        return cached != null ? cached : EMPTY;
    }

    /** 业务事件触发: 模板/授权变更时清空整个缓存, 让所有 session 下次拉到最新合并视图. */
    @EventListener
    public void onTemplateChanged(TemplateChangedEvent event) {
        log.debug("收到 TemplateChangedEvent, 清 per-tenant tool schema 缓存");
        tenantCache.invalidateAll();
    }

    /** 第一版粗粒度: 任何租户的授权变更都全清 (简单稳妥). 后续可按 tenantId 精细化. */
    @EventListener
    public void onActionAuthChanged(ActionAuthChangedEvent event) {
        log.debug("收到 ActionAuthChangedEvent (tenantId={}), 清 per-tenant tool schema 缓存",
                event.getTenantId());
        tenantCache.invalidateAll();
    }

    // ------------------------------------------------------------------
    // private
    // ------------------------------------------------------------------

    /**
     * 给单个租户算 ToolCallback 列表:
     * <ol>
     *   <li>查 tenant_action_config 该租户所有 enabled 行</li>
     *   <li>每条按 templateId 拿对应 ActionTemplate (模板缺失/禁用跳过)</li>
     *   <li>合并 (template.paramSchema, actionConfig.customParams) 算 effectiveSchema</li>
     *   <li>构造 ActionToolCallback (按 action 去重, 多方言 access_type 时取首条)</li>
     * </ol>
     */
    private ToolCallback[] computeForTenant(String tenantId) {
        List<TenantActionConfig> auths = tenantActionConfigMapper.findByTenantId(tenantId);
        List<ToolCallback> callbacks = new ArrayList<>(auths.size());
        Set<String> seenActions = new HashSet<>();

        for (TenantActionConfig auth : auths) {
            if (Boolean.FALSE.equals(auth.getEnabled())) continue;
            if (!seenActions.add(auth.getAction())) continue;  // 同 action 多授权防御 (实际复合主键不会出现)

            ActionTemplate template;
            try {
                template = actionTemplateService.getById(auth.getTemplateId());
            } catch (TemplateNotFoundException e) {
                log.warn("租户 {} 授权了 action={} (templateId={}) 但模板已禁用/删除, 跳过",
                        tenantId, auth.getAction(), auth.getTemplateId());
                continue;
            }

            String effective = SchemaUtils.mergeParamSchemas(
                    template.getParamSchema(), auth.getCustomParams());
            callbacks.add(new DynamicMcpToolProvider.ActionToolCallback(
                    template, effective, mcpToolService));
        }
        log.debug("PerTenantToolCallbackProvider 计算 tenantId={} 暴露 {} 个 tool", tenantId, callbacks.size());
        return callbacks.toArray(new ToolCallback[0]);
    }

    /**
     * 全局视图 (无 session 租户上下文时). 复用原启动时全局注册逻辑.
     */
    private ToolCallback[] globalView() {
        List<ActionTemplate> templates = actionTemplateService.findAllEnabled();
        List<ToolCallback> callbacks = DynamicMcpToolProvider.buildCallbacks(templates, mcpToolService);
        return callbacks.toArray(new ToolCallback[0]);
    }
}
