package com.sea.star.ai.ec.enterprise.connector.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 安全工具类。
 *
 * 目前只包含常量时间字符串比较 —— 凡是校验 token / api key / webhook secret
 * 等密钥类字段的地方，都必须用这里的方法而不是 String.equals。
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * 常量时间字符串比较，防时序攻击。
     *
     * 原理：JDK 的 {@link MessageDigest#isEqual(byte[], byte[])} 对两个字节数组
     * 按最长长度遍历做异或累加，长度不等或内容不同都不会早返回，时间开销基本恒定。
     * String.equals 会在首个不同字符处立即返回，可被时序分析利用。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
