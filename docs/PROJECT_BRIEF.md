# Enterprise Connector 项目说明书

> 面向演讲 / 面试 / 新人 onboarding 的一体化概览文档。包含业务定位、技术栈、架构决策、使用方式，以及开发过程中沉淀的设计取舍和排错故事。

---

## 一、一句话概述

**Enterprise Connector 是一个面向多租户的 MCP (Model Context Protocol) Server，定位为 AI 业务接入层：把"商家自然语言指令 → AI 理解 → 企业数据 (DB / HTTP API) → 标准化结果"这条链路标准化、隔离化、可观测化。**

它不是"又一个 CRUD 后台"。真正要解决的是三件事：

1. **让 AI 能以协议标准 (MCP)、不侵入业务代码的方式调用企业数据**
2. **让多个租户共享同一套连接器时，在资源、权限、限流、审计四个维度做到真正隔离**
3. **让商家 / 运营团队在不重启、不改代码的前提下调整业务参数 (超时、限流、SQL 白名单、模板等)**

---

## 二、业务场景与问题

### 场景链路
```
微信用户 → OpenClaw / ClawBot (AI 意图解析)
         ↓
         MCP Tool Call (POST /mcp, Streamable HTTP)
         ↓
         Enterprise Connector
         ↓
     ┌─────────────┬─────────────┐
     ↓             ↓             ↓
  租户 DB      租户 HTTP API   审计日志
```

### 为什么"连接器"要独立服务
- **AI 不能直连租户 DB**：凭证、只读账号、SQL 注入防线都必须在连接器做
- **租户之间必须隔离**：DataSource / 限流器 / 缓存 key 都按 tenantId 分片
- **对 AI 是单一入口**：MCP 协议统一封装了 1000+ 不同商家 DB schema 的异构性

### 能力矩阵

| 能力 | 用哪个机制 |
|------|------|
| AI → 企业数据 | Spring AI MCP Server (Streamable HTTP 单端点 `/mcp`) + per-tenant tool schema 拦截 |
| 租户隔离 | TenantDataSourceManager (LRU 池, key=`tenantId:dsName`) + TenantRateLimiter + TenantStatusGuard |
| SQL 模板化 | action_template 表 + 多方言 + JSqlParser AST 校验（按 Source 区分强度） |
| 授权与生命周期 | tenant_action_config 白名单 + 软删 + Purge 双重认证 |
| 长任务 | AsyncTaskService + PostgreSQL JSONB 去重 + 回调 |
| 多实例一致性 | Caffeine L1 + Redis L2 + Pub/Sub 失效广播 |
| 凭证安全 | AES-256-GCM 加密 + fail-fast 启动校验 |
| 可观测 | Micrometer → Prometheus + Logback JSON + MDC traceId |
| 热配置 | sys_dict 字典表 + Redis Pub/Sub 刷新 |

---

## 三、架构关键设计决策

以下都是**必须读多个文件才能推断出来的非显而易见设计**，也是面试时最能体现"真实工程经验"的取舍。

### 1. 三层配置体系（消除硬编码，部分参数可运行时热更新）

| 层级 | 存放 | 修改方式 | 适用内容 |
|------|------|---------|------|
| **L1** 字典表 `sys_dict` | DB + 内存 `ConcurrentHashMap` | Admin API CRUD + Redis Pub/Sub 广播 | 超时 / 重试 / 限流阈值 / 行数上限 |
| **L2** `application.yaml` | YAML 配置文件 | 改文件重启 | 连接池、线程池、端口、密钥 |
| **L3** `BusinessConstants` | Java 常量 | 改代码重编译 | Redis key 前缀、Header 名、channel 名 |

**决策理由**：
- 数值阈值放 sys_dict → 线上发现某租户被限流误伤，改字典表一次性生效，不用发版
- 基础设施参数放 yaml → 动得少，改了必然重启
- 纯标识符放常量 → 跨服务调用同一个字符串必须强类型

**业务代码读值一律走** `sysDictService.getInt("limit.absolute_max_rows", 10000)`。兜底默认值是防字典表数据污染的最后防线。

### 2. SQL 模板化 + 多重防线 + 多方言 + PREMIUM 自由模式

租户**不能随意写 SQL**。调用链：
```
action_template (DBA 审核)        → 开发团队维护; 同 action 多方言行需保持元数据一致
    ↓ (PREMIUM 才能, 可选)
tenant_action_config.custom_sql   → premium 租户覆盖, 占位符必须是已声明字段子集
    ↓
SqlWhitelistValidator(sql, Source) → TEMPLATE 跳 AST / TENANT_CUSTOM 严校验
    ↓
DatabaseAdapter                   → setMaxRows 驱动层兜底 + 行数事后告警, 只读账号
    ↓
connector_readonly DB account     → 最后一道兜底 (只读)
```

**为什么这么层层设防**：
- AST 解析可能漏，黑名单可能漏
- 但数据库账号权限这道是操作系统层面的，攻不破
- 一次防线失效不致于系统失守

**SqlWhitelistValidator 双 Source**（Phase 7）：早期一刀切跑 JSqlParser AST，结果合法的 MySQL `LIMIT 10 OFFSET 20` / Oracle `ROWNUM` 等被误伤。改成按来源区分：
- `TEMPLATE`（运维预审）：跳过 AST 解析，只过函数黑名单
- `TENANT_CUSTOM`（租户自定义）：全量校验（长度 / 分号 / AST / 黑名单）
- 字符串字面量与注释剥离后再做函数黑名单匹配，避免误伤 `SELECT 'pg_sleep(5) is dangerous'` 这种合法查询。单测覆盖 41 个用例

**LIMIT 策略演进**（Phase 7）：早期 Adapter 自动追加 LIMIT，副作用是聚合查询被截断、模板作者不可控。现在改为：LIMIT 由模板手写（业务级控制），`setMaxRows(absoluteMaxRows)` 在 JDBC 驱动层做硬上限兜底（防"运维忘写 LIMIT"），实际行数超 `template.max_rows` 仅记 WARN 不截断不抛（[DatabaseAdapter.java:30-32, 73, 94](../src/main/java/com/sea/star/ai/ec/enterprise/connector/service/adapter/DatabaseAdapter.java)）。

**多方言适配**（Phase 7）：`access_type` 一个字段同时表达"接入方式 + 方言"，枚举值 `POSTGRES / MYSQL / ORACLE / SQLSERVER / API`（没有独立的 `db_type` 列，schema 改动最小）：
- 同一 action 可在 `action_template` 中有多行（PG 版 + MySQL 版 + ...），运行时 [BusinessExecutor](../src/main/java/com/sea/star/ai/ec/enterprise/connector/service/BusinessExecutor.java) 按 `template.accessType == datasource.accessType` 校验匹配
- 多方言行的 `description / param_schema / datasource_name` 必须一致 ——[AdminTemplateController](../src/main/java/com/sea/star/ai/ec/enterprise/connector/controller/AdminTemplateController.java) 写入时强校验，不一致拒绝（`INCONSISTENT_TEMPLATE_FAMILY`）
- AI 视角同 action 是**一个** MCP tool（[DynamicMcpToolProvider](../src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/DynamicMcpToolProvider.java) 按 action 字段去重）
- pom 已加 `mysql-connector-j` + `mssql-jdbc`；**Oracle ojdbc11 故意未加**（包 ~7MB + 镜像膨胀 ~2GB 性价比低，ORACLE 枚举就位但实际建池 `ClassNotFoundException` 显式失败）
- 不为每方言独立 Adapter：HikariCP `Connection.isValid()` + JDBC 标准 API（`setQueryTimeout` / `setMaxRows`）已跨方言通用

**PREMIUM custom_sql 自由模式 + 占位符子集约束**（Phase 7）：当 `tenant_action_config.custom_sql` 非空且 tier=PREMIUM 时：
- [BusinessExecutor](../src/main/java/com/sea/star/ai/ec/enterprise/connector/service/BusinessExecutor.java) 跳过 `ParamValidator.validate(template.paramSchema, params)`——租户可自由传参（不强制 required/type/maxLength/pattern）
- `DatabaseAdapter.padMissingParamsFromSql` **从 custom_sql 文本自身**解析占位符给缺失 key 补 null，让 `(:foo IS NULL OR ...)` 这种"可选过滤"模式可用
- **写入子集约束**（[TenantActionConfigService.validateCustomSql](../src/main/java/com/sea/star/ai/ec/enterprise/connector/service/TenantActionConfigService.java)）：占位符必须 ⊆ `(template.paramSchema ∪ tenant.customParams)`，超集字段拒（`CUSTOM_SQL_REFERENCES_UNKNOWN_PARAM`）。**为什么**：MCP tool inputSchema 按模板 paramSchema 全局生成，AI 看不到 custom_sql 引入的新字段，运行时被 padding 静默补 null 会导致"等价 NULL"的怪查询，fail-fast 拦在写入路径
- **降级语义**：PREMIUM → STANDARD 时 `custom_sql` 字段保留不删（留着重新升级时自动恢复），运行时按 `tier == PREMIUM && customSql 非空` 双判断决定走哪条路径，降级后优雅 fallback 到模板 SQL，不抛异常、记 `log.info` 让运维可观测

### 3. Per-tenant MCP tool schema（Phase 7）

**场景**：同一个 action，不同租户授权范围 / customParams 不同，AI 看到的 inputSchema 应该是租户视角的合并 schema。

**协议层关键限制**：spring-ai-mcp-server-webmvc 1.1.4 启动时调一次 `ToolCallbackProvider.getToolCallbacks()` 把结果注册到 `McpAsyncServer.tools` (CopyOnWriteArrayList)，运行时 `tools/list` 请求**直接读这个 list 不再回调 provider**——SSE / Streamable / Stateless 三种 transport 一样。**单纯自实现 ToolCallbackProvider 不能 per-session**。

**解法**：[McpToolsListInterceptFilter](../src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/McpToolsListInterceptFilter.java) 在 servlet filter 层（`@Order(HIGHEST_PRECEDENCE+100)`）短路 `POST /mcp` 的 `tools/list` 请求自己构造响应，调 [PerTenantToolCallbackProvider](../src/main/java/com/sea/star/ai/ec/enterprise/connector/mcp/PerTenantToolCallbackProvider.java) 拿合并后 schema：
- 缓存：Caffeine 30s TTL，key=tenantId
- 失效：`TemplateChangedEvent`（template CRUD）+ `ActionAuthChangedEvent`（grant/revoke/grantAllDefaults）触发全清
- 鉴权：复用 `AuthenticationService.verifyMcp` 验 Bearer token + `X-Tenant-Id` 头
- **未实施**：tools/call 时实时重校验（防客户端拿过期 schema）、`tools/list_changed` 协议推送（依赖 Spring AI MCP 1.1.x 协议层 API）

### 4. 多租户 DataSource 池：LRU + 上限

**为什么不直接给每个租户 new HikariDataSource()？**
> 1000 租户 × 每池 5 连接 = 5000 物理连接。PostgreSQL 默认 `max_connections=100`，会被直接打爆。

解决：[TenantDataSourceManager](../src/main/java/com/sea/star/ai/ec/enterprise/connector/infrastructure/datasource/TenantDataSourceManager.java) 用 `LinkedHashMap(accessOrder=true)` 实现 LRU，超过 `connector.datasource-pool.max-pools` (yaml 实测默认 100，Java 注解 fallback 300) 就淘汰最久未用条目并 `close()`。

**多数据源后 key 格式升级**（Phase 6）：一个租户可以挂多个数据源（订单库 / 库存库 / CRM API），通过逻辑 `ds_name` 寻址。池 key 改为 `tenantId:dsName` 复合 key，按 (tenant, ds) 对计数；`tenant_datasource` 表按 `(tenant_id, ds_name)` 复合主键存储；`action_template.datasource_name` 声明"这个 action 打哪个 ds"（默认 `default`）；`tenant_action_config.datasource_name_override` 可对特定租户覆盖默认。

**并发设计的巧思**：
- **快路径**（缓存命中）：synchronized 块内只做一次 `map.get()`，毫秒级
- **慢路径**（构建新池）：HikariCP 初始化要数百毫秒，**在锁外执行**；再回到锁内做去重决策，避免阻塞其他租户

这一点经常被面试官追问："为什么不直接 ConcurrentHashMap？" 答案：LinkedHashMap 的 accessOrder 才能实现真 LRU，ConcurrentHashMap 没这个能力；并发安全由 synchronized 块守护，热路径已经做了极致优化。

### 5. 两级缓存 + Pub/Sub 失效广播

`TwoLevelCacheManager` = **Caffeine (L1, 本机) + Redis (L2, 共享)**

```
GET:  L1 命中 → 返回
      L1 miss → L2 命中 → 写回 L1 → 返回
      L2 miss → Supplier 回源 → 写回两级
PUT:  同时写 L1 + L2 + 发 Pub/Sub
EVICT: 同时清 L1 + L2 + 发 Pub/Sub
```

**为什么要 Pub/Sub？**
> 多实例部署时，实例 A 更新了数据写到 DB，L1/L2 都更新了，但**实例 B 的 L1 还是旧值**。所以实例 A 要通过 Redis Pub/Sub (channel `cache:invalidate`) 广播 key，实例 B 的 `CacheInvalidationListener` 收到后清本地 L1。消息格式：`"{cacheName}|{key}"`。

类似机制也用于 sys_dict 热更新 (channel `dict:refresh`)。

### 6. 复合主键与 MyBatis-Flex 迁移

`tenant_action_config` 表主键是 `(tenant_id, action)` 复合主键。

**早期用 MyBatis-Plus**，原生不支持，要引第三方插件 `jeffreyning/mybatisplus-plus`（2021 后停更）。**当时在 Phase 4 结束、没测试代码、只改 20 个文件的窗口期，果断迁到 MyBatis-Flex 1.11.6**：
- 两个字段都标 `@Id(keyType=KeyType.None)` 就原生支持
- APT 生成的 `TableDef` 提供类型安全的 `QueryWrapper` 查询
- 社区活跃度更好

**迁移成本**：1 天，~20 文件注解替换 + API rename（`selectById` → `selectOneById`、`selectPage(Page,qw)` → `paginate(page,size,qw)` 等）+ MetaObjectHandler → AutoFillListener。

**迁移时点的判断**：Phase 5 开始前。等 Phase 5 写完 100+ 测试再迁，代价会是现在的 5-10 倍。

### 7. 异步上下文传递：`TaskDecorator` + MDC

`TenantContext` 和 `traceId` 都存在 ThreadLocal。**所有 `@Async` 线程池必须用 `TaskDecorator` 传递**，否则切线程后 `getCurrentTenant()` 返回 null，整个租户上下文丢失。

[AsyncConfig](../src/main/java/com/sea/star/ai/ec/enterprise/connector/config/AsyncConfig.java) 的 `TenantContextTaskDecorator` 在 `decorate(Runnable)` 里抓取当前线程的 `(tenantId, traceId)`，提交到工作线程时重新 set 进 ThreadLocal + MDC，任务完成后清理。

**这是个新手常踩的坑**：用 `CompletableFuture.supplyAsync(...)` 或 `@Async` 不配自定义 Executor，默认走 `ForkJoinPool.commonPool()`，租户上下文直接丢。

### 8. 必要授权白名单 + 软删 + 双重认证物理删 + 状态守卫 (Phase 6)

**从"可选覆盖"升级为"必要授权"**: 早期 Phase 5 `tenant_action_config` 是模板配置的可选覆盖表, 任何租户都能调任何 enabled 模板 —— 安全洞。Phase 6 把它升级成白名单: 行存在 = 授权, 不存在 = `ACTION_NOT_AUTHORIZED` (403)。批量授权 `POST /admin/tenants/{tid}/actions/grant-all-defaults` 一键把所有 enabled 模板授给某租户, 对应 ds 不存在的模板**宽容跳过**不抛错。

**软删 (每表独立, 无级联)**:
- `tenant_config` / `tenant_datasource` / `action_template` / `sys_dict` 有 `deleted BOOLEAN` 字段, MyBatis-Flex `@Column(isLogicDelete=true)` 自动过滤 SELECT
- 软删只动自己 — 软删租户时业务调用在 `getConfig()` 自然被拒, 下属配置无需动, restore 对称干净
- `tenant_action_config` / `async_task` / `audit_log` 物理删 (语义不同, 不加)

**物理删双重认证**: `/purge` 端点要求 `X-API-Key` + `X-Purge-Api-Key` 两个 header。两个 key **独立配置**, admin 日常运维拿不到 purge key。服务端未配 purge-api-key 则**拒绝所有 purge 请求** (保守默认)。

**硬删 (Purge, 不可逆)**: **级联**是硬删独有的行为。`DELETE /admin/tenants/{id}/purge` 同事务清 `tenant_datasource` + `tenant_action_config` + `tenant_config`, `audit_log` 保留作合规证据。需双重认证 (见下)。

**为什么这样设计**:
- 授权白名单: 防商业分层漏洞 (某租户付费能调 action X, 其他租户不应能偷)
- 软删不级联: 语义独立清晰, restore 不会复活"单独软删了的下属"
- 硬删才级联: 硬删本就是清仓场景, 级联是预期
- Purge key 分离: admin-api-key 泄漏时物理删能力不受影响

**踩过的 Flex 坑**: 最初用 `deleted_at TIMESTAMP NULL` 做软删标志, Flex 会发 `WHERE deleted_at = ?` 绑整数 0, PG 炸 "timestamp = integer 类型不匹配"。换成 `deleted BOOLEAN` 才 work (Flex 默认 0/1 语义)。

**状态守卫**: [TenantStatusGuard](../src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/TenantStatusGuard.java) 在 MCP 入口 fail-fast 检查租户 enabled 状态，禁用/软删租户**不进入 rate limiter**（不污染限流计数 / 不触发熔断阈值）；admin operation 走 `requireExists` 宽容路径，允许对禁用租户做配置类操作。这一层把"业务不可用"从"系统拒绝"剥离，错误信号清晰。

### 9. 租户接入边界: 数据形态适配是商户的责任, 不是平台的

**问题来源**: 多租户场景下, 商户 A 的订单表叫 `orders` 字段叫 `order_id`, 商户 B 的叫 `t_order` 字段叫 `order_no`, 商户 C 的 API 返回 `{ "data": { "order_no": ... } }` 嵌套结构。**模板里只有一份 SQL / api_path**, 怎么跨商户复用?

**核心决策**: **接入方负责把数据形态适配成连接器约定的标准 schema**, 平台不为每个客户写定制代码。

#### 9.1 DB 类租户: 商户在自家 DB 建 VIEW 适配

模板写"通用字段名" (`order_id` / `user_id` / `amount` / ...), 商户自家 schema 不同时**自己建 VIEW 做映射**:

```sql
-- 商户 B 的 DB (字段叫 order_no):
CREATE VIEW orders AS
SELECT order_no AS order_id,
       user_no  AS user_id,
       total_amount AS amount,
       order_status AS status,
       create_time  AS created_at
FROM t_order;
GRANT SELECT ON orders TO connector_readonly;
```

**优势**: 模板真正"一份 SQL 打天下" / VIEW 是 PG/MySQL native query rewrite 零开销 / 商户改自家 schema 只调 view 不影响连接器 / **AI 看到的字段名跨商户统一**

#### 9.2 API 类租户: 商户起 BFF / API 网关适配标准 schema

模板的 `api_path` + `api_body_template` 调"标准 API", 商户内部 API 不一致时**自己起一个 BFF/API gateway** 包装成标准 schema:

```
商户内部 API (异构):  GET /v2/orders?id=X1
返回:               { "data": { "order_no": "X1", "total": 99.00, ... } }
                          ↓
                  商户的 BFF 适配层
                          ↓
连接器调用:           GET /standard-orders/{orderId}
返回:               { "orderId": "X1", "amount": 99.00, ... }
```

#### 9.3 极少数大客户走 PREMIUM custom_sql / response transformer

商户绝对不动自家 DB / API 的场景 (合规 / 流程慢 / 历史包袱):
- DB 类: `tenant_action_config.custom_sql` 一户一份 SQL (PREMIUM tier 限定)
- API 类: 未来扩展 `responseFieldMap` JsonPath 转换 (Phase 7+ 待做)

适配成本以 **PREMIUM tier 定价**消化, 不污染主架构。

#### 9.4 设计哲学

这个决策对应**SaaS 平台跟单租户系统最大的区别**:

| 维度 | 单租户系统 | SaaS 平台 (本项目) |
|------|------|------|
| 数据形态 | 一套 schema | N 套, 每商户一套 |
| 适配责任归属 | 平台开发 | **接入方** (商户) |
| 平台如何扩展 | 加客户 = 加代码 | 加客户 = 加配置 |
| 模板 / 业务逻辑 | 一对一定制 | 一对多复用 |

**Stripe / Shopify / 阿里云 RDS 都这么做** — 平台定义"数据契约", 接入方对齐契约。这条决策跟 Phase 6 多数据源、必要授权白名单一起, 共同构成"连接器作为 SaaS 接入层"的核心边界。

#### 9.5 不该做的

- ❌ 字段名 / 表名作为 SQL 参数 (PreparedStatement 不支持, `${}` 拼接 = SQL 注入)
- ❌ 让 AI 自己适配不同字段名 (AI 是统计推理, 不会"懂"两套字段等价, 答错率高)
- ❌ 在 action_template 里加"按商户分支"的 SQL DSL (复杂度爆炸 + 测试矩阵 N×M)
- ❌ 平台为每个客户写定制 SQL / API 适配代码 (反 SaaS 模式, 不可扩展)

**接入文档把"数据契约"写清楚, 是工程化运营的起点。**

---

## 四、技术栈全景与选型理由

| 技术 | 版本 | 用它 / 不用它的原因 |
|------|------|------|
| **Java** | 21 | Virtual Threads、pattern matching、records；系统默认 JDK 17 得手动切 |
| **Spring Boot** | 3.4.5 | 之前试过 Boot 4.0.5，Spring AI MCP Starter / Spring Data Redis 生态跟不上，回退到 3.4.x |
| **MyBatis-Flex** | 1.11.6 | 原生复合主键 + APT 类型安全查询 + 活跃社区；不用 JPA/Hibernate（重量级，N+1 问题不可控）；不用 MP（复合主键要插件） |
| **Spring AI MCP** | 1.1.4 | 标准 MCP 协议 (Streamable HTTP, MCP 2025-03-26 单端点 `/mcp`)；不自己糊 JSON-RPC REST |
| **PostgreSQL** | 16 | JSONB 字段 + `CAST(... AS jsonb)` 原生查询；`params` 去重用 JSONB 等值比较 |
| **MySQL Connector/J** + **mssql-jdbc** | - | Phase 7 多方言；Oracle ojdbc11 故意未加（包 ~7MB + 镜像膨胀 ~2GB 性价比低，ORACLE 枚举就位但建池 `ClassNotFoundException` 显式失败） |
| **Redis** | 7.4 | L2 缓存 + Pub/Sub 失效广播 + 幂等 key + 限流计数 |
| **Caffeine** | - | L1 缓存，比 ConcurrentHashMap 多了 TTL + 大小上限 + 统计 |
| **HikariCP** | - | JDK 连接池事实标准；`Connection.isValid()` 跨方言通用，不按方言设 connectionTestQuery |
| **Resilience4j** | 2.3.0 | 限流 + 熔断；官方适配 Spring Boot 3 + 指标原生上报 Micrometer |
| **JSqlParser** | 5.1 | SQL 白名单校验的 AST 引擎 |
| **Micrometer** + **Prometheus** | - | `/actuator/prometheus` 端点 + P50/P90/P95/P99 直方图 |
| **Logback** + **logstash-logback-encoder** | 8.0 | 生产 profile 输出结构化 JSON 日志给 ELK / Loki |
| **Testcontainers** | 1.21.3 | PG + Redis 真实容器的集成测试 |
| **AES-256-GCM** | JDK 原生 | 敏感字段加密；GCM 带认证标签，篡改会抛 `AEADBadTagException` |

### 为什么密码加密用 GCM 而不是 CBC
- **CBC 无认证**，密文被篡改也会解密出一段垃圾数据，调用方不知道；GCM 有认证标签，篡改立刻被发现并升级为 `SecurityException` 告警
- GCM 并行友好，硬件 AES-NI 加速更彻底
- IV 12 字节 + Tag 16 字节，固定尺寸开销

---

## 五、质量工程

### 5.1 结构化日志（Phase 5.1）
- 生产用 JSON (logstash-logback-encoder)，本地 / dev 用普通 pattern
- `MaskingConverter` 自定义 Logback 转换器，4 条正则扫描：JSON kv、URL query、Bearer/Basic、加密字段名 → 全部打码 `***`
- MDC 承载 `traceId`（由 `TraceIdFilter` 最高优先级注入）+ `tenantId`（由 `TenantContext` 同步设置）

### 5.2 优雅停机（Phase 5.2）
- `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`
- **绝不暴露** `/actuator/shutdown` 端点：该端点默认无认证，任何网络可达的调用方 POST 即可终止 JVM

### 5.3 指标（Phase 5.3）
自定义命名空间 `connector.*`：
- `connector.request.{total,duration}` — 按 (tenant, action, status) 打标；P50/P90/P95/P99 直方图
- `connector.cache.{hit,miss}` — 按 (cache, level) 打标
- `connector.datasource.pool.size` — Gauge，周期采样池大小
- `connector.async.task.{active,total}` — 异步任务生命周期

外加自动注册的 `resilience4j.*` / `http.server.*` / `jvm.*`。

### 5.4 单元测试 —— ~187 个
覆盖核心安全校验、工具类、MCP 拦截器、Adapter / Service 单元行为：
| 测试类 | 覆盖 |
|------|------|
| SqlWhitelistValidatorTest | 41 用例，AST 校验 / 函数黑名单 / 字符串剥离 / TEMPLATE vs TENANT_CUSTOM 双 Source |
| EncryptionUtilsTest | 16 用例，key fail-fast / round-trip / 篡改检测 |
| ParamValidatorTest | 23 用例，schema 驱动的 type/required/pattern/maxLength |
| SecurityUtilsTest | 10 用例，constant-time equals 全分支 |
| MaskingConverterTest | 23 用例，4 条正则 + ILoggingEvent 集成 |
| CallbackUrlValidatorTest | 22 用例，防 SSRF（loopback / 内网 / 多播 / 云元数据） |
| DatabaseAdapterTest | 11 用例，setMaxRows 兜底 / padMissingParamsFromSql / 多方言 supports() |
| SchemaUtilsTest | 13 用例，paramSchema ∪ customParams 合并视图 |
| AccessTypeTest | 2 用例，isDb() 五值枚举 |
| AdminTemplateControllerConsistencyTest | 6 用例，多方言行 INCONSISTENT_TEMPLATE_FAMILY 强校验 |
| DynamicMcpToolProviderTest / McpToolsListInterceptFilterTest / PerTenantToolCallbackProviderTest | 19 用例，per-tenant MCP schema 拦截链路 |

### 5.5 集成测试 —— 58 个
基于 Testcontainers 启真实 PG 16 + Redis 7 容器（多方言 IT 另起 MySQL 8 / SQL Server 2022 容器），覆盖：
- Mapper CRUD + JSONB + 复合主键 + 枚举映射 + AutoFill
- `findDuplicateInflight` 的 JSONB 原生 `CAST` 等值查询
- 两级缓存 + Pub/Sub 失效监听的真实往返
- BusinessExecutor 授权白名单（`ACTION_NOT_AUTHORIZED`）+ custom_sql 自由模式 / 降级 fallback
- 多方言 smoke：`MysqlSmokeIntegrationTest` / `SqlServerSmokeIntegrationTest` / `ActionTemplateMultiDialectIntegrationTest`
- `@Transactional` + `@TransactionalEventListener(AFTER_COMMIT)` 的事件传播
- 负缓存 / L1 命中绕过 DB 等行为断言

---

## 六、开发阶段与产出

| Phase | 内容 | 状态 |
|-------|------|------|
| **1** 骨架 + 数据层 | 6 个实体、6 个 Mapper、异常体系、SysDictService、工具类 | ✅ |
| **2** 核心服务层 | 两级缓存、TenantDataSourceManager、Database/HttpApi 适配器、BusinessExecutor 同步路径 | ✅ |
| **3** 异步 + 安全 + 限流 | AsyncTaskService、SqlWhitelistValidator、AuthInterceptor、Resilience4j | ✅ |
| **4** MCP + Admin API | DynamicMcpToolProvider、McpToolService、Admin Controllers | ✅ |
| **5** 可观测 + 测试 + 部署 | JSON 日志、ConnectorMetrics、UT、IT、Dockerfile、docker-compose | ✅ |
| **6** 多数据源 + 授权白名单 + 软删/Purge | TenantDataSourceManager LRU 池（key=`tenantId:dsName`）、`tenant_action_config` 白名单、软删 + 二级认证 Purge、TenantStatusGuard、AsyncTaskCleanupJob TTL 清理、AuditService `@Async` 异步落库 | ✅ |
| **7** 多方言 + per-tenant MCP schema | AccessType 五值枚举、SqlWhitelistValidator Source 区分、DatabaseAdapter setMaxRows 兜底 + 事后告警、AdminTemplate 多方言一致性强校、PREMIUM custom_sql 自由模式 + 子集约束、McpToolsListInterceptFilter + PerTenantToolCallbackProvider | ✅ |
| **8+** 后续 | Oracle 驱动接入、tools/list_changed 协议推送、多区域部署、K8s | 未开始 |

---

## 七、工程实战故事 (面试细节素材)

### 7.1 迁移决策：MyBatis-Plus → MyBatis-Flex
**情境**：Phase 4 完成、零测试代码，发现 TenantActionConfig 复合主键在 MP 下只能靠停更的第三方插件硬撑。

**判断框架**：
- 现在迁：~20 文件，无测试需要同步改，1 天搞定
- Phase 5 后再迁：100+ 测试用例需要一起改，至少 5 天 + 风险

**执行**：pom 替换依赖 → 6 实体注解替换 → Mapper API rename → MetaObjectHandler → AutoFillListener → QueryWrapper 类型安全改写 → 冒烟验证 8 项。

**收获**：APT 处理器的顺序（Lombok 必须放在 `mybatis-flex-processor` 之前，否则 TableDef 字段为空），这是文档里没说、踩坑才知道的。

### 7.2 循环依赖：AsyncTaskService 的自调用
**症状**：`@Async` 方法被同一个 bean 的非 async 方法调用时不生效。

**旧方案**：通过 `ApplicationContext.getBean` 拿自己的代理对象 — Spring 3.2+ 默认开的"循环依赖检测"会报错。

**新方案**：`@Autowired public void setSelfProxy(@Lazy AsyncTaskService self)` setter 注入 + `@Lazy`。`@Lazy` 让 Spring 在真正调用时才解析代理，打破构造期的循环。

### 7.3 Docker Desktop 4.70 vs docker-java 的血泪史 (Phase 5.5)
**症状**：Testcontainers 启动时必挂 `BadRequestException (Status 400, {"ID":"", ..., "Labels":["com.docker.desktop.address=npipe:..."]})`。

**诊断过程**：
1. 关掉 Enhanced Container Isolation → 无效
2. 关掉 containerd image store → 无效
3. 勾上 "Expose daemon on tcp://localhost:2375" → curl 拿到完整响应 HTTP 200，但 docker-java 还是 400
4. 切换 npipe：`docker_engine` / `dockerDesktopLinuxEngine` / `docker_engine_linux` → 全挂
5. 切 IPv4：`tcp://127.0.0.1:2375` → 全挂
6. 升 Testcontainers 1.20 → 1.21.3 → 全挂

**根因**：Docker Desktop 4.70 引入了一个 CLI-auth 代理，所有未经过 handshake 的 docker API 请求都会被拦截并返回 stub 响应，docker CLI 自己懂这个 handshake 协议，docker-java 不懂。这是一个产品层面的不兼容，不是配置可修。

**结论**：三个可工作环境 — Linux / macOS / WSL2 装 Docker CE / CI。所以 IT 测试代码落盘、本地 Windows 跑不了，改由 CI 验证。

**文档沉淀**：把这个坑记进 `CLAUDE.md` 的"集成测试"一节，未来接手的人不用再踩。

### 7.4 安全边界坚守：MaskingConverter 的 JSON_KV 贪婪 bug
`password=hunter2&id=5` 经 MaskingConverter 后变成 `password=***`（id=5 被贪婪吞掉）。

**判断**：这是 `[^",\s}]+` 没排除 `&` 导致的，属于已知 bug。单测写了 21 个用例，把这个行为记录在测试注释里作为"已知待修复"，没有强行改正则（避免引入新 bug），留到后续迭代。

**这个决策的意义**：测试不是为了"全部 pass"，是为了**精确锚定当前行为**。已知缺陷写在注释里，比掩盖它重要。

### 7.5 指标埋点的成本控制
Gauge 用 `ToDoubleFunction<T>` supplier 回调而不是主动 push，Micrometer 周期采样，**零运行时开销**。

Counter / Timer 按 `(tenantId, action, status)` 打 tag，但注释明确告知"依赖上游 max-pools=100 硬约束，不能超"——标签高基数 OOM 是 Prometheus 的经典坑。

### 7.6 Spring AI MCP 协议层限制：per-session schema 的 servlet filter 绕道

**情境**（Phase 7）：要让同一个 action 在不同租户视角下展示不同 inputSchema（合并 customParams），第一反应是写一个 `PerTenantToolCallbackProvider implements ToolCallbackProvider`，让 spring-ai-mcp 框架按请求回调。

**踩坑**：写完后 `tools/list` 永远拿到第一次启动时构造的全局 schema。读 spring-ai-mcp-server-webmvc 1.1.4 源码才发现：启动时调一次 `ToolCallbackProvider.getToolCallbacks()`，结果灌进 `McpAsyncServer.tools` (CopyOnWriteArrayList)，运行时 `tools/list` **直接读这个 list 不再回调 provider**——SSE / Streamable / Stateless 三种 transport 一样。

**解法**：在协议层之前拦。`McpToolsListInterceptFilter` 是 `@Order(HIGHEST_PRECEDENCE+100)` 的 OncePerRequestFilter，对 `POST /mcp` 缓存 body → 解析 JSON-RPC method → `tools/list` 自己处理（短路 chain）/ 其他 method 包装 request 透传给 spring-ai-mcp。短路路径里复用 AuthenticationService 验 token、读 `X-Tenant-Id` 设 TenantContext、调 PerTenantToolCallbackProvider 拿合并后 schema、序列化 inputSchema 嵌入 JSON-RPC 响应、写 HTTP body。

**收获**：开源框架文档说"实现这个接口就行"，但**协议层缓存**这种关键限制只有读源码才知道。生产里碰到这种"接口被框架持有但只调一次"的设计，不要硬改框架，**用 servlet filter 在更外层短路**是最干净的解法。

### 7.7 多方言 SqlWhitelistValidator 双 Source 演进

**情境**（Phase 7）：上线 MySQL 多方言后，运维提交合法的 `LIMIT 10 OFFSET 20` 模板被 JSqlParser AST 拒。Oracle 的 `ROWNUM` 也类似。一刀切跑 AST 不行——JSqlParser 的方言适配滞后于真实数据库语法。

**判断**：模板是运维预审过的（信任源），租户 custom_sql 才是不可信源。一个校验器跑两套强度。

**解法**：`SqlWhitelistValidator.validate(sql, Source)` 重载。`Source.TEMPLATE` 跳 AST 只过函数黑名单；`Source.TENANT_CUSTOM` 全量校验（长度 / 分号 / AST / 黑名单）。安全模型不退化——租户自定义路径仍按最严格校验，模板路径靠运维审核 + 只读账号兜底。

**收获**：安全校验**不一定要一视同仁**——按"信任来源"分级，能在不破防的前提下兼容更多合法场景。

---

## 八、使用指南

### 8.1 本地启动

```bash
# JDK 21 必须 (系统默认可能 17)
export JAVA_HOME="/d/soft/java/jdk-21.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

# DDL + 字典初始化 (首次)
psql -U postgres -d sea_star_ai -f sql/01_create_tables.sql
psql -U postgres -d sea_star_ai -f sql/04_seed_dict.sql   # 必须, sys_dict 初始值

# 编译 + 启动
./mvnw.cmd clean compile
./mvnw.cmd spring-boot:run
# → :8089 启动, /actuator/health /actuator/prometheus 开放
```

### 8.2 关键环境变量

| 变量 | 含义 | 生成方式 |
|------|------|------|
| `DB_PASSWORD` | PG 密码 | 部署时给 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 | 部署时给 |
| `MCP_AUTH_TOKEN` | MCP 端点 Bearer Token | 部署时给 |
| `ADMIN_API_KEY` | Admin API `X-API-Key` | 部署时给 |
| `ENCRYPTION_KEY` | 敏感字段 AES-256 密钥 (Base64 32 字节) | `openssl rand -base64 32` |
| `CALLBACK_INBOUND_SECRET` | 异步回调签名密钥 | 部署时给 |

**启动时 `ENCRYPTION_KEY` 缺失或格式错 → fail-fast，应用起不来**。本地开发 `application-local.yaml` 有 dev 占位密钥兜底，切勿复制到生产。

### 8.3 测试命令

```bash
# 全量 (IT 需要 Docker, Windows Docker Desktop 4.70 上跑不了)
./mvnw.cmd test

# 只单测 (121 个, ~10s, 开发机 daily 跑)
./mvnw.cmd test -Dtest='!*IntegrationTest'

# 只集成测试 (33 个, 需要 Docker)
./mvnw.cmd test -Dtest='*IntegrationTest'

# 单个类 / 单个方法
./mvnw.cmd test -Dtest='EncryptionUtilsTest'
./mvnw.cmd test -Dtest='EncryptionUtilsTest#roundTrip_basic'
```

### 8.4 Profile

| Profile | 何时用 |
|---------|--------|
| `local` | 默认，本地开发，有 dev 占位密钥 |
| `dev` | 团队 dev 环境，env 变量注入 |
| `prod` | 生产，env 变量必须全部提供，fail-fast |
| `test` | 集成测试，Testcontainers 容器动态注入端口 |

### 8.5 观测端点

```
/actuator/health/{liveness,readiness}   K8s 探针
/actuator/prometheus                     Prometheus 抓取
/actuator/metrics                        人读
```

---

## 九、后续演进方向

| 方向 | 内容 |
|------|------|
| **Oracle JDBC 驱动接入** | 目前 ORACLE 枚举值就位但建池 `ClassNotFoundException`；评估 ojdbc11 引入 vs profile 化驱动按需打包 |
| **tools/list_changed 协议推送** | 当前 PerTenantToolCallbackProvider 失效靠客户端 30s TTL 重拉；升级到 MCP 协议层主动推 list_changed（依赖 Spring AI MCP 1.1.x 协议层 API） |
| **tools/call 实时重校验** | 客户端可能拿过期 schema 调用，运行时再做一次 schema 合法性兜底 |
| **API 类租户 responseFieldMap** | §3.9.3 提到的 PREMIUM 待做项，JsonPath 转换异构 API 响应到标准 schema |
| **多区域部署** | Redis Pub/Sub 跨区域延迟高，改为 NATS / Kafka Compact Topic 做更强一致性的缓存失效 |
| **模板参数 UI** | 目前 `param_schema` 是手写 JSON，做个简单 DSL + 代码生成 |
| **K8s 部署** | Helm Chart + HPA；ConfigMap 注入 env；Secrets 存 encryption-key |
| **压测 & 容量** | 1000 租户 × 100 QPS 下 LRU 淘汰频率、缓存命中率、Hikari 连接池争用情况 |

---

## 十、谈起这个项目可以侧重的点

面试 / 演讲时的"锚点"（按重要性排序）：

1. **三层配置体系** — 凡是"配置在哪里"这个问题，一定有非显而易见的分类逻辑
2. **租户接入边界: VIEW / BFF 适配标准 schema** — 体现 SaaS 平台 vs 单租户系统的根本差异，工程化运营的起点（架构决策 §3.9）
3. **LRU DataSource 池的并发设计** — 快路径无锁 / 慢路径锁外 + 锁内决策，是经典的"先解决正确性再解决性能"
4. **SQL 注入纵深防御** — 模板审核 + SqlWhitelistValidator 双 Source（TEMPLATE 跳 AST / TENANT_CUSTOM 严校）+ NamedParameterJdbcTemplate prepared statement + 只读账号兜底 + setMaxRows 驱动层硬上限。"为什么 LIMIT 不在 Adapter 自动追加" 是个能讲出"防御纵深"思路的好问题
5. **两级缓存 + Pub/Sub 失效广播** — 多实例一致性的标准套路，注意讲"消息广播失败的降级 (log.error + TTL 自愈)"
6. **MyBatis-Plus → Flex 迁移的时机判断** — 工程决策力的体现，不是技术对比
7. **Spring AI MCP 协议层缓存 + servlet filter 绕道**（Phase 7）— 展示读源码定位框架限制 + 在更外层短路的工程定位力（§7.6）
8. **Docker Desktop 4.70 调试故事** — 展示定位能力：从现象 → 关闭可疑设置 → 切通道 → 升版本 → 最终到产品不兼容的结论
9. **AES-GCM 而非 CBC + fail-fast 密钥校验** — 展示对密码学和启动时校验的敏感度
10. **`TaskDecorator` 传递上下文** — 展示对 Spring 异步机制底层的理解
11. **Phase 6 必要授权白名单的演进** — 展示工程决策力 + 安全意识（"Phase 5 → 6 把可选覆盖升级为必要授权，修了商业分层洞"）
12. **PREMIUM custom_sql 自由模式 + 子集约束**（Phase 7）— 展示"商业分层与安全边界并存"：高客单价租户给灵活性，但写入路径的占位符子集校验把 AI 永远不会传的字段拦在门外

不要侧重的：
- ~~"我写了多少行代码"~~ — 无效指标
- ~~"集成了哪些框架"~~ — 技术栈已经写在文档里，不用重复
- ~~Docker 配置细节~~ — 除非对方问，不然是 noise

---

_文档生成时间: 2026-04-30_
_对应代码版本: Phase 7 完成（Git: 15a2713 初始提交）_
_维护: 商讨类技术决策以本文档为准, 实现细节以 `CLAUDE.md` + `docs/DEVELOPMENT_PLAN.md` 为准_
