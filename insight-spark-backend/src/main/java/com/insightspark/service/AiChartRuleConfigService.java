package com.insightspark.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@DependsOn("sqlMigrationRunner")
public class AiChartRuleConfigService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `ai_chart_rule` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `rule_code` VARCHAR(80) NOT NULL UNIQUE,
                  `rule_name` VARCHAR(128) NOT NULL,
                  `scenario_type` VARCHAR(32) NOT NULL,
                  `chart_type` VARCHAR(32) NOT NULL,
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `priority` INT NOT NULL DEFAULT 100,
                  `match_config` TEXT NULL,
                  `render_config` TEXT NULL,
                  `explain_template` TEXT NULL,
                  `created_by` VARCHAR(64) NULL,
                  `updated_by` VARCHAR(64) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_ai_chart_rule_scenario` (`scenario_type`),
                  INDEX `idx_ai_chart_rule_enabled` (`enabled`),
                  INDEX `idx_ai_chart_rule_priority` (`priority`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart recommendation rules';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `ai_chart_style_preference` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `preference_code` VARCHAR(80) NOT NULL UNIQUE,
                  `theme_name` VARCHAR(128) NOT NULL,
                  `color_palette` TEXT NULL,
                  `font_config` TEXT NULL,
                  `layout_config` TEXT NULL,
                  `default_options` TEXT NULL,
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart style preferences';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `ai_chart_rule_audit_log` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `rule_id` BIGINT NULL,
                  `action` VARCHAR(32) NOT NULL,
                  `before_snapshot` TEXT NULL,
                  `after_snapshot` TEXT NULL,
                  `operator` VARCHAR(64) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_ai_chart_audit_rule` (`rule_id`),
                  INDEX `idx_ai_chart_audit_action` (`action`),
                  INDEX `idx_ai_chart_audit_created` (`created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart rule audit log';
                """);
        seedRules();
        seedPreference();
    }

    public List<Map<String, Object>> listRules(String scenarioType, Boolean enabled, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, rule_code AS ruleCode, rule_name AS ruleName, scenario_type AS scenarioType,
                       chart_type AS chartType, enabled, priority, match_config AS matchConfig,
                       render_config AS renderConfig, explain_template AS explainTemplate,
                       created_by AS createdBy, updated_by AS updatedBy, created_at AS createdAt, updated_at AS updatedAt
                FROM ai_chart_rule
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (scenarioType != null && !scenarioType.isBlank()) {
            sql.append(" AND scenario_type = ?");
            args.add(scenarioType.trim());
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            args.add(enabled ? 1 : 0);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (rule_code LIKE ? OR rule_name LIKE ? OR explain_template LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY priority DESC, id ASC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray()).stream()
                .map(this::decodeRule)
                .toList();
    }

    public Map<String, Object> getRule(Long id) {
        return decodeRule(jdbcTemplate.queryForMap("""
                SELECT id, rule_code AS ruleCode, rule_name AS ruleName, scenario_type AS scenarioType,
                       chart_type AS chartType, enabled, priority, match_config AS matchConfig,
                       render_config AS renderConfig, explain_template AS explainTemplate,
                       created_by AS createdBy, updated_by AS updatedBy, created_at AS createdAt, updated_at AS updatedAt
                FROM ai_chart_rule WHERE id = ?
                """, id));
    }

    public Map<String, Object> createRule(Map<String, Object> body) {
        String ruleCode = requireText(body, "ruleCode");
        String ruleName = requireText(body, "ruleName");
        String scenarioType = requireText(body, "scenarioType");
        String chartType = requireText(body, "chartType");
        int priority = intValue(body.get("priority"), 100);
        boolean enabled = boolValue(body.get("enabled"), true);
        String matchConfig = jsonValue(body.get("matchConfig"));
        String renderConfig = jsonValue(body.get("renderConfig"));
        String explainTemplate = textValue(body.get("explainTemplate"));
        String uid = operatorUser();
        validateConfig(matchConfig, renderConfig);

        jdbcTemplate.update("""
                INSERT INTO ai_chart_rule(rule_code, rule_name, scenario_type, chart_type, enabled, priority,
                                          match_config, render_config, explain_template, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ruleCode, ruleName, scenarioType, chartType, enabled ? 1 : 0, priority,
                matchConfig, renderConfig, explainTemplate, uid, uid);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> saved = getRule(id);
        writeAudit(id, "CREATE", null, saved);
        return saved;
    }

    public Map<String, Object> updateRule(Long id, Map<String, Object> body) {
        Map<String, Object> before = getRule(id);
        String ruleCode = requireText(body, "ruleCode");
        String ruleName = requireText(body, "ruleName");
        String scenarioType = requireText(body, "scenarioType");
        String chartType = requireText(body, "chartType");
        int priority = intValue(body.get("priority"), 100);
        boolean enabled = boolValue(body.get("enabled"), true);
        String matchConfig = jsonValue(body.get("matchConfig"));
        String renderConfig = jsonValue(body.get("renderConfig"));
        String explainTemplate = textValue(body.get("explainTemplate"));
        validateConfig(matchConfig, renderConfig);

        jdbcTemplate.update("""
                UPDATE ai_chart_rule
                SET rule_code = ?, rule_name = ?, scenario_type = ?, chart_type = ?, enabled = ?, priority = ?,
                    match_config = ?, render_config = ?, explain_template = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, ruleCode, ruleName, scenarioType, chartType, enabled ? 1 : 0, priority,
                matchConfig, renderConfig, explainTemplate, operatorUser(), id);
        Map<String, Object> saved = getRule(id);
        writeAudit(id, "UPDATE", before, saved);
        return saved;
    }

    public void updateEnabled(Long id, boolean enabled) {
        Map<String, Object> before = getRule(id);
        jdbcTemplate.update("""
                UPDATE ai_chart_rule
                SET enabled = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, enabled ? 1 : 0, operatorUser(), id);
        writeAudit(id, enabled ? "ENABLE" : "DISABLE", before, getRule(id));
    }

    public void deleteRule(Long id) {
        Map<String, Object> before = getRule(id);
        jdbcTemplate.update("DELETE FROM ai_chart_rule WHERE id = ?", id);
        writeAudit(id, "DELETE", before, null);
    }

    public Map<String, Object> getPreferences() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT id, preference_code AS preferenceCode, theme_name AS themeName, color_palette AS colorPalette,
                       font_config AS fontConfig, layout_config AS layoutConfig, default_options AS defaultOptions,
                       enabled, updated_at AS updatedAt
                FROM ai_chart_style_preference
                WHERE preference_code = 'enterprise_default'
                """);
        row.put("colorPalette", readJson(Objects.toString(row.get("colorPalette"), "[]"), List.of()));
        row.put("fontConfig", readJson(Objects.toString(row.get("fontConfig"), "{}"), Map.of()));
        row.put("layoutConfig", readJson(Objects.toString(row.get("layoutConfig"), "{}"), Map.of()));
        row.put("defaultOptions", readJson(Objects.toString(row.get("defaultOptions"), "{}"), Map.of()));
        return row;
    }

    public Map<String, Object> savePreferences(Map<String, Object> body) {
        String themeName = Objects.toString(body.getOrDefault("themeName", "Enterprise Default")).trim();
        String colorPalette = jsonValue(body.getOrDefault("colorPalette", List.of("#2563eb", "#16a34a", "#f59e0b", "#dc2626")));
        String fontConfig = jsonValue(body.getOrDefault("fontConfig", Map.of("fontFamily", "Microsoft YaHei", "fontSize", 12)));
        String layoutConfig = jsonValue(body.getOrDefault("layoutConfig", Map.of("legend", "top", "gridContainLabel", true)));
        String defaultOptions = jsonValue(body.getOrDefault("defaultOptions", Map.of()));
        validateConfig(colorPalette, fontConfig, layoutConfig, defaultOptions);
        jdbcTemplate.update("""
                INSERT INTO ai_chart_style_preference(preference_code, theme_name, color_palette, font_config,
                                                      layout_config, default_options, enabled)
                VALUES ('enterprise_default', ?, ?, ?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE theme_name = VALUES(theme_name),
                                        color_palette = VALUES(color_palette),
                                        font_config = VALUES(font_config),
                                        layout_config = VALUES(layout_config),
                                        default_options = VALUES(default_options),
                                        enabled = 1,
                                        updated_at = CURRENT_TIMESTAMP
                """, themeName, colorPalette, fontConfig, layoutConfig, defaultOptions);
        writeAudit(null, "PREFERENCE_UPDATE", null, body);
        return getPreferences();
    }

    public Map<String, Object> renderConfigSchema() {
        return Map.of(
                "animation", List.of(true, false),
                "legendPositions", List.of("top", "bottom", "left", "right"),
                "dynamicRefreshIntervals", List.of(0, 5, 10, 30, 60),
                "voiceSummaryFields", List.of("title", "metric", "trend", "max", "min", "anomaly"),
                "chartTypes", List.of("line", "bar", "pie", "doughnut", "table")
        );
    }

    public List<Map<String, Object>> auditLogs(String action, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, rule_id AS ruleId, action, before_snapshot AS beforeSnapshot, after_snapshot AS afterSnapshot,
                       operator, created_at AS createdAt
                FROM ai_chart_rule_audit_log WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(action.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit == null ? 50 : limit, 200)));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> testRecommendation(Map<String, Object> body) {
        List<Map<String, Object>> fields = objectList(body.get("fields"));
        List<Map<String, Object>> rows = objectList(body.get("rows"));
        String intent = Objects.toString(body.getOrDefault("intent", ""), "");
        Map<String, Object> profile = profile(fields, rows, intent);
        Map<String, Object> rule = chooseRule(profile, intent);
        Map<String, Object> option = buildOption(rule, fields, rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile);
        result.put("matchedRule", rule);
        result.put("chartType", rule.get("chartType"));
        result.put("option", option);
        result.put("explain", explain(rule, profile));
        writeAudit(longOrNull(rule.get("id")), "TEST", null, result);
        return result;
    }

    public Map<String, Object> recommendForChatResult(String intent, List<Map<String, Object>> fields,
                                                       List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalizedFields = normalizeFieldsForProfile(fields, rows);
        Map<String, Object> profile = profile(normalizedFields, rows == null ? List.of() : rows, intent);
        Map<String, Object> rule = chooseRule(profile, intent);
        Map<String, Object> renderConfig = mapValue(rule.get("renderConfig"));
        Map<String, Object> preference = getPreferences();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chartType", rule.get("chartType"));
        result.put("ruleCode", rule.get("ruleCode"));
        result.put("ruleName", rule.get("ruleName"));
        result.put("scenarioType", rule.get("scenarioType"));
        result.put("explain", explain(rule, profile));
        result.put("profile", profile);
        result.put("optionTemplate", buildOptionTemplate(Objects.toString(rule.get("chartType"), "bar"),
                renderConfig, preference));
        result.put("voiceSummary", buildVoiceSummary(rule, profile, renderConfig));
        return result;
    }

    private Map<String, Object> chooseRule(Map<String, Object> profile, String intent) {
        List<Map<String, Object>> rules = listRules(null, true, null);
        String detected = Objects.toString(profile.get("detectedScenario"), "DETAIL");
        String normalizedIntent = intent.toLowerCase(Locale.ROOT);

        return rules.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> r) -> intValue(r.get("priority"), 0)).reversed())
                .filter(rule -> matchesRule(rule, detected, normalizedIntent, profile))
                .findFirst()
                .orElseGet(() -> fallbackRule(detected));
    }

    private boolean matchesRule(Map<String, Object> rule, String detected, String intent, Map<String, Object> profile) {
        String scenario = Objects.toString(rule.get("scenarioType"), "");
        if ("CUSTOM".equalsIgnoreCase(scenario)) {
            Map<String, Object> match = mapValue(rule.get("matchConfig"));
            String keyword = Objects.toString(match.getOrDefault("keyword", ""), "").toLowerCase(Locale.ROOT);
            return keyword.isBlank() || intent.contains(keyword);
        }
        if (!scenario.equalsIgnoreCase(detected)) {
            return false;
        }
        if ("TIME_SERIES".equalsIgnoreCase(scenario) && intValue(profile.get("timeFieldCount"), 0) <= 0) {
            return false;
        }
        if ("GROUP_COMPARE".equalsIgnoreCase(scenario) && intValue(profile.get("dimensionFieldCount"), 0) <= 0) {
            return false;
        }
        if (!"DETAIL".equalsIgnoreCase(scenario) && intValue(profile.get("numericFieldCount"), 0) <= 0) {
            return false;
        }
        return true;
    }

    private Map<String, Object> profile(List<Map<String, Object>> fields, List<Map<String, Object>> rows, String intent) {
        int timeFields = 0;
        int numericFields = 0;
        int dimensionFields = 0;
        for (Map<String, Object> field : fields) {
            String name = Objects.toString(field.getOrDefault("name", field.getOrDefault("columnName", "")), "");
            String type = Objects.toString(field.getOrDefault("type", ""), "").toLowerCase(Locale.ROOT);
            String lower = name.toLowerCase(Locale.ROOT);
            boolean isTime = type.contains("date") || type.contains("time") || lower.contains("date")
                    || lower.contains("time") || name.contains("日期") || name.contains("时间") || name.contains("月份");
            boolean isNumber = type.contains("int") || type.contains("decimal") || type.contains("double")
                    || type.contains("number") || type.contains("numeric") || type.contains("float")
                    || name.contains("金额") || name.contains("销售额") || name.contains("数量") || name.contains("占比");
            if (isTime) {
                timeFields++;
            } else if (isNumber) {
                numericFields++;
            } else {
                dimensionFields++;
            }
        }
        String detected = detectScenario(intent, timeFields, numericFields, dimensionFields, fields.size(), rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", rows.size());
        result.put("fieldCount", fields.size());
        result.put("timeFieldCount", timeFields);
        result.put("numericFieldCount", numericFields);
        result.put("dimensionFieldCount", dimensionFields);
        result.put("detectedScenario", detected);
        return result;
    }

    private String detectScenario(String intent, int timeFields, int numericFields, int dimensionFields, int fieldCount, int rowCount) {
        String lower = Objects.toString(intent, "").toLowerCase(Locale.ROOT);
        if (lower.contains("trend") || lower.contains("forecast") || intent.contains("趋势") || intent.contains("预测")) {
            return "TIME_SERIES";
        }
        if (lower.contains("ratio") || lower.contains("share") || intent.contains("占比") || intent.contains("比例")) {
            return "RATIO";
        }
        if (lower.contains("compare") || intent.contains("对比") || intent.contains("同比") || intent.contains("环比")) {
            return "GROUP_COMPARE";
        }
        if (timeFields > 0 && numericFields > 0) {
            return "TIME_SERIES";
        }
        if (dimensionFields > 0 && numericFields > 0 && rowCount <= 20) {
            return "GROUP_COMPARE";
        }
        if (fieldCount >= 5 || rowCount > 50) {
            return "DETAIL";
        }
        return numericFields > 0 ? "GROUP_COMPARE" : "DETAIL";
    }

    private Map<String, Object> buildOption(Map<String, Object> rule, List<Map<String, Object>> fields, List<Map<String, Object>> rows) {
        String chartType = Objects.toString(rule.get("chartType"), "table");
        List<String> names = fields.stream()
                .map(field -> Objects.toString(field.getOrDefault("name", field.getOrDefault("columnName", "")), ""))
                .filter(name -> !name.isBlank())
                .toList();
        if ("table".equalsIgnoreCase(chartType)) {
            return new LinkedHashMap<>(Map.of("type", "table", "columns", names, "rows", rows));
        }
        String category = names.isEmpty() ? "category" : names.get(0);
        String metric = names.size() > 1 ? names.get(1) : category;
        List<Object> x = rows.stream().map(row -> row.getOrDefault(category, row.getOrDefault("category", ""))).toList();
        List<Object> y = rows.stream().map(row -> row.getOrDefault(metric, row.getOrDefault("value", 0))).toList();
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", Objects.toString(rule.get("ruleName"), "AI chart recommendation")));
        option.put("tooltip", Map.of("trigger", "pie".equals(chartType) || "doughnut".equals(chartType) ? "item" : "axis"));
        option.put("legend", Map.of("top", "top"));
        option.put("color", getPreferences().get("colorPalette"));
        if ("pie".equals(chartType) || "doughnut".equals(chartType)) {
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                data.add(Map.of("name", x.get(i), "value", y.get(i)));
            }
            option.put("series", List.of(Map.of(
                    "type", "pie",
                    "radius", "doughnut".equals(chartType) ? List.of("45%", "70%") : "65%",
                    "label", Map.of("formatter", "{b}: {d}%"),
                    "data", data
            )));
        } else {
            option.put("xAxis", Map.of("type", "category", "data", x));
            option.put("yAxis", Map.of("type", "value"));
            option.put("series", List.of(Map.of("name", metric, "type", chartType, "smooth", "line".equals(chartType), "data", y)));
        }
        return option;
    }

    private Map<String, Object> buildOptionTemplate(String chartType, Map<String, Object> renderConfig,
                                                    Map<String, Object> preference) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("animation", renderConfig.getOrDefault("animation",
                mapValue(preference.get("defaultOptions")).getOrDefault("animation", true)));
        option.put("color", preference.getOrDefault("colorPalette", List.of()));
        Map<String, Object> layout = mapValue(preference.get("layoutConfig"));
        String legendPosition = Objects.toString(layout.getOrDefault("legend", "top"), "top");
        option.put("legend", Map.of("top".equals(legendPosition) || "bottom".equals(legendPosition) ? legendPosition : "top", 2));
        if ("line".equalsIgnoreCase(chartType) || "bar".equalsIgnoreCase(chartType)) {
            option.put("tooltip", Map.of("trigger", "axis", "confine", true));
            option.put("grid", Map.of("left", 48, "right", 16, "top", 36, "bottom", 56, "containLabel", true));
            Map<String, Object> series0 = new LinkedHashMap<>();
            if ("line".equalsIgnoreCase(chartType)) {
                series0.put("smooth", boolValue(renderConfig.get("smooth"), true));
                series0.put("showSymbol", boolValue(renderConfig.get("showSymbol"), false));
            } else {
                series0.put("barMaxWidth", intValue(renderConfig.get("barMaxWidth"), 32));
                series0.put("itemStyle", Map.of("borderRadius", List.of(4, 4, 0, 0)));
            }
            option.put("series", List.of(series0));
        } else if ("pie".equalsIgnoreCase(chartType) || "doughnut".equalsIgnoreCase(chartType)) {
            option.put("tooltip", Map.of("trigger", "item", "confine", true));
            Map<String, Object> label = mapValue(renderConfig.get("label"));
            Map<String, Object> series0 = new LinkedHashMap<>();
            series0.put("type", "pie");
            series0.put("radius", "doughnut".equalsIgnoreCase(chartType) ? List.of("42%", "68%") : "65%");
            series0.put("minShowLabelAngle", intValue(label.get("minPercent"), 3));
            series0.put("label", Map.of("show", true, "formatter", "{b}: {d}%"));
            option.put("series", List.of(series0));
        }
        if (boolValue(renderConfig.get("dataZoom"), false)) {
            option.put("dataZoom", List.of(
                    Map.of("type", "slider", "show", true, "xAxisIndex", 0, "bottom", 8, "height", 22),
                    Map.of("type", "inside", "xAxisIndex", 0)
            ));
        }
        return option;
    }

    private Map<String, Object> buildVoiceSummary(Map<String, Object> rule, Map<String, Object> profile,
                                                  Map<String, Object> renderConfig) {
        return Map.of(
                "enabled", boolValue(renderConfig.get("voiceSummary"), true),
                "scenarioType", rule.get("scenarioType"),
                "chartType", rule.get("chartType"),
                "summaryTemplate", "已按 " + rule.get("ruleName") + " 推荐 " + rule.get("chartType")
                        + "，数据包含 " + profile.get("rowCount") + " 行。"
        );
    }

    private List<Map<String, Object>> normalizeFieldsForProfile(List<Map<String, Object>> fields, List<Map<String, Object>> rows) {
        if (fields != null && !fields.isEmpty()) {
            return fields.stream().map(field -> {
                Map<String, Object> item = new LinkedHashMap<>(field);
                if (!item.containsKey("name")) {
                    item.put("name", item.getOrDefault("displayName", item.getOrDefault("columnName", "")));
                }
                if (!item.containsKey("type")) {
                    item.put("type", item.getOrDefault("fieldType", ""));
                }
                return item;
            }).toList();
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, Object> first = rows.get(0);
        List<Map<String, Object>> inferred = new ArrayList<>();
        for (String key : first.keySet()) {
            Object value = first.get(key);
            inferred.add(Map.of("name", key, "type", value instanceof Number ? "number" : "string"));
        }
        return inferred;
    }

    private String explain(Map<String, Object> rule, Map<String, Object> profile) {
        String template = Objects.toString(rule.getOrDefault("explainTemplate", ""), "");
        if (!template.isBlank()) {
            return template;
        }
        return "Matched " + rule.get("scenarioType") + " by " + profile.get("fieldCount") + " fields and "
                + profile.get("rowCount") + " rows.";
    }

    private void seedRules() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_chart_rule", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        List<Map<String, Object>> defaults = List.of(
                rule("time_series_default", "时序趋势默认规则", "TIME_SERIES", "line", 400,
                        Map.of("timeRequired", true, "numericRequired", true),
                        Map.of("prediction", Map.of("enabled", true, "confidence", 0.95), "smooth", true),
                        "识别到时间字段和数值指标，推荐折线图展示趋势，并支持预测曲线与 95% 置信区间。"),
                rule("group_compare_default", "分组对比默认规则", "GROUP_COMPARE", "bar", 300,
                        Map.of("dimensionRequired", true, "numericRequired", true, "topN", 20),
                        Map.of("compare", Map.of("mom", true, "yoy", true), "sort", "desc"),
                        "识别到分类维度和数值指标，推荐柱状图展示分组对比，并支持同比、环比逻辑。"),
                rule("ratio_default", "占比分析默认规则", "RATIO", "doughnut", 250,
                        Map.of("dimensionRequired", true, "numericRequired", true),
                        Map.of("label", Map.of("showPercent", true, "digits", 1, "minPercent", 3)),
                        "识别到结构占比分析场景，推荐环形图展示各分类贡献比例。"),
                rule("detail_default", "明细数据默认规则", "DETAIL", "table", 100,
                        Map.of("fallback", true, "minFields", 5),
                        Map.of("pagination", Map.of("pageSize", 20), "sortable", true),
                        "数据更适合逐行查看，推荐表格并支持字段显示、排序和分页。")
        );
        for (Map<String, Object> item : defaults) {
            createRule(item);
        }
    }

    private void seedPreference() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_chart_style_preference WHERE preference_code = 'enterprise_default'", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        savePreferences(Map.of(
                "themeName", "企业默认可视化风格",
                "colorPalette", List.of("#2563eb", "#16a34a", "#f59e0b", "#dc2626", "#7c3aed", "#0891b2"),
                "fontConfig", Map.of("fontFamily", "Microsoft YaHei", "fontSize", 12),
                "layoutConfig", Map.of("legend", "top", "gridContainLabel", true, "height", 360),
                "defaultOptions", Map.of("animation", true, "dataZoom", false, "voiceSummary", true)
        ));
    }

    private Map<String, Object> rule(String code, String name, String scenario, String chart, int priority,
                                     Map<String, Object> match, Map<String, Object> render, String explain) {
        return Map.of(
                "ruleCode", code,
                "ruleName", name,
                "scenarioType", scenario,
                "chartType", chart,
                "enabled", true,
                "priority", priority,
                "matchConfig", match,
                "renderConfig", render,
                "explainTemplate", explain
        );
    }

    private Map<String, Object> fallbackRule(String scenario) {
        String chart = switch (scenario) {
            case "TIME_SERIES" -> "line";
            case "GROUP_COMPARE" -> "bar";
            case "RATIO" -> "doughnut";
            default -> "table";
        };
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", null);
        rule.put("ruleCode", "fallback_" + scenario.toLowerCase(Locale.ROOT));
        rule.put("ruleName", "Fallback " + scenario);
        rule.put("scenarioType", scenario);
        rule.put("chartType", chart);
        rule.put("priority", 0);
        rule.put("explainTemplate", "No configured rule matched, so the engine used a safe fallback.");
        return rule;
    }

    private Map<String, Object> decodeRule(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("enabled", boolValue(row.get("enabled"), true));
        result.put("matchConfig", readJson(Objects.toString(row.get("matchConfig"), "{}"), Map.of()));
        result.put("renderConfig", readJson(Objects.toString(row.get("renderConfig"), "{}"), Map.of()));
        return result;
    }

    private void writeAudit(Long ruleId, String action, Object before, Object after) {
        jdbcTemplate.update("""
                INSERT INTO ai_chart_rule_audit_log(rule_id, action, before_snapshot, after_snapshot, operator)
                VALUES (?, ?, ?, ?, ?)
                """, ruleId, action, jsonValue(before), jsonValue(after), operatorUser());
    }

    private void validateConfig(String... jsonValues) {
        for (String value : jsonValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.length() > 60000) {
                throw new IllegalArgumentException("JSON config is too large");
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("<script") || lower.contains("javascript:") || lower.contains("function(")) {
                throw new IllegalArgumentException("JSON config contains unsafe content");
            }
            readJson(value, Map.of());
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(Objects.toString(k, ""), v));
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            return readJson(text, Map.of());
        }
        return Map.of();
    }

    private List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((k, v) -> row.put(Objects.toString(k, ""), v));
                result.add(row);
            }
        }
        return result;
    }

    private <T> T readJson(String value, T fallback) {
        try {
            if (fallback instanceof List<?>) {
                return (T) objectMapper.readValue(value, List.class);
            }
            return (T) objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String jsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON config");
        }
    }

    private String textValue(Object value) {
        return value == null ? null : Objects.toString(value, null);
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || Objects.toString(value, "").isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return Objects.toString(value, "").trim();
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(Objects.toString(value, String.valueOf(fallback)));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(value, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            String text = Objects.toString(value, "");
            return text.isBlank() ? null : Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String operatorUser() {
        try {
            return AuthContext.userId();
        } catch (IllegalStateException ignored) {
            return "system";
        }
    }
}
