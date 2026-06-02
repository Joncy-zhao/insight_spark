USE `insight_spark`;

-- 管理员端对话查询实验室模板能力数据库变更
-- 生成时间：2026-05-28
-- 变更范围：
-- 1. 新增 is_admin_chat_test_template 管理员测试指令模板表

CREATE TABLE IF NOT EXISTS `is_admin_chat_test_template` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `template_name` VARCHAR(255) NOT NULL COMMENT '模板名称',
  `question` VARCHAR(2000) NOT NULL COMMENT '自然语言测试指令',
  `datasource_scope_json` JSON NULL COMMENT '数据源范围',
  `model_config_json` JSON NULL COMMENT '模型配置',
  `created_by` VARCHAR(64) NULL COMMENT '创建人 user_id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX `idx_admin_chat_test_template_created` (`created_by`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员端对话查询测试指令模板表';
