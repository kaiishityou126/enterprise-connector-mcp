package com.sea.star.ai.ec.enterprise.connector.domain.model;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.TaskStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("async_task")
public class AsyncTask {

    @Id(keyType = KeyType.None)
    private String taskId;

    @Column("tenant_id")
    private String tenantId;

    @Column("action")
    private String action;

    @Column("params")
    private String params;

    @Column("status")
    private TaskStatus status;

    @Column("result")
    private String result;

    @Column("error_message")
    private String errorMessage;

    @Column("retry_count")
    private Integer retryCount;

    @Column("max_retries")
    private Integer maxRetries;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("finished_at")
    private LocalDateTime finishedAt;

    @Column("timeout_at")
    private LocalDateTime timeoutAt;

    @Column("callback_url")
    private String callbackUrl;
}
