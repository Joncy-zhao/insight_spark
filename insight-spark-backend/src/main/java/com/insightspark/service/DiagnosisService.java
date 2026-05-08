package com.insightspark.service;



import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.Document;



import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

import java.net.URI;

import java.net.http.HttpClient;

import java.net.http.HttpRequest;

import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;

import java.time.Instant;

import java.time.ZoneId;

import java.time.format.DateTimeFormatter;

import java.util.ArrayList;

import java.util.Base64;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Objects;

import java.util.concurrent.ThreadLocalRandom;

import java.util.regex.Pattern;

import java.util.zip.ZipEntry;

import java.util.zip.ZipOutputStream;



@Service

public class DiagnosisService {



    private static final Pattern SAFE_COLUMN_NAME = Pattern.compile("^col_\\d{3}$|^sys_id$");

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

            .withZone(ZoneId.systemDefault());



    @Autowired

    private JdbcTemplate jdbcTemplate;



    @Autowired

    private DataUploadService dataUploadService;



    @Autowired

    private PythonAiService pythonAiService;



    @Autowired

    private KnowledgeGraphService knowledgeGraphService;



    @Autowired

    private KnowledgeDocumentService knowledgeDocumentService;



    @Value("${insight.neo4j.enabled:true}")

    private boolean neo4jEnabled;



    @Value("${insight.neo4j.http-url:http://localhost:7474/db/neo4j/tx/commit}")

    private String neo4jHttpUrl;



    @Value("${insight.neo4j.username:neo4j}")

    private String neo4jUsername;



    @Value("${insight.neo4j.password:neo4j}")

    private String neo4jPassword;



    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();



    public Map<String, Object> runDiagnosis(Map<String, Object> request) {

        String tableName = requiredString(request, "tableName");

        String metricField = requiredString(request, "metricField");

        String timeField = optionalString(request, "timeField");
        String detailLevel = Objects.toString(request.getOrDefault("detailLevel", "detailed"), "detailed");
        String anomalyType = Objects.toString(request.getOrDefault("anomalyType", "fluctuation"), "fluctuation");

        List<String> dimensionFields = parseStringList(request.get("dimensionFields"));



        dataUploadService.assertKnownTable(tableName);

        assertFieldExists(tableName, metricField);

        for (String dimensionField : dimensionFields) {

            assertFieldExists(tableName, dimensionField);

        }

        if (timeField != null && !timeField.isBlank()) {

            assertFieldExists(tableName, timeField);

        }



        List<String> selectFields = new ArrayList<>();

        selectFields.add(metricField);

        for (String dimensionField : dimensionFields) {

            if (!selectFields.contains(dimensionField)) {

                selectFields.add(dimensionField);

            }

        }

        if (timeField != null && !timeField.isBlank() && !selectFields.contains(timeField)) {

            selectFields.add(timeField);

        }



        String selectSql = "SELECT " + selectFields.stream()

                .map(this::quoteColumn)

                .reduce((a, b) -> a + ", " + b)

                .orElse(quoteColumn(metricField))

                + " FROM `" + tableName + "` LIMIT 1000";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql);



        String sourceQuestion = optionalString(request, "sourceQuestion");

        String question = sourceQuestion != null ? sourceQuestion : Objects.toString(request.getOrDefault("question",

                tableName + " " + metricField + " " + String.join(" ", dimensionFields)));

        try {
            knowledgeGraphService.syncGraph();
        } catch (Exception ignored) {
            // 诊断阶段优先使用 Neo4j 现有图谱，自动同步失败会在推理证据中体现为图谱上下文不足。
        }
        Map<String, Object> graphPath = knowledgeGraphService.retrieveMultiHopContext(question, tableName);

        List<Map<String, Object>> graphNodes = castMapList(graphPath.getOrDefault("nodes", List.of()));

        List<Map<String, Object>> graphEdges = castMapList(graphPath.getOrDefault("edges", List.of()));

        List<Map<String, Object>> docEvidence = knowledgeDocumentService.search(question, 10);



        Map<String, Object> aiResult = pythonAiService.graphRagDiagnose(question, tableName, metricField,

                        dimensionFields, timeField, graphPath, docEvidence, rows, detailLevel, anomalyType)

                .orElseGet(() -> pythonAiService.diagnose(tableName, metricField, dimensionFields, timeField, rows));

        aiResult.put("relatedKnowledge", graphNodes);

        aiResult.put("graphEdges", graphEdges);

        aiResult.put("graphPath", graphPath);

        aiResult.put("docEvidence", docEvidence);

        aiResult.put("queryRows", rows.stream().limit(20).toList());

        aiResult.put("sourceQuestion", sourceQuestion == null ? question : sourceQuestion);

        aiResult.put("sourceSql", optionalString(request, "sourceSql"));

        aiResult.put("chartSnapshot", normalizeChartSnapshot(request.get("chartSnapshot"), aiResult, rows, metricField));

        aiResult.put("graphReasoningPath", Objects.toString(graphPath.getOrDefault("pathText", buildGraphReasoningPath(graphNodes))));

        aiResult.put("evidenceSources", buildEvidenceSources(docEvidence, graphNodes));
        aiResult.put("reasoningLogs", aiResult.getOrDefault("reasoningLogs", buildReasoningLogs(rows, graphNodes, graphEdges, docEvidence, aiResult, anomalyType)));
        aiResult.put("detailLevel", detailLevel);
        aiResult.put("anomalyType", anomalyType);



        Long reportId = saveReport(tableName, metricField, dimensionFields, timeField, aiResult, request);



        Map<String, Object> result = new LinkedHashMap<>(aiResult);

        result.put("id", reportId);

        result.put("tableName", tableName);

        result.put("metricField", metricField);

        result.put("dimensionFields", dimensionFields);

        result.put("timeField", timeField);
        result.put("detailLevel", detailLevel);
        result.put("anomalyType", anomalyType);

        return result;

    }



    public List<Map<String, Object>> listReports() {

        String cypher = """

                MATCH (r:DiagnosisReport)

                WHERE r.userId = $userId

                RETURN {

                  id: r.reportId,

                  userId: r.userId,

                  tableName: r.tableName,

                  metricField: r.metricField,

                  dimensionFields: r.dimensionFields,

                  timeField: r.timeField,

                  sourceQuestion: r.sourceQuestion,

                  sourceSql: r.sourceSql,

                  chartSnapshot: r.chartSnapshot,
                  bindingJson: r.bindingJson,

                  title: r.title,

                  summary: r.summary,

                  detailLevel: r.detailLevel,

                  anomalyType: r.anomalyType,

                  createdAt: r.createdAt

                } AS row

                ORDER BY r.createdAtEpoch DESC

                LIMIT 100

                """;

        return neo4jQueryRows(cypher, Map.of("userId", com.insightspark.core.auth.AuthContext.userId()));

    }



    public Map<String, Object> getReport(Long reportId) {

        String cypher = """

                MATCH (r:DiagnosisReport {reportId: $reportId})

                WHERE r.userId = $userId

                RETURN {

                  id: r.reportId,

                  userId: r.userId,

                  tableName: r.tableName,

                  metricField: r.metricField,

                  dimensionFields: r.dimensionFields,

                  timeField: r.timeField,

                  sourceQuestion: r.sourceQuestion,

                  sourceSql: r.sourceSql,

                  chartSnapshot: r.chartSnapshot,
                  bindingJson: r.bindingJson,

                  title: r.title,

                  summary: r.summary,

                  reportMarkdown: r.reportMarkdown,

                  resultJson: r.resultJson,

                  detailLevel: r.detailLevel,

                  anomalyType: r.anomalyType,

                  createdAt: r.createdAt

                } AS row

                LIMIT 1

                """;

        List<Map<String, Object>> rows = neo4jQueryRows(cypher, Map.of(

                "reportId", reportId,

                "userId", com.insightspark.core.auth.AuthContext.userId()

        ));

        if (rows.isEmpty()) {

            throw new IllegalArgumentException("诊断报告不存在：" + reportId);

        }

        Map<String, Object> report = rows.get(0);
        if (!hasSnapshotImage(toStringKeyMap(report.get("chartSnapshot")))) {
            Map<String, Object> result = toStringKeyMap(report.get("resultJson"));
            if (!result.isEmpty()) {
                Map<String, Object> generatedSnapshot = normalizeChartSnapshot(null, result, List.of(), Objects.toString(report.get("metricField"), ""));
                report.put("chartSnapshot", generatedSnapshot);
            }
        }
        return report;

    }



    public ExportFile exportReport(Long reportId, String format) {
        return exportReport(reportId, format, Map.of(
                "includeSnapshots", true,
                "includeReasoningLogs", true,
                "enablePdfEncryption", false
        ));
    }

    public ExportFile exportReport(Long reportId, String format, Map<String, Object> exportOptions) {
        Map<String, Object> report = getReport(reportId);
        String normalized = format == null ? "markdown" : format.trim().toLowerCase();
        String title = Objects.toString(report.getOrDefault("title", "智能诊断报告"));
        String markdown = Objects.toString(report.getOrDefault("reportMarkdown", ""));
        if (markdown.isBlank()) {
            markdown = "# " + title + "\n\n" + Objects.toString(report.getOrDefault("summary", ""));
        }

        boolean includeSnapshots = Boolean.parseBoolean(Objects.toString(exportOptions.getOrDefault("includeSnapshots", true)));
        boolean includeReasoningLogs = Boolean.parseBoolean(Objects.toString(exportOptions.getOrDefault("includeReasoningLogs", true)));
        boolean enablePdfEncryption = Boolean.parseBoolean(Objects.toString(exportOptions.getOrDefault("enablePdfEncryption", false)));
        String content = buildExportContent(title, report, markdown, includeSnapshots, includeReasoningLogs, enablePdfEncryption);

        if ("word".equals(normalized) || "doc".equals(normalized) || "docx".equals(normalized)) {
            return new ExportFile(
                    safeFilename(title) + ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    buildDocx(title, content, extractSnapshotImage(report))
            );
        }

        if ("pdf".equals(normalized)) {
            return new ExportFile(safeFilename(title) + ".pdf", "application/pdf", buildPdf(content, enablePdfEncryption, extractSnapshotImage(report)));
        }

        return new ExportFile(safeFilename(title) + ".md", "text/markdown; charset=UTF-8", content.getBytes(StandardCharsets.UTF_8));
    }

    private String buildExportContent(String title, Map<String, Object> report, String markdown,
                                      boolean includeSnapshots, boolean includeReasoningLogs,
                                      boolean enablePdfEncryption) {
        StringBuilder content = new StringBuilder();
        content.append("# ").append(title).append("\n\n")
                .append("- 数据表：").append(Objects.toString(report.get("tableName"), "")).append("\n")
                .append("- 指标字段：").append(Objects.toString(report.get("metricField"), "")).append("\n")
                .append("- 生成时间：").append(Objects.toString(report.get("createdAt"), "")).append("\n")
                .append("- 导出加密：").append(enablePdfEncryption ? "已请求 PDF 加密" : "未启用").append("\n\n");
        if (includeReasoningLogs) {
            content.append("## GraphRAG 根因链路\n\n")
                    .append(extractGraphPath(report))
                    .append("\n\n");
        }
        if (includeSnapshots) {
            content.append("## 图表快照\n\n")
                    .append(buildSnapshotExportSummary(report))
                    .append("\n\n");
        }
        content.append(markdown);
        return content.toString();
    }

    private String buildSnapshotExportSummary(Map<String, Object> report) {
        Map<String, Object> snapshot = toStringKeyMap(report.get("chartSnapshot"));
        if (snapshot.isEmpty()) {
            return "未绑定图表快照。";
        }
        String title = Objects.toString(snapshot.getOrDefault("title", "诊断图表快照"));
        String chartType = Objects.toString(snapshot.getOrDefault("chartType", "chart"));
        List<Map<String, Object>> data = castMapList(snapshot.getOrDefault("data", List.of()));
        String source = Objects.toString(snapshot.getOrDefault("source", "frontend-captured"));
        return "- 快照标题：" + title + "\n"
                + "- 图表类型：" + chartType + "\n"
                + "- 数据点数量：" + data.size() + "\n"
                + "- 快照来源：" + ("server-generated".equals(source) ? "后端自动生成" : "前端图表截图") + "\n"
                + "- 图片内容：已作为图表快照插入导出的 PDF/Word，正文不再展开 base64 图片数据。";
    }

    private Long saveReport(String tableName, String metricField, List<String> dimensionFields,

                            String timeField, Map<String, Object> aiResult, Map<String, Object> request) {

        if (!neo4jEnabled) {

            throw new IllegalStateException("Neo4j 未启用，无法保存诊断报告。请开启 insight.neo4j.enabled");

        }



        long reportId = nextReportId();

        long createdAtEpoch = Instant.now().toEpochMilli();

        String createdAt = DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(createdAtEpoch));



        Map<String, Object> params = new LinkedHashMap<>();

        params.put("reportId", reportId);

        params.put("userId", com.insightspark.core.auth.AuthContext.userId());

        params.put("tableName", tableName);

        params.put("metricField", metricField);

        params.put("dimensionFields", dimensionFields);

        params.put("timeField", timeField);

        params.put("sourceQuestion", safeText(Objects.toString(aiResult.getOrDefault("sourceQuestion", ""), ""), 1000));

        params.put("sourceSql", Objects.toString(aiResult.getOrDefault("sourceSql", ""), ""));

        params.put("chartSnapshot", toJsonString(aiResult.get("chartSnapshot")));
        params.put("bindingJson", buildBindingJson(tableName, aiResult, request));

        params.put("title", Objects.toString(aiResult.getOrDefault("title", "智能诊断报告")));

        params.put("summary", Objects.toString(aiResult.getOrDefault("summary", "")));

        params.put("reportMarkdown", Objects.toString(aiResult.getOrDefault("reportMarkdown", "")));

        params.put("resultJson", toJsonString(aiResult));

        params.put("createdAt", createdAt);

        params.put("createdAtEpoch", createdAtEpoch);

        params.put("detailLevel", Objects.toString(request.getOrDefault("detailLevel", "detailed"), "detailed"));

        params.put("anomalyType", Objects.toString(request.getOrDefault("anomalyType", "fluctuation"), "fluctuation"));



        String cypher = """

                MERGE (r:DiagnosisReport {reportId: $reportId})

                SET r.userId = $userId,

                    r.tableName = $tableName,

                    r.metricField = $metricField,

                    r.dimensionFields = $dimensionFields,

                    r.timeField = $timeField,

                    r.sourceQuestion = $sourceQuestion,

                    r.sourceSql = $sourceSql,

                    r.chartSnapshot = $chartSnapshot,
                    r.bindingJson = $bindingJson,

                    r.title = $title,

                    r.summary = $summary,

                    r.reportMarkdown = $reportMarkdown,

                    r.resultJson = $resultJson,

                    r.detailLevel = $detailLevel,

                    r.anomalyType = $anomalyType,

                    r.createdAt = $createdAt,

                    r.createdAtEpoch = $createdAtEpoch

                WITH r

                MERGE (t:InsightNode {nodeKey: 'upload_table:' + $tableName})

                MERGE (r)-[a:ANALYZES]->(t)

                SET a.weight = 1.5

                RETURN r.reportId AS id

                """;

        neo4jQueryRows(cypher, params);

        return reportId;

    }



    private long nextReportId() {

        long base = Instant.now().toEpochMilli() * 1000;

        return base + ThreadLocalRandom.current().nextInt(1000);

    }



    private List<Map<String, Object>> castMapList(Object value) {

        if (value instanceof List<?> list) {

            List<Map<String, Object>> result = new ArrayList<>();

            for (Object item : list) {

                if (item instanceof Map<?, ?> map) {

                    Map<String, Object> row = new LinkedHashMap<>();

                    for (Map.Entry<?, ?> entry : map.entrySet()) {

                        row.put(Objects.toString(entry.getKey()), entry.getValue());

                    }

                    result.add(row);

                }

            }

            return result;

        }

        return List.of();

    }



    private List<Map<String, Object>> neo4jQueryRows(String cypher, Map<String, Object> params) {

        try {

            String payload = objectMapper.writeValueAsString(Map.of(

                    "statements", List.of(Map.of("statement", cypher, "parameters", params))

            ));

            String token = Base64.getEncoder().encodeToString((neo4jUsername + ":" + neo4jPassword).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder(URI.create(neo4jHttpUrl))

                    .header("Content-Type", "application/json")

                    .header("Authorization", "Basic " + token)

                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))

                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {

                throw new IllegalStateException("HTTP " + response.statusCode() + " - " + response.body());

            }



            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);

            List<Map<String, Object>> errors = castMapList(body.get("errors"));

            if (!errors.isEmpty()) {

                throw new IllegalStateException(formatNeo4jErrors(errors));

            }

            List<Map<String, Object>> results = castMapList(body.get("results"));

            if (results.isEmpty()) {

                return List.of();

            }

            List<Map<String, Object>> data = castMapList(results.get(0).get("data"));

            List<Map<String, Object>> rows = new ArrayList<>();

            for (Map<String, Object> item : data) {

                Object rowObj = item.get("row");

                if (rowObj instanceof List<?> rowList && !rowList.isEmpty()) {

                    Object first = rowList.get(0);

                    if (first instanceof Map<?, ?> map) {

                        Map<String, Object> row = new LinkedHashMap<>();

                        for (Map.Entry<?, ?> entry : map.entrySet()) {

                            row.put(Objects.toString(entry.getKey()), entry.getValue());

                        }

                        rows.add(row);

                    } else {

                        rows.add(Map.of("value", first));

                    }

                }

            }

            return rows;

        } catch (Exception e) {

            throw new IllegalStateException("Neo4j 查询失败：" + safeErrorMessage(e), e);

        }

    }

    private String formatNeo4jErrors(List<Map<String, Object>> errors) {
        return errors.stream()
                .map(error -> {
                    String code = Objects.toString(error.get("code"), "").trim();
                    String message = Objects.toString(error.get("message"), "").trim();
                    if (code.isBlank()) {
                        return message.isBlank() ? Objects.toString(error) : message;
                    }
                    return message.isBlank() ? code : code + " - " + message;
                })
                .filter(item -> !item.isBlank())
                .findFirst()
                .orElse("未知 Neo4j 错误");
    }

    private String safeErrorMessage(Exception e) {
        String message = e == null ? "" : Objects.toString(e.getMessage(), "").trim();
        return message.isBlank() && e != null ? e.getClass().getSimpleName() : message;
    }



    private String buildGraphReasoningPath(List<Map<String, Object>> relatedKnowledge) {

        if (relatedKnowledge == null || relatedKnowledge.isEmpty()) {

            return "暂无图谱上下文，建议先在“知识图谱与GraphRAG”中同步图谱。";

        }

        return relatedKnowledge.stream()

                .limit(6)

                .map(item -> Objects.toString(item.get("nodeType"), "节点") + "「"

                        + Objects.toString(item.get("label"), "") + "」")

                .reduce((a, b) -> a + " -> " + b)

                .orElse("");

    }



    private List<String> buildEvidenceSources(List<Map<String, Object>> docChunks, List<Map<String, Object>> graphContext) {

        List<String> sources = new ArrayList<>();

        for (Map<String, Object> chunk : docChunks) {

            sources.add(Objects.toString(chunk.get("source"), "知识文档") + "：" + previewText(chunk.get("chunkText")));

        }

        if (!graphContext.isEmpty()) {

            sources.add("知识图谱路径：" + buildGraphReasoningPath(graphContext));

        }

        return sources;

    }

    private List<Map<String, Object>> buildReasoningLogs(List<Map<String, Object>> rows,
                                                          List<Map<String, Object>> graphNodes,
                                                          List<Map<String, Object>> graphEdges,
                                                          List<Map<String, Object>> docEvidence,
                                                          Map<String, Object> aiResult,
                                                          String anomalyType) {
        return List.of(
                Map.of("step", 1, "title", "扫描原始异常数据", "status", "completed",
                        "detail", "读取原始数据 " + rows.size() + " 条，计算指标波动、极值、均值和标准差。"),
                Map.of("step", 2, "title", "命中 Neo4j 表/字段节点", "status", "completed",
                        "detail", "围绕异常类型 " + anomalyType + " 命中 " + graphNodes.size() + " 个图谱节点。"),
                Map.of("step", 3, "title", "扩展企业内部文档与行业研报", "status", "completed",
                        "detail", "检索企业文档/研报证据 " + docEvidence.size() + " 条。"),
                Map.of("step", 4, "title", "关联历史报告与图谱关系", "status", "completed",
                        "detail", "扫描 Neo4j 多跳关系 " + graphEdges.size() + " 条，关联历史诊断和字段上下文。"),
                Map.of("step", 5, "title", "输出根因定位结论", "status", "completed",
                        "detail", "生成根因假设 " + castMapList(aiResult.getOrDefault("rootCauses", List.of())).size() + " 条，并输出改进建议。")
        );
    }

    private String buildBindingJson(String tableName, Map<String, Object> aiResult, Map<String, Object> request) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("route", "chat");
        binding.put("tableName", tableName);
        binding.put("sourceQuestion", aiResult.getOrDefault("sourceQuestion", ""));
        binding.put("sourceSql", aiResult.getOrDefault("sourceSql", ""));
        binding.put("chartSnapshot", aiResult.get("chartSnapshot"));
        binding.put("chartType", request.get("chartType"));
        return toJsonString(binding);
    }



    private String previewText(Object value) {

        String text = Objects.toString(value, "").replaceAll("\\s+", " ").trim();

        return text.length() <= 80 ? text : text.substring(0, 80) + "...";

    }



    private String extractGraphPath(Map<String, Object> report) {

        Object resultJsonObj = report.get("resultJson");

        if (resultJsonObj instanceof Map<?, ?> map) {

            return Objects.toString(map.get("graphReasoningPath"), "");

        }

        String resultJson = Objects.toString(report.getOrDefault("resultJson", ""));

        if (resultJson.isBlank()) {

            return "";

        }

        try {

            Map<String, Object> parsed = objectMapper.readValue(resultJson, Map.class);

            return Objects.toString(parsed.getOrDefault("graphReasoningPath", ""));

        } catch (Exception e) {

            return "";

        }

    }



    private void assertFieldExists(String tableName, String columnName) {

        if (!SAFE_COLUMN_NAME.matcher(columnName).matches()) {

            throw new IllegalArgumentException("非法字段名：" + columnName);

        }

        Integer count = jdbcTemplate.queryForObject("""

                SELECT COUNT(*)

                FROM is_data_field

                WHERE table_name = ? AND column_name = ?

                """, Integer.class, tableName, columnName);

        if (count == null || count == 0) {

            throw new IllegalArgumentException("字段不存在或无权访问：" + columnName);

        }

    }



    private String quoteColumn(String columnName) {

        if (!SAFE_COLUMN_NAME.matcher(columnName).matches()) {

            throw new IllegalArgumentException("非法字段名：" + columnName);

        }

        return "`" + columnName + "`";

    }



    private String requiredString(Map<String, Object> request, String key) {

        Object value = request.get(key);

        if (value == null || String.valueOf(value).isBlank()) {

            throw new IllegalArgumentException("缺少必填项：" + key);

        }

        return String.valueOf(value);

    }



    private String optionalString(Map<String, Object> request, String key) {

        Object value = request.get(key);

        if (value == null || String.valueOf(value).isBlank()) {

            return null;

        }

        return String.valueOf(value);

    }



    private List<String> parseStringList(Object value) {

        if (value == null) {

            return List.of();

        }

        if (value instanceof List<?> list) {

            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();

        }

        String raw = String.valueOf(value);

        if (raw.isBlank()) {

            return List.of();

        }

        return List.of(raw.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).toList();

    }



    private String safeFilename(String title) {

        String value = title == null || title.isBlank() ? "diagnosis-report" : title;

        return value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");

    }



    private String safeText(String text, int maxLength) {

        if (text == null) {

            return null;

        }

        return text.length() <= maxLength ? text : text.substring(0, maxLength);

    }

    private String toJsonString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return Objects.toString(value, "");
        }
    }

    private Map<String, Object> normalizeChartSnapshot(Object requestedSnapshot, Map<String, Object> aiResult,
                                                       List<Map<String, Object>> rows, String metricField) {
        Map<String, Object> snapshot = toStringKeyMap(requestedSnapshot);
        if (hasSnapshotImage(snapshot)) {
            return snapshot;
        }

        List<Map<String, Object>> chartData = new ArrayList<>();
        String chartType = "bar";
        String title = "异常数据图表快照";

        List<Map<String, Object>> blocks = castMapList(aiResult.getOrDefault("factorChartBlocks", List.of()));
        if (!blocks.isEmpty()) {
            Map<String, Object> block = blocks.get(0);
            chartType = Objects.toString(block.getOrDefault("chartType", "bar"));
            title = Objects.toString(block.getOrDefault("title", title));
            chartData.addAll(normalizeSnapshotData(castMapList(block.getOrDefault("data", List.of())), metricField));
        }

        if (chartData.isEmpty()) {
            int index = 1;
            for (Map<String, Object> row : rows) {
                if (chartData.size() >= 12) {
                    break;
                }
                double value = toDouble(row.get(metricField));
                if (Math.abs(value) <= 0.000001) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", Objects.toString(row.getOrDefault("name", row.getOrDefault("time", "记录" + index))));
                item.put("value", value);
                chartData.add(item);
                index++;
            }
        }

        if (chartData.isEmpty()) {
            Map<String, Object> emptyItem = new LinkedHashMap<>();
            emptyItem.put("name", "暂无数据");
            emptyItem.put("value", 0);
            chartData.add(emptyItem);
        }

        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("chartType", chartType);
        generated.put("title", title);
        generated.put("fieldMapping", Map.of("metric", metricField));
        generated.put("data", chartData);
        generated.put("imageDataUrl", renderSnapshotImage(title, chartType, chartData));
        generated.put("source", "server-generated");
        generated.put("generatedAt", DATE_TIME_FORMATTER.format(Instant.now()));
        return generated;
    }

    private Map<String, Object> toStringKeyMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(Objects.toString(entry.getKey()), entry.getValue());
            }
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (Exception ignored) {
                return new LinkedHashMap<>();
            }
        }
        return new LinkedHashMap<>();
    }

    private boolean hasSnapshotImage(Map<String, Object> snapshot) {
        String dataUrl = Objects.toString(snapshot.getOrDefault("imageDataUrl", ""));
        return dataUrl.startsWith("data:image") && dataUrl.contains(",");
    }

    private List<Map<String, Object>> normalizeSnapshotData(List<Map<String, Object>> rawData, String metricField) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rawData) {
            String name = Objects.toString(row.getOrDefault("name", row.getOrDefault("time", row.getOrDefault("label", ""))));
            double value = toDouble(row.getOrDefault("value", row.get(metricField)));
            if (!name.isBlank()) {
                result.add(Map.of("name", name, "value", value));
            }
            if (result.size() >= 12) {
                break;
            }
        }
        return result;
    }

    private String renderSnapshotImage(String title, String chartType, List<Map<String, Object>> data) {
        int width = 960;
        int height = 540;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(25, 38, 70));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 26));
            g.drawString(title == null || title.isBlank() ? "诊断图表快照" : title, 42, 54);

            int left = 78;
            int right = 48;
            int top = 96;
            int bottom = 90;
            int chartWidth = width - left - right;
            int chartHeight = height - top - bottom;
            g.setColor(new Color(226, 232, 240));
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(left, top + chartHeight, left + chartWidth, top + chartHeight);
            g.drawLine(left, top, left, top + chartHeight);

            double max = data.stream().mapToDouble(item -> Math.abs(toDouble(item.get("value")))).max().orElse(1);
            if (max <= 0) {
                max = 1;
            }
            if ("line".equalsIgnoreCase(chartType)) {
                drawLineSnapshot(g, data, left, top, chartWidth, chartHeight, max);
            } else {
                drawBarSnapshot(g, data, left, top, chartWidth, chartHeight, max);
            }
        } catch (Exception ignored) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 24));
            g.drawString("诊断图表快照生成失败", 42, 72);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private void drawBarSnapshot(Graphics2D g, List<Map<String, Object>> data, int left, int top, int chartWidth, int chartHeight, double max) {
        int count = Math.max(1, data.size());
        int gap = 14;
        int barWidth = Math.max(18, (chartWidth - gap * (count + 1)) / count);
        Font labelFont = new Font("Microsoft YaHei", Font.PLAIN, 15);
        g.setFont(labelFont);
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> item = data.get(i);
            double value = Math.max(0, toDouble(item.get("value")));
            int barHeight = (int) Math.round((value / max) * (chartHeight - 28));
            int x = left + gap + i * (barWidth + gap);
            int y = top + chartHeight - barHeight;
            g.setColor(new Color(45, 114, 210));
            g.fillRoundRect(x, y, barWidth, barHeight, 8, 8);
            g.setColor(new Color(15, 23, 42));
            String valueText = compactNumber(value);
            g.drawString(valueText, x, Math.max(top + 18, y - 8));
            String label = trimLabel(Objects.toString(item.get("name")), 9);
            g.drawString(label, x + Math.max(0, (barWidth - metrics.stringWidth(label)) / 2), top + chartHeight + 28);
        }
    }

    private void drawLineSnapshot(Graphics2D g, List<Map<String, Object>> data, int left, int top, int chartWidth, int chartHeight, double max) {
        int count = Math.max(1, data.size());
        int previousX = -1;
        int previousY = -1;
        g.setStroke(new BasicStroke(3f));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> item = data.get(i);
            double value = Math.max(0, toDouble(item.get("value")));
            int x = left + (count == 1 ? chartWidth / 2 : (int) Math.round((double) i * chartWidth / (count - 1)));
            int y = top + chartHeight - (int) Math.round((value / max) * (chartHeight - 28));
            if (previousX >= 0) {
                g.setColor(new Color(45, 114, 210));
                g.drawLine(previousX, previousY, x, y);
            }
            g.setColor(new Color(239, 68, 68));
            g.fillOval(x - 5, y - 5, 10, 10);
            g.setColor(new Color(15, 23, 42));
            g.drawString(trimLabel(Objects.toString(item.get("name")), 10), x - 24, top + chartHeight + 28);
            previousX = x;
            previousY = y;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(Objects.toString(value, "0").replace(",", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private String compactNumber(double value) {
        if (Math.abs(value) >= 10000) {
            return String.format("%.1f万", value / 10000);
        }
        return String.format("%.0f", value);
    }

    private String trimLabel(String label, int maxLength) {
        if (label == null) {
            return "";
        }
        return label.length() <= maxLength ? label : label.substring(0, maxLength) + "...";
    }



    private byte[] buildDocx(String title, String content, byte[] snapshotImage) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph titleParagraph = document.createParagraph();
            titleParagraph.setAlignment(ParagraphAlignment.LEFT);
            XWPFRun titleRun = titleParagraph.createRun();
            titleRun.setBold(true);
            titleRun.setFontFamily("Microsoft YaHei");
            titleRun.setFontSize(18);
            titleRun.setText(title == null || title.isBlank() ? "智能诊断报告" : title);

            for (String rawLine : content.split("\\R", -1)) {
                appendDocxLine(document, rawLine);
            }

            if (snapshotImage != null && snapshotImage.length > 0) {
                XWPFParagraph caption = document.createParagraph();
                caption.setSpacingBefore(180);
                XWPFRun captionRun = caption.createRun();
                captionRun.setBold(true);
                captionRun.setFontFamily("Microsoft YaHei");
                captionRun.setFontSize(12);
                captionRun.setText("图表快照");

                XWPFParagraph imageParagraph = document.createParagraph();
                imageParagraph.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun imageRun = imageParagraph.createRun();
                imageRun.addPicture(
                        new ByteArrayInputStream(snapshotImage),
                        Document.PICTURE_TYPE_PNG,
                        "chart-snapshot.png",
                        Units.toEMU(430),
                        Units.toEMU(240)
                );
            }

            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Word 导出失败：" + e.getMessage());
        }
    }

    private void appendDocxLine(XWPFDocument document, String rawLine) {
        String line = rawLine == null ? "" : rawLine;
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(80);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(11);
        if (line.startsWith("# ")) {
            run.setBold(true);
            run.setFontSize(16);
            run.setText(line.substring(2));
        } else if (line.startsWith("## ")) {
            run.setBold(true);
            run.setFontSize(14);
            run.setText(line.substring(3));
        } else if (line.startsWith("### ")) {
            run.setBold(true);
            run.setFontSize(12);
            run.setText(line.substring(4));
        } else if (line.startsWith("- ")) {
            paragraph.setIndentationLeft(360);
            paragraph.setIndentationHanging(180);
            run.setText("• " + line.substring(2));
        } else {
            run.setText(line);
        }
    }

    private void putZip(ZipOutputStream zip, String path, String content) throws java.io.IOException {

        zip.putNextEntry(new ZipEntry(path));

        zip.write(content.stripLeading().getBytes(StandardCharsets.UTF_8));

        zip.closeEntry();

    }



    private byte[] buildPdf(String content, boolean encrypted, byte[] snapshotImage) {
        try (PDDocument document = new PDDocument()) {
            List<String> lines = wrapPdfLines(content);
            PDFont font = loadPdfFont(document);
            PDPage page = null;
            PDPageContentStream stream = null;
            float margin = 48;
            float y = 0;
            for (String line : lines) {
                if (page == null || y < margin + 18) {
                    if (stream != null) {
                        stream.endText();
                        stream.close();
                    }
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    stream.beginText();
                    stream.setFont(font, 10);
                    y = page.getMediaBox().getHeight() - margin;
                    stream.newLineAtOffset(margin, y);
                }
                String safeLine = toPdfSafeText(line);
                stream.showText(safeLine);
                stream.newLineAtOffset(0, -14);
                y -= 14;
            }
            if (stream != null) {
                stream.endText();
                stream.close();
            }
            if (snapshotImage != null && snapshotImage.length > 0) {
                PDPage imagePage = new PDPage(PDRectangle.A4);
                document.addPage(imagePage);
                PDImageXObject image = PDImageXObject.createFromByteArray(document, snapshotImage, "chart-snapshot.png");
                try (PDPageContentStream imageStream = new PDPageContentStream(document, imagePage)) {
                    float maxWidth = imagePage.getMediaBox().getWidth() - 96;
                    float maxHeight = imagePage.getMediaBox().getHeight() - 120;
                    float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
                    float width = image.getWidth() * scale;
                    float height = image.getHeight() * scale;
                    imageStream.drawImage(image, 48, imagePage.getMediaBox().getHeight() - 72 - height, width, height);
                }
            }
            if (encrypted) {
                AccessPermission permission = new AccessPermission();
                permission.setCanPrint(true);
                permission.setCanExtractContent(false);
                StandardProtectionPolicy policy = new StandardProtectionPolicy("insight-spark", "insight-spark", permission);
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("PDF 导出失败：" + e.getMessage());
        }
    }

    private void putZip(ZipOutputStream zip, String path, byte[] content) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content);
        zip.closeEntry();
    }

    private List<String> wrapPdfLines(String content) {
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\\R", -1)) {
            String line = raw == null ? "" : raw;
            if (line.isBlank()) {
                lines.add("");
                continue;
            }
            int width = 80;
            for (int i = 0; i < line.length(); i += width) {
                lines.add(line.substring(i, Math.min(line.length(), i + width)));
            }
        }
        return lines;
    }

    private String toPdfSafeText(String value) {
        return Objects.toString(value, "")
                .replace('\t', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\n\r]]", "");
    }

    private PDFont loadPdfFont(PDDocument document) throws java.io.IOException {
        List<String> candidates = List.of(
                "C:\\Windows\\Fonts\\NotoSansSC-VF.ttf",
                "C:\\Windows\\Fonts\\simhei.ttf",
                "C:\\Windows\\Fonts\\simfang.ttf",
                "C:\\Windows\\Fonts\\simsunb.ttf"
        );
        for (String path : candidates) {
            File file = new File(path);
            if (file.exists()) {
                return PDType0Font.load(document, file);
            }
        }
        return PDType1Font.HELVETICA;
    }

    private byte[] extractSnapshotImage(Map<String, Object> report) {
        Object snapshotObj = report.get("chartSnapshot");
        if (snapshotObj == null) {
            return null;
        }
        Map<String, Object> snapshot = null;
        if (snapshotObj instanceof Map<?, ?> map) {
            snapshot = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                snapshot.put(Objects.toString(entry.getKey()), entry.getValue());
            }
        } else {
            try {
                snapshot = objectMapper.readValue(Objects.toString(snapshotObj, ""), Map.class);
            } catch (Exception ignored) {
                return null;
            }
        }
        String dataUrl = Objects.toString(snapshot.get("imageDataUrl"), "");
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:image") || comma < 0) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }



    private String escapeXml(String value) {

        if (value == null) {

            return "";

        }

        return value.replace("&", "&amp;")

                .replace("<", "&lt;")

                .replace(">", "&gt;")

                .replace("\"", "&quot;");

    }



    private String toUtf16Hex(String value) {

        byte[] bytes = value.getBytes(StandardCharsets.UTF_16BE);

        StringBuilder hex = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {

            hex.append(String.format("%02X", b));

        }

        return hex.toString();

    }



    public record ExportFile(String filename, String contentType, byte[] content) {

    }

}

