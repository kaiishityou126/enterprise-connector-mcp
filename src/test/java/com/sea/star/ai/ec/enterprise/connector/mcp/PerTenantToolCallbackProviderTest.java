package com.sea.star.ai.ec.enterprise.connector.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantActionConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.exception.TemplateNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.ActionAuthChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TemplateChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.tool.ToolCallback;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PerTenantToolCallbackProviderTest {

    @Mock private ActionTemplateService actionTemplateService;
    @Mock private TenantActionConfigMapper tenantActionConfigMapper;
    @Mock private McpToolService mcpToolService;

    private Cache<String, ToolCallback[]> cache;
    private PerTenantToolCallbackProvider provider;

    @BeforeEach
    void setUp() {
        cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(100)
                .build();
        provider = new PerTenantToolCallbackProvider(
                actionTemplateService, tenantActionConfigMapper, mcpToolService, cache);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("无 TenantContext 退回全局视图 (按 action 去重)")
    void noTenantReturnsGlobalView() {
        when(actionTemplateService.findAllEnabled()).thenReturn(List.of(
                template(1, "queryOrder", AccessType.POSTGRES, "{\"oid\":{\"type\":\"string\"}}"),
                template(2, "queryOrder", AccessType.MYSQL, "{\"oid\":{\"type\":\"string\"}}"),
                template(3, "listUsers", AccessType.POSTGRES, "{}")
        ));

        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertThat(callbacks).hasSize(2);  // 按 action 去重
    }

    @Test
    @DisplayName("有 TenantContext: 仅暴露该租户授权的 action")
    void perTenantViewByAuthorization() {
        TenantContext.setCurrentTenant("vip");

        ActionTemplate tpl = template(10, "queryOrder", AccessType.POSTGRES,
                "{\"oid\":{\"type\":\"string\"}}");
        when(tenantActionConfigMapper.findByTenantId("vip")).thenReturn(List.of(
                authConfig("vip", "queryOrder", 10, null, true)
        ));
        when(actionTemplateService.getById(10)).thenReturn(tpl);

        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("queryOrder");
    }

    @Test
    @DisplayName("禁用授权 (enabled=false) 不暴露")
    void disabledAuthorizationSkipped() {
        TenantContext.setCurrentTenant("vip");
        when(tenantActionConfigMapper.findByTenantId("vip")).thenReturn(List.of(
                authConfig("vip", "queryOrder", 10, null, false)
        ));

        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertThat(callbacks).isEmpty();
    }

    @Test
    @DisplayName("授权指向已删除的模板: 跳过不抛异常")
    void deletedTemplateSkipped() {
        TenantContext.setCurrentTenant("vip");
        when(tenantActionConfigMapper.findByTenantId("vip")).thenReturn(List.of(
                authConfig("vip", "queryOrder", 999, null, true)
        ));
        when(actionTemplateService.getById(999))
                .thenThrow(new TemplateNotFoundException("999"));

        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertThat(callbacks).isEmpty();
    }

    @Test
    @DisplayName("customParams 合并到 schema: AI 视角能看到扩展字段")
    void customParamsMergedIntoSchema() {
        TenantContext.setCurrentTenant("vip");

        ActionTemplate tpl = template(10, "queryOrder", AccessType.POSTGRES,
                "{\"oid\":{\"type\":\"string\"}}");
        when(tenantActionConfigMapper.findByTenantId("vip")).thenReturn(List.of(
                authConfig("vip", "queryOrder", 10,
                        "{\"region\":{\"type\":\"string\",\"required\":true}}", true)
        ));
        when(actionTemplateService.getById(10)).thenReturn(tpl);

        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertThat(callbacks).hasSize(1);
        String schema = callbacks[0].getToolDefinition().inputSchema();
        // 模板字段
        assertThat(schema).contains("\"oid\"");
        // 租户增量字段 (能被 AI 看到)
        assertThat(schema).contains("\"region\"");
    }

    @Test
    @DisplayName("缓存生效: 同租户多次调用只查 mapper 一次")
    void cacheHitsAvoidDbQuery() {
        TenantContext.setCurrentTenant("vip");
        when(tenantActionConfigMapper.findByTenantId("vip")).thenReturn(List.of());

        provider.getToolCallbacks();
        provider.getToolCallbacks();
        provider.getToolCallbacks();

        verify(tenantActionConfigMapper, times(1)).findByTenantId("vip");
    }

    @Test
    @DisplayName("TemplateChangedEvent 清空缓存, 下次重新查 mapper")
    void templateChangedInvalidatesCache() {
        TenantContext.setCurrentTenant("vip");
        when(tenantActionConfigMapper.findByTenantId("vip")).thenReturn(List.of());

        provider.getToolCallbacks();             // 第一次, mapper 调 1 次
        provider.onTemplateChanged(new TemplateChangedEvent(this));
        provider.getToolCallbacks();             // 缓存被清, mapper 应再调 1 次

        verify(tenantActionConfigMapper, times(2)).findByTenantId("vip");
    }

    @Test
    @DisplayName("ActionAuthChangedEvent 清空缓存")
    void authChangedInvalidatesCache() {
        TenantContext.setCurrentTenant("vip");
        when(tenantActionConfigMapper.findByTenantId(any())).thenReturn(List.of());

        provider.getToolCallbacks();
        provider.onActionAuthChanged(new ActionAuthChangedEvent(this, "vip"));
        provider.getToolCallbacks();

        verify(tenantActionConfigMapper, times(2)).findByTenantId(eq("vip"));
    }

    // helpers

    private ActionTemplate template(int id, String action, AccessType type, String paramSchema) {
        return ActionTemplate.builder()
                .templateId(id)
                .action(action)
                .accessType(type)
                .name(action)
                .description("test")
                .datasourceName("default")
                .sqlTemplate("SELECT 1")
                .paramSchema(paramSchema)
                .enabled(true)
                .build();
    }

    private TenantActionConfig authConfig(String tenantId, String action, int templateId,
                                          String customParams, boolean enabled) {
        return TenantActionConfig.builder()
                .tenantId(tenantId)
                .action(action)
                .templateId(templateId)
                .customParams(customParams)
                .enabled(enabled)
                .build();
    }
}
