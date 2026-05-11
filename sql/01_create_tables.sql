-- =============================================================================
-- Enterprise Connector - Schema DDL
-- PostgreSQL 16+
-- Phase 6 版本: 多数据源 + 动作授权白名单 + 软删
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 租户配置表: 存储租户身份 + 租户级策略 (数据源拆到 tenant_datasource)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant_config (
    tenant_id       VARCHAR(50)  PRIMARY KEY,
    tenant_name     VARCHAR(100) NOT NULL,
    tier            VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    rate_limit_qps  INT          DEFAULT 10,

    enabled         BOOLEAN      DEFAULT TRUE,
    deleted         BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tenant_enabled ON tenant_config(enabled) WHERE deleted = FALSE;

COMMENT ON TABLE  tenant_config                IS '租户身份 + 租户级策略 (QPS 上限等). 数据源拆到 tenant_datasource';
COMMENT ON COLUMN tenant_config.tenant_id      IS '租户唯一标识, 字母数字下划线连字符, 最长 50';
COMMENT ON COLUMN tenant_config.tenant_name    IS '租户显示名 (中文)';
COMMENT ON COLUMN tenant_config.tier           IS '套餐等级: STANDARD=标准(用预设模板) / PREMIUM=高级(可在 tenant_action_config 配 custom_sql)';
COMMENT ON COLUMN tenant_config.rate_limit_qps IS '单租户 QPS 上限 (跨所有 datasource 共享), 超限返回 429';
COMMENT ON COLUMN tenant_config.enabled        IS '是否可用; false 时所有业务调用抛 TENANT_DISABLED (403)';
COMMENT ON COLUMN tenant_config.deleted        IS '软删标志 (Flex 自动过滤 deleted=false)';
COMMENT ON COLUMN tenant_config.created_at     IS '创建时间';
COMMENT ON COLUMN tenant_config.updated_at     IS '最近更新时间';

-- -----------------------------------------------------------------------------
-- 2. 租户数据源表: 一个租户可以有多个数据源 (订单库 / 库存库 / CRM API 等)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant_datasource (
    tenant_id       VARCHAR(50)  NOT NULL,
    ds_name         VARCHAR(50)  NOT NULL,
    access_type     VARCHAR(10)  NOT NULL
        CHECK (access_type IN ('POSTGRES', 'MYSQL', 'ORACLE', 'SQLSERVER', 'API')),

    db_url          VARCHAR(500),
    db_username     VARCHAR(100),
    db_password_enc VARCHAR(500),
    db_driver       VARCHAR(100) DEFAULT 'org.postgresql.Driver',

    api_base_url    VARCHAR(500),
    api_auth_type   VARCHAR(20),
    api_token_enc   VARCHAR(500),
    api_headers     JSONB,

    enabled         BOOLEAN      DEFAULT TRUE,
    deleted         BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, ds_name)
    -- 故意不加 FK 到 tenant_config: 级联软删靠 Service 层同事务处理,
    -- 避免 ON DELETE CASCADE 在 purge 时变成数据黑洞
);
CREATE INDEX IF NOT EXISTS idx_tenant_ds_tenant ON tenant_datasource(tenant_id) WHERE deleted = FALSE;

COMMENT ON TABLE  tenant_datasource                 IS '租户数据源: 复合主键 (tenant_id, ds_name); 默认 ds_name=default, 可选 orders/inventory/crm_api 等';
COMMENT ON COLUMN tenant_datasource.tenant_id       IS '租户 ID (对应 tenant_config.tenant_id, 不加 FK 方便 purge)';
COMMENT ON COLUMN tenant_datasource.ds_name         IS '数据源名 (逻辑契约), 被 action_template.datasource_name 引用; 默认值 default';
COMMENT ON COLUMN tenant_datasource.access_type     IS '接入类型 + 方言: POSTGRES / MYSQL / ORACLE / SQLSERVER / API; 一个字段同时表达打 JDBC 还是 HTTP 以及 DB 方言';
COMMENT ON COLUMN tenant_datasource.db_url          IS 'JDBC URL, access_type 是 DB 家族 (POSTGRES/MYSQL/ORACLE/SQLSERVER) 时必填';
COMMENT ON COLUMN tenant_datasource.db_username     IS '数据库账号, 建议用只读账号 (兜底防线)';
COMMENT ON COLUMN tenant_datasource.db_password_enc IS 'DB 密码密文 (AES-256-GCM, Base64 编码)';
COMMENT ON COLUMN tenant_datasource.db_driver       IS 'JDBC 驱动类名: POSTGRES=org.postgresql.Driver, MYSQL=com.mysql.cj.jdbc.Driver, SQLSERVER=com.microsoft.sqlserver.jdbc.SQLServerDriver, ORACLE=oracle.jdbc.OracleDriver(驱动需自行加 pom)';
COMMENT ON COLUMN tenant_datasource.api_base_url    IS 'HTTP API 基础 URL, access_type=API 时必填';
COMMENT ON COLUMN tenant_datasource.api_auth_type   IS 'API 认证方式: BEARER / BASIC / API_KEY / NONE';
COMMENT ON COLUMN tenant_datasource.api_token_enc   IS 'API 凭证密文, 格式同 db_password_enc';
COMMENT ON COLUMN tenant_datasource.api_headers     IS '固定附加 Header (JSONB)';
COMMENT ON COLUMN tenant_datasource.enabled         IS '临时切断开关, false 时调用抛 DATASOURCE_DISABLED';
COMMENT ON COLUMN tenant_datasource.deleted         IS '软删标志 (Flex 自动过滤 deleted=false)';
COMMENT ON COLUMN tenant_datasource.created_at      IS '创建时间';
COMMENT ON COLUMN tenant_datasource.updated_at      IS '最近更新时间';

-- -----------------------------------------------------------------------------
-- 3. 操作模板表: 预定义 SQL/API 模板 (团队审核后入库)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS action_template (
    template_id       SERIAL       PRIMARY KEY,
    action            VARCHAR(50)  NOT NULL,
    access_type       VARCHAR(10)  NOT NULL
        CHECK (access_type IN ('POSTGRES', 'MYSQL', 'ORACLE', 'SQLSERVER', 'API')),
    name              VARCHAR(100) NOT NULL,
    description       TEXT,

    datasource_name   VARCHAR(50)  NOT NULL DEFAULT 'default',

    sql_template      TEXT,
    api_path          VARCHAR(200),
    api_method        VARCHAR(10)  DEFAULT 'GET',
    api_body_template TEXT,

    param_schema      JSONB,

    max_rows          INT     DEFAULT 500,
    is_long_running   BOOLEAN DEFAULT FALSE,
    timeout_seconds   INT     DEFAULT 5,

    enabled           BOOLEAN   DEFAULT TRUE,
    deleted           BOOLEAN   DEFAULT FALSE,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_template_action_type
    ON action_template(action, access_type) WHERE deleted = FALSE;

COMMENT ON TABLE  action_template                   IS '操作模板表: 由开发/运维团队维护的预审 SQL / HTTP 模板';
COMMENT ON COLUMN action_template.template_id       IS '自增主键';
COMMENT ON COLUMN action_template.action            IS 'AI 侧 Tool 名 (queryOrder / countOrders 等), MCP 全局唯一';
COMMENT ON COLUMN action_template.access_type       IS '接入类型 + 方言: POSTGRES / MYSQL / ORACLE / SQLSERVER 时填 sql_template, API 时填 api_path; 唯一索引 (action, access_type) 自动允许同 action 多方言版本共存';
COMMENT ON COLUMN action_template.name              IS '模板中文名';
COMMENT ON COLUMN action_template.description       IS '业务描述, 作为 MCP Tool description 暴露给 AI';
COMMENT ON COLUMN action_template.datasource_name   IS '打哪个逻辑数据源 (引用 tenant_datasource.ds_name); 默认 default';
COMMENT ON COLUMN action_template.sql_template      IS '命名参数 SQL 模板, :paramName 占位 (Spring NamedParameterJdbcTemplate 语法, 非 MyBatis 的 #{})';
COMMENT ON COLUMN action_template.api_path          IS 'HTTP 路径, 可带 {var} 路径变量';
COMMENT ON COLUMN action_template.api_method        IS 'HTTP 方法';
COMMENT ON COLUMN action_template.api_body_template IS 'POST/PUT 请求体模板 (JSON), 走 JSON 树结构化替换防注入';
COMMENT ON COLUMN action_template.param_schema      IS '参数 schema (JSONB), 双用途: ParamValidator 运行时校验 + MCP inputSchema 生成';
COMMENT ON COLUMN action_template.max_rows          IS 'SELECT 结果行数上限 (事后校验, 超出记 WARN 不抛); -1 跳过校验 (聚合查询); LIMIT 由模板手写, 不再由 DatabaseAdapter 自动追加';
COMMENT ON COLUMN action_template.is_long_running   IS 'true=异步 (AsyncTaskService)';
COMMENT ON COLUMN action_template.timeout_seconds   IS '单次执行超时 (秒)';
COMMENT ON COLUMN action_template.enabled           IS '业务开关 (支持灰度: 新模板先 false 测, 再 true 放出)';
COMMENT ON COLUMN action_template.deleted           IS '软删标志 (Flex 自动过滤 deleted=false)';
COMMENT ON COLUMN action_template.created_at        IS '创建时间';
COMMENT ON COLUMN action_template.updated_at        IS '最近更新时间';

-- -----------------------------------------------------------------------------
-- 4. 租户操作授权表: 升级为"必要授权白名单"; 物理删 (撤销就该干净)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant_action_config (
    tenant_id                VARCHAR(50) NOT NULL,
    action                   VARCHAR(50) NOT NULL,
    template_id              INT         NOT NULL,

    datasource_name_override VARCHAR(50),
    custom_sql               TEXT,
    custom_api_path          VARCHAR(200),
    custom_params            JSONB,

    enabled                  BOOLEAN   DEFAULT TRUE,
    granted_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, action)
    -- 不加 FK; BusinessExecutor 在调用时显式检查租户和模板
);

COMMENT ON TABLE  tenant_action_config                          IS '租户动作授权白名单: 行存在 = 获授权; 不存在 = ACTION_NOT_AUTHORIZED (403)';
COMMENT ON COLUMN tenant_action_config.tenant_id                IS '租户 ID';
COMMENT ON COLUMN tenant_action_config.action                   IS 'action 名';
COMMENT ON COLUMN tenant_action_config.template_id              IS '关联模板 ID';
COMMENT ON COLUMN tenant_action_config.datasource_name_override IS '覆盖模板的 datasource_name; NULL 时用模板默认';
COMMENT ON COLUMN tenant_action_config.custom_sql               IS 'PREMIUM 租户自定义 SQL, 必须过 SqlWhitelistValidator';
COMMENT ON COLUMN tenant_action_config.custom_api_path          IS '自定义 API 路径';
COMMENT ON COLUMN tenant_action_config.custom_params            IS '自定义参数默认值 (JSONB)';
COMMENT ON COLUMN tenant_action_config.enabled                  IS '临时撤销授权而保留配置 (false = 如同未授权)';
COMMENT ON COLUMN tenant_action_config.granted_at               IS '授权时间 (审计用)';

-- -----------------------------------------------------------------------------
-- 5. 异步任务表: 长任务生命周期记录, 物理删 + TTL 清理
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS async_task (
    task_id         VARCHAR(50)  PRIMARY KEY,
    tenant_id       VARCHAR(50)  NOT NULL,
    action          VARCHAR(50)  NOT NULL,
    params          JSONB,
    status          VARCHAR(20)  NOT NULL,
    result          JSONB,
    error_message   TEXT,

    retry_count     INT DEFAULT 0,
    max_retries     INT DEFAULT 3,

    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    timeout_at      TIMESTAMP,
    callback_url    VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_async_tenant_status ON async_task(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_async_created       ON async_task(created_at);
CREATE INDEX IF NOT EXISTS idx_async_timeout       ON async_task(status, timeout_at);

COMMENT ON TABLE  async_task                 IS '异步任务表 (物理删 + 30 天 TTL 清理)';
COMMENT ON COLUMN async_task.task_id         IS '任务唯一 ID (UUID)';
COMMENT ON COLUMN async_task.tenant_id       IS '租户 ID';
COMMENT ON COLUMN async_task.action          IS '业务操作名';
COMMENT ON COLUMN async_task.params          IS '调用参数 (JSONB), findDuplicateInflight 用 JSONB 等值去重';
COMMENT ON COLUMN async_task.status          IS 'PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT';
COMMENT ON COLUMN async_task.result          IS '成功结果 (JSONB), 仅 SUCCESS';
COMMENT ON COLUMN async_task.error_message   IS '失败原因';
COMMENT ON COLUMN async_task.retry_count     IS '已重试次数';
COMMENT ON COLUMN async_task.max_retries     IS '最大重试次数';
COMMENT ON COLUMN async_task.created_at      IS '任务提交时间';
COMMENT ON COLUMN async_task.started_at      IS '首次执行开始时间, 重试不刷新';
COMMENT ON COLUMN async_task.finished_at     IS '进入终态的时间';
COMMENT ON COLUMN async_task.timeout_at      IS '超时时刻, 扫描器用';
COMMENT ON COLUMN async_task.callback_url    IS '完成后回调的 URL (http/https), CallbackUrlValidator 防 SSRF';

-- -----------------------------------------------------------------------------
-- 6. 审计日志表: append-only, 不删不改
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    log_id          BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(50)  NOT NULL,
    action          VARCHAR(50)  NOT NULL,
    caller_identity VARCHAR(100),
    params          JSONB,
    result_summary  VARCHAR(500),
    duration_ms     INT,
    trace_id        VARCHAR(50),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_time ON audit_log(tenant_id, created_at);

COMMENT ON TABLE  audit_log                 IS '审计日志: 每次调用一条, append-only';
COMMENT ON COLUMN audit_log.log_id          IS 'BIGSERIAL 自增主键';
COMMENT ON COLUMN audit_log.tenant_id       IS '租户 ID';
COMMENT ON COLUMN audit_log.action          IS '业务操作名';
COMMENT ON COLUMN audit_log.caller_identity IS '调用方标识 (mcp / admin:xxx)';
COMMENT ON COLUMN audit_log.params          IS '请求参数快照 (JSONB), 敏感字段应先掩码';
COMMENT ON COLUMN audit_log.result_summary  IS '结果概要 (code + 前 N 字符消息)';
COMMENT ON COLUMN audit_log.duration_ms     IS '端到端耗时 (毫秒)';
COMMENT ON COLUMN audit_log.trace_id        IS 'traceId, 和应用日志 MDC 对齐';
COMMENT ON COLUMN audit_log.created_at      IS '写入时间';

-- -----------------------------------------------------------------------------
-- 7. 系统字典表: 运行时可调参数, 软删
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_dict (
    dict_key    VARCHAR(100) PRIMARY KEY,
    dict_value  VARCHAR(500) NOT NULL,
    value_type  VARCHAR(20)  NOT NULL DEFAULT 'INT',
    group_name  VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    deleted     BOOLEAN      DEFAULT FALSE,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_dict_group ON sys_dict(group_name) WHERE deleted = FALSE;

COMMENT ON TABLE  sys_dict             IS '系统字典: 运行时可调参数 (限流/超时/重试等), Pub/Sub dict:refresh 广播';
COMMENT ON COLUMN sys_dict.dict_key    IS '字典 key, 点分命名: group.item';
COMMENT ON COLUMN sys_dict.dict_value  IS '字典值, 字符串存储';
COMMENT ON COLUMN sys_dict.value_type  IS 'INT / LONG / STRING / BOOLEAN';
COMMENT ON COLUMN sys_dict.group_name  IS '分组名: limit / async / resilience / security';
COMMENT ON COLUMN sys_dict.description IS '配置含义';
COMMENT ON COLUMN sys_dict.deleted     IS '软删标志 (Flex 自动过滤 deleted=false)';
COMMENT ON COLUMN sys_dict.created_at  IS '创建时间';
COMMENT ON COLUMN sys_dict.updated_at  IS '最近更新时间';
