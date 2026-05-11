package com.sea.star.ai.ec.enterprise.connector.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.sea.star.ai.ec.enterprise.connector.exception.SqlValidationException;
import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlWhitelistValidatorTest {

    @Mock
    private SysDictService sysDictService;

    private SqlWhitelistValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SqlWhitelistValidator(sysDictService);
        when(sysDictService.getInt(eq("limit.max_custom_sql_length"), anyInt())).thenReturn(2000);
    }

    @Nested
    @DisplayName("基础合法性")
    class Basic {

        @Test
        void 合法_SELECT_放行() {
            assertThatCode(() -> validator.validate("SELECT id, name FROM users WHERE tenant_id = '1'"))
                    .doesNotThrowAnyException();
        }

        @Test
        void 空串_拒绝() {
            assertThatThrownBy(() -> validator.validate(""))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("为空");
        }

        @Test
        void null_拒绝() {
            assertThatThrownBy(() -> validator.validate(null))
                    .isInstanceOf(SqlValidationException.class);
        }

        @Test
        void 仅空白_拒绝() {
            assertThatThrownBy(() -> validator.validate("   \n  "))
                    .isInstanceOf(SqlValidationException.class);
        }
    }

    @Nested
    @DisplayName("长度限制")
    class MaxLength {

        @Test
        void 超长_拒绝() {
            when(sysDictService.getInt(eq("limit.max_custom_sql_length"), anyInt())).thenReturn(10);
            assertThatThrownBy(() -> validator.validate("SELECT * FROM users"))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("长度限制");
        }

        @Test
        void 字典未配置时_用兜底默认值() {
            // MockitoExtension 默认 stub 返回 0，这里显式给 2000 作为兜底
            when(sysDictService.getInt(eq("limit.max_custom_sql_length"), anyInt())).thenReturn(2000);
            assertThatCode(() -> validator.validate("SELECT 1"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("分号 / 多语句")
    class Semicolon {

        @Test
        void 尾分号_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELECT 1;"))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("分号");
        }

        @Test
        void 中间分号_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELECT 1; DROP TABLE users"))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("分号");
        }
    }

    @Nested
    @DisplayName("AST 结构校验")
    class AstCheck {

        @Test
        void INSERT_拒绝() {
            assertThatThrownBy(() -> validator.validate("INSERT INTO users (id) VALUES (1)"))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("必须是 SELECT");
        }

        @Test
        void UPDATE_拒绝() {
            assertThatThrownBy(() -> validator.validate("UPDATE users SET name = 'x'"))
                    .isInstanceOf(SqlValidationException.class);
        }

        @Test
        void DELETE_拒绝() {
            assertThatThrownBy(() -> validator.validate("DELETE FROM users"))
                    .isInstanceOf(SqlValidationException.class);
        }

        @Test
        void 语法错误_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELEC FROM x"))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("无法解析");
        }

        @Test
        void SELECT_INTO_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELECT id INTO backup FROM users"))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("SELECT INTO");
        }

        @Test
        void UNION_里含_SELECT_INTO_拒绝() {
            String sql = "SELECT id FROM a UNION SELECT id INTO backup FROM b";
            assertThatThrownBy(() -> validator.validate(sql))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("SELECT INTO");
        }

        @Test
        void 正常_UNION_放行() {
            assertThatCode(() -> validator.validate("SELECT id FROM a UNION SELECT id FROM b"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("函数黑名单")
    class BlockedFunctions {

        @Test
        void pg_sleep_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELECT pg_sleep(5)"))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("禁止调用的函数");
        }

        @Test
        void 大小写混合_pg_sleep_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELECT Pg_SLEEP(5)"))
                    .isInstanceOf(SqlValidationException.class);
        }

        @Test
        void dblink_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELECT * FROM dblink('host=x', 'SELECT 1') AS t(id int)"))
                    .isInstanceOf(SqlValidationException.class);
        }

        @Test
        void lo_export_拒绝() {
            assertThatThrownBy(() -> validator.validate("SELECT lo_export(1, '/etc/passwd')"))
                    .isInstanceOf(SqlValidationException.class);
        }

        @Test
        void 字符串字面量里的函数名_不误伤() {
            // 'pg_sleep(5)' 在字符串里，stripLiteralsAndComments 会剥掉
            assertThatCode(() -> validator.validate("SELECT 'pg_sleep(5) is dangerous' AS warning FROM users"))
                    .doesNotThrowAnyException();
        }

        @Test
        void 行注释里的函数名_不误伤() {
            String sql = "SELECT id FROM users -- pg_sleep(5)\n WHERE tenant_id = '1'";
            assertThatCode(() -> validator.validate(sql))
                    .doesNotThrowAnyException();
        }

        @Test
        void 块注释里的函数名_不误伤() {
            String sql = "SELECT id FROM users /* pg_sleep(5) */ WHERE tenant_id = '1'";
            assertThatCode(() -> validator.validate(sql))
                    .doesNotThrowAnyException();
        }

        @Test
        void 字符串内含转义单引号_正确闭合() {
            // 'it''s pg_sleep(5)' 包含 SQL 标准的 '' 转义
            String sql = "SELECT 'it''s pg_sleep(5)' FROM users";
            assertThatCode(() -> validator.validate(sql))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("stripLiteralsAndComments 单元行为")
    class StripHelper {

        @Test
        void 空串() {
            assertThat(SqlWhitelistValidator.stripLiteralsAndComments(""))
                    .isEqualTo("");
        }

        @Test
        void 只字符串字面量() {
            assertThat(SqlWhitelistValidator.stripLiteralsAndComments("'abc'"))
                    .isEqualTo("''");
        }

        @Test
        void 未闭合字符串_吞到末尾() {
            assertThat(SqlWhitelistValidator.stripLiteralsAndComments("SELECT 'unterminated"))
                    .isEqualTo("SELECT ''");
        }

        @Test
        void 未闭合块注释_吞到末尾() {
            assertThat(SqlWhitelistValidator.stripLiteralsAndComments("SELECT 1 /* not closed"))
                    .isEqualTo("SELECT 1 ");
        }
    }

    @Nested
    @DisplayName("extractNamedParameters: 占位符提取")
    class ExtractNamedParameters {

        @Test
        void 基础_单个占位符() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(
                    "SELECT * FROM orders WHERE id = :oid"))
                    .containsExactly("oid");
        }

        @Test
        void 多个占位符_保持出现顺序() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(
                    "SELECT * FROM orders WHERE :a IS NOT NULL AND b = :b AND c = :c"))
                    .containsExactly("a", "b", "c");
        }

        @Test
        void 重复占位符_去重() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(
                    "SELECT * WHERE a = :foo OR b = :foo OR c = :foo"))
                    .containsExactly("foo");
        }

        @Test
        void PG_双冒号_cast_不识别() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(
                    "SELECT * WHERE :oid::varchar IS NULL OR id = :oid::int"))
                    .containsExactly("oid");
        }

        @Test
        void 字符串字面量_内的_伪占位_不识别() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(
                    "SELECT 'hello :fake world' AS msg, id FROM t WHERE id = :real"))
                    .containsExactly("real");
        }

        @Test
        void 行注释_内的_伪占位_不识别() {
            String sql = "SELECT id FROM t -- :commented_out\nWHERE id = :real";
            assertThat(SqlWhitelistValidator.extractNamedParameters(sql))
                    .containsExactly("real");
        }

        @Test
        void 块注释_内的_伪占位_不识别() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(
                    "SELECT id FROM t /* :commented :out */ WHERE id = :real"))
                    .containsExactly("real");
        }

        @Test
        void 空_或_null_返回空集() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(null)).isEmpty();
            assertThat(SqlWhitelistValidator.extractNamedParameters("")).isEmpty();
            assertThat(SqlWhitelistValidator.extractNamedParameters("   ")).isEmpty();
            assertThat(SqlWhitelistValidator.extractNamedParameters("SELECT 1")).isEmpty();
        }

        @Test
        void 占位符_含数字_下划线() {
            assertThat(SqlWhitelistValidator.extractNamedParameters(
                    "SELECT :user_id_1, :v2 FROM t"))
                    .containsExactly("user_id_1", "v2");
        }
    }

    @Nested
    @DisplayName("Source 来源策略 (多方言适配)")
    class SourceStrategy {

        /**
         * INSERT 语句: JSqlParser 能解析但不是 Select, TENANT_CUSTOM 的 AST 校验会拒,
         * TEMPLATE 跳过 AST 不会拒. 用来精准区分两种 source 的校验强度.
         */
        private static final String NON_SELECT_SQL = "INSERT INTO users (id, name) VALUES (1, 'foo')";

        @Test
        void TEMPLATE_来源_非_SELECT_放行() {
            // TEMPLATE 跳过 AST, INSERT 也能通过 (函数黑名单不触发)
            assertThatCode(() -> validator.validate(NON_SELECT_SQL,
                    SqlWhitelistValidator.Source.TEMPLATE))
                    .doesNotThrowAnyException();
        }

        @Test
        void TEMPLATE_来源_含_pg_sleep_仍拦截() {
            // 函数黑名单是核心防御, TEMPLATE 也必须走
            String sql = "SELECT id FROM users WHERE id = pg_sleep(5)";
            assertThatThrownBy(() -> validator.validate(sql, SqlWhitelistValidator.Source.TEMPLATE))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("禁止调用的函数");
        }

        @Test
        void TENANT_CUSTOM_来源_非_SELECT_拒绝() {
            // 租户 custom_sql 必须是 SELECT, INSERT 应被 AST 校验拒
            assertThatThrownBy(() -> validator.validate(NON_SELECT_SQL,
                    SqlWhitelistValidator.Source.TENANT_CUSTOM))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("必须是 SELECT");
        }

        @Test
        void TEMPLATE_来源_分号_仍拒绝() {
            // 分号是基础防御, 两类来源都拦
            assertThatThrownBy(() -> validator.validate("SELECT 1; DROP TABLE users",
                    SqlWhitelistValidator.Source.TEMPLATE))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("分号");
        }

        @Test
        void 单参重载_默认走_TENANT_CUSTOM_严校验() {
            // validate(sql) 应等价于 validate(sql, TENANT_CUSTOM), INSERT 应被拒
            assertThatThrownBy(() -> validator.validate(NON_SELECT_SQL))
                    .isInstanceOf(SqlValidationException.class)
                    .hasMessageContaining("必须是 SELECT");
        }
    }
}
