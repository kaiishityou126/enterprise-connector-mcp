package com.sea.star.ai.ec.enterprise.connector.util;

import org.slf4j.MDC;

/**
 * ThreadLocal 租户上下文。
 *
 * 同步要点：
 *   - set/clear 时自动同步 MDC("tenantId")，这样结构化日志能直接拿到当前租户
 *   - @Async 线程池由 TenantContextTaskDecorator 传递本 ThreadLocal + MDC 快照（Phase 3 实现）
 *
 * MDC 的 key 和 TraceIdFilter 的 traceId key 一起构成结构化日志的租户可追踪性。
 */
public final class TenantContext {

    /** MDC key, 供 logback-spring.xml 引用 */
    public static final String MDC_KEY = "tenantId";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
        if (tenantId != null) {
            MDC.put(MDC_KEY, tenantId);
        } else {
            MDC.remove(MDC_KEY);
        }
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        MDC.remove(MDC_KEY);
    }
}
