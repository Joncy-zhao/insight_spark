USE `insight_spark`;

-- 用户端预测与情景模拟模块预警事件解释数据库变更
-- 生成时间：2026-06-02
-- 编码要求：UTF-8
-- 变更范围：
-- 1. is_advanced_alert_event 追加 LLM 解释快照、解释备注和解释更新时间字段
-- 2. 使用 information_schema 判断字段是否存在，重复执行不会影响旧数据
-- 3. 仅追加可空字段，不清空、不覆盖、不回写历史预警事件

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_event'
        AND column_name = 'llm_explanation_json'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `llm_explanation_json` JSON NULL COMMENT ''预警事件 LLM/规则解释快照'''
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
        AND column_name = 'explanation_note'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `explanation_note` VARCHAR(1000) NULL COMMENT ''预警解释备注'''
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
        AND column_name = 'explanation_updated_at'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_event` ADD COLUMN `explanation_updated_at` DATETIME NULL COMMENT ''预警解释更新时间'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
