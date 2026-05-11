package com.sea.star.ai.ec.enterprise.connector.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.exception.BaseException;
import com.sea.star.ai.ec.enterprise.connector.service.security.AuthenticationService;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 拦截 MCP {@code POST /mcp} 上的 {@code tools/list} 请求, 自己构造响应,
 * 短路掉 spring-ai-mcp server 启动时一次性快照的全局 tools 列表.
 *
 * <p><b>背景</b>: spring-ai-mcp-server-webmvc 1.1.4 在启动时调一次
 * {@link ToolCallbackProvider#getToolCallbacks()}, 把结果注册到 McpAsyncServer.tools
 * (CopyOnWriteArrayList), 运行时直接读这个 list, **不再回调 provider**. 所以
 * {@link PerTenantToolCallbackProvider} 自实现没用 — 启动时 TenantContext 是 null,
 * 永远只读 globalView.
 *
 * <p>唯一可行的 per-session 路径: 在 servlet filter 层 (协议层之前) 拦截 tools/list 请求,
 * 自己读 X-Tenant-Id + 调 provider + 构造 JSON-RPC 响应直接返回, 不进入 spring-ai-mcp 链.
 *
 * <p><b>非 tools/list 请求</b> (initialize / notifications/initialized / tools/call /
 * resources/list / ping / ...) 透传给 spring-ai-mcp 处理, 行为不变.
 *
 * <p><b>认证 + 租户上下文</b>:
 * <ul>
 *   <li>本 Filter 在 {@code chain.doFilter} 之前执行, 也在 {@code AuthInterceptor.preHandle}
 *       之前 (Interceptor 在 DispatcherServlet 内). 因此 tools/list 短路路径下,
 *       Filter 自己复用 {@link AuthenticationService#verifyMcp} 验 token + 自己读 X-Tenant-Id 设
 *       {@link TenantContext}.</li>
 *   <li>透传路径 (非 tools/list) 不动 TenantContext, 让 AuthInterceptor 走原流程.</li>
 * </ul>
 *
 * <p><b>请求体缓存</b>: HTTP body 是一次性 InputStream. Filter 解析 JSON-RPC method 已读了一次,
 * 透传时下游 (spring-ai-mcp) 也要读. 所以用 {@link CachedBodyHttpServletRequest} 包装,
 * 让 body 可重复读.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class McpToolsListInterceptFilter extends OncePerRequestFilter {

    private static final String MCP_ENDPOINT = "/mcp";
    private static final String TOOLS_LIST_METHOD = "tools/list";

    private final AuthenticationService authenticationService;
    private final ToolCallbackProvider toolCallbackProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 只关心 POST /mcp 上的请求, 其他全透传
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !MCP_ENDPOINT.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // 缓存 body, 让透传路径 spring-ai-mcp 也能读
        byte[] body = readAllBytes(request);
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, body);

        // 解析 JSON-RPC method
        String method;
        JsonNode rpc;
        try {
            rpc = JsonUtils.mapper().readTree(body);
            method = rpc.path("method").asText("");
        } catch (Exception e) {
            // 解析失败让 spring-ai-mcp 处理, 它的 JSON-RPC 错误格式更标准
            chain.doFilter(wrapped, response);
            return;
        }

        if (!TOOLS_LIST_METHOD.equals(method)) {
            chain.doFilter(wrapped, response);
            return;
        }

        // tools/list, 自己处理
        handleToolsList(request, response, rpc);
    }

    /**
     * 处理 tools/list 请求, 直接写响应 body 不走 spring-ai-mcp 链.
     */
    private void handleToolsList(HttpServletRequest request, HttpServletResponse response,
                                 JsonNode rpc) throws IOException {
        // 1. 验 token (复用 AuthenticationService)
        try {
            authenticationService.verifyMcp(request);
        } catch (BaseException e) {
            writeJsonRpcError(response, rpc.get("id"),
                    e.getErrorCode().getHttpStatus(), e.getMessage());
            return;
        }

        // 2. 设 TenantContext, 让 PerTenantToolCallbackProvider 能按租户合并
        String tenantId = request.getHeader(BusinessConstants.MCP_HEADER_TENANT_ID);
        boolean tenantSet = false;
        try {
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.setCurrentTenant(tenantId.trim());
                tenantSet = true;
            }

            // 3. 调 provider 拿 per-tenant 合并后的 ToolCallback[]
            ToolCallback[] callbacks;
            try {
                callbacks = toolCallbackProvider.getToolCallbacks();
            } catch (Exception e) {
                log.error("调 ToolCallbackProvider.getToolCallbacks 失败", e);
                writeJsonRpcError(response, rpc.get("id"), 500, "tools/list 内部错误: " + e.getMessage());
                return;
            }

            // 4. 构造 JSON-RPC 响应 + 写出
            String responseJson = buildToolsListResponseJson(rpc.get("id"), callbacks);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            try (PrintWriter writer = response.getWriter()) {
                writer.write(responseJson);
            }
        } finally {
            if (tenantSet) {
                TenantContext.clear();
            }
        }
    }

    /**
     * 构造 MCP 协议规定的 tools/list 响应:
     * <pre>{@code
     * {
     *   "jsonrpc": "2.0",
     *   "id": <request id>,
     *   "result": {
     *     "tools": [{"name": "...", "description": "...", "inputSchema": {...}}, ...]
     *   }
     * }
     * }</pre>
     */
    static String buildToolsListResponseJson(JsonNode idNode, ToolCallback[] callbacks) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("jsonrpc", "2.0");
        if (idNode != null && !idNode.isNull()) {
            root.set("id", idNode);
        }
        ObjectNode result = root.putObject("result");
        ArrayNode tools = result.putArray("tools");
        if (callbacks != null) {
            for (ToolCallback cb : callbacks) {
                ToolDefinition def = cb.getToolDefinition();
                ObjectNode tool = tools.addObject();
                tool.put("name", def.name());
                if (def.description() != null) {
                    tool.put("description", def.description());
                }
                // inputSchema 是序列化后的 JSON 字符串, 反序列化后嵌入 (避免双重 escape)
                String inputSchema = def.inputSchema();
                if (inputSchema != null && !inputSchema.isBlank()) {
                    try {
                        tool.set("inputSchema", JsonUtils.mapper().readTree(inputSchema));
                    } catch (Exception e) {
                        // schema 非法就退化为最小有效 schema
                        ObjectNode fallback = tool.putObject("inputSchema");
                        fallback.put("type", "object");
                    }
                } else {
                    ObjectNode fallback = tool.putObject("inputSchema");
                    fallback.put("type", "object");
                }
            }
        }
        return root.toString();
    }

    private void writeJsonRpcError(HttpServletResponse response, JsonNode idNode,
                                   int httpStatus, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("jsonrpc", "2.0");
        if (idNode != null && !idNode.isNull()) {
            root.set("id", idNode);
        }
        ObjectNode error = root.putObject("error");
        error.put("code", -32000);
        error.put("message", message != null ? message : "");
        try (PrintWriter writer = response.getWriter()) {
            writer.write(root.toString());
        }
    }

    private static byte[] readAllBytes(HttpServletRequest request) throws IOException {
        return request.getInputStream().readAllBytes();
    }

    // ------------------------------------------------------------------
    // body 缓存 wrapper
    // ------------------------------------------------------------------

    /**
     * HTTP body 是一次性 InputStream, 读完就空. 包装一下让 body 可重复读, 让透传路径
     * (chain.doFilter) 下游的 spring-ai-mcp handler 能正常解析 JSON-RPC.
     */
    static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(new ByteArrayInputStream(cachedBody));
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        CachedServletInputStream(ByteArrayInputStream input) {
            this.input = input;
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
