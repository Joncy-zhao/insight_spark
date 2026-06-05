-- 看板分组/用途（管理员看板管理）
ALTER TABLE `is_dashboard`
  ADD COLUMN IF NOT EXISTS `group_name` VARCHAR(128) NULL COMMENT '分组/用途' AFTER `description`;

CREATE INDEX IF NOT EXISTS `idx_dashboard_group_name` ON `is_dashboard` (`group_name`);
