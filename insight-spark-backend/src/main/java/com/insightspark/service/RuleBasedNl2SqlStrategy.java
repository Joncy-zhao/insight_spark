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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            new SemanticFieldRule("渠道", "DIMENSION", 98, List.of("channel", "source", "platform"),
                    List.of("渠道", "来源", "平台")),
            new SemanticFieldRule("产品线", "DIMENSION", 97, List.of("product_line", "line", "series"),
                    List.of("产品线", "业务线", "产品系列")),
            new SemanticFieldRule("营销活动", "DIMENSION", 82, List.of("campaign", "activity", "marketing"),
                    List.of("营销活动", "活动", "投放活动")),
            new SemanticFieldRule("备注", "DIMENSION", 60, List.of("remark", "memo", "note", "comment"),
                    List.of("备注", "说明", "描述")),
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
            new SemanticFieldRule("订单量", "METRIC", 115, List.of("order_count", "orders", "order_qty", "order_num"),
                    List.of("订单量", "订单数", "订单")),
            new SemanticFieldRule("退货率", "METRIC", 113, List.of("return_rate", "refund_rate", "ret_rate", "rate"),
                    List.of("退货率", "退款率", "退货")),
            new SemanticFieldRule("库存周转天数", "METRIC", 112, List.of("inventory_turnover_days", "turnover_days", "stock_days"),
                    List.of("库存周转天数", "周转天数", "库存周转")),
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
        String text = question == null ? "" : question;
        String lower = text.toLowerCase();
        if (text.contains("地图") || text.contains("地域") || text.contains("地区")
                || text.contains("省份") || text.contains("城市") || lower.contains("map")
                || lower.contains("geo")) {
            return "map";
        }
        if (text.contains("散点") || text.contains("相关") || text.contains("相关性")
                || text.contains("离群") || lower.contains("scatter") || lower.contains("correlation")) {
            return "scatter";
        }
        if (text.contains("雷达") || text.contains("能力") || text.contains("评分")
                || text.contains("画像") || text.contains("多指标") || lower.contains("radar")) {
            return "radar";
        }
        if (text.contains("指标卡") || text.contains("核心指标") || text.contains("当前值")
                || text.contains("总量") || text.contains("总额") || lower.contains("kpi")
                || lower.contains("metric")) {
            return "metric";
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
            String whereClause = detailWhereClause(fieldChoice);
            String orderClause = detailOrderClause(fieldChoice);
            return "SELECT " + selectColumns + " FROM `" + tableName + "`" + whereClause + orderClause
                    + " LIMIT " + detailLimit(fieldChoice);
        }
        String valueExpr = fieldChoice.metricColumn() == null
                ? "COUNT(1)"
                : "SUM(CAST(NULLIF(`" + fieldChoice.metricColumn() + "`, '') AS DECIMAL(18,2)))";
        if ("metric".equalsIgnoreCase(chartType)) {
            String label = fieldChoice.metricDisplayName() == null || fieldChoice.metricDisplayName().isBlank()
                    ? "记录数"
                    : fieldChoice.metricDisplayName();
            return "SELECT '" + metricCardLabel(label).replace("'", "''") + "' AS name, "
                    + metricCardValueExpression(fieldChoice) + " AS value FROM `"
                    + tableName + "` LIMIT 1";
        }
        String orderExpr = "line".equals(chartType) ? "name ASC" : "value DESC";
        return "SELECT `" + fieldChoice.dimensionColumn() + "` AS name, " + valueExpr + " AS value FROM `"
                + tableName + "` WHERE `" + fieldChoice.dimensionColumn() + "` IS NOT NULL AND `"
                + fieldChoice.dimensionColumn() + "` <> '' GROUP BY `" + fieldChoice.dimensionColumn()
                + "` ORDER BY " + orderExpr + " LIMIT 30";
    }

    private String metricCardValueExpression(FieldChoice fieldChoice) {
        if (fieldChoice.metricColumn() == null) {
            return "COUNT(1)";
        }
        String label = Objects.toString(fieldChoice.metricDisplayName(), "");
        String castExpr = "CAST(NULLIF(`" + fieldChoice.metricColumn() + "`, '') AS DECIMAL("
                + (isAverageMetricLabel(label) ? "18,6" : "18,2") + "))";
        return (isAverageMetricLabel(label) ? "AVG(" : "SUM(") + castExpr + ")";
    }

    private String metricCardLabel(String label) {
        if (label == null || label.isBlank()) {
            return "记录数";
        }
        return isAverageMetricLabel(label) && !label.startsWith("平均") && !label.startsWith("均值")
                ? "平均" + label
                : label;
    }

    private boolean isAverageMetricLabel(String label) {
        String text = Objects.toString(label, "").toLowerCase();
        return text.contains("率") || text.contains("ratio") || text.contains("rate")
                || text.contains("percent") || text.contains("天数") || text.contains("时长")
                || text.contains("周期") || text.contains("周转") || text.contains("客单价")
                || text.contains("单价") || text.startsWith("平均") || text.startsWith("均值");
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
        List<Map.Entry<Map<String, Object>, Integer>> explicitlyMentioned = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            int position = firstMentionPosition(question, field);
            if (position >= 0) {
                explicitlyMentioned.add(Map.entry(field, position));
            }
        }
        explicitlyMentioned.sort((left, right) -> Integer.compare(left.getValue(), right.getValue()));
        for (Map.Entry<Map<String, Object>, Integer> entry : explicitlyMentioned) {
            String column = Objects.toString(entry.getKey().get("columnName"), "").trim();
            if (!column.isBlank()) {
                selected.add(column);
            }
        }
        List<SemanticFieldRule> rules = new ArrayList<>(matchedRulesForQuestion(question, "TEXT"));
        rules.addAll(matchedRulesForQuestion(question, "NUMBER"));
        rules.addAll(matchedRulesForQuestion(question, "DATE"));
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

    private String detailOrderClause(FieldChoice fieldChoice) {
        if (fieldChoice == null || fieldChoice.resolutionLog() == null) {
            return "";
        }
        String orderColumn = Objects.toString(fieldChoice.resolutionLog().get("detailOrderColumn"), "").trim();
        if (orderColumn.isBlank()) {
            return "";
        }
        String direction = Objects.toString(fieldChoice.resolutionLog().getOrDefault("detailOrderDirection", "ASC"), "ASC")
                .trim().toUpperCase(Locale.ROOT);
        if (!"DESC".equals(direction)) {
            direction = "ASC";
        }
        return " ORDER BY `" + orderColumn + "` " + direction;
    }

    private String detailWhereClause(FieldChoice fieldChoice) {
        List<Map<String, Object>> filters = detailFilters(fieldChoice);
        if (filters.isEmpty()) {
            return "";
        }
        List<String> predicates = new ArrayList<>();
        for (Map<String, Object> filter : filters) {
            String column = Objects.toString(filter.get("column"), "").trim();
            String value = Objects.toString(filter.get("value"), "").trim();
            if (column.isBlank() || value.isBlank()) {
                continue;
            }
            String escaped = escapeSqlLiteral(value);
            predicates.add("(`" + column + "` = '" + escaped + "' OR `" + column + "` LIKE '%" + escaped + "%')");
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    private List<Map<String, Object>> detailFilters(FieldChoice fieldChoice) {
        if (fieldChoice == null || fieldChoice.resolutionLog() == null) {
            return List.of();
        }
        Object raw = fieldChoice.resolutionLog().get("detailFilters");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> safe = new LinkedHashMap<>();
                map.forEach((key, value) -> safe.put(Objects.toString(key, ""), value));
                filters.add(safe);
            }
        }
        return filters;
    }

    private int detailLimit(FieldChoice fieldChoice) {
        Object raw = fieldChoice == null || fieldChoice.resolutionLog() == null
                ? null
                : fieldChoice.resolutionLog().get("detailLimit");
        if (raw instanceof Number number) {
            return clampLimit(number.intValue());
        }
        try {
            return clampLimit(Integer.parseInt(Objects.toString(raw, "").trim()));
        } catch (Exception ignored) {
            return 100;
        }
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
        Map<String, Object> orderField = resolveDetailOrderField(question, fields);
        if (!orderField.isEmpty()) {
            log.put("detailOrderColumn", Objects.toString(orderField.get("columnName"), ""));
            log.put("detailOrderLabel", bestDisplayLabel(orderField));
            log.put("detailOrderDirection", resolveDetailOrderDirection(question));
        }
        List<Map<String, Object>> detailFilters = resolveDetailFilters(question, fields);
        if (!detailFilters.isEmpty()) {
            log.put("detailFilters", detailFilters);
        }
        log.put("detailLimit", resolveDetailLimit(question));
        log.put("reason", "基于用户原话、字段展示名、物理字段名和内置业务同义词匹配。");
        return log;
    }

    private List<Map<String, Object>> resolveDetailFilters(String question, List<Map<String, Object>> fields) {
        String text = Objects.toString(question, "");
        Map<String, Map<String, Object>> filtersByColumn = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            if (!isFilterableDetailField(field)) {
                continue;
            }
            String value = resolveExplicitFilterValue(text, field);
            if (value.isBlank()) {
                continue;
            }
            String column = Objects.toString(field.get("columnName"), "").trim();
            if (column.isBlank()) {
                continue;
            }
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("column", column);
            filter.put("label", bestDisplayLabel(field));
            filter.put("value", value);
            filtersByColumn.put(column, filter);
        }
        addKnownValueFilter(text, fields, filtersByColumn, "区域", List.of(
                "华东", "华南", "华北", "华中", "中南", "西南", "西北", "东北", "东南", "港澳台"));
        return List.copyOf(filtersByColumn.values());
    }

    private void addKnownValueFilter(String question, List<Map<String, Object>> fields,
                                     Map<String, Map<String, Object>> filtersByColumn,
                                     String fieldKeyword, List<String> values) {
        Map<String, Object> field = findTextFieldByKeyword(fields, fieldKeyword);
        if (field == null) {
            return;
        }
        String column = Objects.toString(field.get("columnName"), "").trim();
        if (column.isBlank() || filtersByColumn.containsKey(column)) {
            return;
        }
        for (String value : values) {
            if (question.contains(value)) {
                Map<String, Object> filter = new LinkedHashMap<>();
                filter.put("column", column);
                filter.put("label", bestDisplayLabel(field));
                filter.put("value", value);
                filtersByColumn.put(column, filter);
                return;
            }
        }
    }

    private String resolveExplicitFilterValue(String question, Map<String, Object> field) {
        for (String label : fieldLabels(field)) {
            if (label.isBlank() || isPhysicalColumnCode(label)) {
                continue;
            }
            String valueBefore = valueBeforeLabel(question, label);
            if (isLikelyDetailFilterValue(valueBefore, label)) {
                return valueBefore;
            }
            String valueAfter = valueAfterLabel(question, label);
            if (isLikelyDetailFilterValue(valueAfter, label)) {
                return valueAfter;
            }
        }
        return "";
    }

    private String valueBeforeLabel(String question, String label) {
        int index = question.indexOf(label);
        if (index <= 0) {
            return "";
        }
        String prefix = question.substring(0, index);
        Matcher matcher = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,24})\\s*(?:的)?$").matcher(prefix);
        if (!matcher.find()) {
            return "";
        }
        return cleanDetailFilterValue(matcher.group(1));
    }

    private String valueAfterLabel(String question, String label) {
        int index = question.indexOf(label);
        if (index < 0) {
            return "";
        }
        String suffix = question.substring(index + label.length());
        Matcher matcher = Pattern.compile("^(?:\\s*(?:为|是|=|等于|包含|包括|选择|筛选|过滤为|限定为|设为|在|只看|仅看)\\s*)([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,24})")
                .matcher(suffix);
        if (!matcher.find()) {
            return "";
        }
        return cleanDetailFilterValue(matcher.group(1));
    }

    private String cleanDetailFilterValue(String value) {
        return Objects.toString(value, "").trim()
                .replaceAll("^(?:请|帮我|查询|查看|列出|筛选|过滤|只看|仅看|展示|生成|统计|分析|当前|数据中|数据里|所有|全部|按|在)+", "")
                .replaceAll("(?:的|中|下|里)$", "")
                .trim();
    }

    private boolean isLikelyDetailFilterValue(String value, String label) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank() || text.length() > 20 || text.equals(label)) {
            return false;
        }
        return !Set.of("字段", "包括", "包含", "明细", "明细表", "数据", "当前数据", "销售", "销售明细", "统计", "分析")
                .contains(text);
    }

    private boolean isFilterableDetailField(Map<String, Object> field) {
        String type = Objects.toString(field.get("fieldType"), "").toUpperCase(Locale.ROOT);
        return !"NUMBER".equals(type) && !isTimeLikeField(field);
    }

    private Map<String, Object> findTextFieldByKeyword(List<Map<String, Object>> fields, String keyword) {
        String target = normalizeForFieldMatch(keyword);
        return fields.stream()
                .filter(this::isFilterableDetailField)
                .filter(field -> fieldLabels(field).stream()
                        .map(this::normalizeForFieldMatch)
                        .anyMatch(label -> label.contains(target) || target.contains(label)))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> resolveDetailOrderField(String question, List<Map<String, Object>> fields) {
        String text = Objects.toString(question, "");
        if (!containsAny(text, "排序", "升序", "降序", "正序", "倒序", "按", "order", "sort")) {
            return Map.of();
        }
        for (Map<String, Object> field : fields) {
            for (String label : fieldLabels(field)) {
                if (label.isBlank()) {
                    continue;
                }
                if (text.contains("按" + label) || text.contains("以" + label)
                        || text.contains("根据" + label) || text.contains(label + "排序")
                        || text.contains(label + "升序") || text.contains(label + "降序")) {
                    return field;
                }
            }
        }
        if (containsAny(text, "日期", "时间", "按日", "每日")) {
            return fields.stream().filter(this::isTimeLikeField).findFirst().orElse(Map.of());
        }
        return Map.of();
    }

    private String resolveDetailOrderDirection(String question) {
        String text = Objects.toString(question, "");
        String lower = text.toLowerCase(Locale.ROOT);
        return containsAny(text, "降序", "倒序", "从高到低", "由高到低", "从大到小", "由大到小")
                || lower.contains(" desc") || lower.contains("descending")
                ? "DESC"
                : "ASC";
    }

    private int resolveDetailLimit(String question) {
        String text = Objects.toString(question, "");
        Matcher matcher = Pattern.compile("(?i)(?:前|最多|限制|显示|列出|limit\\s*)\\s*(\\d{1,4})\\s*(?:条|行|rows?)?")
                .matcher(text);
        if (matcher.find()) {
            return clampLimit(Integer.parseInt(matcher.group(1)));
        }
        return 100;
    }

    private int clampLimit(int value) {
        return Math.max(1, Math.min(value, 200));
    }

    private int firstMentionPosition(String question, Map<String, Object> field) {
        String source = Objects.toString(question, "");
        String normalizedSource = normalizeForFieldMatch(source);
        int best = Integer.MAX_VALUE;
        for (String label : fieldLabels(field)) {
            if (label.isBlank() || isPhysicalColumnCode(label)) {
                continue;
            }
            int position = source.indexOf(label);
            if (position >= 0) {
                best = Math.min(best, position);
                continue;
            }
            String normalizedLabel = normalizeForFieldMatch(label);
            if (normalizedLabel.length() >= 2 && normalizedSource.contains(normalizedLabel)) {
                best = Math.min(best, normalizedSource.indexOf(normalizedLabel));
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private List<String> fieldLabels(Map<String, Object> field) {
        List<String> labels = new ArrayList<>();
        labels.add(Objects.toString(field.get("displayName"), "").trim());
        labels.add(Objects.toString(field.get("sourceFieldName"), "").trim());
        labels.add(Objects.toString(field.get("fieldComment"), "").trim());
        labels.add(Objects.toString(field.get("columnName"), "").trim());
        return labels.stream().filter(label -> !label.isBlank()).distinct().toList();
    }

    private String bestDisplayLabel(Map<String, Object> field) {
        String displayName = Objects.toString(field.get("displayName"), "");
        String sourceFieldName = Objects.toString(field.get("sourceFieldName"), "");
        String fieldComment = Objects.toString(field.get("fieldComment"), "");
        String columnName = Objects.toString(field.get("columnName"), "");
        return isPhysicalColumnCode(displayName)
                ? firstNonBlank(sourceFieldName, fieldComment, columnName)
                : firstNonBlank(displayName, sourceFieldName, fieldComment, columnName);
    }

    private String normalizeForFieldMatch(String value) {
        return Objects.toString(value, "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "");
    }

    private String escapeSqlLiteral(String value) {
        return Objects.toString(value, "").replace("'", "''");
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
