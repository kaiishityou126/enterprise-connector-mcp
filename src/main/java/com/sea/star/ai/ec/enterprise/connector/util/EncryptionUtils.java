package com.sea.star.ai.ec.enterprise.connector.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM 加解密。
 *
 * 密钥从 ${connector.security.encryption-key} 读取（Base64 编码，32 字节）。
 * 启动时若密钥未配置或格式错误，直接抛异常让应用 fail-fast，避免运行期才发现。
 *
 * 密文格式: Base64(IV[12] || ciphertext || GCM_TAG[16])
 */
@Slf4j
@Component
public class EncryptionUtils {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public EncryptionUtils(@Value("${connector.security.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            //加密密钥未配置: connector.security.encryption-key(环境变量 ENCRYPTION_KEY)
            throw new IllegalStateException(
                    "加密密钥未配置, 应用无法启动");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            //加密密钥不是合法 Base64: connector.security.encryption-key。生成方式: openssl rand -base64 32
            throw new IllegalStateException(
                    "加密密钥不合法!", e);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            //加密密钥长度必须为 32 字节 (Base64 解码后)
            throw new IllegalStateException(
                    "加密密钥不合法!");
        }
        this.keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 加密。入参 null → 返回 null。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密。入参 null → 返回 null。
     *
     * GCM 认证标签不匹配会抛 SecurityException —— 表明密文被篡改或使用了错误的密钥，
     * 调用方应单独告警而非当作一般异常处理。
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < IV_LENGTH) {
                throw new IllegalArgumentException("密文长度不足, 非合法 AES-GCM 密文");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherBytes = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherBytes, 0, cipherBytes.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            log.error("GCM 认证标签不匹配, 密文可能被篡改或密钥错误");
            throw new SecurityException("密文完整性校验失败", e);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }
}
