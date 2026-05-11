package com.sea.star.ai.ec.enterprise.connector.service;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.ActionTemplateTableDef.ACTION_TEMPLATE;

import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.ActionTemplateMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.exception.TemplateNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.cache.TwoLevelCacheManager;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TemplateChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.service.security.SqlWhitelistValidator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作模板服务：按 (action, accessType) 查询模板，走两级缓存。
 *
 * 缓存 key 格式: "{action}:{accessType}"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionTemplateService {

    private final ActionTemplateMapper actionTemplateMapper;
    private final TwoLevelCacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;
    private final SqlWhitelistValidator sqlWhitelistValidator;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有启用的模板。MCP DynamicMcpToolProvider 启动时调用此方法枚举可暴露的 tool。
     * 不走缓存 —— 启动场景一次性读取, 也便于后续扩展"配置热更新时重新注册 tools"。
     */
    public List<ActionTemplate> findAllEnabled() {
        return actionTemplateMapper.selectListByQuery(QueryWrapper.create()
                .where(ACTION_TEMPLATE.ENABLED.eq(Boolean.TRUE))
                .orderBy(ACTION_TEMPLATE.ACTION.asc())
                .orderBy(ACTION_TEMPLATE.ACCESS_TYPE.asc()));
    }

    public ActionTemplate getTemplate(String action, AccessType accessType) {
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(accessType, "accessType 不能为空");
        String key = buildKey(action, accessType);
        ActionTemplate template = cacheManager.get(
                BusinessConstants.CACHE_ACTION_TEMPLATE,
                key,
                ActionTemplate.class,
                () -> actionTemplateMapper.findByActionAndAccessType(action, accessType));
        if (template == null) {
            throw new TemplateNotFoundException(action + "/" + accessType);
        }
        if (Boolean.FALSE.equals(template.getEnabled())) {
            throw new TemplateNotFoundException(action + "/" + accessType + " (已禁用)");
        }
        return template;
    }

    /**
     * 按 templateId 查模板, 走两级缓存 (Phase 6 新增).
     * <p>
     * 授权链路: tenant_action_config.template_id → 本方法直接拿模板,
     * 避免 action 同名跨多条 access_type 时的歧义。
     */
    public ActionTemplate getById(Integer templateId) {
        Objects.requireNonNull(templateId, "templateId 不能为空");
        String key = "id:" + templateId;
        ActionTemplate template = cacheManager.get(
                BusinessConstants.CACHE_ACTION_TEMPLATE,
                key,
                ActionTemplate.class,
                () -> actionTemplateMapper.selectOneById(templateId));
        if (template == null) {
            throw new TemplateNotFoundException("templateId=" + templateId);
        }
        if (Boolean.FALSE.equals(template.getEnabled())) {
            throw new TemplateNotFoundException("templateId=" + templateId + " (已禁用)");
        }
        return template;
    }

    @Transactional
    public void create(ActionTemplate template) {
        Objects.requireNonNull(template, "template 不能为空");
        validateSqlIfPresent(template);
        actionTemplateMapper.insert(template);
        invalidateAllCacheKeys(template, template.getTemplateId());
        eventPublisher.publishEvent(new TemplateChangedEvent(this));
    }

    @Transactional
    public void update(ActionTemplate template) {
        Objects.requireNonNull(template, "template 不能为空");
        Objects.requireNonNull(template.getTemplateId(), "templateId 不能为空");
        validateSqlIfPresent(template);
        int rows = actionTemplateMapper.update(template);
        if (rows == 0) {
            throw new TemplateNotFoundException(String.valueOf(template.getTemplateId()));
        }
        invalidateAllCacheKeys(template, template.getTemplateId());
        eventPublisher.publishEvent(new TemplateChangedEvent(this));
    }

    /**
     * 写入时预校验模板 SQL (源=TEMPLATE: 跳过 AST, 仅过函数黑名单 + 长度 + 分号).
     * 让校验只在写入阶段做一次, 避免每次执行重复扫描.
     */
    private void validateSqlIfPresent(ActionTemplate template) {
        AccessType type = template.getAccessType();
        if (type == null || !type.isDb()) return;
        String sql = template.getSqlTemplate();
        if (sql == null || sql.isBlank()) return;
        sqlWhitelistValidator.validate(sql, SqlWhitelistValidator.Source.TEMPLATE);
    }

    @Transactional
    public void delete(Integer templateId) {
        Objects.requireNonNull(templateId, "templateId 不能为空");
        ActionTemplate existing = actionTemplateMapper.selectOneById(templateId);
        if (existing == null) {
            throw new TemplateNotFoundException(String.valueOf(templateId));
        }
        actionTemplateMapper.deleteById(templateId); // Flex 自动软删
        invalidateAllCacheKeys(existing, templateId);
        eventPublisher.publishEvent(new TemplateChangedEvent(this));
    }

    /** 恢复软删模板 (Admin restore 用) */
    @Transactional
    public void restore(Integer templateId) {
        int rows = jdbcTemplate.update(
                "UPDATE action_template SET deleted = FALSE, updated_at = CURRENT_TIMESTAMP WHERE template_id = ?",
                templateId);
        if (rows == 0) throw new TemplateNotFoundException(String.valueOf(templateId));
        ActionTemplate restored = loadRawById(templateId);
        if (restored != null) invalidateAllCacheKeys(restored, templateId);
        eventPublisher.publishEvent(new TemplateChangedEvent(this));
        log.info("Admin 恢复模板 templateId={}", templateId);
    }

    /** 物理删除模板 (Admin purge 用, 不可恢复) */
    @Transactional
    public void purge(Integer templateId) {
        ActionTemplate existing = loadRawById(templateId);
        int rows = jdbcTemplate.update("DELETE FROM action_template WHERE template_id = ?", templateId);
        if (rows == 0) throw new TemplateNotFoundException(String.valueOf(templateId));
        if (existing != null) invalidateAllCacheKeys(existing, templateId);
        eventPublisher.publishEvent(new TemplateChangedEvent(this));
        log.warn("Admin 物理删除模板 templateId={} (不可恢复)", templateId);
    }

    /** 绕开 Flex logic-delete 过滤直接查一条 (含软删的); 缓存清理要用 */
    private ActionTemplate loadRawById(Integer templateId) {
        return jdbcTemplate.query(
                "SELECT action, access_type FROM action_template WHERE template_id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    ActionTemplate t = new ActionTemplate();
                    t.setAction(rs.getString("action"));
                    t.setAccessType(com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType
                            .valueOf(rs.getString("access_type")));
                    return t;
                }, templateId);
    }

    private void invalidateAllCacheKeys(ActionTemplate t, Integer templateId) {
        String actionKey = buildKey(t.getAction(), t.getAccessType());
        cacheManager.evict(BusinessConstants.CACHE_ACTION_TEMPLATE, actionKey);
        publishInvalidate(actionKey);
        // Phase 6 新增的 id:{templateId} 缓存条目也要清
        String idKey = "id:" + templateId;
        cacheManager.evict(BusinessConstants.CACHE_ACTION_TEMPLATE, idKey);
        publishInvalidate(idKey);
    }

    private String buildKey(String action, AccessType accessType) {
        return action + ":" + accessType.name();
    }

    private void publishInvalidate(String key) {
        if (stringRedisTemplate == null) return;
        try {
            String payload = BusinessConstants.CACHE_ACTION_TEMPLATE + "|" + key;
            stringRedisTemplate.convertAndSend(
                    BusinessConstants.CHANNEL_CACHE_INVALIDATE, payload);
        } catch (Exception e) {
            // 广播失败会导致多实例 L1 缓存不一致，需要告警
            log.error("发布模板缓存失效消息失败，可能导致多实例数据不一致 key={}", key, e);
        }
    }
}
