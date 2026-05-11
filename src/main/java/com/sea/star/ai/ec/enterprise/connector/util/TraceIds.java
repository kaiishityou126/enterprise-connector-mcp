package com.sea.star.ai.ec.enterprise.connector.util;

import com.sea.star.ai.ec.enterprise.connector.infrastructure.trace.TraceIdFilter;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * traceId 获取工具。
 *
 * HTTP 请求由 TraceIdFilter 写入 MDC；非 HTTP 入口（MCP Tool、定时任务、@Async）
 * 没有 Filter 保护，MDC 里没 traceId，此工具负责兜底生成并写回 MDC。
 */
public final class TraceIds {

    private TraceIds() {}

    /**
     * 返回当前 traceId；若 MDC 没有则生成一个并写回 MDC。
     */
    public static String currentOrGenerate() {
        String id = MDC.get(TraceIdFilter.MDC_KEY);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "");
            MDC.put(TraceIdFilter.MDC_KEY, id);
        }
        return id;
    }

    /**
     * 只读当前 traceId；未设置时返回 null，不会自动生成。
     * 适合 GlobalExceptionHandler 等只是"把值放入响应"的场景。
     */
    public static String current() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }
}
