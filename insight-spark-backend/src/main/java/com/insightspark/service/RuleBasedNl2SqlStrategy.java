package com.insightspark.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class RuleBasedNl2SqlStrategy {

    public FieldChoice chooseFields(String question, List<Map<String, Object>> fields) {
        Map<String, Object> dimension = findBestField(question, fields, "TEXT");
        if (question.contains("趋势") || question.contains("每日") || question.contains("日期") || question.contains("时间")) {
            Map<String, Object> dateField = findBestField(question, fields, "DATE");
            if (dateField != null) {
                dimension = dateField;
            }
        }
        if (dimension == null) {
            dimension = fields.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("当前数据表没有可查询字段"));
        }

        Map<String, Object> metric = findBestField(question, fields, "NUMBER");
        String dimensionType = Objects.toString(dimension.get("fieldType"), "TEXT");
        return new FieldChoice(
                Objects.toString(dimension.get("columnName")),
                Objects.toString(dimension.get("displayName")),
                dimensionType,
                metric == null ? null : Objects.toString(metric.get("columnName")),
                metric == null ? null : Objects.toString(metric.get("displayName"))
        );
    }

    public String chooseChartType(String question, String dimensionType) {
        if (question.contains("占比") || question.contains("比例") || question.contains("分类")) {
            return "pie";
        }
        if ("DATE".equals(dimensionType) || question.contains("趋势") || question.contains("变化")) {
            return "line";
        }
        return "bar";
    }

    public String buildSql(String tableName, FieldChoice fieldChoice, String chartType) {
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
                .filter(field -> preferredType.equals(Objects.toString(field.get("fieldType"))))
                .filter(field -> question.contains(Objects.toString(field.get("displayName")))
                        || question.contains(Objects.toString(field.get("sourceFieldName"))))
                .findFirst()
                .orElseGet(() -> fields.stream()
                        .filter(field -> preferredType.equals(Objects.toString(field.get("fieldType"))))
                        .findFirst()
                        .orElse(null));
    }

    private Map<String, Object> findSemanticField(String question, List<Map<String, Object>> fields, String preferredType) {
        List<String> terms = new java.util.ArrayList<>();
        if (question.contains("省份") || question.contains("省市") || question.contains("地区") || question.contains("省")) {
            terms.addAll(List.of("province", "prov", "state"));
        }
        if (question.contains("城市") || question.contains("市")) {
            terms.add("city");
        }
        if (question.contains("区域") || question.contains("大区")) {
            terms.addAll(List.of("region", "area"));
        }
        if (question.contains("销售额") || question.contains("销售") || question.contains("金额")
                || question.contains("营收") || question.contains("收入")) {
            terms.addAll(List.of("sales", "sale", "amount", "amt", "revenue", "gmv"));
        }
        if (question.contains("利润") || question.contains("盈利") || question.contains("毛利")) {
            terms.addAll(List.of("profit", "margin"));
        }
        if (question.contains("数量") || question.contains("销量") || question.contains("件数")) {
            terms.addAll(List.of("qty", "quantity", "count", "volume"));
        }
        if (question.contains("折扣") || question.contains("折让")) {
            terms.add("discount");
        }
        if (terms.isEmpty()) {
            return null;
        }
        return fields.stream()
                .filter(field -> preferredType.equals(Objects.toString(field.get("fieldType"))))
                .filter(field -> {
                    String haystack = (Objects.toString(field.get("columnName"), "") + " "
                            + Objects.toString(field.get("displayName"), "") + " "
                            + Objects.toString(field.get("sourceFieldName"), "") + " "
                            + Objects.toString(field.get("fieldComment"), "")).toLowerCase();
                    return terms.stream().anyMatch(haystack::contains);
                })
                .findFirst()
                .orElse(null);
    }

    public record FieldChoice(String dimensionColumn, String dimensionDisplayName, String dimensionType,
                              String metricColumn, String metricDisplayName) {
    }
}
