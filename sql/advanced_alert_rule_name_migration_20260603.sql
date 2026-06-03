USE `insight_spark`;

-- 用户端预测与情景模拟模块预警规则名称数据库变更
-- 生成时间：2026-06-03
-- 编码要求：UTF-8
-- 变更范围：
-- 1. is_advanced_alert_rule 追加 rule_name 字段，用于钉钉/邮件推送展示完整规则名称
-- 2. 使用 information_schema 判断字段是否存在，重复执行不会影响旧数据
-- 3. 仅追加可空字段，不清空、不覆盖、不回写历史预警规则

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_alert_rule'
        AND column_name = 'rule_name'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_alert_rule` ADD COLUMN `rule_name` VARCHAR(255) NULL COMMENT ''预警规则展示名称/自然语言指令'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
