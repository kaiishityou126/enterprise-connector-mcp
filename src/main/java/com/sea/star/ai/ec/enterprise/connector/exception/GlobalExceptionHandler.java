package com.sea.star.ai.ec.enterprise.connector.exception;

import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<UnifiedResult> handleBusiness(BaseException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("业务异常 code={}, message={}, traceId={}",
                code.getCode(), e.getMessage(), MDC.get("traceId"));
        UnifiedResult body = UnifiedResult.fail(code, e.getMessage());
        body.setTraceId(com.sea.star.ai.ec.enterprise.connector.util.TraceIds.current());
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<UnifiedResult> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fieldErrorDescription(fe))
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}, traceId={}", detail, MDC.get("traceId"));
        UnifiedResult body = UnifiedResult.fail(ErrorCode.PARAM_INVALID, detail);
        body.setTraceId(com.sea.star.ai.ec.enterprise.connector.util.TraceIds.current());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 处理 @RequestParam / @PathVariable 上 @Validated 触发的校验异常。
     * MethodArgumentNotValidException 只覆盖 @RequestBody 的 DTO 校验。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<UnifiedResult> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数约束违反: {}, traceId={}", detail, MDC.get("traceId"));
        UnifiedResult body = UnifiedResult.fail(ErrorCode.PARAM_INVALID, detail);
        body.setTraceId(com.sea.star.ai.ec.enterprise.connector.util.TraceIds.current());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<UnifiedResult> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expectedType = e.getRequiredType() != null
                ? e.getRequiredType().getSimpleName() : "未知";
        String msg = String.format("参数 %s 类型错误, 期望 %s, 实际值 [%s]",
                e.getName(), expectedType, e.getValue());
        log.warn("{}, traceId={}", msg, MDC.get("traceId"));
        UnifiedResult body = UnifiedResult.fail(ErrorCode.PARAM_INVALID, msg);
        body.setTraceId(com.sea.star.ai.ec.enterprise.connector.util.TraceIds.current());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<UnifiedResult> handleBodyMissing(HttpMessageNotReadableException e) {
        // 打 root cause 让排查更快: 比如 "Cannot deserialize value of type AccessType from String 'DB'"
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.warn("请求体解析失败, traceId={}, cause={}", MDC.get("traceId"), root.getMessage());
        UnifiedResult body = UnifiedResult.fail(ErrorCode.PARAM_INVALID, "请求体格式错误");
        body.setTraceId(com.sea.star.ai.ec.enterprise.connector.util.TraceIds.current());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<UnifiedResult> handleUnknown(Exception e) {
        log.error("未捕获异常, traceId={}", MDC.get("traceId"), e);
        UnifiedResult body = UnifiedResult.fail(ErrorCode.SYSTEM_ERROR);
        body.setTraceId(com.sea.star.ai.ec.enterprise.connector.util.TraceIds.current());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String fieldErrorDescription(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }
}
