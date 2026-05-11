package com.sea.star.ai.ec.enterprise.connector.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sea.star.ai.ec.enterprise.connector.exception.ParamValidationException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ParamValidatorTest {

    @Nested
    @DisplayName("schema 为空的快路径")
    class EmptySchema {

        @Test
        void null_schema_直接通过() {
            assertThatCode(() -> ParamValidator.validate(null, Map.of("x", 1)))
                    .doesNotThrowAnyException();
        }

        @Test
        void 空串_schema_直接通过() {
            assertThatCode(() -> ParamValidator.validate("", Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        void 空json对象_schema_直接通过() {
            assertThatCode(() -> ParamValidator.validate("{}", Map.of("x", 1)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("required 校验")
    class Required {

        private static final String SCHEMA =
                "{\"orderId\":{\"type\":\"string\",\"required\":true}}";

        @Test
        void 必填字段缺失_抛ParamValidationException() {
            assertThatThrownBy(() -> ParamValidator.validate(SCHEMA, Map.of()))
                    .isInstanceOf(ParamValidationException.class)
                    .hasMessageContaining("orderId");
        }

        @Test
        void params为null_且有必填_抛异常() {
            assertThatThrownBy(() -> ParamValidator.validate(SCHEMA, null))
                    .isInstanceOf(ParamValidationException.class);
        }

        @Test
        void 必填字段有值_通过() {
            assertThatCode(() -> ParamValidator.validate(SCHEMA, Map.of("orderId", "abc")))
                    .doesNotThrowAnyException();
        }

        @Test
        void 非必填_缺失_放行() {
            String schema = "{\"note\":{\"type\":\"string\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        void 非必填_值为null_放行() {
            String schema = "{\"note\":{\"type\":\"string\"}}";
            Map<String, Object> params = new HashMap<>();
            params.put("note", null);
            assertThatCode(() -> ParamValidator.validate(schema, params))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("type 校验")
    class TypeCheck {

        @Test
        void string_类型_类型错抛异常() {
            String schema = "{\"x\":{\"type\":\"string\"}}";
            assertThatThrownBy(() -> ParamValidator.validate(schema, Map.of("x", 123)))
                    .isInstanceOf(ParamValidationException.class)
                    .hasMessageContaining("string");
        }

        @Test
        void integer_类型_接受Integer() {
            String schema = "{\"x\":{\"type\":\"integer\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", 42)))
                    .doesNotThrowAnyException();
        }

        @Test
        void integer_类型_接受Long() {
            String schema = "{\"x\":{\"type\":\"integer\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", 42L)))
                    .doesNotThrowAnyException();
        }

        @Test
        void integer_类型_拒绝Double() {
            String schema = "{\"x\":{\"type\":\"integer\"}}";
            assertThatThrownBy(() -> ParamValidator.validate(schema, Map.of("x", 1.5)))
                    .isInstanceOf(ParamValidationException.class);
        }

        @Test
        void number_类型_接受所有数字() {
            String schema = "{\"x\":{\"type\":\"number\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", 1.5))).doesNotThrowAnyException();
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", 2))).doesNotThrowAnyException();
        }

        @Test
        void boolean_类型() {
            String schema = "{\"x\":{\"type\":\"boolean\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", true))).doesNotThrowAnyException();
            assertThatThrownBy(() -> ParamValidator.validate(schema, Map.of("x", "true")))
                    .isInstanceOf(ParamValidationException.class);
        }

        @Test
        void 未知类型_不抛错() {
            String schema = "{\"x\":{\"type\":\"uuid\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", "anything")))
                    .doesNotThrowAnyException();
        }

        @Test
        void 大小写不敏感_STRING也认可() {
            String schema = "{\"x\":{\"type\":\"STRING\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", "abc")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("maxLength 校验")
    class MaxLengthCheck {

        @Test
        void 超长_抛异常() {
            String schema = "{\"x\":{\"type\":\"string\",\"maxLength\":3}}";
            assertThatThrownBy(() -> ParamValidator.validate(schema, Map.of("x", "abcd")))
                    .isInstanceOf(ParamValidationException.class)
                    .hasMessageContaining("长度超限");
        }

        @Test
        void 恰好_边界通过() {
            String schema = "{\"x\":{\"type\":\"string\",\"maxLength\":3}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", "abc")))
                    .doesNotThrowAnyException();
        }

        @Test
        void 非string也走toString长度() {
            String schema = "{\"x\":{\"maxLength\":2}}";
            // 123 → "123" length=3 > 2
            assertThatThrownBy(() -> ParamValidator.validate(schema, Map.of("x", 123)))
                    .isInstanceOf(ParamValidationException.class);
        }
    }

    @Nested
    @DisplayName("pattern 校验")
    class PatternCheck {

        @Test
        void 匹配_通过() {
            String schema = "{\"x\":{\"type\":\"string\",\"pattern\":\"^[A-Z0-9-]+$\"}}";
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("x", "AB-123")))
                    .doesNotThrowAnyException();
        }

        @Test
        void 不匹配_抛异常() {
            String schema = "{\"x\":{\"type\":\"string\",\"pattern\":\"^[A-Z]+$\"}}";
            assertThatThrownBy(() -> ParamValidator.validate(schema, Map.of("x", "abc")))
                    .isInstanceOf(ParamValidationException.class)
                    .hasMessageContaining("格式不匹配");
        }
    }

    @Nested
    @DisplayName("组合规则")
    class Combined {

        @Test
        void 所有规则同时满足_通过() {
            String schema = """
                    {"orderId":{"type":"string","required":true,"maxLength":10,"pattern":"^[A-Z0-9]+$"}}
                    """;
            assertThatCode(() -> ParamValidator.validate(schema, Map.of("orderId", "ORD12345")))
                    .doesNotThrowAnyException();
        }

        @Test
        void 多字段_任一不合_抛异常() {
            String schema = """
                    {
                      "a":{"type":"string","required":true},
                      "b":{"type":"integer","required":true}
                    }
                    """;
            // b 类型错
            assertThatThrownBy(() -> ParamValidator.validate(schema,
                    Map.of("a", "ok", "b", "not-a-number")))
                    .isInstanceOf(ParamValidationException.class)
                    .hasMessageContaining("b");
        }
    }
}
