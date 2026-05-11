package com.sea.star.ai.ec.enterprise.connector.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback 自定义转换器，在日志输出前对敏感字段做掩码。
 *
 * 触发模式（case-insensitive）：
 *   - JSON/Map 形式:   "password":"xxx" / password=xxx / "token":"xxx"
 *   - URL 参数形式:    ?token=xxx&secret=yyy
 *   - HTTP Header:     Authorization: Bearer xxx
 *   - 通用关键字:       password / passwd / pwd / token / secret / authorization / api[-_]?key
 *
 * 引用方式（logback-spring.xml）：
 *   <conversionRule conversionWord="mask"
 *       converterClass="com.sea.star...MaskingConverter"/>
 *   <pattern>%msg</pattern>  →  <pattern>%mask</pattern>
 *
 * 性能：单条日志做 4 次 regex replace，热路径可接受；不做 regex 预编译缓存的优化空间（每条日志构造一次）。
 */
public class MaskingConverter extends ClassicConverter {

    /** JSON / Map 形式: "key":"value" 或 "key":value 或 key=value */
    private static final Pattern JSON_KV = Pattern.compile(
            "(?i)(\"?(?:password|passwd|pwd|token|secret|authorization|api[-_]?key)\"?\\s*[:=]\\s*\"?)([^\",\\s}]+)(\"?)",
            Pattern.CASE_INSENSITIVE);

    /** URL query 或 form 形式: key=value&... */
    private static final Pattern URL_KV = Pattern.compile(
            "(?i)([?&](?:password|passwd|pwd|token|secret|api[-_]?key)=)([^&\\s]+)",
            Pattern.CASE_INSENSITIVE);

    /** HTTP Authorization header: Bearer xxx / Basic xxx */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(Bearer|Basic)\\s+([A-Za-z0-9._~+/=\\-]+)",
            Pattern.CASE_INSENSITIVE);

    /** Base64 长字符串（AES 密文等），仅当以 "enc" 后缀字段命名时掩 */
    private static final Pattern ENC_FIELD = Pattern.compile(
            "(?i)(\"?(?:db_password_enc|api_token_enc|dbPasswordEnc|apiTokenEnc)\"?\\s*[:=]\\s*\"?)([^\",\\s}]+)(\"?)",
            Pattern.CASE_INSENSITIVE);

    private static final String MASK = "***";

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) return message;
        return mask(message);
    }

    /** 包级可见以便单测直接调用 */
    static String mask(String input) {
        if (input == null) return null;
        String result = input;

        // 1. JSON / Map key:value
        Matcher m1 = JSON_KV.matcher(result);
        if (m1.find()) {
            result = m1.replaceAll("$1" + MASK + "$3");
        }
        // 2. URL / Form key=value
        Matcher m2 = URL_KV.matcher(result);
        if (m2.find()) {
            result = m2.replaceAll("$1" + MASK);
        }
        // 3. Authorization header Bearer/Basic
        Matcher m3 = BEARER.matcher(result);
        if (m3.find()) {
            result = m3.replaceAll("$1 " + MASK);
        }
        // 4. 加密字段（db_password_enc 等）
        Matcher m4 = ENC_FIELD.matcher(result);
        if (m4.find()) {
            result = m4.replaceAll("$1" + MASK + "$3");
        }
        return result;
    }
}
