package com.insightspark.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;

@Component
public class RuleBasedNl2SqlStrategy {

    private static final List<SemanticFieldRule> SEMANTIC_FIELD_RULES = List.of(
            new SemanticFieldRule("产品类型", "DIMENSION", 120, List.of("product_type", "producttype", "type", "category", "cate"),
                    List.of("产品类型", "商品类型", "品类", "类别")),
            new SemanticFieldRule("产品子类", "DIMENSION", 115, List.of("product_type_2", "sub_type", "subtype", "sub_category", "subcategory"),
                    List.of("产品子类", "商品子类", "子品类", "子类别")),
            new SemanticFieldRule("产品名称", "DIMENSION", 110, List.of("product_name", "product", "sku", "item", "name"),
                    List.of("产品名称", "商品名称")),
            new SemanticFieldRule("客户类型", "DIMENSION", 108, List.of("cus_type", "customer_type", "client_type"),
                    List.of("客户类型")),
            new SemanticFieldRule("客户名称", "DIMENSION", 106, List.of("cus_name", "customer_name", "client_name", "customer"),
                    List.of("客户", "顾客", "客户名称")),
            new SemanticFieldRule("省份", "DIMENSION", 104, List.of("province", "prov", "state"),
                    List.of("省份", "省市", "地区", "省")),
            new SemanticFieldRule("城市", "DIMENSION", 102, List.of("city"),
                    List.of("城市", "市")),
            new SemanticFieldRule("区域", "DIMENSION", 100, List.of("region", "area"),
                    List.of("区域", "大区")),
            new SemanticFieldRule("时间", "TIME", 130, List.of("date", "time", "day", "month", "year", "week", "quarter",
                    "order_date", "sales_date", "biz_date", "stat_date", "trade_date", "created_at", "updated_at",
                    "日期", "时间", "订单日期", "销售日期", "月份", "年月", "年份", "年度", "月度", "季度"),
                    List.of("日期", "时间", "日", "每日", "天", "月份", "月", "每月", "月度", "年月", "年份", "年", "年度",
                            "周", "每周", "季度", "每季度")),
            new SemanticFieldRule("销售额", "METRIC", 120, List.of("sales_amt", "sales", "sale", "amount", "amt", "revenue", "gmv"),
                    List.of("销售额", "销售", "金额", "营收", "收入")),
            new SemanticFieldRule("利润", "METRIC", 118, List.of("profit", "margin"),
                    List.of("利润", "盈利", "毛利")),
            new SemanticFieldRule("数量", "METRIC", 116, List.of("qty", "quantity", "count", "volume"),
                    List.of("数量", "销量", "件数")),
            new SemanticFieldRule("折扣", "METRIC", 114, List.of("discount"),
                    List.of("折扣", "折让"))
    );

    public FieldChoice chooseFields(String question, List<Map<String, Object>> fields) {
        Map<String, Object> dimension = findBestField(question, fields, "TEXT");
        if (isTimeSeriesQuestion(question)) {
            Map<String, Object> dateField = findBestField(question, fields, "DATE");
            if (dateField != null) {
                dimension = dateField;
            }
        }
        if (dimension == null) {
            dimension = fields.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("当前数据表没有可查询字段"));
        }

        Map<String, Object> metric = findBestField(question, fields, "NUMBER");
        String dimensionType = isTimeSeriesQuestion(question) && isTimeLikeField(dimension)
                ? "DATE"
                : Objects.toString(dimension.get("fieldType"), "TEXT");
        return new FieldChoice(
                Objects.toString(dimension.get("columnName")),
                Objects.toString(dimension.get("displayName")),
                dimensionType,
                metric == null ? null : Objects.toString(metric.get("columnName")),
                metric == null ? null : Objects.toString(metric.get("displayName")),
                chooseTableColumns(question, fields),
                buildResolutionLog(question, fields, dimension, metric)
        );
    }

    public String chooseChartType(String question, String dimensionType) {
        if (isDetailQuestion(question)) {
            return "table";
        }
        if (isTimeSeriesQuestion(question) && isTimeDimensionType(dimensionType)) {
            return "line";
        }
        if (question.contains("占比") || question.contains("比例") || question.contains("分类")) {
            return "pie";
        }
        if (isTimeDimensionType(dimensionType)) {
            return "line";
        }
        return "bar";
    }

    public String buildSql(String tableName, FieldChoice fieldChoice, String chartType) {
        if ("table".equalsIgnoreCase(chartType)) {
            List<String> columns = fieldChoice.tableColumns();
            if (columns == null || columns.isEmpty()) {
                columns = List.of(fieldChoice.dimensionColumn());
            }
            String selectColumns = columns.stream()
                    .filter(column -> column != null && !column.isBlank())
                    .distinct()
                    .limit(12)
                    .map(column -> "`" + column + "`")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("*");
            return "SELECT " + selectColumns + " FROM `" + tableName + "` LIMIT 100";
        }
        String valueExpr = fieldChoice.metricColumn() == null
                ? "COUNT(1)"
                : "SUM(CAST(NULLIF(`" + fieldChoice.metricColumn() + "`, '') AS DECIMAL(18,2)))";
        String orderExpr = "line".equals(chartType) ? "name ASC" : "value DESC";
        return "SELECT `" + fieldChoice.dimensionColumn() + "` AS name, " + valueExpr + " AS value FROM `"
                + tableName + "` WHERE `" + fieldChoice.dimensionColumn() + "` IS NOT NULL AND `"
                + fieldChoice.dimensionColumn() + "` <> '' GROUP BY `" + fieldChoice.dimensionColumn()
                + "` ORDER BY " + orderExpr + " LIMIT 30";
    }

    private Map<String, Object> findBestField(String question, List<Map<String, Object>> fields, String preferredType) {
        Map<String, Object> semanticMatch = findSemanticField(question, fields, preferredType);
        if (semanticMatch != null) {
            return semanticMatch;
        }
        return fields.stream()
                .filter(field -> fieldTypeMatches(field, preferredType))
                .filter(field -> question.contains(Objects.toString(field.get("displayName")))
                        || question.contains(Objects.toString(field.get("sourceFieldName"))))
                .findFirst()
                .orElseGet(() -> fields.stream()
                        .filter(field -> fieldTypeMatches(field, preferredType))
                        .findFirst()
                        .orElse(null));
    }

    private Map<String, Object> findSemanticField(String question, List<Map<String, Object>> fields, String preferredType) {
        List<SemanticFieldRule> rules = matchedRulesForQuestion(question, preferredType);
        if (rules.isEmpty()) {
            return null;
        }
        return fields.stream()
                .filter(field -> fieldTypeMatches(field, preferredType))
                .map(field -> Map.entry(field, semanticScore(field, rules)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private int semanticScore(Map<String, Object> field, List<SemanticFieldRule> rules) {
        String columnName = Objects.toString(field.get("columnName"), "").toLowerCase(Locale.ROOT);
        String displayName = Objects.toString(field.get("displayName"), "").toLowerCase(Locale.ROOT);
        String sourceFieldName = Objects.toString(field.get("sourceFieldName"), "").toLowerCase(Locale.ROOT);
        String fieldComment = Objects.toString(field.get("fieldComment"), "").toLowerCase(Locale.ROOT);
        String haystack = columnName + " " + displayName + " " + sourceFieldName + " " + fieldComment;
        int score = 0;
        for (SemanticFieldRule rule : rules) {
            for (String candidate : rule.candidateFields()) {
                String normalized = candidate.toLowerCase(Locale.ROOT);
                if (columnName.equals(normalized) || sourceFieldName.equals(normalized)) {
                    score += rule.priority() + 40;
                } else if (columnName.contains(normalized) || sourceFieldName.contains(normalized)) {
                    score += rule.priority();
                } else if (displayName.contains(normalized) || fieldComment.contains(normalized)) {
                    score += Math.max(8, rule.priority() / 4);
                } else if (haystack.contains(normalized)) {
                    score += Math.max(4, rule.priority() / 8);
                }
            }
        }
        return score;
    }

    private boolean isDetailQuestion(String question) {
        String text = Objects.toString(question, "");
        boolean asksMultipleFields = text.contains("显示") && (text.contains("、") || text.contains("，")
                || text.contains(",") || text.contains("和"));
        return text.contains("明细") || text.contains("详情") || text.contains("列表")
                || text.contains("表格") || asksMultipleFields || text.contains("列出")
                || text.toLowerCase(Locale.ROOT).contains("detail");
    }

    private List<String> chooseTableColumns(String question, List<Map<String, Object>> fields) {
        Set<String> selected = new LinkedHashSet<>();
        List<SemanticFieldRule> rules = new ArrayList<>(matchedRulesForQuestion(question, "TEXT"));
        rules.addAll(matchedRulesForQuestion(question, "NUMBER"));
        for (SemanticFieldRule rule : rules) {
            for (Map<String, Object> field : fields) {
                if (!matchesRuleToField(rule, field)) {
                    continue;
                }
                String column = Objects.toString(field.get("columnName"), "").trim();
                if (!column.isBlank()) {
                    selected.add(column);
                }
            }
        }
        if (selected.isEmpty()) {
            for (Map<String, Object> field : fields) {
                String column = Objects.toString(field.get("columnName"), "").trim();
                if (!column.isBlank()) {
                    selected.add(column);
                }
                if (selected.size() >= 8) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private boolean matchesRuleToField(SemanticFieldRule rule, Map<String, Object> field) {
        String haystack = (Objects.toString(field.get("columnName"), "") + " "
                + Objects.toString(field.get("displayName"), "") + " "
                + Objects.toString(field.get("sourceFieldName"), "") + " "
                + Objects.toString(field.get("fieldComment"), "")).toLowerCase(Locale.ROOT);
        for (String candidate : rule.candidateFields()) {
            String normalized = candidate.toLowerCase(Locale.ROOT);
            if (haystack.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean fieldTypeMatches(Map<String, Object> field, String preferredType) {
        String type = Objects.toString(field.get("fieldType"), "").toUpperCase(Locale.ROOT);
        String preferred = Objects.toString(preferredType, "").toUpperCase(Locale.ROOT);
        if ("DATE".equals(preferred)) {
            return "DATE".equals(type) || isTimeLikeField(field);
        }
        return preferred.equals(type);
    }

    private boolean isTimeLikeField(Map<String, Object> field) {
        String type = Objects.toString(field.get("fieldType"), "").toLowerCase(Locale.ROOT);
        String label = (Objects.toString(field.get("columnName"), "") + " "
                + Objects.toString(field.get("displayName"), "") + " "
                + Objects.toString(field.get("sourceFieldName"), "") + " "
                + Objects.toString(field.get("fieldComment"), "")).toLowerCase(Locale.ROOT);
        if (type.contains("date") || type.contains("time")) {
            return true;
        }
        boolean hasTimeSignal = containsAny(label, "date", "time", "day", "month", "year", "week", "quarter",
                "日期", "时间", "月份", "年月", "年份", "年度", "月度", "季度");
        return hasTimeSignal && !hasStrongMetricSignal(label);
    }

    private boolean isTimeSeriesQuestion(String question) {
        String text = Objects.toString(question, "");
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

    private boolean isTimeDimensionType(String dimensionType) {
        String type = Objects.toString(dimensionType, "").toUpperCase(Locale.ROOT);
        return "DATE".equals(type) || "TIME".equals(type);
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate.toLowerCase(Locale.ROOT))) {
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

    private List<SemanticFieldRule> matchedRulesForQuestion(String question, String preferredType) {
        String q = Objects.toString(question, "");
        List<SemanticFieldRule> matched = new ArrayList<>();
        for (SemanticFieldRule rule : SEMANTIC_FIELD_RULES) {
            if (!roleMatchesPreferredType(rule.fieldRole(), preferredType)) {
                continue;
            }
            if (rule.keywords().stream().anyMatch(q::contains)) {
                matched.add(rule);
            }
        }
        matched.sort((left, right) -> Integer.compare(right.priority(), left.priority()));
        return matched;
    }

    private boolean roleMatchesPreferredType(String fieldRole, String preferredType) {
        String role = Objects.toString(fieldRole, "").toUpperCase(Locale.ROOT);
        String type = Objects.toString(preferredType, "").toUpperCase(Locale.ROOT);
        if ("NUMBER".equals(type)) {
            return "METRIC".equals(role);
        }
        if ("TEXT".equals(type)) {
            return "DIMENSION".equals(role);
        }
        if ("DATE".equals(type)) {
            return "TIME".equals(role);
        }
        return true;
    }

    private Map<String, Object> buildResolutionLog(String question, List<Map<String, Object>> fields,
                                                   Map<String, Object> dimension, Map<String, Object> metric) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("dimensionKey", Objects.toString(dimension.get("columnName"), ""));
        log.put("dimensionName", Objects.toString(dimension.get("displayName"), ""));
        log.put("metricKey", metric == null ? "" : Objects.toString(metric.get("columnName"), ""));
        log.put("metricName", metric == null ? "记录数" : Objects.toString(metric.get("displayName"), ""));
        log.put("tableColumns", chooseTableColumns(question, fields));
        log.put("tableColumnLabels", buildTableColumnLabels(fields));
        log.put("reason", "基于用户原话、字段展示名、物理字段名和内置业务同义词匹配。");
        return log;
    }

    private Map<String, Object> buildTableColumnLabels(List<Map<String, Object>> fields) {
        Map<String, Object> labels = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "").trim();
            if (columnName.isBlank()) {
                continue;
            }
            String displayName = Objects.toString(field.get("displayName"), "");
            String sourceFieldName = Objects.toString(field.get("sourceFieldName"), "");
            String fieldComment = Objects.toString(field.get("fieldComment"), "");
            String label = isPhysicalColumnCode(displayName)
                    ? firstNonBlank(sourceFieldName, fieldComment, columnName)
                    : firstNonBlank(displayName, sourceFieldName, fieldComment, columnName);
            labels.put(columnName, label);
        }
        return labels;
    }

    private boolean isPhysicalColumnCode(String value) {
        return Objects.toString(value, "").trim().matches("(?i)^col[_-]?\\d+$");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = Objects.toString(value, "").trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    public record FieldChoice(String dimensionColumn, String dimensionDisplayName, String dimensionType,
                              String metricColumn, String metricDisplayName, List<String> tableColumns,
                              Map<String, Object> resolutionLog) {
        public FieldChoice(String dimensionColumn, String dimensionDisplayName, String dimensionType,
                           String metricColumn, String metricDisplayName) {
            this(dimensionColumn, dimensionDisplayName, dimensionType, metricColumn, metricDisplayName,
                    List.of(), Map.of());
        }
    }

    private record SemanticFieldRule(String businessTerm, String fieldRole, int priority,
                                     List<String> candidateFields, List<String> keywords) {
    }
}
