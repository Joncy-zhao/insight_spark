-- insight_spark_schema.sql
-- Source: akalizg/syy, backend initialization SQL reconstructed from Java services.
-- Scope:
--   1) System tables automatically initialized by Spring Boot services.
--   2) Deterministic seed data in SqlAuditService.
--   3) Dynamic biz_data_* table template generated during Excel/CSV upload.
--
-- Important:
--   AuthService creates demo users through Java code using random salt + PBKDF2.
--   Therefore fixed INSERT statements for is_user would NOT be one-to-one with the repository runtime behavior.

CREATE DATABASE IF NOT EXISTS `insight_spark`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `insight_spark`;

-- =========================================================
-- 1. AuthService.initAuthTables()
-- =========================================================

CREATE TABLE IF NOT EXISTS `is_user` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL UNIQUE,
  `username` VARCHAR(64) NOT NULL UNIQUE,
  `nickname` VARCHAR(64) NOT NULL,
  `phone` VARCHAR(32) NULL UNIQUE,
  `email` VARCHAR(128) NULL UNIQUE,
  `password_hash` VARCHAR(512) NOT NULL,
  `password_salt` VARCHAR(128) NOT NULL,
  `password_algorithm` VARCHAR(64) NOT NULL DEFAULT 'PBKDF2WithHmacSHA256',
  `role` VARCHAR(32) NOT NULL DEFAULT 'USER',
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  `last_login_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_is_user_role` (`role`),
  INDEX `idx_is_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC用户表';

-- AuthService.ensureUser("demo-user", "demo-user", "普通用户", null, "user@example.com", "user123456", "USER")
-- AuthService.ensureUser("admin", "admin", "管理员", null, "admin@example.com", "admin123456", "ADMIN")
-- These two users are initialized by Java at runtime with random salt and PBKDF2 hash.

-- =========================================================
-- 2. DataUploadService.initCatalogTables()
-- =========================================================

CREATE TABLE IF NOT EXISTS `is_data_table` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `source_name` VARCHAR(255) NOT NULL,
  `display_name` VARCHAR(255) NOT NULL,
  `table_name` VARCHAR(128) NOT NULL UNIQUE,
  `owner_id` VARCHAR(64) NOT NULL DEFAULT '',
  `row_count` INT NOT NULL DEFAULT 0,
  `field_count` INT NOT NULL DEFAULT 0,
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传数据表元信息';

CREATE TABLE IF NOT EXISTS `is_data_field` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `table_name` VARCHAR(128) NOT NULL,
  `source_field_name` VARCHAR(255) NOT NULL,
  `column_name` VARCHAR(128) NOT NULL,
  `field_type` VARCHAR(32) NOT NULL,
  `display_name` VARCHAR(255) NOT NULL,
  `field_comment` VARCHAR(512) NULL,
  `sensitive` TINYINT(1) NOT NULL DEFAULT 0,
  `sort_order` INT NOT NULL,
  INDEX `idx_is_data_field_table` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传数据字段元信息';

-- DataUploadService.addColumnIfMissing(...) compatibility migrations:
-- ALTER TABLE `is_data_field` ADD COLUMN `source_field_name` VARCHAR(255) NOT NULL DEFAULT '';
-- ALTER TABLE `is_data_field` ADD COLUMN `column_name` VARCHAR(128) NOT NULL DEFAULT '';
-- ALTER TABLE `is_data_field` ADD COLUMN `field_type` VARCHAR(32) NOT NULL DEFAULT 'TEXT';
-- ALTER TABLE `is_data_field` ADD COLUMN `display_name` VARCHAR(255) NOT NULL DEFAULT '';
-- ALTER TABLE `is_data_field` ADD COLUMN `field_comment` VARCHAR(512) NULL;
-- ALTER TABLE `is_data_field` ADD COLUMN `sensitive` TINYINT(1) NOT NULL DEFAULT 0;
-- ALTER TABLE `is_data_field` ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS `is_business_model` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `model_name` VARCHAR(255) NOT NULL,
  `model_requirement` VARCHAR(2000) NULL,
  `table_name` VARCHAR(128) NOT NULL,
  `owner_id` VARCHAR(64) NOT NULL DEFAULT '',
  `model_json` JSON NOT NULL,
  `published` TINYINT(1) NOT NULL DEFAULT 0,
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_business_model_table` (`table_name`),
  INDEX `idx_business_model_published` (`published`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='零代码业务模型与企业模型库';

CREATE TABLE IF NOT EXISTS `is_analysis_template` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `template_name` VARCHAR(255) NOT NULL,
  `file_name` VARCHAR(255) NULL,
  `template_type` VARCHAR(50) NULL,
  `template_content` LONGTEXT NULL,
  `created_by` VARCHAR(64) NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务分析模板';

CREATE TABLE IF NOT EXISTS `is_file_process_task` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `task_id` VARCHAR(64) NOT NULL UNIQUE,
  `status` VARCHAR(32) NOT NULL,
  `progress` INT NOT NULL DEFAULT 0,
  `message` VARCHAR(1000) NULL,
  `result_json` JSON NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_file_process_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传解析进度任务';

-- DataUploadService.persistParsedFile(...) dynamic table template.
-- The real table name is generated by nextTableName(), and the number of col_xxx fields depends on uploaded file headers.
-- CREATE TABLE `biz_data_数字` (
--   `sys_id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '系统自增主键',
--   `col_001` VARCHAR(255) COMMENT '原始表头1',
--   `col_002` VARCHAR(255) COMMENT '原始表头2'
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户上传动态数据表';

-- =========================================================
-- 3. PermissionService.initPermissionTables()
-- =========================================================

CREATE TABLE IF NOT EXISTS `is_data_permission` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL,
  `table_name` VARCHAR(128) NOT NULL,
  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
  `source` VARCHAR(32) NOT NULL DEFAULT 'GRANT',
  `expire_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_data_permission_user_table_type` (`user_id`, `table_name`, `permission_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表访问授权';

CREATE TABLE IF NOT EXISTS `is_permission_request` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `applicant_id` VARCHAR(64) NOT NULL,
  `table_name` VARCHAR(128) NOT NULL,
  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
  `reason` VARCHAR(1000) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `reviewer_id` VARCHAR(64) NULL,
  `review_comment` VARCHAR(1000) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` DATETIME NULL,
  INDEX `idx_permission_request_status` (`status`),
  INDEX `idx_permission_request_applicant` (`applicant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限申请记录';

-- =========================================================
-- 4. DatasourceService.initDatasourceTables()
-- =========================================================

CREATE TABLE IF NOT EXISTS `is_official_datasource` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `db_type` VARCHAR(32) NOT NULL DEFAULT 'MYSQL',
  `host` VARCHAR(255) NOT NULL,
  `port` INT NOT NULL,
  `database_name` VARCHAR(128) NOT NULL,
  `username` VARCHAR(128) NOT NULL,
  `password` VARCHAR(255) NOT NULL,
  `jdbc_url` VARCHAR(1000) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'DISABLED',
  `last_test_status` VARCHAR(32) NULL,
  `last_test_message` VARCHAR(1000) NULL,
  `last_sync_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源配置';

ALTER TABLE `is_official_datasource` ADD COLUMN `pool_max_size` INT NOT NULL DEFAULT 10;
ALTER TABLE `is_official_datasource` ADD COLUMN `pool_timeout_ms` INT NOT NULL DEFAULT 30000;
ALTER TABLE `is_official_datasource` ADD COLUMN `readonly_enforced` TINYINT(1) NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS `is_official_schema_table` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `datasource_id` BIGINT NOT NULL,
  `table_name` VARCHAR(128) NOT NULL,
  `table_comment` VARCHAR(512) NULL,
  `table_rows` BIGINT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_official_schema_table` (`datasource_id`, `table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源表结构';

CREATE TABLE IF NOT EXISTS `is_official_schema_field` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `datasource_id` BIGINT NOT NULL,
  `table_name` VARCHAR(128) NOT NULL,
  `column_name` VARCHAR(128) NOT NULL,
  `data_type` VARCHAR(64) NOT NULL,
  `column_comment` VARCHAR(512) NULL,
  `is_nullable` VARCHAR(8) NULL,
  `column_key` VARCHAR(16) NULL,
  `ordinal_position` INT NOT NULL,
  `business_name` VARCHAR(255) NULL,
  `sensitive` TINYINT(1) NOT NULL DEFAULT 0,
  UNIQUE KEY `uk_official_schema_field` (`datasource_id`, `table_name`, `column_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源字段结构';

CREATE TABLE IF NOT EXISTS `is_official_datasource_permission` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `datasource_id` BIGINT NOT NULL,
  `principal_type` VARCHAR(32) NOT NULL,
  `principal_id` VARCHAR(128) NOT NULL,
  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
  `expire_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_official_ds_permission` (`datasource_id`, `principal_type`, `principal_id`, `permission_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源用户角色授权';

CREATE TABLE IF NOT EXISTS `is_federal_relation` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `datasource_id` BIGINT NOT NULL,
  `left_table` VARCHAR(128) NOT NULL,
  `left_field` VARCHAR(128) NOT NULL,
  `right_source_type` VARCHAR(32) NOT NULL,
  `right_table` VARCHAR(128) NOT NULL,
  `right_field` VARCHAR(128) NOT NULL,
  `relation_type` VARCHAR(32) NOT NULL DEFAULT 'LEFT_JOIN',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_federal_relation_ds` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Excel与官方库联邦关联配置';

-- =========================================================
-- 5. DiagnosisService.initDiagnosisTables()
-- =========================================================

CREATE TABLE IF NOT EXISTS `is_diagnosis_report` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL DEFAULT '',
  `table_name` VARCHAR(128) NOT NULL,
  `metric_field` VARCHAR(128) NOT NULL,
  `dimension_fields` VARCHAR(512) NULL,
  `time_field` VARCHAR(128) NULL,
  `title` VARCHAR(255) NOT NULL,
  `summary` VARCHAR(2000) NOT NULL,
  `report_markdown` MEDIUMTEXT NULL,
  `result_json` JSON NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_diagnosis_report_created_at` (`created_at`),
  INDEX `idx_diagnosis_report_table` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能诊断报告';

-- =========================================================
-- 6. KnowledgeDocumentService.initTables()
-- =========================================================

CREATE TABLE IF NOT EXISTS is_knowledge_doc (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  file_name VARCHAR(255),
  doc_type VARCHAR(50),
  content LONGTEXT,
  created_by VARCHAR(64),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档';

CREATE TABLE IF NOT EXISTS is_knowledge_chunk (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  chunk_text TEXT NOT NULL,
  keywords VARCHAR(1000),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_knowledge_chunk_doc(doc_id),
  INDEX idx_knowledge_chunk_index(chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档切片';

-- =========================================================
-- 7. KnowledgeGraphService.initKnowledgeGraphTables()
-- =========================================================

CREATE TABLE IF NOT EXISTS `is_kg_node` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `node_key` VARCHAR(255) NOT NULL UNIQUE,
  `node_type` VARCHAR(64) NOT NULL,
  `label` VARCHAR(255) NOT NULL,
  `source_type` VARCHAR(64) NULL,
  `source_id` VARCHAR(255) NULL,
  `content` TEXT NULL,
  `weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_kg_node_type` (`node_type`),
  INDEX `idx_kg_node_label` (`label`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轻量知识图谱节点';

CREATE TABLE IF NOT EXISTS `is_kg_edge` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `from_key` VARCHAR(255) NOT NULL,
  `to_key` VARCHAR(255) NOT NULL,
  `relation_type` VARCHAR(64) NOT NULL,
  `weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_kg_edge` (`from_key`, `to_key`, `relation_type`),
  INDEX `idx_kg_edge_from` (`from_key`),
  INDEX `idx_kg_edge_to` (`to_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轻量知识图谱关系';

-- KnowledgeGraphService.addColumnIfMissing(...) compatibility migrations:
-- ALTER TABLE `is_kg_node` ADD COLUMN `node_type` VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN';
-- ALTER TABLE `is_kg_node` ADD COLUMN `label` VARCHAR(255) NOT NULL DEFAULT '';
-- ALTER TABLE `is_kg_node` ADD COLUMN `source_type` VARCHAR(64) NULL;
-- ALTER TABLE `is_kg_node` ADD COLUMN `source_id` VARCHAR(255) NULL;
-- ALTER TABLE `is_kg_node` ADD COLUMN `content` TEXT NULL;
-- ALTER TABLE `is_kg_node` ADD COLUMN `weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00;
-- ALTER TABLE `is_kg_node` ADD COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;
-- ALTER TABLE `is_kg_node` ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
-- ALTER TABLE `is_kg_node` MODIFY COLUMN `name` VARCHAR(255) NULL;
-- UPDATE is_kg_node SET label = COALESCE(NULLIF(label, ''), name, node_key) WHERE label IS NULL OR label = '';
-- UPDATE is_kg_node SET label = COALESCE(NULLIF(label, ''), node_key) WHERE label IS NULL OR label = '';
-- ALTER TABLE `is_kg_edge` ADD COLUMN `relation_type` VARCHAR(64) NOT NULL DEFAULT 'RELATED';
-- ALTER TABLE `is_kg_edge` ADD COLUMN `weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00;

-- =========================================================
-- 8. SqlAuditService.initAuditTable()
-- =========================================================

CREATE TABLE IF NOT EXISTS `is_sql_audit_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL DEFAULT '',
  `question` VARCHAR(1000) NOT NULL,
  `table_name` VARCHAR(128) NULL,
  `engine` VARCHAR(64) NULL,
  `generated_sql` TEXT NOT NULL,
  `risk_level` VARCHAR(32) NOT NULL,
  `risk_reason` VARCHAR(1000) NULL,
  `matched_rules` VARCHAR(1000) NULL,
  `sensitive_fields` VARCHAR(1000) NULL,
  `slow_query` TINYINT(1) NOT NULL DEFAULT 0,
  `execute_status` VARCHAR(32) NOT NULL,
  `duration_ms` BIGINT NULL,
  `error_message` VARCHAR(1000) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_sql_audit_created_at` (`created_at`),
  INDEX `idx_sql_audit_risk_level` (`risk_level`),
  INDEX `idx_sql_audit_status` (`execute_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL安全审计日志';

-- SqlAuditService.addColumnIfMissing(...) compatibility migrations:
-- ALTER TABLE `is_sql_audit_log` ADD COLUMN `matched_rules` VARCHAR(1000) NULL;
-- ALTER TABLE `is_sql_audit_log` ADD COLUMN `sensitive_fields` VARCHAR(1000) NULL;
-- ALTER TABLE `is_sql_audit_log` ADD COLUMN `slow_query` TINYINT(1) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS `is_sql_audit_rule` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `rule_code` VARCHAR(64) NOT NULL UNIQUE,
  `rule_name` VARCHAR(128) NOT NULL,
  `risk_level` VARCHAR(32) NOT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `rule_desc` VARCHAR(1000) NULL,
  `threshold_value` BIGINT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL审计规则配置';

CREATE TABLE IF NOT EXISTS `is_sensitive_field_rule` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `field_keyword` VARCHAR(128) NOT NULL UNIQUE,
  `mask_type` VARCHAR(32) NOT NULL DEFAULT 'MIDDLE',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感字段识别与脱敏规则';

-- SqlAuditService.seedRules()

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('ONLY_SELECT', '只允许 SELECT', 'BLOCKED', 1, '禁止非查询语句进入 BI 分析链路', NULL)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('DANGEROUS_KEYWORD', '危险关键字拦截', 'BLOCKED', 1, '拦截 DROP/DELETE/UPDATE/INSERT/ALTER 等破坏性关键字', NULL)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('MULTI_STATEMENT', '多语句拦截', 'BLOCKED', 1, '拦截分号拼接的多语句 SQL', NULL)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('TABLE_SCOPE', '授权表范围校验', 'BLOCKED', 1, '校验 SQL 是否仅访问当前授权数据表', NULL)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('LIMIT_REQUIRED', '结果集 LIMIT 检查', 'WARN', 1, '缺少 LIMIT 时标记为大结果集风险', NULL)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('NO_SELECT_STAR', '禁止 SELECT *', 'WARN', 1, '使用 SELECT * 时提示限制字段范围', NULL)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('SENSITIVE_FIELD', '敏感字段访问识别', 'WARN', 1, '识别 SQL 是否访问敏感字段', NULL)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('SLOW_QUERY', '慢查询识别', 'WARN', 1, '执行耗时超过阈值时标记为慢查询', 3000)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
VALUES ('SLOW_QUERY_BREAKER', '慢查询熔断阈值', 'BLOCKED', 1, '执行耗时超过阈值时记录熔断风险，提示管理员优化 SQL', 8000)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value);

-- SqlAuditService.seedSensitiveRules()

INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
VALUES ('phone', 'MOBILE', 1)
ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type);

INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
VALUES ('mobile', 'MOBILE', 1)
ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type);

INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
VALUES ('idcard', 'ID_CARD', 1)
ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type);

INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
VALUES ('手机号', 'MOBILE', 1)
ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type);

INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
VALUES ('身份证', 'ID_CARD', 1)
ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type);

INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
VALUES ('amount', 'MIDDLE', 1)
ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type);

INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
VALUES ('金额', 'MIDDLE', 1)
ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type);
