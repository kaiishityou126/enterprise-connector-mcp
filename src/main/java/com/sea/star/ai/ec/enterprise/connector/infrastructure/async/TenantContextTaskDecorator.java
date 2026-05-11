package com.sea.star.ai.ec.enterprise.connector.infrastructure.async;

import com.sea.star.ai.ec.enterprise.connector.util.TenantContext;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 把提交线程的 ThreadLocal（租户上下文 + MDC）拷贝到执行线程，
 * 解决 @Async 线程池复用时 ThreadLocal 丢失问题。
 *
 * 每个 submit() 都会执行 decorate()：
 *   - 提交线程：捕获 tenantId + MDC 快照
 *   - 执行线程：set → runnable.run() → finally clear
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        String tenantId = TenantContext.getCurrentTenant();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        return () -> {
            // 执行线程可能被前一个任务污染，先清空
            TenantContext.clear();
            MDC.clear();
            try {
                if (tenantId != null) {
                    TenantContext.setCurrentTenant(tenantId);
                }
                if (mdcSnapshot != null) {
                    MDC.setContextMap(mdcSnapshot);
                }
                runnable.run();
            } finally {
                TenantContext.clear();
                MDC.clear();
            }
        };
    }
}
