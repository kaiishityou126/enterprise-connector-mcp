package com.sea.star.ai.ec.enterprise.connector.mcp;

import com.sea.star.ai.ec.enterprise.connector.domain.model.UnifiedResult;
import com.sea.star.ai.ec.enterprise.connector.util.SecurityUtils;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部回调接收端点. 异步任务完成后 AsyncTaskService 向 callback_url POST 结果;
 * 当 callback_url 指向本服务时 (测试场景或自回调), 由本 Controller 处理.
 *
 * 路径设计:
 *   POST  /internal/callback      接收异步任务完成回调 (需 X-Callback-Secret 头)
 *
 * 安全: 不走 AuthInterceptor 的 MCP Token 路径 (调用方是自己, Token 不会传),
 * 改用 /internal/** 前缀 + 共享密钥 Header 限制访问. 常量时间比较防时序攻击.
 */
@Slf4j
@RestController
@RequestMapping("/internal/callback")
public class McpWebhookController {

    public static final String CALLBACK_SECRET_HEADER = "X-Callback-Secret";

    @Value("${connector.async.callback.inbound-secret:}")
    private String inboundSecret;

    /**
     * 启动时检查配置。未配置时应用依旧能启动（因为可能根本不需要回调功能），
     * 但会打 ERROR 日志避免运维误以为回调在工作。
     */
    @PostConstruct
    void validateConfig() {
        if (inboundSecret == null || inboundSecret.isBlank()) {
            log.error("connector.async.callback.inbound-secret 未配置, 所有 /internal/callback "
                    + "请求将被拒绝。如需接收回调, 请设置环境变量 CALLBACK_INBOUND_SECRET");
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = CALLBACK_SECRET_HEADER, required = false) String secret,
            @RequestBody UnifiedResult result) {

        if (inboundSecret == null || inboundSecret.isBlank()) {
            log.warn("inbound-secret 未配置, 拒绝回调");
            return ResponseEntity.status(401).body(Map.of("accepted", false));
        }
        // 常量时间比较, 防时序攻击
        if (!SecurityUtils.constantTimeEquals(inboundSecret, secret)) {
            log.warn("回调 secret 不匹配, taskId={}", result == null ? "?" : result.getTaskId());
            return ResponseEntity.status(401).body(Map.of("accepted", false));
        }

        log.info("收到异步任务回调 taskId={}, success={}, code={}",
                result.getTaskId(), result.isSuccess(), result.getCode());
        // 此处示例只是打日志；真实使用场景下可以把结果转发给 OpenClaw / 业务队列。
        return ResponseEntity.ok(Map.of("accepted", true));
    }
}
