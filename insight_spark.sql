/*
 Navicat Premium Dump SQL

 Source Server         : MySQL8.0
 Source Server Type    : MySQL
 Source Server Version : 80041 (8.0.41)
 Source Host           : localhost:3306
 Source Schema         : insight_spark

 Target Server Type    : MySQL
 Target Server Version : 80041 (8.0.41)
 File Encoding         : 65001

 Date: 22/07/2026 11:13:59
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ai_chart_rule
-- ----------------------------
DROP TABLE IF EXISTS `ai_chart_rule`;
CREATE TABLE `ai_chart_rule`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_code` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `rule_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `scenario_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `chart_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `priority` int NOT NULL DEFAULT 100,
  `match_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `render_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `explain_template` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `updated_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `rule_code`(`rule_code` ASC) USING BTREE,
  INDEX `idx_ai_chart_rule_scenario`(`scenario_type` ASC) USING BTREE,
  INDEX `idx_ai_chart_rule_enabled`(`enabled` ASC) USING BTREE,
  INDEX `idx_ai_chart_rule_priority`(`priority` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI chart recommendation rules' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of ai_chart_rule
-- ----------------------------
INSERT INTO `ai_chart_rule` VALUES (1, 'time_series_default', '时序趋势默认规则', 'TIME_SERIES', 'line', 1, 400, '{\"timeRequired\":true,\"numericRequired\":true}', '{\"prediction\":{\"confidence\":0.95,\"enabled\":true},\"smooth\":true}', '识别到时间字段和数值指标，推荐折线图展示趋势，并支持预测曲线与 95% 置信区间。', 'system', 'system', '2026-07-22 11:00:40', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule` VALUES (2, 'group_compare_default', '分组对比默认规则', 'GROUP_COMPARE', 'bar', 1, 300, '{\"dimensionRequired\":true,\"numericRequired\":true,\"topN\":20}', '{\"sort\":\"desc\",\"compare\":{\"yoy\":true,\"mom\":true}}', '识别到分类维度和数值指标，推荐柱状图展示分组对比，并支持同比、环比逻辑。', 'system', 'system', '2026-07-22 11:00:40', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule` VALUES (3, 'ratio_default', '占比分析默认规则', 'RATIO', 'doughnut', 1, 250, '{\"numericRequired\":true,\"dimensionRequired\":true}', '{\"label\":{\"digits\":1,\"minPercent\":3,\"showPercent\":true}}', '识别到结构占比分析场景，推荐环形图展示各分类贡献比例。', 'system', 'system', '2026-07-22 11:00:40', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule` VALUES (4, 'radar_default', '多指标雷达默认规则', 'RADAR', 'radar', 1, 240, '{\"numericRequired\":true,\"minNumericFields\":3}', '{\"radar\":{\"symbolSize\":4,\"lineWidth\":2,\"areaOpacity\":0.12}}', '识别到多指标评分、能力画像或综合评价场景，推荐雷达图展示多个维度的相对表现。', 'system', 'system', '2026-07-22 11:00:41', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule` VALUES (5, 'scatter_default', '相关分布散点默认规则', 'SCATTER', 'scatter', 1, 230, '{\"numericRequired\":true,\"minNumericFields\":2}', '{\"scatter\":{\"opacity\":0.78,\"symbolSize\":10}}', '识别到两个数值指标的相关性、分布或离群点分析场景，推荐散点图。', 'system', 'system', '2026-07-22 11:00:41', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule` VALUES (6, 'metric_card_default', '核心指标卡默认规则', 'METRIC', 'metric', 1, 220, '{\"numericRequired\":true,\"singleMetric\":true}', '{\"metric\":{\"trend\":true,\"compareLabel\":\"较上期\",\"precision\":2}}', '识别到单指标、KPI、总量或当前值展示场景，推荐指标卡突出核心数值。', 'system', 'system', '2026-07-22 11:00:41', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule` VALUES (7, 'geo_map_default', '地域分布地图默认规则', 'MAP', 'map', 1, 210, '{\"geoRequired\":true,\"numericRequired\":true}', '{\"map\":{\"roam\":false,\"mapName\":\"china\",\"geoLevel\":\"province\"}}', '识别到省份、城市、地区等地域分布场景，推荐地图展示空间分布。', 'system', 'system', '2026-07-22 11:00:41', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule` VALUES (8, 'detail_default', '明细数据默认规则', 'DETAIL', 'table', 1, 100, '{\"minFields\":5,\"fallback\":true}', '{\"sortable\":true,\"pagination\":{\"pageSize\":20}}', '数据更适合逐行查看，推荐表格并支持字段显示、排序和分页。', 'system', 'system', '2026-07-22 11:00:42', '2026-07-22 11:00:42');

-- ----------------------------
-- Table structure for ai_chart_rule_audit_log
-- ----------------------------
DROP TABLE IF EXISTS `ai_chart_rule_audit_log`;
CREATE TABLE `ai_chart_rule_audit_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint NULL DEFAULT NULL,
  `action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `before_snapshot` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `after_snapshot` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `operator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_ai_chart_audit_rule`(`rule_id` ASC) USING BTREE,
  INDEX `idx_ai_chart_audit_action`(`action` ASC) USING BTREE,
  INDEX `idx_ai_chart_audit_created`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI chart rule audit log' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of ai_chart_rule_audit_log
-- ----------------------------
INSERT INTO `ai_chart_rule_audit_log` VALUES (1, 1, 'CREATE', NULL, '{\"id\":1,\"ruleCode\":\"time_series_default\",\"ruleName\":\"时序趋势默认规则\",\"scenarioType\":\"TIME_SERIES\",\"chartType\":\"line\",\"enabled\":true,\"priority\":400,\"matchConfig\":{\"timeRequired\":true,\"numericRequired\":true},\"renderConfig\":{\"prediction\":{\"confidence\":0.95,\"enabled\":true},\"smooth\":true},\"explainTemplate\":\"识别到时间字段和数值指标，推荐折线图展示趋势，并支持预测曲线与 95% 置信区间。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:40\",\"updatedAt\":\"2026-07-22T11:00:40\"}', 'system', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule_audit_log` VALUES (2, 2, 'CREATE', NULL, '{\"id\":2,\"ruleCode\":\"group_compare_default\",\"ruleName\":\"分组对比默认规则\",\"scenarioType\":\"GROUP_COMPARE\",\"chartType\":\"bar\",\"enabled\":true,\"priority\":300,\"matchConfig\":{\"dimensionRequired\":true,\"numericRequired\":true,\"topN\":20},\"renderConfig\":{\"sort\":\"desc\",\"compare\":{\"yoy\":true,\"mom\":true}},\"explainTemplate\":\"识别到分类维度和数值指标，推荐柱状图展示分组对比，并支持同比、环比逻辑。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:40\",\"updatedAt\":\"2026-07-22T11:00:40\"}', 'system', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule_audit_log` VALUES (3, 3, 'CREATE', NULL, '{\"id\":3,\"ruleCode\":\"ratio_default\",\"ruleName\":\"占比分析默认规则\",\"scenarioType\":\"RATIO\",\"chartType\":\"doughnut\",\"enabled\":true,\"priority\":250,\"matchConfig\":{\"numericRequired\":true,\"dimensionRequired\":true},\"renderConfig\":{\"label\":{\"digits\":1,\"minPercent\":3,\"showPercent\":true}},\"explainTemplate\":\"识别到结构占比分析场景，推荐环形图展示各分类贡献比例。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:40\",\"updatedAt\":\"2026-07-22T11:00:40\"}', 'system', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule_audit_log` VALUES (4, 4, 'CREATE', NULL, '{\"id\":4,\"ruleCode\":\"radar_default\",\"ruleName\":\"多指标雷达默认规则\",\"scenarioType\":\"RADAR\",\"chartType\":\"radar\",\"enabled\":true,\"priority\":240,\"matchConfig\":{\"numericRequired\":true,\"minNumericFields\":3},\"renderConfig\":{\"radar\":{\"symbolSize\":4,\"lineWidth\":2,\"areaOpacity\":0.12}},\"explainTemplate\":\"识别到多指标评分、能力画像或综合评价场景，推荐雷达图展示多个维度的相对表现。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:41\",\"updatedAt\":\"2026-07-22T11:00:41\"}', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_audit_log` VALUES (5, 5, 'CREATE', NULL, '{\"id\":5,\"ruleCode\":\"scatter_default\",\"ruleName\":\"相关分布散点默认规则\",\"scenarioType\":\"SCATTER\",\"chartType\":\"scatter\",\"enabled\":true,\"priority\":230,\"matchConfig\":{\"numericRequired\":true,\"minNumericFields\":2},\"renderConfig\":{\"scatter\":{\"opacity\":0.78,\"symbolSize\":10}},\"explainTemplate\":\"识别到两个数值指标的相关性、分布或离群点分析场景，推荐散点图。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:41\",\"updatedAt\":\"2026-07-22T11:00:41\"}', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_audit_log` VALUES (6, 6, 'CREATE', NULL, '{\"id\":6,\"ruleCode\":\"metric_card_default\",\"ruleName\":\"核心指标卡默认规则\",\"scenarioType\":\"METRIC\",\"chartType\":\"metric\",\"enabled\":true,\"priority\":220,\"matchConfig\":{\"numericRequired\":true,\"singleMetric\":true},\"renderConfig\":{\"metric\":{\"trend\":true,\"compareLabel\":\"较上期\",\"precision\":2}},\"explainTemplate\":\"识别到单指标、KPI、总量或当前值展示场景，推荐指标卡突出核心数值。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:41\",\"updatedAt\":\"2026-07-22T11:00:41\"}', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_audit_log` VALUES (7, 7, 'CREATE', NULL, '{\"id\":7,\"ruleCode\":\"geo_map_default\",\"ruleName\":\"地域分布地图默认规则\",\"scenarioType\":\"MAP\",\"chartType\":\"map\",\"enabled\":true,\"priority\":210,\"matchConfig\":{\"geoRequired\":true,\"numericRequired\":true},\"renderConfig\":{\"map\":{\"roam\":false,\"mapName\":\"china\",\"geoLevel\":\"province\"}},\"explainTemplate\":\"识别到省份、城市、地区等地域分布场景，推荐地图展示空间分布。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:41\",\"updatedAt\":\"2026-07-22T11:00:41\"}', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_audit_log` VALUES (8, 8, 'CREATE', NULL, '{\"id\":8,\"ruleCode\":\"detail_default\",\"ruleName\":\"明细数据默认规则\",\"scenarioType\":\"DETAIL\",\"chartType\":\"table\",\"enabled\":true,\"priority\":100,\"matchConfig\":{\"minFields\":5,\"fallback\":true},\"renderConfig\":{\"sortable\":true,\"pagination\":{\"pageSize\":20}},\"explainTemplate\":\"数据更适合逐行查看，推荐表格并支持字段显示、排序和分页。\",\"createdBy\":\"system\",\"updatedBy\":\"system\",\"createdAt\":\"2026-07-22T11:00:42\",\"updatedAt\":\"2026-07-22T11:00:42\"}', 'system', '2026-07-22 11:00:42');
INSERT INTO `ai_chart_rule_audit_log` VALUES (9, NULL, 'PREFERENCE_UPDATE', NULL, '{\"fontConfig\":{\"fontSize\":12,\"fontFamily\":\"Microsoft YaHei\"},\"colorPalette\":[\"#2563eb\",\"#16a34a\",\"#f59e0b\",\"#dc2626\",\"#7c3aed\",\"#0891b2\"],\"layoutConfig\":{\"legend\":\"top\",\"height\":360,\"gridContainLabel\":true},\"themeName\":\"企业默认可视化风格\",\"defaultOptions\":{\"animation\":true,\"dataZoom\":false,\"voiceSummary\":true}}', 'system', '2026-07-22 11:00:42');

-- ----------------------------
-- Table structure for ai_chart_rule_version
-- ----------------------------
DROP TABLE IF EXISTS `ai_chart_rule_version`;
CREATE TABLE `ai_chart_rule_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint NOT NULL,
  `rule_code` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `version_no` int NOT NULL,
  `snapshot` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `change_action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `operator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ai_chart_rule_version_no`(`rule_id` ASC, `version_no` ASC) USING BTREE,
  INDEX `idx_ai_chart_rule_version_rule`(`rule_id` ASC) USING BTREE,
  INDEX `idx_ai_chart_rule_version_code`(`rule_code` ASC) USING BTREE,
  INDEX `idx_ai_chart_rule_version_created`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI chart rule version snapshots' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of ai_chart_rule_version
-- ----------------------------
INSERT INTO `ai_chart_rule_version` VALUES (1, 1, 'time_series_default', 1, '{\"ruleCode\":\"time_series_default\",\"ruleName\":\"时序趋势默认规则\",\"scenarioType\":\"TIME_SERIES\",\"chartType\":\"line\",\"enabled\":true,\"priority\":400,\"matchConfig\":{\"timeRequired\":true,\"numericRequired\":true},\"renderConfig\":{\"prediction\":{\"confidence\":0.95,\"enabled\":true},\"smooth\":true},\"explainTemplate\":\"识别到时间字段和数值指标，推荐折线图展示趋势，并支持预测曲线与 95% 置信区间。\"}', 'CREATE', 'system', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule_version` VALUES (2, 2, 'group_compare_default', 1, '{\"ruleCode\":\"group_compare_default\",\"ruleName\":\"分组对比默认规则\",\"scenarioType\":\"GROUP_COMPARE\",\"chartType\":\"bar\",\"enabled\":true,\"priority\":300,\"matchConfig\":{\"dimensionRequired\":true,\"numericRequired\":true,\"topN\":20},\"renderConfig\":{\"sort\":\"desc\",\"compare\":{\"yoy\":true,\"mom\":true}},\"explainTemplate\":\"识别到分类维度和数值指标，推荐柱状图展示分组对比，并支持同比、环比逻辑。\"}', 'CREATE', 'system', '2026-07-22 11:00:40');
INSERT INTO `ai_chart_rule_version` VALUES (3, 3, 'ratio_default', 1, '{\"ruleCode\":\"ratio_default\",\"ruleName\":\"占比分析默认规则\",\"scenarioType\":\"RATIO\",\"chartType\":\"doughnut\",\"enabled\":true,\"priority\":250,\"matchConfig\":{\"numericRequired\":true,\"dimensionRequired\":true},\"renderConfig\":{\"label\":{\"digits\":1,\"minPercent\":3,\"showPercent\":true}},\"explainTemplate\":\"识别到结构占比分析场景，推荐环形图展示各分类贡献比例。\"}', 'CREATE', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_version` VALUES (4, 4, 'radar_default', 1, '{\"ruleCode\":\"radar_default\",\"ruleName\":\"多指标雷达默认规则\",\"scenarioType\":\"RADAR\",\"chartType\":\"radar\",\"enabled\":true,\"priority\":240,\"matchConfig\":{\"numericRequired\":true,\"minNumericFields\":3},\"renderConfig\":{\"radar\":{\"symbolSize\":4,\"lineWidth\":2,\"areaOpacity\":0.12}},\"explainTemplate\":\"识别到多指标评分、能力画像或综合评价场景，推荐雷达图展示多个维度的相对表现。\"}', 'CREATE', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_version` VALUES (5, 5, 'scatter_default', 1, '{\"ruleCode\":\"scatter_default\",\"ruleName\":\"相关分布散点默认规则\",\"scenarioType\":\"SCATTER\",\"chartType\":\"scatter\",\"enabled\":true,\"priority\":230,\"matchConfig\":{\"numericRequired\":true,\"minNumericFields\":2},\"renderConfig\":{\"scatter\":{\"opacity\":0.78,\"symbolSize\":10}},\"explainTemplate\":\"识别到两个数值指标的相关性、分布或离群点分析场景，推荐散点图。\"}', 'CREATE', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_version` VALUES (6, 6, 'metric_card_default', 1, '{\"ruleCode\":\"metric_card_default\",\"ruleName\":\"核心指标卡默认规则\",\"scenarioType\":\"METRIC\",\"chartType\":\"metric\",\"enabled\":true,\"priority\":220,\"matchConfig\":{\"numericRequired\":true,\"singleMetric\":true},\"renderConfig\":{\"metric\":{\"trend\":true,\"compareLabel\":\"较上期\",\"precision\":2}},\"explainTemplate\":\"识别到单指标、KPI、总量或当前值展示场景，推荐指标卡突出核心数值。\"}', 'CREATE', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_version` VALUES (7, 7, 'geo_map_default', 1, '{\"ruleCode\":\"geo_map_default\",\"ruleName\":\"地域分布地图默认规则\",\"scenarioType\":\"MAP\",\"chartType\":\"map\",\"enabled\":true,\"priority\":210,\"matchConfig\":{\"geoRequired\":true,\"numericRequired\":true},\"renderConfig\":{\"map\":{\"roam\":false,\"mapName\":\"china\",\"geoLevel\":\"province\"}},\"explainTemplate\":\"识别到省份、城市、地区等地域分布场景，推荐地图展示空间分布。\"}', 'CREATE', 'system', '2026-07-22 11:00:41');
INSERT INTO `ai_chart_rule_version` VALUES (8, 8, 'detail_default', 1, '{\"ruleCode\":\"detail_default\",\"ruleName\":\"明细数据默认规则\",\"scenarioType\":\"DETAIL\",\"chartType\":\"table\",\"enabled\":true,\"priority\":100,\"matchConfig\":{\"minFields\":5,\"fallback\":true},\"renderConfig\":{\"sortable\":true,\"pagination\":{\"pageSize\":20}},\"explainTemplate\":\"数据更适合逐行查看，推荐表格并支持字段显示、排序和分页。\"}', 'CREATE', 'system', '2026-07-22 11:00:42');

-- ----------------------------
-- Table structure for ai_chart_style_preference
-- ----------------------------
DROP TABLE IF EXISTS `ai_chart_style_preference`;
CREATE TABLE `ai_chart_style_preference`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `preference_code` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `theme_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `color_palette` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `font_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `layout_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `default_options` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `preference_code`(`preference_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI chart style preferences' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of ai_chart_style_preference
-- ----------------------------
INSERT INTO `ai_chart_style_preference` VALUES (1, 'enterprise_default', '企业默认可视化风格', '[\"#2563eb\",\"#16a34a\",\"#f59e0b\",\"#dc2626\",\"#7c3aed\",\"#0891b2\"]', '{\"fontSize\":12,\"fontFamily\":\"Microsoft YaHei\"}', '{\"legend\":\"top\",\"height\":360,\"gridContainLabel\":true}', '{\"animation\":true,\"dataZoom\":false,\"voiceSummary\":true}', 1, '2026-07-22 11:00:42');

-- ----------------------------
-- Table structure for is_admin_chat_test_artifact
-- ----------------------------
DROP TABLE IF EXISTS `is_admin_chat_test_artifact`;
CREATE TABLE `is_admin_chat_test_artifact`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL COMMENT '测试会话 id',
  `artifact_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'SQL / CHART / TABLE / REASONING / SECURITY / PERMISSION',
  `artifact_json` json NULL COMMENT '产物完整 JSON',
  `sql_text` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'SQL 文本',
  `chart_config_json` json NULL COMMENT '图表配置 JSON',
  `result_preview_json` json NULL COMMENT '结果预览 JSON',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_admin_chat_test_artifact_session`(`session_id` ASC, `artifact_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员端对话查询测试产物表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_admin_chat_test_artifact
-- ----------------------------

-- ----------------------------
-- Table structure for is_admin_chat_test_export
-- ----------------------------
DROP TABLE IF EXISTS `is_admin_chat_test_export`;
CREATE TABLE `is_admin_chat_test_export`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL COMMENT '测试会话 id',
  `export_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '导出类型',
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '导出文件名',
  `file_path` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '导出文件路径',
  `export_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SUCCESS' COMMENT '导出状态',
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建人 user_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_admin_chat_test_export_session`(`session_id` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员端对话查询测试导出记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_admin_chat_test_export
-- ----------------------------

-- ----------------------------
-- Table structure for is_admin_chat_test_session
-- ----------------------------
DROP TABLE IF EXISTS `is_admin_chat_test_session`;
CREATE TABLE `is_admin_chat_test_session`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tester_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '测试人 user_id',
  `tester_role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '测试人角色',
  `question` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '自然语言测试指令',
  `datasource_scope_json` json NULL COMMENT '测试数据源范围',
  `model_config_json` json NULL COMMENT '模型配置',
  `permission_context_json` json NULL COMMENT '权限模拟上下文',
  `final_sql` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '最终生成 SQL',
  `risk_level` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'SQL 风险等级',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED / RUNNING / SUCCESS / FAILED',
  `duration_ms` bigint NOT NULL DEFAULT 0 COMMENT '执行耗时',
  `error_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '异常信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_admin_chat_test_session_tester`(`tester_user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_admin_chat_test_session_status`(`status` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员端对话查询测试会话表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_admin_chat_test_session
-- ----------------------------

-- ----------------------------
-- Table structure for is_admin_chat_test_step
-- ----------------------------
DROP TABLE IF EXISTS `is_admin_chat_test_step`;
CREATE TABLE `is_admin_chat_test_step`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL COMMENT '测试会话 id',
  `step_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '步骤类型',
  `step_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '步骤标题',
  `step_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SUCCESS' COMMENT '步骤状态',
  `step_payload_json` json NULL COMMENT '步骤上下文',
  `error_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '异常信息',
  `started_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  `finished_at` datetime NULL DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_admin_chat_test_step_session`(`session_id` ASC, `id` ASC) USING BTREE,
  INDEX `idx_admin_chat_test_step_type`(`step_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员端对话查询测试步骤表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_admin_chat_test_step
-- ----------------------------

-- ----------------------------
-- Table structure for is_admin_chat_test_template
-- ----------------------------
DROP TABLE IF EXISTS `is_admin_chat_test_template`;
CREATE TABLE `is_admin_chat_test_template`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '模板名称',
  `question` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '自然语言测试指令',
  `datasource_scope_json` json NULL COMMENT '数据源范围',
  `model_config_json` json NULL COMMENT '模型配置',
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建人 user_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_admin_chat_test_template_created`(`created_by` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员端对话查询测试指令模板表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_admin_chat_test_template
-- ----------------------------

-- ----------------------------
-- Table structure for is_advanced_alert_event
-- ----------------------------
DROP TABLE IF EXISTS `is_advanced_alert_event`;
CREATE TABLE `is_advanced_alert_event`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint NOT NULL COMMENT '预警规则 ID',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '规则所属用户 user_id',
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '数据源表名',
  `metric_field` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '指标字段',
  `time_field` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '时间字段',
  `bucket_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '触发时间桶',
  `actual_value` decimal(20, 4) NOT NULL COMMENT '实际指标值',
  `threshold_value` decimal(20, 4) NULL DEFAULT NULL COMMENT '阈值',
  `operator` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '判断条件：lt/gt/zscore',
  `z_score` decimal(20, 6) NULL DEFAULT NULL COMMENT 'Z-Score 值',
  `baseline_value` decimal(20, 4) NULL DEFAULT NULL COMMENT '历史基线均值',
  `deviation_rate` decimal(20, 6) NULL DEFAULT NULL COMMENT '偏离比例',
  `reason` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '触发原因说明',
  `chart_snapshot_json` json NULL COMMENT '图表快照 JSON',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'OPEN' COMMENT '事件状态：OPEN/ACK/CLOSED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `ack_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '确认人 user_id',
  `ack_at` datetime NULL DEFAULT NULL COMMENT '确认时间',
  `closed_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '关闭人 user_id',
  `closed_at` datetime NULL DEFAULT NULL COMMENT '关闭时间',
  `handle_note` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '处理备注',
  `status_updated_at` datetime NULL DEFAULT NULL COMMENT '状态更新时间',
  `llm_explanation_json` json NULL COMMENT '预警事件 LLM/规则解释快照',
  `explanation_note` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '预警解释备注',
  `explanation_updated_at` datetime NULL DEFAULT NULL COMMENT '预警解释更新时间',
  `org_scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'GLOBAL' COMMENT '组织/权限域，用于预警事件隔离',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_advanced_alert_event_rule_bucket`(`rule_id` ASC, `bucket_name` ASC, `operator` ASC) USING BTREE,
  INDEX `idx_advanced_alert_event_user`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_event_rule`(`rule_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_event_status`(`status` ASC) USING BTREE,
  INDEX `idx_advanced_alert_event_status_updated`(`status` ASC, `status_updated_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_event_org`(`org_scope` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预测与情景模拟模块预警事件' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_advanced_alert_event
-- ----------------------------

-- ----------------------------
-- Table structure for is_advanced_alert_push_log
-- ----------------------------
DROP TABLE IF EXISTS `is_advanced_alert_push_log`;
CREATE TABLE `is_advanced_alert_push_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL COMMENT '预警事件 ID',
  `rule_id` bigint NOT NULL COMMENT '预警规则 ID',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '规则所属用户 user_id',
  `channel` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '推送渠道：email/dingtalk',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT '推送状态：PENDING/SUCCESS/FAILED',
  `attempt_count` int NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `target` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '推送目标地址或 webhook 摘要',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '告警标题',
  `content` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '告警内容摘要',
  `error_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '失败原因',
  `request_json` json NULL COMMENT '推送请求快照',
  `response_json` json NULL COMMENT '推送响应快照',
  `last_attempt_at` datetime NULL DEFAULT NULL COMMENT '最近尝试时间',
  `next_retry_at` datetime NULL DEFAULT NULL COMMENT '下次建议重试时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `org_scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'GLOBAL' COMMENT '组织/权限域，用于预警推送记录隔离',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_advanced_alert_push_log_event`(`event_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_push_log_rule`(`rule_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_push_log_user`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_push_log_status`(`status` ASC, `next_retry_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_push_log_org`(`org_scope` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预测与情景模拟模块预警推送记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_advanced_alert_push_log
-- ----------------------------

-- ----------------------------
-- Table structure for is_advanced_alert_rule
-- ----------------------------
DROP TABLE IF EXISTS `is_advanced_alert_rule`;
CREATE TABLE `is_advanced_alert_rule`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '规则所属用户 user_id',
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '数据源表名',
  `metric_field` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '指标字段',
  `time_field` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '时间字段',
  `granularity` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'day' COMMENT '聚合粒度：day/week/month/quarter/year',
  `filter_expression` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '用户输入过滤条件',
  `resolved_filter_expression` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '解析后的物理字段过滤条件',
  `operator` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'lt' COMMENT '判断条件：lt/gt/zscore',
  `threshold_value` decimal(20, 4) NULL DEFAULT NULL COMMENT '阈值',
  `detection_cycle` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'daily' COMMENT '检测周期：hourly/daily/weekly/monthly',
  `channels_json` json NULL COMMENT '通知渠道 JSON，例如 email/dingtalk',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '规则状态：ACTIVE/DISABLED/DELETED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `last_checked_at` datetime NULL DEFAULT NULL COMMENT '最近一次离线 Agent 检测时间',
  `last_triggered_at` datetime NULL DEFAULT NULL COMMENT '最近一次生成预警事件时间',
  `rule_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '预警规则展示名称/自然语言指令',
  `org_scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'GLOBAL' COMMENT '组织/权限域，用于预警规则隔离',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_advanced_alert_rule_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_advanced_alert_rule_table`(`table_name` ASC) USING BTREE,
  INDEX `idx_advanced_alert_rule_status`(`status` ASC) USING BTREE,
  INDEX `idx_advanced_alert_rule_schedule`(`status` ASC, `detection_cycle` ASC, `last_checked_at` ASC) USING BTREE,
  INDEX `idx_advanced_alert_rule_org`(`org_scope` ASC, `status` ASC, `updated_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预测与情景模拟模块预警规则' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_advanced_alert_rule
-- ----------------------------

-- ----------------------------
-- Table structure for is_advanced_analysis_plan
-- ----------------------------
DROP TABLE IF EXISTS `is_advanced_analysis_plan`;
CREATE TABLE `is_advanced_analysis_plan`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '方案所属用户 user_id',
  `plan_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '方案类型：forecast/whatIf',
  `plan_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '方案名称',
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '数据源表名',
  `metric_label` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '页面展示指标名称',
  `time_range_label` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '页面展示时间范围',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SAVED' COMMENT '方案状态：SAVED/DELETED',
  `request_json` json NULL COMMENT '用户确认后的参数、字段映射、过滤条件等',
  `result_json` json NULL COMMENT '最近一次计算结果快照',
  `llm_json` json NULL COMMENT 'LLM 解析结果与解释建议',
  `field_mapping_json` json NULL COMMENT '用户确认后的字段映射快照',
  `version_no` int NOT NULL DEFAULT 1 COMMENT '当前结果版本号',
  `last_calculated_at` datetime NULL DEFAULT NULL COMMENT '最近一次计算时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_advanced_analysis_plan_user`(`user_id` ASC, `updated_at` ASC) USING BTREE,
  INDEX `idx_advanced_analysis_plan_type`(`plan_type` ASC) USING BTREE,
  INDEX `idx_advanced_analysis_plan_table`(`table_name` ASC) USING BTREE,
  INDEX `idx_advanced_analysis_plan_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预测与情景模拟模块预测/推演方案资产' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_advanced_analysis_plan
-- ----------------------------

-- ----------------------------
-- Table structure for is_advanced_analysis_plan_version
-- ----------------------------
DROP TABLE IF EXISTS `is_advanced_analysis_plan_version`;
CREATE TABLE `is_advanced_analysis_plan_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL COMMENT '方案主表 ID',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '方案所属用户 user_id',
  `plan_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '方案类型：forecast/whatIf',
  `plan_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '版本记录时的方案名称',
  `version_no` int NOT NULL COMMENT '版本号',
  `request_json` json NULL COMMENT '用户确认参数快照',
  `result_json` json NULL COMMENT '结果快照',
  `llm_json` json NULL COMMENT 'LLM 解析快照',
  `field_mapping_json` json NULL COMMENT '版本字段映射快照',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_advanced_analysis_plan_version`(`plan_id` ASC, `version_no` ASC) USING BTREE,
  INDEX `idx_advanced_analysis_plan_version_user`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_advanced_analysis_plan_version_plan`(`plan_id` ASC, `version_no` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预测与情景模拟模块方案版本快照' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_advanced_analysis_plan_version
-- ----------------------------

-- ----------------------------
-- Table structure for is_analysis_template
-- ----------------------------
DROP TABLE IF EXISTS `is_analysis_template`;
CREATE TABLE `is_analysis_template`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `template_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `template_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '业务分析模板' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_analysis_template
-- ----------------------------

-- ----------------------------
-- Table structure for is_annotation
-- ----------------------------
DROP TABLE IF EXISTS `is_annotation`;
CREATE TABLE `is_annotation`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '作者 user_id',
  `target_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '对象类型',
  `target_id` bigint NOT NULL COMMENT '对象主键',
  `dashboard_id` bigint NULL DEFAULT NULL COMMENT '所属看板 id',
  `bind_json` json NULL COMMENT '绑定上下文 JSON',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '批注正文',
  `tag` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '批注标签',
  `is_deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `is_hidden` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否隐藏',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_annotation_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_annotation_target`(`target_type` ASC, `target_id` ASC) USING BTREE,
  INDEX `idx_annotation_dashboard`(`dashboard_id` ASC) USING BTREE,
  CONSTRAINT `fk_annotation_dashboard` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`) ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT `fk_annotation_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '业务批注' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_annotation
-- ----------------------------

-- ----------------------------
-- Table structure for is_business_model
-- ----------------------------
DROP TABLE IF EXISTS `is_business_model`;
CREATE TABLE `is_business_model`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `model_requirement` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `owner_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
  `model_json` json NOT NULL,
  `published` tinyint(1) NOT NULL DEFAULT 0,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_business_model_table`(`table_name` ASC) USING BTREE,
  INDEX `idx_business_model_published`(`published` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '零代码业务模型与企业模型库' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_business_model
-- ----------------------------

-- ----------------------------
-- Table structure for is_chat_conversation
-- ----------------------------
DROP TABLE IF EXISTS `is_chat_conversation`;
CREATE TABLE `is_chat_conversation`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `data_source_id` bigint NOT NULL DEFAULT 0,
  `scope_json` json NULL,
  `business_model_id` bigint NULL DEFAULT NULL,
  `summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `last_turn_id` bigint NULL DEFAULT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `is_deleted` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_chat_conv_user_time`(`user_id` ASC, `updated_at` ASC) USING BTREE,
  INDEX `idx_chat_conv_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '对话会话' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_chat_conversation
-- ----------------------------

-- ----------------------------
-- Table structure for is_chat_conversation_artifact
-- ----------------------------
DROP TABLE IF EXISTS `is_chat_conversation_artifact`;
CREATE TABLE `is_chat_conversation_artifact`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `turn_id` bigint NOT NULL,
  `history_id` bigint NULL DEFAULT NULL,
  `artifact_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `artifact_json` json NULL,
  `sql_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `chart_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `risk_level` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_chat_artifact_conversation`(`conversation_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_chat_artifact_turn`(`turn_id` ASC) USING BTREE,
  INDEX `idx_chat_artifact_history`(`history_id` ASC) USING BTREE,
  INDEX `idx_chat_artifact_type`(`artifact_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '对话产物' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_chat_conversation_artifact
-- ----------------------------

-- ----------------------------
-- Table structure for is_chat_conversation_turn
-- ----------------------------
DROP TABLE IF EXISTS `is_chat_conversation_turn`;
CREATE TABLE `is_chat_conversation_turn`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `parent_turn_id` bigint NULL DEFAULT NULL,
  `turn_no` int NOT NULL,
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `message_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `intent_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `context_json` json NULL,
  `followup_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NEW',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_chat_turn_conversation`(`conversation_id` ASC, `turn_no` ASC) USING BTREE,
  INDEX `idx_chat_turn_parent`(`parent_turn_id` ASC) USING BTREE,
  INDEX `idx_chat_turn_role`(`role` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '对话轮次' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_chat_conversation_turn
-- ----------------------------

-- ----------------------------
-- Table structure for is_chat_history_admin_audit
-- ----------------------------
DROP TABLE IF EXISTS `is_chat_history_admin_audit`;
CREATE TABLE `is_chat_history_admin_audit`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '操作类型：ADMIN_DELETE / ADMIN_RERUN / AUTO_PURGE',
  `history_id` bigint NULL DEFAULT NULL COMMENT '关联历史记录 id',
  `related_history_id` bigint NULL DEFAULT NULL COMMENT '关联的新旧历史记录 id',
  `operator_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '操作人 user_id',
  `operator_role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '操作角色',
  `target_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '目标用户 user_id',
  `action_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '操作原因',
  `payload_json` json NULL COMMENT '补充上下文',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_chat_history_admin_audit_history`(`history_id` ASC) USING BTREE,
  INDEX `idx_chat_history_admin_audit_action`(`action_type` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理员对话历史治理审计表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_chat_history_admin_audit
-- ----------------------------

-- ----------------------------
-- Table structure for is_chat_query_history
-- ----------------------------
DROP TABLE IF EXISTS `is_chat_query_history`;
CREATE TABLE `is_chat_query_history`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `data_source_id` bigint NOT NULL,
  `query_table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `query_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `generated_sql` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `reasoning_process` json NULL,
  `llm_model_used` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'unknown',
  `chart_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `chart_snapshot` json NULL,
  `execution_status` tinyint(1) NULL DEFAULT 1,
  `risk_level` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'SAFE',
  `audit_info` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `execution_time_ms` int NULL DEFAULT NULL,
  `is_hit_cache` tinyint(1) NULL DEFAULT 0,
  `is_deleted` tinyint(1) NULL DEFAULT 0,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `conversation_id` bigint NULL DEFAULT NULL,
  `parent_history_id` bigint NULL DEFAULT NULL,
  `turn_no` int NULL DEFAULT NULL,
  `message_role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'ASSISTANT',
  `intent_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `context_json` json NULL,
  `scope_json` json NULL,
  `artifact_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'CHART',
  `summary_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `deleted_at` datetime NULL DEFAULT NULL,
  `deleted_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `delete_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_chat_history_user_time`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_chat_history_risk_level`(`risk_level` ASC) USING BTREE,
  INDEX `idx_chat_history_data_source`(`data_source_id` ASC) USING BTREE,
  INDEX `idx_chat_history_deleted_at`(`is_deleted` ASC, `deleted_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '对话查询与全量历史审计表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_chat_query_history
-- ----------------------------

-- ----------------------------
-- Table structure for is_comment
-- ----------------------------
DROP TABLE IF EXISTS `is_comment`;
CREATE TABLE `is_comment`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` bigint NULL DEFAULT NULL COMMENT '父评论 id',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '作者 user_id',
  `target_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '挂载对象类型',
  `target_id` bigint NOT NULL COMMENT '挂载对象主键',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '评论内容',
  `mentions_json` json NULL COMMENT '提及用户 JSON',
  `is_deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_comment_parent`(`parent_id` ASC) USING BTREE,
  INDEX `idx_comment_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_comment_target`(`target_type` ASC, `target_id` ASC) USING BTREE,
  CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '协同评论' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_comment
-- ----------------------------

-- ----------------------------
-- Table structure for is_compliance_document
-- ----------------------------
DROP TABLE IF EXISTS `is_compliance_document`;
CREATE TABLE `is_compliance_document`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'v1.0',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `updated_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `doc_key`(`doc_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'enterprise compliance document' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_compliance_document
-- ----------------------------
INSERT INTO `is_compliance_document` VALUES (1, 'DATA_SECURITY', '企业数据安全合规文档', 'v1.0', '1. 数据仅可用于申请时声明的业务目的，禁止转发给未授权人员、群组或外部系统。\n2. 手机号、身份证、金额、订单号等敏感字段必须按系统脱敏规则展示和导出。\n3. 官方库访问以表级授权为最小边界；未授权表不可用于对话查询、预览或导出。\n4. 普通用户只能访问本人上传数据和审批通过的数据；管理员操作必须保留审计痕迹。\n5. 违规访问、复制、截图传播或绕过审批使用数据，将触发账号冻结和内部合规处理。\n', 'system', '2026-07-22 10:59:24');

-- ----------------------------
-- Table structure for is_dashboard
-- ----------------------------
DROP TABLE IF EXISTS `is_dashboard`;
CREATE TABLE `is_dashboard`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `owner_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '所有者 user_id',
  `author_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '看板原作者 user_id',
  `source_dashboard_id` bigint NULL DEFAULT NULL COMMENT '另存来源看板 id',
  `save_as_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '另存/复制生成者 user_id',
  `publisher_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '执行发布者 user_id',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '看板名称',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '看板描述',
  `group_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '分组/用途',
  `group_id` bigint NULL DEFAULT NULL COMMENT '所属分组 id',
  `layout_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '画布与组件布局 JSON',
  `is_public` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否公共看板',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `share_token` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '分享 token',
  `share_expire_at` datetime NULL DEFAULT NULL COMMENT '分享过期时间',
  `view_count` bigint NOT NULL DEFAULT 0 COMMENT '访问量（打开次数）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_dashboard_share_token`(`share_token` ASC) USING BTREE,
  INDEX `idx_dashboard_owner`(`owner_user_id` ASC) USING BTREE,
  INDEX `idx_dashboard_public_status`(`is_public` ASC, `status` ASC) USING BTREE,
  INDEX `idx_dashboard_group_name`(`group_name` ASC) USING BTREE,
  INDEX `idx_dashboard_group_id`(`group_id` ASC) USING BTREE,
  CONSTRAINT `fk_dashboard_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `is_user` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '数据看板' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_dashboard
-- ----------------------------

-- ----------------------------
-- Table structure for is_dashboard_component
-- ----------------------------
DROP TABLE IF EXISTS `is_dashboard_component`;
CREATE TABLE `is_dashboard_component`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '看板内组件主键',
  `dashboard_id` bigint NOT NULL COMMENT '看板 id',
  `chart_id` bigint NOT NULL COMMENT '兼容历史图表 is_chat_query_history.id',
  `artifact_id` bigint NULL DEFAULT NULL COMMENT '对话产物 is_chat_conversation_artifact.id',
  `turn_id` bigint NULL DEFAULT NULL COMMENT '对话轮次 is_chat_conversation_turn.id',
  `position_config` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '{\"x\":0,\"y\":0,\"w\":6,\"h\":4}' COMMENT '位置 JSON 冗余',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_dashboard_component_board`(`dashboard_id` ASC) USING BTREE,
  INDEX `idx_dashboard_component_chart`(`chart_id` ASC) USING BTREE,
  INDEX `idx_dashboard_component_artifact`(`artifact_id` ASC) USING BTREE,
  INDEX `idx_dashboard_component_turn`(`turn_id` ASC) USING BTREE,
  CONSTRAINT `fk_dashboard_component_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '看板与对话图表关联' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_dashboard_component
-- ----------------------------

-- ----------------------------
-- Table structure for is_dashboard_follow
-- ----------------------------
DROP TABLE IF EXISTS `is_dashboard_follow`;
CREATE TABLE `is_dashboard_follow`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dashboard_id` bigint NOT NULL COMMENT '看板 id',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '关注者 user_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_dashboard_follow_user`(`dashboard_id` ASC, `user_id` ASC) USING BTREE,
  INDEX `idx_dashboard_follow_user`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_dashboard_follow_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_dashboard_follow_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '看板关注' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_dashboard_follow
-- ----------------------------

-- ----------------------------
-- Table structure for is_dashboard_group
-- ----------------------------
DROP TABLE IF EXISTS `is_dashboard_group`;
CREATE TABLE `is_dashboard_group`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` bigint NULL DEFAULT NULL COMMENT '父分组 id',
  `owner_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '用户个人分组所有者，NULL 表示平台分组',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分组名称',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_dashboard_group_parent`(`parent_id` ASC) USING BTREE,
  INDEX `idx_dashboard_group_owner`(`owner_user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '看板分组' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_dashboard_group
-- ----------------------------

-- ----------------------------
-- Table structure for is_dashboard_permission
-- ----------------------------
DROP TABLE IF EXISTS `is_dashboard_permission`;
CREATE TABLE `is_dashboard_permission`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dashboard_id` bigint NOT NULL,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `permission_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'READ',
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'REQUEST',
  `expire_at` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_dashboard_permission_user_board_type`(`dashboard_id` ASC, `user_id` ASC, `permission_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '公共看板访问授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_dashboard_permission
-- ----------------------------

-- ----------------------------
-- Table structure for is_dashboard_team_permission
-- ----------------------------
DROP TABLE IF EXISTS `is_dashboard_team_permission`;
CREATE TABLE `is_dashboard_team_permission`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dashboard_id` bigint NOT NULL COMMENT '看板 id',
  `team_id` bigint NOT NULL COMMENT '团队 id',
  `permission_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'READ' COMMENT 'READ/EDIT',
  `granted_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '授权人 user_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_dashboard_team_perm`(`dashboard_id` ASC, `team_id` ASC, `permission_type` ASC) USING BTREE,
  INDEX `idx_dashboard_team_perm_team`(`team_id` ASC) USING BTREE,
  CONSTRAINT `fk_dashboard_team_perm_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_dashboard_team_perm_team` FOREIGN KEY (`team_id`) REFERENCES `is_team` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '看板团队授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_dashboard_team_permission
-- ----------------------------

-- ----------------------------
-- Table structure for is_data_field
-- ----------------------------
DROP TABLE IF EXISTS `is_data_field`;
CREATE TABLE `is_data_field`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_field_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `column_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `field_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `display_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `field_comment` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sensitive` tinyint(1) NOT NULL DEFAULT 0,
  `sort_order` int NOT NULL,
  `synonyms` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `kg_sync_enabled` tinyint(1) NOT NULL DEFAULT 1,
  `kg_sync_rule` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_is_data_field_table`(`table_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '上传数据字段元信息' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_data_field
-- ----------------------------

-- ----------------------------
-- Table structure for is_data_permission
-- ----------------------------
DROP TABLE IF EXISTS `is_data_permission`;
CREATE TABLE `is_data_permission`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `permission_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'READ',
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'GRANT',
  `expire_at` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_data_permission_user_table_type`(`user_id` ASC, `table_name` ASC, `permission_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '数据表访问授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_data_permission
-- ----------------------------

-- ----------------------------
-- Table structure for is_data_row_policy
-- ----------------------------
DROP TABLE IF EXISTS `is_data_row_policy`;
CREATE TABLE `is_data_row_policy`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `principal_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `principal_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `filter_expression` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_data_row_policy_table`(`table_name` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '上传表行级数据域策略' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_data_row_policy
-- ----------------------------

-- ----------------------------
-- Table structure for is_data_table
-- ----------------------------
DROP TABLE IF EXISTS `is_data_table`;
CREATE TABLE `is_data_table`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `display_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `owner_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
  `row_count` int NOT NULL DEFAULT 0,
  `field_count` int NOT NULL DEFAULT 0,
  `file_size` bigint NOT NULL DEFAULT 0,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `table_name`(`table_name` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '上传数据表元信息' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_data_table
-- ----------------------------

-- ----------------------------
-- Table structure for is_federal_relation
-- ----------------------------
DROP TABLE IF EXISTS `is_federal_relation`;
CREATE TABLE `is_federal_relation`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `left_table` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `left_field` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `right_source_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `right_table` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `right_field` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `relation_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'LEFT_JOIN',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_federal_relation_ds`(`datasource_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Excel与官方库联邦关联配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_federal_relation
-- ----------------------------

-- ----------------------------
-- Table structure for is_file_process_task
-- ----------------------------
DROP TABLE IF EXISTS `is_file_process_task`;
CREATE TABLE `is_file_process_task`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `progress` int NOT NULL DEFAULT 0,
  `message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `result_json` json NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `task_id`(`task_id` ASC) USING BTREE,
  INDEX `idx_file_process_task_id`(`task_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件上传解析进度任务' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_file_process_task
-- ----------------------------

-- ----------------------------
-- Table structure for is_kg_edge
-- ----------------------------
DROP TABLE IF EXISTS `is_kg_edge`;
CREATE TABLE `is_kg_edge`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `from_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `to_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `relation_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `weight` decimal(10, 2) NOT NULL DEFAULT 1.00,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_kg_edge`(`from_key` ASC, `to_key` ASC, `relation_type` ASC) USING BTREE,
  INDEX `idx_kg_edge_from`(`from_key` ASC) USING BTREE,
  INDEX `idx_kg_edge_to`(`to_key` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '轻量知识图谱关系' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_kg_edge
-- ----------------------------

-- ----------------------------
-- Table structure for is_kg_node
-- ----------------------------
DROP TABLE IF EXISTS `is_kg_node`;
CREATE TABLE `is_kg_node`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `node_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `node_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `label` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `source_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `weight` decimal(10, 2) NOT NULL DEFAULT 1.00,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `node_key`(`node_key` ASC) USING BTREE,
  INDEX `idx_kg_node_type`(`node_type` ASC) USING BTREE,
  INDEX `idx_kg_node_label`(`label` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '轻量知识图谱节点' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_kg_node
-- ----------------------------

-- ----------------------------
-- Table structure for is_knowledge_chunk
-- ----------------------------
DROP TABLE IF EXISTS `is_knowledge_chunk`;
CREATE TABLE `is_knowledge_chunk`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_id` bigint NOT NULL,
  `chunk_index` int NOT NULL,
  `chunk_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `keywords` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_knowledge_chunk_doc`(`doc_id` ASC) USING BTREE,
  INDEX `idx_knowledge_chunk_index`(`chunk_index` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识文档切片' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_knowledge_chunk
-- ----------------------------

-- ----------------------------
-- Table structure for is_knowledge_doc
-- ----------------------------
DROP TABLE IF EXISTS `is_knowledge_doc`;
CREATE TABLE `is_knowledge_doc`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `doc_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识文档' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_knowledge_doc
-- ----------------------------

-- ----------------------------
-- Table structure for is_neo4j_runtime_config
-- ----------------------------
DROP TABLE IF EXISTS `is_neo4j_runtime_config`;
CREATE TABLE `is_neo4j_runtime_config`  (
  `id` bigint NOT NULL,
  `uri` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'http://localhost:7474',
  `username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'neo4j',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
  `database_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'neo4j',
  `sync_rule` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Neo4j知识图谱运行配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_neo4j_runtime_config
-- ----------------------------
INSERT INTO `is_neo4j_runtime_config` VALUES (1, 'http://localhost:7474', 'neo4j', 'nisibusisa250', 'neo4j', '同步官方数据源表、字段、业务含义、同义词和联邦关系', 1, '2026-07-22 10:59:50');

-- ----------------------------
-- Table structure for is_neo4j_write_audit
-- ----------------------------
DROP TABLE IF EXISTS `is_neo4j_write_audit`;
CREATE TABLE `is_neo4j_write_audit`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `entity_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `relation_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `cypher` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `params_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_neo4j_write_audit_status`(`status` ASC) USING BTREE,
  INDEX `idx_neo4j_write_audit_created_at`(`created_at` ASC) USING BTREE,
  INDEX `idx_neo4j_write_audit_entity`(`entity_key` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Neo4j write audit' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_neo4j_write_audit
-- ----------------------------

-- ----------------------------
-- Table structure for is_official_datasource
-- ----------------------------
DROP TABLE IF EXISTS `is_official_datasource`;
CREATE TABLE `is_official_datasource`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `db_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MYSQL',
  `host` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `port` int NOT NULL,
  `database_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `jdbc_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'DISABLED',
  `last_test_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `last_test_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `last_sync_at` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `pool_max_size` int NOT NULL DEFAULT 10,
  `pool_timeout_ms` int NOT NULL DEFAULT 30000,
  `readonly_enforced` tinyint(1) NOT NULL DEFAULT 1,
  `kg_sync_rule` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '官方数据源配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_official_datasource
-- ----------------------------

-- ----------------------------
-- Table structure for is_official_datasource_permission
-- ----------------------------
DROP TABLE IF EXISTS `is_official_datasource_permission`;
CREATE TABLE `is_official_datasource_permission`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `principal_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `principal_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `permission_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'READ',
  `expire_at` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_official_ds_permission`(`datasource_id` ASC, `principal_type` ASC, `principal_id` ASC, `permission_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '官方数据源用户角色授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_official_datasource_permission
-- ----------------------------

-- ----------------------------
-- Table structure for is_official_row_policy
-- ----------------------------
DROP TABLE IF EXISTS `is_official_row_policy`;
CREATE TABLE `is_official_row_policy`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `principal_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `principal_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `filter_expression` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_official_row_policy_ds`(`datasource_id` ASC, `table_name` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '官方数据源行级隔离规则' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_official_row_policy
-- ----------------------------

-- ----------------------------
-- Table structure for is_official_schema_field
-- ----------------------------
DROP TABLE IF EXISTS `is_official_schema_field`;
CREATE TABLE `is_official_schema_field`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `column_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `data_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `column_comment` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `is_nullable` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `column_key` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `ordinal_position` int NOT NULL,
  `business_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sensitive` tinyint(1) NOT NULL DEFAULT 0,
  `business_desc` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `synonyms` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `kg_sync_enabled` tinyint(1) NOT NULL DEFAULT 1,
  `kg_sync_rule` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_official_schema_field`(`datasource_id` ASC, `table_name` ASC, `column_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '官方数据源字段结构' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_official_schema_field
-- ----------------------------

-- ----------------------------
-- Table structure for is_official_schema_relation
-- ----------------------------
DROP TABLE IF EXISTS `is_official_schema_relation`;
CREATE TABLE `is_official_schema_relation`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `column_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `referenced_table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `referenced_column_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `constraint_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_official_schema_relation`(`datasource_id` ASC, `table_name` ASC, `column_name` ASC, `referenced_table_name` ASC, `referenced_column_name` ASC) USING BTREE,
  INDEX `idx_official_schema_relation_ds`(`datasource_id` ASC, `table_name` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '官方数据源外键关系' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_official_schema_relation
-- ----------------------------

-- ----------------------------
-- Table structure for is_official_schema_table
-- ----------------------------
DROP TABLE IF EXISTS `is_official_schema_table`;
CREATE TABLE `is_official_schema_table`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `table_comment` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `table_rows` bigint NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_official_schema_table`(`datasource_id` ASC, `table_name` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '官方数据源表结构' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_official_schema_table
-- ----------------------------

-- ----------------------------
-- Table structure for is_official_table_permission
-- ----------------------------
DROP TABLE IF EXISTS `is_official_table_permission`;
CREATE TABLE `is_official_table_permission`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `principal_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `principal_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `permission_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'READ',
  `expire_at` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ADMIN',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_official_table_permission`(`datasource_id` ASC, `table_name` ASC, `principal_type` ASC, `principal_id` ASC, `permission_type` ASC) USING BTREE,
  INDEX `idx_official_table_permission_principal`(`principal_type` ASC, `principal_id` ASC, `permission_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'official table permission' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_official_table_permission
-- ----------------------------

-- ----------------------------
-- Table structure for is_perf_intervention
-- ----------------------------
DROP TABLE IF EXISTS `is_perf_intervention`;
CREATE TABLE `is_perf_intervention`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `audit_log_id` bigint NOT NULL COMMENT '关联 is_sql_audit_log.id',
  `action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '处置动作',
  `operator_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '操作人 user_id',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_perf_intervention_audit`(`audit_log_id` ASC) USING BTREE,
  INDEX `idx_perf_intervention_created`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '性能治理处置记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_perf_intervention
-- ----------------------------

-- ----------------------------
-- Table structure for is_permission_request
-- ----------------------------
DROP TABLE IF EXISTS `is_permission_request`;
CREATE TABLE `is_permission_request`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `applicant_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `resource_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'TABLE',
  `resource_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `permission_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'READ',
  `reason` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `scope_desc` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `expire_at` datetime NULL DEFAULT NULL,
  `attachment_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `attachment_content_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `attachment_size` bigint NULL DEFAULT NULL,
  `attachment_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `attachment_note` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING',
  `reviewer_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `review_comment` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_permission_request_status`(`status` ASC) USING BTREE,
  INDEX `idx_permission_request_applicant`(`applicant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限申请记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_permission_request
-- ----------------------------

-- ----------------------------
-- Table structure for is_role
-- ----------------------------
DROP TABLE IF EXISTS `is_role`;
CREATE TABLE `is_role`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `role_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `parent_role_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `role_level` int NOT NULL DEFAULT 1,
  `data_scope` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SELF',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `role_code`(`role_code` ASC) USING BTREE,
  INDEX `idx_is_role_parent`(`parent_role_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RBAC role definition' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_role
-- ----------------------------
INSERT INTO `is_role` VALUES (1, 'USER', '普通用户', NULL, 1, 'SELF', '本人上传数据、已授权官方数据与公共看板申请能力', 1, '2026-07-22 10:59:23', '2026-07-22 10:59:23');
INSERT INTO `is_role` VALUES (2, 'ADMIN', '管理员', 'USER', 3, 'ALL', '继承普通用户权限，并拥有全局配置、审批和治理权限', 1, '2026-07-22 10:59:23', '2026-07-22 10:59:23');
INSERT INTO `is_role` VALUES (3, 'SUPER_ADMIN', '超级管理员', 'ADMIN', 4, 'ALL', '拥有管理员端全部菜单与操作权限，不受 RBAC 勾选限制', 1, '2026-07-22 10:59:23', '2026-07-22 10:59:23');

-- ----------------------------
-- Table structure for is_role_permission
-- ----------------------------
DROP TABLE IF EXISTS `is_role_permission`;
CREATE TABLE `is_role_permission`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `permission_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `permission_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `permission_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `resource_scope` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_is_role_permission`(`role_code` ASC, `permission_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 29 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RBAC role permission binding' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_role_permission
-- ----------------------------
INSERT INTO `is_role_permission` VALUES (1, 'USER', 'menu:user-workbench', '用户工作台', 'MENU', 'USER', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (2, 'USER', 'menu:chat-analysis', '对话分析', 'MENU', 'USER', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (3, 'USER', 'menu:data-upload', '数据上传', 'MENU', 'USER', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (4, 'USER', 'menu:dashboard', '我的看板', 'MENU', 'USER', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (5, 'USER', 'menu:diagnosis', '智能诊断', 'MENU', 'USER', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (6, 'USER', 'menu:permission-center', '数据权限中心', 'MENU', 'USER', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (7, 'USER', 'data:self-upload', '本人上传数据', 'DATA', 'SELF', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (8, 'USER', 'data:granted-official-table', '已授权官方库表', 'DATA', 'GRANTED_TABLE', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (9, 'ADMIN', 'menu:permission-approval', '权限审批', 'MENU', 'ADMIN', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (10, 'ADMIN', 'menu:datasource-admin', '数据源管理', 'MENU', 'ADMIN', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (11, 'ADMIN', 'menu:sql-audit', 'SQL 审计', 'MENU', 'ADMIN', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (12, 'ADMIN', 'data:all', '全量数据', 'DATA', 'ALL', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (13, 'ADMIN', 'operation:rbac-manage', '用户与角色权限管理', 'OPERATION', 'ADMIN', '2026-07-22 10:59:23');
INSERT INTO `is_role_permission` VALUES (14, 'SUPER_ADMIN', 'operation:super-admin', '超级管理员', 'OPERATION', 'ALL', '2026-07-22 10:59:24');

-- ----------------------------
-- Table structure for is_schema_migration
-- ----------------------------
DROP TABLE IF EXISTS `is_schema_migration`;
CREATE TABLE `is_schema_migration`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `script_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `applied_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_schema_migration_script`(`script_name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 24 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '启动期 SQL migration 执行记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_schema_migration
-- ----------------------------
INSERT INTO `is_schema_migration` VALUES (1, 'db/migration/chat_conversation_migration_20260524.sql', '2026-07-22 11:00:00');
INSERT INTO `is_schema_migration` VALUES (2, 'db/migration/dashboard_component_artifact_turn_migration_20260524.sql', '2026-07-22 11:00:00');
INSERT INTO `is_schema_migration` VALUES (3, 'db/migration/admin_chat_history_migration_20260527.sql', '2026-07-22 11:00:01');
INSERT INTO `is_schema_migration` VALUES (4, 'db/migration/admin_chat_query_lab_migration_20260527.sql', '2026-07-22 11:00:04');
INSERT INTO `is_schema_migration` VALUES (5, 'db/migration/admin_chat_query_template_migration_20260528.sql', '2026-07-22 11:00:04');
INSERT INTO `is_schema_migration` VALUES (6, 'db/migration/advanced_alert_rule_migration_20260529.sql', '2026-07-22 11:00:05');
INSERT INTO `is_schema_migration` VALUES (7, 'db/migration/advanced_alert_rule_schedule_migration_20260601.sql', '2026-07-22 11:00:09');
INSERT INTO `is_schema_migration` VALUES (8, 'db/migration/advanced_alert_rule_name_migration_20260603.sql', '2026-07-22 11:00:10');
INSERT INTO `is_schema_migration` VALUES (9, 'db/migration/advanced_alert_event_migration_20260529.sql', '2026-07-22 11:00:11');
INSERT INTO `is_schema_migration` VALUES (10, 'db/migration/advanced_alert_event_lifecycle_migration_20260601.sql', '2026-07-22 11:00:19');
INSERT INTO `is_schema_migration` VALUES (11, 'db/migration/advanced_alert_event_explanation_migration_20260602.sql', '2026-07-22 11:00:23');
INSERT INTO `is_schema_migration` VALUES (12, 'db/migration/advanced_alert_push_log_migration_20260601.sql', '2026-07-22 11:00:24');
INSERT INTO `is_schema_migration` VALUES (13, 'db/migration/advanced_alert_org_scope_migration_20260602.sql', '2026-07-22 11:00:30');
INSERT INTO `is_schema_migration` VALUES (14, 'db/migration/advanced_analysis_plan_migration_20260529.sql', '2026-07-22 11:00:31');
INSERT INTO `is_schema_migration` VALUES (15, 'db/migration/advanced_analysis_plan_version_migration_20260601.sql', '2026-07-22 11:00:32');
INSERT INTO `is_schema_migration` VALUES (16, 'db/migration/advanced_analysis_field_mapping_migration_20260602.sql', '2026-07-22 11:00:35');
INSERT INTO `is_schema_migration` VALUES (17, 'db/migration/ai_chart_rule_config_migration_20260603.sql', '2026-07-22 11:00:38');
INSERT INTO `is_schema_migration` VALUES (18, 'db/migration/ai_chart_rule_version_migration_20260604.sql', '2026-07-22 11:00:39');
INSERT INTO `is_schema_migration` VALUES (19, 'db/migration/dashboard_view_count_migration_20260606.sql', '2026-07-22 11:00:39');
INSERT INTO `is_schema_migration` VALUES (20, 'db/migration/dashboard_publisher_migration_20260606.sql', '2026-07-22 11:00:39');
INSERT INTO `is_schema_migration` VALUES (21, 'db/migration/dashboard_author_migration_20260606.sql', '2026-07-22 11:00:40');
INSERT INTO `is_schema_migration` VALUES (22, 'db/migration/dashboard_save_as_migration_20260606.sql', '2026-07-22 11:00:40');
INSERT INTO `is_schema_migration` VALUES (23, 'db/migration/is_data_field_semantic_metadata_migration_20260608.sql', '2026-07-22 11:00:40');

-- ----------------------------
-- Table structure for is_semantic_cache_audit
-- ----------------------------
DROP TABLE IF EXISTS `is_semantic_cache_audit`;
CREATE TABLE `is_semantic_cache_audit`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cache_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `question` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `cached_sql` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `hit_count` bigint NOT NULL DEFAULT 0,
  `risk_level` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SAFE',
  `risk_reason` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `redis_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'LOCAL',
  `quarantine_reason` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `quarantined_at` datetime NULL DEFAULT NULL,
  `last_hit_at` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_semantic_cache_key`(`cache_key` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Redis语义缓存审计' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_semantic_cache_audit
-- ----------------------------

-- ----------------------------
-- Table structure for is_sensitive_field_rule
-- ----------------------------
DROP TABLE IF EXISTS `is_sensitive_field_rule`;
CREATE TABLE `is_sensitive_field_rule`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `field_keyword` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `mask_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MIDDLE',
  `access_action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MASK',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `field_keyword`(`field_keyword` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '敏感字段识别与脱敏规则' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_sensitive_field_rule
-- ----------------------------
INSERT INTO `is_sensitive_field_rule` VALUES (1, 'phone', 'MOBILE', 'MASK', 1, '2026-07-22 10:59:41');
INSERT INTO `is_sensitive_field_rule` VALUES (2, 'mobile', 'MOBILE', 'MASK', 1, '2026-07-22 10:59:41');
INSERT INTO `is_sensitive_field_rule` VALUES (3, 'idcard', 'ID_CARD', 'MASK', 1, '2026-07-22 10:59:41');
INSERT INTO `is_sensitive_field_rule` VALUES (4, '手机号', 'MOBILE', 'MASK', 1, '2026-07-22 10:59:41');
INSERT INTO `is_sensitive_field_rule` VALUES (5, '身份证', 'ID_CARD', 'MASK', 1, '2026-07-22 10:59:41');
INSERT INTO `is_sensitive_field_rule` VALUES (6, 'amount', 'MIDDLE', 'MASK', 1, '2026-07-22 10:59:41');
INSERT INTO `is_sensitive_field_rule` VALUES (7, '金额', 'MIDDLE', 'MASK', 1, '2026-07-22 10:59:41');

-- ----------------------------
-- Table structure for is_sql_audit_log
-- ----------------------------
DROP TABLE IF EXISTS `is_sql_audit_log`;
CREATE TABLE `is_sql_audit_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
  `question` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `table_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `engine` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `generated_sql` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `risk_level` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `risk_reason` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `matched_rules` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sensitive_fields` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `slow_query` tinyint(1) NOT NULL DEFAULT 0,
  `execute_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `duration_ms` bigint NULL DEFAULT NULL,
  `error_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `generation_trace` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `kg_match_log` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `cache_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `cache_hit` tinyint(1) NOT NULL DEFAULT 0,
  `cache_sql` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `cache_audit_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `redis_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'LOCAL',
  `mask_detail` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `execution_guard` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `query_guard_action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `review_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'OPEN',
  `review_note` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `reviewed_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `reviewed_at` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_sql_audit_created_at`(`created_at` ASC) USING BTREE,
  INDEX `idx_sql_audit_risk_level`(`risk_level` ASC) USING BTREE,
  INDEX `idx_sql_audit_status`(`execute_status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'SQL安全审计日志' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_sql_audit_log
-- ----------------------------

-- ----------------------------
-- Table structure for is_sql_audit_rule
-- ----------------------------
DROP TABLE IF EXISTS `is_sql_audit_rule`;
CREATE TABLE `is_sql_audit_rule`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `rule_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `risk_level` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `rule_desc` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `threshold_value` bigint NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `rule_code`(`rule_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 29 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'SQL审计规则配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_sql_audit_rule
-- ----------------------------
INSERT INTO `is_sql_audit_rule` VALUES (1, 'ONLY_SELECT', '只允许 SELECT', 'BLOCKED', 1, '禁止非查询语句进入 BI 分析链路', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (2, 'DANGEROUS_KEYWORD', '危险关键字拦截', 'BLOCKED', 1, '拦截 DROP/DELETE/UPDATE/INSERT/ALTER 等破坏性关键字', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (3, 'MULTI_STATEMENT', '多语句拦截', 'BLOCKED', 1, '拦截分号拼接的多语句 SQL', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (4, 'TABLE_SCOPE', '授权表范围校验', 'BLOCKED', 1, '校验 SQL 是否仅访问当前授权数据表', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (5, 'LIMIT_REQUIRED', '结果集 LIMIT 检查', 'WARN', 1, '缺少 LIMIT 时标记为大结果集风险', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (6, 'NO_SELECT_STAR', '禁止 SELECT *', 'WARN', 1, '使用 SELECT * 时提示限制字段范围', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (7, 'SENSITIVE_FIELD', '敏感字段访问识别', 'WARN', 1, '识别 SQL 是否访问敏感字段', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (8, 'SENSITIVE_FIELD_BLOCK', '敏感字段强制拦截', 'BLOCKED', 1, '字段规则 accessAction=BLOCK 时直接拦截敏感字段访问', NULL, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (9, 'SLOW_QUERY', '慢查询识别', 'WARN', 1, '执行耗时超过阈值时标记为慢查询', 3000, '2026-07-22 10:59:40', '2026-07-22 10:59:40');
INSERT INTO `is_sql_audit_rule` VALUES (10, 'SLOW_QUERY_BREAKER', '慢查询熔断阈值', 'BLOCKED', 1, '执行耗时超过阈值时记录熔断风险，提示管理员优化 SQL', 8000, '2026-07-22 10:59:41', '2026-07-22 10:59:41');
INSERT INTO `is_sql_audit_rule` VALUES (11, 'QUERY_TIMEOUT_MS', '查询超时熔断', 'BLOCKED', 1, '执行前设置查询超时，超时由数据库驱动中断', 5000, '2026-07-22 10:59:41', '2026-07-22 10:59:41');
INSERT INTO `is_sql_audit_rule` VALUES (12, 'MAX_SCAN_ROWS', '最大扫描行数', 'BLOCKED', 1, '执行前扫描行数阈值，接入 EXPLAIN 后用于直接拦截', 50000, '2026-07-22 10:59:41', '2026-07-22 10:59:41');
INSERT INTO `is_sql_audit_rule` VALUES (13, 'QUERY_QUEUE_TIMEOUT_MS', '查询队列等待超时', 'BLOCKED', 1, '并发查询队列等待超过阈值时直接熔断', 2000, '2026-07-22 10:59:41', '2026-07-22 10:59:41');
INSERT INTO `is_sql_audit_rule` VALUES (14, 'SYSTEM_TABLE_BLOCK', '系统表访问拦截', 'BLOCKED', 1, '禁止普通查询访问系统库、元数据库、审计底表和用户底表', NULL, '2026-07-22 10:59:41', '2026-07-22 10:59:41');

-- ----------------------------
-- Table structure for is_system_announcement
-- ----------------------------
DROP TABLE IF EXISTS `is_system_announcement`;
CREATE TABLE `is_system_announcement`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '公告标题',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '公告正文',
  `audience` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ALL' COMMENT '受众：ALL / USER / ADMIN',
  `pinned` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否置顶',
  `priority` int NOT NULL DEFAULT 0 COMMENT '排序优先级',
  `publish_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PUBLISHED' COMMENT '发布状态',
  `published_at` datetime NULL DEFAULT NULL COMMENT '发布时间',
  `expire_at` datetime NULL DEFAULT NULL COMMENT '过期时间',
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '发布人 user_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_announcement_audience_published`(`audience` ASC, `publish_status` ASC, `published_at` ASC) USING BTREE,
  INDEX `idx_announcement_expire`(`expire_at` ASC) USING BTREE,
  INDEX `fk_announcement_creator`(`created_by` ASC) USING BTREE,
  CONSTRAINT `fk_announcement_creator` FOREIGN KEY (`created_by`) REFERENCES `is_user` (`user_id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统公告' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_system_announcement
-- ----------------------------

-- ----------------------------
-- Table structure for is_system_config
-- ----------------------------
DROP TABLE IF EXISTS `is_system_config`;
CREATE TABLE `is_system_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '配置键',
  `config_value` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '配置值',
  `value_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'STRING' COMMENT '值类型',
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '配置分组',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '配置说明',
  `updated_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最后修改人 user_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_system_config_key`(`config_key` ASC) USING BTREE,
  INDEX `idx_system_config_category`(`category` ASC) USING BTREE,
  INDEX `fk_system_config_updater`(`updated_by` ASC) USING BTREE,
  CONSTRAINT `fk_system_config_updater` FOREIGN KEY (`updated_by`) REFERENCES `is_user` (`user_id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_system_config
-- ----------------------------

-- ----------------------------
-- Table structure for is_team
-- ----------------------------
DROP TABLE IF EXISTS `is_team`;
CREATE TABLE `is_team`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '团队名称',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '团队说明',
  `owner_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '创建者 user_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_team_owner`(`owner_user_id` ASC) USING BTREE,
  CONSTRAINT `fk_team_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `is_user` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '协作团队' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_team
-- ----------------------------

-- ----------------------------
-- Table structure for is_team_member
-- ----------------------------
DROP TABLE IF EXISTS `is_team_member`;
CREATE TABLE `is_team_member`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_id` bigint NOT NULL COMMENT '团队 id',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '成员 user_id',
  `member_role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER/ADMIN/MEMBER',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_team_member`(`team_id` ASC, `user_id` ASC) USING BTREE,
  INDEX `idx_team_member_user`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_team_member_team` FOREIGN KEY (`team_id`) REFERENCES `is_team` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_team_member_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '团队成员' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_team_member
-- ----------------------------

-- ----------------------------
-- Table structure for is_user
-- ----------------------------
DROP TABLE IF EXISTS `is_user`;
CREATE TABLE `is_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `nickname` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `phone` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `email` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `password_hash` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password_salt` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password_algorithm` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PBKDF2WithHmacSHA256',
  `role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'USER',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `last_login_at` datetime NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `user_id`(`user_id` ASC) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE,
  UNIQUE INDEX `phone`(`phone` ASC) USING BTREE,
  UNIQUE INDEX `email`(`email` ASC) USING BTREE,
  INDEX `idx_is_user_role`(`role` ASC) USING BTREE,
  INDEX `idx_is_user_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RBAC用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_user
-- ----------------------------
INSERT INTO `is_user` VALUES (1, 'demo-user', 'demo-user', '普通用户', NULL, 'user@example.com', 'k+n+KVXn21emREPyCMHB4OOW7Oj3Tb1Kq2hafl/mA2E=', 'Hnq//XBFZZDBw8FvVWMUfA==', 'PBKDF2WithHmacSHA256', 'USER', 'ACTIVE', NULL, '2026-07-22 11:01:01', '2026-07-22 11:01:01');
INSERT INTO `is_user` VALUES (2, 'admin', 'admin', '超级管理员', NULL, 'admin@example.com', 'kX248vZR6cgdPfQ7dSma70DX867mwfkFDQJExROUh7s=', 'YwhTbHuo14NxEXbg59wDJQ==', 'PBKDF2WithHmacSHA256', 'SUPER_ADMIN', 'ACTIVE', '2026-07-22 11:03:04', '2026-07-22 11:01:01', '2026-07-22 11:03:04');

-- ----------------------------
-- Table structure for is_user_role
-- ----------------------------
DROP TABLE IF EXISTS `is_user_role`;
CREATE TABLE `is_user_role`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `role_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'SYSTEM',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_is_user_role`(`user_id` ASC, `role_code` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RBAC user role binding' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_user_role
-- ----------------------------
INSERT INTO `is_user_role` VALUES (1, 'admin', 'SUPER_ADMIN', 'LEGACY_ROLE_COLUMN', '2026-07-22 11:02:14');
INSERT INTO `is_user_role` VALUES (2, 'demo-user', 'USER', 'LEGACY_ROLE_COLUMN', '2026-07-22 11:02:14');

-- ----------------------------
-- Table structure for is_voice_preference
-- ----------------------------
DROP TABLE IF EXISTS `is_voice_preference`;
CREATE TABLE `is_voice_preference`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `recognition_locale` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'zh-CN',
  `voice_locale` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'zh-CN',
  `voice_gender` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'female',
  `speech_rate` decimal(4, 2) NOT NULL DEFAULT 1.00,
  `speech_volume` decimal(4, 2) NOT NULL DEFAULT 0.85,
  `auto_speak_conclusion` tinyint(1) NOT NULL DEFAULT 0,
  `auto_send_after_recognize` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_voice_preference_user`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户语音偏好' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of is_voice_preference
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
