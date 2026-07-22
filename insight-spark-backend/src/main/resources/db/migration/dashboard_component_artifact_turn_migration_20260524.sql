USE `insight_spark`;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard_component'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard_component'
        AND column_name = 'artifact_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_dashboard_component` ADD COLUMN `artifact_id` BIGINT NULL COMMENT ''关联 is_chat_conversation_artifact.id'''
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
        AND table_name = 'is_dashboard_component'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard_component'
        AND column_name = 'turn_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_dashboard_component` ADD COLUMN `turn_id` BIGINT NULL COMMENT ''关联 is_chat_conversation_turn.id'''
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
        AND table_name = 'is_dashboard_component'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard_component'
        AND index_name = 'idx_dashboard_component_artifact'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_dashboard_component_artifact` ON `is_dashboard_component` (`artifact_id`)'
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
        AND table_name = 'is_dashboard_component'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard_component'
        AND index_name = 'idx_dashboard_component_turn'
    ),
    'SELECT 1',
    'CREATE INDEX `idx_dashboard_component_turn` ON `is_dashboard_component` (`turn_id`)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
