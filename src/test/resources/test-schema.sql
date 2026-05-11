-- =============================================================================
-- Testcontainers 初始化 DDL (Phase 6 版本)
-- 跟 sql/01_create_tables.sql 保持一致, 不同之处: 省略 COMMENT (测试用不上)
-- =============================================================================

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
);

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
);

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
