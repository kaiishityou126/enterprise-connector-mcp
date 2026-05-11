package com.sea.star.ai.ec.enterprise.connector.domain.dto;

import com.sea.star.ai.ec.enterprise.connector.domain.model.enums.AccessType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 创建租户数据源 (Phase 6).
 * 路径: POST /admin/datasources/{tid}/{dsName} — tid/dsName 从路径取, 不出现在 body.
 * <p>
 * 敏感字段 {@code dbPassword} / {@code apiToken} 传明文, 服务端 AES-256-GCM 加密入库.
 */
@Data
@ToString(exclude = {"dbPassword", "apiToken"})
public class TenantDatasourceCreateRequest {

    @NotNull(message = "接入类型不能为空")
    private AccessType accessType;

    @Size(max = 500)
    private String dbUrl;

    @Size(max = 100)
    private String dbUsername;

    @Size(max = 500)
    private String dbPassword;

    /**
     * JDBC 驱动类名. accessType=DB 时必填 (服务层校验, 见 TenantDatasourceService.create).
     * 显式声明避免 HikariCP 在 fat-jar/多驱动并存时走 DriverManager SPI 自动发现选错驱动
     * (表现为"连得上但行为异常"). 常用值: PG=org.postgresql.Driver, MySQL=com.mysql.cj.jdbc.Driver.
     */
    @Size(max = 100)
    private String dbDriver;

    @Size(max = 500)
    private String apiBaseUrl;

    @Size(max = 20)
    private String apiAuthType;

    private String apiToken;

    /** JSONB 字段, 传 JSON 字符串 (如 {"X-Trace":"abc"}) */
    private String apiHeaders;
}
