USE `insight_spark`;

-- 用户端预测与情景模拟模块预警规则数据库变更
-- 生成时间：2026-05-29
-- 编码要求：UTF-8
-- 变更范围：
-- 1. 新增 is_advanced_alert_rule 预警规则表
-- 2. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会清空或覆盖历史数据

CREATE TABLE IF NOT EXISTS `is_advanced_alert_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL COMMENT '规则所属用户 user_id',
  `table_name` VARCHAR(128) NOT NULL COMMENT '数据源表名',
  `metric_field` VARCHAR(128) NOT NULL COMMENT '指标字段',
  `time_field` VARCHAR(128) NOT NULL COMMENT '时间字段',
  `granularity` VARCHAR(32) NOT NULL DEFAULT 'day' COMMENT '聚合粒度：day/week/month/quarter/year',
  `filter_expression` VARCHAR(1000) NULL COMMENT '用户输入过滤条件',
  `resolved_filter_expression` VARCHAR(1000) NULL COMMENT '解析后的物理字段过滤条件',
  `operator` VARCHAR(32) NOT NULL DEFAULT 'lt' COMMENT '判断条件：lt/gt/zscore',
  `threshold_value` DECIMAL(20,4) NULL COMMENT '阈值',
  `detection_cycle` VARCHAR(64) NOT NULL DEFAULT 'daily' COMMENT '检测周期：hourly/daily/weekly/monthly',
  `channels_json` JSON NULL COMMENT '通知渠道 JSON，例如 email/dingtalk',
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '规则状态：ACTIVE/DISABLED/DELETED',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_advanced_alert_rule_user` (`user_id`),
  INDEX `idx_advanced_alert_rule_table` (`table_name`),
  INDEX `idx_advanced_alert_rule_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测与情景模拟模块预警规则';
