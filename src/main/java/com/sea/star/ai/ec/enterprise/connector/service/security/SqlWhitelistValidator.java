package com.sea.star.ai.ec.enterprise.connector.service.security;

import com.sea.star.ai.ec.enterprise.connector.exception.SqlValidationException;
import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.springframework.stereotype.Component;

/**
 * SQL 白名单校验, 区分两种来源应用不同强度的策略.
 *
 * <p>来源:
 * <ul>
 *   <li>{@link Source#TEMPLATE} - 来自 action_template, 由开发/运维团队预审写入. 跳过 AST 校验,
 *       因为 JSqlParser 对 MySQL 反引号 / Oracle CONNECT BY / SQL Server hint 等方言支持有限,
 *       严校验会误伤合法的多方言 SQL. 长度 + 分号 + 函数黑名单仍执行.</li>
 *   <li>{@link Source#TENANT_CUSTOM} - 来自 tenant_action_config.custom_sql, premium 租户写入.
 *       全部校验 (长度 + 分号 + AST 必须 SELECT + 函数黑名单), 严防注入和高危调用.</li>
 * </ul>
 *
 * <p>共同策略:
 * <ol>
 *   <li>长度上限 (sys_dict.limit.max_custom_sql_length)</li>
 *   <li>禁止分号 (多语句注入)</li>
 *   <li>函数黑名单 (pg_sleep / xp_cmdshell / dblink 等危险函数)</li>
 * </ol>
 *
 * <p>仅 TENANT_CUSTOM 额外执行:
 * <ol start="4">
 *   <li>JSqlParser AST 校验: 必须是 SELECT, 禁止 SELECT INTO</li>
 * </ol>
 *
 * <p>兜底防线: 数据库只读账号 (connector_readonly 角色). 即使校验被绕过也写不进去.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlWhitelistValidator {

    /** SQL 来源, 决定校验强度. */
    public enum Source {
        /** 运维预审的模板 SQL, 跳过 AST (JSqlParser 多方言兼容性差). */
        TEMPLATE,
        /** 租户 custom_sql, 全量严校验. */
        TENANT_CUSTOM
    }

    private static final String DICT_MAX_SQL_LENGTH = "limit.max_custom_sql_length";

    /** 禁止的函数名 */
    private static final List<String> BLOCKED_FUNCTIONS = List.of(
            "pg_sleep", "pg_read_file", "pg_read_binary_file", "pg_ls_dir",
            "pg_terminate_backend", "pg_cancel_backend",
            "lo_export", "lo_import", "lo_creat", "lo_unlink",
            "dblink", "dblink_exec", "dblink_connect",
            "copy_from", "copy_to",
            "xp_cmdshell", "load_file", "sleep", "benchmark"
    );

    /** 预编译一个大正则: \b(func1|func2|...)\s*\( */
    private static final Pattern BLOCKED_FUNCTION_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", BLOCKED_FUNCTIONS) + ")\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * 命名参数正则: lookbehind {@code (?<!:)} 排除 PG {@code ::cast} 第二个冒号
     * 被误识别为参数起点 (如 {@code :oid::varchar} 中只匹配 :oid).
     */
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(
            "(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

    private final SysDictService sysDictService;

    /**
     * 单参重载: 默认按 TENANT_CUSTOM 严校验, 仅供旧测试和向后兼容用.
     *
     * <p>新代码必须用 {@link #validate(String, Source)} 显式传来源. 误把模板 SQL
     * 当成 TENANT_CUSTOM 严校验, 会因为 JSqlParser 不识别多方言语法而把合法模板拒掉.
     *
     * @deprecated 使用 {@link #validate(String, Source)} 替代
     */
    @Deprecated
    public void validate(String sql) {
        validate(sql, Source.TENANT_CUSTOM);
    }

    /**
     * 按来源选择校验强度. 不合规时抛 SqlValidationException (HTTP 400).
     */
    public void validate(String sql, Source source) {
        if (sql == null || sql.isBlank()) {
            throw new SqlValidationException("SQL 为空");
        }

        int maxLen = sysDictService.getInt(DICT_MAX_SQL_LENGTH, 2000);
        if (sql.length() > maxLen) {
            throw new SqlValidationException(
                    "SQL 超过长度限制 " + maxLen + " 字符");
        }

        if (sql.contains(";")) {
            throw new SqlValidationException("SQL 不允许包含分号");
        }

        // AST 校验: 仅对租户 custom_sql 执行 (JSqlParser 对 MySQL/Oracle/SQL Server 方言支持有限,
        // 模板信任运维预审, 强校验会误伤合法的反引号/CONNECT BY/hint 等)
        if (source == Source.TENANT_CUSTOM) {
            Statement stmt;
            try {
                stmt = CCJSqlParserUtil.parse(sql);
            } catch (JSQLParserException e) {
                throw new SqlValidationException(
                        "custom_sql 无法解析: " + rootCauseMessage(e));
            }
            if (!(stmt instanceof Select select)) {
                throw new SqlValidationException(
                        "custom_sql 必须是 SELECT, 实际: " + stmt.getClass().getSimpleName());
            }
            assertNoSelectInto(select);
        }

        // 函数黑名单扫描: 两类来源都执行. 先剥离字符串字面量和注释, 避免误伤合法 SQL
        // 例如 SELECT 'The pg_sleep(5) is dangerous' FROM t  —— 字符串里的函数名不应被拦
        String stripped = stripLiteralsAndComments(sql);
        if (BLOCKED_FUNCTION_PATTERN.matcher(stripped).find()) {
            throw new SqlValidationException(
                    "SQL 含禁止调用的函数, 请检查是否使用了 pg_sleep/xp_cmdshell/dblink 等");
        }
    }

    /**
     * 移除 SQL 中的字符串字面量（'xxx', "xxx"）、行注释（--）和块注释（/* ... *&#47;），
     * 替换为空字符串。这样函数黑名单扫描只对真实代码生效。
     *
     * SQL 标准中用两个连续单引号 ('') 表示字符串内的一个单引号字符。
     *
     * <p>本方法是 package private 工具, 同包内 (SqlWhitelistValidator 自身的黑名单扫描)
     * 和 DatabaseAdapter (custom_sql 占位符提取) 共用. 改为 public 是为了跨包复用 +
     * 现有覆盖测试 ({@code StripHelper} 嵌套类) 已充分.
     */
    public static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // 行注释 --
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                i = (end < 0) ? n : end;
                continue;
            }
            // 块注释 /* ... */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 2;
                continue;
            }
            // 单引号字符串
            if (c == '\'') {
                i++;
                while (i < n) {
                    char cc = sql.charAt(i);
                    if (cc == '\'') {
                        // 连续两个 '' 表示字面量里的单引号
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append("''"); // 占位，避免相邻 token 连起来
                continue;
            }
            // 双引号：PostgreSQL 中是标识符引用，保留原样（函数名仍会出现）
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 提取 SQL 中所有 NamedParameterJdbcTemplate 风格的 {@code :name} 占位符.
     * 自动剥离字符串字面量 / 注释, 并通过 lookbehind 排除 PG {@code ::cast} 误识.
     * <p>
     * 用途:
     * <ul>
     *   <li>{@code DatabaseAdapter.padMissingParamsFromSql} — 给 source 缺失 key 补 null</li>
     *   <li>{@code TenantActionConfigService.validateCustomSql} — 校验 custom_sql 占位符
     *       必须 ⊆ 模板 paramSchema 已声明字段集 (防止 PREMIUM 写引用 AI 看不见的参数)</li>
     * </ul>
     *
     * @return 占位符名集合, 用 LinkedHashSet 保证遍历顺序稳定 (利于错误信息可读)
     */
    public static Set<String> extractNamedParameters(String sql) {
        if (sql == null || sql.isBlank()) return Set.of();
        String stripped = stripLiteralsAndComments(sql);
        Set<String> names = new LinkedHashSet<>();
        Matcher m = NAMED_PARAM_PATTERN.matcher(stripped);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private void assertNoSelectInto(Select select) {
        if (select instanceof PlainSelect ps
                && ps.getIntoTables() != null
                && !ps.getIntoTables().isEmpty()) {
            throw new SqlValidationException("custom_sql 不允许 SELECT INTO");
        }
        if (select instanceof SetOperationList sol) {
            for (Select inner : sol.getSelects()) {
                assertNoSelectInto(inner);
            }
        }
    }

    private String rootCauseMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }
}
