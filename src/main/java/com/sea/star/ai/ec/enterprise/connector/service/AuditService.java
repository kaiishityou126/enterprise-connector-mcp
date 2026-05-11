package com.sea.star.ai.ec.enterprise.connector.service;

import com.sea.star.ai.ec.enterprise.connector.config.AsyncConfig;
import com.sea.star.ai.ec.enterprise.connector.domain.mapper.AuditLogMapper;
import com.sea.star.ai.ec.enterprise.connector.domain.model.AuditLog;
import com.sea.star.ai.ec.enterprise.connector.util.JsonUtils;
import com.sea.star.ai.ec.enterprise.connector.util.TraceIds;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计日志异步写入。
 *
 * 走 @Async 主要考虑业务请求链路上不要额外加数据库 I/O 延迟；
 * audit_log 表是 BIGSERIAL 追加写入，不需要强一致时序。
 *
 * result_summary 只存简短摘要（成功/失败 + code + 可选 message），
 * 避免把完整业务数据（可能是敏感订单）写进审计表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int SUMMARY_MAX_LEN = 480;

    private final AuditLogMapper auditLogMapper;

    /**
     * 异步写入审计记录。调用方无需等待。
     *
     * @param tenantId       租户 ID
     * @param action         操作名
     * @param callerIdentity 调用方标识（token 里的 clientId 等）
     * @param params         入参（会序列化为 JSON；敏感字段由调用方负责剔除）
     * @param success        是否成功
     * @param code           业务/错误码
     * @param message        可选简短消息
     * @param durationMs     耗时
     */
    @Async(AsyncConfig.ASYNC_EXECUTOR)
    public void record(String tenantId, String action, String callerIdentity,
                       Map<String, Object> params, boolean success,
                       String code, String message, int durationMs) {
        try {
            String summary = buildSummary(success, code, message);
            AuditLog entry = AuditLog.builder()
                    .tenantId(tenantId)
                    .action(action)
                    .callerIdentity(callerIdentity)
                    .params(params == null ? null : JsonUtils.toJson(params))
                    .resultSummary(summary)
                    .durationMs(durationMs)
                    .traceId(TraceIds.current())
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            // 审计写失败不应影响主流程，只记日志
            log.error("写入审计日志失败 tenantId={}, action={}", tenantId, action, e);
        }
    }

    private String buildSummary(boolean success, String code, String message) {
        StringBuilder sb = new StringBuilder()
                .append(success ? "SUCCESS " : "FAILED ")
                .append(code == null ? "" : code);
        if (message != null && !message.isBlank()) {
            sb.append(" | ").append(message);
        }
        String s = sb.toString();
        return s.length() > SUMMARY_MAX_LEN ? s.substring(0, SUMMARY_MAX_LEN) : s;
    }
}
