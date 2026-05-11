# Enterprise Connector 开发计划

> 版本：v2.0 | 更新日期：2026-04-16
>
> 本文档既是**业务设计说明**也是**开发实施 Plan**，每个模块标注了优先级和开发阶段。

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 技术栈](#2-技术栈)
- [3. 架构总览](#3-架构总览)
- [4. 数据库设计](#4-数据库设计)
- [5. 项目包结构](#5-项目包结构)
- [6. 核心模块设计](#6-核心模块设计)
- [7. 安全设计](#7-安全设计)
- [8. 可观测性设计](#8-可观测性设计)
- [9. 配置文件](#9-配置文件)
- [10. 开发阶段与任务](#10-开发阶段与任务)
- [11. 部署方案](#11-部署方案)
- [12. 扩展点（未来版本）](#12-扩展点未来版本)

---

## 1. 项目概述

### 1.1 业务目标

为使用 OpenClaw + ClawBot 的中小商家提供**统一业务数据接入层**。商家通过微信自然语言指令（如"查今天订单总额"），经 AI 解析后，由本连接器对接商家实际数据源，将结果标准化返回。

### 1.2 核心能力

| 能力 | 说明 |
|------|------|
| 多租户 | 每个商家一个 tenantId，数据软隔离 |
| 多接入类型 | 直连数据库（DB）或调用外部 HTTP API |
| 操作模板化 | 预定义 SQL/API 模板存储在数据库，按 action 匹配执行 |
| 同步模式（默认） | 快速操作（<5s）直接返回结果 |
| 异步模式（兜底） | 耗时操作返回 taskId，后台执行并回调 |
| 安全纵深 | 认证鉴权 + SQL 白名单 + 数据库只读账号 + 审计日志 |

### 1.3 MCP 协议

连接器作为 **MCP Server**，OpenClaw 作为 **MCP Client**，通过 MCP (Model Context Protocol) 标准通信。

---

## 2. 技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 基础框架 | Spring Boot | 3.4.5 | 主框架（Boot 4 生态未完善，回到 3.4.x） |
| MCP 集成 | Spring AI `spring-ai-starter-mcp-server-webmvc` | 1.1.4 | 标准 MCP 协议 (SSE/streamable-http)，tool 由 DynamicMcpToolProvider 从 action_template 动态注册 |
| 数据库 | PostgreSQL | 16+ | 主库 + 租户配置存储 |
| 连接池 | HikariCP | Spring Boot 自带 | 连接池 |
| 本地缓存 | Caffeine | 3.2+ | L1 缓存 |
| 远程缓存 | Redis (Lettuce) | 7.4+ | L2 缓存 + 缓存失效广播 |
| ORM | MyBatis-Flex | 1.11.6 | 数据访问层（原生复合主键 + APT 类型安全查询，不使用 JPA） |
| HTTP 客户端 | Spring WebClient | Spring Boot 自带 | 非阻塞调用商家 API |
| 熔断限流 | Resilience4j | 2.3+ | 熔断、限流、重试 |
| 异步任务 | Spring @Async | - | 配合 TaskDecorator 传递上下文 |
| SQL 解析 | JSqlParser | 5.1+ | SQL 白名单校验 |
| API 文档 | SpringDoc OpenAPI | 2.8+ | Swagger UI |
| 监控 | Micrometer + Prometheus | Spring Boot 自带 | Metrics 采集 |
| 工具库 | Lombok | 1.18+ | 减少样板代码 |
| 构建工具 | Maven | 3.9+ | - |
| JDK | OpenJDK | 21 | LTS |
| 测试 | Testcontainers | 1.20+ | 集成测试（PostgreSQL + Redis） |

---

## 3. 架构总览

### 3.1 数据流（MCP 视角）

```
微信用户 → ClawBot → OpenClaw (MCP Client)
                          │
                          ▼ MCP 协议调用（带认证 Token）
                   ┌──────────────┐
                   │  Connector   │
                   │ (MCP Server) │
                   └──────┬───────┘
                          │
           ┌──────────────┼──────────────┐
           ▼              ▼              ▼
     认证鉴权层      租户配置服务      审计日志
           │              │
           ▼              ▼
      限流/熔断 ←── 两级缓存(Caffeine+Redis)
           │              │
           ▼              ▼
     BusinessExecutor ← 模板服务(action_template)
           │
     ┌─────┴─────┐
     ▼           ▼
  同步执行    异步执行(@Async)
     │           │
     ▼           ▼
  适配器路由     适配器路由
  ┌────┴────┐   ┌────┴────┐
  ▼         ▼   ▼         ▼
DB适配器  HTTP适配器      回调 webhook
  │         │
  ▼         ▼
商家DB    商家API
(只读账号) (Bearer/Basic)
```

### 3.2 关键设计原则

| 原则 | 具体做法 |
|------|---------|
| 纵深防御 | SQL 白名单 → 参数化查询 → 数据库只读账号，三层保护 |
| 租户隔离 | ThreadLocal + TaskDecorator，异步场景不丢失 |
| 快速失败 | 熔断器 + 超时控制，避免级联故障 |
| 可观测 | 结构化日志 + Metrics + 全链路 traceId |
| 模板化 | SQL/API 模板存 DB，新增 action 不改代码 |

---

## 4. 数据库设计 (Phase 6)

### 4.1 ER 关系

```
tenant_config 1 ──── N tenant_datasource        (Phase 6: 数据源从 tenant_config 拆分)
     │
     1
     │
     N
tenant_action_config N ──── 1 action_template   (tenant_action_config 是必要授权白名单)

async_task N ──── 1 tenant_config (弱关联, 不加 FK)

sys_dict            (系统字典表, 启动时加载到内存, 运行时 CRUD)
audit_log           (独立追加表, 不与其他表关联)
```

**Phase 6 关键变化**:
- `tenant_config` 瘦身为租户身份 + 策略 (QPS / enabled / tier), 数据源拆到新表 `tenant_datasource`
- 新增复合主键表 `tenant_datasource` (tenant_id, ds_name): 一个租户可挂多个数据源
- `action_template.datasource_name` 字段: 声明该 action 打哪个逻辑数据源 (默认 `default`)
- `tenant_action_config.datasource_name_override`: 可覆盖模板默认的 ds_name
- **tenant_action_config 从可选覆盖升级为必要授权白名单** (无行即 ACTION_NOT_AUTHORIZED)
- **软删 (deleted BOOLEAN)**: 4 张表 (tenant_config / tenant_datasource / action_template / sys_dict), Flex `@Column(isLogicDelete=true)` 自动过滤
- **软删不级联**: 软删 tenant_config 只动自己; getConfig() 被 Flex 过滤后抛 TENANT_NOT_FOUND, 业务自然被拒. 下属 datasource / action_config 保留, restore 后立即可用
- **硬删 (purge) 才级联**: DELETE /admin/tenants/{id}/purge 同事务清 3 张表

### 4.2 DDL

#### 4.2.1 租户配置表 `tenant_config`

Phase 6 **只含身份 + 租户级策略**, 数据源字段迁到 `tenant_datasource`.

```sql
CREATE TABLE tenant_config (
    tenant_id       VARCHAR(50)  PRIMARY KEY,
    tenant_name     VARCHAR(100) NOT NULL,
    tier            VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',  -- STANDARD / PREMIUM
    rate_limit_qps  INT          DEFAULT 10,                    -- 每租户每秒最大请求数

    enabled         BOOLEAN      DEFAULT TRUE,                  -- false=禁用 (业务调用抛 TENANT_DISABLED)
    deleted         BOOLEAN      DEFAULT FALSE,                 -- 软删标志, Flex 自动过滤
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tenant_enabled ON tenant_config(enabled) WHERE deleted = FALSE;
```

#### 4.2.2 租户数据源表 `tenant_datasource` (Phase 6 新增)

一个租户可以挂多个数据源 (订单库 / 库存库 / CRM API 等), 通过逻辑 `ds_name` 区分.
`ds_name` 是**逻辑契约名**, 同 `ds_name` 跨租户可以指向不同物理数据库.

```sql
CREATE TABLE tenant_datasource (
    tenant_id       VARCHAR(50)  NOT NULL,
    ds_name         VARCHAR(50)  NOT NULL,                      -- 默认 'default', 可选 orders/inventory/crm_api 等
    access_type     VARCHAR(10)  NOT NULL,                      -- DB / API

    -- DB 接入配置 (access_type=DB 时使用)
    db_url          VARCHAR(500),
    db_username     VARCHAR(100),
    db_password_enc VARCHAR(500),                               -- 加密存储, AES-256-GCM
    db_driver       VARCHAR(100) DEFAULT 'org.postgresql.Driver',

    -- API 接入配置 (access_type=API 时使用)
    api_base_url    VARCHAR(500),
    api_auth_type   VARCHAR(20),                                -- BEARER / BASIC / NONE
    api_token_enc   VARCHAR(500),                               -- 加密存储
    api_headers     JSONB,

    enabled         BOOLEAN      DEFAULT TRUE,
    deleted         BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, ds_name)
    -- 故意不加 FK 到 tenant_config: 级联软删靠 Service 层同事务处理,
    -- 避免 ON DELETE CASCADE 在 purge 时变成数据黑洞
);

CREATE INDEX idx_tenant_ds_tenant ON tenant_datasource(tenant_id) WHERE deleted = FALSE;
```

#### 4.2.3 操作模板表 `action_template`

由**开发/运维团队维护**的预定义模板, 经过安全审核后入库.

```sql
CREATE TABLE action_template (
    template_id       SERIAL       PRIMARY KEY,
    action            VARCHAR(50)  NOT NULL,                    -- queryOrder, syncInventory ...
    access_type       VARCHAR(10)  NOT NULL,                    -- DB / API
    name              VARCHAR(100) NOT NULL,                    -- 显示名称
    description       TEXT,                                     -- 同时作为 MCP Tool description 暴露给 AI

    -- Phase 6 新增: 声明打哪个逻辑数据源
    datasource_name   VARCHAR(50)  NOT NULL DEFAULT 'default',  -- 引用 tenant_datasource.ds_name

    -- DB 模板
    sql_template      TEXT,                                     -- 用 :paramName 占位 (Spring NamedParameterJdbcTemplate 语法, 非 MyBatis 的 #{})
    -- API 模板
    api_path          VARCHAR(200),                             -- /api/v1/orders/{orderId}
    api_method        VARCHAR(10)  DEFAULT 'GET',
    api_body_template TEXT,                                     -- POST 请求体模板(JSON)

    -- 参数 schema (JSONB), 双用途: ParamValidator 运行时校验 + MCP inputSchema 生成
    param_schema      JSONB,

    -- 执行约束
    max_rows          INT     DEFAULT 500,                      -- 最大返回行数 (-1=不限制, 用于聚合)
    is_long_running   BOOLEAN DEFAULT FALSE,                    -- true=异步 (走 AsyncTaskService)
    timeout_seconds   INT     DEFAULT 5,                        -- 单次执行超时 (秒)

    enabled           BOOLEAN   DEFAULT TRUE,                   -- 业务开关, 支持灰度
    deleted           BOOLEAN   DEFAULT FALSE,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_template_action_type
    ON action_template(action, access_type) WHERE deleted = FALSE;
```

#### 4.2.4 租户动作授权表 `tenant_action_config`

**Phase 6 从"可选覆盖"升级为"必要授权白名单"**:
- 行存在 = 该租户获得该 action 的授权
- 行不存在 = 调用时抛 `ACTION_NOT_AUTHORIZED` (403)
- `enabled=false` 等价未授权
- **物理删**: 撤销就该干净, 历史由 `audit_log` 保留

```sql
CREATE TABLE tenant_action_config (
    tenant_id                VARCHAR(50) NOT NULL,
    action                   VARCHAR(50) NOT NULL,
    template_id              INT         NOT NULL,

    -- Phase 6 新增: 覆盖 action_template.datasource_name
    datasource_name_override VARCHAR(50),

    -- premium 租户可填自定义 SQL (SqlWhitelistValidator 校验)
    custom_sql               TEXT,
    custom_api_path          VARCHAR(200),
    custom_params            JSONB,

    enabled                  BOOLEAN   DEFAULT TRUE,            -- false = 临时撤销授权 (等价未授权)
    granted_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP,-- Phase 6 新增: 授权时间 (审计用)
    PRIMARY KEY (tenant_id, action)
    -- 不加 FK; BusinessExecutor 在调用时显式检查租户和模板
);
```

#### 4.2.5 异步任务表 `async_task`

物理删 + 30 天 TTL 清理 ([AsyncTaskCleanupJob](../src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/scheduler/AsyncTaskCleanupJob.java) 每天凌晨 3 点扫).

```sql
CREATE TABLE async_task (
    task_id         VARCHAR(50)  PRIMARY KEY,
    tenant_id       VARCHAR(50)  NOT NULL,
    action          VARCHAR(50)  NOT NULL,
    params          JSONB,
    status          VARCHAR(20)  NOT NULL,           -- PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT
    result          JSONB,
    error_message   TEXT,

    retry_count     INT       DEFAULT 0,
    max_retries     INT       DEFAULT 3,

    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    timeout_at      TIMESTAMP,                       -- 超时截止时间
    callback_url    VARCHAR(500)
);

CREATE INDEX idx_async_tenant_status ON async_task(tenant_id, status);
CREATE INDEX idx_async_created ON async_task(created_at);
CREATE INDEX idx_async_timeout ON async_task(status, timeout_at);
```

#### 4.2.6 审计日志表 `audit_log`

追加写入, **不允许 UPDATE / DELETE**. Purge 操作也保留 audit_log 作为合规证据.

```sql
CREATE TABLE audit_log (
    log_id          BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(50)  NOT NULL,
    action          VARCHAR(50)  NOT NULL,
    caller_identity VARCHAR(100),                    -- mcp / admin:xxx / ...
    params          JSONB,
    result_summary  VARCHAR(500),                    -- 成功/失败 + 摘要, 不存完整数据
    duration_ms     INT,
    trace_id        VARCHAR(50),                     -- 和应用日志 MDC traceId 对齐
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_tenant_time ON audit_log(tenant_id, created_at);

-- 只读保护: 应用层使用的数据库角色不授予 UPDATE/DELETE 权限
-- REVOKE UPDATE, DELETE ON audit_log FROM connector_app;
```

#### 4.2.7 系统字典表 `sys_dict`

运行时可调的业务参数, 启动时加载到内存, 通过 Admin API 热更新 (Redis Pub/Sub `dict:refresh` 广播).

```sql
CREATE TABLE sys_dict (
    dict_key    VARCHAR(100) PRIMARY KEY,
    dict_value  VARCHAR(500) NOT NULL,
    value_type  VARCHAR(20)  NOT NULL DEFAULT 'INT',  -- INT / LONG / STRING / BOOLEAN
    group_name  VARCHAR(50)  NOT NULL,                 -- limit / async / resilience / security
    description VARCHAR(200),
    deleted     BOOLEAN      DEFAULT FALSE,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dict_group ON sys_dict(group_name) WHERE deleted = FALSE;
```

---

## 5. 项目包结构

```
com.sea.star.ai.ec.enterprise.connector/
├── SeaStarEnterpriseConnectorApplication.java
│
├── config/                          # --- 配置类 ---
│   ├── McpServerConfig.java         # MCP Server 配置
│   ├── CacheConfig.java             # Caffeine + Redis 两级缓存
│   ├── AsyncConfig.java             # 线程池 + TaskDecorator（上下文传递）
│   ├── WebClientConfig.java         # WebClient + 超时配置
│   ├── ResilienceConfig.java        # Resilience4j 熔断/限流
│   ├── SecurityConfig.java          # API 认证鉴权
│   └── OpenApiConfig.java           # Swagger 文档
│
├── domain/                          # --- 领域模型 ---
│   ├── model/
│   │   ├── TenantConfig.java
│   │   ├── ActionTemplate.java      # 操作模板
│   │   ├── TenantActionConfig.java  # 租户-操作关联
│   │   ├── AsyncTask.java
│   │   ├── AuditLog.java
│   │   ├── SysDict.java              # 字典表实体
│   │   ├── UnifiedResult.java       # 统一返回结构
│   │   └── enums/
│   │       ├── AccessType.java      # DB / API
│   │       ├── TaskStatus.java      # PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT
│   │       ├── TenantTier.java      # STANDARD / PREMIUM
│   │       └── ErrorCode.java       # 统一错误码枚举
│   ├── dto/                         # 请求/响应 DTO（带校验注解）
│   │   ├── TenantConfigCreateRequest.java
│   │   ├── TenantConfigUpdateRequest.java
│   │   ├── TemplateCreateRequest.java
│   │   └── TemplateUpdateRequest.java
│   └── mapper/                      # MyBatis-Flex BaseMapper + APT 生成 TableDef
│       ├── TenantConfigMapper.java
│       ├── ActionTemplateMapper.java
│       ├── TenantActionConfigMapper.java
│       ├── AsyncTaskMapper.java
│       ├── AuditLogMapper.java
│       └── SysDictMapper.java
│
├── service/                         # --- 业务服务 ---
│   ├── SysDictService.java          # 字典表：启动加载 + 内存读取 + 热更新
│   ├── TenantConfigService.java     # 租户配置 + 两级缓存
│   ├── ActionTemplateService.java   # 模板管理 + 解析
│   ├── BusinessExecutor.java        # 核心执行器（同步/异步路由）
│   ├── AsyncTaskService.java        # 异步任务 + 重试 + 回调
│   ├── AuditService.java            # 审计日志写入
│   ├── adapter/
│   │   ├── BusinessAdapter.java     # 适配器接口
│   │   ├── DatabaseAdapter.java     # DB 适配器（DataSource 池管理）
│   │   └── HttpApiAdapter.java      # HTTP API 适配器
│   └── security/
│       ├── SqlWhitelistValidator.java  # SQL 白名单校验（JSqlParser）
│       └── AuthenticationService.java  # Token 验证
│
├── mcp/                             # --- MCP 协议层 ---
│   ├── McpToolService.java          # 暴露 MCP Tool（queryOrder 等）
│   └── McpWebhookController.java    # 异步任务回调端点
│
├── controller/                      # --- REST API ---
│   ├── AdminTenantController.java   # 租户 CRUD
│   ├── AdminTemplateController.java # 模板管理
│   ├── AdminTaskController.java     # 异步任务查询
│   ├── AdminDictController.java     # 字典表 CRUD + 按 group 查询
│   └── HealthController.java        # 健康检查（liveness / readiness）
│
├── infrastructure/                  # --- 基础设施 ---
│   ├── cache/
│   │   ├── TwoLevelCacheManager.java    # 两级缓存管理器
│   │   ├── CacheInvalidationListener.java # Redis Pub/Sub 缓存失效监听
│   │   └── DictRefreshListener.java       # Redis Pub/Sub 字典刷新监听
│   ├── datasource/
│   │   └── TenantDataSourceManager.java # 租户 DataSource 池（LRU + 上限）
│   └── async/
│       ├── TenantContextTaskDecorator.java # ThreadLocal 传递装饰器
│       └── AsyncTaskRecoveryRunner.java    # 启动时恢复中断任务
│
├── constant/
│   └── BusinessConstants.java       # 全局常量（边界值、缓存名、Redis 前缀等）
│
├── util/
│   ├── TenantContext.java           # ThreadLocal 租户上下文
│   ├── EncryptionUtils.java         # 敏感字段加解密
│   ├── ParamValidator.java          # MCP 参数动态校验（基于 param_schema）
│   └── JsonUtils.java               # JSON 工具
│
└── exception/
    ├── BaseException.java           # 异常基类（errorCode + message）
    ├── BusinessException.java       # 通用业务异常
    ├── TenantNotFoundException.java
    ├── TemplateNotFoundException.java
    ├── TaskNotFoundException.java
    ├── ParamValidationException.java
    ├── SqlValidationException.java
    ├── DuplicateRequestException.java
    ├── RateLimitExceededException.java
    ├── AdapterExecutionException.java
    └── GlobalExceptionHandler.java  # 统一异常处理（日志脱敏 + 标准化响应）
```

---

## 6. 核心模块设计

### 6.1 统一返回结构 `UnifiedResult`

```java
@Data
@Builder
public class UnifiedResult {
    private boolean success;
    private String code;           // 业务状态码：SUCCESS / BIZ_ERROR / SYSTEM_ERROR
    private String message;
    private Object data;
    private Long timestamp;
    private String taskId;         // 异步模式时返回
    private String traceId;        // 全链路追踪 ID
}
```

### 6.2 适配器接口 `BusinessAdapter`

```java
public interface BusinessAdapter {
    UnifiedResult execute(String action, String resolvedSqlOrPath, Map<String, Object> params);
    boolean supports(AccessType accessType);
}
```

> 注意：适配器接收的是**已解析的 SQL/Path**，不再自己去读配置。模板解析由 `ActionTemplateService` 完成。

### 6.3 租户 DataSource 池管理 `TenantDataSourceManager`

解决原方案中 DataSource 无限增长问题。

```
核心策略：
- 使用 LinkedHashMap(accessOrder=true) 实现 LRU
- 最大容量：100（可配置）
- 淘汰时调用 dataSource.close() 释放连接
- 每个租户 HikariCP 配置：maxPoolSize=5, connectionTimeout=3s, statement_timeout=5s
- 租户禁用/删除时主动清除对应 DataSource
```

```java
public class TenantDataSourceManager {
    private final int maxSize;    // 默认 100
    private final Map<String, HikariDataSource> pool; // LRU

    public DataSource getOrCreate(String tenantId, TenantConfig config) { ... }
    public void evict(String tenantId) { ... }  // 主动淘汰并 close
    public void closeAll() { ... }              // @PreDestroy 时调用
}
```

### 6.4 SQL 白名单校验 `SqlWhitelistValidator`

仅对 **premium 租户的 custom_sql** 生效，预定义模板由团队审核不走此校验。

```
校验规则（使用 JSqlParser 做 AST 级解析）：
1. 禁止分号（多语句）
2. 只允许 SELECT 语句（禁止 INSERT/UPDATE/DELETE/DROP/CREATE/ALTER）
3. 禁止 SELECT INTO
4. 禁止危险函数：lo_export, dblink, dblink_exec, pg_sleep, pg_read_file,
   pg_ls_dir, pg_terminate_backend, copy
5. 解析失败视为非法

兜底防线（数据库层面）：
- 租户查询统一使用 connector_readonly 角色连接
- 该角色只有 SELECT 权限
- 即使校验被绕过，数据库也会拒绝写操作
```

### 6.5 两级缓存 + 失效广播

```
写入路径：
  Admin API 更新 tenant_config
    → 写入 PostgreSQL
    → 删除 Redis 缓存
    → 发布 Redis Pub/Sub 消息（channel: cache:invalidate, payload: tenantId）
    → 本机 Caffeine 清除

读取路径：
  getConfig(tenantId)
    → L1 Caffeine 命中？返回
    → L2 Redis 命中？写回 L1，返回
    → L3 PostgreSQL 查询，写入 L2 + L1，返回

失效广播（多实例一致性）：
  所有实例订阅 Redis channel "cache:invalidate"
    → 收到消息后清除本地 Caffeine 对应 key

缓存参数：
  L1 Caffeine: expireAfterWrite=30min, maximumSize=1000
  L2 Redis: TTL=1h
```

### 6.6 ThreadLocal 异步传递 `TenantContextTaskDecorator`

解决 `@Async` 线程池中 ThreadLocal 丢失问题。

```java
public class TenantContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        // 在提交线程中捕获上下文
        String tenantId = TenantContext.getCurrentTenant();
        String traceId = MDC.get("traceId");
        return () -> {
            try {
                // 在执行线程中恢复上下文
                TenantContext.setCurrentTenant(tenantId);
                MDC.put("traceId", traceId);
                runnable.run();
            } finally {
                TenantContext.clear();
                MDC.clear();
            }
        };
    }
}
```

在 `AsyncConfig` 中注册：

```java
@Bean
public ThreadPoolTaskExecutor asyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(50);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("async-task-");
    executor.setTaskDecorator(new TenantContextTaskDecorator());  // 关键
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());
    executor.initialize();
    return executor;
}
```

### 6.7 异步任务服务 `AsyncTaskService`

```
任务生命周期：
  PENDING → RUNNING → SUCCESS
                    → FAILED → (retry_count < max_retries) → PENDING  // 重试
                             → (retry_count >= max_retries) → FAILED  // 最终失败
                    → TIMEOUT

提交流程：
  1. 生成 taskId（UUID），计算 timeout_at = now + timeout_seconds
  2. 插入 async_task 记录，状态 PENDING
  3. 提交到 @Async 线程池执行

执行流程：
  1. 更新状态 RUNNING，记录 started_at
  2. 调用适配器执行
  3. 成功：更新 SUCCESS + result，若有 callback_url 则回调
  4. 失败：retry_count++ < max_retries → 状态回 PENDING，延迟后重新提交
         retry_count >= max_retries → 状态 FAILED

回调机制（带重试）：
  - 使用 WebClient POST 结果到 callback_url
  - 失败重试 3 次，指数退避（1s, 2s, 4s）
  - 最终失败记录日志，不丢弃结果（结果已存 DB）

超时扫描（定时任务）：
  - 每 60 秒扫描 status=RUNNING AND timeout_at < now
  - 标记为 TIMEOUT，记录日志

启动恢复 AsyncTaskRecoveryRunner：
  - @PostConstruct 或 ApplicationReadyEvent
  - 扫描 status IN (PENDING, RUNNING) 的任务
  - RUNNING → 标记 FAILED（无法恢复执行中状态）
  - PENDING + retry_count < max_retries → 重新提交
```

### 6.8 核心执行器 `BusinessExecutor`

```
execute(tenantId, action, params):
  1. 获取 TenantConfig（走两级缓存）
  2. 获取 TenantActionConfig（租户+action 关联）
  3. 获取 ActionTemplate（模板详情）
  4. 解析最终 SQL 或 API 路径：
     - custom_sql 不为空（premium 租户）→ 走 SqlWhitelistValidator 校验后使用
     - 否则 → 使用 template 中的 sql_template / api_path
  5. 参数校验：根据 param_schema 校验 params（详见 6.10）
  6. 判断同步/异步：
     - template.is_long_running = true → 异步
     - 否则 → 同步
  7. 同步：调用适配器，记录审计日志，返回结果
  8. 异步：防重复提交检查（详见 6.12）→ 提交到 AsyncTaskService，返回 taskId
```

### 6.9 限流与熔断 `ResilienceConfig`

```
每租户限流（RateLimiter）：
  - 使用 Resilience4j RateLimiter
  - 默认 QPS 从 tenant_config.rate_limit_qps 读取
  - 超限返回 429 Too Many Requests

商家 API 熔断（CircuitBreaker）：
  - 按租户隔离熔断器
  - 失败率阈值 50%，滑动窗口 10 次调用
  - 熔断时直接返回降级结果，不再调用商家 API
  - 半开状态允许 3 次试探

数据库查询超时：
  - HikariCP connectionTimeout: 3s
  - statement_timeout: 5s（PostgreSQL 级别）
```

### 6.10 入参校验（Validation）

系统有两类入口，校验策略不同：

```
入口 A — Admin API（REST Controller）：
  使用 Jakarta Validation 注解，由 Spring 自动触发。

入口 B — MCP Tool 调用（McpToolService）：
  MCP 参数由 AI 生成，不经过 Controller 层，
  需要在 BusinessExecutor 内手动校验。
```

#### 6.10.1 Admin API 校验（注解驱动）

Controller 方法参数加 `@Valid`，实体类用注解约束：

```java
// DTO 示例
public class TenantConfigCreateRequest {
    @NotBlank(message = "租户ID不能为空")
    @Size(max = 50, message = "租户ID最长50字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "租户ID只允许字母数字下划线和连字符")
    private String tenantId;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 100)
    private String tenantName;

    @NotNull(message = "接入类型不能为空")
    private AccessType accessType;

    @Size(max = 500, message = "数据库URL最长500字符")
    private String dbUrl;

    @Min(value = 1, message = "QPS限流最小为1")
    @Max(value = 1000, message = "QPS限流最大为1000")
    private Integer rateLimitQps;
}
```

校验失败由 `GlobalExceptionHandler` 统一捕获，返回标准错误格式。

#### 6.10.2 MCP 调用参数校验（Schema 驱动）

MCP Tool 的参数由 AI 解析生成，不走 Controller，因此在 `BusinessExecutor` 执行前，
根据 `action_template.param_schema` 做**动态校验**：

```
param_schema 示例（存在 action_template 表中）：
{
  "orderId": {
    "type": "string",
    "required": true,
    "maxLength": 64,
    "pattern": "^[A-Za-z0-9-]+$",
    "description": "订单ID"
  },
  "startDate": {
    "type": "string",
    "required": false,
    "pattern": "^\\d{4}-\\d{2}-\\d{2}$",
    "description": "起始日期(yyyy-MM-dd)"
  }
}

校验逻辑（ParamValidator 工具类）：
  1. 遍历 schema 中每个字段定义
  2. required=true 但 params 中缺失 → 拒绝
  3. type 不匹配 → 拒绝
  4. maxLength / pattern 不符合 → 拒绝
  5. params 中出现 schema 未定义的字段 → 忽略（不传递给适配器）

校验失败返回：
  UnifiedResult { success=false, code="PARAM_INVALID", message="orderId: 不能为空" }
```

### 6.11 边界值校验

除了格式校验，还需要对**业务语义上的边界**做保护：

```
边界约束分两层：模板级（按 action 差异化）和 全局级（兜底保护）。

#### 模板级约束（action_template 表字段）

不同 action 对结果集的预期完全不同，行数限制应由模板自己定义：

┌──────────────────┬──────────┬───────────┬─────────────────────────────┐
│ 模板 action       │ max_rows │ timeout_s │ 说明                         │
├──────────────────┼──────────┼───────────┼─────────────────────────────┤
│ queryOrder       │ 50       │ 5         │ 查单条/几条明细               │
│ queryOrderList   │ 500      │ 10        │ 分页列表                     │
│ salesReport      │ NULL     │ 30        │ SELECT SUM/COUNT 聚合，无需限行 │
│ exportOrders     │ 10000    │ 300       │ 大量导出，走异步               │
└──────────────────┴──────────┴───────────┴─────────────────────────────┘

  - max_rows = NULL → 不追加 LIMIT（适用于聚合查询，返回结果本身就是少量行）
  - max_rows = N    → DatabaseAdapter 强制追加 LIMIT N（即使模板 SQL 没写 LIMIT）
  - timeout_seconds → 覆盖全局默认超时，长报表可放宽

#### 全局兜底约束（BusinessConstants）

不管模板怎么配，系统级有硬上限：

┌────────────────────────┬──────────┬──────────────────────────────────┐
│ 校验项                  │ 限制值    │ 触发位置                          │
├────────────────────────┼──────────┼──────────────────────────────────┤
│ max_rows 上限（即使模板配更大）│ ≤ 10000  │ DatabaseAdapter                  │
│ timeout_seconds 上限     │ ≤ 600    │ DatabaseAdapter / HttpApiAdapter  │
│ 单次 API 响应体大小      │ ≤ 5MB    │ HttpApiAdapter（WebClient 配置）  │
│ 请求参数 Map 总 key 数   │ ≤ 20     │ BusinessExecutor                 │
│ 单个参数值长度           │ ≤ 1000   │ ParamValidator                   │
│ custom_sql 长度          │ ≤ 2000   │ SqlWhitelistValidator            │
│ 异步任务最大并发(每租户)  │ ≤ 5      │ AsyncTaskService                 │
│ 异步任务总排队数          │ ≤ 200    │ AsyncConfig（队列容量）           │
│ 日期范围跨度             │ ≤ 365 天  │ ParamValidator（日期类参数）       │
│ 批量操作条数             │ ≤ 100    │ ParamValidator                   │
│ tenant_id 长度           │ 1-50     │ Jakarta Validation               │
│ callback_url 长度        │ ≤ 500    │ Jakarta Validation               │
└────────────────────────┴──────────┴──────────────────────────────────┘

#### 实现逻辑（DatabaseAdapter 中）

```java
// 模板配了 max_rows → 用模板值（但不超过全局上限）
// 模板配了 NULL     → 不追加 LIMIT（聚合查询场景）
Integer maxRows = template.getMaxRows();
if (maxRows != null) {
    int effectiveLimit = Math.min(maxRows, BusinessConstants.ABSOLUTE_MAX_ROWS);
    sql = sql + " LIMIT " + effectiveLimit;
}

// timeout 同理
int timeout = Math.min(
    template.getTimeoutSeconds() != null ? template.getTimeoutSeconds() : DEFAULT_TIMEOUT,
    BusinessConstants.ABSOLUTE_MAX_TIMEOUT
);
```

其他全局保护：
  - WebClient 响应体限制：
    webClient.mutate().codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
```

### 6.12 防重复提交

两个层面的防重：

```
层面 1 — MCP 请求级幂等（同步调用）：
  场景：网络抖动导致 OpenClaw 重发相同请求。
  方案：
    - OpenClaw 在请求中携带 requestId（MCP 协议支持）
    - Connector 用 Redis SET NX 做幂等键：
      key:   idempotent:{tenantId}:{requestId}
      value: 上次结果的 JSON
      TTL:   5 分钟
    - 若 key 已存在 → 直接返回缓存的结果，不重复执行
    - 若 key 不存在 → 正常执行，执行后写入结果

层面 2 — 异步任务防重复提交：
  场景：用户短时间内对同一 action + 相同参数发起多次请求。
  方案：
    - 提交异步任务前，查询是否存在相同条件的 PENDING/RUNNING 任务：
      SELECT task_id FROM async_task
      WHERE tenant_id = ? AND action = ? AND params = ?::jsonb
        AND status IN ('PENDING', 'RUNNING')
        AND created_at > NOW() - INTERVAL '10 minutes'
    - 若存在 → 直接返回已有 taskId，不重复创建
    - 若不存在 → 正常创建新任务

层面 3 — Admin API 防重复提交（租户创建等写操作）：
  方案：
    - 数据库唯一约束兜底（tenant_id PRIMARY KEY）
    - 创建接口返回 409 Conflict（而非 500）告知已存在
```

### 6.13 配置管理（消除硬编码）

#### 6.13.1 三层配置体系

文档中涉及的所有可配置值，按**能否运行时调整**和**是否与基础设施相关**分为三层：

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        配置值分层策略                                     │
├──────────┬──────────────┬────────────────┬─────────────┬────────────────┤
│ 层级      │ 存放位置      │ 修改方式        │ 是否需重启   │ 适用范围        │
├──────────┼──────────────┼────────────────┼─────────────┼────────────────┤
│ L1 字典表 │ sys_dict 表   │ Admin API CRUD │ 不需要       │ 业务参数/限制值  │
│ L2 配置文件│ application.yml│ 改文件重启     │ 需要重启     │ 基础设施参数     │
│ L3 代码常量│ BusinessConstants│ 改代码重编译 │ 需要重新部署  │ 永远不变的标识符  │
└──────────┴──────────────┴────────────────┴─────────────┴────────────────┘
```

判断原则：
- **能不能在运行时改？** 能 → L1 字典表
- **改了之后需要重建连接池/线程池等基础设施吗？** 需要 → L2 yml
- **改了就不是同一个东西了（协议名、key 前缀）？** 是 → L3 代码常量

#### 6.13.2 字典表 `sys_dict`

```sql
CREATE TABLE sys_dict (
    dict_key        VARCHAR(100) PRIMARY KEY,         -- 唯一标识，如 limit.absolute_max_rows
    dict_value      VARCHAR(500) NOT NULL,            -- 值（统一 String，代码中按类型解析）
    value_type      VARCHAR(20)  NOT NULL DEFAULT 'INT', -- INT / LONG / STRING / BOOLEAN
    group_name      VARCHAR(50)  NOT NULL,            -- 分组：limit / async / resilience / security
    description     VARCHAR(200),                     -- 说明
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dict_group ON sys_dict(group_name);
```

#### 6.13.3 字典服务 `SysDictService`

```
启动加载：
  @PostConstruct / ApplicationReadyEvent
  → SELECT * FROM sys_dict
  → 加载到 ConcurrentHashMap<String, String> 内存中

读取（业务代码调用）：
  SysDictService.getInt("limit.absolute_max_rows", 10000)
                                                     ↑ 兜底默认值，防止字典表漏配

更新（Admin API 调用）：
  1. 校验 key 存在、value_type 匹配、值在合理范围内
  2. UPDATE sys_dict SET dict_value = ?, updated_at = NOW()
  3. 更新本机内存 ConcurrentHashMap
  4. 发布 Redis Pub/Sub 消息（channel: dict:refresh, payload: dict_key）
  5. 其他实例收到消息后刷新对应 key

多实例同步（与缓存失效机制复用同一套 Pub/Sub 模式）：
  订阅 Redis channel "dict:refresh"
  → 收到消息 → 从 DB 重新加载该 key → 更新内存
```

```java
@Service
public class SysDictService {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    // 带默认值的类型安全读取，字典表未配时不会报错
    public int getInt(String key, int defaultValue) {
        String val = cache.get(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }
    public long getLong(String key, long defaultValue) { ... }
    public String getString(String key, String defaultValue) { ... }
    public boolean getBoolean(String key, boolean defaultValue) { ... }
}
```

#### 6.13.4 全量硬编码盘点与归属

**L1 — 字典表（运行时可调，不重启）**

```
分组: limit（业务限制值）
┌──────────────────────────────────┬──────────┬─────────────────────────────┐
│ dict_key                          │ 默认值    │ 说明                         │
├──────────────────────────────────┼──────────┼─────────────────────────────┤
│ limit.absolute_max_rows          │ 10000    │ 模板 max_rows 的系统硬上限    │
│ limit.default_max_rows           │ 500      │ 模板未配 max_rows 时的默认值  │
│ limit.absolute_max_timeout       │ 600      │ 模板 timeout 的系统硬上限(秒) │
│ limit.default_query_timeout      │ 5        │ 模板未配 timeout 时的默认值   │
│ limit.max_api_response_size_mb   │ 5        │ 商家 API 响应体上限(MB)       │
│ limit.max_param_count            │ 20       │ 单次请求参数数量上限          │
│ limit.max_param_value_length     │ 1000     │ 单个参数值长度上限            │
│ limit.max_custom_sql_length      │ 2000     │ premium 租户 custom_sql 长度  │
│ limit.max_date_range_days        │ 365      │ 日期范围跨度上限              │
│ limit.max_batch_size             │ 100      │ 批量操作条数上限              │
└──────────────────────────────────┴──────────┴─────────────────────────────┘

分组: async（异步任务）
┌──────────────────────────────────┬──────────┬─────────────────────────────┐
│ dict_key                          │ 默认值    │ 说明                         │
├──────────────────────────────────┼──────────┼─────────────────────────────┤
│ async.default_max_retries        │ 3        │ 任务默认最大重试次数          │
│ async.default_timeout_seconds    │ 300      │ 异步任务默认超时(秒)          │
│ async.max_tasks_per_tenant       │ 5        │ 每租户异步任务并发上限        │
│ async.timeout_scan_interval      │ 60       │ 超时扫描间隔(秒)             │
│ async.callback_retry_max         │ 3        │ 回调最大重试次数              │
│ async.callback_retry_backoff_ms  │ 1000     │ 回调重试退避基数(毫秒)        │
│ async.dedup_lookback_minutes     │ 10       │ 任务防重复回溯窗口(分钟)      │
└──────────────────────────────────┴──────────┴─────────────────────────────┘

分组: resilience（限流熔断）
┌──────────────────────────────────┬──────────┬─────────────────────────────┐
│ dict_key                          │ 默认值    │ 说明                         │
├──────────────────────────────────┼──────────┼─────────────────────────────┤
│ resilience.default_qps           │ 10       │ 租户未配 rate_limit_qps 时默认│
│ resilience.cb_failure_rate       │ 50       │ 熔断器失败率阈值(%)           │
│ resilience.cb_sliding_window     │ 10       │ 熔断器滑动窗口大小            │
│ resilience.cb_half_open_permits  │ 3        │ 半开状态允许试探次数          │
└──────────────────────────────────┴──────────┴─────────────────────────────┘

分组: security（安全）
┌──────────────────────────────────┬──────────┬─────────────────────────────┐
│ dict_key                          │ 默认值    │ 说明                         │
├──────────────────────────────────┼──────────┼─────────────────────────────┤
│ security.idempotent_key_ttl      │ 300      │ 幂等 key 过期时间(秒)         │
└──────────────────────────────────┴──────────┴─────────────────────────────┘
```

**L2 — application.yml（启动时加载，改了需重启）**

这些值影响**基础设施初始化**（连接池、线程池、缓存容器），不适合运行时热改：

```
spring.datasource.hikari:
  maximum-pool-size: 20              # 主库连接池，改了需重建池
  connection-timeout: 3000

spring.data.redis:
  host / port                        # Redis 地址，改了需重连

spring.ai.mcp.server:
  port: 8081                         # MCP 端口，改了需重启监听

server:
  port: 8080                         # HTTP 端口
  shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 30s

connector:
  security:
    mcp-token / admin-api-key / encryption-key   # 密钥类，环境变量注入
  cache:
    caffeine:
      expire-after-write-minutes: 30  # Caffeine 过期策略，构建时确定
      maximum-size: 1000              # Caffeine 容量，构建时确定
    redis:
      ttl-seconds: 3600               # Redis TTL
      invalidation-channel: cache:invalidate
  datasource-pool:
    max-tenants: 100                  # DataSource 池容量，构建时确定
    per-tenant-max-connections: 5     # 每租户 HikariCP 配置
    statement-timeout-seconds: 5
  async:
    thread-pool:
      core-size: 10                   # 线程池，构建时确定
      max-size: 50
      queue-capacity: 200
```

**L3 — 代码常量 `BusinessConstants`（永远不变，编译进代码）**

只保留**标识符和协议约定**，不再放任何数值上限：

```java
public final class BusinessConstants {
    private BusinessConstants() {}

    // ---- 缓存名称（与 CacheConfig 中 @Cacheable 注解对应，改名等于换缓存） ----
    public static final String CACHE_TENANT_CONFIG = "tenantConfig";
    public static final String CACHE_ACTION_TEMPLATE = "actionTemplate";

    // ---- Redis Key 前缀（改了会导致旧 key 残留） ----
    public static final String REDIS_PREFIX_IDEMPOTENT = "idempotent:";
    public static final String REDIS_PREFIX_RATE_LIMIT = "ratelimit:";

    // ---- HTTP Header 名（协议约定，不会变） ----
    public static final String AUTH_HEADER_MCP = "Authorization";
    public static final String AUTH_HEADER_ADMIN = "X-API-Key";

    // ---- Pub/Sub Channel（多实例间约定，不能随意改） ----
    public static final String CHANNEL_CACHE_INVALIDATE = "cache:invalidate";
    public static final String CHANNEL_DICT_REFRESH = "dict:refresh";

    // ---- 字典表 Group 名 ----
    public static final String DICT_GROUP_LIMIT = "limit";
    public static final String DICT_GROUP_ASYNC = "async";
    public static final String DICT_GROUP_RESILIENCE = "resilience";
    public static final String DICT_GROUP_SECURITY = "security";
}
```

#### 6.13.5 业务代码使用方式

```java
// 之前（硬编码）：
int effectiveLimit = Math.min(maxRows, 10000);

// 之后（读字典表）：
int absoluteMax = sysDictService.getInt("limit.absolute_max_rows", 10000);
int effectiveLimit = Math.min(maxRows, absoluteMax);
//                                                      ↑ 10000 只是兜底默认值
//                                                        字典表有值时用字典表的值

// 之前（硬编码）：
if (retryCount >= 3) { ... }

// 之后：
int maxRetries = sysDictService.getInt("async.default_max_retries", 3);
if (retryCount >= maxRetries) { ... }
```

#### 6.13.6 使用原则

```
1. 业务代码中不允许出现裸数字/魔法字符串
2. 所有数值上限、超时、重试次数等 → 读 SysDictService，带兜底默认值
3. 缓存名、Redis key 前缀、Header 名等标识符 → 引用 BusinessConstants
4. 连接池/线程池/端口等基础设施参数 → application.yml + @ConfigurationProperties
5. 密钥类配置 → 环境变量注入，不进代码库也不进字典表
6. 字典表的 dict_key 命名规范：{group}.{snake_case_name}，如 limit.max_batch_size
```

### 6.14 统一异常处理 `GlobalExceptionHandler`

#### 6.14.1 异常体系

```
RuntimeException
  └── BaseException (抽象基类, 包含 errorCode + message)
        ├── TenantNotFoundException        — 404, TENANT_NOT_FOUND
        ├── TemplateNotFoundException      — 404, TEMPLATE_NOT_FOUND
        ├── TaskNotFoundException          — 404, TASK_NOT_FOUND
        ├── ParamValidationException       — 400, PARAM_INVALID
        ├── SqlValidationException         — 400, SQL_INVALID
        ├── DuplicateRequestException      — 409, DUPLICATE_REQUEST
        ├── RateLimitExceededException     — 429, RATE_LIMIT_EXCEEDED
        ├── CircuitBreakerOpenException    — 503, SERVICE_DEGRADED
        ├── AdapterExecutionException      — 502, UPSTREAM_ERROR
        └── BusinessException              — 500, BIZ_ERROR (通用业务异常)
```

#### 6.14.2 错误码规范

```
格式：{模块}_{具体错误}，全大写

模块划分：
  TENANT_     — 租户相关
  TEMPLATE_   — 模板相关
  PARAM_      — 参数相关
  SQL_        — SQL 相关
  TASK_       — 异步任务相关
  AUTH_       — 认证鉴权相关
  ADAPTER_    — 适配器/上游相关
  SYSTEM_     — 系统级错误

完整错误码枚举 ErrorCode：
  TENANT_NOT_FOUND        (404, "租户不存在")
  TENANT_DISABLED          (403, "租户已禁用")
  TEMPLATE_NOT_FOUND      (404, "操作模板不存在")
  PARAM_INVALID            (400, "参数校验失败")
  PARAM_MISSING            (400, "必填参数缺失")
  SQL_INVALID              (400, "SQL 校验未通过")
  TASK_NOT_FOUND           (404, "任务不存在")
  TASK_DUPLICATE           (409, "任务重复提交")
  AUTH_TOKEN_MISSING       (401, "认证 Token 缺失")
  AUTH_TOKEN_INVALID       (401, "认证 Token 无效")
  AUTH_FORBIDDEN           (403, "无权限访问")
  ADAPTER_DB_ERROR         (502, "数据库查询失败")
  ADAPTER_API_ERROR        (502, "外部 API 调用失败")
  ADAPTER_TIMEOUT          (504, "上游响应超时")
  RATE_LIMIT_EXCEEDED      (429, "请求频率超限")
  SERVICE_DEGRADED         (503, "服务降级中")
  SYSTEM_ERROR             (500, "系统内部错误")
```

#### 6.14.3 统一错误响应格式

```java
// 所有异常最终返回此结构，与 UnifiedResult 对齐
{
  "success": false,
  "code": "PARAM_INVALID",
  "message": "orderId: 不能为空",
  "data": null,
  "timestamp": 1713264000000,
  "traceId": "abc-123-def"
}
```

#### 6.14.4 GlobalExceptionHandler 处理策略

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. 自定义业务异常 → 按 errorCode 中定义的 HTTP 状态码返回
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<UnifiedResult> handleBusiness(BaseException e) { ... }

    // 2. Jakarta Validation 异常 → 400 + 拼接所有字段错误信息
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<UnifiedResult> handleValidation(...) { ... }

    // 3. 参数类型不匹配 → 400
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ...

    // 4. 请求体缺失 → 400
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ...

    // 5. 兜底：未知异常 → 500 + 只返回"系统内部错误"，不暴露堆栈
    @ExceptionHandler(Exception.class)
    public ResponseEntity<UnifiedResult> handleUnknown(Exception e) {
        log.error("未捕获异常, traceId={}", MDC.get("traceId"), e);  // 日志记录完整堆栈
        return ResponseEntity.status(500)
            .body(UnifiedResult.fail("SYSTEM_ERROR", "系统内部错误")); // 响应不暴露细节
    }
}

关键原则：
  - 对外：只返回 code + 人可读 message，永远不暴露堆栈、SQL、内部路径
  - 对内：log.error 记录完整异常 + traceId，方便排查
  - 敏感信息过滤：异常 message 中如果包含 SQL、密码等，替换为通用描述
```

### 6.15 多数据源 + 必要授权白名单 (Phase 6 新增)

#### 6.15.1 场景动因

Phase 5 的设计假设一个租户只有一个数据源 (DB 或 API 二选一), 现实中商户往往同时挂多个系统:
- 订单系统 (MySQL)
- 库存系统 (PostgreSQL)
- CRM 系统 (REST API)
- 支付系统 (另一套 REST API)

因此 Phase 6 把数据源从 `tenant_config` 拆到独立表 `tenant_datasource`, 通过逻辑 `ds_name` 寻址.

#### 6.15.2 数据源绑定设计 (方案 B3)

| 层级 | 字段 | 语义 |
|------|------|------|
| 模板级 | `action_template.datasource_name` | 声明默认打哪个 ds (如 `orders`) |
| 租户级 | `tenant_action_config.datasource_name_override` | 可覆盖模板默认 (少数租户特殊命名场景) |

**解析链**:
```
dsName = tenant_action_config.datasource_name_override
      ?? action_template.datasource_name
      ?? "default"  (兜底)
```

**同 ds_name 跨租户解耦**: 商户 A 的 `orders` 可以是 PostgreSQL, 商户 B 的 `orders` 可以是 MySQL, 配置完全独立.

#### 6.15.3 必要授权白名单

Phase 5 `tenant_action_config` 是"可选覆盖", 任何租户都能调任何 enabled 模板 — **商业分层漏洞**.

Phase 6 升级为**必要授权**:
- `BusinessExecutor.resolveContext` 第一件事就是查 `tenant_action_config(tenantId, action)`
- 查不到 → 抛 `ActionNotAuthorizedException` (403)
- 查到但 `enabled=false` → 同样抛 `ACTION_NOT_AUTHORIZED`
- 只有行存在 + enabled=true 才进入后续流程

**批量授权 API**: `POST /admin/tenants/{tid}/actions/grant-all-defaults` 一键把所有 enabled 模板授予某租户, 对应 ds 不存在的跳过不抛错 (宽容批处理).

#### 6.15.4 TenantStatusGuard

集中入口检查: MCP 调用入口 + 异步任务执行前先走 `TenantStatusGuard.requireActive(tenantId)`:
- Flex 自动过滤软删的 `tenant_config` → 抛 `TenantNotFoundException`
- `enabled=false` → 抛 `TenantDisabledException`

Admin 运维操作 (解冻、恢复) 走 `requireExists(tenantId)`, 允许对禁用/禁用租户操作.

### 6.16 硬删 (Purge) 双重认证 (Phase 6 新增)

#### 6.16.1 软删 vs 硬删语义

| 操作 | 行为 | 级联 |
|------|------|------|
| 软删 (`DELETE /admin/tenants/{id}`) | `UPDATE tenant_config SET deleted=true` | **不级联**, 只动自己 |
| 恢复 (`POST /admin/tenants/{id}/restore`) | `UPDATE tenant_config SET deleted=false` | **不级联**, 下属配置从未被动 |
| 硬删 (`DELETE /admin/tenants/{id}/purge`) | 真 `DELETE FROM tenant_config + tenant_datasource + tenant_action_config` | **级联** (清仓语义) |

为什么软删不级联? 业务调用链第一步 `getConfig()` 被 Flex 自动过滤, 抛 `TENANT_NOT_FOUND` → 业务被拒. 下属配置保留, restore 立即可用 — 最干净的"暂停服务 + 保留配置"语义.

#### 6.16.2 双重认证

`/purge` 结尾的端点在 `AuthInterceptor` 中叠加 `verifyPurge()`, 要求:
```
X-API-Key: {admin-api-key}           (Admin 层认证, 所有 /admin/** 都要)
X-Purge-Api-Key: {admin-purge-api-key} (Purge 专用, 只 /purge 端点要)
```

两个 key **独立配置**:
- `admin-api-key` 给日常运维, 可做 CRUD / 软删 / restore (**可恢复**)
- `admin-purge-api-key` 给 DBA / SRE, 额外能做物理删 (**不可恢复**)

即使 `admin-api-key` 泄漏, 物理删能力仍受保护.

**保守默认**: 未配置 `admin-purge-api-key` 时 `verifyPurge()` 抛 `AUTH_FORBIDDEN` 拒绝所有 purge 请求 — 等于关闭物理删功能.

#### 6.16.3 异步任务 TTL 清理

`async_task` 表无软删, 由 `AsyncTaskCleanupJob` 每天凌晨 3 点清理 30 天前的终态任务:
- `SUCCESS` / `FAILED` / `TIMEOUT` 状态
- 分批 1000 条, 单次最多 100 批 (防失控)
- 保留天数可通过 `sys_dict.async.cleanup_retention_days` 调整

---

## 7. 安全

### 7.1 认证鉴权

```
MCP 调用认证：
  - OpenClaw 每次调用携带 Authorization: Bearer <token>
  - Connector 验证 token 有效性（JWT 或预共享密钥，取决于 OpenClaw 方案）
  - 从 token 中提取 tenantId，不信任客户端传入的 tenantId

Admin API 认证：
  - API Key 认证（Header: X-API-Key）
  - 后续可升级为 RBAC
```

### 7.2 敏感数据保护

```
存储加密：
  - db_password_enc, api_token_enc 使用 AES-256 加密后存储
  - 密钥通过环境变量注入，不进代码库
  - 工具类 EncryptionUtils 统一加解密

日志脱敏：
  - Logback 自定义 PatternLayout，对以下字段做掩码：
    password, token, secret, authorization
  - SQL 参数日志只记录参数名不记录值（或部分掩码）

传输安全：
  - 生产环境强制 HTTPS
  - 商家 API 调用走 HTTPS
```

### 7.3 SQL 安全（纵深防御）

```
层级          防护措施                               拦截对象
─────────────────────────────────────────────────────────────
L1 模板管控    预定义模板由团队审核入库                  所有租户
L2 白名单校验  JSqlParser AST 校验 custom_sql           premium 租户
L3 参数化执行  MyBatis 参数绑定 / NamedParameterJdbcTemplate 防参数注入
L4 数据库权限  connector_readonly 角色，只有 SELECT      兜底防线
L5 执行限制    statement_timeout = 5s                   防慢查询攻击
L6 审计日志    记录每次执行的 SQL + 参数 + 结果摘要      事后追溯
```

---

## 8. 可观测性设计

### 8.1 结构化日志

```
每次请求日志包含：
  - traceId: 全链路追踪 ID（从 OpenClaw 传入或自动生成）
  - tenantId: 租户 ID（通过 MDC 注入）
  - action: 操作类型
  - duration: 耗时(ms)
  - status: SUCCESS / FAILED
  - adapter: DB / API

日志格式（JSON，便于 ELK/Loki 采集）：
  {"timestamp":"...","level":"INFO","traceId":"abc123","tenantId":"T001",
   "action":"queryOrder","adapter":"DB","duration":42,"status":"SUCCESS"}
```

### 8.2 Metrics（Micrometer + Prometheus）

```
关键指标：
  connector.request.total         (counter)  按 tenant, action, status 分组
  connector.request.duration      (timer)    按 tenant, action 分组
  connector.cache.hit             (counter)  按 level(L1/L2/L3) 分组
  connector.datasource.pool.size  (gauge)    当前租户 DataSource 池大小
  connector.async.task.active     (gauge)    按 status 分组
  connector.async.task.total      (counter)  按 tenant, action, status 分组
  connector.circuit.state         (gauge)    熔断器状态 (CLOSED/OPEN/HALF_OPEN)
```

### 8.3 健康检查

```
GET /actuator/health/liveness   → 进程存活（总是 UP）
GET /actuator/health/readiness  → 服务就绪
  - PostgreSQL 连接正常
  - Redis 连接正常
  - 异步线程池未满
```

---

## 9. 配置文件

### application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/connector_db
    username: connector_app
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      connection-timeout: 3000
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  ai:
    mcp:
      server:
        enabled: true
        name: enterprise-connector-mcp
        version: 1.0.0
        protocol: STREAMABLE
        port: 8081

server:
  port: 8080
  shutdown: graceful                  # 优雅停机

spring.lifecycle:
  timeout-per-shutdown-phase: 30s     # 停机等待时间

# ---- 连接器自定义配置 ----
connector:
  security:
    mcp-token: ${MCP_AUTH_TOKEN}      # MCP 调用认证 token
    admin-api-key: ${ADMIN_API_KEY}   # Admin API 密钥
    encryption-key: ${ENCRYPTION_KEY} # AES 加密密钥（Base64）
  async:
    default-timeout-seconds: 300
    recovery-on-startup: true         # 启动时恢复中断任务
    callback:
      enabled: true
      retry-max: 3
      retry-backoff-ms: 1000
  cache:
    caffeine:
      expire-after-write-minutes: 30
      maximum-size: 1000
    redis:
      ttl-seconds: 3600
      invalidation-channel: cache:invalidate
  datasource-pool:
    max-tenants: 100                  # 最大同时缓存的租户 DataSource 数
    per-tenant-max-connections: 5
    statement-timeout-seconds: 5
  resilience:
    rate-limiter:
      default-qps: 10
    circuit-breaker:
      failure-rate-threshold: 50
      sliding-window-size: 10
      permitted-in-half-open: 3

# ---- 监控 ----
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when_authorized
  metrics:
    tags:
      application: enterprise-connector
```

---

## 10. 开发阶段与任务

### Phase 1：项目骨架 + 数据层（基础可运行）

| # | 任务 | 产出 | 验收标准 |
|---|------|------|---------|
| 1.1 | pom.xml 添加全部依赖 | pom.xml | mvn compile 通过 |
| 1.2 | 创建 application.yml | 配置文件 | 应用能启动 |
| 1.3 | 执行 DDL 建表（6 张，含 sys_dict） | SQL 脚本 | 表结构正确 |
| 1.4 | 实体类（MyBatis-Flex 注解 @Table/@Id/@Column）+ Mapper（com.mybatisflex.core.BaseMapper） | domain/ | - |
| 1.5 | 枚举类 | AccessType, TaskStatus, TenantTier, ErrorCode | - |
| 1.6 | 统一返回结构 UnifiedResult | model/ | - |
| 1.7 | 异常体系 BaseException + 各子类 | exception/ | 覆盖全部错误码 |
| 1.8 | GlobalExceptionHandler（完整版） | exception/ | 所有异常类型→标准格式，不暴露堆栈 |
| 1.9 | BusinessConstants（仅标识符常量） | constant/ | 缓存名、Redis 前缀、Header 名 |
| 1.10 | SysDictService（字典表加载 + 读取 + 热更新） | service/ | 启动加载、getInt/getString 可用 |
| 1.11 | DictRefreshListener（Redis Pub/Sub） | infrastructure/ | 多实例字典同步 |
| 1.12 | sys_dict 初始数据（全量字典项） | SQL seed 脚本 | 所有业务参数有默认值 |
| 1.13 | DTO + Jakarta Validation 注解 | domain/dto/ | Admin API 入参格式/边界校验 |
| 1.14 | 工具类 TenantContext, JsonUtils, EncryptionUtils, ParamValidator | util/ | 单元测试通过 |

### Phase 2：核心服务层（能跑通主流程）

| # | 任务 | 产出 | 验收标准 |
|---|------|------|---------|
| 2.1 | 两级缓存配置 + TwoLevelCacheManager | config/ + infrastructure/ | 命中/穿透逻辑正确 |
| 2.2 | Redis Pub/Sub 缓存失效 | CacheInvalidationListener | 多实例缓存一致 |
| 2.3 | TenantConfigService | service/ | 缓存读写正确 |
| 2.4 | ActionTemplateService | service/ | 模板查询 + 解析 |
| 2.5 | TenantDataSourceManager（LRU 池） | infrastructure/ | 上限淘汰 + close |
| 2.6 | DatabaseAdapter | service/adapter/ | 参数化 SQL 执行 |
| 2.7 | WebClientConfig + HttpApiAdapter | config/ + adapter/ | Bearer/Basic 认证 |
| 2.8 | BusinessAdapter 接口 | service/adapter/ | - |
| 2.9 | BusinessExecutor（同步路径） | service/ | 端到端查询返回结果 |

### Phase 3：异步 + 安全 + 限流 + 防护

| # | 任务 | 产出 | 验收标准 |
|---|------|------|---------|
| 3.1 | AsyncConfig + TenantContextTaskDecorator | config/ + infrastructure/ | ThreadLocal 异步传递 |
| 3.2 | AsyncTaskService（提交 + 执行 + 重试 + 回调） | service/ | 完整生命周期 |
| 3.3 | AsyncTaskRecoveryRunner | infrastructure/ | 重启后恢复任务 |
| 3.4 | BusinessExecutor 补充异步路径 | service/ | 长任务走异步 |
| 3.5 | SqlWhitelistValidator | service/security/ | JSqlParser 校验 |
| 3.6 | AuthenticationService | service/security/ | Token 验证 |
| 3.7 | SecurityConfig（拦截器/过滤器） | config/ | MCP + Admin 双认证 |
| 3.8 | ResilienceConfig（限流 + 熔断） | config/ | 限流拒绝 / 熔断降级 |
| 3.9 | AuditService + 审计日志 | service/ | 每次操作有记录 |
| 3.10 | MCP 请求幂等（Redis SET NX） | infrastructure/ | 相同 requestId 不重复执行 |
| 3.11 | 异步任务防重复提交 | AsyncTaskService | 相同 tenant+action+params 不重复创建 |
| 3.12 | DatabaseAdapter 强制 LIMIT 保护 | adapter/ | 查询结果不超过 MAX_QUERY_ROWS |
| 3.13 | WebClient 响应体大小限制 | WebClientConfig | 响应不超过 MAX_API_RESPONSE_SIZE |

### Phase 4：MCP 接入 + Admin API

| # | 任务 | 产出 | 验收标准 |
|---|------|------|---------|
| 4.1 | McpServerConfig | config/ | MCP Server 启动 |
| 4.2 | McpToolService（暴露 queryOrder） | mcp/ | MCP Client 调用成功 |
| 4.3 | McpWebhookController | mcp/ | 异步回调推送 |
| 4.4 | AdminTenantController（租户 CRUD） | controller/ | RESTful API |
| 4.5 | AdminTemplateController（模板管理） | controller/ | RESTful API |
| 4.6 | AdminTaskController（任务查询） | controller/ | 按 tenant/status 查询 |
| 4.7 | AdminDictController（字典 CRUD + 按 group 查询） | controller/ | 热更新不重启生效 |
| 4.8 | HealthController（liveness + readiness） | controller/ | 探针正确 |
| 4.9 | OpenApiConfig + Swagger | config/ | 文档可访问 |

### Phase 5：可观测 + 测试 + 部署 ✅

| # | 任务 | 产出 | 验收标准 |
|---|------|------|---------|
| 5.1 | 结构化日志（JSON + MDC） | logback-spring.xml | 日志含 traceId/tenantId |
| 5.2 | 日志脱敏 | logback 自定义 Layout (MaskingConverter) | 密码/token 不出现在日志 |
| 5.3 | Metrics 埋点 | ConnectorMetrics | Prometheus /actuator/prometheus 可采集 |
| 5.4 | 单元测试 | test/util, test/service/security, test/infrastructure/logging | 121 UT 全绿 |
| 5.5 | 集成测试（Testcontainers） | test/integration + test/domain/mapper | 33+ IT 覆盖核心流程 (Docker 环境跑) |
| 5.6 | Dockerfile | Dockerfile | 镜像构建成功 |
| 5.7 | docker-compose（PG + Redis + App） | docker-compose.yml | 一键启动 |
| 5.8 | 优雅停机验证 | - | 关闭时等待任务完成 |

### Phase 6：多数据源 + 必要授权白名单 + 软删 ✅

| # | 任务 | 产出 | 验收标准 |
|---|------|------|---------|
| 6.1 | DDL + 实体重构 | sql/01_create_tables.sql, TenantDatasource 新实体 | tenant_config 瘦身, tenant_datasource 新表, 4 张表加 `deleted` 字段 |
| 6.2 | BusinessExecutor 授权检查 + dsName 解析 + TenantStatusGuard | BusinessExecutor, TenantStatusGuard | 无授权 → ACTION_NOT_AUTHORIZED; override 能解析 |
| 6.3 | Admin API (Datasource / ActionConfig / restore / purge) | AdminDatasourceController, AdminActionConfigController, 4 DTO | 12 个新端点可用 |
| 6.4 | AsyncTaskCleanupJob + purge-api-key 双重认证 | AsyncTaskCleanupJob, AuthenticationService.verifyPurge | /purge 端点需 X-Purge-Api-Key |
| 6.5 | IT 适配 + 文档 + Postman | TenantDatasourceMapperIT, BusinessExecutorAuthorizationIT, 8 docs | 编译通过, 121 UT 全绿 |
| 6.6 | Review 修复 (R.1-R.4) | AuthInterceptor 去死代码, evictAllForTenant 锁外 close, 回调独立线程池 | 三项小修复 + 编译通过 |

**Phase 6 关键设计见 §6.15 (多数据源 + 授权白名单) 和 §6.16 (硬删双重认证).**

---

## 11. 部署方案

### 开发环境

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: connector_db
      POSTGRES_USER: connector_app
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports: ["5432:5432"]
    volumes: ["./sql/init.sql:/docker-entrypoint-initdb.d/init.sql"]

  redis:
    image: redis:7.4-alpine
    ports: ["6379:6379"]

  connector:
    build: .
    ports: ["8080:8080", "8081:8081"]
    environment:
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
      MCP_AUTH_TOKEN: ${MCP_AUTH_TOKEN}
      ADMIN_API_KEY: ${ADMIN_API_KEY}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
    depends_on: [postgres, redis]
```

### 生产环境要点

- **水平扩展**：无状态设计（状态在 DB + Redis），可多实例部署
- **Redis Pub/Sub** 保证多实例缓存一致性
- **优雅停机**：`server.shutdown=graceful`，等待进行中请求和异步任务
- **健康探针**：K8s 配置 liveness + readiness probe
- **密钥管理**：通过 K8s Secret 或 Vault 注入环境变量

---

## 12. 扩展点（未来版本）

### 12.1 代码层扩展

| 方向 | 说明 | 改动范围 |
|------|------|---------|
| 新增操作类型 | 如 syncInventory, generateReport | `action_template` 表插入记录, 不改代码 |
| ~~单租户多数据源~~ | ~~`tenant_config` 增加 `extra_data_sources JSONB`~~ | **已由 Phase 6 完成** (见 `tenant_datasource` 表) |
| 任务进度查询 | `async_task` 增加 `progress INT` 字段 | AsyncTaskService 扩展 |
| MCP Tool 热刷新 | 新增 action_template 不用重启即可注册为 MCP Tool | 监听模板变更事件 → 重建 ToolCallbackProvider |
| MCP STDIO 模式 | 本地调试用 | McpServerConfig 切换 |
| 消息队列替代 @Async | RabbitMQ / Redis Stream | AsyncTaskService 重构 |
| RBAC 权限 | Admin API 细粒度权限控制 (不同 admin 角色能做不同操作) | AuthenticationService 升级, 从单 key 改成 JWT/RBAC |
| Per-tenant MCP Token | 每商户独立 Token, 连接器从 Token 解出 tenantId, 覆盖 arguments | AuthenticationService + JWT 签发 (抵御 prompt injection) |
| Caffeine 请求合并 (buildAsync) | 防缓存击穿 | TwoLevelCacheManager 改造 (当前流量未到需要程度) |

### 12.2 运营侧扩展 (不改代码, 但工程化运营必需)

**Phase 7 候选**: 围绕"商户接入 / 运营效率"的工作,跟代码改动交织不多,但必须做。

#### 12.2.1 商户"一键开通"编排接口

**现状痛点**: 商户接入要调 5 个独立动作 (create tenant / create datasource / grant actions / 绑定渠道 / 冒烟),散在两个系统 (连接器 + OpenClaw),运营手动调容易:
- 漏步 (没建 ds 直接授权 → `DATASOURCE_NOT_FOUND`)
- 顺序错 (先授权后建 ds → 授权时校验 ds 存在,报 `PARAM_INVALID`)
- 错误时无法统一回滚

**设计**: 封装成单一接口 `POST /admin/tenants/provision`

```
POST /admin/tenants/provision
{
  "tenantId": "merchant_a",
  "tenantName": "商户 A",
  "tier": "STANDARD",
  "datasources": [
    { "dsName": "default", "accessType": "DB", "dbUrl": "...", "dbUsername": "...", "dbPassword": "..." }
  ],
  "grantDefaults": true,
  "channels": [
    { "channel": "wechat_corp", "corpId": "wwabc123" },
    { "channel": "dingtalk",    "corpId": "ding456" }
  ]
}
```

后端编排逻辑:
1. 开事务 (跨连接器 + OpenClaw 的分布式事务靠 Saga / TCC)
2. 依序执行 5 个动作
3. 任一失败整体 rollback (清理已建的 tenant / ds)
4. 数据源连通性预检 (试 `Connection.isValid(1)`, 失败直接返回, 不写入 DB)
5. 返回 `{ tenantId, createdDatasources, grantedActions, boundChannels }` 回执

**实施位置**: 放连接器里 (如果 OpenClaw 侧的身份绑定可通过内部 HTTP 触发) 或独立的"运营编排服务"。

#### 12.2.2 商户接入 SOP + 运营后台

**现状痛点**: 接入过程是"运营口头约定 + Postman 手动操作", 没有:
- 准入校验 (合同生效 / 付费到账 / 合规审核)
- 资源配额管理 (该商户给多少 QPS / 多少 ds / 多少 action)
- 邀请商户管理员自服务 (商户在自己那头完成渠道绑定)
- 接入回执 / 验收清单

**设计**: 独立的"运营后台" (不在连接器代码库内), 封装:
- 准入工作流 (CRM 联动)
- 资源配额 (按 tier 映射到连接器的 sys_dict 配置)
- 商户自助渠道绑定 (OpenClaw 提供商户账户 UI, 商户自己填企业微信 / 钉钉 corpid)
- 接入回执 PDF 生成, 给销售 / 商户 / 运营留档

**跟连接器的关系**: 运营后台**只是 admin-api-key 的消费方**之一, 连接器不感知。

#### 12.2.3 商户自助管控台 (面向商户)

**现状痛点**: 商户接入后, 无法:
- 查自己的调用量 / 成本
- 暂停 / 启用某个 action (比如某段时间不想让 AI 查订单, 只让查库存)
- 调整自己的 rate_limit_qps
- 自助修改数据源凭证 (比如定期轮换 DB 密码)

**设计**: 商户侧前端 + 后端 API (面向商户, 独立于 admin-api-key 体系):
- 商户 Token 只能操作自己的 tenantId 下的资源
- 只开放部分 Admin 能力 (查自己的 ds, 不能建新 tenant;改自己的密码, 不能改别人的)
- 所有商户操作进 audit_log, 带 `caller_identity=tenant_self:merchant_a`

**实施**: 需要引入 "商户维度的认证" — per-tenant API Key 或 OAuth2。这是 Phase 8+ 的重活儿。

#### 12.2.4 监控告警对接

**现状痛点**: Prometheus 指标有了, 但没有默认告警规则:
- 单租户 QPS 接近配额触发降级预警
- `connector.request.duration P99 > 1s` 持续 5 分钟
- 租户数据源连接失败率飙升
- 异步任务堆积超过阈值

**设计**: 写一套 Prometheus alert rules + Grafana dashboard 模板 (yaml),交付给 SRE 团队导入。

---

---

## 附录 A：数据库初始化脚本位置

```
sql/
├── 01_create_tables.sql       # DDL（6 张表，含 sys_dict）
├── 02_create_roles.sql        # connector_readonly 角色
├── 03_seed_templates.sql      # 预置操作模板（queryOrder 等）
└── 04_seed_dict.sql           # 字典表初始数据（全量业务参数默认值）
```

## 附录 A2：未来扩展点 — PREMIUM 租户 per-tenant tool schema

### 背景

Phase 7 期间为 PREMIUM 租户的 `custom_sql` 加了**占位符子集约束**：`custom_sql` 引用的 `:name` 必须 ⊆ `action_template.param_schema` 已声明字段。校验在 [TenantActionConfigService.validateCustomSql](../src/main/java/com/sea/star/ai/ec/enterprise/connector/service/TenantActionConfigService.java) 写入时拦，错误码 `CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM` (400)。

**根因**：MCP `tools/list` 返回的 inputSchema 是按 `action_template.param_schema` 全局生成的（[DynamicMcpToolProvider](../src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/DynamicMcpToolProvider.java) 启动时注册），所有租户看同一份。AI 永远不会传"模板没声明"的字段。

### 业务需求触发条件

当出现以下场景时启动该扩展点：

- 多个 PREMIUM 客户提出"我们的查询需要额外参数 X，希望 AI 能传"
- 业务上不愿意把 X 加到全局 `action_template.param_schema`（污染其他租户）
- 客户付费意愿支持 ~3-5d 工作量

### 方案 Y：per-tenant tool schema (custom_params 字段真正落地)

#### 数据模型（已就位）

```
tenant_action_config.custom_params  JSONB
-- 当前是预留字段, 写入但运行时不消费. 启用后存"租户级追加的 paramSchema 增量".
-- 例: {"region":{"type":"string","required":true},"date_from":{"type":"string"}}
```

#### Schema 合并语义

```
最终 inputSchema = action_template.param_schema  ∪  tenant_action_config.custom_params
                   (全局基础)                       (租户增量)
```

约束：
- 同名字段冲突时**租户增量优先**（让 PREMIUM 租户能"窄化"已有字段约束，但**不能放宽**——例如可以从 `optional` 改 `required`，但不能改类型）
- 子集校验放宽：`custom_sql` 占位符必须 ⊆ (template.paramSchema ∪ tenant.customParams)，而不是只 ⊆ template.paramSchema
- `BusinessExecutor` 的 `ParamValidator.validate` 也跟着升级：合并 schema 后做严校验（**取代当前的"customSqlMode 完全跳过 ParamValidator"**，让 PREMIUM 租户的 customParams 真正生效）

#### MCP per-session schema 暴露

最难的一环。Spring AI MCP 1.1.x 的 `ToolCallbackProvider` 是单例的、启动时注册全局 callbacks。要让不同 session 看到不同 schema 需要：

**路径 A**（推荐）：自实现 `ToolCallbackResolver` 或包装 `ToolCallbackProvider`
- 拦截 MCP `tools/list` 请求
- 从 `Authentication` / 请求头拿当前租户 ID（已有 `AuthInterceptor` + `TenantContext`，复用）
- 查 `tenant_action_config` 该租户所有授权 action，按 (action, customParams) 在内存合并 schema
- 动态构造 `ToolCallback` 列表返回

**路径 B**：MCP 协议层 `tools/list_changed` 通知
- 在租户授权变更时 push `notifications/tools/list_changed`
- 客户端重新拉 tools/list
- 但 Spring AI MCP 1.1.x 不一定开箱支持 notifications，要看协议层实现

#### 实施清单

| 项 | 估算 |
|---|---|
| 启用 `tenant_action_config.custom_params` 写入校验（JSON Schema 合法性） | 0.3d |
| `BusinessExecutor` 用合并 schema 校验 params（替代当前跳过逻辑） | 0.5d |
| 自实现 per-session `ToolCallbackProvider`（路径 A） | 1.5d |
| `tools/list_changed` 协议层推送（路径 B 可选） | 1d |
| 单测 + IT（多租户合并 schema、tools/list 按租户过滤） | 1d |
| 文档（CLAUDE.md / USAGE_GUIDE） | 0.2d |
| **合计** | **3.5–4.5d** |

#### 风险

- Spring AI MCP 内部 API 不稳定，跨版本升级可能要 patch 适配层
- per-session schema 增加内存占用（每租户每 action 一份合并 schema），需要缓存策略（Caffeine 按 tenantId TTL）
- AI 客户端兼容性：`tools/list_changed` 通知部分客户端不支持（Anthropic / OpenAI / Google 实现差异），要测

#### 替代方案（不推荐）

- **修改 `action_template.param_schema`**：把租户 customSql 占位符反向写入全局模板。Phase 7 讨论时已否决，详见 [CLAUDE.md §2](../CLAUDE.md) "PREMIUM custom_sql"段评估的 6 个隐患（多租户污染、增删不对称、跨租户类型冲突、MCP 热刷新难、责任边界破坏、多方言一致性约束被破坏）。

---

## 附录 B：安全检查清单

- [ ] `db_password_enc` / `api_token_enc` 加密存储
- [ ] `connector_readonly` 角色只有 SELECT 权限
- [ ] `SqlWhitelistValidator` 覆盖所有 custom_sql 入口
- [ ] 日志中不出现明文密码/token
- [ ] MCP 调用必须携带有效 token
- [ ] Admin API 必须携带 API Key
- [ ] `audit_log` 表不可 UPDATE/DELETE
- [ ] `statement_timeout` 已配置
