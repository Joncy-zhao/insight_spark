-- UTF-8
-- Admin AI chart recommendation rule version snapshots.
-- This migration only creates a new version table and does not modify existing data.

CREATE TABLE IF NOT EXISTS `ai_chart_rule_version` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `rule_id` BIGINT NOT NULL,
  `rule_code` VARCHAR(80) NOT NULL,
  `version_no` INT NOT NULL,
  `snapshot` TEXT NOT NULL,
  `change_action` VARCHAR(32) NOT NULL,
  `operator` VARCHAR(64) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_ai_chart_rule_version_no` (`rule_id`, `version_no`),
  INDEX `idx_ai_chart_rule_version_rule` (`rule_id`),
  INDEX `idx_ai_chart_rule_version_code` (`rule_code`),
  INDEX `idx_ai_chart_rule_version_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart rule version snapshots';
