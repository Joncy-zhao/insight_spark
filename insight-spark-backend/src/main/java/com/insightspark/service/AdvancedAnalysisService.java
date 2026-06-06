package com.insightspark.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class AdvancedAnalysisService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate pushRestTemplate;
    private volatile boolean alertAgentRunning = false;

    @Value("${insight.advanced-alert.dingtalk-webhook:}")
    private String dingtalkWebhook;

    @Value("${insight.advanced-alert.dingtalk-secret:}")
    private String dingtalkSecret;

    @Value("${insight.advanced-alert.email-target:}")
    private String alertEmailTarget;

    @Value("${insight.advanced-alert.email-from:}")
    private String alertEmailFrom;

    @Value("${insight.advanced-alert.smtp-host:}")
    private String alertSmtpHost;

    @Value("${insight.advanced-alert.smtp-port:587}")
    private int alertSmtpPort;

    @Value("${insight.advanced-alert.smtp-username:}")
    private String alertSmtpUsername;

    @Value("${insight.advanced-alert.smtp-password:}")
    private String alertSmtpPassword;

    @Value("${insight.advanced-alert.smtp-auth:true}")
    private boolean alertSmtpAuth;

    @Value("${insight.advanced-alert.smtp-starttls:true}")
    private boolean alertSmtpStarttls;

    @Value("${insight.advanced-alert.smtp-ssl:false}")
    private boolean alertSmtpSsl;

    @Value("${insight.redis.enabled:true}")
    private boolean redisEnabled;

    @Value("${insight.redis.host:localhost}")
    private String redisHost;

    @Value("${insight.redis.port:6379}")
    private int redisPort;

    @Value("${insight.redis.password:}")
    private String redisPassword;

    @Value("${insight.redis.database:0}")
    private int redisDatabase;

    @Value("${insight.advanced-analysis.forecast-cache-enabled:true}")
    private boolean forecastCacheEnabled;

    @Value("${insight.advanced-analysis.forecast-cache-ttl-seconds:1800}")
    private int forecastCacheTtlSeconds;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private ChatQueryHistoryService chatQueryHistoryService;

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    private AiChartRuleConfigService aiChartRuleConfigService;

    public AdvancedAnalysisService() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        this.pushRestTemplate = new RestTemplate(requestFactory);
    }

    @Scheduled(cron = "${insight.advanced-alert.agent-cron:0 */15 * * * *}")
    public void scheduledAlertRuleDetection() {
        if (alertAgentRunning) {
            return;
        }
        alertAgentRunning = true;
        try {
            runAlertRuleDetection(Map.of("scope", "scheduled"));
        } catch (Exception ignored) {
            // Scheduled detection must not block application runtime.
        } finally {
            alertAgentRunning = false;
        }
    }

    public Map<String, Object> fieldMeta(Map<String, Object> request) {
        String tableName = required(request, "tableName");
        List<Map<String, Object>> fields = dataUploadService.listFields(tableName);
        List<Map<String, Object>> timeFields = fields.stream()
                .filter(this::isDateField)
                .toList();
        List<Map<String, Object>> numericFields = fields.stream()
                .filter(this::isNumericField)
                .toList();
        return Map.of(
                "tableName", tableName,
                "fields", fields,
                "timeFields", timeFields,
                "numericFields", numericFields
        );
    }

    private void attachChartRecommendation(Map<String, Object> result, String question,
                                           List<Map<String, Object>> fields,
                                           List<Map<String, Object>> rows,
                                           String fallbackChartType) {
        try {
            Map<String, Object> recommendation = new LinkedHashMap<>(
                    aiChartRuleConfigService.recommendForChatResult(question, fields, rows));
            recommendation.putIfAbsent("status", "CONFIGURED");
            applyChartRecommendation(result, recommendation);
        } catch (Exception ignored) {
            Map<String, Object> recommendation = new LinkedHashMap<>();
            recommendation.put("chartType", fallbackChartType);
            recommendation.put("ruleCode", "fallback_time_series");
            recommendation.put("ruleName", "时序趋势兜底规则");
            recommendation.put("scenarioType", "TIME_SERIES");
            recommendation.put("status", "FALLBACK");
            recommendation.put("explain", "AI 图表推荐规则暂不可用，已使用时序预测兜底规则渲染历史值、预测值和置信区间。");
            applyChartRecommendation(result, recommendation);
        }
    }

    private void applyChartRecommendation(Map<String, Object> result, Map<String, Object> recommendation) {
        if (recommendation == null || recommendation.isEmpty()) {
            return;
        }
        result.put("chartRecommendation", recommendation);
        result.put("chartRuleCode", recommendation.getOrDefault("ruleCode", ""));
        result.put("chartRuleName", recommendation.getOrDefault("ruleName", ""));
        result.put("chartScenarioType", recommendation.getOrDefault("scenarioType", ""));
        result.put("chartRecommendationStatus", recommendation.getOrDefault("status", ""));
        result.put("chartRecommendationExplain", recommendation.getOrDefault("explain", ""));
        result.put("voiceSummary", recommendation.getOrDefault("voiceSummary", Map.of()));
        Object template = recommendation.get("optionTemplate");
        if (template instanceof Map<?, ?>) {
            result.put("optionTemplate", template);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRows(Object rows) {
        if (rows instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    public Map<String, Object> forecast(Map<String, Object> request) {
        long startedAt = System.currentTimeMillis();
        String tableName = required(request, "tableName");
        String timeField = required(request, "timeField");
        String metricField = required(request, "metricField");
        String granularity = normalizeGranularity(text(request.getOrDefault("granularity", "month")));
        int horizon = parsePositiveInt(request.get("horizon"), 3);
        String algorithm = text(request.getOrDefault("algorithm", "Holt-Winters"));
        String filterExpression = sanitizeFilterExpression(request.get("filterExpression"));
        String resolvedFilterExpression = resolveFilterExpression(tableName, filterExpression);
        ForecastParams params = forecastParams(request);

        validateField(tableName, timeField, true);
        validateField(tableName, metricField, false);

        String cacheKey = forecastCacheKey("table", Map.of(
                "tableName", tableName,
                "timeField", timeField,
                "metricField", metricField,
                "granularity", granularity,
                "horizon", horizon,
                "algorithm", algorithm,
                "filterExpression", resolvedFilterExpression,
                "params", params.toMap()
        ));
        Map<String, Object> cached = readForecastCache(cacheKey);
        if (!cached.isEmpty()) {
            markForecastCacheHit(cached, cacheKey, startedAt);
            attachChartRecommendation(cached, "预测 " + metricField, dataUploadService.listFields(tableName),
                    castRows(cached.get("series")), "line");
            attachAdvancedGraphContext(cached, firstText(request.get("sourceQuestion"), "预测 " + metricField), tableName);
            return cached;
        }

        SeriesPreprocessResult preprocess = preprocessSeries(
                loadSeries(tableName, timeField, metricField, granularity, 240, resolvedFilterExpression),
                granularity
        );
        List<Point> history = preprocess.points();
        if (history.size() < 3) {
            throw new IllegalArgumentException("可用于预测的时间序列不足，至少需要 3 个有效时间点");
        }
        List<Point> forecast = forecastSeries(history, horizon, algorithm, params, granularity);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Point point : history) {
            series.add(row(point.name(), point.value(), null, null, null));
        }
        for (Point point : forecast) {
            double band = Math.max(Math.abs(point.value()) * 0.12D, standardDeviation(history) * 1.2D);
            series.add(row(point.name(), null, round(point.value()), round(point.value() + band), round(point.value() - band)));
        }
        double last = forecast.get(forecast.size() - 1).value();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "forecast");
        result.put("tableName", tableName);
        result.put("metricField", metricField);
        result.put("timeField", timeField);
        result.put("filterExpression", filterExpression);
        result.put("resolvedFilterExpression", resolvedFilterExpression);
        result.put("granularity", granularity);
        result.put("algorithm", algorithm);
        result.put("algorithmParams", params.toMap());
        result.put("confidence", "95%");
        result.put("series", attachAnomalyPoints(series, rawAnomalyValues(preprocess)));
        result.put("cacheHit", false);
        result.put("cacheKey", cacheKey);
        result.put("dataQuality", dataQuality(history, preprocess));
        result.put("insights", List.of(
                Map.of("label", "历史点数", "value", history.size()),
                Map.of("label", "预测点数", "value", forecast.size()),
                Map.of("label", "末期预测", "value", round(last))
        ));
        result.put("explanation", forecastExplanation(algorithm, granularity, history, forecast, params, "真实数据源", preprocess));
        result.put("executionTimeMs", elapsedMs(startedAt));
        attachChartRecommendation(result, "预测 " + metricField, dataUploadService.listFields(tableName), series, "line");
        attachAdvancedGraphContext(result, firstText(request.get("sourceQuestion"), "预测 " + metricField), tableName);
        writeForecastCache(cacheKey, result);
        return result;
    }

    public Map<String, Object> forecastFromSeries(Map<String, Object> request) {
        long startedAt = System.currentTimeMillis();
        String tableName = text(request.get("tableName"));
        String metric = text(request.getOrDefault("metric", "核心指标"));
        int horizon = parsePositiveInt(request.get("horizon"), 3);
        String algorithm = text(request.getOrDefault("algorithm", "Holt-Winters"));
        ForecastParams params = forecastParams(request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputRows = request.get("series") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        List<Point> rawHistory = inputRows.stream()
                .map(row -> new Point(firstText(
                        row.get("name"), row.get("bucket_name"), row.get("dim_name"), row.get("dimension")),
                        parseDouble(firstText(
                                row.get("value"),
                                row.get("history"),
                                row.get("metric_value"),
                                row.get("metric"),
                                row.get("amount"),
                                row.get("total")), Double.NaN)))
                .filter(point -> !point.name().isBlank() && !Double.isNaN(point.value()))
                .toList();
        String inferredGranularity = inferGranularity(rawHistory);
        SeriesPreprocessResult preprocess = preprocessSeries(rawHistory, inferredGranularity);
        List<Point> history = preprocess.points();
        if (history.size() < 3) {
            throw new IllegalArgumentException("上一轮查询结果不足，至少需要 3 个有效时间点才能预测");
        }
        String cacheKey = forecastCacheKey("series", Map.of(
                "tableName", tableName,
                "metric", metric,
                "horizon", horizon,
                "algorithm", algorithm,
                "params", params.toMap(),
                "history", history.stream().map(point -> Map.of("name", point.name(), "value", round(point.value()))).toList()
        ));
        Map<String, Object> cached = readForecastCache(cacheKey);
        if (!cached.isEmpty()) {
            markForecastCacheHit(cached, cacheKey, startedAt);
            attachChartRecommendation(cached, "预测 " + metric, List.of(
                    Map.of("name", "query_result_dimension", "columnName", "query_result_dimension", "type", "date", "sourceFieldName", "查询结果维度"),
                    Map.of("name", metric, "columnName", metric, "type", "number", "sourceFieldName", metric)
            ), castRows(cached.get("series")), "line");
            attachAdvancedGraphContext(cached, firstText(request.get("sourceQuestion"), "预测 " + metric), tableName);
            return cached;
        }
        List<Point> forecast = forecastSeries(history, horizon, algorithm, params, inferredGranularity);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Point point : history) {
            series.add(row(point.name(), round(point.value()), null, null, null));
        }
        for (Point point : forecast) {
            double band = Math.max(Math.abs(point.value()) * 0.12D, standardDeviation(history) * 1.2D);
            series.add(row(point.name(), null, round(point.value()), round(point.value() + band), round(point.value() - band)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "forecast");
        result.put("tableName", tableName);
        result.put("metricField", metric);
        result.put("timeField", "query_result_dimension");
        result.put("granularity", "query-result");
        result.put("algorithm", algorithm);
        result.put("algorithmParams", params.toMap());
        result.put("confidence", "95%");
        result.put("series", attachAnomalyPoints(series, rawAnomalyValues(preprocess)));
        result.put("cacheHit", false);
        result.put("cacheKey", cacheKey);
        result.put("dataQuality", dataQuality(history, preprocess));
        result.put("insights", List.of(
                Map.of("label", "真实序列点数", "value", history.size()),
                Map.of("label", "预测点数", "value", forecast.size()),
                Map.of("label", "数据来源", "value", "上一轮查询结果")
        ));
        result.put("explanation", forecastExplanation(algorithm, inferredGranularity, history, forecast, params, "上一轮查询结果", preprocess));
        result.put("executionTimeMs", elapsedMs(startedAt));
        attachChartRecommendation(result, "预测 " + metric, List.of(
                Map.of("name", "query_result_dimension", "columnName", "query_result_dimension", "type", "date", "sourceFieldName", "查询结果维度"),
                Map.of("name", metric, "columnName", metric, "type", "number", "sourceFieldName", metric)
        ), series, "line");
        attachAdvancedGraphContext(result, firstText(request.get("sourceQuestion"), "预测 " + metric), tableName);
        writeForecastCache(cacheKey, result);
        return result;
    }

    public Map<String, Object> whatIf(Map<String, Object> request) {
        String tableName = required(request, "tableName");
        String targetMetric = required(request, "targetMetric");
        validateField(tableName, targetMetric, false);
        rejectUnsafeFormulaInstruction(request.get("sourceQuestion"));
        String formula = sanitizeFormulaExpression(request.get("formula"));
        FormulaPlan formulaPlan = formula.isBlank() ? null : buildFormulaPlan(tableName, formula);
        String formulaScope = formulaPlan == null ? "" : normalizeFormulaScope(request.get("formulaScope"));
        List<Map<String, Double>> formulaRows = formulaPlan != null && "row".equals(formulaScope)
                ? loadFormulaRows(tableName, formulaPlan)
                : List.of();
        List<Map<String, Object>> tableFields = formulaPlan == null ? dataUploadService.listFields(tableName) : formulaPlan.fields();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = request.get("variables") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        if (variables.isEmpty()) {
            throw new IllegalArgumentException("请至少配置一个推演变量");
        }

        double base = formulaPlan == null
                ? loadMetricAverage(tableName, targetMetric)
                : formulaBaseValue(formulaPlan, formulaRows, formulaScope);
        List<Map<String, Object>> normalizedVariables = new ArrayList<>();
        for (Map<String, Object> variable : variables) {
            String requestedField = text(variable.getOrDefault("field", variable.getOrDefault("name", "")));
            String variableName = text(variable.getOrDefault("name", requestedField));
            String field = formulaPlan == null
                    ? requestedField
                    : resolveFormulaVariableKey(formulaPlan, requestedField, variableName);
            if (field.isBlank()) {
                continue;
            }
            double change = parseDouble(variable.getOrDefault("change", variable.getOrDefault("changePercent", 0D)), 0D);
            String mode = normalizeWhatIfMode(text(variable.getOrDefault("mode", "percent")));
            double minValue = parseDouble(variable.get("min"), Double.NaN);
            double maxValue = parseDouble(variable.get("max"), Double.NaN);
            boolean sourceNumericField = isNumericColumn(tableFields, field);
            if (formulaPlan == null || !formulaPlan.baseValues().containsKey(field)) {
                if (!sourceNumericField) {
                    continue;
                }
                validateField(tableName, field, false);
            }
            double currentValue = formulaPlan != null && formulaPlan.baseValues().containsKey(field)
                    ? formulaPlan.baseValues().get(field)
                    : loadMetricAverage(tableName, field);
            double targetValue = applyWhatIfChangeValue(currentValue, change, mode, minValue, maxValue);
            double normalizedChange = normalizedChangeRate(currentValue, targetValue, change);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("name", variableName.isBlank() ? field : variableName);
            item.put("mode", mode);
            item.put("change", change);
            item.put("normalizedChange", round(normalizedChange));
            item.put("currentValue", round(currentValue));
            item.put("targetValue", round(targetValue));
            item.put("min", Double.isNaN(minValue) ? null : round(minValue));
            item.put("max", Double.isNaN(maxValue) ? null : round(maxValue));
            item.put("estimatedCorrelation", 0D);
            normalizedVariables.add(item);
        }
        if (formulaPlan != null) {
            appendMissingFormulaVariables(formulaPlan, normalizedVariables);
        }
        if (normalizedVariables.isEmpty()) {
            throw new IllegalArgumentException("推演变量未能匹配到有效数值字段");
        }

        if (formulaPlan != null) {
            enrichFormulaVariableImpacts(formulaPlan, normalizedVariables, base, formulaRows, formulaScope);
        }
        double conservative;
        double scenario;
        double optimistic;
        RegressionFit regressionFit = null;
        if (formulaPlan == null) {
            regressionFit = fitWhatIfRegression(tableName, targetMetric, normalizedVariables, base);
            enrichRegressionVariableImpacts(regressionFit, normalizedVariables, base);
            conservative = regressionScenarioValue(regressionFit, normalizedVariables, 0.5D);
            scenario = regressionScenarioValue(regressionFit, normalizedVariables, 1D);
            optimistic = regressionScenarioValue(regressionFit, normalizedVariables, 1.35D);
        } else {
            conservative = formulaScenarioValue(formulaPlan, normalizedVariables, 0.5D, formulaRows, formulaScope);
            scenario = formulaScenarioValue(formulaPlan, normalizedVariables, 1D, formulaRows, formulaScope);
            optimistic = formulaScenarioValue(formulaPlan, normalizedVariables, 1.35D, formulaRows, formulaScope);
        }
        double recommended = Math.max(Math.max(conservative, scenario), optimistic);
        List<Map<String, Object>> series = List.of(
                Map.of("name", "基准方案", "value", round(base)),
                Map.of("name", "保守方案", "value", round(conservative)),
                Map.of("name", "中性方案", "value", round(scenario)),
                Map.of("name", "乐观方案", "value", round(optimistic)),
                Map.of("name", "推荐方案", "value", round(recommended))
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "whatIf");
        result.put("tableName", tableName);
        result.put("targetMetric", targetMetric);
        result.put("formula", formula);
        result.put("resolvedFormula", formulaPlan == null ? "" : formulaPlan.resolvedExpression());
        result.put("formulaScope", formulaPlan == null ? "" : formulaScope);
        result.put("calculationMode", formulaPlan == null ? "regression" : "formula");
        result.put("variables", normalizedVariables);
        result.put("series", series);
        List<Map<String, Object>> insights = new ArrayList<>();
        insights.add(Map.of("label", "模拟变化", "value", round(base == 0D ? 0D : (scenario - base) / base * 100D) + "%"));
        insights.add(Map.of("label", "变量数量", "value", normalizedVariables.size()));
        insights.add(Map.of("label", "场景数量", "value", 3));
        insights.add(Map.of("label", "计算方式", "value", formulaPlan == null
                ? "多变量岭回归拟合 + 多场景推演"
                : ("row".equals(formulaScope) ? "业务公式按行推演 + 多场景拟合" : "业务公式聚合推演 + 多场景拟合")));
        if (formulaPlan != null) {
            insights.add(Map.of("label", "公式口径", "value", "row".equals(formulaScope) ? "逐行计算后求平均" : "字段均值计算"));
        }
        result.put("insights", insights);
        result.put("fitQuality", regressionFit == null ? Map.of() : regressionFit.quality());
        result.put("explanation", whatIfExplanation(base, conservative, scenario, optimistic, recommended, normalizedVariables, formulaPlan, regressionFit, formulaScope));
        attachAdvancedGraphContext(result, firstText(request.get("sourceQuestion"), "推演 " + targetMetric), tableName);
        return result;
    }

    public Map<String, Object> saveAlertRule(Map<String, Object> request) {
        String tableName = required(request, "tableName");
        String metricField = required(request, "metricField");
        String timeField = required(request, "timeField");
        String granularity = normalizeGranularity(text(request.getOrDefault("granularity", "day")));
        String operator = normalizeAlertOperator(text(request.getOrDefault("operator", "lt")));
        Double threshold = "zscore".equals(operator) ? null : parseDouble(request.get("threshold"), Double.NaN);
        String detectionCycle = normalizeDetectionCycle(text(request.getOrDefault("detectionCycle", "daily")));
        String filterExpression = sanitizeFilterExpression(request.get("filterExpression"));
        String resolvedFilterExpression = resolveFilterExpression(tableName, filterExpression);
        List<String> channels = normalizeChannels(request.get("channels"));

        assertCanAccessAdvancedTable(tableName);
        validateField(tableName, metricField, false);
        validateField(tableName, timeField, true);
        if (!"zscore".equals(operator) && (threshold == null || Double.isNaN(threshold))) {
            throw new IllegalArgumentException("阈值预警需要填写有效阈值");
        }
        String ruleName = alertRuleNameFromRequest(request, tableName, metricField, operator, threshold, filterExpression);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_advanced_alert_rule(
                      user_id, org_scope, rule_name, table_name, metric_field, time_field, granularity,
                      filter_expression, resolved_filter_expression, operator, threshold_value,
                      detection_cycle, channels_json, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, AuthContext.userId());
            ps.setString(2, currentOrgScope());
            ps.setString(3, ruleName);
            ps.setString(4, tableName);
            ps.setString(5, metricField);
            ps.setString(6, timeField);
            ps.setString(7, granularity);
            ps.setString(8, filterExpression);
            ps.setString(9, resolvedFilterExpression);
            ps.setString(10, operator);
            if (threshold == null || Double.isNaN(threshold)) {
                ps.setObject(11, null);
            } else {
                ps.setDouble(11, threshold);
            }
            ps.setString(12, detectionCycle);
            ps.setString(13, toJson(channels));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        return alertRuleDetail(id);
    }

    public List<Map<String, Object>> listAlertRules() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, org_scope AS orgScope,
                       rule_name AS ruleName, table_name AS tableName, metric_field AS metricField,
                       time_field AS timeField, granularity, filter_expression AS filterExpression,
                       resolved_filter_expression AS resolvedFilterExpression, operator,
                       threshold_value AS threshold, detection_cycle AS detectionCycle,
                       channels_json AS channelsJson, status, last_checked_at AS lastCheckedAt,
                       COALESCE(last_triggered_at, (
                         SELECT MAX(e.created_at)
                         FROM is_advanced_alert_event e
                         WHERE e.rule_id = is_advanced_alert_rule.id
                       )) AS lastTriggeredAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_advanced_alert_rule
                WHERE user_id = ? OR ? = 'ADMIN'
                ORDER BY updated_at DESC, id DESC
                LIMIT 200
                """, AuthContext.userId(), AuthContext.role());
        rows.forEach(this::parseAlertRuleJsonFields);
        return rows;
    }

    public Map<String, Object> getAlertRule(long id) {
        return alertRuleDetail(id);
    }

    public Map<String, Object> updateAlertRule(long id, Map<String, Object> request) {
        Map<String, Object> current = alertRuleDetail(id);
        String tableName = text(request.getOrDefault("tableName", current.get("tableName")));
        String metricField = text(request.getOrDefault("metricField", current.get("metricField")));
        String timeField = text(request.getOrDefault("timeField", current.get("timeField")));
        String granularity = normalizeGranularity(text(request.getOrDefault("granularity", current.get("granularity"))));
        String operator = normalizeAlertOperator(text(request.getOrDefault("operator", current.get("operator"))));
        Object thresholdInput = request.containsKey("threshold") ? request.get("threshold") : current.get("threshold");
        Double threshold = "zscore".equals(operator) ? null : parseDouble(thresholdInput, Double.NaN);
        String detectionCycle = normalizeDetectionCycle(text(request.getOrDefault("detectionCycle", current.get("detectionCycle"))));
        String filterExpression = sanitizeFilterExpression(request.getOrDefault("filterExpression", current.get("filterExpression")));
        String resolvedFilterExpression = resolveFilterExpression(tableName, filterExpression);
        List<String> channels = request.containsKey("channels")
                ? normalizeChannels(request.get("channels"))
                : normalizeChannels(current.get("channels"));
        String status = normalizeAlertRuleStatus(text(request.getOrDefault("status", current.get("status"))));
        String nextOrgScope = AuthContext.userId().equals(text(current.get("userId")))
                ? currentOrgScope()
                : orgScopeFromRule(current);

        assertCanAccessAdvancedTable(tableName);
        validateField(tableName, metricField, false);
        validateField(tableName, timeField, true);
        if (!"zscore".equals(operator) && (threshold == null || Double.isNaN(threshold))) {
            throw new IllegalArgumentException("阈值预警需要填写有效阈值");
        }
        String ruleName = alertRuleNameFromRequestWithFallback(
                request,
                current.get("ruleName"),
                tableName,
                metricField,
                operator,
                threshold,
                filterExpression
        );

        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_alert_rule
                SET org_scope = ?, rule_name = ?, table_name = ?, metric_field = ?, time_field = ?, granularity = ?,
                    filter_expression = ?, resolved_filter_expression = ?, operator = ?,
                    threshold_value = ?, detection_cycle = ?, channels_json = CAST(? AS JSON),
                    status = ?
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """,
                nextOrgScope, ruleName, tableName, metricField, timeField, granularity,
                filterExpression, resolvedFilterExpression, operator,
                threshold == null || Double.isNaN(threshold) ? null : threshold,
                detectionCycle, toJson(channels), status,
                id, AuthContext.userId(), AuthContext.role());
        if (updated <= 0) {
            throw new IllegalArgumentException("预警规则不存在或无权操作");
        }
        return alertRuleDetail(id);
    }

    public Map<String, Object> updateAlertRuleStatus(long id, Map<String, Object> request) {
        String status = normalizeAlertRuleStatus(text(request.getOrDefault("status", "ACTIVE")));
        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_alert_rule
                SET status = ?
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """, status, id, AuthContext.userId(), AuthContext.role());
        if (updated <= 0) {
            throw new IllegalArgumentException("预警规则不存在或无权操作");
        }
        return alertRuleDetail(id);
    }

    public Map<String, Object> deleteAlertRule(long id) {
        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_alert_rule
                SET status = 'DELETED'
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """, id, AuthContext.userId(), AuthContext.role());
        if (updated <= 0) {
            throw new IllegalArgumentException("预警规则不存在或无权操作");
        }
        return Map.of("id", id, "status", "DELETED");
    }

    public Map<String, Object> runAlertRuleDetection(Map<String, Object> request) {
        long ruleId = parseLong(request.get("ruleId"), 0L);
        boolean scheduled = "scheduled".equalsIgnoreCase(text(request.get("scope"))) && ruleId <= 0;
        boolean force = parseBoolean(request.get("force"), false) || ruleId > 0 || !scheduled;
        List<Map<String, Object>> rules = loadActiveAlertRules(ruleId, scheduled);
        int checked = 0;
        int skipped = 0;
        int created = 0;
        int refreshed = 0;
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            if (!force && !alertRuleDue(rule, LocalDateTime.now())) {
                skipped += 1;
                continue;
            }
            checked += 1;
            try {
                List<Map<String, Object>> ruleEvents = withAlertRulePrincipal(rule, () -> detectAlertRule(rule));
                markAlertRuleChecked(rule, !ruleEvents.isEmpty());
                for (Map<String, Object> event : ruleEvents) {
                    if ("refreshed".equals(text(event.get("eventAction")))) {
                        refreshed += 1;
                    } else {
                        created += 1;
                    }
                }
                events.addAll(ruleEvents);
            } catch (Exception ignored) {
                markAlertRuleChecked(rule, false);
                // A single invalid rule should not stop other rules.
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedRules", checked);
        result.put("skippedRules", skipped);
        result.put("createdEvents", created);
        result.put("refreshedEvents", refreshed);
        result.put("scope", scheduled ? "scheduled" : "manual");
        result.put("force", force);
        result.put("events", events);
        return result;
    }

    public Map<String, Object> alertPushConfigStatus() {
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("channel", "email");
        email.put("available", emailPushConfigured());
        email.put("target", maskPushTarget(alertEmailTarget));
        email.put("message", emailPushConfigured()
                ? "已配置 SMTP 邮件推送"
                : "未完整配置 SMTP：需配置 email-target、smtp-host、smtp-username、smtp-password");
        Map<String, Object> dingtalk = new LinkedHashMap<>();
        dingtalk.put("channel", "dingtalk");
        dingtalk.put("available", !text(dingtalkWebhook).isBlank());
        dingtalk.put("target", maskPushTarget(dingtalkWebhook));
        dingtalk.put("signed", !text(dingtalkSecret).isBlank());
        dingtalk.put("message", text(dingtalkWebhook).isBlank()
                ? "未配置 insight.advanced-alert.dingtalk-webhook"
                : (text(dingtalkSecret).isBlank() ? "已配置钉钉 Webhook" : "已配置钉钉 Webhook 与加签密钥"));
        return Map.of(
                "email", email,
                "dingtalk", dingtalk
        );
    }

    public List<Map<String, Object>> listAlertEvents(Map<String, Object> request) {
        long ruleId = parseLong(request.get("ruleId"), 0L);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, rule_id AS ruleId, user_id AS userId, org_scope AS orgScope,
                       table_name AS tableName,
                       metric_field AS metricField, time_field AS timeField, bucket_name AS bucketName,
                       actual_value AS actualValue, threshold_value AS threshold, operator, z_score AS zScore,
                       baseline_value AS baselineValue, deviation_rate AS deviationRate, reason,
                       chart_snapshot_json AS chartSnapshotJson, llm_explanation_json AS llmExplanationJson,
                       explanation_note AS explanationNote, explanation_updated_at AS explanationUpdatedAt,
                       status, ack_by AS ackBy,
                       ack_at AS ackAt, closed_by AS closedBy, closed_at AS closedAt,
                       handle_note AS handleNote, status_updated_at AS statusUpdatedAt,
                       created_at AS createdAt
                FROM is_advanced_alert_event
                WHERE (user_id = ? OR ? = 'ADMIN')
                """);
        args.add(AuthContext.userId());
        args.add(AuthContext.role());
        if (ruleId > 0) {
            sql.append(" AND rule_id = ?");
            args.add(ruleId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT 200");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        rows.forEach(this::parseAlertEventJsonFields);
        return rows;
    }

    public Map<String, Object> getAlertEvent(long id) {
        return alertEventDetail(id);
    }

    public List<Map<String, Object>> listAlertPushLogs(Map<String, Object> request) {
        long eventId = parseLong(request.get("eventId"), 0L);
        long ruleId = parseLong(request.get("ruleId"), 0L);
        String status = text(request.get("status")).toUpperCase(Locale.ROOT);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_id AS eventId, rule_id AS ruleId, user_id AS userId, org_scope AS orgScope,
                       channel, status, attempt_count AS attemptCount, target, title, content,
                       error_message AS errorMessage, request_json AS requestJson, response_json AS responseJson,
                       last_attempt_at AS lastAttemptAt, next_retry_at AS nextRetryAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_advanced_alert_push_log
                WHERE (user_id = ? OR ? = 'ADMIN')
                """);
        args.add(AuthContext.userId());
        args.add(AuthContext.role());
        if (eventId > 0) {
            sql.append(" AND event_id = ?");
            args.add(eventId);
        }
        if (ruleId > 0) {
            sql.append(" AND rule_id = ?");
            args.add(ruleId);
        }
        if (List.of("PENDING", "SUCCESS", "FAILED").contains(status)) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT 200");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        rows.forEach(this::parseAlertPushLogJsonFields);
        return rows;
    }

    public Map<String, Object> retryAlertPushLog(long id) {
        Map<String, Object> log = alertPushLogDetail(id);
        Map<String, Object> event = alertEventDetail(parseLong(log.get("eventId"), 0L));
        Map<String, Object> rule = alertRuleDetail(parseLong(log.get("ruleId"), 0L));
        refreshAlertPushLogContent(log, rule, event);
        Map<String, Object> result = attemptAlertPush(log, event);
        return alertPushLogDetail(parseLong(result.get("id"), id));
    }

    public Map<String, Object> updateAlertEventStatus(long id, Map<String, Object> request) {
        String status = normalizeAlertEventStatus(text(request.getOrDefault("status", request.get("action"))));
        String note = truncate(text(request.getOrDefault("handleNote", request.getOrDefault("note", ""))), 1000);
        int hasExplanationNote = request.containsKey("explanationNote") ? 1 : 0;
        String explanationNote = truncate(text(request.get("explanationNote")), 1000);
        String userId = AuthContext.userId();
        int updated;
        if ("ACK".equals(status)) {
            updated = jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET status = 'ACK', ack_by = ?, ack_at = CURRENT_TIMESTAMP,
                        handle_note = CASE WHEN ? = '' THEN handle_note ELSE ? END,
                        explanation_note = CASE WHEN ? = 0 THEN explanation_note ELSE ? END,
                        explanation_updated_at = CASE WHEN ? = 0 THEN explanation_updated_at ELSE CURRENT_TIMESTAMP END,
                        status_updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status <> 'CLOSED' AND (user_id = ? OR ? = 'ADMIN')
                    """, userId, note, note, hasExplanationNote, explanationNote, hasExplanationNote, id, AuthContext.userId(), AuthContext.role());
        } else if ("CLOSED".equals(status)) {
            updated = jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET status = 'CLOSED',
                        ack_by = COALESCE(ack_by, ?),
                        ack_at = COALESCE(ack_at, CURRENT_TIMESTAMP),
                        closed_by = ?, closed_at = CURRENT_TIMESTAMP,
                        handle_note = CASE WHEN ? = '' THEN handle_note ELSE ? END,
                        explanation_note = CASE WHEN ? = 0 THEN explanation_note ELSE ? END,
                        explanation_updated_at = CASE WHEN ? = 0 THEN explanation_updated_at ELSE CURRENT_TIMESTAMP END,
                        status_updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                    """, userId, userId, note, note, hasExplanationNote, explanationNote, hasExplanationNote, id, AuthContext.userId(), AuthContext.role());
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET status = 'OPEN', closed_by = NULL, closed_at = NULL,
                        handle_note = CASE WHEN ? = '' THEN handle_note ELSE ? END,
                        explanation_note = CASE WHEN ? = 0 THEN explanation_note ELSE ? END,
                        explanation_updated_at = CASE WHEN ? = 0 THEN explanation_updated_at ELSE CURRENT_TIMESTAMP END,
                        status_updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                    """, note, note, hasExplanationNote, explanationNote, hasExplanationNote, id, AuthContext.userId(), AuthContext.role());
        }
        if (updated <= 0) {
            throw new IllegalArgumentException("预警事件不存在、已关闭或无权操作");
        }
        return alertEventDetail(id);
    }

    public Map<String, Object> saveAlertEventExplanation(long id, Map<String, Object> request) {
        Map<String, Object> explanation = normalizeExplanation(
                asJsonObject(request.get("explanation")),
                "rule",
                "规则解释"
        );
        String note = truncate(text(request.getOrDefault("explanationNote", request.get("note"))), 1000);
        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_alert_event
                SET llm_explanation_json = CAST(? AS JSON),
                    explanation_note = CASE WHEN ? = '' THEN explanation_note ELSE ? END,
                    explanation_updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """, toJson(explanation), note, note, id, AuthContext.userId(), AuthContext.role());
        if (updated <= 0) {
            throw new IllegalArgumentException("预警事件不存在或无权操作");
        }
        return alertEventDetail(id);
    }

    public Map<String, Object> savePlan(Map<String, Object> request) {
        String planType = normalizePlanType(text(request.getOrDefault("planType", request.get("type"))));
        String rawPlanName = text(request.getOrDefault("planName", request.getOrDefault("title", "")));
        String planName = rawPlanName.isBlank()
                ? ("forecast".equals(planType) ? "时序预测方案" : "What-if 推演方案")
                : rawPlanName;
        String tableName = text(request.get("tableName"));
        String metricLabel = text(request.getOrDefault("metricLabel", request.get("metric")));
        String timeRangeLabel = text(request.getOrDefault("timeRangeLabel", request.get("timeRange")));
        Map<String, Object> requestJson = asJsonObject(request.get("request"));
        Map<String, Object> resultJson = asJsonObject(request.get("result"));
        Map<String, Object> llmJson = asJsonObject(request.get("llm"));
        Map<String, Object> fieldMappingJson = normalizePlanFieldMapping(
                planType,
                requestJson,
                resultJson,
                asJsonObject(request.get("fieldMapping"))
        );
        if (requestJson.isEmpty() && resultJson.isEmpty()) {
            throw new IllegalArgumentException("方案缺少可保存的参数或结果");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_advanced_analysis_plan(
                      user_id, plan_type, plan_name, table_name, metric_label, time_range_label,
                      status, request_json, result_json, llm_json, field_mapping_json, version_no, last_calculated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'SAVED', CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), 1, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, AuthContext.userId());
            ps.setString(2, planType);
            ps.setString(3, truncate(planName, 200));
            ps.setString(4, tableName.isBlank() ? null : tableName);
            ps.setString(5, metricLabel.isBlank() ? null : truncate(metricLabel, 200));
            ps.setString(6, timeRangeLabel.isBlank() ? null : truncate(timeRangeLabel, 200));
            ps.setString(7, toJson(requestJson));
            ps.setString(8, toJson(resultJson));
            ps.setString(9, toJson(llmJson));
            ps.setString(10, toJson(fieldMappingJson));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        Map<String, Object> detail = planDetail(id);
        attachPinnableChartHistory(detail);
        insertPlanVersionSnapshot(detail);
        return detail;
    }

    public List<Map<String, Object>> listPlans(Map<String, Object> request) {
        String planType = normalizeOptionalPlanType(text(request.get("planType")));
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id AS userId, plan_type AS planType, plan_name AS planName,
                       table_name AS tableName, metric_label AS metricLabel,
                       time_range_label AS timeRangeLabel, status, version_no AS versionNo,
                       last_calculated_at AS lastCalculatedAt, created_at AS createdAt, updated_at AS updatedAt,
                       request_json AS requestJson, result_json AS resultJson, llm_json AS llmJson,
                       field_mapping_json AS fieldMappingJson
                FROM is_advanced_analysis_plan
                WHERE status <> 'DELETED' AND (user_id = ? OR ? = 'ADMIN')
                """);
        args.add(AuthContext.userId());
        args.add(AuthContext.role());
        if (!planType.isBlank()) {
            sql.append(" AND plan_type = ?");
            args.add(planType);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT 200");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        rows.forEach(this::parsePlanJsonFields);
        return rows;
    }

    public Map<String, Object> getPlan(long id) {
        return planDetail(id);
    }

    public Map<String, Object> deletePlan(long id) {
        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_analysis_plan
                SET status = 'DELETED'
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """, id, AuthContext.userId(), AuthContext.role());
        if (updated <= 0) {
            throw new IllegalArgumentException("方案不存在或无权操作");
        }
        return Map.of("id", id, "status", "DELETED");
    }

    public Map<String, Object> renamePlan(long id, Map<String, Object> request) {
        String planName = text(request.getOrDefault("planName", request.get("name")));
        if (planName.isBlank()) {
            throw new IllegalArgumentException("方案名称不能为空");
        }
        insertPlanVersionSnapshot(planDetail(id));
        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_analysis_plan
                SET plan_name = ?, version_no = version_no + 1
                WHERE id = ? AND status <> 'DELETED' AND (user_id = ? OR ? = 'ADMIN')
                """, truncate(planName, 200), id, AuthContext.userId(), AuthContext.role());
        if (updated <= 0) {
            throw new IllegalArgumentException("方案不存在或无权重命名");
        }
        Map<String, Object> detail = planDetail(id);
        insertPlanVersionSnapshot(detail);
        return detail;
    }

    public Map<String, Object> recalculatePlan(long id) {
        Map<String, Object> plan = planDetail(id);
        insertPlanVersionSnapshot(plan);
        String planType = text(plan.get("planType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> requestJson = plan.get("request") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        if (requestJson.isEmpty()) {
            throw new IllegalArgumentException("方案缺少复算参数");
        }
        Map<String, Object> result = "forecast".equals(planType)
                ? (requestJson.get("series") instanceof List<?> ? forecastFromSeries(requestJson) : forecast(requestJson))
                : whatIf(requestJson);
        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_analysis_plan
                SET result_json = CAST(? AS JSON),
                    field_mapping_json = CAST(? AS JSON),
                    version_no = version_no + 1,
                    last_calculated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """,
                toJson(result),
                toJson(normalizePlanFieldMapping(
                        planType,
                        requestJson,
                        result,
                        asJsonObject(plan.get("fieldMapping"))
                )),
                id,
                AuthContext.userId(),
                AuthContext.role());
        if (updated <= 0) {
            throw new IllegalArgumentException("方案不存在或无权复算");
        }
        Map<String, Object> detail = planDetail(id);
        if ("forecast".equals(planType)) {
            attachPinnableChartHistory(detail);
        }
        insertPlanVersionSnapshot(detail);
        return detail;
    }

    public Map<String, Object> recalculatePlanForAdminHistory(long id, Map<String, Object> originDetail) {
        Map<String, Object> detail = recalculatePlan(id);
        Map<String, Object> result = asJsonObject(detail.get("result"));
        long newHistoryId = parseLong(firstText(result.get("queryHistoryId"), result.get("chartId")), 0L);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", id);
        response.put("planName", detail.get("planName"));
        response.put("analysisType", detail.get("planType"));
        response.put("newHistoryId", newHistoryId);
        response.put("engine", "advanced-analysis");
        response.put("chartType", "forecast".equals(text(detail.get("planType"))) ? "line" : "bar");
        response.put("riskLevel", "SAFE");
        response.put("cacheHit", result.get("cacheHit"));
        response.put("message", "高级分析复跑完成");
        response.put("result", result);
        response.put("originHistoryId", originDetail == null ? null : originDetail.get("id"));
        return response;
    }

    public Map<String, Object> recalculateForecastSnapshotForAdminHistory(Map<String, Object> originDetail) {
        Map<String, Object> snapshot = asJsonObject(originDetail == null ? null : originDetail.get("chartSnapshot"));
        Map<String, Object> meta = asJsonObject(snapshot.get("forecastMeta"));
        Map<String, Object> mapping = asJsonObject(snapshot.get("fieldMapping"));
        String tableName = firstText(originDetail == null ? null : originDetail.get("queryTableName"), snapshot.get("tableName"), meta.get("tableName"), mapping.get("tableName"));
        String timeField = firstText(meta.get("timeField"), mapping.get("timeField"));
        String metricField = firstText(meta.get("metricField"), mapping.get("metricField"), mapping.get("metricKey"));
        if (tableName.isBlank() || timeField.isBlank() || metricField.isBlank()) {
            throw new IllegalArgumentException("该预测记录缺少时间字段、指标字段或数据源，无法按预测流程复跑");
        }
        Map<String, Object> params = asJsonObject(meta.get("algorithmParams"));
        Map<String, Object> request = new LinkedHashMap<>(params);
        request.put("tableName", tableName);
        request.put("timeField", timeField);
        request.put("metricField", metricField);
        request.put("granularity", firstText(meta.get("granularity"), mapping.get("granularity"), "month"));
        request.put("algorithm", firstText(meta.get("algorithm"), params.get("algorithm"), "Holt-Winters"));
        request.put("horizon", inferForecastHorizon(snapshot, 3));
        putIfPresent(request, "filterExpression", firstText(meta.get("filterExpression"), mapping.get("filterExpression")));
        putIfPresent(request, "sourceQuestion", firstText(originDetail == null ? null : originDetail.get("question"), snapshot.get("message")));

        Map<String, Object> result = forecast(request);
        Map<String, Object> pseudoPlan = new LinkedHashMap<>();
        pseudoPlan.put("id", snapshot.get("advancedAnalysisPlanId"));
        pseudoPlan.put("planName", firstText(originDetail == null ? null : originDetail.get("question"), snapshot.get("message"), "预测复跑结果"));
        pseudoPlan.put("planType", "forecast");
        pseudoPlan.put("tableName", tableName);
        pseudoPlan.put("metricLabel", firstText(mapping.get("metric"), meta.get("metricField"), metricField));
        pseudoPlan.put("timeRangeLabel", request.get("granularity"));
        pseudoPlan.put("request", request);
        pseudoPlan.put("result", result);
        pseudoPlan.put("fieldMapping", normalizePlanFieldMapping("forecast", request, result, mapping));
        pseudoPlan.put("versionNo", parsePositiveInt(snapshot.get("advancedAnalysisPlanVersion"), 1) + 1);
        Long newHistoryId = attachPinnableChartHistory(pseudoPlan);
        long newId = newHistoryId == null ? 0L : newHistoryId;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("analysisType", "forecast");
        response.put("newHistoryId", newId);
        response.put("engine", "advanced-analysis");
        response.put("chartType", "line");
        response.put("riskLevel", "SAFE");
        response.put("cacheHit", result.get("cacheHit"));
        response.put("message", "预测记录复跑完成");
        response.put("result", result);
        return response;
    }

    public List<Map<String, Object>> listPlanVersions(long id) {
        Map<String, Object> plan = planDetail(id);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, plan_id AS planId, user_id AS userId, plan_type AS planType,
                       plan_name AS planName, version_no AS versionNo,
                       request_json AS requestJson, result_json AS resultJson,
                       llm_json AS llmJson, field_mapping_json AS fieldMappingJson,
                       created_at AS createdAt
                FROM is_advanced_analysis_plan_version
                WHERE plan_id = ? AND user_id = ?
                ORDER BY version_no DESC, id DESC
                LIMIT 20
                """, id, AuthContext.userId());
        rows.forEach(row -> {
            row.put("request", parseJsonObject(row.remove("requestJson")));
            row.put("result", parseJsonObject(row.remove("resultJson")));
            row.put("llm", parseJsonObject(row.remove("llmJson")));
            row.put("fieldMapping", normalizePlanFieldMapping(
                    text(row.get("planType")),
                    asJsonObject(row.get("request")),
                    asJsonObject(row.get("result")),
                    parseJsonObject(row.remove("fieldMappingJson"))
            ));
        });
        if (rows.isEmpty()) {
            rows.add(Map.of(
                    "planId", id,
                    "planType", plan.get("planType"),
                    "planName", plan.get("planName"),
                    "versionNo", plan.get("versionNo"),
                    "request", plan.get("request"),
                    "result", plan.get("result"),
                    "llm", plan.get("llm"),
                    "fieldMapping", plan.get("fieldMapping"),
                    "createdAt", plan.get("updatedAt")
            ));
        }
        return rows;
    }

    public Map<String, Object> comparePlanVersions(long id, Map<String, Object> request) {
        int leftVersion = parsePositiveInt(request.get("leftVersion"), 0);
        int rightVersion = parsePositiveInt(request.get("rightVersion"), 0);
        if (leftVersion <= 0 || rightVersion <= 0) {
            throw new IllegalArgumentException("请选择两个有效版本进行对比");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT plan_type AS planType, version_no AS versionNo, plan_name AS planName, request_json AS requestJson,
                       result_json AS resultJson, llm_json AS llmJson,
                       field_mapping_json AS fieldMappingJson, created_at AS createdAt
                FROM is_advanced_analysis_plan_version
                WHERE plan_id = ? AND version_no IN (?, ?) AND user_id = ?
                ORDER BY version_no ASC
                """, id, leftVersion, rightVersion, AuthContext.userId());
        if (rows.size() < 2) {
            throw new IllegalArgumentException("未找到可对比的两个版本");
        }
        Map<String, Object> first = normalizePlanVersionRow(rows.get(0));
        Map<String, Object> second = normalizePlanVersionRow(rows.get(1));
        Map<String, Object> left = parsePositiveInt(first.get("versionNo"), 0) == leftVersion ? first : second;
        Map<String, Object> right = parsePositiveInt(first.get("versionNo"), 0) == rightVersion ? first : second;
        return Map.of(
                "planId", id,
                "left", left,
                "right", right,
                "summary", List.of(
                        Map.of("label", "版本差异", "value", leftVersion + " vs " + rightVersion),
                        Map.of("label", "名称变化", "value", String.valueOf(left.get("planName")) + " → " + String.valueOf(right.get("planName")))
                )
        );
    }

    public Map<String, Object> latestPlanVersionDiff(long id) {
        List<Map<String, Object>> rows = listPlanVersions(id);
        if (rows.size() < 2) {
            return Map.of(
                    "planId", id,
                    "available", rows.size(),
                    "message", "当前方案版本不足，至少需要 2 个版本才能对比"
            );
        }
        Map<String, Object> latest = rows.get(0);
        Map<String, Object> previous = rows.get(1);
        return comparePlanVersions(id, Map.of(
                "leftVersion", previous.get("versionNo"),
                "rightVersion", latest.get("versionNo")
        ));
    }

    private List<Point> loadSeries(String tableName, String timeField, String metricField, String granularity, int limit, String filterExpression) {
        String physicalTable = physicalTable(tableName);
        String timeExpr = dateBucketExpr(timeField, granularity);
        String sql = "SELECT " + timeExpr + " AS bucket_name, `" + metricField + "` AS metric_value "
                + "FROM `" + physicalTable + "` "
                + "WHERE `" + timeField + "` IS NOT NULL AND `" + timeField + "` <> '' "
                + (filterExpression.isBlank() ? "" : "AND (" + filterExpression + ") ")
                + "ORDER BY bucket_name ASC LIMIT " + Math.max(500, Math.min(limit * 50, 10000));
        List<Map<String, Object>> rows = query(tableName, sql);
        return aggregateSeriesRows(rows);
    }

    private List<Point> loadSeriesAroundBucket(String tableName,
                                               String timeField,
                                               String metricField,
                                               String granularity,
                                               String bucketName,
                                               String filterExpression) {
        List<String> bucketWindow = alertSnapshotBucketWindow(bucketName, granularity);
        if (bucketWindow.isEmpty()) {
            return loadSeries(tableName, timeField, metricField, granularity, 240, filterExpression);
        }
        String physicalTable = physicalTable(tableName);
        String timeExpr = dateBucketExpr(timeField, granularity);
        String inValues = bucketWindow.stream()
                .map(this::sqlStringLiteral)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String sql = "SELECT " + timeExpr + " AS bucket_name, `" + metricField + "` AS metric_value "
                + "FROM `" + physicalTable + "` "
                + "WHERE `" + timeField + "` IS NOT NULL AND `" + timeField + "` <> '' "
                + (filterExpression.isBlank() ? "" : "AND (" + filterExpression + ") ")
                + "AND " + timeExpr + " IN (" + inValues + ") "
                + "ORDER BY bucket_name ASC LIMIT 10000";
        List<Map<String, Object>> rows = query(tableName, sql);
        List<Point> points = aggregateSeriesRows(rows);
        if (points.size() > 1) {
            return points;
        }
        return loadSeries(tableName, timeField, metricField, granularity, 240, filterExpression);
    }

    private List<Point> aggregateSeriesRows(List<Map<String, Object>> rows) {
        Map<String, Double> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String bucket = text(row.get("bucket_name"));
            if (bucket.isBlank()) {
                continue;
            }
            buckets.put(bucket, buckets.getOrDefault(bucket, 0D) + parseDouble(row.get("metric_value"), 0D));
        }
        return buckets.entrySet().stream()
                .map(entry -> new Point(entry.getKey(), entry.getValue()))
                .filter(point -> !point.name().isBlank())
                .sorted(Comparator.comparing(Point::name))
                .toList();
    }

    private SeriesPreprocessResult preprocessSeries(List<Point> rawHistory, String granularity) {
        if (rawHistory == null || rawHistory.isEmpty()) {
            return new SeriesPreprocessResult(List.of(), 0, 0, 0, List.of());
        }
        Map<String, Double> merged = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (Point point : rawHistory) {
            if (point == null || point.name().isBlank() || Double.isNaN(point.value())) {
                continue;
            }
            if (merged.containsKey(point.name())) {
                duplicateCount += 1;
            }
            merged.put(point.name(), merged.getOrDefault(point.name(), 0D) + point.value());
        }
        List<Point> deduplicated = merged.entrySet().stream()
                .map(entry -> new Point(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Point::name))
                .toList();
        List<Point> filled = fillMissingBuckets(deduplicated, granularity);
        int filledCount = Math.max(0, filled.size() - deduplicated.size());
        OutlierAdjustResult adjusted = adjustOutliers(filled);
        return new SeriesPreprocessResult(adjusted.points(), duplicateCount, filledCount,
                adjusted.adjustedCount(), adjusted.rawAnomalies());
    }

    private List<Point> fillMissingBuckets(List<Point> history, String granularity) {
        if (history.size() < 2) {
            return history;
        }
        List<Point> result = new ArrayList<>();
        Point previous = history.get(0);
        result.add(previous);
        for (int i = 1; i < history.size(); i += 1) {
            Point current = history.get(i);
            List<String> missingBuckets = missingBucketNames(previous.name(), current.name(), granularity);
            for (int index = 0; index < missingBuckets.size(); index += 1) {
                String bucket = missingBuckets.get(index);
                result.add(new Point(bucket, interpolateValue(previous, current, index + 1, missingBuckets.size() + 1)));
            }
            result.add(current);
            previous = current;
        }
        return result;
    }

    private double interpolateValue(Point previous, Point current, int step, int totalSteps) {
        if (totalSteps <= 0) {
            return previous.value();
        }
        double ratio = step / (double) totalSteps;
        return previous.value() + (current.value() - previous.value()) * ratio;
    }

    private List<String> missingBucketNames(String startName, String endName, String granularity) {
        int distance = bucketDistance(startName, endName, granularity);
        if (distance <= 1 || distance > 120) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (int offset = 1; offset < distance; offset += 1) {
            names.add(nextBucketName(startName, offset, granularity));
        }
        return names;
    }

    private int bucketDistance(String startName, String endName, String granularity) {
        try {
            return switch (normalizeGranularity(granularity)) {
                case "day" -> (int) java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.parse(startName),
                        LocalDate.parse(endName)
                );
                case "week" -> weekIndex(endName) - weekIndex(startName);
                case "quarter" -> quarterIndex(endName) - quarterIndex(startName);
                case "year" -> Integer.parseInt(endName) - Integer.parseInt(startName);
                default -> monthIndex(endName) - monthIndex(startName);
            };
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private int monthIndex(String bucketName) {
        String[] parts = bucketName.split("-");
        return Integer.parseInt(parts[0]) * 12 + Integer.parseInt(parts[1]);
    }

    private int quarterIndex(String bucketName) {
        String[] parts = bucketName.split("-Q");
        return Integer.parseInt(parts[0]) * 4 + Integer.parseInt(parts[1]);
    }

    private int weekIndex(String bucketName) {
        String[] parts = bucketName.split("-W");
        return Integer.parseInt(parts[0]) * 53 + Integer.parseInt(parts[1]);
    }

    private OutlierAdjustResult adjustOutliers(List<Point> history) {
        if (history.size() < 6) {
            return new OutlierAdjustResult(history, 0, List.of());
        }
        double avg = history.stream().mapToDouble(Point::value).average().orElse(0D);
        double std = standardDeviation(history);
        if (std <= 0D) {
            return new OutlierAdjustResult(history, 0, List.of());
        }
        double lower = avg - std * 3D;
        double upper = avg + std * 3D;
        int adjustedCount = 0;
        List<Point> result = new ArrayList<>();
        List<Point> anomalies = new ArrayList<>();
        for (Point point : history) {
            double adjusted = clamp(point.value(), lower, upper);
            if (Math.abs(adjusted - point.value()) > 0.000001D) {
                adjustedCount += 1;
                anomalies.add(point);
            }
            result.add(new Point(point.name(), adjusted));
        }
        return new OutlierAdjustResult(result, adjustedCount, anomalies);
    }

    private double loadMetricAverage(String tableName, String metricField) {
        String sql = "SELECT AVG(" + numericExpr(metricField) + ") AS value FROM `" + physicalTable(tableName) + "`";
        List<Map<String, Object>> rows = query(tableName, sql);
        if (rows.isEmpty()) return 0D;
        return parseDouble(rows.get(0).get("value"), 0D);
    }

    private double estimateCorrelation(String tableName, String variableField, String targetField) {
        String variableExpr = numericExpr(variableField);
        String targetExpr = numericExpr(targetField);
        String sql = "SELECT " + variableExpr + " AS x_value, " + targetExpr + " AS y_value "
                + "FROM `" + physicalTable(tableName) + "` "
                + "WHERE `" + variableField + "` IS NOT NULL AND `" + targetField + "` IS NOT NULL LIMIT 500";
        List<Map<String, Object>> rows = query(tableName, sql);
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            xs.add(parseDouble(row.get("x_value"), 0D));
            ys.add(parseDouble(row.get("y_value"), 0D));
        }
        if (xs.size() < 3) return 0.35D;
        double avgX = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double avgY = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double numerator = 0D;
        double sumX = 0D;
        double sumY = 0D;
        for (int i = 0; i < xs.size(); i += 1) {
            double dx = xs.get(i) - avgX;
            double dy = ys.get(i) - avgY;
            numerator += dx * dy;
            sumX += dx * dx;
            sumY += dy * dy;
        }
        double denominator = Math.sqrt(sumX * sumY);
        if (denominator == 0D) return 0.35D;
        return Math.max(-1D, Math.min(1D, numerator / denominator));
    }

    private String sanitizeFormulaExpression(Object value) {
        String formula = normalizeFormulaSyntax(text(value));
        if (formula.isBlank()) {
            return "";
        }
        formula = stripFormulaTail(formula);
        formula = stripFormulaDisplayName(formula);
        formula = stripFormulaTail(formula);
        formula = normalizeFormulaSyntax(formula);
        if (formula.length() > 240) {
            throw new IllegalArgumentException("业务公式长度不能超过 240 个字符");
        }
        if (!formula.matches("^[\\p{L}\\p{N}_\\s`\"'.+\\-*/()（）,，<>!=]+$")) {
            throw new IllegalArgumentException("业务公式仅支持字段、数字、函数、条件和安全运算符");
        }
        String lower = formula.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(select|from|where|drop|delete|update|insert|alter|union|sleep|benchmark)\\b.*")) {
            throw new IllegalArgumentException("业务公式仅支持只读四则运算");
        }
        return formula;
    }

    private void rejectUnsafeFormulaInstruction(Object value) {
        String instruction = normalizeFormulaSyntax(text(value)).replaceAll("\\s+", " ").trim();
        if (instruction.isBlank()) {
            return;
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        boolean formulaLike = instruction.contains("=")
                || instruction.contains("公式")
                || instruction.contains("按")
                || instruction.contains("按照");
        boolean sqlLike = lower.matches(".*\\b(select|from|where|drop|delete|update|insert|alter|union|sleep|benchmark)\\b.*");
        if (formulaLike && sqlLike) {
            throw new IllegalArgumentException("业务公式仅支持字段、数字、函数、条件和安全运算符，不能包含 SQL 语句");
        }
    }

    private String normalizeFormulaSyntax(String value) {
        return text(value)
                .replace('＝', '=')
                .replace('（', '(')
                .replace('）', ')')
                .replace('，', ',')
                .replaceAll("(?i)\\bSAFE[\\s-]+DIVIDE\\b", "SAFE_DIVIDE");
    }

    private String stripFormulaDisplayName(String formula) {
        int depth = 0;
        int topLevelEqualsIndex = -1;
        for (int i = 0; i < formula.length(); i += 1) {
            char ch = formula.charAt(i);
            if (ch == '(') {
                depth += 1;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0 || ch != '=') {
                continue;
            }
            char previous = i > 0 ? formula.charAt(i - 1) : '\0';
            char next = i + 1 < formula.length() ? formula.charAt(i + 1) : '\0';
            if (previous == '>' || previous == '<' || previous == '!' || previous == '=' || next == '=') {
                continue;
            }
            if (topLevelEqualsIndex >= 0) {
                throw new IllegalArgumentException("业务公式仅支持一个顶层等号");
            }
            topLevelEqualsIndex = i;
        }
        return topLevelEqualsIndex >= 0 ? formula.substring(topLevelEqualsIndex + 1).trim() : formula;
    }

    private String stripFormulaTail(String value) {
        String formula = text(value)
                .replace('，', ',')
                .replace('。', ' ')
                .replace('；', ' ')
                .replace(';', ' ')
                .replace('、', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        for (String word : List.of("推演", "预测", "变化", "会怎么", "会怎样", "怎么办", "结果", "分析", "测算", "模拟", "如果", "若", "假设", "并", "请")) {
            int index = formula.indexOf(word);
            if (index > 0) {
                formula = formula.substring(0, index).trim();
            }
        }
        return formula.replaceAll("\\s*(提升|增长|上涨|下降|降低|减少)\\s*$", "")
                .replaceAll("[\\s,，。；;、]+$", "")
                .trim();
    }

    private FormulaPlan buildFormulaPlan(String tableName, String formula) {
        Map<String, String> aliases = filterAliasMap(tableName);
        List<FormulaToken> tokens = tokenizeFormula(formula);
        List<FormulaToken> resolvedTokens = new ArrayList<>();
        Map<String, Double> baseValues = new LinkedHashMap<>();
        List<Map<String, Object>> fields = dataUploadService.listFields(tableName);
        for (FormulaToken token : tokens) {
            if (!"identifier".equals(token.type())) {
                resolvedTokens.add(token);
                continue;
            }
            String column = resolveFieldAlias(aliases, token.value());
            if (column == null || !isNumericColumn(fields, column)) {
                throw new IllegalArgumentException("业务公式字段不存在或不是数值字段: " + token.value());
            }
            baseValues.putIfAbsent(column, loadMetricAverage(tableName, column));
            resolvedTokens.add(new FormulaToken("identifier", column));
        }
        if (baseValues.isEmpty()) {
            throw new IllegalArgumentException("业务公式至少需要包含一个数值字段");
        }
        return new FormulaPlan(formula, resolvedFormulaExpression(resolvedTokens), resolvedTokens, baseValues, fields);
    }

    private String resolveFormulaVariableKey(FormulaPlan plan, String requestedField, String variableName) {
        String field = text(requestedField);
        if (plan.baseValues().containsKey(field)) {
            return field;
        }
        String byField = resolveFormulaVariableAlias(plan, field);
        if (!byField.isBlank()) {
            return byField;
        }
        return resolveFormulaVariableAlias(plan, variableName);
    }

    private String resolveFormulaVariableAlias(FormulaPlan plan, String value) {
        String normalized = normalizeLooseAlias(value);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> formulaFields = new ArrayList<>(plan.baseValues().keySet());
        for (String field : formulaFields) {
            if (normalizeLooseAlias(field).equals(normalized)) {
                return field;
            }
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Map<String, Object> fieldMeta : plan.fields()) {
            String column = text(fieldMeta.get("columnName"));
            if (plan.baseValues().containsKey(column)) {
                addFieldAliases(aliases, fieldMeta, column);
            }
        }
        String resolved = resolveFieldAlias(aliases, value);
        return resolved == null || !plan.baseValues().containsKey(resolved) ? "" : resolved;
    }

    private boolean isNumericColumn(List<Map<String, Object>> fields, String column) {
        return fields.stream().anyMatch(field -> column.equals(text(field.get("columnName"))) && isNumericField(field));
    }

    private List<FormulaToken> tokenizeFormula(String formula) {
        List<FormulaToken> tokens = new ArrayList<>();
        for (int i = 0; i < formula.length();) {
            char ch = formula.charAt(i);
            if (Character.isWhitespace(ch)) {
                i += 1;
                continue;
            }
            if (ch == '`' || ch == '"' || ch == '\'') {
                int end = formula.indexOf(ch, i + 1);
                if (end <= i) {
                    throw new IllegalArgumentException("业务公式字段引用未闭合");
                }
                String value = formula.substring(i + 1, end).trim();
                if (value.isBlank()) {
                    throw new IllegalArgumentException("业务公式存在空字段引用");
                }
                tokens.add(new FormulaToken("identifier", value));
                i = end + 1;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '$' || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                int end = i + 1;
                while (end < formula.length()) {
                    char current = formula.charAt(end);
                    if ("+-*/(),，<>!=".indexOf(current) >= 0) {
                        break;
                    }
                    end += 1;
                }
                String identifier = formula.substring(i, end).trim();
                if (identifier.isBlank()) {
                    throw new IllegalArgumentException("业务公式存在空字段引用");
                }
                int nextIndex = end;
                while (nextIndex < formula.length() && Character.isWhitespace(formula.charAt(nextIndex))) {
                    nextIndex += 1;
                }
                String normalizedIdentifier = identifier.toUpperCase(Locale.ROOT);
                if (nextIndex < formula.length()
                        && formula.charAt(nextIndex) == '('
                        && allowedFormulaFunctions().contains(normalizedIdentifier)) {
                    tokens.add(new FormulaToken("function", normalizedIdentifier));
                } else {
                    tokens.add(new FormulaToken("identifier", identifier));
                }
                i = end;
                continue;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int end = i + 1;
                while (end < formula.length() && (Character.isDigit(formula.charAt(end)) || formula.charAt(end) == '.')) {
                    end += 1;
                }
                String number = formula.substring(i, end);
                if (!number.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
                    throw new IllegalArgumentException("业务公式存在无效数字: " + number);
                }
                tokens.add(new FormulaToken("number", number));
                i = end;
                continue;
            }
            if (i + 1 < formula.length()) {
                String twoChars = formula.substring(i, i + 2);
                if (List.of(">=", "<=", "==", "!=", "<>").contains(twoChars)) {
                    tokens.add(new FormulaToken("operator", twoChars));
                    i += 2;
                    continue;
                }
            }
            if ("+-*/(),，<>=".indexOf(ch) >= 0) {
                tokens.add(new FormulaToken("operator", String.valueOf(ch)));
                i += 1;
                continue;
            }
            throw new IllegalArgumentException("业务公式包含不支持字符: " + ch);
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("业务公式不能为空");
        }
        return tokens;
    }

    private List<String> allowedFormulaFunctions() {
        return List.of("IF", "ABS", "MIN", "MAX", "ROUND", "DIVIDE", "SAFE_DIVIDE");
    }

    private String resolvedFormulaExpression(List<FormulaToken> tokens) {
        List<String> parts = new ArrayList<>();
        for (FormulaToken token : tokens) {
            if ("identifier".equals(token.type())) {
                parts.add("`" + token.value() + "`");
            } else if ("function".equals(token.type())) {
                parts.add(token.value());
            } else if ("，".equals(token.value())) {
                parts.add(",");
            } else {
                parts.add(token.value());
            }
        }
        return String.join(" ", parts);
    }

    private double evaluateFormulaPlan(FormulaPlan plan, Map<String, Double> values) {
        FormulaEvaluator evaluator = new FormulaEvaluator(plan.tokens(), values);
        return evaluator.parse();
    }

    private String normalizeFormulaScope(Object value) {
        String scope = text(value).trim().toLowerCase(Locale.ROOT);
        if (scope.contains("row") || scope.contains("line") || scope.contains("行")) {
            return "row";
        }
        return "aggregate";
    }

    private List<Map<String, Double>> loadFormulaRows(String tableName, FormulaPlan plan) {
        List<String> fields = new ArrayList<>(plan.baseValues().keySet());
        if (fields.isEmpty()) {
            return List.of();
        }
        String selectFields = fields.stream()
                .map(field -> numericExpr(field) + " AS `" + field + "`")
                .collect(Collectors.joining(", "));
        String notNullFilters = fields.stream()
                .map(field -> "`" + field + "` IS NOT NULL")
                .collect(Collectors.joining(" AND "));
        String sql = "SELECT " + selectFields
                + " FROM `" + physicalTable(tableName) + "` "
                + "WHERE " + notNullFilters
                + " LIMIT 5000";
        List<Map<String, Object>> rows = query(tableName, sql);
        List<Map<String, Double>> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Double> item = new LinkedHashMap<>();
            boolean valid = true;
            for (String field : fields) {
                double value = parseDouble(row.get(field), Double.NaN);
                if (!Double.isFinite(value)) {
                    valid = false;
                    break;
                }
                item.put(field, value);
            }
            if (valid) {
                values.add(item);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("业务公式按行计算缺少有效数据行");
        }
        return values;
    }

    private double formulaBaseValue(FormulaPlan plan, List<Map<String, Double>> rows, String formulaScope) {
        if ("row".equals(formulaScope)) {
            return formulaRowsAverage(plan, rows);
        }
        return evaluateFormulaPlan(plan, plan.baseValues());
    }

    private double formulaRowsAverage(FormulaPlan plan, List<Map<String, Double>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("业务公式按行计算缺少有效数据行");
        }
        double sum = 0D;
        int count = 0;
        for (Map<String, Double> row : rows) {
            sum += evaluateFormulaPlan(plan, row);
            count += 1;
        }
        return count == 0 ? 0D : sum / count;
    }

    private String normalizeWhatIfMode(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("percent", "absolute", "set").contains(mode)) {
            return mode;
        }
        return "percent";
    }

    private double applyWhatIfChangeValue(double currentValue, double change, String mode, double minValue, double maxValue) {
        double targetValue = switch (normalizeWhatIfMode(mode)) {
            case "absolute" -> currentValue + change;
            case "set" -> change;
            default -> currentValue * (1D + change / 100D);
        };
        if (!Double.isNaN(minValue) && targetValue < minValue) {
            targetValue = minValue;
        }
        if (!Double.isNaN(maxValue) && targetValue > maxValue) {
            targetValue = maxValue;
        }
        return targetValue;
    }

    private double normalizedChangeRate(double currentValue, double targetValue, double fallbackChange) {
        if (currentValue == 0D) {
            return fallbackChange;
        }
        return (targetValue - currentValue) / Math.abs(currentValue) * 100D;
    }

    private double formulaScenarioValue(FormulaPlan plan,
                                        List<Map<String, Object>> variables,
                                        double multiplier,
                                        List<Map<String, Double>> rows,
                                        String formulaScope) {
        if ("row".equals(formulaScope)) {
            return formulaRowScenarioValue(plan, variables, multiplier, rows);
        }
        Map<String, Double> values = new LinkedHashMap<>(plan.baseValues());
        for (Map<String, Object> variable : variables) {
            String field = text(variable.get("field"));
            if (!values.containsKey(field)) {
                continue;
            }
            double currentValue = values.get(field);
            double targetValue = parseDouble(variable.get("targetValue"), currentValue);
            values.put(field, currentValue + (targetValue - currentValue) * multiplier);
        }
        return evaluateFormulaPlan(plan, values);
    }

    private double formulaRowScenarioValue(FormulaPlan plan,
                                           List<Map<String, Object>> variables,
                                           double multiplier,
                                           List<Map<String, Double>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("业务公式按行计算缺少有效数据行");
        }
        double sum = 0D;
        int count = 0;
        for (Map<String, Double> row : rows) {
            Map<String, Double> values = new LinkedHashMap<>(row);
            for (Map<String, Object> variable : variables) {
                String field = text(variable.get("field"));
                if (!values.containsKey(field)) {
                    continue;
                }
                double currentValue = values.get(field);
                double change = parseDouble(variable.get("change"), 0D);
                String mode = normalizeWhatIfMode(text(variable.getOrDefault("mode", "percent")));
                double minValue = parseDouble(variable.get("min"), Double.NaN);
                double maxValue = parseDouble(variable.get("max"), Double.NaN);
                double targetValue = applyWhatIfChangeValue(currentValue, change, mode, minValue, maxValue);
                values.put(field, currentValue + (targetValue - currentValue) * multiplier);
            }
            sum += evaluateFormulaPlan(plan, values);
            count += 1;
        }
        return count == 0 ? 0D : sum / count;
    }

    private RegressionFit fitWhatIfRegression(String tableName, String targetMetric, List<Map<String, Object>> variables, double fallbackBase) {
        List<String> variableFields = variables.stream()
                .map(variable -> text(variable.get("field")))
                .filter(field -> !field.isBlank())
                .distinct()
                .toList();
        if (variableFields.isEmpty()) {
            return RegressionFit.fallback(fallbackBase, variableFields);
        }
        String selectVariables = variableFields.stream()
                .map(field -> numericExpr(field) + " AS `" + field + "`")
                .collect(Collectors.joining(", "));
        String notNullFilters = variableFields.stream()
                .map(field -> "`" + field + "` IS NOT NULL")
                .collect(Collectors.joining(" AND "));
        String sql = "SELECT " + numericExpr(targetMetric) + " AS target_value, " + selectVariables
                + " FROM `" + physicalTable(tableName) + "` "
                + "WHERE `" + targetMetric + "` IS NOT NULL AND " + notNullFilters
                + " LIMIT 1000";
        List<Map<String, Object>> rows = query(tableName, sql);
        if (rows.size() < Math.max(6, variableFields.size() + 2)) {
            return RegressionFit.fallback(fallbackBase, variableFields);
        }
        int n = rows.size();
        int p = variableFields.size();
        double[][] x = new double[n][p];
        double[] y = new double[n];
        for (int rowIndex = 0; rowIndex < n; rowIndex += 1) {
            Map<String, Object> row = rows.get(rowIndex);
            y[rowIndex] = parseDouble(row.get("target_value"), 0D);
            for (int columnIndex = 0; columnIndex < p; columnIndex += 1) {
                x[rowIndex][columnIndex] = parseDouble(row.get(variableFields.get(columnIndex)), 0D);
            }
        }
        double[] means = new double[p];
        double[] stds = new double[p];
        for (int columnIndex = 0; columnIndex < p; columnIndex += 1) {
            double sum = 0D;
            for (int rowIndex = 0; rowIndex < n; rowIndex += 1) {
                sum += x[rowIndex][columnIndex];
            }
            means[columnIndex] = sum / n;
            double variance = 0D;
            for (int rowIndex = 0; rowIndex < n; rowIndex += 1) {
                variance += Math.pow(x[rowIndex][columnIndex] - means[columnIndex], 2);
            }
            stds[columnIndex] = Math.sqrt(variance / n);
            if (stds[columnIndex] < 0.0000001D) {
                stds[columnIndex] = 1D;
            }
        }
        double yMean = Arrays.stream(y).average().orElse(fallbackBase);
        double[][] xtx = new double[p][p];
        double[] xty = new double[p];
        for (int rowIndex = 0; rowIndex < n; rowIndex += 1) {
            double centeredY = y[rowIndex] - yMean;
            for (int i = 0; i < p; i += 1) {
                double xi = (x[rowIndex][i] - means[i]) / stds[i];
                xty[i] += xi * centeredY;
                for (int j = 0; j < p; j += 1) {
                    double xj = (x[rowIndex][j] - means[j]) / stds[j];
                    xtx[i][j] += xi * xj;
                }
            }
        }
        double lambda = Math.max(0.1D, n * 0.02D);
        for (int i = 0; i < p; i += 1) {
            xtx[i][i] += lambda;
        }
        double[] standardizedCoefficients = solveLinearSystem(xtx, xty);
        double[] coefficients = new double[p];
        for (int i = 0; i < p; i += 1) {
            coefficients[i] = standardizedCoefficients[i] / stds[i];
        }
        double intercept = yMean;
        for (int i = 0; i < p; i += 1) {
            intercept -= coefficients[i] * means[i];
        }
        double sse = 0D;
        double sst = 0D;
        for (int rowIndex = 0; rowIndex < n; rowIndex += 1) {
            double predicted = intercept;
            for (int columnIndex = 0; columnIndex < p; columnIndex += 1) {
                predicted += coefficients[columnIndex] * x[rowIndex][columnIndex];
            }
            sse += Math.pow(y[rowIndex] - predicted, 2);
            sst += Math.pow(y[rowIndex] - yMean, 2);
        }
        double rSquared = sst <= 0D ? 0D : Math.max(0D, Math.min(1D, 1D - sse / sst));
        return new RegressionFit(false, intercept, coefficients, variableFields, means, stds, n, rSquared);
    }

    private double regressionScenarioValue(RegressionFit fit, List<Map<String, Object>> variables, double multiplier) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map<String, Object> variable : variables) {
            String field = text(variable.get("field"));
            double currentValue = parseDouble(variable.get("currentValue"), 0D);
            double targetValue = parseDouble(variable.get("targetValue"), currentValue);
            values.put(field, currentValue + (targetValue - currentValue) * multiplier);
        }
        return fit.predict(values);
    }

    private void enrichRegressionVariableImpacts(RegressionFit fit, List<Map<String, Object>> variables, double base) {
        for (Map<String, Object> variable : variables) {
            String field = text(variable.get("field"));
            double currentValue = parseDouble(variable.get("currentValue"), 0D);
            double targetValue = parseDouble(variable.get("targetValue"), currentValue);
            double coefficient = fit.coefficient(field);
            double absoluteImpact = coefficient * (targetValue - currentValue);
            double impactRate = base == 0D ? 0D : absoluteImpact / Math.abs(base) * 100D;
            double normalizedChange = parseDouble(variable.get("normalizedChange"), 0D);
            variable.put("regressionCoefficient", round(coefficient));
            variable.put("formulaImpact", round(impactRate));
            variable.put("estimatedCorrelation", round(Math.abs(normalizedChange) < 0.0000001D ? 0D : impactRate / normalizedChange));
        }
    }

    private double[] solveLinearSystem(double[][] matrix, double[] values) {
        int size = values.length;
        double[][] a = new double[size][size + 1];
        for (int i = 0; i < size; i += 1) {
            System.arraycopy(matrix[i], 0, a[i], 0, size);
            a[i][size] = values[i];
        }
        for (int column = 0; column < size; column += 1) {
            int pivot = column;
            for (int row = column + 1; row < size; row += 1) {
                if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) {
                    pivot = row;
                }
            }
            double[] temp = a[column];
            a[column] = a[pivot];
            a[pivot] = temp;
            double pivotValue = a[column][column];
            if (Math.abs(pivotValue) < 0.0000001D) {
                continue;
            }
            for (int item = column; item <= size; item += 1) {
                a[column][item] /= pivotValue;
            }
            for (int row = 0; row < size; row += 1) {
                if (row == column) {
                    continue;
                }
                double factor = a[row][column];
                for (int item = column; item <= size; item += 1) {
                    a[row][item] -= factor * a[column][item];
                }
            }
        }
        double[] result = new double[size];
        for (int i = 0; i < size; i += 1) {
            result[i] = Double.isFinite(a[i][size]) ? a[i][size] : 0D;
        }
        return result;
    }

    private void enrichFormulaVariableImpacts(FormulaPlan plan,
                                              List<Map<String, Object>> variables,
                                              double base,
                                              List<Map<String, Double>> rows,
                                              String formulaScope) {
        for (Map<String, Object> variable : variables) {
            String field = text(variable.get("field"));
            if (!plan.baseValues().containsKey(field)) {
                variable.put("formulaImpact", 0D);
                variable.put("estimatedCorrelation", 0D);
                continue;
            }
            double nextValue;
            if ("row".equals(formulaScope)) {
                nextValue = formulaRowScenarioValue(plan, List.of(variable), 1D, rows);
            } else {
                Map<String, Double> values = new LinkedHashMap<>(plan.baseValues());
                values.put(field, parseDouble(variable.get("targetValue"), plan.baseValues().get(field)));
                nextValue = evaluateFormulaPlan(plan, values);
            }
            double impact = base == 0D ? 0D : (nextValue - base) / Math.abs(base) * 100D;
            variable.put("formulaImpact", round(impact));
            variable.put("estimatedCorrelation", round(impact == 0D ? 0D : impact / Math.max(Math.abs(parseDouble(variable.get("normalizedChange"), 0D)), 1D)));
        }
    }

    private void appendMissingFormulaVariables(FormulaPlan plan, List<Map<String, Object>> variables) {
        List<String> existing = variables.stream()
                .map(variable -> text(variable.get("field")))
                .filter(field -> !field.isBlank())
                .toList();
        for (String field : plan.baseValues().keySet()) {
            if (existing.contains(field)) {
                continue;
            }
            double currentValue = plan.baseValues().get(field);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("name", field);
            item.put("mode", "percent");
            item.put("change", 0D);
            item.put("normalizedChange", 0D);
            item.put("currentValue", round(currentValue));
            item.put("targetValue", round(currentValue));
            item.put("min", null);
            item.put("max", null);
            item.put("estimatedCorrelation", 0D);
            item.put("formulaOnly", true);
            variables.add(item);
        }
    }

    private List<Point> forecastSeries(List<Point> history, int horizon, String algorithm, ForecastParams params, String granularity) {
        String normalized = text(algorithm).toLowerCase(Locale.ROOT);
        if (normalized.contains("prophet")) {
            return prophetLikeForecast(history, horizon, params, granularity);
        }
        return holtWintersForecast(history, horizon, params, granularity);
    }

    private List<Point> holtWintersForecast(List<Point> history, int horizon, ForecastParams params, String granularity) {
        int size = history.size();
        int seasonLength = params.seasonLength() > 0 ? Math.min(params.seasonLength(), Math.max(2, size)) : inferSeasonLength(size);
        double alpha = params.alpha();
        double beta = params.beta();
        double gamma = params.gamma();
        double level = history.get(0).value();
        double trend = size > 1 ? history.get(1).value() - history.get(0).value() : 0D;
        double[] seasonal = initialSeasonalFactors(history, seasonLength);
        for (int i = 0; i < size; i += 1) {
            double value = history.get(i).value();
            int seasonIndex = i % seasonLength;
            double lastLevel = level;
            double season = seasonal[seasonIndex] == 0D ? 1D : seasonal[seasonIndex];
            level = alpha * (value / season) + (1D - alpha) * (level + trend);
            trend = beta * (level - lastLevel) + (1D - beta) * trend;
            seasonal[seasonIndex] = gamma * (value / Math.max(level, 0.0001D)) + (1D - gamma) * season;
        }
        List<Point> result = new ArrayList<>();
        String lastName = history.get(size - 1).name();
        for (int i = 1; i <= Math.max(1, Math.min(horizon, 60)); i += 1) {
            double season = seasonal[(size + i - 1) % seasonLength];
            result.add(new Point(nextBucketName(lastName, i, granularity), Math.max(0D, (level + trend * i) * season)));
        }
        return result;
    }

    private List<Point> prophetLikeForecast(List<Point> history, int horizon, ForecastParams params, String granularity) {
        int size = history.size();
        double avgX = (size - 1D) / 2D;
        double avgY = history.stream().mapToDouble(Point::value).average().orElse(0D);
        double numerator = 0D;
        double denominator = 0D;
        for (int i = 0; i < size; i += 1) {
            numerator += (i - avgX) * (history.get(i).value() - avgY);
            denominator += Math.pow(i - avgX, 2);
        }
        double slope = denominator == 0D ? 0D : numerator / denominator;
        double intercept = avgY - slope * avgX;
        int seasonLength = params.seasonLength() > 0 ? Math.min(params.seasonLength(), Math.max(2, size)) : inferSeasonLength(size);
        double[] seasonal = new double[seasonLength];
        int[] counts = new int[seasonLength];
        for (int i = 0; i < size; i += 1) {
            double trendValue = intercept + slope * i;
            seasonal[i % seasonLength] += history.get(i).value() - trendValue;
            counts[i % seasonLength] += 1;
        }
        for (int i = 0; i < seasonLength; i += 1) {
            seasonal[i] = counts[i] == 0 ? 0D : seasonal[i] / counts[i];
        }
        List<Point> result = new ArrayList<>();
        String lastName = history.get(size - 1).name();
        for (int i = 1; i <= Math.max(1, Math.min(horizon, 60)); i += 1) {
            int nextIndex = size + i - 1;
            double value = intercept + slope * nextIndex + seasonal[nextIndex % seasonLength];
            result.add(new Point(nextBucketName(lastName, i, granularity), Math.max(0D, value)));
        }
        return result;
    }

    private int inferSeasonLength(int size) {
        if (size >= 24) return 12;
        if (size >= 12) return 6;
        if (size >= 8) return 4;
        return Math.max(2, Math.min(3, size));
    }

    private double[] initialSeasonalFactors(List<Point> history, int seasonLength) {
        double avg = history.stream().mapToDouble(Point::value).average().orElse(1D);
        if (avg == 0D) avg = 1D;
        double[] seasonal = new double[seasonLength];
        int[] counts = new int[seasonLength];
        for (int i = 0; i < history.size(); i += 1) {
            seasonal[i % seasonLength] += history.get(i).value() / avg;
            counts[i % seasonLength] += 1;
        }
        for (int i = 0; i < seasonLength; i += 1) {
            seasonal[i] = counts[i] == 0 ? 1D : seasonal[i] / counts[i];
        }
        return seasonal;
    }

    private ForecastParams forecastParams(Map<String, Object> request) {
        double alpha = clamp(parseDouble(request.get("alpha"), 0.55D), 0.01D, 0.99D);
        double beta = clamp(parseDouble(request.get("beta"), 0.28D), 0.01D, 0.99D);
        double gamma = clamp(parseDouble(request.get("gamma"), 0.20D), 0.01D, 0.99D);
        int seasonLength = parsePositiveInt(request.get("seasonLength"), 0);
        return new ForecastParams(alpha, beta, gamma, seasonLength);
    }

    private Map<String, Object> forecastExplanation(String algorithm,
                                                    String granularity,
                                                    List<Point> history,
                                                    List<Point> forecast,
                                                    ForecastParams params,
                                                    String sourceLabel,
                                                    SeriesPreprocessResult preprocess) {
        double lastHistory = history.isEmpty() ? 0D : history.get(history.size() - 1).value();
        double firstForecast = forecast.isEmpty() ? lastHistory : forecast.get(0).value();
        double lastForecast = forecast.isEmpty() ? lastHistory : forecast.get(forecast.size() - 1).value();
        double changeRate = lastHistory == 0D ? 0D : (lastForecast - lastHistory) / Math.abs(lastHistory) * 100D;
        double firstStepRate = lastHistory == 0D ? 0D : (firstForecast - lastHistory) / Math.abs(lastHistory) * 100D;
        double std = standardDeviation(history);
        double avg = history.stream().mapToDouble(Point::value).average().orElse(0D);
        double volatility = avg == 0D ? 0D : std / Math.abs(avg) * 100D;
        String algorithmName = text(algorithm).toLowerCase(Locale.ROOT).contains("prophet")
                ? "Prophet-like 趋势拟合"
                : "Holt-Winters 指数平滑";
        int seasonLength = params.seasonLength() > 0
                ? Math.min(params.seasonLength(), Math.max(2, history.size()))
                : inferSeasonLength(history.size());
        List<String> calculation = new ArrayList<>();
        calculation.add("数据来源为" + sourceLabel + "，按" + granularityLabel(granularity) + "聚合后得到 "
                + history.size() + " 个历史点，并向前预测 " + forecast.size() + " 个点。");
        calculation.add("算法采用" + algorithmName + "，季节周期按 "
                + seasonLength + " 期处理，结果同时给出 95% 置信区间。");
        calculation.add("最近一期历史值为 " + round(lastHistory)
                + "，首期预测值为 " + round(firstForecast)
                + "，末期预测值为 " + round(lastForecast)
                + "，相对最近一期变化 " + signedPercent(changeRate) + "。");
        calculation.add("历史均值为 " + round(avg)
                + "，标准差为 " + round(std)
                + "，波动系数约 " + signedPercent(volatility).replace("+", "") + "。");
        String preprocessMessage = preprocess == null
                ? ""
                : buildPreprocessMessage(preprocess.filledCount(), preprocess.duplicateCount(), preprocess.outlierAdjustedCount());
        if (!preprocessMessage.isBlank()) {
            calculation.add("预测前已执行时序预处理：" + preprocessMessage + "。");
        }

        List<String> suggestions = new ArrayList<>();
        if (changeRate > 5D) {
            suggestions.add("预测趋势偏上行，建议提前关注库存、产能、预算或人员配置是否能承接增长。");
        } else if (changeRate < -5D) {
            suggestions.add("预测趋势偏下行，建议排查需求、渠道、价格或区域结构变化，必要时提前制定补救动作。");
        } else {
            suggestions.add("预测趋势整体平稳，建议重点跟踪实际值是否持续落在置信区间内。");
        }
        if (history.size() < 8) {
            suggestions.add("历史点数偏少，当前预测不确定性较高，建议补充更长时间窗口后再复算。");
        }
        if (Math.abs(firstStepRate) > 20D || volatility > 35D) {
            suggestions.add("历史波动较大或首期预测跳变明显，建议结合业务事件、促销、节假日或口径变更交叉验证。");
        }
        if (preprocess != null && (preprocess.filledCount() > 0 || preprocess.outlierAdjustedCount() > 0)) {
            suggestions.add("本次序列经过缺失或异常处理，建议核对被补齐/修正的时间段是否存在真实业务事件。");
        }
        suggestions.add("置信区间越宽代表未来不确定性越高，决策时建议同时参考上下界，而不只看单一预测值。");

        return Map.of(
                "source", "rule",
                "sourceLabel", "规则解释",
                "calculation", calculation,
                "suggestions", suggestions
        );
    }

    private Map<String, Object> whatIfExplanation(double base,
                                                  double conservative,
                                                  double scenario,
                                                  double optimistic,
                                                  double recommended,
                                                  List<Map<String, Object>> variables,
                                                  FormulaPlan formulaPlan,
                                                  RegressionFit regressionFit,
                                                  String formulaScope) {
        double scenarioRate = base == 0D ? 0D : (scenario - base) / Math.abs(base) * 100D;
        double recommendedRate = base == 0D ? 0D : (recommended - base) / Math.abs(base) * 100D;
        List<Map<String, Object>> rankedVariables = variables.stream()
                .map(variable -> {
                    double normalizedChange = parseDouble(variable.get("normalizedChange"), 0D);
                    double correlation = parseDouble(variable.get("estimatedCorrelation"), 0D);
                    double formulaImpact = parseDouble(variable.get("formulaImpact"), Double.NaN);
                    double impact = Double.isNaN(formulaImpact) ? normalizedChange * correlation : formulaImpact;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", text(variable.getOrDefault("name", variable.getOrDefault("field", "变量"))));
                    row.put("impact", impact);
                    row.put("direction", impact >= 0D ? "正向" : "反向");
                    return row;
                })
                .sorted((a, b) -> Double.compare(Math.abs(parseDouble(b.get("impact"), 0D)), Math.abs(parseDouble(a.get("impact"), 0D))))
                .toList();
        Map<String, Object> topVariable = rankedVariables.isEmpty() ? Map.of() : rankedVariables.get(0);
        String topVariableName = text(topVariable.getOrDefault("name", "变量"));
        double topImpact = parseDouble(topVariable.get("impact"), 0D);

        List<String> calculation = new ArrayList<>();
        calculation.add("基准方案为 " + round(base)
                + "，保守方案为 " + round(conservative)
                + "，中性方案为 " + round(scenario)
                + "，乐观方案为 " + round(optimistic) + "。");
        calculation.add("推荐方案取当前多场景中的最优结果，值为 " + round(recommended)
                + "，相对基准变化 " + signedPercent(recommendedRate) + "。");
        if (formulaPlan == null) {
            String fitText = regressionFit == null || regressionFit.fallback()
                    ? "样本不足，已使用保守均值模型"
                    : "多变量岭回归拟合，样本数 " + regressionFit.sampleSize() + "，R² " + round(regressionFit.rSquared());
            calculation.add("中性方案相对基准变化 " + signedPercent(scenarioRate)
                    + "，变量影响基于" + fitText + "。");
        } else {
            String scopeText = "row".equals(formulaScope) ? "逐行计算后求平均" : "字段均值计算";
            calculation.add("中性方案相对基准变化 " + signedPercent(scenarioRate)
                    + "，变量影响基于业务公式「" + formulaPlan.displayExpression() + "」和" + scopeText + "。");
        }
        if (!topVariableName.isBlank()) {
            calculation.add("当前敏感性最高的变量是「" + topVariableName
                    + "」，估计影响方向为" + (topImpact >= 0D ? "正向" : "反向")
                    + "，影响强度约 " + signedPercent(topImpact) + "。");
        }

        List<String> suggestions = new ArrayList<>();
        if (recommendedRate > 0D) {
            suggestions.add("推荐方案优于基准，可优先评估该变量组合在预算、库存、交付和合规上的可执行性。");
        } else if (recommendedRate < 0D) {
            suggestions.add("当前变量组合未带来正向收益，建议降低负向变量幅度或补充新的可控变量重新推演。");
        } else {
            suggestions.add("当前推演对目标指标影响有限，建议引入更直接的业务变量或使用更细粒度样本复算。");
        }
        if (!topVariableName.isBlank()) {
            suggestions.add("优先复核「" + topVariableName + "」的业务可控性，因为它对结果最敏感。");
        }
        if (formulaPlan == null) {
            suggestions.add("当前数值来自回归拟合估计，不等同于因果结论；落地前建议结合业务公式或实验数据校验。");
        } else {
            String scopeText = "row".equals(formulaScope) ? "按行计算口径" : "字段均值聚合口径";
            suggestions.add("当前数值来自配置的业务公式和" + scopeText + "，建议确认公式口径、字段单位和实际管理口径一致。");
        }

        return Map.of(
                "source", "rule",
                "sourceLabel", "规则解释",
                "calculation", calculation,
                "suggestions", suggestions
        );
    }

    public Map<String, Object> fallbackResultExplanation(Map<String, Object> request) {
        String type = normalizeExplanationType(text(request.getOrDefault("type", request.get("analysisType"))));
        Map<String, Object> result = asJsonObject(request.get("result"));
        if (result.isEmpty()) {
            result = asJsonObject(request.get("analysis"));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("缺少后端算法结果，无法生成解释");
        }
        Map<String, Object> existing = asJsonObject(result.get("explanation"));
        if (!existing.isEmpty()) {
            return normalizeExplanation(existing, "rule", "规则解释");
        }
        if ("forecast".equals(type)) {
            return fallbackForecastExplanation(result);
        }
        if ("whatIf".equals(type)) {
            return fallbackWhatIfExplanation(result);
        }
        return fallbackAlertExplanation(result);
    }

    private String normalizeExplanationType(String value) {
        String text = value == null ? "" : value.trim();
        if (List.of("forecast", "whatIf", "alert").contains(text)) {
            return text;
        }
        if ("what-if".equalsIgnoreCase(text) || "what_if".equalsIgnoreCase(text)) {
            return "whatIf";
        }
        return "alert";
    }

    private Map<String, Object> fallbackForecastExplanation(Map<String, Object> result) {
        List<Map<String, Object>> rows = asJsonObjectList(result.get("series"));
        long historyCount = rows.stream().filter(row -> row.get("history") != null).count();
        List<Map<String, Object>> forecastRows = rows.stream().filter(row -> row.get("forecast") != null).toList();
        Object lastForecast = forecastRows.isEmpty() ? null : forecastRows.get(forecastRows.size() - 1).get("forecast");
        String algorithm = firstText(result.get("algorithm"), asJsonObject(result.get("params")).get("algorithm"), "预测算法");
        List<String> calculation = new ArrayList<>();
        calculation.add("当前解释基于后端 " + algorithm + " 预测结果生成，历史点数 "
                + historyCount + "，预测点数 " + forecastRows.size() + "。");
        if (lastForecast != null) {
            calculation.add("末期预测值为 " + text(lastForecast) + "，应结合 95% 置信区间上下界判断不确定性。");
        }
        return explanation("rule", "规则解释", calculation, List.of(
                "建议优先核对预测趋势和最近业务动作是否一致。",
                "若置信区间较宽，应以保守方案安排预算、库存或运营资源。"
        ));
    }

    private Map<String, Object> fallbackWhatIfExplanation(Map<String, Object> result) {
        List<Map<String, Object>> rows = asJsonObjectList(result.get("series"));
        String base = findSeriesValue(rows, "基准方案");
        String recommended = findSeriesValue(rows, "推荐方案");
        String formula = firstText(result.get("formula"), asJsonObject(result.get("params")).get("formula"));
        List<String> calculation = new ArrayList<>();
        calculation.add("当前解释基于后端 What-if 推演结果生成，数值未由 AI 重新计算。");
        if (!base.isBlank() || !recommended.isBlank()) {
            calculation.add("基准方案为 " + (base.isBlank() ? "-" : base)
                    + "，推荐方案为 " + (recommended.isBlank() ? "-" : recommended) + "。");
        }
        if (!formula.isBlank()) {
            calculation.add("本次推演使用业务公式「" + formula + "」作为计算口径。");
        }
        return explanation("rule", "规则解释", calculation, List.of(
                "建议先检查推荐方案变量是否真实可控，再评估执行成本。",
                "推演结果用于比较方案，不应直接等同于因果结论。"
        ));
    }

    private Map<String, Object> fallbackAlertExplanation(Map<String, Object> result) {
        Map<String, Object> params = asJsonObject(result.get("params"));
        String operator = firstText(params.get("operator"), result.get("operator"));
        String threshold = firstText(params.get("threshold"), result.get("threshold"));
        String reason = firstText(result.get("reason"));
        List<String> calculation = new ArrayList<>();
        calculation.add("当前解释基于后端阈值/Z-Score 预警判断结果生成，异常结论未由 AI 重新判断。");
        if (!operator.isBlank() || !threshold.isBlank()) {
            calculation.add("预警条件为 " + (operator.isBlank() ? "-" : operator)
                    + "，阈值为 " + (threshold.isBlank() ? "-" : threshold) + "。");
        }
        if (!reason.isBlank()) {
            calculation.add("触发原因：" + reason);
        }
        return explanation("rule", "规则解释", calculation, List.of(
                "建议先核对触发时间桶的原始数据和过滤口径。",
                "处理完成后可在预警事件中记录确认、关闭或重开备注。"
        ));
    }

    private Map<String, Object> normalizeExplanation(Map<String, Object> raw, String fallbackSource, String fallbackLabel) {
        List<String> calculation = normalizeTextList(firstPresent(raw, "calculation", "calculationResults", "algorithmResults"));
        List<String> suggestions = normalizeTextList(firstPresent(raw, "suggestions", "recommendations", "aiSuggestions"));
        return explanation(
                firstText(raw.get("source"), fallbackSource),
                firstText(raw.get("sourceLabel"), fallbackLabel),
                calculation,
                suggestions
        );
    }

    private Map<String, Object> explanation(String source,
                                            String sourceLabel,
                                            List<String> calculation,
                                            List<String> suggestions) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("sourceLabel", sourceLabel);
        result.put("calculation", calculation == null ? List.of() : calculation.stream().filter(item -> !text(item).isBlank()).toList());
        result.put("suggestions", suggestions == null ? List.of() : suggestions.stream().filter(item -> !text(item).isBlank()).toList());
        result.put("guardrail", "explanation-only");
        return result;
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private List<String> normalizeTextList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::text).filter(item -> !item.isBlank()).limit(5).toList();
        }
        String text = text(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asJsonObjectList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
            return result;
        }
        return List.of();
    }

    private String findSeriesValue(List<Map<String, Object>> rows, String name) {
        return rows.stream()
                .filter(row -> name.equals(text(row.get("name"))))
                .map(row -> text(row.get("value")))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String granularityLabel(String granularity) {
        return switch (normalizeGranularity(granularity)) {
            case "day" -> "日";
            case "week" -> "周";
            case "quarter" -> "季度";
            case "year" -> "年";
            default -> "月";
        };
    }

    private String signedPercent(double value) {
        return (value >= 0D ? "+" : "") + round(value) + "%";
    }

    private boolean alertRuleDue(Map<String, Object> rule, LocalDateTime now) {
        Object lastCheckedValue = rule.get("lastCheckedAt");
        if (lastCheckedValue == null) {
            return true;
        }
        LocalDateTime lastChecked = toLocalDateTime(lastCheckedValue);
        if (lastChecked == null) {
            return true;
        }
        String cycle = normalizeDetectionCycle(text(rule.getOrDefault("detectionCycle", "daily")));
        LocalDateTime nextAt = switch (cycle) {
            case "hourly" -> lastChecked.plusHours(1);
            case "weekly" -> lastChecked.plusWeeks(1);
            case "monthly" -> lastChecked.plusMonths(1);
            default -> lastChecked.plusDays(1);
        };
        return !nextAt.isAfter(now);
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().atStartOfDay();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.replace(" ", "T"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void markAlertRuleChecked(Map<String, Object> rule, boolean triggered) {
        long ruleId = parseLong(rule.get("id"), 0L);
        if (ruleId <= 0) {
            return;
        }
        if (triggered) {
            jdbcTemplate.update("""
                    UPDATE is_advanced_alert_rule
                    SET last_checked_at = CURRENT_TIMESTAMP,
                        last_triggered_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, ruleId);
            return;
        }
        jdbcTemplate.update("""
                UPDATE is_advanced_alert_rule
                SET last_checked_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, ruleId);
    }

    private List<Map<String, Object>> loadActiveAlertRules(long ruleId, boolean scheduledOnly) {
        List<Object> args = new ArrayList<>();
        AuthContext.UserPrincipal principal = currentPrincipalOrNull();
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id AS userId, org_scope AS orgScope,
                       rule_name AS ruleName, table_name AS tableName, metric_field AS metricField,
                       time_field AS timeField, granularity, filter_expression AS filterExpression,
                       resolved_filter_expression AS resolvedFilterExpression, operator,
                       threshold_value AS threshold, detection_cycle AS detectionCycle,
                       channels_json AS channelsJson, status, last_checked_at AS lastCheckedAt,
                       COALESCE(last_triggered_at, (
                         SELECT MAX(e.created_at)
                         FROM is_advanced_alert_event e
                         WHERE e.rule_id = is_advanced_alert_rule.id
                       )) AS lastTriggeredAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_advanced_alert_rule
                WHERE status = 'ACTIVE'
                """);
        if (ruleId > 0) {
            sql.append(" AND id = ?");
            args.add(ruleId);
        } else if (scheduledOnly) {
            sql.append("""
                     AND (
                       last_checked_at IS NULL
                       OR (detection_cycle = 'hourly' AND last_checked_at <= DATE_SUB(NOW(), INTERVAL 1 HOUR))
                       OR (detection_cycle = 'daily' AND last_checked_at <= DATE_SUB(NOW(), INTERVAL 1 DAY))
                       OR (detection_cycle = 'weekly' AND last_checked_at <= DATE_SUB(NOW(), INTERVAL 7 DAY))
                       OR (detection_cycle = 'monthly' AND last_checked_at <= DATE_SUB(NOW(), INTERVAL 1 MONTH))
                     )
                    """);
        }
        if (principal != null && !"ADMIN".equalsIgnoreCase(principal.role())) {
            sql.append(" AND user_id = ?");
            args.add(principal.userId());
        }
        sql.append(" ORDER BY COALESCE(last_checked_at, created_at) ASC, updated_at ASC LIMIT 200");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        rows.forEach(this::parseAlertRuleJsonFields);
        return rows;
    }

    private List<Map<String, Object>> detectAlertRule(Map<String, Object> rule) {
        long ruleId = parseLong(rule.get("id"), 0L);
        String tableName = text(rule.get("tableName"));
        String timeField = text(rule.get("timeField"));
        String metricField = text(rule.get("metricField"));
        String granularity = normalizeGranularity(text(rule.getOrDefault("granularity", "day")));
        String operator = normalizeAlertOperator(text(rule.getOrDefault("operator", "lt")));
        String filterExpression = text(rule.get("resolvedFilterExpression"));
        double threshold = parseDouble(rule.get("threshold"), Double.NaN);
        assertCanAccessAdvancedTable(tableName);
        List<Point> history = loadSeries(tableName, timeField, metricField, granularity, 120, filterExpression);
        if (history.size() < 3) {
            return List.of();
        }
        double baseline = history.stream().limit(Math.max(1, history.size() - 1)).mapToDouble(Point::value).average().orElse(0D);
        double std = standardDeviation(history.subList(0, Math.max(1, history.size() - 1)));
        List<Map<String, Object>> events = new ArrayList<>();
        for (Point point : history.subList(Math.max(0, history.size() - 12), history.size())) {
            double zScore = std <= 0D ? 0D : (point.value() - baseline) / std;
            boolean triggered = switch (operator) {
                case "gt" -> !Double.isNaN(threshold) && point.value() > threshold;
                case "zscore" -> Math.abs(zScore) >= 3D;
                default -> !Double.isNaN(threshold) && point.value() < threshold;
            };
            if (!triggered) {
                continue;
            }
            Map<String, Object> event = insertAlertEvent(rule, point, threshold, baseline, zScore, history);
            if (!event.isEmpty()) {
                events.add(event);
            }
        }
        return events;
    }

    private Map<String, Object> insertAlertEvent(Map<String, Object> rule, Point point, double threshold, double baseline, double zScore, List<Point> history) {
        long ruleId = parseLong(rule.get("id"), 0L);
        String operator = normalizeAlertOperator(text(rule.get("operator")));
        double deviationRate = baseline == 0D ? 0D : (point.value() - baseline) / baseline * 100D;
        String reason = buildAlertReason(operator, point.value(), threshold, baseline, zScore, deviationRate);
        Map<String, Object> snapshot = buildAlertChartSnapshot(rule, point, threshold, baseline, zScore, history);
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO is_advanced_alert_event(
                  rule_id, user_id, org_scope, table_name, metric_field, time_field, bucket_name,
                  actual_value, threshold_value, operator, z_score, baseline_value,
                  deviation_rate, reason, chart_snapshot_json, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), 'OPEN')
                """,
                ruleId,
                text(rule.get("userId")),
                orgScopeFromRule(rule),
                text(rule.get("tableName")),
                text(rule.get("metricField")),
                text(rule.get("timeField")),
                point.name(),
                round(point.value()),
                Double.isNaN(threshold) ? null : round(threshold),
                operator,
                round(zScore),
                round(baseline),
                round(deviationRate),
                reason,
                toJson(snapshot));
        if (inserted <= 0) {
            jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET org_scope = ?, actual_value = ?, threshold_value = ?, z_score = ?,
                        baseline_value = ?, deviation_rate = ?, reason = ?,
                        chart_snapshot_json = CAST(? AS JSON)
                    WHERE rule_id = ? AND bucket_name = ? AND operator = ?
                    """,
                    orgScopeFromRule(rule),
                    round(point.value()),
                    Double.isNaN(threshold) ? null : round(threshold),
                    round(zScore),
                    round(baseline),
                    round(deviationRate),
                    reason,
                    toJson(snapshot),
                    ruleId,
                    point.name(),
                    operator);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, rule_id AS ruleId, user_id AS userId, org_scope AS orgScope,
                       table_name AS tableName,
                       metric_field AS metricField, time_field AS timeField, bucket_name AS bucketName,
                       actual_value AS actualValue, threshold_value AS threshold, operator, z_score AS zScore,
                       baseline_value AS baselineValue, deviation_rate AS deviationRate, reason,
                       chart_snapshot_json AS chartSnapshotJson, llm_explanation_json AS llmExplanationJson,
                       explanation_note AS explanationNote, explanation_updated_at AS explanationUpdatedAt,
                       status, ack_by AS ackBy,
                       ack_at AS ackAt, closed_by AS closedBy, closed_at AS closedAt,
                       handle_note AS handleNote, status_updated_at AS statusUpdatedAt,
                       created_at AS createdAt
                FROM is_advanced_alert_event
                WHERE rule_id = ? AND bucket_name = ? AND operator = ?
                LIMIT 1
                """, ruleId, point.name(), operator);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> event = new LinkedHashMap<>(rows.get(0));
        parseAlertEventJsonFields(event);
        event.put("eventAction", inserted > 0 ? "created" : "refreshed");
        hydrateLegacyAlertSnapshot(event, true);
        if (inserted > 0) {
            createAlertPushLogs(rule, event);
        }
        return event;
    }

    private Map<String, Object> buildAlertChartSnapshot(Map<String, Object> rule,
                                                        Point point,
                                                        double threshold,
                                                        double baseline,
                                                        double zScore,
                                                        List<Point> history) {
        String operator = normalizeAlertOperator(text(rule.get("operator")));
        List<Point> slice = alertSnapshotSlice(history, point.name());
        List<Map<String, Object>> data = new ArrayList<>();
        for (Point item : slice) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", item.name());
            row.put("value", round(item.value()));
            row.put("triggered", item.name().equals(point.name()));
            data.add(row);
        }
        Map<String, Object> markLine = new LinkedHashMap<>();
        if (!Double.isNaN(threshold)) {
            markLine.put("threshold", round(threshold));
        }
        markLine.put("baseline", round(baseline));
        Map<String, Object> chartOption = new LinkedHashMap<>();
        chartOption.put("tooltip", Map.of("trigger", "axis"));
        chartOption.put("legend", Map.of("top", 4, "data", List.of("检测值")));
        chartOption.put("grid", Map.of("left", 54, "right", 24, "top", 48, "bottom", 42, "containLabel", true));
        chartOption.put("xAxis", Map.of("type", "category", "data", data.stream().map(row -> row.get("name")).toList()));
        chartOption.put("yAxis", Map.of("type", "value"));
        List<Map<String, Object>> markLineData = new ArrayList<>();
        if (!Double.isNaN(threshold)) {
            markLineData.add(Map.of("name", "阈值", "yAxis", round(threshold)));
        }
        markLineData.add(Map.of("name", "历史基线", "yAxis", round(baseline)));
        chartOption.put("series", List.of(Map.of(
                "name", "检测值",
                "type", "line",
                "smooth", true,
                "data", data.stream().map(row -> row.get("value")).toList(),
                "markLine", Map.of("symbol", "none", "data", markLineData)
        )));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bucketName", point.name());
        snapshot.put("actualValue", round(point.value()));
        snapshot.put("threshold", Double.isNaN(threshold) ? null : round(threshold));
        snapshot.put("baseline", round(baseline));
        snapshot.put("zScore", round(zScore));
        snapshot.put("operator", operator);
        snapshot.put("granularity", normalizeGranularity(text(rule.getOrDefault("granularity", "day"))));
        snapshot.put("metricField", text(rule.get("metricField")));
        snapshot.put("timeField", text(rule.get("timeField")));
        snapshot.put("tableName", text(rule.get("tableName")));
        snapshot.put("data", data);
        snapshot.put("markLine", markLine);
        snapshot.put("chartOption", chartOption);
        attachAdvancedGraphContext(snapshot, "预警 " + text(rule.get("metricField")), text(rule.get("tableName")));
        return snapshot;
    }

    private List<Point> alertSnapshotSlice(List<Point> history, String bucketName) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int triggerIndex = -1;
        for (int i = 0; i < history.size(); i += 1) {
            if (history.get(i).name().equals(bucketName)) {
                triggerIndex = i;
                break;
            }
        }
        if (triggerIndex < 0) {
            triggerIndex = history.size() - 1;
        }
        int start = Math.max(0, triggerIndex - 8);
        int end = Math.min(history.size(), triggerIndex + 4);
        return new ArrayList<>(history.subList(start, end));
    }

    private Map<String, Object> alertEventDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, rule_id AS ruleId, user_id AS userId, org_scope AS orgScope, table_name AS tableName,
                       metric_field AS metricField, time_field AS timeField, bucket_name AS bucketName,
                       actual_value AS actualValue, threshold_value AS threshold, operator, z_score AS zScore,
                       baseline_value AS baselineValue, deviation_rate AS deviationRate, reason,
                       chart_snapshot_json AS chartSnapshotJson, llm_explanation_json AS llmExplanationJson,
                       explanation_note AS explanationNote, explanation_updated_at AS explanationUpdatedAt,
                       status, ack_by AS ackBy,
                       ack_at AS ackAt, closed_by AS closedBy, closed_at AS closedAt,
                       handle_note AS handleNote, status_updated_at AS statusUpdatedAt,
                       created_at AS createdAt
                FROM is_advanced_alert_event
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                LIMIT 1
                """, id, AuthContext.userId(), AuthContext.role());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("预警事件不存在或无权访问");
        }
        Map<String, Object> event = new LinkedHashMap<>(rows.get(0));
        parseAlertEventJsonFields(event);
        hydrateLegacyAlertSnapshot(event, true);
        return event;
    }

    private String buildAlertReason(String operator, double actual, double threshold, double baseline, double zScore, double deviationRate) {
        if ("zscore".equals(operator)) {
            return "Z-Score 异常检测触发：当前值 %.2f，历史基线 %.2f，Z-Score %.2f，偏离 %.2f%%"
                    .formatted(actual, baseline, zScore, deviationRate);
        }
        String compare = "gt".equals(operator) ? "高于" : "低于";
        return "阈值预警触发：当前值 %.2f %s 阈值 %.2f，历史基线 %.2f，偏离 %.2f%%"
                .formatted(actual, compare, threshold, baseline, deviationRate);
    }

    private void createAlertPushLogs(Map<String, Object> rule, Map<String, Object> event) {
        for (String channel : normalizeChannels(rule.get("channels"))) {
            Map<String, Object> log = insertAlertPushLog(rule, event, channel);
            try {
                attemptAlertPush(log, event);
            } catch (RuntimeException ignored) {
                // Push failures are recorded in the log table and must not block alert detection.
            }
        }
    }

    private Map<String, Object> insertAlertPushLog(Map<String, Object> rule, Map<String, Object> event, String channel) {
        String normalizedChannel = normalizePushChannel(channel);
        String title = buildAlertPushTitle(rule, event);
        String content = buildAlertPushContent(rule, event);
        String target = pushTarget(normalizedChannel);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_advanced_alert_push_log(
                      event_id, rule_id, user_id, org_scope, channel, status, target, title, content, request_json
                    ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, CAST(? AS JSON))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, parseLong(event.get("id"), 0L));
            ps.setLong(2, parseLong(event.get("ruleId"), parseLong(rule.get("id"), 0L)));
            ps.setString(3, text(event.getOrDefault("userId", rule.get("userId"))));
            ps.setString(4, alertOrgScope(event, rule));
            ps.setString(5, normalizedChannel);
            ps.setString(6, maskPushTarget(target));
            ps.setString(7, title);
            ps.setString(8, content);
            ps.setString(9, toJson(Map.of(
                    "channel", normalizedChannel,
                    "target", maskPushTarget(target),
                    "eventId", event.get("id"),
                    "ruleId", event.get("ruleId"),
                    "title", title,
                    "content", content
            )));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        return alertPushLogDetail(id);
    }

    private void refreshAlertPushLogContent(Map<String, Object> log, Map<String, Object> rule, Map<String, Object> event) {
        long id = parseLong(log.get("id"), 0L);
        if (id <= 0 || rule == null || rule.isEmpty() || event == null || event.isEmpty()) {
            return;
        }
        String title = buildAlertPushTitle(rule, event);
        String content = buildAlertPushContent(rule, event);
        log.put("title", title);
        log.put("content", content);
        jdbcTemplate.update("""
                UPDATE is_advanced_alert_push_log
                SET title = ?, content = ?
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """, title, content, id, AuthContext.userId(), AuthContext.role());
    }

    private Map<String, Object> attemptAlertPush(Map<String, Object> log, Map<String, Object> event) {
        long id = parseLong(log.get("id"), 0L);
        String channel = normalizePushChannel(text(log.get("channel")));
        String title = text(log.get("title"));
        String content = text(log.get("content"));
        String target = pushTarget(channel);
        Map<String, Object> response = new LinkedHashMap<>();
        String status = "FAILED";
        String errorMessage = "";
        try {
            if ("email".equals(channel)) {
                response = sendEmailAlert(title, content, event);
                status = "SUCCESS";
            } else if ("dingtalk".equals(channel)) {
                if (target.isBlank()) {
                    throw new IllegalStateException("钉钉 Webhook 未配置：请配置 insight.advanced-alert.dingtalk-webhook");
                }
                response = sendDingtalkAlert(target, title, content, event);
                status = "SUCCESS";
            } else {
                throw new IllegalArgumentException("不支持的推送渠道: " + channel);
            }
        } catch (RuntimeException e) {
            errorMessage = truncate(e.getMessage() == null ? "推送失败" : e.getMessage(), 1000);
            response.put("error", errorMessage);
        }
        jdbcTemplate.update("""
                UPDATE is_advanced_alert_push_log
                SET status = ?, attempt_count = attempt_count + 1,
                    target = ?, error_message = ?,
                    response_json = CAST(? AS JSON),
                    last_attempt_at = CURRENT_TIMESTAMP,
                    next_retry_at = CASE WHEN ? = 'SUCCESS' THEN NULL ELSE DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE) END
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """,
                status,
                maskPushTarget(target),
                errorMessage.isBlank() ? null : errorMessage,
                toJson(response),
                status,
                id,
                AuthContext.userId(),
                AuthContext.role());
        return Map.of("id", id, "status", status);
    }

    private Map<String, Object> sendEmailAlert(String title, String content, Map<String, Object> event) {
        if (text(alertEmailTarget).isBlank()) {
            throw new IllegalStateException("邮件目标未配置：请配置 insight.advanced-alert.email-target");
        }
        if (text(alertSmtpHost).isBlank()) {
            throw new IllegalStateException("SMTP 主机未配置：请配置 insight.advanced-alert.smtp-host");
        }
        if (text(alertSmtpUsername).isBlank()) {
            throw new IllegalStateException("SMTP 用户名未配置：请配置 insight.advanced-alert.smtp-username");
        }
        if (text(alertSmtpPassword).isBlank()) {
            throw new IllegalStateException("SMTP 授权码/密码未配置：请配置 insight.advanced-alert.smtp-password");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(text(alertSmtpHost));
        sender.setPort(alertSmtpPort);
        sender.setUsername(text(alertSmtpUsername));
        sender.setPassword(text(alertSmtpPassword));
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(alertSmtpAuth));
        props.put("mail.smtp.starttls.enable", String.valueOf(alertSmtpStarttls));
        props.put("mail.smtp.ssl.enable", String.valueOf(alertSmtpSsl));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(text(alertEmailFrom).isBlank() ? text(alertSmtpUsername) : text(alertEmailFrom));
        message.setTo(text(alertEmailTarget).split("[,，;；\\s]+"));
        message.setSubject(title);
        message.setText(content + "\n\n" + buildAlertPushFooter(event));
        try {
            sender.send(message);
            return Map.of(
                    "mode", "smtp",
                    "host", text(alertSmtpHost),
                    "port", alertSmtpPort,
                    "target", maskPushTarget(alertEmailTarget),
                    "message", "邮件发送成功"
            );
        } catch (MailException e) {
            throw new IllegalStateException("邮件推送失败：" + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendDingtalkAlert(String webhook, String title, String content, Map<String, Object> event) {
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", title);
        markdown.put("text", "### " + title + "\n\n"
                + content + "\n\n"
                + buildAlertPushFooter(event));
        Map<String, Object> payload = Map.of(
                "msgtype", "markdown",
                "markdown", markdown
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String signedWebhook = signedDingtalkWebhook(webhook);
            Map<String, Object> response = pushRestTemplate.postForObject(signedWebhook, new HttpEntity<>(payload, headers), Map.class);
            Map<String, Object> result = response == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response);
            Object errCode = result.get("errcode");
            if (errCode != null && !"0".equals(text(errCode))) {
                throw new IllegalStateException("钉钉返回失败：" + result);
            }
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("钉钉推送失败：" + e.getMessage(), e);
        }
    }

    private String signedDingtalkWebhook(String webhook) {
        String secret = text(dingtalkSecret);
        if (secret.isBlank()) {
            return webhook;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = java.util.Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            String separator = webhook.contains("?") ? "&" : "?";
            return webhook + separator + "timestamp=" + timestamp + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> alertPushLogDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, event_id AS eventId, rule_id AS ruleId, user_id AS userId, org_scope AS orgScope,
                       channel, status, attempt_count AS attemptCount, target, title, content,
                       error_message AS errorMessage, request_json AS requestJson, response_json AS responseJson,
                       last_attempt_at AS lastAttemptAt, next_retry_at AS nextRetryAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_advanced_alert_push_log
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                LIMIT 1
                """, id, AuthContext.userId(), AuthContext.role());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("预警推送记录不存在或无权访问");
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        parseAlertPushLogJsonFields(row);
        return row;
    }

    private String buildAlertPushTitle(Map<String, Object> rule, Map<String, Object> event) {
        return "预警触发 | " + alertRuleDisplayName(rule, event) + " | " + alertValueOrDash(event.get("bucketName"));
    }

    private String buildAlertPushContent(Map<String, Object> rule, Map<String, Object> event) {
        Map<String, Object> explanation = asJsonObject(event.get("llmExplanation"));
        if (explanation.isEmpty()) {
            explanation = generateAlertPushExplanation(rule, event);
        }
        return buildReadableAlertPushContent(rule, event, explanation);
    }

    private String buildRuleAlertPushContent(Map<String, Object> rule, Map<String, Object> event) {
        return buildReadableAlertPushContent(rule, event, Map.of());
    }

    private String buildAiAlertPushContent(Map<String, Object> rule, Map<String, Object> event) {
        Map<String, Object> explanation = asJsonObject(event.get("llmExplanation"));
        if (explanation.isEmpty()) {
            explanation = generateAlertPushExplanation(rule, event);
        }
        return buildReadableAlertPushContent(rule, event, explanation);
    }

    private String buildReadableAlertPushContent(Map<String, Object> rule, Map<String, Object> event, Map<String, Object> explanation) {
        String tableName = text(event.get("tableName"));
        String metricField = text(event.get("metricField"));
        String timeField = text(event.get("timeField"));
        String tableLabel = alertTableLabel(tableName);
        String metricLabel = alertFieldLabel(tableName, metricField, false);
        String timeFieldLabel = alertFieldLabel(tableName, timeField, true);
        String ruleName = alertRuleDisplayName(rule, event);
        String operator = normalizeAlertOperator(text(event.get("operator")));
        boolean hasThreshold = hasAlertNumber(event.get("threshold"));
        List<String> reasons = compactAlertPushItems(normalizeTextList(explanation.get("calculation")), 3);
        if (reasons.isEmpty() && !text(event.get("reason")).isBlank()) {
            reasons = compactAlertPushItems(List.of(text(event.get("reason"))), 3);
        }
        List<String> suggestions = compactAlertPushItems(normalizeTextList(explanation.get("suggestions")), 3);

        StringBuilder builder = new StringBuilder();
        builder.append("【规则名称】\n");
        builder.append(ruleName).append("\n\n");

        builder.append("【预警摘要】\n");
        builder.append(metricLabel)
                .append(" 在 ")
                .append(alertValueOrDash(event.get("bucketName")))
                .append(" 触发")
                .append(alertOperatorLabel(operator))
                .append("预警，实际值 ")
                .append(formatAlertNumber(event.get("actualValue")));
        if (hasThreshold) {
            builder.append("，阈值 ").append(formatAlertNumber(event.get("threshold")));
        }
        builder.append("。\n\n");

        builder.append("【关键指标】\n");
        appendAlertPushLine(builder, "数据源", tableLabel);
        appendAlertPushLine(builder, "指标", metricLabel);
        appendAlertPushLine(builder, "时间字段", timeFieldLabel);
        appendAlertPushLine(builder, "时间桶", event.get("bucketName"));
        appendAlertPushLine(builder, "实际值", formatAlertNumber(event.get("actualValue")));
        if (hasThreshold) {
            appendAlertPushLine(builder, "阈值", formatAlertNumber(event.get("threshold")));
        }
        if (hasAlertNumber(event.get("baselineValue"))) {
            appendAlertPushLine(builder, "历史基线", formatAlertNumber(event.get("baselineValue")));
        }
        if (hasAlertNumber(event.get("deviationRate"))) {
            appendAlertPushLine(builder, "偏离率", formatAlertPercent(event.get("deviationRate")));
        }
        if (hasAlertNumber(event.get("zScore"))) {
            appendAlertPushLine(builder, "Z-Score", formatAlertNumber(event.get("zScore")));
        }
        String filterExpression = firstText(rule.get("filterExpression"), rule.get("resolvedFilterExpression"));
        if (!filterExpression.isBlank()) {
            appendAlertPushLine(builder, "过滤条件", filterExpression);
        }
        builder.append("\n");

        appendAlertPushList(builder, "触发原因", reasons);
        appendAlertPushList(builder, "建议动作", suggestions);

        builder.append("【说明】\n");
        builder.append("- 异常判断以系统预警规则结果为准，请登录系统查看图表快照与处理详情。");
        return truncate(builder.toString(), 2000);
    }

    private String buildAlertPushFooter(Map<String, Object> event) {
        StringBuilder builder = new StringBuilder();
        builder.append("【追踪信息】\n");
        appendAlertPushLine(builder, "事件 ID", event.get("id"));
        appendAlertPushLine(builder, "规则 ID", event.get("ruleId"));
        appendAlertPushLine(builder, "时间桶", event.get("bucketName"));
        builder.append("- 查看方式：请登录系统查看预警图表快照与处理详情。");
        return builder.toString();
    }

    private void appendAlertPushLine(StringBuilder builder, String label, Object value) {
        builder.append("- ").append(label).append("：").append(alertValueOrDash(value)).append("\n");
    }

    private void appendAlertPushList(StringBuilder builder, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        builder.append("【").append(title).append("】\n");
        for (int i = 0; i < items.size(); i += 1) {
            builder.append(i + 1).append(". ").append(items.get(i)).append("\n");
        }
        builder.append("\n");
    }

    private String alertOperatorLabel(String operator) {
        return switch (normalizeAlertOperator(operator)) {
            case "gt" -> "高于阈值";
            case "zscore" -> "Z-Score 异常";
            default -> "低于阈值";
        };
    }

    private String alertRuleNameFromRequest(Map<String, Object> request,
                                            String tableName,
                                            String metricField,
                                            String operator,
                                            Double threshold,
                                            String filterExpression) {
        return alertRuleNameFromRequestWithFallback(request, null, tableName, metricField, operator, threshold, filterExpression);
    }

    private String alertRuleNameFromRequestWithFallback(Map<String, Object> request,
                                                        Object existingRuleName,
                                                        String tableName,
                                                        String metricField,
                                                        String operator,
                                                        Double threshold,
                                                        String filterExpression) {
        String explicit = firstText(
                request.get("ruleName"),
                request.get("alertName"),
                request.get("title"),
                request.get("sourceQuestion")
        );
        if (!explicit.isBlank()) {
            return truncate(explicit, 255);
        }
        String existing = text(existingRuleName);
        if (!existing.isBlank()) {
            return truncate(existing, 255);
        }
        return truncate(defaultAlertRuleDisplayName(tableName, metricField, operator, threshold, filterExpression), 255);
    }

    private String alertRuleDisplayName(Map<String, Object> rule, Map<String, Object> event) {
        String tableName = firstText(rule == null ? null : rule.get("tableName"), event == null ? null : event.get("tableName"));
        String metricField = firstText(rule == null ? null : rule.get("metricField"), event == null ? null : event.get("metricField"));
        String operator = firstText(rule == null ? null : rule.get("operator"), event == null ? null : event.get("operator"));
        Double threshold = hasAlertNumber(rule == null ? null : rule.get("threshold"))
                ? parseDouble(rule.get("threshold"), Double.NaN)
                : parseDouble(event == null ? null : event.get("threshold"), Double.NaN);
        String filterExpression = firstText(
                rule == null ? null : rule.get("filterExpression"),
                rule == null ? null : rule.get("resolvedFilterExpression")
        );
        String ruleName = firstText(
                rule == null ? null : rule.get("ruleName"),
                event == null ? null : event.get("ruleName")
        );
        if (!ruleName.isBlank()) {
            return ruleName;
        }
        return defaultAlertRuleDisplayName(tableName, metricField, operator, threshold, filterExpression);
    }

    private String defaultAlertRuleDisplayName(String tableName,
                                               String metricField,
                                               String operator,
                                               Double threshold,
                                               String filterExpression) {
        String metricLabel = alertFieldLabel(tableName, metricField, false);
        String condition = switch (normalizeAlertOperator(operator)) {
            case "gt" -> "高于 " + formatAlertNumber(threshold);
            case "zscore" -> "出现 Z-Score 异常";
            default -> "低于 " + formatAlertNumber(threshold);
        };
        String filter = text(filterExpression);
        return metricLabel + " " + condition + " 触发预警" + (filter.isBlank() ? "" : "（" + filter + "）");
    }

    private String alertTableLabel(String tableName) {
        String raw = text(tableName);
        if (raw.isBlank()) {
            return "-";
        }
        try {
            for (Map<String, Object> item : dataUploadService.listTables()) {
                if (!raw.equals(text(item.get("tableName")))) {
                    continue;
                }
                String label = firstText(item.get("displayName"), item.get("sourceName"), item.get("physicalTableName"), item.get("tableName"));
                return labelWithRawName(label, raw);
            }
        } catch (RuntimeException ignored) {
            // Table metadata is optional for push copy; raw table names are still usable.
        }
        return raw;
    }

    private String alertFieldLabel(String tableName, String field, boolean allowDisplayName) {
        String raw = text(field);
        if (raw.isBlank()) {
            return "-";
        }
        try {
            for (Map<String, Object> item : dataUploadService.listFields(tableName)) {
                boolean matched = raw.equals(text(item.get("columnName")))
                        || raw.equals(text(item.get("sourceFieldName")))
                        || raw.equals(text(item.get("displayName")))
                        || raw.equals(text(item.get("businessName")));
                if (!matched) {
                    continue;
                }
                String label = allowDisplayName
                        ? firstText(item.get("businessName"), item.get("displayName"), item.get("sourceFieldName"), item.get("fieldComment"), item.get("columnName"))
                        : firstText(item.get("businessName"), item.get("sourceFieldName"), item.get("displayName"), item.get("fieldComment"), item.get("columnName"));
                if (label.isBlank()) {
                    return raw;
                }
                return label;
            }
        } catch (RuntimeException ignored) {
            // Field metadata is optional for push copy; raw field names are still usable.
        }
        return raw;
    }

    private String labelWithRawName(String label, String raw) {
        String cleanLabel = text(label);
        String cleanRaw = text(raw);
        if (cleanLabel.isBlank()) {
            return cleanRaw.isBlank() ? "-" : cleanRaw;
        }
        if (cleanRaw.isBlank()
                || cleanLabel.equals(cleanRaw)
                || cleanLabel.contains("（" + cleanRaw + "）")
                || cleanLabel.contains("(" + cleanRaw + ")")) {
            return cleanLabel;
        }
        return cleanLabel + "（" + cleanRaw + "）";
    }

    private List<String> compactAlertPushItems(List<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(this::compactAlertPushText)
                .filter(item -> !item.isBlank())
                .limit(limit)
                .toList();
    }

    private String compactAlertPushText(Object value) {
        String compact = text(value)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (compact.length() <= 140) {
            return compact;
        }
        return compact.substring(0, 137) + "...";
    }

    private boolean hasAlertNumber(Object value) {
        double parsed = parseDouble(value, Double.NaN);
        return !Double.isNaN(parsed) && !Double.isInfinite(parsed);
    }

    private String formatAlertNumber(Object value) {
        String raw = text(value);
        if (raw.isBlank() || "nan".equalsIgnoreCase(raw) || "null".equalsIgnoreCase(raw)) {
            return "-";
        }
        double parsed = parseDouble(value, Double.NaN);
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            return raw;
        }
        double rounded = round(parsed);
        String formatted = String.format(Locale.US, "%,.2f", rounded);
        if (formatted.endsWith(".00")) {
            return formatted.substring(0, formatted.length() - 3);
        }
        while (formatted.endsWith("0")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted.endsWith(".") ? formatted.substring(0, formatted.length() - 1) : formatted;
    }

    private String formatAlertPercent(Object value) {
        return formatAlertNumber(value) + "%";
    }

    private String alertValueOrDash(Object value) {
        String raw = text(value);
        return raw.isBlank() || "null".equalsIgnoreCase(raw) || "nan".equalsIgnoreCase(raw) ? "-" : raw;
    }

    private Map<String, Object> generateAlertPushExplanation(Map<String, Object> rule, Map<String, Object> event) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tableName", text(event.get("tableName")));
        params.put("metricField", text(event.get("metricField")));
        params.put("timeField", text(event.get("timeField")));
        params.put("operator", text(event.get("operator")));
        params.put("threshold", event.get("threshold"));
        params.put("filterExpression", text(rule.get("filterExpression")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "alert");
        result.put("event", event);
        result.put("params", params);
        result.put("reason", text(event.get("reason")));
        result.put("chartSnapshot", event.getOrDefault("chartSnapshot", Map.of()));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "advanced-alert-push");
        context.put("eventId", event.get("id"));
        context.put("ruleId", event.get("ruleId"));
        return pythonAiService.explainAdvancedAnalysis("alert", "生成预警推送原因说明", result, context)
                .map(raw -> normalizeExplanation(raw, "rule", "规则解释"))
                .orElseGet(() -> fallbackAlertExplanation(result));
    }

    private Map<String, Object> alertRuleDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, org_scope AS orgScope, rule_name AS ruleName,
                       table_name AS tableName, metric_field AS metricField,
                       time_field AS timeField, granularity, filter_expression AS filterExpression,
                       resolved_filter_expression AS resolvedFilterExpression, operator,
                       threshold_value AS threshold, detection_cycle AS detectionCycle,
                       channels_json AS channelsJson, status, last_checked_at AS lastCheckedAt,
                       COALESCE(last_triggered_at, (
                         SELECT MAX(e.created_at)
                         FROM is_advanced_alert_event e
                         WHERE e.rule_id = is_advanced_alert_rule.id
                       )) AS lastTriggeredAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_advanced_alert_rule
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                LIMIT 1
                """, id, AuthContext.userId(), AuthContext.role());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("预警规则不存在或无权访问");
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        parseAlertRuleJsonFields(row);
        row.put("ruleName", firstText(row.get("ruleName"), defaultAlertRuleDisplayName(
                text(row.get("tableName")),
                text(row.get("metricField")),
                text(row.get("operator")),
                parseDouble(row.get("threshold"), Double.NaN),
                text(row.get("filterExpression"))
        )));
        attachAdvancedGraphContext(row, "预警 " + text(row.get("metricField")), text(row.get("tableName")));
        return row;
    }

    private Map<String, Object> planDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, plan_type AS planType, plan_name AS planName,
                       table_name AS tableName, metric_label AS metricLabel,
                       time_range_label AS timeRangeLabel, status, version_no AS versionNo,
                       last_calculated_at AS lastCalculatedAt, created_at AS createdAt, updated_at AS updatedAt,
                       request_json AS requestJson, result_json AS resultJson, llm_json AS llmJson,
                       field_mapping_json AS fieldMappingJson
                FROM is_advanced_analysis_plan
                WHERE id = ? AND status <> 'DELETED' AND (user_id = ? OR ? = 'ADMIN')
                LIMIT 1
                """, id, AuthContext.userId(), AuthContext.role());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("方案不存在或无权访问");
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        parsePlanJsonFields(row);
        return row;
    }

    private void insertPlanVersionSnapshot(Map<String, Object> plan) {
        long planId = parseLong(plan.get("id"), 0L);
        if (planId <= 0) {
            return;
        }
        int versionNo = parsePositiveInt(plan.get("versionNo"), 1);
        jdbcTemplate.update("""
                INSERT IGNORE INTO is_advanced_analysis_plan_version(
                  plan_id, user_id, plan_type, plan_name, version_no, request_json, result_json, llm_json, field_mapping_json
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON))
                """,
                planId,
                AuthContext.userId(),
                text(plan.get("planType")),
                text(plan.get("planName")),
                versionNo,
                toJson(plan.get("request")),
                toJson(plan.get("result")),
                toJson(plan.get("llm")),
                toJson(plan.get("fieldMapping")));
    }

    private Map<String, Object> normalizePlanVersionRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("request", parseJsonObject(result.remove("requestJson")));
        result.put("result", parseJsonObject(result.remove("resultJson")));
        result.put("llm", parseJsonObject(result.remove("llmJson")));
        result.put("fieldMapping", normalizePlanFieldMapping(
                text(result.get("planType")),
                asJsonObject(result.get("request")),
                asJsonObject(result.get("result")),
                parseJsonObject(result.remove("fieldMappingJson"))
        ));
        return result;
    }

    private Long attachPinnableChartHistory(Map<String, Object> plan) {
        if (plan == null || !"forecast".equals(text(plan.get("planType")))) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = plan.get("result") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        List<Map<String, Object>> chartData = forecastChartData(result);
        if (chartData.isEmpty()) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> fieldMapping = plan.get("fieldMapping") instanceof Map<?, ?> mapping
                ? new LinkedHashMap<>((Map<String, Object>) mapping)
                : Map.of();
        Map<String, Object> request = asJsonObject(plan.get("request"));
        Map<String, Object> resultParams = asJsonObject(result.get("params"));
        Map<String, Object> algorithmParams = new LinkedHashMap<>();
        algorithmParams.putAll(asJsonObject(result.get("algorithmParams")));
        asJsonObject(resultParams.get("algorithmParams")).forEach(algorithmParams::putIfAbsent);
        putIfPresent(algorithmParams, "alpha", firstText(algorithmParams.get("alpha"), resultParams.get("alpha"), request.get("alpha")));
        putIfPresent(algorithmParams, "beta", firstText(algorithmParams.get("beta"), resultParams.get("beta"), request.get("beta")));
        putIfPresent(algorithmParams, "gamma", firstText(algorithmParams.get("gamma"), resultParams.get("gamma"), request.get("gamma")));
        putIfPresent(algorithmParams, "seasonLength", firstText(algorithmParams.get("seasonLength"), resultParams.get("seasonLength"), request.get("seasonLength")));
        String algorithm = firstText(result.get("algorithm"), resultParams.get("algorithm"), request.get("algorithm"), algorithmParams.get("algorithm"), "Holt-Winters");
        int versionNo = parsePositiveInt(plan.get("versionNo"), 1);
        snapshot.put("tableName", text(plan.get("tableName")));
        snapshot.put("message", buildForecastDashboardTitle(plan, result, algorithm, versionNo));
        snapshot.put("chartType", "line");
        snapshot.put("fieldMapping", Map.of(
                "dimension", "预测周期",
                "dimensionKey", "name",
                "metric", text(plan.getOrDefault("metricLabel", "预测值")),
                "metricKey", "value"
        ));
        snapshot.put("dimensions", List.of("name", "history", "forecast", "upper", "lower", "value"));
        snapshot.put("encode", Map.of("x", "name", "y", "forecast"));
        snapshot.put("optionTemplate", forecastDashboardOptionTemplate());
        snapshot.put("data", chartData);
        snapshot.put("graphContext", result.getOrDefault("graphContext", List.of()));
        snapshot.put("graphPath", result.getOrDefault("graphPath", Map.of()));
        snapshot.put("graphSqlHints", result.getOrDefault("graphSqlHints", Map.of()));
        snapshot.put("cacheHit", parseBoolean(result.get("cacheHit"), false));
        snapshot.put("cacheKey", text(result.get("cacheKey")));
        snapshot.put("advancedAnalysisPlanId", plan.get("id"));
        snapshot.put("advancedAnalysisPlanName", text(plan.get("planName")));
        snapshot.put("advancedAnalysisPlanVersion", versionNo);
        snapshot.put("advancedAnalysisType", plan.get("planType"));
        List<Map<String, Object>> reasoningReplaySteps = asJsonObjectList(result.get("reasoningReplaySteps"));
        if (reasoningReplaySteps.isEmpty()) {
            reasoningReplaySteps = asJsonObjectList(result.get("reasoningLogs"));
        }
        Map<String, Object> llm = asJsonObject(plan.get("llm"));
        if (reasoningReplaySteps.isEmpty()) {
            reasoningReplaySteps = asJsonObjectList(llm.get("reasoningReplaySteps"));
        }
        if (reasoningReplaySteps.isEmpty()) {
            reasoningReplaySteps = asJsonObjectList(llm.get("thinkingLogs"));
        }
        if (!reasoningReplaySteps.isEmpty()) {
            snapshot.put("reasoningReplaySteps", reasoningReplaySteps);
            snapshot.put("reasoningLogs", reasoningReplaySteps);
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "advanced-analysis-plan-recalculate");
        action.put("label", "重新计算预测");
        action.put("planId", plan.get("id"));
        action.put("planVersion", versionNo);
        snapshot.put("advancedAnalysisAction", action);
        Map<String, Object> forecastMeta = new LinkedHashMap<>();
        forecastMeta.put("algorithm", algorithm);
        forecastMeta.put("confidence", firstText(result.get("confidence"), resultParams.get("confidence"), "95%"));
        forecastMeta.put("algorithmParams", algorithmParams);
        forecastMeta.put("granularity", firstText(result.get("granularity"), resultParams.get("granularity"), request.get("granularity"), fieldMapping.get("granularity")));
        forecastMeta.put("timeField", firstText(result.get("timeField"), resultParams.get("timeField"), request.get("timeField"), fieldMapping.get("timeField")));
        forecastMeta.put("metricField", firstText(result.get("metricField"), resultParams.get("metricField"), request.get("metricField"), fieldMapping.get("metricField")));
        forecastMeta.put("filterExpression", firstText(result.get("filterExpression"), resultParams.get("filterExpression"), request.get("filterExpression"), fieldMapping.get("filterExpression")));
        forecastMeta.put("dataQuality", result.getOrDefault("dataQuality", Map.of()));
        snapshot.put("forecastMeta", forecastMeta);
        Long executionTimeMs = Math.max(1L, parseLong(firstText(result.get("executionTimeMs"), resultParams.get("executionTimeMs")), 1L));
        Long historyId = chatQueryHistoryService.recordSuccess(
                text(plan.get("planName")),
                text(plan.get("tableName")),
                snapshot,
                executionTimeMs
        );
        if (historyId != null && historyId > 0) {
            attachForecastHistoryConversationMetadata(historyId, plan, result);
            result.put("queryHistoryId", historyId);
            result.put("chartId", historyId);
            plan.put("result", result);
            plan.put("queryHistoryId", historyId);
            plan.put("chartId", historyId);
            persistPlanResultLink(plan.get("id"), result);
            return historyId;
        }
        return null;
    }

    private void attachForecastHistoryConversationMetadata(Long historyId, Map<String, Object> plan, Map<String, Object> result) {
        Map<String, Object> llm = asJsonObject(plan.get("llm"));
        Map<String, Object> request = asJsonObject(plan.get("request"));
        long conversationId = parseLong(firstText(result.get("conversationId"), llm.get("conversationId"), request.get("conversationId")), 0L);
        long userTurnId = parseLong(firstText(result.get("userTurnId"), llm.get("userTurnId"), request.get("userTurnId")), 0L);
        long assistantTurnId = parseLong(firstText(result.get("assistantTurnId"), result.get("turnId"), llm.get("assistantTurnId"), request.get("assistantTurnId")), 0L);
        long artifactId = parseLong(firstText(result.get("artifactId"), llm.get("artifactId"), request.get("artifactId")), 0L);
        if (conversationId <= 0 && assistantTurnId > 0) {
            conversationId = conversationIdByTurnId(assistantTurnId);
        }
        if (conversationId <= 0 && artifactId > 0) {
            conversationId = conversationIdByArtifactId(artifactId);
        }
        if (assistantTurnId <= 0 && artifactId > 0) {
            assistantTurnId = turnIdByArtifactId(artifactId);
        }
        Integer turnNo = turnNoByTurnId(assistantTurnId);
        if (conversationId > 0) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("module", "advancedAnalysis");
            context.put("analysisType", text(plan.get("planType")));
            context.put("planId", plan.get("id"));
            if (userTurnId > 0) context.put("userTurnId", userTurnId);
            if (assistantTurnId > 0) context.put("assistantTurnId", assistantTurnId);
            if (artifactId > 0) context.put("artifactId", artifactId);
            context.put("engine", "advanced-analysis");
            chatQueryHistoryService.attachConversationMetadata(
                    historyId,
                    conversationId,
                    null,
                    turnNo,
                    "ASSISTANT",
                    advancedHistoryIntentType(text(plan.get("planType"))),
                    context,
                    Map.of("tableName", text(plan.get("tableName"))),
                    "ADVANCED_ANALYSIS",
                    "高级分析预测历史已关联原始对话"
            );
        }
        if (artifactId > 0) {
            jdbcTemplate.update("""
                    UPDATE is_chat_conversation_artifact
                       SET history_id = ?
                     WHERE id = ? AND (history_id IS NULL OR history_id = 0)
                    """, historyId, artifactId);
        }
    }

    private long conversationIdByTurnId(long turnId) {
        if (turnId <= 0) return 0L;
        try {
            Long value = jdbcTemplate.queryForObject("""
                    SELECT conversation_id FROM is_chat_conversation_turn WHERE id = ? LIMIT 1
                    """, Long.class, turnId);
            return value == null ? 0L : value;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long conversationIdByArtifactId(long artifactId) {
        if (artifactId <= 0) return 0L;
        try {
            Long value = jdbcTemplate.queryForObject("""
                    SELECT conversation_id FROM is_chat_conversation_artifact WHERE id = ? LIMIT 1
                    """, Long.class, artifactId);
            return value == null ? 0L : value;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long turnIdByArtifactId(long artifactId) {
        if (artifactId <= 0) return 0L;
        try {
            Long value = jdbcTemplate.queryForObject("""
                    SELECT turn_id FROM is_chat_conversation_artifact WHERE id = ? LIMIT 1
                    """, Long.class, artifactId);
            return value == null ? 0L : value;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private Integer turnNoByTurnId(long turnId) {
        if (turnId <= 0) return null;
        try {
            Integer value = jdbcTemplate.queryForObject("""
                    SELECT turn_no FROM is_chat_conversation_turn WHERE id = ? LIMIT 1
                    """, Integer.class, turnId);
            return value == null || value <= 0 ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String advancedHistoryIntentType(String planType) {
        String type = normalizePlanType(text(planType));
        return switch (type) {
            case "whatIf" -> "ADVANCED_WHAT_IF";
            case "alert" -> "ADVANCED_ALERT";
            default -> "ADVANCED_FORECAST";
        };
    }

    private void persistPlanResultLink(Object planIdValue, Map<String, Object> result) {
        long planId = parseLong(planIdValue, 0L);
        if (planId <= 0) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE is_advanced_analysis_plan
                SET result_json = CAST(? AS JSON)
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """,
                toJson(result),
                planId,
                AuthContext.userId(),
                AuthContext.role());
    }

    private List<Map<String, Object>> forecastChartData(Map<String, Object> result) {
        Object series = result.get("series");
        if (!(series instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String name = text(map.get("name"));
            double history = parseDouble(map.get("history"), Double.NaN);
            double forecast = parseDouble(map.get("forecast"), Double.NaN);
            double upper = parseDouble(map.get("upper"), Double.NaN);
            double lower = parseDouble(map.get("lower"), Double.NaN);
            double anomaly = parseDouble(map.get("anomaly"), Double.NaN);
            double value = Double.isNaN(forecast) ? history : forecast;
            if (name.isBlank() || Double.isNaN(value)) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("name", name);
            point.put("value", round(value));
            point.put("history", Double.isNaN(history) ? null : round(history));
            point.put("forecast", Double.isNaN(forecast) ? null : round(forecast));
            point.put("upper", Double.isNaN(upper) ? null : round(upper));
            point.put("lower", Double.isNaN(lower) ? null : round(lower));
            point.put("anomaly", Double.isNaN(anomaly) ? null : round(anomaly));
            point.put("phase", Double.isNaN(forecast) ? "history" : "forecast");
            data.add(point);
        }
        return data;
    }

    private int inferForecastHorizon(Map<String, Object> snapshot, int fallback) {
        Object data = snapshot == null ? null : snapshot.get("data");
        if (!(data instanceof List<?> list)) {
            return fallback;
        }
        int count = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            boolean forecast = map.get("forecast") != null
                    || map.get("upper") != null
                    || map.get("lower") != null
                    || "forecast".equalsIgnoreCase(text(map.get("phase")));
            if (forecast) {
                count += 1;
            }
        }
        return count > 0 ? Math.min(count, 60) : fallback;
    }

    private String buildForecastDashboardTitle(Map<String, Object> plan,
                                               Map<String, Object> result,
                                               String algorithm,
                                               int versionNo) {
        String planName = text(plan.get("planName"));
        String metric = firstText(plan.get("metricLabel"), result.get("metricField"), "预测值");
        String granularity = firstText(plan.get("timeRangeLabel"), result.get("granularity"));
        String suffix = algorithm.toLowerCase(Locale.ROOT).contains("prophet") ? "Prophet-like" : algorithm;
        return truncate((planName.isBlank() ? metric + "预测图表" : planName)
                + " / v" + versionNo
                + " / " + suffix
                + (granularity.isBlank() ? "" : " / " + granularity), 200);
    }

    private Map<String, Object> forecastDashboardOptionTemplate() {
        return Map.of(
                "tooltip", Map.of("trigger", "axis", "confine", true),
                "legend", Map.of("top", 2, "data", List.of("历史值", "预测值", "置信上界", "置信下界")),
                "grid", Map.of("left", 48, "right", 18, "top", 46, "bottom", 70, "containLabel", true),
                "series", List.of(
                        Map.of(
                                "name", "历史值",
                                "type", "line",
                                "encode", Map.of("x", "name", "y", "history"),
                                "showSymbol", false,
                                "connectNulls", false,
                                "lineStyle", Map.of("color", "#2563eb", "width", 2),
                                "itemStyle", Map.of("color", "#2563eb")
                        ),
                        Map.of(
                                "name", "预测值",
                                "type", "line",
                                "encode", Map.of("x", "name", "y", "forecast"),
                                "showSymbol", true,
                                "connectNulls", false,
                                "lineStyle", Map.of("color", "#16a34a", "width", 2, "type", "dashed"),
                                "itemStyle", Map.of("color", "#16a34a")
                        ),
                        Map.of(
                                "name", "置信上界",
                                "type", "line",
                                "encode", Map.of("x", "name", "y", "upper"),
                                "showSymbol", false,
                                "connectNulls", false,
                                "lineStyle", Map.of("color", "#93c5fd", "width", 1),
                                "areaStyle", Map.of("color", "rgba(147, 197, 253, 0.16)")
                        ),
                        Map.of(
                                "name", "置信下界",
                                "type", "line",
                                "encode", Map.of("x", "name", "y", "lower"),
                                "showSymbol", false,
                                "connectNulls", false,
                                "lineStyle", Map.of("color", "#93c5fd", "width", 1)
                        )
                )
        );
    }

    private long elapsedMs(long startedAt) {
        return Math.max(1L, System.currentTimeMillis() - startedAt);
    }

    private String forecastCacheKey(String scope, Map<String, Object> payload) {
        String normalizedPayload = toJson(normalizeCachePayload(payload));
        return "insight:advanced-analysis:forecast:v2:" + scope + ":" + sha256(normalizedPayload);
    }

    @SuppressWarnings("unchecked")
    private Object normalizeCachePayload(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> text(entry.getKey())))
                    .collect(Collectors.toMap(
                            entry -> text(entry.getKey()),
                            entry -> normalizeCachePayload(entry.getValue()),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalizeCachePayload).toList();
        }
        return value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toHexString(text(value).hashCode());
        }
    }

    private Map<String, Object> readForecastCache(String cacheKey) {
        String payload = redisGet(cacheKey);
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        return new LinkedHashMap<>(parseJsonObject(payload));
    }

    private void writeForecastCache(String cacheKey, Map<String, Object> result) {
        if (cacheKey == null || cacheKey.isBlank() || result == null || result.isEmpty()) {
            return;
        }
        redisSet(cacheKey, toJson(result), Math.max(60, forecastCacheTtlSeconds));
    }

    private void markForecastCacheHit(Map<String, Object> result, String cacheKey, long startedAt) {
        result.put("cacheHit", true);
        result.put("cacheKey", cacheKey);
        result.put("executionTimeMs", elapsedMs(startedAt));
        result.put("cacheSource", "redis");
    }

    private void attachAdvancedGraphContext(Map<String, Object> target, String question, String tableName) {
        if (target == null || target.isEmpty()) {
            return;
        }
        String q = firstText(question, target.get("metricField"), target.get("targetMetric"), target.get("metric"));
        String table = firstText(tableName, target.get("tableName"));
        if (q.isBlank() && table.isBlank()) {
            return;
        }
        try {
            Map<String, Object> graphPath = knowledgeGraphService.retrieveMultiHopContextSafely(q, table);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> graphContext = graphPath.get("ragContext") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            target.put("graphContext", graphContext);
            target.put("graphPath", graphPath);
            target.put("graphSqlHints", knowledgeGraphService.buildSqlMappingHints(q, table, graphContext));
        } catch (Exception ignored) {
            target.putIfAbsent("graphContext", List.of());
        }
    }

    private boolean redisSet(String key, String value, int ttlSeconds) {
        if (!forecastCacheEnabled || !redisEnabled || key == null || key.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        try {
            return redisCommand("SETEX", key, String.valueOf(Math.max(60, ttlSeconds)), value) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String redisGet(String key) {
        if (!forecastCacheEnabled || !redisEnabled || key == null || key.isBlank()) {
            return null;
        }
        try {
            String response = redisCommand("GET", key);
            return response == null || response.isBlank() ? null : response;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String redisCommand(String... args) throws Exception {
        try (Socket socket = new Socket(redisHost, redisPort)) {
            socket.setSoTimeout(1500);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            if (redisPassword != null && !redisPassword.isBlank()) {
                writeRedisCommand(out, "AUTH", redisPassword);
                readRedisReply(in);
            }
            if (redisDatabase > 0) {
                writeRedisCommand(out, "SELECT", String.valueOf(redisDatabase));
                readRedisReply(in);
            }
            writeRedisCommand(out, args);
            return readRedisReply(in);
        }
    }

    private void writeRedisCommand(OutputStream out, String... args) throws Exception {
        StringBuilder command = new StringBuilder("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = Objects.toString(arg, "").getBytes(StandardCharsets.UTF_8);
            command.append("$").append(bytes.length).append("\r\n")
                    .append(Objects.toString(arg, "")).append("\r\n");
        }
        out.write(command.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String readRedisReply(InputStream in) throws Exception {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        String line = readRedisLine(in);
        if (type == '+') {
            return line;
        }
        if (type == '-') {
            throw new IllegalStateException(line);
        }
        if (type == ':') {
            return line;
        }
        if (type == '$') {
            int length = Integer.parseInt(line);
            if (length < 0) {
                return null;
            }
            byte[] body = in.readNBytes(length);
            in.readNBytes(2);
            return new String(body, StandardCharsets.UTF_8);
        }
        return line;
    }

    private String readRedisLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prev = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (prev == '\r' && current == '\n') {
                byte[] bytes = out.toByteArray();
                int length = Math.max(0, bytes.length - 1);
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            out.write(current);
            prev = current;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private void parseAlertRuleJsonFields(Map<String, Object> row) {
        row.put("channels", parseJsonList(row.remove("channelsJson")));
    }

    private void parseAlertEventJsonFields(Map<String, Object> row) {
        row.put("chartSnapshot", parseJsonObject(row.remove("chartSnapshotJson")));
        row.put("llmExplanation", parseJsonObject(row.remove("llmExplanationJson")));
    }

    private void parseAlertPushLogJsonFields(Map<String, Object> row) {
        row.put("request", parseJsonObject(row.remove("requestJson")));
        row.put("response", parseJsonObject(row.remove("responseJson")));
    }

    private void parsePlanJsonFields(Map<String, Object> row) {
        Map<String, Object> request = parseJsonObject(row.remove("requestJson"));
        Map<String, Object> result = parseJsonObject(row.remove("resultJson"));
        Map<String, Object> llm = parseJsonObject(row.remove("llmJson"));
        Map<String, Object> fieldMapping = parseJsonObject(row.remove("fieldMappingJson"));
        row.put("request", request);
        row.put("result", result);
        row.put("llm", llm);
        row.put("fieldMapping", normalizePlanFieldMapping(text(row.get("planType")), request, result, fieldMapping));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizePlanFieldMapping(String planType,
                                                          Map<String, Object> request,
                                                          Map<String, Object> result,
                                                          Map<String, Object> explicitMapping) {
        Map<String, Object> requestSource = request == null ? Map.of() : request;
        Map<String, Object> resultSource = result == null ? Map.of() : result;
        Map<String, Object> mapping = new LinkedHashMap<>();
        if (explicitMapping != null && !explicitMapping.isEmpty()) {
            mapping.putAll(explicitMapping);
        }
        Map<String, Object> params = resultSource.get("params") instanceof Map<?, ?> paramsMap
                ? new LinkedHashMap<>((Map<String, Object>) paramsMap)
                : Map.of();
        String type = normalizePlanType(text(planType));
        String tableName = firstText(mapping.get("tableName"), requestSource.get("tableName"), params.get("tableName"), resultSource.get("tableName"));
        if (!tableName.isBlank()) {
            mapping.put("tableName", tableName);
        }
        if ("forecast".equals(type)) {
            putIfPresent(mapping, "timeField", firstText(mapping.get("timeField"), requestSource.get("timeField"), params.get("timeField"), resultSource.get("timeField")));
            putIfPresent(mapping, "metricField", firstText(mapping.get("metricField"), requestSource.get("metricField"), params.get("metricField"), resultSource.get("metricField")));
            putIfPresent(mapping, "granularity", firstText(mapping.get("granularity"), requestSource.get("granularity"), params.get("granularity"), resultSource.get("granularity")));
            putIfPresent(mapping, "filterExpression", firstText(mapping.get("filterExpression"), requestSource.get("filterExpression"), params.get("filterExpression")));
            putIfPresent(mapping, "metricLabel", firstText(mapping.get("metricLabel"), resultSource.get("metric"), requestSource.get("metric"), resultSource.get("metricField")));
            mapping.put("mappingType", "forecast");
            mapping.put("confirmed", true);
            return mapping;
        }
        if ("whatIf".equals(type)) {
            putIfPresent(mapping, "targetMetric", firstText(mapping.get("targetMetric"), requestSource.get("targetMetric"), params.get("targetMetric"), resultSource.get("targetMetric")));
            putIfPresent(mapping, "formula", firstText(mapping.get("formula"), requestSource.get("formula"), params.get("formula"), resultSource.get("formula")));
            putIfPresent(mapping, "formulaScope", firstText(mapping.get("formulaScope"), requestSource.get("formulaScope"), params.get("formulaScope"), resultSource.get("formulaScope")));
            Object variables = mapping.get("variables");
            if (!(variables instanceof List<?>)) {
                variables = requestSource.get("variables") instanceof List<?> ? requestSource.get("variables") : params.get("variables");
            }
            if (variables instanceof List<?> list) {
                mapping.put("variables", list);
            }
            mapping.put("mappingType", "whatIf");
            mapping.put("confirmed", true);
            return mapping;
        }
        return mapping;
    }

    private String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        String text = text(value);
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }

    @SuppressWarnings("unchecked")
    private void hydrateLegacyAlertSnapshot(Map<String, Object> event, boolean persist) {
        Map<String, Object> snapshot = event.get("chartSnapshot") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        Object dataValue = snapshot.get("data");
        if (dataValue instanceof List<?> list && list.size() > 1) {
            return;
        }
        long ruleId = parseLong(event.get("ruleId"), 0L);
        if (ruleId <= 0) {
            return;
        }
        List<Map<String, Object>> ruleRows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, org_scope AS orgScope, rule_name AS ruleName,
                       table_name AS tableName, metric_field AS metricField,
                       time_field AS timeField, granularity, filter_expression AS filterExpression,
                       resolved_filter_expression AS resolvedFilterExpression, operator,
                       threshold_value AS threshold, detection_cycle AS detectionCycle,
                       channels_json AS channelsJson, status, last_checked_at AS lastCheckedAt,
                       last_triggered_at AS lastTriggeredAt, created_at AS createdAt, updated_at AS updatedAt
                FROM is_advanced_alert_rule
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                LIMIT 1
                """, ruleId, AuthContext.userId(), AuthContext.role());
        if (ruleRows.isEmpty()) {
            return;
        }
        Map<String, Object> rule = new LinkedHashMap<>(ruleRows.get(0));
        String tableName = text(rule.get("tableName"));
        String timeField = text(rule.get("timeField"));
        String metricField = text(rule.get("metricField"));
        String granularity = normalizeGranularity(text(rule.getOrDefault("granularity", "day")));
        String filterExpression = text(rule.get("resolvedFilterExpression"));
        if (tableName.isBlank() || timeField.isBlank() || metricField.isBlank()) {
            return;
        }
        String bucketName = text(event.get("bucketName"));
        List<Point> history = loadSeriesAroundBucket(tableName, timeField, metricField, granularity, bucketName, filterExpression);
        if (history.size() < 2) {
            return;
        }
        Point point = history.stream()
                .filter(item -> item.name().equals(bucketName))
                .findFirst()
                .orElseGet(() -> new Point(bucketName, parseDouble(event.get("actualValue"), 0D)));
        double threshold = parseDouble(event.get("threshold"), parseDouble(rule.get("threshold"), Double.NaN));
        double baseline = parseDouble(event.get("baselineValue"), history.stream()
                .limit(Math.max(1, history.size() - 1))
                .mapToDouble(Point::value)
                .average()
                .orElse(0D));
        double zScore = parseDouble(event.get("zScore"), 0D);
        Map<String, Object> hydrated = buildAlertChartSnapshot(rule, point, threshold, baseline, zScore, history);
        event.put("chartSnapshot", hydrated);
        event.put("snapshotHydrated", true);
        if (persist) {
            jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET chart_snapshot_json = CAST(? AS JSON)
                    WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                    """, toJson(hydrated), parseLong(event.get("id"), 0L), AuthContext.userId(), AuthContext.role());
        }
    }

    private String normalizePlanType(String value) {
        String text = value == null ? "" : value.trim();
        if (List.of("forecast", "whatIf").contains(text)) {
            return text;
        }
        throw new IllegalArgumentException("仅支持保存预测或 What-if 推演方案");
    }

    private String normalizeOptionalPlanType(String value) {
        String text = value == null ? "" : value.trim();
        return List.of("forecast", "whatIf").contains(text) ? text : "";
    }

    private String normalizeAlertOperator(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("lt", "gt", "zscore").contains(text)) {
            return text;
        }
        return "lt";
    }

    private String normalizeDetectionCycle(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("hourly", "daily", "weekly", "monthly").contains(text)) {
            return text;
        }
        return "daily";
    }

    private String normalizeAlertRuleStatus(String value) {
        String text = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (List.of("ACTIVE", "DISABLED", "DELETED").contains(text)) {
            return text;
        }
        return "ACTIVE";
    }

    private String normalizeAlertEventStatus(String value) {
        String text = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (List.of("ACK", "ACKED", "CONFIRM", "CONFIRMED", "确认").contains(text)) {
            return "ACK";
        }
        if (List.of("CLOSE", "CLOSED", "DONE", "RESOLVED", "关闭").contains(text)) {
            return "CLOSED";
        }
        if (List.of("OPEN", "REOPEN", "REOPENED", "重新打开").contains(text)) {
            return "OPEN";
        }
        return "ACK";
    }

    private List<String> normalizeChannels(Object value) {
        List<String> raw = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                raw.add(text(item).toLowerCase(Locale.ROOT));
            }
        } else {
            String text = text(value).toLowerCase(Locale.ROOT);
            if ("both".equals(text)) {
                raw.add("email");
                raw.add("dingtalk");
            } else if (!text.isBlank()) {
                raw.add(text);
            }
        }
        List<String> channels = raw.stream()
                .filter(item -> List.of("email", "dingtalk").contains(item))
                .distinct()
                .toList();
        return channels.isEmpty() ? List.of("email", "dingtalk") : channels;
    }

    private String normalizePushChannel(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("dingtalk".equals(text) || "钉钉".equals(text)) {
            return "dingtalk";
        }
        return "email";
    }

    private String pushTarget(String channel) {
        return "dingtalk".equals(normalizePushChannel(channel)) ? text(dingtalkWebhook) : text(alertEmailTarget);
    }

    private boolean emailPushConfigured() {
        return !text(alertEmailTarget).isBlank()
                && !text(alertSmtpHost).isBlank()
                && !text(alertSmtpUsername).isBlank()
                && !text(alertSmtpPassword).isBlank();
    }

    private String maskPushTarget(Object value) {
        String text = text(value);
        if (text.isBlank()) {
            return "";
        }
        if (text.contains("@")) {
            int at = text.indexOf('@');
            String prefix = text.substring(0, Math.min(2, at));
            return prefix + "***" + text.substring(at);
        }
        if (text.length() <= 16) {
            return "***";
        }
        return text.substring(0, 10) + "***" + text.substring(text.length() - 6);
    }

    private String toJson(Object value) {
        try {
            Object safeValue = value == null ? Map.of() : value;
            return objectMapper.writeValueAsString(safeValue);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败");
        }
    }

    private List<Object> parseJsonList(Object value) {
        String text = text(value);
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> parseJsonObject(Object value) {
        String text = text(value);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asJsonObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        String text = text(value);
        if (text.isBlank()) {
            return Map.of();
        }
        return parseJsonObject(text);
    }

    private String truncate(String value, int maxLength) {
        String text = text(value);
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String nextBucketName(String lastName, int offset, String granularity) {
        try {
            return switch (normalizeGranularity(granularity)) {
                case "day" -> LocalDate.parse(lastName).plusDays(offset).format(DateTimeFormatter.ISO_LOCAL_DATE);
                case "week" -> nextWeekBucketName(lastName, offset);
                case "quarter" -> nextQuarterBucketName(lastName, offset);
                case "year" -> String.valueOf(Integer.parseInt(lastName) + offset);
                default -> LocalDate.parse(lastName.length() == 7 ? lastName + "-01" : lastName)
                        .plusMonths(offset)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM"));
            };
        } catch (DateTimeParseException ignored) {
            return "未来" + offset;
        } catch (RuntimeException ignored) {
            return "未来" + offset;
        }
    }

    private String nextWeekBucketName(String lastName, int offset) {
        String[] parts = lastName.split("-W");
        int year = Integer.parseInt(parts[0]);
        int week = Integer.parseInt(parts[1]);
        WeekFields iso = WeekFields.ISO;
        LocalDate date = LocalDate.of(year, 1, 4)
                .with(iso.weekBasedYear(), year)
                .with(iso.weekOfWeekBasedYear(), week)
                .with(iso.dayOfWeek(), 1)
                .plusWeeks(offset);
        int nextYear = date.get(iso.weekBasedYear());
        int nextWeek = date.get(iso.weekOfWeekBasedYear());
        return "%d-W%02d".formatted(nextYear, nextWeek);
    }

    private String nextQuarterBucketName(String lastName, int offset) {
        String[] parts = lastName.split("-Q");
        int year = Integer.parseInt(parts[0]);
        int quarter = Integer.parseInt(parts[1]);
        int total = (year * 4) + quarter - 1 + offset;
        return "%d-Q%d".formatted(total / 4, total % 4 + 1);
    }

    private List<String> alertSnapshotBucketWindow(String bucketName, String granularity) {
        String normalized = normalizeGranularity(granularity);
        String name = text(bucketName);
        if (name.isBlank()) {
            return List.of();
        }
        try {
            List<String> names = new ArrayList<>();
            for (int offset = -8; offset <= 3; offset += 1) {
                String item = offset == 0 ? name : nextBucketName(name, offset, normalized);
                if (!item.isBlank() && !item.startsWith("未来")) {
                    names.add(item);
                }
            }
            return names.stream().distinct().sorted(this::compareBucketName).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private int compareBucketName(String left, String right) {
        return left.compareTo(right);
    }

    private String sqlStringLiteral(String value) {
        return "'" + text(value).replace("'", "''") + "'";
    }

    private String inferGranularity(List<Point> history) {
        String name = history.isEmpty() ? "" : history.get(history.size() - 1).name();
        if (name.matches("\\d{4}-\\d{2}-\\d{2}")) return "day";
        if (name.matches("\\d{4}-W\\d{2}")) return "week";
        if (name.matches("\\d{4}-Q[1-4]")) return "quarter";
        if (name.matches("\\d{4}")) return "year";
        return "month";
    }

    private Map<String, Object> dataQuality(List<Point> history, SeriesPreprocessResult preprocess) {
        double avg = history.stream().mapToDouble(Point::value).average().orElse(0D);
        double std = standardDeviation(history);
        int filledCount = preprocess == null ? 0 : preprocess.filledCount();
        int duplicateCount = preprocess == null ? 0 : preprocess.duplicateCount();
        int outlierCount = preprocess == null ? 0 : preprocess.outlierAdjustedCount();
        String preprocessMessage = buildPreprocessMessage(filledCount, duplicateCount, outlierCount);
        String baseMessage = history.size() >= 8 ? "数据量满足基础预测要求" : "数据点偏少，预测不确定性较高";
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("points", history.size());
        quality.put("average", round(avg));
        quality.put("stdDev", round(std));
        quality.put("filledMissingPoints", filledCount);
        quality.put("mergedDuplicatePoints", duplicateCount);
        quality.put("outlierAdjustedPoints", outlierCount);
        quality.put("message", preprocessMessage.isBlank() ? baseMessage : baseMessage + "；" + preprocessMessage);
        return quality;
    }

    private Map<String, Double> rawAnomalyValues(SeriesPreprocessResult preprocess) {
        if (preprocess == null || preprocess.rawAnomalies() == null || preprocess.rawAnomalies().isEmpty()) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (Point point : preprocess.rawAnomalies()) {
            if (point != null && !point.name().isBlank()) {
                result.put(point.name(), round(point.value()));
            }
        }
        return result;
    }

    private List<Map<String, Object>> attachAnomalyPoints(List<Map<String, Object>> series, Map<String, Double> anomalies) {
        if (series == null || series.isEmpty() || anomalies == null || anomalies.isEmpty()) {
            return series == null ? List.of() : series;
        }
        List<Map<String, Object>> result = new ArrayList<>(series.size());
        for (Map<String, Object> row : series) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            String name = text(copy.get("name"));
            if (anomalies.containsKey(name)) {
                copy.put("anomaly", anomalies.get(name));
            }
            result.add(copy);
        }
        return result;
    }

    private String buildPreprocessMessage(int filledCount, int duplicateCount, int outlierCount) {
        List<String> parts = new ArrayList<>();
        if (filledCount > 0) {
            parts.add("已补齐 " + filledCount + " 个缺失时间点");
        }
        if (duplicateCount > 0) {
            parts.add("已合并 " + duplicateCount + " 个重复时间点");
        }
        if (outlierCount > 0) {
            parts.add("已按 3σ 截尾处理 " + outlierCount + " 个异常点");
        }
        return String.join("，", parts);
    }

    private double standardDeviation(List<Point> history) {
        double avg = history.stream().mapToDouble(Point::value).average().orElse(0D);
        double variance = history.stream().mapToDouble(point -> Math.pow(point.value() - avg, 2)).average().orElse(0D);
        return Math.sqrt(variance);
    }

    private Map<String, Object> row(String name, Object history, Object forecast, Object upper, Object lower) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("history", history);
        row.put("forecast", forecast);
        row.put("upper", upper);
        row.put("lower", lower);
        return row;
    }

    private String dateBucketExpr(String field, String granularity) {
        String column = "`" + field + "`";
        return switch (granularity) {
            case "day" -> "DATE_FORMAT(" + column + ", '%Y-%m-%d')";
            case "week" -> "DATE_FORMAT(" + column + ", '%x-W%v')";
            case "quarter" -> "CONCAT(YEAR(" + column + "), '-Q', QUARTER(" + column + "))";
            case "year" -> "DATE_FORMAT(" + column + ", '%Y')";
            default -> "DATE_FORMAT(" + column + ", '%Y-%m')";
        };
    }

    private String numericExpr(String field) {
        return "CAST(NULLIF(TRIM(`" + field + "`), '') AS DECIMAL(20,4))";
    }

    private String normalizeGranularity(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("day", "week", "month", "quarter", "year").contains(text)) {
            return text;
        }
        return "month";
    }

    private void validateField(String tableName, String field, boolean date) {
        List<Map<String, Object>> fields = dataUploadService.listFields(tableName);
        Map<String, Object> matched = fields.stream()
                .filter(item -> field.equals(Objects.toString(item.get("columnName"), "")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("字段不存在或无权限访问: " + field));
        if (date && !isDateField(matched)) {
            throw new IllegalArgumentException("请选择日期/时间字段: " + field);
        }
        if (!date && !isNumericField(matched)) {
            throw new IllegalArgumentException("请选择数值型指标字段: " + field);
        }
    }

    private boolean isDateField(Map<String, Object> field) {
        String type = text(field.getOrDefault("fieldType", field.getOrDefault("dataType", ""))).toUpperCase(Locale.ROOT);
        String label = (text(field.get("displayName")) + " "
                + text(field.get("sourceFieldName")) + " "
                + text(field.get("fieldComment")) + " "
                + text(field.get("columnName"))).toLowerCase(Locale.ROOT);
        return type.contains("DATE") || type.contains("TIME") || label.contains("date") || label.contains("time")
                || label.contains("day") || label.contains("month") || label.contains("year")
                || label.contains("日期") || label.contains("时间") || label.contains("订单日")
                || label.contains("月份") || label.contains("年度") || label.contains("年月");
    }

    private boolean isNumericField(Map<String, Object> field) {
        String type = text(field.getOrDefault("fieldType", field.getOrDefault("dataType", ""))).toUpperCase(Locale.ROOT);
        return type.contains("NUMBER") || type.contains("INT") || type.contains("DECIMAL")
                || type.contains("DOUBLE") || type.contains("FLOAT");
    }

    private List<Map<String, Object>> query(String tableName, String sql) {
        if (datasourceService.isOfficialSource(tableName)) {
            return datasourceService.executeQueryWithoutAudit(tableName, sql);
        }
        return jdbcTemplate.queryForList(sql);
    }

    private String physicalTable(String tableName) {
        return datasourceService.isOfficialSource(tableName) ? datasourceService.physicalTableName(tableName) : tableName;
    }

    private String required(Map<String, Object> request, String key) {
        String value = text(request.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        return value;
    }

    private void assertCanAccessAdvancedTable(String tableName) {
        dataUploadService.assertKnownTable(tableName);
    }

    private String currentOrgScope() {
        AuthContext.UserPrincipal principal = currentPrincipalOrNull();
        if (principal == null) {
            return "SYSTEM";
        }
        String role = text(principal.role()).toUpperCase(Locale.ROOT);
        return role.isBlank() ? "ROLE:USER" : "ROLE:" + role;
    }

    private AuthContext.UserPrincipal currentPrincipalOrNull() {
        try {
            return AuthContext.get();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private String orgScopeFromRule(Map<String, Object> rule) {
        String scope = text(rule.get("orgScope"));
        return scope.isBlank() ? "GLOBAL" : scope;
    }

    private String alertOrgScope(Map<String, Object> event, Map<String, Object> rule) {
        String scope = text(event.get("orgScope"));
        return scope.isBlank() ? orgScopeFromRule(rule) : scope;
    }

    private String roleFromOrgScope(String orgScope, String userId) {
        String scope = text(orgScope);
        if (scope.toUpperCase(Locale.ROOT).startsWith("ROLE:")) {
            String role = scope.substring("ROLE:".length()).trim();
            if (!role.isBlank()) {
                return role;
            }
        }
        List<String> roles = jdbcTemplate.queryForList("""
                SELECT role FROM is_user WHERE user_id = ? LIMIT 1
                """, String.class, userId);
        return roles.isEmpty() || text(roles.get(0)).isBlank() ? "USER" : text(roles.get(0)).toUpperCase(Locale.ROOT);
    }

    private AuthContext.UserPrincipal principalFromAlertRule(Map<String, Object> rule) {
        String userId = text(rule.get("userId"));
        if (userId.isBlank()) {
            return currentPrincipalOrNull();
        }
        String role = roleFromOrgScope(orgScopeFromRule(rule), userId);
        return new AuthContext.UserPrincipal(null, userId, userId, userId, role);
    }

    private <T> T withAlertRulePrincipal(Map<String, Object> rule, Supplier<T> action) {
        AuthContext.UserPrincipal previous = currentPrincipalOrNull();
        AuthContext.UserPrincipal scoped = principalFromAlertRule(rule);
        if (scoped != null) {
            AuthContext.set(scoped);
        }
        try {
            return action.get();
        } finally {
            if (previous != null) {
                AuthContext.set(previous);
            } else {
                AuthContext.clear();
            }
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String sanitizeFilterExpression(Object value) {
        String expression = text(value);
        if (expression.isBlank()) {
            return "";
        }
        String lower = expression.toLowerCase(Locale.ROOT);
        if (!expression.matches("^[\\p{L}\\p{N}_\\s`\"'.=<>!%(),:-]+$")) {
            throw new IllegalArgumentException("过滤条件仅支持安全的只读表达式");
        }
        if (lower.contains(";") || lower.contains("--") || lower.contains("/*")) {
            throw new IllegalArgumentException("过滤条件包含非法字符");
        }
        if (lower.matches(".*\\b(drop|delete|update|insert|alter|truncate|create|grant|revoke|execute|union|sleep|benchmark)\\b.*")) {
            throw new IllegalArgumentException("过滤条件仅支持只读筛选表达式");
        }
        return expression;
    }

    private String resolveFilterExpression(String tableName, String filterExpression) {
        if (filterExpression.isBlank()) {
            return "";
        }
        Map<String, String> aliases = filterAliasMap(tableName);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < filterExpression.length();) {
            char ch = filterExpression.charAt(i);
            if (ch == '\'' || ch == '"') {
                int end = i + 1;
                while (end < filterExpression.length()) {
                    char current = filterExpression.charAt(end);
                    if (current == ch) {
                        end += 1;
                        break;
                    }
                    end += current == '\\' && end + 1 < filterExpression.length() ? 2 : 1;
                }
                result.append(filterExpression, i, Math.min(end, filterExpression.length()));
                i = end;
                continue;
            }
            if (ch == '`') {
                int end = filterExpression.indexOf('`', i + 1);
                if (end > i) {
                    String token = filterExpression.substring(i + 1, end);
                    result.append(resolveFilterToken(token, aliases));
                    i = end + 1;
                    continue;
                }
            }
            if (isFilterIdentifierChar(ch)) {
                int end = i + 1;
                while (end < filterExpression.length() && isFilterIdentifierChar(filterExpression.charAt(end))) {
                    end += 1;
                }
                String token = filterExpression.substring(i, end);
                result.append(resolveFilterToken(token, aliases));
                i = end;
                continue;
            }
            result.append(ch);
            i += 1;
        }
        return result.toString();
    }

    private String resolveFilterToken(String token, Map<String, String> aliases) {
        String trimmed = text(token).replace("`", "");
        if (trimmed.isBlank() || trimmed.matches("\\d+(\\.\\d+)?") || isSqlKeyword(trimmed)) {
            return token;
        }
        String column = resolveFieldAlias(aliases, trimmed);
        return column == null ? token : "`" + column + "`";
    }

    private Map<String, String> filterAliasMap(String tableName) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Map<String, Object> field : dataUploadService.listFields(tableName)) {
            String column = text(field.get("columnName"));
            if (column.isBlank()) {
                continue;
            }
            addFieldAliases(aliases, field, column);
            addSemanticFilterAliases(aliases, field, column);
            for (String synonym : text(field.get("synonyms")).split("[,，;；、|/\\s]+")) {
                addFilterAlias(aliases, synonym, column);
            }
        }
        return aliases;
    }

    private void addFieldAliases(Map<String, String> aliases, Map<String, Object> field, String column) {
        addExactFilterAlias(aliases, field.get("columnName"), column);
        addExactFilterAlias(aliases, field.get("sourceFieldName"), column);
        addExactFilterAlias(aliases, field.get("displayName"), column);
        addExactFilterAlias(aliases, field.get("businessName"), column);
        addExactFilterAlias(aliases, field.get("fieldComment"), column);
    }

    private void addSemanticFilterAliases(Map<String, String> aliases, Map<String, Object> field, String column) {
        String text = (text(field.get("columnName")) + " "
                + text(field.get("sourceFieldName")) + " "
                + text(field.get("displayName")) + " "
                + text(field.get("businessName")) + " "
                + text(field.get("fieldComment")) + " "
                + text(field.get("synonyms"))).toLowerCase(Locale.ROOT);
        if (containsAny(text, "region", "area", "province", "city", "区域", "地区", "大区", "省份", "省", "城市")) {
            addFilterAlias(aliases, "region", column);
            addFilterAlias(aliases, "area", column);
            addFilterAlias(aliases, "province", column);
            addFilterAlias(aliases, "city", column);
            addFilterAlias(aliases, "区域", column);
            addFilterAlias(aliases, "地区", column);
            addFilterAlias(aliases, "大区", column);
        }
        if (containsAny(text, "channel", "渠道", "来源", "通路")) {
            addFilterAlias(aliases, "channel", column);
            addFilterAlias(aliases, "渠道", column);
        }
        if (containsAny(text, "category", "type", "kind", "class", "品类", "类别", "分类", "类型", "产品线")) {
            addFilterAlias(aliases, "category", column);
            addFilterAlias(aliases, "type", column);
            addFilterAlias(aliases, "品类", column);
            addFilterAlias(aliases, "类别", column);
            addFilterAlias(aliases, "产品线", column);
        }
        if (containsAny(text, "customer", "client", "客户", "客群")) {
            addFilterAlias(aliases, "customer", column);
            addFilterAlias(aliases, "client", column);
            addFilterAlias(aliases, "客户", column);
        }
        if (containsAny(text, "product", "sku", "商品", "产品")) {
            addFilterAlias(aliases, "product", column);
            addFilterAlias(aliases, "sku", column);
            addFilterAlias(aliases, "产品", column);
        }
        if (containsAny(text, "sales", "sale_amt", "sales_amt", "salesamount", "revenue", "amount", "gmv", "销售", "销售额", "销售金额", "营收", "收入")) {
            addFilterAlias(aliases, "sales", column);
            addFilterAlias(aliases, "sales_amt", column);
            addFilterAlias(aliases, "salesamt", column);
            addFilterAlias(aliases, "销售", column);
            addFilterAlias(aliases, "销售额", column);
            addFilterAlias(aliases, "销售金额", column);
            addFilterAlias(aliases, "营收", column);
            addFilterAlias(aliases, "收入", column);
        }
        if (containsAny(text, "profit", "margin", "利润", "毛利", "净利")) {
            addFilterAlias(aliases, "profit", column);
            addFilterAlias(aliases, "利润", column);
            addFilterAlias(aliases, "毛利", column);
            addFilterAlias(aliases, "净利", column);
        }
        if (containsAny(text, "cost", "expense", "成本", "费用", "支出")) {
            addFilterAlias(aliases, "cost", column);
            addFilterAlias(aliases, "expense", column);
            addFilterAlias(aliases, "成本", column);
            addFilterAlias(aliases, "费用", column);
        }
        if (containsAny(text, "qty", "quantity", "volume", "销量", "数量", "件数", "订单量")) {
            addFilterAlias(aliases, "qty", column);
            addFilterAlias(aliases, "quantity", column);
            addFilterAlias(aliases, "销量", column);
            addFilterAlias(aliases, "数量", column);
            addFilterAlias(aliases, "件数", column);
        }
        if (containsAny(text, "discount", "折扣", "折让", "优惠")) {
            addFilterAlias(aliases, "discount", column);
            addFilterAlias(aliases, "折扣", column);
            addFilterAlias(aliases, "优惠", column);
        }
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void addFilterAlias(Map<String, String> aliases, Object alias, String column) {
        String text = text(alias);
        String normalized = normalizeAlias(text);
        if (!normalized.isBlank()) {
            aliases.putIfAbsent(normalized, column);
        }
        String loose = normalizeLooseAlias(text);
        if (!loose.isBlank()) {
            aliases.putIfAbsent(loose, column);
        }
    }

    private void addExactFilterAlias(Map<String, String> aliases, Object alias, String column) {
        String text = text(alias);
        String normalized = normalizeAlias(text);
        if (!normalized.isBlank()) {
            aliases.put(normalized, column);
        }
        String loose = normalizeLooseAlias(text);
        if (!loose.isBlank()) {
            aliases.put(loose, column);
        }
    }

    private boolean isFilterIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private boolean isSqlKeyword(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return List.of("and", "or", "not", "in", "is", "null", "like", "between", "true", "false").contains(lower);
    }

    private String normalizeAlias(String value) {
        return value == null ? "" : value.trim().replace("`", "").toLowerCase(Locale.ROOT);
    }

    private String normalizeLooseAlias(String value) {
        return normalizeAlias(value).replaceAll("[\\s_\\-./]+", "");
    }

    private String resolveFieldAlias(Map<String, String> aliases, String value) {
        String exact = aliases.get(normalizeAlias(value));
        if (exact != null) {
            return exact;
        }
        return aliases.get(normalizeLooseAlias(value));
    }

    private int parsePositiveInt(Object value, int fallback) {
        try {
            int parsed = Integer.parseInt(text(value));
            return Math.max(1, Math.min(parsed, 60));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long parseLong(Object value, long fallback) {
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean parseBoolean(Object value, boolean fallback) {
        String text = text(value).toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "y", "on", "是", "启用").contains(text)) {
            return true;
        }
        if (List.of("false", "0", "no", "n", "off", "否", "停用").contains(text)) {
            return false;
        }
        return fallback;
    }

    private double parseDouble(Object value, double fallback) {
        String raw = text(value);
        if (raw.isBlank()) {
            return fallback;
        }
        String normalized = raw
                .replace(",", "")
                .replace("，", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace("$", "")
                .replace("元", "")
                .trim();
        double multiplier = 1D;
        if (normalized.contains("%")) {
            multiplier = 0.01D;
            normalized = normalized.replace("%", "");
        }
        if (normalized.contains("亿")) {
            multiplier *= 100000000D;
            normalized = normalized.replace("亿", "");
        }
        if (normalized.contains("万")) {
            multiplier *= 10000D;
            normalized = normalized.replace("万", "");
        }
        if (normalized.contains("千")) {
            multiplier *= 1000D;
            normalized = normalized.replace("千", "");
        }
        if (normalized.toLowerCase(Locale.ROOT).endsWith("k")) {
            multiplier *= 1000D;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.toLowerCase(Locale.ROOT).endsWith("w")) {
            multiplier *= 10000D;
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[-+]?\\d+(?:\\.\\d+)?")
                .matcher(normalized);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group()) * multiplier;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private record Point(String name, double value) {
    }

    private record SeriesPreprocessResult(List<Point> points, int duplicateCount, int filledCount,
                                          int outlierAdjustedCount, List<Point> rawAnomalies) {
    }

    private record OutlierAdjustResult(List<Point> points, int adjustedCount, List<Point> rawAnomalies) {
    }

    private record FormulaToken(String type, String value) {
    }

    private record FormulaPlan(String displayExpression,
                               String resolvedExpression,
                               List<FormulaToken> tokens,
                               Map<String, Double> baseValues,
                               List<Map<String, Object>> fields) {
    }

    private record RegressionFit(boolean fallback,
                                 double intercept,
                                 double[] coefficients,
                                 List<String> fields,
                                 double[] means,
                                 double[] stds,
                                 int sampleSize,
                                 double rSquared) {
        private static RegressionFit fallback(double base, List<String> fields) {
            return new RegressionFit(true, base, new double[fields.size()], fields, new double[fields.size()], new double[fields.size()], 0, 0D);
        }

        private double coefficient(String field) {
            int index = fields.indexOf(field);
            return index < 0 || index >= coefficients.length ? 0D : coefficients[index];
        }

        private double predict(Map<String, Double> values) {
            double result = intercept;
            for (int index = 0; index < fields.size(); index += 1) {
                String field = fields.get(index);
                double value = values.getOrDefault(field, means.length > index ? means[index] : 0D);
                result += coefficient(field) * value;
            }
            return result;
        }

        private Map<String, Object> quality() {
            return Map.of(
                    "algorithm", fallback ? "mean-fallback" : "ridge-regression",
                    "sampleSize", sampleSize,
                    "rSquared", Math.round(rSquared * 10000D) / 10000D,
                    "variableCount", fields.size()
            );
        }
    }

    private record ForecastParams(double alpha, double beta, double gamma, int seasonLength) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "alpha", alpha,
                    "beta", beta,
                    "gamma", gamma,
                    "seasonLength", seasonLength
            );
        }
    }

    private class FormulaEvaluator {
        private final List<FormulaToken> tokens;
        private final Map<String, Double> values;
        private int index = 0;

        private FormulaEvaluator(List<FormulaToken> tokens, Map<String, Double> values) {
            this.tokens = tokens;
            this.values = values;
        }

        private double parse() {
            double value = comparison();
            if (index < tokens.size()) {
                throw new IllegalArgumentException("业务公式存在无法解析的片段: " + tokens.get(index).value());
            }
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("业务公式计算结果无效");
            }
            return value;
        }

        private double comparison() {
            double value = expression();
            while (match(">") || match(">=") || match("<") || match("<=") || match("==") || match("=") || match("!=")) {
                String operator = tokens.get(index - 1).value();
                double next = expression();
                boolean result = switch (operator) {
                    case ">" -> value > next;
                    case ">=" -> value >= next;
                    case "<" -> value < next;
                    case "<=" -> value <= next;
                    case "!=", "<>" -> Math.abs(value - next) > 0.0000001D;
                    default -> Math.abs(value - next) <= 0.0000001D;
                };
                value = result ? 1D : 0D;
            }
            return value;
        }

        private double expression() {
            double value = term();
            while (match("+") || match("-")) {
                String operator = tokens.get(index - 1).value();
                double next = term();
                value = "+".equals(operator) ? value + next : value - next;
            }
            return value;
        }

        private double term() {
            double value = factor();
            while (match("*") || match("/")) {
                String operator = tokens.get(index - 1).value();
                double next = factor();
                if ("/".equals(operator) && Math.abs(next) < 0.0000001D) {
                    throw new IllegalArgumentException("业务公式存在除以 0 的风险");
                }
                value = "*".equals(operator) ? value * next : value / next;
            }
            return value;
        }

        private double factor() {
            if (match("+")) {
                return factor();
            }
            if (match("-")) {
                return -factor();
            }
            if (match("(")) {
                double value = comparison();
                if (!match(")")) {
                    throw new IllegalArgumentException("业务公式括号不匹配");
                }
                return value;
            }
            if (index >= tokens.size()) {
                throw new IllegalArgumentException("业务公式不完整");
            }
            FormulaToken token = tokens.get(index);
            index += 1;
            if ("number".equals(token.type())) {
                return parseDouble(token.value(), 0D);
            }
            if ("identifier".equals(token.type())) {
                return values.getOrDefault(token.value(), 0D);
            }
            if ("function".equals(token.type())) {
                return functionValue(token.value());
            }
            throw new IllegalArgumentException("业务公式运算符位置不正确: " + token.value());
        }

        private double functionValue(String name) {
            if (!match("(")) {
                throw new IllegalArgumentException("业务公式函数缺少左括号: " + name);
            }
            List<Double> args = new ArrayList<>();
            if (!peek(")")) {
                do {
                    args.add(comparison());
                } while (match(",") || match("，"));
            }
            if (!match(")")) {
                throw new IllegalArgumentException("业务公式函数括号不匹配: " + name);
            }
            return switch (name) {
                case "IF" -> {
                    if (args.size() != 3) {
                        throw new IllegalArgumentException("IF 函数需要 3 个参数");
                    }
                    yield Math.abs(args.get(0)) > 0.0000001D ? args.get(1) : args.get(2);
                }
                case "ABS" -> {
                    if (args.size() != 1) {
                        throw new IllegalArgumentException("ABS 函数需要 1 个参数");
                    }
                    yield Math.abs(args.get(0));
                }
                case "MIN" -> {
                    if (args.isEmpty()) {
                        throw new IllegalArgumentException("MIN 函数至少需要 1 个参数");
                    }
                    yield args.stream().mapToDouble(Double::doubleValue).min().orElse(0D);
                }
                case "MAX" -> {
                    if (args.isEmpty()) {
                        throw new IllegalArgumentException("MAX 函数至少需要 1 个参数");
                    }
                    yield args.stream().mapToDouble(Double::doubleValue).max().orElse(0D);
                }
                case "ROUND" -> {
                    if (args.isEmpty() || args.size() > 2) {
                        throw new IllegalArgumentException("ROUND 函数需要 1 到 2 个参数");
                    }
                    int scale = args.size() == 2 ? Math.max(0, Math.min(6, (int) Math.round(args.get(1)))) : 2;
                    double factor = Math.pow(10D, scale);
                    yield Math.round(args.get(0) * factor) / factor;
                }
                case "DIVIDE", "SAFE_DIVIDE" -> {
                    if (args.size() < 2 || args.size() > 3) {
                        throw new IllegalArgumentException(name + " 函数需要 2 到 3 个参数");
                    }
                    double denominator = args.get(1);
                    if (Math.abs(denominator) < 0.0000001D) {
                        yield args.size() == 3 ? args.get(2) : 0D;
                    }
                    yield args.get(0) / denominator;
                }
                default -> throw new IllegalArgumentException("不支持的业务公式函数: " + name);
            };
        }

        private boolean match(String value) {
            if (index >= tokens.size()) {
                return false;
            }
            if (!value.equals(tokens.get(index).value())) {
                return false;
            }
            index += 1;
            return true;
        }

        private boolean peek(String value) {
            return index < tokens.size() && value.equals(tokens.get(index).value());
        }
    }
}
