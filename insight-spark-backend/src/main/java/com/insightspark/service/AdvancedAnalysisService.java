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

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Service
public class AdvancedAnalysisService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate pushRestTemplate;
    private volatile boolean alertAgentRunning = false;

    @Value("${insight.advanced-alert.dingtalk-webhook:}")
    private String dingtalkWebhook;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private ChatQueryHistoryService chatQueryHistoryService;

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

    public Map<String, Object> forecast(Map<String, Object> request) {
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
        result.put("series", series);
        result.put("dataQuality", dataQuality(history, preprocess));
        result.put("insights", List.of(
                Map.of("label", "历史点数", "value", history.size()),
                Map.of("label", "预测点数", "value", forecast.size()),
                Map.of("label", "末期预测", "value", round(last))
        ));
        result.put("explanation", forecastExplanation(algorithm, granularity, history, forecast, params, "真实数据源", preprocess));
        return result;
    }

    public Map<String, Object> forecastFromSeries(Map<String, Object> request) {
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
                .map(row -> new Point(text(row.get("name")), parseDouble(row.get("value"), Double.NaN)))
                .filter(point -> !point.name().isBlank() && !Double.isNaN(point.value()))
                .toList();
        String inferredGranularity = inferGranularity(rawHistory);
        SeriesPreprocessResult preprocess = preprocessSeries(rawHistory, inferredGranularity);
        List<Point> history = preprocess.points();
        if (history.size() < 3) {
            throw new IllegalArgumentException("上一轮查询结果不足，至少需要 3 个有效时间点才能预测");
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
        result.put("series", series);
        result.put("dataQuality", dataQuality(history, preprocess));
        result.put("insights", List.of(
                Map.of("label", "真实序列点数", "value", history.size()),
                Map.of("label", "预测点数", "value", forecast.size()),
                Map.of("label", "数据来源", "value", "上一轮查询结果")
        ));
        result.put("explanation", forecastExplanation(algorithm, inferredGranularity, history, forecast, params, "上一轮查询结果", preprocess));
        return result;
    }

    public Map<String, Object> whatIf(Map<String, Object> request) {
        String tableName = required(request, "tableName");
        String targetMetric = required(request, "targetMetric");
        validateField(tableName, targetMetric, false);
        String formula = sanitizeFormulaExpression(request.get("formula"));
        FormulaPlan formulaPlan = formula.isBlank() ? null : buildFormulaPlan(tableName, formula);
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
                : evaluateFormulaPlan(formulaPlan, formulaPlan.baseValues());
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
            double correlation = formulaPlan == null ? estimateCorrelation(tableName, field, targetMetric) : 0D;
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
            item.put("estimatedCorrelation", round(correlation));
            normalizedVariables.add(item);
        }
        if (normalizedVariables.isEmpty()) {
            throw new IllegalArgumentException("推演变量未能匹配到有效数值字段");
        }

        if (formulaPlan != null) {
            enrichFormulaVariableImpacts(formulaPlan, normalizedVariables, base);
        }
        double conservative;
        double scenario;
        double optimistic;
        if (formulaPlan == null) {
            double neutralEffect = scenarioEffect(normalizedVariables, 1D);
            double conservativeEffect = scenarioEffect(normalizedVariables, 0.5D);
            double optimisticEffect = scenarioEffect(normalizedVariables, 1.35D);
            conservative = applyScenarioEffect(base, conservativeEffect);
            scenario = applyScenarioEffect(base, neutralEffect);
            optimistic = applyScenarioEffect(base, optimisticEffect);
        } else {
            conservative = formulaScenarioValue(formulaPlan, normalizedVariables, 0.5D);
            scenario = formulaScenarioValue(formulaPlan, normalizedVariables, 1D);
            optimistic = formulaScenarioValue(formulaPlan, normalizedVariables, 1.35D);
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
        result.put("calculationMode", formulaPlan == null ? "correlation" : "formula");
        result.put("variables", normalizedVariables);
        result.put("series", series);
        result.put("insights", List.of(
                Map.of("label", "模拟变化", "value", round(base == 0D ? 0D : (scenario - base) / base * 100D) + "%"),
                Map.of("label", "变量数量", "value", normalizedVariables.size()),
                Map.of("label", "场景数量", "value", 3),
                Map.of("label", "计算方式", "value", formulaPlan == null ? "历史相关性估计 + 多场景拟合" : "业务公式推演 + 多场景拟合")
        ));
        result.put("explanation", whatIfExplanation(base, conservative, scenario, optimistic, recommended, normalizedVariables, formulaPlan));
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

        validateField(tableName, metricField, false);
        validateField(tableName, timeField, true);
        if (!"zscore".equals(operator) && (threshold == null || Double.isNaN(threshold))) {
            throw new IllegalArgumentException("阈值预警需要填写有效阈值");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_advanced_alert_rule(
                      user_id, table_name, metric_field, time_field, granularity,
                      filter_expression, resolved_filter_expression, operator, threshold_value,
                      detection_cycle, channels_json, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, AuthContext.userId());
            ps.setString(2, tableName);
            ps.setString(3, metricField);
            ps.setString(4, timeField);
            ps.setString(5, granularity);
            ps.setString(6, filterExpression);
            ps.setString(7, resolvedFilterExpression);
            ps.setString(8, operator);
            if (threshold == null || Double.isNaN(threshold)) {
                ps.setObject(9, null);
            } else {
                ps.setDouble(9, threshold);
            }
            ps.setString(10, detectionCycle);
            ps.setString(11, toJson(channels));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        return alertRuleDetail(id);
    }

    public List<Map<String, Object>> listAlertRules() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
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

        validateField(tableName, metricField, false);
        validateField(tableName, timeField, true);
        if (!"zscore".equals(operator) && (threshold == null || Double.isNaN(threshold))) {
            throw new IllegalArgumentException("阈值预警需要填写有效阈值");
        }

        int updated = jdbcTemplate.update("""
                UPDATE is_advanced_alert_rule
                SET table_name = ?, metric_field = ?, time_field = ?, granularity = ?,
                    filter_expression = ?, resolved_filter_expression = ?, operator = ?,
                    threshold_value = ?, detection_cycle = ?, channels_json = CAST(? AS JSON),
                    status = ?
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """,
                tableName, metricField, timeField, granularity,
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
                List<Map<String, Object>> ruleEvents = detectAlertRule(rule);
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
        dingtalk.put("message", text(dingtalkWebhook).isBlank() ? "未配置 insight.advanced-alert.dingtalk-webhook" : "已配置钉钉 Webhook");
        return Map.of(
                "email", email,
                "dingtalk", dingtalk
        );
    }

    public List<Map<String, Object>> listAlertEvents(Map<String, Object> request) {
        long ruleId = parseLong(request.get("ruleId"), 0L);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, rule_id AS ruleId, user_id AS userId, table_name AS tableName,
                       metric_field AS metricField, time_field AS timeField, bucket_name AS bucketName,
                       actual_value AS actualValue, threshold_value AS threshold, operator, z_score AS zScore,
                       baseline_value AS baselineValue, deviation_rate AS deviationRate, reason,
                       chart_snapshot_json AS chartSnapshotJson, status, ack_by AS ackBy,
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
                SELECT id, event_id AS eventId, rule_id AS ruleId, user_id AS userId,
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
        Map<String, Object> result = attemptAlertPush(log, event);
        return alertPushLogDetail(parseLong(result.get("id"), id));
    }

    public Map<String, Object> updateAlertEventStatus(long id, Map<String, Object> request) {
        String status = normalizeAlertEventStatus(text(request.getOrDefault("status", request.get("action"))));
        String note = truncate(text(request.getOrDefault("handleNote", request.getOrDefault("note", ""))), 1000);
        String userId = AuthContext.userId();
        int updated;
        if ("ACK".equals(status)) {
            updated = jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET status = 'ACK', ack_by = ?, ack_at = CURRENT_TIMESTAMP,
                        handle_note = CASE WHEN ? = '' THEN handle_note ELSE ? END,
                        status_updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status <> 'CLOSED' AND (user_id = ? OR ? = 'ADMIN')
                    """, userId, note, note, id, AuthContext.userId(), AuthContext.role());
        } else if ("CLOSED".equals(status)) {
            updated = jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET status = 'CLOSED',
                        ack_by = COALESCE(ack_by, ?),
                        ack_at = COALESCE(ack_at, CURRENT_TIMESTAMP),
                        closed_by = ?, closed_at = CURRENT_TIMESTAMP,
                        handle_note = CASE WHEN ? = '' THEN handle_note ELSE ? END,
                        status_updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                    """, userId, userId, note, note, id, AuthContext.userId(), AuthContext.role());
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE is_advanced_alert_event
                    SET status = 'OPEN', closed_by = NULL, closed_at = NULL,
                        handle_note = CASE WHEN ? = '' THEN handle_note ELSE ? END,
                        status_updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                    """, note, note, id, AuthContext.userId(), AuthContext.role());
        }
        if (updated <= 0) {
            throw new IllegalArgumentException("预警事件不存在、已关闭或无权操作");
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
        if (requestJson.isEmpty() && resultJson.isEmpty()) {
            throw new IllegalArgumentException("方案缺少可保存的参数或结果");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_advanced_analysis_plan(
                      user_id, plan_type, plan_name, table_name, metric_label, time_range_label,
                      status, request_json, result_json, llm_json, version_no, last_calculated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'SAVED', CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), 1, CURRENT_TIMESTAMP)
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
                       request_json AS requestJson, result_json AS resultJson, llm_json AS llmJson
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
                SET result_json = CAST(? AS JSON), version_no = version_no + 1, last_calculated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND (user_id = ? OR ? = 'ADMIN')
                """, toJson(result), id, AuthContext.userId(), AuthContext.role());
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

    public List<Map<String, Object>> listPlanVersions(long id) {
        Map<String, Object> plan = planDetail(id);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, plan_id AS planId, user_id AS userId, plan_type AS planType,
                       plan_name AS planName, version_no AS versionNo,
                       request_json AS requestJson, result_json AS resultJson,
                       llm_json AS llmJson, created_at AS createdAt
                FROM is_advanced_analysis_plan_version
                WHERE plan_id = ? AND user_id = ?
                ORDER BY version_no DESC, id DESC
                LIMIT 20
                """, id, AuthContext.userId());
        rows.forEach(row -> {
            row.put("request", parseJsonObject(row.remove("requestJson")));
            row.put("result", parseJsonObject(row.remove("resultJson")));
            row.put("llm", parseJsonObject(row.remove("llmJson")));
        });
        if (rows.isEmpty()) {
            rows.add(Map.of(
                    "planId", id,
                    "planName", plan.get("planName"),
                    "versionNo", plan.get("versionNo"),
                    "request", plan.get("request"),
                    "result", plan.get("result"),
                    "llm", plan.get("llm"),
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
                SELECT version_no AS versionNo, plan_name AS planName, request_json AS requestJson,
                       result_json AS resultJson, llm_json AS llmJson, created_at AS createdAt
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
        String metricExpr = numericExpr(metricField);
        String sql = "SELECT " + timeExpr + " AS bucket_name, SUM(" + metricExpr + ") AS metric_value "
                + "FROM `" + physicalTable + "` "
                + "WHERE `" + timeField + "` IS NOT NULL AND `" + timeField + "` <> '' "
                + (filterExpression.isBlank() ? "" : "AND (" + filterExpression + ") ")
                + "GROUP BY bucket_name ORDER BY bucket_name ASC LIMIT " + Math.max(12, Math.min(limit, 500));
        List<Map<String, Object>> rows = query(tableName, sql);
        return rows.stream()
                .map(row -> new Point(text(row.get("bucket_name")), parseDouble(row.get("metric_value"), 0D)))
                .filter(point -> !point.name().isBlank())
                .sorted(Comparator.comparing(Point::name))
                .toList();
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
        String metricExpr = numericExpr(metricField);
        String inValues = bucketWindow.stream()
                .map(this::sqlStringLiteral)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String sql = "SELECT " + timeExpr + " AS bucket_name, SUM(" + metricExpr + ") AS metric_value "
                + "FROM `" + physicalTable + "` "
                + "WHERE `" + timeField + "` IS NOT NULL AND `" + timeField + "` <> '' "
                + (filterExpression.isBlank() ? "" : "AND (" + filterExpression + ") ")
                + "AND " + timeExpr + " IN (" + inValues + ") "
                + "GROUP BY bucket_name ORDER BY bucket_name ASC";
        List<Map<String, Object>> rows = query(tableName, sql);
        List<Point> points = rows.stream()
                .map(row -> new Point(text(row.get("bucket_name")), parseDouble(row.get("metric_value"), 0D)))
                .filter(point -> !point.name().isBlank())
                .sorted(Comparator.comparing(Point::name))
                .toList();
        if (points.size() > 1) {
            return points;
        }
        return loadSeries(tableName, timeField, metricField, granularity, 240, filterExpression);
    }

    private SeriesPreprocessResult preprocessSeries(List<Point> rawHistory, String granularity) {
        if (rawHistory == null || rawHistory.isEmpty()) {
            return new SeriesPreprocessResult(List.of(), 0, 0, 0);
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
        return new SeriesPreprocessResult(adjusted.points(), duplicateCount, filledCount, adjusted.adjustedCount());
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
            return new OutlierAdjustResult(history, 0);
        }
        double avg = history.stream().mapToDouble(Point::value).average().orElse(0D);
        double std = standardDeviation(history);
        if (std <= 0D) {
            return new OutlierAdjustResult(history, 0);
        }
        double lower = avg - std * 3D;
        double upper = avg + std * 3D;
        int adjustedCount = 0;
        List<Point> result = new ArrayList<>();
        for (Point point : history) {
            double adjusted = clamp(point.value(), lower, upper);
            if (Math.abs(adjusted - point.value()) > 0.000001D) {
                adjustedCount += 1;
            }
            result.add(new Point(point.name(), adjusted));
        }
        return new OutlierAdjustResult(result, adjustedCount);
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
        String formula = text(value);
        if (formula.isBlank()) {
            return "";
        }
        formula = formula.replace('＝', '=');
        int equalsIndex = formula.indexOf('=');
        if (equalsIndex >= 0) {
            if (formula.indexOf('=', equalsIndex + 1) >= 0) {
                throw new IllegalArgumentException("业务公式仅支持一个等号");
            }
            formula = formula.substring(equalsIndex + 1).trim();
        }
        if (formula.length() > 240) {
            throw new IllegalArgumentException("业务公式长度不能超过 240 个字符");
        }
        if (!formula.matches("^[\\p{L}\\p{N}_\\s`\"'.+\\-*/()（）]+$")) {
            throw new IllegalArgumentException("业务公式仅支持字段、数字和 + - * / () 运算");
        }
        String lower = formula.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(select|from|where|drop|delete|update|insert|alter|union|sleep|benchmark)\\b.*")) {
            throw new IllegalArgumentException("业务公式仅支持只读四则运算");
        }
        return formula.replace('（', '(').replace('）', ')');
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
                    if ("+-*/()".indexOf(current) >= 0) {
                        break;
                    }
                    end += 1;
                }
                String identifier = formula.substring(i, end).trim();
                if (identifier.isBlank()) {
                    throw new IllegalArgumentException("业务公式存在空字段引用");
                }
                tokens.add(new FormulaToken("identifier", identifier));
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
            if ("+-*/()".indexOf(ch) >= 0) {
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

    private String resolvedFormulaExpression(List<FormulaToken> tokens) {
        List<String> parts = new ArrayList<>();
        for (FormulaToken token : tokens) {
            if ("identifier".equals(token.type())) {
                parts.add("`" + token.value() + "`");
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

    private double scenarioEffect(List<Map<String, Object>> variables, double multiplier) {
        double effect = 0D;
        for (Map<String, Object> variable : variables) {
            double normalizedChange = parseDouble(variable.get("normalizedChange"), 0D);
            double correlation = parseDouble(variable.get("estimatedCorrelation"), 0D);
            effect += normalizedChange * correlation * multiplier;
        }
        return effect;
    }

    private double applyScenarioEffect(double base, double effect) {
        return Math.max(0D, base * (1D + effect / 100D));
    }

    private double formulaScenarioValue(FormulaPlan plan, List<Map<String, Object>> variables, double multiplier) {
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
        return Math.max(0D, evaluateFormulaPlan(plan, values));
    }

    private void enrichFormulaVariableImpacts(FormulaPlan plan, List<Map<String, Object>> variables, double base) {
        for (Map<String, Object> variable : variables) {
            String field = text(variable.get("field"));
            if (!plan.baseValues().containsKey(field)) {
                variable.put("formulaImpact", 0D);
                variable.put("estimatedCorrelation", 0D);
                continue;
            }
            Map<String, Double> values = new LinkedHashMap<>(plan.baseValues());
            values.put(field, parseDouble(variable.get("targetValue"), plan.baseValues().get(field)));
            double nextValue = evaluateFormulaPlan(plan, values);
            double impact = base == 0D ? 0D : (nextValue - base) / Math.abs(base) * 100D;
            variable.put("formulaImpact", round(impact));
            variable.put("estimatedCorrelation", round(impact == 0D ? 0D : impact / Math.max(Math.abs(parseDouble(variable.get("normalizedChange"), 0D)), 1D)));
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
                                                  FormulaPlan formulaPlan) {
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
            calculation.add("中性方案相对基准变化 " + signedPercent(scenarioRate)
                    + "，变量影响基于历史相关性和配置变化幅度估计。");
        } else {
            calculation.add("中性方案相对基准变化 " + signedPercent(scenarioRate)
                    + "，变量影响基于业务公式「" + formulaPlan.displayExpression() + "」和字段均值计算。");
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
            suggestions.add("当前数值来自拟合估计，不等同于因果结论；落地前建议结合业务公式或实验数据校验。");
        } else {
            suggestions.add("当前数值来自配置的业务公式，建议确认公式口径、字段单位和聚合口径与实际管理口径一致。");
        }

        return Map.of(
                "source", "rule",
                "sourceLabel", "规则解释",
                "calculation", calculation,
                "suggestions", suggestions
        );
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
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
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
                  rule_id, user_id, table_name, metric_field, time_field, bucket_name,
                  actual_value, threshold_value, operator, z_score, baseline_value,
                  deviation_rate, reason, chart_snapshot_json, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), 'OPEN')
                """,
                ruleId,
                text(rule.get("userId")),
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
                    SET actual_value = ?, threshold_value = ?, z_score = ?,
                        baseline_value = ?, deviation_rate = ?, reason = ?,
                        chart_snapshot_json = CAST(? AS JSON)
                    WHERE rule_id = ? AND bucket_name = ? AND operator = ?
                    """,
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
                SELECT id, rule_id AS ruleId, user_id AS userId, table_name AS tableName,
                       metric_field AS metricField, time_field AS timeField, bucket_name AS bucketName,
                       actual_value AS actualValue, threshold_value AS threshold, operator, z_score AS zScore,
                       baseline_value AS baselineValue, deviation_rate AS deviationRate, reason,
                       chart_snapshot_json AS chartSnapshotJson, status, ack_by AS ackBy,
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
                SELECT id, rule_id AS ruleId, user_id AS userId, table_name AS tableName,
                       metric_field AS metricField, time_field AS timeField, bucket_name AS bucketName,
                       actual_value AS actualValue, threshold_value AS threshold, operator, z_score AS zScore,
                       baseline_value AS baselineValue, deviation_rate AS deviationRate, reason,
                       chart_snapshot_json AS chartSnapshotJson, status, ack_by AS ackBy,
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
        String title = buildAlertPushTitle(event);
        String content = buildAlertPushContent(rule, event);
        String target = pushTarget(normalizedChannel);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_advanced_alert_push_log(
                      event_id, rule_id, user_id, channel, status, target, title, content, request_json
                    ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, CAST(? AS JSON))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, parseLong(event.get("id"), 0L));
            ps.setLong(2, parseLong(event.get("ruleId"), parseLong(rule.get("id"), 0L)));
            ps.setString(3, text(event.getOrDefault("userId", rule.get("userId"))));
            ps.setString(4, normalizedChannel);
            ps.setString(5, maskPushTarget(target));
            ps.setString(6, title);
            ps.setString(7, content);
            ps.setString(8, toJson(Map.of(
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
        message.setText(content + "\n\n"
                + "事件ID：" + text(event.get("id")) + "\n"
                + "规则ID：" + text(event.get("ruleId")) + "\n"
                + "时间桶：" + text(event.get("bucketName")) + "\n"
                + "状态：请登录系统查看预警图表快照与处理详情。");
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
                + "- 事件ID：" + text(event.get("id")) + "\n"
                + "- 规则ID：" + text(event.get("ruleId")) + "\n"
                + "- 时间桶：" + text(event.get("bucketName")));
        Map<String, Object> payload = Map.of(
                "msgtype", "markdown",
                "markdown", markdown
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            Map<String, Object> response = pushRestTemplate.postForObject(webhook, new HttpEntity<>(payload, headers), Map.class);
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

    private Map<String, Object> alertPushLogDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, event_id AS eventId, rule_id AS ruleId, user_id AS userId,
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

    private String buildAlertPushTitle(Map<String, Object> event) {
        return "预警触发：规则#" + text(event.get("ruleId")) + " / " + text(event.get("bucketName"));
    }

    private String buildAlertPushContent(Map<String, Object> rule, Map<String, Object> event) {
        return truncate("数据源：" + text(event.get("tableName"))
                + "；指标：" + text(event.get("metricField"))
                + "；时间字段：" + text(event.get("timeField"))
                + "；实际值：" + text(event.get("actualValue"))
                + "；阈值：" + text(event.get("threshold"))
                + "；基线：" + text(event.get("baselineValue"))
                + "；原因：" + text(event.get("reason"))
                + (text(rule.get("filterExpression")).isBlank() ? "" : "；过滤条件：" + text(rule.get("filterExpression"))),
                2000);
    }

    private Map<String, Object> alertRuleDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
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
        return row;
    }

    private Map<String, Object> planDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, plan_type AS planType, plan_name AS planName,
                       table_name AS tableName, metric_label AS metricLabel,
                       time_range_label AS timeRangeLabel, status, version_no AS versionNo,
                       last_calculated_at AS lastCalculatedAt, created_at AS createdAt, updated_at AS updatedAt,
                       request_json AS requestJson, result_json AS resultJson, llm_json AS llmJson
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
                  plan_id, user_id, plan_type, plan_name, version_no, request_json, result_json, llm_json
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON))
                """,
                planId,
                AuthContext.userId(),
                text(plan.get("planType")),
                text(plan.get("planName")),
                versionNo,
                toJson(plan.get("request")),
                toJson(plan.get("result")),
                toJson(plan.get("llm")));
    }

    private Map<String, Object> normalizePlanVersionRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("request", parseJsonObject(result.remove("requestJson")));
        result.put("result", parseJsonObject(result.remove("resultJson")));
        result.put("llm", parseJsonObject(result.remove("llmJson")));
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
        snapshot.put("tableName", text(plan.get("tableName")));
        snapshot.put("message", text(plan.get("planName")));
        snapshot.put("chartType", "line");
        snapshot.put("fieldMapping", Map.of(
                "dimension", "预测周期",
                "dimensionKey", "name",
                "metric", text(plan.getOrDefault("metricLabel", "预测值")),
                "metricKey", "value"
        ));
        snapshot.put("data", chartData);
        snapshot.put("advancedAnalysisPlanId", plan.get("id"));
        snapshot.put("advancedAnalysisType", plan.get("planType"));
        Long historyId = chatQueryHistoryService.recordSuccess(
                text(plan.get("planName")),
                text(plan.get("tableName")),
                snapshot,
                null
        );
        if (historyId != null && historyId > 0) {
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
            Object valueCandidate = map.containsKey("forecast") ? map.get("forecast") : map.get("value");
            double value = parseDouble(valueCandidate, Double.NaN);
            if (name.isBlank() || Double.isNaN(value)) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("name", name);
            point.put("value", round(value));
            data.add(point);
        }
        return data;
    }

    private void parseAlertRuleJsonFields(Map<String, Object> row) {
        row.put("channels", parseJsonList(row.remove("channelsJson")));
    }

    private void parseAlertEventJsonFields(Map<String, Object> row) {
        row.put("chartSnapshot", parseJsonObject(row.remove("chartSnapshotJson")));
    }

    private void parseAlertPushLogJsonFields(Map<String, Object> row) {
        row.put("request", parseJsonObject(row.remove("requestJson")));
        row.put("response", parseJsonObject(row.remove("responseJson")));
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
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
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

    private void parsePlanJsonFields(Map<String, Object> row) {
        row.put("request", parseJsonObject(row.remove("requestJson")));
        row.put("result", parseJsonObject(row.remove("resultJson")));
        row.put("llm", parseJsonObject(row.remove("llmJson")));
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
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
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
        try {
            return Double.parseDouble(text(value).replace(",", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private record Point(String name, double value) {
    }

    private record SeriesPreprocessResult(List<Point> points, int duplicateCount, int filledCount, int outlierAdjustedCount) {
    }

    private record OutlierAdjustResult(List<Point> points, int adjustedCount) {
    }

    private record FormulaToken(String type, String value) {
    }

    private record FormulaPlan(String displayExpression,
                               String resolvedExpression,
                               List<FormulaToken> tokens,
                               Map<String, Double> baseValues,
                               List<Map<String, Object>> fields) {
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
            double value = expression();
            if (index < tokens.size()) {
                throw new IllegalArgumentException("业务公式存在无法解析的片段: " + tokens.get(index).value());
            }
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("业务公式计算结果无效");
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
                double value = expression();
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
            throw new IllegalArgumentException("业务公式运算符位置不正确: " + token.value());
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
    }
}
