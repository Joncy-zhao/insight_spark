-- 分组归属：NULL=平台公共分组（管理员），非空=用户个人分组
ALTER TABLE `is_dashboard_group`
  ADD COLUMN `owner_user_id` VARCHAR(64) NULL COMMENT '用户个人分组所有者，NULL 表示平台分组' AFTER `parent_id`;

CREATE INDEX `idx_dashboard_group_owner` ON `is_dashboard_group` (`owner_user_id`);
