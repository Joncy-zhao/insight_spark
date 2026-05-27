USE `insight_spark`;

-- 管理员端对话历史模块数据库变更
-- 生成时间：2026-05-27
-- 变更范围：
-- 1. is_chat_query_history 增加逻辑删除治理字段
-- 2. 新增 is_chat_history_admin_audit 管理员治理审计表
-- 3. is_sql_audit_log 增加 redis_status 字段

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'deleted_at'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `deleted_at` DATETIME NULL COMMENT ''逻辑删除时间'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'deleted_by'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `deleted_by` VARCHAR(64) NULL COMMENT ''删除操作者 user_id'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'delete_reason'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `delete_reason` VARCHAR(255) NULL COMMENT ''删除原因'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND index_name = 'idx_chat_history_deleted_at'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_chat_history_deleted_at` ON `is_chat_query_history` (`is_deleted`, `deleted_at`)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `is_chat_history_admin_audit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `action_type` VARCHAR(32) NOT NULL COMMENT '操作类型：ADMIN_DELETE / ADMIN_RERUN / AUTO_PURGE',
  `history_id` BIGINT NULL COMMENT '关联历史记录 id',
  `related_history_id` BIGINT NULL COMMENT '关联的新旧历史记录 id',
  `operator_user_id` VARCHAR(64) NULL COMMENT '操作人 user_id',
  `operator_role` VARCHAR(32) NULL COMMENT '操作角色',
  `target_user_id` VARCHAR(64) NULL COMMENT '目标用户 user_id',
  `action_reason` VARCHAR(255) NULL COMMENT '操作原因',
  `payload_json` JSON NULL COMMENT '补充上下文',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX `idx_chat_history_admin_audit_history` (`history_id`),
  INDEX `idx_chat_history_admin_audit_action` (`action_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员对话历史治理审计表';

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_sql_audit_log'
        AND column_name = 'redis_status'
    ),
    'SELECT 1',
    'ALTER TABLE `is_sql_audit_log` ADD COLUMN `redis_status` VARCHAR(32) NULL DEFAULT ''LOCAL'' COMMENT ''Redis 缓存状态'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
