package com.insightspark.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private DatasourceService datasourceService;

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
        ForecastParams params = forecastParams(request);

        validateField(tableName, timeField, true);
        validateField(tableName, metricField, false);

        List<Point> history = loadSeries(tableName, timeField, metricField, granularity, 240);
        if (history.size() < 3) {
            throw new IllegalArgumentException("可用于预测的时间序列不足，至少需要 3 个有效时间点");
        }
        List<Point> forecast = forecastSeries(history, horizon, algorithm, params);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Point point : history) {
            series.add(row(point.name(), point.value(), null, null, null));
        }
        for (Point point : forecast) {
            double band = Math.max(Math.abs(point.value()) * 0.12D, standardDeviation(history) * 1.2D);
            series.add(row(point.name(), null, round(point.value()), round(point.value() + band), round(point.value() - band)));
        }
        double last = forecast.get(forecast.size() - 1).value();
        return Map.of(
                "type", "forecast",
                "tableName", tableName,
                "metricField", metricField,
                "timeField", timeField,
                "granularity", granularity,
                "algorithm", algorithm,
                "algorithmParams", params.toMap(),
                "confidence", "95%",
                "series", series,
                "dataQuality", dataQuality(history),
                "insights", List.of(
                        Map.of("label", "历史点数", "value", history.size()),
                        Map.of("label", "预测点数", "value", forecast.size()),
                        Map.of("label", "末期预测", "value", round(last))
                )
        );
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
        List<Point> forecast = forecastSeries(history, horizon, algorithm, params);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Point point : history) {
            series.add(row(point.name(), round(point.value()), null, null, null));
        }
        for (Point point : forecast) {
            double band = Math.max(Math.abs(point.value()) * 0.12D, standardDeviation(history) * 1.2D);
            series.add(row(point.name(), null, round(point.value()), round(point.value() + band), round(point.value() - band)));
        }
        return Map.of(
                "type", "forecast",
                "tableName", tableName,
                "metricField", metric,
                "timeField", "query_result_dimension",
                "granularity", "query-result",
                "algorithm", algorithm,
                "algorithmParams", params.toMap(),
                "confidence", "95%",
                "series", series,
                "dataQuality", dataQuality(history),
                "insights", List.of(
                        Map.of("label", "真实序列点数", "value", history.size()),
                        Map.of("label", "预测点数", "value", forecast.size()),
                        Map.of("label", "数据来源", "value", "上一轮查询结果")
                )
        );
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
        double effect = 0D;
        List<Map<String, Object>> normalizedVariables = new ArrayList<>();
        for (Map<String, Object> variable : variables) {
            String field = text(variable.getOrDefault("field", variable.getOrDefault("name", "")));
            if (field.isBlank()) {
                continue;
            }
            validateField(tableName, field, false);
            double change = parseDouble(variable.getOrDefault("change", variable.getOrDefault("changePercent", 0D)), 0D);
            double correlation = estimateCorrelation(tableName, field, targetMetric);
            effect += change * correlation;
            normalizedVariables.add(Map.of(
                    "field", field,
                    "name", text(variable.getOrDefault("name", field)),
                    "change", change,
                    "estimatedCorrelation", round(correlation)
            ));
        }
        if (normalizedVariables.isEmpty()) {
            throw new IllegalArgumentException("推演变量未能匹配到有效数值字段");
        }

        double scenario = Math.max(0D, base * (1D + effect / 100D));
        double recommended = Math.max(base, scenario) * 1.05D;
        return Map.of(
                "type", "whatIf",
                "tableName", tableName,
                "targetMetric", targetMetric,
                "variables", normalizedVariables,
                "series", List.of(
                        Map.of("name", "基准方案", "value", round(base)),
                        Map.of("name", "模拟方案", "value", round(scenario)),
                        Map.of("name", "推荐方案", "value", round(recommended))
                ),
                "insights", List.of(
                        Map.of("label", "模拟变化", "value", round(base == 0D ? 0D : (scenario - base) / base * 100D) + "%"),
                        Map.of("label", "变量数量", "value", normalizedVariables.size()),
                        Map.of("label", "计算方式", "value", "历史相关性估计")
                )
        );
    }

    private List<Point> loadSeries(String tableName, String timeField, String metricField, String granularity, int limit) {
        String physicalTable = physicalTable(tableName);
        String timeExpr = dateBucketExpr(timeField, granularity);
        String metricExpr = numericExpr(metricField);
        String sql = "SELECT " + timeExpr + " AS bucket_name, SUM(" + metricExpr + ") AS metric_value "
                + "FROM `" + physicalTable + "` "
                + "WHERE `" + timeField + "` IS NOT NULL AND `" + timeField + "` <> '' "
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

    private List<Point> forecastSeries(List<Point> history, int horizon, String algorithm, ForecastParams params) {
        String normalized = text(algorithm).toLowerCase(Locale.ROOT);
        if (normalized.contains("prophet")) {
            return prophetLikeForecast(history, horizon, params);
        }
        return holtWintersForecast(history, horizon, params);
    }

    private List<Point> holtWintersForecast(List<Point> history, int horizon, ForecastParams params) {
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
            result.add(new Point(nextBucketName(lastName, i), Math.max(0D, (level + trend * i) * season)));
        }
        return result;
    }

    private List<Point> prophetLikeForecast(List<Point> history, int horizon, ForecastParams params) {
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
            result.add(new Point(nextBucketName(lastName, i), Math.max(0D, value)));
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String nextBucketName(String lastName, int offset) {
        try {
            LocalDate date = LocalDate.parse(lastName.length() == 7 ? lastName + "-01" : lastName);
            return date.plusMonths(offset).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (DateTimeParseException ignored) {
            return "未来" + offset;
        }
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
        String label = (text(field.get("displayName")) + " " + text(field.get("columnName"))).toLowerCase(Locale.ROOT);
        return type.contains("DATE") || type.contains("TIME") || label.contains("date") || label.contains("time")
                || label.contains("日期") || label.contains("时间");
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

    private int parsePositiveInt(Object value, int fallback) {
        try {
            int parsed = Integer.parseInt(text(value));
            return Math.max(1, Math.min(parsed, 60));
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
