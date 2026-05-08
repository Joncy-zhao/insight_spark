package com.insightspark.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

@Service
public class ChatBiService {

    private static final Logger log = LoggerFactory.getLogger(ChatBiService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private SqlAuditService sqlAuditService;

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    private RuleBasedNl2SqlStrategy ruleBasedNl2SqlStrategy;

    public Map<String, Object> executeChat(String question, String tableName) {
        log.info("Received chat question: {}", question);
        ensureNotCancelled("请求初始化");

        String activeTable = (tableName == null || tableName.isBlank()) ? dataUploadService.latestTableName()
                : tableName;
        boolean officialSource = datasourceService.isOfficialSource(activeTable);
        String queryTableName = officialSource ? datasourceService.physicalTableName(activeTable) : activeTable;
        dataUploadService.assertKnownTable(activeTable);
        ensureNotCancelled("数据源校验");

        if (!officialSource && question.contains("关联官方")) {
            ensureNotCancelled("联邦数据关联");
            List<Map<String, Object>> joinedRows = datasourceService.executeFederalJoin(activeTable, 200);
            Map<String, Object> response = new HashMap<>();
            response.put("tableName", activeTable);
            response.put("physicalTableName", queryTableName);
            response.put("sourceType", "FEDERAL_JOIN");
            response.put("sql", "FEDERAL_IN_MEMORY_JOIN(" + activeTable + ")");
            response.put("data", joinedRows);
            response.put("chartType", "bar");
            response.put("fieldMapping", Map.of("join", "Excel 与官方库关联"));
            response.put("engine", "java-federal-join");
            response.put("riskLevel", "SAFE");
            response.put("riskReason", "已按联邦关联配置执行只读分步查询并在 Java 内存中合并");
            response.put("message", "已根据联邦关联配置合并上传表与官方库信息。");
            return response;
        }

        List<Map<String, Object>> fields = dataUploadService.listFields(activeTable);
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("当前数据表没有字段元信息，请在“数据上传”页面重新上传文件，或选择字段数大于 0 的数据表。");
        }
        ensureNotCancelled("字段元信息加载");
        Map<String, Object> graphPath = knowledgeGraphService.retrieveMultiHopContext(question, activeTable);
        List<Map<String, Object>> graphContext = asMapList(graphPath.get("ragContext"));
        if (graphContext.isEmpty() && !knowledgeGraphService.hasGraphData()) {
            ensureNotCancelled("图谱补全同步");
            knowledgeGraphService.syncGraph();
            graphPath = knowledgeGraphService.retrieveMultiHopContext(question, activeTable);
            graphContext = asMapList(graphPath.get("ragContext"));
        }
        if (graphContext.isEmpty()) {
            graphContext = buildLocalFieldContext(activeTable, fields);
        }
        ensureNotCancelled("图谱上下文准备");
        List<Map<String, Object>> previewRows = dataUploadService.preview(activeTable, 1, 8);
        ensureNotCancelled("样例数据预览");
        Optional<Map<String, Object>> aiResult = pythonAiService.textToSql(question, queryTableName, fields,
                previewRows);
        ensureNotCancelled("SQL 生成");

        String generatedSql;
        String chartType;
        Map<String, Object> fieldMapping;
        String engine;
        String fallbackReason = null;

        if (aiResult.isPresent()) {
            try {
                Map<String, Object> ai = aiResult.get();
                generatedSql = Objects.toString(ai.get("sql"), "").trim();
                chartType = Objects.toString(ai.getOrDefault("chartType", "bar"), "bar").trim();
                fieldMapping = safeFieldMapping(ai.get("fieldMapping"));
                if (generatedSql.isBlank()) {
                    throw new IllegalArgumentException("AI 返回 SQL 为空");
                }
                if (chartType.isBlank()) {
                    chartType = "bar";
                }
                engine = "python-ai-service";
            } catch (Exception parseEx) {
                log.warn("AI 返回内容解析失败，切换 Java 兜底策略: {}", parseEx.getMessage());
                RuleBasedNl2SqlStrategy.FieldChoice fieldChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
                chartType = ruleBasedNl2SqlStrategy.chooseChartType(question, fieldChoice.dimensionType());
                generatedSql = ruleBasedNl2SqlStrategy.buildSql(queryTableName, fieldChoice, chartType);
                fieldMapping = fallbackFieldMapping(fieldChoice);
                engine = "java-fallback-ai-parse";
                fallbackReason = "AI_RESPONSE_INVALID";
            }
        } else {
            RuleBasedNl2SqlStrategy.FieldChoice fieldChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
            chartType = ruleBasedNl2SqlStrategy.chooseChartType(question, fieldChoice.dimensionType());
            generatedSql = ruleBasedNl2SqlStrategy.buildSql(queryTableName, fieldChoice, chartType);
            fieldMapping = fallbackFieldMapping(fieldChoice);
            engine = "java-fallback";
            fallbackReason = "AI_UNAVAILABLE";
        }

        log.info("Generated SQL: {}", generatedSql);
        ensureNotCancelled("SQL 审计前");

        SqlAuditService.AuditResult auditResult = sqlAuditService.inspect(generatedSql, activeTable);
        if (auditResult.blocked()) {
            sqlAuditService.record(question, activeTable, engine, generatedSql, auditResult,
                    "BLOCKED", 0L, auditResult.riskReason());
            throw new IllegalArgumentException("SQL 安全审计未通过：" + auditResult.riskReason());
        }

        generatedSql = sqlAuditService.ensureLimit(generatedSql, 200);
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> queryResult;
        boolean fallbackExecuted = false;
        Integer previousTimeout = jdbcTemplate.getQueryTimeout();
        try {
            jdbcTemplate.setQueryTimeout(5);
            try {
                ensureNotCancelled("执行查询前");
                queryResult = officialSource
                        ? datasourceService.executeQueryWithoutAudit(activeTable, generatedSql)
                        : queryUploadTable(generatedSql);
                ensureNotCancelled("执行查询后");
            } catch (RuntimeException executionError) {
                if (engine.startsWith("java-fallback")) {
                    throw executionError;
                }
                log.warn("AI SQL 执行失败，尝试 Java 规则兜底重试: {}", executionError.getMessage());
                ensureNotCancelled("兜底重试前");
                RuleBasedNl2SqlStrategy.FieldChoice fieldChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
                String fallbackChartType = ruleBasedNl2SqlStrategy.chooseChartType(question, fieldChoice.dimensionType());
                String fallbackSql = ruleBasedNl2SqlStrategy.buildSql(queryTableName, fieldChoice, fallbackChartType);
                String limitedFallbackSql = sqlAuditService.ensureLimit(fallbackSql, 200);
                SqlAuditService.AuditResult fallbackAudit = sqlAuditService.inspect(limitedFallbackSql, activeTable);
                if (fallbackAudit.blocked()) {
                    throw executionError;
                }
                generatedSql = limitedFallbackSql;
                chartType = fallbackChartType;
                fieldMapping = fallbackFieldMapping(fieldChoice);
                engine = "java-fallback-exec-retry";
                fallbackReason = "AI_SQL_EXEC_FAILED";
                fallbackExecuted = true;
                ensureNotCancelled("兜底重试执行前");
                queryResult = officialSource
                        ? datasourceService.executeQueryWithoutAudit(activeTable, generatedSql)
                        : queryUploadTable(generatedSql);
                ensureNotCancelled("兜底重试执行后");
            }
            ensureNotCancelled("结果加工前");
            queryResult = attachDimensionKey(queryResult, fieldMapping);
            queryResult = sqlAuditService.maskRows(activeTable, queryResult);
            if (isAllNullChartRows(queryResult)) {
                ensureNotCancelled("空结果恢复前");
                Map<String, Object> recovery = rebuildQueryFromTableProfile(activeTable, queryTableName, question,
                        fields, chartType);
                if (recovery != null) {
                    generatedSql = Objects.toString(recovery.getOrDefault("sql", generatedSql));
                    chartType = Objects.toString(recovery.getOrDefault("chartType", chartType));
                    fieldMapping = (Map<String, Object>) recovery.getOrDefault("fieldMapping", fieldMapping);
                    queryResult = (List<Map<String, Object>>) recovery.getOrDefault("data", queryResult);
                }
            }
            queryResult = normalizeChartRows(queryResult, chartType, fieldMapping);
            ensureNotCancelled("结果归一化后");
            long durationMs = System.currentTimeMillis() - startedAt;
            sqlAuditService.record(question, activeTable, engine, generatedSql, auditResult,
                    "SUCCESS", durationMs, null);
        } catch (RuntimeException e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            sqlAuditService.record(question, activeTable, engine, generatedSql, auditResult,
                    "FAILED", durationMs, e.getMessage());
            throw e;
        } finally {
            jdbcTemplate.setQueryTimeout(previousTimeout);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("tableName", activeTable);
        response.put("physicalTableName", queryTableName);
        response.put("sourceType", officialSource ? "OFFICIAL" : "UPLOAD");
        response.put("sql", generatedSql);
        response.put("data", queryResult);
        response.put("chartType", chartType);
        response.put("fieldMapping", fieldMapping);
        response.put("engine", engine);
        response.put("fallbackUsed", engine.startsWith("java-fallback") || fallbackExecuted);
        response.put("fallbackReason", fallbackReason);
        response.put("graphContext", graphContext);
        response.put("riskLevel", auditResult.riskLevel());
        response.put("riskReason", auditResult.riskReason());
        response.put("message", "分析完成。已基于字段「" + fieldMapping.getOrDefault("dimension", "未知维度")
                + "」和指标「" + fieldMapping.getOrDefault("metric", "记录数")
                + "」生成" + chartName(chartType) + "。");

        return response;
    }

    private boolean isAllNullChartRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        return rows.stream().allMatch(row -> isEmptyChartValue(row, "name", "dim_name", "dimension")
                && isEmptyChartValue(row, "value", "metric_value", "metric"));
    }

    private boolean isEmptyChartValue(Map<String, Object> row, String... keys) {
        Object value = null;
        for (String key : keys) {
            if (row.containsKey(key)) {
                value = row.get(key);
                break;
            }
        }
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private List<Map<String, Object>> buildLocalFieldContext(String tableName, List<Map<String, Object>> fields) {
        List<Map<String, Object>> context = new ArrayList<>();
        if (tableName != null && !tableName.isBlank()) {
            Map<String, Object> tableNode = new HashMap<>();
            tableNode.put("nodeKey", "local_table:" + tableName);
            tableNode.put("nodeType", "TABLE");
            tableNode.put("label", tableName);
            tableNode.put("sourceType", "LOCAL");
            tableNode.put("sourceId", tableName);
            tableNode.put("content", "本地字段元信息");
            context.add(tableNode);
        }
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            String displayName = Objects.toString(field.get("displayName"), columnName);
            String fieldType = Objects.toString(field.get("fieldType"), "TEXT");
            String fieldComment = Objects.toString(field.get("fieldComment"), "").trim();
            String sensitive = formatSensitive(field.get("sensitive"));
            String content = "字段类型：" + fieldType + "；敏感：" + sensitive;
            if (!fieldComment.isBlank()) {
                content = content + "；" + fieldComment;
            }
            Map<String, Object> fieldNode = new HashMap<>();
            fieldNode.put("nodeKey", "local_field:" + tableName + ":" + columnName);
            fieldNode.put("nodeType", "FIELD");
            fieldNode.put("label", displayName);
            fieldNode.put("sourceType", "LOCAL");
            fieldNode.put("sourceId", tableName + "." + columnName);
            fieldNode.put("content", content);
            context.add(fieldNode);
        }
        return context;
    }

    private String formatSensitive(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "true" : "false";
        }
        if (value instanceof Number number) {
            return number.intValue() != 0 ? "true" : "false";
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return "false";
        }
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text)
                        ? "true"
                        : "false";
    }
    private Map<String, Object> rebuildQueryFromTableProfile(String activeTable, String queryTableName, String question,
            List<Map<String, Object>> fields, String chartType) {
        List<Map<String, Object>> previewRows = queryTablePreview(queryTableName, 20);
        if (previewRows.isEmpty()) {
            return null;
        }
        Map<String, Object> best = inferBestColumnsFromPreview(question, fields, previewRows);
        String dimensionKey = Objects.toString(best.getOrDefault("dimensionKey", ""));
        String metricKey = Objects.toString(best.getOrDefault("metricKey", ""));
        if (dimensionKey.isBlank() || metricKey.isBlank()) {
            return null;
        }
        String sql = "SELECT `" + dimensionKey + "` AS dim_name, SUM(CAST(NULLIF(`" + metricKey
                + "`, '') AS DECIMAL(18,2))) AS metric_value "
                + "FROM `" + queryTableName + "` WHERE `" + dimensionKey + "` IS NOT NULL AND `" + dimensionKey
                + "` <> '' "
                + "GROUP BY `" + dimensionKey + "` ORDER BY metric_value DESC LIMIT 30";
        List<Map<String, Object>> data = jdbcTemplate.queryForList(sql);
        data = attachDimensionKey(data, Map.of("dimensionKey", dimensionKey));
        data = sqlAuditService.maskRows(activeTable, data);
        return Map.of(
                "sql", sql,
                "chartType", chartType,
                "fieldMapping", Map.of(
                        "dimension", best.getOrDefault("dimension", dimensionKey),
                        "metric", best.getOrDefault("metric", metricKey),
                        "dimensionKey", dimensionKey,
                        "metricKey", metricKey),
                "data", data);
    }

    private List<Map<String, Object>> queryTablePreview(String tableName, int limit) {
        return jdbcTemplate.queryForList("SELECT * FROM `" + tableName + "` LIMIT " + Math.max(1, Math.min(limit, 20)));
    }

    private Map<String, Object> inferBestColumnsFromPreview(String question, List<Map<String, Object>> fields,
            List<Map<String, Object>> previewRows) {
        Map<String, Object> result = new HashMap<>();
        if (fields == null || fields.isEmpty() || previewRows.isEmpty()) {
            return result;
        }
        List<String> orderedColumns = new java.util.ArrayList<>(previewRows.get(0).keySet());
        String dimensionKey = null;
        String metricKey = null;
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            if (dimensionKey == null && isQuestionLikelyDimension(question, field)) {
                dimensionKey = columnName;
            }
            if (metricKey == null && isQuestionLikelyMetric(question, field)) {
                metricKey = columnName;
            }
        }
        if (dimensionKey == null) {
            dimensionKey = orderedColumns.stream().filter(col -> isMostlyText(previewRows, col)).findFirst()
                    .orElse(orderedColumns.get(0));
        }
        String finalDimensionKey = dimensionKey;
        if (metricKey == null) {
            metricKey = orderedColumns.stream()
                    .filter(col -> isMostlyNumeric(previewRows, col) && !col.equals(finalDimensionKey))
                    .findFirst()
                    .orElseGet(() -> orderedColumns.stream()
                            .filter(col -> !col.equals(finalDimensionKey))
                            .findFirst()
                            .orElse(finalDimensionKey));
        }
        result.put("dimensionKey", dimensionKey);
        result.put("metricKey", metricKey);
        result.put("dimension", dimensionKey);
        result.put("metric", metricKey);
        return result;
    }

    private boolean isQuestionLikelyDimension(String question, Map<String, Object> field) {
        String display = Objects.toString(field.get("displayName"), "");
        String comment = Objects.toString(field.get("fieldComment"), "");
        String column = Objects.toString(field.get("columnName"), "");
        String type = Objects.toString(field.get("fieldType"), "TEXT");
        if ("NUMBER".equals(type)) {
            return false;
        }
        return question.contains(display) || question.contains(column) || question.contains(comment)
                || display.contains("省") || display.contains("地区") || display.contains("分类") || display.contains("品类")
                || display.contains("城市")
                || column.matches(".*(province|region|city|category|type|name|kind|class).*?");
    }

    private boolean isQuestionLikelyMetric(String question, Map<String, Object> field) {
        String display = Objects.toString(field.get("displayName"), "");
        String comment = Objects.toString(field.get("fieldComment"), "");
        String column = Objects.toString(field.get("columnName"), "");
        String type = Objects.toString(field.get("fieldType"), "TEXT");
        if (!"NUMBER".equals(type)) {
            return false;
        }
        return question.contains(display) || question.contains(column) || question.contains(comment)
                || display.contains("销售") || display.contains("金额") || display.contains("数量") || display.contains("总额")
                || column.matches(".*(sales|amount|amt|total|count|qty|price|profit|revenue).*?");
    }

    private boolean isMostlyText(List<Map<String, Object>> rows, String column) {
        int sample = Math.min(rows.size(), 10);
        if (sample == 0)
            return false;
        int textCount = 0;
        for (int i = 0; i < sample; i++) {
            Object value = rows.get(i).get(column);
            if (value != null && !isNumericValue(value)) {
                textCount++;
            }
        }
        return textCount >= Math.max(1, sample / 2);
    }

    private boolean isMostlyNumeric(List<Map<String, Object>> rows, String column) {
        int sample = Math.min(rows.size(), 10);
        if (sample == 0)
            return false;
        int numericCount = 0;
        for (int i = 0; i < sample; i++) {
            if (isNumericValue(rows.get(i).get(column))) {
                numericCount++;
            }
        }
        return numericCount >= Math.max(1, sample / 2);
    }

    private boolean isNumericValue(Object value) {
        if (value == null)
            return false;
        if (value instanceof Number)
            return true;
        try {
            Double.parseDouble(Objects.toString(value).replace(",", "").trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> normalizeChartRows(List<Map<String, Object>> rows, String chartType,
            Map<String, Object> fieldMapping) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        if (rows.stream().allMatch(row -> row.containsKey("name") && row.containsKey("value"))) {
            return rows;
        }
        String dimensionKey = Objects.toString(fieldMapping.getOrDefault("dimensionKey", ""));
        String metricKey = Objects.toString(fieldMapping.getOrDefault("metricKey", ""));
        if (dimensionKey.isBlank() || metricKey.isBlank()) {
            Map<String, Object> firstRow = rows.get(0);
            if (dimensionKey.isBlank()) {
                dimensionKey = firstRow.keySet().stream().findFirst().orElse("name");
            }
            if (metricKey.isBlank()) {
                metricKey = firstRow.keySet().stream().skip(1).findFirst().orElse("value");
            }
        }
        String finalDimensionKey = dimensionKey;
        String finalMetricKey = metricKey;
        return rows.stream().map(row -> {
            Map<String, Object> normalized = new HashMap<>();
            Object dimensionValue = row.containsKey(finalDimensionKey) ? row.get(finalDimensionKey)
                    : row.getOrDefault("dim_name",
                            row.getOrDefault("name", row.getOrDefault("dimension", row.get(finalDimensionKey))));
            Object metricValue = row.containsKey(finalMetricKey) ? row.get(finalMetricKey)
                    : row.getOrDefault("metric_value",
                            row.getOrDefault("value", row.getOrDefault("metric", row.get(finalMetricKey))));
            normalized.put("name", dimensionValue);
            normalized.put("value", metricValue);
            return normalized;
        }).toList();
    }

    private String chartName(String chartType) {
        return chartType.equals("bar") ? "柱状图" : chartType.equals("pie") ? "饼图" : "折线图";
    }

    private List<Map<String, Object>> queryUploadTable(String sql) {
        ensureNotCancelled("上传表查询前");
        jdbcTemplate.setQueryTimeout(5);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        ensureNotCancelled("上传表查询后");
        return rows;
    }
    private Map<String, Object> safeFieldMapping(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> safe = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            if (!key.isBlank()) {
                safe.put(key, entry.getValue());
            }
        }
        return safe;
    }

    private Map<String, Object> fallbackFieldMapping(RuleBasedNl2SqlStrategy.FieldChoice fieldChoice) {
        return Map.of(
                "dimension", fieldChoice.dimensionDisplayName(),
                "metric", fieldChoice.metricDisplayName() == null ? "记录数" : fieldChoice.metricDisplayName(),
                "dimensionKey", fieldChoice.dimensionColumn(),
                "metricKey", fieldChoice.metricColumn() == null ? "value" : fieldChoice.metricColumn());
    }

    private List<Map<String, Object>> attachDimensionKey(List<Map<String, Object>> rows, Map<String, Object> fieldMapping) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        String dimensionKey = Objects.toString(fieldMapping.getOrDefault("dimensionKey", ""), "").trim();
        if (dimensionKey.isBlank()) {
            return rows;
        }
        List<Map<String, Object>> annotated = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new HashMap<>(row);
            if (!copy.containsKey(dimensionKey)) {
                Object aliasValue = firstNonBlankValue(copy, "dim_name", "name", "dimension");
                if (aliasValue != null) {
                    copy.put(dimensionKey, aliasValue);
                }
            }
            annotated.add(copy);
        }
        return annotated;
    }

    private Object firstNonBlankValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                Object value = row.get(key);
                if (value == null) {
                    continue;
                }
                if (value instanceof String text && text.trim().isEmpty()) {
                    continue;
                }
                return value;
            }
        }
        return null;
    }

    private void ensureNotCancelled(String stage) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户已停止生成（" + stage + "）");
        }
    }
}
