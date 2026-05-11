package com.sea.star.ai.ec.enterprise.connector.infrastructure.event;

import org.springframework.context.ApplicationEvent;

/**
 * action_template 变更事件 (新增/修改/删除/恢复/Purge).
 * <p>
 * 监听方:
 * <ul>
 *   <li>{@code PerTenantToolCallbackProvider} — 清 per-session schema 缓存,
 *       让下次 tools/list 拿到最新模板 paramSchema 合并视图</li>
 * </ul>
 * 第一版采用粗粒度 (全部清空) 策略, 简单稳妥.
 */
public class TemplateChangedEvent extends ApplicationEvent {

    public TemplateChangedEvent(Object source) {
        super(source);
    }
}
