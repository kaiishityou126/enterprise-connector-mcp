# Enterprise Connector

> 一个生产级的多租户 **MCP (Model Context Protocol) Server**，把 AI Agent 安全、隔离、可观测地接入企业业务数据（DB / HTTP API）。
>
> **Spring Boot 3.4 · JDK 21 · Spring AI MCP 1.1.4 · MyBatis-Flex · PostgreSQL · Redis**

---

## 这个项目解决什么问题

让 AI Agent 用自然语言查企业业务数据时，**不能让它直连数据库** —— 注入、越权、删库、跨租户泄漏，每一条都是事故。

本项目作为 AI 与企业数据之间的**接入层**，提供：

- **协议标准化**：用 MCP 把异构的企业数据源统一暴露为 AI 可调用的 tool
- **多租户隔离**：DataSource、限流器、缓存 key、SQL 模板权限、tool schema 全部按 `tenantId` 分片
- **安全兜底**：模板化 SQL + 白名单 AST 校验 + 只读 DB 账号三层防线
- **运行时可调**：超时/限流/行数上限走字典表 + Pub/Sub 热更新，**改值不重启**

---

## 技术亮点（按"工程难度"排序）

### 1. Per-session Tool Schema —— Spring AI MCP 启动快照机制的绕行方案

**问题**：spring-ai-mcp-server-webmvc 1.1.4 在启动时调用一次 `ToolCallbackProvider.getToolCallbacks()` 把结果**注册到 `McpAsyncServer.tools` (CopyOnWriteArrayList)**，运行时 `tools/list` 请求**直接读这个 list，不再回调 provider** —— SSE / Streamable / Stateless 三种 transport 一致。这意味着自实现 `ToolCallbackProvider` 无法做 per-tenant schema。

**方案**：在 servlet filter 层（协议层之前）拦截 `tools/list` 请求，自己构造响应短路掉 spring-ai-mcp 的处理链。

参见 [`McpToolsListInterceptFilter`](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/McpToolsListInterceptFilter.java)：
- `OncePerRequestFilter` + `@Order(HIGHEST_PRECEDENCE+100)`
- 缓存 body → 解析 JSON-RPC method → `tools/list` 自己处理（短路 chain）/ 其他透传
- 短路路径：复用 `AuthenticationService.verifyMcp` 验 token → 读 `X-Tenant-Id` 设 `TenantContext` → 调 `PerTenantToolCallbackProvider` 拿合并后的 schema → 序列化 `ToolDefinition.inputSchema` 嵌入响应
- 缓存：Caffeine 30s TTL，key=tenantId
- 失效：监听 `TemplateChangedEvent` / `ActionAuthChangedEvent` 全量清空

### 2. PREMIUM 自由 SQL + 占位符子集校验

**痛点**：高级租户可以覆盖模板写自定义 SQL，但 AI 看到的 inputSchema 是按模板 paramSchema 全局生成的。如果租户 SQL 引入了模板里没声明的占位符，AI 永远不会传 → 运行时被 padding 静默补 null → 怪查询。

**方案**：
- 写入路径在 [`TenantActionConfigService.validateCustomSql`](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/TenantActionConfigService.java) 解析 custom_sql 占位符 → diff 模板 paramSchema 字段集 → 有超集字段直接拒（`CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM` 400）—— **fail-fast 拦在写入路径**
- 运行时 [`BusinessExecutor`](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/BusinessExecutor.java) 跳过 paramSchema 强校验，让租户自由传参
- [`DatabaseAdapter.padMissingParamsFromSql`](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/adapter/DatabaseAdapter.java) **从 SQL 文本自身**解析占位符给缺失 key 补 null，让 `(:foo IS NULL OR ...)` 这种"可选过滤"模式可用
- 安全模型不退化：SqlWhitelistValidator 仍按 `TENANT_CUSTOM` 严校验（长度/分号/AST/函数黑名单），prepared statement 防注入，只读账号兜底

### 3. 多租户 DataSource 池 + LRU 淘汰

[`TenantDataSourceManager`](src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/datasource/TenantDataSourceManager.java) 用 `LinkedHashMap(accessOrder=true)` 实现 LRU，key=`tenantId:dsName`，配置 `connector.datasource-pool.max-pools`（默认 300）控制上限，超过淘汰最久未用并 `close()`。

**为什么需要 LRU**：300 池 × 5 连接 = 1500 连接 worst case，需要 PgBouncer 或调大 PG `max_connections`。不加 LRU 则无界增长。

**并发策略**：快路径（命中）在 synchronized 内只做一次 `map.get`；慢路径（构建新池）在锁外执行查询 + HikariCP 初始化，再加锁决策去重，避免阻塞其他 (租户, ds)。

### 4. 三层配置体系（消除硬编码）

| 层级 | 存放 | 修改方式 | 示例 |
|------|------|---------|------|
| L1 字典表 `sys_dict` | DB + 内存 ConcurrentHashMap | Admin API CRUD + Redis Pub/Sub 广播 | 超时/重试/限流阈值/行数上限 |
| L2 `application.yaml` | 配置文件 | 改文件重启 | 连接池、线程池、端口、密钥 |
| L3 `BusinessConstants` | 代码常量 | 改代码重编译 | Redis key 前缀、Header 名 |

业务代码读值永远走 `sysDictService.getInt(key, default)`。**不能往代码常量里塞数字**。

### 5. 两级缓存 + Pub/Sub 失效广播

`TwoLevelCacheManager` = Caffeine(L1) + Redis(L2)。写入/失效时同时广播 channel `cache:invalidate`，消息格式 `{cacheName}|{key}`，其他实例收到后清本地 L1。多实例部署一致性保证。

`sys_dict` 走 channel `dict:refresh`。Admin 写操作还会发 Spring `ApplicationEvent`，本 JVM 内连接池/缓存即时失效；跨实例靠 Redis Pub/Sub。**两套机制各司其职**。

### 6. 多方言适配（不加 db_type 列）

`access_type` 一个字段同时表达"接入方式 + 方言"：`POSTGRES / MYSQL / ORACLE / SQLSERVER / API`。
- 同一 action 在 `action_template` 中可有多行（PG 版 + MySQL 版 + ...），运行时按 `template.accessType == datasource.accessType` 匹配
- AI 视角同 action 是一个 MCP tool，[`DynamicMcpToolProvider`](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/DynamicMcpToolProvider.java) 按 action 字段去重
- 多方言行的 description / param_schema / datasource_name 必须一致（写入强校验，不一致拒绝）
- `SqlWhitelistValidator.validate(sql, source)`：`TEMPLATE`（运维预审）跳过 AST 解析避免 JSqlParser 误伤合法方言，`TENANT_CUSTOM` 严校验

### 7. 软删 + 二级认证 Purge + 状态守卫

- **软删**：`tenant_config` / `tenant_datasource` / `action_template` / `sys_dict` 有 `deleted` 字段，Flex 自动过滤；**每表独立无级联**
- **物理删（Purge）**：在 `X-API-Key` 基础上额外要求 `X-Purge-Api-Key`，两 key 独立配置，admin 日常运维拿不到 purge key
- **状态守卫**：[`TenantStatusGuard`](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/TenantStatusGuard.java) 在 MCP 入口 fail-fast 检查，租户禁用/软删时不进入 rate limiter，不污染限流状态

### 8. 凭证加密 + 启动 fail-fast

- AES-256-GCM，密文格式 `Base64(IV[12] || ciphertext || GCM_TAG[16])`
- 启动时密钥缺失/格式错 → fail-fast 阻止应用启动
- 解密时 `AEADBadTagException` 单独捕获并以 `SecurityException` 上抛（篡改告警信号）
- HikariCP 密码：`new HikariDataSource(hc)` 后立即 `hc.setPassword(null)` 清空配置对象
- 日志脱敏：[`MaskingConverter`](src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/logging/MaskingConverter.java) 是 logback conversion word，输出阶段对密码/token/手机号等正则脱敏

---

## 架构概览

```
                              ┌──────────────────────────┐
微信用户 → OpenClaw / ClawBot │  Enterprise Connector    │
   (AI 意图解析)              │  (本项目, MCP Server)     │
        │                     │                          │
        │ POST /mcp           │  ┌──────────────────┐   │     ┌─────────────┐
        │ Streamable HTTP     │  │ McpToolsList     │   │     │  租户 A DB  │
        │ X-Tenant-Id: A      │→ │ InterceptFilter  │   │  →  │ (只读账号)  │
        ├─────────────────────┤  │ (per-session     │   │     └─────────────┘
        │ Authorization Bearer│  │  schema)         │   │
        │                     │  └────────┬─────────┘   │     ┌─────────────┐
        │                     │           ↓             │  →  │  租户 A API │
        │                     │  ┌──────────────────┐   │     └─────────────┘
        │                     │  │ McpToolService   │   │
        │                     │  │  限流/幂等/审计  │   │     ┌─────────────┐
        │                     │  └────────┬─────────┘   │     │ Audit Log   │
        │                     │           ↓             │  →  │ (Append)    │
        │                     │  ┌──────────────────┐   │     └─────────────┘
        │                     │  │ BusinessExecutor │   │
        │                     │  │ ┌──────┬───────┐ │   │     ┌─────────────┐
        │                     │  │ │ DB   │ HTTP  │ │   │     │ PG + Redis  │
        │                     │  │ │ Adpt │ Adpt  │ │   │←──→ │ (元数据/缓存)│
        │                     │  │ └──────┴───────┘ │   │     └─────────────┘
        │                     │  └──────────────────┘   │
        │                     └──────────────────────────┘
        ↓
   多个租户 (按 tenantId 路由到对应物理资源, 不共享)
```

完整架构决策见 [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) 和 [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)。

---

## 技术栈

| 类别 | 选型 | 理由 |
|------|------|------|
| 语言/框架 | Java 21 + Spring Boot 3.4.5 | 虚拟线程 + Jakarta，现代企业 Java 标准栈 |
| MCP 协议 | Spring AI 1.1.4 (`spring-ai-starter-mcp-server-webmvc`) | 官方实现，Streamable HTTP 单端点 |
| ORM | MyBatis-Flex 1.11.6 | 原生复合主键 + APT 类型安全 + 软删自动过滤 |
| 数据库 | PostgreSQL 16 (元数据 + JSONB) | 跨方言适配支持 MySQL / SQL Server |
| 缓存 | Caffeine (L1) + Redis (L2) | 多实例一致性 + 本地零延迟 |
| 限流/熔断 | Resilience4j 2.3.0 | per-tenant 限流，阈值热更新 |
| SQL 安全 | JSqlParser 5.1 AST + 自维护函数黑名单 | TENANT_CUSTOM 来源严校验 |
| 加密 | AES-256-GCM | 凭证字段，启动 fail-fast |
| 可观测 | Micrometer + Prometheus + Logback JSON | 结构化日志 + MDC traceId |
| 测试 | JUnit 5 + Testcontainers | 165+ 测试，单测/IT ≈ 3:1 |

---

## 快速开始

### 前置条件

- JDK 21（必须，JDK 17 编译失败）
- Docker（用于本地起 PG + Redis）
- Maven Wrapper 已包含

### 一键起依赖 + 应用

```bash
# 1. 启 PG + Redis
docker compose up -d

# 2. 加载 DDL + 种子字典
psql -h localhost -U postgres -f sql/01_create_tables.sql
psql -h localhost -U postgres -f sql/04_seed_dict.sql

# 3. 设环境变量
export DB_PASSWORD=postgres
export REDIS_HOST=localhost
export MCP_AUTH_TOKEN=$(openssl rand -base64 32)
export ADMIN_API_KEY=$(openssl rand -base64 32)
export ENCRYPTION_KEY=$(openssl rand -base64 32)  # AES-256, 必须 32 字节

# 4. JDK 21
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"

# 5. 启动
./mvnw spring-boot:run
```

### 跑测试

```bash
# 全量（需 Docker）
./mvnw test

# 只单测
./mvnw test -Dtest='!*IntegrationTest'

# 只 IT
./mvnw test -Dtest='*IntegrationTest'
```

> ⚠️ Windows Docker Desktop 4.70 跑不了 IT（CLI-auth 代理问题），请在 WSL2 / Linux / macOS / CI 上跑。详见 [CLAUDE.md](CLAUDE.md) "集成测试"章节。

### 访问 MCP

```bash
# tools/list (per-tenant schema)
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $MCP_AUTH_TOKEN" \
  -H "X-Tenant-Id: tenant-001" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

完整端到端示例见 [docs/USAGE_GUIDE.md](docs/USAGE_GUIDE.md) 和 [docs/postman](docs/postman)。

---

## 文档导航

| 文件 | 用途 |
|------|------|
| [CLAUDE.md](CLAUDE.md) | 关键架构决策 + 陷阱 + 阶段状态（最权威，~600 行）|
| [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) | 面向演讲/面试的概览 |
| [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) | 完整设计文档（业务模型/DDL/分阶段任务，~1400 行）|
| [docs/USAGE_GUIDE.md](docs/USAGE_GUIDE.md) | 端到端使用手册 |

---

## 开发阶段

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | 骨架 + 数据层（实体/Mapper/异常体系/SysDictService）| ✅ |
| 2 | 核心服务层（两级缓存 / DataSource 池 / DB+HTTP 适配器 / 同步执行）| ✅ |
| 3 | 异步 + 安全 + 限流（AsyncTaskService / SqlWhitelist / Resilience4j）| ✅ |
| 4 | MCP 接入 + Admin API（动态 tool 注册 / 6 个 Admin Controller）| ✅ |
| 5 | 可观测 + 测试 + 部署（结构化日志 / Prometheus / Testcontainers IT）| ✅ |
| 6 | 多数据源 + 授权白名单 + 软删/Purge | ✅ |
| 7 | 多方言适配（POSTGRES/MYSQL/ORACLE/SQLSERVER）| ✅ |

165+ 个 `@Test`，单测 / IT ≈ 3:1。

---

## 状态

PoC / 个人作品集项目。**未在生产环境运行**，但所有架构决策都按生产标准设计（可观测、安全、合规、可扩展）。代码质量、测试覆盖、文档完整度可以作为企业 AI 落地的参考实现。

如果你在做：
- 企业级 AI Agent 接入数据
- 多租户 SaaS 的隔离设计
- Spring AI MCP 1.1.x 的工程化实践
- 模板化 SQL + AI 安全方案

—— 欢迎提 issue 交流。

