package com.insightspark.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BusinessSemanticService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public BusinessSemanticContext resolveContext(String tableName, Map<String, Object> options,
                                                  List<Map<String, Object>> fields) {
        String table = text(tableName);
        if (table.isBlank()) {
            return BusinessSemanticContext.empty();
        }
        List<Long> preferredIds = new ArrayList<>();
        addId(preferredIds, options == null ? null : options.get("activeBusinessModelId"));
        addId(preferredIds, options == null ? null : options.get("lastAppliedBusinessModelId"));
        addId(preferredIds, options == null ? null : options.get("lastCreatedBusinessModelId"));

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Long id : preferredIds) {
            Map<String, Object> model = findModelById(id, table);
            if (!model.isEmpty()) {
                candidates.add(model);
                break;
            }
        }
        if (candidates.isEmpty()) {
            Map<String, Object> fallback = findLatestModelForTable(table);
            if (!fallback.isEmpty()) {
                candidates.add(fallback);
            }
        }
        if (candidates.isEmpty()) {
            return BusinessSemanticContext.empty();
        }
        Map<String, Object> model = candidates.get(0);
        Map<String, Object> modelJson = parseJson(model.get("modelJson"));
        Long modelId = toLong(model.get("id"));
        String source = preferredIds.contains(modelId) ? "activeBusinessModel" : "tableDefaultModel";
        return new BusinessSemanticContext(
                true,
                modelId,
                text(model.get("modelName")),
                table,
                source,
                text(model.get("updatedAt")),
                normalizeList(modelJson.get("dictionaryEntries")),
                normalizeList(modelJson.get("metricDefinitions")),
                normalizeList(modelJson.get("dimensionSystem")),
                fields == null ? List.of() : fields
        );
    }

    public BusinessSemanticPlan resolvePlan(String question, BusinessSemanticContext context) {
        if (context == null || !context.available()) {
            return BusinessSemanticPlan.empty();
        }
        BusinessSemanticMatch metric = resolveMetric(question, context);
        BusinessSemanticMatch dimension = resolveDimension(question, context);
        boolean timeSeries = isTimeSeriesQuestion(question);
        boolean detail = isDetailQuestion(question);
        boolean formulaApplied = metric != null && !text(metric.formula()).isBlank()
                && !isSameIdentifier(metric.formula(), metric.field());
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("enabled", metric != null || dimension != null);
        trace.put("modelId", context.modelId());
        trace.put("modelName", context.modelName());
        trace.put("source", context.source());
        trace.put("modelVersion", context.modelVersion());
        if (metric != null) {
            trace.put("matchedMetric", metric.name());
            trace.put("resolvedMetricField", metric.field());
            trace.put("metricColumn", metric.column());
            trace.put("formulaApplied", formulaApplied);
            trace.put("metricSource", metric.source());
            trace.put("dictionaryMatched", metric.dictionaryMatched());
            trace.put("dictionaryHitTerm", metric.dictionaryHitTerm());
        }
        if (dimension != null) {
            trace.put("matchedDimension", dimension.name());
            trace.put("resolvedDimensionField", dimension.field());
            trace.put("dimensionColumn", dimension.column());
            trace.put("dimensionSource", dimension.source());
        }
        trace.put("finalSqlValidated", false);
        return new BusinessSemanticPlan(metric, dimension, timeSeries, detail, trace);
    }

    public BusinessAnalysisResolution resolveAnalysis(String question, String tableName,
                                                      Map<String, Object> options,
                                                      List<Map<String, Object>> fields) {
        BusinessSemanticContext context = resolveContext(tableName, options, fields);
        BusinessSemanticPlan plan = resolvePlan(question, context);
        if (plan == null || plan.metric() == null) {
            return BusinessAnalysisResolution.empty(plan == null ? Map.of("enabled", false) : plan.trace());
        }
        BusinessSemanticMatch metric = plan.metric();
        String metricExpression = compileMetricExpression(metric);
        Map<String, Object> trace = new LinkedHashMap<>(plan.trace());
        trace.put("analysisSemanticValidated", true);
        trace.put("analysisMetricField", metric.column());
        trace.put("analysisMetricLabel", metric.name());
        if (!metricExpression.isBlank()) {
            trace.put("analysisMetricExpression", metricExpression);
        }
        if (!text(metric.formula()).isBlank()) {
            trace.put("analysisFormula", metric.formula());
        }
        return new BusinessAnalysisResolution(
                true,
                metric.name(),
                metric.column(),
                metric.field(),
                metricExpression,
                text(metric.formula()),
                !text(metric.formula()).isBlank() && !isSameIdentifier(metric.formula(), metric.field()),
                trace
        );
    }

    public BusinessSqlCorrection enforceSql(String question, String queryTableName, String generatedSql,
                                            String chartType, Map<String, Object> fieldMapping,
                                            BusinessSemanticPlan plan) {
        if (plan == null || !plan.hasSemanticConstraint()) {
            return new BusinessSqlCorrection(generatedSql, chartType, fieldMapping == null ? Map.of() : fieldMapping,
                    Map.of("enabled", false), false, "");
        }
        BusinessSemanticMatch metric = plan.metric();
        BusinessSemanticMatch dimension = plan.dimension();
        String metricExpr = "";
        if (metric != null) {
            metricExpr = compileMetricExpression(metric);
            if (metricExpr.isBlank()) {
                throw new IllegalArgumentException("业务模型指标「" + metric.name() + "」未绑定有效数值字段或公式，无法生成查询。");
            }
        }
        String dimensionExpr = "";
        String dimensionColumn = "";
        if (dimension != null) {
            dimensionColumn = text(dimension.column());
            if (dimensionColumn.isBlank()) {
                throw new IllegalArgumentException("业务模型维度「" + dimension.name() + "」未绑定有效字段，无法生成查询。");
            }
            dimensionExpr = "`" + dimensionColumn + "`";
        }

        String sql = text(generatedSql);
        String nextChartType = text(chartType).isBlank() ? "bar" : chartType;
        Map<String, Object> nextMapping = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        boolean changed = false;
        boolean shouldRebuild = metric != null && !sqlUsesMetric(sql, metric)
                || dimension != null && !sqlUsesDimension(sql, dimensionColumn)
                || metric != null && !metric.formula().isBlank() && !sqlContainsFormulaColumns(sql, metric);
        if (shouldRebuild) {
            if (plan.detail()) {
                sql = rebuildDetailSql(queryTableName, plan);
                nextChartType = "table";
            } else if (dimension != null || plan.timeSeries()) {
                Map<String, Object> time = plan.timeSeries() ? findBestTimeField(plan.contextFields()) : Map.of();
                boolean useTime = plan.timeSeries() && !time.isEmpty() && dimension == null;
                String selectDimensionExpr = useTime ? timeDimensionExpression(question, text(time.get("columnName"))) : dimensionExpr;
                String dimensionAlias = "dim_name";
                String groupExpr = selectDimensionExpr;
                String orderExpr = useTime ? "dim_name ASC" : "metric_value DESC";
                sql = "SELECT " + selectDimensionExpr + " AS " + dimensionAlias + ", " + metricExpr + " AS metric_value "
                        + "FROM `" + queryTableName + "` WHERE " + nonEmptyPredicate(useTime ? text(time.get("columnName")) : dimensionColumn)
                        + " GROUP BY " + groupExpr + " ORDER BY " + orderExpr + " LIMIT 30";
                nextChartType = useTime ? "line" : "bar";
                if (useTime) {
                    nextMapping.put("dimension", timeLabel(question, time));
                    nextMapping.put("dimensionKey", text(time.get("columnName")));
                    nextMapping.put("dimensionExpr", selectDimensionExpr);
                }
            } else {
                sql = "SELECT " + metricExpr + " AS metric_value FROM `" + queryTableName + "` LIMIT 1";
                nextChartType = "bar";
            }
            changed = true;
        }
        if (metric != null) {
            nextMapping.put("metric", metric.name());
            nextMapping.put("metricKey", metric.column());
            nextMapping.put("metricField", metric.field());
            nextMapping.put("metricExpr", metricExpr);
            if (!metric.formula().isBlank()) {
                nextMapping.put("formula", metric.formula());
            }
        }
        if (dimension != null) {
            nextMapping.put("dimension", dimension.name());
            nextMapping.put("dimensionKey", dimension.column());
            nextMapping.put("dimensionField", dimension.field());
            nextMapping.put("dimensionExpr", "`" + dimension.column() + "`");
        }
        Map<String, Object> trace = new LinkedHashMap<>(plan.trace());
        trace.put("finalSqlValidated", true);
        trace.put("sqlRebuilt", changed);
        return new BusinessSqlCorrection(sql, nextChartType, nextMapping, trace, changed,
                changed ? "BUSINESS_MODEL_SQL_REBUILD" : "BUSINESS_MODEL_SQL_VALIDATED");
    }

    private BusinessSemanticMatch resolveMetric(String question, BusinessSemanticContext context) {
        List<BusinessSemanticMatch> matches = new ArrayList<>();
        for (Map<String, Object> metric : context.metricDefinitions()) {
            String name = text(metric.get("name"));
            String field = text(metric.get("field"));
            String formula = text(metric.get("formula"));
            int score = scoreMention(question, List.of(name, field, formula));
            if (score <= 0) {
                continue;
            }
            String column = resolveColumn(firstNonBlank(field, primaryFormulaField(formula)), context.fields(), true);
            if (column.isBlank()) {
                continue;
            }
            matches.add(new BusinessSemanticMatch(name, field, column, text(metric.get("aggregation")),
                    formula, "metricDefinitions", false, "", score, context.fields()));
        }
        for (Map<String, Object> entry : context.dictionaryEntries()) {
            String term = text(entry.get("term"));
            String field = text(entry.get("field"));
            List<String> aliases = new ArrayList<>();
            aliases.add(term);
            aliases.add(field);
            aliases.addAll(splitAliases(text(entry.get("synonyms"))));
            int score = scoreMention(question, aliases);
            if (score <= 0 || field.isBlank()) {
                continue;
            }
            String column = resolveColumn(field, context.fields(), true);
            if (column.isBlank()) {
                continue;
            }
            BusinessSemanticMatch existing = findMetricByField(context.metricDefinitions(), field, column, context.fields());
            if (existing != null) {
                matches.add(existing.withDictionary(term, score + 20));
            } else {
                matches.add(new BusinessSemanticMatch(firstNonBlank(term, field), field, column, "SUM",
                        field, "dictionaryEntries", true, term, score + 10, context.fields()));
            }
        }
        return matches.stream().max((a, b) -> Integer.compare(a.score(), b.score())).orElse(null);
    }

    private BusinessSemanticMatch findMetricByField(List<Map<String, Object>> metrics, String fieldRef,
                                                    String column, List<Map<String, Object>> fields) {
        for (Map<String, Object> metric : metrics) {
            String field = text(metric.get("field"));
            String formula = text(metric.get("formula"));
            if (fieldRef.equals(field) || column.equals(resolveColumn(field, fields, true))
                    || formulaTokenEquals(formula, fieldRef)) {
                return new BusinessSemanticMatch(text(metric.get("name")), field, column,
                        text(metric.get("aggregation")), formula, "metricDefinitions",
                        false, "", 0, fields);
            }
        }
        return null;
    }

    private BusinessSemanticMatch resolveDimension(String question, BusinessSemanticContext context) {
        List<BusinessSemanticMatch> matches = new ArrayList<>();
        for (Map<String, Object> dimension : context.dimensionSystem()) {
            String name = text(dimension.get("name"));
            String field = text(dimension.get("field"));
            String column = resolveColumn(firstNonBlank(field, name), context.fields(), false);
            if (column.isBlank()) {
                continue;
            }
            int score = scoreMention(question, dimensionMentionAliases(name, field, column, context.fields()));
            if (score <= 0) {
                continue;
            }
            matches.add(new BusinessSemanticMatch(name, field, column, "", "", "dimensionSystem",
                    false, "", score, context.fields()));
        }
        for (Map<String, Object> entry : context.dictionaryEntries()) {
            String term = text(entry.get("term"));
            String field = text(entry.get("field"));
            List<String> aliases = new ArrayList<>();
            aliases.add(term);
            aliases.add(field);
            aliases.addAll(splitAliases(text(entry.get("synonyms"))));
            int score = scoreMention(question, aliases);
            if (score <= 0 || field.isBlank()) {
                continue;
            }
            String column = resolveColumn(field, context.fields(), false);
            if (column.isBlank() || isNumericColumn(column, context.fields())) {
                continue;
            }
            matches.add(new BusinessSemanticMatch(firstNonBlank(term, field), field, column, "", "",
                    "dictionaryEntries", true, term, score, context.fields()));
        }
        return matches.stream().max((a, b) -> Integer.compare(a.score(), b.score())).orElse(null);
    }

    private List<String> dimensionMentionAliases(String name, String field, String column, List<Map<String, Object>> fields) {
        List<String> aliases = new ArrayList<>();
        addAlias(aliases, name);
        addAlias(aliases, field);
        addAlias(aliases, column);
        for (Map<String, Object> item : fields) {
            if (!column.equals(text(item.get("columnName")))) {
                continue;
            }
            addAlias(aliases, text(item.get("displayName")));
            addAlias(aliases, text(item.get("sourceFieldName")));
            addAlias(aliases, text(item.get("fieldComment")));
        }
        List<String> expanded = new ArrayList<>(aliases);
        for (String alias : aliases) {
            expanded.addAll(groupingAliases(alias));
        }
        return expanded;
    }

    private List<String> groupingAliases(String alias) {
        String normalized = normalize(alias);
        if (normalized.isBlank()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        result.add(normalized);
        if (normalized.endsWith("份") && normalized.length() > 1) {
            result.add(normalized.substring(0, normalized.length() - 1));
        }
        if ("城市".equals(normalized)) {
            result.add("市");
        }
        if ("地区".equals(normalized)) {
            result.add("区域");
        }
        if ("区域".equals(normalized)) {
            result.add("地区");
        }
        List<String> prefixed = new ArrayList<>();
        for (String item : result) {
            if (item.length() <= 1) {
                prefixed.add("各" + item);
                prefixed.add("按" + item);
                prefixed.add("分" + item);
            } else {
                prefixed.add("各" + item);
                prefixed.add("按" + item);
                prefixed.add("分" + item);
                prefixed.add(item + "维度");
            }
        }
        result.addAll(prefixed);
        return new ArrayList<>(result);
    }

    private void addAlias(List<String> aliases, String alias) {
        String value = text(alias);
        if (!value.isBlank() && !aliases.contains(value)) {
            aliases.add(value);
        }
    }

    private String compileMetricExpression(BusinessSemanticMatch metric) {
        String formula = text(metric.formula());
        if (formula.isBlank() || isSameIdentifier(formula, metric.field())) {
            return aggregateColumn(metric.column(), metric.aggregation());
        }
        String expression = formula;
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[\\u4e00-\\u9fa5]{2,}").matcher(formula);
        while (matcher.find()) {
            String token = matcher.group();
            if (isSqlFunction(token)) {
                continue;
            }
            String column = resolveColumn(token, metric.fields(), true);
            if (column.isBlank()) {
                continue;
            }
            columns.add(column);
            expression = replaceToken(expression, token, aggregateColumn(column, "SUM"));
        }
        if (columns.isEmpty()) {
            return aggregateColumn(metric.column(), metric.aggregation());
        }
        return protectDivision(expression);
    }

    private String rebuildDetailSql(String queryTableName, BusinessSemanticPlan plan) {
        List<String> columns = new ArrayList<>();
        if (plan.dimension() != null) {
            columns.add(plan.dimension().column());
        }
        if (plan.metric() != null) {
            columns.add(plan.metric().column());
        }
        if (columns.isEmpty()) {
            columns = plan.contextFields().stream()
                    .map(field -> text(field.get("columnName")))
                    .filter(item -> !item.isBlank())
                    .limit(12)
                    .toList();
        }
        String select = columns.stream().distinct().limit(12).map(column -> "`" + column + "`")
                .reduce((a, b) -> a + ", " + b).orElse("*");
        return "SELECT " + select + " FROM `" + queryTableName + "` LIMIT 100";
    }

    private boolean sqlUsesMetric(String sql, BusinessSemanticMatch metric) {
        String source = text(sql);
        if (source.isBlank() || metric == null) {
            return true;
        }
        if (source.contains("`" + metric.column() + "`")) {
            return true;
        }
        return sqlContainsFormulaColumns(source, metric);
    }

    private boolean sqlUsesDimension(String sql, String dimensionColumn) {
        String column = text(dimensionColumn);
        return column.isBlank() || text(sql).contains("`" + column + "`");
    }

    private boolean sqlContainsFormulaColumns(String sql, BusinessSemanticMatch metric) {
        String formula = text(metric.formula());
        if (formula.isBlank()) {
            return false;
        }
        List<String> columns = new ArrayList<>();
        Matcher matcher = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[\\u4e00-\\u9fa5]{2,}").matcher(formula);
        while (matcher.find()) {
            String column = resolveColumn(matcher.group(), metric.fields(), true);
            if (!column.isBlank()) {
                columns.add(column);
            }
        }
        return !columns.isEmpty() && columns.stream().allMatch(column -> text(sql).contains("`" + column + "`"));
    }

    private Map<String, Object> findModelById(Long id, String tableName) {
        if (id == null || id <= 0) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, model_name AS modelName, table_name AS tableName, model_json AS modelJson,
                       owner_id AS ownerId, published, updated_at AS updatedAt
                FROM is_business_model
                WHERE id = ? AND status = 'ACTIVE'
                """, id);
        return rows.stream()
                .filter(row -> tableName.equals(text(row.get("tableName"))))
                .filter(this::canReuse)
                .findFirst()
                .orElse(Map.of());
    }

    private Map<String, Object> findLatestModelForTable(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, model_name AS modelName, table_name AS tableName, model_json AS modelJson,
                       owner_id AS ownerId, published, updated_at AS updatedAt
                FROM is_business_model
                WHERE table_name = ? AND status = 'ACTIVE'
                ORDER BY updated_at DESC
                LIMIT 10
                """, tableName);
        return rows.stream().filter(this::canReuse).findFirst().orElse(Map.of());
    }

    private boolean canReuse(Map<String, Object> row) {
        try {
            if (AuthContext.isAdmin()) {
                return true;
            }
            if (truthy(row.get("published"))) {
                return true;
            }
            return text(row.get("ownerId")).equals(AuthContext.userId());
        } catch (Exception ignored) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(text(key), item));
            return result;
        }
        String json = text(value);
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, val) -> copy.put(text(key), val));
                result.add(copy);
            }
        }
        return result;
    }

    private String resolveColumn(String reference, List<Map<String, Object>> fields, boolean preferNumeric) {
        String ref = normalize(reference);
        if (ref.isBlank()) {
            return "";
        }
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (Map<String, Object> field : fields) {
            String column = text(field.get("columnName"));
            if (column.isBlank()) {
                continue;
            }
            if (preferNumeric && !isNumeric(field)) {
                continue;
            }
            int score = fieldMatchScore(ref, field);
            if (score > bestScore) {
                bestScore = score;
                best = column;
            }
        }
        return bestScore > 0 ? best : "";
    }

    private int fieldMatchScore(String normalizedRef, Map<String, Object> field) {
        int score = 0;
        List<String> aliases = List.of(
                text(field.get("columnName")),
                text(field.get("displayName")),
                text(field.get("sourceFieldName")),
                text(field.get("fieldComment"))
        );
        for (String alias : aliases) {
            String normalized = normalize(alias);
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.equals(normalizedRef)) {
                score = Math.max(score, 200);
            } else if (normalized.contains(normalizedRef) || normalizedRef.contains(normalized)) {
                score = Math.max(score, 80);
            }
        }
        return score;
    }

    private int scoreMention(String question, List<String> aliases) {
        String normalizedQuestion = normalize(question);
        int score = 0;
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.length() < 2) {
                continue;
            }
            if (normalizedQuestion.contains(normalizedAlias)) {
                score += 100 + Math.min(normalizedAlias.length(), 20);
            }
        }
        return score;
    }

    private Map<String, Object> findBestTimeField(List<Map<String, Object>> fields) {
        return fields.stream()
                .map(field -> Map.entry(field, timeScore(field)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Map.of());
    }

    private int timeScore(Map<String, Object> field) {
        String haystack = normalize(text(field.get("columnName")) + " " + text(field.get("displayName")) + " "
                + text(field.get("sourceFieldName")) + " " + text(field.get("fieldComment")));
        int score = "DATE".equalsIgnoreCase(text(field.get("fieldType"))) ? 100 : 0;
        if (containsAny(haystack, "date", "time", "day", "month", "year", "orderdate", "日期", "时间", "月份", "年月")) {
            score += 80;
        }
        return score;
    }

    private String timeDimensionExpression(String question, String column) {
        String q = text(question);
        if (containsAny(q, "每月", "按月", "月份", "月度", "每个月")) {
            return "DATE_FORMAT(`" + column + "`, '%Y-%m')";
        }
        if (containsAny(q, "每天", "每日", "按日", "日度")) {
            return "DATE(`" + column + "`)";
        }
        if (containsAny(q, "每年", "按年", "年度")) {
            return "YEAR(`" + column + "`)";
        }
        return "DATE_FORMAT(`" + column + "`, '%Y-%m')";
    }

    private String timeLabel(String question, Map<String, Object> field) {
        String base = firstNonBlank(text(field.get("displayName")), text(field.get("sourceFieldName")), text(field.get("columnName")));
        return base.isBlank() ? "时间" : base;
    }

    private String nonEmptyPredicate(String column) {
        if (column.isBlank()) {
            return "1=1";
        }
        return "`" + column + "` IS NOT NULL AND `" + column + "` <> ''";
    }

    private String aggregateColumn(String column, String aggregation) {
        String safeColumn = text(column);
        if (safeColumn.isBlank()) {
            return "";
        }
        String agg = text(aggregation).toUpperCase(Locale.ROOT);
        if ("COUNT".equals(agg) || "COUNT_DISTINCT".equals(agg)) {
            return "COUNT(`" + safeColumn + "`)";
        }
        if ("AVG".equals(agg)) {
            return "AVG(CAST(NULLIF(`" + safeColumn + "`, '') AS DECIMAL(18,2)))";
        }
        if ("MAX".equals(agg) || "MIN".equals(agg)) {
            return agg + "(CAST(NULLIF(`" + safeColumn + "`, '') AS DECIMAL(18,2)))";
        }
        return "SUM(CAST(NULLIF(`" + safeColumn + "`, '') AS DECIMAL(18,2)))";
    }

    private String replaceToken(String expression, String token, String replacement) {
        return expression.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])",
                Matcher.quoteReplacement(replacement));
    }

    private String protectDivision(String expression) {
        String value = expression.replaceAll("\\s+", " ").trim();
        int slash = value.indexOf('/');
        if (slash < 0) {
            return value;
        }
        String left = value.substring(0, slash).trim();
        String right = value.substring(slash + 1).trim();
        if (left.isBlank() || right.isBlank()) {
            return value;
        }
        return left + " / NULLIF(" + right + ", 0)";
    }

    private String primaryFormulaField(String formula) {
        Matcher matcher = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(text(formula));
        return matcher.find() ? matcher.group() : "";
    }

    private boolean formulaTokenEquals(String formula, String field) {
        String target = normalize(field);
        Matcher matcher = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[\\u4e00-\\u9fa5]{2,}").matcher(text(formula));
        while (matcher.find()) {
            if (normalize(matcher.group()).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameIdentifier(String left, String right) {
        return !normalize(left).isBlank() && normalize(left).equals(normalize(right));
    }

    private boolean isSqlFunction(String token) {
        String lower = text(token).toLowerCase(Locale.ROOT);
        return Set.of("sum", "avg", "count", "min", "max", "nullif", "cast", "decimal", "date_format", "date", "year")
                .contains(lower);
    }

    private boolean isNumericColumn(String column, List<Map<String, Object>> fields) {
        return fields.stream().anyMatch(field -> column.equals(text(field.get("columnName"))) && isNumeric(field));
    }

    private boolean isNumeric(Map<String, Object> field) {
        return "NUMBER".equalsIgnoreCase(text(field.get("fieldType")))
                || "DECIMAL".equalsIgnoreCase(text(field.get("dataType")));
    }

    private boolean isTimeSeriesQuestion(String question) {
        return containsAny(text(question), "趋势", "走势", "变化", "每月", "按月", "月份", "月度",
                "每日", "按日", "每年", "年度", "trend");
    }

    private boolean isDetailQuestion(String question) {
        return containsAny(text(question), "明细", "详情", "列表", "表格", "列出", "detail");
    }

    private boolean containsAny(String text, String... values) {
        String source = text(text).toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (source.contains(text(value).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitAliases(String value) {
        List<String> result = new ArrayList<>();
        for (String item : text(value).split("[,，、/|;；\\s]+")) {
            String alias = item.trim();
            if (!alias.isBlank()) {
                result.add(alias);
            }
        }
        return result;
    }

    private String normalize(String value) {
        return text(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "");
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = text(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Long toLong(Object value) {
        try {
            String text = text(value);
            return text.isBlank() ? null : Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addId(List<Long> ids, Object value) {
        Long id = toLong(value);
        if (id != null && id > 0 && !ids.contains(id)) {
            ids.add(id);
        }
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Set.of("true", "1", "yes", "on").contains(text(value).toLowerCase(Locale.ROOT));
    }

    public record BusinessSemanticContext(boolean available,
                                          Long modelId,
                                          String modelName,
                                          String tableName,
                                          String source,
                                          String modelVersion,
                                          List<Map<String, Object>> dictionaryEntries,
                                          List<Map<String, Object>> metricDefinitions,
                                          List<Map<String, Object>> dimensionSystem,
                                          List<Map<String, Object>> fields) {
        static BusinessSemanticContext empty() {
            return new BusinessSemanticContext(false, null, "", "", "", "", List.of(), List.of(), List.of(), List.of());
        }
    }

    public record BusinessSemanticMatch(String name,
                                        String field,
                                        String column,
                                        String aggregation,
                                        String formula,
                                        String source,
                                        boolean dictionaryMatched,
                                        String dictionaryHitTerm,
                                        int score,
                                        List<Map<String, Object>> fields) {
        BusinessSemanticMatch withDictionary(String term, int nextScore) {
            return new BusinessSemanticMatch(name, field, column, aggregation, formula, source,
                    true, term, nextScore, fields);
        }
    }

    public record BusinessSemanticPlan(BusinessSemanticMatch metric,
                                       BusinessSemanticMatch dimension,
                                       boolean timeSeries,
                                       boolean detail,
                                       Map<String, Object> trace) {
        static BusinessSemanticPlan empty() {
            return new BusinessSemanticPlan(null, null, false, false, Map.of("enabled", false));
        }

        boolean hasSemanticConstraint() {
            return metric != null || dimension != null;
        }

        List<Map<String, Object>> contextFields() {
            if (metric != null) {
                return metric.fields();
            }
            return dimension == null ? List.of() : dimension.fields();
        }
    }

    public record BusinessSqlCorrection(String sql,
                                        String chartType,
                                        Map<String, Object> fieldMapping,
                                        Map<String, Object> trace,
                                        boolean changed,
                                        String reason) {
    }

    public record BusinessAnalysisResolution(boolean matched,
                                             String metricLabel,
                                             String metricColumn,
                                             String metricField,
                                             String metricExpression,
                                             String formula,
                                             boolean formulaApplied,
                                             Map<String, Object> trace) {
        static BusinessAnalysisResolution empty(Map<String, Object> trace) {
            return new BusinessAnalysisResolution(false, "", "", "", "", "", false,
                    trace == null ? Map.of("enabled", false) : trace);
        }
    }
}
