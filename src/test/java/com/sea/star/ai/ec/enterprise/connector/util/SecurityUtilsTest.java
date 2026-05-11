package com.sea.star.ai.ec.enterprise.connector.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityUtilsTest {

    @Test
    @DisplayName("相同字符串返回 true")
    void 相同字符串() {
        assertThat(SecurityUtils.constantTimeEquals("abc", "abc")).isTrue();
    }

    @Test
    @DisplayName("不同字符串返回 false")
    void 不同字符串() {
        assertThat(SecurityUtils.constantTimeEquals("abc", "abd")).isFalse();
    }

    @Test
    @DisplayName("长度不同返回 false")
    void 长度不同() {
        assertThat(SecurityUtils.constantTimeEquals("abc", "abcd")).isFalse();
    }

    @Test
    @DisplayName("任一为 null 返回 false")
    void null_输入() {
        assertThat(SecurityUtils.constantTimeEquals(null, "x")).isFalse();
        assertThat(SecurityUtils.constantTimeEquals("x", null)).isFalse();
        assertThat(SecurityUtils.constantTimeEquals(null, null)).isFalse();
    }

    @Test
    @DisplayName("空串等于空串")
    void 空串() {
        assertThat(SecurityUtils.constantTimeEquals("", "")).isTrue();
    }

    @Test
    @DisplayName("空串不等于非空")
    void 空串和非空() {
        assertThat(SecurityUtils.constantTimeEquals("", "x")).isFalse();
    }

    @Test
    @DisplayName("UTF-8 中文字符串比较正确")
    void 中文字符串() {
        assertThat(SecurityUtils.constantTimeEquals("密钥-secret", "密钥-secret")).isTrue();
        assertThat(SecurityUtils.constantTimeEquals("密钥-secret", "密钥-SECRET")).isFalse();
    }

    @Test
    @DisplayName("大小写敏感")
    void 大小写敏感() {
        assertThat(SecurityUtils.constantTimeEquals("ABC", "abc")).isFalse();
    }

    @Test
    @DisplayName("仅首字符不同也应返回 false (不早返回才是安全要求, 行为上和 equals 一致)")
    void 首字符不同() {
        assertThat(SecurityUtils.constantTimeEquals("Xbcdef", "Abcdef")).isFalse();
    }

    @Test
    @DisplayName("仅末字符不同也应返回 false")
    void 末字符不同() {
        assertThat(SecurityUtils.constantTimeEquals("abcdeX", "abcdeY")).isFalse();
    }
}
