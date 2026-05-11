package com.sea.star.ai.ec.enterprise.connector.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

/**
 * 校验 {@link DynamicMcpToolProvider#buildCallbacks} 在多方言场景下按 action 去重,
 * AI 视角同一 action 永远只暴露一个 tool, 方言对它透明.
 */
@ExtendWith(MockitoExtension.class)
class DynamicMcpToolProviderTest {

    @Mock private McpToolService mcpToolService;

    @Test
    @DisplayName("同 action 多方言行去重: 只注册一个 ToolCallback")
    void deduplicatesMultiDialectByAction() {
        List<ActionTemplate> templates = List.of(
                template(1, "queryOrder", AccessType.POSTGRES),
                template(2, "queryOrder", AccessType.MYSQL),
                template(3, "queryOrder", AccessType.SQLSERVER),
                template(4, "queryInventory", AccessType.POSTGRES));

        List<ToolCallback> callbacks =
                DynamicMcpToolProvider.buildCallbacks(templates, mcpToolService);

        // 只有 queryOrder 和 queryInventory 两个唯一 action
        assertThat(callbacks).hasSize(2);
        assertThat(callbacks)
                .extracting(c -> c.getToolDefinition().name())
                .containsExactly("queryOrder", "queryInventory");
    }

    @Test
    @DisplayName("空模板列表: 返回空 callbacks 不抛异常")
    void emptyTemplates() {
        List<ToolCallback> callbacks =
                DynamicMcpToolProvider.buildCallbacks(List.of(), mcpToolService);
        assertThat(callbacks).isEmpty();
    }

    @Test
    @DisplayName("单一 action 单一方言: 一对一注册")
    void singleAction() {
        List<ToolCallback> callbacks = DynamicMcpToolProvider.buildCallbacks(
                List.of(template(1, "queryOrder", AccessType.POSTGRES)),
                mcpToolService);
        assertThat(callbacks).hasSize(1);
    }

    private ActionTemplate template(Integer id, String action, AccessType type) {
        return ActionTemplate.builder()
                .templateId(id)
                .action(action)
                .accessType(type)
                .name(action)
                .description("测试模板")
                .datasourceName("default")
                .sqlTemplate("SELECT 1")
                .paramSchema("{}")
                .enabled(true)
                .build();
    }
}
