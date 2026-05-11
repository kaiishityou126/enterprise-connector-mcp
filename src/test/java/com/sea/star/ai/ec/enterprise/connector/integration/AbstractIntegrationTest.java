package com.sea.star.ai.ec.enterprise.connector.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * 所有集成测试的公共基类。
 *
 * <ul>
 *   <li>PG 容器：使用 postgres:16-alpine, 启动时挂载 test-schema.sql 做 DDL。</li>
 *   <li>Redis 容器：redis:7.4-alpine, 暴露 6379 端口。</li>
 *   <li>容器以 {@code static} 持有, JVM 整个 Surefire 生命周期内复用一次,
 *       子类之间共享, 避免每个 IT 重启。</li>
 *   <li>JDBC URL 追加 <code>?stringtype=unspecified</code> 让 PG 驱动不显式声明参数类型,
 *       这样 MyBatis 传 {@code String} 给 JSONB 列时 PG 会自动隐式转换, 不需要额外 TypeHandler。</li>
 * </ul>
 *
 * 子类加 {@code @SpringBootTest} 继承即可; 或者 {@code @MybatisFlexTest} 做窄 slice。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("sea_star_ai_test")
            .withUsername("test")
            .withPassword("test")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/01-schema.sql")
            .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        // PG: 拼 stringtype=unspecified 让 String→JSONB 的隐式转换生效
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "?stringtype=unspecified");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Redis
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
