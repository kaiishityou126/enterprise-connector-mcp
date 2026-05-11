package com.sea.star.ai.ec.enterprise.connector.constant;

/**
 * 纯标识符常量。
 * 所有数值上限、超时、重试次数等业务可调参数存 sys_dict 字典表。
 */
public final class BusinessConstants {

    private BusinessConstants() {}

    // ---- 缓存名称 ----
    public static final String CACHE_TENANT_CONFIG = "tenantConfig";
    public static final String CACHE_TENANT_DATASOURCE = "tenantDatasource";
    public static final String CACHE_ACTION_TEMPLATE = "actionTemplate";

    // ---- Redis Key 前缀 ----
    public static final String REDIS_PREFIX_IDEMPOTENT = "idempotent:";
    public static final String REDIS_PREFIX_RATE_LIMIT = "ratelimit:";

    // ---- HTTP Header 名 ----
    public static final String AUTH_HEADER_MCP = "Authorization";
    public static final String AUTH_HEADER_ADMIN = "X-API-Key";
    public static final String AUTH_HEADER_PURGE = "X-Purge-Api-Key";
    /**
     * MCP 请求可选头: 传当前会话所属租户 ID. 当前 MCP token 是全局共享, 服务端无法从 token
     * 反推租户. 客户端 (openclaw 等) 在 /mcp/** 请求里加这个头, AuthInterceptor 校验完 token
     * 后设到 TenantContext, 让 PerTenantToolCallbackProvider 在 tools/list 时按租户暴露
     * 合并 schema (template.paramSchema ∪ tenant.customParams). 不传时退回全局视图.
     */
    public static final String MCP_HEADER_TENANT_ID = "X-Tenant-Id";

    // ---- Pub/Sub Channel ----
    public static final String CHANNEL_CACHE_INVALIDATE = "cache:invalidate";
    public static final String CHANNEL_DICT_REFRESH = "dict:refresh";

    // ---- 字典表 Group 名 ----
    public static final String DICT_GROUP_LIMIT = "limit";
    public static final String DICT_GROUP_ASYNC = "async";
    public static final String DICT_GROUP_RESILIENCE = "resilience";
    public static final String DICT_GROUP_SECURITY = "security";

    // ---- 字典 value_type ----
    public static final String DICT_TYPE_INT = "INT";
    public static final String DICT_TYPE_LONG = "LONG";
    public static final String DICT_TYPE_STRING = "STRING";
    public static final String DICT_TYPE_BOOLEAN = "BOOLEAN";
}
