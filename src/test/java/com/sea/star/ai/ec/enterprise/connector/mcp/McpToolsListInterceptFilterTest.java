package com.sea.star.ai.ec.enterprise.connector.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sea.star.ai.ec.enterprise.connector.service.security.AuthenticationService;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import jakarta.servlet.FilterChain;
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
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolsListInterceptFilterTest {

    @Mock private AuthenticationService authenticationService;
    @Mock private ToolCallbackProvider toolCallbackProvider;
    @Mock private FilterChain chain;

    private McpToolsListInterceptFilter filter;

    @BeforeEach
    void setUp() {
        filter = new McpToolsListInterceptFilter(authenticationService, toolCallbackProvider);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("非 POST /mcp 直接透传, 不读 body 不调 provider")
    void nonMcpEndpointPassthrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/admin/health");
        req.setContent("{}".getBytes());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(toolCallbackProvider, never()).getToolCallbacks();
    }

    @Test
    @DisplayName("GET /mcp 直接透传 (只拦 POST)")
    void getMcpPassthrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mcp");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(toolCallbackProvider, never()).getToolCallbacks();
    }

    @Test
    @DisplayName("POST /mcp 但不是 tools/list (例如 initialize) → 用包装 request 透传")
    void initializePassthrough() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.setContent(body.getBytes());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        // 透传时 chain 收到的是包装 request (body 可重读), 不是原 req
        verify(chain, times(1)).doFilter(any(McpToolsListInterceptFilter.CachedBodyHttpServletRequest.class), any());
        verify(toolCallbackProvider, never()).getToolCallbacks();
    }

    @Test
    @DisplayName("POST /mcp tools/list → 自己处理, 不调 chain, 调 provider")
    void toolsListIntercepted() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\",\"params\":{}}";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.setContent(body.getBytes());
        req.addHeader("Authorization", "Bearer xxx");
        req.addHeader("X-Tenant-Id", "merchant_a");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{
                fakeTool("queryOrder", "查订单", "{\"type\":\"object\",\"properties\":{\"oid\":{\"type\":\"string\"}}}")
        });

        filter.doFilterInternal(req, resp, chain);

        // 短路 chain
        verify(chain, never()).doFilter(any(), any());
        // 调了 provider 一次
        verify(toolCallbackProvider, times(1)).getToolCallbacks();
        // 验了 token
        verify(authenticationService, times(1)).verifyMcp(req);

        // 响应 200 + 正确的 JSON-RPC 格式
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentType()).contains("application/json");
        JsonNode out = JsonUtils.mapper().readTree(resp.getContentAsString());
        assertThat(out.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(out.get("id").asInt()).isEqualTo(42);
        JsonNode tools = out.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name").asText()).isEqualTo("queryOrder");
        // inputSchema 是嵌套 object 不是双重 escape 的字符串
        assertThat(tools.get(0).get("inputSchema").isObject()).isTrue();
        assertThat(tools.get(0).get("inputSchema").path("properties").has("oid")).isTrue();
    }

    @Test
    @DisplayName("tools/list 时 X-Tenant-Id 设到 TenantContext, 调用结束被清")
    void tenantContextSetAndCleared() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.setContent(body.getBytes());
        req.addHeader("X-Tenant-Id", "vip");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        // 让 provider 在调用瞬间断言 TenantContext 已设
        when(toolCallbackProvider.getToolCallbacks()).thenAnswer(inv -> {
            assertThat(TenantContext.getCurrentTenant()).isEqualTo("vip");
            return new ToolCallback[]{};
        });

        filter.doFilterInternal(req, resp, chain);

        // 调完后清掉
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("body 不是合法 JSON → 透传给 spring-ai-mcp, 让它返回标准 JSON-RPC 错误")
    void invalidJsonBodyPassthrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.setContent("not json".getBytes());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        verify(chain, times(1)).doFilter(any(McpToolsListInterceptFilter.CachedBodyHttpServletRequest.class), any());
        verify(toolCallbackProvider, never()).getToolCallbacks();
    }

    @Test
    @DisplayName("buildToolsListResponseJson: callbacks 为空也合法返回")
    void buildResponseEmptyCallbacks() throws Exception {
        com.fasterxml.jackson.databind.JsonNode idNode = JsonUtils.mapper().valueToTree(7);
        String json = McpToolsListInterceptFilter.buildToolsListResponseJson(idNode, new ToolCallback[]{});
        JsonNode out = JsonUtils.mapper().readTree(json);
        assertThat(out.get("id").asInt()).isEqualTo(7);
        assertThat(out.path("result").path("tools").isArray()).isTrue();
        assertThat(out.path("result").path("tools")).isEmpty();
    }

    @Test
    @DisplayName("buildToolsListResponseJson: id 是 string 也保留原始类型")
    void buildResponsePreservesStringId() throws Exception {
        com.fasterxml.jackson.databind.JsonNode idNode = JsonUtils.mapper().valueToTree("req-abc");
        String json = McpToolsListInterceptFilter.buildToolsListResponseJson(idNode, new ToolCallback[]{});
        JsonNode out = JsonUtils.mapper().readTree(json);
        assertThat(out.get("id").asText()).isEqualTo("req-abc");
        assertThat(out.get("id").isTextual()).isTrue();
    }

    private static ToolCallback fakeTool(String name, String desc, String inputSchema) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(name)
                .description(desc)
                .inputSchema(inputSchema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }
}
