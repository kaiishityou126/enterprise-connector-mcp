package com.sea.star.ai.ec.enterprise.connector.service.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.exception.AdapterExecutionException;
import com.sea.star.ai.ec.enterprise.connector.infrastructure.datasource.TenantDataSourceManager;
import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
import com.sea.star.ai.ec.enterprise.connector.service.security.SqlWhitelistValidator;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 直连数据库适配器。
 *
 * <p>参数绑定走 NamedParameterJdbcTemplate，防 SQL 注入。
 *
 * <p>多方言适配: 通过 {@link AccessType#isDb()} 接受 POSTGRES/MYSQL/ORACLE/SQLSERVER,
 * 实际方言差异由模板的 SQL 自身处理 (LIMIT/JOIN 语法/JSON 函数), Adapter 不再做语法翻译.
 *
 * <p>行数上限: LIMIT 由模板手写, Adapter 不再自动拼接. 查询完成后做事后校验,
 * 实际行数 > template.max_rows 仅记 WARN, 不截断/不抛异常 (避免半截结果迷惑业务).
 * 这是给运维"忘写 LIMIT"的事后告警, 主要防御还是模板审核 + 只读账号兜底.
 *
 * <p>超时: 优先使用模板 timeout_seconds, 超过 sys_dict.limit.absolute_max_timeout 则截断.
 * Statement.setQueryTimeout 跨方言通用 (PG/MySQL/SQL Server/Oracle 均支持).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseAdapter implements BusinessAdapter {

    private static final String DICT_ABSOLUTE_MAX_ROWS = "limit.absolute_max_rows";
    private static final String DICT_DEFAULT_MAX_ROWS = "limit.default_max_rows";
    private static final String DICT_ABSOLUTE_MAX_TIMEOUT = "limit.absolute_max_timeout";
    private static final String DICT_DEFAULT_QUERY_TIMEOUT = "limit.default_query_timeout";

    private final TenantDataSourceManager dataSourceManager;
    private final SysDictService sysDictService;

    @Override
    public boolean supports(AccessType accessType) {
        return accessType != null && accessType.isDb();
    }

    @Override
    public UnifiedResult execute(AdapterRequest request) {
        String tenantId = request.getTenantConfig().getTenantId();
        String dsName = request.getDatasource().getDsName();
        String sql = request.getResolvedSql();
        if (sql == null || sql.isBlank()) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_DB_ERROR, "SQL 为空, 无法执行");
        }

        int timeoutSeconds = resolveTimeout(request.getTemplate().getTimeoutSeconds());
        int absoluteMaxRows = sysDictService.getInt(DICT_ABSOLUTE_MAX_ROWS, 10000);

        DataSource ds = dataSourceManager.getOrCreate(tenantId, dsName);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        jdbc.getJdbcTemplate().setQueryTimeout(timeoutSeconds);
        // 结果集硬上限: JDBC 标准 setMaxRows 由驱动负责截断, 防止运维忘写 LIMIT 导致全表回流 OOM.
        // 仅作"绝对天花板", 业务级 LIMIT 仍由模板手写控制. 三家驱动 (PG/MySQL/SQL Server) 都支持.
        jdbc.getJdbcTemplate().setMaxRows(absoluteMaxRows);

        MapSqlParameterSource source = new MapSqlParameterSource();
        if (request.getParams() != null) {
            request.getParams().forEach(source::addValue);
        }
        // 让 SQL 里"WHERE (:p::varchar IS NULL OR ...)" 这种"可选过滤"模式真正能用 —
        // 否则 NamedParameterJdbcTemplate 会因 source 缺 key 抛 "No value supplied for ...".
        // 占位符集合的来源按路径分流:
        //   - custom_sql 路径: 从租户 SQL 自身解析 (PREMIUM 自由占位符, 跟模板 paramSchema 解耦)
        //   - 模板路径: 从模板 paramSchema 拿 (跟 ParamValidator 契约一致, 必填字段已先一步拦截)
        if (request.isCustomSqlMode()) {
            padMissingParamsFromSql(source, sql);
        } else {
            padMissingParamsWithNull(source, request.getTemplate().getParamSchema());
        }

        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, source);
            long cost = System.currentTimeMillis() - start;
            checkRowsExceedMaxRows(rows.size(), request.getTemplate().getMaxRows(), tenantId, dsName);
            log.info("DB 查询完成 tenantId={}, ds={}, rows={}, cost={}ms",
                    tenantId, dsName, rows.size(), cost);
            return UnifiedResult.ok(rows);
        } catch (QueryTimeoutException e) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_TIMEOUT,
                    "数据库查询超时 (>" + timeoutSeconds + "s), tenantId=" + tenantId + "/" + dsName, e);
        } catch (Exception e) {
            throw new AdapterExecutionException(
                    ErrorCode.ADAPTER_DB_ERROR,
                    "数据库查询失败, tenantId=" + tenantId + "/" + dsName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 事后行数校验. LIMIT 由模板手写 + setMaxRows 兜底, 此处只做"超出告警", 不抛异常.
     */
    private void checkRowsExceedMaxRows(int actualRows, Integer templateMaxRows, String tenantId, String dsName) {
        // 聚合查询标记早退, 省掉两次 sys_dict 查询
        if (templateMaxRows != null && templateMaxRows < 0) {
            return;
        }
        int absoluteMax = sysDictService.getInt(DICT_ABSOLUTE_MAX_ROWS, 10000);
        int defaultMax = sysDictService.getInt(DICT_DEFAULT_MAX_ROWS, 500);
        int effective = computeEffectiveMaxRows(templateMaxRows, defaultMax, absoluteMax);
        if (effective >= 0 && actualRows > effective) {
            log.warn("DB 查询行数超过 max_rows 告警值 tenantId={}, ds={}, actualRows={}, maxRows={} - 模板可能漏写 LIMIT",
                    tenantId, dsName, actualRows, effective);
        }
    }

    /**
     * 计算行数告警上限 (纯函数, 便于单测).
     * <ul>
     *   <li>templateMaxRows = null  → 返回 defaultMax (兜底默认)</li>
     *   <li>templateMaxRows &lt; 0 → 返回 -1 (聚合查询标记, 跳过校验)</li>
     *   <li>templateMaxRows &gt; absoluteMax → 返回 absoluteMax (兜底封顶)</li>
     *   <li>其他 → 返回 templateMaxRows 本身</li>
     * </ul>
     */
    static int computeEffectiveMaxRows(Integer templateMaxRows, int defaultMax, int absoluteMax) {
        if (templateMaxRows == null) {
            return defaultMax;
        }
        if (templateMaxRows < 0) {
            return -1;
        }
        return Math.min(templateMaxRows, absoluteMax);
    }

    private int resolveTimeout(Integer templateTimeout) {
        int absoluteMax = sysDictService.getInt(DICT_ABSOLUTE_MAX_TIMEOUT, 600);
        int defaultTimeout = sysDictService.getInt(DICT_DEFAULT_QUERY_TIMEOUT, 5);
        int effective = templateTimeout != null ? templateTimeout : defaultTimeout;
        return Math.min(effective, absoluteMax);
    }

    /**
     * 按 param_schema 声明的字段集合, 把 source 里缺失的 key 显式补成 null.
     * <p>
     * 解决 NamedParameterJdbcTemplate 的语义陷阱: SQL 里 :orderId 即使写在
     * "WHERE :orderId::varchar IS NULL OR ..." 中, source 也必须有 orderId 这个 key
     * (值可以为 null). 没有 key 直接抛 "No value supplied for the SQL parameter".
     * <p>
     * 校验阶段已经由 ParamValidator.validate 走过 paramSchema, 缺必填会先抛错;
     * 走到这里的字段都是"可选未传 → 应当是 null". 与 ParamValidator 的契约保持一致.
     */
    @SuppressWarnings("unchecked")
    private static void padMissingParamsWithNull(MapSqlParameterSource source, String paramSchemaJson) {
        if (paramSchemaJson == null || paramSchemaJson.isBlank()) return;
        Map<String, Object> schema = JsonUtils.fromJson(paramSchemaJson,
                new TypeReference<Map<String, Object>>() {});
        if (schema == null) return;
        for (String key : schema.keySet()) {
            if (!source.hasValue(key)) {
                source.addValue(key, null);
            }
        }
    }

    /**
     * 从 SQL 文本解析命名占位符集合, 给 source 里缺失的 key 补 null. 用于 custom_sql 路径,
     * 此时占位符以租户 SQL 自身为准, 不再受模板 paramSchema 限制.
     * <p>
     * 占位符提取由 {@link SqlWhitelistValidator#extractNamedParameters} 承担
     * (它已正确处理字符串字面量/注释/PG cast 等边界, 跟 TenantActionConfigService
     * 的写入校验复用同一逻辑).
     * <p>
     * 安全模型: 补的占位符仍走 NamedParameterJdbcTemplate prepared statement 绑定,
     * 不存在注入风险. 租户 SQL 引用的占位符调用方没传 → 补 null, 让
     * {@code WHERE (:foo IS NULL OR col = :foo)} 这种可选过滤分支等价"不过滤".
     */
    static void padMissingParamsFromSql(MapSqlParameterSource source, String sql) {
        for (String name : SqlWhitelistValidator.extractNamedParameters(sql)) {
            if (!source.hasValue(name)) {
                source.addValue(name, null);
            }
        }
    }
}
