package com.sea.star.ai.ec.enterprise.connector.domain.model;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("audit_log")
public class AuditLog {

    @Id(keyType = KeyType.Auto)
    private Long logId;

    @Column("tenant_id")
    private String tenantId;

    @Column("action")
    private String action;

    @Column("caller_identity")
    private String callerIdentity;

    @Column("params")
    private String params;

    @Column("result_summary")
    private String resultSummary;

    @Column("duration_ms")
    private Integer durationMs;

    @Column("trace_id")
    private String traceId;

    @Column("created_at")
    private LocalDateTime createdAt;
}
