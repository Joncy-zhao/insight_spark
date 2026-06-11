package com.insightspark.service;



import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;
import org.apache.pdfbox.io.MemoryUsageSetting;
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
import java.awt.GraphicsEnvironment;
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
import java.util.LinkedHashSet;
import java.util.Set;

import java.util.concurrent.ThreadLocalRandom;

import java.util.function.Consumer;

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

        return runDiagnosis(request, progress -> {
        });

    }

    public Map<String, Object> runDiagnosis(Map<String, Object> request, Consumer<Map<String, Object>> progressConsumer) {

        emitDiagnosisProgress(progressConsumer, 8, "\u4efb\u52a1\u521b\u5efa", 1, "\u5df2\u63a5\u6536\u8bca\u65ad\u8bf7\u6c42\uff0c\u5f00\u59cb\u6821\u9a8c\u6570\u636e\u8868\u3001\u6307\u6807\u5b57\u6bb5\u548c\u62a5\u544a\u53c2\u6570\u3002", "running");

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

        emitDiagnosisProgress(progressConsumer, 18, "\u5b57\u6bb5\u6821\u9a8c", 2, "\u6570\u636e\u8868\u4e0e\u8bca\u65ad\u5b57\u6bb5\u6821\u9a8c\u5b8c\u6210\uff0c\u51c6\u5907\u8bfb\u53d6\u539f\u59cb\u6837\u672c\u3002", "running");



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

        emitDiagnosisProgress(progressConsumer, 30, "\u5f02\u5e38\u626b\u63cf", 3, "\u5df2\u8bfb\u53d6\u539f\u59cb\u6570\u636e " + rows.size() + " \u6761\uff0c\u5f00\u59cb\u8bc6\u522b\u5f02\u5e38\u8282\u70b9\u548c\u7edf\u8ba1\u6ce2\u52a8\u3002", "running");



        String sourceQuestion = optionalString(request, "sourceQuestion");

        String question = sourceQuestion != null ? sourceQuestion : Objects.toString(request.getOrDefault("question",

                tableName + " " + metricField + " " + String.join(" ", dimensionFields)));

        try {
            knowledgeGraphService.syncGraph();
        } catch (Exception ignored) {
            // 璇婃柇闃舵浼樺厛浣跨敤 Neo4j 鐜版湁鍥捐氨锛岃嚜鍔ㄥ悓姝ュけ璐ヤ細鍦ㄦ帹鐞嗚瘉鎹腑浣撶幇涓哄浘璋变笂涓嬫枃涓嶈冻銆?
        }
        Map<String, Object> graphPath = knowledgeGraphService.retrieveMultiHopContextSafely(question, tableName);

        List<Map<String, Object>> graphNodes = castMapList(graphPath.getOrDefault("nodes", List.of()));

        List<Map<String, Object>> graphEdges = castMapList(graphPath.getOrDefault("edges", List.of()));

        emitDiagnosisProgress(progressConsumer, 45, "Neo4j \u591a\u8df3\u6269\u5c55", 4, "\u5df2\u6269\u5c55\u56fe\u8c31\u8282\u70b9 " + graphNodes.size() + " \u4e2a\u3001\u5173\u7cfb " + graphEdges.size() + " \u6761\uff0c\u5f00\u59cb\u53ec\u56de\u77e5\u8bc6\u6587\u6863\u8bc1\u636e\u3002", "running");

        List<Map<String, Object>> anomalyMarkers = buildAnomalyMarkers(rows, metricField, dimensionFields, timeField, Map.of());
        String documentSearchQuery = buildDiagnosisDocumentSearchQuery(question, rows, metricField, dimensionFields,
                timeField, fieldLabels, anomalyMarkers);
        List<Map<String, Object>> docEvidence = knowledgeDocumentService.search(documentSearchQuery, 10);

        emitDiagnosisProgress(progressConsumer, 58, "GraphRAG \u6587\u6863\u53ec\u56de", 5, "\u5df2\u53ec\u56de\u4f01\u4e1a\u6587\u6863/\u884c\u4e1a\u7814\u62a5\u8bc1\u636e " + docEvidence.size() + " \u6761\uff0c\u5f00\u59cb\u878d\u5408\u591a\u8df3\u8bc1\u636e\u94fe\u3002", "running");



        Optional<Map<String, Object>> graphRagResult = pythonAiService.graphRagDiagnose(question, tableName, metricField,

                        dimensionFields, timeField, graphPath, docEvidence, rows, fieldLabels, detailLevel, anomalyType)

                ;

        emitDiagnosisProgress(progressConsumer, 72, "\u6839\u56e0\u63a8\u7406", 6, graphRagResult.isPresent()
                ? "GraphRAG AI \u5df2\u8fd4\u56de\u6839\u56e0\u63a8\u7406\u7ed3\u679c\uff0c\u6b63\u5728\u7ed3\u6784\u5316\u62a5\u544a\u5185\u5bb9\u3002"
                : "GraphRAG AI \u672a\u8fd4\u56de\u53ef\u7528\u7ed3\u679c\uff0c\u5df2\u5207\u6362\u5230\u540e\u7aef\u89c4\u5219\u8bca\u65ad\u3002", "running");

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
        Object conversationId = request.get("conversationId");
        if (conversationId != null && !Objects.toString(conversationId, "").isBlank()) {
            aiResult.put("conversationId", Objects.toString(conversationId, ""));
        }

        anomalyMarkers = buildAnomalyMarkers(rows, metricField, dimensionFields, timeField, aiResult);
        aiResult.put("anomalyMarkers", anomalyMarkers);

        aiResult.put("graphReasoningPath", Objects.toString(graphPath.getOrDefault("pathText", buildGraphReasoningPath(graphNodes))));

        aiResult.put("evidenceSources", buildEvidenceSources(docEvidence, graphNodes));
        aiResult.put("reasoningLogs", aiResult.getOrDefault("reasoningLogs", buildReasoningLogs(rows, graphNodes, graphEdges, docEvidence, aiResult, anomalyType, graphRagRuntime)));
        aiResult.put("detailLevel", detailLevel);
        aiResult.put("anomalyType", anomalyType);
        enhanceBusinessDiagnosis(aiResult, rows, tableName, metricField, dimensionFields, timeField,
                fieldLabels, docEvidence, graphNodes, graphEdges, detailLevel, anomalyType);
        List<Map<String, Object>> historicalSimilarReports = findSimilarHistoricalReports(tableName, metricField,
                anomalyType, dimensionFields, aiResult);
        aiResult.put("historicalSimilarReports", historicalSimilarReports);
        if (!historicalSimilarReports.isEmpty()) {
            List<Map<String, Object>> logs = new ArrayList<>(castMapList(aiResult.getOrDefault("reasoningLogs", List.of())));
            logs.add(Map.of(
                    "step", logs.size() + 1,
                    "title", "\u5386\u53f2\u76f8\u4f3c\u8bca\u65ad\u53ec\u56de",
                    "status", "completed",
                    "detail", "\u5df2\u547d\u4e2d " + historicalSimilarReports.size() + " \u4efd\u5386\u53f2\u8bca\u65ad\u62a5\u544a\uff0c\u7528\u4e8e\u5bf9\u6bd4\u5f02\u5e38\u6a21\u5f0f\u548c\u6839\u56e0\u7ed3\u8bba\u3002"
            ));
            aiResult.put("reasoningLogs", logs);
        }
        aiResult.put("chartSnapshot", normalizeChartSnapshot(request.get("chartSnapshot"), aiResult, rows, metricField,
                castMapList(aiResult.getOrDefault("anomalyMarkers", anomalyMarkers))));

        emitDiagnosisProgress(progressConsumer, 86, "\u62a5\u544a\u7ec4\u88c5", 7, "\u5df2\u5b8c\u6210\u5f02\u5e38\u8282\u70b9\u3001\u6839\u56e0\u7ed3\u8bba\u3001\u56fe\u8868\u5feb\u7167\u548c\u62a5\u544a\u6b63\u6587\u7ec4\u88c5\uff0c\u51c6\u5907\u5199\u5165 Neo4j\u3002", "running");



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

        emitDiagnosisProgress(progressConsumer, 96, "\u62a5\u544a\u6301\u4e45\u5316", 8, reportPersisted
                ? "\u62a5\u544a\u5df2\u5199\u5165 Neo4j\uff0c\u51c6\u5907\u8fd4\u56de\u524d\u7aef\u3002"
                : "\u62a5\u544a\u672a\u80fd\u5199\u5165 Neo4j\uff0c\u5c06\u4ee5\u672c\u6b21\u5185\u5b58\u7ed3\u679c\u8fd4\u56de\u3002", reportPersisted ? "completed" : "warning");



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

    private void emitDiagnosisProgress(Consumer<Map<String, Object>> progressConsumer,
                                       int percentage,
                                       String step,
                                       int logStep,
                                       String detail,
                                       String status) {
        if (progressConsumer == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("percentage", percentage);
        event.put("step", step);
        event.put("log", Map.of(
                "step", logStep,
                "title", step,
                "status", status,
                "detail", detail
        ));
        try {
            progressConsumer.accept(event);
        } catch (Exception ignored) {
            // 娴佸紡杩涘害鏄寮轰綋楠岋紝鎺ㄩ€佸け璐ヤ笉搴斾腑鏂瘖鏂富娴佺▼銆?
        }
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

            throw new IllegalArgumentException("璇婃柇鎶ュ憡涓嶅瓨鍦細" + reportId);

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
            report.put("chartSnapshot", result.get("chartSnapshot"));
            report.put("resultJson", result);
        }
        return report;

    }

    public Map<String, Object> deleteReport(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("鎶ュ憡ID涓嶈兘涓虹┖");
        }
        return deleteReports(List.of(reportId));
    }

    public Map<String, Object> deleteReports(Object rawIds) {
        List<Long> reportIds = castLongList(rawIds);
        if (reportIds.isEmpty()) {
            throw new IllegalArgumentException("璇烽€夋嫨瑕佸垹闄ょ殑璇婃柇鎶ュ憡");
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
            throw new IllegalArgumentException("璇婃柇鎶ュ憡涓嶅瓨鍦ㄦ垨鏃犳潈鍒犻櫎");
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
        String title = cleanDisplayText(report.getOrDefault("title", "\u667a\u80fd\u8bca\u65ad\u62a5\u544a"), "\u667a\u80fd\u8bca\u65ad\u62a5\u544a");
        String markdown = cleanReportMarkdown(report);

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
            return new ExportFile(safeFilename(title) + ".pdf", "application/pdf", buildPdf(content, enablePdfEncryption, includeSnapshots ? extractSnapshotImage(report) : null));
        }

        return new ExportFile(safeFilename(title) + ".md", "text/markdown; charset=UTF-8", content.getBytes(StandardCharsets.UTF_8));
    }

    public ExportFile encryptVisualPdf(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("寰呭姞瀵?PDF 涓嶈兘涓虹┖");
        }
        if (pdfBytes.length > 200L * 1024L * 1024L) {
            throw new IllegalArgumentException("PDF 鏂囦欢瓒呰繃 200MB锛屾棤娉曞湪绾垮姞瀵?");
        }
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupTempFileOnly());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            AccessPermission permission = new AccessPermission();
            permission.setCanPrint(true);
            permission.setCanExtractContent(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("insight-spark", "insight-spark", permission);
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            byte[] encryptedBytes = out.toByteArray();
            if (!containsAsciiToken(encryptedBytes, "/Encrypt")) {
                throw new IllegalStateException("PDF encryption failed: missing encryption dictionary");
            }
            String safeName = safeFilename(filename == null || filename.isBlank() ? "鏅鸿兘璇婃柇鎶ュ憡" : filename);
            if (!safeName.toLowerCase().endsWith(".pdf")) {
                safeName += ".pdf";
            }
            return new ExportFile(safeName, "application/pdf", encryptedBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("PDF \u5bfc\u51fa\u5931\u8d25\uff1a" + e.getMessage());
        }
    }

    private boolean containsAsciiToken(byte[] bytes, String token) {
        byte[] pattern = token.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private String buildExportContent(String title, Map<String, Object> report, String markdown,
                                      boolean includeSnapshots, boolean includeReasoningLogs,
                                      boolean enablePdfEncryption) {
        StringBuilder content = new StringBuilder();
        content.append("# ").append(title).append("\n\n")
                .append("- \u6570\u636e\u8868\uff1a").append(Objects.toString(report.get("tableName"), "")).append("\n")
                .append("- \u6307\u6807\u5b57\u6bb5\uff1a").append(Objects.toString(report.get("metricField"), "")).append("\n")
                .append("- \u751f\u6210\u65f6\u95f4\uff1a").append(Objects.toString(report.get("createdAt"), "")).append("\n")
                .append("- \u5bfc\u51fa\u52a0\u5bc6\uff1a").append(enablePdfEncryption ? "\u5df2\u8bf7\u6c42 PDF \u52a0\u5bc6" : "\u672a\u542f\u7528").append("\n\n");
        content.append(markdown == null ? "" : markdown).append("\n\n");
        Map<String, Object> exportData = mergedReportData(report);
        if (!"simple".equalsIgnoreCase(Objects.toString(exportData.getOrDefault("detailLevel", report.get("detailLevel")), ""))) {
            content.append("## \u62a5\u544a\u7ed1\u5b9a\u4e0e\u56de\u6eaf\u8bf4\u660e\n\n")
                    .append(buildTraceabilityNarrative(report, exportData))
                    .append("\n\n");
        }
        if (includeSnapshots) {
            content.append("## \u56fe\u8868\u5feb\u7167\n\n")
                    .append(buildSnapshotExportSummary(report))
                    .append("\n\n");
        }
        content.append("## \u5f02\u5e38\u8282\u70b9\u6807\u6ce8\n\n")
                .append(buildAnomalyExportSummary(report))
                .append("\n\n")
                .append("## \u539f\u59cb\u6570\u636e\u660e\u7ec6\n\n")
                .append(buildRawDataExportSummary(report))
                .append("\n\n");
        if (includeReasoningLogs) {
            content.append("## GraphRAG \u63a8\u7406\u65e5\u5fd7\n\n")
                    .append(extractGraphPath(report))
                    .append("\n\n");
        }
        return content.toString();
    }

    private String cleanReportMarkdown(Map<String, Object> report) {
        Map<String, Object> data = mergedReportData(report);
        Map<String, String> fieldLabels = toStringMap(data.get("fieldLabels"));
        String markdown = Objects.toString(data.getOrDefault("reportMarkdown", report.getOrDefault("reportMarkdown", "")), "").trim();
        if (!markdown.isBlank() && !looksMojibake(markdown)) {
            return markdown;
        }
        String tableName = Objects.toString(data.getOrDefault("tableName", report.getOrDefault("tableName", "biz_data")));
        String metricField = Objects.toString(data.getOrDefault("metricField", report.getOrDefault("metricField", "metric")));
        String timeField = Objects.toString(data.getOrDefault("timeField", report.getOrDefault("timeField", "")));
        List<String> dimensionFields = castStringList(data.getOrDefault("dimensionFields", report.getOrDefault("dimensionFields", List.of())));
        List<Map<String, Object>> rows = castMapList(data.getOrDefault("rawDataRows", data.getOrDefault("queryRows", List.of())));
        List<Map<String, Object>> evidence = distinctEvidence(castMapList(data.getOrDefault("docEvidence", List.of())));
        List<Map<String, Object>> rootCauses = castMapList(data.getOrDefault("rootCauses", List.of()));
        return buildBusinessReportMarkdown(data, tableName, metricField, dimensionFields, timeField, fieldLabels,
                rows, evidence, rootCauses, Objects.toString(data.getOrDefault("detailLevel", "detailed")));
    }


    private String buildAnomalyExportSummary(Map<String, Object> report) {
        Map<String, Object> result = toStringKeyMap(report.get("resultJson"));
        List<Map<String, Object>> markers = castMapList(result.getOrDefault("anomalyMarkers", List.of()));
        if (markers.isEmpty()) {
            Map<String, Object> snapshot = toStringKeyMap(report.get("chartSnapshot"));
            markers = castMapList(snapshot.getOrDefault("anomalyMarkers", List.of()));
        }
        if (markers.isEmpty()) {
            return "\u672a\u8bc6\u522b\u5230\u8d85\u8fc7\u9608\u503c\u7684\u5f02\u5e38\u8282\u70b9\u3002";
        }
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> marker : markers) {
            builder.append("- ")
                    .append(cleanDisplayText(marker.getOrDefault("label", "\u5f02\u5e38\u70b9"), "\u5f02\u5e38\u70b9"))
                    .append("\uff1a")
                    .append(cleanDisplayText(marker.getOrDefault("valueLabel", marker.getOrDefault("value", "")), "-"))
                    .append("\uff0c")
                    .append(cleanDisplayText(marker.getOrDefault("reason", "\u5df2\u6807\u6ce8\u4e3a\u5f02\u5e38\u8282\u70b9"), "\u5df2\u6807\u6ce8\u4e3a\u5f02\u5e38\u8282\u70b9"))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String buildRawDataExportSummary(Map<String, Object> report) {
        Map<String, Object> result = toStringKeyMap(report.get("resultJson"));
        List<Map<String, Object>> rows = castMapList(result.getOrDefault("rawDataRows", result.getOrDefault("queryRows", List.of())));
        if (rows.isEmpty()) {
            return "\u672a\u7ed1\u5b9a\u539f\u59cb\u6570\u636e\u660e\u7ec6\u3002";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (Map<String, Object> row : rows.stream().limit(10).toList()) {
            builder.append(index++).append(". ");
            builder.append(row.entrySet().stream()
                    .limit(8)
                    .map(entry -> cleanDisplayText(entry.getKey(), "") + "=" + cleanDisplayText(entry.getValue(), ""))
                    .reduce((a, b) -> a + "\uff1b" + b)
                    .orElse(""));
            builder.append("\n");
        }
        if (rows.size() > 10) {
            builder.append("... \u5171\u7ed1\u5b9a ").append(rows.size()).append(" \u6761\u660e\u7ec6\uff0c\u5bfc\u51fa\u4ec5\u5c55\u793a\u524d 10 \u6761\u3002\n");
        }
        return builder.toString().trim();
    }

    private String buildSnapshotExportSummary(Map<String, Object> report) {
        Map<String, Object> snapshot = toStringKeyMap(report.get("chartSnapshot"));
        if (snapshot.isEmpty()) {
            return "\u672a\u7ed1\u5b9a\u56fe\u8868\u5feb\u7167\u3002";
        }
        String title = cleanDisplayText(snapshot.getOrDefault("title", "\u8bca\u65ad\u56fe\u8868\u5feb\u7167"), "\u8bca\u65ad\u56fe\u8868\u5feb\u7167");
        String chartType = chartTypeLabel(Objects.toString(snapshot.getOrDefault("chartType", "chart")));
        List<Map<String, Object>> data = castMapList(snapshot.getOrDefault("data", List.of()));
        String source = Objects.toString(snapshot.getOrDefault("source", "frontend-captured"));
        String sourceLabel = "server-generated".equals(source) ? "\u540e\u7aef\u81ea\u52a8\u751f\u6210" : "\u524d\u7aef\u56fe\u8868\u622a\u56fe";
        return "- \u5feb\u7167\u6807\u9898\uff1a" + title + "\n"
                + "- \u56fe\u8868\u7c7b\u578b\uff1a" + chartType + "\n"
                + "- \u6570\u636e\u70b9\u6570\u91cf\uff1a" + data.size() + "\n"
                + "- \u5feb\u7167\u6765\u6e90\uff1a" + sourceLabel + "\n"
                + "- \u56fe\u7247\u5185\u5bb9\uff1a\u5df2\u4f5c\u4e3a\u56fe\u8868\u5feb\u7167\u63d2\u5165\u5bfc\u51fa\u7684 PDF/Word\uff0c\u6b63\u6587\u4e0d\u5c55\u5f00 base64 \u56fe\u7247\u6570\u636e\u3002";
    }

    private Long saveReport(String tableName, String metricField, List<String> dimensionFields,

                            String timeField, Map<String, Object> aiResult, Map<String, Object> request) {

        if (!neo4jEnabled) {

            throw new IllegalStateException("Neo4j 鏈惎鐢紝鏃犳硶淇濆瓨璇婃柇鎶ュ憡銆傝寮€鍚?insight.neo4j.enabled");

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
        String title = replacePhysicalFields(Objects.toString(aiResult.getOrDefault("title", "鏅鸿兘璇婃柇鎶ュ憡")), persistedFieldLabels);
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

            throw new IllegalStateException("Neo4j 鏌ヨ澶辫触锛?" + safeErrorMessage(e), e);

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
                .orElse("鏈煡 Neo4j 閿欒");
    }

    private String safeErrorMessage(Exception e) {
        String message = e == null ? "" : Objects.toString(e.getMessage(), "").trim();
        return message.isBlank() && e != null ? e.getClass().getSimpleName() : message;
    }



    private String buildGraphReasoningPath(List<Map<String, Object>> relatedKnowledge) {

        if (relatedKnowledge == null || relatedKnowledge.isEmpty()) {

            return "\u6682\u65e0\u56fe\u8c31\u4e0a\u4e0b\u6587\uff0c\u5efa\u8bae\u5148\u5728\u300c\u77e5\u8bc6\u56fe\u8c31\u4e0e GraphRAG\u300d\u4e2d\u540c\u6b65\u56fe\u8c31\u3002";

        }

        return relatedKnowledge.stream()

                .limit(6)

                .map(item -> Objects.toString(item.get("nodeType"), "??") + "?"

                        + Objects.toString(item.get("label"), "") + "?")

                .reduce((a, b) -> a + " -> " + b)

                .orElse("");

    }



    private List<String> buildEvidenceSources(List<Map<String, Object>> docChunks, List<Map<String, Object>> graphContext) {

        List<String> sources = new ArrayList<>();

        for (Map<String, Object> chunk : docChunks) {

            sources.add(Objects.toString(chunk.get("source"), "鐭ヨ瘑鏂囨。") + "锛?" + previewText(chunk.get("chunkText")));

        }

        if (!graphContext.isEmpty()) {

            sources.add("鐭ヨ瘑鍥捐氨璺緞锛?" + buildGraphReasoningPath(graphContext));

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
                                          String detailLevel,
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
                "\u5168\u6837\u672c"));
        aiResult.put("anomalyDimensionContributions", buildDimensionContributionBlocks(
                rawAnomalyRows.isEmpty() ? rows : rawAnomalyRows,
                metricField,
                dimensionFields,
                fieldLabels,
                rawAnomalyRows.isEmpty() ? "\u5168\u6837\u672c" : "\u5f02\u5e38\u8282\u70b9\u5b50\u96c6"));
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
        aiResult.put("title", replacePhysicalFields(Objects.toString(aiResult.getOrDefault("title", "鏅鸿兘璇婃柇鎶ュ憡")), fieldLabels));
        aiResult.put("summary", replacePhysicalFields(Objects.toString(aiResult.getOrDefault("summary", "")), fieldLabels));
        aiResult.put("reportMarkdown", buildBusinessReportMarkdown(aiResult, tableName, metricField,
                dimensionFields, timeField, fieldLabels, rows, evidence, rootCauses, detailLevel));

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
                "鍏ㄦ牱鏈?");
        result.put("dimensionContributions", dimensionContributions);
        result.put("anomalyDimensionContributions", relabelContributionBlocks(
                castMapList(result.getOrDefault("anomalyDimensionContributions", List.of())),
                fieldLabels,
                "寮傚父鑺傜偣瀛愰泦"));
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
            String rawScope = displayText(contribution.getOrDefault("scope", scope));
            normalized.put("dimensionField", dimensionField);
            normalized.put("dimensionLabel", dimensionLabel.isBlank() ? labelOf(fieldLabels, dimensionField) : dimensionLabel);
            normalized.put("scope", looksMojibake(rawScope) || rawScope.isBlank() ? scope : rawScope);
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
            if (looksPhysicalField(title.replace(" \u8d21\u732e\u62c6\u89e3", "")) || title.matches("(?i).*col_\\d{3}.*") || looksMojibake(title)) {
                title = (dimensionLabel.isBlank() ? "\u4e1a\u52a1\u7ef4\u5ea6" : dimensionLabel) + "\u8d21\u732e\u62c6\u89e3";
            } else {
                for (Map.Entry<String, String> entry : fieldLabels.entrySet()) {
                    title = title.replace(entry.getKey(), entry.getValue());
                }
            }
            block.put("title", title.isBlank() ? "\u5173\u8054\u56e0\u7d20\u56fe\u8868" : title);
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
        if (title.matches("(?i).*col_\\d{3}.*") || looksMojibake(title)) {
            Object mapping = toStringKeyMap(snapshot.get("fieldMapping")).get("dimension");
            List<String> dimensions = castStringList(mapping);
            String dimension = dimensions.isEmpty() ? "" : readableFieldLabel(dimensions.get(0), dimensions.get(0), fieldLabels);
            title = (dimension.isBlank() ? "\u8bca\u65ad\u56fe\u8868" : dimension) + "\u8d21\u732e\u56fe\u8868";
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
        evidence.put("sourceType", Objects.toString(item.getOrDefault("docType", "鏂囨。")));
        evidence.put("label", Objects.toString(item.getOrDefault("title", item.getOrDefault("fileName", "鐭ヨ瘑鏂囨。"))));
        evidence.put("source", Objects.toString(item.getOrDefault("source", evidence.get("label"))));
        evidence.put("content", fullText(item.get("chunkText")));
        evidence.put("preview", previewText(item.get("chunkText")));
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
        String anomalyLabel = Objects.toString(topMarker.getOrDefault("label", "\u5173\u952e\u5f02\u5e38\u8282\u70b9"));
        String anomalyValue = Objects.toString(topMarker.getOrDefault("valueLabel", metricLabel));
        String evidenceText = evidence.isEmpty()
                ? "\u672a\u547d\u4e2d\u5916\u90e8\u6587\u6863\u8bc1\u636e\uff0c\u4e3b\u8981\u4f9d\u636e\u539f\u59cb\u6570\u636e\u3001\u7ef4\u5ea6\u8d21\u732e\u548c\u56fe\u8c31\u4e0a\u4e0b\u6587\u5224\u65ad\u3002"
                : distinctEvidence(evidence).stream()
                    .limit(2)
                    .map(item -> "\u300a" + item.get("label") + "\u300b" + Objects.toString(item.get("content"), ""))
                    .reduce((a, b) -> a + "\uff1b" + b)
                    .orElse("");
        String businessContext = describeTopBusinessContext(rows, metricField, dimensionFields, timeField, fieldLabels);
        String firstEvidenceLabel = evidence.isEmpty() ? "\u539f\u59cb\u6570\u636e\u660e\u7ec6" : "\u300a" + evidence.get(0).get("label") + "\u300b";
        Map<String, Double> topDimensionContribution = dimensionFields.isEmpty()
                ? Map.of()
                : aggregateByDimension(rows, metricField, dimensionFields.get(0));
        String topDimensionName = topDimensionContribution.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        String businessCauseName = buildBusinessRootCauseName(topMarker, dimensionFields, fieldLabels, topDimensionName);

        causes.add(rootCause("HIGH", businessCauseName, metricLabel, 0.9,
                anomalyLabel + " \u7684" + anomalyValue + "\uff0c" + businessContext
                        + "\u7ed3\u5408" + firstEvidenceLabel + "\u4e0e\u7ef4\u5ea6\u8d21\u732e\u5206\u6790\uff0c\u53ef\u5224\u65ad\u8be5\u5f02\u5e38\u66f4\u50cf\u662f\u7531\u5177\u4f53\u4e1a\u52a1\u8282\u70b9\u6216\u7ec4\u5408\u53e3\u5f84\u96c6\u4e2d\u62c9\u52a8\u3002\u5f53\u524d\u6307\u6807\u300c"
                        + metricLabel + "\u300d\u7684\u9ad8\u4f4e\u53d8\u5316\u4e0e\u5bf9\u5e94\u7ef4\u5ea6\u5206\u5e03\u9ad8\u5ea6\u76f8\u5173\u3002" + evidenceText,
                "\u4f18\u5148\u590d\u6838\u5f02\u5e38\u8282\u70b9\u5bf9\u5e94\u7684\u539f\u59cb\u8bb0\u5f55\u3001\u4e1a\u52a1\u4e8b\u4ef6\u3001\u7edf\u8ba1\u53e3\u5f84\u548c\u6570\u636e\u91c7\u96c6\u94fe\u8def\uff0c\u786e\u8ba4\u6ce2\u52a8\u662f\u5426\u7531\u771f\u5b9e\u4e1a\u52a1\u53d8\u5316\u89e6\u53d1\u3002"));

        if (!graphNodes.isEmpty()) {
            boolean metadataOnly = isMetadataOnlyGraph(graphEdges);
            causes.add(rootCause(metadataOnly ? "MEDIUM" : "HIGH",
                    metadataOnly ? "Neo4j \u5143\u6570\u636e\u4e0a\u4e0b\u6587\u4e0d\u8db3" : "Neo4j \u591a\u8df3\u5173\u8054\u6307\u5411\u4e1a\u52a1\u94fe\u8def\u5f02\u5e38",
                    metricLabel,
                    metadataOnly ? 0.68 : 0.86,
                    "Neo4j \u53ec\u56de\u7684\u56fe\u8c31\u4e0a\u4e0b\u6587\u5305\u542b "
                            + graphNodes.size() + " \u4e2a\u8282\u70b9\u3001" + graphEdges.size() + " \u6761\u5173\u7cfb\u3002"
                            + summarizeGraphEvidence(graphNodes, graphEdges)
                            + (metadataOnly ? " \u4f46\u5173\u7cfb\u4ee5\u5b57\u6bb5\u6216\u8868\u7ed3\u6784\u4e3a\u4e3b\uff0c\u5c1a\u672a\u5f62\u6210\u8db3\u591f\u7684\u4e1a\u52a1\u56e0\u679c\u94fe\uff0c\u9700\u8981\u7ed3\u5408\u4eba\u5de5\u590d\u76d8\u3002" : ""),
                    metadataOnly ? "\u5b8c\u5584\u56fe\u8c31\u4e2d\u4e1a\u52a1\u5b9e\u4f53\u3001\u4e8b\u4ef6\u548c\u6307\u6807\u7684\u5173\u8054\u5173\u7cfb\uff0c\u907f\u514d\u62a5\u544a\u53ea\u57fa\u4e8e\u5b57\u6bb5\u5143\u6570\u636e\u63a8\u65ad\u3002"
                            : "\u6cbf\u56fe\u8c31\u5173\u7cfb\u8ffd\u6eaf\u76f8\u5173\u4e1a\u52a1\u5bf9\u8c61\u3001\u4e8b\u4ef6\u548c\u7ec4\u7ec7\u73af\u8282\uff0c\u5bf9\u9ad8\u8d21\u732e\u8282\u70b9\u505a\u4e13\u9879\u590d\u6838\u3002"));
        }

        if (!dimensionFields.isEmpty()) {
            String dimension = dimensionFields.get(0);
            String dimensionLabel = labelOf(fieldLabels, dimension);
            Map<String, Double> contribution = aggregateByDimension(rows, metricField, dimension);
            String topDimension = contribution.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(entry -> entry.getKey() + " \u8d21\u732e " + compactNumber(entry.getValue()))
                    .orElse("\u6682\u65e0\u660e\u663e\u9ad8\u8d21\u732e\u56e0\u5b50");
            causes.add(rootCause("MEDIUM", "\u4e1a\u52a1\u7ef4\u5ea6\u8d21\u732e\u96c6\u4e2d", dimensionLabel, 0.84,
                    dimensionLabel + " \u4e2d\u300c" + topDimension + "\u300d\uff0c\u8bf4\u660e\u6307\u6807\u5f02\u5e38\u4e0d\u662f\u5168\u5c40\u5747\u5300\u6ce2\u52a8\uff0c\u800c\u662f\u5728\u7279\u5b9a\u53e3\u5f84\u4e0a\u66f4\u96c6\u4e2d\u3002",
                    "\u5c06\u8be5\u7ef4\u5ea6\u4f5c\u4e3a\u4e3b\u8981\u62c6\u89e3\u53e3\u5f84\uff0c\u5bf9\u9ad8\u8d21\u732e\u56e0\u5b50\u5206\u522b\u6838\u5bf9\u4e1a\u52a1\u6d3b\u52a8\u3001\u5ba2\u6237\u7ed3\u6784\u548c\u6570\u636e\u8bb0\u5f55\u3002"));
        }

        if (!evidence.isEmpty()) {
            Map<String, Object> firstEvidence = evidence.get(0);
            causes.add(rootCause("MEDIUM", "\u6587\u6863\u8bc1\u636e\u8865\u5145\u652f\u6491\u6839\u56e0\u5047\u8bbe", "\u539f\u59cb\u6570\u636e\u4e0e\u77e5\u8bc6\u6587\u6863", 0.8,
                    "GraphRAG \u547d\u4e2d\u6587\u6863\u300a" + firstEvidence.get("label") + "\u300b\uff1a" + firstEvidence.get("content"),
                    "\u5c06\u547d\u4e2d\u6587\u6863\u4e2d\u7684\u4e1a\u52a1\u4e8b\u4ef6\u3001\u7b56\u7565\u53d8\u66f4\u6216\u884c\u4e1a\u80cc\u666f\u4e0e\u5f02\u5e38\u8282\u70b9\u8fdb\u884c\u4eba\u5de5\u5bf9\u9f50\u3002"));
        }

        if (timeField != null && !timeField.isBlank()) {
            causes.add(rootCause("MEDIUM", "\u65f6\u95f4\u7a97\u53e3\u6ce2\u52a8\u6216\u77ed\u671f\u8109\u51b2", labelOf(fieldLabels, timeField), 0.74,
                    "\u5f02\u5e38\u7c7b\u578b\u4e3a" + anomalyTypeLabel(anomalyType) + "\uff0c\u4e14\u5b58\u5728\u53ef\u56de\u6eaf\u65f6\u95f4\u5b57\u6bb5\u300c" + labelOf(fieldLabels, timeField) + "\u300d\uff0c\u9700\u8981\u6309\u5f02\u5e38\u524d\u540e\u7a97\u53e3\u6bd4\u8f83\u3002",
                    "\u5efa\u8bae\u5efa\u7acb\u76f8\u90bb\u65f6\u95f4\u7a97\u53e3\u5bf9\u6bd4\uff0c\u533a\u5206\u77ed\u671f\u8109\u51b2\u3001\u5468\u671f\u6027\u53d8\u5316\u548c\u6301\u7eed\u8d8b\u52bf\u53d8\u5316\u3002"));
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
            return "\u5173\u952e\u5f02\u5e38\u8282\u70b9\u9a71\u52a8";
        }
        return String.join("/", parts) + "\u96c6\u4e2d\u6ce2\u52a8\u9a71\u52a8";
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
            case "fluctuation" -> "\u6ce2\u52a8\u5f02\u5e38";
            case "structure" -> "\u7ed3\u6784\u5f02\u5e38";
            case "trend" -> "\u8d8b\u52bf\u5f02\u5e38";
            default -> Objects.toString(anomalyType, "\u5f02\u5e38");
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
            parts.add(labelOf(fieldLabels, timeField) + "\u4e3a" + topRow.get(timeField));
        }
        for (String dimension : dimensionFields.stream().limit(3).toList()) {
            if (topRow.get(dimension) != null) {
                parts.add(labelOf(fieldLabels, dimension) + "\u4e3a" + topRow.get(dimension));
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "\u9ad8\u8d21\u732e\u8bb0\u5f55\u663e\u793a" + String.join("\u3001", parts) + "\u3002";
    }

    private String summarizeGraphEvidence(List<Map<String, Object>> graphNodes, List<Map<String, Object>> graphEdges) {
        String nodeSummary = graphNodes.stream()
                .limit(5)
                .map(node -> Objects.toString(node.getOrDefault("label", node.getOrDefault("sourceId", "")))
                        + "(" + Objects.toString(node.getOrDefault("nodeType", "NODE")) + ")")
                .filter(item -> !item.isBlank())
                .reduce((a, b) -> a + " -> " + b)
                .orElse("\u672a\u547d\u4e2d\u5177\u4f53\u8282\u70b9");
        String edgeSummary = graphEdges.stream()
                .limit(3)
                .map(edge -> Objects.toString(edge.getOrDefault("relationType", "RELATED")))
                .filter(item -> !item.isBlank())
                .reduce((a, b) -> a + "\u3001" + b)
                .orElse("RELATED");
        return " \u5173\u952e\u8282\u70b9\u94fe\u8def\uff1a" + nodeSummary + "\uff0c\u5173\u7cfb\u7c7b\u578b\uff1a" + edgeSummary + "\u3002";
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
        chain.add(evidenceHop(1, "\u5f02\u5e38\u6307\u6807\u5b9a\u4f4d", metricLabel,
                "\u4ece\u6570\u636e\u8868\u300c" + tableName + "\u300d\u4e2d\u8bc6\u522b\u6307\u6807\u300c" + metricLabel + "\u300d\u7684\u5f02\u5e38\u8282\u70b9 "
                        + anomalyMarkers.size() + " \u4e2a\u3002", 1.0));
        int step = 2;
        for (String dimension : dimensionFields.stream().limit(3).toList()) {
            chain.add(evidenceHop(step++, "\u4e1a\u52a1\u7ef4\u5ea6\u4e0b\u94bb", labelOf(fieldLabels, dimension),
                    "\u6309\u300c" + labelOf(fieldLabels, dimension) + "\u300d\u62c6\u89e3\u8d21\u732e\u5ea6\u548c\u9ad8\u8d21\u732e\u56e0\u5b50\u3002", 0.88));
        }
        if (timeField != null && !timeField.isBlank()) {
            chain.add(evidenceHop(step++, "\u65f6\u95f4\u7a97\u53e3\u56de\u6eaf", labelOf(fieldLabels, timeField),
                    "\u4f7f\u7528\u300c" + labelOf(fieldLabels, timeField) + "\u300d\u5b9a\u4f4d\u5f02\u5e38\u53d1\u751f\u7a97\u53e3\u3002", 0.86));
        }
        if (!graphNodes.isEmpty()) {
            chain.add(evidenceHop(step++, "Neo4j \u56fe\u8c31\u6269\u5c55", "\u56fe\u8c31\u8282\u70b9/\u5173\u7cfb",
                    summarizeGraphEvidence(graphNodes, graphEdges), graphEdges.isEmpty() ? 0.72 : 0.82));
        }
        for (Map<String, Object> item : evidence.stream().limit(3).toList()) {
            chain.add(evidenceHop(step++, "\u6587\u6863\u8bc1\u636e\u53ec\u56de",
                    Objects.toString(item.getOrDefault("label", "\u77e5\u8bc6\u6587\u6863")),
                    Objects.toString(item.getOrDefault("content", "")),
                    toDouble(item.getOrDefault("score", 0)) > 0 ? 0.8 : 0.62));
        }
        if (!rootCauses.isEmpty()) {
            Map<String, Object> cause = rootCauses.get(0);
            chain.add(evidenceHop(step, "\u6839\u56e0\u7ed3\u8bba\u751f\u6210",
                    Objects.toString(cause.getOrDefault("causeType", "\u6839\u56e0\u5047\u8bbe")),
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
            String key = Objects.toString(row.getOrDefault(dimensionField, "鏈垎缁?"));
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
        return reportCell(Objects.toString(marker.getOrDefault("label", "寮傚父鐐?"), "寮傚父鐐?"));
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
            return "Positive Outlier (\u6b63\u5411\u6781\u503c)";
        }
        if (value < avg) {
            return "Negative Outlier (\u8d1f\u5411\u6781\u503c)";
        }
        return "Deviation Outlier (\u504f\u79bb\u5f02\u5e38)";
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
            return "\u6307\u6807\u9ad8\u4e8e\u6837\u672c\u5747\u503c";
        }
        if (value < avg) {
            return "\u6307\u6807\u4f4e\u4e8e\u6837\u672c\u5747\u503c";
        }
        return "\u6307\u6807\u63a5\u8fd1\u5747\u503c\uff0c\u6807\u51c6\u5dee = " + stdValue;
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
            if (!action.isBlank() && !looksMojibake(action)) {
                suggestions.add(action);
            }
        }
        if (!evidence.isEmpty()) {
            suggestions.add("\u6838\u5bf9\u547d\u4e2d\u6587\u6863\u4e0e\u5f02\u5e38\u8282\u70b9\u7684\u65f6\u95f4\u3001\u4e1a\u52a1\u5bf9\u8c61\u548c\u7edf\u8ba1\u53e3\u5f84\u662f\u5426\u4e00\u81f4\uff0c\u907f\u514d\u5c06\u80cc\u666f\u6750\u6599\u8bef\u5224\u4e3a\u76f4\u63a5\u539f\u56e0\u3002");
        }
        suggestions.add("\u590d\u6838\u5f02\u5e38\u8282\u70b9\u5bf9\u5e94\u7684\u539f\u59cb\u8bb0\u5f55\u3001\u4e1a\u52a1\u4e8b\u4ef6\u3001\u7edf\u8ba1\u53e3\u5f84\u548c\u6570\u636e\u91c7\u96c6\u94fe\u8def\uff0c\u786e\u8ba4\u6ce2\u52a8\u662f\u5426\u7531\u771f\u5b9e\u4e1a\u52a1\u53d8\u5316\u89e6\u53d1\u3002");
        suggestions.add("\u5bf9\u5f02\u5e38\u5cf0\u503c\u6216\u4f4e\u8c37\u5efa\u7acb\u76f8\u90bb\u65f6\u95f4\u7a97\u53e3\u5bf9\u6bd4\uff0c\u5224\u65ad\u6ce2\u52a8\u662f\u77ed\u671f\u8109\u51b2\u8fd8\u662f\u8d8b\u52bf\u53d8\u5316\u3002");
        suggestions.add("\u4e3a\u6838\u5fc3\u6307\u6807\u8bbe\u7f6e\u6309\u4e1a\u52a1\u5b57\u6bb5\u547d\u540d\u7684\u76d1\u63a7\u9608\u503c\uff0c\u907f\u514d\u540e\u7eed\u62a5\u544a\u7ee7\u7eed\u4f7f\u7528\u7269\u7406\u5b57\u6bb5\u540d\u3002");
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
        hops.add("\u6570\u636e\u8868\u300c" + tableName + "\u300d");
        hops.add("\u6307\u6807\u300c" + labelOf(fieldLabels, metricField) + "\u300d\u5f02\u5e38\u5b9a\u4f4d");
        for (String dimension : dimensionFields.stream().limit(2).toList()) {
            hops.add("\u7ef4\u5ea6\u300c" + labelOf(fieldLabels, dimension) + "\u300d\u8d21\u732e\u62c6\u89e3");
        }
        if (timeField != null && !timeField.isBlank()) {
            hops.add("\u65f6\u95f4\u300c" + labelOf(fieldLabels, timeField) + "\u300d\u56de\u6eaf");
        }
        if (!evidence.isEmpty()) {
            hops.add("\u6587\u6863\u8bc1\u636e\u300c" + evidence.get(0).get("label") + "\u300d");
        }
        if (!graphNodes.isEmpty()) {
            hops.add("Neo4j \u8282\u70b9 " + graphNodes.size() + " \u4e2a / \u5173\u7cfb " + graphEdges.size() + " \u6761");
        }
        if (!rootCauses.isEmpty()) {
            hops.add("\u6839\u56e0\u300c" + rootCauses.get(0).get("causeType") + "\u300d");
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
                Map.of("step", 1, "title", "\u6570\u636e\u8868\u4e0e\u5b57\u6bb5\u6821\u9a8c", "status", "completed",
                        "detail", "\u5df2\u8bfb\u53d6\u539f\u59cb\u6570\u636e " + rows.size() + " \u6761\uff0c\u5e76\u5b8c\u6210\u6307\u6807\u4e0e\u7ef4\u5ea6\u5b57\u6bb5\u6821\u9a8c\u3002"),
                Map.of("step", 2, "title", "\u5f02\u5e38\u8282\u70b9\u8bc6\u522b", "status", "completed",
                        "detail", "\u5df2\u6309 " + anomalyTypeLabel(anomalyType) + " \u8bc6\u522b\u6307\u6807\u6781\u503c\u548c\u504f\u79bb\u5747\u503c\u7684\u89c2\u6d4b\u70b9\u3002"),
                Map.of("step", 3, "title", "GraphRAG \u6587\u6863\u53ec\u56de", "status", "completed",
                        "detail", "\u547d\u4e2d\u77e5\u8bc6\u6587\u6863 " + evidence.size() + " \u6761\uff0c\u7528\u4e8e\u8865\u5145\u4e1a\u52a1\u80cc\u666f\u548c\u8bc1\u636e\u3002"),
                Map.of("step", 4, "title", "Neo4j \u56fe\u8c31\u6269\u5c55", "status", "completed",
                        "detail", "\u53ec\u56de\u56fe\u8c31\u8282\u70b9 " + graphNodes.size() + " \u4e2a\u3001\u5173\u7cfb " + graphEdges.size() + " \u6761\u3002"),
                Map.of("step", 5, "title", "GraphRAG \u8bc1\u636e\u94fe\u7ec4\u88c5", "status", "completed",
                        "detail", "\u5df2\u5c06\u5f02\u5e38\u8282\u70b9\u3001\u7ef4\u5ea6\u8d21\u732e\u3001\u6587\u6863\u8bc1\u636e\u548c Neo4j \u56fe\u8c31\u4e0a\u4e0b\u6587\u7ec4\u88c5\u4e3a "
                                + graphRagEvidenceChain.size() + " \u6b65\u8bc1\u636e\u94fe\u3002"),
                Map.of("step", 6, "title", "\u6839\u56e0\u4e0e\u5efa\u8bae\u751f\u6210", "status", "completed",
                        "detail", "\u5df2\u751f\u6210 " + rootCauses.size() + " \u6761\u6839\u56e0\u5047\u8bbe\u53ca\u5bf9\u5e94\u5904\u7f6e\u5efa\u8bae\u3002")
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
                                               List<Map<String, Object>> rootCauses,
                                               String detailLevel) {
        boolean simpleReport = "simple".equalsIgnoreCase(Objects.toString(detailLevel, ""));
        String metricLabel = labelOf(fieldLabels, metricField);
        Map<String, Object> statistics = toStringKeyMap(aiResult.get("statistics"));
        List<Map<String, Object>> markers = castMapList(aiResult.getOrDefault("anomalyMarkers", List.of()));
        List<Map<String, Object>> graphRagEvidenceChain = castMapList(aiResult.getOrDefault("graphRagEvidenceChain", List.of()));
        List<Map<String, Object>> graphEdges = castMapList(aiResult.getOrDefault("graphEdges", List.of()));
        List<Map<String, Object>> graphNodes = castMapList(aiResult.getOrDefault("relatedKnowledge", List.of()));
        List<Map<String, Object>> historicalSimilarReports = castMapList(aiResult.getOrDefault("historicalSimilarReports", List.of()));
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
                .orElse("\u672a\u9009\u62e9\u7ef4\u5ea6");
        String timeName = timeField != null && !timeField.isBlank() ? labelOf(fieldLabels, timeField) : "\u672a\u9009\u62e9\u65f6\u95f4\u5b57\u6bb5";
        List<String> markdownSuggestions = suggestions.isEmpty()
                ? fallbackReportSuggestions(metricLabel, dimensionFields.stream().map(field -> labelOf(fieldLabels, field)).toList(), timeName, evidence, markers)
                : suggestions;

        StringBuilder md = new StringBuilder();
        if (simpleReport) {
            md.append("# \u667a\u80fd\u8bca\u65ad\u7b80\u62a5\n\n");
            md.append("## \u6458\u8981\n\n");
            md.append("\u672c\u6b21\u5bf9\u6570\u636e\u8868\u300c").append(tableName).append("\u300d\u7684\u6307\u6807\u300c").append(metricLabel)
                    .append("\u300d\u8fdb\u884c\u8bca\u65ad\uff0c\u5171\u5206\u6790 ").append(rows.size()).append(" \u6761\u8bb0\u5f55\uff0c\u8bc6\u522b ")
                    .append(markers.size()).append(" \u4e2a\u91cd\u70b9\u5f02\u5e38\u8282\u70b9\u3002\u6837\u672c\u603b\u91cf\u4e3a ").append(totalValue)
                    .append("\uff0c\u5747\u503c\u4e3a ").append(avgValue).append("\uff0c\u6700\u5927\u503c\u4e3a ").append(maxValue)
                    .append("\uff0c\u6700\u5c0f\u503c\u4e3a ").append(minValue).append("\u3002")
                    .append(rootCauseConclusion(rootCauses)).append("\n\n");
            md.append("## \u5173\u952e\u5f02\u5e38\u70b9\n\n");
            appendMarkerBullets(md, markers, statistics, metricField, metricLabel, timeField, fieldLabels, 3);
            md.append("\n## \u9996\u8981\u6839\u56e0\n\n");
            if (rootCauses.isEmpty()) {
                md.append("- \u6682\u672a\u5f62\u6210\u53ef\u9760\u6839\u56e0\u5047\u8bbe\u3002\n");
            } else {
                Map<String, Object> cause = rootCauses.get(0);
                md.append("- **").append(cause.getOrDefault("causeType", "\u6839\u56e0\u5047\u8bbe"))
                        .append("**\uff1a").append(evidenceReportSummary(cause, metricLabel)).append("\n");
            }
            md.append("\n## \u8bc1\u636e\n\n");
            appendEvidenceBullets(md, evidence, graphRagEvidenceChain, rootCauses, 3);
            md.append("\n## \u5904\u7f6e\u5efa\u8bae\n\n");
            markdownSuggestions.stream().limit(3).forEach(item -> md.append("- ").append(item).append("\n"));
            md.append("\n## \u56de\u6eaf\u4fe1\u606f\n\n");
            md.append("- \u751f\u6210\u65f6\u95f4\uff1a").append(createdAt).append("\n");
            md.append("- \u6570\u636e\u8868\uff1a").append(tableName).append("\n");
            md.append("- \u6307\u6807\u5b57\u6bb5\uff1a").append(metricLabel).append("\n");
            md.append("- \u5206\u6790\u7ef4\u5ea6\uff1a").append(dimensionNames).append("\n");
            md.append("- \u65f6\u95f4\u5b57\u6bb5\uff1a").append(timeName).append("\n");
            return md.toString();
        }

        md.append("# \u667a\u80fd\u8bca\u65ad\u62a5\u544a\n\n");
        md.append(":::report-meta\n");
        md.append("Diagnostic Analysis Report | Insight Spark System  \n");
        md.append("\u6570\u636e\u8868\uff1a`").append(tableName).append("` | \u6307\u6807\uff1a").append(metricLabel).append("  \n");
        md.append("\u5206\u6790\u7ef4\u5ea6\uff1a").append(dimensionNames).append(" | \u751f\u6210\u65f6\u95f4\uff1a").append(createdAt).append("\n");
        md.append(":::\n\n");
        md.append("> **Abstract / \u6458\u8981\uff1a** ");
        md.append(detailedSummaryText(metricLabel, statistics, rows, markers, rootCauses, graphNodes, graphEdges)).append("\n\n");

        md.append("## I. \u5f02\u5e38\u8282\u70b9\u4e0e\u7edf\u8ba1\u7279\u5f81\n\n");
        md.append("| \u89c2\u6d4b\u7a97\u53e3 | \u6307\u6807\u6570\u503c | \u504f\u79bb\u8bf4\u660e | \u5f02\u5e38\u7c7b\u578b |\n");
        md.append("| --- | ---: | --- | --- |\n");
        if (markers.isEmpty()) {
            md.append("| - | - | \u672a\u53d1\u73b0\u8d85\u8fc7\u9608\u503c\u7684\u5f02\u5e38\u8282\u70b9 | Normal Observation |\n");
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

        md.append("\n## II. GraphRAG \u63a8\u7406\u8def\u5f84\n\n");
        md.append("\u63a8\u7406\u8def\u5f84\uff1a").append(graphPath.isBlank() ? "\u672a\u5f62\u6210\u5b8c\u6574\u8def\u5f84" : graphPath).append("\n\n");
        md.append("\u672c\u6b21\u53ec\u56de Neo4j \u8282\u70b9 ").append(graphNodes.size()).append(" \u4e2a\u3001\u5173\u7cfb ").append(graphEdges.size())
                .append(" \u6761\uff0c\u8bc1\u636e\u94fe\u5171 ").append(graphRagEvidenceChain.size()).append(" \u6b65\u3002\n\n");
        if (evidence.isEmpty()) {
            md.append("> **Corpus Absence Note / \u6587\u6863\u8bc1\u636e\u4e0d\u8db3\uff1a** RAG \u68c0\u7d22\u672a\u547d\u4e2d\u8db3\u591f\u5916\u90e8\u6587\u6863\u8bc1\u636e\uff0c\u5f53\u524d\u7ed3\u8bba\u4e3b\u8981\u4f9d\u636e\u539f\u59cb\u6570\u636e\u3001\u7ef4\u5ea6\u8d21\u732e\u548c\u56fe\u8c31\u4e0a\u4e0b\u6587\u3002\n\n");
        } else {
            md.append("> **Corpus Evidence Note / \u6587\u6863\u8bc1\u636e\uff1a** RAG \u68c0\u7d22\u547d\u4e2d ").append(evidence.size())
                    .append(" \u6761\u77e5\u8bc6\u6587\u6863\uff0c\u5df2\u7eb3\u5165\u6839\u56e0\u5047\u8bbe\u6392\u5e8f\u548c\u5efa\u8bae\u751f\u6210\u3002\n\n");
        }
        appendHistoricalSimilarReportsMarkdown(md, historicalSimilarReports);

        md.append("## III. \u6839\u56e0\u5206\u6790\n\n");
        for (Map<String, Object> cause : rootCauses) {
            md.append("- **[\u7f6e\u4fe1\u5ea6 ").append(formatConfidence(cause.get("confidence")))
                    .append(" / ").append(cause.getOrDefault("level", "MEDIUM")).append("] ")
                    .append(cause.getOrDefault("causeType", "\u6839\u56e0\u5047\u8bbe"))
                    .append("**\uff1a\u5f71\u54cd\u5b57\u6bb5 ").append(cause.getOrDefault("impactField", metricLabel))
                    .append("\uff0c").append(evidenceReportSummary(cause, metricLabel)).append("\n");
        }

        md.append("\n## IV. \u7ef4\u5ea6\u5f52\u56e0\n\n");
        if (dimensionFields.isEmpty()) {
            md.append("- \u672a\u9009\u62e9\u53ef\u62c6\u89e3\u7ef4\u5ea6\uff0c\u5f53\u524d\u4ec5\u80fd\u6309\u6307\u6807\u6574\u4f53\u6ce2\u52a8\u5206\u6790\u3002\n");
        } else {
            md.append("| \u5206\u6790\u53e3\u5f84 | \u7ef4\u5ea6 | \u56e0\u5b50 | \u8d21\u732e\u503c | \u5360\u6bd4 |\n");
            md.append("| --- | --- | --- | ---: | ---: |\n");
            List<List<String>> dimensionRows = dimensionDocxRows(aiResult, rows, metricField, metricLabel, dimensionFields,
                    dimensionFields.stream().map(field -> labelOf(fieldLabels, field)).toList(), fieldLabels);
            for (List<String> row : dimensionRows.stream().skip(1).toList()) {
                md.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
        }
        if (!evidence.isEmpty()) {
            md.append("\n### \u77e5\u8bc6\u6587\u6863\u8bc1\u636e\n\n");
            for (Map<String, Object> item : evidence.stream().limit(5).toList()) {
                md.append("- \u300a").append(item.getOrDefault("label", "\u77e5\u8bc6\u6587\u6863")).append("\u300b\uff1a")
                        .append(item.getOrDefault("preview", item.getOrDefault("content", ""))).append("\n");
            }
        }

        md.append("\n## V. \u7ed3\u8bba\u4e0e\u5904\u7f6e\u5efa\u8bae\n\n");
        markdownSuggestions.forEach(item -> md.append("- ").append(item).append("\n"));
        return md.toString();
    }


    private void appendMarkerBullets(StringBuilder md,
                                     List<Map<String, Object>> markers,
                                     Map<String, Object> statistics,
                                     String metricField,
                                     String metricLabel,
                                     String timeField,
                                     Map<String, String> fieldLabels,
                                     int limit) {
        if (markers.isEmpty()) {
            md.append("- \u672a\u53d1\u73b0\u8d85\u8fc7\u9608\u503c\u7684\u5f02\u5e38\u8282\u70b9\u3002\n");
            return;
        }
        double avg = toDouble(statistics.get("avg"));
        String stdValue = formatReportNumber(statistics.get("std"));
        for (Map<String, Object> marker : markers.stream().limit(limit).toList()) {
            Map<String, Object> markerRow = toStringKeyMap(marker.get("row"));
            String window = reportObservationWindow(marker, markerRow, timeField, fieldLabels);
            double value = reportMarkerValue(marker, markerRow, metricField);
            md.append("- ").append(window)
                    .append("\uff1a").append(metricLabel).append(" = ").append(formatReportNumber(value))
                    .append("\uff0c").append(reportDeviationText(value, avg, stdValue))
                    .append("\uff0c").append(Objects.toString(marker.getOrDefault("reason", reportOutlierType(value, avg))))
                    .append("\n");
        }
    }

    private void appendEvidenceBullets(StringBuilder md,
                                       List<Map<String, Object>> evidence,
                                       List<Map<String, Object>> graphRagEvidenceChain,
                                       List<Map<String, Object>> rootCauses,
                                       int limit) {
        int count = 0;
        for (Map<String, Object> item : evidence.stream().limit(limit).toList()) {
            md.append("- \u6587\u6863\u8bc1\u636e\u300a")
                    .append(item.getOrDefault("label", "\u77e5\u8bc6\u6587\u6863"))
                    .append("\u300b\uff1a")
                    .append(item.getOrDefault("preview", item.getOrDefault("content", "")))
                    .append("\n");
            count++;
        }
        if (count < limit && !graphRagEvidenceChain.isEmpty()) {
            md.append("- GraphRAG \u8bc1\u636e\u94fe\uff1a\u5171 ")
                    .append(graphRagEvidenceChain.size())
                    .append(" \u6b65\uff0c\u8986\u76d6\u5f02\u5e38\u5b9a\u4f4d\u3001\u7ef4\u5ea6\u62c6\u89e3\u548c\u6839\u56e0\u751f\u6210\u3002\n");
            count++;
        }
        if (count < limit && !rootCauses.isEmpty()) {
            Map<String, Object> cause = rootCauses.get(0);
            md.append("- \u6839\u56e0\u8bc1\u636e\uff1a")
                    .append(evidenceReportSummary(cause, Objects.toString(cause.getOrDefault("impactField", "\u6307\u6807"))))
                    .append("\n");
            count++;
        }
        if (count == 0) {
            md.append("- \u5f53\u524d\u8bc1\u636e\u4e3b\u8981\u6765\u81ea\u539f\u59cb\u6570\u636e\u7684\u5f02\u5e38\u8282\u70b9\u3001\u7ef4\u5ea6\u8d21\u732e\u548c\u7edf\u8ba1\u504f\u79bb\u3002\n");
        }
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

    private void appendHistoricalSimilarReportsMarkdown(StringBuilder md, List<Map<String, Object>> reports) {
        if (reports == null || reports.isEmpty()) {
            md.append("> **鍘嗗彶鐩镐技璇婃柇鍙洖锛?* 褰撳墠鏈懡涓弧瓒崇浉浼煎害闃堝€肩殑鍘嗗彶璇婃柇鎶ュ憡锛屽悗缁姤鍛婄疮绉悗鍙敤浜庡紓甯告ā寮忓鐩樸€俓n\n");
            return;
        }
        md.append("> **鍘嗗彶鐩镐技璇婃柇鍙洖锛?* 绯荤粺鍛戒腑 ").append(reports.size())
                .append(" 浠藉巻鍙茶瘖鏂姤鍛婏紝鐢ㄤ簬瀵规瘮寮傚父妯″紡銆佹牴鍥犵粨璁哄拰寤鸿鍔ㄤ綔銆俓n\n");
        md.append("| 鍘嗗彶鎶ュ憡 | 鐩镐技搴?| 鍖归厤鍘熷洜 | 鍘嗗彶鏍瑰洜 |\n");
        md.append("| --- | ---: | --- | --- |\n");
        for (Map<String, Object> report : reports) {
            md.append("| ")
                    .append(cleanMarkdownText(report.getOrDefault("title", "鍘嗗彶璇婃柇鎶ュ憡")))
                    .append(" | ")
                    .append(formatConfidence(report.getOrDefault("score", 0)))
                    .append(" | ")
                    .append(cleanMarkdownText(report.getOrDefault("matchReason", "")))
                    .append(" | ")
                    .append(cleanMarkdownText(report.getOrDefault("rootCause", "")))
                    .append(" |\n");
        }
        md.append("\n");
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
        runtime.put("strictGraphRagSatisfied", graphRagAiUsed && neo4jEnabled && (!graphNodes.isEmpty() || !docEvidence.isEmpty()));
        runtime.put("fallbackReason", graphRagAiUsed
                ? ""
                : "Python GraphRAG service unavailable or returned no result; generated by built-in diagnosis pipeline.");
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
                Map.of("step", 1, "title", "鎵弿鍘熷寮傚父鏁版嵁", "status", "completed",
                        "detail", "璇诲彇鍘熷鏁版嵁 " + rows.size() + " 鏉★紝璁＄畻鎸囨爣娉㈠姩銆佹瀬鍊笺€佸潎鍊煎拰鏍囧噯宸€?"),
                Map.of("step", 2, "title", "鍛戒腑 Neo4j 琛?瀛楁鑺傜偣", "status", "completed",
                        "detail", "鍥寸粫寮傚父绫诲瀷 " + anomalyType + " 鍛戒腑 " + graphNodes.size() + " 涓浘璋辫妭鐐广€?"),
                Map.of("step", 3, "title", "鎵╁睍浼佷笟鍐呴儴鏂囨。涓庤涓氱爺鎶?", "status", "completed",
                        "detail", "妫€绱紒涓氭枃妗?鐮旀姤璇佹嵁 " + docEvidence.size() + " 鏉°€?"),
                Map.of("step", 4, "title", "鍏宠仈鍘嗗彶鎶ュ憡涓庡浘璋卞叧绯?", "status", "completed",
                        "detail", "鎵弿 Neo4j 澶氳烦鍏崇郴 " + graphEdges.size() + " 鏉★紝鍏宠仈鍘嗗彶璇婃柇鍜屽瓧娈典笂涓嬫枃銆?"),
                Map.of("step", 5, "title", "杈撳嚭鏍瑰洜瀹氫綅缁撹", "status", "completed",
                        "detail", "鐢熸垚鏍瑰洜鍋囪 " + castMapList(aiResult.getOrDefault("rootCauses", List.of())).size() + " 鏉★紝骞惰緭鍑烘敼杩涘缓璁€?")
        );
    }

    private String buildBindingJson(String tableName, Map<String, Object> aiResult, Map<String, Object> request) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("route", Objects.toString(request.getOrDefault("sourceRoute", "diagnosis"), "diagnosis"));
        binding.put("tableName", tableName);
        binding.put("conversationId", request.get("conversationId"));
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

    private List<Map<String, Object>> findSimilarHistoricalReports(String tableName,
                                                                    String metricField,
                                                                    String anomalyType,
                                                                    List<String> dimensionFields,
                                                                    Map<String, Object> currentResult) {
        if (!neo4jEnabled) {
            return List.of();
        }
        String cypher = """
                MATCH (r:DiagnosisReport)
                WHERE r.userId = $userId
                RETURN {
                  id: r.reportId,
                  title: r.title,
                  summary: r.summary,
                  tableName: r.tableName,
                  metricField: r.metricField,
                  dimensionFields: r.dimensionFields,
                  anomalyType: r.anomalyType,
                  createdAt: r.createdAt,
                  resultJson: r.resultJson
                } AS row
                ORDER BY r.createdAtEpoch DESC
                LIMIT 50
                """;
        try {
            List<Map<String, Object>> candidates = neo4jQueryRows(cypher, Map.of(
                    "userId", com.insightspark.core.auth.AuthContext.userId()
            ));
            List<Map<String, Object>> scored = new ArrayList<>();
            for (Map<String, Object> report : candidates) {
                Map<String, Object> candidateResult = toStringKeyMap(report.get("resultJson"));
                double score = historicalSimilarityScore(report, candidateResult, tableName, metricField,
                        anomalyType, dimensionFields, currentResult);
                if (score < 0.35) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", report.get("id"));
                row.put("title", Objects.toString(report.getOrDefault("title", "鍘嗗彶璇婃柇鎶ュ憡")));
                row.put("createdAt", Objects.toString(report.getOrDefault("createdAt", "")));
                row.put("tableName", Objects.toString(report.getOrDefault("tableName", "")));
                row.put("metricField", Objects.toString(report.getOrDefault("metricField", "")));
                row.put("anomalyType", Objects.toString(report.getOrDefault("anomalyType", "")));
                row.put("score", Math.round(score * 100.0) / 100.0);
                row.put("rootCause", primaryRootCauseName(castMapList(candidateResult.getOrDefault("rootCauses", List.of()))));
                row.put("summary", safeText(Objects.toString(report.getOrDefault("summary", "")), 180));
                row.put("matchReason", historicalMatchReason(report, candidateResult, tableName, metricField, anomalyType, dimensionFields));
                scored.add(row);
            }
            scored.sort((a, b) -> Double.compare(toDouble(b.get("score")), toDouble(a.get("score"))));
            return dedupeHistoricalReports(scored).stream().limit(3).toList();
        } catch (Exception e) {
            log.warn("Historical diagnosis retrieval skipped: {}", safeErrorMessage(e));
            return List.of();
        }
    }

    private List<Map<String, Object>> dedupeHistoricalReports(List<Map<String, Object>> reports) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> report : reports) {
            String tableName = normalizedReportText(report.get("tableName"));
            String metricField = normalizedReportText(report.get("metricField"));
            String anomalyType = normalizedReportText(report.get("anomalyType"));
            String rootCause = normalizedReportText(report.get("rootCause"));
            String title = normalizedReportText(report.get("title"));
            String exactKey = "id:" + normalizedReportText(report.get("id"));
            String semanticKey = String.join("|", tableName, metricField, anomalyType, rootCause);
            String fallbackKey = String.join("|", title, tableName, metricField, rootCause);
            if (!exactKey.equals("id:") && !seen.add(exactKey)) {
                continue;
            }
            if (!rootCause.isBlank() && !seen.add("semantic:" + semanticKey)) {
                continue;
            }
            if (rootCause.isBlank() && !seen.add("fallback:" + fallbackKey)) {
                continue;
            }
            result.add(report);
        }
        return result;
    }

    private double historicalSimilarityScore(Map<String, Object> report,
                                             Map<String, Object> candidateResult,
                                             String tableName,
                                             String metricField,
                                             String anomalyType,
                                             List<String> dimensionFields,
                                             Map<String, Object> currentResult) {
        double score = 0;
        if (Objects.equals(Objects.toString(report.get("tableName"), ""), tableName)) {
            score += 0.28;
        }
        if (Objects.equals(Objects.toString(report.get("metricField"), ""), metricField)) {
            score += 0.24;
        }
        if (Objects.equals(Objects.toString(report.get("anomalyType"), ""), anomalyType)) {
            score += 0.16;
        }
        List<String> candidateDimensions = castStringList(report.getOrDefault("dimensionFields", candidateResult.get("dimensionFields")));
        long overlap = dimensionFields.stream().filter(candidateDimensions::contains).count();
        if (!dimensionFields.isEmpty()) {
            score += Math.min(0.16, overlap * 0.08);
        }
        String currentRoot = primaryRootCauseName(castMapList(currentResult.getOrDefault("rootCauses", List.of())));
        String candidateRoot = primaryRootCauseName(castMapList(candidateResult.getOrDefault("rootCauses", List.of())));
        if (!currentRoot.isBlank() && !candidateRoot.isBlank()) {
            if (currentRoot.equals(candidateRoot)) {
                score += 0.16;
            } else if (currentRoot.contains(candidateRoot) || candidateRoot.contains(currentRoot)) {
                score += 0.08;
            }
        }
        return Math.min(1.0, score);
    }

    private String historicalMatchReason(Map<String, Object> report,
                                         Map<String, Object> candidateResult,
                                         String tableName,
                                         String metricField,
                                         String anomalyType,
                                         List<String> dimensionFields) {
        List<String> reasons = new ArrayList<>();
        if (Objects.equals(Objects.toString(report.get("tableName"), ""), tableName)) {
            reasons.add("鍚屼竴鏁版嵁琛?");
        }
        if (Objects.equals(Objects.toString(report.get("metricField"), ""), metricField)) {
            reasons.add("鍚屼竴鎸囨爣");
        }
        if (Objects.equals(Objects.toString(report.get("anomalyType"), ""), anomalyType)) {
            reasons.add("寮傚父绫诲瀷涓€鑷?");
        }
        List<String> candidateDimensions = castStringList(report.getOrDefault("dimensionFields", candidateResult.get("dimensionFields")));
        long overlap = dimensionFields.stream().filter(candidateDimensions::contains).count();
        if (overlap > 0) {
            reasons.add("缁村害瀛楁閲嶅悎 " + overlap + " 椤?");
        }
        return reasons.isEmpty() ? "鏍瑰洜鏂囨湰鎴栬瘖鏂笂涓嬫枃鐩歌繎" : String.join("銆?", reasons);
    }



    private String previewText(Object value) {

        String text = Objects.toString(value, "").replaceAll("\\s+", " ").trim();

        return text.length() <= 80 ? text : text.substring(0, 80) + "...";

    }

    private String fullText(Object value) {

        return cleanMarkdownText(value);

    }

    private String cleanMarkdownText(Object value) {

        return Objects.toString(value, "")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                .replaceAll("__(.*?)__", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1")
                .replaceAll("\\s+", " ")
                .trim();

    }

    private String evidenceReportSummary(Map<String, Object> cause, String metricLabel) {

        String rawEvidence = cleanMarkdownText(cause.getOrDefault("evidence", ""));
        String causeType = cleanMarkdownText(cause.getOrDefault("causeType", ""));
        String impactField = cleanMarkdownText(cause.getOrDefault("impactField", metricLabel));
        String context = firstEvidenceSentence(rawEvidence);
        List<String> signals = new ArrayList<>();

        addEvidenceSignal(signals, rawEvidence, "\u4f9b\u5e94\u94fe|\u8865\u8d27|SKU|\u5e93\u5b58|\u4ed3\u914d", "\u4f9b\u5e94\u94fe\u3001\u8865\u8d27\u6216\u5e93\u5b58\u53ef\u5f97\u6027");
        addEvidenceSignal(signals, rawEvidence, "\u6ee1\u51cf|\u4fc3\u9500|\u6298\u6263|\u6d3b\u52a8|\u5927\u4fc3|\u4ef7\u683c\u7b56\u7565", "\u4fc3\u9500\u3001\u4ef7\u683c\u7b56\u7565\u6216\u6d3b\u52a8\u72b6\u6001");
        addEvidenceSignal(signals, rawEvidence, "\u4f01\u4e1a\u5ba2\u6237|\u5ba1\u6279|\u91c7\u8d2d\u8282\u594f|\u5927\u5ba2\u6237", "\u4f01\u4e1a\u5ba2\u6237\u91c7\u8d2d\u6216\u5ba1\u6279\u8282\u594f");
        addEvidenceSignal(signals, rawEvidence, "\u6e20\u9053|\u8f6c\u5316\u7387|\u7ebf\u4e0a|\u76f4\u8425|\u7ecf\u9500", "\u6e20\u9053\u7ed3\u6784\u6216\u8f6c\u5316\u7387\u6ce2\u52a8");
        addEvidenceSignal(signals, rawEvidence, "\u7269\u6d41|\u8c03\u62e8|\u65f6\u6548|\u9000\u6b3e|\u53d6\u6d88", "\u7269\u6d41\u5c65\u7ea6\u3001\u8c03\u62e8\u6216\u9000\u6b3e\u53d6\u6d88");

        if (rawEvidence.isBlank()) {
            return "\u5173\u952e\u8bc1\u636e\uff1a\u5f53\u524d\u672a\u547d\u4e2d\u53ef\u76f4\u63a5\u5f15\u7528\u7684\u6587\u6863\u539f\u6587\uff0c\u7ed3\u8bba\u4e3b\u8981\u4f9d\u636e\u5f02\u5e38\u8282\u70b9\u3001\u7ef4\u5ea6\u8d21\u732e\u548c\u56fe\u8c31\u5173\u7cfb\u7efc\u5408\u8bc4\u4f30\u3002\u5efa\u8bae\u8865\u5145\u4e1a\u52a1\u590d\u76d8\u6750\u6599\u540e\u91cd\u65b0\u6821\u9a8c\u3002";
        }

        StringBuilder summary = new StringBuilder("\u5173\u952e\u8bc1\u636e\uff1a");
        if (!context.isBlank()) {
            summary.append(context);
            if (!context.endsWith("\u3002") && !context.endsWith("\uff1b") && !context.endsWith(";")) {
                summary.append("\u3002");
            }
        } else {
            summary.append("\u8bc1\u636e\u94fe\u663e\u793a\u300c").append(causeType.isBlank() ? "\u6839\u56e0\u5047\u8bbe" : causeType)
                    .append("\u300d\u4e0e\u300c").append(impactField.isBlank() ? metricLabel : impactField).append("\u300d\u5b58\u5728\u5173\u8054\u3002");
        }

        if (signals.isEmpty()) {
            summary.append("\u6587\u6863\u8bc1\u636e\u4e0e\u5f02\u5e38\u8282\u70b9\u3001\u7ef4\u5ea6\u8d21\u732e\u7ed3\u679c\u65b9\u5411\u4e00\u81f4\u3002");
        } else {
            summary.append("\u6587\u6863\u8bc1\u636e\u96c6\u4e2d\u6307\u5411").append(String.join("\u3001", signals)).append("\u7b49\u5f71\u54cd\u56e0\u7d20\u3002");
        }
        summary.append("\u5efa\u8bae\u4f18\u5148\u6821\u9a8c\u5bf9\u5e94\u65f6\u95f4\u7a97\u53e3\u5185\u7684\u4e1a\u52a1\u53e3\u5f84\u3001\u8fd0\u8425\u914d\u7f6e\u548c\u5173\u952e\u8bb0\u5f55\u3002");
        return limitReportText(summary.toString(), 320);

    }

    private void addEvidenceSignal(List<String> signals, String text, String regex, String label) {

        if (Pattern.compile(regex).matcher(text).find() && !signals.contains(label)) {
            signals.add(label);
        }

    }

    private String firstEvidenceSentence(String text) {

        String clean = cleanMarkdownText(text)
                .replaceAll("\u8bc1\u636e\uff1a?=?.*", "")
                .replaceAll("\u300a[^\\u300b]+\u300b", "")
                .trim();
        if (clean.isBlank()) {
            return "";
        }
        int end = -1;
        for (String separator : List.of("\u3002", "\uff1b", ";")) {
            int index = clean.indexOf(separator);
            if (index >= 0 && (end < 0 || index < end)) {
                end = index;
            }
        }
        String sentence = end >= 0 ? clean.substring(0, end + 1) : clean;
        return limitReportText(sentence, 140);

    }

    private String limitReportText(String text, int maxLength) {

        String clean = cleanMarkdownText(text);
        if (clean.length() <= maxLength) {
            return clean;
        }
        return clean.substring(0, Math.max(0, maxLength - 1)).trim() + "\u2026";

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

            throw new IllegalArgumentException("闈炴硶瀛楁鍚嶏細" + columnName);

        }

        Integer count = jdbcTemplate.queryForObject("""

                SELECT COUNT(*)

                FROM is_data_field

                WHERE table_name = ? AND column_name = ?

                """, Integer.class, tableName, columnName);

        if (count == null || count == 0) {

            throw new IllegalArgumentException("瀛楁涓嶅瓨鍦ㄦ垨鏃犳潈璁块棶锛?" + columnName);

        }

    }



    private String quoteColumn(String columnName) {

        if (!SAFE_COLUMN_NAME.matcher(columnName).matches()) {

            throw new IllegalArgumentException("闈炴硶瀛楁鍚嶏細" + columnName);

        }

        return "`" + columnName + "`";

    }



    private String requiredString(Map<String, Object> request, String key) {

        Object value = request.get(key);

        if (value == null || String.valueOf(value).isBlank()) {

            throw new IllegalArgumentException("缂哄皯蹇呭～椤癸細" + key);

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

    private String displayText(Object value) {
        String text = Objects.toString(value, "").trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }



    private String cleanDisplayText(Object value, String fallback) {
        String text = displayText(value);
        if (text.isBlank() || looksMojibake(text)) {
            return fallback;
        }
        return text;
    }

    private boolean looksMojibake(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long hits = text.chars()
                .filter(ch -> ch == '\ufffd'
                        || ch == '\u95c2'
                        || ch == '\u9359'
                        || ch == '\u9366'
                        || ch == '\u9427'
                        || ch == '\u95b8'
                        || ch == '\u701a'
                        || ch == '\u5a34'
                        || ch == '\u6d93'
                        || ch == '\u95bf'
                        || ch == '\u95b5')
                .count();
        return hits >= 2 || text.contains("???");
    }

    private String normalizedReportText(Object value) {
        return displayText(value).replaceAll("\\s+", " ").trim().toLowerCase();
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
            String renderTitle = cleanDisplayText(snapshot.getOrDefault("title", "\u8bca\u65ad\u56fe\u8868\u5feb\u7167"), "\u8bca\u65ad\u56fe\u8868\u5feb\u7167");
            String renderSignature = snapshotRenderSignature(snapshot, fieldLabels);
            boolean needsContentRerender = forceRerender || hadPhysicalTitle || snapshotNeedsRerender(snapshot, fieldLabels) || looksMojibake(renderTitle);
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
        String title = "\u5f02\u5e38\u6570\u636e\u56fe\u8868\u5feb\u7167";

        List<Map<String, Object>> blocks = castMapList(aiResult.getOrDefault("factorChartBlocks", List.of()));
        if (!blocks.isEmpty()) {
            Map<String, Object> block = blocks.get(0);
            chartType = Objects.toString(block.getOrDefault("chartType", "bar"));
            title = cleanDisplayText(block.getOrDefault("title", title), title);
            chartData.addAll(normalizeSnapshotData(castMapList(block.getOrDefault("data", List.of())), metricField));
        }
        title = replacePhysicalFields(title, fieldLabels);
        if (looksMojibake(title)) {
            title = "\u5f02\u5e38\u6570\u636e\u56fe\u8868\u5feb\u7167";
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
                item.put("name", cleanDisplayText(row.getOrDefault("name", row.getOrDefault("time", "\u8282\u70b9" + index)), "\u8282\u70b9" + index));
                item.put("value", value);
                chartData.add(item);
                index++;
            }
        }

        if (chartData.isEmpty()) {
            Map<String, Object> emptyItem = new LinkedHashMap<>();
            emptyItem.put("name", "\u6682\u65e0\u6570\u636e");
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
            marker.put("reason", extreme
                    ? "\u6307\u6807\u6570\u503c\u5904\u4e8e\u5f53\u524d\u6837\u672c\u7684\u6781\u503c\u533a\u95f4\uff0c\u5df2\u6807\u8bb0\u4e3a\u5f02\u5e38\u8282\u70b9"
                    : "\u6307\u6807\u504f\u79bb\u5747\u503c " + marker.get("deviation") + " \u4e2a\u6807\u51c6\u5dee");
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
            first.put("label", "\u6700\u9ad8\u6307\u6807\u8bb0\u5f55");
            first.put("metricField", metricField);
            first.put("value", max);
            first.put("valueLabel", metricField + " = " + compactNumber(max));
            first.put("reason", "\u6837\u672c\u672a\u8d85\u8fc7\u6807\u51c6\u5dee\u9608\u503c\uff0c\u7cfb\u7edf\u9009\u53d6\u6700\u9ad8\u6307\u6807\u8bb0\u5f55\u4f5c\u4e3a\u91cd\u70b9\u89c2\u6d4b\u8282\u70b9");
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
        return "\u8282\u70b9" + fallbackIndex;
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
            g.setFont(chartFont(Font.BOLD, 26));
            g.drawString(title == null || title.isBlank() ? "\u8bca\u65ad\u56fe\u8868\u5feb\u7167" : title, 42, 54);

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
            g.setFont(chartFont(Font.BOLD, 24));
            g.drawString("\u8bca\u65ad\u56fe\u8868\u5feb\u7167\u751f\u6210\u5931\u8d25", 42, 72);
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
        Font labelFont = chartFont(Font.PLAIN, 15);
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
        g.setFont(chartFont(Font.PLAIN, 15));
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
            return String.format("%.1f\u4e07", value / 10000);
        }
        return String.format("%.0f", value);
    }

    private Font chartFont(int style, int size) {
        Set<String> available = new LinkedHashSet<>(List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : List.of("Microsoft YaHei", "SimHei", "SimSun", "Noto Sans CJK SC", "Arial Unicode MS", "Dialog")) {
            if (available.contains(family) || "Dialog".equals(family)) {
                return new Font(family, style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
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
            coverRun.setText(title == null || title.isBlank() ? "\u667a\u80fd\u8bca\u65ad\u62a5\u544a" : title);

            XWPFParagraph subtitle = document.createParagraph();
            subtitle.setAlignment(ParagraphAlignment.CENTER);
            subtitle.setSpacingAfter(360);
            XWPFRun subtitleRun = subtitle.createRun();
            subtitleRun.setFontFamily("SimSun");
            subtitleRun.setFontSize(12);
            subtitleRun.setItalic(true);
            subtitleRun.setColor("333333");
            subtitleRun.setText("GraphRAG \u591a\u8df3\u63a8\u7406 | Neo4j \u77e5\u8bc6\u56fe\u8c31 | \u539f\u59cb\u6570\u636e\u53ef\u56de\u6eaf");

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
                captionRun.setText("\u56fe\u8868\u5feb\u7167");

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
            throw new IllegalArgumentException("Word 瀵煎嚭澶辫触锛?" + e.getMessage());
        }
    }

    private byte[] buildAcademicDocx(Map<String, Object> report, byte[] snapshotImage, boolean includeReasoningLogs) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            configureDocxPage(document);
            Map<String, Object> data = mergedReportData(report);
            Map<String, Object> chartSnapshot = toStringKeyMap(data.get("chartSnapshot"));
            Map<String, String> fieldLabels = toStringMap(data.get("fieldLabels"));
            String tableName = Objects.toString(data.getOrDefault("tableName", report.getOrDefault("tableName", "biz_data")));
            String metricField = Objects.toString(data.getOrDefault("metricField", report.getOrDefault("metricField", "metric")));
            String metricLabel = readableFieldLabel(metricField, data.get("metricFieldLabel"), fieldLabels);
            String timeField = Objects.toString(data.getOrDefault("timeField", report.getOrDefault("timeField", "")));
            String timeLabel = readableFieldLabel(timeField, data.get("timeFieldLabel"), fieldLabels);
            if (timeLabel.isBlank()) {
                timeLabel = "\u65f6\u95f4";
            }
            List<String> dimensionFields = castStringList(data.getOrDefault("dimensionFields", report.getOrDefault("dimensionFields", List.of())));
            List<String> dimensionLabels = resolveDimensionLabels(data, dimensionFields, fieldLabels);
            Map<String, Object> stats = toStringKeyMap(data.get("statistics"));
            List<Map<String, Object>> rows = castMapList(data.getOrDefault("rawDataRows", data.getOrDefault("queryRows", List.of())));
            List<Map<String, Object>> markers = castMapList(data.getOrDefault("anomalyMarkers", List.of()));
            List<Map<String, Object>> rootCauses = castMapList(data.getOrDefault("rootCauses", List.of()));
            List<Map<String, Object>> evidence = distinctEvidence(castMapList(data.getOrDefault("docEvidence", List.of())));
            List<String> suggestions = castStringList(data.getOrDefault("suggestions", List.of()));
            boolean simpleReport = "simple".equalsIgnoreCase(Objects.toString(data.getOrDefault("detailLevel", report.getOrDefault("detailLevel", ""))));
            String createdAt = Objects.toString(report.getOrDefault("createdAt", DATE_TIME_FORMATTER.format(Instant.now())));
            if (createdAt.length() > 10) {
                createdAt = createdAt.substring(0, 10);
            }
            if (simpleReport) {
                appendSimpleDiagnosisBrief(document, report, data, tableName, metricLabel, timeField, timeLabel,
                        metricField, fieldLabels, stats, rows, markers, rootCauses, evidence, suggestions, createdAt);
                appendDocxExportAppendices(document, report, data, chartSnapshot, snapshotImage, includeReasoningLogs,
                        markers, rows, 7, false);
                document.write(out);
                return out.toByteArray();
            }

            appendDetailedDiagnosisReport(document, report, data, tableName, metricLabel, timeField, timeLabel,
                    metricField, fieldLabels, stats, rows, markers, rootCauses, evidence, suggestions, dimensionFields,
                    dimensionLabels, createdAt);

            appendDocxExportAppendices(document, report, data, chartSnapshot, snapshotImage, includeReasoningLogs,
                    markers, rows, 7, true);

            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Word \u5bfc\u51fa\u5931\u8d25\uff1a" + e.getMessage());
        }
    }

    private void appendDocxExportAppendices(XWPFDocument document,
                                            Map<String, Object> report,
                                            Map<String, Object> data,
                                            Map<String, Object> chartSnapshot,
                                            byte[] snapshotImage,
                                            boolean includeReasoningLogs,
                                            List<Map<String, Object>> markers,
                                            List<Map<String, Object>> rows,
                                            int startSectionNumber,
                                            boolean includeTraceability) throws Exception {
            int nextSectionNumber = startSectionNumber;
            if (includeTraceability) {
                appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, romanSection(nextSectionNumber++) + ". \u62a5\u544a\u7ed1\u5b9a\u4e0e\u56de\u6eaf\u8bf4\u660e (Traceability Binding)"), false);
                appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, buildTraceabilityNarrative(report, data)), false);
            }
            if (snapshotImage != null && snapshotImage.length > 0) {
                appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, romanSection(nextSectionNumber++) + ". \u56fe\u8868\u5feb\u7167 (Chart Snapshot)"), false);
                appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, buildSnapshotNarrative(chartSnapshot, markers, rows)), false);
                XWPFParagraph imageParagraph = document.createParagraph();
                imageParagraph.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun imageRun = imageParagraph.createRun();
                imageRun.addPicture(new ByteArrayInputStream(snapshotImage), Document.PICTURE_TYPE_PNG, "chart-snapshot.png", Units.toEMU(390), Units.toEMU(220));
            }
            if (includeReasoningLogs) {
                List<Map<String, Object>> reasoningLogs = castMapList(data.getOrDefault("reasoningLogs", List.of()));
                if (!reasoningLogs.isEmpty()) {
                    String sectionNo = romanSection(nextSectionNumber);
                    appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, sectionNo + ". GraphRAG \u63a8\u7406\u65e5\u5fd7 (Reasoning Logs)"), false);
                    appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH,
                            "\u672c\u8282\u8bb0\u5f55\u8bca\u65ad\u5f15\u64ce\u4ece\u5b57\u6bb5\u6620\u5c04\u3001\u5f02\u5e38\u626b\u63cf\u3001\u77e5\u8bc6\u68c0\u7d22\u3001\u591a\u8df3\u63a8\u7406\u5230\u6839\u56e0\u8f93\u51fa\u7684\u5173\u952e\u6267\u884c\u8fc7\u7a0b\uff0c\u7528\u4e8e\u56de\u6eaf\u62a5\u544a\u751f\u6210\u4f9d\u636e\u4e0e\u6a21\u578b\u5224\u65ad\u8def\u5f84\u3002"), false);
                    appendDocxTable(document, ReportBlock.table(reasoningLogDocxRows(reasoningLogs)));
                    appendCaption(document, "\u8868 " + sectionNo + ". GraphRAG \u63a8\u7406\u8fc7\u7a0b\u65e5\u5fd7");
                }
            }
    }


    private void appendDetailedDiagnosisReport(XWPFDocument document,
                                               Map<String, Object> report,
                                               Map<String, Object> data,
                                               String tableName,
                                               String metricLabel,
                                               String timeField,
                                               String timeLabel,
                                               String metricField,
                                               Map<String, String> fieldLabels,
                                               Map<String, Object> stats,
                                               List<Map<String, Object>> rows,
                                               List<Map<String, Object>> markers,
                                               List<Map<String, Object>> rootCauses,
                                               List<Map<String, Object>> evidence,
                                               List<String> suggestions,
                                               List<String> dimensionFields,
                                               List<String> dimensionLabels,
                                               String createdAt) {
        appendDocType(document, "Diagnostic Analysis Report | Insight Spark System");
        appendDocTitle(document, "\u667a\u80fd\u8bca\u65ad\u62a5\u544a");
        appendSubtitle(document, "\u6570\u636e\u8868: " + tableName + " | \u6307\u6807: " + metricLabel);
        appendAuthors(document, "\u5206\u6790\u7ef4\u5ea6: " + String.join("\u3001", dimensionLabels)
                + "\n\u751f\u6210\u65f6\u95f4: " + createdAt);
        appendHeaderDivider(document);

        appendAbstract(document, "Abstract / \u6458\u8981", detailedSummaryText(metricLabel, stats, rows, markers, rootCauses,
                castMapList(data.getOrDefault("relatedKnowledge", List.of())),
                castMapList(data.getOrDefault("graphEdges", List.of()))));

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "1. \u5173\u952e\u5f02\u5e38\u70b9"), false);
        appendDocxTable(document, ReportBlock.table(anomalyDocxRows(markers, stats, timeField, timeLabel, metricField, metricLabel)));

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "2. \u6839\u56e0\u5224\u65ad"), false);
        if (rootCauses.isEmpty()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH,
                    "\u5f53\u524d\u8bc1\u636e\u4e0d\u8db3\uff0c\u5c1a\u672a\u5f62\u6210\u7a33\u5b9a\u6839\u56e0\u7ed3\u8bba\u3002"), false);
        } else {
            for (Map<String, Object> cause : rootCauses.stream().limit(3).toList()) {
                appendCauseBlock(document, cause, metricLabel);
            }
        }

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "3. \u7ef4\u5ea6\u8d21\u732e\u5206\u6790"), false);
        appendDocxTable(document, ReportBlock.table(dimensionDocxRows(data, rows, metricField, metricLabel,
                dimensionFields, dimensionLabels, fieldLabels)));

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "4. \u8bc1\u636e\u94fe"), false);
        for (String item : simpleEvidenceItems(evidence, rootCauses, markers, metricLabel).stream().limit(5).toList()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM, item), false);
        }

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "5. \u5904\u7f6e\u5efa\u8bae"), false);
        List<String> reportSuggestions = suggestions.isEmpty()
                ? fallbackReportSuggestions(metricLabel, dimensionLabels, timeLabel, evidence, markers)
                : suggestions;
        for (String suggestion : reportSuggestions.stream().limit(5).toList()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM, suggestion), false);
        }

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "6. \u5386\u53f2\u76f8\u4f3c\u62a5\u544a"), false);
        appendHistoricalSimilarReportsDocx(document, castMapList(data.getOrDefault("similarReports", data.getOrDefault("historicalReports", List.of()))));
    }

    private void appendSimpleDiagnosisBrief(XWPFDocument document,
                                             Map<String, Object> report,
                                             Map<String, Object> data,
                                             String tableName,
                                             String metricLabel,
                                             String timeField,
                                             String timeLabel,
                                             String metricField,
                                             Map<String, String> fieldLabels,
                                             Map<String, Object> stats,
                                             List<Map<String, Object>> rows,
                                             List<Map<String, Object>> markers,
                                             List<Map<String, Object>> rootCauses,
                                             List<Map<String, Object>> evidence,
                                             List<String> suggestions,
                                             String createdAt) {
        appendDocType(document, "Diagnostic Brief | Insight Spark System");
        appendDocTitle(document, "\u4e1a\u52a1\u6307\u6807\u5f02\u5e38\u8bca\u65ad\u7b80\u62a5");
        appendSubtitle(document, "\u4ee5\u6570\u636e\u96c6 " + tableName + " \u7684 " + metricLabel + " \u6307\u6807\u4e3a\u4f8b");
        appendAuthors(document, "\u81ea\u52a8\u751f\u6210\u73af\u5883: \u667a\u80fd\u8bca\u65ad\u5f15\u64ce (Build: 2026.05)\n\u8bca\u65ad\u65f6\u95f4: " + createdAt);
        appendHeaderDivider(document);

        appendAbstract(document, "1. \u6458\u8981", simpleSummaryText(metricLabel, stats, rows, markers, rootCauses));

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "2. \u5173\u952e\u5f02\u5e38\u70b9"), false);
        appendDocxTable(document, ReportBlock.table(anomalyDocxRows(markers.stream().limit(3).toList(), stats,
                timeField, timeLabel, metricField, metricLabel)));

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "3. \u9996\u8981\u6839\u56e0"), false);
        if (rootCauses.isEmpty()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH,
                    "\u5f53\u524d\u8bc1\u636e\u4e0d\u8db3\uff0c\u5efa\u8bae\u8865\u5145\u4e1a\u52a1\u7ef4\u5ea6\u3001\u65f6\u95f4\u7a97\u53e3\u4e0e\u77e5\u8bc6\u6587\u6863\u540e\u91cd\u65b0\u8bca\u65ad\u3002"), false);
        } else {
            appendCauseBlock(document, rootCauses.get(0), metricLabel);
        }

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "4. \u5173\u952e\u8bc1\u636e"), false);
        List<String> evidenceItems = simpleEvidenceItems(evidence, rootCauses, markers, metricLabel);
        for (String item : evidenceItems.stream().limit(3).toList()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM, item), false);
        }

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "5. \u5904\u7f6e\u5efa\u8bae"), false);
        List<String> briefSuggestions = suggestions.isEmpty()
                ? fallbackReportSuggestions(metricLabel, resolveDimensionLabels(data, castStringList(data.getOrDefault("dimensionFields", List.of())), fieldLabels), timeLabel, evidence, markers)
                : suggestions;
        for (String suggestion : briefSuggestions.stream().limit(3).toList()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM, suggestion), false);
        }

        appendDocxBlock(document, new ReportBlock(ReportBlockType.H2, "6. \u56de\u6eaf\u4fe1\u606f"), false);
        appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, buildTraceabilityNarrative(report, data)), false);
    }

    private String simpleSummaryText(String metricLabel,
                                     Map<String, Object> stats,
                                     List<Map<String, Object>> rows,
                                     List<Map<String, Object>> markers,
                                     List<Map<String, Object>> rootCauses) {
        String rootCause = rootCauses.isEmpty() ? "\u8bc1\u636e\u4e0d\u8db3" : primaryRootCauseName(rootCauses);
        return "\u672c\u6b21\u56f4\u7ed5\u300c" + metricLabel + "\u300d\u5206\u6790 "
                + Objects.toString(stats.getOrDefault("count", rows.size()))
                + " \u6761\u6837\u672c\uff0c\u5408\u8ba1 " + formatReportNumber(stats.get("total"))
                + "\uff0c\u5747\u503c " + formatReportNumber(stats.get("avg"))
                + "\uff0c\u8bc6\u522b " + markers.size()
                + " \u4e2a\u5173\u952e\u5f02\u5e38\u70b9\u3002\u9996\u8981\u6839\u56e0\u5224\u65ad\u4e3a\u300c" + rootCause + "\u300d\u3002";
    }

    private String detailedSummaryText(String metricLabel,
                                       Map<String, Object> stats,
                                       List<Map<String, Object>> rows,
                                       List<Map<String, Object>> markers,
                                       List<Map<String, Object>> rootCauses,
                                       List<Map<String, Object>> graphNodes,
                                       List<Map<String, Object>> graphEdges) {
        String rootCause = rootCauses == null || rootCauses.isEmpty() ? "\u8bc1\u636e\u4e0d\u8db3" : primaryRootCauseName(rootCauses);
        return "\u672c\u6b21\u56f4\u7ed5\u6838\u5fc3\u4e1a\u52a1\u6307\u6807\u300c" + metricLabel + "\u300d\u5c55\u5f00\u3002"
                + "\u7cfb\u7edf\u5728\u6709\u6548\u89c2\u6d4b\u533a\u95f4\u5185\u63d0\u53d6\u4e86 " + rows.size()
                + " \u6761\u6837\u672c\u8bb0\u5f55\u8fdb\u884c\u5f02\u5e38\u626b\u63cf\uff0c\u8bc6\u522b\u51fa "
                + (markers == null ? 0 : markers.size()) + " \u4e2a\u663e\u8457\u5f02\u5e38\u8282\u70b9\u3002"
                + "\u7edf\u8ba1\u7ed3\u679c\u663e\u793a\uff0c\u6837\u672c\u603b\u8ba1\u6570\u503c\u4e3a " + formatReportNumber(stats.get("total"))
                + "\uff0c\u5747\u503c (\u03bc) \u4e3a " + formatReportNumber(stats.get("avg"))
                + "\uff0c\u533a\u95f4\u6781\u503c\u5206\u522b\u4e3a Max = " + formatReportNumber(stats.get("max"))
                + " \u4e0e Min = " + formatReportNumber(stats.get("min")) + "\u3002"
                + "\u7cfb\u7edf\u878d\u5408 GraphRAG\u3001Neo4j \u77e5\u8bc6\u56fe\u8c31\u4e0e\u6587\u6863\u8bc1\u636e"
                + "\uff08\u6d89\u53ca " + (graphNodes == null ? 0 : graphNodes.size()) + " \u4e2a\u8282\u70b9\u4e0e "
                + (graphEdges == null ? 0 : graphEdges.size()) + " \u6761\u8fb9\uff09\uff0c"
                + "\u6700\u7ec8\u5c06\u6838\u5fc3\u6839\u56e0\u6307\u5411\u300c" + rootCause + "\u300d\u3002"
                + "\u672c\u6587\u6863\u8be6\u7ec6\u8bb0\u5f55\u4e86\u6570\u636e\u7279\u5f81\u3001\u591a\u8df3\u63a8\u7406\u8def\u5f84\u53ca\u591a\u7ef4\u5ea6\u5f02\u8d28\u6027\u5206\u6790\u7ed3\u679c\u3002";
    }

    private List<String> simpleEvidenceItems(List<Map<String, Object>> evidence,
                                             List<Map<String, Object>> rootCauses,
                                             List<Map<String, Object>> markers,
                                             String metricLabel) {
        List<String> items = new ArrayList<>();
        if (!markers.isEmpty()) {
            Map<String, Object> marker = markers.get(0);
            items.add("\u5f02\u5e38\u70b9: " + Objects.toString(marker.getOrDefault("label", "-"))
                    + "\uff0c" + metricLabel + " = " + Objects.toString(marker.getOrDefault("valueLabel", marker.getOrDefault("value", "-"))));
        }
        if (!rootCauses.isEmpty()) {
            Map<String, Object> cause = rootCauses.get(0);
            String summary = cleanMarkdownText(cause.getOrDefault("evidence", cause.getOrDefault("description", "")));
            if (!summary.isBlank()) {
                items.add(summary);
            }
        }
        for (Map<String, Object> item : distinctEvidence(evidence).stream().limit(2).toList()) {
            String label = cleanMarkdownText(item.getOrDefault("label", item.getOrDefault("source", "\u6587\u6863\u8bc1\u636e")));
            String content = cleanMarkdownText(item.getOrDefault("preview", item.getOrDefault("content", item.getOrDefault("text", ""))));
            items.add(label + "\uff1a" + safeText(content, 160));
        }
        if (items.isEmpty()) {
            items.add("\u6682\u65e0\u53ef\u5f15\u7528\u7684\u6587\u6863\u8bc1\u636e\uff0c\u5efa\u8bae\u8865\u5145\u4e1a\u52a1\u590d\u76d8\u6587\u6863\u540e\u91cd\u65b0\u751f\u6210\u3002");
        }
        return items;
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
                .replaceAll("[锛屻€傦紱锛氥€?.!锛?锛?\\-_*`~\\[\\]()锛堬級銆愩€戙€娿€?>]", "");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String primaryRootCauseName(List<Map<String, Object>> rootCauses) {
        if (rootCauses == null || rootCauses.isEmpty()) {
            return "\u8bc1\u636e\u4e0d\u8db3\uff0c\u6682\u672a\u5f62\u6210\u660e\u786e\u6839\u56e0";
        }
        return Objects.toString(rootCauses.get(0).getOrDefault("causeType", "\u6839\u56e0\u5047\u8bbe")).trim();
    }

    private String rootCauseConclusion(List<Map<String, Object>> rootCauses) {
        if (rootCauses == null || rootCauses.isEmpty()) {
            return "\u5f53\u524d\u8bc1\u636e\u4e0d\u8db3\uff0c\u5efa\u8bae\u8865\u5145\u66f4\u591a\u4e1a\u52a1\u4e0a\u4e0b\u6587\u540e\u518d\u786e\u8ba4\u6839\u56e0\u3002";
        }
        return "\u6700\u7ec8\u5c06\u6838\u5fc3\u6839\u56e0\u6307\u5411\u300c" + primaryRootCauseName(rootCauses) + "\u300d\u3002";
    }

    private String confidenceBandText(List<Map<String, Object>> rootCauses) {
        if (rootCauses == null || rootCauses.isEmpty()) {
            return "\u4f4e\u7f6e\u4fe1";
        }
        List<String> levels = new ArrayList<>();
        for (Map<String, Object> cause : rootCauses) {
            String level = Objects.toString(cause.getOrDefault("level", "")).trim().toUpperCase();
            if (!level.isBlank() && !levels.contains(level)) {
                levels.add(level);
            }
        }
        if (levels.isEmpty()) {
            return "\u4e2d\u7f6e\u4fe1";
        }
        return levels.stream()
                .map(level -> switch (level) {
                    case "HIGH" -> "\u9ad8\u7f6e\u4fe1";
                    case "LOW" -> "\u4f4e\u7f6e\u4fe1";
                    default -> "\u4e2d\u7f6e\u4fe1";
                })
                .reduce((a, b) -> a + "\u3001" + b)
                .orElse("\u4e2d\u7f6e\u4fe1");
    }

    private String romanSection(int number) {
        return switch (number) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(number);
        };
    }

    private List<String> fallbackReportSuggestions(String metricLabel,
                                                   List<String> dimensionLabels,
                                                   String timeLabel,
                                                   List<Map<String, Object>> evidence,
                                                   List<Map<String, Object>> markers) {
        List<String> suggestions = new ArrayList<>();
        if (!markers.isEmpty()) {
            suggestions.add("\u4f18\u5148\u590d\u6838\u5f02\u5e38\u8282\u70b9\u5bf9\u5e94\u7684\u539f\u59cb\u8bb0\u5f55\uff0c\u786e\u8ba4\u300c" + metricLabel + "\u300d\u6ce2\u52a8\u662f\u5426\u6765\u81ea\u771f\u5b9e\u4e1a\u52a1\u4e8b\u4ef6\u3001\u7edf\u8ba1\u53e3\u5f84\u53d8\u5316\u6216\u6570\u636e\u91c7\u96c6\u5f02\u5e38\u3002");
        } else {
            suggestions.add("\u8865\u5145\u66f4\u957f\u89c2\u6d4b\u7a97\u53e3\u6216\u66f4\u9ad8\u9897\u7c92\u5ea6\u660e\u7ec6\u6570\u636e\u540e\u91cd\u65b0\u626b\u63cf\u300c" + metricLabel + "\u300d\uff0c\u907f\u514d\u6837\u672c\u4e0d\u8db3\u5bfc\u81f4\u5f02\u5e38\u5224\u65ad\u4e0d\u7a33\u5b9a\u3002");
        }
        if (dimensionLabels != null && !dimensionLabels.isEmpty()) {
            suggestions.add("\u56f4\u7ed5\u300c" + String.join("\u3001", dimensionLabels.stream().limit(3).toList()) + "\u300d\u7ee7\u7eed\u4e0b\u94bb\u5230\u660e\u7ec6\u5bf9\u8c61\uff0c\u9a8c\u8bc1\u5934\u90e8\u8d21\u732e\u662f\u5426\u96c6\u4e2d\u653e\u5927\u6307\u6807\u6ce2\u52a8\u3002");
        } else {
            suggestions.add("\u8865\u5145\u53ef\u89e3\u91ca\u300c" + metricLabel + "\u300d\u53d8\u5316\u7684\u4e1a\u52a1\u7ef4\u5ea6\u5b57\u6bb5\uff0c\u7528\u4e8e\u751f\u6210\u53ef\u5f52\u56e0\u7684\u8d21\u732e\u62c6\u89e3\u3002");
        }
        if (timeLabel != null && !timeLabel.isBlank()) {
            suggestions.add("\u4ee5\u300c" + timeLabel + "\u300d\u4e3a\u8f74\u5bf9\u5f02\u5e38\u8282\u70b9\u524d\u540e\u76f8\u90bb\u7a97\u53e3\u505a\u5bf9\u6bd4\uff0c\u5224\u65ad\u6ce2\u52a8\u662f\u77ed\u671f\u8109\u51b2\u8fd8\u662f\u8d8b\u52bf\u53d8\u5316\u3002");
        }
        if (evidence == null || evidence.isEmpty()) {
            suggestions.add("\u4e0a\u4f20\u4f01\u4e1a\u590d\u76d8\u6587\u6863\u6216\u884c\u4e1a\u7814\u62a5\u5e76\u91cd\u65b0\u7eb3\u5165 GraphRAG\uff0c\u4ee5\u63d0\u5347\u6839\u56e0\u7ed3\u8bba\u7684\u5916\u90e8\u8bc1\u636e\u652f\u6491\u3002");
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
        titleRun.setText(label + ": ");
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
        String causeType = cleanMarkdownText(cause.getOrDefault("causeType", "\u6839\u56e0\u5047\u8bbe"));
        String impactField = Objects.toString(cause.getOrDefault("impactField", metricLabel));
        String evidence = evidenceReportSummary(cause, metricLabel);
        String action = Objects.toString(cause.getOrDefault("action", "")).trim();
        appendDocxBlock(document, new ReportBlock(ReportBlockType.LIST_ITEM,
                "[\u7f6e\u4fe1\u5ea6 " + confidence + " / " + level + "] " + causeType + "\uff1a\u4e3b\u8981\u5f71\u54cd\u5bf9\u8c61\u4e3a\u300c" + impactField + "\u300d\u3002"), false);
        if (!evidence.isBlank()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, evidence), false);
        }
        if (!action.isBlank()) {
            appendDocxBlock(document, new ReportBlock(ReportBlockType.PARAGRAPH, "\u5efa\u8bae\u52a8\u4f5c\uff1a" + action), false);
        }
    }

    private void appendHistoricalSimilarReportsDocx(XWPFDocument document, List<Map<String, Object>> reports) {
        if (reports == null || reports.isEmpty()) {
            appendEvidenceBlock(document, "\u5386\u53f2\u76f8\u4f3c\u8bca\u65ad\u53ec\u56de\uff1a\u5f53\u524d\u672a\u547d\u4e2d\u6ee1\u8db3\u76f8\u4f3c\u5ea6\u9608\u503c\u7684\u5386\u53f2\u8bca\u65ad\u62a5\u544a\uff0c\u540e\u7eed\u62a5\u544a\u7d2f\u79ef\u540e\u53ef\u7528\u4e8e\u5f02\u5e38\u6a21\u5f0f\u590d\u76d8\u3002");
            return;
        }
        appendEvidenceBlock(document, "\u5386\u53f2\u76f8\u4f3c\u8bca\u65ad\u53ec\u56de\uff1a\u7cfb\u7edf\u547d\u4e2d " + reports.size()
                + " \u4efd\u5386\u53f2\u8bca\u65ad\u62a5\u544a\uff0c\u7528\u4e8e\u5bf9\u6bd4\u5f02\u5e38\u6a21\u5f0f\u3001\u6839\u56e0\u7ed3\u8bba\u548c\u5efa\u8bae\u52a8\u4f5c\u3002");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("\u5386\u53f2\u62a5\u544a", "\u76f8\u4f3c\u5ea6", "\u5339\u914d\u539f\u56e0", "\u5386\u53f2\u6839\u56e0"));
        for (Map<String, Object> report : reports) {
            rows.add(List.of(
                    cleanMarkdownText(report.getOrDefault("title", "\u5386\u53f2\u8bca\u65ad\u62a5\u544a")),
                    formatConfidence(report.getOrDefault("score", 0)),
                    cleanMarkdownText(report.getOrDefault("matchReason", "")),
                    cleanMarkdownText(report.getOrDefault("rootCause", ""))
            ));
        }
        appendDocxTable(document, ReportBlock.table(rows));
    }

    private String buildTraceabilityNarrative(Map<String, Object> report, Map<String, Object> data) {
        Map<String, Object> binding = toStringKeyMap(report.getOrDefault("bindingJson", data.get("bindingJson")));
        Map<String, Object> snapshot = toStringKeyMap(data.getOrDefault("chartSnapshot", report.get("chartSnapshot")));
        String route = Objects.toString(binding.getOrDefault("route", snapshot.getOrDefault("sourceRoute", ""))).trim();
        boolean hasExplicitRoute = "dashboard".equals(route) || "chat".equals(route);
        String routeLabel = "dashboard".equals(route) ? "\u770b\u677f\u9875\u9762"
                : ("chat".equals(route) ? "\u5bf9\u8bdd\u67e5\u8be2\u9875\u9762" : "\u8bca\u65ad\u751f\u6210\u8fc7\u7a0b");
        String tableName = displayText(data.getOrDefault("tableName", report.getOrDefault("tableName", "")));
        String dashboardName = displayText(binding.getOrDefault("dashboardName", snapshot.getOrDefault("dashboardName", "")));
        String cardTitle = displayText(binding.getOrDefault("cardTitle", snapshot.getOrDefault("cardTitle", snapshot.getOrDefault("title", ""))));
        String chartType = displayText(snapshot.getOrDefault("chartType", binding.getOrDefault("chartType", "")));
        int rowCount = castMapList(data.getOrDefault("rawDataRows", data.getOrDefault("queryRows", List.of()))).size();
        List<String> parts = new ArrayList<>();
        parts.add("\u672c\u62a5\u544a\u5df2\u7ed1\u5b9a\u539f\u59cb\u6570\u636e\u8868\u300c" + tableName + "\u300d\u4e0e\u8bca\u65ad\u751f\u6210\u65f6\u7684\u56fe\u8868\u5feb\u7167");
        if (!chartType.isBlank()) {
            parts.add("\u56fe\u8868\u7c7b\u578b\u4e3a\u300c" + chartTypeLabel(chartType) + "\u300d");
        }
        if (!dashboardName.isBlank() || !cardTitle.isBlank()) {
            String sourceText = List.of(dashboardName, cardTitle).stream()
                    .filter(item -> item != null && !item.isBlank())
                    .reduce((a, b) -> a + " / " + b)
                    .orElse("");
            parts.add("\u6765\u6e90\u4e3a\u300c" + sourceText + "\u300d");
        } else if (hasExplicitRoute) {
            parts.add("\u6765\u6e90\u4e3a\u300c" + routeLabel + "\u300d");
        } else {
            parts.add("\u6765\u6e90\u4e3a\u300c\u8bca\u65ad\u751f\u6210\u8fc7\u7a0b / \u540e\u7aef\u81ea\u52a8\u751f\u6210\u300d");
        }
        parts.add("\u5f53\u524d\u62a5\u544a\u4fdd\u7559 " + rowCount + " \u6761\u539f\u59cb\u6570\u636e\u660e\u7ec6\u7528\u4e8e\u56de\u6eaf");
        String suffix = hasExplicitRoute
                ? "\u3002\u5728\u7ebf\u9884\u89c8\u4e2d\u53ef\u901a\u8fc7\u56fe\u8868\u5feb\u7167\u6216\u56de\u6eaf\u5165\u53e3\u5b9a\u4f4d\u81f3\u5bf9\u5e94\u7684" + routeLabel + "\uff1b\u5bfc\u51fa\u7684 PDF/Word \u6587\u4ef6\u4fdd\u7559\u4e0a\u8ff0\u7ed1\u5b9a\u4fe1\u606f\u3002"
                : "\u3002\u672c\u62a5\u544a\u672a\u7ed1\u5b9a\u5230\u5177\u4f53\u7684\u5bf9\u8bdd\u67e5\u8be2\u6216\u770b\u677f\u9875\u9762\uff0c\u5bfc\u51fa\u6587\u4ef6\u4ec5\u4fdd\u7559\u8bca\u65ad\u8bf7\u6c42\u3001\u539f\u59cb\u6570\u636e\u548c\u56fe\u8868\u5feb\u7167\u7528\u4e8e\u79bb\u7ebf\u590d\u6838\u3002";
        return String.join("\uff0c", parts) + suffix;
    }

    private String evidencePreview(List<Map<String, Object>> evidence) {
        return evidence.stream()
                .limit(2)
                .map(item -> "\u300a" + Objects.toString(item.getOrDefault("label", item.getOrDefault("source", "\u77e5\u8bc6\u6587\u6863"))) + "\u300b" + Objects.toString(item.getOrDefault("preview", previewText(item.getOrDefault("content", item.getOrDefault("text", ""))))))
                .reduce((a, b) -> a + "\uff1b" + b)
                .orElse("\u77e5\u8bc6\u6587\u6863\u547d\u4e2d\u7247\u6bb5\u4e0d\u8db3");
    }

    private String corpusEvidenceSummary(List<Map<String, Object>> evidence) {
        List<String> labels = evidence.stream()
                .map(item -> cleanMarkdownText(item.getOrDefault("label", item.getOrDefault("source", "\u77e5\u8bc6\u6587\u6863"))))
                .filter(label -> !label.isBlank())
                .distinct()
                .limit(3)
                .map(label -> "\u300a" + label + "\u300b")
                .toList();
        String text = evidence.stream()
                .map(item -> cleanMarkdownText(item.getOrDefault("content", item.getOrDefault("text", item.getOrDefault("preview", "")))))
                .reduce("", (a, b) -> a + " " + b);
        List<String> themes = new ArrayList<>();
        addEvidenceSignal(themes, text, "\u4f9b\u5e94\u94fe|\u8865\u8d27|SKU|\u5e93\u5b58|\u4ed3\u914d", "\u4f9b\u5e94\u94fe\u4e0e\u5e93\u5b58\u53ef\u5f97\u6027");
        addEvidenceSignal(themes, text, "\u6ee1\u51cf|\u4fc3\u9500|\u6298\u6263|\u6d3b\u52a8|\u5927\u4fc3|\u4ef7\u683c\u7b56\u7565", "\u4fc3\u9500\u4e0e\u4ef7\u683c\u7b56\u7565\u53d8\u5316");
        addEvidenceSignal(themes, text, "\u4f01\u4e1a\u5ba2\u6237|\u5ba1\u6279|\u91c7\u8d2d\u8282\u594f|\u5927\u5ba2\u6237", "\u4f01\u4e1a\u5ba2\u6237\u91c7\u8d2d\u8282\u594f");
        addEvidenceSignal(themes, text, "\u6e20\u9053|\u8f6c\u5316\u7387|\u7ebf\u4e0a|\u76f4\u8425|\u7ecf\u9500", "\u6e20\u9053\u7ed3\u6784\u4e0e\u8f6c\u5316\u7387\u6ce2\u52a8");
        addEvidenceSignal(themes, text, "\u7269\u6d41|\u8c03\u62e8|\u65f6\u6548|\u9000\u6b3e|\u53d6\u6d88", "\u7269\u6d41\u5c65\u7ea6\u4e0e\u8de8\u533a\u8c03\u62e8");
        String sourceText = labels.isEmpty() ? "\u4f01\u4e1a\u590d\u76d8\u6587\u6863\u6216\u884c\u4e1a\u7814\u62a5" : String.join("\u3001", labels);
        String themeText = themes.isEmpty() ? "\u5f02\u5e38\u8282\u70b9\u3001\u7ef4\u5ea6\u8d21\u732e\u548c\u56fe\u8c31\u5173\u7cfb" : String.join("\u3001", themes);
        return "\u68c0\u7d22\u8bc1\u636e\u6458\u8981 (Corpus Evidence Note)\uff1a\u5728 RAG \u68c0\u7d22\u9636\u6bb5\uff0c\u547d\u4e2d " + evidence.size()
                + " \u6761\u4f01\u4e1a\u590d\u76d8\u6587\u6863\u6216\u5916\u90e8\u884c\u4e1a\u7814\u62a5\uff0c\u6765\u6e90\u5305\u62ec " + sourceText
                + "\u3002\u8bc1\u636e\u4e3b\u9898\u96c6\u4e2d\u5728" + themeText
                + "\uff0c\u7cfb\u7edf\u5df2\u5c06\u5176\u7eb3\u5165\u6839\u56e0\u5047\u8bbe\u6392\u5e8f\u3001\u7f6e\u4fe1\u5ea6\u8bc4\u4f30\u4e0e\u5efa\u8bae\u52a8\u4f5c\u751f\u6210\u3002";
    }

    private String buildSnapshotNarrative(Map<String, Object> snapshot, List<Map<String, Object>> markers, List<Map<String, Object>> rows) {
        String title = cleanDisplayText(snapshot.getOrDefault("title", "\u8bca\u65ad\u56fe\u8868\u5feb\u7167"), "\u8bca\u65ad\u56fe\u8868\u5feb\u7167");
        if (looksMojibake(title) || title.contains("\u7481") || title.contains("\u5c1e") || title.contains("\u9397")) {
            title = "\u8bca\u65ad\u56fe\u8868\u5feb\u7167";
        }
        String chartType = chartTypeLabel(Objects.toString(snapshot.getOrDefault("chartType", "chart")));
        String source = Objects.toString(snapshot.getOrDefault("source", snapshot.getOrDefault("sourceRoute", "")));
        String sourceLabel = source.isBlank() || "server-generated".equals(source) ? "\u540e\u7aef\u81ea\u52a8\u751f\u6210" : source;
        int dataPointCount = castMapList(snapshot.getOrDefault("data", List.of())).size();
        if (dataPointCount == 0) {
            dataPointCount = rows.size();
        }
        return "\u56fe\u8868\u5feb\u7167\u7528\u4e8e\u56fa\u5b9a\u672c\u6b21\u8bca\u65ad\u751f\u6210\u65f6\u7684\u539f\u59cb\u56fe\u8868\u72b6\u6001\u3002\u5f53\u524d\u5feb\u7167\u300c" + title + "\u300d\u4e3a" + chartType
                + "\uff0c\u6765\u6e90\u4e3a" + sourceLabel + "\uff0c\u5305\u542b " + dataPointCount + " \u4e2a\u6570\u636e\u70b9\uff0c\u5e76\u6807\u6ce8 "
                + markers.size() + " \u4e2a\u5f02\u5e38\u8282\u70b9\uff0c\u53ef\u7528\u4e8e\u540e\u7eed\u56de\u6eaf\u8bca\u65ad\u7ed3\u8bba\u6240\u4f9d\u636e\u7684\u56fe\u8868\u73b0\u573a\u3002";
    }

    private String chartTypeLabel(String chartType) {
        return switch (Objects.toString(chartType, "").toLowerCase()) {
            case "bar" -> "\u67f1\u72b6\u56fe";
            case "line" -> "\u6298\u7ebf\u56fe";
            case "pie" -> "\u997c\u56fe";
            case "scatter" -> "\u6563\u70b9\u56fe";
            default -> chartType == null || chartType.isBlank() || looksMojibake(chartType) ? "\u56fe\u8868" : chartType;
        };
    }

    private List<List<String>> anomalyDocxRows(List<Map<String, Object>> markers, Map<String, Object> stats,
                                               String timeField, String timeLabel, String metricField, String metricLabel) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("\u89c2\u6d4b\u65e5\u671f (Time Window)", "\u6307\u6807\u6570\u503c (Value)", "\u504f\u79bb\u5ea6 / \u7edf\u8ba1\u68c0\u9a8c\u91cf", "\u5f02\u5e38\u6807\u5b9a\u7c7b\u578b"));
        if (markers.isEmpty()) {
            rows.add(List.of("-", "-", "\u672a\u53d1\u73b0 Z-Score \u7edd\u5bf9\u503c\u8d85\u8fc7\u9608\u503c\u7684\u8282\u70b9", "Normal Observation"));
            return rows;
        }
        double avg = toDouble(stats.get("avg"));
        String stdValue = formatReportNumber(stats.get("std"));
        for (Map<String, Object> marker : markers.stream().limit(5).toList()) {
            Map<String, Object> markerRow = toStringKeyMap(marker.get("row"));
            String window = reportObservationWindow(marker, markerRow, timeField, Map.of(timeField, timeLabel));
            if (looksMojibake(window) || window.isBlank()) {
                window = cleanDisplayText(marker.getOrDefault("label", "-"), "-");
            }
            double value = reportMarkerValue(marker, markerRow, metricLabel);
            if (value == 0) {
                value = reportMarkerValue(marker, markerRow, metricField);
            }
            rows.add(List.of(window, formatReportNumber(value), cleanDisplayText(marker.getOrDefault("reason", reportDeviationText(value, avg, stdValue)), reportDeviationText(value, avg, stdValue)), reportOutlierType(value, avg)));
        }
        return rows;
    }

    private List<List<String>> dimensionDocxRows(Map<String, Object> data, List<Map<String, Object>> rows,
                                                 String metricField, String metricLabel,
                                                 List<String> dimensionFields, List<String> dimensionLabels,
                                                 Map<String, String> fieldLabels) {
        List<List<String>> tableRows = new ArrayList<>();
        tableRows.add(List.of("\u5206\u6790\u53e3\u5f84 (Scope)", "\u4e00\u9636\u7ef4\u5ea6 (Dimension)", "\u4e8c\u9636\u56e0\u5b50 (Factor)", "\u8d21\u732e\u503c (Value)", "\u53e3\u5f84\u5185\u5360\u6bd4 (Ratio)"));
        List<Map<String, Object>> contributions = castMapList(data.getOrDefault("anomalyDimensionContributions", List.of()));
        if (contributions.isEmpty()) {
            contributions = castMapList(data.getOrDefault("dimensionContributions", List.of()));
        }
        if (!contributions.isEmpty()) {
            for (Map<String, Object> contribution : contributions.stream().limit(3).toList()) {
                String dimensionField = Objects.toString(contribution.getOrDefault("dimensionField", contribution.getOrDefault("dimension", "")));
                String dimension = readableFieldLabel(dimensionField, contribution.getOrDefault("dimensionLabel", contribution.getOrDefault("dimension", "")), fieldLabels);
                if (dimension.isBlank() || looksMojibake(dimension)) {
                    dimension = "\u4e1a\u52a1\u7ef4\u5ea6";
                }
                String scope = cleanDisplayText(contribution.getOrDefault("scope", "\u5168\u6837\u672c"), "\u5168\u6837\u672c");
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
                        .forEach(entry -> tableRows.add(List.of("\u5168\u6837\u672c", dimensionLabel, entry.getKey(), formatReportNumber(entry.getValue()), total == 0 ? "0.0%" : String.format("%.1f%%", entry.getValue() / total * 100))));
            }
        }
        if (tableRows.size() == 1) {
            tableRows.add(List.of("\u672a\u9009\u62e9\u7ef4\u5ea6", "\u5f53\u524d\u62a5\u544a\u672a\u63d0\u4f9b\u53ef\u62c6\u89e3\u7ef4\u5ea6", "-", "-", "-"));
        }
        return tableRows;
    }

    private List<List<String>> reasoningLogDocxRows(List<Map<String, Object>> reasoningLogs) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("\u6b65\u9aa4 (Step)", "\u73af\u8282 (Stage)", "\u72b6\u6001 (Status)", "\u8fc7\u7a0b\u8bf4\u660e (Detail)"));
        int fallbackStep = 1;
        for (Map<String, Object> log : reasoningLogs.stream().limit(12).toList()) {
            String step = Objects.toString(log.getOrDefault("step", fallbackStep++), "");
            String title = cleanDisplayText(log.getOrDefault("title", log.getOrDefault("stage", "\u63a8\u7406\u6b65\u9aa4")), "\u63a8\u7406\u6b65\u9aa4");
            String status = Objects.toString(log.getOrDefault("status", "completed"), "");
            String detail = cleanDisplayText(log.getOrDefault("detail", log.getOrDefault("message", "")), "");
            rows.add(List.of(step, title, status, detail));
        }
        if (rows.size() == 1) {
            rows.add(List.of("-", "\u6682\u65e0\u63a8\u7406\u65e5\u5fd7", "-", "\u5f53\u524d\u62a5\u544a\u672a\u7ed1\u5b9a\u53ef\u5bfc\u51fa\u7684\u63a8\u7406\u8fc7\u7a0b\u65e5\u5fd7"));
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
            run.setText("\u2022 " + line);
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
                coverStream.showText("\u667a\u80fd\u8bca\u65ad\u62a5\u544a");
                coverStream.setFont(font, 12);
                coverStream.newLineAtOffset(0, -36);
                coverStream.showText("GraphRAG \u591a\u8df3\u63a8\u7406 | Neo4j \u77e5\u8bc6\u56fe\u8c31 | \u539f\u59cb\u6570\u636e\u53ef\u56de\u6eaf");
                coverStream.newLineAtOffset(0, -40);
                coverStream.showText("\u751f\u6210\u65f6\u95f4\uff1a" + DATE_TIME_FORMATTER.format(Instant.now()));
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
                            stream.showText("Insight Spark \u667a\u80fd\u8bca\u65ad\u62a5\u544a | Page " + pageNumber);
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
                    String prefix = block.type() == ReportBlockType.LIST_ITEM ? "- " : "";
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
                stream.showText("Insight Spark 鏅鸿兘璇婃柇鎶ュ憡 | Page " + pageNumber);
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
            throw new IllegalArgumentException("PDF \u5bfc\u51fa\u5931\u8d25\uff1a" + e.getMessage());
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
        boolean skipDirectiveBlock = false;
        for (int index = 0; index < lines.length; index++) {
            String rawLine = lines[index];
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith(":::")) {
                skipDirectiveBlock = !skipDirectiveBlock;
                continue;
            }
            if (skipDirectiveBlock) {
                continue;
            }
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
            } else if (line.matches("^\\*[^*].*琛╘\s*[IVXLC]+.*\\*$")) {
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
