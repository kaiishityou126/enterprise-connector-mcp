package com.sea.star.ai.ec.enterprise.connector.controller;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.ActionTemplateTableDef.ACTION_TEMPLATE;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.TemplateCreateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.TemplateUpdateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.ActionTemplateMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sea.star.ai.ec.enterprise.connector.domain.model.ActionTemplate;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.exception.TemplateNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.service.ActionTemplateService;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作模板管理 API. 由 DBA / 运维团队维护预审 SQL / API 模板, 商户通过 tenant_action_config 引用.
 *
 * 路径设计:
 *   GET     /admin/templates                     分页列表 (支持 action/accessType/enabled 过滤)
 *   GET     /admin/templates/{templateId}        查单条
 *   POST    /admin/templates                     创建
 *   PUT     /admin/templates/{templateId}        PATCH 更新 (只覆盖非 null 字段)
 *   DELETE  /admin/templates/{templateId}        软删
 *   POST    /admin/templates/{templateId}/restore   恢复软删
 *   DELETE  /admin/templates/{templateId}/purge     物理删 (不可逆, 需 X-Purge-Api-Key)
 *
 * 新增 / 修改模板需要重启应用才会被 MCP DynamicMcpToolProvider 重新注册为 Tool.
 */
@Slf4j
@RestController
@RequestMapping("/admin/templates")
@RequiredArgsConstructor
public class AdminTemplateController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ActionTemplateService actionTemplateService;
    private final ActionTemplateMapper actionTemplateMapper;

    @GetMapping
    public UnifiedResult list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) AccessType accessType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean enabled) {
        int safePage = Math.max(1, page);
        int safeSize = Math.clamp((long) size, 1, MAX_PAGE_SIZE);

        QueryWrapper qw = QueryWrapper.create()
                .orderBy(ACTION_TEMPLATE.ACTION.asc())
                .orderBy(ACTION_TEMPLATE.ACCESS_TYPE.asc());
        if (accessType != null) qw.where(ACTION_TEMPLATE.ACCESS_TYPE.eq(accessType));
        if (action != null && !action.isBlank()) qw.where(ACTION_TEMPLATE.ACTION.eq(action));
        if (enabled != null) qw.where(ACTION_TEMPLATE.ENABLED.eq(enabled));

        Page<ActionTemplate> result = actionTemplateMapper.paginate(safePage, safeSize, qw);
        return UnifiedResult.ok(result);
    }

    @GetMapping("/{templateId}")
    public UnifiedResult get(@PathVariable Integer templateId) {
        ActionTemplate t = actionTemplateMapper.selectOneById(templateId);
        if (t == null) throw new TemplateNotFoundException(String.valueOf(templateId));
        return UnifiedResult.ok(t);
    }

    @PostMapping
    public UnifiedResult create(@Valid @RequestBody TemplateCreateRequest req) {
        ActionTemplate entity = ActionTemplate.builder()
                .action(req.getAction())
                .accessType(req.getAccessType())
                .name(req.getName())
                .description(req.getDescription())
                .datasourceName(req.getDatasourceName() != null && !req.getDatasourceName().isBlank()
                        ? req.getDatasourceName() : "default")
                .sqlTemplate(req.getSqlTemplate())
                .apiPath(req.getApiPath())
                .apiMethod(req.getApiMethod())
                .apiBodyTemplate(req.getApiBodyTemplate())
                .paramSchema(req.getParamSchema())
                .maxRows(req.getMaxRows())
                .isLongRunning(req.getIsLongRunning())
                .timeoutSeconds(req.getTimeoutSeconds())
                .enabled(Boolean.TRUE)
                .build();
        assertConsistencyWithSiblings(entity, null);
        actionTemplateService.create(entity);
        log.info("Admin 创建模板 action={} accessType={} ds={}",
                req.getAction(), req.getAccessType(), entity.getDatasourceName());
        return UnifiedResult.ok(entity.getTemplateId());
    }

    @PutMapping("/{templateId}")
    public UnifiedResult update(@PathVariable Integer templateId,
                                @Valid @RequestBody TemplateUpdateRequest req) {
        ActionTemplate existing = actionTemplateMapper.selectOneById(templateId);
        if (existing == null) throw new TemplateNotFoundException(String.valueOf(templateId));

        if (req.getName() != null) existing.setName(req.getName());
        if (req.getDescription() != null) existing.setDescription(req.getDescription());
        if (req.getDatasourceName() != null) existing.setDatasourceName(req.getDatasourceName());
        if (req.getSqlTemplate() != null) existing.setSqlTemplate(req.getSqlTemplate());
        if (req.getApiPath() != null) existing.setApiPath(req.getApiPath());
        if (req.getApiMethod() != null) existing.setApiMethod(req.getApiMethod());
        if (req.getApiBodyTemplate() != null) existing.setApiBodyTemplate(req.getApiBodyTemplate());
        if (req.getParamSchema() != null) existing.setParamSchema(req.getParamSchema());
        if (req.getMaxRows() != null) existing.setMaxRows(req.getMaxRows());
        if (req.getIsLongRunning() != null) existing.setIsLongRunning(req.getIsLongRunning());
        if (req.getTimeoutSeconds() != null) existing.setTimeoutSeconds(req.getTimeoutSeconds());
        if (req.getEnabled() != null) existing.setEnabled(req.getEnabled());

        assertConsistencyWithSiblings(existing, templateId);
        actionTemplateService.update(existing);
        log.info("Admin 更新模板 templateId={}", templateId);
        return UnifiedResult.ok(templateId);
    }

    /**
     * 校验同 action 多方言模板的"族内一致性": description / param_schema / datasource_name
     * 必须 byte-equal 一致. AI 视角同 action 是同一个 tool, 元数据不一致会让 AI 拿到的 schema
     * 跟实际 SQL 期望的字段对不上.
     *
     * @param candidate 待写入的模板 (新建或更新后状态)
     * @param excludeTemplateId 更新场景排除自身; 新建时传 null
     */
    private void assertConsistencyWithSiblings(ActionTemplate candidate, Integer excludeTemplateId) {
        if (candidate.getAction() == null || candidate.getAction().isBlank()) {
            return;
        }
        List<ActionTemplate> siblings = actionTemplateMapper.findAllByAction(candidate.getAction());
        for (ActionTemplate s : siblings) {
            if (excludeTemplateId != null && excludeTemplateId.equals(s.getTemplateId())) {
                continue;
            }
            if (!Objects.equals(s.getDescription(), candidate.getDescription())) {
                throw new BusinessException(ErrorCode.INCONSISTENT_TEMPLATE_FAMILY,
                        "action=" + candidate.getAction() + " 已存在 access_type=" + s.getAccessType()
                                + " 的模板, description 与之不一致");
            }
            if (!paramSchemaSemanticallyEqual(s.getParamSchema(), candidate.getParamSchema())) {
                throw new BusinessException(ErrorCode.INCONSISTENT_TEMPLATE_FAMILY,
                        "action=" + candidate.getAction() + " 已存在 access_type=" + s.getAccessType()
                                + " 的模板, param_schema 与之不一致");
            }
            if (!Objects.equals(s.getDatasourceName(), candidate.getDatasourceName())) {
                throw new BusinessException(ErrorCode.INCONSISTENT_TEMPLATE_FAMILY,
                        "action=" + candidate.getAction() + " 已存在 access_type=" + s.getAccessType()
                                + " 的模板, datasource_name 与之不一致");
            }
        }
    }

    /**
     * 语义对比 paramSchema (JSON 字符串). 避免格式差异 (空白/字段顺序) 误判不一致.
     * 任一侧解析失败时降级为 byte-equal 对比 (保守拦截).
     */
    private static boolean paramSchemaSemanticallyEqual(String a, String b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        try {
            JsonNode na = JsonUtils.mapper().readTree(a);
            JsonNode nb = JsonUtils.mapper().readTree(b);
            return na.equals(nb);
        } catch (Exception e) {
            // 任一侧不是合法 JSON, 退回字节比较
            return false;
        }
    }

    @DeleteMapping("/{templateId}")
    public UnifiedResult delete(@PathVariable Integer templateId) {
        actionTemplateService.delete(templateId);
        log.info("Admin 软删模板 templateId={}", templateId);
        return UnifiedResult.ok(templateId);
    }

    @PostMapping("/{templateId}/restore")
    public UnifiedResult restore(@PathVariable Integer templateId) {
        actionTemplateService.restore(templateId);
        return UnifiedResult.ok(templateId);
    }

    /** 物理删除模板 (不可恢复). Phase 6.4 由 AuthInterceptor 额外要求 X-Purge-Api-Key. */
    @DeleteMapping("/{templateId}/purge")
    public UnifiedResult purge(@PathVariable Integer templateId) {
        actionTemplateService.purge(templateId);
        log.warn("Admin 物理删除模板 templateId={}", templateId);
        return UnifiedResult.ok(templateId);
    }
}
