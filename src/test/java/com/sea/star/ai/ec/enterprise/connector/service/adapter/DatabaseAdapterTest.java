package com.sea.star.ai.ec.enterprise.connector.service.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

class DatabaseAdapterTest {

    @Nested
    @DisplayName("computeEffectiveMaxRows: 事后行数告警的上限计算")
    class ComputeEffectiveMaxRows {

        @Test
        @DisplayName("templateMaxRows=null 时使用 defaultMax")
        void nullTemplateMaxRowsUsesDefault() {
            assertThat(DatabaseAdapter.computeEffectiveMaxRows(null, 500, 10000)).isEqualTo(500);
        }

        @Test
        @DisplayName("templateMaxRows<0 返回 -1, 表示聚合查询跳过校验")
        void negativeReturnsMinusOne() {
            assertThat(DatabaseAdapter.computeEffectiveMaxRows(-1, 500, 10000)).isEqualTo(-1);
            assertThat(DatabaseAdapter.computeEffectiveMaxRows(-100, 500, 10000)).isEqualTo(-1);
        }

        @Test
        @DisplayName("templateMaxRows 正常值返回模板值")
        void normalValueReturnsTemplate() {
            assertThat(DatabaseAdapter.computeEffectiveMaxRows(100, 500, 10000)).isEqualTo(100);
            assertThat(DatabaseAdapter.computeEffectiveMaxRows(5000, 500, 10000)).isEqualTo(5000);
        }

        @Test
        @DisplayName("templateMaxRows 超过 absoluteMax 时被封顶")
        void exceedsAbsoluteCapped() {
            assertThat(DatabaseAdapter.computeEffectiveMaxRows(99999, 500, 10000)).isEqualTo(10000);
        }

        @Test
        @DisplayName("templateMaxRows=0 视为正常值, 不视为聚合标记")
        void zeroIsNormal() {
            assertThat(DatabaseAdapter.computeEffectiveMaxRows(0, 500, 10000)).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("padMissingParamsFromSql: custom_sql 路径从 SQL 解析占位符补 null")
    class PadMissingParamsFromSql {

        @Test
        @DisplayName("缺失的占位符被补成 null, 已传的不被覆盖")
        void padsMissingPreservesExisting() {
            MapSqlParameterSource source = new MapSqlParameterSource()
                    .addValue("oid", "ORD-1");
            String sql = "SELECT * FROM orders WHERE order_id = :oid AND status = :status";
            DatabaseAdapter.padMissingParamsFromSql(source, sql);

            assertThat(source.hasValue("oid")).isTrue();
            assertThat(source.getValue("oid")).isEqualTo("ORD-1");  // 不被覆盖
            assertThat(source.hasValue("status")).isTrue();
            assertThat(source.getValue("status")).isNull();         // 缺失补 null
        }

        @Test
        @DisplayName("可选过滤模式: 调用方什么都不传时, 所有占位符都补 null")
        void allMissingFilledWithNull() {
            MapSqlParameterSource source = new MapSqlParameterSource();
            String sql = "SELECT * FROM orders WHERE 1=1 "
                    + "AND (:status IS NULL OR status = :status) "
                    + "AND (:userId IS NULL OR user_id = :userId)";
            DatabaseAdapter.padMissingParamsFromSql(source, sql);

            assertThat(source.hasValue("status")).isTrue();
            assertThat(source.getValue("status")).isNull();
            assertThat(source.hasValue("userId")).isTrue();
            assertThat(source.getValue("userId")).isNull();
        }

        @Test
        @DisplayName("字符串字面量内的 :fake 不被解析为参数")
        void literalColonNotTreatedAsParam() {
            MapSqlParameterSource source = new MapSqlParameterSource();
            // 字符串里的 :fake 是数据, 不是参数
            String sql = "SELECT 'message::has :fake colon' AS msg, id FROM users WHERE id = :realId";
            DatabaseAdapter.padMissingParamsFromSql(source, sql);

            assertThat(source.hasValue("realId")).isTrue();
            assertThat(source.hasValue("fake")).isFalse();
        }

        @Test
        @DisplayName("PG 双冒号 cast 不被解析为参数")
        void pgDoubleColonCastNotParam() {
            MapSqlParameterSource source = new MapSqlParameterSource();
            // ::varchar 是 PG 类型转换, 不是参数
            String sql = "SELECT * FROM orders WHERE :oid::varchar IS NULL OR id = :oid";
            DatabaseAdapter.padMissingParamsFromSql(source, sql);

            assertThat(source.hasValue("oid")).isTrue();
            assertThat(source.getValue("oid")).isNull();
            // 不应解析出名为 "varchar" 的参数
            assertThat(source.hasValue("varchar")).isFalse();
        }

        @Test
        @DisplayName("空 SQL / null 不抛异常")
        void emptyOrNullSafe() {
            MapSqlParameterSource source = new MapSqlParameterSource();
            DatabaseAdapter.padMissingParamsFromSql(source, null);
            DatabaseAdapter.padMissingParamsFromSql(source, "");
            DatabaseAdapter.padMissingParamsFromSql(source, "   ");
            // 不抛异常即可
        }

        @Test
        @DisplayName("同一占位符多次出现只补一次, 不会重复添加")
        void duplicateOccurrencesOk() {
            MapSqlParameterSource source = new MapSqlParameterSource();
            String sql = "SELECT * FROM t WHERE a = :foo OR b = :foo OR c = :foo";
            DatabaseAdapter.padMissingParamsFromSql(source, sql);

            assertThat(source.hasValue("foo")).isTrue();
            assertThat(source.getValue("foo")).isNull();
        }
    }
}
