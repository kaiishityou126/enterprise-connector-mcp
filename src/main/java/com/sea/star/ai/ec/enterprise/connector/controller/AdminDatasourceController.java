package com.sea.star.ai.ec.enterprise.connector.controller;

import com.sea.star.ai.ec.enterprise.connector.domain.dto.TenantDatasourceCreateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.dto.TenantDatasourceUpdateRequest;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantDatasourceMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.exception.DatasourceNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.service.TenantDatasourceService;
import com.sea.star.ai.ec.enterprise.connector.service.security.TenantStatusGuard;
import com.sea.star.ai.ec.enterprise.connector.util.EncryptionUtils;
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
 * 租户数据源管理 API. 一个租户可挂多个数据源 (订单库 / 库存库 / CRM API 等), 按逻辑 ds_name 寻址.
 *
 * 路径设计:
 *   GET     /admin/datasources/{tid}                   列出该租户所有数据源
 *   GET     /admin/datasources/{tid}/{dsName}          查单条
 *   POST    /admin/datasources/{tid}/{dsName}          创建 (body 传明文密码, 服务端加密)
 *   PUT     /admin/datasources/{tid}/{dsName}          PATCH 更新 (只覆盖非 null 字段)
 *   DELETE  /admin/datasources/{tid}/{dsName}          软删
 *   POST    /admin/datasources/{tid}/{dsName}/restore  恢复软删
 *   DELETE  /admin/datasources/{tid}/{dsName}/purge    物理删 (不可逆, 需 X-Purge-Api-Key)
 *
 * 扁平路径与 /admin/tenants 并列, 不嵌套 — 便于跨租户做运维批处理.
 * 响应里 db_password_enc / api_token_enc 永远剥离为 null, 防止密文外泄.
 */
@Slf4j
@RestController
@RequestMapping("/admin/datasources")
@RequiredArgsConstructor
public class AdminDatasourceController {

    private final TenantDatasourceService datasourceService;
    private final TenantDatasourceMapper datasourceMapper;
    private final TenantStatusGuard tenantStatusGuard;
    private final EncryptionUtils encryptionUtils;

    @GetMapping("/{tid}")
    public UnifiedResult listByTenant(@PathVariable String tid) {
        tenantStatusGuard.requireExists(tid);
        List<TenantDatasource> list = datasourceService.listByTenant(tid);
        list.forEach(this::scrubSensitive);
        return UnifiedResult.ok(list);
    }

    @GetMapping("/{tid}/{dsName}")
    public UnifiedResult get(@PathVariable String tid, @PathVariable String dsName) {
        TenantDatasource ds = datasourceMapper.findByTenantAndDs(tid, dsName);
        if (ds == null) throw new DatasourceNotFoundException(tid, dsName);
        scrubSensitive(ds);
        return UnifiedResult.ok(ds);
    }

    @PostMapping("/{tid}/{dsName}")
    public UnifiedResult create(@PathVariable String tid,
                                @PathVariable String dsName,
                                @Valid @RequestBody TenantDatasourceCreateRequest req) {
        tenantStatusGuard.requireExists(tid);
        TenantDatasource ds = TenantDatasource.builder()
                .tenantId(tid)
                .dsName(dsName)
                .accessType(req.getAccessType())
                .dbUrl(req.getDbUrl())
                .dbUsername(req.getDbUsername())
                .dbPasswordEnc(encryptIfPresent(req.getDbPassword()))
                .dbDriver(req.getDbDriver())
                .apiBaseUrl(req.getApiBaseUrl())
                .apiAuthType(req.getApiAuthType())
                .apiTokenEnc(encryptIfPresent(req.getApiToken()))
                .apiHeaders(req.getApiHeaders())
                .enabled(Boolean.TRUE)
                .build();
        datasourceService.create(ds);
        log.info("Admin 创建数据源 tenantId={} ds={}", tid, dsName);
        return UnifiedResult.ok(tid + "/" + dsName);
    }

    @PutMapping("/{tid}/{dsName}")
    public UnifiedResult update(@PathVariable String tid,
                                @PathVariable String dsName,
                                @Valid @RequestBody TenantDatasourceUpdateRequest req) {
        TenantDatasource existing = datasourceMapper.findByTenantAndDs(tid, dsName);
        if (existing == null) throw new DatasourceNotFoundException(tid, dsName);

        if (req.getAccessType() != null) existing.setAccessType(req.getAccessType());
        if (req.getDbUrl() != null) existing.setDbUrl(req.getDbUrl());
        if (req.getDbUsername() != null) existing.setDbUsername(req.getDbUsername());
        if (req.getDbPassword() != null) existing.setDbPasswordEnc(encryptIfPresent(req.getDbPassword()));
        if (req.getDbDriver() != null) existing.setDbDriver(req.getDbDriver());
        if (req.getApiBaseUrl() != null) existing.setApiBaseUrl(req.getApiBaseUrl());
        if (req.getApiAuthType() != null) existing.setApiAuthType(req.getApiAuthType());
        if (req.getApiToken() != null) existing.setApiTokenEnc(encryptIfPresent(req.getApiToken()));
        if (req.getApiHeaders() != null) existing.setApiHeaders(req.getApiHeaders());
        if (req.getEnabled() != null) existing.setEnabled(req.getEnabled());

        datasourceService.update(existing);
        log.info("Admin 更新数据源 tenantId={} ds={}", tid, dsName);
        return UnifiedResult.ok(tid + "/" + dsName);
    }

    @DeleteMapping("/{tid}/{dsName}")
    public UnifiedResult delete(@PathVariable String tid, @PathVariable String dsName) {
        datasourceService.delete(tid, dsName);
        log.info("Admin 软删数据源 tenantId={} ds={}", tid, dsName);
        return UnifiedResult.ok(tid + "/" + dsName);
    }

    @PostMapping("/{tid}/{dsName}/restore")
    public UnifiedResult restore(@PathVariable String tid, @PathVariable String dsName) {
        datasourceService.restore(tid, dsName);
        return UnifiedResult.ok(tid + "/" + dsName);
    }

    /**
     * 物理删除数据源 (不可恢复).
     * <p>
     * Phase 6.4 会要求调用方额外传 {@code X-Purge-Api-Key} (AuthInterceptor 做二级校验).
     * 本 Controller 不做 key 校验, 交给拦截器层统一处理.
     */
    @DeleteMapping("/{tid}/{dsName}/purge")
    public UnifiedResult purge(@PathVariable String tid, @PathVariable String dsName) {
        datasourceService.purge(tid, dsName);
        return UnifiedResult.ok(tid + "/" + dsName);
    }

    private void scrubSensitive(TenantDatasource ds) {
        ds.setDbPasswordEnc(null);
        ds.setApiTokenEnc(null);
    }

    private String encryptIfPresent(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        return encryptionUtils.encrypt(plaintext);
    }
}
