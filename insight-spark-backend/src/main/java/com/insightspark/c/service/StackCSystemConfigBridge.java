package com.insightspark.c.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.service.AiChartRuleConfigService;
import com.insightspark.service.DatasourceService;
import com.insightspark.service.PythonAiService;
import com.insightspark.service.SqlAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将 Stack C 配置键与全项目真实配置源（SQL 审计、Neo4j、图表偏好、数据源、Environment 等）双向桥接。
 */
@Service
public class StackCSystemConfigBridge {

    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
            "DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "TRUNCATE");
    private static final List<String> UPLOAD_FORMATS = List.of("xlsx", "xls", "csv");
    private static final long UPLOAD_MAX_BYTES = 100L * 1024 * 1024;

    @Autowired
    private SqlAuditService sqlAuditService;

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private AiChartRuleConfigService aiChartRuleConfigService;

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record BindingMeta(
            String binding,
            String source,
            String note,
            boolean readOnly) {
    }

    public record HydratedField(String value, BindingMeta meta) {
    }

    public Map<String, BindingMeta> bindingCatalog() {
        Map<String, BindingMeta> map = new LinkedHashMap<>();
        for (StackCSystemConfigDefinitions.ConfigDef def : StackCSystemConfigDefinitions.ALL) {
            map.put(def.key(), defaultBinding(def.key()));
        }
        return map;
    }

    public Map<String, HydratedField> hydrateAll(Map<String, String> storedValues) {
        Map<String, HydratedField> out = new LinkedHashMap<>();
        Map<String, Map<String, Object>> rulesByCode = indexRules(sqlAuditService.listRules());
        Map<String, Object> neo4j = safeNeo4j();
        Map<String, Object> chartPrefs = safeChartPrefs();
        Map<String, Object> dsAgg = datasourceAggregates();
        List<Map<String, Object>> aiModels = safeAiModels();

        for (StackCSystemConfigDefinitions.ConfigDef def : StackCSystemConfigDefinitions.ALL) {
            String key = def.key();
            String stored = storedValues.get(key);
            HydratedField field = hydrateOne(key, stored, rulesByCode, neo4j, chartPrefs, dsAgg, aiModels);
            out.put(key, field);
        }
        return out;
    }

    public void apply(String key, String value) {
        String k = Objects.toString(key, "").trim();
        String v = value == null ? "" : value;
        switch (k) {
            case "perf.alert.slow_ms" -> syncSqlRule("SLOW_QUERY", v, true);
            case "perf.slow.query.circuit.enabled" -> sqlAuditService.updateRuleStatus("SLOW_QUERY_BREAKER", bool(v));
            case "perf.slow.query.circuit.threshold" -> syncSqlRule("SLOW_QUERY_BREAKER", v, boolRuleEnabled("SLOW_QUERY_BREAKER"));
            case "security.sql.intercept.enabled" -> {
                boolean on = bool(v);
                sqlAuditService.updateRuleStatus("DANGEROUS_KEYWORD", on);
                sqlAuditService.updateRuleStatus("TABLE_SCOPE", on);
            }
            case "security.sensitive.fields" -> syncSensitiveFields(v);
            case "security.sensitive.mask.rule" -> applyDefaultMaskRule(v);
            case "ai.neo4j.uri", "ai.neo4j.database" -> applyNeo4j(v, k);
            case "interaction.chart.defaultTheme", "interaction.chart.animation" -> applyChartPref(k, v);
            default -> {
                // is_system_config 镜像 + 无需额外落库
            }
        }
    }

    private HydratedField hydrateOne(
            String key,
            String stored,
            Map<String, Map<String, Object>> rulesByCode,
            Map<String, Object> neo4j,
            Map<String, Object> chartPrefs,
            Map<String, Object> dsAgg,
            List<Map<String, Object>> aiModels) {
        return switch (key) {
            case "ai.llm.routing.gateway" -> field(
                    env("insight.ai-service-url", stored),
                    "ENVIRONMENT", "application.yml · insight.ai-service-url · 只读展示", true);
            case "ai.neo4j.uri" -> field(
                    str(neo4j.get("uri"), stored),
                    "NEO4J", "is_neo4j_runtime_config", false);
            case "ai.neo4j.database" -> field(
                    str(neo4j.get("databaseName"), stored),
                    "NEO4J", "is_neo4j_runtime_config", false);
            case "ai.llm.model" -> field(resolveDefaultModel(aiModels, stored),
                    "AI_SERVICE", "Python .env OPENAI_MODEL · 只读展示", true);
            case "ai.llm.provider" -> field(resolveProvider(aiModels, stored),
                    "AI_SERVICE", "Python .env OPENAI_BASE_URL 推断 · 只读展示", true);
            case "ai.llm.temperature" -> field(
                    resolveModelTemperature(aiModels, stored),
                    "AI_SERVICE", "PythonAiService 默认 temperature · 只读展示", true);
            case "ai.llm.maxTokens" -> field(
                    resolveModelMaxTokens(aiModels, stored),
                    "AI_SERVICE", "Python /ai/models 或默认值 · 只读展示", true);
            case "ai.text2sql.prompt" -> field(
                    str(stored, ""),
                    "AI_SERVICE", "Text-to-SQL 提示词 · 推理链路待接入 · 只读展示", true);
            case "security.sql.intercept.enabled" -> field(
                    String.valueOf(ruleEnabled(rulesByCode, "DANGEROUS_KEYWORD")
                            && ruleEnabled(rulesByCode, "TABLE_SCOPE")),
                    "SQL_AUDIT", "is_sql_audit_rule · DANGEROUS_KEYWORD + TABLE_SCOPE", false);
            case "security.sensitive.fields" -> field(
                    serializeSensitiveRules(sqlAuditService.listSensitiveRules()),
                    "SQL_AUDIT", "is_sensitive_field_rule", false);
            case "security.sensitive.mask.rule" -> field(
                    dominantMaskType(sqlAuditService.listSensitiveRules(), stored),
                    "SQL_AUDIT", "is_sensitive_field_rule.mask_type 众数", false);
            case "security.password.minLength" -> field(
                    str(stored, "8"),
                    "RUNTIME_CONFIG", "AuthService 注册校验 · 保存即生效", false);
            case "security.password.requireSpecial" -> field(
                    str(stored, "false"),
                    "RUNTIME_CONFIG", "AuthService 注册校验 · 保存即生效", false);
            case "security.sql.whitelist" -> field(
                    str(stored, "[]"),
                    "RUNTIME_CONFIG", "SqlAuditService 全局表白名单 · 保存即生效", false);
            case "perf.alert.slow_ms" -> field(
                    str(ruleThreshold(rulesByCode, "SLOW_QUERY"), stored),
                    "SQL_AUDIT", "is_sql_audit_rule.SLOW_QUERY（与性能治理同步）", false);
            case "security.dangerous.ops" -> field(
                    toJson(List.of("DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "TRUNCATE", "DELETE FROM")),
                    "RUNTIME", "SqlAuditService.inspect() 硬编码关键字", true);
            case "security.cors.origins" -> field(
                    str(stored, toJson(List.of("http://localhost:5173"))),
                    "RUNTIME_CONFIG", "动态 CORS · 保存即生效", false);
            case "security.api.rate.limit.perMinute" -> field(
                    str(stored, "120"),
                    "RUNTIME_CONFIG", "is_system_config（限流拦截待接入）", false);
            case "perf.redis.cache.enabled" -> field(
                    env("insight.redis.enabled", stored),
                    "ENVIRONMENT", "application.yml · insight.redis.enabled", false);
            case "perf.redis.cache.ttlSeconds" -> field(
                    env("insight.redis.ttl-seconds", stored != null ? stored : "3600"),
                    "ENVIRONMENT", "application.yml · insight.redis.ttl-seconds", false);
            case "perf.slow.query.circuit.enabled" -> field(
                    String.valueOf(ruleEnabled(rulesByCode, "SLOW_QUERY_BREAKER")),
                    "SQL_AUDIT", "is_sql_audit_rule.SLOW_QUERY_BREAKER", false);
            case "perf.slow.query.circuit.threshold" -> field(
                    str(ruleThreshold(rulesByCode, "SLOW_QUERY_BREAKER"), stored),
                    "SQL_AUDIT", "慢查询熔断阈值(ms)", false);
            case "perf.alert.cpu.percent" -> field(
                    stored != null ? stored : env("perf.alert.cpu.percent", "90"),
                    "SYSTEM_CONFIG", "is_system_config · 性能治理已读取", false);
            case "upload.max.fileSizeMb" -> field(
                    str(stored, String.valueOf(UPLOAD_MAX_BYTES / 1024 / 1024)),
                    "RUNTIME_CONFIG", "DataUploadService.validateFile · 保存即生效", false);
            case "upload.allowed.formats" -> field(
                    str(stored, toJson(UPLOAD_FORMATS)),
                    "RUNTIME_CONFIG", "DataUploadService.validateFile · 保存即生效", false);
            case "upload.dedup.enabled" -> field(
                    str(stored, "true"),
                    "RUNTIME_CONFIG", "DataUploadService 去重 · 保存即生效", false);
            case "datasource.pool.maxActive" -> field(
                    str(dsAgg.get("avgPoolMaxSize"), stored),
                    "DATASOURCE", "is_official_datasource.pool_max_size 均值", false);
            case "datasource.connect.timeoutMs" -> field(
                    str(dsAgg.get("avgPoolTimeoutMs"), stored),
                    "DATASOURCE", "is_official_datasource.pool_timeout_ms 均值", false);
            case "datasource.pool.minIdle" -> field("1", "RUNTIME", "OfficialDatasourcePoolManager minimumIdle=1", true);
            case "interaction.chart.defaultTheme" -> field(
                    chartTheme(chartPrefs, stored),
                    "AI_CHART", "ai_chart_style_preference.theme_name", false);
            case "interaction.chart.animation" -> field(
                    chartAnimation(chartPrefs, stored),
                    "AI_CHART", "ai_chart_style_preference.default_options.animation", false);
            case "interaction.chat.historyRetentionDays" -> field(
                    env("insight.chat-history.deleted-retention-days", stored != null ? stored : "30"),
                    "ENVIRONMENT", "application.yml · insight.chat-history.deleted-retention-days", false);
            case "interaction.sse.timeoutSeconds" -> field("120", "RUNTIME", "PythonAiService HTTP 超时 120s", true);
            case "ai.graphrag.topK" -> field(
                    str(stored, "16"),
                    "RUNTIME_CONFIG", "KnowledgeGraphService multiHop · 保存即生效", false);
            case "ai.graphrag.hopDepth" -> field(
                    str(stored, "3"),
                    "RUNTIME_CONFIG", "KnowledgeGraphService multiHop · 保存即生效", false);
            case "notify.alert.push.enabled" -> field(
                    str(stored, String.valueOf(alertPushAvailable())),
                    "RUNTIME_CONFIG", "AdvancedAnalysis 预警 Agent", false);
            case "notify.alert.channels" -> field(
                    str(stored, toJson(alertChannels())),
                    "RUNTIME_CONFIG", "预警推送渠道偏好", false);
            case "notify.anomaly.enabled" -> field(
                    str(stored, "true"),
                    "RUNTIME_CONFIG", "AdvancedAnalysis 定时 Agent · 保存即生效", false);
            case "notify.anomaly.recipients" -> field(
                    str(stored, "[]"),
                    "RUNTIME_CONFIG", "异常告警接收用户 · 保存即生效", false);
            default -> field(
                    stored != null ? stored : "",
                    defaultBinding(key).binding(),
                    defaultBinding(key).source(),
                    defaultBinding(key).readOnly());
        };
    }

    private BindingMeta defaultBinding(String key) {
        if (key.startsWith("notify.") || key.startsWith("upload.") || key.startsWith("datasource.federated")) {
            return new BindingMeta("RUNTIME_CONFIG", "is_system_config", "保存即生效", false);
        }
        if (key.startsWith("ai.graphrag")) {
            return new BindingMeta("RUNTIME_CONFIG", "is_system_config", "保存即生效", false);
        }
        if (key.startsWith("ai.llm.") || "ai.text2sql.prompt".equals(key)) {
            return new BindingMeta("AI_SERVICE", "只读展示", "修改请前往 Python AI 服务或 application.yml", true);
        }
        if (key.startsWith("perf.dashboard") || key.startsWith("interaction.voice")) {
            return new BindingMeta("RUNTIME_CONFIG", "is_system_config", "保存即生效", false);
        }
        return new BindingMeta("SYSTEM_CONFIG", "is_system_config", "全局 KV 存储", false);
    }

    private void syncSqlRule(String ruleCode, String thresholdMs, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        if (thresholdMs != null && !thresholdMs.isBlank()) {
            body.put("thresholdValue", Long.parseLong(thresholdMs.trim()));
        }
        sqlAuditService.updateRuleConfig(ruleCode, body);
    }

    private boolean boolRuleEnabled(String ruleCode) {
        return sqlAuditService.listRules().stream()
                .filter(r -> ruleCode.equals(r.get("ruleCode")))
                .findFirst()
                .map(r -> Boolean.TRUE.equals(r.get("enabled")))
                .orElse(true);
    }

    private void syncSensitiveFields(String json) {
        List<Map<String, Object>> items = parseJsonList(json);
        for (Map<String, Object> item : items) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("fieldKeyword", item.get("fieldKeyword"));
            req.put("maskType", item.getOrDefault("maskType", "MIDDLE"));
            req.put("accessAction", item.getOrDefault("accessAction", "MASK"));
            req.put("enabled", item.getOrDefault("enabled", true));
            sqlAuditService.saveSensitiveRule(req);
        }
    }

    private void applyDefaultMaskRule(String maskRule) {
        List<Map<String, Object>> rules = sqlAuditService.listSensitiveRules();
        String mask = Objects.toString(maskRule, "MASK").trim().toUpperCase(Locale.ROOT);
        for (Map<String, Object> rule : rules) {
            Map<String, Object> req = new LinkedHashMap<>(rule);
            req.put("maskType", mask);
            sqlAuditService.saveSensitiveRule(req);
        }
    }

    private void applyNeo4j(String value, String key) {
        Map<String, Object> req = new LinkedHashMap<>(safeNeo4j());
        if ("ai.neo4j.uri".equals(key)) {
            req.put("uri", value);
        } else {
            req.put("databaseName", value);
        }
        if (!req.containsKey("uri") || Objects.toString(req.get("uri"), "").isBlank()) {
            req.put("uri", "http://localhost:7474");
        }
        datasourceService.saveNeo4jConfig(req);
    }

    private void applyChartPref(String key, String value) {
        Map<String, Object> prefs = new LinkedHashMap<>(safeChartPrefs());
        if ("interaction.chart.defaultTheme".equals(key)) {
            prefs.put("themeName", value);
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> options = prefs.get("defaultOptions") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m)
                    : new LinkedHashMap<>();
            options.put("animation", bool(value));
            prefs.put("defaultOptions", options);
        }
        aiChartRuleConfigService.savePreferences(prefs);
    }

    private Map<String, Map<String, Object>> indexRules(List<Map<String, Object>> rules) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> rule : rules) {
            map.put(Objects.toString(rule.get("ruleCode"), ""), rule);
        }
        return map;
    }

    private Map<String, Object> safeNeo4j() {
        try {
            return new LinkedHashMap<>(datasourceService.getNeo4jConfig());
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> safeChartPrefs() {
        try {
            return new LinkedHashMap<>(aiChartRuleConfigService.getPreferences());
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> safeAiModels() {
        try {
            return pythonAiService.listModels();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> datasourceAggregates() {
        try {
            return jdbcTemplate.queryForMap("""
                    SELECT
                      COALESCE(ROUND(AVG(pool_max_size)), 10) AS avgPoolMaxSize,
                      COALESCE(ROUND(AVG(pool_timeout_ms)), 30000) AS avgPoolTimeoutMs,
                      COUNT(*) AS datasourceCount
                    FROM is_official_datasource
                    WHERE status <> 'DELETED'
                    """);
        } catch (Exception e) {
            return Map.of("avgPoolMaxSize", 10, "avgPoolTimeoutMs", 30000, "datasourceCount", 0);
        }
    }

    private String resolveDefaultModel(List<Map<String, Object>> models, String fallback) {
        if (!models.isEmpty()) {
            Map<String, Object> first = models.get(0);
            Object model = first.get("model");
            if (model == null) {
                model = first.get("name");
            }
            if (model == null) {
                model = first.get("id");
            }
            if (model == null) {
                model = first.get("modelId");
            }
            if (model != null && !String.valueOf(model).isBlank()) {
                return String.valueOf(model);
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : "";
    }

    private String resolveProvider(List<Map<String, Object>> models, String fallback) {
        if (!models.isEmpty()) {
            Object provider = models.get(0).get("provider");
            if (provider != null) {
                return String.valueOf(provider);
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : "openai";
    }

    private String resolveModelTemperature(List<Map<String, Object>> models, String fallback) {
        if (!models.isEmpty()) {
            Object temperature = models.get(0).get("temperature");
            if (temperature != null) {
                return String.valueOf(temperature);
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : "0.2";
    }

    private String resolveModelMaxTokens(List<Map<String, Object>> models, String fallback) {
        if (!models.isEmpty()) {
            Object maxTokens = models.get(0).get("maxTokens");
            if (maxTokens == null) {
                maxTokens = models.get(0).get("max_tokens");
            }
            if (maxTokens != null) {
                return String.valueOf(maxTokens);
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : "4096";
    }

    private String serializeSensitiveRules(List<Map<String, Object>> rules) {
        List<Map<String, Object>> slim = rules.stream().map(r -> Map.<String, Object>of(
                "fieldKeyword", r.get("fieldKeyword"),
                "maskType", r.get("maskType"),
                "accessAction", r.get("accessAction"),
                "enabled", r.get("enabled")
        )).collect(Collectors.toCollection(ArrayList::new));
        return toJson(slim);
    }

    private String dominantMaskType(List<Map<String, Object>> rules, String fallback) {
        if (rules.isEmpty()) {
            return fallback != null ? fallback : "MASK";
        }
        return Objects.toString(rules.get(0).get("maskType"), "MASK");
    }

    private String chartTheme(Map<String, Object> prefs, String fallback) {
        String theme = Objects.toString(prefs.get("themeName"), "").trim();
        if (theme.isBlank()) {
            return fallback != null ? fallback : "light";
        }
        return theme.toLowerCase(Locale.ROOT).contains("dark") ? "dark" : "light";
    }

    private String chartAnimation(Map<String, Object> prefs, String fallback) {
        Object options = prefs.get("defaultOptions");
        if (options instanceof Map<?, ?> map && map.containsKey("animation")) {
            return String.valueOf(map.get("animation"));
        }
        return fallback != null ? fallback : "true";
    }

    private boolean alertPushAvailable() {
        return !env("insight.advanced-alert.dingtalk-webhook", "").isBlank()
                || !env("insight.advanced-alert.email-target", "").isBlank();
    }

    private List<String> alertChannels() {
        List<String> channels = new ArrayList<>();
        if (!env("insight.advanced-alert.dingtalk-webhook", "").isBlank()) {
            channels.add("dingtalk");
        }
        if (!env("insight.advanced-alert.email-target", "").isBlank()) {
            channels.add("email");
        }
        if (channels.isEmpty()) {
            channels.add("webhook");
        }
        return channels;
    }

    private boolean ruleEnabled(Map<String, Map<String, Object>> rules, String code) {
        Map<String, Object> rule = rules.get(code);
        return rule != null && Boolean.TRUE.equals(rule.get("enabled"));
    }

    private String ruleThreshold(Map<String, Map<String, Object>> rules, String code) {
        Map<String, Object> rule = rules.get(code);
        if (rule == null || rule.get("thresholdValue") == null) {
            return null;
        }
        return String.valueOf(rule.get("thresholdValue"));
    }

    private String env(String key, String fallback) {
        return Optional.ofNullable(environment.getProperty(key)).orElse(fallback == null ? "" : fallback);
    }

    private boolean bool(String value) {
        String v = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v);
    }

    private String str(Object value, String fallback) {
        if (value == null) {
            return fallback == null ? "" : fallback;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() && fallback != null ? fallback : s;
    }

    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 格式无效：" + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private HydratedField field(String value, String binding, String source, boolean readOnly) {
        String note = readOnly ? "只读：修改需改对应模块代码或 yml" : "保存后写入真实数据源";
        return new HydratedField(value, new BindingMeta(binding, source, note, readOnly));
    }
}
