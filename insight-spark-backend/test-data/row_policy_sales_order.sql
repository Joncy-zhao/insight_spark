-- Test table for official datasource row-level isolation.
-- Import this into the MySQL database configured as an official datasource.

DROP TABLE IF EXISTS `demo_sales_order`;

CREATE TABLE `demo_sales_order` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'order primary key',
  `order_no` VARCHAR(32) NOT NULL COMMENT 'order number',
  `customer_name` VARCHAR(80) NOT NULL COMMENT 'customer name',
  `region` VARCHAR(20) NOT NULL COMMENT 'sales region',
  `province` VARCHAR(20) NOT NULL COMMENT 'province',
  `sales_role` VARCHAR(32) NOT NULL COMMENT 'role that owns this row',
  `sales_owner` VARCHAR(32) NOT NULL COMMENT 'sales owner user id',
  `amount` DECIMAL(12,2) NOT NULL COMMENT 'order amount',
  `order_date` DATE NOT NULL COMMENT 'order date',
  `status` VARCHAR(20) NOT NULL COMMENT 'order status',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_demo_sales_order_no` (`order_no`),
  KEY `idx_demo_sales_order_region` (`region`),
  KEY `idx_demo_sales_order_role` (`sales_role`),
  KEY `idx_demo_sales_order_owner` (`sales_owner`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='row policy test sales orders';

INSERT INTO `demo_sales_order`
  (`order_no`, `customer_name`, `region`, `province`, `sales_role`, `sales_owner`, `amount`, `order_date`, `status`)
VALUES
  ('SO-2026-001', '杭州星河科技', '华东', '浙江', 'sales_east', 'user_east', 128000.00, '2026-05-01', 'PAID'),
  ('SO-2026-002', '上海云帆制造', '华东', '上海', 'sales_east', 'user_east', 93600.00, '2026-05-03', 'PAID'),
  ('SO-2026-003', '南京智造工厂', '华东', '江苏', 'sales_east', 'user_east', 156800.00, '2026-05-05', 'SIGNED'),
  ('SO-2026-004', '广州南岭贸易', '华南', '广东', 'sales_south', 'user_south', 88000.00, '2026-05-07', 'PAID'),
  ('SO-2026-005', '深圳前海零售', '华南', '广东', 'sales_south', 'user_south', 231000.00, '2026-05-10', 'SIGNED'),
  ('SO-2026-006', '厦门海风食品', '华南', '福建', 'sales_south', 'user_south', 67000.00, '2026-05-12', 'PAID'),
  ('SO-2026-007', '北京北辰服务', '华北', '北京', 'sales_north', 'user_north', 198000.00, '2026-05-15', 'PAID'),
  ('SO-2026-008', '天津港湾物流', '华北', '天津', 'sales_north', 'user_north', 74500.00, '2026-05-18', 'SIGNED'),
  ('SO-2026-009', '青岛海岸运输', '华北', '山东', 'sales_north', 'user_north', 112300.00, '2026-05-20', 'PAID'),
  ('SO-2026-010', '成都锦城餐饮', '西南', '四川', 'sales_west', 'user_west', 99500.00, '2026-05-21', 'PAID'),
  ('SO-2026-011', '重庆山城能源', '西南', '重庆', 'sales_west', 'user_west', 305000.00, '2026-05-23', 'SIGNED'),
  ('SO-2026-012', '昆明云岭文旅', '西南', '云南', 'sales_west', 'user_west', 52000.00, '2026-05-25', 'PAID');
