package com.sea.star.ai.ec.enterprise.connector.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EncryptionUtilsTest {

    private static String validKey;

    @BeforeAll
    static void buildKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        validKey = Base64.getEncoder().encodeToString(raw);
    }

    @Nested
    @DisplayName("构造器 fail-fast 校验")
    class Constructor {

        @Test
        void 合法32字节密钥_成功() {
            assertThatCode(() -> new EncryptionUtils(validKey)).doesNotThrowAnyException();
        }

        @Test
        void 空密钥_抛IllegalStateException() {
            assertThatThrownBy(() -> new EncryptionUtils(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未配置");
        }

        @Test
        void null_抛IllegalStateException() {
            assertThatThrownBy(() -> new EncryptionUtils(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未配置");
        }

        @Test
        void 非Base64_抛IllegalStateException() {
            assertThatThrownBy(() -> new EncryptionUtils("not_base64_!!!@@"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不合法");
        }

        @Test
        void 密钥长度不足32字节_抛IllegalStateException() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> new EncryptionUtils(shortKey))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不合法");
        }

        @Test
        void 密钥长度超过32字节_抛IllegalStateException() {
            String longKey = Base64.getEncoder().encodeToString(new byte[64]);
            assertThatThrownBy(() -> new EncryptionUtils(longKey))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("加解密 round-trip")
    class RoundTrip {

        private final EncryptionUtils crypto = new EncryptionUtils(validKey);

        @Test
        void 普通字符串_可正确还原() {
            String plain = "my-db-password-123";
            String cipher = crypto.encrypt(plain);
            assertThat(cipher).isNotNull().isNotEqualTo(plain);
            assertThat(crypto.decrypt(cipher)).isEqualTo(plain);
        }

        @Test
        void 中文字符串_UTF8可正确还原() {
            String plain = "测试-密码-中文汉字";
            assertThat(crypto.decrypt(crypto.encrypt(plain))).isEqualTo(plain);
        }

        @Test
        void 空字符串_可加解密() {
            assertThat(crypto.decrypt(crypto.encrypt(""))).isEqualTo("");
        }

        @Test
        void null入参_返回null() {
            assertThat(crypto.encrypt(null)).isNull();
            assertThat(crypto.decrypt(null)).isNull();
        }

        @Test
        void 相同明文两次加密_得到不同密文_IV随机() {
            String plain = "same-text";
            String c1 = crypto.encrypt(plain);
            String c2 = crypto.encrypt(plain);
            assertThat(c1).isNotEqualTo(c2);
            assertThat(crypto.decrypt(c1)).isEqualTo(plain);
            assertThat(crypto.decrypt(c2)).isEqualTo(plain);
        }

        @Test
        void 长文本_可加解密() {
            String plain = "x".repeat(10_000);
            assertThat(crypto.decrypt(crypto.encrypt(plain))).isEqualTo(plain);
        }
    }

    @Nested
    @DisplayName("解密异常分支")
    class DecryptFailures {

        private final EncryptionUtils crypto = new EncryptionUtils(validKey);

        @Test
        void 密文篡改_抛SecurityException() {
            String cipher = crypto.encrypt("plain");
            // 改动尾部 1 个字符 → GCM tag 验证失败
            char[] ch = cipher.toCharArray();
            ch[ch.length - 2] = ch[ch.length - 2] == 'A' ? 'B' : 'A';
            String tampered = new String(ch);

            assertThatThrownBy(() -> crypto.decrypt(tampered))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("完整性校验失败");
        }

        @Test
        void 不同密钥解密_抛SecurityException() {
            String cipher = crypto.encrypt("plain");
            String otherKey = Base64.getEncoder().encodeToString(new byte[32]);
            EncryptionUtils other = new EncryptionUtils(otherKey);
            assertThatThrownBy(() -> other.decrypt(cipher))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        void 密文长度不足_抛IllegalStateException() {
            // IV 本身需要 12 字节 = 16 Base64 字符, 这里给 4 字节
            String tooShort = Base64.getEncoder().encodeToString(new byte[4]);
            assertThatThrownBy(() -> crypto.decrypt(tooShort))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("解密失败");
        }

        @Test
        void 非Base64密文_抛IllegalStateException() {
            assertThatThrownBy(() -> crypto.decrypt("!!not-base64!!"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
