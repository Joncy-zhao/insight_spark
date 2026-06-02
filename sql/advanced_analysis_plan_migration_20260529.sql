USE `insight_spark`;

-- 用户端预测与情景模拟模块方案资产数据库变更
-- 生成时间：2026-05-29
-- 编码要求：UTF-8
-- 变更范围：
-- 1. 新增 is_advanced_analysis_plan 预测/推演方案资产表
-- 2. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会清空或覆盖历史数据
-- 3. 与预警规则表解耦，避免影响已实现的离线预警功能

CREATE TABLE IF NOT EXISTS `is_advanced_analysis_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL COMMENT '方案所属用户 user_id',
  `plan_type` VARCHAR(32) NOT NULL COMMENT '方案类型：forecast/whatIf',
  `plan_name` VARCHAR(200) NOT NULL COMMENT '方案名称',
  `table_name` VARCHAR(128) NULL COMMENT '数据源表名',
  `metric_label` VARCHAR(200) NULL COMMENT '页面展示指标名称',
  `time_range_label` VARCHAR(200) NULL COMMENT '页面展示时间范围',
  `status` VARCHAR(32) NOT NULL DEFAULT 'SAVED' COMMENT '方案状态：SAVED/DELETED',
  `request_json` JSON NULL COMMENT '用户确认后的参数、字段映射、过滤条件等',
  `result_json` JSON NULL COMMENT '最近一次计算结果快照',
  `llm_json` JSON NULL COMMENT 'LLM 解析结果与解释建议',
  `version_no` INT NOT NULL DEFAULT 1 COMMENT '当前结果版本号',
  `last_calculated_at` DATETIME NULL COMMENT '最近一次计算时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_advanced_analysis_plan_user` (`user_id`, `updated_at`),
  INDEX `idx_advanced_analysis_plan_type` (`plan_type`),
  INDEX `idx_advanced_analysis_plan_table` (`table_name`),
  INDEX `idx_advanced_analysis_plan_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测与情景模拟模块预测/推演方案资产';
