package com.insightspark.service;



import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
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
import java.math.BigInteger;

import java.net.URI;

import java.net.http.HttpClient;

import java.net.http.HttpRequest;

import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;

import java.text.DecimalFormat;

import java.time.Instant;

import java.time.ZoneId;

import java.time.format.DateTimeFormatter;

import java.util.ArrayList;

import java.util.Base64;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Objects;

import java.util.Optional;

import java.util.concurrent.ThreadLocalRandom;

import java.util.regex.Matcher;

import java.util.regex.Pattern;

import java.util.zip.ZipEntry;

import java.util.zip.ZipOutputStream;



@Service

public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);



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

    private static final DecimalFormat REPORT_NUMBER_FORMAT = new DecimalFormat("#,##0.00");



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
        Map<String, String> fieldLabels = loadFieldLabels(tableName);



        String sourceQuestion = optionalString(request, "sourceQuestion");

        String question = sourceQuestion != null ? sourceQuestion : Objects.toString(request.getOrDefault("question",

                tableName + " " + metricField + " " + String.join(" ", dimensionFields)));

        try {
            knowledgeGraphService.syncGraph();
        } catch (Exception ignored) {
            // 诊断阶段优先使用 Neo4j 现有图谱，自动同步失败会在推理证据中体现为图谱上下文不足。
        }
        Map<String, Object> graphPath = knowledgeGraphService.retrieveMultiHopContextSafely(question, tableName);

        List<Map<String, Object>> graphNodes = castMapList(graphPath.getOrDefault("nodes", List.of()));

        List<Map<String, Object>> graphEdges = castMapList(graphPath.getOrDefault("edges", List.of()));

        List<Map<String, Object>> anomalyMarkers = buildAnomalyMarkers(rows, metricField, dimensionFields, timeField, Map.of());
        String documentSearchQuery = buildDiagnosisDocumentSearchQuery(question, rows, metricField, dimensionFields,
                timeField, fieldLabels, anomalyMarkers);
        List<Map<String, Object>> docEvidence = knowledgeDocumentService.search(documentSearchQuery, 10);



        Optional<Map<String, Object>> graphRagResult = pythonAiService.graphRagDiagnose(question, tableName, metricField,

                        dimensionFields, timeField, graphPath, docEvidence, rows, fieldLabels, detailLevel, anomalyType)

                ;

        boolean graphRagAiUsed = graphRagResult.isPresent();

        Map<String, Object> aiResult = graphRagResult

                .orElseGet(() -> pythonAiService.diagnose(tableName, metricField, dimensionFields, timeField, rows));

        Map<String, Object> graphRagRuntime = buildGraphRagRuntime(graphRagAiUsed, rows, graphNodes, graphEdges, docEvidence);

        aiResult.put("graphRagRuntime", graphRagRuntime);

        aiResult.put("relatedKnowledge", graphNodes);

        aiResult.put("graphEdges", graphEdges);

        aiResult.put("graphPath", graphPath);

        aiResult.put("docEvidence", docEvidence);

        List<Map<String, Object>> rawDataRows = rows.stream().limit(200).toList();
        aiResult.put("queryRows", rows.stream().limit(20).toList());
        aiResult.put("rawDataRows", rawDataRows);

        aiResult.put("sourceQuestion", sourceQuestion == null ? question : sourceQuestion);

        aiResult.put("sourceSql", optionalString(request, "sourceSql"));

        anomalyMarkers = buildAnomalyMarkers(rows, metricField, dimensionFields, timeField, aiResult);
        aiResult.put("anomalyMarkers", anomalyMarkers);

        aiResult.put("graphReasoningPath", Objects.toString(graphPath.getOrDefault("pathText", buildGraphReasoningPath(graphNodes))));

        aiResult.put("evidenceSources", buildEvidenceSources(docEvidence, graphNodes));
        aiResult.put("reasoningLogs", aiResult.getOrDefault("reasoningLogs", buildReasoningLogs(rows, graphNodes, graphEdges, docEvidence, aiResult, anomalyType, graphRagRuntime)));
        aiResult.put("detailLevel", detailLevel);
        aiResult.put("anomalyType", anomalyType);
        enhanceBusinessDiagnosis(aiResult, rows, tableName, metricField, dimensionFields, timeField,
                fieldLabels, docEvidence, graphNodes, graphEdges, anomalyType);
        aiResult.put("chartSnapshot", normalizeChartSnapshot(request.get("chartSnapshot"), aiResult, rows, metricField,
                castMapList(aiResult.getOrDefault("anomalyMarkers", anomalyMarkers))));



        Long reportId = null;
        boolean reportPersisted = false;
        String persistMode = "NEO4J";
        String persistError = "";
        try {
            reportId = saveReport(tableName, metricField, dimensionFields, timeField, aiResult, request);
            reportPersisted = true;
        } catch (Exception e) {
            persistMode = "DEGRADED_NO_PERSIST";
            persistError = safeErrorMessage(e);
            log.warn("Diagnosis report persistence degraded, return in-memory result only: {}", persistError);
        }

        Map<String, Object> reportPersistence = new LinkedHashMap<>();
        reportPersistence.put("persisted", reportPersisted);
        reportPersistence.put("mode", persistMode);
        reportPersistence.put("error", reportPersisted ? "" : persistError);
        reportPersistence.put("neo4jEnabled", neo4jEnabled);
        aiResult.put("reportPersistence", reportPersistence);
        aiResult.put("reportPersisted", reportPersisted);
        if (!reportPersisted) {
            aiResult.put("reportFallbackReason", persistError);
        }



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

        try {
            return hydrateReportListRows(neo4jQueryRows(cypher, Map.of("userId", com.insightspark.core.auth.AuthContext.userId())));
        } catch (Exception e) {
            log.warn("List diagnosis reports degraded to empty list: {}", safeErrorMessage(e));
            return List.of();
        }

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
        Map<String, Object> result = toStringKeyMap(report.get("resultJson"));
        if (!result.isEmpty()) {
            hydrateStoredReportFields(report, result);
            boolean hadPhysicalSnapshot = snapshotHasPhysicalTitle(toStringKeyMap(report.get("chartSnapshot")), result);
            result.put("factorChartBlocks", relabelFactorChartBlocks(
                    castMapList(result.getOrDefault("factorChartBlocks", List.of())),
                    toStringMap(result.get("fieldLabels")),
                    castMapList(result.getOrDefault("dimensionContributions", List.of()))));
            Map<String, Object> snapshot = toStringKeyMap(report.get("chartSnapshot"));
            if (snapshot.isEmpty()) {
                snapshot = toStringKeyMap(result.get("chartSnapshot"));
            }
            if (!hasSnapshotImage(snapshot) || hadPhysicalSnapshot || snapshotNeedsRerender(snapshot, toStringMap(result.get("fieldLabels")))) {
                if (hadPhysicalSnapshot) {
                    snapshot.put("forceRerender", true);
                }
                Map<String, Object> generatedSnapshot = normalizeChartSnapshot(snapshot, result, List.of(), Objects.toString(report.get("metricField"), ""), castMapList(result.getOrDefault("anomalyMarkers", List.of())));
                report.put("chartSnapshot", generatedSnapshot);
                result.put("chartSnapshot", generatedSnapshot);
            } else {
                relabelChartSnapshot(snapshot, toStringMap(result.get("fieldLabels")));
                report.put("chartSnapshot", snapshot);
                result.put("chartSnapshot", snapshot);
            }
            report.put("resultJson", result);
        }
        return report;

    }

    public Map<String, Object> deleteReport(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("报告ID不能为空");
        }
        return deleteReports(List.of(reportId));
    }

    public Map<String, Object> deleteReports(Object rawIds) {
        List<Long> reportIds = castLongList(rawIds);
        if (reportIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的诊断报告");
        }
        String cypher = """
                MATCH (r:DiagnosisReport)
                WHERE r.userId = $userId AND r.reportId IN $reportIds
                WITH collect(r) AS reports
                FOREACH (report IN reports | DETACH DELETE report)
                RETURN {deleted: size(reports)} AS row
                """;
        List<Map<String, Object>> rows = neo4jQueryRows(cypher, Map.of(
                "reportIds", reportIds,
                "userId", com.insightspark.core.auth.AuthContext.userId()
        ));
        long deleted = rows.isEmpty() ? 0L : Math.round(toDouble(rows.get(0).get("deleted")));
        if (deleted <= 0) {
            throw new IllegalArgumentException("诊断报告不存在或无权删除");
        }
        return Map.of("deleted", deleted, "requested", reportIds.size());
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
                    buildAcademicDocx(report, includeSnapshots ? extractSnapshotImage(report) : null, includeReasoningLogs)
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
        content.append("## 异常节点标注\n\n")
                .append(buildAnomalyExportSummary(report))
                .append("\n\n")
                .append("## 原始数据明细\n\n")
                .append(buildRawDataExportSummary(report))
                .append("\n\n");
        content.append(markdown);
        return content.toString();
    }

    private String buildAnomalyExportSummary(Map<String, Object> report) {
        Map<String, Object> result = toStringKeyMap(report.get("resultJson"));
        List<Map<String, Object>> markers = castMapList(result.getOrDefault("anomalyMarkers", List.of()));
        if (markers.isEmpty()) {
            Map<String, Object> snapshot = toStringKeyMap(report.get("chartSnapshot"));
            markers = castMapList(snapshot.getOrDefault("anomalyMarkers", List.of()));
        }
        if (markers.isEmpty()) {
            return "未识别到超过阈值的异常节点。";
        }
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> marker : markers) {
            builder.append("- ")
                    .append(Objects.toString(marker.getOrDefault("label", "异常点")))
                    .append("：")
                    .append(Objects.toString(marker.getOrDefault("valueLabel", marker.getOrDefault("value", ""))))
                    .append("，")
                    .append(Objects.toString(marker.getOrDefault("reason", "已标注为异常节点")))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String buildRawDataExportSummary(Map<String, Object> report) {
        Map<String, Object> result = toStringKeyMap(report.get("resultJson"));
        List<Map<String, Object>> rows = castMapList(result.getOrDefault("rawDataRows", result.getOrDefault("queryRows", List.of())));
        if (rows.isEmpty()) {
            return "未绑定原始数据明细。";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (Map<String, Object> row : rows.stream().limit(10).toList()) {
            builder.append(index++).append(". ");
            builder.append(row.entrySet().stream()
                    .limit(8)
                    .map(entry -> entry.getKey() + "=" + Objects.toString(entry.getValue(), ""))
                    .reduce((a, b) -> a + "；" + b)
                    .orElse(""));
            builder.append("\n");
        }
        if (rows.size() > 10) {
            builder.append("... 共绑定 ").append(rows.size()).append(" 条明细，导出仅展示前 10 条。\n");
        }
        return builder.toString().trim();
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

        Map<String, String> persistedFieldLabels = toStringMap(aiResult.get("fieldLabels"));
        if (persistedFieldLabels.isEmpty() && !tableName.isBlank()) {
            persistedFieldLabels.putAll(loadFieldLabels(tableName));
        }
        String title = replacePhysicalFields(Objects.toString(aiResult.getOrDefault("title", "智能诊断报告")), persistedFieldLabels);
        String summary = replacePhysicalFields(Objects.toString(aiResult.getOrDefault("summary", "")), persistedFieldLabels);
        String reportMarkdown = replacePhysicalFields(Objects.toString(aiResult.getOrDefault("reportMarkdown", "")), persistedFieldLabels);
        aiResult.put("title", title);
        aiResult.put("summary", summary);
        aiResult.put("reportMarkdown", reportMarkdown);

        params.put("title", title);

        params.put("summary", summary);

        params.put("reportMarkdown", reportMarkdown);

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

    private List<String> castStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> Objects.toString(item, "").trim())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return List.of();
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<?> parsed = objectMapper.readValue(text, List.class);
                return parsed.stream()
                        .map(item -> Objects.toString(item, "").trim())
                        .filter(item -> !item.isBlank())
                        .toList();
            } catch (Exception ignored) {
                String body = text.substring(1, text.length() - 1).trim();
                if (body.isBlank()) {
                    return List.of();
                }
                return List.of(body.split(",")).stream()
                        .map(item -> item.replaceAll("^['\"]|['\"]$", "").trim())
                        .filter(item -> !item.isBlank())
                        .toList();
            }
        }
        if (text.contains(",")) {
            return List.of(text.split(",")).stream()
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of(text);
    }

    private List<Long> castLongList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Number number) {
            return List.of(number.longValue());
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::parseLongValue)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return List.of();
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<?> parsed = objectMapper.readValue(text, List.class);
                return castLongList(parsed);
            } catch (Exception ignored) {
                text = text.substring(1, text.length() - 1);
            }
        }
        return List.of(text.split(",")).stream()
                .map(item -> item.replaceAll("^['\"]|['\"]$", "").trim())
                .map(this::parseLongValue)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Long parseLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
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

    private void enhanceBusinessDiagnosis(Map<String, Object> aiResult,
                                          List<Map<String, Object>> rows,
                                          String tableName,
                                          String metricField,
                                          List<String> dimensionFields,
                                          String timeField,
                                          Map<String, String> fieldLabels,
                                          List<Map<String, Object>> docEvidence,
                                          List<Map<String, Object>> graphNodes,
                                          List<Map<String, Object>> graphEdges,
                                          String anomalyType) {
        aiResult.put("fieldLabels", fieldLabels);
        aiResult.put("metricFieldLabel", labelOf(fieldLabels, metricField));
        aiResult.put("dimensionFieldLabels", dimensionFields.stream().map(field -> labelOf(fieldLabels, field)).toList());
        aiResult.put("timeFieldLabel", labelOf(fieldLabels, timeField));

        List<Map<String, Object>> rawMarkers = castMapList(aiResult.getOrDefault("anomalyMarkers", List.of()));
        List<Map<String, Object>> rawAnomalyRows = rawMarkers.stream()
                .map(marker -> toStringKeyMap(marker.get("row")))
                .filter(row -> !row.isEmpty())
                .toList();
        aiResult.put("dimensionContributions", relabelContributionBlocks(
                castMapList(aiResult.getOrDefault("dimensionContributions", List.of())),
                fieldLabels,
                "全样本"));
        aiResult.put("anomalyDimensionContributions", buildDimensionContributionBlocks(
                rawAnomalyRows.isEmpty() ? rows : rawAnomalyRows,
                metricField,
                dimensionFields,
                fieldLabels,
                rawAnomalyRows.isEmpty() ? "全样本" : "异常节点子集"));
        aiResult.put("factorChartBlocks", relabelFactorChartBlocks(
                castMapList(aiResult.getOrDefault("factorChartBlocks", List.of())),
                fieldLabels,
                castMapList(aiResult.getOrDefault("dimensionContributions", List.of()))));

        List<Map<String, Object>> businessRows = rows.stream()
                .limit(200)
                .map(row -> relabelRow(row, fieldLabels))
                .toList();
        aiResult.put("rawDataRows", businessRows);
        aiResult.put("queryRows", businessRows.stream().limit(20).toList());

        List<Map<String, Object>> anomalyMarkers = rawMarkers.stream()
                .map(marker -> relabelAnomalyMarker(marker, fieldLabels))
                .toList();
        aiResult.put("anomalyMarkers", anomalyMarkers);

        List<Map<String, Object>> evidence = distinctEvidence(docEvidence.stream()
                .map(this::normalizeDocEvidence)
                .toList()).stream().limit(6).toList();
        aiResult.put("docEvidence", evidence);
        aiResult.put("evidenceSources", buildEvidenceSources(evidence, graphNodes));

        List<Map<String, Object>> rootCauses = upgradeRootCauseTemplates(aiResult, rows, metricField,
                dimensionFields, timeField, fieldLabels, evidence, graphNodes, graphEdges, anomalyType);
        aiResult.put("rootCauses", rootCauses);

        List<Map<String, Object>> graphRagEvidenceChain = buildGraphRagEvidenceChain(tableName, metricField,
                dimensionFields, timeField, fieldLabels, anomalyMarkers, graphNodes, graphEdges, evidence, rootCauses);
        aiResult.put("graphRagEvidenceChain", graphRagEvidenceChain);
        aiResult.put("suggestions", upgradeSuggestions(aiResult, fieldLabels, evidence, rootCauses));
        aiResult.put("graphReasoningPath", buildBusinessGraphReasoningPath(tableName, metricField,
                dimensionFields, timeField, fieldLabels, graphNodes, graphEdges, evidence, rootCauses));
        aiResult.put("relatedKnowledge", mergeBusinessKnowledge(graphNodes, evidence, rootCauses));
        aiResult.put("reasoningLogs", buildBusinessReasoningLogs(rows, graphNodes, graphEdges, evidence,
                graphRagEvidenceChain, rootCauses, anomalyType));
        aiResult.put("title", replacePhysicalFields(Objects.toString(aiResult.getOrDefault("title", "智能诊断报告")), fieldLabels));
        aiResult.put("summary", replacePhysicalFields(Objects.toString(aiResult.getOrDefault("summary", "")), fieldLabels));
        aiResult.put("reportMarkdown", buildBusinessReportMarkdown(aiResult, tableName, metricField,
                dimensionFields, timeField, fieldLabels, rows, evidence, rootCauses));

        Map<String, Object> snapshot = toStringKeyMap(aiResult.get("chartSnapshot"));
        if (!snapshot.isEmpty()) {
            snapshot.put("fieldMapping", Map.of(
                    "metric", labelOf(fieldLabels, metricField),
                    "dimension", dimensionFields.stream().map(field -> labelOf(fieldLabels, field)).toList(),
                    "time", labelOf(fieldLabels, timeField)
            ));
            snapshot.put("anomalyMarkers", anomalyMarkers);
            relabelChartSnapshot(snapshot, fieldLabels);
            aiResult.put("chartSnapshot", snapshot);
        }
    }

    private Map<String, String> loadFieldLabels(String tableName) {
        List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                SELECT column_name AS columnName, source_field_name AS sourceFieldName, display_name AS displayName
                FROM is_data_field
                WHERE table_name = ?
                ORDER BY sort_order ASC
                """, tableName);
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            String displayName = Objects.toString(field.get("displayName"), "");
            String sourceName = Objects.toString(field.get("sourceFieldName"), "");
            String label = !displayName.isBlank() ? displayName : sourceName;
            if (!columnName.isBlank() && !label.isBlank()) {
                labels.put(columnName, label);
            }
        }
        return labels;
    }

    private void hydrateStoredReportFields(Map<String, Object> report, Map<String, Object> result) {
        String tableName = Objects.toString(report.getOrDefault("tableName", result.getOrDefault("tableName", "")), "");
        Map<String, String> fieldLabels = toStringMap(result.get("fieldLabels"));
        if (fieldLabels.isEmpty() && !tableName.isBlank()) {
            fieldLabels.putAll(loadFieldLabels(tableName));
        }
        if (!fieldLabels.isEmpty()) {
            result.put("fieldLabels", fieldLabels);
        }
        report.put("title", replacePhysicalFields(Objects.toString(report.getOrDefault("title", ""), ""), fieldLabels));
        report.put("summary", replacePhysicalFields(Objects.toString(report.getOrDefault("summary", ""), ""), fieldLabels));
        report.put("reportMarkdown", replacePhysicalFields(Objects.toString(report.getOrDefault("reportMarkdown", ""), ""), fieldLabels));
        result.put("title", replacePhysicalFields(Objects.toString(result.getOrDefault("title", report.getOrDefault("title", "")), ""), fieldLabels));
        result.put("summary", replacePhysicalFields(Objects.toString(result.getOrDefault("summary", report.getOrDefault("summary", "")), ""), fieldLabels));
        result.put("reportMarkdown", replacePhysicalFields(Objects.toString(result.getOrDefault("reportMarkdown", report.getOrDefault("reportMarkdown", "")), ""), fieldLabels));
        result.put("rawDataRows", relabelRows(castMapList(result.getOrDefault("rawDataRows", result.getOrDefault("queryRows", List.of()))), fieldLabels));
        result.put("queryRows", relabelRows(castMapList(result.getOrDefault("queryRows", result.getOrDefault("rawDataRows", List.of()))), fieldLabels)
                .stream()
                .limit(20)
                .toList());

        String metricField = Objects.toString(report.getOrDefault("metricField", result.getOrDefault("metricField", "")), "");
        String timeField = Objects.toString(report.getOrDefault("timeField", result.getOrDefault("timeField", "")), "");
        List<String> dimensionFields = castStringList(report.getOrDefault("dimensionFields", result.getOrDefault("dimensionFields", List.of())));
        if (!metricField.isBlank()) {
            result.put("metricField", metricField);
            result.put("metricFieldLabel", readableFieldLabel(metricField, result.get("metricFieldLabel"), fieldLabels));
        }
        if (!timeField.isBlank()) {
            result.put("timeField", timeField);
            result.put("timeFieldLabel", readableFieldLabel(timeField, result.get("timeFieldLabel"), fieldLabels));
        }
        if (!dimensionFields.isEmpty()) {
            result.put("dimensionFields", dimensionFields);
            result.put("dimensionFieldLabels", dimensionFields.stream()
                    .map(field -> readableFieldLabel(field, "", fieldLabels))
                    .filter(label -> !label.isBlank())
                    .toList());
        }

        List<Map<String, Object>> dimensionContributions = relabelContributionBlocks(
                castMapList(result.getOrDefault("dimensionContributions", List.of())),
                fieldLabels,
                "全样本");
        result.put("dimensionContributions", dimensionContributions);
        result.put("anomalyDimensionContributions", relabelContributionBlocks(
                castMapList(result.getOrDefault("anomalyDimensionContributions", List.of())),
                fieldLabels,
                "异常节点子集"));
    }

    private List<Map<String, Object>> hydrateReportListRows(List<Map<String, Object>> reports) {
        Map<String, Map<String, String>> labelsByTable = new LinkedHashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> report : reports) {
            Map<String, Object> row = new LinkedHashMap<>(report);
            String tableName = Objects.toString(row.getOrDefault("tableName", ""), "");
            Map<String, String> fieldLabels = labelsByTable.computeIfAbsent(tableName,
                    key -> key.isBlank() ? Map.of() : loadFieldLabels(key));
            String metricField = Objects.toString(row.getOrDefault("metricField", ""), "");
            String timeField = Objects.toString(row.getOrDefault("timeField", ""), "");
            row.put("metricFieldLabel", readableFieldLabel(metricField, "", fieldLabels));
            row.put("timeFieldLabel", readableFieldLabel(timeField, "", fieldLabels));
            row.put("title", replacePhysicalFields(Objects.toString(row.getOrDefault("title", ""), ""), fieldLabels));
            row.put("summary", replacePhysicalFields(Objects.toString(row.getOrDefault("summary", ""), ""), fieldLabels));
            row.put("dimensionFieldLabels", castStringList(row.getOrDefault("dimensionFields", List.of())).stream()
                    .map(field -> readableFieldLabel(field, "", fieldLabels))
                    .filter(label -> !label.isBlank())
                    .toList());
            result.add(row);
        }
        return result;
    }

    private String labelOf(Map<String, String> labels, String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        return labels.getOrDefault(field, field);
    }

    private String labelOfOrValue(Map<String, String> labels, Object value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return text;
        }
        return labels.getOrDefault(text, text);
    }

    private String replacePhysicalFields(String text, Map<String, String> fieldLabels) {
        String result = Objects.toString(text, "");
        for (Map.Entry<String, String> entry : fieldLabels.entrySet()) {
            String field = Objects.toString(entry.getKey(), "").trim();
            String label = Objects.toString(entry.getValue(), "").trim();
            if (field.isBlank() || label.isBlank() || field.equals(label) || looksPhysicalField(label)) {
                continue;
            }
            result = Pattern.compile("\\b" + Pattern.quote(field) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(result)
                    .replaceAll(Matcher.quoteReplacement(label));
        }
        return result;
    }

    private List<Map<String, Object>> relabelContributionBlocks(List<Map<String, Object>> contributions,
                                                                Map<String, String> fieldLabels,
                                                                String scope) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> contribution : contributions) {
            Map<String, Object> normalized = new LinkedHashMap<>(contribution);
            String dimensionField = Objects.toString(contribution.getOrDefault("dimensionField", contribution.getOrDefault("dimension", "")));
            String dimensionLabel = readableFieldLabel(dimensionField, contribution.getOrDefault("dimensionLabel", ""), fieldLabels);
            normalized.put("dimensionField", dimensionField);
            normalized.put("dimensionLabel", dimensionLabel.isBlank() ? labelOf(fieldLabels, dimensionField) : dimensionLabel);
            normalized.put("scope", Objects.toString(contribution.getOrDefault("scope", scope)));
            normalized.put("topItems", relabelContributionItems(castMapList(contribution.getOrDefault("topItems", List.of())), fieldLabels));
            result.add(normalized);
        }
        return result;
    }

    private List<Map<String, Object>> relabelContributionItems(List<Map<String, Object>> items, Map<String, String> fieldLabels) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> normalized = new LinkedHashMap<>(item);
            normalized.put("name", labelOfOrValue(fieldLabels, item.getOrDefault("name", item.getOrDefault("label", "-"))));
            result.add(normalized);
        }
        return result;
    }

    private List<Map<String, Object>> buildDimensionContributionBlocks(List<Map<String, Object>> sourceRows,
                                                                      String metricField,
                                                                      List<String> dimensionFields,
                                                                      Map<String, String> fieldLabels,
                                                                      String scope) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (String dimension : dimensionFields.stream().limit(3).toList()) {
            Map<String, Double> contribution = aggregateByDimension(sourceRows, metricField, dimension);
            double total = contribution.values().stream().mapToDouble(Double::doubleValue).sum();
            List<Map<String, Object>> topItems = contribution.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(entry -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", entry.getKey());
                        item.put("value", Math.round(entry.getValue() * 100.0) / 100.0);
                        item.put("share", total == 0 ? 0 : Math.round(entry.getValue() / total * 10000.0) / 100.0);
                        return item;
                    })
                    .toList();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("dimensionField", dimension);
            block.put("dimensionLabel", labelOf(fieldLabels, dimension));
            block.put("scope", scope);
            block.put("topItems", topItems);
            blocks.add(block);
        }
        return relabelContributionBlocks(blocks, fieldLabels, scope);
    }

    private List<Map<String, Object>> relabelFactorChartBlocks(List<Map<String, Object>> blocks,
                                                               Map<String, String> fieldLabels,
                                                               List<Map<String, Object>> contributions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            Map<String, Object> block = new LinkedHashMap<>(blocks.get(index));
            Map<String, Object> contribution = index < contributions.size() ? contributions.get(index) : Map.of();
            String rawDimension = Objects.toString(contribution.getOrDefault("dimensionField", block.getOrDefault("dimensionField", "")));
            String dimensionLabel = readableFieldLabel(rawDimension, contribution.getOrDefault("dimensionLabel", block.getOrDefault("dimensionLabel", "")), fieldLabels);
            String title = Objects.toString(block.getOrDefault("title", "")).trim();
            if (looksPhysicalField(title.replace(" 贡献拆解", "")) || title.matches("(?i).*col_\\d{3}.*")) {
                title = (dimensionLabel.isBlank() ? "业务维度" : dimensionLabel) + "贡献拆解";
            } else {
                for (Map.Entry<String, String> entry : fieldLabels.entrySet()) {
                    title = title.replace(entry.getKey(), entry.getValue());
                }
            }
            block.put("title", title.isBlank() ? "关联因素图表块" : title);
            block.put("dimensionLabel", dimensionLabel);
            block.put("data", relabelContributionItems(castMapList(block.getOrDefault("data", List.of())), fieldLabels));
            result.add(block);
        }
        return result;
    }

    private void relabelChartSnapshot(Map<String, Object> snapshot, Map<String, String> fieldLabels) {
        String title = Objects.toString(snapshot.getOrDefault("title", "")).trim();
        for (Map.Entry<String, String> entry : fieldLabels.entrySet()) {
            title = title.replace(entry.getKey(), entry.getValue());
        }
        if (title.matches("(?i).*col_\\d{3}.*")) {
            Object mapping = toStringKeyMap(snapshot.get("fieldMapping")).get("dimension");
            List<String> dimensions = castStringList(mapping);
            title = (dimensions.isEmpty() ? "业务维度" : dimensions.get(0)) + "贡献拆解";
        }
        if (!title.isBlank()) {
            snapshot.put("title", title);
        }
        snapshot.put("data", relabelContributionItems(castMapList(snapshot.getOrDefault("data", List.of())), fieldLabels));
    }

    private boolean snapshotNeedsRerender(Map<String, Object> snapshot, Map<String, String> fieldLabels) {
        String title = Objects.toString(snapshot.getOrDefault("title", ""));
        if (title.matches("(?i).*col_\\d{3}.*")) {
            return true;
        }
        if (fieldLabels.isEmpty()) {
            return false;
        }
        String dataUrl = Objects.toString(snapshot.getOrDefault("imageDataUrl", ""));
        return !dataUrl.isBlank() && castMapList(snapshot.getOrDefault("data", List.of())).stream()
                .anyMatch(item -> Objects.toString(item.getOrDefault("name", item.getOrDefault("label", ""))).matches("(?i)^col_\\d{3}$"));
    }

    private boolean snapshotHasPhysicalTitle(Map<String, Object> snapshot, Map<String, Object> result) {
        if (Objects.toString(snapshot.getOrDefault("title", "")).matches("(?i).*col_\\d{3}.*")) {
            return true;
        }
        return castMapList(result.getOrDefault("factorChartBlocks", List.of())).stream()
                .map(block -> Objects.toString(block.getOrDefault("title", "")))
                .anyMatch(title -> title.matches("(?i).*col_\\d{3}.*"));
    }

    private String buildDiagnosisDocumentSearchQuery(String question,
                                                     List<Map<String, Object>> rows,
                                                     String metricField,
                                                     List<String> dimensionFields,
                                                     String timeField,
                                                     Map<String, String> fieldLabels,
                                                     List<Map<String, Object>> anomalyMarkers) {
        List<String> terms = new ArrayList<>();
        terms.add(question);
        terms.add(labelOf(fieldLabels, metricField));
        terms.add(labelOf(fieldLabels, timeField));
        dimensionFields.forEach(field -> terms.add(labelOf(fieldLabels, field)));

        for (Map<String, Object> marker : anomalyMarkers.stream().limit(4).toList()) {
            terms.add(Objects.toString(marker.get("label"), ""));
            Map<String, Object> row = toStringKeyMap(marker.get("row"));
            addBusinessRowTerms(terms, row, dimensionFields, timeField);
        }
        rows.stream()
                .sorted((a, b) -> Double.compare(toDouble(b.get(metricField)), toDouble(a.get(metricField))))
                .limit(6)
                .forEach(row -> addBusinessRowTerms(terms, row, dimensionFields, timeField));

        return terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .distinct()
                .reduce((a, b) -> a + " " + b)
                .orElse(question);
    }

    private void addBusinessRowTerms(List<String> terms, Map<String, Object> row,
                                     List<String> dimensionFields, String timeField) {
        if (timeField != null && !timeField.isBlank()) {
            terms.add(Objects.toString(row.get(timeField), ""));
        }
        for (String dimension : dimensionFields) {
            terms.add(Objects.toString(row.get(dimension), ""));
        }
    }

    private Map<String, Object> relabelRow(Map<String, Object> row, Map<String, String> labels) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            result.put(labelOf(labels, entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<Map<String, Object>> relabelRows(List<Map<String, Object>> rows, Map<String, String> labels) {
        return rows.stream()
                .map(row -> relabelRow(row, labels))
                .toList();
    }

    private Map<String, Object> relabelAnomalyMarker(Map<String, Object> marker, Map<String, String> labels) {
        Map<String, Object> result = new LinkedHashMap<>(marker);
        String metric = Objects.toString(marker.get("metricField"), "");
        String metricLabel = labelOf(labels, metric);
        result.put("metricField", metricLabel);
        result.put("valueLabel", Objects.toString(marker.getOrDefault("valueLabel", ""))
                .replace(metric, metricLabel));
        result.put("row", relabelRow(toStringKeyMap(marker.get("row")), labels));
        return result;
    }

    private Map<String, Object> normalizeDocEvidence(Map<String, Object> item) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("nodeType", "DOC_EVIDENCE");
        evidence.put("sourceType", Objects.toString(item.getOrDefault("docType", "文档")));
        evidence.put("label", Objects.toString(item.getOrDefault("title", item.getOrDefault("fileName", "知识文档"))));
        evidence.put("source", Objects.toString(item.getOrDefault("source", evidence.get("label"))));
        evidence.put("content", previewText(item.get("chunkText")));
        evidence.put("score", item.getOrDefault("score", 0));
        return evidence;
    }

    private List<Map<String, Object>> upgradeRootCauseTemplates(Map<String, Object> aiResult,
                                                                List<Map<String, Object>> rows,
                                                                String metricField,
                                                                List<String> dimensionFields,
                                                                String timeField,
                                                                Map<String, String> fieldLabels,
                                                                List<Map<String, Object>> evidence,
                                                                List<Map<String, Object>> graphNodes,
                                                                List<Map<String, Object>> graphEdges,
                                                                String anomalyType) {
        List<Map<String, Object>> causes = new ArrayList<>();
        String metricLabel = labelOf(fieldLabels, metricField);
        List<Map<String, Object>> markers = castMapList(aiResult.getOrDefault("anomalyMarkers", List.of()));
        Map<String, Object> topMarker = markers.isEmpty() ? Map.of() : markers.get(0);
        String anomalyLabel = Objects.toString(topMarker.getOrDefault("label", "关键异常节点"));
        String anomalyValue = Objects.toString(topMarker.getOrDefault("valueLabel", metricLabel));
        String evidenceText = evidence.isEmpty()
                ? "暂无命中的企业文档/行业研报证据，结论主要来自数据波动和知识图谱字段关系。"
                : distinctEvidence(evidence).stream()
                    .limit(2)
                    .map(item -> "《" + item.get("label") + "》" + Objects.toString(item.get("content"), ""))
                    .reduce((a, b) -> a + "；" + b)
                    .orElse("");
        String businessContext = describeTopBusinessContext(rows, metricField, dimensionFields, timeField, fieldLabels);
        String firstEvidenceLabel = evidence.isEmpty() ? "企业复盘与行业研报" : "《" + evidence.get(0).get("label") + "》";
        Map<String, Double> topDimensionContribution = dimensionFields.isEmpty()
                ? Map.of()
                : aggregateByDimension(rows, metricField, dimensionFields.get(0));
        String topDimensionName = topDimensionContribution.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        String businessCauseName = buildBusinessRootCauseName(topMarker, dimensionFields, fieldLabels, topDimensionName);

        causes.add(rootCause("HIGH", businessCauseName, metricLabel, 0.9,
                anomalyLabel + " 出现 " + anomalyValue + "，" + businessContext
                        + "依据" + firstEvidenceLabel + "及异常节点数据，需要优先核验当前业务场景中对 "
                        + metricLabel + " 产生直接影响的事件、口径变化或运营条件。证据：" + evidenceText,
                "复核异常节点对应的原始记录、业务事件、统计口径和数据采集链路，确认波动是否由真实业务变化触发。"));

        if (!graphNodes.isEmpty()) {
            boolean metadataOnly = isMetadataOnlyGraph(graphEdges);
            causes.add(rootCause(metadataOnly ? "MEDIUM" : "HIGH",
                    metadataOnly ? "Neo4j 字段关系支撑的结构线索" : "Neo4j 多跳关系支持的业务因素",
                    metricLabel,
                    metadataOnly ? 0.68 : 0.86,
                    "Neo4j 从当前数据表和查询语义出发扩展到 "
                            + graphNodes.size() + " 个节点、" + graphEdges.size() + " 条关系。"
                            + summarizeGraphEvidence(graphNodes, graphEdges)
                            + (metadataOnly ? " 当前图谱关系以表字段元数据为主，可支撑回溯链路，但不足以单独构成业务因果根因。" : ""),
                    metadataOnly ? "补充业务事件、文档证据或历史诊断节点后，再将图谱线索升级为因果判断。"
                            : "沿图谱命中的表、字段、历史报告或业务标签继续核验上游数据口径和下游分析结论。"));
        }

        if (!dimensionFields.isEmpty()) {
            String dimension = dimensionFields.get(0);
            String dimensionLabel = labelOf(fieldLabels, dimension);
            Map<String, Double> contribution = aggregateByDimension(rows, metricField, dimension);
            String topDimension = contribution.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(entry -> entry.getKey() + " 贡献 " + compactNumber(entry.getValue()))
                    .orElse("暂无明确头部维度");
            causes.add(rootCause("MEDIUM", "业务维度贡献集中", dimensionLabel, 0.84,
                    dimensionLabel + " 中 " + topDimension + "，说明异常不是随机噪声，而是集中在特定业务分组。",
                    "围绕头部维度继续拆解相关下钻维度、业务对象和时间窗口，确认该分组是否放大了异常。"));
        }

        if (!evidence.isEmpty()) {
            Map<String, Object> firstEvidence = evidence.get(0);
            causes.add(rootCause("MEDIUM", "文档证据指向的业务因素", "企业文档/行业研报", 0.8,
                    "GraphRAG 检索到《" + firstEvidence.get("label") + "》：" + firstEvidence.get("content"),
                    "把文档中提到的业务因素与异常节点的时间、维度和原始明细进行交叉验证。"));
        }

        if (timeField != null && !timeField.isBlank()) {
            causes.add(rootCause("MEDIUM", "异常前后窗口的持续性风险", labelOf(fieldLabels, timeField), 0.74,
                    "异常类型为 " + anomalyTypeLabel(anomalyType) + "，且存在可回溯时间字段 " + labelOf(fieldLabels, timeField) + "，需要按异常前后窗口比较。",
                    "对异常节点前后相邻窗口的核心指标、相关维度和原始明细进行联动分析，判断尖峰或低谷是否会延续。"));
        }
        return causes;
    }

    private String buildBusinessRootCauseName(Map<String, Object> topMarker,
                                              List<String> dimensionFields,
                                              Map<String, String> fieldLabels,
                                              String fallbackDimensionValue) {
        Map<String, Object> row = toStringKeyMap(topMarker.get("row"));
        List<String> parts = new ArrayList<>();
        for (String dimension : dimensionFields.stream().limit(3).toList()) {
            String value = Objects.toString(row.getOrDefault(dimension, row.getOrDefault(labelOf(fieldLabels, dimension), ""))).trim();
            if (!value.isBlank()) {
                parts.add(value);
            }
        }
        if (parts.isEmpty() && fallbackDimensionValue != null && !fallbackDimensionValue.isBlank()) {
            parts.add(fallbackDimensionValue);
        }
        if (parts.isEmpty()) {
            return "关键异常节点业务波动";
        }
        return String.join("/", parts) + "集中波动驱动";
    }

    private boolean isMetadataOnlyGraph(List<Map<String, Object>> graphEdges) {
        if (graphEdges == null || graphEdges.isEmpty()) {
            return true;
        }
        long metadataEdges = graphEdges.stream()
                .map(edge -> Objects.toString(edge.getOrDefault("relationType", edge.getOrDefault("type", ""))).trim().toUpperCase())
                .filter(type -> type.equals("HAS_FIELD") || type.equals("FIELD_OF") || type.equals("HAS_COLUMN") || type.equals("RELATED"))
                .count();
        return metadataEdges >= Math.max(1, graphEdges.size() * 0.8);
    }

    private String anomalyTypeLabel(String anomalyType) {
        return switch (Objects.toString(anomalyType, "").toLowerCase()) {
            case "fluctuation" -> "波动异常";
            case "structure" -> "结构异常";
            case "trend" -> "趋势异常";
            default -> Objects.toString(anomalyType, "异常");
        };
    }

    private String describeTopBusinessContext(List<Map<String, Object>> rows,
                                              String metricField,
                                              List<String> dimensionFields,
                                              String timeField,
                                              Map<String, String> fieldLabels) {
        Map<String, Object> topRow = rows.stream()
                .max((a, b) -> Double.compare(toDouble(a.get(metricField)), toDouble(b.get(metricField))))
                .orElse(Map.of());
        List<String> parts = new ArrayList<>();
        if (timeField != null && !timeField.isBlank() && topRow.get(timeField) != null) {
            parts.add(labelOf(fieldLabels, timeField) + "为" + topRow.get(timeField));
        }
        for (String dimension : dimensionFields.stream().limit(3).toList()) {
            if (topRow.get(dimension) != null) {
                parts.add(labelOf(fieldLabels, dimension) + "为" + topRow.get(dimension));
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "高贡献记录显示" + String.join("、", parts) + "。";
    }

    private String summarizeGraphEvidence(List<Map<String, Object>> graphNodes, List<Map<String, Object>> graphEdges) {
        String nodeSummary = graphNodes.stream()
                .limit(5)
                .map(node -> Objects.toString(node.getOrDefault("label", node.getOrDefault("sourceId", "")))
                        + "(" + Objects.toString(node.getOrDefault("nodeType", "NODE")) + ")")
                .filter(item -> !item.isBlank())
                .reduce((a, b) -> a + " -> " + b)
                .orElse("暂无可展示节点");
        String edgeSummary = graphEdges.stream()
                .limit(3)
                .map(edge -> Objects.toString(edge.getOrDefault("relationType", "RELATED")))
                .filter(item -> !item.isBlank())
                .reduce((a, b) -> a + "、" + b)
                .orElse("RELATED");
        return " 关键节点链路：" + nodeSummary + "；关系类型：" + edgeSummary + "。";
    }

    private List<Map<String, Object>> buildGraphRagEvidenceChain(String tableName,
                                                                 String metricField,
                                                                 List<String> dimensionFields,
                                                                 String timeField,
                                                                 Map<String, String> fieldLabels,
                                                                 List<Map<String, Object>> anomalyMarkers,
                                                                 List<Map<String, Object>> graphNodes,
                                                                 List<Map<String, Object>> graphEdges,
                                                                 List<Map<String, Object>> evidence,
                                                                 List<Map<String, Object>> rootCauses) {
        List<Map<String, Object>> chain = new ArrayList<>();
        String metricLabel = labelOf(fieldLabels, metricField);
        chain.add(evidenceHop(1, "异常指标定位", metricLabel,
                "从数据表「" + tableName + "」读取指标「" + metricLabel + "」，识别异常节点 "
                        + anomalyMarkers.size() + " 个。", 1.0));
        int step = 2;
        for (String dimension : dimensionFields.stream().limit(3).toList()) {
            chain.add(evidenceHop(step++, "业务维度下钻", labelOf(fieldLabels, dimension),
                    "按业务维度「" + labelOf(fieldLabels, dimension) + "」聚合拆解异常贡献。", 0.88));
        }
        if (timeField != null && !timeField.isBlank()) {
            chain.add(evidenceHop(step++, "时间窗口回溯", labelOf(fieldLabels, timeField),
                    "沿时间字段「" + labelOf(fieldLabels, timeField) + "」定位异常前后窗口。", 0.86));
        }
        if (!graphNodes.isEmpty()) {
            chain.add(evidenceHop(step++, "Neo4j 图谱扩展", "图谱节点/关系",
                    summarizeGraphEvidence(graphNodes, graphEdges), graphEdges.isEmpty() ? 0.72 : 0.82));
        }
        for (Map<String, Object> item : evidence.stream().limit(3).toList()) {
            chain.add(evidenceHop(step++, "文档证据召回",
                    Objects.toString(item.getOrDefault("label", "知识文档")),
                    Objects.toString(item.getOrDefault("content", "")),
                    toDouble(item.getOrDefault("score", 0)) > 0 ? 0.8 : 0.62));
        }
        if (!rootCauses.isEmpty()) {
            Map<String, Object> cause = rootCauses.get(0);
            chain.add(evidenceHop(step, "根因结论生成",
                    Objects.toString(cause.getOrDefault("causeType", "根因结论")),
                    Objects.toString(cause.getOrDefault("evidence", "")),
                    toDouble(cause.getOrDefault("confidence", 0.75))));
        }
        return chain;
    }

    private Map<String, Object> evidenceHop(int step, String hopType, String label, String detail, double confidence) {
        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("step", step);
        hop.put("hopType", hopType);
        hop.put("label", label);
        hop.put("detail", detail);
        hop.put("confidence", Math.round(confidence * 100.0) / 100.0);
        return hop;
    }

    private Map<String, Object> rootCause(String level, String causeType, String impactField,
                                          double confidence, String evidence, String action) {
        Map<String, Object> cause = new LinkedHashMap<>();
        cause.put("level", level);
        cause.put("causeType", causeType);
        cause.put("impactField", impactField);
        cause.put("confidence", confidence);
        cause.put("evidence", evidence);
        cause.put("action", action);
        return cause;
    }

    private Map<String, Double> aggregateByDimension(List<Map<String, Object>> rows, String metricField, String dimensionField) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = Objects.toString(row.getOrDefault(dimensionField, "未分组"));
            result.put(key, result.getOrDefault(key, 0d) + toDouble(row.get(metricField)));
        }
        return result;
    }

    private String reportObservationWindow(Map<String, Object> marker,
                                           Map<String, Object> markerRow,
                                           String timeField,
                                           Map<String, String> fieldLabels) {
        String timeLabel = labelOf(fieldLabels, timeField);
        if (timeField != null && !timeField.isBlank()) {
            String rawTime = Objects.toString(markerRow.getOrDefault(timeField, ""), "").trim();
            String labelTime = Objects.toString(markerRow.getOrDefault(timeLabel, ""), "").trim();
            if (!labelTime.isBlank()) {
                return reportCell(labelTime);
            }
            if (!rawTime.isBlank()) {
                return reportCell(rawTime);
            }
        }
        return reportCell(Objects.toString(marker.getOrDefault("label", "异常点"), "异常点"));
    }

    private double reportMarkerValue(Map<String, Object> marker, Map<String, Object> markerRow, String metricField) {
        double value = toDouble(marker.get("value"));
        if (value != 0) {
            return value;
        }
        value = toDouble(marker.get("metricValue"));
        if (value != 0) {
            return value;
        }
        return toDouble(markerRow.get(metricField));
    }

    private String reportOutlierType(double value, double avg) {
        if (value > avg) {
            return "Positive Outlier (正向极值)";
        }
        if (value < avg) {
            return "Negative Outlier (负向极值)";
        }
        return "Deviation Outlier (标准差偏离)";
    }

    private String formatReportNumber(Object value) {
        double number = toDouble(value);
        synchronized (REPORT_NUMBER_FORMAT) {
            return REPORT_NUMBER_FORMAT.format(number);
        }
    }

    private String formatConfidence(Object value) {
        double confidence = toDouble(value);
        if (confidence <= 0) {
            return "-";
        }
        return String.format("%.2f", confidence);
    }

    private String reportDeviationText(double value, double avg, String stdValue) {
        if (value > avg) {
            return "处于样本总体极大值极点";
        }
        if (value < avg) {
            return "处于样本总体极小值极点";
        }
        return "偏离均值，标准差 σ = " + stdValue;
    }

    private String reportCell(Object value) {
        return Objects.toString(value, "-").replace("|", "\\|").replace("\n", " ").replace("\r", " ").trim();
    }

    private List<String> upgradeSuggestions(Map<String, Object> aiResult,
                                            Map<String, String> fieldLabels,
                                            List<Map<String, Object>> evidence,
                                            List<Map<String, Object>> rootCauses) {
        List<String> suggestions = new ArrayList<>();
        for (Map<String, Object> cause : rootCauses) {
            String action = Objects.toString(cause.get("action"), "");
            if (!action.isBlank()) {
                suggestions.add(action);
            }
        }
        if (!evidence.isEmpty()) {
            suggestions.add("将命中的企业文档/行业研报作为复盘附件，和异常节点一并提交给业务负责人确认。");
            suggestions.add("对照文档证据核查其中提到的业务事件、外部环境或管理动作是否影响本次异常指标。");
        }
        suggestions.add("复盘异常节点对应的业务维度和原始明细，重点核查头部记录、口径变更和数据采集异常。");
        suggestions.add("对异常低谷或尖峰建立相邻时间窗口对比，并把处理结论回写到报告回溯记录。");
        suggestions.add("为核心指标设置按业务字段命名的监控阈值，避免后续报告继续使用物理字段名。");
        return suggestions.stream().distinct().limit(6).toList();
    }

    private String buildBusinessGraphReasoningPath(String tableName,
                                                   String metricField,
                                                   List<String> dimensionFields,
                                                   String timeField,
                                                   Map<String, String> fieldLabels,
                                                   List<Map<String, Object>> graphNodes,
                                                   List<Map<String, Object>> graphEdges,
                                                   List<Map<String, Object>> evidence,
                                                   List<Map<String, Object>> rootCauses) {
        List<String> hops = new ArrayList<>();
        hops.add("数据表「" + tableName + "」");
        hops.add("指标「" + labelOf(fieldLabels, metricField) + "」异常");
        for (String dimension : dimensionFields.stream().limit(2).toList()) {
            hops.add("业务维度「" + labelOf(fieldLabels, dimension) + "」拆解");
        }
        if (timeField != null && !timeField.isBlank()) {
            hops.add("时间窗口「" + labelOf(fieldLabels, timeField) + "」回溯");
        }
        if (!evidence.isEmpty()) {
            hops.add("文档证据「" + evidence.get(0).get("label") + "」");
        }
        if (!graphNodes.isEmpty()) {
            hops.add("Neo4j 图谱节点 " + graphNodes.size() + " 个 / 关系 " + graphEdges.size() + " 条");
        }
        if (!rootCauses.isEmpty()) {
            hops.add("根因「" + rootCauses.get(0).get("causeType") + "」");
        }
        return String.join(" -> ", hops);
    }

    private List<Map<String, Object>> mergeBusinessKnowledge(List<Map<String, Object>> graphNodes,
                                                             List<Map<String, Object>> evidence,
                                                             List<Map<String, Object>> rootCauses) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.addAll(evidence);
        result.addAll(graphNodes.stream().limit(8).toList());
        for (Map<String, Object> cause : rootCauses) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeType", "ROOT_CAUSE");
            item.put("sourceType", "DIAGNOSIS");
            item.put("label", cause.get("causeType"));
            item.put("content", cause.get("evidence"));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> buildBusinessReasoningLogs(List<Map<String, Object>> rows,
                                                                 List<Map<String, Object>> graphNodes,
                                                                 List<Map<String, Object>> graphEdges,
                                                                 List<Map<String, Object>> evidence,
                                                                 List<Map<String, Object>> graphRagEvidenceChain,
                                                                 List<Map<String, Object>> rootCauses,
                                                                 String anomalyType) {
        return List.of(
                Map.of("step", 1, "title", "业务字段映射", "status", "completed",
                        "detail", "将物理字段转换为业务字段后扫描 " + rows.size() + " 条原始数据。"),
                Map.of("step", 2, "title", "异常节点识别", "status", "completed",
                        "detail", "围绕 " + anomalyType + " 计算极值、均值、标准差和异常节点。"),
                Map.of("step", 3, "title", "GraphRAG 文档召回", "status", "completed",
                        "detail", "检索企业内部文档/行业研报证据 " + evidence.size() + " 条。"),
                Map.of("step", 4, "title", "Neo4j 多跳扩展", "status", "completed",
                        "detail", "扩展图谱节点 " + graphNodes.size() + " 个、关系 " + graphEdges.size() + " 条。"),
                Map.of("step", 5, "title", "GraphRAG 证据链融合", "status", "completed",
                        "detail", "融合异常节点、业务维度、Neo4j 图谱和文档证据，形成 "
                                + graphRagEvidenceChain.size() + " 跳证据链。"),
                Map.of("step", 6, "title", "根因模板融合", "status", "completed",
                        "detail", "融合数据异常、业务维度、文档证据生成 " + rootCauses.size() + " 条根因结论。")
        );
    }

    private String buildBusinessReportMarkdown(Map<String, Object> aiResult,
                                               String tableName,
                                               String metricField,
                                               List<String> dimensionFields,
                                               String timeField,
                                               Map<String, String> fieldLabels,
                                               List<Map<String, Object>> rows,
                                               List<Map<String, Object>> evidence,
                                               List<Map<String, Object>> rootCauses) {
        String title = Objects.toString(aiResult.getOrDefault("title", "智能诊断报告"));
        String metricLabel = labelOf(fieldLabels, metricField);
        Map<String, Object> statistics = toStringKeyMap(aiResult.get("statistics"));
        List<Map<String, Object>> markers = castMapList(aiResult.getOrDefault("anomalyMarkers", List.of()));
        List<Map<String, Object>> graphRagEvidenceChain = castMapList(aiResult.getOrDefault("graphRagEvidenceChain", List.of()));
        List<Map<String, Object>> graphEdges = castMapList(aiResult.getOrDefault("graphEdges", List.of()));
        List<Map<String, Object>> graphNodes = castMapList(aiResult.getOrDefault("relatedKnowledge", List.of()));
        List<String> suggestions = castStringList(aiResult.getOrDefault("suggestions", List.of()));
        String createdAt = DATE_TIME_FORMATTER.format(Instant.now());
        String maxValue = formatReportNumber(statistics.get("max"));
        String minValue = formatReportNumber(statistics.get("min"));
        String totalValue = formatReportNumber(statistics.get("total"));
        String avgValue = formatReportNumber(statistics.get("avg"));
        String stdValue = formatReportNumber(statistics.get("std"));
        String graphPath = Objects.toString(aiResult.getOrDefault("graphReasoningPath", "")).trim();
        String dimensionNames = dimensionFields.stream()
                .map(field -> labelOf(fieldLabels, field))
                .filter(name -> name != null && !name.isBlank())
                .reduce((a, b) -> a + "/" + b)
                .orElse("未选择维度");
        String timeName = timeField != null && !timeField.isBlank() ? labelOf(fieldLabels, timeField) : "未选择时间字段";

        StringBuilder md = new StringBuilder();
        md.append("# 基于 GraphRAG 的多跳关联推理与业务指标异常归因分析\n\n");
        md.append(":::report-meta\n");
        md.append("Diagnostic Analysis Report | Insight Spark System  \n");
        md.append("数据集：`").append(tableName).append("` | 目标指标：").append(metricLabel).append("  \n");
        md.append("自动生成环境：智能诊断引擎 | 诊断时间：").append(createdAt).append("\n");
        md.append(":::\n\n");

        md.append("> **Abstract / 诊断摘要：** ");
        md.append("本次分析围绕核心业务指标「").append(metricLabel).append("」展开。系统在有效观测区间内提取了 ")
                .append(rows.size()).append(" 条样本记录进行异常扫描。");
        md.append("统计结果显示，样本总计数值为 ").append(totalValue)
                .append("，均值 μ 为 ").append(avgValue)
                .append("，区间极值分别为 Max = ").append(maxValue)
                .append(" 与 Min = ").append(minValue).append("。");
        md.append("通过统计算法，系统识别出 ").append(markers.size()).append(" 个具备统计学显著性的异常节点。");
        md.append("为探究异常机制，系统引入 GraphRAG 技术，融合业务维度拆解、时序窗口回溯与 Neo4j 知识图谱");
        md.append("（涉及 ").append(graphNodes.size()).append(" 个节点与 ").append(graphEdges.size()).append(" 条边），");
        md.append(rootCauseConclusion(rootCauses)).append("本文档详细记录了数据特征、多跳推理路径及多维度异质性分析结果。\n\n");

        md.append("## I. 描述性统计与异常检测 (Statistical Characteristics)\n\n");
        md.append("针对数据集 `").append(tableName).append("` 中的目标变量「").append(metricLabel)
                .append("」，系统执行了基准扫描。有效样本量 N = ").append(rows.size())
                .append("。基于分布特征，系统标定了 ").append(markers.size())
                .append(" 个显著偏离常态分布区间的异常观测点。具体检测结果如表 I 所示。\n\n");
        md.append("| 观测窗口 | 指标数值 | 偏离度 / 统计检验量 | 异常标定类型 |\n");
        md.append("| --- | ---: | --- | --- |\n");
        if (markers.isEmpty()) {
            md.append("| - | - | 未发现 Z-Score 绝对值超过阈值的节点 | Normal Observation |\n");
        } else {
            for (Map<String, Object> marker : markers.stream().limit(5).toList()) {
                Map<String, Object> markerRow = toStringKeyMap(marker.get("row"));
                String window = reportObservationWindow(marker, markerRow, timeField, fieldLabels);
                double value = reportMarkerValue(marker, markerRow, metricField);
                md.append("| ").append(window)
                        .append(" | ").append(formatReportNumber(value))
                        .append(" | ").append(reportDeviationText(value, toDouble(statistics.get("avg")), stdValue))
                        .append(" | ").append(reportOutlierType(value, toDouble(statistics.get("avg"))))
                        .append(" |\n");
            }
        }
        md.append("\n*表 I. 核心指标异常节点识别清单*\n\n");

        md.append("## II. 图谱知识检索与多跳推理链路 (GraphRAG Reasoning)\n\n");
        md.append("为克服单一数据视角的局限性，本次诊断未仅停留在字段级的相关性分析，而是构建了基于 GraphRAG 的因果推理拓扑。");
        md.append("推理链路严格遵循以下演进次序：数据表关联 -> 指标层映射 -> 业务维度空间拆解（")
                .append(dimensionNames).append("） -> 时序变量回溯（").append(timeName).append("）。\n\n");
        md.append("在图计算阶段，系统调用 Neo4j 图数据库，遍历 ").append(graphNodes.size())
                .append(" 个关联实体节点与 ").append(graphEdges.size())
                .append(" 条语义关系边，")
                .append(rootCauseConclusion(rootCauses));
        md.append("\n\n");
        if (evidence.isEmpty()) {
            md.append("> **检索证据缺失声明 (Corpus Absence Note)：** 在 RAG 检索阶段，未命中可引用的企业内部复盘文档或外部行业研报。当前得出的根因结论高度依赖于底层统计波动特征与图谱结构的内生字段关系。建议管理层后续向知识库补充非结构化业务说明，以提升归因模型的鲁棒性。\n\n");
        } else {
            md.append("> **检索证据摘要 (Corpus Evidence Note)：** RAG 检索阶段命中 ")
                    .append(evidence.size()).append(" 条企业文档或行业研报证据，系统已将其纳入根因假设排序与建议动作生成。\n\n");
        }
        md.append("## III. 归因定位与置信度评估 (Attribution Analysis)\n\n");
        md.append("基于上述多跳推理逻辑，系统对诱发指标波动的潜在因素进行了权重分配与显著性评估。当前根因结论覆盖")
                .append(confidenceBandText(rootCauses))
                .append("置信区间：\n\n");
        for (Map<String, Object> cause : rootCauses) {
            md.append("- **[置信度: ").append(formatConfidence(cause.get("confidence")))
                    .append(" / ").append(cause.getOrDefault("level", "MEDIUM")).append("] ")
                    .append(cause.getOrDefault("causeType", "根因假设"))
                    .append("：** 主要影响对象为「").append(cause.getOrDefault("impactField", metricLabel))
                    .append("」。").append(cause.getOrDefault("evidence", "")).append("\n");
        }

        md.append("\n## IV. 多维度异质性分析 (Multidimensional Heterogeneity)\n\n");
        md.append("为进一步剥离异常值的结构来源，本节对核心维度（").append(dimensionNames)
                .append("）进行了下钻与贡献度拆解。表 II 优先展示异常节点子集贡献；当异常节点明细不足时，回退展示全样本贡献分布。\n\n");
        if (dimensionFields.isEmpty()) {
            md.append("- 未选择拆解维度，建议至少选择可解释该指标波动的业务维度。\n");
        } else {
            md.append("| 分析口径 (Scope) | 一阶维度 (Dimension) | 二阶因子 (Factor) | 贡献值 (Value) | 口径内占比 (Ratio) |\n");
            md.append("| --- | --- | --- | ---: | ---: |\n");
            List<List<String>> dimensionRows = dimensionDocxRows(aiResult, rows, metricField, metricLabel, dimensionFields,
                    dimensionFields.stream().map(field -> labelOf(fieldLabels, field)).toList(), fieldLabels);
            for (List<String> row : dimensionRows.stream().skip(1).toList()) {
                md.append("| ")
                        .append(String.join(" | ", row))
                        .append(" |\n");
            }
            md.append("\n*表 II. 核心业务维度贡献与相对比重拆解（优先异常节点口径）*\n");
        }

        if (!evidence.isEmpty()) {
            md.append("\n### 企业文档 / 行业研报证据\n\n");
            for (Map<String, Object> item : evidence.stream().limit(5).toList()) {
                md.append("- 《").append(item.getOrDefault("label", "知识文档")).append("》：")
                        .append(item.getOrDefault("content", "")).append("\n");
            }
        }

        md.append("\n## V. 结论与对策建议 (Conclusion & Recommendations)\n\n");
        md.append("综上分析，本次指标异动具有显著的结构性与节点性特征。为防范潜在的业务连续性风险并优化数据观测模型，提出以下干预建议：\n\n");
        List<String> markdownSuggestions = suggestions.isEmpty()
                ? fallbackReportSuggestions(metricLabel, dimensionFields.stream().map(field -> labelOf(fieldLabels, field)).toList(), timeName, evidence, markers)
                : suggestions;
        for (String suggestion : markdownSuggestions) {
            md.append("- ").append(suggestion).append("\n");
        }
        return md.toString();
    }

    private List<Map<String, Object>> buildReasoningLogs(List<Map<String, Object>> rows,
                                                          List<Map<String, Object>> graphNodes,
                                                          List<Map<String, Object>> graphEdges,
                                                          List<Map<String, Object>> docEvidence,
                                                          Map<String, Object> aiResult,
                                                          String anomalyType,
                                                          Map<String, Object> graphRagRuntime) {
        List<Map<String, Object>> logs = new ArrayList<>(buildReasoningLogs(rows, graphNodes, graphEdges, docEvidence, aiResult, anomalyType));
        logs.add(Map.of(
                "step", 6,
                "title", "GraphRAG runtime",
                "status", Objects.toString(graphRagRuntime.getOrDefault("mode", "")),
                "detail", "mode=" + graphRagRuntime.getOrDefault("mode", "")
                        + ", graphNodes=" + graphRagRuntime.getOrDefault("graphNodeCount", 0)
                        + ", graphEdges=" + graphRagRuntime.getOrDefault("graphEdgeCount", 0)
                        + ", docEvidence=" + graphRagRuntime.getOrDefault("docEvidenceCount", 0)
        ));
        return logs;
    }

    private Map<String, Object> buildGraphRagRuntime(boolean graphRagAiUsed,
                                                     List<Map<String, Object>> rows,
                                                     List<Map<String, Object>> graphNodes,
                                                     List<Map<String, Object>> graphEdges,
                                                     List<Map<String, Object>> docEvidence) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("mode", graphRagAiUsed ? "PYTHON_GRAPHRAG" : "FALLBACK_DIAGNOSIS");
        runtime.put("queryRowCount", rows.size());
        runtime.put("graphNodeCount", graphNodes.size());
        runtime.put("graphEdgeCount", graphEdges.size());
        runtime.put("docEvidenceCount", docEvidence.size());
        runtime.put("neo4jEnabled", neo4jEnabled);
        runtime.put("completedAt", DATE_TIME_FORMATTER.format(Instant.now()));
        return runtime;
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
        binding.put("route", Objects.toString(request.getOrDefault("sourceRoute", "chat"), "chat"));
        binding.put("tableName", tableName);
        binding.put("sourceQuestion", aiResult.getOrDefault("sourceQuestion", ""));
        binding.put("sourceSql", aiResult.getOrDefault("sourceSql", ""));
        binding.put("chartSnapshot", aiResult.get("chartSnapshot"));
        binding.put("rawDataRows", aiResult.getOrDefault("rawDataRows", List.of()));
        binding.put("anomalyMarkers", aiResult.getOrDefault("anomalyMarkers", List.of()));
        binding.put("reasoningLogs", aiResult.getOrDefault("reasoningLogs", List.of()));
        binding.put("graphReasoningPath", aiResult.getOrDefault("graphReasoningPath", ""));
        binding.put("chartType", request.get("chartType"));
        binding.put("dashboardId", request.get("dashboardId"));
        binding.put("dashboardName", request.get("dashboardName"));
        binding.put("cardId", request.get("cardId"));
        binding.put("cardTitle", request.get("cardTitle"));
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
                                                       List<Map<String, Object>> rows, String metricField,
                                                       List<Map<String, Object>> anomalyMarkers) {
        Map<String, Object> snapshot = toStringKeyMap(requestedSnapshot);
        Map<String, String> fieldLabels = toStringMap(aiResult.get("fieldLabels"));
        if (hasSnapshotImage(snapshot)) {
            boolean forceRerender = Boolean.parseBoolean(Objects.toString(snapshot.remove("forceRerender"), "false"));
            boolean hadPhysicalTitle = Objects.toString(snapshot.getOrDefault("title", "")).matches("(?i).*col_\\d{3}.*");
            snapshot.putIfAbsent("anomalyMarkers", anomalyMarkers);
            relabelChartSnapshot(snapshot, fieldLabels);
            String renderTitle = Objects.toString(snapshot.getOrDefault("title", "诊断图表快照"));
            String renderSignature = snapshotRenderSignature(snapshot, fieldLabels);
            boolean needsContentRerender = forceRerender || hadPhysicalTitle || snapshotNeedsRerender(snapshot, fieldLabels);
            boolean needsLegacyServerRerender = !renderSignature.equals(Objects.toString(snapshot.getOrDefault("renderSignature", "")))
                    && shouldRefreshStoredSnapshotImage(snapshot);
            if (needsContentRerender || needsLegacyServerRerender) {
                snapshot.put("imageDataUrl", renderSnapshotImage(
                        renderTitle,
                        Objects.toString(snapshot.getOrDefault("chartType", "bar")),
                        castMapList(snapshot.getOrDefault("data", List.of()))));
                snapshot.put("renderedTitle", renderTitle);
                snapshot.put("renderSignature", renderSignature);
            }
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
        title = replacePhysicalFields(title, fieldLabels);

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
        generated.put("anomalyMarkers", anomalyMarkers);
        generated.put("source", "server-generated");
        generated.put("generatedAt", DATE_TIME_FORMATTER.format(Instant.now()));
        relabelChartSnapshot(generated, fieldLabels);
        String renderSignature = snapshotRenderSignature(generated, fieldLabels);
        generated.put("imageDataUrl", renderSnapshotImage(
                Objects.toString(generated.getOrDefault("title", title)),
                chartType,
                castMapList(generated.getOrDefault("data", chartData))));
        generated.put("renderedTitle", Objects.toString(generated.getOrDefault("title", title)));
        generated.put("renderSignature", renderSignature);
        return generated;
    }

    private boolean shouldRefreshStoredSnapshotImage(Map<String, Object> snapshot) {
        String source = Objects.toString(snapshot.getOrDefault("source", "")).trim();
        if ("server-generated".equalsIgnoreCase(source)) {
            return true;
        }
        String title = Objects.toString(snapshot.getOrDefault("title", ""));
        if (title.matches("(?i).*col_\\d{3}.*")) {
            return true;
        }
        return Objects.toString(snapshot.getOrDefault("renderedTitle", "")).matches("(?i).*col_\\d{3}.*");
    }

    private String snapshotRenderSignature(Map<String, Object> snapshot, Map<String, String> fieldLabels) {
        String title = Objects.toString(snapshot.getOrDefault("title", ""));
        String chartType = Objects.toString(snapshot.getOrDefault("chartType", ""));
        String labels = fieldLabels.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
        String data = castMapList(snapshot.getOrDefault("data", List.of())).stream()
                .limit(20)
                .map(item -> Objects.toString(item.getOrDefault("name", item.getOrDefault("label", ""))) + "="
                        + Objects.toString(item.getOrDefault("value", "")))
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
        return title + "::" + chartType + "::" + labels + "::" + data;
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

    private List<Map<String, Object>> buildAnomalyMarkers(List<Map<String, Object>> rows, String metricField,
                                                         List<String> dimensionFields, String timeField,
                                                         Map<String, Object> aiResult) {
        List<Double> values = rows.stream()
                .map(row -> toDouble(row.get(metricField)))
                .filter(value -> Math.abs(value) > 0.000001)
                .toList();
        if (values.isEmpty()) {
            return List.of();
        }
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(value -> Math.pow(value - avg, 2)).average().orElse(0);
        double std = Math.sqrt(variance);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(avg);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(avg);
        List<Map<String, Object>> markers = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> row : rows) {
            double value = toDouble(row.get(metricField));
            double deviation = std <= 0 ? Math.abs(value - avg) : Math.abs(value - avg) / std;
            boolean extreme = Math.abs(value - max) < 0.000001 || Math.abs(value - min) < 0.000001;
            if (!extreme && deviation < 1.5) {
                continue;
            }
            Map<String, Object> marker = new LinkedHashMap<>();
            String label = resolveAnomalyLabel(row, dimensionFields, timeField, index + 1);
            marker.put("key", "anomaly-" + index);
            marker.put("label", label);
            marker.put("metricField", metricField);
            marker.put("value", value);
            marker.put("valueLabel", metricField + " = " + compactNumber(value));
            marker.put("deviation", Math.round(deviation * 100.0) / 100.0);
            marker.put("reason", extreme ? "指标处于当前样本极值，已标记为异常节点" : "指标偏离均值 " + marker.get("deviation") + " 个标准差");
            marker.put("row", row);
            markers.add(marker);
            index++;
            if (markers.size() >= 6) {
                break;
            }
        }
        if (markers.isEmpty()) {
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("key", "anomaly-top");
            first.put("label", "最高波动点");
            first.put("metricField", metricField);
            first.put("value", max);
            first.put("valueLabel", metricField + " = " + compactNumber(max));
            first.put("reason", "当前样本未超过阈值，默认标注最高值用于回溯");
            first.put("row", rows.stream().filter(row -> Math.abs(toDouble(row.get(metricField)) - max) < 0.000001).findFirst().orElse(Map.of()));
            markers.add(first);
        }
        return markers;
    }

    private String resolveAnomalyLabel(Map<String, Object> row, List<String> dimensionFields, String timeField, int fallbackIndex) {
        if (timeField != null && !timeField.isBlank() && row.get(timeField) != null) {
            return Objects.toString(row.get(timeField));
        }
        for (String dimensionField : dimensionFields) {
            if (row.get(dimensionField) != null) {
                return Objects.toString(row.get(dimensionField));
            }
        }
        return "记录" + fallbackIndex;
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
            configureDocxPage(document);
            XWPFParagraph cover = document.createParagraph();
            cover.setAlignment(ParagraphAlignment.CENTER);
            cover.setSpacingAfter(220);
            XWPFRun coverRun = cover.createRun();
            coverRun.setBold(true);
            coverRun.setFontFamily("SimSun");
            coverRun.setFontSize(18);
            coverRun.setText(title == null || title.isBlank() ? "智能诊断报告" : title);

            XWPFParagraph subtitle = document.createParagraph();
            subtitle.setAlignment(ParagraphAlignment.CENTER);
            subtitle.setSpacingAfter(360);
            XWPFRun subtitleRun = subtitle.createRun();
            subtitleRun.setFontFamily("SimSun");
            subtitleRun.setFontSize(12);
            subtitleRun.setItalic(true);
            subtitleRun.setColor("333333");
            subtitleRun.setText("GraphRAG 多跳推理 | Neo4j 知识图谱 | 原始数据可回溯");

            for (ReportBlock block : parseReportBlocks(content)) {
                appendDocxBlock(document, block, false);
            }

            if (snapshotImage != null && snapshotImage.length > 0) {
                XWPFParagraph caption = document.createParagraph();
                caption.setSpacingBefore(180);
                XWPFRun captionRun = caption.createRun();
                captionRun.setBold(true);
                captionRun.setFontFamily("SimSun");
                captionRun.setFontSize(12);
                captionRun.setText("图表快照");

                XWPFParagraph imageParagraph = document.createParagraph();
                imageParagraph.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun imageRun = imageParagraph.createRun();
                imageRun.addPicture(
                        new ByteArrayInputStream(snapshotImage),
                        Document.PICTURE_TYPE_PNG,
                        "chart-snapshot.png",
                        Units.toEMU(390),
                        Units.toEMU(220)
                );
            }

            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Word 导出失败：" + e.getMessage());
        }
    }

    private byte[] buildAcademicDocx(Map<String, Object> report, byte[] snapshotImage, boolean includeReasoningLogs) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            configureDocxPage(document);
            Map<String, Object> data = mergedReportData(report);
            Map<String, Object> chartSnapshot = toStringKeyMap(data.get("chartSnapshot"));
            Map<String, String> fieldLabels = toStringMap(data.get("fieldLabels"));
            String tableName = Objects.toString(data.getOrDefault("tableName", report.getOrDefault("tableName", "biz_data")));
            String metricField = Objects.toString(data.getOrDefault("metricField", report.getOrDefault("metricField", "指标")));
            String metricLabel = readableFieldLabel(metricField, data.get("metricFieldLabel"), fieldLabels);
            String timeField = Objects.toString(data.getOrDefault("timeField", report.getOrDefault("timeField", "")));
            String timeLabel = readableFieldLabel(timeField, data.get("timeFieldLabel"), fieldLabels);
            if (timeLabel.isBlank()) {
                timeLabel = "日期";
            }
            List<String> dimensionFields = castStringList(data.getOrDefault("dimensionFields", report.getOrDefault("dimensionFields", List.of())));
            List<String> dimensionLabels = resolveDimensionLabels(data, dimensionFields, fieldLabels);
            Map<String, Object> stats = toStringKeyMap(data.get("statistics"));
            List<Map<String, Object>> rows = castMapList(data.getOrDefault("rawDataRows", data.getOrDefault("queryRows", List.of())));
            List<Map<String, Object>> markers = castMapList(data.getOrDefault("anomalyMarkers", List.of()));
            List<Map<String, Object>> rootCauses = castMapList(data.getOrDefault("rootCauses", List.of()));
            List<Map<String, Object>> evidence = distinctEvidence(castMapList(data.getOrDefault("docEvidence", List.of())));
            List<String> suggestions = castStringList(data.getOrDefault("suggestions", List.of()));
            List<Map<String, Object>> graphNodes = castMapList(toStringKeyMap(data.get("graphPath")).getOrDefault("nodes", List.of()));
            List<Map<String, Object>> graphEdges = castMapList(data.getOrDefault("graphEdges", toStringKeyMap(data.get("graphPath")).getOrDefault("edges", List.of())));
            String rootCauseName = primaryRootCauseName(rootCauses);
            String rootCauseConclusion = rootCauseConclusion(rootCauses);
            String dimensionChain = dimensionLabels.isEmpty() ? "未选择维度字段" : String.join("/", dimensionLabels);
            String confidenceBands = confidenceBandText(rootCauses);
            String createdAt = Objects.toString(report.getOrDefault("createdAt", DATE_TIME_FORMATTER.format(Instant.now())));
            if (createdAt.length() > 10) {
                createdAt = createdAt.substring(0, 10);
            }
            String count = Objects.toString(stats.getOrDefault("count", rows.size()));

            appendDocType(document, "Diagnostic Analysis Report | Insight Spark System");
            appendDocTitle(document, "基于 GraphRAG 的多跳关联推理与业务指标异常归因分析");
            appendSubtitle(document, "—— 以数据集 " + tableName + " " + metricLabel + "指标为例");
            appendAuthors(document, "自动生成环境: 智能诊断引擎 (Build: 2026.05)\n诊断时间: " + createdAt);
            appendHeaderDivider(document);

            appendAbstract(document, "Abstract / 诊断摘要：", "本次分析围绕核心业务指标「" + metricLabel + "」展开。系统在有效观测区间内提取了 "
                    + count + " 条样本记录进行异常扫描。统计结果显示，样本总计数值为 " + formatReportNumber(stats.get("total"))
                    + "，均值 (μ) 为 " + formatReportNumber(stats.get("avg"))
                    + "，区间极值分别为 Max = " + formatReportNumber(stats.get("max"))
                    + " 与 Min = " + formatReportNumber(stats.get("min")) + "。通过统计算法，系统识别出 "
                    + markers.size() + " 个具备统计学显著性的异常节点。为探究异常机制，系统引入 GraphRAG（Graph Retrieval-Augmented Generation）技术，融合业务维度拆解、时序窗口回溯与 Neo4j 知识图谱（涉及 "
                    + graphNodes.size() + " 个节点与 " + graphEdges.size() + " 条边），" + rootCauseConclusion
                    + "本文档详细记录了数据特征、多跳推理路径及多维度异质性分析结果。");

            appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "I. 描述性统计与异常检测 (Statistical Characteristics)"), false);
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "针对数据集 " + tableName + " 中的目标变量「" + metricLabel
                    + "」，系统执行了基准扫描。有效样本量 N = " + count + "。基于分布特征，系统标定了 "
                    + markers.size() + " 个显著偏离常态分布区间的异常观测点。具体检测结果如表 I 所示。"), false);
            appendDocxTable(document, ReportBlock.table(anomalyDocxRows(markers, stats, timeField, timeLabel, metricField, metricLabel)));
            appendCaption(document, "表 I. 核心指标异常节点识别清单");

            appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "II. 图谱知识检索与多跳推理链路 (GraphRAG Reasoning)"), false);
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "为克服单一数据视角的局限性，本次诊断未仅停留在字段级的相关性分析，而是构建了基于 GraphRAG 的因果推理拓扑。推理链路严格遵循以下演进次序：数据表关联 -> 指标层映射 -> 业务维度空间拆解（"
                    + dimensionChain + "） -> 时序变量回溯（" + timeLabel + "）。"), false);
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "在图计算阶段，系统调用 Neo4j 图数据库，遍历 "
                    + graphNodes.size() + " 个关联实体节点与 " + graphEdges.size() + " 条语义关系边，" + rootCauseConclusion), false);
            appendEvidenceBlock(document, evidence.isEmpty()
                    ? "检索证据缺失声明 (Corpus Absence Note)：在 RAG 检索阶段，未命中可引用的企业内部复盘文档或外部行业研报。当前得出的根因结论高度依赖于底层统计波动特征与图谱结构的内生字段关系。建议管理层后续向知识库补充非结构化业务说明，以提升归因模型的鲁棒性。"
                    : "检索证据摘要 (Corpus Evidence Note)：在 RAG 检索阶段，命中 " + evidence.size() + " 条企业内部复盘文档或外部行业研报。代表性证据包括：" + evidencePreview(evidence) + "。");

            appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "III. 归因定位与置信度评估 (Attribution Analysis)"), false);
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "基于上述多跳推理逻辑，系统对诱发指标波动的潜在因素进行了权重分配与显著性评估。当前根因结论覆盖" + confidenceBands + "置信区间："), false);
            if (rootCauses.isEmpty()) {
                appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM, "[置信度: 0.45 / LOW] 证据不足：主要影响对象为「" + metricLabel + "」。当前样本未形成高置信度根因，建议补充业务维度、时间窗口与知识文档后重新诊断。"), false);
            } else {
                for (Map<String, Object> cause : rootCauses) {
                    appendCauseBlock(document, cause, metricLabel);
                }
            }

            appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "IV. 多维度异质性分析 (Multidimensional Heterogeneity)"), false);
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "为进一步剥离异常值的结构来源，本节对"
                    + (dimensionLabels.isEmpty() ? "核心业务维度" : dimensionLabels.size() + " 大核心维度（" + String.join("、", dimensionLabels) + "）")
                    + "进行了下钻与贡献度拆解。表 II 优先展示异常节点子集贡献；当异常节点明细不足时，回退展示全样本贡献分布。"), false);
            appendDocxTable(document, ReportBlock.table(dimensionDocxRows(data, rows, metricField, metricLabel, dimensionFields, dimensionLabels, fieldLabels)));
            appendCaption(document, "表 II. 核心业务维度贡献与相对比重拆解（优先异常节点口径）");

            appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "V. 结论与对策建议 (Conclusion & Recommendations)"), false);
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "综上分析，本次指标异动具有显著的结构性与节点性特征。为防范潜在的业务连续性风险并优化数据观测模型，提出以下干预建议："), false);
            if (suggestions.isEmpty()) {
                for (String suggestion : fallbackReportSuggestions(metricLabel, dimensionLabels, timeLabel, evidence, markers)) {
                    appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM, suggestion), false);
                }
            } else {
                for (String suggestion : suggestions) {
                    appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM, suggestion), false);
                }
            }

            boolean snapshotIncluded = snapshotImage != null && snapshotImage.length > 0;
            if (snapshotIncluded) {
                appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "VI. 图表快照 (Chart Snapshot)"), false);
                appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, buildSnapshotNarrative(chartSnapshot, markers, rows)), false);
                XWPFParagraph imageParagraph = document.createParagraph();
                imageParagraph.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun imageRun = imageParagraph.createRun();
                imageRun.addPicture(new ByteArrayInputStream(snapshotImage), Document.PICTURE_TYPE_PNG, "chart-snapshot.png", Units.toEMU(390), Units.toEMU(220));
            }

            if (includeReasoningLogs) {
                List<Map<String, Object>> reasoningLogs = castMapList(data.getOrDefault("reasoningLogs", List.of()));
                if (!reasoningLogs.isEmpty()) {
                    String sectionNo = snapshotIncluded ? "VII" : "VI";
                    appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, sectionNo + ". GraphRAG 推理日志 (Reasoning Logs)"), false);
                    appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "本节记录诊断引擎从字段映射、异常扫描、知识检索、多跳推理到根因输出的关键执行过程，用于回溯报告生成依据与模型判断路径。"), false);
                    appendDocxTable(document, ReportBlock.table(reasoningLogDocxRows(reasoningLogs)));
                    appendCaption(document, "表 " + sectionNo + ". GraphRAG 推理过程日志");
                }
            }

            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Word 导出失败：" + e.getMessage());
        }
    }

    private void configureDocxPage(XWPFDocument document) {
        var body = document.getDocument().getBody();
        var sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        var pageSize = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(11906));
        pageSize.setH(BigInteger.valueOf(16838));
        var margin = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1440));
        margin.setBottom(BigInteger.valueOf(1440));
        margin.setLeft(BigInteger.valueOf(1803));
        margin.setRight(BigInteger.valueOf(1803));
        margin.setHeader(BigInteger.valueOf(720));
        margin.setFooter(BigInteger.valueOf(720));
        margin.setGutter(BigInteger.ZERO);
    }

    private Map<String, Object> mergedReportData(Map<String, Object> report) {
        Map<String, Object> data = new LinkedHashMap<>(toStringKeyMap(report.get("resultJson")));
        report.forEach(data::putIfAbsent);
        data.put("tableName", report.getOrDefault("tableName", data.get("tableName")));
        data.put("metricField", report.getOrDefault("metricField", data.get("metricField")));
        data.put("dimensionFields", report.getOrDefault("dimensionFields", data.get("dimensionFields")));
        data.put("timeField", report.getOrDefault("timeField", data.get("timeField")));
        data.put("chartSnapshot", data.getOrDefault("chartSnapshot", report.get("chartSnapshot")));
        return data;
    }

    private Map<String, String> toStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : toStringKeyMap(value).entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            String label = Objects.toString(entry.getValue(), "").trim();
            if (!key.isBlank() && !label.isBlank()) {
                result.put(key, label);
            }
        }
        return result;
    }

    private String mappedFieldName(String value, Map<String, String> fieldLabels) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return text;
        }
        String direct = fieldLabels.getOrDefault(text, "").trim();
        return direct.isBlank() ? text : direct;
    }

    private String readableFieldLabel(String field, Object explicitLabel, Map<String, String> fieldLabels) {
        String label = Objects.toString(explicitLabel, "").trim();
        if (!label.isBlank() && !looksPhysicalField(label)) {
            return label;
        }
        String mapped = fieldLabels.getOrDefault(Objects.toString(field, ""), "").trim();
        if (!mapped.isBlank()) {
            return mapped;
        }
        return Objects.toString(field, "").trim();
    }

    private boolean looksPhysicalField(String value) {
        return Objects.toString(value, "").matches("(?i)^col_\\d{3}$|^sys_id$");
    }

    private List<String> resolveDimensionLabels(Map<String, Object> data, List<String> dimensionFields, Map<String, String> fieldLabels) {
        List<String> explicit = castStringList(data.getOrDefault("dimensionFieldLabels", List.of()));
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < dimensionFields.size(); index++) {
            String field = dimensionFields.get(index);
            Object explicitLabel = index < explicit.size() ? explicit.get(index) : "";
            String label = readableFieldLabel(field, explicitLabel, fieldLabels);
            labels.add(label.isBlank() ? field : label);
        }
        if (labels.isEmpty()) {
            labels.addAll(explicit.stream().filter(item -> !item.isBlank()).toList());
        }
        return labels;
    }

    private List<Map<String, Object>> distinctEvidence(List<Map<String, Object>> evidence) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> item : evidence) {
            String label = Objects.toString(item.getOrDefault("label", item.getOrDefault("source", ""))).trim();
            String content = previewText(item.getOrDefault("content", item.getOrDefault("text", "")));
            String key = (label + "|" + evidenceFingerprint(content)).replaceAll("\\s+", " ");
            unique.putIfAbsent(key, item);
        }
        return new ArrayList<>(unique.values());
    }

    private String evidenceFingerprint(String content) {
        String normalized = Objects.toString(content, "")
                .replaceAll("\\s+", "")
                .replaceAll("[，。；：、,.!！?？#\\-_*`~\\[\\]()（）【】《》<>]", "");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String primaryRootCauseName(List<Map<String, Object>> rootCauses) {
        if (rootCauses == null || rootCauses.isEmpty()) {
            return "证据不足，暂未形成单一收敛根因";
        }
        return Objects.toString(rootCauses.get(0).getOrDefault("causeType", "根因假设")).trim();
    }

    private String rootCauseConclusion(List<Map<String, Object>> rootCauses) {
        if (rootCauses == null || rootCauses.isEmpty()) {
            return "当前未形成单一收敛根因，系统将结论标定为「证据不足」。";
        }
        return "最终将核心根因指向「" + primaryRootCauseName(rootCauses) + "」。";
    }

    private String confidenceBandText(List<Map<String, Object>> rootCauses) {
        if (rootCauses == null || rootCauses.isEmpty()) {
            return "低（Low）";
        }
        List<String> levels = new ArrayList<>();
        for (Map<String, Object> cause : rootCauses) {
            String level = Objects.toString(cause.getOrDefault("level", "")).trim().toUpperCase();
            if (!level.isBlank() && !levels.contains(level)) {
                levels.add(level);
            }
        }
        if (levels.isEmpty()) {
            return "中（Medium）";
        }
        return levels.stream()
                .map(level -> switch (level) {
                    case "HIGH" -> "高（High）";
                    case "LOW" -> "低（Low）";
                    default -> "中（Medium）";
                })
                .reduce((a, b) -> a + "、" + b)
                .orElse("中（Medium）");
    }

    private List<String> fallbackReportSuggestions(String metricLabel,
                                                   List<String> dimensionLabels,
                                                   String timeLabel,
                                                   List<Map<String, Object>> evidence,
                                                   List<Map<String, Object>> markers) {
        List<String> suggestions = new ArrayList<>();
        if (!markers.isEmpty()) {
            suggestions.add("复核异常节点对应的原始记录，确认「" + metricLabel + "」波动是否来自真实业务事件、统计口径变化或数据采集异常。");
        } else {
            suggestions.add("补充更长观测窗口或更高频明细数据后重新扫描「" + metricLabel + "」，避免样本不足导致异常判断不稳定。");
        }
        if (dimensionLabels != null && !dimensionLabels.isEmpty()) {
            suggestions.add("围绕「" + String.join("、", dimensionLabels.stream().limit(3).toList()) + "」继续下钻到明细对象，验证头部贡献项是否集中放大指标波动。");
        } else {
            suggestions.add("补充可解释「" + metricLabel + "」变化的业务维度字段，用于生成可归因的贡献拆解。");
        }
        if (timeLabel != null && !timeLabel.isBlank()) {
            suggestions.add("以「" + timeLabel + "」为轴对异常节点前后相邻窗口做对比，判断波动是短期脉冲还是趋势变化。");
        }
        if (evidence == null || evidence.isEmpty()) {
            suggestions.add("上传企业复盘文档或行业研报并重新纳入 GraphRAG，以提升根因结论的外部证据支撑。");
        }
        return suggestions.stream().distinct().limit(4).toList();
    }

    private void appendDocType(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(180);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Arial");
        run.setFontSize(8);
        run.setCharacterSpacing(20);
        run.setText(text);
    }

    private void appendDocTitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(100);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("SimSun");
        run.setFontSize(16);
        run.setText(text);
    }

    private void appendSubtitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(220);
        XWPFRun run = paragraph.createRun();
        run.setItalic(true);
        run.setFontFamily("SimSun");
        run.setFontSize(10);
        run.setText(text);
    }

    private void appendAuthors(XWPFDocument document, String text) {
        for (String line : text.split("\\R")) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            paragraph.setSpacingAfter(0);
            XWPFRun run = paragraph.createRun();
            run.setFontFamily("Arial");
            run.setFontSize(9);
            run.setText(line);
        }
        XWPFParagraph spacer = document.createParagraph();
        spacer.setSpacingAfter(220);
    }

    private void appendHeaderDivider(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setBorderBottom(Borders.SINGLE);
        paragraph.setSpacingAfter(420);
        paragraph.createRun().setText("");
    }

    private void appendAbstract(XWPFDocument document, String label, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(360);
        paragraph.setIndentationRight(360);
        paragraph.setSpacingAfter(360);
        paragraph.setSpacingBetween(1.25);
        XWPFRun titleRun = paragraph.createRun();
        titleRun.setBold(true);
        titleRun.setFontFamily("Arial");
        titleRun.setFontSize(9);
        titleRun.setText(label);
        XWPFRun bodyRun = paragraph.createRun();
        bodyRun.setFontFamily("SimSun");
        bodyRun.setFontSize(9);
        bodyRun.setText(text);
    }

    private void appendEvidenceBlock(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setBorderLeft(Borders.SINGLE);
        paragraph.setIndentationLeft(240);
        paragraph.setSpacingBefore(120);
        paragraph.setSpacingAfter(180);
        paragraph.setSpacingBetween(1.15);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("SimSun");
        run.setFontSize(9);
        run.setColor("333333");
        run.setText(text);
    }

    private void appendCauseBlock(XWPFDocument document, Map<String, Object> cause, String metricLabel) {
        String confidence = formatConfidence(cause.get("confidence"));
        String level = Objects.toString(cause.getOrDefault("level", "MEDIUM"));
        String causeType = Objects.toString(cause.getOrDefault("causeType", "根因假设"));
        String impactField = Objects.toString(cause.getOrDefault("impactField", metricLabel));
        String evidence = Objects.toString(cause.getOrDefault("evidence", "")).trim();
        String action = Objects.toString(cause.getOrDefault("action", "")).trim();
        appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM,
                "[置信度: " + confidence + " / " + level + "] " + causeType + "：主要影响对象为「" + impactField + "」。"), false);
        if (!evidence.isBlank()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "关键证据：" + evidence), false);
        }
        if (!action.isBlank()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "建议动作：" + action), false);
        }
    }

    private String evidencePreview(List<Map<String, Object>> evidence) {
        return evidence.stream()
                .limit(2)
                .map(item -> "《" + Objects.toString(item.getOrDefault("label", item.getOrDefault("source", "知识文档"))) + "》" + previewText(item.getOrDefault("content", item.getOrDefault("text", ""))))
                .reduce((a, b) -> a + "；" + b)
                .orElse("知识文档命中片段");
    }

    private String buildSnapshotNarrative(Map<String, Object> snapshot, List<Map<String, Object>> markers, List<Map<String, Object>> rows) {
        String title = Objects.toString(snapshot.getOrDefault("title", "诊断图表快照"));
        String chartType = chartTypeLabel(Objects.toString(snapshot.getOrDefault("chartType", "chart")));
        String source = Objects.toString(snapshot.getOrDefault("source", snapshot.getOrDefault("sourceRoute", "")));
        String sourceLabel = source.isBlank() ? "诊断生成过程" : ("server-generated".equals(source) ? "后端自动生成" : source);
        int dataPointCount = castMapList(snapshot.getOrDefault("data", List.of())).size();
        if (dataPointCount == 0) {
            dataPointCount = rows.size();
        }
        return "图表快照用于固定本次诊断生成时的原始图表状态。当前快照「" + title + "」为" + chartType
                + "，来源为" + sourceLabel + "，包含 " + dataPointCount + " 个数据点，并标注 "
                + markers.size() + " 个异常节点，可用于后续回溯诊断结论所依据的图表现场。";
    }

    private String chartTypeLabel(String chartType) {
        return switch (Objects.toString(chartType, "").toLowerCase()) {
            case "bar" -> "柱状图";
            case "line" -> "折线图";
            case "pie" -> "饼图";
            case "scatter" -> "散点图";
            default -> chartType == null || chartType.isBlank() ? "图表" : chartType;
        };
    }

    private List<List<String>> anomalyDocxRows(List<Map<String, Object>> markers, Map<String, Object> stats,
                                               String timeField, String timeLabel, String metricField, String metricLabel) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("观测日期 (Time Window)", "指标数值 (Value)", "偏离度 / 统计检验量", "异常标定类型"));
        if (markers.isEmpty()) {
            rows.add(List.of("-", "-", "未发现 Z-Score 绝对值超过阈值的节点", "Normal Observation"));
            return rows;
        }
        double avg = toDouble(stats.get("avg"));
        String stdValue = formatReportNumber(stats.get("std"));
        for (Map<String, Object> marker : markers.stream().limit(5).toList()) {
            Map<String, Object> markerRow = toStringKeyMap(marker.get("row"));
            String window = reportObservationWindow(marker, markerRow, timeField, Map.of(timeField, timeLabel));
            if ("异常点".equals(window) || window.isBlank()) {
                window = Objects.toString(marker.getOrDefault("label", "-"));
            }
            double value = reportMarkerValue(marker, markerRow, metricLabel);
            if (value == 0) {
                value = reportMarkerValue(marker, markerRow, metricField);
            }
            rows.add(List.of(window, formatReportNumber(value), Objects.toString(marker.getOrDefault("reason", reportDeviationText(value, avg, stdValue))), reportOutlierType(value, avg)));
        }
        return rows;
    }

    private List<List<String>> dimensionDocxRows(Map<String, Object> data, List<Map<String, Object>> rows,
                                                 String metricField, String metricLabel,
                                                 List<String> dimensionFields, List<String> dimensionLabels,
                                                 Map<String, String> fieldLabels) {
        List<List<String>> tableRows = new ArrayList<>();
        tableRows.add(List.of("分析口径 (Scope)", "一阶维度 (Dimension)", "二阶因子 (Factor)", "贡献值 (Value)", "口径内占比 (Ratio)"));
        List<Map<String, Object>> contributions = castMapList(data.getOrDefault("anomalyDimensionContributions", List.of()));
        if (contributions.isEmpty()) {
            contributions = castMapList(data.getOrDefault("dimensionContributions", List.of()));
        }
        if (!contributions.isEmpty()) {
            for (Map<String, Object> contribution : contributions.stream().limit(3).toList()) {
                String dimensionField = Objects.toString(contribution.getOrDefault("dimensionField", contribution.getOrDefault("dimension", "")));
                String dimension = readableFieldLabel(dimensionField, contribution.getOrDefault("dimensionLabel", contribution.getOrDefault("dimension", "")), fieldLabels);
                if (dimension.isBlank()) {
                    dimension = "业务维度";
                }
                String scope = Objects.toString(contribution.getOrDefault("scope", "全样本"));
                for (Map<String, Object> item : castMapList(contribution.getOrDefault("topItems", List.of())).stream().limit(4).toList()) {
                    tableRows.add(List.of(scope, dimension, mappedFieldName(Objects.toString(item.getOrDefault("name", item.getOrDefault("label", "-"))), fieldLabels), formatReportNumber(item.get("value")), formatPercentValue(item.get("share"))));
                }
            }
        } else {
            for (int index = 0; index < dimensionFields.size(); index++) {
                String dimension = dimensionFields.get(index);
                String dimensionLabel = index < dimensionLabels.size() ? dimensionLabels.get(index) : dimension;
                Map<String, Double> contribution = aggregateByDimension(rows, metricLabel, dimensionLabel);
                if (contribution.isEmpty()) {
                    contribution = aggregateByDimension(rows, metricField, dimension);
                }
                double total = contribution.values().stream().mapToDouble(Double::doubleValue).sum();
                contribution.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .limit(4)
                        .forEach(entry -> tableRows.add(List.of("全样本", dimensionLabel, entry.getKey(), formatReportNumber(entry.getValue()), total == 0 ? "0.0%" : String.format("%.1f%%", entry.getValue() / total * 100))));
            }
        }
        if (tableRows.size() == 1) {
            tableRows.add(List.of("未选择维度", "当前报告未提供可拆解维度", "-", "-", "-"));
        }
        return tableRows;
    }

    private List<List<String>> reasoningLogDocxRows(List<Map<String, Object>> reasoningLogs) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("步骤 (Step)", "环节 (Stage)", "状态 (Status)", "过程说明 (Detail)"));
        int fallbackStep = 1;
        for (Map<String, Object> log : reasoningLogs.stream().limit(12).toList()) {
            String step = Objects.toString(log.getOrDefault("step", fallbackStep++), "");
            String title = Objects.toString(log.getOrDefault("title", log.getOrDefault("stage", "推理步骤")), "");
            String status = Objects.toString(log.getOrDefault("status", "completed"), "");
            String detail = Objects.toString(log.getOrDefault("detail", log.getOrDefault("message", "")), "");
            rows.add(List.of(step, title, status, detail));
        }
        if (rows.size() == 1) {
            rows.add(List.of("-", "暂无推理日志", "-", "当前报告未绑定可导出的推理过程日志"));
        }
        return rows;
    }

    private String formatPercentValue(Object value) {
        double number = toDouble(value);
        if (Math.abs(number) > 0 && Math.abs(number) <= 1) {
            number *= 100;
        }
        return String.format("%.1f%%", number);
    }

    private void appendDocxBlock(XWPFDocument document, ReportBlock block, boolean inTable) {
        if (!inTable && block.type() == ReportBlockType.TABLE) {
            appendDocxTable(document, block);
            return;
        }
        if (!inTable && block.type() == ReportBlockType.CAPTION) {
            appendCaption(document, block.text());
            return;
        }
        String line = block.text();
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(block.type() == ReportBlockType.BLANK ? 40 : 90);
        paragraph.setSpacingBetween(1.25);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("SimSun");
        run.setFontSize(11);
        if (block.type() == ReportBlockType.H1) {
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            paragraph.setSpacingAfter(180);
            run.setBold(true);
            run.setFontSize(16);
            run.setColor("000000");
            run.setText(line);
        } else if (block.type() == ReportBlockType.H2) {
            paragraph.setSpacingBefore(180);
            paragraph.setSpacingAfter(90);
            run.setBold(true);
            run.setFontFamily("Arial");
            run.setFontSize(12);
            run.setColor("000000");
            run.setText(line);
        } else if (block.type() == ReportBlockType.H3) {
            run.setBold(true);
            run.setFontSize(12);
            run.setColor("000000");
            run.setText(line);
        } else if (block.type() == ReportBlockType.LIST_ITEM) {
            paragraph.setIndentationLeft(360);
            paragraph.setIndentationHanging(180);
            run.setText("• " + line);
        } else if (block.type() == ReportBlockType.BLANK) {
            run.setText("");
        } else {
            paragraph.setFirstLineIndent(440);
            run.setText(line);
        }
    }

    private void appendCaption(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(180);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("SimSun");
        run.setFontSize(10);
        run.setText(text);
    }

    private void appendDocxTable(XWPFDocument document, ReportBlock block) {
        List<List<String>> rows = block.tableRows();
        if (rows.isEmpty()) {
            return;
        }
        XWPFTable table = document.createTable(rows.size(), Math.max(1, rows.get(0).size()));
        table.setTableAlignment(TableRowAlign.CENTER);
        table.setWidth("100%");
        var tblPr = table.getCTTbl().getTblPr();
        var tblW = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblW.setW(BigInteger.valueOf(8300));
        tblW.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth.DXA);
        var borders = tblPr.isSetTblBorders() ? tblPr.getTblBorders() : tblPr.addNewTblBorders();
        borders.addNewTop().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.SINGLE);
        borders.getTop().setSz(BigInteger.valueOf(16));
        borders.addNewBottom().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.SINGLE);
        borders.getBottom().setSz(BigInteger.valueOf(16));
        borders.addNewInsideH().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.DASHED);
        borders.getInsideH().setSz(BigInteger.valueOf(4));
        borders.addNewInsideV().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.NONE);
        borders.addNewLeft().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.NONE);
        borders.addNewRight().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.NONE);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XWPFTableRow tableRow = table.getRow(rowIndex);
            List<String> row = rows.get(rowIndex);
            for (int colIndex = 0; colIndex < row.size(); colIndex++) {
                XWPFTableCell cell = tableRow.getCell(colIndex);
                if (cell == null) {
                    cell = tableRow.addNewTableCell();
                }
                cell.setText("");
                XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
                paragraph.setAlignment(ParagraphAlignment.CENTER);
                paragraph.setSpacingAfter(0);
                XWPFRun run = paragraph.createRun();
                run.setFontFamily("SimSun");
                run.setFontSize(10);
                run.setBold(rowIndex == 0);
                run.setText(row.get(colIndex));
            }
        }
    }

    private void putZip(ZipOutputStream zip, String path, String content) throws java.io.IOException {

        zip.putNextEntry(new ZipEntry(path));

        zip.write(content.stripLeading().getBytes(StandardCharsets.UTF_8));

        zip.closeEntry();

    }



    private byte[] buildPdf(String content, boolean encrypted, byte[] snapshotImage) {
        try (PDDocument document = new PDDocument()) {
            List<ReportBlock> blocks = parseReportBlocks(content);
            PDFont font = loadPdfFont(document);
            PDFont boldFont = font;
            PDPage coverPage = new PDPage(PDRectangle.A4);
            document.addPage(coverPage);
            try (PDPageContentStream coverStream = new PDPageContentStream(document, coverPage)) {
                coverStream.beginText();
                coverStream.setFont(font, 22);
                coverStream.newLineAtOffset(60, coverPage.getMediaBox().getHeight() - 140);
                coverStream.showText("智能诊断报告");
                coverStream.setFont(font, 12);
                coverStream.newLineAtOffset(0, -36);
                coverStream.showText("GraphRAG 多跳推理 | Neo4j 知识图谱 | 原始数据可回溯");
                coverStream.newLineAtOffset(0, -40);
                coverStream.showText("生成时间：" + DATE_TIME_FORMATTER.format(Instant.now()));
                coverStream.endText();
            }
            PDPage page = null;
            PDPageContentStream stream = null;
            float margin = 48;
            float y = 0;
            int pageNumber = 1;
            for (ReportBlock block : blocks) {
                List<String> lines = wrapPdfText(block.text(), block.type() == ReportBlockType.LIST_ITEM ? 70 : 78);
                if (block.type() == ReportBlockType.BLANK) {
                    lines = List.of("");
                }
                for (String line : lines) {
                    float fontSize = pdfFontSize(block.type());
                    float leading = pdfLeading(block.type());
                    if (page == null || y < margin + leading) {
                        if (stream != null) {
                            stream.endText();
                            stream.beginText();
                            stream.setFont(font, 8);
                            stream.newLineAtOffset(margin, 28);
                            stream.showText("Insight Spark 智能诊断报告 | Page " + pageNumber);
                            stream.endText();
                            stream.close();
                            pageNumber++;
                        }
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        stream.beginText();
                        y = page.getMediaBox().getHeight() - margin;
                        stream.newLineAtOffset(margin, y);
                    }
                    stream.setFont(block.type() == ReportBlockType.H1 || block.type() == ReportBlockType.H2 || block.type() == ReportBlockType.H3 ? boldFont : font, fontSize);
                    String prefix = block.type() == ReportBlockType.LIST_ITEM ? "• " : "";
                    String safeLine = toPdfSafeText(prefix + line);
                    stream.showText(safeLine);
                    stream.newLineAtOffset(0, -leading);
                    y -= leading;
                }
                if (block.type() == ReportBlockType.H1 || block.type() == ReportBlockType.H2 || block.type() == ReportBlockType.H3) {
                    stream.newLineAtOffset(0, -5);
                    y -= 5;
                }
            }
            if (stream != null) {
                stream.endText();
                stream.beginText();
                stream.setFont(font, 8);
                stream.newLineAtOffset(margin, 28);
                stream.showText("Insight Spark 智能诊断报告 | Page " + pageNumber);
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

    private List<String> wrapPdfText(String text, int width) {
        String line = Objects.toString(text, "");
        if (line.isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < line.length(); i += width) {
            lines.add(line.substring(i, Math.min(line.length(), i + width)));
        }
        return lines;
    }

    private float pdfFontSize(ReportBlockType type) {
        return switch (type) {
            case H1 -> 16f;
            case H2 -> 13f;
            case H3 -> 11.5f;
            case BLANK -> 8f;
            default -> 10f;
        };
    }

    private float pdfLeading(ReportBlockType type) {
        return switch (type) {
            case H1 -> 24f;
            case H2 -> 20f;
            case H3 -> 17f;
            case BLANK -> 8f;
            default -> 15f;
        };
    }

    private List<ReportBlock> parseReportBlocks(String markdown) {
        List<ReportBlock> blocks = new ArrayList<>();
        String[] lines = Objects.toString(markdown, "").split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String rawLine = lines[index];
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                blocks.add(new ReportBlock(ReportBlockType.BLANK, ""));
            } else if (isMarkdownTableStart(lines, index)) {
                List<List<String>> tableRows = new ArrayList<>();
                tableRows.add(parseMarkdownTableRow(lines[index]));
                index += 2;
                while (index < lines.length && lines[index] != null && lines[index].trim().matches("^\\|.*\\|\\s*$")) {
                    tableRows.add(parseMarkdownTableRow(lines[index]));
                    index++;
                }
                index--;
                blocks.add(ReportBlock.table(tableRows));
            } else if (line.startsWith("### ")) {
                blocks.add(new ReportBlock(ReportBlockType.H3, cleanMarkdownInline(line.substring(4))));
            } else if (line.startsWith("## ")) {
                blocks.add(new ReportBlock(ReportBlockType.H2, cleanMarkdownInline(line.substring(3))));
            } else if (line.startsWith("# ")) {
                blocks.add(new ReportBlock(ReportBlockType.H1, cleanMarkdownInline(line.substring(2))));
            } else if (line.matches("^[-*]\\s+.*")) {
                blocks.add(new ReportBlock(ReportBlockType.LIST_ITEM, cleanMarkdownInline(line.replaceFirst("^[-*]\\s+", ""))));
            } else if (line.matches("^\\*[^*].*表\\s*[IVXLC]+.*\\*$")) {
                blocks.add(new ReportBlock(ReportBlockType.CAPTION, cleanMarkdownInline(line.replaceAll("^\\*", "").replaceAll("\\*$", ""))));
            } else {
                blocks.add(new ReportBlock(ReportBlockType.PARAGRAPH, cleanMarkdownInline(line)));
            }
        }
        return blocks;
    }

    private boolean isMarkdownTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length) {
            return false;
        }
        String current = Objects.toString(lines[index], "").trim();
        String next = Objects.toString(lines[index + 1], "").trim();
        return current.matches("^\\|.*\\|$")
                && next.matches("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$");
    }

    private List<String> parseMarkdownTableRow(String line) {
        String normalized = Objects.toString(line, "").trim()
                .replaceAll("^\\|", "")
                .replaceAll("\\|$", "");
        List<String> cells = new ArrayList<>();
        for (String cell : normalized.split("\\|", -1)) {
            cells.add(cleanMarkdownInline(cell.trim()));
        }
        return cells;
    }

    private String cleanMarkdownInline(String value) {
        String text = Objects.toString(value, "");
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("__(.*?)__", "$1");
        text = text.replaceAll("`([^`]+)`", "$1");
        Matcher matcher = Pattern.compile("\\[([^]]+)]\\([^)]+\\)").matcher(text);
        return matcher.replaceAll("$1");
    }

    private enum ReportBlockType {
        H1, H2, H3, LIST_ITEM, PARAGRAPH, BLANK, TABLE, CAPTION
    }

    private record ReportBlock(ReportBlockType type, String text, List<List<String>> tableRows) {
        private ReportBlock(ReportBlockType type, String text) {
            this(type, text, List.of());
        }

        private static ReportBlock table(List<List<String>> tableRows) {
            return new ReportBlock(ReportBlockType.TABLE, "", tableRows);
        }
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

