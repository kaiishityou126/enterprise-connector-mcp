package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;

/**
 * MCP Tool 调用请求（HTTP transport 简化版）。
 *
 * 字段语义对齐 MCP 协议里的 tools/call 方法：
 *   - tenantId / action / params 是业务参数
 *   - requestId 用于幂等（OpenClaw 应为每次用户请求生成唯一值）
 *   - callbackUrl 可选，用于异步任务完成后回调
 */
@Data
public class McpToolRequest {

    @NotBlank(message = "tenantId 不能为空")
    @Size(max = 50)
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$")
    private String tenantId;

    @NotBlank(message = "action 不能为空")
    @Size(max = 50)
    private String action;

    private Map<String, Object> params;

    @Size(max = 128)
    private String requestId;

    @Size(max = 500)
    private String callbackUrl;
}
