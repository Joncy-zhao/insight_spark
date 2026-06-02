USE `insight_spark`;

-- 用户端预测与情景模拟模块预警规则调度数据库变更
-- 生成时间：2026-06-01
-- 编码要求：UTF-8
-- 变更范围：
-- 1. is_advanced_alert_rule 追加 last_checked_at、last_triggered_at 字段
-- 2. 使用 information_schema 判断字段/索引是否存在，重复执行不会影响旧数据
-- 3. 仅追加可空字段，不清空、不覆盖、不回写历史预警规则

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_rule'
        AND column_name = 'last_checked_at'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_rule` ADD COLUMN `last_checked_at` DATETIME NULL COMMENT ''最近一次离线 Agent 检测时间'''
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
        AND table_name = 'is_advanced_alert_rule'
        AND column_name = 'last_triggered_at'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_rule` ADD COLUMN `last_triggered_at` DATETIME NULL COMMENT ''最近一次生成预警事件时间'''
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
        AND index_name = 'idx_advanced_alert_rule_schedule'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_advanced_alert_rule_schedule` ON `is_advanced_alert_rule` (`status`, `detection_cycle`, `last_checked_at`)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
