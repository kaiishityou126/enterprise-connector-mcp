package com.sea.star.ai.ec.enterprise.connector.controller;

import com.sea.star.ai.ec.enterprise.connector.service.SysDictService;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自定义健康检查端点, K8s liveness/readiness 探针直接对接用.
 *
 * 路径设计:
 *   GET  /health/liveness      进程存活检查 (总返回 UP)
 *   GET  /health/readiness     服务就绪检查 (PG + Redis + 字典加载, 任一挂返回 503)
 *
 * Spring Actuator 也提供 /actuator/health, 这里额外提供简化版,
 * 不依赖 Actuator 权限控制, K8s 直连无阻力.
 */
@Slf4j
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final SysDictService sysDictService;

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean dbOk = checkDatabase(details);
        boolean redisOk = checkRedis(details);
        // 字典大小只作为观测信息, 不影响就绪判定：
        //   若启动时 DB 故障导致字典为空, 稍后 DB 恢复后业务依然能读默认值跑起来,
        //   不应该因此让 K8s 永远不路由流量 (只有人工调 admin 才能恢复, 违背自愈原则)。
        details.put("sysDictSize", sysDictService.size());

        boolean allUp = dbOk && redisOk;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DOWN");
        body.put("details", details);
        return allUp ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    private boolean checkDatabase(Map<String, Object> details) {
        try (var conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(1);
            details.put("database", valid ? "UP" : "DOWN");
            return valid;
        } catch (Exception e) {
            details.put("database", "DOWN: " + e.getMessage());
            return false;
        }
    }

    private boolean checkRedis(Map<String, Object> details) {
        if (redisConnectionFactory == null) {
            details.put("redis", "DISABLED");
            return true; // Redis 是可选的，不影响 readiness
        }
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            String pong = conn.ping();
            boolean up = "PONG".equalsIgnoreCase(pong);
            details.put("redis", up ? "UP" : "UNEXPECTED: " + pong);
            return up;
        } catch (Exception e) {
            details.put("redis", "DOWN: " + e.getMessage());
            return false;
        }
    }
}
