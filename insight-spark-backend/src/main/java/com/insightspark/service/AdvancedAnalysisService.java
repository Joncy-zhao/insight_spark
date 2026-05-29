package com.insightspark.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
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

@Service
public class AdvancedAnalysisService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean alertAgentRunning = false;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private DatasourceService datasourceService;

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

        List<Point> history = loadSeries(tableName, timeField, metricField, granularity, 240, resolvedFilterExpression);
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
        result.put("dataQuality", dataQuality(history));
        result.put("insights", List.of(
                Map.of("label", "历史点数", "value", history.size()),
                Map.of("label", "预测点数", "value", forecast.size()),
                Map.of("label", "末期预测", "value", round(last))
        ));
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
        List<Point> history = inputRows.stream()
                .map(row -> new Point(text(row.get("name")), parseDouble(row.get("value"), Double.NaN)))
                .filter(point -> !point.name().isBlank() && !Double.isNaN(point.value()))
                .toList();
        if (history.size() < 3) {
            throw new IllegalArgumentException("上一轮查询结果不足，至少需要 3 个有效时间点才能预测");
        }
        List<Point> forecast = forecastSeries(history, horizon, algorithm, params, inferGranularity(history));
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
        result.put("dataQuality", dataQuality(history));
        result.put("insights", List.of(
                Map.of("label", "真实序列点数", "value", history.size()),
                Map.of("label", "预测点数", "value", forecast.size()),
                Map.of("label", "数据来源", "value", "上一轮查询结果")
        ));
        return result;
    }

    public Map<String, Object> whatIf(Map<String, Object> request) {
        String tableName = required(request, "tableName");
        String targetMetric = required(request, "targetMetric");
        validateField(tableName, targetMetric, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = request.get("variables") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        if (variables.isEmpty()) {
            throw new IllegalArgumentException("请至少配置一个推演变量");
        }

        double base = loadMetricAverage(tableName, targetMetric);
        List<Map<String, Object>> normalizedVariables = new ArrayList<>();
        for (Map<String, Object> variable : variables) {
            String field = text(variable.getOrDefault("field", variable.getOrDefault("name", "")));
            if (field.isBlank()) {
                continue;
            }
            validateField(tableName, field, false);
            double change = parseDouble(variable.getOrDefault("change", variable.getOrDefault("changePercent", 0D)), 0D);
            String mode = normalizeWhatIfMode(text(variable.getOrDefault("mode", "percent")));
            double minValue = parseDouble(variable.get("min"), Double.NaN);
            double maxValue = parseDouble(variable.get("max"), Double.NaN);
            double currentValue = loadMetricAverage(tableName, field);
            double correlation = estimateCorrelation(tableName, field, targetMetric);
            double normalizedChange = normalizeWhatIfChange(currentValue, change, mode, minValue, maxValue);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("name", text(variable.getOrDefault("name", field)));
            item.put("mode", mode);
            item.put("change", change);
            item.put("normalizedChange", round(normalizedChange));
            item.put("currentValue", round(currentValue));
            item.put("min", Double.isNaN(minValue) ? null : round(minValue));
            item.put("max", Double.isNaN(maxValue) ? null : round(maxValue));
            item.put("estimatedCorrelation", round(correlation));
            normalizedVariables.add(item);
        }
        if (normalizedVariables.isEmpty()) {
            throw new IllegalArgumentException("推演变量未能匹配到有效数值字段");
        }

        double neutralEffect = scenarioEffect(normalizedVariables, 1D);
        double conservativeEffect = scenarioEffect(normalizedVariables, 0.5D);
        double optimisticEffect = scenarioEffect(normalizedVariables, 1.35D);
        double conservative = applyScenarioEffect(base, conservativeEffect);
        double scenario = applyScenarioEffect(base, neutralEffect);
        double optimistic = applyScenarioEffect(base, optimisticEffect);
        double recommended = Math.max(Math.max(conservative, scenario), optimistic);
        return Map.of(
                "type", "whatIf",
                "tableName", tableName,
                "targetMetric", targetMetric,
                "variables", normalizedVariables,
                "series", List.of(
                        Map.of("name", "基准方案", "value", round(base)),
                        Map.of("name", "保守方案", "value", round(conservative)),
                        Map.of("name", "中性方案", "value", round(scenario)),
                        Map.of("name", "乐观方案", "value", round(optimistic)),
                        Map.of("name", "推荐方案", "value", round(recommended))
                ),
                "insights", List.of(
                        Map.of("label", "模拟变化", "value", round(base == 0D ? 0D : (scenario - base) / base * 100D) + "%"),
                        Map.of("label", "变量数量", "value", normalizedVariables.size()),
                        Map.of("label", "场景数量", "value", 3),
                        Map.of("label", "计算方式", "value", "历史相关性估计 + 多场景拟合")
                )
        );
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
                       channels_json AS channelsJson, status, created_at AS createdAt, updated_at AS updatedAt
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
        List<Map<String, Object>> rules = loadActiveAlertRules(ruleId);
        int checked = 0;
        int created = 0;
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            checked += 1;
            try {
                List<Map<String, Object>> ruleEvents = detectAlertRule(rule);
                created += ruleEvents.size();
                events.addAll(ruleEvents);
            } catch (Exception ignored) {
                // A single invalid rule should not stop other rules.
            }
        }
        return Map.of(
                "checkedRules", checked,
                "createdEvents", created,
                "events", events
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
                       chart_snapshot_json AS chartSnapshotJson, status, created_at AS createdAt
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
        return planDetail(id);
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

    public Map<String, Object> recalculatePlan(long id) {
        Map<String, Object> plan = planDetail(id);
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
        return planDetail(id);
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

    private String normalizeWhatIfMode(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("percent", "absolute", "set").contains(mode)) {
            return mode;
        }
        return "percent";
    }

    private double normalizeWhatIfChange(double currentValue, double change, String mode, double minValue, double maxValue) {
        double effectiveChange = switch (normalizeWhatIfMode(mode)) {
            case "absolute" -> currentValue == 0D ? change : change / Math.abs(currentValue) * 100D;
            case "set" -> currentValue == 0D ? change : (change - currentValue) / Math.abs(currentValue) * 100D;
            default -> change;
        };
        double targetValue = currentValue * (1D + effectiveChange / 100D);
        if (!Double.isNaN(minValue) && targetValue < minValue) {
            targetValue = minValue;
        }
        if (!Double.isNaN(maxValue) && targetValue > maxValue) {
            targetValue = maxValue;
        }
        if (currentValue == 0D) {
            return effectiveChange;
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

    private List<Map<String, Object>> loadActiveAlertRules(long ruleId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
                       time_field AS timeField, granularity, filter_expression AS filterExpression,
                       resolved_filter_expression AS resolvedFilterExpression, operator,
                       threshold_value AS threshold, detection_cycle AS detectionCycle,
                       channels_json AS channelsJson, status, created_at AS createdAt, updated_at AS updatedAt
                FROM is_advanced_alert_rule
                WHERE status = 'ACTIVE'
                """);
        if (ruleId > 0) {
            sql.append(" AND id = ?");
            args.add(ruleId);
        }
        sql.append(" ORDER BY updated_at ASC LIMIT 200");
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
            Map<String, Object> event = insertAlertEvent(rule, point, threshold, baseline, zScore);
            if (!event.isEmpty()) {
                events.add(event);
            }
        }
        return events;
    }

    private Map<String, Object> insertAlertEvent(Map<String, Object> rule, Point point, double threshold, double baseline, double zScore) {
        long ruleId = parseLong(rule.get("id"), 0L);
        String operator = normalizeAlertOperator(text(rule.get("operator")));
        double deviationRate = baseline == 0D ? 0D : (point.value() - baseline) / baseline * 100D;
        String reason = buildAlertReason(operator, point.value(), threshold, baseline, zScore, deviationRate);
        Map<String, Object> snapshot = Map.of(
                "bucketName", point.name(),
                "actualValue", round(point.value()),
                "threshold", Double.isNaN(threshold) ? null : round(threshold),
                "baseline", round(baseline),
                "zScore", round(zScore),
                "operator", operator
        );
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
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, rule_id AS ruleId, user_id AS userId, table_name AS tableName,
                       metric_field AS metricField, time_field AS timeField, bucket_name AS bucketName,
                       actual_value AS actualValue, threshold_value AS threshold, operator, z_score AS zScore,
                       baseline_value AS baselineValue, deviation_rate AS deviationRate, reason,
                       chart_snapshot_json AS chartSnapshotJson, status, created_at AS createdAt
                FROM is_advanced_alert_event
                WHERE rule_id = ? AND bucket_name = ? AND operator = ?
                LIMIT 1
                """, ruleId, point.name(), operator);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> event = new LinkedHashMap<>(rows.get(0));
        parseAlertEventJsonFields(event);
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

    private Map<String, Object> alertRuleDetail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
                       time_field AS timeField, granularity, filter_expression AS filterExpression,
                       resolved_filter_expression AS resolvedFilterExpression, operator,
                       threshold_value AS threshold, detection_cycle AS detectionCycle,
                       channels_json AS channelsJson, status, created_at AS createdAt, updated_at AS updatedAt
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

    private void parseAlertRuleJsonFields(Map<String, Object> row) {
        row.put("channels", parseJsonList(row.remove("channelsJson")));
    }

    private void parseAlertEventJsonFields(Map<String, Object> row) {
        row.put("chartSnapshot", parseJsonObject(row.remove("chartSnapshotJson")));
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

    private String inferGranularity(List<Point> history) {
        String name = history.isEmpty() ? "" : history.get(history.size() - 1).name();
        if (name.matches("\\d{4}-\\d{2}-\\d{2}")) return "day";
        if (name.matches("\\d{4}-W\\d{2}")) return "week";
        if (name.matches("\\d{4}-Q[1-4]")) return "quarter";
        if (name.matches("\\d{4}")) return "year";
        return "month";
    }

    private Map<String, Object> dataQuality(List<Point> history) {
        double avg = history.stream().mapToDouble(Point::value).average().orElse(0D);
        double std = standardDeviation(history);
        return Map.of(
                "points", history.size(),
                "average", round(avg),
                "stdDev", round(std),
                "message", history.size() >= 8 ? "数据量满足基础预测要求" : "数据点偏少，预测不确定性较高"
        );
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
        String column = aliases.get(normalizeAlias(trimmed));
        return column == null ? token : "`" + column + "`";
    }

    private Map<String, String> filterAliasMap(String tableName) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Map<String, Object> field : dataUploadService.listFields(tableName)) {
            String column = text(field.get("columnName"));
            if (column.isBlank()) {
                continue;
            }
            addExactFilterAlias(aliases, field.get("columnName"), column);
            addExactFilterAlias(aliases, field.get("sourceFieldName"), column);
            addExactFilterAlias(aliases, field.get("displayName"), column);
            addExactFilterAlias(aliases, field.get("businessName"), column);
            addExactFilterAlias(aliases, field.get("fieldComment"), column);
            addSemanticFilterAliases(aliases, field, column);
            for (String synonym : text(field.get("synonyms")).split("[,，;；、|/\\s]+")) {
                addFilterAlias(aliases, synonym, column);
            }
        }
        return aliases;
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
        String normalized = normalizeAlias(text(alias));
        if (!normalized.isBlank()) {
            aliases.putIfAbsent(normalized, column);
        }
    }

    private void addExactFilterAlias(Map<String, String> aliases, Object alias, String column) {
        String normalized = normalizeAlias(text(alias));
        if (!normalized.isBlank()) {
            aliases.put(normalized, column);
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
}
