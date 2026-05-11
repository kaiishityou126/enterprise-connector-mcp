package com.sea.star.ai.ec.enterprise.connector.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SQL Server 驱动 + HikariCP 跨方言 smoke test (Phase 7).
 *
 * <p>验证项: mssql-jdbc 驱动建池 + Connection.isValid() + 简单 SELECT.
 * 跟 MysqlSmokeIntegrationTest 思路一致, 区别只是驱动类和容器镜像.
 *
 * <p>注: SQL Server 镜像 ~1.5GB, 启动 30s+, 标 Tag "slow" 让 CI 可单独排期.
 */
@Testcontainers
class SqlServerSmokeIntegrationTest {

    @SuppressWarnings("resource")
    private static final MSSQLServerContainer<?> MSSQL = new MSSQLServerContainer<>(
            "mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense()
            .withReuse(true);

    private static final HikariDataSource dataSource;

    static {
        // 跟 AbstractIntegrationTest 风格保持一致: static 初始化块启容器 + 建池
        MSSQL.start();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(MSSQL.getJdbcUrl());
        hc.setUsername(MSSQL.getUsername());
        hc.setPassword(MSSQL.getPassword());
        hc.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        hc.setMaximumPoolSize(2);
        // 不显式设 connectionTestQuery, 默认走 Connection.isValid() —— 跨方言通用
        dataSource = new HikariDataSource(hc);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    @DisplayName("SQL Server HikariCP 建池后 Connection.isValid() 返回 true")
    void connectionIsValid() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }
    }

    @Test
    @DisplayName("SQL Server 简单 SELECT 跑通")
    void simpleSelect() {
        Integer one = new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
        assertThat(one).isEqualTo(1);
    }
}
