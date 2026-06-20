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
import java.util.Set;

@Service
@DependsOn("sqlMigrationRunner")
public class AiChartRuleConfigService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_TEST_FIELD_COUNT = 60;
    private static final int MAX_TEST_ROW_COUNT = 500;
    private static final int MAX_TEST_JSON_LENGTH = 120_000;
    private static final int MAX_CONFIG_TEXT_LENGTH = 60_000;
    private static final int MAX_SAFE_STRING_LENGTH = 4_000;
    private static final Set<String> RENDER_ROOT_KEYS = Set.of(
            "animation", "animationDuration", "animationDurationUpdate", "animationEasing",
            "animationEasingUpdate", "animationThreshold", "smooth", "showSymbol", "barMaxWidth", "tooltip", "dataZoom", "label",
            "prediction", "voiceSummary", "dynamic", "refreshIntervalSeconds", "dynamicRefreshInterval",
            "compare", "sort", "pagination", "sortable", "table", "radar", "scatter", "metric", "map"
    );
    private static final Set<String> RENDER_TOOLTIP_KEYS = Set.of("show", "trigger", "confine", "axisPointerType", "axisPointer");
    private static final Set<String> RENDER_AXIS_POINTER_KEYS = Set.of("type");
    private static final Set<String> RENDER_DATA_ZOOM_KEYS = Set.of("enabled", "threshold", "start", "end", "height", "bottom");
    private static final Set<String> RENDER_DYNAMIC_KEYS = Set.of(
            "refreshIntervalSeconds", "incrementalRendering", "progressive", "progressiveThreshold",
            "largeThreshold", "autoDataZoomThreshold", "autoLegendScrollThreshold", "dataZoomStart", "dataZoomEnd"
    );
    private static final Set<String> RENDER_LABEL_KEYS = Set.of("show", "showPercent", "digits", "minPercent", "formatter", "position", "fontSize");
    private static final Set<String> RENDER_PREDICTION_KEYS = Set.of(
            "enabled", "confidence", "confidenceLabel", "horizon", "algorithm", "showExplanation",
            "legendConfig", "legend", "showHistory", "historyLabel", "showForecast", "forecastLabel",
            "showUpper", "upperLabel", "showLower", "lowerLabel", "showAnomaly", "anomalyLabel"
    );
    private static final Set<String> RENDER_PREDICTION_SERIES_KEYS = Set.of("history", "forecast", "upper", "lower", "anomaly");
    private static final Set<String> RENDER_PREDICTION_LEGEND_ITEM_KEYS = Set.of("show", "label");
    private static final Set<String> RENDER_VOICE_SUMMARY_KEYS = Set.of("enabled", "order", "templates", "chartTemplates", "summaryTemplate", "maxItems");
    private static final Set<String> RENDER_VOICE_FIELD_KEYS = Set.of("title", "metric", "max", "min", "trend", "anomaly");
    private static final Set<String> RENDER_CHART_TYPE_KEYS = Set.of(
            "line", "bar", "pie", "doughnut", "table", "radar", "scatter", "metric", "map");
    private static final Set<String> RENDER_COMPARE_KEYS = Set.of("mom", "yoy");
    private static final Set<String> RENDER_PAGINATION_KEYS = Set.of("pageSize");
    private static final Set<String> RENDER_TABLE_KEYS = Set.of("showHeader", "stripe", "border", "pageSize", "sortable");
    private static final Set<String> RENDER_RADAR_KEYS = Set.of("areaOpacity", "lineWidth", "symbolSize");
    private static final Set<String> RENDER_SCATTER_KEYS = Set.of("symbolSize", "opacity");
    private static final Set<String> RENDER_METRIC_KEYS = Set.of("unit", "precision", "compareLabel", "trend");
    private static final Set<String> RENDER_MAP_KEYS = Set.of("mapName", "geoLevel", "roam", "areaColor", "borderColor", "emphasisColor");

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
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `ai_chart_rule_version` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `rule_id` BIGINT NOT NULL,
                  `rule_code` VARCHAR(80) NOT NULL,
                  `version_no` INT NOT NULL,
                  `snapshot` TEXT NOT NULL,
                  `change_action` VARCHAR(32) NOT NULL,
                  `operator` VARCHAR(64) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_ai_chart_rule_version_no` (`rule_id`, `version_no`),
                  INDEX `idx_ai_chart_rule_version_rule` (`rule_id`),
                  INDEX `idx_ai_chart_rule_version_code` (`rule_code`),
                  INDEX `idx_ai_chart_rule_version_created` (`created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chart rule version snapshots';
                """);
        seedRules();
        seedPreference();
        seedInitialRuleVersions();
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
        requireAdminForWrite();
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
        validateRuleConfig(matchConfig, renderConfig);

        jdbcTemplate.update("""
                INSERT INTO ai_chart_rule(rule_code, rule_name, scenario_type, chart_type, enabled, priority,
                                          match_config, render_config, explain_template, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ruleCode, ruleName, scenarioType, chartType, enabled ? 1 : 0, priority,
                matchConfig, renderConfig, explainTemplate, uid, uid);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> saved = getRule(id);
        writeAudit(id, "CREATE", null, saved);
        recordRuleVersion(id, "CREATE", saved);
        return saved;
    }

    public Map<String, Object> updateRule(Long id, Map<String, Object> body) {
        requireAdminForWrite();
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
        validateRuleConfig(matchConfig, renderConfig);

        jdbcTemplate.update("""
                UPDATE ai_chart_rule
                SET rule_code = ?, rule_name = ?, scenario_type = ?, chart_type = ?, enabled = ?, priority = ?,
                    match_config = ?, render_config = ?, explain_template = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, ruleCode, ruleName, scenarioType, chartType, enabled ? 1 : 0, priority,
                matchConfig, renderConfig, explainTemplate, operatorUser(), id);
        Map<String, Object> saved = getRule(id);
        writeAudit(id, "UPDATE", before, saved);
        recordRuleVersion(id, "UPDATE", saved);
        return saved;
    }

    public void updateEnabled(Long id, boolean enabled) {
        requireAdminForWrite();
        Map<String, Object> before = getRule(id);
        jdbcTemplate.update("""
                UPDATE ai_chart_rule
                SET enabled = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, enabled ? 1 : 0, operatorUser(), id);
        Map<String, Object> saved = getRule(id);
        String action = enabled ? "ENABLE" : "DISABLE";
        writeAudit(id, action, before, saved);
        recordRuleVersion(id, action, saved);
    }

    public void deleteRule(Long id) {
        requireAdminForWrite();
        Map<String, Object> before = getRule(id);
        recordRuleVersion(id, "DELETE", before);
        jdbcTemplate.update("DELETE FROM ai_chart_rule WHERE id = ?", id);
        writeAudit(id, "DELETE", before, null);
    }

    public List<Map<String, Object>> listRuleVersions(Long ruleId) {
        return jdbcTemplate.queryForList("""
                        SELECT id, rule_id AS ruleId, rule_code AS ruleCode, version_no AS versionNo,
                               snapshot, change_action AS changeAction, operator, created_at AS createdAt
                        FROM ai_chart_rule_version
                        WHERE rule_id = ?
                        ORDER BY version_no DESC, id DESC
                        """, ruleId).stream()
                .map(this::decodeVersion)
                .toList();
    }

    public Map<String, Object> rollbackRuleVersion(Long ruleId, Long versionId) {
        requireAdminForWrite();
        Map<String, Object> before = getRule(ruleId);
        Map<String, Object> version = decodeVersion(jdbcTemplate.queryForMap("""
                SELECT id, rule_id AS ruleId, rule_code AS ruleCode, version_no AS versionNo,
                       snapshot, change_action AS changeAction, operator, created_at AS createdAt
                FROM ai_chart_rule_version
                WHERE id = ? AND rule_id = ?
                """, versionId, ruleId));
        Map<String, Object> snapshot = mapValue(version.get("snapshot"));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("版本快照为空，无法回滚");
        }
        validateRuleConfig(jsonValue(snapshot.get("matchConfig")), jsonValue(snapshot.get("renderConfig")));
        jdbcTemplate.update("""
                UPDATE ai_chart_rule
                SET rule_code = ?, rule_name = ?, scenario_type = ?, chart_type = ?, enabled = ?, priority = ?,
                    match_config = ?, render_config = ?, explain_template = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                requireText(snapshot, "ruleCode"),
                requireText(snapshot, "ruleName"),
                requireText(snapshot, "scenarioType"),
                requireText(snapshot, "chartType"),
                boolValue(snapshot.get("enabled"), true) ? 1 : 0,
                intValue(snapshot.get("priority"), 100),
                jsonValue(snapshot.get("matchConfig")),
                jsonValue(snapshot.get("renderConfig")),
                textValue(snapshot.get("explainTemplate")),
                operatorUser(),
                ruleId);
        Map<String, Object> saved = getRule(ruleId);
        writeAudit(ruleId, "ROLLBACK", before, Map.of("version", version, "rule", saved));
        recordRuleVersion(ruleId, "ROLLBACK", saved);
        return saved;
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
        requireAdminForWrite();
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
                "tooltipTriggers", List.of("axis", "item", "none"),
                "dynamicOptions", List.of("dataZoom", "incrementalRendering", "progressive", "largeThreshold",
                        "autoDataZoomThreshold", "autoLegendScrollThreshold"),
                "voiceSummaryFields", List.of("title", "metric", "trend", "max", "min", "anomaly"),
                "chartTypes", List.of("line", "bar", "pie", "doughnut", "table", "radar", "scatter", "metric", "map")
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

    public Map<String, Object> exportConfig() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", "insight-spark-ai-chart-rules");
        payload.put("version", 1);
        payload.put("exportedAt", java.time.LocalDateTime.now().toString());
        payload.put("rules", listRules(null, null, null).stream()
                .map(this::stripRuleRuntimeFields)
                .toList());
        payload.put("preferences", getPreferences());
        return payload;
    }

    public Map<String, Object> previewImport(Map<String, Object> body) {
        List<Map<String, Object>> incomingRules = objectList(body.get("rules"));
        if (incomingRules.size() > 200) {
            throw new IllegalArgumentException("导入规则数量不能超过 200 条");
        }
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (Map<String, Object> rule : listRules(null, null, null)) {
            existing.put(Objects.toString(rule.get("ruleCode"), ""), rule);
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        int create = 0;
        int overwrite = 0;
        int unchanged = 0;
        for (Map<String, Object> raw : incomingRules) {
            Map<String, Object> rule = normalizeImportRule(raw);
            String code = Objects.toString(rule.get("ruleCode"), "");
            Map<String, Object> old = existing.get(code);
            String action = old == null ? "CREATE" : sameRule(old, rule) ? "UNCHANGED" : "OVERWRITE";
            if ("CREATE".equals(action)) create++;
            if ("OVERWRITE".equals(action)) overwrite++;
            if ("UNCHANGED".equals(action)) unchanged++;
            changes.add(Map.of(
                    "ruleCode", code,
                    "ruleName", rule.get("ruleName"),
                    "action", action
            ));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createCount", create);
        result.put("overwriteCount", overwrite);
        result.put("unchangedCount", unchanged);
        result.put("preferenceIncluded", body.get("preferences") instanceof Map<?, ?>);
        result.put("changes", changes);
        return result;
    }

    public Map<String, Object> importConfig(Map<String, Object> body) {
        requireAdminForWrite();
        Map<String, Object> preview = previewImport(body);
        List<Map<String, Object>> incomingRules = objectList(body.get("rules"));
        int applied = 0;
        for (Map<String, Object> raw : incomingRules) {
            Map<String, Object> rule = normalizeImportRule(raw);
            upsertImportedRule(rule);
            applied++;
        }
        if (body.get("preferences") instanceof Map<?, ?> preferenceMap) {
            savePreferences(mapValue(preferenceMap));
        }
        Map<String, Object> result = new LinkedHashMap<>(preview);
        result.put("appliedRuleCount", applied);
        writeAudit(null, "IMPORT", null, result);
        return result;
    }

    public Map<String, Object> testRecommendation(Map<String, Object> body) {
        validateTestPayload(body);
        List<Map<String, Object>> fields = objectList(body.get("fields"));
        List<Map<String, Object>> rows = objectList(body.get("rows"));
        String intent = Objects.toString(body.getOrDefault("intent", ""), "");
        Map<String, Object> profile = profile(fields, rows, intent);
        Map<String, Object> rule = chooseRule(profile, intent);
        Map<String, Object> option = buildOption(rule, fields, rows);
        Map<String, Object> optionTemplate = buildOptionTemplate(Objects.toString(rule.get("chartType"), "bar"),
                mapValue(rule.get("renderConfig")), getPreferences());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile);
        result.put("matchedRule", rule);
        result.put("chartType", rule.get("chartType"));
        result.put("option", option);
        result.put("optionTemplate", optionTemplate);
        result.put("explain", explain(rule, profile));
        writeAudit(longOrNull(rule.get("id")), "TEST", null, result);
        return result;
    }

    public Map<String, Object> latestInteractiveOptionTemplate(String ruleCode, String fallbackChartType) {
        Map<String, Object> fullTemplate = latestOptionTemplate(ruleCode, fallbackChartType);
        if (fullTemplate.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> interactive = new LinkedHashMap<>();
        for (String key : List.of("animation", "tooltip", "dataZoom", "dynamic")) {
            if (fullTemplate.containsKey(key)) {
                interactive.put(key, fullTemplate.get(key));
            }
        }
        if (!interactive.containsKey("dataZoom")) {
            interactive.put("dataZoom", Map.of("enabled", false));
        }
        return interactive;
    }

    public Map<String, Object> latestOptionTemplate(String ruleCode, String fallbackChartType) {
        String code = Objects.toString(ruleCode, "").trim();
        if (code.isBlank()) {
            return Map.of();
        }
        Map<String, Object> rule = findRuleByCode(code);
        if (rule == null || !boolValue(rule.get("enabled"), true)) {
            return Map.of(
                    "dynamic", Map.of("refreshIntervalSeconds", 0),
                    "dataZoom", Map.of("enabled", false)
            );
        }
        return buildOptionTemplate(
                Objects.toString(rule.getOrDefault("chartType", fallbackChartType), fallbackChartType),
                mapValue(rule.get("renderConfig")),
                getPreferences());
    }

    private Map<String, Object> stripRuleRuntimeFields(Map<String, Object> rule) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("ruleCode", "ruleName", "scenarioType", "chartType", "enabled",
                "priority", "matchConfig", "renderConfig", "explainTemplate")) {
            out.put(key, rule.get(key));
        }
        return out;
    }

    private Map<String, Object> normalizeImportRule(Map<String, Object> raw) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", requireText(raw, "ruleCode"));
        rule.put("ruleName", requireText(raw, "ruleName"));
        rule.put("scenarioType", requireText(raw, "scenarioType"));
        rule.put("chartType", requireText(raw, "chartType"));
        rule.put("enabled", boolValue(raw.get("enabled"), true));
        rule.put("priority", intValue(raw.get("priority"), 100));
        rule.put("matchConfig", mapValue(raw.get("matchConfig")));
        rule.put("renderConfig", mapValue(raw.get("renderConfig")));
        rule.put("explainTemplate", textValue(raw.get("explainTemplate")));
        validateRuleConfig(jsonValue(rule.get("matchConfig")), jsonValue(rule.get("renderConfig")));
        return rule;
    }

    private boolean sameRule(Map<String, Object> oldRule, Map<String, Object> newRule) {
        return Objects.equals(stripRuleRuntimeFields(oldRule), stripRuleRuntimeFields(newRule));
    }

    private void upsertImportedRule(Map<String, Object> rule) {
        Map<String, Object> before = findRuleByCode(Objects.toString(rule.get("ruleCode"), ""));
        String uid = operatorUser();
        jdbcTemplate.update("""
                INSERT INTO ai_chart_rule(rule_code, rule_name, scenario_type, chart_type, enabled, priority,
                                          match_config, render_config, explain_template, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name),
                                        scenario_type = VALUES(scenario_type),
                                        chart_type = VALUES(chart_type),
                                        enabled = VALUES(enabled),
                                        priority = VALUES(priority),
                                        match_config = VALUES(match_config),
                                        render_config = VALUES(render_config),
                                        explain_template = VALUES(explain_template),
                                        updated_by = VALUES(updated_by),
                                        updated_at = CURRENT_TIMESTAMP
                """,
                rule.get("ruleCode"),
                rule.get("ruleName"),
                rule.get("scenarioType"),
                rule.get("chartType"),
                boolValue(rule.get("enabled"), true) ? 1 : 0,
                intValue(rule.get("priority"), 100),
                jsonValue(rule.get("matchConfig")),
                jsonValue(rule.get("renderConfig")),
                textValue(rule.get("explainTemplate")),
                uid,
                uid);
        Map<String, Object> saved = findRuleByCode(Objects.toString(rule.get("ruleCode"), ""));
        if (before == null || !sameRule(before, saved)) {
            recordRuleVersion(longOrNull(saved.get("id")), before == null ? "IMPORT_CREATE" : "IMPORT_OVERWRITE", saved);
        }
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
        if ("MAP".equalsIgnoreCase(scenario) && intValue(profile.get("geoFieldCount"), 0) <= 0) {
            return false;
        }
        if ("SCATTER".equalsIgnoreCase(scenario) && intValue(profile.get("numericFieldCount"), 0) < 2) {
            return false;
        }
        if ("RADAR".equalsIgnoreCase(scenario)
                && intValue(profile.get("numericFieldCount"), 0) < 3
                && intValue(profile.get("dimensionFieldCount"), 0) < 3) {
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
        int geoFields = 0;
        for (Map<String, Object> field : fields) {
            String name = Objects.toString(field.getOrDefault("name", field.getOrDefault("columnName", "")), "");
            String sourceName = Objects.toString(field.getOrDefault("sourceFieldName", ""), "");
            String type = Objects.toString(field.getOrDefault("type", ""), "").toLowerCase(Locale.ROOT);
            String combinedName = name + " " + sourceName;
            String lower = buildFieldSearchText(field, combinedName);
            boolean isTime = isTimeLikeField(type, lower);
            boolean isNumber = isNumericLikeField(type, lower)
                    || combinedName.contains("金额") || combinedName.contains("销售额") || combinedName.contains("数量")
                    || combinedName.contains("占比") || lower.contains("amt") || lower.contains("qty")
                    || lower.contains("profit") || lower.contains("discount");
            if (isTime) {
                timeFields++;
            } else if (isNumber) {
                numericFields++;
            } else {
                dimensionFields++;
            }
            if (isGeoLikeField(lower)) {
                geoFields++;
            }
        }
        String detected = detectScenario(intent, timeFields, numericFields, dimensionFields, geoFields, fields.size(), rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", rows.size());
        result.put("fieldCount", fields.size());
        result.put("timeFieldCount", timeFields);
        result.put("numericFieldCount", numericFields);
        result.put("dimensionFieldCount", dimensionFields);
        result.put("geoFieldCount", geoFields);
        result.put("detectedScenario", detected);
        return result;
    }

    private String detectScenario(String intent, int timeFields, int numericFields, int dimensionFields, int geoFields, int fieldCount, int rowCount) {
        if (isExplicitDetailIntent(intent)) {
            return "DETAIL";
        }
        String lower = Objects.toString(intent, "").toLowerCase(Locale.ROOT);
        if (isMapIntent(intent)) {
            return geoFields > 0 && numericFields > 0 ? "MAP" : "GROUP_COMPARE";
        }
        if (isScatterIntent(intent)) {
            return numericFields >= 2 ? "SCATTER" : "GROUP_COMPARE";
        }
        if (isRadarIntent(intent)) {
            return numericFields >= 3 || dimensionFields >= 3 ? "RADAR" : "GROUP_COMPARE";
        }
        if (isMetricIntent(intent)) {
            return numericFields > 0 ? "METRIC" : "DETAIL";
        }
        if (isTimeSeriesIntent(intent)) {
            return timeFields > 0 ? "TIME_SERIES" : (dimensionFields > 0 && numericFields > 0 ? "GROUP_COMPARE" : "DETAIL");
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

    private boolean isRadarIntent(String intent) {
        String lower = Objects.toString(intent, "").toLowerCase(Locale.ROOT);
        return lower.contains("radar") || intent.contains("雷达") || intent.contains("能力")
                || intent.contains("评分") || intent.contains("画像") || intent.contains("多指标")
                || intent.contains("综合评价") || intent.contains("综合表现")
                || intent.contains("综合对比") || intent.contains("综合分析")
                || intent.contains("综合差异") || intent.contains("表现差异");
    }

    private boolean isScatterIntent(String intent) {
        String lower = Objects.toString(intent, "").toLowerCase(Locale.ROOT);
        return lower.contains("scatter") || lower.contains("correlation") || intent.contains("散点")
                || intent.contains("相关") || intent.contains("相关性") || intent.contains("关系")
                || intent.contains("离群") || intent.contains("异常点") || intent.contains("异常分布点");
    }

    private boolean isMetricIntent(String intent) {
        String lower = Objects.toString(intent, "").toLowerCase(Locale.ROOT);
        return lower.contains("kpi") || lower.contains("metric") || lower.contains("indicator")
                || intent.contains("指标卡") || intent.contains("核心指标") || intent.contains("当前值")
                || intent.contains("总量") || intent.contains("总额") || intent.contains("单指标");
    }

    private boolean isMapIntent(String intent) {
        String lower = Objects.toString(intent, "").toLowerCase(Locale.ROOT);
        return lower.contains("map") || lower.contains("geo") || intent.contains("地图")
                || intent.contains("地域") || intent.contains("地区") || intent.contains("省份")
                || intent.contains("城市") || intent.contains("区域分布");
    }

    private boolean isExplicitDetailIntent(String intent) {
        String text = Objects.toString(intent, "");
        String lower = text.toLowerCase(Locale.ROOT);
        boolean asksMultipleFields = text.contains("显示") && (text.contains("、") || text.contains("，")
                || text.contains(",") || text.contains("和"));
        return text.contains("明细") || text.contains("详情") || text.contains("列表")
                || text.contains("表格") || text.contains("列出") || asksMultipleFields
                || lower.contains("detail");
    }

    private boolean isTimeSeriesIntent(String intent) {
        String text = Objects.toString(intent, "");
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("trend") || lower.contains("forecast")
                || text.contains("趋势") || text.contains("预测")
                || text.contains("每日") || text.contains("按日") || text.contains("每天")
                || text.contains("每月") || text.contains("按月") || text.contains("月度") || text.contains("月份")
                || text.contains("每周") || text.contains("按周") || text.contains("周度")
                || text.contains("每季度") || text.contains("按季度") || text.contains("季度")
                || text.contains("每年") || text.contains("按年") || text.contains("年度")
                || text.contains("日期") || text.contains("时间");
    }

    private boolean isTimeLikeField(String type, String fieldText) {
        String normalizedType = Objects.toString(type, "").toLowerCase(Locale.ROOT);
        String text = Objects.toString(fieldText, "").toLowerCase(Locale.ROOT);
        if (normalizedType.contains("date") || normalizedType.contains("time")) {
            return true;
        }
        boolean hasTimeSignal = containsAny(text, "date", "time", "day", "month", "year", "week", "quarter",
                "日期", "时间", "月份", "年月", "年份", "年度", "月度", "季度");
        return hasTimeSignal && !hasStrongMetricSignal(text);
    }

    private boolean isGeoLikeField(String fieldText) {
        String text = Objects.toString(fieldText, "").toLowerCase(Locale.ROOT);
        return containsAny(text, "province", "city", "region", "area", "country", "geo", "location",
                "省", "省份", "城市", "地区", "区域", "大区", "地域", "地市", "国家");
    }

    private boolean isNumericLikeField(String type, String fieldText) {
        String normalizedType = Objects.toString(type, "").toLowerCase(Locale.ROOT);
        String text = Objects.toString(fieldText, "").toLowerCase(Locale.ROOT);
        return normalizedType.contains("int") || normalizedType.contains("decimal") || normalizedType.contains("double")
                || normalizedType.contains("number") || normalizedType.contains("numeric") || normalizedType.contains("float")
                || containsAny(text, "amount", "sales", "sale", "revenue", "gmv", "profit", "margin", "qty",
                "quantity", "discount", "score", "rating", "金额", "销售额", "销售", "收入", "营收", "利润",
                "数量", "销量", "折扣", "占比", "评分", "得分", "指标", "总量");
    }

    private String buildFieldSearchText(Map<String, Object> field, String fallback) {
        return (Objects.toString(fallback, "") + " "
                + Objects.toString(field.get("columnName"), "") + " "
                + Objects.toString(field.get("displayName"), "") + " "
                + Objects.toString(field.get("sourceFieldName"), "") + " "
                + Objects.toString(field.get("fieldComment"), "")).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... candidates) {
        String source = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (source.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStrongMetricSignal(String text) {
        return containsAny(Objects.toString(text, "").toLowerCase(Locale.ROOT),
                "sales_amt", "amount", "amt", "revenue", "gmv", "profit", "margin", "qty", "quantity", "discount",
                "销售额", "金额", "收入", "营收", "利润", "数量", "销量", "折扣", "占比");
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
        if ("metric".equalsIgnoreCase(chartType)) {
            Map<String, Object> metricCard = new LinkedHashMap<>();
            metricCard.put("type", "metric");
            metricCard.put("label", metric);
            metricCard.put("value", y.isEmpty() ? 0 : y.get(0));
            metricCard.put("rows", rows);
            return metricCard;
        }
        Map<String, Object> option = new LinkedHashMap<>();
        Map<String, Object> preference = getPreferences();
        Map<String, Object> font = mapValue(preference.get("fontConfig"));
        Map<String, Object> layout = mapValue(preference.get("layoutConfig"));
        option.put("title", Map.of("text", Objects.toString(rule.get("ruleName"), "AI chart recommendation")));
        option.put("tooltip", Map.of("trigger", "pie".equals(chartType) || "doughnut".equals(chartType) ? "item" : "axis"));
        option.put("legend", buildLegendOption(Objects.toString(layout.getOrDefault("legend", "top"), "top"), Map.of()));
        option.put("color", preference.get("colorPalette"));
        option.put("textStyle", buildTextStyleOption(font));
        option.put("layout", Map.of("height", boundedInt(layout.get("height"), 360, 240, 800)));
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
        } else if ("radar".equalsIgnoreCase(chartType)) {
            List<Map<String, Object>> indicators = new ArrayList<>();
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                indicators.add(Map.of("name", Objects.toString(x.get(i), ""), "max", 100));
            }
            option.put("radar", Map.of("indicator", indicators));
            option.put("series", List.of(Map.of(
                    "type", "radar",
                    "data", List.of(Map.of("name", metric, "value", y))
            )));
        } else if ("scatter".equalsIgnoreCase(chartType)) {
            String xMetric = names.size() > 1 ? names.get(0) : category;
            String yMetric = names.size() > 1 ? names.get(1) : metric;
            List<List<Object>> data = rows.stream()
                    .map(row -> List.of(
                            chartOptionValue(firstPresent(row.get(xMetric), row.get("x")), 0),
                            chartOptionValue(firstPresent(row.get(yMetric), row.get("y"), row.get("value")), 0)))
                    .toList();
            option.put("xAxis", Map.of("type", "value"));
            option.put("yAxis", Map.of("type", "value"));
            option.put("series", List.of(Map.of("name", yMetric, "type", "scatter", "data", data)));
        } else if ("map".equalsIgnoreCase(chartType)) {
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                data.add(Map.of("name", Objects.toString(x.get(i), ""), "value", chartOptionValue(y.get(i), 0)));
            }
            option.put("visualMap", Map.of("left", "left", "min", 0, "max", 100, "calculable", true));
            option.put("series", List.of(Map.of("type", "map", "map", "china", "name", metric, "data", data)));
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
        Map<String, Object> defaultOptions = mapValue(preference.get("defaultOptions"));
        Map<String, Object> font = mapValue(preference.get("fontConfig"));
        Map<String, Object> layout = mapValue(preference.get("layoutConfig"));
        Map<String, Object> dynamic = buildDynamicRenderConfig(renderConfig);
        applyAnimationOption(option, renderConfig, defaultOptions);
        option.put("color", preference.getOrDefault("colorPalette", List.of()));
        option.put("textStyle", buildTextStyleOption(font));
        option.put("layout", Map.of(
                "legend", Objects.toString(layout.getOrDefault("legend", "top"), "top"),
                "height", boundedInt(layout.get("height"), 360, 240, 800)
        ));
        option.put("dynamic", dynamic);
        if ("table".equalsIgnoreCase(chartType)) {
            option.put("type", "table");
            option.put("table", Map.of(
                    "showHeader", true,
                    "stripe", true,
                    "border", true
            ));
            return option;
        }
        if ("metric".equalsIgnoreCase(chartType)) {
            Map<String, Object> metric = mapValue(renderConfig.get("metric"));
            option.put("type", "metric");
            option.put("metric", Map.of(
                    "unit", Objects.toString(metric.getOrDefault("unit", ""), ""),
                    "precision", intValue(metric.get("precision"), 2),
                    "compareLabel", Objects.toString(metric.getOrDefault("compareLabel", "较上期"), "较上期"),
                    "trend", boolValue(metric.get("trend"), true)
            ));
            return option;
        }
        String legendPosition = Objects.toString(layout.getOrDefault("legend", "top"), "top");
        option.put("legend", buildLegendOption(legendPosition, dynamic));
        if ("line".equalsIgnoreCase(chartType) || "bar".equalsIgnoreCase(chartType)) {
            option.put("tooltip", buildTooltipOption(chartType, renderConfig));
            option.put("grid", buildGridOption(legendPosition));
            Map<String, Object> series0 = new LinkedHashMap<>();
            applyDynamicSeriesConfig(series0, dynamic);
            if ("line".equalsIgnoreCase(chartType)) {
                series0.put("smooth", boolValue(renderConfig.get("smooth"), true));
                series0.put("showSymbol", boolValue(renderConfig.get("showSymbol"), false));
                Map<String, Object> prediction = mapValue(renderConfig.get("prediction"));
                if (boolValue(prediction.get("enabled"), false)) {
                    Map<String, Object> predictionOption = new LinkedHashMap<>();
                    predictionOption.put("enabled", true);
                    predictionOption.put("confidence", prediction.getOrDefault("confidence", 0.95));
                    predictionOption.put("confidenceLabel", confidenceLabel(prediction.getOrDefault("confidence", 0.95)));
                    Map<String, Object> legend = buildPredictionLegendConfig(prediction);
                    predictionOption.put("legendConfig", legend);
                    predictionOption.put("legend", predictionLegendNames(legend));
                    predictionOption.put("showExplanation", boolValue(prediction.get("showExplanation"), true));
                    option.put("prediction", predictionOption);
                }
            } else {
                series0.put("barMaxWidth", intValue(renderConfig.get("barMaxWidth"), 32));
                series0.put("itemStyle", Map.of("borderRadius", List.of(4, 4, 0, 0)));
            }
            option.put("series", List.of(series0));
        } else if ("pie".equalsIgnoreCase(chartType) || "doughnut".equalsIgnoreCase(chartType)) {
            option.put("tooltip", buildTooltipOption(chartType, renderConfig));
            Map<String, Object> label = mapValue(renderConfig.get("label"));
            Map<String, Object> series0 = new LinkedHashMap<>();
            series0.put("type", "pie");
            applyDynamicSeriesConfig(series0, dynamic);
            series0.put("radius", "doughnut".equalsIgnoreCase(chartType) ? List.of("42%", "68%") : "65%");
            series0.put("minShowLabelAngle", intValue(label.get("minPercent"), 3));
            series0.put("label", Map.of("show", true, "formatter", "{b}: {d}%"));
            option.put("series", List.of(series0));
        } else if ("radar".equalsIgnoreCase(chartType)) {
            Map<String, Object> radar = mapValue(renderConfig.get("radar"));
            Map<String, Object> series0 = new LinkedHashMap<>();
            series0.put("type", "radar");
            series0.put("symbolSize", intValue(radar.get("symbolSize"), 4));
            series0.put("lineStyle", Map.of("width", intValue(radar.get("lineWidth"), 2)));
            series0.put("areaStyle", Map.of("opacity", doubleValue(radar.get("areaOpacity"), 0.12)));
            option.put("tooltip", buildTooltipOption("pie", renderConfig));
            option.put("radar", Map.of(
                    "radius", "62%",
                    "splitArea", Map.of("show", true)
            ));
            option.put("series", List.of(series0));
        } else if ("scatter".equalsIgnoreCase(chartType)) {
            Map<String, Object> scatter = mapValue(renderConfig.get("scatter"));
            Map<String, Object> series0 = new LinkedHashMap<>();
            applyDynamicSeriesConfig(series0, dynamic);
            series0.put("type", "scatter");
            series0.put("symbolSize", intValue(scatter.get("symbolSize"), 10));
            series0.put("itemStyle", Map.of("opacity", doubleValue(scatter.get("opacity"), 0.78)));
            option.put("tooltip", buildTooltipOption("scatter", renderConfig));
            option.put("grid", buildGridOption(legendPosition));
            option.put("xAxis", Map.of("type", "value"));
            option.put("yAxis", Map.of("type", "value"));
            option.put("series", List.of(series0));
        } else if ("map".equalsIgnoreCase(chartType)) {
            Map<String, Object> map = mapValue(renderConfig.get("map"));
            Map<String, Object> series0 = new LinkedHashMap<>();
            series0.put("type", "map");
            series0.put("map", Objects.toString(map.getOrDefault("mapName", "china"), "china"));
            series0.put("roam", boolValue(map.get("roam"), false));
            option.put("tooltip", buildTooltipOption("pie", renderConfig));
            option.put("visualMap", Map.of("left", "left", "calculable", true));
            option.put("series", List.of(series0));
        }
        if (shouldEnableDataZoom(renderConfig.get("dataZoom"), defaultOptions)) {
            option.put("dataZoom", buildDataZoomOption(renderConfig.get("dataZoom"), dynamic));
        } else if ("line".equalsIgnoreCase(chartType) || "bar".equalsIgnoreCase(chartType) || "scatter".equalsIgnoreCase(chartType)) {
            option.put("dataZoom", Map.of("enabled", false));
        }
        return option;
    }

    private Map<String, Object> buildDynamicRenderConfig(Map<String, Object> renderConfig) {
        Map<String, Object> raw = new LinkedHashMap<>(mapValue(renderConfig.get("dynamic")));
        if (renderConfig.containsKey("dynamicRefreshInterval")) {
            raw.put("refreshIntervalSeconds", renderConfig.get("dynamicRefreshInterval"));
        }
        if (renderConfig.containsKey("refreshIntervalSeconds")) {
            raw.put("refreshIntervalSeconds", renderConfig.get("refreshIntervalSeconds"));
        }
        Map<String, Object> dataZoom = mapValue(renderConfig.get("dataZoom"));
        if (!dataZoom.isEmpty()) {
            raw.putIfAbsent("autoDataZoomThreshold", dataZoom.get("threshold"));
            raw.putIfAbsent("dataZoomStart", dataZoom.get("start"));
            raw.putIfAbsent("dataZoomEnd", dataZoom.get("end"));
        }
        Map<String, Object> dynamic = new LinkedHashMap<>();
        dynamic.put("refreshIntervalSeconds", boundedInt(raw.get("refreshIntervalSeconds"), 0, 0, 3600));
        dynamic.put("incrementalRendering", boolValue(raw.get("incrementalRendering"), false));
        dynamic.put("progressive", boundedInt(raw.get("progressive"), 0, 0, 20000));
        dynamic.put("progressiveThreshold", boundedInt(raw.get("progressiveThreshold"), 3000, 0, 100000));
        dynamic.put("largeThreshold", boundedInt(raw.get("largeThreshold"), 2000, 0, 100000));
        dynamic.put("autoDataZoomThreshold", boundedInt(raw.get("autoDataZoomThreshold"), 14, 4, 500));
        dynamic.put("autoLegendScrollThreshold", boundedInt(raw.get("autoLegendScrollThreshold"), 10, 4, 500));
        dynamic.put("dataZoomStart", boundedInt(raw.get("dataZoomStart"), 0, 0, 100));
        dynamic.put("dataZoomEnd", boundedInt(raw.get("dataZoomEnd"), 60, 1, 100));
        return dynamic;
    }

    private void applyAnimationOption(Map<String, Object> option, Map<String, Object> renderConfig,
                                      Map<String, Object> defaultOptions) {
        boolean enabled = defaultOptions.containsKey("animation")
                ? boolValue(defaultOptions.get("animation"), true)
                : (renderConfig.containsKey("animation") ? boolValue(renderConfig.get("animation"), true) : true);
        option.put("animation", enabled);
        if (enabled) {
            option.put("animationDuration", boundedInt(renderConfig.get("animationDuration"), 1500, 100, 5000));
            option.put("animationDurationUpdate", boundedInt(renderConfig.get("animationDurationUpdate"), 1200, 100, 5000));
            option.put("animationEasing", Objects.toString(renderConfig.getOrDefault("animationEasing", "cubicOut"), "cubicOut"));
            option.put("animationEasingUpdate", Objects.toString(renderConfig.getOrDefault("animationEasingUpdate", "cubicOut"), "cubicOut"));
            option.put("animationThreshold", boundedInt(renderConfig.get("animationThreshold"), 2000, 0, 100000));
        } else {
            option.put("animationDuration", 0);
            option.put("animationDurationUpdate", 0);
        }
    }

    private Map<String, Object> buildLegendOption(String legendPosition, Map<String, Object> dynamic) {
        String position = Set.of("top", "bottom", "left", "right").contains(legendPosition) ? legendPosition : "top";
        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("type", "scroll");
        legend.put(position, 2);
        legend.put("pageButtonGap", 6);
        legend.put("pageIconSize", 10);
        legend.put("selector", false);
        legend.put("autoScrollThreshold", dynamic.getOrDefault("autoLegendScrollThreshold", 10));
        return legend;
    }

    private Map<String, Object> buildTextStyleOption(Map<String, Object> font) {
        String fontFamily = Objects.toString(font.getOrDefault("fontFamily", "Microsoft YaHei"), "Microsoft YaHei").trim();
        int fontSize = boundedInt(font.get("fontSize"), 12, 10, 28);
        Map<String, Object> textStyle = new LinkedHashMap<>();
        textStyle.put("fontFamily", fontFamily.isBlank() ? "Microsoft YaHei" : fontFamily);
        textStyle.put("fontSize", fontSize);
        return textStyle;
    }

    private Map<String, Object> buildGridOption(String legendPosition) {
        String position = Set.of("top", "bottom", "left", "right").contains(legendPosition) ? legendPosition : "top";
        int top = "top".equals(position) ? 48 : 32;
        int bottom = "bottom".equals(position) ? 72 : 56;
        int left = "left".equals(position) ? 92 : 48;
        int right = "right".equals(position) ? 92 : 16;
        return Map.of("left", left, "right", right, "top", top, "bottom", bottom, "containLabel", true);
    }

    private Map<String, Object> buildTooltipOption(String chartType, Map<String, Object> renderConfig) {
        Map<String, Object> raw = mapValue(renderConfig.get("tooltip"));
        boolean show = raw.isEmpty() && !(renderConfig.get("tooltip") instanceof Map<?, ?>)
                ? boolValue(renderConfig.get("tooltip"), true)
                : boolValue(raw.get("show"), true);
        String defaultTrigger = "pie".equalsIgnoreCase(chartType) || "doughnut".equalsIgnoreCase(chartType) ? "item" : "axis";
        String trigger = Objects.toString(raw.getOrDefault("trigger", defaultTrigger), defaultTrigger).toLowerCase(Locale.ROOT);
        if (!Set.of("axis", "item", "none").contains(trigger)) {
            trigger = defaultTrigger;
        }
        Map<String, Object> tooltip = new LinkedHashMap<>();
        tooltip.put("show", show && !"none".equals(trigger));
        tooltip.put("trigger", "none".equals(trigger) ? defaultTrigger : trigger);
        tooltip.put("confine", boolValue(raw.get("confine"), true));
        String axisPointerType = Objects.toString(raw.getOrDefault("axisPointerType", "shadow"), "shadow").toLowerCase(Locale.ROOT);
        if (Set.of("line", "shadow", "cross", "none").contains(axisPointerType) && !"none".equals(axisPointerType)) {
            tooltip.put("axisPointer", Map.of("type", axisPointerType));
        }
        return tooltip;
    }

    private boolean shouldEnableDataZoom(Object rawDataZoom, Map<String, Object> defaultOptions) {
        if (defaultOptions.containsKey("dataZoom")) {
            return boolValue(defaultOptions.get("dataZoom"), false);
        }
        if (rawDataZoom instanceof Map<?, ?> map) {
            Map<String, Object> normalized = mapValue(map);
            return boolValue(normalized.get("enabled"), true);
        }
        if (rawDataZoom != null) {
            return boolValue(rawDataZoom, false);
        }
        return boolValue(defaultOptions.get("dataZoom"), false);
    }

    private List<Map<String, Object>> buildDataZoomOption(Object rawDataZoom, Map<String, Object> dynamic) {
        Map<String, Object> raw = mapValue(rawDataZoom);
        int start = boundedInt(raw.get("start"), intValue(dynamic.get("dataZoomStart"), 0), 0, 100);
        int end = boundedInt(raw.get("end"), intValue(dynamic.get("dataZoomEnd"), 60), 1, 100);
        if (end <= start) {
            end = Math.min(100, start + 1);
        }
        int height = boundedInt(raw.get("height"), 22, 12, 60);
        int bottom = boundedInt(raw.get("bottom"), 8, 0, 120);
        return List.of(
                Map.of("type", "slider", "show", true, "xAxisIndex", 0, "bottom", bottom,
                        "height", height, "start", start, "end", end),
                Map.of("type", "inside", "xAxisIndex", 0, "start", start, "end", end)
        );
    }

    private void applyDynamicSeriesConfig(Map<String, Object> series, Map<String, Object> dynamic) {
        if (boolValue(dynamic.get("incrementalRendering"), false)) {
            int progressive = intValue(dynamic.get("progressive"), 400);
            series.put("progressive", progressive > 0 ? progressive : 400);
            series.put("progressiveThreshold", intValue(dynamic.get("progressiveThreshold"), 3000));
        }
        int largeThreshold = intValue(dynamic.get("largeThreshold"), 2000);
        if (largeThreshold > 0) {
            series.put("largeThreshold", largeThreshold);
        }
    }

    private Map<String, Object> buildPredictionLegendConfig(Map<String, Object> prediction) {
        Map<String, Object> legend = new LinkedHashMap<>(mapValue(prediction.get("legendConfig")));
        mergePredictionLegendItem(legend, prediction, "history", "showHistory", "historyLabel", "历史值", true);
        mergePredictionLegendItem(legend, prediction, "forecast", "showForecast", "forecastLabel", "预测值", true);
        mergePredictionLegendItem(legend, prediction, "upper", "showUpper", "upperLabel", "置信上界", true);
        mergePredictionLegendItem(legend, prediction, "lower", "showLower", "lowerLabel", "置信下界", true);
        mergePredictionLegendItem(legend, prediction, "anomaly", "showAnomaly", "anomalyLabel", "异常点", false);
        return legend;
    }

    private void mergePredictionLegendItem(Map<String, Object> legend, Map<String, Object> prediction,
                                           String key, String showKey, String labelKey,
                                           String defaultLabel, boolean defaultVisible) {
        Map<String, Object> current = new LinkedHashMap<>(mapValue(legend.get(key)));
        current.put("show", boolValue(prediction.getOrDefault(showKey, current.get("show")), defaultVisible));
        String label = Objects.toString(prediction.getOrDefault(labelKey, current.getOrDefault("label", defaultLabel)), "").trim();
        current.put("label", label.isBlank() ? defaultLabel : label);
        legend.put(key, current);
    }

    private List<String> predictionLegendNames(Map<String, Object> legend) {
        List<String> names = new ArrayList<>();
        for (String key : List.of("history", "forecast", "upper", "lower", "anomaly")) {
            Map<String, Object> item = mapValue(legend.get(key));
            if (boolValue(item.get("show"), true)) {
                String label = Objects.toString(item.getOrDefault("label", ""), "").trim();
                if (!label.isBlank()) {
                    names.add(label);
                }
            }
        }
        return names;
    }

    private String confidenceLabel(Object value) {
        if (value instanceof Number number) {
            double confidence = number.doubleValue();
            if (confidence > 0 && confidence <= 1) {
                return Math.round(confidence * 100) + "%";
            }
            return Math.round(confidence) + "%";
        }
        String text = Objects.toString(value, "95%").trim();
        if (text.endsWith("%")) {
            return text;
        }
        try {
            double confidence = Double.parseDouble(text);
            if (confidence > 0 && confidence <= 1) {
                return Math.round(confidence * 100) + "%";
            }
            return Math.round(confidence) + "%";
        } catch (Exception ignored) {
            return "95%";
        }
    }

    private Map<String, Object> buildVoiceSummary(Map<String, Object> rule, Map<String, Object> profile,
                                                  Map<String, Object> renderConfig) {
        Object raw = renderConfig.get("voiceSummary");
        Map<String, Object> config = raw instanceof Map<?, ?> rawMap ? mapValue(rawMap) : Map.of();
        boolean enabled = raw instanceof Map<?, ?>
                ? boolValue(config.get("enabled"), true)
                : boolValue(raw, true);
        String chartType = Objects.toString(rule.get("chartType"), "bar");
        List<String> order = stringList(config.get("order"),
                List.of("title", "metric", "max", "min", "trend", "anomaly"));
        Map<String, Object> templates = defaultVoiceTemplates(config);
        Map<String, Object> chartTemplates = defaultChartVoiceTemplates(config);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("scenarioType", rule.get("scenarioType"));
        result.put("chartType", chartType);
        result.put("ruleCode", rule.get("ruleCode"));
        result.put("ruleName", rule.get("ruleName"));
        result.put("order", order);
        result.put("templates", templates);
        result.put("chartTemplates", chartTemplates);
        result.put("summaryTemplate", Objects.toString(config.getOrDefault("summaryTemplate",
                chartTemplates.getOrDefault(chartType, chartTemplates.getOrDefault("bar", ""))), ""));
        result.put("maxItems", intValue(config.get("maxItems"), 3));
        result.put("rowCount", profile.get("rowCount"));
        return result;
    }

    private Map<String, Object> defaultVoiceTemplates(Map<String, Object> config) {
        Map<String, Object> templates = new LinkedHashMap<>();
        templates.put("title", "查询完成，已按「{ruleName}」生成{chartTypeName}。");
        templates.put("metric", "当前按{dimension}分析{metric}，共{count}项结果。");
        templates.put("max", "最大值为{maxName}，数值{maxValue}。");
        templates.put("min", "最小值为{minName}，数值{minValue}。");
        templates.put("trend", "整体趋势为{trend}。");
        templates.put("anomaly", "检测到{anomalyCount}个异常点。");
        templates.putAll(mapValue(config.get("templates")));
        return templates;
    }

    private Map<String, Object> defaultChartVoiceTemplates(Map<String, Object> config) {
        Map<String, Object> chartTemplates = new LinkedHashMap<>();
        chartTemplates.put("line", "查询完成，已生成折线图。当前按{dimension}分析{metric}，整体趋势为{trend}，最大值为{maxName}{maxValue}。");
        chartTemplates.put("bar", "查询完成，已生成柱状图。当前按{dimension}对比{metric}，最大值为{maxName}{maxValue}，最小值为{minName}{minValue}。");
        chartTemplates.put("pie", "查询完成，已生成饼图。当前展示{metric}的占比结构，最高项为{maxName}{maxValue}。");
        chartTemplates.put("doughnut", "查询完成，已生成环形图。当前展示{metric}的占比结构，最高项为{maxName}{maxValue}。");
        chartTemplates.put("table", "查询完成，已生成表格。当前展示{count}行明细数据，包含{dimension}和{metric}等字段。");
        chartTemplates.put("radar", "查询完成，已生成雷达图。当前围绕{dimension}对比{metric}，用于观察多指标画像。");
        chartTemplates.put("scatter", "查询完成，已生成散点图。当前展示{metric}的分布与相关性，最高项为{maxName}{maxValue}。");
        chartTemplates.put("metric", "查询完成，已生成指标卡。当前核心指标为{metric}，数值为{maxValue}。");
        chartTemplates.put("map", "查询完成，已生成地图。当前按{dimension}展示{metric}的地域分布，最高区域为{maxName}{maxValue}。");
        chartTemplates.putAll(mapValue(config.get("chartTemplates")));
        return chartTemplates;
    }

    private List<Map<String, Object>> normalizeFieldsForProfile(List<Map<String, Object>> fields, List<Map<String, Object>> rows) {
        if (fields != null && !fields.isEmpty()) {
            return fields.stream().map(field -> {
                Map<String, Object> item = new LinkedHashMap<>(field);
                if (!item.containsKey("name")) {
                    item.put("name", item.getOrDefault("displayName",
                            item.getOrDefault("sourceFieldName", item.getOrDefault("columnName", ""))));
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
                rule("radar_default", "多指标雷达默认规则", "RADAR", "radar", 240,
                        Map.of("numericRequired", true, "minNumericFields", 3),
                        Map.of("radar", Map.of("areaOpacity", 0.12, "lineWidth", 2, "symbolSize", 4)),
                        "识别到多指标评分、能力画像或综合评价场景，推荐雷达图展示多个维度的相对表现。"),
                rule("scatter_default", "相关分布散点默认规则", "SCATTER", "scatter", 230,
                        Map.of("numericRequired", true, "minNumericFields", 2),
                        Map.of("scatter", Map.of("symbolSize", 10, "opacity", 0.78)),
                        "识别到两个数值指标的相关性、分布或离群点分析场景，推荐散点图。"),
                rule("metric_card_default", "核心指标卡默认规则", "METRIC", "metric", 220,
                        Map.of("numericRequired", true, "singleMetric", true),
                        Map.of("metric", Map.of("precision", 2, "compareLabel", "较上期", "trend", true)),
                        "识别到单指标、KPI、总量或当前值展示场景，推荐指标卡突出核心数值。"),
                rule("geo_map_default", "地域分布地图默认规则", "MAP", "map", 210,
                        Map.of("geoRequired", true, "numericRequired", true),
                        Map.of("map", Map.of("mapName", "china", "geoLevel", "province", "roam", false)),
                        "识别到省份、城市、地区等地域分布场景，推荐地图展示空间分布。"),
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
            case "RADAR" -> "radar";
            case "SCATTER" -> "scatter";
            case "METRIC" -> "metric";
            case "MAP" -> "map";
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

    private Map<String, Object> findRuleByCode(String ruleCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, rule_code AS ruleCode, rule_name AS ruleName, scenario_type AS scenarioType,
                       chart_type AS chartType, enabled, priority, match_config AS matchConfig,
                       render_config AS renderConfig, explain_template AS explainTemplate,
                       created_by AS createdBy, updated_by AS updatedBy, created_at AS createdAt, updated_at AS updatedAt
                FROM ai_chart_rule WHERE rule_code = ?
                """, ruleCode);
        return rows.isEmpty() ? null : decodeRule(rows.get(0));
    }

    private Map<String, Object> decodeRule(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("enabled", boolValue(row.get("enabled"), true));
        result.put("matchConfig", readJson(Objects.toString(row.get("matchConfig"), "{}"), Map.of()));
        result.put("renderConfig", readJson(Objects.toString(row.get("renderConfig"), "{}"), Map.of()));
        return result;
    }

    private Map<String, Object> decodeVersion(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("snapshot", readJson(Objects.toString(row.get("snapshot"), "{}"), Map.of()));
        return result;
    }

    private void seedInitialRuleVersions() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_chart_rule_version", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        for (Map<String, Object> rule : listRules(null, null, null)) {
            recordRuleVersion(longOrNull(rule.get("id")), "INIT", rule);
        }
    }

    private void recordRuleVersion(Long ruleId, String action, Map<String, Object> snapshot) {
        if (ruleId == null || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        Integer nextVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) + 1
                FROM ai_chart_rule_version
                WHERE rule_id = ?
                """, Integer.class, ruleId);
        jdbcTemplate.update("""
                INSERT INTO ai_chart_rule_version(rule_id, rule_code, version_no, snapshot, change_action, operator)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                ruleId,
                Objects.toString(snapshot.get("ruleCode"), ""),
                nextVersion == null ? 1 : nextVersion,
                jsonValue(stripRuleRuntimeFields(snapshot)),
                action,
                operatorUser());
    }

    private void writeAudit(Long ruleId, String action, Object before, Object after) {
        jdbcTemplate.update("""
                INSERT INTO ai_chart_rule_audit_log(rule_id, action, before_snapshot, after_snapshot, operator)
                VALUES (?, ?, ?, ?, ?)
                """, ruleId, action, jsonValue(before), jsonValue(after), operatorUser());
    }

    private void requireAdminForWrite() {
        try {
            if (!AuthContext.isAdmin()) {
                throw new SecurityException("仅管理员可修改 AI 图表推荐规则配置");
            }
        } catch (IllegalStateException ignored) {
            // Startup seed and isolated unit tests run without a request-scoped AuthContext.
        }
    }

    private void validateTestPayload(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("测试样例不能为空");
        }
        int jsonLength = jsonLength(body);
        if (jsonLength > MAX_TEST_JSON_LENGTH) {
            throw new IllegalArgumentException("规则测试样例 JSON 过大，最大支持 " + MAX_TEST_JSON_LENGTH + " 字符");
        }
        List<?> fields = rawList(body.get("fields"));
        if (fields.size() > MAX_TEST_FIELD_COUNT) {
            throw new IllegalArgumentException("规则测试样例字段数不能超过 " + MAX_TEST_FIELD_COUNT + " 个");
        }
        List<?> rows = rawList(body.get("rows"));
        if (rows.size() > MAX_TEST_ROW_COUNT) {
            throw new IllegalArgumentException("规则测试样例行数不能超过 " + MAX_TEST_ROW_COUNT + " 行");
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map && map.size() > MAX_TEST_FIELD_COUNT * 2) {
                throw new IllegalArgumentException("规则测试样例单行字段过多");
            }
        }
    }

    private void validateRuleConfig(String matchConfig, String renderConfig) {
        validateConfig(matchConfig, renderConfig);
        validateRenderConfig(renderConfig);
    }

    private void validateConfig(String... jsonValues) {
        for (String value : jsonValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.length() > MAX_CONFIG_TEXT_LENGTH) {
                throw new IllegalArgumentException("配置 JSON 过大，单项最多支持 " + MAX_CONFIG_TEXT_LENGTH + " 字符");
            }
            Object parsed = parseJsonConfig(value);
            validateSafeJsonValue(parsed, "$");
        }
    }

    private void validateRenderConfig(String renderConfig) {
        if (renderConfig == null || renderConfig.isBlank()) {
            return;
        }
        Object parsed = parseJsonConfig(renderConfig);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("ECharts 渲染配置必须是 JSON 对象");
        }
        validateRenderConfigMap(mapValue(raw), "");
    }

    private Object parseJsonConfig(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 格式不正确，请检查逗号、引号和括号是否完整");
        }
    }

    private void validateSafeJsonValue(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey(), "");
                validateSafeJsonKey(key, path);
                validateSafeJsonValue(entry.getValue(), path + "." + key);
            }
            return;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 1_000) {
                throw new IllegalArgumentException("配置数组过长，单个数组最多支持 1000 项");
            }
            for (Object item : list) {
                validateSafeJsonValue(item, path + "[]");
            }
            return;
        }
        if (value instanceof String text) {
            validateSafeText(text, path);
        }
    }

    private void validateSafeJsonKey(String key, String path) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (normalized.contains("script")
                || normalized.contains("function")
                || normalized.equals("url")
                || normalized.endsWith("url")
                || normalized.equals("href")
                || normalized.equals("src")
                || normalized.equals("link")
                || normalized.equals("onclick")
                || normalized.equals("onload")
                || normalized.equals("onerror")) {
            throw new IllegalArgumentException("检测到不安全配置字段：" + friendlyPath(path + "." + key)
                    + "，禁止配置脚本、函数入口或外链地址字段");
        }
    }

    private void validateSafeText(String text, String path) {
        if (text.length() > MAX_SAFE_STRING_LENGTH) {
            throw new IllegalArgumentException("配置文本过长：" + friendlyPath(path)
                    + "，单个文本最多支持 " + MAX_SAFE_STRING_LENGTH + " 字符");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("\\s+", "");
        if (lower.contains("<script")
                || lower.contains("</script")
                || lower.contains("javascript:")
                || lower.contains("vbscript:")
                || lower.contains("data:text/html")
                || lower.contains("data:application/javascript")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("://")
                || compact.contains("function(")
                || compact.contains("newfunction(")
                || compact.contains("eval(")
                || compact.contains("settimeout(")
                || compact.contains("setinterval(")
                || compact.contains("document.")
                || compact.contains("window.")
                || compact.contains("fetch(")
                || compact.contains("xmlhttprequest")
                || compact.contains("=>")) {
            throw new IllegalArgumentException("检测到不安全配置内容：" + friendlyPath(path)
                    + "，禁止在图表配置中写入脚本、函数体或外链 URL");
        }
    }

    private void validateRenderConfigMap(Map<String, Object> map, String path) {
        Set<String> allowed = allowedRenderKeys(path);
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("暂不支持该图表渲染配置节点：" + friendlyPath(path)
                    + "。请使用页面中的结构化配置项，或改用允许的动态渲染、预测、语音播报配置");
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("暂不支持该图表渲染配置字段：" + friendlyPath(renderPath(path, key))
                        + "。为避免影响前端渲染安全，当前只允许规则引擎已支持的配置项");
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                validateRenderConfigMap(mapValue(nested), renderPath(path, key));
            } else if (value instanceof List<?> list) {
                validateRenderConfigList(list, renderPath(path, key));
            }
        }
    }

    private Set<String> allowedRenderKeys(String path) {
        return switch (path) {
            case "" -> RENDER_ROOT_KEYS;
            case "tooltip" -> RENDER_TOOLTIP_KEYS;
            case "tooltip.axisPointer" -> RENDER_AXIS_POINTER_KEYS;
            case "dataZoom" -> RENDER_DATA_ZOOM_KEYS;
            case "dynamic" -> RENDER_DYNAMIC_KEYS;
            case "label" -> RENDER_LABEL_KEYS;
            case "prediction" -> RENDER_PREDICTION_KEYS;
            case "prediction.legendConfig" -> RENDER_PREDICTION_SERIES_KEYS;
            case "prediction.legendConfig.history", "prediction.legendConfig.forecast",
                    "prediction.legendConfig.upper", "prediction.legendConfig.lower",
                    "prediction.legendConfig.anomaly" -> RENDER_PREDICTION_LEGEND_ITEM_KEYS;
            case "voiceSummary" -> RENDER_VOICE_SUMMARY_KEYS;
            case "voiceSummary.templates" -> RENDER_VOICE_FIELD_KEYS;
            case "voiceSummary.chartTemplates" -> RENDER_CHART_TYPE_KEYS;
            case "compare" -> RENDER_COMPARE_KEYS;
            case "pagination" -> RENDER_PAGINATION_KEYS;
            case "table" -> RENDER_TABLE_KEYS;
            case "radar" -> RENDER_RADAR_KEYS;
            case "scatter" -> RENDER_SCATTER_KEYS;
            case "metric" -> RENDER_METRIC_KEYS;
            case "map" -> RENDER_MAP_KEYS;
            default -> Set.of();
        };
    }

    private void validateRenderConfigList(List<?> list, String path) {
        if (list.size() > 100) {
            throw new IllegalArgumentException("图表渲染配置数组过长：" + friendlyPath(path) + "，最多支持 100 项");
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> nested) {
                validateRenderConfigMap(mapValue(nested), path + "[]");
            } else if (item instanceof List<?> nestedList) {
                validateRenderConfigList(nestedList, path + "[]");
            }
        }
    }

    private String renderPath(String path, String key) {
        return path == null || path.isBlank() ? key : path + "." + key;
    }

    private String friendlyPath(String path) {
        String value = Objects.toString(path, "").trim();
        if (value.isBlank() || "$".equals(value)) {
            return "根配置";
        }
        return value.startsWith("$.") ? value.substring(2) : value;
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

    private List<?> rawList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private List<String> stringList(Object value, List<String> fallback) {
        if (!(value instanceof List<?> list)) {
            return fallback;
        }
        List<String> result = list.stream()
                .map(item -> Objects.toString(item, "").trim())
                .filter(item -> !item.isBlank())
                .toList();
        return result.isEmpty() ? fallback : result;
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
            throw new IllegalArgumentException("JSON 配置序列化失败，请检查配置内容");
        }
    }

    private int jsonLength(Object value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (Exception ignored) {
            return Objects.toString(value, "").length();
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

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(Objects.toString(value, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object chartOptionValue(Object value, Object fallback) {
        return value == null ? fallback : value;
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = intValue(value, fallback);
        return Math.min(max, Math.max(min, parsed));
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
