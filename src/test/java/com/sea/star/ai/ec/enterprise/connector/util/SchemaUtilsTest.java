package com.sea.star.ai.ec.enterprise.connector.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SchemaUtilsTest {

    @Nested
    @DisplayName("mergeParamSchemas: 合并语义")
    class Merge {

        @Test
        @DisplayName("两侧都空 返回 null")
        void bothBlankReturnsNull() {
            assertThat(SchemaUtils.mergeParamSchemas(null, null)).isNull();
            assertThat(SchemaUtils.mergeParamSchemas("", "")).isNull();
            assertThat(SchemaUtils.mergeParamSchemas("  ", null)).isNull();
        }

        @Test
        @DisplayName("customParams 为空 返回 templateSchema 原样 (恒等)")
        void customEmptyReturnsTemplate() {
            String tpl = "{\"oid\":{\"type\":\"string\"}}";
            assertThat(SchemaUtils.mergeParamSchemas(tpl, null)).isEqualTo(tpl);
            assertThat(SchemaUtils.mergeParamSchemas(tpl, "")).isEqualTo(tpl);
            assertThat(SchemaUtils.mergeParamSchemas(tpl, "  ")).isEqualTo(tpl);
        }

        @Test
        @DisplayName("templateSchema 为空 返回 customParams 原样")
        void templateEmptyReturnsCustom() {
            String custom = "{\"region\":{\"type\":\"string\"}}";
            assertThat(SchemaUtils.mergeParamSchemas(null, custom)).isEqualTo(custom);
            assertThat(SchemaUtils.mergeParamSchemas("", custom)).isEqualTo(custom);
        }

        @Test
        @DisplayName("两侧都有 字段并集")
        void unionFields() {
            String tpl = "{\"oid\":{\"type\":\"string\"}}";
            String custom = "{\"region\":{\"type\":\"string\"}}";
            String merged = SchemaUtils.mergeParamSchemas(tpl, custom);
            assertThat(merged).contains("oid").contains("region");
            assertThat(SchemaUtils.fieldNames(merged)).containsExactlyInAnyOrder("oid", "region");
        }

        @Test
        @DisplayName("同名字段 customParams 优先覆盖")
        void customOverridesTemplate() {
            String tpl = "{\"oid\":{\"type\":\"string\",\"required\":false}}";
            String custom = "{\"oid\":{\"type\":\"string\",\"required\":true}}";
            String merged = SchemaUtils.mergeParamSchemas(tpl, custom);
            assertThat(merged).contains("\"required\":true");
            assertThat(merged).doesNotContain("\"required\":false");
        }

        @Test
        @DisplayName("非法 JSON 退回 templateSchema 不抛异常")
        void invalidJsonFallback() {
            String tpl = "{\"oid\":{\"type\":\"string\"}}";
            assertThat(SchemaUtils.mergeParamSchemas(tpl, "not json"))
                    .isEqualTo(tpl);
        }
    }

    @Nested
    @DisplayName("fieldNames: 提取顶层字段名")
    class FieldNames {

        @Test
        void empty() {
            assertThat(SchemaUtils.fieldNames(null)).isEmpty();
            assertThat(SchemaUtils.fieldNames("")).isEmpty();
            assertThat(SchemaUtils.fieldNames("not json")).isEmpty();
            assertThat(SchemaUtils.fieldNames("[]")).isEmpty();  // 不是 object
        }

        @Test
        void multipleFields() {
            assertThat(SchemaUtils.fieldNames(
                    "{\"a\":{},\"b\":{\"type\":\"string\"},\"c\":1}"))
                    .containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("保持插入顺序 (LinkedHashSet)")
        void preservesInsertionOrder() {
            assertThat(SchemaUtils.fieldNames("{\"z\":1,\"a\":2,\"m\":3}"))
                    .containsExactly("z", "a", "m");
        }
    }

    @Nested
    @DisplayName("assertValidCustomParams: 写入前合法性")
    class AssertValid {

        @Test
        @DisplayName("空字符串/null 视为合法")
        void blankIsValid() {
            SchemaUtils.assertValidCustomParams(null);
            SchemaUtils.assertValidCustomParams("");
            SchemaUtils.assertValidCustomParams("  ");
        }

        @Test
        @DisplayName("合法 JSON object 通过")
        void validObject() {
            SchemaUtils.assertValidCustomParams("{}");
            SchemaUtils.assertValidCustomParams("{\"region\":{\"type\":\"string\"}}");
        }

        @Test
        @DisplayName("非 object (数组/字符串/数字) 抛异常")
        void notObjectRejected() {
            assertThatThrownBy(() -> SchemaUtils.assertValidCustomParams("[]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JSON object");
            assertThatThrownBy(() -> SchemaUtils.assertValidCustomParams("\"foo\""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SchemaUtils.assertValidCustomParams("123"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非法 JSON 抛异常")
        void invalidJsonRejected() {
            assertThatThrownBy(() -> SchemaUtils.assertValidCustomParams("not json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不是合法 JSON");
        }
    }
}
