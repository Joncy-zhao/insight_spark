USE `insight_spark`;

-- 用户端预测与情景模拟模块预警组织维度数据库变更
-- 生成时间：2026-06-02
-- 编码要求：UTF-8
-- 变更范围：
-- 1. is_advanced_alert_rule / is_advanced_alert_event / is_advanced_alert_push_log 追加 org_scope 字段
-- 2. 使用 information_schema 判断字段/索引是否存在，重复执行不会影响旧数据
-- 3. 仅追加默认字段和索引，不清空、不覆盖、不回写历史数据

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_rule'
        AND column_name = 'org_scope'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_rule` ADD COLUMN `org_scope` VARCHAR(64) NOT NULL DEFAULT ''GLOBAL'' COMMENT ''组织/权限域，用于预警规则隔离'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_event'
        AND column_name = 'org_scope'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `org_scope` VARCHAR(64) NOT NULL DEFAULT ''GLOBAL'' COMMENT ''组织/权限域，用于预警事件隔离'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_push_log'
        AND column_name = 'org_scope'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_push_log` ADD COLUMN `org_scope` VARCHAR(64) NOT NULL DEFAULT ''GLOBAL'' COMMENT ''组织/权限域，用于预警推送记录隔离'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_rule'
        AND index_name = 'idx_advanced_alert_rule_org'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_advanced_alert_rule_org` ON `is_advanced_alert_rule` (`org_scope`, `status`, `updated_at`)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_event'
        AND index_name = 'idx_advanced_alert_event_org'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_advanced_alert_event_org` ON `is_advanced_alert_event` (`org_scope`, `created_at`)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_push_log'
        AND index_name = 'idx_advanced_alert_push_log_org'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_advanced_alert_push_log_org` ON `is_advanced_alert_push_log` (`org_scope`, `created_at`)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
