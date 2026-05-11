-- =============================================================================
-- Demo 租户业务库 seed - 用于演示 queryOrder / countOrders 等模板
-- PostgreSQL 16+
--
-- 适用场景:
--   1. 本地开发: 跑通 MCP queryOrder 端到端流程
--   2. 新租户 onboarding 演示: 提供一个标准 schema 让租户参考
--
-- 与连接器自身 schema (sql/01_create_tables.sql) 完全分离:
--   连接器自身 = 元数据库 (tenant_config / action_template / async_task / ...)
--   本脚本    = 租户业务库 (orders / customers / ...) 由租户运维负责
--
-- 使用方法 (以 merchant_a 租户为例, 库名 merchant_a_db):
--
--   # 1. 用 DBA 账号 (postgres) 创建租户业务库 + 只读账号
--   psql -U postgres -h localhost -c "CREATE DATABASE merchant_a_db;"
--   psql -U postgres -h localhost -c "CREATE ROLE connector_readonly LOGIN PASSWORD 'change_me_in_prod';"
--
--   # 2. 切到业务库, 跑本脚本
--   psql -U postgres -h localhost -d merchant_a_db -f sql/05_seed_demo_tenant_db.sql
--
--   # 3. 在 tenant_datasource 表里登记:
--   INSERT INTO tenant_datasource (tenant_id, ds_name, access_type, jdbc_url, username, password, ...)
--   VALUES ('merchant_a', 'default', 'POSTGRES',
--           'jdbc:postgresql://localhost:5432/merchant_a_db?stringtype=unspecified',
--           'connector_readonly', '<encrypted password>', ...);
--   注: 通过 Admin API POST /admin/datasources 创建时, password 由服务端 EncryptionUtils 加密
--   注 2: access_type 取 POSTGRES / MYSQL / SQLSERVER / ORACLE / API; 旧值 'DB' 在 02_migrate_access_type.sql 中迁移
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 订单表: queryOrder / countOrders 模板的数据来源
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    order_id    VARCHAR(32)    PRIMARY KEY,
    user_id     VARCHAR(32)    NOT NULL,
    amount      DECIMAL(12,2)  NOT NULL,
    status      VARCHAR(20)    NOT NULL,         -- PAID / REFUNDED / PENDING / CANCELLED
    created_at  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status  ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created ON orders(created_at DESC);

COMMENT ON TABLE  orders             IS '订单表 (demo) — queryOrder / countOrders 模板查询此表';
COMMENT ON COLUMN orders.order_id    IS '订单号, 全局唯一';
COMMENT ON COLUMN orders.user_id     IS '下单用户 ID';
COMMENT ON COLUMN orders.amount      IS '订单金额 (元)';
COMMENT ON COLUMN orders.status      IS '订单状态: PAID / REFUNDED / PENDING / CANCELLED';

-- -----------------------------------------------------------------------------
-- 样本数据: 至少覆盖三种 status, 多个 user_id, 用于多种过滤组合验证
-- -----------------------------------------------------------------------------
INSERT INTO orders (order_id, user_id, amount, status, created_at) VALUES
    ('ORD001', 'U100',  99.00, 'PAID',     CURRENT_TIMESTAMP - INTERVAL '7 days'),
    ('ORD002', 'U100',  50.00, 'REFUNDED', CURRENT_TIMESTAMP - INTERVAL '5 days'),
    ('ORD003', 'U100', 200.00, 'PAID',     CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('ORD004', 'U200', 150.00, 'PAID',     CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('ORD005', 'U200',  30.00, 'PENDING',  CURRENT_TIMESTAMP - INTERVAL '1 days'),
    ('ORD006', 'U300', 500.00, 'CANCELLED',CURRENT_TIMESTAMP - INTERVAL '12 hours'),
    ('ORD007', 'U300',  88.00, 'PAID',     CURRENT_TIMESTAMP - INTERVAL '6 hours'),
    ('ORD008', 'U400', 320.00, 'PAID',     CURRENT_TIMESTAMP - INTERVAL '2 hours'),
    ('ORD009', 'U400',  45.00, 'PENDING',  CURRENT_TIMESTAMP - INTERVAL '30 minutes'),
    ('ORD010', 'U500', 999.00, 'PAID',     CURRENT_TIMESTAMP - INTERVAL '5 minutes')
ON CONFLICT (order_id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 授权: 给 connector_readonly 只读权限
-- -----------------------------------------------------------------------------
-- 先确保角色存在; 不存在直接报错阻断 — 否则后续 GRANT 会失败但表已建好, 留下"权限不够"的坑
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'connector_readonly') THEN
        RAISE EXCEPTION 'Role connector_readonly 不存在。请先用 DBA 账号执行: CREATE ROLE connector_readonly LOGIN PASSWORD ''<your-password>'';';
    END IF;
END $$;

-- 授本表 SELECT
GRANT SELECT ON orders TO connector_readonly;
-- 后续在本库新建的表自动给 SELECT (避免每加一张表都要 GRANT)
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO connector_readonly;
-- public schema 本身需要 USAGE
GRANT USAGE ON SCHEMA public TO connector_readonly;

-- -----------------------------------------------------------------------------
-- 验证 (可选): 用 connector_readonly 账号能查到 10 条记录
-- -----------------------------------------------------------------------------
-- psql -U connector_readonly -h localhost -d merchant_a_db -c "SELECT count(*) FROM orders;"
-- 期望输出: 10
