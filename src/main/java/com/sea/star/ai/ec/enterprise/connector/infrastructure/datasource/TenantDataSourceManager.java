package com.sea.star.ai.ec.enterprise.connector.infrastructure.datasource;

import com.sea.star.ai.ec.enterprise.connector.domain.model.TenantDatasource;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.exception.AdapterExecutionException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TenantConfigChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.event.TenantDatasourceChangedEvent;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.metrics.ConnectorMetrics;
import com.sea.star.ai.ec.enterprise.connector.service.TenantDatasourceService;
import com.sea.star.ai.ec.enterprise.connector.util.EncryptionUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按 (tenantId, dsName) 隔离的 DataSource 池 (Phase 6 起支持多数据源)。
 * <p>
 * LRU 淘汰 + 上限保护:
 *   300 个池 × 5 连接 = 1500 连接 worst case, 需要 PgBouncer 或调大 PG max_connections.
 * <p>
 * 并发策略: LinkedHashMap(accessOrder=true) 非线程安全, 所有访问在 synchronized 块中。
 * HikariCP 初始化走 DB/网络 I/O 可能耗时数百毫秒, 所以 build() 在锁外执行,
 * "先快读 → 无锁构建 → 再加锁决策"避免阻塞其他 (tenant, ds) 对。
 * <p>
 * Pool map key 格式: {@code "tenantId:dsName"}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantDataSourceManager {

    private final TenantDatasourceService tenantDatasourceService;
    private final EncryptionUtils encryptionUtils;
    private final ConnectorMetrics metrics;

    /** Pool 数量上限 (多数据源后按 (tenant, ds) 对计数); 兼容旧配置名 max-tenants */
    @Value("${connector.datasource-pool.max-pools:${connector.datasource-pool.max-tenants:300}}")
    private int maxPools;

    @Value("${connector.datasource-pool.per-tenant-max-connections:5}")
    private int perTenantMaxConnections;

    /** LRU 缓存: accessOrder=true 使 get 也会更新位置; key = "tenantId:dsName" */
    private final Map<String, HikariDataSource> pool = new LinkedHashMap<>(16, 0.75f, true);

    @PostConstruct
    void registerMetrics() {
        metrics.registerDataSourcePoolGauge(pool, Map::size);
    }

    /**
     * 获取或创建 (tenantId, dsName) 对应的 DataSource。
     */
    public DataSource getOrCreate(String tenantId, String dsName) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(dsName, "dsName 不能为空");
        String key = buildKey(tenantId, dsName);

        // 快路径: 命中缓存
        synchronized (this) {
            HikariDataSource existing = pool.get(key);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
        }

        // 慢路径: 无锁构建 (读 TenantDatasource + HikariCP 初始化)
        HikariDataSource built = build(tenantId, dsName);

        synchronized (this) {
            HikariDataSource winner = pool.get(key);
            if (winner != null && !winner.isClosed()) {
                log.debug("并发构建同 key DataSource, 丢弃本实例 key={}", key);
                closeQuietly(built, key);
                return winner;
            }
            pool.put(key, built);
            enforceCapacity();
            return built;
        }
    }

    /** 淘汰单个 (tenantId, dsName) 的池 */
    public void evict(String tenantId, String dsName) {
        String key = buildKey(tenantId, dsName);
        HikariDataSource ds;
        synchronized (this) {
            ds = pool.remove(key);
        }
        // close 可能走网络 I/O (Hikari 关连接池), 放锁外防止阻塞其他租户的 getOrCreate
        closeQuietly(ds, key);
    }

    /** 淘汰某租户下所有池 (租户变更/删除时用) */
    public void evictAllForTenant(String tenantId) {
        String prefix = tenantId + ":";
        // Step 1: 锁内只做 map 修改, 收集待关闭的池实例
        java.util.Map<String, HikariDataSource> removed = new java.util.LinkedHashMap<>();
        synchronized (this) {
            java.util.Iterator<java.util.Map.Entry<String, HikariDataSource>> it = pool.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, HikariDataSource> e = it.next();
                if (e.getKey().startsWith(prefix)) {
                    removed.put(e.getKey(), e.getValue());
                    it.remove();
                }
            }
        }
        // Step 2: 锁外 close, close 本身不需要 map 锁, 避免阻塞其他租户操作
        // closeQuietly 参数顺序是 (ds, key), Map.forEach 给的是 (key, value), 要换序
        removed.forEach((k, v) -> closeQuietly(v, k));
        if (!removed.isEmpty()) {
            log.info("租户变更/删除, 淘汰 {} 个池 tenantId={}", removed.size(), tenantId);
        }
    }

    /**
     * 监听租户配置变更: UPDATE/DELETE 时淘汰该租户下的所有池。
     * AFTER_COMMIT 保证只在事务成功后触发。
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onTenantConfigChanged(TenantConfigChangedEvent event) {
        if (event.getKind() == TenantConfigChangedEvent.Kind.CREATED) return;
        evictAllForTenant(event.getTenantId());
    }

    /**
     * 监听数据源变更: 任何增删改都要淘汰本地池, 下次 getOrCreate 重建。
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onTenantDatasourceChanged(TenantDatasourceChangedEvent event) {
        evict(event.getTenantId(), event.getDsName());
    }

    private void enforceCapacity() {
        while (pool.size() > maxPools) {
            Iterator<Map.Entry<String, HikariDataSource>> it = pool.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<String, HikariDataSource> oldest = it.next();
            it.remove();
            log.info("DataSource 池超过上限 {}, LRU 淘汰 key={}", maxPools, oldest.getKey());
            closeQuietly(oldest.getValue(), oldest.getKey());
        }
    }

    private HikariDataSource build(String tenantId, String dsName) {
        TenantDatasource ds = tenantDatasourceService.getDatasource(tenantId, dsName);
        if (ds.getDbUrl() == null || ds.getDbUrl().isBlank()) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_DB_ERROR,
                    "数据源未配置 db_url: " + tenantId + "/" + dsName);
        }

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("ds-" + tenantId + "-" + dsName);
        hc.setJdbcUrl(ds.getDbUrl());
        hc.setUsername(ds.getDbUsername());
        hc.setPassword(encryptionUtils.decrypt(ds.getDbPasswordEnc()));
        if (ds.getDbDriver() != null && !ds.getDbDriver().isBlank()) {
            hc.setDriverClassName(ds.getDbDriver());
        }
        hc.setMaximumPoolSize(perTenantMaxConnections);
        hc.setConnectionTimeout(3_000);
        hc.setReadOnly(true);
        try {
            HikariDataSource h = new HikariDataSource(hc);
            hc.setPassword(null); // 清空配置里的明文密码, 防日志泄漏
            log.info("创建 DataSource tenantId={} ds={} url={}", tenantId, dsName, ds.getDbUrl());
            return h;
        } catch (Exception e) {
            hc.setPassword(null);
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_DB_ERROR,
                    "创建 DataSource 失败: " + tenantId + "/" + dsName, e);
        }
    }

    private String buildKey(String tenantId, String dsName) {
        return tenantId + ":" + dsName;
    }

    private void closeQuietly(HikariDataSource ds, String key) {
        if (ds == null) return;
        try {
            ds.close();
        } catch (Exception e) {
            log.warn("关闭 DataSource 失败 key={}", key, e);
        }
    }

    @PreDestroy
    public synchronized void closeAll() {
        log.info("关闭所有租户 DataSource, 共 {} 个", pool.size());
        pool.forEach((k, ds) -> closeQuietly(ds, k));
        pool.clear();
    }
}
