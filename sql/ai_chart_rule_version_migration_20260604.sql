USE `insight_spark`;

-- 管理员端 AI 图表推荐规则版本快照数据库变更
-- 生成时间：2026-06-04
-- 编码要求：UTF-8
-- 变更范围：
-- 1. 新增 ai_chart_rule_version 规则版本快照表
-- 2. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会清空或覆盖历史数据
-- 3. 仅追加版本快照表结构，不删除、不更新、不迁移既有业务数据

CREATE TABLE IF NOT EXISTS `ai_chart_rule_version` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `rule_id` BIGINT NOT NULL COMMENT '规则 ID',
  `rule_code` VARCHAR(80) NOT NULL COMMENT '规则编码快照',
  `version_no` INT NOT NULL COMMENT '版本号',
  `snapshot` TEXT NOT NULL COMMENT '规则配置快照 JSON',
  `change_action` VARCHAR(32) NOT NULL COMMENT '变更动作',
  `operator` VARCHAR(64) NULL COMMENT '操作人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY `uk_ai_chart_rule_version_no` (`rule_id`, `version_no`),
  INDEX `idx_ai_chart_rule_version_rule` (`rule_id`),
  INDEX `idx_ai_chart_rule_version_code` (`rule_code`),
  INDEX `idx_ai_chart_rule_version_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 图表推荐规则版本快照';
