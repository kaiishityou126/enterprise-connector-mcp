package com.sea.star.ai.ec.enterprise.connector.controller;

import com.sea.star.ai.ec.enterprise.connector.domain.dto.TenantActionConfigGrantRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.TenantActionConfigUpdateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantActionConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.service.TenantActionConfigService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户动作授权管理 API (Phase 6).
 * <p>
 * 路径设计 (RPC 风格动词):
 *   POST   /admin/tenants/{tid}/actions/{action}/grant         授权 / 新增
 *   PUT    /admin/tenants/{tid}/actions/{action}/grant         修改授权配置
 *   DELETE /admin/tenants/{tid}/actions/{action}/grant         撤销授权 (物理删)
 *   GET    /admin/tenants/{tid}/action-configs                 列出该租户所有授权
 *   GET    /admin/tenants/{tid}/actions/{action}/grant         查单条授权
 *   POST   /admin/tenants/{tid}/actions/grant-all-defaults     批量授权所有 enabled 模板
 */
@Slf4j
@RestController
@RequestMapping("/admin/tenants/{tid}")
@RequiredArgsConstructor
public class AdminActionConfigController {

    private final TenantActionConfigService tenantActionConfigService;

    @GetMapping("/action-configs")
    public UnifiedResult list(@PathVariable String tid) {
        List<TenantActionConfig> list = tenantActionConfigService.listByTenant(tid);
        return UnifiedResult.ok(list);
    }

    @GetMapping("/actions/{action}/grant")
    public UnifiedResult get(@PathVariable String tid, @PathVariable String action) {
        return UnifiedResult.ok(tenantActionConfigService.get(tid, action));
    }

    @PostMapping("/actions/{action}/grant")
    public UnifiedResult grant(@PathVariable String tid,
                               @PathVariable String action,
                               @Valid @RequestBody TenantActionConfigGrantRequest req) {
        TenantActionConfig spec = TenantActionConfig.builder()
                .templateId(req.getTemplateId())
                .datasourceNameOverride(req.getDatasourceNameOverride())
                .customSql(req.getCustomSql())
                .customApiPath(req.getCustomApiPath())
                .customParams(req.getCustomParams())
                .enabled(req.getEnabled() != null ? req.getEnabled() : Boolean.TRUE)
                .build();
        TenantActionConfig saved = tenantActionConfigService.grant(tid, action, spec);
        return UnifiedResult.ok(saved);
    }

    @PutMapping("/actions/{action}/grant")
    public UnifiedResult update(@PathVariable String tid,
                                @PathVariable String action,
                                @Valid @RequestBody TenantActionConfigUpdateRequest req) {
        TenantActionConfig patch = TenantActionConfig.builder()
                .datasourceNameOverride(req.getDatasourceNameOverride())
                .customSql(req.getCustomSql())
                .customApiPath(req.getCustomApiPath())
                .customParams(req.getCustomParams())
                .enabled(req.getEnabled())
                .build();
        TenantActionConfig updated = tenantActionConfigService.update(tid, action, patch);
        return UnifiedResult.ok(updated);
    }

    @DeleteMapping("/actions/{action}/grant")
    public UnifiedResult revoke(@PathVariable String tid, @PathVariable String action) {
        tenantActionConfigService.revoke(tid, action);
        return UnifiedResult.ok(tid + "/" + action);
    }

    /**
     * 批量授权: 把所有 enabled 模板都授权给该租户.
     * 对应 ds 不存在的模板会跳过 (不抛错).
     */
    @PostMapping("/actions/grant-all-defaults")
    public UnifiedResult grantAllDefaults(@PathVariable String tid) {
        List<String> newly = tenantActionConfigService.grantAllDefaults(tid);
        return UnifiedResult.ok(newly);
    }
}
