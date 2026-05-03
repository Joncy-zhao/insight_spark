package com.insightspark.service;

import com.alibaba.excel.EasyExcel;
import com.insightspark.core.excel.DynamicDataListener;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DataUploadService {

    private static final Logger log = LoggerFactory.getLogger(DataUploadService.class);
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("^biz_data_\\d+$");

    // 注入 JdbcTemplate，它是我们在数据库里“施工”的神器
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private DatasourceService datasourceService;

    @PostConstruct
    public void initCatalogTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_data_table` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `source_name` VARCHAR(255) NOT NULL,
                  `display_name` VARCHAR(255) NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL UNIQUE,
                  `owner_id` VARCHAR(64) NOT NULL DEFAULT 'demo-user',
                  `row_count` INT NOT NULL DEFAULT 0,
                  `field_count` INT NOT NULL DEFAULT 0,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传数据表元信息';
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_data_field` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `table_name` VARCHAR(128) NOT NULL,
                  `source_field_name` VARCHAR(255) NOT NULL,
                  `column_name` VARCHAR(128) NOT NULL,
                  `field_type` VARCHAR(32) NOT NULL,
                  `display_name` VARCHAR(255) NOT NULL,
                  `field_comment` VARCHAR(512) NULL,
                  `sensitive` TINYINT(1) NOT NULL DEFAULT 0,
                  `sort_order` INT NOT NULL,
                  INDEX `idx_is_data_field_table` (`table_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传数据字段元信息';
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_business_model` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `model_name` VARCHAR(255) NOT NULL,
                  `model_requirement` VARCHAR(2000) NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `owner_id` VARCHAR(64) NOT NULL DEFAULT 'demo-user',
                  `model_json` JSON NOT NULL,
                  `published` TINYINT(1) NOT NULL DEFAULT 0,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_business_model_table` (`table_name`),
                  INDEX `idx_business_model_published` (`published`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='零代码业务模型与企业模型库';
                """);
    }

    public Map<String, Object> processFile(MultipartFile file) throws IOException {
        String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "未命名文件");
        ParsedFile parsedFile = parseFile(file, originalFilename);

        if (parsedFile.headers().isEmpty() || parsedFile.rows().isEmpty()) {
            throw new IllegalArgumentException("解析失败：未找到表头或有效数据");
        }

        return persistParsedFile(originalFilename, parsedFile, null);
    }

    public Map<String, Object> processFiles(List<MultipartFile> files, String mergeMode, String joinKey,
                                            String modelRequirement) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个 Excel/CSV 文件");
        }
        if (files.size() > 5) {
            throw new IllegalArgumentException("批量上传最多支持 5 个文件");
        }

        List<ParsedFile> parsedFiles = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        for (MultipartFile file : files) {
            String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "未命名文件");
            ParsedFile parsedFile = parseFile(file, originalFilename);
            if (parsedFile.headers().isEmpty() || parsedFile.rows().isEmpty()) {
                throw new IllegalArgumentException("文件 " + originalFilename + " 未找到表头或有效数据");
            }
            sourceNames.add(originalFilename);
            parsedFiles.add(parsedFile);
        }

        String normalizedMode = mergeMode == null || mergeMode.isBlank() ? "SAME_HEADER" : mergeMode.trim().toUpperCase();
        ParsedFile merged = "KEY_JOIN".equals(normalizedMode)
                ? joinByKey(parsedFiles, sourceNames, joinKey)
                : mergeSameHeader(parsedFiles);
        String displayName = files.size() == 1
                ? removeExtension(sourceNames.get(0))
                : "多文件合并_" + System.currentTimeMillis();
        Map<String, Object> result = persistParsedFile(String.join(" + ", sourceNames), merged, displayName);
        if (modelRequirement != null && !modelRequirement.isBlank()) {
            result.put("businessModel", saveBusinessModel(
                    "模型_" + result.get("displayName"),
                    modelRequirement,
                    Objects.toString(result.get("tableName")),
                    buildModelJson(modelRequirement, Objects.toString(result.get("tableName")), merged.headers(), merged.rows())
            ));
        }
        result.put("mergeMode", normalizedMode);
        result.put("sourceFiles", sourceNames);
        return result;
    }

    private Map<String, Object> persistParsedFile(String originalFilename, ParsedFile parsedFile, String displayNameOverride) {
        if (parsedFile.headers().isEmpty() || parsedFile.rows().isEmpty()) {
            throw new IllegalArgumentException("解析失败：未找到表头或有效数据");
        }

        String tableName = nextTableName();
        List<FieldMeta> fields = buildFieldMeta(parsedFile.headers(), parsedFile.rows());

        log.info("开始动态建表: {}", tableName);
        StringBuilder createSql = new StringBuilder("CREATE TABLE `").append(tableName).append("` (");
        createSql.append("`sys_id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '系统自增主键', ");

        for (int i = 0; i < fields.size(); i++) {
            FieldMeta field = fields.get(i);
            createSql.append("`").append(field.columnName()).append("` VARCHAR(255) COMMENT '")
                    .append(escapeSqlComment(field.sourceFieldName())).append("'");
            if (i < fields.size() - 1) {
                createSql.append(", ");
            }
        }
        createSql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户上传动态数据表';");

        jdbcTemplate.execute(createSql.toString());

        log.info("开始拼接批量插入 SQL...");
        StringBuilder insertSql = new StringBuilder("INSERT INTO `").append(tableName).append("` (");
        StringBuilder placeholders = new StringBuilder("VALUES (");

        for (int i = 0; i < fields.size(); i++) {
            insertSql.append("`").append(fields.get(i).columnName()).append("`");
            placeholders.append("?");
            if (i < fields.size() - 1) {
                insertSql.append(", ");
                placeholders.append(", ");
            }
        }
        insertSql.append(") ").append(placeholders).append(");");

        List<Object[]> batchArgs = new ArrayList<>();
        for (List<String> rowData : parsedFile.rows()) {
            Object[] args = new Object[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                args[i] = i < rowData.size() ? rowData.get(i) : null;
            }
            batchArgs.add(args);
        }
        jdbcTemplate.batchUpdate(insertSql.toString(), batchArgs);

        saveCatalog(originalFilename, displayNameOverride, tableName, fields, parsedFile.rows().size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceName", originalFilename);
        result.put("displayName", displayNameOverride == null || displayNameOverride.isBlank()
                ? removeExtension(originalFilename)
                : displayNameOverride);
        result.put("tableName", tableName);
        result.put("rowCount", parsedFile.rows().size());
        result.put("fieldCount", fields.size());
        result.put("fields", fields.stream().map(FieldMeta::toMap).toList());
        return result;
    }

    public List<Map<String, Object>> listTables() {
        List<Map<String, Object>> tables = new ArrayList<>(permissionService.listAccessibleTables());
        tables.addAll(datasourceService.listEnabledQueryTables());
        return tables;
    }

    public List<Map<String, Object>> listFields(String tableName) {
        if (datasourceService.isOfficialSource(tableName)) {
            return datasourceService.listQueryFields(tableName);
        }
        assertKnownTable(tableName);
        return jdbcTemplate.queryForList("""
                SELECT source_field_name AS sourceFieldName, column_name AS columnName, field_type AS fieldType,
                       display_name AS displayName, field_comment AS fieldComment, `sensitive`, sort_order AS sortOrder
                FROM is_data_field
                WHERE table_name = ?
                ORDER BY sort_order ASC
                """, tableName);
    }

    public List<Map<String, Object>> preview(String tableName, int limit) {
        return preview(tableName, 1, limit);
    }

    public List<Map<String, Object>> preview(String tableName, int page, int pageSize) {
        if (datasourceService.isOfficialSource(tableName)) {
            return datasourceService.previewQueryTable(tableName, page, pageSize);
        }
        assertKnownTable(tableName);
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safeLimit;
        return jdbcTemplate.queryForList("SELECT * FROM `" + tableName + "` LIMIT " + safeLimit + " OFFSET " + offset);
    }

    public Map<String, Object> previewPage(String tableName, int page, int pageSize) {
        List<Map<String, Object>> rows = preview(tableName, page, pageSize);
        long total = countRows(tableName);
        return Map.of(
                "rows", rows,
                "page", Math.max(1, page),
                "pageSize", Math.max(1, Math.min(pageSize, 100)),
                "total", total
        );
    }

    public long countRows(String tableName) {
        if (datasourceService.isOfficialSource(tableName)) {
            return datasourceService.countQueryTable(tableName);
        }
        assertKnownTable(tableName);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + tableName + "`", Long.class);
        return count == null ? 0 : count;
    }

    public Map<String, Object> renameTable(String tableName, String displayName) {
        assertKnownTable(tableName);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        jdbcTemplate.update("""
                UPDATE is_data_table
                SET display_name = ?
                WHERE table_name = ?
                """, displayName.trim(), tableName);
        return Map.of("tableName", tableName, "displayName", displayName.trim());
    }

    public void deleteTable(String tableName) {
        assertKnownTable(tableName);
        jdbcTemplate.update("UPDATE is_data_table SET status = 'DELETED' WHERE table_name = ?", tableName);
    }

    public List<Map<String, Object>> listBusinessModels(boolean enterpriseOnly) {
        String sql = """
                SELECT id, model_name AS modelName, model_requirement AS modelRequirement,
                       table_name AS tableName, owner_id AS ownerId, model_json AS modelJson,
                       published, status, created_at AS createdAt, updated_at AS updatedAt
                FROM is_business_model
                WHERE status = 'ACTIVE'
                """ + (enterpriseOnly ? " AND published = 1" : "") + " ORDER BY updated_at DESC";
        return jdbcTemplate.queryForList(sql);
    }

    public Map<String, Object> createBusinessModel(Map<String, Object> request) {
        String tableName = Objects.toString(request.get("tableName"), "");
        String requirement = Objects.toString(request.get("requirement"), "");
        String modelName = Objects.toString(request.getOrDefault("modelName", "业务模型_" + System.currentTimeMillis()));
        assertKnownTable(tableName);
        List<String> headers = listFields(tableName).stream()
                .map(item -> Objects.toString(item.get("displayName"), Objects.toString(item.get("columnName"))))
                .toList();
        Map<String, Object> modelJson = buildModelJson(requirement, tableName, headers, List.of());
        return saveBusinessModel(modelName, requirement, tableName, modelJson);
    }

    public void publishBusinessModel(Long modelId, boolean published) {
        jdbcTemplate.update("UPDATE is_business_model SET published = ? WHERE id = ? AND status = 'ACTIVE'", published, modelId);
    }

    public Map<String, Object> applyBusinessModel(Long modelId, String tableName) {
        assertKnownTable(tableName);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT model_name AS modelName, model_requirement AS modelRequirement, model_json AS modelJson
                FROM is_business_model
                WHERE id = ? AND status = 'ACTIVE'
                """, modelId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("业务模型不存在：" + modelId);
        }
        Map<String, Object> row = rows.get(0);
        return saveBusinessModel(
                Objects.toString(row.get("modelName")) + "_套用",
                Objects.toString(row.get("modelRequirement")),
                tableName,
                buildModelJson(Objects.toString(row.get("modelRequirement")), tableName,
                        listFields(tableName).stream().map(item -> Objects.toString(item.get("displayName"))).toList(),
                        List.of())
        );
    }

    public boolean existsTable(String tableName) {
        if (!isSafeBizTableName(tableName)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_data_table WHERE table_name = ? AND status = 'ACTIVE'",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    public String latestTableName() {
        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT t.table_name
                FROM is_data_table t
                LEFT JOIN is_data_permission p
                       ON p.table_name = t.table_name
                      AND p.user_id = ?
                      AND p.permission_type = 'READ'
                WHERE t.status = 'ACTIVE'
                  AND (t.owner_id = ? OR p.id IS NOT NULL)
                ORDER BY t.created_at DESC
                LIMIT 1
                """, String.class, permissionService.currentUserId(), permissionService.currentUserId());
        if (tableNames.isEmpty()) {
            throw new IllegalArgumentException("请先上传 Excel/CSV 数据表");
        }
        return tableNames.get(0);
    }

    public void assertKnownTable(String tableName) {
        if (datasourceService.isOfficialSource(tableName)) {
            return;
        }
        if (!existsTable(tableName)) {
            throw new IllegalArgumentException("数据表不存在或无访问权限：" + tableName);
        }
        permissionService.assertCanAccessTable(tableName);
    }

    private ParsedFile parseFile(MultipartFile file, String originalFilename) throws IOException {
        String lowerName = originalFilename.toLowerCase();
        if (lowerName.endsWith(".csv")) {
            return parseCsv(file);
        }
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return parseExcel(file);
        }
        throw new IllegalArgumentException("仅支持 .xlsx、.xls、.csv 文件");
    }

    private ParsedFile parseExcel(MultipartFile file) throws IOException {
        DynamicDataListener listener = new DynamicDataListener();
        EasyExcel.read(file.getInputStream(), listener).sheet().doRead();

        Map<Integer, String> headMap = listener.getHeadMap();
        if (headMap == null || headMap.isEmpty()) {
            return new ParsedFile(Collections.emptyList(), Collections.emptyList());
        }

        int maxIndex = Collections.max(headMap.keySet());
        List<String> headers = new ArrayList<>();
        for (int i = 0; i <= maxIndex; i++) {
            headers.add(normalizeHeader(headMap.get(i), i));
        }

        List<List<String>> rows = new ArrayList<>();
        for (Map<Integer, String> row : listener.getDataList()) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < headers.size(); i++) {
                values.add(row.get(i));
            }
            rows.add(values);
        }
        return new ParsedFile(headers, rows);
    }

    private ParsedFile parseCsv(MultipartFile file) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(parseCsvLine(line));
                }
            }
        }
        if (lines.isEmpty()) {
            return new ParsedFile(Collections.emptyList(), Collections.emptyList());
        }

        List<String> headers = new ArrayList<>();
        List<String> rawHeaders = lines.get(0);
        for (int i = 0; i < rawHeaders.size(); i++) {
            headers.add(normalizeHeader(rawHeaders.get(i), i));
        }
        return new ParsedFile(headers, lines.subList(1, lines.size()));
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private List<FieldMeta> buildFieldMeta(List<String> headers, List<List<String>> rows) {
        List<FieldMeta> fields = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String columnName = String.format("col_%03d", i + 1);
            fields.add(new FieldMeta(headers.get(i), columnName, inferType(rows, i), headers.get(i), i));
        }
        return fields;
    }

    private String inferType(List<List<String>> rows, int index) {
        int checked = 0;
        int numberCount = 0;
        int dateCount = 0;
        for (List<String> row : rows) {
            if (index >= row.size()) {
                continue;
            }
            String value = row.get(index);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            checked++;
            if (value.matches("^-?\\d+(\\.\\d+)?$")) {
                numberCount++;
            }
            if (value.matches("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*$")) {
                dateCount++;
            }
        }
        if (checked > 0 && numberCount >= checked * 0.8) {
            return "NUMBER";
        }
        if (checked > 0 && dateCount >= checked * 0.8) {
            return "DATE";
        }
        return "TEXT";
    }

    private ParsedFile mergeSameHeader(List<ParsedFile> files) {
        List<String> headers = new ArrayList<>();
        for (ParsedFile file : files) {
            for (String header : file.headers()) {
                if (!headers.contains(header)) {
                    headers.add(header);
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (ParsedFile file : files) {
            for (List<String> sourceRow : file.rows()) {
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < file.headers().size(); i++) {
                    rowMap.put(file.headers().get(i), i < sourceRow.size() ? sourceRow.get(i) : "");
                }
                List<String> mergedRow = new ArrayList<>();
                for (String header : headers) {
                    mergedRow.add(rowMap.getOrDefault(header, ""));
                }
                rows.add(mergedRow);
            }
        }
        return new ParsedFile(headers, rows);
    }

    private ParsedFile joinByKey(List<ParsedFile> files, List<String> sourceNames, String joinKey) {
        if (joinKey == null || joinKey.isBlank()) {
            throw new IllegalArgumentException("按字段关联合并时必须填写关联字段");
        }
        String key = joinKey.trim();
        List<String> headers = new ArrayList<>();
        headers.add(key);
        List<Map<String, String>> fileRows = new ArrayList<>();
        Set<String> allKeys = new HashSet<>();

        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            ParsedFile file = files.get(fileIndex);
            int keyIndex = file.headers().indexOf(key);
            if (keyIndex < 0) {
                throw new IllegalArgumentException("文件 " + sourceNames.get(fileIndex) + " 缺少关联字段：" + key);
            }
            String prefix = removeExtension(sourceNames.get(fileIndex));
            for (String header : file.headers()) {
                if (!header.equals(key)) {
                    String mergedHeader = prefix + "_" + header;
                    if (!headers.contains(mergedHeader)) {
                        headers.add(mergedHeader);
                    }
                }
            }
            for (List<String> row : file.rows()) {
                String keyValue = keyIndex < row.size() ? Objects.toString(row.get(keyIndex), "") : "";
                if (keyValue.isBlank()) {
                    continue;
                }
                allKeys.add(keyValue);
                Map<String, String> rowMap = new LinkedHashMap<>();
                rowMap.put("__key", keyValue);
                for (int i = 0; i < file.headers().size(); i++) {
                    if (i == keyIndex) {
                        continue;
                    }
                    rowMap.put(prefix + "_" + file.headers().get(i), i < row.size() ? row.get(i) : "");
                }
                fileRows.add(rowMap);
            }
        }

        Map<String, Map<String, String>> joined = new LinkedHashMap<>();
        for (String keyValue : allKeys) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put(key, keyValue);
            joined.put(keyValue, row);
        }
        for (Map<String, String> fileRow : fileRows) {
            String keyValue = fileRow.remove("__key");
            joined.get(keyValue).putAll(fileRow);
        }

        List<List<String>> rows = new ArrayList<>();
        for (Map<String, String> rowMap : joined.values()) {
            List<String> row = new ArrayList<>();
            for (String header : headers) {
                row.add(rowMap.getOrDefault(header, ""));
            }
            rows.add(row);
        }
        return new ParsedFile(headers, rows);
    }

    private Map<String, Object> saveBusinessModel(String modelName, String requirement, String tableName, Map<String, Object> modelJson) {
        String json = toJson(modelJson);
        jdbcTemplate.update("""
                INSERT INTO is_business_model(model_name, model_requirement, table_name, owner_id, model_json)
                VALUES (?, ?, ?, ?, CAST(? AS JSON))
                """, modelName, requirement, tableName, permissionService.currentUserId(), json);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("modelName", modelName);
        result.put("modelRequirement", requirement);
        result.put("tableName", tableName);
        result.put("modelJson", json);
        result.put("published", false);
        return result;
    }

    private Map<String, Object> buildModelJson(String requirement, String tableName, List<String> headers, List<List<String>> rows) {
        List<String> metricFields = new ArrayList<>();
        List<String> dimensionFields = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String type = rows.isEmpty() ? "TEXT" : inferType(rows, i);
            if ("NUMBER".equals(type)) {
                metricFields.add(headers.get(i));
            } else {
                dimensionFields.add(headers.get(i));
            }
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("tableName", tableName);
        model.put("requirement", requirement);
        model.put("metricDefinitions", metricFields.stream().map(name -> Map.of(
                "name", name,
                "aggregation", "SUM",
                "description", "根据上传字段自动生成的指标口径"
        )).toList());
        model.put("dimensionSystem", dimensionFields.stream().map(name -> Map.of(
                "name", name,
                "usage", "筛选、分组、钻取"
        )).toList());
        model.put("analysisLogic", List.of(
                "按核心指标做总体趋势与结构拆解",
                "按维度识别贡献 Top 项与异常波动",
                "可在对话分析、智能诊断、企业模型库中复用"
        ));
        model.put("reusableParameters", Map.of("timeWindow", "可配置", "topN", 10, "compareMode", "环比/同比"));
        return model;
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("业务模型序列化失败：" + e.getMessage());
        }
    }

    private void saveCatalog(String originalFilename, String displayNameOverride, String tableName, List<FieldMeta> fields, int rowCount) {
        jdbcTemplate.update("""
                INSERT INTO is_data_table(source_name, display_name, table_name, owner_id, row_count, field_count, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, originalFilename,
                displayNameOverride == null || displayNameOverride.isBlank() ? removeExtension(originalFilename) : displayNameOverride,
                tableName, permissionService.currentUserId(), rowCount, fields.size());

        jdbcTemplate.batchUpdate("""
                INSERT INTO is_data_field(table_name, source_field_name, column_name, field_type, display_name, field_comment, `sensitive`, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, fields, fields.size(), (ps, field) -> {
            ps.setString(1, tableName);
            ps.setString(2, field.sourceFieldName());
            ps.setString(3, field.columnName());
            ps.setString(4, field.fieldType());
            ps.setString(5, field.displayName());
            ps.setString(6, "");
            ps.setBoolean(7, isSensitiveField(field.sourceFieldName()));
            ps.setInt(8, field.sortOrder());
        });
    }

    private boolean isSensitiveField(String fieldName) {
        String lower = fieldName.toLowerCase();
        return lower.contains("phone") || lower.contains("mobile") || lower.contains("idcard")
                || fieldName.contains("手机号") || fieldName.contains("身份证");
    }

    private boolean isSafeBizTableName(String tableName) {
        return tableName != null && SAFE_TABLE_NAME.matcher(tableName).matches();
    }

    private String normalizeHeader(String header, int index) {
        if (header == null || header.trim().isEmpty()) {
            return "未命名字段" + (index + 1);
        }
        return header.trim();
    }

    private String removeExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0) {
            return filename;
        }
        return filename.substring(0, dotIndex);
    }

    private String nextTableName() {
        return "biz_data_" + System.currentTimeMillis() + Math.abs((int) (System.nanoTime() % 1000));
    }

    private String escapeSqlComment(String comment) {
        return comment.replace("'", "''");
    }

    private record ParsedFile(List<String> headers, List<List<String>> rows) {
    }

    private record FieldMeta(String sourceFieldName, String columnName, String fieldType, String displayName, int sortOrder) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sourceFieldName", sourceFieldName);
            map.put("columnName", columnName);
            map.put("fieldType", fieldType);
            map.put("displayName", displayName);
            map.put("sortOrder", sortOrder);
            return map;
        }
    }
}
