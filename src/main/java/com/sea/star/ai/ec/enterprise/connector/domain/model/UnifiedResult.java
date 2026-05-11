package com.sea.star.ai.ec.enterprise.connector.domain.model;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedResult {

    private boolean success;
    private String code;
    private String message;
    private Object data;
    private Long timestamp;
    private String taskId;
    private String traceId;

    public static UnifiedResult ok(Object data) {
        return UnifiedResult.builder()
                .success(true)
                .code(ErrorCode.SUCCESS.getCode())
                .message(ErrorCode.SUCCESS.getMessage())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static UnifiedResult async(String taskId) {
        return UnifiedResult.builder()
                .success(true)
                .code(ErrorCode.SUCCESS.getCode())
                .message("任务已提交")
                .taskId(taskId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static UnifiedResult fail(ErrorCode errorCode) {
        return UnifiedResult.builder()
                .success(false)
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static UnifiedResult fail(ErrorCode errorCode, String message) {
        return UnifiedResult.builder()
                .success(false)
                .code(errorCode.getCode())
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static UnifiedResult fail(String code, String message) {
        return UnifiedResult.builder()
                .success(false)
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
