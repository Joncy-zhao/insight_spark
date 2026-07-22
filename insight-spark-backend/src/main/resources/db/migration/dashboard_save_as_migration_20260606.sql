-- 另存人（另存/复制生成当前看板的人）
-- 发布者语义调整与历史回填见 StackCSchemaInitializer.backfillDashboardAuthorAndPublisher()

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
        AND column_name = 'save_as_user_id'
    ),
    'SELECT 1',
    'ALTER TABLE `is_dashboard` ADD COLUMN `save_as_user_id` VARCHAR(64) NULL COMMENT ''另存/复制生成者 user_id'' AFTER `source_dashboard_id`'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
