package com.sea.star.ai.ec.enterprise.connector.service.security;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import com.sea.star.ai.ec.enterprise.connector.exception.BaseException;
import com.sea.star.ai.ec.enterprise.connector.exception.BusinessException;
import com.sea.star.ai.ec.enterprise.connector.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 认证服务：两类入口各自校验策略。
 *
 * - MCP 调用 (/mcp/**) : Authorization: Bearer {token}，token 与 ${connector.security.mcp-token} 比对
 * - Admin API (/admin/**): X-API-Key 头，与 ${connector.security.admin-api-key} 比对
 *
 * 当前用简单的预共享密钥方案；未来可替换为 JWT 或 OAuth。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    @Value("${connector.security.mcp-token:}")
    private String mcpToken;

    @Value("${connector.security.admin-api-key:}")
    private String adminApiKey;

    /** 物理删除 (purge) 端点的二级认证 key; 未配置时所有 /purge 端点被拒 */
    @Value("${connector.security.admin-purge-api-key:}")
    private String adminPurgeApiKey;

    /**
     * 校验 MCP 调用的 Authorization 头。
     */
    public void verifyMcp(HttpServletRequest request) {
        if (mcpToken == null || mcpToken.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "服务端未配置 MCP 认证 token");
        }
        String header = request.getHeader(BusinessConstants.AUTH_HEADER_MCP);
        if (header == null || header.isBlank()) {
            throw business(ErrorCode.AUTH_TOKEN_MISSING);
        }
        String token;
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = header.substring(7).trim();
        } else {
            throw business(ErrorCode.AUTH_TOKEN_INVALID, "Authorization 头需使用 Bearer 方案");
        }
        if (!constantTimeEquals(token, mcpToken)) {
            throw business(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }

    /**
     * 校验 Admin API 的 X-API-Key 头。
     */
    public void verifyAdmin(HttpServletRequest request) {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "服务端未配置 Admin API Key");
        }
        String key = request.getHeader(BusinessConstants.AUTH_HEADER_ADMIN);
        if (key == null || key.isBlank()) {
            throw business(ErrorCode.AUTH_TOKEN_MISSING);
        }
        if (!constantTimeEquals(key, adminApiKey)) {
            throw business(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }

    /**
     * 物理删除 (purge) 端点的二级认证: 校验 X-Purge-Api-Key (与 admin-api-key 独立).
     * 未配置 purge-api-key 时拒绝所有 /purge 请求 (保守默认).
     */
    public void verifyPurge(HttpServletRequest request) {
        if (adminPurgeApiKey == null || adminPurgeApiKey.isBlank()) {
            throw business(ErrorCode.AUTH_FORBIDDEN,
                    "服务端未配置 admin-purge-api-key, 物理删除功能被禁用");
        }
        String key = request.getHeader(BusinessConstants.AUTH_HEADER_PURGE);
        if (key == null || key.isBlank()) {
            throw business(ErrorCode.AUTH_TOKEN_MISSING,
                    "物理删除需要 " + BusinessConstants.AUTH_HEADER_PURGE + " 头");
        }
        if (!constantTimeEquals(key, adminPurgeApiKey)) {
            throw business(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return SecurityUtils.constantTimeEquals(a, b);
    }

    private BaseException business(ErrorCode code) {
        return new BusinessException(code);
    }

    private BaseException business(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }
}
