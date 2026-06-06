-- 看板访问量：统计打开/预览次数
-- 使用 information_schema 判断字段是否存在，重复执行安全

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_dashboard'
        AND column_name = 'view_count'
    ),
    'SELECT 1',
    'ALTER TABLE `is_dashboard` ADD COLUMN `view_count` BIGINT NOT NULL DEFAULT 0 COMMENT ''访问量（打开次数）'' AFTER `share_expire_at`'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
