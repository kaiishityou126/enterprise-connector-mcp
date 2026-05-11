package com.sea.star.ai.ec.enterprise.connector.service.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 所有用例用 IP 字面量, InetAddress.getAllByName 不会真正 DNS 查询,
 * 因此测试无需网络 (CI 友好)。
 */
class CallbackUrlValidatorTest {

    private final CallbackUrlValidator validator = new CallbackUrlValidator();

    @Nested
    @DisplayName("空输入放行")
    class EmptyInput {
        @Test
        void null_不校验() {
            assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
        }

        @Test
        void 空串_不校验() {
            assertThatCode(() -> validator.validate("")).doesNotThrowAnyException();
        }

        @Test
        void 空白字符_不校验() {
            assertThatCode(() -> validator.validate("   ")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("合法 URL")
    class ValidUrl {

        @Test
        void 公网IP_http_放行() {
            // 1.1.1.1 不是 loopback/link-local/site-local/multicast
            assertThatCode(() -> validator.validate("http://1.1.1.1/callback"))
                    .doesNotThrowAnyException();
        }

        @Test
        void 公网IP_https_放行() {
            assertThatCode(() -> validator.validate("https://8.8.8.8/webhook"))
                    .doesNotThrowAnyException();
        }

        @Test
        void 带端口_放行() {
            assertThatCode(() -> validator.validate("https://1.1.1.1:8443/webhook"))
                    .doesNotThrowAnyException();
        }

        @Test
        void 带查询串_放行() {
            assertThatCode(() -> validator.validate("https://1.1.1.1/cb?x=1&y=2"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("scheme 校验")
    class SchemeCheck {

        @Test
        void ftp_拒绝() {
            assertThatThrownBy(() -> validator.validate("ftp://1.1.1.1/"))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.PARAM_INVALID)
                    .hasMessageContaining("scheme");
        }

        @Test
        void file_拒绝() {
            assertThatThrownBy(() -> validator.validate("file:///etc/passwd"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        void 无scheme_拒绝() {
            assertThatThrownBy(() -> validator.validate("//1.1.1.1/x"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("URL 语法 / host / userinfo")
    class UriShape {

        @Test
        void 非法URI语法_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://[malformed"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("语法");
        }

        @Test
        void 空host_拒绝() {
            // "http:///x" → host 为 null
            assertThatThrownBy(() -> validator.validate("http:///path"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("host");
        }

        @Test
        void 含userinfo_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://user:pass@1.1.1.1/cb"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("userinfo");
        }
    }

    @Nested
    @DisplayName("私网 / 环回 / link-local / any-local / 多播")
    class PrivateAddress {

        @Test
        void loopback_127_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://127.0.0.1/cb"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("loopback");
        }

        @Test
        void 内网10段_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://10.0.0.1/cb"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("内网");
        }

        @Test
        void 内网192168段_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://192.168.1.1/cb"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("内网");
        }

        @Test
        void 内网172_16_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://172.16.0.1/cb"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("内网");
        }

        @Test
        void any_local_0000_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://0.0.0.0/cb"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        void 多播地址_拒绝() {
            // 224.0.0.0/4 是 IPv4 多播
            assertThatThrownBy(() -> validator.validate("http://224.0.0.1/cb"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("多播");
        }
    }

    @Nested
    @DisplayName("云元数据地址阻断")
    class Metadata {

        @Test
        void AWS_IMDS_169254拒绝() {
            // 169.254.169.254 既是 link-local 也是 metadata,
            // 代码里 METADATA_HOSTS 字符串命中优先于 DNS 解析分支
            assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("元数据");
        }

        @Test
        void GCP_metadata域名_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://metadata.google.internal/"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("元数据");
        }

        @Test
        void metadata短域名_拒绝() {
            assertThatThrownBy(() -> validator.validate("http://metadata/"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("元数据");
        }
    }
}
