USE `insight_spark`;

CREATE TABLE IF NOT EXISTS `is_chat_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `data_source_id` BIGINT NOT NULL DEFAULT 0,
  `scope_json` JSON NULL,
  `business_model_id` BIGINT NULL,
  `summary` TEXT NULL,
  `last_turn_id` BIGINT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_chat_conv_user_time` (`user_id`, `updated_at`),
  INDEX `idx_chat_conv_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话';

CREATE TABLE IF NOT EXISTS `is_chat_conversation_turn` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `conversation_id` BIGINT NOT NULL,
  `parent_turn_id` BIGINT NULL,
  `turn_no` INT NOT NULL,
  `role` VARCHAR(16) NOT NULL,
  `message_text` TEXT NOT NULL,
  `intent_type` VARCHAR(64) NULL,
  `context_json` JSON NULL,
  `followup_mode` VARCHAR(32) NOT NULL DEFAULT 'NEW',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_chat_turn_conversation` (`conversation_id`, `turn_no`),
  INDEX `idx_chat_turn_parent` (`parent_turn_id`),
  INDEX `idx_chat_turn_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话轮次';

CREATE TABLE IF NOT EXISTS `is_chat_conversation_artifact` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `conversation_id` BIGINT NOT NULL,
  `turn_id` BIGINT NOT NULL,
  `history_id` BIGINT NULL,
  `artifact_type` VARCHAR(32) NOT NULL,
  `artifact_json` JSON NULL,
  `sql_text` TEXT NULL,
  `chart_type` VARCHAR(50) NULL,
  `risk_level` VARCHAR(20) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_chat_artifact_conversation` (`conversation_id`, `created_at`),
  INDEX `idx_chat_artifact_turn` (`turn_id`),
  INDEX `idx_chat_artifact_history` (`history_id`),
  INDEX `idx_chat_artifact_type` (`artifact_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话产物';

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'query_table_name'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `query_table_name` VARCHAR(128) NULL COMMENT ''查询目标表名，兼容上传表与官方数据源'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'conversation_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `conversation_id` BIGINT NULL COMMENT ''关联 is_chat_conversation.id'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'parent_history_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `parent_history_id` BIGINT NULL COMMENT ''兼容历史表分支/追问父记录'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'turn_no'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `turn_no` INT NULL COMMENT ''会话内轮次序号'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'message_role'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `message_role` VARCHAR(16) NULL DEFAULT ''ASSISTANT'' COMMENT ''消息角色 USER/ASSISTANT/SYSTEM'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'intent_type'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `intent_type` VARCHAR(64) NULL COMMENT ''意图类型 QUERY/FOLLOWUP/COMPARE/DRILLDOWN/EXPLAIN'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'context_json'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `context_json` JSON NULL COMMENT ''本轮上下文元数据'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'scope_json'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `scope_json` JSON NULL COMMENT ''数据源、表、业务模型等分析范围'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'artifact_type'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `artifact_type` VARCHAR(32) NULL DEFAULT ''CHART'' COMMENT ''主要产物类型 SQL/CHART/TABLE/TEXT/REPORT'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_chat_query_history'
        AND column_name = 'summary_text'
    ),
    'SELECT 1',
    'ALTER TABLE `is_chat_query_history` ADD COLUMN `summary_text` TEXT NULL COMMENT ''本轮摘要或可检索摘要'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
