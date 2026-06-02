USE `insight_spark`;

-- 用户端预测与情景模拟模块预警推送记录数据库变更
-- 生成时间：2026-06-01
-- 编码要求：UTF-8
-- 变更范围：
-- 1. 新增 is_advanced_alert_push_log 预警推送记录表
-- 2. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会清空或覆盖历史数据
-- 3. 仅追加独立日志表，不修改既有业务表数据

CREATE TABLE IF NOT EXISTS `is_advanced_alert_push_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `event_id` BIGINT NOT NULL COMMENT '预警事件 ID',
  `rule_id` BIGINT NOT NULL COMMENT '预警规则 ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '规则所属用户 user_id',
  `channel` VARCHAR(32) NOT NULL COMMENT '推送渠道：email/dingtalk',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '推送状态：PENDING/SUCCESS/FAILED',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `target` VARCHAR(512) NULL COMMENT '推送目标地址或 webhook 摘要',
  `title` VARCHAR(255) NOT NULL COMMENT '告警标题',
  `content` VARCHAR(2000) NOT NULL COMMENT '告警内容摘要',
  `error_message` VARCHAR(1000) NULL COMMENT '失败原因',
  `request_json` JSON NULL COMMENT '推送请求快照',
  `response_json` JSON NULL COMMENT '推送响应快照',
  `last_attempt_at` DATETIME NULL COMMENT '最近尝试时间',
  `next_retry_at` DATETIME NULL COMMENT '下次建议重试时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_advanced_alert_push_log_event` (`event_id`, `created_at`),
  INDEX `idx_advanced_alert_push_log_rule` (`rule_id`, `created_at`),
  INDEX `idx_advanced_alert_push_log_user` (`user_id`, `created_at`),
  INDEX `idx_advanced_alert_push_log_status` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测与情景模拟模块预警推送记录';
