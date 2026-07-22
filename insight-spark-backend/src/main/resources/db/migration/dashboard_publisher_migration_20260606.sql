-- 看板发布者：最后执行发布（ACTIVE）操作的用户

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard'
        AND column_name = 'publisher_user_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_dashboard` ADD COLUMN `publisher_user_id` VARCHAR(64) NULL COMMENT ''最后发布者 user_id'' AFTER `owner_user_id`'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
