USE `insight_spark`;

-- is_data_field semantic metadata compatibility migration
-- Generated at: 2026-06-08
-- Encoding: UTF-8
-- Scope:
-- 1. Add semantic metadata columns required by SQL audit and GraphRAG.
-- 2. Use information_schema checks so the script can be executed repeatedly.
-- 3. Only add missing columns. Existing data is not deleted, updated, overwritten, or rebuilt.

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
        AND column_name = 'field_comment'
    ),
    'SELECT 1',
    'ALTER TABLE `is_data_field` ADD COLUMN `field_comment` VARCHAR(512) NULL COMMENT ''field business comment'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
        AND column_name = 'synonyms'
    ),
    'SELECT 1',
    'ALTER TABLE `is_data_field` ADD COLUMN `synonyms` VARCHAR(1000) NULL COMMENT ''business synonyms for semantic matching'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
        AND column_name = 'sensitive'
    ),
    'SELECT 1',
    'ALTER TABLE `is_data_field` ADD COLUMN `sensitive` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''sensitive field flag'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    NOT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
    )
    OR EXISTS(
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'is_data_field'
        AND column_name = 'sort_order'
    ),
    'SELECT 1',
    'ALTER TABLE `is_data_field` ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT ''field display order'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
