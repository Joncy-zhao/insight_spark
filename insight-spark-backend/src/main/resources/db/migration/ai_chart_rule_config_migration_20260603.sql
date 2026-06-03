-- UTF-8
-- Admin AI chart recommendation rule configuration.
-- This migration only creates new tables for the admin chart-rule module.

CREATE TABLE IF NOT EXISTS `ai_chart_rule` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `rule_code` VARCHAR(80) NOT NULL UNIQUE,
  `rule_name` VARCHAR(128) NOT NULL,
  `scenario_type` VARCHAR(32) NOT NULL,
  `chart_type` VARCHAR(32) NOT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `priority` INT NOT NULL DEFAULT 100,
  `match_config` TEXT NULL,
  `render_config` TEXT NULL,
  `explain_template` TEXT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_ai_chart_rule_scenario` (`scenario_type`),
  INDEX `idx_ai_chart_rule_enabled` (`enabled`),
  INDEX `idx_ai_chart_rule_priority` (`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart recommendation rules';

CREATE TABLE IF NOT EXISTS `ai_chart_style_preference` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `preference_code` VARCHAR(80) NOT NULL UNIQUE,
  `theme_name` VARCHAR(128) NOT NULL,
  `color_palette` TEXT NULL,
  `font_config` TEXT NULL,
  `layout_config` TEXT NULL,
  `default_options` TEXT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart style preferences';

CREATE TABLE IF NOT EXISTS `ai_chart_rule_audit_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `rule_id` BIGINT NULL,
  `action` VARCHAR(32) NOT NULL,
  `before_snapshot` TEXT NULL,
  `after_snapshot` TEXT NULL,
  `operator` VARCHAR(64) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_ai_chart_audit_rule` (`rule_id`),
  INDEX `idx_ai_chart_audit_action` (`action`),
  INDEX `idx_ai_chart_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart rule audit log';
