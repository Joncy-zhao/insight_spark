USE `insight_spark`;

-- 用户端预测与情景模拟模块方案版本快照数据库变更
-- 生成时间：2026-06-01
-- 编码要求：UTF-8
-- 变更范围：
-- 1. 新增 is_advanced_analysis_plan_version 方案版本快照表
-- 2. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会清空或覆盖历史数据
-- 3. 仅追加版本快照，不回写旧记录，避免影响既有方案资产

CREATE TABLE IF NOT EXISTS `is_advanced_analysis_plan_version` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `plan_id` BIGINT NOT NULL COMMENT '方案主表 ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '方案所属用户 user_id',
  `plan_type` VARCHAR(32) NOT NULL COMMENT '方案类型：forecast/whatIf',
  `plan_name` VARCHAR(200) NOT NULL COMMENT '版本记录时的方案名称',
  `version_no` INT NOT NULL COMMENT '版本号',
  `request_json` JSON NULL COMMENT '用户确认参数快照',
  `result_json` JSON NULL COMMENT '结果快照',
  `llm_json` JSON NULL COMMENT 'LLM 解析快照',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY `uk_advanced_analysis_plan_version` (`plan_id`, `version_no`),
  INDEX `idx_advanced_analysis_plan_version_user` (`user_id`, `created_at`),
  INDEX `idx_advanced_analysis_plan_version_plan` (`plan_id`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测与情景模拟模块方案版本快照';
