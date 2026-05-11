# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

Enterprise Connector 是一个 **MCP (Model Context Protocol) Server**，作为 OpenClaw + ClawBot 场景下的**多租户业务数据接入层**：商家通过微信自然语言指令 → AI 解析 → 本连接器对接商家 DB / HTTP API → 返回标准化结果。

**完整设计文档在 [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)**（~1400 行，含业务模型、DDL、架构决策、开发阶段）。在开始任何有实质性改动之前先读这个。

## 构建 & 运行

本项目已 Git 化（首个提交 `15a2713 初始提交: Enterprise Connector MCP Server (Phase 1-7)`），暂无 README.md（[HELP.md](HELP.md) 是 Spring Initializr 生成的脚手架，可忽略）。测试套件: 单元测试 + Testcontainers IT (~165 个 `@Test`, 单测/IT 约 3:1, 见 §[集成测试](#集成测试-phase-55))。

```bash
# JDK 必须是 21（系统默认可能是 17，需手工指定）
export JAVA_HOME="/d/soft/java/jdk-21.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

# 编译
./mvnw.cmd clean compile

# 启动应用（需要 PostgreSQL + Redis + 下面的环境变量）
./mvnw.cmd spring-boot:run

# 全量跑测试（IT 需要 Docker, Windows Docker Desktop 4.70 跑不了, 详见 §集成测试）
./mvnw.cmd test

# 只跑单测 (无 Docker 依赖, 开发机 daily 跑)
./mvnw.cmd test -Dtest='!*IntegrationTest'

# 只跑集成测试
./mvnw.cmd test -Dtest='*IntegrationTest'

# 单个测试类 / 单个方法
./mvnw.cmd test -Dtest='EncryptionUtilsTest'
./mvnw.cmd test -Dtest='EncryptionUtilsTest#roundTrip_basic'
```

启动应用前需要执行 DDL：
```bash
psql ... -f sql/01_create_tables.sql
psql ... -f sql/04_seed_dict.sql   # 必须！加载 sys_dict 初始值
```

本地起依赖（避免装本地 Postgres/Redis）: `docker compose up -d`（[docker-compose.yml](docker-compose.yml) 提供 PG + Redis；应用本身的镜像见 [Dockerfile](Dockerfile)）。

必需的环境变量：`DB_PASSWORD` / `REDIS_HOST` / `MCP_AUTH_TOKEN` / `ADMIN_API_KEY` / `ENCRYPTION_KEY`（Base64 编码的 32 字节密钥，用 `openssl rand -base64 32` 生成；缺失或格式错启动 fail-fast）。本地开发如果没设，`application-local.yaml` 里有 dev 占位 key 兜底，**切勿复制到生产**。

## 关键架构决策（非显而易见，必须先理解）

### 1. 三层配置体系（消除硬编码，运行时热更新）

所有"可调参数"按性质分到三个地方：

| 层级 | 存放 | 修改方式 | 示例 |
|------|------|---------|------|
| L1 字典表 `sys_dict` | DB + 内存 `ConcurrentHashMap` | Admin API CRUD + Redis Pub/Sub 广播 | 超时/重试/限流阈值/行数上限 |
| L2 `application.yaml` | 配置文件 | 改文件重启 | 连接池、线程池、端口、密钥 |
| L3 `BusinessConstants` | 代码常量 | 改代码重编译 | Redis key 前缀、Header 名、channel 名 |

业务代码读值永远走 `sysDictService.getInt("limit.absolute_max_rows", 10000)`（兜底默认值是最后防线，字典表正常加载时以字典表为准）。**不要在代码里直接写数值上限**，也不要往 `BusinessConstants` 里塞数字。

### 2. SQL 模板化（不允许租户随意写 SQL）+ 多方言适配 (Phase 7)

- `action_template` 表由**开发/运维团队维护**（经安全审核），租户通过 `tenant_action_config` 选择模板
- premium 租户可以覆盖 `custom_sql`，但必须过 [SqlWhitelistValidator](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/SqlWhitelistValidator.java)
- **兜底防线**：租户 DB 连接应用只读账号（`connector_readonly`），即使校验被绕过也写不进去
- `template.max_rows` 是**事后行数告警上限**（不再自动追加 LIMIT）：模板自己手写 LIMIT，超出 max_rows 仅记 WARN 日志；负数跳过校验（聚合查询）

**PREMIUM custom_sql 自由模式 + 占位符子集约束**：当 `tenant_action_config.custom_sql` 非空时，PREMIUM 租户的**校验和 padding 跟模板 paramSchema 解耦**，但**占位符集合必须 ⊆ 模板 paramSchema 已声明字段**：

- [BusinessExecutor.execute](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/BusinessExecutor.java) 跳过 `ParamValidator.validate(template.paramSchema, params)`——租户可自由传参（不强制 required/type/maxLength/pattern）
- [DatabaseAdapter.padMissingParamsFromSql](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/adapter/DatabaseAdapter.java) **从 custom_sql 文本自身**解析占位符给缺失 key 补 null（不是从模板 paramSchema），让 `(:foo IS NULL OR ...)` 这种"可选过滤"模式在租户 SQL 里也能用
- 信号通过 `AdapterRequest.customSqlMode` 标志传递（`ResolvedContext.isUsingCustomSql()` 决策）
- **写入子集校验**（[TenantActionConfigService.validateCustomSql](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/TenantActionConfigService.java)）：grant/update 时解析 custom_sql 占位符 → diff 模板 paramSchema 字段集 → 有超集字段直接拒（`CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM` 400）。**为什么这条约束**：MCP tool inputSchema 是按模板 paramSchema 全局生成的，AI 看不到 custom_sql 引入的新字段——租户能写但 AI 永远不会传，运行时被 padding 静默补 null 导致"等价 NULL"的怪查询。fail-fast 拦在写入路径。
- 占位符提取共用工具：[SqlWhitelistValidator.extractNamedParameters](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/SqlWhitelistValidator.java)（被写入校验和 padFromSql 复用）
- 安全模型不退化：SqlWhitelistValidator 仍按 TENANT_CUSTOM 严校验（长度/分号/AST/函数黑名单），NamedParameterJdbcTemplate prepared statement 防注入，只读账号兜底
- ✅ **Per-session tool schema 已落地（方案 Y 拦截器）**：靠 [McpToolsListInterceptFilter](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/McpToolsListInterceptFilter.java) 在 servlet filter 层短路 `tools/list` 请求自己构造响应，调 [PerTenantToolCallbackProvider](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/PerTenantToolCallbackProvider.java) 拿合并后的 schema。AI 视角同 action 不同租户**看到不同 schema**。
  - **关键限制（实测确认）**：spring-ai-mcp-server-webmvc 1.1.4 在**启动时调一次** `ToolCallbackProvider.getToolCallbacks()` 把结果注册到 `McpAsyncServer.tools` (CopyOnWriteArrayList)，运行时 `tools/list` 请求**直接读这个 list，不再回调 provider**——SSE / Streamable / Stateless 三种 transport 一样。所以单纯自实现 ToolCallbackProvider 不能 per-session，必须在协议层之前拦截
  - **拦截设计**：[McpToolsListInterceptFilter](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/McpToolsListInterceptFilter.java) 是 `@Order(HIGHEST_PRECEDENCE+100)` 的 OncePerRequestFilter，对 `POST /mcp`：缓存 body → 解析 JSON-RPC method → `tools/list` 自己处理（短路 chain）/ 其他 method 包装 request 透传给 spring-ai-mcp。短路路径里：复用 `AuthenticationService.verifyMcp` 验 token → 读 X-Tenant-Id 设 TenantContext → 调 provider → 序列化 ToolDefinition.inputSchema 嵌入响应 → 写 HTTP body
  - **PerTenantToolCallbackProvider** 还在但只被 Filter 调（spring-ai-mcp 启动时调那一次走 globalView，构造空 tools 列表给 server——这是无害的，因为运行时 server 端 list 不被读）
  - 缓存：Caffeine 30s TTL, key=tenantId（在 PerTenantToolCallbackProvider 内部）
  - 失效：`TemplateChangedEvent` (ActionTemplateService.create/update/delete/restore/purge) + `ActionAuthChangedEvent` (TenantActionConfigService.grant/update/revoke/grantAllDefaults) 触发, 全量清空
  - 子集校验放宽：custom_sql 占位符 ⊆ (template.paramSchema ∪ tenant.customParams), 不再只 ⊆ template.paramSchema
  - **未实施**：tools/call 时实时重校验（客户端拿过期 schema 防御）、tools/list_changed 推送（依赖 Spring AI MCP 1.1.x 协议层 API）

**降级语义（运行时双判断）**：[BusinessExecutor.resolveSql](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/BusinessExecutor.java) 和 `ResolvedContext.isUsingCustomSql()` 都按 `tier == PREMIUM && customSql 非空` 双判断决定是否走 custom_sql 路径。商户从 PREMIUM 降级到 STANDARD 时：
- `tenant_action_config.custom_sql` 字段**保留不删**（留着未来重新升级时自动恢复）
- 运行时**优雅 fallback** 到模板 SQL，不抛异常、业务不断
- 记 `log.info` 让运维可观测（"租户 X action=Y 已降级，customSql 保留但不生效"）
- 写入路径仍由 `TenantActionConfigService.validateCustomSql` 拦非 PREMIUM 写入，安全边界不变

**多方言适配（Phase 7）**: `access_type` 一个字段同时表达"接入方式 + 方言"，枚举值 `POSTGRES / MYSQL / ORACLE / SQLSERVER / API`。**没有 `db_type` 列**——之前讨论过加列，最终决定合并到 access_type，schema 改动最小、唯一索引 `(action, access_type)` 自动获得方言维度。
- 同一个 action 可在 `action_template` 中有多行（PG 版 + MySQL 版 + ...），运行时 [BusinessExecutor](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/BusinessExecutor.java) 按 `template.accessType == datasource.accessType` 校验匹配（**复用现有第 160 行的校验**，不一致即拒）
- AI 视角同 action 是**一个** MCP tool —— [DynamicMcpToolProvider](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/DynamicMcpToolProvider.java) 按 action 字段去重，元数据取首条
- 多方言行的 `description` / `param_schema` / `datasource_name` 必须一致 —— [AdminTemplateController](src/main/java/com/sea/star/ai/ec/enterprise/connector/controller/AdminTemplateController.java) 写入时强校验，不一致拒绝（`INCONSISTENT_TEMPLATE_FAMILY`）
- `SqlWhitelistValidator.validate(sql, source)` 区分来源：`TEMPLATE`（运维预审）跳过 AST 解析（避免 JSqlParser 误伤合法 MySQL/Oracle 方言）只过函数黑名单；`TENANT_CUSTOM`（租户自定义）严校验
- pom 已加 `mysql-connector-j` + `mssql-jdbc`；**Oracle 驱动 ojdbc11 未加**（包 ~7MB + 镜像 ~2GB 性价比低），ORACLE 枚举值就位但实际写入会在 HikariCP 建池时 `ClassNotFoundException` 显式失败
- HikariCP 用默认的 `Connection.isValid()` 跨方言通用，不按方言设置 `connectionTestQuery`

### 3. 多数据源 + DataSource 池 (Phase 6)

一个租户可以挂多个数据源 (订单库 / 库存库 / CRM API 等), 通过逻辑 `ds_name` 寻址:
- `tenant_datasource` 表按复合主键 `(tenant_id, ds_name)` 存储物理连接信息
- `action_template.datasource_name` 声明"这个 action 打哪个 ds" (默认 `default`)
- `tenant_action_config.datasource_name_override` 可对特定租户覆盖默认

[TenantDataSourceManager](src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/datasource/TenantDataSourceManager.java) 用 `LinkedHashMap(accessOrder=true)` 实现 LRU, key 是 `tenantId:dsName`, 配置 `connector.datasource-pool.max-pools` (默认 300) 控制上限, 超过就淘汰最久未用的并 `close()`。

**为什么需要 LRU + 上限**: 300 个池 × 5 连接 = 1500 连接 worst case, 需要 PgBouncer 或调大 PG `max_connections`。不加 LRU 则无界增长。

**并发策略**: 快路径 (命中) 在 synchronized 内只做一次 `map.get`; 慢路径 (构建新池) 在锁外执行 TenantDatasourceService 查询 + HikariCP 初始化, 再加锁决策去重, 避免阻塞其他 (租户, ds)。

### 4. 两级缓存 + Pub/Sub 失效广播

`TwoLevelCacheManager` = Caffeine(L1) + Redis(L2)。**写入/失效时必须同时广播 Pub/Sub 消息**（channel `cache:invalidate`），消息格式 `"{cacheName}|{key}"`，其他实例收到后清本地 L1。这是多实例部署的一致性保证。

类似的，`sys_dict` 的更新也走 Redis Pub/Sub（channel `dict:refresh`）。

**本进程内的另一半**：Admin 写操作 (改 `tenant_config` / `tenant_datasource`) 还会发 Spring `ApplicationEvent` ([infrastructure/event/](src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/event/) 下的 `TenantConfigChangedEvent` / `TenantDatasourceChangedEvent`)，用于本 JVM 内的连接池/缓存即时失效；跨实例失效靠 Redis Pub/Sub。两套机制各司其职，**写新 Admin 接口时两个都不能漏**。

### 5. 复合主键 `TenantActionConfig` 的特殊处理

DB 主键是 `(tenant_id, action)` 复合主键，两个字段都标 `@Id(keyType=KeyType.None)`。Flex `BaseMapper.selectOneById(Serializable)` 只接单 key，所以按复合键查询/删除统一走 QueryWrapper 里的 `findByTenantAndAction` / `deleteByTenantAndAction` —— 详见 [TenantActionConfigMapper](src/main/java/com/sea/star/ai/ec/enterprise/connector/domain/mapper/TenantActionConfigMapper.java)。**不要**用 `mapper.deleteById(tenantId)` 之类的单参调用，会编译失败或语义错误。

> 历史注记：早期用 MyBatis-Plus，因不原生支持复合主键而迁到 MyBatis-Flex。迁移背景保留在 `feedback_orm_choice` 记忆里。

### 6. 异步上下文传递

`TenantContext` 和 MDC `traceId` 通过 ThreadLocal 存储。**所有 `@Async` 线程池必须用 `TaskDecorator` 传递** —— 已由 [AsyncConfig](src/main/java/com/sea/star/ai/ec/enterprise/connector/config/AsyncConfig.java) 的 `TenantContextTaskDecorator` 完成。绝对不要直接用默认线程池跑业务任务, 租户上下文 / traceId 会丢。

### 7. 必要授权白名单 + 软删 + 双重认证物理删 (Phase 6)

**授权**: `tenant_action_config` 升级为白名单。调用时先查 (tenantId, action), 没行 → `ACTION_NOT_AUTHORIZED` (403)。grant/revoke 走 `/admin/tenants/{tid}/actions/{action}/grant` (RPC 风格动词)。

**软删**: `tenant_config` / `tenant_datasource` / `action_template` / `sys_dict` 有 `deleted BOOLEAN` 字段, Flex `@Column(isLogicDelete=true)` 自动过滤。`tenant_action_config` / `async_task` / `audit_log` 物理删 (前者撤销即清, 后两者按 TTL / append-only)。

**软删语义(每表独立, 无级联)**:
- 软删 `tenant_config` **只动自己**, 不级联下属。业务调用链第一步 `TenantConfigService.getConfig(tenantId)` 会被 Flex 自动过滤成"查不到",抛 `TENANT_NOT_FOUND` → 业务被拒。不需要额外动 `tenant_datasource` / `tenant_action_config`。
- 软删 `tenant_datasource` 只动自己, BusinessExecutor 走到 `TenantDatasourceService.getDatasource` 会抛 `DATASOURCE_NOT_FOUND`。
- restore 只恢复自身表。各表软删/恢复互不干扰, 语义独立清晰。

**硬删 (Purge, 不可逆)**: **级联清理**是硬删独有的行为。`DELETE /admin/tenants/{id}/purge` 同事务清 `tenant_datasource` + `tenant_action_config` + `tenant_config` 本身。`audit_log` 保留作合规证据。

**物理删端点二级认证**: `/purge` 端点要求在 `X-API-Key` 基础上额外传 `X-Purge-Api-Key` ([AuthenticationService.verifyPurge](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/AuthenticationService.java))。未配置 key 则全部拒绝 (保守默认)。两个 key **独立配置**, admin 日常运维拿不到 purge key。

**状态守卫**: [TenantStatusGuard](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/TenantStatusGuard.java) 在 MCP 入口 fail-fast 检查 (unless admin operation), 租户禁用/软删时不进入 rate limiter, 不污染限流状态。

## 技术栈要点

- **ORM 用 MyBatis-Flex 1.11.6，绝对不要引入 JPA/Hibernate 或回退到 MyBatis-Plus**（对应 `feedback_orm_choice` 记忆）
  - 实体注解：`@Table` / `@Id` / `@Column`（**不是** MP 的 `@TableName` / `@TableId` / `@TableField`，也不是 JPA 的 `@Entity`）
  - BaseMapper：`com.mybatisflex.core.BaseMapper`
  - 常用 API：`selectOneById(id)` / `update(entity)` / `deleteById(id)` / `selectCountByQuery(qw)` / `paginate(page, size, qw)` / `selectAll()`
  - QueryWrapper：`QueryWrapper.create()` 构造（**非泛型**），`.where(TABLE_DEF.COL.eq(x))` 用 APT 生成的 TableDef 做类型安全查询
  - 自动填充 `createdAt` / `updatedAt` 走 [AutoFillListener](src/main/java/com/sea/star/ai/ec/enterprise/connector/config/AutoFillListener.java)，通过 `FlexGlobalConfig.registerInsertListener/registerUpdateListener` 按实体注册——不要在实体字段上写 `@TableField(fill=...)`（那是 MP 语法，Flex 不认）
  - APT：`mybatis-flex-processor` 在 `maven-compiler-plugin.annotationProcessorPaths` 里**必须放在 Lombok 之后**，否则生成的 TableDef 字段为空
  - pom 用 `mybatis-flex-spring-boot3-starter` + `spring-boot-starter-jdbc`
- Spring Boot **3.4.5** + JDK 21，Jakarta namespace（不是 javax）。之前试过 Boot 4.0.5，但 Spring AI MCP Starter / Spring Data Redis 等都没跟上 Boot 4，遂回到 3.4.x
- **MCP 协议接入** 用 Spring AI `spring-ai-starter-mcp-server-webmvc` 1.1.4
  - Tool 定义**从 `action_template` 表动态读取**：[DynamicMcpToolProvider](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/DynamicMcpToolProvider.java) 启动时枚举所有 enabled 模板注册为 `ToolCallback`
  - Tool 执行路由到 [McpToolService](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/McpToolService.java)（限流/幂等/租户上下文/审计全套）再下沉到 `BusinessExecutor`
  - MCP 端点配置：**Streamable HTTP** 单端点 `POST /mcp`（`application.yaml` 的 `spring.ai.mcp.server.protocol: STREAMABLE`，MCP 2025-03-26 标准）。AuthInterceptor + SecurityConfig 同时覆盖了 `/mcp` 和 `/mcp/**` 两条路径——`/mcp` 自身没 trailing slash，必须显式列出，否则 AntPathMatcher 不匹配会**绕过认证**。Bearer Token 校验通过后，可选 `X-Tenant-Id` 头让 PerTenantToolCallbackProvider 在 tools/list 时按租户合并 schema
  - 不再有自己写的 `McpController`（之前占位 JSON-RPC REST 已删除）
  - 但**有** [McpWebhookController](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/McpWebhookController.java)：处理 `POST /internal/callback`，是异步任务自回调入口（AsyncTaskService 完成时回 POST 给 callback_url），**不走** MCP Bearer Token，改用 `X-Callback-Secret` 头 + 常量时间比较。`/internal/**` 前缀刻意避开 `/mcp/**` 拦截器规则
  - 新增 action_template / 改 customParams **不再需要重启**：[PerTenantToolCallbackProvider](src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/PerTenantToolCallbackProvider.java) 监听 `TemplateChangedEvent` / `ActionAuthChangedEvent` 立即清缓存，30s 内任意 MCP 客户端重拉 `tools/list` 看到新 schema
- 敏感字段加密：AES-256-GCM，[`EncryptionUtils`](src/main/java/com/sea/star/ai/ec/enterprise/connector/util/EncryptionUtils.java)
  - 密文格式：`Base64(IV[12] || ciphertext || GCM_TAG[16])`
  - 启动时密钥缺失/格式错 → fail-fast 阻止应用启动
  - 解密时 `AEADBadTagException` 单独捕获并以 `SecurityException` 上抛（篡改告警信号）
- 其他安全约定：
  - Redis 序列化：`RedisConfig` 使用 `BasicPolymorphicTypeValidator` 白名单，仅允许项目内包 + `java.util/lang/time/math`
  - `HttpApiAdapter.substitutePathVars` 必须 URL 编码参数值，`renderBody` 走 JSON 树结构化替换（不是字符串 replace）——防注入
  - HikariCP 密码：`new HikariDataSource(hc)` 后立即 `hc.setPassword(null)` 清空配置对象
  - 日志脱敏：[MaskingConverter](src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/logging/MaskingConverter.java) 是 logback 的 conversion word，在 [logback-spring.xml](src/main/resources/logback-spring.xml) 模式串里配置，输出阶段对密码/token/手机号等敏感字段正则脱敏。新增敏感字段类型时改这里，**不要在业务代码里手工 `.replace()`**

## 开发阶段状态

参见 `docs/DEVELOPMENT_PLAN.md` §10：

- ✅ **Phase 1**（骨架 + 数据层）：实体/Mapper/异常体系/SysDictService/工具类
- ✅ **Phase 2**（核心服务层）：两级缓存、TenantDataSourceManager、Database/HttpApi 适配器、BusinessExecutor 同步路径
- ✅ **Phase 3**（异步 + 安全 + 限流）：`AsyncTaskService`、`SqlWhitelistValidator`、`AuthInterceptor`、Resilience4j 限流/熔断
- ✅ **Phase 4**（MCP 接入 + Admin API）：`McpToolService`、Admin Controllers、**Spring AI MCP 动态 tool 注册**
- ✅ **Phase 5**（可观测 + 测试 + 部署）：结构化日志（含 `MaskingConverter` 脱敏）、ConnectorMetrics（Prometheus）、单元测试、Testcontainers IT（见"集成测试"一节）
- ✅ **Phase 6**（多数据源 + 授权白名单 + 软删/Purge）：[TenantDataSourceManager](src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/datasource/TenantDataSourceManager.java) LRU 池、`tenant_action_config` 白名单、软删 + 二级认证 Purge、[TenantStatusGuard](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/TenantStatusGuard.java)、[AsyncTaskCleanupJob](src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/scheduler/AsyncTaskCleanupJob.java) TTL 清理（详见 §3 + §7）
- ✅ **Phase 7**（多方言适配）：`AccessType` 枚举扩展为 `POSTGRES/MYSQL/ORACLE/SQLSERVER/API`、pom 加 mysql/mssql 驱动、`DatabaseAdapter` 删除自动 LIMIT 改成事后告警、`SqlWhitelistValidator.validate(sql, Source)` 按来源选校验强度、`AdminTemplateController` 多方言行一致性强校验、`DynamicMcpToolProvider` 按 action 去重（详见 §2）

**在跨 Phase 前检查 `DEVELOPMENT_PLAN.md` 对应任务表，遵循已规划的类名和分层边界。**

## 约定与陷阱

- **JAVA_HOME**：系统默认是 JDK 17，必须手工切到 JDK 21 才能编译
- **文件注释语言**：业务注释用中文，对外错误消息用中文；与 Phase 2 之前的代码保持一致
- **Lombok**：实体 + DTO 必须加 `@ToString(exclude=...)` 排除敏感字段（密码/token/密文）
- **枚举入 DB**：应用层存 `enum`，但用 `name()` 比较时必须先 `Objects.requireNonNull`（已在 Mapper 中做过）
- **traceId**：`TraceIdFilter` 最高优先级拦截所有请求，从 `X-Trace-Id` 头或 UUID 生成写入 MDC
- **编码警告**：Maven 在 Windows 控制台输出带中文乱码，不是错误，`BUILD SUCCESS` 才是判断依据

## 集成测试 (Phase 5.5)

集成测试用 Testcontainers 启真实的 PostgreSQL 16 + Redis 7 容器。代码全在 [src/test/java/.../integration/](src/test/java/com/sea/star/ai/ec/enterprise/connector/integration/) 和 [domain/mapper/*IntegrationTest.java](src/test/java/com/sea/star/ai/ec/enterprise/connector/domain/mapper/)，基类 `AbstractIntegrationTest` 通过 `@DynamicPropertySource` 把容器端口注入到 Spring 的 `spring.datasource.url` 等属性。

**JDBC URL 末尾必须挂 `?stringtype=unspecified`**，否则 MyBatis 传 `String` 给 JSONB 列会被 PG 拒绝 "character varying cannot be cast to jsonb"。基类已处理。

**本地运行要求**：Testcontainers 需要一个 docker-java 兼容的 Docker daemon。已验证会挂的环境：
- ❌ **Docker Desktop 4.70（Windows）**：所有 pipe（`docker_engine` / `dockerDesktopLinuxEngine` / `docker_engine_linux`）和 TCP 2375 都会被 Docker Desktop 的 CLI-auth 代理拦截，返回一个 stub Info 响应（标签 `com.docker.desktop.address=npipe://\\.\pipe\docker_cli`），docker-java 认不出会 `BadRequestException` 启动失败。docker CLI 自己有 handshake 协议能绕过，docker-java 没有。已尝试并确认无解的规避：切 npipe、切 TCP、关 ECI、关 containerd image store、升 Testcontainers 1.20→1.21。
- ✅ **Linux / macOS**：unix socket 直连，Testcontainers 默认就能用。
- ✅ **WSL2 里装 Docker CE**（绕开 Docker Desktop）：在 WSL2 shell 里跑 `./mvnw test`。
- ✅ **CI runner**（GitHub Actions、GitLab CI 的 Linux agent）。

因此 Phase 5.5 的 IT 测试代码已完成并在本地 Windows Docker Desktop 4.70 下不能跑，通过 **CI 验证**或**在 WSL2 / macOS / Linux 本机跑**确认。

**诊断开关**：Docker 连通性怀疑时把 [src/test/resources/logback-test.xml](src/test/resources/logback-test.xml) 里 `<logger name="org.testcontainers">` 调成 DEBUG，会打印 Testcontainers 依次尝试的 strategy 名字和具体失败原因。
