package com.sea.star.ai.ec.enterprise.connector.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MySQL 驱动 + HikariCP 跨方言 smoke test (Phase 7).
 *
 * <p>验证项: 在不依赖 Spring Boot 上下文的前提下, 用 mysql-connector-j 驱动建池,
 * Connection.isValid() 跨方言通用, 一条最简单的 SELECT 能跑通.
 *
 * <p>本测试与 AbstractIntegrationTest 解耦: 不要 Spring 应用启动 (避免 PG 容器 +
 * MySQL 容器同时启混淆 ServiceConnection), 只验证驱动可用性.
 */
@Testcontainers
class MysqlSmokeIntegrationTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("smoke")
            .withUsername("root")
            .withPassword("test")
            .withReuse(true);

    private static final HikariDataSource dataSource;

    static {
        // 跟 AbstractIntegrationTest 风格保持一致: static 初始化块启容器 + 建池
        MYSQL.start();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(MYSQL.getJdbcUrl());
        hc.setUsername(MYSQL.getUsername());
        hc.setPassword(MYSQL.getPassword());
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setMaximumPoolSize(2);
        // 不显式设 connectionTestQuery, 默认走 Connection.isValid() —— 跨方言通用
        dataSource = new HikariDataSource(hc);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    @DisplayName("MySQL HikariCP 建池后 Connection.isValid() 返回 true")
    void connectionIsValid() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }
    }

    @Test
    @DisplayName("MySQL 简单 SELECT 跑通")
    void simpleSelect() {
        Integer one = new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
        assertThat(one).isEqualTo(1);
    }
}
