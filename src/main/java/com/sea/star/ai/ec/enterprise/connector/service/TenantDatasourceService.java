package com.sea.star.ai.ec.enterprise.connector.service;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.TenantDatasourceMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.exception.DatasourceNotFoundException;
import com.sea.star.ai.ec.enterprise.connector.exception.ParamValidationException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.cache.TwoLevelCacheManager;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TenantDatasourceChangedEvent;
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
 * 租户数据源服务 (Phase 6 新增).
 * <p>
 * 两级缓存 key 格式: {@code tenantDatasource:{tenantId}:{dsName}}.
 * <p>
 * 职责:
 *   - 按 (tenantId, dsName) 取 {@link TenantDatasource}, 走两级缓存
 *   - CRUD + 失效广播 (via Redis Pub/Sub + Spring event)
 *   - 密码 / token 解密 (密文列只从本实体读, 不从 TenantConfig 读)
 * <p>
 * 关注点分离: TenantConfigService 只管租户身份/策略,
 * 数据源的生命周期完全由本服务管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDatasourceService {

    private final TenantDatasourceMapper tenantDatasourceMapper;
    private final TwoLevelCacheManager cacheManager;
    private final ApplicationEventPublisher eventPublisher;
    /** 给 restore/purge 绕开 Flex logic-delete 自动过滤用 */
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 按 (tenantId, dsName) 取数据源, 走两级缓存。找不到或已软删或 enabled=false 都会抛异常。
     */
    public TenantDatasource getDatasource(String tenantId, String dsName) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(dsName, "dsName 不能为空");

        String cacheKey = buildCacheKey(tenantId, dsName);
        TenantDatasource ds = cacheManager.get(
                BusinessConstants.CACHE_TENANT_DATASOURCE,
                cacheKey,
                TenantDatasource.class,
                () -> tenantDatasourceMapper.findByTenantAndDs(tenantId, dsName));
        if (ds == null) {
            throw new DatasourceNotFoundException(tenantId, dsName);
        }
        if (Boolean.FALSE.equals(ds.getEnabled())) {
            throw new DatasourceNotFoundException(tenantId, dsName,
                    "数据源已禁用: " + tenantId + "/" + dsName);
        }
        return ds;
    }

    /** 列出某租户的全部未软删数据源 */
    public List<TenantDatasource> listByTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        return tenantDatasourceMapper.findByTenantId(tenantId);
    }

    @Transactional
    public void create(TenantDatasource ds) {
        Objects.requireNonNull(ds, "datasource 不能为空");
        Objects.requireNonNull(ds.getTenantId(), "tenantId 不能为空");
        Objects.requireNonNull(ds.getDsName(), "dsName 不能为空");
        validateForAccessType(ds);
        tenantDatasourceMapper.insert(ds);
        // 写穿: 创建后立刻放入缓存, 下次读无需打 DB
        cacheManager.put(BusinessConstants.CACHE_TENANT_DATASOURCE,
                buildCacheKey(ds.getTenantId(), ds.getDsName()), ds);
        publishInvalidate(ds.getTenantId(), ds.getDsName());
        eventPublisher.publishEvent(new TenantDatasourceChangedEvent(
                ds.getTenantId(), ds.getDsName(), TenantDatasourceChangedEvent.Kind.CREATED));
    }

    @Transactional
    public void update(TenantDatasource ds) {
        Objects.requireNonNull(ds, "datasource 不能为空");
        validateForAccessType(ds);
        int rows = tenantDatasourceMapper.update(ds);
        if (rows == 0) {
            throw new DatasourceNotFoundException(ds.getTenantId(), ds.getDsName());
        }
        // 失效缓存而不是 put, 保证跟 DB 一致 (update 可能没带所有字段)
        cacheManager.evict(BusinessConstants.CACHE_TENANT_DATASOURCE,
                buildCacheKey(ds.getTenantId(), ds.getDsName()));
        publishInvalidate(ds.getTenantId(), ds.getDsName());
        eventPublisher.publishEvent(new TenantDatasourceChangedEvent(
                ds.getTenantId(), ds.getDsName(), TenantDatasourceChangedEvent.Kind.UPDATED));
    }

    @Transactional
    public void delete(String tenantId, String dsName) {
        // Flex 自动改写成 UPDATE deleted_at = NOW() (软删)
        tenantDatasourceMapper.deleteByTenantAndDs(tenantId, dsName);
        cacheManager.evict(BusinessConstants.CACHE_TENANT_DATASOURCE,
                buildCacheKey(tenantId, dsName));
        publishInvalidate(tenantId, dsName);
        eventPublisher.publishEvent(new TenantDatasourceChangedEvent(
                tenantId, dsName, TenantDatasourceChangedEvent.Kind.DELETED));
    }

    /** 恢复软删的数据源 (Admin restore 用, 绕开 Flex logic-delete 过滤) */
    @Transactional
    public void restore(String tenantId, String dsName) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(dsName, "dsName 不能为空");
        int rows = jdbcTemplate.update(
                "UPDATE tenant_datasource SET deleted = FALSE, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND ds_name = ?",
                tenantId, dsName);
        if (rows == 0) throw new DatasourceNotFoundException(tenantId, dsName);
        cacheManager.evict(BusinessConstants.CACHE_TENANT_DATASOURCE, buildCacheKey(tenantId, dsName));
        publishInvalidate(tenantId, dsName);
        eventPublisher.publishEvent(new TenantDatasourceChangedEvent(
                tenantId, dsName, TenantDatasourceChangedEvent.Kind.UPDATED));
        log.info("Admin 恢复数据源 tenantId={} ds={}", tenantId, dsName);
    }

    /** 物理删除数据源 (Admin purge 用, 不可恢复) */
    @Transactional
    public void purge(String tenantId, String dsName) {
        int rows = jdbcTemplate.update(
                "DELETE FROM tenant_datasource WHERE tenant_id = ? AND ds_name = ?",
                tenantId, dsName);
        if (rows == 0) throw new DatasourceNotFoundException(tenantId, dsName);
        cacheManager.evict(BusinessConstants.CACHE_TENANT_DATASOURCE, buildCacheKey(tenantId, dsName));
        publishInvalidate(tenantId, dsName);
        eventPublisher.publishEvent(new TenantDatasourceChangedEvent(
                tenantId, dsName, TenantDatasourceChangedEvent.Kind.DELETED));
        log.warn("Admin 物理删除数据源 tenantId={} ds={} (不可恢复)", tenantId, dsName);
    }

    /**
     * 按 accessType 校验"该类型必填字段"是否齐全。fail-fast 优于让用户在调用时才看到 AdapterExecutionException.
     * <p>
     * DB 家族 (POSTGRES/MYSQL/ORACLE/SQLSERVER): 要求 dbUrl + dbDriver 都不为空
     * (dbDriver 显式声明避免 HikariCP 走 SPI 自动发现踩驱动选错坑).
     * accessType=API: 要求 apiBaseUrl 不为空.
     */
    private void validateForAccessType(TenantDatasource ds) {
        AccessType type = ds.getAccessType();
        if (type == null) {
            throw new ParamValidationException("accessType 不能为空");
        }
        if (type.isDb()) {
            if (isBlank(ds.getDbUrl())) {
                throw new ParamValidationException("accessType=" + type + " 时 dbUrl 不能为空");
            }
            if (isBlank(ds.getDbDriver())) {
                throw new ParamValidationException(
                        "accessType=" + type + " 时 dbDriver 不能为空, 请显式传 JDBC 驱动类名 (如 org.postgresql.Driver)");
            }
        } else if (type == AccessType.API) {
            if (isBlank(ds.getApiBaseUrl())) {
                throw new ParamValidationException("accessType=API 时 apiBaseUrl 不能为空");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String buildCacheKey(String tenantId, String dsName) {
        return tenantId + ":" + dsName;
    }

    private void publishInvalidate(String tenantId, String dsName) {
        if (stringRedisTemplate == null) return;
        try {
            String payload = BusinessConstants.CACHE_TENANT_DATASOURCE
                    + "|" + buildCacheKey(tenantId, dsName);
            stringRedisTemplate.convertAndSend(
                    BusinessConstants.CHANNEL_CACHE_INVALIDATE, payload);
        } catch (Exception e) {
            log.error("发布数据源缓存失效消息失败 tenantId={} dsName={}", tenantId, dsName, e);
        }
    }
}
