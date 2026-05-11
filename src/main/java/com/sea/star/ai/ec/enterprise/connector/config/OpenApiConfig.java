package com.sea.star.ai.ec.enterprise.connector.config;

import com.sea.star.ai.ec.enterprise.connector.constant.BusinessConstants;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 配置。
 * 定义两种安全方案对应两类入口（MCP Bearer / Admin API Key），
 * 具体哪个端点用哪个安全方案由 controller 层的 @Operation 注解指定。
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_MCP = "mcp-bearer";
    private static final String SCHEME_ADMIN = "admin-api-key";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise Connector API")
                        .version("1.0.0")
                        .description("MCP Server + Admin API for multi-tenant business data access"))
                .components(new Components()
                        .addSecuritySchemes(SCHEME_MCP, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("MCP 调用用 Bearer Token"))
                        .addSecuritySchemes(SCHEME_ADMIN, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(BusinessConstants.AUTH_HEADER_ADMIN)
                                .description("Admin API 用 X-API-Key 头")))
                // 默认全局 admin key，mcp 端点在方法上单独覆盖
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_ADMIN));
    }
}
