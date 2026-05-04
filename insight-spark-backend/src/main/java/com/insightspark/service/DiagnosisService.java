package com.insightspark.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Pattern;

@Service
public class DiagnosisService {

    private static final Pattern SAFE_COLUMN_NAME = Pattern.compile("^col_\\d{3}$|^sys_id$");

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initDiagnosisTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_diagnosis_report` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL DEFAULT '',
                  `table_name` VARCHAR(128) NOT NULL,
                  `metric_field` VARCHAR(128) NOT NULL,
                  `dimension_fields` VARCHAR(512) NULL,
                  `time_field` VARCHAR(128) NULL,
                  `title` VARCHAR(255) NOT NULL,
                  `summary` VARCHAR(2000) NOT NULL,
                  `report_markdown` MEDIUMTEXT NULL,
                  `result_json` JSON NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_diagnosis_report_created_at` (`created_at`),
                  INDEX `idx_diagnosis_report_table` (`table_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能诊断报告';
                """);
    }

    public Map<String, Object> runDiagnosis(Map<String, Object> request) {
        String tableName = requiredString(request, "tableName");
        String metricField = requiredString(request, "metricField");
        String timeField = optionalString(request, "timeField");
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

        String question = Objects.toString(request.getOrDefault("question",
                tableName + " " + metricField + " " + String.join(" ", dimensionFields)));
        List<Map<String, Object>> graphContext = knowledgeGraphService.retrieveMultiHopContext(question, tableName);
        List<Map<String, Object>> docChunks = knowledgeDocumentService.search(question, 10);
        Map<String, Object> aiResult = pythonAiService.graphRagDiagnose(question, tableName, metricField, rows, graphContext, docChunks)
                .orElseGet(() -> pythonAiService.diagnose(tableName, metricField, dimensionFields, timeField, rows));
        aiResult.put("relatedKnowledge", graphContext);
        aiResult.put("docEvidence", docChunks);
        aiResult.put("graphReasoningPath", buildGraphReasoningPath((List<Map<String, Object>>) aiResult.get("relatedKnowledge")));
        aiResult.put("evidenceSources", buildEvidenceSources(docChunks, graphContext));
        Long reportId = saveReport(tableName, metricField, dimensionFields, timeField, aiResult);

        Map<String, Object> result = new LinkedHashMap<>(aiResult);
        result.put("id", reportId);
        result.put("tableName", tableName);
        result.put("metricField", metricField);
        result.put("dimensionFields", dimensionFields);
        result.put("timeField", timeField);
        return result;
    }

    public List<Map<String, Object>> listReports() {
        return jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
                       dimension_fields AS dimensionFields, time_field AS timeField, title, summary,
                       created_at AS createdAt
                FROM is_diagnosis_report
                ORDER BY created_at DESC
                LIMIT 100
                """);
    }

    public Map<String, Object> getReport(Long reportId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, table_name AS tableName, metric_field AS metricField,
                       dimension_fields AS dimensionFields, time_field AS timeField, title, summary,
                       report_markdown AS reportMarkdown, result_json AS resultJson, created_at AS createdAt
                FROM is_diagnosis_report
                WHERE id = ?
                """, reportId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("诊断报告不存在：" + reportId);
        }
        return rows.get(0);
    }

    public ExportFile exportReport(Long reportId, String format) {
        Map<String, Object> report = getReport(reportId);
        String normalized = format == null ? "markdown" : format.trim().toLowerCase();
        String title = Objects.toString(report.getOrDefault("title", "智能诊断报告"));
        String markdown = Objects.toString(report.getOrDefault("reportMarkdown", ""));
        if (markdown.isBlank()) {
            markdown = "# " + title + "\n\n" + Objects.toString(report.getOrDefault("summary", ""));
        }

        if ("word".equals(normalized) || "doc".equals(normalized) || "docx".equals(normalized)) {
            String content = title + "\n\n"
                    + "数据表：" + Objects.toString(report.get("tableName"), "") + "\n"
                    + "指标字段：" + Objects.toString(report.get("metricField"), "") + "\n"
                    + "摘要：" + Objects.toString(report.get("summary"), "") + "\n"
                    + "GraphRAG 根因链路：" + extractGraphPath(report) + "\n\n"
                    + markdown;
            return new ExportFile(
                    safeFilename(title) + ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    buildDocx(title, content)
            );
        }

        if ("pdf".equals(normalized)) {
            String content = title + "\n\n"
                    + "Table: " + Objects.toString(report.get("tableName"), "") + "\n"
                    + "Metric: " + Objects.toString(report.get("metricField"), "") + "\n"
                    + "Summary: " + Objects.toString(report.get("summary"), "") + "\n"
                    + "GraphRAG: " + extractGraphPath(report) + "\n\n"
                    + markdown;
            return new ExportFile(safeFilename(title) + ".pdf", "application/pdf", buildPdf(content));
        }

        String content = "# " + title + "\n\n"
                + "- 数据表：" + Objects.toString(report.get("tableName"), "") + "\n"
                + "- 指标字段：" + Objects.toString(report.get("metricField"), "") + "\n"
                + "- 生成时间：" + Objects.toString(report.get("createdAt"), "") + "\n\n"
                + "## GraphRAG 根因链路\n\n" + extractGraphPath(report) + "\n\n"
                + markdown;
        return new ExportFile(safeFilename(title) + ".md", "text/markdown; charset=UTF-8", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private String previewText(Object value) {
        String text = Objects.toString(value, "").replaceAll("\\s+", " ").trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private String extractGraphPath(Map<String, Object> report) {
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

    private Long saveReport(String tableName, String metricField, List<String> dimensionFields,
                            String timeField, Map<String, Object> aiResult) {
        String resultJson = toJson(aiResult);
        jdbcTemplate.update("""
                INSERT INTO is_diagnosis_report(user_id, table_name, metric_field, dimension_fields, time_field,
                                                title, summary, report_markdown, result_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """,
                com.insightspark.core.auth.AuthContext.userId(),
                tableName,
                metricField,
                String.join(",", dimensionFields),
                timeField,
                Objects.toString(aiResult.getOrDefault("title", "智能诊断报告")),
                Objects.toString(aiResult.getOrDefault("summary", "")),
                Objects.toString(aiResult.getOrDefault("reportMarkdown", "")),
                resultJson
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("诊断结果序列化失败：" + e.getMessage());
        }
    }

    private String safeFilename(String title) {
        String value = title == null || title.isBlank() ? "diagnosis-report" : title;
        return value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private byte[] buildDocx(String title, String content) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, java.nio.charset.StandardCharsets.UTF_8)) {
                putZip(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                          <Default Extension="xml" ContentType="application/xml"/>
                          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                          <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                          <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                        </Types>
                        """);
                putZip(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                        </Relationships>
                        """);
                putZip(zip, "docProps/core.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                          xmlns:dc="http://purl.org/dc/elements/1.1/"
                          xmlns:dcterms="http://purl.org/dc/terms/"
                          xmlns:dcmitype="http://purl.org/dc/dcmitype/"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                          <dc:title>%s</dc:title>
                        </cp:coreProperties>
                        """.formatted(escapeXml(title)));
                putZip(zip, "docProps/app.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
                          xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
                          <Application>Insight Spark</Application>
                        </Properties>
                        """);
                putZip(zip, "word/document.xml", buildDocumentXml(content));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Word 导出失败：" + e.getMessage());
        }
    }

    private String buildDocumentXml(String content) {
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\\R", -1)) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escapeXml(line))
                    .append("</w:t></w:r></w:p>");
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>%s<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body>
                </w:document>
                """.formatted(body);
    }

    private void putZip(ZipOutputStream zip, String path, String content) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private byte[] buildPdf(String content) {
        List<String> lines = content.lines().limit(42).toList();
        StringBuilder stream = new StringBuilder("BT /F1 11 Tf 50 780 Td 14 TL ");
        for (String line : lines) {
            stream.append("<").append(toUtf16Hex(line.length() > 92 ? line.substring(0, 92) : line)).append("> Tj T* ");
        }
        stream.append("ET");
        String s = stream.toString();
        String obj1 = "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n";
        String obj2 = "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n";
        String obj3 = "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n";
        String obj4 = "4 0 obj << /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [6 0 R] >> endobj\n";
        String obj5 = "5 0 obj << /Length " + s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1).length + " >> stream\n" + s + "\nendstream endobj\n";
        String obj6 = "6 0 obj << /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 2 >> >> endobj\n";
        String[] objects = {obj1, obj2, obj3, obj4, obj5, obj6};
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (String object : objects) {
            offsets.add(pdf.length());
            pdf.append(object);
        }
        int xref = pdf.length();
        pdf.append("xref\n0 7\n0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer << /Size 7 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF");
        return pdf.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
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
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    public record ExportFile(String filename, String contentType, byte[] content) {
    }
}
