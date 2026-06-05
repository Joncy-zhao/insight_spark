-- 看板分组树（支持嵌套子分组）
CREATE TABLE IF NOT EXISTS `is_dashboard_group` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` BIGINT NULL COMMENT '父分组 id',
  `name` VARCHAR(128) NOT NULL COMMENT '分组名称',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dashboard_group_parent` (`parent_id`),
  CONSTRAINT `fk_dashboard_group_parent` FOREIGN KEY (`parent_id`) REFERENCES `is_dashboard_group` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板分组';

ALTER TABLE `is_dashboard`
  ADD COLUMN `group_id` BIGINT NULL COMMENT '所属分组 id' AFTER `group_name`;

ALTER TABLE `is_dashboard`
  ADD CONSTRAINT `fk_dashboard_group` FOREIGN KEY (`group_id`) REFERENCES `is_dashboard_group` (`id`)
    ON DELETE SET NULL ON UPDATE CASCADE;
