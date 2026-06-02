USE `insight_spark`;

-- 用户端预测与情景模拟模块字段映射快照数据库变更
-- 生成时间：2026-06-02
-- 编码要求：UTF-8
-- 变更范围：
-- 1. is_advanced_analysis_plan 追加 field_mapping_json 字段，保存当前方案最新确认字段映射
-- 2. is_advanced_analysis_plan_version 追加 field_mapping_json 字段，保存每个版本的字段映射快照
-- 3. 使用 information_schema 判断字段是否存在，重复执行不会影响旧数据
-- 4. 仅追加可空 JSON 字段，不清空、不覆盖、不回写历史方案资产

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_advanced_analysis_plan'
        AND column_name = 'field_mapping_json'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_analysis_plan` ADD COLUMN `field_mapping_json` JSON NULL COMMENT ''用户确认后的字段映射快照'' AFTER `llm_json`'
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
        AND table_name = 'is_advanced_analysis_plan_version'
        AND column_name = 'field_mapping_json'
    ),
    'SELECT 1',
    'ALTER TABLE `is_advanced_analysis_plan_version` ADD COLUMN `field_mapping_json` JSON NULL COMMENT ''版本字段映射快照'' AFTER `llm_json`'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
