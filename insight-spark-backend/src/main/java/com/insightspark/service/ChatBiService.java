package com.insightspark.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    public Map<String, Object> executeChat(String question, String tableName) {
        log.info("🤖 收到用户自然语言提问: {}", question);

        String activeTable = (tableName == null || tableName.isBlank()) ? dataUploadService.latestTableName() : tableName;
        boolean officialSource = datasourceService.isOfficialSource(activeTable);
        String queryTableName = officialSource ? datasourceService.physicalTableName(activeTable) : activeTable;
        dataUploadService.assertKnownTable(activeTable);

        List<Map<String, Object>> fields = dataUploadService.listFields(activeTable);
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("当前数据表没有字段元信息，请在“数据上传”页面重新上传文件，或选择字段数大于 0 的数据表。");
        }
        List<Map<String, Object>> graphContext = knowledgeGraphService.retrieveContext(question, activeTable);
        Optional<Map<String, Object>> aiResult = pythonAiService.textToSql(question, queryTableName, fields);

        String generatedSql;
        String chartType;
        Map<String, Object> fieldMapping;
        String engine;

        if (aiResult.isPresent()) {
            Map<String, Object> ai = aiResult.get();
            generatedSql = Objects.toString(ai.get("sql"));
            chartType = Objects.toString(ai.getOrDefault("chartType", "bar"));
            fieldMapping = (Map<String, Object>) ai.getOrDefault("fieldMapping", Map.of());
            engine = "python-ai-service";
        } else {
            FieldChoice fieldChoice = chooseFields(question, fields);
            chartType = chooseChartType(question, fieldChoice.dimensionType());
            generatedSql = buildSql(queryTableName, fieldChoice, chartType);
            fieldMapping = Map.of(
                    "dimension", fieldChoice.dimensionDisplayName(),
                    "metric", fieldChoice.metricDisplayName() == null ? "记录数" : fieldChoice.metricDisplayName()
            );
            engine = "java-fallback";
        }

        log.info("🧠 AI 生成 SQL: {}", generatedSql);

        SqlAuditService.AuditResult auditResult = sqlAuditService.inspect(generatedSql, queryTableName);
        if (auditResult.blocked()) {
            sqlAuditService.record(question, activeTable, engine, generatedSql, auditResult,
                    "BLOCKED", 0L, auditResult.riskReason());
            throw new IllegalArgumentException("SQL 安全审计未通过：" + auditResult.riskReason());
        }

        generatedSql = sqlAuditService.ensureLimit(generatedSql, 200);
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> queryResult;
        Integer previousTimeout = jdbcTemplate.getQueryTimeout();
        try {
            jdbcTemplate.setQueryTimeout(5);
            queryResult = officialSource
                    ? datasourceService.executeQuery(activeTable, generatedSql)
                    : jdbcTemplate.queryForList(generatedSql);
            queryResult = sqlAuditService.maskRows(activeTable, queryResult);
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
        response.put("graphContext", graphContext);
        response.put("riskLevel", auditResult.riskLevel());
        response.put("riskReason", auditResult.riskReason());
        response.put("message", "分析完成。已基于字段「" + fieldMapping.getOrDefault("dimension", "未知维度")
                + "」和指标「" + fieldMapping.getOrDefault("metric", "记录数")
                + "」生成" + chartName(chartType) + "。");

        return response;
    }

    private FieldChoice chooseFields(String question, List<Map<String, Object>> fields) {
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

    private Map<String, Object> findBestField(String question, List<Map<String, Object>> fields, String preferredType) {
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

    private String chooseChartType(String question, String dimensionType) {
        if (question.contains("占比") || question.contains("比例") || question.contains("分类")) {
            return "pie";
        }
        if ("DATE".equals(dimensionType) || question.contains("趋势") || question.contains("变化")) {
            return "line";
        }
        return "bar";
    }

    private String buildSql(String tableName, FieldChoice fieldChoice, String chartType) {
        String valueExpr = fieldChoice.metricColumn() == null
                ? "COUNT(1)"
                : "SUM(CAST(NULLIF(`" + fieldChoice.metricColumn() + "`, '') AS DECIMAL(18,2)))";
        String orderExpr = "line".equals(chartType) ? "name ASC" : "value DESC";
        return "SELECT `" + fieldChoice.dimensionColumn() + "` AS name, " + valueExpr + " AS value FROM `"
                + tableName + "` WHERE `" + fieldChoice.dimensionColumn() + "` IS NOT NULL AND `"
                + fieldChoice.dimensionColumn() + "` <> '' GROUP BY `" + fieldChoice.dimensionColumn()
                + "` ORDER BY " + orderExpr + " LIMIT 30";
    }

    private String chartName(String chartType) {
        return chartType.equals("bar") ? "柱状图" : chartType.equals("pie") ? "饼图" : "折线图";
    }

    private record FieldChoice(String dimensionColumn, String dimensionDisplayName, String dimensionType,
                               String metricColumn, String metricDisplayName) {
    }
}
