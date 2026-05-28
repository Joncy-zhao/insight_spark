USE `insight_spark`;

-- 管理员端对话查询实验室数据库变更
-- 生成时间：2026-05-27
-- 变更范围：
-- 1. 新增 is_admin_chat_test_session 管理员测试会话表
-- 2. 新增 is_admin_chat_test_step 管理员测试步骤表
-- 3. 新增 is_admin_chat_test_artifact 管理员测试产物表
-- 4. 新增 is_admin_chat_test_export 管理员测试导出记录表

CREATE TABLE IF NOT EXISTS `is_admin_chat_test_session` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `tester_user_id` VARCHAR(64) NULL COMMENT '测试人 user_id',
  `tester_role` VARCHAR(32) NULL COMMENT '测试人角色',
  `question` VARCHAR(2000) NOT NULL COMMENT '自然语言测试指令',
  `datasource_scope_json` JSON NULL COMMENT '测试数据源范围',
  `model_config_json` JSON NULL COMMENT '模型配置',
  `permission_context_json` JSON NULL COMMENT '权限模拟上下文',
  `final_sql` LONGTEXT NULL COMMENT '最终生成 SQL',
  `risk_level` VARCHAR(32) NULL COMMENT 'SQL 风险等级',
  `status` VARCHAR(32) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED / RUNNING / SUCCESS / FAILED',
  `duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '执行耗时',
  `error_message` VARCHAR(1000) NULL COMMENT '异常信息',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_admin_chat_test_session_tester` (`tester_user_id`, `created_at`),
  INDEX `idx_admin_chat_test_session_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员端对话查询测试会话表';

CREATE TABLE IF NOT EXISTS `is_admin_chat_test_step` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `session_id` BIGINT NOT NULL COMMENT '测试会话 id',
  `step_type` VARCHAR(64) NOT NULL COMMENT '步骤类型',
  `step_title` VARCHAR(255) NOT NULL COMMENT '步骤标题',
  `step_status` VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '步骤状态',
  `step_payload_json` JSON NULL COMMENT '步骤上下文',
  `error_message` VARCHAR(1000) NULL COMMENT '异常信息',
  `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  `finished_at` DATETIME NULL COMMENT '完成时间',
  INDEX `idx_admin_chat_test_step_session` (`session_id`, `id`),
  INDEX `idx_admin_chat_test_step_type` (`step_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员端对话查询测试步骤表';

CREATE TABLE IF NOT EXISTS `is_admin_chat_test_artifact` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `session_id` BIGINT NOT NULL COMMENT '测试会话 id',
  `artifact_type` VARCHAR(32) NOT NULL COMMENT 'SQL / CHART / TABLE / REASONING / SECURITY / PERMISSION',
  `artifact_json` JSON NULL COMMENT '产物完整 JSON',
  `sql_text` LONGTEXT NULL COMMENT 'SQL 文本',
  `chart_config_json` JSON NULL COMMENT '图表配置 JSON',
  `result_preview_json` JSON NULL COMMENT '结果预览 JSON',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX `idx_admin_chat_test_artifact_session` (`session_id`, `artifact_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员端对话查询测试产物表';

CREATE TABLE IF NOT EXISTS `is_admin_chat_test_export` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `session_id` BIGINT NOT NULL COMMENT '测试会话 id',
  `export_type` VARCHAR(32) NOT NULL COMMENT '导出类型',
  `file_name` VARCHAR(255) NULL COMMENT '导出文件名',
  `file_path` VARCHAR(1000) NULL COMMENT '导出文件路径',
  `export_status` VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '导出状态',
  `created_by` VARCHAR(64) NULL COMMENT '创建人 user_id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX `idx_admin_chat_test_export_session` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员端对话查询测试导出记录表';
