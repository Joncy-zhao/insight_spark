USE `insight_spark`;

-- 用户端预测与情景模拟模块预警事件生命周期数据库变更
-- 生成时间：2026-06-01
-- 编码要求：UTF-8
-- 变更范围：
-- 1. is_advanced_alert_event 追加确认、关闭、处理备注字段
-- 2. 使用 information_schema 判断字段/索引是否存在，重复执行不会影响旧数据
-- 3. 仅追加可空字段，不清空、不覆盖、不回写历史预警事件

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_event'
        AND column_name = 'ack_by'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `ack_by` VARCHAR(64) NULL COMMENT ''确认人 user_id'''
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
        AND column_name = 'ack_at'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `ack_at` DATETIME NULL COMMENT ''确认时间'''
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
        AND column_name = 'closed_by'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `closed_by` VARCHAR(64) NULL COMMENT ''关闭人 user_id'''
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
        AND column_name = 'closed_at'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `closed_at` DATETIME NULL COMMENT ''关闭时间'''
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
        AND column_name = 'handle_note'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `handle_note` VARCHAR(1000) NULL COMMENT ''处理备注'''
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
        AND column_name = 'status_updated_at'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `status_updated_at` DATETIME NULL COMMENT ''状态更新时间'''
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
        AND index_name = 'idx_advanced_alert_event_status_updated'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_advanced_alert_event_status_updated` ON `is_advanced_alert_event` (`status`, `status_updated_at`)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
