package com.sea.star.ai.ec.enterprise.connector.controller;

import static com.sea.star.ai.ec.enterprise.connector.domain.model.table.TenantConfigTableDef.TENANT_CONFIG;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.TenantConfigCreateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.TenantConfigUpdateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantConfigMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.exception.TenantNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.service.TenantConfigService;
import jakarta.validation.Valid;
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
 * 租户管理 API. 只管租户身份和租户级策略 (QPS / enabled / tier), 数据源和授权分开走子资源接口.
 *
 * 路径设计:
 *   GET     /admin/tenants                      分页列表 (支持 enabled 过滤)
 *   GET     /admin/tenants/{tenantId}           查单个
 *   POST    /admin/tenants                      创建
 *   PUT     /admin/tenants/{tenantId}           PATCH 更新 (只覆盖非 null 字段, 含 enabled 禁用/启用)
 *   DELETE  /admin/tenants/{tenantId}           软删 (不级联; 业务调用走 getConfig 自然被拒)
 *   POST    /admin/tenants/{tenantId}/restore   恢复软删
 *   DELETE  /admin/tenants/{tenantId}/purge     物理删 (不可逆, 级联清 datasource/action_config, 需 X-Purge-Api-Key)
 *   POST    /admin/tenants/{tenantId}/refresh   强制清缓存
 *
 * 相关 Controller:
 *   - 数据源 CRUD    → AdminDatasourceController  (/admin/datasources/{tid}/{dsName})
 *   - 动作授权管理   → AdminActionConfigController (/admin/tenants/{tid}/actions/{action}/grant)
 */
@Slf4j
@RestController
@RequestMapping("/admin/tenants")
@RequiredArgsConstructor
public class AdminTenantController {

    private static final int MAX_PAGE_SIZE = 200;

    private final TenantConfigService tenantConfigService;
    private final TenantConfigMapper tenantConfigMapper;

    @GetMapping
    public UnifiedResult list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean enabled) {
        int safePage = Math.max(1, page);
        int safeSize = Math.clamp((long) size, 1, MAX_PAGE_SIZE);

        QueryWrapper qw = QueryWrapper.create().orderBy(TENANT_CONFIG.TENANT_ID.asc());
        if (enabled != null) qw.where(TENANT_CONFIG.ENABLED.eq(enabled));

        Page<TenantConfig> pageResult = tenantConfigMapper.paginate(safePage, safeSize, qw);
        return UnifiedResult.ok(pageResult);
    }

    @GetMapping("/{tenantId}")
    public UnifiedResult get(@PathVariable String tenantId) {
        TenantConfig cfg = tenantConfigMapper.selectOneById(tenantId);
        if (cfg == null) throw new TenantNotFoundException(tenantId);
        return UnifiedResult.ok(cfg);
    }

    @PostMapping
    public UnifiedResult create(@Valid @RequestBody TenantConfigCreateRequest req) {
        TenantConfig entity = TenantConfig.builder()
                .tenantId(req.getTenantId())
                .tenantName(req.getTenantName())
                .tier(req.getTier())
                .rateLimitQps(req.getRateLimitQps())
                .enabled(Boolean.TRUE)
                .build();
        tenantConfigService.create(entity);
        log.info("Admin 创建租户 tenantId={}", req.getTenantId());
        return UnifiedResult.ok(entity.getTenantId());
    }

    @PutMapping("/{tenantId}")
    public UnifiedResult update(@PathVariable String tenantId,
                                @Valid @RequestBody TenantConfigUpdateRequest req) {
        TenantConfig existing = tenantConfigMapper.selectOneById(tenantId);
        if (existing == null) throw new TenantNotFoundException(tenantId);

        // 只覆盖请求中非 null 的字段
        if (req.getTenantName() != null) existing.setTenantName(req.getTenantName());
        if (req.getTier() != null) existing.setTier(req.getTier());
        if (req.getRateLimitQps() != null) existing.setRateLimitQps(req.getRateLimitQps());
        if (req.getEnabled() != null) existing.setEnabled(req.getEnabled());

        tenantConfigService.update(existing);
        log.info("Admin 更新租户 tenantId={}", tenantId);
        return UnifiedResult.ok(tenantId);
    }

    @DeleteMapping("/{tenantId}")
    public UnifiedResult delete(@PathVariable String tenantId) {
        tenantConfigService.delete(tenantId);
        log.info("Admin 软删租户 tenantId={}", tenantId);
        return UnifiedResult.ok(tenantId);
    }

    @PostMapping("/{tenantId}/refresh")
    public UnifiedResult refresh(@PathVariable String tenantId) {
        tenantConfigService.refresh(tenantId);
        return UnifiedResult.ok(tenantId);
    }

    /** 恢复软删的租户 (含级联恢复下属 datasource) */
    @PostMapping("/{tenantId}/restore")
    public UnifiedResult restore(@PathVariable String tenantId) {
        tenantConfigService.restore(tenantId);
        log.info("Admin 恢复租户 tenantId={}", tenantId);
        return UnifiedResult.ok(tenantId);
    }

    /**
     * 物理删除租户 (不可恢复, 级联清理 tenant_datasource / tenant_action_config).
     * Phase 6.4 由 AuthInterceptor 额外要求 X-Purge-Api-Key.
     */
    @DeleteMapping("/{tenantId}/purge")
    public UnifiedResult purge(@PathVariable String tenantId) {
        tenantConfigService.purge(tenantId);
        log.warn("Admin 物理删除租户 tenantId={}", tenantId);
        return UnifiedResult.ok(tenantId);
    }
}
