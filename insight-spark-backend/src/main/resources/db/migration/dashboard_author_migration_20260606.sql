-- 看板作者（首创者，永久不变）与另存来源
-- 数据回填见 StackCSchemaInitializer.backfillDashboardAuthorAndPublisher()

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard'
        AND column_name = 'author_user_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_dashboard` ADD COLUMN `author_user_id` VARCHAR(64) NULL COMMENT ''看板原作者 user_id'' AFTER `owner_user_id`'
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
        AND table_name = 'is_dashboard'
        AND column_name = 'source_dashboard_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_dashboard` ADD COLUMN `source_dashboard_id` BIGINT NULL COMMENT ''另存来源看板 id'' AFTER `author_user_id`'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
