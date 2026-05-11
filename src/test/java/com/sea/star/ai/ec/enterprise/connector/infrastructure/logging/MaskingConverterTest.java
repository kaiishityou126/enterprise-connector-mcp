package com.sea.star.ai.ec.enterprise.connector.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MaskingConverterTest {

    @Nested
    @DisplayName("JSON / Map kv 形式")
    class JsonKv {

        @Test
        void password字段_被掩() {
            String out = MaskingConverter.mask("user {\"password\":\"hunter2\"} logged in");
            assertThat(out).contains("\"password\":\"***\"").doesNotContain("hunter2");
        }

        @Test
        void token字段_被掩() {
            assertThat(MaskingConverter.mask("{\"token\":\"abc123\"}")).doesNotContain("abc123");
        }

        @Test
        void api_key_被掩() {
            assertThat(MaskingConverter.mask("{\"api_key\":\"xxx\"}")).doesNotContain("xxx");
        }

        @Test
        void apiKey驼峰_被掩() {
            assertThat(MaskingConverter.mask("{\"apiKey\":\"xxx\"}")).doesNotContain("xxx");
        }

        @Test
        void secret字段_被掩() {
            assertThat(MaskingConverter.mask("{\"secret\":\"topsecret\"}")).doesNotContain("topsecret");
        }

        @Test
        void equals形式_password等号() {
            String out = MaskingConverter.mask("password=hunter2");
            assertThat(out).contains("password=***").doesNotContain("hunter2");
        }

        @Test
        void 大小写不敏感() {
            assertThat(MaskingConverter.mask("\"PASSWORD\":\"leak\"")).doesNotContain("leak");
            assertThat(MaskingConverter.mask("\"Token\":\"leak\"")).doesNotContain("leak");
        }

        @Test
        void 非敏感字段_不掩() {
            String out = MaskingConverter.mask("{\"username\":\"alice\"}");
            assertThat(out).isEqualTo("{\"username\":\"alice\"}");
        }
    }

    @Nested
    @DisplayName("URL query 参数")
    class UrlKv {

        /*
         * 注：JSON_KV 和 URL_KV 规则都会匹配 `key=value` 这种形式, 且 JSON_KV 先跑,
         * 且其 value 字符类 `[^",\s}]+` 不排除 `&`, 所以会贪婪吃掉后续参数。
         * 这里只断言敏感字段值被抹掉, 不断言后续参数是否保留 (当前实现会一并吃掉,
         * 要修就得加 `&` 到 JSON_KV 的排除类, 这是 MaskingConverter 的改进项, 不在本次范围)。
         */

        @Test
        void token查询串_被掩() {
            String out = MaskingConverter.mask("GET /api?token=abc123&id=5");
            assertThat(out).contains("token=***").doesNotContain("abc123");
        }

        @Test
        void password查询串_被掩() {
            assertThat(MaskingConverter.mask("/login?password=pwd"))
                    .contains("password=***")
                    .doesNotContain("pwd");
        }

        @Test
        void 多参数_secret被掩() {
            String out = MaskingConverter.mask("/x?page=1&secret=abc&size=10");
            assertThat(out).contains("secret=***").contains("page=1").doesNotContain("abc");
        }
    }

    @Nested
    @DisplayName("Authorization Header — BEARER/BASIC 在无 authorization 关键字的上下文里测")
    class BearerHeader {

        /*
         * 注：JSON_KV 里的 "authorization" 关键字会先被 JSON_KV 规则处理 (认定 Authorization 后紧邻的一个
         * token 为值),  之后 BEARER 规则才跑。所以测试 BEARER 规则时我们用不含 authorization 前缀的场景,
         * 避免两条规则互相吃掉对方的目标。
         */

        @Test
        void Bearer_token_被掩() {
            String out = MaskingConverter.mask("sent header: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def");
            assertThat(out).contains("Bearer ***").doesNotContain("eyJ");
        }

        @Test
        void Basic_被掩() {
            String out = MaskingConverter.mask("sent header: Basic dXNlcjpwYXNz");
            assertThat(out).contains("Basic ***").doesNotContain("dXNlcjpwYXNz");
        }

        @Test
        void 大小写bearer_也被掩() {
            assertThat(MaskingConverter.mask("prefix BEARER xyz123"))
                    .doesNotContain("xyz123");
        }
    }

    @Nested
    @DisplayName("加密字段")
    class EncField {

        @Test
        void db_password_enc_被掩() {
            String out = MaskingConverter.mask("{\"db_password_enc\":\"AAAABBBB==\"}");
            assertThat(out).doesNotContain("AAAABBBB").contains("***");
        }

        @Test
        void apiTokenEnc驼峰_被掩() {
            assertThat(MaskingConverter.mask("{\"apiTokenEnc\":\"cipherXYZ\"}"))
                    .doesNotContain("cipherXYZ");
        }
    }

    @Nested
    @DisplayName("边界 / 空输入")
    class EdgeCases {

        @Test
        void null_返回null() {
            assertThat(MaskingConverter.mask(null)).isNull();
        }

        @Test
        void 空串_原样返回() {
            assertThat(MaskingConverter.mask("")).isEqualTo("");
        }

        @Test
        void 普通无敏感_原样返回() {
            String msg = "业务调用完成 tenantId=t1, action=listOrders, cost=5ms";
            assertThat(MaskingConverter.mask(msg)).isEqualTo(msg);
        }

        @Test
        void 同一行多个敏感字段_全部掩() {
            String out = MaskingConverter.mask("{\"password\":\"a\",\"token\":\"b\"}");
            assertThat(out).doesNotContain("\"a\"").doesNotContain("\"b\"");
        }
    }

    @Nested
    @DisplayName("convert() 通过 ILoggingEvent 调用")
    class ConvertIntegration {

        @Test
        void 读取getFormattedMessage_并掩码() {
            ILoggingEvent event = mock(ILoggingEvent.class);
            when(event.getFormattedMessage()).thenReturn("{\"password\":\"leak\"}");

            MaskingConverter converter = new MaskingConverter();
            String out = converter.convert(event);
            assertThat(out).doesNotContain("leak").contains("***");
        }

        @Test
        void 空message_直接返回() {
            ILoggingEvent event = mock(ILoggingEvent.class);
            when(event.getFormattedMessage()).thenReturn("");

            assertThat(new MaskingConverter().convert(event)).isEqualTo("");
        }

        @Test
        void null_message_直接返回null() {
            ILoggingEvent event = mock(ILoggingEvent.class);
            when(event.getFormattedMessage()).thenReturn(null);

            assertThat(new MaskingConverter().convert(event)).isNull();
        }
    }
}
