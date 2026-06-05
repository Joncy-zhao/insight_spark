-- 看板分组/用途（管理员看板管理）
-- MySQL 8.0.12+ 支持 IF NOT EXISTS；旧版本由 StackCSchemaInitializer.addColumnIfMissing 兜底

ALTER TABLE `is_dashboard`
  ADD COLUMN `group_name` VARCHAR(128) NULL COMMENT '分组/用途' AFTER `description`;

CREATE INDEX `idx_dashboard_group_name` ON `is_dashboard` (`group_name`);
