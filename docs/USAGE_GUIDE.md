# 使用文档 —— 查询订单全链路示例 (Phase 6)

> **场景**: OpenClaw 还没接入, 在 Postman 里从零把一个"查询订单"的 MCP 调用链跑通。
>
> **读者**: 第一次接触这个连接器的同事 / 评审 / 面试候选者。

本文档把"商户接入 → 查询"链路拆成 **7 步**, 每步都给完整的请求 body。Phase 6 起多租户、多数据源、必要授权是刚需, 流程比 Phase 5 多两步 (建数据源 + 显式授权)。

**🚀 快速开始**: 不想逐条粘贴? 导入下面两个文件一键就位:
- [`enterprise-connector.postman_collection.json`](./enterprise-connector.postman_collection.json) — 分 7 个文件夹的完整流程
- [`enterprise-connector.postman_environment.json`](./enterprise-connector.postman_environment.json) — 环境变量 (本地 dev 占位值)

导入方式: Postman → `Import` → 选这两个文件 → 右上角选 "SeaStar Local" 环境 → 按文件夹顺序跑。

---

## 目录

- [0. 准备工作](#0-准备工作)
- [1. 创建租户 (Admin API)](#1-创建租户-admin-api)
- [2. 给租户配置数据源 (Admin API, Phase 6 新增)](#2-给租户配置数据源-admin-api-phase-6-新增)
- [3. 创建 action_template (Admin API)](#3-创建-action_template-admin-api)
- [4. 授权 action 给租户 (Admin API, Phase 6 新增)](#4-授权-action-给租户-admin-api-phase-6-新增)
- [5. 重启应用让 MCP 工具生效](#5-重启应用让-mcp-工具生效)
- [6. 通过 MCP 协议调用 `queryOrder`](#6-通过-mcp-协议调用-queryorder)
- [7. 审计日志 & 故障排查](#7-审计日志--故障排查)
- [附录 A: Postman 环境变量配置](#附录-a-postman-环境变量配置)
- [附录 B: 常见错误码速查](#附录-b-常见错误码速查)
- [附录 C: 物理删 (Purge) 双重认证](#附录-c-物理删-purge-双重认证)
- [附录 D: 商户接入约束 SOP (异构 schema 适配)](#附录-d-商户接入约束-sop-异构-schema-适配)

---

## 0. 准备工作

### 0.1 基础设施

```bash
curl http://localhost:8089/health/readiness
# {"status":"UP","details":{"database":"UP","redis":"UP","sysDictSize":25}}
```

### 0.2 模拟"商户的订单库"

```sql
CREATE DATABASE merchant_a_db;
\c merchant_a_db

CREATE TABLE orders (
    order_id     VARCHAR(32) PRIMARY KEY,
    user_id      VARCHAR(32) NOT NULL,
    amount       DECIMAL(10,2) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE ROLE connector_readonly WITH LOGIN PASSWORD '123456';
GRANT CONNECT ON DATABASE merchant_a_db TO connector_readonly;
GRANT USAGE ON SCHEMA public TO connector_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO connector_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO connector_readonly;

INSERT INTO orders (order_id, user_id, amount, status) VALUES
  ('ORD001', 'U100', 99.00,  'PAID'),
  ('ORD002', 'U100', 48.50,  'REFUNDED'),
  ('ORD003', 'U200', 150.00, 'PAID'),
  ('ORD004', 'U200', 250.00, 'PENDING');
```

### 0.3 Postman 环境变量

| key | value |
|-----|-------|
| `baseUrl` | `http://localhost:8089` |
| `adminApiKey` | `local-dev-admin-api-key-change-in-prod` |
| `purgeApiKey` | `local-dev-purge-api-key-DO-NOT-USE-IN-PROD` |
| `mcpToken` | `local-dev-mcp-token-change-in-prod` |
| `tenantId` | `merchant_a` |
| `dsName` | `default` |

---

## 1. 创建租户 (Admin API)

Phase 6 起 `tenant_config` **只存身份 + 租户级策略**, 数据源独立管理。

### 请求

```
POST {{baseUrl}}/admin/tenants
Headers:
  X-API-Key: {{adminApiKey}}
  Content-Type: application/json

Body (JSON):
```

```json
{
  "tenantId": "{{tenantId}}",
  "tenantName": "商户 A",
  "tier": "STANDARD",
  "rateLimitQps": 10
}
```

### 期望响应

```json
{ "success": true, "code": "OK", "data": "merchant_a" }
```

### 关键点

- `accessType` / `dbUrl` / `dbPassword` 等字段**不再在这里**, 下一步去 `/admin/datasources` 配
- `tier: STANDARD` → 不允许 custom_sql 覆盖模板;想要就改 `PREMIUM`
- `rateLimitQps: 10` → 超限抛 `RATE_LIMIT_EXCEEDED` (429)

---

## 2. 给租户配置数据源 (Admin API, Phase 6 新增)

一个租户可以有**多个数据源** (订单库 / 库存库 / CRM API 等), 通过唯一的 `dsName` 区分。

### 前置: 租户业务库必须已经存在并有目标表

`tenant_datasource` 只是告诉连接器"去哪连", **不会**自动建库 / 建表。如果库不存在或表不存在, 调用模板时会报 `ADAPTER_DB_ERROR / relation "xxx" does not exist`。

本仓库提供了 demo 业务库 seed 脚本 [sql/05_seed_demo_tenant_db.sql](../sql/05_seed_demo_tenant_db.sql), 用于本地演示 / onboarding 参考:

```bash
# 1) DBA 账号建库 + 只读账号
psql -U postgres -h localhost \
  -c "CREATE DATABASE merchant_a_db;" \
  -c "CREATE ROLE connector_readonly LOGIN PASSWORD 'change_me_in_prod';"

# 2) 切到业务库, 跑脚本 (建 orders 表 + 10 条样本 + GRANT)
psql -U postgres -h localhost -d merchant_a_db -f sql/05_seed_demo_tenant_db.sql

# 3) 用只读账号验证能查到数据
psql -U connector_readonly -h localhost -d merchant_a_db -c "SELECT count(*) FROM orders;"
# 期望: 10
```

生产环境**不要跑这个脚本** — 真实业务表 schema 由商户运维负责, 接入时按 [附录 D](#附录-d-商户接入约束-sop-异构-schema-适配) 的 SOP 用 VIEW 适配。

### 请求

```
POST {{baseUrl}}/admin/datasources/{{tenantId}}/{{dsName}}
Headers:
  X-API-Key: {{adminApiKey}}
  Content-Type: application/json

Body (JSON):
```

```json
{
  "accessType": "DB",
  "dbUrl": "jdbc:postgresql://localhost:5432/merchant_a_db",
  "dbUsername": "connector_readonly",
  "dbPassword": "ro_pwd_xxxx",
  "dbDriver": "org.postgresql.Driver"
}
```

### 期望响应

```json
{ "success": true, "code": "OK", "data": "merchant_a/default" }
```

### 关键点

- **URL 里的 `{dsName}` 就是逻辑契约名**, 模板会引用它 (见下一步)
- 约定默认 ds 叫 `default`;别的场景比如 `orders` / `crm_api` 按你命名规范走
- `dbPassword` 传**明文**, 服务端 AES-256-GCM 加密存 `db_password_enc` 列, DB 里永远看不到明文
- 响应或 `GET` 时密文字段**会被剥离为 null**, 防密文外泄
- **`dbDriver` 强烈推荐显式传** — 字段技术上可空 (HikariCP 会回退到 `DriverManager.getDriver(jdbcUrl)` 做 SPI 自动发现), 但在 fat-jar / 多驱动并存 / 容器化场景下自动发现可能选错驱动, 表现为 "连得上但行为异常" (查不到数据 / 类型推断错 / 隔离级别变化)。常用值:
  - PostgreSQL: `org.postgresql.Driver`
  - MySQL 8.x: `com.mysql.cj.jdbc.Driver`
  - Oracle: `oracle.jdbc.OracleDriver`
  - SQL Server: `com.microsoft.sqlserver.jdbc.SQLServerDriver`

### 其他操作

```
GET    /admin/datasources/{tid}               列出该租户所有数据源
GET    /admin/datasources/{tid}/{dsName}      查单条
PUT    /admin/datasources/{tid}/{dsName}      PATCH 改字段
DELETE /admin/datasources/{tid}/{dsName}      软删 (可 restore)
POST   /admin/datasources/{tid}/{dsName}/restore    恢复软删
DELETE /admin/datasources/{tid}/{dsName}/purge      物理删 (不可逆, 需 X-Purge-Api-Key)
```

---

## 3. 创建 action_template (Admin API)

Phase 6 起模板多了个 `datasourceName` 字段, 声明"这个 action 打哪个逻辑数据源"。

### 请求

```
POST {{baseUrl}}/admin/templates
Headers:
  X-API-Key: {{adminApiKey}}
  Content-Type: application/json

Body (JSON):
```

```json
{
  "action": "queryOrder",
  "accessType": "DB",
  "name": "查询订单",
  "description": "根据订单号或用户 ID 查询订单。支持状态过滤。",
  "datasourceName": "default",
  "sqlTemplate": "SELECT order_id, user_id, amount, status, created_at FROM orders WHERE (:orderId::varchar IS NULL OR order_id = :orderId) AND (:userId::varchar IS NULL OR user_id = :userId) AND (:status::varchar IS NULL OR status = :status) ORDER BY created_at DESC",
  "paramSchema": "{\"orderId\":{\"type\":\"string\",\"maxLength\":32,\"pattern\":\"^[A-Z0-9]+$\",\"description\":\"订单号\"},\"userId\":{\"type\":\"string\",\"maxLength\":32,\"description\":\"用户 ID\"},\"status\":{\"type\":\"string\",\"maxLength\":20,\"description\":\"订单状态 PAID/REFUNDED/PENDING\"}}",
  "maxRows": 100,
  "isLongRunning": false,
  "timeoutSeconds": 5
}
```

### 关键点

- `datasourceName: "default"` → 所有租户调 queryOrder 会打各租户的 `default` 数据源
- `accessType: DB` 和数据源的 `accessType` 必须一致, 否则调用时抛 SYSTEM_ERROR
- 其他字段含义和 Phase 5 一致 (见前版本说明)

### SQL 模板编写注意事项

写 sqlTemplate 时容易踩的坑, 在新建模板**前**先对照过一遍, 比创建后报错再改省事:

#### 1. 占位符语法: `:paramName`, **不是** MyBatis 的 `#{paramName}`

[DatabaseAdapter](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/adapter/DatabaseAdapter.java) 跑的是 Spring `NamedParameterJdbcTemplate`, 不是 MyBatis。`#{}` 会被 PG 当裸字符报 "bad SQL grammar"。

#### 2. 裸占位符必须显式 cast (`stringtype=unspecified` 副作用)

`application.yaml` 里全局设了 `spring.datasource.hikari.data-source-properties.stringtype: unspecified` (为了让 JSONB 字段能从 Java String 写入)。代价: PG 在 prepare 阶段无法从 String 类型推断裸占位符的类型。

**典型坑场景**:

| ❌ PG 推断不出类型 | ✅ 改成显式 cast |
|---|---|
| `:p IS NULL` | `:p::varchar IS NULL` |
| `:p = :p` | `:p::varchar = :p` |
| `COALESCE(:p, 'x')` | `COALESCE(:p::varchar, 'x')` |
| `CASE WHEN :p THEN ...` | `CASE WHEN :p::boolean THEN ...` |
| `array_length(:p, 1)` | `array_length(:p::int[], 1)` |

而 `WHERE col = :p` 不需要 cast, PG 能从 `col` 列类型推断出来。**只在"参数与字面量/参数比"或"独立函数调用"时需要 cast**。

cast 的 type 跟 paramSchema 里的 type 对齐:
- `string` → `::varchar` 或 `::text`
- `integer` → `::int`
- `boolean` → `::boolean`
- `array` → `::int[]` / `::text[]`

#### 3. JSONB 字段查询: `?` 操作符冲突

PG 的 JSONB 有原生 `?` (存在键) / `?|` / `?&` 操作符。这和 JDBC 的 `?` 占位符冲突, NamedParameterJdbcTemplate 会把 `data->>'key' ? :p` 误解析。绕开方案:

```sql
-- ❌ 冲突
WHERE attrs ? :key

-- ✅ 用函数替代
WHERE jsonb_exists(attrs, :key::text)
```

字段访问没冲突, 正常用即可:
```sql
WHERE attrs->>'category' = :category::varchar
```

#### 4. 不要写 `LIMIT` 子句

`DatabaseAdapter` 会根据 `template.max_rows` 自动追加 `LIMIT`, 你写了反而和模板的 `max_rows` 不一致, 也会被 [appendLimit](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/adapter/DatabaseAdapter.java#L97) 的尾部正则识别后跳过追加 — 等于失去了平台保护。聚合查询 (SUM/COUNT) 把 `maxRows` 设为 **负数** 跳过追加, 不要在 SQL 里手写 `LIMIT 1`。

#### 5. 多语句 / DML 严格禁止

模板必须是**单个 SELECT** 语句, 不允许 `;` 分号拼多句, 不允许 `INSERT/UPDATE/DELETE/DDL`。即使审核漏过, 兜底防线还有: 租户 DB 应用的是只读账号 (`connector_readonly`), 写操作会被 PG 在执行层拒绝。

#### 6. PREMIUM 租户的 `customSql` 同样适用以上 5 条

custom_sql 走的是同一个 [DatabaseAdapter](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/adapter/DatabaseAdapter.java), 同一个 [SqlWhitelistValidator](src/main/java/com/sea/star/ai/ec/enterprise/connector/service/security/SqlWhitelistValidator.java) AST 校验。规则一致, 区别只是写入位置 (`tenant_action_config.custom_sql` 而非 `action_template.sql_template`)。

---

## 4. 授权 action 给租户 (Admin API, Phase 6 新增)

Phase 6 **必须显式授权**才能调用。无授权 → `ACTION_NOT_AUTHORIZED` (403)。

### 方式 A: 单条授权

```
POST {{baseUrl}}/admin/tenants/{{tenantId}}/actions/queryOrder/grant
Headers:
  X-API-Key: {{adminApiKey}}
  Content-Type: application/json

Body (JSON):
```

```json
{
  "templateId": 1
}
```

`templateId` 是上一步返回的自增 ID。其他字段全部可选:
- `datasourceNameOverride` — 覆盖模板的默认 datasourceName
- `customSql` — PREMIUM 租户用, 过白名单校验
- `enabled` — 默认 `true`, 传 `false` 则等价未授权

### 方式 B: 批量授权 (推荐)

```
POST {{baseUrl}}/admin/tenants/{{tenantId}}/actions/grant-all-defaults
Headers:
  X-API-Key: {{adminApiKey}}
```

一键把所有 enabled 模板授权给该租户。**对应 ds 不存在的模板会跳过**, 返回实际新增的 action 列表。

### 期望响应

```json
{ "success": true, "data": ["queryOrder"] }
```

### 其他操作

```
GET    /admin/tenants/{tid}/action-configs                  列授权
GET    /admin/tenants/{tid}/actions/{action}/grant          查单条
PUT    /admin/tenants/{tid}/actions/{action}/grant          改配置 (custom_sql / override / enabled)
DELETE /admin/tenants/{tid}/actions/{action}/grant          撤销授权 (物理删)
```

---

## 5. 重启应用让 MCP 工具生效

```bash
./mvnw.cmd spring-boot:run
```

**依然需要重启** (Phase 6 没改这个限制)。启动日志:

```
INFO  c.s.s.a.e.e.c.m.DynamicMcpToolProvider - MCP 启动: 动态注册 1 个 Tool
```

> 未来 Phase 7+ 可做模板变更事件 → 热重建 ToolCallbackProvider, 不用重启。

---

## 6. 通过 MCP 协议调用 `queryOrder`

服务端用 **MCP 2025-03-26 标准的 Streamable HTTP transport** (单端点 `POST /mcp`, 同步请求-响应; 不再是旧的 SSE+HTTP 双端点+sessionId 模式).

### Streamable HTTP 协议关键点

切换到 Streamable HTTP 后, 请求和响应都是普通的 HTTP request/response, **Postman / 任何 HTTP 客户端都能直接用**. 但有两个**协议要求**必须满足:

1. **`Mcp-Session-Id` 头** (sessionId 没消失, 只是搬到了 HTTP 头里)
   - 第一次 `initialize` 请求**不带**这个头
   - 服务端在 initialize 的**响应头**返回 `Mcp-Session-Id: <UUID>`
   - 之后所有请求 (notifications/initialized, tools/list, tools/call) **必须在请求头带** `Mcp-Session-Id: <UUID>`, 否则服务端报 "Session ID missing"

2. **`Accept` 头必须含两个 media type**
   - `Accept: application/json, text/event-stream` (两个都要在, Postman 自动注入的 `*/*` 不算)

> ⚠️ **Postman 坑提醒**: Streamable transport 服务端**严格要求** `Accept: application/json, text/event-stream` (两个 media type 都要在). Postman 默认会自动注入 `Accept: */*` 覆盖你手工配的值, 导致请求被服务端 406 拒绝, 报 "Invalid Accept headers".
>
> **修复 1 (推荐)**: 直接用本仓库 [docs/enterprise-connector.postman_collection.json](enterprise-connector.postman_collection.json), 它已经在 §5 / §6 文件夹层级加了 Pre-request Script 强制覆盖 Accept 头.
>
> **修复 2**: 单个请求自己加 Pre-request Script (Headers 标签页旁边):
> ```javascript
> pm.request.headers.upsert({key: 'Accept', value: 'application/json, text/event-stream'});
> ```
>
> **修复 3**: Headers 标签页右上角的"X hidden"灰字, 展开后**取消勾选 `Accept: */*`** 那行让我们手工配的生效.

---

### curl 实操 (推荐, 不依赖任何 SDK)

#### Windows CMD 版本

```cmd
set "TOKEN=local-dev-mcp-token-change-in-prod"
set "TENANT=merchant_a"

REM Step 1: initialize — 用 -i 让响应包含 headers, 从中抓 Mcp-Session-Id
curl.exe -i -X POST "http://localhost:8089/mcp" -H "Authorization: Bearer %TOKEN%" -H "X-Tenant-Id: %TENANT%" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"cli\",\"version\":\"1.0\"}}}"

REM 把响应里 "Mcp-Session-Id: xxx" 那行的 xxx 复制下来手工设到 SID
set "SID=PASTE_FROM_PREVIOUS_RESPONSE_HEADERS"

REM Step 2: notifications/initialized (通知, 无 id, 服务端返回 202)
curl.exe -X POST "http://localhost:8089/mcp" -H "Authorization: Bearer %TOKEN%" -H "X-Tenant-Id: %TENANT%" -H "Mcp-Session-Id: %SID%" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"

REM Step 3: tools/list — 响应直接在 HTTP body 里返回, 含合并后的 inputSchema
curl.exe -X POST "http://localhost:8089/mcp" -H "Authorization: Bearer %TOKEN%" -H "X-Tenant-Id: %TENANT%" -H "Mcp-Session-Id: %SID%" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"

REM Step 4: queryOrder 调用
curl.exe -X POST "http://localhost:8089/mcp" -H "Authorization: Bearer %TOKEN%" -H "X-Tenant-Id: %TENANT%" -H "Mcp-Session-Id: %SID%" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"queryOrder\",\"arguments\":{\"tenantId\":\"merchant_a\",\"userId\":\"U100\"}}}"
```

> **CMD 转义规则速查**: 单行命令外层 `"..."`, 内部所有 `"` 改成 `\"`。如果 JSON 里出现 `%` (字面百分号), 写成 `%%`。
> **不想转义?** 把每段 JSON 存成 `step1.json` 等文件, 用 `-d @step1.json` 引用。

#### Windows PowerShell 版本

```powershell
$TOKEN = "local-dev-mcp-token-change-in-prod"
$TENANT = "merchant_a"

# Step 1: initialize — 用 Invoke-WebRequest 拿到响应头, 自动提取 Mcp-Session-Id
$resp = Invoke-WebRequest -Method POST "http://localhost:8089/mcp" `
    -Headers @{
        "Authorization"="Bearer $TOKEN";
        "X-Tenant-Id"=$TENANT;
        "Accept"="application/json, text/event-stream"
    } `
    -ContentType "application/json" `
    -Body '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}'
$SID = $resp.Headers["Mcp-Session-Id"]
Write-Host "SID = $SID"
Write-Host $resp.Content

# Step 2: notifications/initialized
curl.exe -X POST "http://localhost:8089/mcp" `
     -H "Authorization: Bearer $TOKEN" `
     -H "X-Tenant-Id: $TENANT" `
     -H "Mcp-Session-Id: $SID" `
     -H "Content-Type: application/json" `
     -H "Accept: application/json, text/event-stream" `
     -d '{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}'

# Step 3: tools/list
curl.exe -X POST "http://localhost:8089/mcp" `
     -H "Authorization: Bearer $TOKEN" `
     -H "X-Tenant-Id: $TENANT" `
     -H "Mcp-Session-Id: $SID" `
     -H "Content-Type: application/json" `
     -H "Accept: application/json, text/event-stream" `
     -d '{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}'

# Step 4: queryOrder
curl.exe -X POST "http://localhost:8089/mcp" `
     -H "Authorization: Bearer $TOKEN" `
     -H "X-Tenant-Id: $TENANT" `
     -H "Mcp-Session-Id: $SID" `
     -H "Content-Type: application/json" `
     -H "Accept: application/json, text/event-stream" `
     -d '{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"queryOrder\",\"arguments\":{\"tenantId\":\"merchant_a\",\"userId\":\"U100\"}}}'
```

#### Bash 版本 (Git Bash / Linux / macOS)

```bash
TOKEN="local-dev-mcp-token-change-in-prod"
TENANT="merchant_a"

# Step 1: initialize — -D - 把响应 headers 打印到 stdout, grep 抓 Mcp-Session-Id
SID=$(curl -s -D - -X POST "http://localhost:8089/mcp" \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}' \
     -o /dev/null \
     | grep -i 'mcp-session-id' | awk '{print $2}' | tr -d '\r')
echo "SID=$SID"

# Step 2: notifications/initialized
curl -X POST "http://localhost:8089/mcp" \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT" \
     -H "Mcp-Session-Id: $SID" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# Step 3: tools/list
curl -X POST "http://localhost:8089/mcp" \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT" \
     -H "Mcp-Session-Id: $SID" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# Step 4: queryOrder
curl -X POST "http://localhost:8089/mcp" \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT" \
     -H "Mcp-Session-Id: $SID" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"queryOrder","arguments":{"tenantId":"merchant_a","userId":"U100"}}}'
```

> **MCP handshake 协议说明**: `initialize` 是**请求** (request, 带 `id`, 期待 response, 服务端 HTTP body 直接返回 capabilities)。`notifications/initialized` 是**通知** (notification, 无 `id`, 无 response, 仅告知服务端"客户端准备好了")。这是 MCP 协议规定的两步必经流程 — 跳过 Step 2 直接发 `tools/list` 会被拒。
>
> **X-Tenant-Id 头**: 可选, 但**带上能让 AI 看到合并后 (template.paramSchema ∪ tenant.customParams) 的 schema** — PerTenantToolCallbackProvider 在每次 tools/list 时按这个头反查租户级 customParams 字段. 不带时退回全局视图 (只有 template.paramSchema).

Streamable HTTP 模式下, 每条 POST 的响应**直接是 HTTP body** (Content-Type 通常是 `text/event-stream` 或 `application/json`, 取决于服务端). tools/call 成功响应示例:

```
event: message
data: {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"success\":true,\"data\":[{\"order_id\":\"ORD001\",...}]}"}]}}
```

(notifications/* 通知类请求服务端返回 202 Accepted 空 body, 这是协议规定, 不是错误.)

### 方法 B: MCP Inspector (推荐, 可视化, 适合演示)

[MCP 官方提供的图形化调试工具](https://github.com/modelcontextprotocol/inspector), 一行命令启动 Web UI:

```bash
npx @modelcontextprotocol/inspector
```

打开浏览器后:
1. **Transport Type**: 选 `Streamable HTTP` (新版本 Inspector 已支持)
2. **URL**: `http://localhost:8089/mcp`
3. **Auth**: 选 Bearer Token, 填 `local-dev-mcp-token-change-in-prod`
4. **Custom Headers**: 加 `X-Tenant-Id: merchant_a` (可选, 但加上才能看到 customParams 合并后的 schema)
5. 点 **Connect** → 自动完成 handshake + tools/list
6. 在 **Tools** 面板选 `queryOrder` → 填 arguments → **Run**

**优点**: 完全 GUI, 单端点 POST 直接通 — 实测**最适合演示给非技术 stakeholder**。
**缺点**: 需要 Node.js 环境。

### 方法 C: 内置一个简单的 Python/Node 测试客户端

如果你团队不想装 Node, 可以放一个 ~30 行的 Python 脚本到 `scripts/test_mcp.py`, 启动后自动:
1. 单端点 POST /mcp 发 initialize → notifications/initialized → tools/list → tools/call queryOrder
2. 解析 HTTP 响应 body 打印结果 (Streamable HTTP 同步返回, 不再需要 SSE 流处理)

这个脚本目前**没写**, 是 Phase 7 待办项。

---

### Phase 6 新错误码语义

| 场景 | Phase 5 行为 | Phase 6 行为 |
|-----|------|------|
| 未授权的 action 被调用 | 模板存在就能调 | **403 ACTION_NOT_AUTHORIZED** |
| 租户无对应 ds | `tenant_config.db_url=NULL` → 报 ADAPTER_DB_ERROR | **404 DATASOURCE_NOT_FOUND** |
| 租户 `enabled=false` | 进 rate limiter 后再失败 | **fail-fast 在 rate limiter 之前** (TenantStatusGuard) |
| 软删的租户 | (Phase 5 无软删) | 直接当 404 TENANT_NOT_FOUND |

---

### tools/call 请求格式参考

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "queryOrder",
    "arguments": {
      "tenantId": "merchant_a",
      "userId": "U100"
    }
  }
}
```

成功响应 (HTTP body 直接返回):
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"success\":true,\"code\":\"OK\",\"data\":[{\"order_id\":\"ORD001\",\"user_id\":\"U100\",\"amount\":99.00,\"status\":\"PAID\",\"created_at\":\"...\"},{\"order_id\":\"ORD002\",...}]}"
    }]
  }
}
```

`result.content[0].text` 里嵌套的就是连接器返回的 `UnifiedResult` JSON。

---

## 7. 审计日志 & 故障排查

### 7.1 每次调用都有审计

```sql
SELECT log_id, tenant_id, action, caller_identity,
       result_summary, duration_ms, trace_id, created_at
FROM audit_log
WHERE tenant_id = 'merchant_a'
ORDER BY created_at DESC LIMIT 10;
```

### 7.2 Prometheus 指标

```bash
curl http://localhost:8089/actuator/prometheus | grep connector_
```

### 7.3 Phase 6 新增错误场景

| 症状 | 可能原因 | 排查 |
|------|---------|------|
| 403 ACTION_NOT_AUTHORIZED | 租户没给该 action 授权 | `GET /admin/tenants/{tid}/action-configs` 查授权清单 |
| 404 DATASOURCE_NOT_FOUND | 租户对应的 dsName 没配 | `GET /admin/datasources/{tid}` 查租户所有 ds |
| 500 "模板和数据源 access_type 不匹配" | template 是 DB 类, ds 是 API 类 (或反之) | 重新查 template 和 ds 的 accessType |

---

## 附录 A: Postman 环境变量配置

| VARIABLE | INITIAL VALUE |
|----------|---------------|
| baseUrl | `http://localhost:8089` |
| adminApiKey | `local-dev-admin-api-key-change-in-prod` |
| purgeApiKey | `local-dev-purge-api-key-DO-NOT-USE-IN-PROD` |
| mcpToken | `local-dev-mcp-token-change-in-prod` |
| tenantId | `merchant_a` |
| dsName | `default` |
| ~~sessionId~~ | (Streamable HTTP 不再需要, 已废弃此变量) |

---

## 附录 B: 常见错误码速查

| code | HTTP | 什么情况 |
|------|------|---------|
| `OK` | 200 | 成功 |
| `PARAM_INVALID` | 400 | 参数不符合 paramSchema / Jakarta Validation |
| `SQL_INVALID` | 400 | custom_sql 被 SqlWhitelistValidator 拒 |
| `TENANT_NOT_FOUND` | 404 | 租户不存在或已软删 |
| `TENANT_DISABLED` | 403 | `tenant_config.enabled=false` |
| `DATASOURCE_NOT_FOUND` | 404 | `tenant_datasource` 里查不到该 (tid, dsName) **(Phase 6 新增)** |
| `TEMPLATE_NOT_FOUND` | 404 | `action` 没对应的 template, 或 template 禁用 |
| `ACTION_NOT_AUTHORIZED` | 403 | 租户未授权调用该 action **(Phase 6 新增)** |
| `UNAUTHORIZED` | 401 | X-API-Key / Bearer Token / X-Purge-Api-Key 错 |
| `RATE_LIMIT_EXCEEDED` | 429 | QPS 超限 |
| `DUPLICATE_REQUEST` | 409 | 幂等: 同 requestId 5 分钟内复用 |
| `ADAPTER_DB_ERROR` | 500 | 租户 DB 连接 / SQL 执行失败 |
| `ADAPTER_API_ERROR` | 500 | 租户 API 调用失败 |
| `AUTH_FORBIDDEN` | 403 | 物理删端点未配置 purge-api-key 或无权限 |

---

## 附录 C: 物理删 (Purge) 双重认证

Phase 6.4 新增: 所有 `/purge` 结尾的端点需要**两个 header**:

```
X-API-Key: {{adminApiKey}}           ← 所有 Admin 都要
X-Purge-Api-Key: {{purgeApiKey}}     ← 只 Purge 专用
```

### 受保护的端点

```
DELETE /admin/tenants/{id}/purge
DELETE /admin/templates/{id}/purge
DELETE /admin/datasources/{tid}/{dsName}/purge
```

### 为什么两个 key 分离

- 持有 `admin-api-key` 的人日常能做的: 创建 / 更新 / 软删 / restore (**可恢复**)
- 持有 `admin-purge-api-key` 的人额外能做: 物理删 (**不可恢复**)
- 即使 admin-api-key 泄漏, 物理删能力仍受保护
- 建议生产环境 purge-api-key **只给 DBA / SRE 少数人**, 日常运维拿不到

### 部署时如果不想开放物理删能力

**不设** `ADMIN_PURGE_API_KEY` 环境变量 → `AuthenticationService.verifyPurge()` 会抛 `AUTH_FORBIDDEN`, 所有 `/purge` 端点被拒绝。相当于关闭物理删功能, 只留软删。

---

## 附录 D: 商户接入约束 SOP (异构 schema 适配)

**为什么有这个 SOP**: 不同商户的数据库 schema / API 结构往往不一致 (字段名 / 表名 / 嵌套结构), 但模板是**全局共享的一份**。直接跑会"列不存在 / 字段缺失" 报错, AI 也读不懂异构返回。

**核心原则**: 接入方 (商户) 负责把数据形态适配成连接器约定的标准 schema。**平台不为每个客户写定制代码** — 这是 SaaS 平台跟单租户系统的本质区别。

### D.1 DB 类租户接入清单

商户接入连接器作为 DB 类数据源时, 需在自家 DB 完成:

```
☑ 1. 创建只读账号
   CREATE ROLE connector_readonly WITH LOGIN PASSWORD '<由我方提供>';
   GRANT CONNECT ON DATABASE <商户库> TO connector_readonly;
   GRANT USAGE ON SCHEMA <schema> TO connector_readonly;

☑ 2. 暴露符合连接器 standard schema 的表 / VIEW
   连接器 actions 约定的标准 schema (示例):
     - orders     (字段: order_id / user_id / amount / status / created_at)
     - customers  (字段: customer_id / name / phone / created_at)
     - inventory  (字段: sku / stock_count / updated_at)

☑ 3. 自家字段不一致时建 VIEW 映射
   示例 — 商户 DB 字段叫 order_no / total_amount:
   
   CREATE VIEW orders AS
   SELECT order_no       AS order_id,
          user_no        AS user_id,
          total_amount   AS amount,
          order_status   AS status,
          create_time    AS created_at
   FROM t_order;

☑ 4. GRANT SELECT 给 connector_readonly
   GRANT SELECT ON orders TO connector_readonly;
   ALTER DEFAULT PRIVILEGES IN SCHEMA <schema> GRANT SELECT ON TABLES TO connector_readonly;

☑ 5. 字段类型约束:
   - 主键字段: VARCHAR / BIGINT, 不接受 ENUM / 自定义类型
   - 时间字段: TIMESTAMP / DATETIME, 不接受 BIGINT 时间戳 (除非建 view 转换)
   - 金额字段: DECIMAL(N,2), 不接受 VARCHAR
   - JSONB / JSON 字段: 暂不支持作为查询返回 (Phase 7+)

☑ 6. (可选) 暴露 OpenAPI 描述, 我方接入团队做 contract test
```

### D.2 API 类租户接入清单

商户接入连接器作为 HTTP API 类数据源时:

```
☑ 1. API 必须返回符合 standard schema 的 JSON
   GET /orders/{orderId} → 200 OK
   {
     "orderId":   string,         // 必填
     "userId":    string,         // 必填  
     "amount":    number,         // DECIMAL, 必填
     "status":    string,         // PAID/REFUNDED/PENDING, 必填
     "createdAt": string (ISO8601 with timezone)
   }

☑ 2. 错误响应使用标准 HTTP status + JSON error body
   404 → { "error": "ORDER_NOT_FOUND", "message": "..." }
   500 → { "error": "INTERNAL_ERROR" }

☑ 3. 自家 API 不一致时, 商户**自起 BFF / API Gateway**做适配:
   
   商户内部 (异构):  GET /v2/api/orderQuery?orderNo=X1
                  → { "code": 0, "data": { "order_no": "X1", "total": 99.00, ... } }
                              ↓
                      商户的 BFF 适配层 (商户自己实现)
                              ↓
   连接器调用:        GET /standard-orders/{orderId}
                  → { "orderId": "X1", "amount": 99.00, ... }

☑ 4. API 须支持以下认证方式之一: BEARER token / BASIC auth / API Key (header)

☑ 5. 响应时间 < 5s (默认 timeout); 复杂场景需联系我方调整
   
☑ 6. 响应体大小 < 5MB (sys_dict.limit.max_api_response_size_mb)
```

### D.3 不接受标准 schema 的"重客户" → PREMIUM Tier

如果商户:
- 自家 DB 完全不能动 (合规 / 流程 / 历史包袱)
- 自家 API 完全不能起 BFF (运维资源不足)

**走 PREMIUM tier**, 由我方运营定制:
- DB 类: `tenant_action_config.custom_sql` 一户一份 SQL (经 SqlWhitelistValidator 校验)
- API 类: 未来扩展 `responseFieldMap` JsonPath 转换 (Phase 7+ 待做)

PREMIUM 接入费 / 月费按"定制 + 维护成本"定价。

### D.4 接入团队工作清单 (我方)

```
新商户接入 SOP:
☑ 1. 跟商户对齐选择路径: 标准 schema / PREMIUM 定制
☑ 2. 提供 schema 文档 + 示例 (orders / customers / ...)
☑ 3. 商户完成自适配后, 接入团队做冒烟测试:
    - 用 connector_readonly 账号连商户 DB / 调商户 API
    - 拉一条样例数据验证字段全存在 + 类型正确
☑ 4. 通过 Admin API 创建 tenant + datasource + 授权 actions
☑ 5. 用 Postman 走通 1-2 个 MCP 调用, 确认端到端 OK
☑ 6. 通知商户在自己 OpenClaw 后台绑定渠道
☑ 7. 邀请商户做最终验证 (微信 / 钉钉 发消息测试)
```

### D.5 设计哲学

> "**平台定义数据契约, 接入方对齐契约**" 是 SaaS 平台保持可扩展性的关键设计。
>
> Stripe、Shopify、阿里云 RDS 都这么做。任何"为某个客户写定制 SQL / 适配代码"的诱惑都要抵制 — 一旦放开, 平台就在为每个客户写胶水, 不再是 SaaS, 而是定制开发外包。

详细架构论证见 [PROJECT_BRIEF.md 决策 #8](./PROJECT_BRIEF.md#8-租户接入边界-数据形态适配是商户的责任-不是平台的)。

---

_文档生成时间: 2026-04-23_
_对应代码版本: Phase 6 完成_
_相关文档: [CLAUDE.md](../CLAUDE.md) / [PROJECT_BRIEF.md](./PROJECT_BRIEF.md) / [DEVELOPMENT_PLAN.md](./DEVELOPMENT_PLAN.md)_
