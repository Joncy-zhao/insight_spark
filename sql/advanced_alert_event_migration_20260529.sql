USE `insight_spark`;

-- 用户端预测与情景模拟模块预警事件数据库变更
-- 生成时间：2026-05-29
-- 编码要求：UTF-8
-- 变更范围：
-- 1. 新增 is_advanced_alert_event 预警事件表
-- 2. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会清空或覆盖历史数据
-- 3. 使用唯一键避免同一规则、同一时间桶重复生成事件

CREATE TABLE IF NOT EXISTS `is_advanced_alert_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `rule_id` BIGINT NOT NULL COMMENT '预警规则 ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '规则所属用户 user_id',
  `table_name` VARCHAR(128) NOT NULL COMMENT '数据源表名',
  `metric_field` VARCHAR(128) NOT NULL COMMENT '指标字段',
  `time_field` VARCHAR(128) NOT NULL COMMENT '时间字段',
  `bucket_name` VARCHAR(128) NOT NULL COMMENT '触发时间桶',
  `actual_value` DECIMAL(20,4) NOT NULL COMMENT '实际指标值',
  `threshold_value` DECIMAL(20,4) NULL COMMENT '阈值',
  `operator` VARCHAR(32) NOT NULL COMMENT '判断条件：lt/gt/zscore',
  `z_score` DECIMAL(20,6) NULL COMMENT 'Z-Score 值',
  `baseline_value` DECIMAL(20,4) NULL COMMENT '历史基线均值',
  `deviation_rate` DECIMAL(20,6) NULL COMMENT '偏离比例',
  `reason` VARCHAR(1000) NOT NULL COMMENT '触发原因说明',
  `chart_snapshot_json` JSON NULL COMMENT '图表快照 JSON',
  `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN' COMMENT '事件状态：OPEN/ACK/CLOSED',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY `uk_advanced_alert_event_rule_bucket` (`rule_id`, `bucket_name`, `operator`),
  INDEX `idx_advanced_alert_event_user` (`user_id`, `created_at`),
  INDEX `idx_advanced_alert_event_rule` (`rule_id`, `created_at`),
  INDEX `idx_advanced_alert_event_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测与情景模拟模块预警事件';
