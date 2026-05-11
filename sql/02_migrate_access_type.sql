-- =============================================================================
-- 02_migrate_access_type.sql
-- 把 access_type 旧值 'DB' 回填为 'POSTGRES' (当前所有 DB 实例都是 PG).
--
-- 多方言适配后 access_type 表达 "接入方式 + 方言":
-- POSTGRES / MYSQL / ORACLE / SQLSERVER / API. CHECK 约束已在 01_create_tables.sql
-- 的 access_type 字段定义里内联声明, 本脚本仅做数据回填, 不动 DDL.
--
-- 上线步骤:
--   1. 应用先停止或切换到只读 (避免迁移期间写入旧值 'DB')
--   2. 执行本脚本 (幂等: 重复执行不会出错)
--   3. 部署新版本应用 (含扩展后的 AccessType 枚举)
--
-- 回滚: 把 'POSTGRES' UPDATE 回 'DB' (旧约束放宽到 ('DB', 'API') 时才适用)
-- =============================================================================

BEGIN;

-- 现有数据回填: 当前所有 DB 实例都是 PostgreSQL, 按事实改成 POSTGRES
UPDATE tenant_datasource SET access_type = 'POSTGRES' WHERE access_type = 'DB';
UPDATE action_template   SET access_type = 'POSTGRES' WHERE access_type = 'DB';

-- 校验: 应该没有遗漏的 'DB' 行
DO $$
DECLARE
    bad_ds  INT;
    bad_tpl INT;
BEGIN
    SELECT COUNT(*) INTO bad_ds  FROM tenant_datasource WHERE access_type = 'DB';
    SELECT COUNT(*) INTO bad_tpl FROM action_template   WHERE access_type = 'DB';
    IF bad_ds + bad_tpl > 0 THEN
        RAISE EXCEPTION '迁移后仍有 access_type=DB 的行: tenant_datasource=%, action_template=%', bad_ds, bad_tpl;
    END IF;
END $$;

COMMIT;

-- 注意:
-- 1. CHECK 约束: 见 01_create_tables.sql 字段定义里的 CHECK (access_type IN (...)).
--    若已存在的生产 DB 之前没有 CHECK 约束 (老版本 01 没加), 跑完本脚本后需手工执行:
--      ALTER TABLE tenant_datasource ADD CONSTRAINT tenant_datasource_access_type_check
--          CHECK (access_type IN ('POSTGRES', 'MYSQL', 'ORACLE', 'SQLSERVER', 'API'));
--      ALTER TABLE action_template ADD CONSTRAINT action_template_access_type_check
--          CHECK (access_type IN ('POSTGRES', 'MYSQL', 'ORACLE', 'SQLSERVER', 'API'));
-- 2. ORACLE 枚举值已就位但 pom 未加 ojdbc11 驱动. 提前写入 ORACLE 的 tenant_datasource 行
--    会在首次建池时 ClassNotFoundException 显式失败. 真接入 Oracle 客户时只需补 pom 依赖.
-- 3. 同一 action 在多 DB 上跑时, 应在 action_template 中插入多行 (一行一种 access_type),
--    DynamicMcpToolProvider 会自动按 action 字段去重, AI 仍只看到一个 tool.
