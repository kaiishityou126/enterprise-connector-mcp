-- =============================================================================
-- sys_dict 初始化数据
-- 按 DEVELOPMENT_PLAN.md §6.13.4 的全量字典项
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 分组: limit（业务限制值）
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict (dict_key, dict_value, value_type, group_name, description) VALUES
    ('limit.absolute_max_rows',        '10000', 'INT', 'limit', '模板 max_rows 的系统硬上限'),
    ('limit.default_max_rows',         '500',   'INT', 'limit', '模板未配 max_rows 时的默认值'),
    ('limit.absolute_max_timeout',     '600',   'INT', 'limit', '模板 timeout 的系统硬上限(秒)'),
    ('limit.default_query_timeout',    '5',     'INT', 'limit', '模板未配 timeout 时的默认值'),
    ('limit.max_api_response_size_mb', '5',     'INT', 'limit', '商家 API 响应体上限(MB)'),
    ('limit.max_param_count',          '20',    'INT', 'limit', '单次请求参数数量上限'),
    ('limit.max_param_value_length',   '1000',  'INT', 'limit', '单个参数值长度上限'),
    ('limit.max_custom_sql_length',    '2000',  'INT', 'limit', 'premium 租户 custom_sql 长度'),
    ('limit.max_date_range_days',      '365',   'INT', 'limit', '日期范围跨度上限'),
    ('limit.max_batch_size',           '100',   'INT', 'limit', '批量操作条数上限')
ON CONFLICT (dict_key) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 分组: async（异步任务）
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict (dict_key, dict_value, value_type, group_name, description) VALUES
    ('async.default_max_retries',       '3',    'INT',  'async', '任务默认最大重试次数'),
    ('async.default_timeout_seconds',   '300',  'INT',  'async', '异步任务默认超时(秒)'),
    ('async.max_tasks_per_tenant',      '5',    'INT',  'async', '每租户异步任务并发上限'),
    ('async.timeout_scan_interval',     '60',   'INT',  'async', '超时扫描间隔(秒)'),
    ('async.callback_retry_max',        '3',    'INT',  'async', '回调最大重试次数'),
    ('async.callback_retry_backoff_ms', '1000', 'LONG', 'async', '回调重试退避基数(毫秒)'),
    ('async.dedup_lookback_minutes',    '10',   'INT',  'async', '任务防重复回溯窗口(分钟)'),
    ('async.cleanup_retention_days',    '30',   'INT',  'async', '终态任务保留天数, 超过由 AsyncTaskCleanupJob 清理')
ON CONFLICT (dict_key) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 分组: resilience（限流熔断）
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict (dict_key, dict_value, value_type, group_name, description) VALUES
    ('resilience.default_qps',          '10', 'INT', 'resilience', '租户未配 rate_limit_qps 时的默认值'),
    ('resilience.cb_failure_rate',      '50', 'INT', 'resilience', '熔断器失败率阈值(%)'),
    ('resilience.cb_sliding_window',    '10', 'INT', 'resilience', '熔断器滑动窗口大小'),
    ('resilience.cb_half_open_permits', '3',  'INT', 'resilience', '半开状态允许试探次数')
ON CONFLICT (dict_key) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 分组: security（安全）
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict (dict_key, dict_value, value_type, group_name, description) VALUES
    ('security.idempotent_key_ttl', '300', 'INT', 'security', '幂等 key 过期时间(秒)')
ON CONFLICT (dict_key) DO NOTHING;
