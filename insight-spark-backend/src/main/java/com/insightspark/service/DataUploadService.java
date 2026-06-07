package com.insightspark.service;

import com.alibaba.excel.EasyExcel;
import com.insightspark.c.service.StackCRuntimeConfigProvider;
import com.insightspark.core.auth.AuthContext;
import com.insightspark.core.excel.DynamicDataListener;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
public class DataUploadService {

    private static final Logger log = LoggerFactory.getLogger(DataUploadService.class);
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("^biz_data_\\d+$");
    private static final double AUTO_APPLY_MODEL_MIN_SCORE = 0.52D;
    private static final int AUTO_APPLY_MODEL_CANDIDATE_LIMIT = 200;

    // 注入 JdbcTemplate，它是我们在数据库里“施工”的神器
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private SqlAuditService sqlAuditService;

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private StackCRuntimeConfigProvider runtimeConfig;

    private final ExecutorService uploadExecutor = Executors.newCachedThreadPool();

    @PostConstruct
    public void initCatalogTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_data_table` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `source_name` VARCHAR(255) NOT NULL,
                  `display_name` VARCHAR(255) NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL UNIQUE,
                  `owner_id` VARCHAR(64) NOT NULL DEFAULT '',
                  `row_count` INT NOT NULL DEFAULT 0,
                  `field_count` INT NOT NULL DEFAULT 0,
                  `file_size` BIGINT NOT NULL DEFAULT 0,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传数据表元信息';
                """);
        addColumnIfMissing("is_data_table", "file_size", "`file_size` BIGINT NOT NULL DEFAULT 0");

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
                addColumnIfMissing("is_data_field", "source_field_name", "`source_field_name` VARCHAR(255) NOT NULL DEFAULT ''");
                addColumnIfMissing("is_data_field", "column_name", "`column_name` VARCHAR(128) NOT NULL DEFAULT ''");
                addColumnIfMissing("is_data_field", "field_type", "`field_type` VARCHAR(32) NOT NULL DEFAULT 'TEXT'");
                addColumnIfMissing("is_data_field", "display_name", "`display_name` VARCHAR(255) NOT NULL DEFAULT ''");
                addColumnIfMissing("is_data_field", "field_comment", "`field_comment` VARCHAR(512) NULL");
                addColumnIfMissing("is_data_field", "synonyms", "`synonyms` VARCHAR(1000) NULL");
                addColumnIfMissing("is_data_field", "sensitive", "`sensitive` TINYINT(1) NOT NULL DEFAULT 0");
                addColumnIfMissing("is_data_field", "kg_sync_enabled", "`kg_sync_enabled` TINYINT(1) NOT NULL DEFAULT 1");
                addColumnIfMissing("is_data_field", "kg_sync_rule", "`kg_sync_rule` VARCHAR(1000) NULL");
                addColumnIfMissing("is_data_field", "sort_order", "`sort_order` INT NOT NULL DEFAULT 0");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_business_model` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `model_name` VARCHAR(255) NOT NULL,
                  `model_requirement` VARCHAR(2000) NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `owner_id` VARCHAR(64) NOT NULL DEFAULT '',
                  `model_json` JSON NOT NULL,
                  `published` TINYINT(1) NOT NULL DEFAULT 0,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_business_model_table` (`table_name`),
                  INDEX `idx_business_model_published` (`published`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='零代码业务模型与企业模型库';
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_analysis_template` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `template_name` VARCHAR(255) NOT NULL,
                  `file_name` VARCHAR(255) NULL,
                  `template_type` VARCHAR(50) NULL,
                  `template_content` LONGTEXT NULL,
                  `created_by` VARCHAR(64) NULL,
                  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务分析模板';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_file_process_task` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `task_id` VARCHAR(64) NOT NULL UNIQUE,
                  `status` VARCHAR(32) NOT NULL,
                  `progress` INT NOT NULL DEFAULT 0,
                  `message` VARCHAR(1000) NULL,
                  `result_json` JSON NULL,
                  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_file_process_task_id` (`task_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传解析进度任务';
                """);
    }

    public Map<String, Object> processFileWithTask(MultipartFile file) throws IOException {
        return processFileWithTask(file, null);
    }

    public Map<String, Object> processFileWithTask(MultipartFile file, String displayName) throws IOException {
        String taskId = createTask("WAITING", 0, "上传任务已创建");
        try {
            TaskProgressTracker progress = new DatabaseTaskProgressTracker(taskId);
            progress.received("文件已接收，准备解析");
            Map<String, Object> result = processFile(file, displayName, progress);
            updateTask(taskId, "SUCCESS", 100, "文件处理完成", result);
            Map<String, Object> wrapped = new LinkedHashMap<>(result);
            wrapped.put("taskId", taskId);
            wrapped.put("task", getUploadTask(taskId));
            return wrapped;
        } catch (Exception e) {
            updateTask(taskId, "FAILED", 100, e.getMessage(), null);
            throw e;
        }
    }

    public Map<String, Object> processFilesWithTask(List<MultipartFile> files, String mergeMode, String joinKey,
                                                    String modelRequirement) throws IOException {
        return processFilesWithTask(files, mergeMode, joinKey, modelRequirement, null);
    }

    public Map<String, Object> processFilesWithTask(List<MultipartFile> files, String mergeMode, String joinKey,
                                                    String modelRequirement, String displayName) throws IOException {
        String taskId = createTask("WAITING", 0, "批量上传任务已创建");
        try {
            TaskProgressTracker progress = new DatabaseTaskProgressTracker(taskId);
            progress.received("文件已接收，准备解析");
            Map<String, Object> result = processFiles(files, mergeMode, joinKey, modelRequirement, displayName, progress);
            updateTask(taskId, "SUCCESS", 100, "批量文件处理完成", result);
            Map<String, Object> wrapped = new LinkedHashMap<>(result);
            wrapped.put("taskId", taskId);
            wrapped.put("task", getUploadTask(taskId));
            return wrapped;
        } catch (Exception e) {
            updateTask(taskId, "FAILED", 100, e.getMessage(), null);
            throw e;
        }
    }

    public Map<String, Object> startAsyncProcessFile(MultipartFile file) throws IOException {
        return startAsyncProcessFile(file, null);
    }

    public Map<String, Object> startAsyncProcessFile(MultipartFile file, String displayName) throws IOException {
        String taskId = createTask("WAITING", 0, "上传任务已创建");
        AuthContext.UserPrincipal principal = AuthContext.get();
        StoredMultipartFile storedFile = StoredMultipartFile.from(file);
        uploadExecutor.submit(() -> runWithAuth(principal, () -> {
            try {
                TaskProgressTracker progress = new DatabaseTaskProgressTracker(taskId);
                progress.received("文件已接收，准备解析");
                Map<String, Object> result = processFile(storedFile, displayName, progress);
                updateTask(taskId, "SUCCESS", 100, "文件处理完成", result);
            } catch (Exception e) {
                updateTask(taskId, "FAILED", 100, e.getMessage(), null);
            }
        }));
        return getUploadTask(taskId);
    }

    public Map<String, Object> startAsyncProcessFiles(List<MultipartFile> files, String mergeMode, String joinKey,
                                                      String modelRequirement) throws IOException {
        return startAsyncProcessFiles(files, mergeMode, joinKey, modelRequirement, null);
    }

    public Map<String, Object> startAsyncProcessFiles(List<MultipartFile> files, String mergeMode, String joinKey,
                                                      String modelRequirement, String displayName) throws IOException {
        String taskId = createTask("WAITING", 0, "批量上传任务已创建");
        AuthContext.UserPrincipal principal = AuthContext.get();
        List<MultipartFile> storedFiles = files.stream().map(file -> {
            try {
                return StoredMultipartFile.from(file);
            } catch (IOException e) {
                throw new IllegalArgumentException("文件暂存失败：" + e.getMessage(), e);
            }
        }).map(item -> (MultipartFile) item).toList();
        uploadExecutor.submit(() -> runWithAuth(principal, () -> {
            try {
                TaskProgressTracker progress = new DatabaseTaskProgressTracker(taskId);
                progress.received("文件已接收，准备解析");
                Map<String, Object> result = processFiles(storedFiles, mergeMode, joinKey, modelRequirement, displayName, progress);
                updateTask(taskId, "SUCCESS", 100, "批量文件处理完成", result);
            } catch (Exception e) {
                updateTask(taskId, "FAILED", 100, e.getMessage(), null);
            }
        }));
        return getUploadTask(taskId);
    }

    private void runWithAuth(AuthContext.UserPrincipal principal, Runnable action) {
        try {
            AuthContext.set(principal);
            action.run();
        } finally {
            AuthContext.clear();
        }
    }

    public Map<String, Object> getUploadTask(String taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT task_id AS taskId, status, progress, message, result_json AS resultJson,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_file_process_task
                WHERE task_id = ?
                """, taskId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("上传任务不存在：" + taskId);
        }
        return rows.get(0);
    }

    private String createTask(String status, int progress, String message) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO is_file_process_task(task_id, status, progress, message)
                VALUES (?, ?, ?, ?)
                """, taskId, status, progress, message);
        return taskId;
    }

    private void updateTask(String taskId, String status, int progress, String message, Map<String, Object> result) {
        String resultJson = result == null ? null : toJson(result);
        jdbcTemplate.update("""
                UPDATE is_file_process_task
                SET status = ?, progress = ?, message = ?, result_json = CASE WHEN ? IS NULL THEN result_json ELSE CAST(? AS JSON) END
                WHERE task_id = ?
                """, status, Math.max(0, Math.min(progress, 100)), message, resultJson, resultJson, taskId);
    }

    public Map<String, Object> processFile(MultipartFile file) throws IOException {
        return processFile(file, null);
    }

    public Map<String, Object> processFile(MultipartFile file, String displayName) throws IOException {
        return processFile(file, displayName, TaskProgressTracker.noop());
    }

    private Map<String, Object> processFile(MultipartFile file, String displayName, TaskProgressTracker progress) throws IOException {
        ensureUploadRoleAllowed();
        String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "未命名文件");
        ParsedFile parsedFile = parseFile(file, originalFilename, progress);

        if (parsedFile.headers().isEmpty() || parsedFile.rows().isEmpty()) {
            throw new IllegalArgumentException("解析失败：未找到表头或有效数据");
        }

        return persistParsedFile(originalFilename, parsedFile, displayName, file.getSize(), progress);
    }

    public Map<String, Object> processFiles(List<MultipartFile> files, String mergeMode, String joinKey,
                                            String modelRequirement) throws IOException {
        return processFiles(files, mergeMode, joinKey, modelRequirement, null);
    }

    public Map<String, Object> processFiles(List<MultipartFile> files, String mergeMode, String joinKey,
                                            String modelRequirement, String displayNameOverride) throws IOException {
        return processFiles(files, mergeMode, joinKey, modelRequirement, displayNameOverride, TaskProgressTracker.noop());
    }

    private Map<String, Object> processFiles(List<MultipartFile> files, String mergeMode, String joinKey,
                                             String modelRequirement, String displayNameOverride,
                                             TaskProgressTracker progress) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个 Excel/CSV 文件");
        }
        if (files.size() > 5) {
            throw new IllegalArgumentException("批量上传最多支持 5 个文件");
        }

        List<ParsedFile> parsedFiles = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        long totalFileSize = 0L;
        for (MultipartFile file : files) {
            String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "未命名文件");
            ParsedFile parsedFile = parseFile(file, originalFilename, progress);
            if (parsedFile.headers().isEmpty() || parsedFile.rows().isEmpty()) {
                throw new IllegalArgumentException("文件 " + originalFilename + " 未找到表头或有效数据");
            }
            sourceNames.add(originalFilename);
            parsedFiles.add(parsedFile);
            totalFileSize += Math.max(0L, file.getSize());
        }

        String normalizedMode = mergeMode == null || mergeMode.isBlank() ? "SAME_HEADER" : mergeMode.trim().toUpperCase();
        ParsedFile merged = "KEY_JOIN".equals(normalizedMode)
                ? joinByKey(parsedFiles, sourceNames, joinKey)
                : mergeSameHeader(parsedFiles);
        String displayName = displayNameOverride != null && !displayNameOverride.isBlank()
                ? displayNameOverride.trim()
                : files.size() == 1
                ? removeExtension(sourceNames.get(0))
                : "多文件合并_" + System.currentTimeMillis();
        progress.merging(merged.rows().size());
        Map<String, Object> result = persistParsedFile(String.join(" + ", sourceNames), merged, displayName, totalFileSize, progress);
        if (modelRequirement != null && !modelRequirement.isBlank()) {
            progress.metadata("正在按业务需求生成模型");
            result.put("businessModel", saveBusinessModel(
                    "模型_" + result.get("displayName"),
                    modelRequirement,
                    Objects.toString(result.get("tableName")),
                    buildAcceptanceModelJson(modelRequirement, Objects.toString(result.get("tableName")), merged.headers(), merged.rows())
            ));
        }
        result.put("mergeMode", normalizedMode);
        result.put("sourceFiles", sourceNames);
        return result;
    }

    private Map<String, Object> persistParsedFile(String originalFilename, ParsedFile parsedFile, String displayNameOverride) {
        return persistParsedFile(originalFilename, parsedFile, displayNameOverride, 0L, TaskProgressTracker.noop());
    }

    private Map<String, Object> persistParsedFile(String originalFilename, ParsedFile parsedFile, String displayNameOverride,
                                                  TaskProgressTracker progress) {
        return persistParsedFile(originalFilename, parsedFile, displayNameOverride, 0L, progress);
    }

    private Map<String, Object> persistParsedFile(String originalFilename, ParsedFile parsedFile, String displayNameOverride,
                                                  long fileSize, TaskProgressTracker progress) {
        if (parsedFile.headers().isEmpty() || parsedFile.rows().isEmpty()) {
            throw new IllegalArgumentException("解析失败：未找到表头或有效数据");
        }

        String tableName = nextTableName();
        List<FieldMeta> fields = buildFieldMeta(parsedFile.headers(), parsedFile.rows());
        progress.buildingTable(parsedFile.rows().size());

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
        int insertedRows = 0;
        int chunkSize = 500;
        for (int start = 0; start < batchArgs.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, batchArgs.size());
            jdbcTemplate.batchUpdate(insertSql.toString(), batchArgs.subList(start, end));
            insertedRows = end;
            progress.persistedRows(insertedRows, batchArgs.size());
        }

        progress.metadata("正在保存字段元信息");
        saveCatalog(originalFilename, displayNameOverride, tableName, fields, parsedFile.rows().size(), fileSize);
        try {
            progress.metadata("正在同步知识图谱");
            knowledgeGraphService.syncGraph();
        } catch (Exception e) {
            log.warn("上传数据已保存，但同步 Neo4j 知识图谱失败：{}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceName", originalFilename);
        result.put("displayName", displayNameOverride == null || displayNameOverride.isBlank()
                ? removeExtension(originalFilename)
                : displayNameOverride);
        result.put("tableName", tableName);
        result.put("rowCount", parsedFile.rows().size());
        result.put("fieldCount", fields.size());
        result.put("fileSize", fileSize);
        result.put("fields", fields.stream().map(FieldMeta::toMap).toList());
        Map<String, Object> cleaningStrategy = generateCleaningStrategy(tableName);
        result.put("cleaningStrategy", cleaningStrategy);
        if (cleaningActions(cleaningStrategy).isEmpty()) {
            activateCleanedTable(tableName);
            result.put("cleaningStatus", "ACTIVE");
        } else {
            result.put("cleaningStatus", "PENDING_CLEANING");
        }
        try {
            result.put("autoAppliedModel", autoApplyBestBusinessModelForTable(tableName));
        } catch (Exception ex) {
            log.warn("新表自动适配业务模型失败，table={}, error={}", tableName, ex.getMessage());
            Map<String, Object> autoApplyError = new LinkedHashMap<>();
            autoApplyError.put("matched", false);
            autoApplyError.put("applied", false);
            autoApplyError.put("tableName", tableName);
            autoApplyError.put("error", ex.getMessage());
            result.put("autoAppliedModel", autoApplyError);
        }
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
                       display_name AS displayName, field_comment AS fieldComment, synonyms,
                       `sensitive`, kg_sync_enabled AS kgSyncEnabled, kg_sync_rule AS kgSyncRule,
                       sort_order AS sortOrder
                FROM is_data_field
                WHERE table_name = ?
                ORDER BY sort_order ASC
                """, tableName);
    }

    public Map<String, Object> updateFieldMeta(String tableName, String columnName, Map<String, Object> request) {
        assertKnownTable(tableName);
        List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                SELECT id, field_type AS fieldType, display_name AS displayName,
                       field_comment AS fieldComment, synonyms, `sensitive`,
                       kg_sync_enabled AS kgSyncEnabled, kg_sync_rule AS kgSyncRule
                FROM is_data_field
                WHERE table_name = ? AND column_name = ?
                LIMIT 1
                """, tableName, columnName);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("字段不存在：" + columnName);
        }
        Map<String, Object> current = fields.get(0);
        String displayName = Objects.toString(request.getOrDefault("displayName", current.get("displayName")), "").trim();
        String fieldType = Objects.toString(request.getOrDefault("fieldType", current.get("fieldType")), "TEXT").trim().toUpperCase();
        String fieldComment = Objects.toString(request.getOrDefault("fieldComment", current.get("fieldComment")), "").trim();
        String synonyms = Objects.toString(request.getOrDefault("synonyms", current.get("synonyms")), "").trim();
        boolean sensitive = parseBooleanFlag(request.getOrDefault("sensitive", current.get("sensitive")));
        boolean kgSyncEnabled = parseBooleanFlag(request.getOrDefault("kgSyncEnabled", current.get("kgSyncEnabled")));
        String kgSyncRule = Objects.toString(request.getOrDefault("kgSyncRule", current.get("kgSyncRule")), "").trim();
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("字段中文名不能为空");
        }
        if (!List.of("TEXT", "NUMBER", "DATE").contains(fieldType)) {
            throw new IllegalArgumentException("字段类型仅支持 TEXT / NUMBER / DATE");
        }
        jdbcTemplate.update("""
                UPDATE is_data_field
                SET display_name = ?, field_type = ?, field_comment = ?, synonyms = ?,
                    `sensitive` = ?, kg_sync_enabled = ?, kg_sync_rule = ?
                WHERE table_name = ? AND column_name = ?
                """, displayName, fieldType, fieldComment, synonyms, sensitive ? 1 : 0,
                kgSyncEnabled ? 1 : 0, kgSyncRule, tableName, columnName);
        return Map.of(
                "tableName", tableName,
                "columnName", columnName,
                "displayName", displayName,
                "fieldType", fieldType,
                "fieldComment", fieldComment,
                "synonyms", synonyms,
                "sensitive", sensitive,
                "kgSyncEnabled", kgSyncEnabled,
                "kgSyncRule", kgSyncRule
        );
    }

    public List<Map<String, Object>> preview(String tableName, int limit) {
        return preview(tableName, 1, limit);
    }

    public List<Map<String, Object>> preview(String tableName, int page, int pageSize) {
        List<Map<String, Object>> rows;
        if (datasourceService.isOfficialSource(tableName)) {
            rows = datasourceService.previewQueryTable(tableName, page, pageSize);
        } else {
            assertKnownTable(tableName);
            int safePage = Math.max(1, page);
            int safeLimit = Math.max(1, Math.min(pageSize, 100));
            int offset = (safePage - 1) * safeLimit;
            String sql = sqlAuditService.applyDataRowPolicies(tableName,
                    "SELECT * FROM `" + tableName + "` LIMIT " + safeLimit + " OFFSET " + offset);
            long startedAt = System.currentTimeMillis();
            rows = jdbcTemplate.queryForList(sql);
            sqlAuditService.record("上传表数据预览", tableName, "upload-preview", sql,
                    sqlAuditService.inspect(sql, tableName), "SUCCESS",
                    System.currentTimeMillis() - startedAt, null);
        }
        return sqlAuditService.maskRowsByFields(rows, listFields(tableName));
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

    public byte[] exportTableCsv(String tableName) {
        assertKnownTable(tableName);
        List<Map<String, Object>> fields = listFields(tableName);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("数据表没有可导出的字段: " + tableName);
        }

        List<String> columns = fields.stream()
                .map(field -> Objects.toString(field.get("columnName"), ""))
                .filter(column -> !column.isBlank())
                .toList();
        List<String> headers = fields.stream()
                .map(field -> Objects.toString(field.getOrDefault("displayName", field.get("columnName")), ""))
                .toList();

        List<Map<String, Object>> rows;
        if (datasourceService.isOfficialSource(tableName)) {
            rows = preview(tableName, 1, 50_000);
        } else {
            String columnSql = String.join(", ", columns.stream().map(this::quoteColumn).toList());
            String sql = sqlAuditService.applyDataRowPolicies(tableName,
                    "SELECT " + columnSql + " FROM `" + tableName + "` LIMIT 50000");
            long startedAt = System.currentTimeMillis();
            rows = jdbcTemplate.queryForList(sql);
            sqlAuditService.record("上传表数据导出", tableName, "upload-export", sql,
                    sqlAuditService.inspect(sql, tableName), "SUCCESS",
                    System.currentTimeMillis() - startedAt, null);
        }
        rows = sqlAuditService.maskRows(tableName, rows);

        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvLine(csv, headers);
        for (Map<String, Object> row : rows) {
            appendCsvLine(csv, columns.stream().map(column -> Objects.toString(row.get(column), "")).toList());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
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
        StringBuilder sql = new StringBuilder("""
                SELECT id, model_name AS modelName, model_requirement AS modelRequirement,
                       table_name AS tableName, owner_id AS ownerId, model_json AS modelJson,
                       published, status, created_at AS createdAt, updated_at AS updatedAt
                FROM is_business_model
                WHERE status = 'ACTIVE'
                """);
        List<Object> args = new ArrayList<>();
        if (enterpriseOnly) {
            sql.append(" AND published = 1");
        } else if (!AuthContext.isAdmin()) {
            sql.append(" AND owner_id = ?");
            args.add(permissionService.currentUserId());
        }
        sql.append(" ORDER BY updated_at DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> createBusinessModel(Map<String, Object> request) {
        String tableName = Objects.toString(request.get("tableName"), "");
        String requirement = Objects.toString(request.get("requirement"), "");
        String modelName = Objects.toString(request.getOrDefault("modelName", "业务模型_" + System.currentTimeMillis()));
        assertKnownTable(tableName);
        Map<String, Object> modelJson = new LinkedHashMap<>();
        modelJson.put("tableName", tableName);
        modelJson.put("requirement", requirement);
        if (request.containsKey("dictionaryEntries")) {
            modelJson.put("dictionaryEntries", sanitizeDictionaryEntries(request.get("dictionaryEntries"), tableName));
        }
        if (request.containsKey("metricDefinitions")) {
            modelJson.put("metricDefinitions", sanitizeMetricDefinitions(request.get("metricDefinitions"), tableName));
        }
        modelJson.putIfAbsent("dictionaryEntries", List.of());
        modelJson.putIfAbsent("metricDefinitions", List.of());
        return saveBusinessModel(modelName, requirement, tableName, modelJson);
    }

    public Map<String, Object> uploadTemplate(MultipartFile file) throws IOException {
        String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "analysis-template.txt");
        String lowerName = originalFilename.toLowerCase();
        if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".md")) {
            throw new IllegalArgumentException("分析模板当前仅支持 .txt / .md 文件");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String templateName = removeExtension(originalFilename);
        String templateType = lowerName.endsWith(".md") ? "MARKDOWN" : "TEXT";
        jdbcTemplate.update("""
                INSERT INTO is_analysis_template(template_name, file_name, template_type, template_content, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, templateName, originalFilename, templateType, content, permissionService.currentUserId());
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("id", id, "templateName", templateName, "fileName", originalFilename, "templateType", templateType);
    }

    public List<Map<String, Object>> listTemplates() {
        return jdbcTemplate.queryForList("""
                SELECT id, template_name AS templateName, file_name AS fileName,
                       template_type AS templateType, created_by AS createdBy, created_at AS createdAt
                FROM is_analysis_template
                WHERE created_by = ? OR ? = 'ADMIN'
                ORDER BY created_at DESC
                LIMIT 100
                """, permissionService.currentUserId(), permissionService.currentRole());
    }

    public Map<String, Object> createBusinessModelFromTemplate(Map<String, Object> request) {
        String tableName = Objects.toString(request.get("tableName"), "");
        Long templateId = Long.parseLong(Objects.toString(request.get("templateId"), "0"));
        String requirement = Objects.toString(request.getOrDefault("requirement", ""));
        assertKnownTable(tableName);
        List<Map<String, Object>> templates = jdbcTemplate.queryForList("""
                SELECT template_name AS templateName, template_content AS templateContent
                FROM is_analysis_template
                WHERE id = ?
                """, templateId);
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("分析模板不存在：" + templateId);
        }
        Map<String, Object> template = templates.get(0);
        String templateName = Objects.toString(template.get("templateName"), "分析模板");
        String templateContent = Objects.toString(template.get("templateContent"), "");
        Map<String, Object> modelJson = buildTemplateModelJson(requirement, tableName, templateContent, listFields(tableName));
        return saveBusinessModel(templateName + "_生成模型", requirement, tableName, modelJson);
    }

    public void publishBusinessModel(Long modelId, boolean published) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, owner_id AS ownerId
                FROM is_business_model
                WHERE id = ? AND status = 'ACTIVE'
                """, modelId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("业务模型不存在：" + modelId);
        }
        ensureCanOperateBusinessModel(rows.get(0));
        jdbcTemplate.update("UPDATE is_business_model SET published = ? WHERE id = ? AND status = 'ACTIVE'", published, modelId);
    }

    public Map<String, Object> getBusinessModelDetail(Long modelId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, model_name AS modelName, model_requirement AS modelRequirement,
                       table_name AS tableName, owner_id AS ownerId, model_json AS modelJson,
                       published, status, created_at AS createdAt, updated_at AS updatedAt
                FROM is_business_model
                WHERE id = ? AND status = 'ACTIVE'
                """, modelId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("业务模型不存在：" + modelId);
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        ensureCanReuseBusinessModel(row);
        return row;
    }

    public Map<String, Object> updateBusinessModel(Long modelId, Map<String, Object> request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, model_name AS modelName, model_requirement AS modelRequirement,
                       table_name AS tableName, owner_id AS ownerId, model_json AS modelJson,
                       published, status
                FROM is_business_model
                WHERE id = ? AND status = 'ACTIVE'
                """, modelId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("业务模型不存在：" + modelId);
        }
        Map<String, Object> row = rows.get(0);
        ensureCanOperateBusinessModel(row);

        String modelName = Objects.toString(request.getOrDefault("modelName", row.get("modelName")), "").trim();
        String requirement = Objects.toString(request.getOrDefault("modelRequirement", row.get("modelRequirement")), "").trim();
        if (modelName.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        String tableName = Objects.toString(row.get("tableName"), "");

        Map<String, Object> modelJson = parseModelJson(row.get("modelJson"));
        modelJson.put("tableName", tableName);
        modelJson.put("requirement", requirement);

        if (request.containsKey("dictionaryEntries")) {
            modelJson.put("dictionaryEntries", sanitizeDictionaryEntries(request.get("dictionaryEntries"), tableName));
        }
        if (request.containsKey("metricDefinitions")) {
            modelJson.put("metricDefinitions", sanitizeMetricDefinitions(request.get("metricDefinitions"), tableName));
        }
        if (request.containsKey("dimensionSystem")) {
            modelJson.put("dimensionSystem", sanitizeDimensionSystem(request.get("dimensionSystem"), tableName));
        }
        if (request.containsKey("analysisLogic")) {
            modelJson.put("analysisLogic", sanitizeAnalysisLogic(request.get("analysisLogic")));
        }

        String json = toJson(modelJson);
        jdbcTemplate.update("""
                UPDATE is_business_model
                SET model_name = ?, model_requirement = ?, model_json = CAST(? AS JSON)
                WHERE id = ? AND status = 'ACTIVE'
                """, modelName, requirement, json, modelId);

        try {
            knowledgeGraphService.syncGraph();
        } catch (Exception e) {
            log.warn("业务模型更新已保存，但图谱同步失败：{}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", modelId);
        result.put("modelName", modelName);
        result.put("modelRequirement", requirement);
        result.put("tableName", tableName);
        result.put("modelJson", json);
        result.put("published", parseBooleanFlag(row.get("published")));
        return result;
    }

    public void deleteBusinessModel(Long modelId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, owner_id AS ownerId
                FROM is_business_model
                WHERE id = ? AND status = 'ACTIVE'
                """, modelId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("业务模型不存在：" + modelId);
        }
        ensureCanOperateBusinessModel(rows.get(0));
        jdbcTemplate.update("""
                UPDATE is_business_model
                SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, modelId);
    }

    public Map<String, Object> applyBusinessModel(Long modelId, String tableName) {
        assertKnownTable(tableName);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT model_name AS modelName, model_requirement AS modelRequirement, model_json AS modelJson,
                       owner_id AS ownerId, published
                FROM is_business_model
                WHERE id = ? AND status = 'ACTIVE'
                """, modelId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("业务模型不存在：" + modelId);
        }
        Map<String, Object> row = rows.get(0);
        ensureCanReuseBusinessModel(row);
        String requirement = Objects.toString(row.get("modelRequirement"), "").trim();
        String sourceModelName = Objects.toString(row.get("modelName"), "").trim();
        List<String> headers = listFields(tableName).stream()
                .map(item -> Objects.toString(item.getOrDefault("displayName", item.get("columnName")), ""))
                .filter(name -> !name.isBlank())
                .toList();
        Map<String, Object> sourceModelJson = parseModelJson(row.get("modelJson"));
        Map<String, Object> targetModelJson = buildAcceptanceModelJson(requirement, tableName, headers, List.of());
        targetModelJson.put("tableName", tableName);
        targetModelJson.put("requirement", requirement);

        List<Map<String, Object>> dictionaryEntries = sanitizeDictionaryEntries(sourceModelJson.get("dictionaryEntries"), tableName);
        if (!dictionaryEntries.isEmpty()) {
            targetModelJson.put("dictionaryEntries", dictionaryEntries);
        }

        List<Map<String, Object>> metricDefinitions = sanitizeMetricDefinitions(sourceModelJson.get("metricDefinitions"), tableName);
        if (!metricDefinitions.isEmpty()) {
            targetModelJson.put("metricDefinitions", metricDefinitions);
        }

        List<Map<String, Object>> dimensionSystem = sanitizeDimensionSystem(sourceModelJson.get("dimensionSystem"), tableName);
        if (!dimensionSystem.isEmpty()) {
            targetModelJson.put("dimensionSystem", dimensionSystem);
        }

        List<String> analysisLogic = sanitizeAnalysisLogic(sourceModelJson.get("analysisLogic"));
        if (!analysisLogic.isEmpty()) {
            targetModelJson.put("analysisLogic", analysisLogic);
        }

        Map<String, Object> reusableParameters = sanitizeReusableParameters(sourceModelJson.get("reusableParameters"));
        if (!reusableParameters.isEmpty()) {
            targetModelJson.put("reusableParameters", reusableParameters);
        }

        List<Map<String, Object>> chartSuggestions = sanitizeChartSuggestions(sourceModelJson.get("chartSuggestions"), tableName);
        if (!chartSuggestions.isEmpty()) {
            targetModelJson.put("chartSuggestions", chartSuggestions);
        }

        return saveBusinessModel(
                (sourceModelName.isBlank() ? "业务模型" : sourceModelName) + "_套用",
                requirement,
                tableName,
                targetModelJson
        );
    }

    public Map<String, Object> autoApplyBestBusinessModelForTable(String tableName) {
        assertKnownTable(tableName);
        String normalizedTableName = Objects.toString(tableName, "").trim();
        if (normalizedTableName.isBlank()) {
            throw new IllegalArgumentException("数据表名不能为空");
        }

        List<Map<String, Object>> targetFields = loadTableFieldMeta(normalizedTableName);
        Set<String> targetAliases = buildNormalizedFieldAliasSet(targetFields);
        Map<String, Object> baseResult = new LinkedHashMap<>();
        baseResult.put("tableName", normalizedTableName);
        baseResult.put("targetFieldCount", targetAliases.size());

        if (targetAliases.isEmpty()) {
            baseResult.put("matched", false);
            baseResult.put("applied", false);
            baseResult.put("reason", "目标表无可匹配字段");
            return baseResult;
        }

        List<Map<String, Object>> candidates = listAutoApplyModelCandidates(normalizedTableName);
        if (candidates.isEmpty()) {
            baseResult.put("matched", false);
            baseResult.put("applied", false);
            baseResult.put("reason", "暂无可用业务模型");
            return baseResult;
        }

        AutoApplyMatch best = null;
        for (Map<String, Object> candidate : candidates) {
            Long modelId = toLong(candidate.get("id"));
            if (modelId == null || modelId <= 0) {
                continue;
            }
            String sourceTable = Objects.toString(candidate.get("tableName"), "").trim();
            if (sourceTable.isBlank() || normalizedTableName.equals(sourceTable)) {
                continue;
            }
            Set<String> sourceAliases = buildNormalizedFieldAliasSet(loadTableFieldMeta(sourceTable));
            if (sourceAliases.isEmpty()) {
                continue;
            }
            Set<String> overlap = new HashSet<>(sourceAliases);
            overlap.retainAll(targetAliases);
            if (overlap.isEmpty()) {
                continue;
            }
            double union = sourceAliases.size() + targetAliases.size() - overlap.size();
            if (union <= 0) {
                continue;
            }
            double jaccard = overlap.size() / union;
            double targetCoverage = overlap.size() / (double) targetAliases.size();
            double sourceCoverage = overlap.size() / (double) sourceAliases.size();
            double score = 0.6D * jaccard + 0.25D * targetCoverage + 0.15D * sourceCoverage;
            if (score < AUTO_APPLY_MODEL_MIN_SCORE) {
                continue;
            }
            AutoApplyMatch current = new AutoApplyMatch(modelId, candidate, score, overlap.size(),
                    targetCoverage, sourceCoverage, jaccard);
            if (best == null || current.score > best.score || (
                    Math.abs(current.score - best.score) < 1e-9
                            && compareUpdatedAt(candidate, best.candidate) > 0
            )) {
                best = current;
            }
        }

        if (best == null) {
            baseResult.put("matched", false);
            baseResult.put("applied", false);
            baseResult.put("reason", "未找到字段匹配度足够高的业务模型");
            return baseResult;
        }

        Map<String, Object> applied = applyBusinessModel(best.modelId, normalizedTableName);
        Map<String, Object> result = new LinkedHashMap<>(baseResult);
        result.put("matched", true);
        result.put("applied", true);
        result.put("score", roundDouble(best.score));
        result.put("jaccard", roundDouble(best.jaccard));
        result.put("targetCoverage", roundDouble(best.targetCoverage));
        result.put("sourceCoverage", roundDouble(best.sourceCoverage));
        result.put("overlapFieldCount", best.overlapCount);
        result.put("sourceModelId", best.modelId);
        result.put("sourceModelName", Objects.toString(best.candidate.get("modelName"), ""));
        result.put("sourceModelTable", Objects.toString(best.candidate.get("tableName"), ""));
        result.put("appliedModelId", applied.get("id"));
        result.put("appliedModelName", applied.get("modelName"));
        return result;
    }

    public boolean existsTable(String tableName) {
        if (!isSafeBizTableName(tableName)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_data_table WHERE table_name = ? AND status IN ('ACTIVE', 'PENDING_CLEANING')",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    public String latestTableName() {
        List<Map<String, Object>> tables = listTables();
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("请先上传 Excel/CSV 数据表或授权官方库数据表");
        }
        return Objects.toString(tables.get(0).get("tableName"), "");
    }

    public void assertKnownTable(String tableName) {
        if (datasourceService.isOfficialSource(tableName)) {
            datasourceService.assertCanAccessOfficialSource(tableName);
            return;
        }
        if (!existsTable(tableName)) {
            throw new IllegalArgumentException("数据表不存在或无访问权限：" + tableName);
        }
        if (!canOperatePendingCleaningTable(tableName)) {
            permissionService.assertCanAccessTable(tableName);
        }
    }

    private boolean canOperatePendingCleaningTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        if (AuthContext.isAdmin()) {
            return true;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_data_table
                WHERE table_name = ? AND owner_id = ? AND status IN ('ACTIVE', 'PENDING_CLEANING')
                """, Integer.class, tableName, permissionService.currentUserId());
        return count != null && count > 0;
    }

    private void ensureCanOperateBusinessModel(Map<String, Object> modelRow) {
        if (AuthContext.isAdmin()) {
            return;
        }
        String ownerId = Objects.toString(modelRow.get("ownerId"), "");
        if (!ownerId.equals(permissionService.currentUserId())) {
            throw new IllegalArgumentException("无权限修改该业务模型");
        }
    }

    private void ensureCanReuseBusinessModel(Map<String, Object> modelRow) {
        if (AuthContext.isAdmin()) {
            return;
        }
        boolean published = parseBooleanFlag(modelRow.get("published"));
        if (published) {
            return;
        }
        String ownerId = Objects.toString(modelRow.get("ownerId"), "");
        if (ownerId.equals(permissionService.currentUserId())) {
            return;
        }
        throw new IllegalArgumentException("无权套用该业务模型，仅本人模型或已发布企业模型可复用");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseModelJson(Object modelJsonObj) {
        if (modelJsonObj instanceof Map<?, ?> map) {
            Map<String, Object> parsed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                parsed.put(Objects.toString(entry.getKey(), ""), entry.getValue());
            }
            return parsed;
        }
        String json = Objects.toString(modelJsonObj, "").trim();
        if (json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("业务模型内容解析失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeDictionaryEntries(Object value) {
        return sanitizeDictionaryEntries(value, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeDictionaryEntries(Object value, String tableName) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tableFields = loadTableFieldMeta(tableName);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String term = Objects.toString(raw.get("term"), "").trim();
            String rawField = extractBusinessModelFieldRef(raw);
            String synonymsText = Objects.toString(raw.get("synonyms"), "").trim();
            List<String> synonyms = splitAndTrim(synonymsText);
            String inferredField = resolveFieldBindingByCandidates(
                    tableFields,
                    joinNonBlank(List.of(term, synonymsText, String.join(" ", synonyms)))
            );
            String field = ensureResolvedFieldBinding(tableFields, rawField, inferredField, false);
            if (term.isBlank() && field.isBlank() && synonyms.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", term);
            row.put("field", field);
            row.put("synonyms", String.join(",", synonyms));
            result.add(row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeMetricDefinitions(Object value) {
        return sanitizeMetricDefinitions(value, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeMetricDefinitions(Object value, String tableName) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tableFields = loadTableFieldMeta(tableName);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String name = Objects.toString(raw.get("name"), "").trim();
            String rawField = extractBusinessModelFieldRef(raw);
            String aggregation = Objects.toString(raw.get("aggregation"), "").trim();
            String formula = Objects.toString(raw.get("formula"), "").trim();
            if (name.isBlank()) {
                continue;
            }
            String inferredField = resolveFieldBindingByCandidates(
                    tableFields,
                    joinNonBlank(List.of(name, formula, String.join(" ", extractFormulaTokens(formula))))
            );
            String field = ensureResolvedFieldBinding(tableFields, rawField, inferredField, true);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("field", field);
            row.put("aggregation", aggregation.isBlank() ? "SUM" : aggregation.toUpperCase());
            row.put("formula", formula);
            result.add(row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeDimensionSystem(Object value) {
        return sanitizeDimensionSystem(value, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeDimensionSystem(Object value, String tableName) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tableFields = loadTableFieldMeta(tableName);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String name = Objects.toString(raw.get("name"), "").trim();
            String rawField = extractBusinessModelFieldRef(raw);
            String inferredField = resolveFieldBindingByCandidates(
                    tableFields,
                    joinNonBlank(List.of(name, rawField))
            );
            String field = ensureResolvedFieldBinding(tableFields, rawField, inferredField, false);
            if (name.isBlank() && field.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("field", field);
            result.add(row);
        }
        return result;
    }

    private List<String> sanitizeAnalysisLogic(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String line = Objects.toString(item, "").trim();
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeReusableParameters(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            if (key.isBlank()) {
                continue;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeChartSuggestions(Object value, String tableName) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tableFields = loadTableFieldMeta(tableName);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String title = Objects.toString(raw.get("title"), "").trim();
            String chartType = Objects.toString(raw.get("chartType"), "").trim();
            String rawDimension = Objects.toString(raw.get("dimension"), "").trim();
            String rawMetric = Objects.toString(raw.get("metric"), "").trim();
            String dimension = ensureResolvedFieldBinding(
                    tableFields,
                    rawDimension,
                    resolveFieldBindingByCandidates(tableFields, rawDimension),
                    false
            );
            String metric = ensureResolvedFieldBinding(
                    tableFields,
                    rawMetric,
                    resolveFieldBindingByCandidates(tableFields, rawMetric),
                    true
            );
            Map<String, Object> row = new LinkedHashMap<>();
            if (!title.isBlank()) {
                row.put("title", title);
            }
            if (!chartType.isBlank()) {
                row.put("chartType", chartType);
            }
            if (!dimension.isBlank()) {
                row.put("dimension", dimension);
            }
            if (!metric.isBlank()) {
                row.put("metric", metric);
            }
            if (!row.isEmpty()) {
                result.add(row);
            }
        }
        return result;
    }

    private List<String> splitAndTrim(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : text.split("[,，;；\\s]+")) {
            String value = part.trim();
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private String extractBusinessModelFieldRef(Map<?, ?> raw) {
        for (String key : List.of("field", "columnName", "sourceFieldName", "fieldName", "dimensionField", "targetField")) {
            String value = Objects.toString(raw.get(key), "").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeFieldToken(String value) {
        return Objects.toString(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[`\"'\\s_\\-()（）\\[\\]{}<>:：.,，、/\\\\]+", "");
    }

    private Set<String> buildNormalizedFieldAliasSet(List<Map<String, Object>> fields) {
        Set<String> aliases = new HashSet<>();
        if (fields == null || fields.isEmpty()) {
            return aliases;
        }
        for (Map<String, Object> field : fields) {
            if (field == null) {
                continue;
            }
            addFieldAlias(aliases, field.get("columnName"));
            addFieldAlias(aliases, field.get("displayName"));
            addFieldAlias(aliases, field.get("sourceFieldName"));
            addFieldAlias(aliases, field.get("fieldComment"));
            String synonyms = Objects.toString(field.get("synonyms"), "");
            for (String token : splitAndTrim(synonyms)) {
                addFieldAlias(aliases, token);
            }
        }
        return aliases;
    }

    private void addFieldAlias(Set<String> aliases, Object rawValue) {
        if (aliases == null) {
            return;
        }
        String normalized = normalizeFieldToken(Objects.toString(rawValue, ""));
        if (!normalized.isBlank()) {
            aliases.add(normalized);
        }
    }

    private List<Map<String, Object>> listAutoApplyModelCandidates(String targetTableName) {
        String normalizedTarget = Objects.toString(targetTableName, "").trim();
        StringBuilder sql = new StringBuilder("""
                SELECT id,
                       model_name AS modelName,
                       table_name AS tableName,
                       owner_id AS ownerId,
                       published,
                       updated_at AS updatedAt
                FROM is_business_model
                WHERE status = 'ACTIVE'
                  AND table_name <> ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(normalizedTarget);
        if (!AuthContext.isAdmin()) {
            sql.append(" AND (published = 1 OR owner_id = ?)");
            args.add(permissionService.currentUserId());
        }
        sql.append("""
                ORDER BY updated_at DESC
                LIMIT ?
                """);
        args.add(AUTO_APPLY_MODEL_CANDIDATE_LIMIT);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int compareUpdatedAt(Map<String, Object> left, Map<String, Object> right) {
        long leftMs = parseDateTimeMs(left == null ? null : left.get("updatedAt"));
        long rightMs = parseDateTimeMs(right == null ? null : right.get("updatedAt"));
        return Long.compare(leftMs, rightMs);
    }

    private long parseDateTimeMs(Object value) {
        if (value == null) {
            return Long.MIN_VALUE;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant().toEpochMilli();
        }
        if (value instanceof java.time.Instant instant) {
            return instant.toEpochMilli();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return java.time.Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // fallback
        }
        try {
            String normalized = text.replace('T', ' ').replace("Z", "");
            return java.sql.Timestamp.valueOf(normalized).getTime();
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    private double roundDouble(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private List<Map<String, Object>> loadTableFieldMeta(String tableName) {
        String table = Objects.toString(tableName, "").trim();
        if (table.isBlank()) {
            return List.of();
        }
        try {
            return listFields(table);
        } catch (Exception ex) {
            log.debug("加载字段元信息失败，table={}, error={}", table, ex.getMessage());
            return List.of();
        }
    }

    private boolean isNumericFieldType(String value) {
        String type = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) {
            return false;
        }
        return type.contains("NUMBER")
                || type.contains("INT")
                || type.contains("DECIMAL")
                || type.contains("DOUBLE")
                || type.contains("FLOAT")
                || type.contains("REAL")
                || type.contains("NUMERIC")
                || type.contains("LONG")
                || type.contains("SHORT");
    }

    private String joinNonBlank(List<String> values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            String line = Objects.toString(value, "").trim();
            if (!line.isBlank()) {
                parts.add(line);
            }
        }
        return String.join(" ", parts);
    }

    private String resolveFieldBindingByCandidates(List<Map<String, Object>> tableFields, String rawCandidates) {
        if (tableFields == null || tableFields.isEmpty()) {
            return "";
        }
        List<String> candidates = splitAndTrim(rawCandidates);
        if (candidates.isEmpty()) {
            return "";
        }
        List<String> normalizedCandidates = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = normalizeFieldToken(candidate);
            if (!normalized.isBlank() && !normalizedCandidates.contains(normalized)) {
                normalizedCandidates.add(normalized);
            }
        }
        if (normalizedCandidates.isEmpty()) {
            return "";
        }

        Map<String, String> exactAlias = new LinkedHashMap<>();
        List<Map<String, Object>> aliasRows = new ArrayList<>();
        for (Map<String, Object> field : tableFields) {
            String columnName = Objects.toString(field.get("columnName"), "").trim();
            if (columnName.isBlank()) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            aliases.add(normalizeFieldToken(columnName));
            aliases.add(normalizeFieldToken(Objects.toString(field.get("displayName"), "")));
            aliases.add(normalizeFieldToken(Objects.toString(field.get("sourceFieldName"), "")));
            List<String> merged = aliases.stream().filter(item -> !item.isBlank()).distinct().toList();
            if (merged.isEmpty()) {
                continue;
            }
            for (String alias : merged) {
                exactAlias.putIfAbsent(alias, columnName);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("columnName", columnName);
            row.put("aliases", merged);
            aliasRows.add(row);
        }

        for (String candidate : normalizedCandidates) {
            String matched = exactAlias.get(candidate);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }

        String bestColumn = "";
        double bestScore = 0D;
        for (String candidate : normalizedCandidates) {
            for (Map<String, Object> row : aliasRows) {
                String column = Objects.toString(row.get("columnName"), "").trim();
                @SuppressWarnings("unchecked")
                List<String> aliases = (List<String>) row.getOrDefault("aliases", List.of());
                for (String alias : aliases) {
                    if (alias.length() < 2 || candidate.length() < 2) {
                        continue;
                    }
                    if (!alias.contains(candidate) && !candidate.contains(alias)) {
                        continue;
                    }
                    double overlap = (double) Math.min(alias.length(), candidate.length()) /
                            (double) Math.max(alias.length(), candidate.length());
                    if (overlap > bestScore) {
                        bestScore = overlap;
                        bestColumn = column;
                    }
                }
            }
        }
        if (bestScore >= 0.65D) {
            return bestColumn;
        }
        return "";
    }

    private String ensureResolvedFieldBinding(List<Map<String, Object>> tableFields,
                                              String rawField,
                                              String inferredField,
                                              boolean preferNumeric) {
        Set<String> allowedColumns = new HashSet<>();
        for (Map<String, Object> field : tableFields) {
            String column = Objects.toString(field.get("columnName"), "").trim();
            if (!column.isBlank()) {
                allowedColumns.add(column);
            }
        }

        String candidate = Objects.toString(rawField, "").trim();
        if (!candidate.isBlank()) {
            if (allowedColumns.contains(candidate)) {
                return candidate;
            }
            String aliasHit = resolveFieldBindingByCandidates(tableFields, candidate);
            if (!aliasHit.isBlank()) {
                return aliasHit;
            }
        }

        String inferred = Objects.toString(inferredField, "").trim();
        if (!inferred.isBlank()) {
            if (allowedColumns.contains(inferred)) {
                return inferred;
            }
            String aliasHit = resolveFieldBindingByCandidates(tableFields, inferred);
            if (!aliasHit.isBlank()) {
                return aliasHit;
            }
        }

        if (preferNumeric) {
            for (Map<String, Object> field : tableFields) {
                String column = Objects.toString(field.get("columnName"), "").trim();
                if (column.isBlank()) {
                    continue;
                }
                String type = Objects.toString(field.getOrDefault("fieldType", field.get("dataType")), "");
                if (isNumericFieldType(type)) {
                    return column;
                }
            }
        }

        for (Map<String, Object> field : tableFields) {
            String column = Objects.toString(field.get("columnName"), "").trim();
            if (!column.isBlank()) {
                return column;
            }
        }
        return "";
    }

    private List<String> extractFormulaTokens(String formula) {
        String normalized = Objects.toString(formula, "")
                .replaceAll("[`\"'\\[\\]]", " ")
                .replaceAll("[+\\-*/%(),=<>!?;:]", " ");
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            String value = part.trim();
            if (value.length() > 1) {
                tokens.add(value);
            }
        }
        return tokens;
    }

    private void assertFieldExists(String tableName, String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("字段名不能为空");
        }
        if (datasourceService.isOfficialSource(tableName)) {
            boolean exists = datasourceService.listQueryFields(tableName).stream()
                    .anyMatch(field -> columnName.equals(Objects.toString(field.get("columnName"), "")));
            if (!exists) {
                throw new IllegalArgumentException("字段不存在或无访问权限: " + columnName);
            }
            return;
        }

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_data_field
                WHERE table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("字段不存在或无访问权限: " + columnName);
        }
    }

    private ParsedFile parseFile(MultipartFile file, String originalFilename) throws IOException {
        return parseFile(file, originalFilename, TaskProgressTracker.noop());
    }

    private ParsedFile parseFile(MultipartFile file, String originalFilename, TaskProgressTracker progress) throws IOException {
        String lowerName = originalFilename.toLowerCase();
        if (lowerName.endsWith(".csv")) {
            return parseCsv(file, progress);
        }
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return parseExcel(file, progress);
        }
        throw new IllegalArgumentException("仅支持 .xlsx、.xls、.csv 文件");
    }

    private ParsedFile parseExcel(MultipartFile file) throws IOException {
        return parseExcel(file, TaskProgressTracker.noop());
    }

    private ParsedFile parseExcel(MultipartFile file, TaskProgressTracker progress) throws IOException {
        progress.startParsingUnknown("正在解析 Excel 文件");
        DynamicDataListener listener = new DynamicDataListener(progress::parsedRowsUnknownTotal);
        EasyExcel.read(file.getInputStream(), listener).sheet().doRead();

        Map<Integer, String> headMap = listener.getHeadMap();
        if (headMap == null || headMap.isEmpty()) {
            progress.parsedRows(0, 0);
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
        progress.parsedRows(rows.size(), rows.size());
        return new ParsedFile(headers, rows);
    }

    private ParsedFile parseCsv(MultipartFile file) throws IOException {
        return parseCsv(file, TaskProgressTracker.noop());
    }

    private ParsedFile parseCsv(MultipartFile file, TaskProgressTracker progress) throws IOException {
        int totalRows = countNonEmptyCsvRows(file);
        progress.startParsing(totalRows, "正在解析 CSV 文件");
        List<List<String>> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int parsedRows = 0;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(parseCsvLine(line));
                    if (lines.size() > 1) {
                        parsedRows++;
                        progress.parsedRows(parsedRows, totalRows);
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            progress.parsedRows(0, 0);
            return new ParsedFile(Collections.emptyList(), Collections.emptyList());
        }

        List<String> headers = new ArrayList<>();
        List<String> rawHeaders = lines.get(0);
        for (int i = 0; i < rawHeaders.size(); i++) {
            headers.add(normalizeHeader(rawHeaders.get(i), i));
        }
        return new ParsedFile(headers, lines.subList(1, lines.size()));
    }

    private int countNonEmptyCsvRows(MultipartFile file) throws IOException {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
        }
        return Math.max(0, count - 1);
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

    private Map<String, Object> buildTemplateModelJson(String requirement, String tableName, String templateContent,
                                                       List<Map<String, Object>> fields) {
        List<String> headers = fields.stream()
                .map(item -> Objects.toString(item.getOrDefault("displayName", item.get("columnName"))))
                .toList();
        Map<String, Object> model = buildAcceptanceModelJson(requirement + "\n" + templateContent, tableName, headers, List.of());
        model.put("requirement", requirement);
        return model;
    }

    private Map<String, Object> buildAcceptanceModelJson(String requirement, String tableName, List<String> headers,
                                                         List<List<String>> rows) {
        List<String> metricFields = new ArrayList<>();
        List<String> dimensionFields = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String haystack = (requirement + " " + header).toLowerCase();
            boolean looksMetric = (!rows.isEmpty() && "NUMBER".equals(inferType(rows, i)))
                    || haystack.contains("销售") || haystack.contains("金额") || haystack.contains("数量")
                    || haystack.contains("客单价") || haystack.contains("amount") || haystack.contains("sales");
            if (looksMetric) {
                metricFields.add(header);
            } else {
                dimensionFields.add(header);
            }
        }
        if (metricFields.isEmpty()) {
            metricFields.add("记录数");
        }
        if (dimensionFields.isEmpty()) {
            dimensionFields.add("业务分类");
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("modelName", inferModelName(requirement));
        model.put("tableName", tableName);
        model.put("requirement", requirement);
        model.put("metricDefinitions", metricFields.stream().limit(6).map(name -> Map.of(
                "name", name,
                "field", name,
                "aggregation", "记录数".equals(name) ? "COUNT" : "SUM",
                "formula", "记录数".equals(name) ? "COUNT(1)" : "SUM(" + name + ")"
        )).toList());
        model.put("dimensionSystem", dimensionFields.stream().limit(8).map(name -> Map.of(
                "name", name,
                "field", name
        )).toList());
        model.put("analysisLogic", List.of(
                "按核心维度拆解指标贡献",
                "按时间或批次观察趋势变化",
                "识别异常波动并生成诊断报告",
                "沉淀为个人模型或发布到企业模型库复用"
        ));
        model.put("chartSuggestions", metricFields.stream().limit(4).map(name -> Map.of(
                "title", "各维度" + name + "分析",
                "chartType", "bar"
        )).toList());
        return model;
    }

    private String inferModelName(String requirement) {
        String text = requirement == null ? "" : requirement.trim();
        if (text.contains("生命周期")) {
            return "电商用户生命周期分析模型";
        }
        if (text.contains("销售")) {
            return "销售经营分析模型";
        }
        if (text.length() > 18) {
            return text.substring(0, 18) + "分析模型";
        }
        return text.isBlank() ? "零代码业务分析模型" : text + "分析模型";
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("业务模型序列化失败：" + e.getMessage());
        }
    }

    private void saveCatalog(String originalFilename, String displayNameOverride, String tableName, List<FieldMeta> fields, int rowCount, long fileSize) {
        jdbcTemplate.update("""
                INSERT INTO is_data_table(source_name, display_name, table_name, owner_id, row_count, field_count, file_size, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_CLEANING')
                """, originalFilename,
                displayNameOverride == null || displayNameOverride.isBlank() ? removeExtension(originalFilename) : displayNameOverride,
                tableName, permissionService.currentUserId(), rowCount, fields.size(), Math.max(0L, fileSize));

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

    private boolean parseBooleanFlag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = Objects.toString(value, "").trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
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

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        if (!columnExists(tableName, columnName)) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + definition);
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
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

    private String quoteColumn(String columnName) {
        if (columnName == null || !columnName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法字段名: " + columnName);
        }
        return "`" + columnName + "`";
    }

    private String blankValueCondition(String quotedColumn) {
        String trimmed = "TRIM(" + quotedColumn + ")";
        return quotedColumn + " IS NULL OR " + trimmed + " = '' OR UPPER(" + trimmed + ") IN ('NULL', 'N/A', 'NA', 'NAN', '--', '-') OR " +
                trimmed + " IN ('无', '空', '缺失', '未填写', '未知')";
    }

    private String nonBlankValueCondition(String quotedColumn) {
        return "NOT (" + blankValueCondition(quotedColumn) + ")";
    }

    private String activeUploadRowCondition(String tableName) {
        if (columnExists(tableName, "cleaning_isolated")) {
            return "(`cleaning_isolated` IS NULL OR `cleaning_isolated` = 0)";
        }
        return "1=1";
    }

    private void appendCsvLine(StringBuilder csv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escapeCsv(values.get(i)));
        }
        csv.append('\n');
    }

    private String escapeCsv(String value) {
        String safeValue = value == null ? "" : value;
        if (safeValue.contains("\"") || safeValue.contains(",") || safeValue.contains("\n") || safeValue.contains("\r")) {
            return "\"" + safeValue.replace("\"", "\"\"") + "\"";
        }
        return safeValue;
    }

    private interface TaskProgressTracker {
        TaskProgressTracker NOOP = new TaskProgressTracker() {
        };

        static TaskProgressTracker noop() {
            return NOOP;
        }

        default void received(String message) {
        }

        default void startParsing(int totalRows, String message) {
        }

        default void startParsingUnknown(String message) {
        }

        default void parsedRows(int parsedRows, int totalRows) {
        }

        default void parsedRowsUnknownTotal(int parsedRows) {
        }

        default void merging(int totalRows) {
        }

        default void buildingTable(int totalRows) {
        }

        default void persistedRows(int insertedRows, int totalRows) {
        }

        default void metadata(String message) {
        }
    }

    private class DatabaseTaskProgressTracker implements TaskProgressTracker {
        private static final int PARSE_START = 1;
        private static final int PARSE_END = 50;
        private static final int PERSIST_START = 51;
        private static final int PERSIST_END = 95;
        private final String taskId;
        private int lastProgress = 0;
        private int lastParsedMessageRows = 0;

        private DatabaseTaskProgressTracker(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public void received(String message) {
            publish("UPLOADING", 0, message);
        }

        @Override
        public void startParsing(int totalRows, String message) {
            publish("PARSING", PARSE_START, totalRows > 0 ? message + "，共 " + totalRows + " 行" : message);
        }

        @Override
        public void startParsingUnknown(String message) {
            publish("PARSING", PARSE_START, message + "，正在统计总行数");
        }

        @Override
        public void parsedRows(int parsedRows, int totalRows) {
            if (parsedRows > 1 && parsedRows < totalRows && parsedRows - lastParsedMessageRows < 200) {
                return;
            }
            lastParsedMessageRows = parsedRows;
            int progress = totalRows <= 0 ? PARSE_END : PARSE_START + (int) Math.floor((double) parsedRows * (PARSE_END - PARSE_START) / totalRows);
            publish("PARSING", progress, "已真实解析 " + parsedRows + " / " + Math.max(totalRows, parsedRows) + " 行");
        }

        @Override
        public void parsedRowsUnknownTotal(int parsedRows) {
            if (parsedRows == 1 || parsedRows - lastParsedMessageRows >= 200) {
                lastParsedMessageRows = parsedRows;
                publish("PARSING", PARSE_START, "已真实解析 " + parsedRows + " 行，正在统计总行数");
            }
        }

        @Override
        public void merging(int totalRows) {
            publish("BUILDING", PARSE_END, "文件合并完成，共 " + totalRows + " 行，准备入库");
        }

        @Override
        public void buildingTable(int totalRows) {
            publish("BUILDING", PERSIST_START, "正在建表，准备写入 " + totalRows + " 行");
        }

        @Override
        public void persistedRows(int insertedRows, int totalRows) {
            int progress = totalRows <= 0 ? PERSIST_END : PERSIST_START + (int) Math.floor((double) insertedRows * (PERSIST_END - PERSIST_START) / totalRows);
            publish("BUILDING", progress, "已真实写入 " + insertedRows + " / " + Math.max(totalRows, insertedRows) + " 行");
        }

        @Override
        public void metadata(String message) {
            publish("BUILDING", Math.max(lastProgress, 96), message);
        }

        private void publish(String status, int progress, String message) {
            int safeProgress = Math.max(lastProgress, Math.max(0, Math.min(progress, 99)));
            lastProgress = safeProgress;
            updateTask(taskId, status, safeProgress, message, null);
        }
    }

    private static final class AutoApplyMatch {
        private final Long modelId;
        private final Map<String, Object> candidate;
        private final double score;
        private final int overlapCount;
        private final double targetCoverage;
        private final double sourceCoverage;
        private final double jaccard;

        private AutoApplyMatch(Long modelId,
                               Map<String, Object> candidate,
                               double score,
                               int overlapCount,
                               double targetCoverage,
                               double sourceCoverage,
                               double jaccard) {
            this.modelId = modelId;
            this.candidate = candidate;
            this.score = score;
            this.overlapCount = overlapCount;
            this.targetCoverage = targetCoverage;
            this.sourceCoverage = sourceCoverage;
            this.jaccard = jaccard;
        }
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

    public Map<String, Object> getDataQuality(String tableName) {
        assertKnownTable(tableName);
        List<Map<String, Object>> fields = listFields(tableName);
        long totalRows = countRows(tableName);
        
        int totalFields = fields.size();
        int emptyFieldCount = 0;
        int anomalyFieldCount = 0;
        double totalNullRate = 0;
        
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"));
            Map<String, Object> stats = getFieldStatistics(tableName, columnName);
            
            long nullCount = ((Number) stats.getOrDefault("nullCount", 0)).longValue();
            double nullRate = totalRows > 0 ? (double) nullCount / totalRows : 0;
            totalNullRate += nullRate;
            
            if (nullRate > 0.5) {
                emptyFieldCount++;
            }
            
            List<Map<String, Object>> anomalies = (List<Map<String, Object>>) stats.getOrDefault("anomalies", List.of());
            if (!anomalies.isEmpty()) {
                anomalyFieldCount++;
            }
        }
        
        double avgNullRate = totalFields > 0 ? totalNullRate / totalFields : 0;
        int qualityScore = calculateQualityScore(avgNullRate, emptyFieldCount, anomalyFieldCount, totalFields);
        
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("tableName", tableName);
        quality.put("totalRows", totalRows);
        quality.put("totalFields", totalFields);
        quality.put("qualityScore", qualityScore);
        quality.put("avgNullRate", Math.round(avgNullRate * 10000) / 100.0);
        quality.put("emptyFieldCount", emptyFieldCount);
        quality.put("anomalyFieldCount", anomalyFieldCount);
        quality.put("qualityLevel", getQualityLevel(qualityScore));
        quality.put("suggestions", generateQualitySuggestions(avgNullRate, emptyFieldCount, anomalyFieldCount));
        quality.put("cleaningStrategy", generateCleaningStrategy(tableName, fields, totalRows));
        
        return quality;
    }

    public Map<String, Object> generateCleaningStrategy(String tableName) {
        assertKnownTable(tableName);
        List<Map<String, Object>> fields = listFields(tableName);
        return generateCleaningStrategy(tableName, fields, countRows(tableName));
    }

    private Map<String, Object> generateCleaningStrategy(String tableName, List<Map<String, Object>> fields, long totalRows) {
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, String> fieldLabels = buildFieldLabels(fields);
        int totalNullCells = 0;
        int totalAnomalyRows = 0;

        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            if (columnName.isBlank()) {
                continue;
            }
            String displayName = Objects.toString(field.getOrDefault("displayName", columnName), columnName);
            String fieldType = Objects.toString(field.getOrDefault("fieldType", "TEXT"), "TEXT");
            Map<String, Object> stats = getFieldStatistics(tableName, columnName);
            long nullCount = ((Number) stats.getOrDefault("nullCount", 0)).longValue();
            if (nullCount > 0) {
                totalNullCells += (int) Math.min(Integer.MAX_VALUE, nullCount);
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "FILL_NULL");
                action.put("columnName", columnName);
                action.put("displayName", displayName);
                action.put("fieldType", fieldType);
                action.put("affectedRows", nullCount);
                action.put("fillValue", defaultFillValue(fieldType));
                List<Map<String, Object>> sampleRows = findNullRows(tableName, columnName);
                action.put("rowIds", sampleRows.stream().map(row -> row.get("sys_id")).toList());
                action.put("sampleRows", sampleRows);
                action.put("description", "将字段“" + displayName + "”的 " + nullCount + " 个空值填充为默认值");
                actions.add(action);
            }

            List<Map<String, Object>> anomalies = (List<Map<String, Object>>) stats.getOrDefault("anomalies", List.of());
            if (!anomalies.isEmpty()) {
                totalAnomalyRows += anomalies.size();
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "MARK_ANOMALY_AND_ISOLATE");
                action.put("columnName", columnName);
                action.put("displayName", displayName);
                action.put("affectedRows", anomalies.size());
                action.put("rowIds", anomalies.stream().map(item -> item.get("rowId")).toList());
                action.put("sampleRows", findRowsByIds(tableName, anomalies.stream().map(item -> item.get("rowId")).toList(), 20));
                action.put("description", "将字段“" + displayName + "”检测到的 " + anomalies.size() + " 个异常值所在行标记并隔离");
                actions.add(action);
            }
        }

        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("tableName", tableName);
        strategy.put("totalRows", totalRows);
        strategy.put("fields", fields);
        strategy.put("fieldLabels", fieldLabels);
        strategy.put("actionCount", actions.size());
        strategy.put("totalNullCells", totalNullCells);
        strategy.put("totalAnomalyRows", totalAnomalyRows);
        strategy.put("requiresConfirmation", true);
        strategy.put("actions", actions);
        strategy.put("summary", actions.isEmpty()
                ? "未发现需要自动处理的空值或数值异常"
                : "发现 " + totalNullCells + " 个空值、" + totalAnomalyRows + " 个异常行，可确认后自动处理");
        return strategy;
    }

    private Map<String, String> buildFieldLabels(List<Map<String, Object>> fields) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            if (columnName.isBlank()) {
                continue;
            }
            String sourceFieldName = Objects.toString(field.get("sourceFieldName"), "");
            String displayName = Objects.toString(field.get("displayName"), "");
            labels.put(columnName, !sourceFieldName.isBlank() ? sourceFieldName : (!displayName.isBlank() ? displayName : columnName));
        }
        return labels;
    }

    public Map<String, Object> applyCleaningStrategy(String tableName, Map<String, Object> request) {
        assertKnownTable(tableName);
        List<Map<String, Object>> actions = parseCleaningActions(tableName, request);
        Map<String, Object> cleaningStrategy = generateCleaningStrategy(tableName);
        List<Map<String, Object>> processedActions = new ArrayList<>();
        int filledRows = 0;
        int markedRows = 0;

        for (Map<String, Object> action : actions) {
            String type = Objects.toString(action.get("type"), "").toUpperCase();
            Map<String, Object> processedAction = new LinkedHashMap<>(action);
            if ("FILL_NULL".equals(type)) {
                String columnName = Objects.toString(action.get("columnName"), "");
                assertFieldExists(tableName, columnName);
                List<Object> rowIds = parseObjectRowIds(action.get("rowIds"));
                String fillValue = Objects.toString(action.getOrDefault("fillValue", defaultFillValue(
                        fieldTypeOf(tableName, columnName)
                )), "");
                String quotedColumn = quoteColumn(columnName);
                filledRows += jdbcTemplate.update(
                        "UPDATE `" + tableName + "` SET " + quotedColumn + " = ? WHERE " + blankValueCondition(quotedColumn),
                        fillValue
                );
                processedAction.put("afterRows", findRowsByIds(tableName, rowIds, 20));
            } else if ("MARK_ANOMALY_AND_ISOLATE".equals(type)) {
                ensureCleaningColumns(tableName);
                List<Long> rowIds = parseRowIds(action.get("rowIds"));
                if (!rowIds.isEmpty()) {
                    String columnName = Objects.toString(action.get("columnName"), "");
                    String reason = "字段 " + columnName + " 存在数值异常";
                    markedRows += markRowsAsAnomaly(tableName, rowIds, reason);
                    processedAction.put("afterRows", findRowsByIds(tableName, new ArrayList<>(rowIds), 20));
                }
            }
            processedActions.add(processedAction);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableName", tableName);
        result.put("filledRows", filledRows);
        result.put("markedAnomalyRows", markedRows);
        result.put("appliedActions", actions.size());
        result.put("processedActions", processedActions);
        result.put("cleaningStrategy", cleaningStrategy);
        return result;
    }

    public Map<String, Object> activateCleanedTable(String tableName) {
        return activateCleanedTable(tableName, false);
    }

    public Map<String, Object> activateCleanedTable(String tableName, boolean skipCleaning) {
        assertKnownTable(tableName);
        Map<String, Object> cleaningStrategy = generateCleaningStrategy(tableName);
        List<Map<String, Object>> actions = cleaningActions(cleaningStrategy);
        if (!skipCleaning && !actions.isEmpty()) {
            throw new IllegalArgumentException("请先处理空值与异常值，确认没有待处理问题后才能存入我的数据表");
        }
        int updated = jdbcTemplate.update("""
                UPDATE is_data_table
                SET status = 'ACTIVE'
                WHERE table_name = ?
                  AND (? = 1 OR owner_id = ?)
                """, tableName, AuthContext.isAdmin() ? 1 : 0, permissionService.currentUserId());
        if (updated == 0) {
            throw new IllegalArgumentException("数据表不存在或无权限激活：" + tableName);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableName", tableName);
        result.put("status", "ACTIVE");
        result.put("skipCleaning", skipCleaning);
        result.put("cleaningStrategy", cleaningStrategy);
        return result;
    }
    
    public Map<String, Object> getFieldStatistics(String tableName, String columnName) {
        assertKnownTable(tableName);
        assertFieldExists(tableName, columnName);
        if (datasourceService.isOfficialSource(tableName)) {
            return getFieldStatisticsForOfficialSource(tableName, columnName);
        }
        return getFieldStatisticsForUploadSource(tableName, columnName);
    }
    
    public List<Map<String, Object>> detectAnomalies(String tableName, String columnName) {
        assertKnownTable(tableName);
        assertFieldExists(tableName, columnName);
        if (datasourceService.isOfficialSource(tableName)) {
            return detectAnomaliesForOfficialSource(tableName, columnName);
        }
        return detectUploadFieldAnomalies(tableName, columnName);
    }

    private void appendNumericOutliers(List<Map<String, Object>> rows, List<Map<String, Object>> anomalies) {
        List<Double> numbers = new ArrayList<>();
        Map<Object, Double> rowNumbers = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Double number = parseNumber(Objects.toString(row.get("value"), "").trim());
            if (number != null) {
                numbers.add(number);
                rowNumbers.put(row.get("sys_id"), number);
            }
        }
        if (numbers.size() < 4) {
            return;
        }
        Collections.sort(numbers);
        double avg = numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double stdDev = Math.sqrt(numbers.stream().mapToDouble(value -> Math.pow(value - avg, 2)).average().orElse(0));
        double q1 = percentile(numbers, 0.25);
        double q3 = percentile(numbers, 0.75);
        double iqr = q3 - q1;
        double sigmaLower = stdDev == 0 ? Double.NEGATIVE_INFINITY : avg - 3 * stdDev;
        double sigmaUpper = stdDev == 0 ? Double.POSITIVE_INFINITY : avg + 3 * stdDev;
        double iqrLower = iqr == 0 ? Double.NEGATIVE_INFINITY : q1 - 1.5 * iqr;
        double iqrUpper = iqr == 0 ? Double.POSITIVE_INFINITY : q3 + 1.5 * iqr;

        for (Map<String, Object> row : rows) {
            if (anomalies.size() >= 100) {
                return;
            }
            Object rowId = row.get("sys_id");
            if (hasAnomalyForRow(anomalies, rowId)) {
                continue;
            }
            Double value = rowNumbers.get(rowId);
            if (value == null) {
                continue;
            }
            boolean sigmaOutlier = value < sigmaLower || value > sigmaUpper;
            boolean iqrOutlier = value < iqrLower || value > iqrUpper;
            if (sigmaOutlier || iqrOutlier) {
                String reason = iqrOutlier
                        ? "鏁板€艰秴鍑?IQR 鍥涘垎浣嶈寖鍥?[" + String.format("%.2f", iqrLower) + ", " + String.format("%.2f", iqrUpper) + "]"
                        : "鏁板€艰秴鍑?3σ 鑼冨洿 [" + String.format("%.2f", sigmaLower) + ", " + String.format("%.2f", sigmaUpper) + "]";
                anomalies.add(anomaly(rowId, row.get("value"), iqrOutlier ? "IQR_OUTLIER" : "SIGMA_OUTLIER", reason));
            }
        }
    }

    private boolean hasAnomalyForRow(List<Map<String, Object>> anomalies, Object rowId) {
        return anomalies.stream().anyMatch(item -> Objects.equals(item.get("rowId"), rowId));
    }

    private Map<String, Object> anomaly(Object rowId, Object value, String type, String reason) {
        Map<String, Object> anomaly = new LinkedHashMap<>();
        anomaly.put("rowId", rowId);
        anomaly.put("value", value);
        anomaly.put("type", type);
        anomaly.put("reason", reason);
        return anomaly;
    }

    private double percentile(List<Double> sortedNumbers, double percentile) {
        if (sortedNumbers.isEmpty()) {
            return 0;
        }
        double index = percentile * (sortedNumbers.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedNumbers.get(lower);
        }
        return sortedNumbers.get(lower) + (sortedNumbers.get(upper) - sortedNumbers.get(lower)) * (index - lower);
    }

    private Double parseNumber(String value) {
        try {
            String normalized = value.replace(",", "").trim();
            if (!normalized.matches("^-?\\d+(\\.\\d+)?$")) {
                return null;
            }
            return Double.parseDouble(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean looksEmailField(String label) {
        return label.contains("email") || label.contains("邮箱") || label.contains("邮件");
    }

    private boolean looksPhoneField(String label) {
        return label.contains("phone") || label.contains("mobile") || label.contains("tel")
                || label.contains("手机") || label.contains("电话");
    }

    private boolean looksDateField(String label) {
        return label.contains("date") || label.contains("time") || label.contains("日期") || label.contains("时间");
    }

    private boolean looksAgeField(String label) {
        return label.contains("age") || label.contains("年龄");
    }

    private boolean looksAmountField(String label) {
        return label.contains("amount") || label.contains("price") || label.contains("金额") || label.contains("价格")
                || label.contains("数量") || label.contains("销售额") || label.contains("销售");
    }

    private boolean looksLevelField(String label) {
        return label.contains("level") || label.contains("grade") || label.contains("等级") || label.contains("级别")
                || label.contains("状态");
    }

    private boolean looksNumericField(String label) {
        return label.contains("count") || label.contains("score") || label.contains("rate") || label.contains("num")
                || label.contains("分数") || label.contains("比例") || label.contains("率");
    }

    private boolean isValidEmail(String value) {
        return value.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private boolean isValidPhone(String value) {
        return value.matches("^1[3-9]\\d{9}$") || value.matches("^\\+?\\d{6,20}$");
    }

    private boolean isValidDateLike(String value) {
        if (!value.matches("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*$")) {
            return false;
        }
        String[] parts = value.split("[ T]")[0].split("[-/]");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        return year >= 1900 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    private boolean isValidLevelValue(String value) {
        String normalized = value.trim().toUpperCase();
        return List.of(
                "A", "B", "C", "D", "S", "VIP", "SVIP",
                "高", "中", "低", "正常", "有效", "否", "是",
                "一级", "二级", "三级", "未分级", "未知等级"
        ).contains(normalized);
    }

    public Map<String, Object> getFieldDistribution(String tableName, String columnName) {
        assertKnownTable(tableName);
        assertFieldExists(tableName, columnName);
        if (datasourceService.isOfficialSource(tableName)) {
            return getFieldDistributionForOfficialSource(tableName, columnName);
        }
        return getFieldDistributionForUploadSource(tableName, columnName);
    }
    
    public Map<String, Object> batchReplace(String tableName, String columnName, String oldValue, String newValue) {
        assertKnownTable(tableName);
        assertFieldExists(tableName, columnName);
        
        String quotedColumn = quoteColumn(columnName);
        
        int affected = jdbcTemplate.update(
            "UPDATE `" + tableName + "` SET " + quotedColumn + " = ? WHERE " + quotedColumn + " = ?",
            newValue, oldValue
        );
        
        return Map.of(
            "tableName", tableName,
            "columnName", columnName,
            "oldValue", oldValue,
            "newValue", newValue,
            "affectedRows", affected
        );
    }

    public Map<String, Object> updateCell(String tableName, Long rowId, String columnName, Object value) {
        assertKnownTable(tableName);
        assertFieldExists(tableName, columnName);
        if (rowId == null || rowId <= 0) {
            throw new IllegalArgumentException("行ID不能为空");
        }
        if ("sys_id".equalsIgnoreCase(columnName)) {
            throw new IllegalArgumentException("系统ID不允许修改");
        }
        String quotedColumn = quoteColumn(columnName);
        int affected = jdbcTemplate.update(
                "UPDATE `" + tableName + "` SET " + quotedColumn + " = ? WHERE sys_id = ?",
                value, rowId
        );
        return Map.of(
                "tableName", tableName,
                "rowId", rowId,
                "columnName", columnName,
                "value", value == null ? "" : value,
                "affectedRows", affected
        );
    }

    private List<Map<String, Object>> parseCleaningActions(String tableName, Map<String, Object> request) {
        Object rawActions = request == null ? null : request.get("actions");
        if (rawActions == null) {
            return (List<Map<String, Object>>) generateCleaningStrategy(tableName).getOrDefault("actions", List.of());
        }
        if (!(rawActions instanceof List<?> list)) {
            throw new IllegalArgumentException("清洗策略格式不正确");
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> action = new LinkedHashMap<>();
                map.forEach((key, value) -> action.put(String.valueOf(key), value));
                actions.add(action);
            }
        }
        return actions;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cleaningActions(Map<String, Object> cleaningStrategy) {
        Object rawActions = cleaningStrategy == null ? null : cleaningStrategy.get("actions");
        if (!(rawActions instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                actions.add((Map<String, Object>) map);
            }
        }
        return actions;
    }

    private String defaultFillValue(String fieldType) {
        return switch (Objects.toString(fieldType, "TEXT").toUpperCase()) {
            case "NUMBER" -> "0";
            case "DATE" -> "1970-01-01";
            default -> "未填写";
        };
    }

    private String fieldTypeOf(String tableName, String columnName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT field_type AS fieldType FROM is_data_field WHERE table_name = ? AND column_name = ? LIMIT 1",
                tableName, columnName
        );
        return rows.isEmpty() ? "TEXT" : Objects.toString(rows.get(0).get("fieldType"), "TEXT");
    }

    private List<Map<String, Object>> findNullRows(String tableName, String columnName) {
        String quotedColumn = quoteColumn(columnName);
        return jdbcTemplate.queryForList(
                "SELECT * FROM `" + tableName + "` WHERE " + activeUploadRowCondition(tableName) +
                        " AND (" + blankValueCondition(quotedColumn) + ") LIMIT 20"
        );
    }

    private List<Map<String, Object>> findRowsByIds(String tableName, List<?> rawRowIds, int limit) {
        List<Object> rowIds = parseObjectRowIds(rawRowIds).stream().limit(limit).toList();
        if (rowIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(rowIds.size(), "?"));
        return jdbcTemplate.queryForList(
                "SELECT * FROM `" + tableName + "` WHERE sys_id IN (" + placeholders + ") ORDER BY sys_id",
                rowIds.toArray()
        );
    }

    private List<Object> parseObjectRowIds(Object rawRowIds) {
        if (!(rawRowIds instanceof List<?> list)) {
            return List.of();
        }
        List<Object> rowIds = new ArrayList<>();
        for (Object value : list) {
            if (value != null && !rowIds.contains(value)) {
                rowIds.add(value);
            }
            if (rowIds.size() >= 1000) {
                break;
            }
        }
        return rowIds;
    }

    private void ensureCleaningColumns(String tableName) {
        addColumnIfMissing(tableName, "is_cleaning_anomaly", "`is_cleaning_anomaly` TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing(tableName, "cleaning_isolated", "`cleaning_isolated` TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing(tableName, "cleaning_anomaly_reason", "`cleaning_anomaly_reason` VARCHAR(1000) NULL");
    }

    private List<Long> parseRowIds(Object rawRowIds) {
        if (!(rawRowIds instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(value -> Long.parseLong(String.valueOf(value)))
                .distinct()
                .limit(1000)
                .toList();
    }

    private int markRowsAsAnomaly(String tableName, List<Long> rowIds, String reason) {
        int affected = 0;
        int chunkSize = 200;
        for (int start = 0; start < rowIds.size(); start += chunkSize) {
            List<Long> chunk = rowIds.subList(start, Math.min(start + chunkSize, rowIds.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(reason);
            args.addAll(chunk);
            affected += jdbcTemplate.update(
                    "UPDATE `" + tableName + "` SET `is_cleaning_anomaly` = 1, `cleaning_isolated` = 1, `cleaning_anomaly_reason` = ? WHERE sys_id IN (" + placeholders + ")",
                    args.toArray()
            );
        }
        return affected;
    }
    
    public Map<String, Object> deleteRows(String tableName, List<Long> rowIds) {
        assertKnownTable(tableName);
        
        if (rowIds == null || rowIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的行");
        }
        
        if (rowIds.size() > 1000) {
            throw new IllegalArgumentException("单次最多删除 1000 行");
        }
        
        String placeholders = String.join(",", Collections.nCopies(rowIds.size(), "?"));
        int affected = jdbcTemplate.update(
            "DELETE FROM `" + tableName + "` WHERE sys_id IN (" + placeholders + ")",
            rowIds.toArray()
        );
        
        jdbcTemplate.update(
            "UPDATE is_data_table SET row_count = (SELECT COUNT(*) FROM `" + tableName + "`) WHERE table_name = ?",
            tableName
        );
        
        return Map.of(
            "tableName", tableName,
            "deletedRows", affected
        );
    }
    
    public Map<String, Object> deleteColumn(String tableName, String columnName) {
        assertKnownTable(tableName);
        assertFieldExists(tableName, columnName);
        
        String quotedColumn = quoteColumn(columnName);
        
        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` DROP COLUMN " + quotedColumn);
        
        jdbcTemplate.update(
            "DELETE FROM is_data_field WHERE table_name = ? AND column_name = ?",
            tableName, columnName
        );
        
        jdbcTemplate.update(
            "UPDATE is_data_table SET field_count = (SELECT COUNT(*) FROM is_data_field WHERE table_name = ?) WHERE table_name = ?",
            tableName, tableName
        );
        
        return Map.of(
            "tableName", tableName,
            "columnName", columnName,
            "deleted", true
        );
    }
    
    public Map<String, Object> transformData(String tableName, String columnName, String transformType, Map<String, Object> options) {
        assertKnownTable(tableName);
        assertFieldExists(tableName, columnName);
        
        String quotedColumn = quoteColumn(columnName);
        String updateSql = null;
        
        switch (transformType.toUpperCase()) {
            case "TRIM":
                updateSql = "UPDATE `" + tableName + "` SET " + quotedColumn + " = TRIM(" + quotedColumn + ")";
                break;
            case "UPPER":
                updateSql = "UPDATE `" + tableName + "` SET " + quotedColumn + " = UPPER(" + quotedColumn + ")";
                break;
            case "LOWER":
                updateSql = "UPDATE `" + tableName + "` SET " + quotedColumn + " = LOWER(" + quotedColumn + ")";
                break;
            case "DATE_FORMAT":
                String format = Objects.toString(options.getOrDefault("format", "%Y-%m-%d"), "%Y-%m-%d");
                updateSql = "UPDATE `" + tableName + "` SET " + quotedColumn + 
                    " = DATE_FORMAT(STR_TO_DATE(" + quotedColumn + ", '%Y-%m-%d'), '" + format + "') " +
                    "WHERE " + quotedColumn + " IS NOT NULL AND " + quotedColumn + " != ''";
                break;
            case "MULTIPLY":
                double factor = Double.parseDouble(Objects.toString(options.getOrDefault("factor", "1"), "1"));
                updateSql = "UPDATE `" + tableName + "` SET " + quotedColumn + 
                    " = CAST(" + quotedColumn + " AS DECIMAL(20,4)) * " + factor +
                    " WHERE " + quotedColumn + " IS NOT NULL AND " + quotedColumn + " != ''";
                break;
            case "FILL_NULL":
                String fillValue = Objects.toString(options.getOrDefault("value", ""), "");
                updateSql = "UPDATE `" + tableName + "` SET " + quotedColumn + " = ? WHERE " + blankValueCondition(quotedColumn);
                break;
            default:
                throw new IllegalArgumentException("不支持的转换类型：" + transformType);
        }
        
        int affected;
        if ("FILL_NULL".equals(transformType.toUpperCase())) {
            String fillValue = Objects.toString(options.getOrDefault("value", ""), "");
            affected = jdbcTemplate.update(updateSql, fillValue);
        } else {
            affected = jdbcTemplate.update(updateSql);
        }
        
        return Map.of(
            "tableName", tableName,
            "columnName", columnName,
            "transformType", transformType,
            "affectedRows", affected
        );
    }
    
    public Map<String, Object> validateFile(MultipartFile file) throws IOException {
        String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "未命名文件");
        long fileSize = file.getSize();
        
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("fileName", originalFilename);
        validation.put("fileSize", fileSize);
        validation.put("fileSizeMB", Math.round(fileSize / 1024.0 / 1024.0 * 100) / 100.0);
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        if (fileSize > uploadMaxBytes()) {
            errors.add("文件大小超过 " + (uploadMaxBytes() / 1024 / 1024) + "MB 限制");
        }

        String lowerName = originalFilename.toLowerCase();
        List<String> allowedFormats = uploadAllowedFormats();
        if (!allowedFormats.isEmpty()) {
            boolean matched = allowedFormats.stream()
                    .map(ext -> ext.startsWith(".") ? ext.toLowerCase(Locale.ROOT) : "." + ext.toLowerCase(Locale.ROOT))
                    .anyMatch(lowerName::endsWith);
            if (!matched) {
                errors.add("仅支持 " + String.join("、", allowedFormats) + " 格式");
            }
        }
        
        if (fileSize < 100) {
            warnings.add("文件过小，可能为空文件");
        }
        
        String md5 = calculateMD5(file.getBytes());
        validation.put("md5", md5);
        
        boolean dedupEnabled = runtimeConfig == null || runtimeConfig.getBoolean("upload.dedup.enabled", true);
        boolean isDuplicate = dedupEnabled && checkDuplicateByMD5(md5);
        if (isDuplicate) {
            warnings.add("检测到重复文件（MD5 已存在）");
        }
        
        validation.put("valid", errors.isEmpty());
        validation.put("errors", errors);
        validation.put("warnings", warnings);
        validation.put("isDuplicate", isDuplicate);
        
        return validation;
    }
    
    private int calculateQualityScore(double avgNullRate, int emptyFieldCount, int anomalyFieldCount, int totalFields) {
        int score = 100;
        
        score -= (int) (avgNullRate * 50);
        
        if (totalFields > 0) {
            score -= (emptyFieldCount * 100 / totalFields) / 2;
            score -= (anomalyFieldCount * 100 / totalFields) / 4;
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    private String getQualityLevel(int score) {
        if (score >= 90) return "优秀";
        if (score >= 75) return "良好";
        if (score >= 60) return "中等";
        if (score >= 40) return "较差";
        return "差";
    }
    
    private List<String> generateQualitySuggestions(double avgNullRate, int emptyFieldCount, int anomalyFieldCount) {
        List<String> suggestions = new ArrayList<>();
        
        if (avgNullRate > 0.3) {
            suggestions.add("数据空值率较高（" + Math.round(avgNullRate * 100) + "%），建议填充默认值或删除空值行");
        }
        
        if (emptyFieldCount > 0) {
            suggestions.add("存在 " + emptyFieldCount + " 个空值率超过 50% 的字段，建议删除或补充数据");
        }
        
        if (anomalyFieldCount > 0) {
            suggestions.add("检测到 " + anomalyFieldCount + " 个字段存在异常值，建议查看异常值详情并处理");
        }
        
        if (suggestions.isEmpty()) {
            suggestions.add("数据质量良好，可直接用于分析");
        }
        
        return suggestions;
    }

    private Map<String, Object> getFieldStatisticsForUploadSource(String tableName, String columnName) {
        String quotedColumn = quoteColumn(columnName);
        String activeCondition = activeUploadRowCondition(tableName);
        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `" + tableName + "` WHERE " + activeCondition,
                Long.class
        );
        long totalCount = activeCount == null ? 0 : activeCount;

        String nullCondition = blankValueCondition(quotedColumn);
        String nonBlankCondition = nonBlankValueCondition(quotedColumn);

        Long nullCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM `" + tableName + "` WHERE " + activeCondition + " AND (" + nullCondition + ")",
            Long.class
        );

        Long distinctCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT " + quotedColumn + ") FROM `" + tableName + "` WHERE " + activeCondition + " AND (" + nonBlankCondition + ")",
            Long.class
        );

        nullCount = nullCount == null ? 0 : nullCount;
        distinctCount = distinctCount == null ? 0 : distinctCount;
        long nonNullCount = totalCount - nullCount;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("columnName", columnName);
        stats.put("totalCount", totalCount);
        stats.put("nonNullCount", nonNullCount);
        stats.put("nullCount", nullCount);
        stats.put("nullRate", totalCount > 0 ? Math.round((double) nullCount / totalCount * 10000) / 100.0 : 0);
        stats.put("distinctCount", distinctCount);
        stats.put("duplicateRate", nonNullCount > 0 ? Math.round((1 - (double) distinctCount / nonNullCount) * 10000) / 100.0 : 0);

        List<Map<String, Object>> fieldMeta = jdbcTemplate.queryForList(
            "SELECT field_type AS fieldType FROM is_data_field WHERE table_name = ? AND column_name = ?",
            tableName, columnName
        );

        if (!fieldMeta.isEmpty()) {
            String fieldType = Objects.toString(fieldMeta.get(0).get("fieldType"), "TEXT");

            if ("NUMBER".equals(fieldType)) {
                addNumericStatistics(stats, tableName, quotedColumn, activeCondition);
            } else if ("DATE".equals(fieldType)) {
                addDateStatistics(stats, tableName, quotedColumn);
            }
        }

        stats.put("anomalies", detectUploadFieldAnomalies(tableName, columnName));
        return stats;
    }

    private Map<String, Object> getFieldStatisticsForOfficialSource(String tableName, String columnName) {
        String quotedColumn = quoteColumn(columnName);
        long totalCount = countRows(tableName);
        String physicalTable = datasourceService.physicalTableName(tableName);
        String sourceKey = tableName;

        long nullCount = readLongValue(datasourceService.executeQueryWithoutAudit(sourceKey,
                "SELECT COUNT(*) AS v FROM `" + physicalTable + "` WHERE " + quotedColumn + " IS NULL"), "v");
        long distinctCount = readLongValue(datasourceService.executeQueryWithoutAudit(sourceKey,
                "SELECT COUNT(DISTINCT " + quotedColumn + ") AS v FROM `" + physicalTable + "` WHERE " + quotedColumn + " IS NOT NULL"), "v");
        long nonNullCount = Math.max(0, totalCount - nullCount);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("columnName", columnName);
        stats.put("totalCount", totalCount);
        stats.put("nonNullCount", nonNullCount);
        stats.put("nullCount", nullCount);
        stats.put("nullRate", totalCount > 0 ? Math.round((double) nullCount / totalCount * 10000) / 100.0 : 0);
        stats.put("distinctCount", distinctCount);
        stats.put("duplicateRate", nonNullCount > 0 ? Math.round((1 - (double) distinctCount / nonNullCount) * 10000) / 100.0 : 0);

        String fieldType = resolveFieldType(tableName, columnName);
        if ("NUMBER".equals(fieldType)) {
            String numericExpr = "CAST(NULLIF(TRIM(" + quotedColumn + "), '') AS DECIMAL(20,4))";
            String numericStatsSql = "SELECT " + String.join(", ",
                    "MIN(" + numericExpr + ") AS min_value",
                    "MAX(" + numericExpr + ") AS max_value",
                    "AVG(" + numericExpr + ") AS avg_value",
                    "STD(" + numericExpr + ") AS std_dev"
            ) + " FROM `" + physicalTable + "` " +
                    "WHERE " + quotedColumn + " IS NOT NULL " +
                    "AND TRIM(" + quotedColumn + ") <> '' " +
                    "AND TRIM(" + quotedColumn + ") REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$'";
            Map<String, Object> numStats = firstRow(datasourceService.executeQueryWithoutAudit(sourceKey,
                    numericStatsSql));
            stats.put("min", numStats.get("min_value"));
            stats.put("max", numStats.get("max_value"));
            stats.put("avg", numStats.get("avg_value"));
            stats.put("stdDev", numStats.get("std_dev"));
        } else if ("DATE".equals(fieldType)) {
            Map<String, Object> dateStats = firstRow(datasourceService.executeQueryWithoutAudit(sourceKey,
                    "SELECT MIN(" + quotedColumn + ") AS minDate, MAX(" + quotedColumn + ") AS maxDate " +
                            "FROM `" + physicalTable + "` WHERE " + quotedColumn + " IS NOT NULL"));
            stats.put("minDate", dateStats.get("minDate"));
            stats.put("maxDate", dateStats.get("maxDate"));
        }

        stats.put("anomalies", detectAnomaliesForOfficialSource(tableName, columnName));
        return stats;
    }

    private List<Map<String, Object>> detectAnomaliesForUploadSource(String tableName, String columnName) {
        List<Map<String, Object>> fieldMeta = jdbcTemplate.queryForList(
            "SELECT field_type AS fieldType FROM is_data_field WHERE table_name = ? AND column_name = ?",
            tableName, columnName
        );

        if (fieldMeta.isEmpty()) {
            return List.of();
        }

        String fieldType = Objects.toString(fieldMeta.get(0).get("fieldType"), "TEXT");
        if (!"NUMBER".equals(fieldType)) {
            return List.of();
        }

        String quotedColumn = quoteColumn(columnName);
        String activeCondition = activeUploadRowCondition(tableName);
        Map<String, Object> stats = new LinkedHashMap<>();
        addNumericStatistics(stats, tableName, quotedColumn, activeCondition);

        Double avg = toNullableDouble(stats.get("avg"));
        Double stdDev = toNullableDouble(stats.get("stdDev"));

        if (avg == null || stdDev == null || stdDev == 0) {
            return List.of();
        }

        double lowerBound = avg - 3 * stdDev;
        double upperBound = avg + 3 * stdDev;

        String numericExpr = "CAST(NULLIF(TRIM(" + quotedColumn + "), '') AS DECIMAL(20,4))";
        List<Map<String, Object>> anomalies = jdbcTemplate.queryForList(
            "SELECT sys_id, " + quotedColumn + " AS value FROM `" + tableName +
            "` WHERE " + activeCondition + " " +
            "AND " + quotedColumn + " IS NOT NULL " +
            "AND TRIM(" + quotedColumn + ") <> '' " +
            "AND TRIM(" + quotedColumn + ") REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$' " +
            "AND (" + numericExpr + " < ? OR " + numericExpr + " > ?) " +
            "LIMIT 100",
            lowerBound, upperBound
        );
        anomalies = sqlAuditService.maskRows(tableName, anomalies);

        return anomalies.stream().map(row -> {
            Map<String, Object> anomaly = new LinkedHashMap<>();
            anomaly.put("rowId", row.get("sys_id"));
            anomaly.put("value", row.get("value"));
            anomaly.put("type", "OUTLIER");
            anomaly.put("reason", "数值超出 3σ 范围 [" + String.format("%.2f", lowerBound) + ", " + String.format("%.2f", upperBound) + "]");
            return anomaly;
        }).toList();
    }

    private List<Map<String, Object>> detectUploadFieldAnomalies(String tableName, String columnName) {
        List<Map<String, Object>> anomalies = new ArrayList<>(detectBusinessRuleAnomalies(tableName, columnName));
        for (Map<String, Object> anomaly : detectAnomaliesForUploadSource(tableName, columnName)) {
            if (anomalies.size() >= 100) {
                break;
            }
            if (!hasAnomalyForRow(anomalies, anomaly.get("rowId"))) {
                anomalies.add(anomaly);
            }
        }
        return anomalies;
    }

    private List<Map<String, Object>> detectBusinessRuleAnomalies(String tableName, String columnName) {
        List<Map<String, Object>> fieldMeta = jdbcTemplate.queryForList(
                "SELECT source_field_name AS sourceFieldName, display_name AS displayName, field_type AS fieldType FROM is_data_field WHERE table_name = ? AND column_name = ? LIMIT 1",
                tableName, columnName
        );
        if (fieldMeta.isEmpty()) {
            return List.of();
        }
        Map<String, Object> meta = fieldMeta.get(0);
        String label = (Objects.toString(meta.get("sourceFieldName"), "") + " " +
                Objects.toString(meta.get("displayName"), "") + " " + columnName).toLowerCase(Locale.ROOT);
        String fieldType = Objects.toString(meta.get("fieldType"), "TEXT");
        String quotedColumn = quoteColumn(columnName);
        String activeCondition = activeUploadRowCondition(tableName);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sys_id, " + quotedColumn + " AS value FROM `" + tableName + "` " +
                        "WHERE " + activeCondition + " AND (" + nonBlankValueCondition(quotedColumn) + ") LIMIT 1000"
        );
        List<Map<String, Object>> anomalies = new ArrayList<>();
        boolean emailField = containsAny(label, "email", "e-mail", "mail", "邮箱", "邮件");
        boolean phoneField = containsAny(label, "phone", "mobile", "tel", "手机号", "手机", "电话");
        boolean ageField = containsAny(label, "age", "年龄");
        boolean amountField = containsAny(label, "amount", "price", "cost", "fee", "money", "金额", "消费", "价格", "费用", "单价");
        boolean countField = containsAny(label, "count", "quantity", "qty", "num", "数量", "订单数量", "件数", "次数");
        boolean rateField = containsAny(label, "rate", "percent", "比例", "率");
        boolean levelField = containsAny(label, "level", "grade", "等级", "级别");

        for (Map<String, Object> row : rows) {
            if (anomalies.size() >= 100) {
                break;
            }
            Object rowId = row.get("sys_id");
            String value = Objects.toString(row.get("value"), "").trim();
            if (emailField && !isValidEmail(value)) {
                anomalies.add(anomaly(rowId, row.get("value"), "INVALID_EMAIL", "邮箱格式不合法"));
                continue;
            }
            if (phoneField && !isValidPhone(value)) {
                anomalies.add(anomaly(rowId, row.get("value"), "INVALID_PHONE", "手机号/电话格式不合法"));
                continue;
            }
            if (ageField) {
                Double number = parseNumber(value);
                if (number == null || number < 0 || number > 120) {
                    anomalies.add(anomaly(rowId, row.get("value"), "INVALID_AGE", "年龄应为 0 到 120 之间的数字"));
                    continue;
                }
            }
            if (amountField || countField) {
                Double number = parseNumber(value);
                if (number == null || number < 0) {
                    anomalies.add(anomaly(rowId, row.get("value"), amountField ? "NEGATIVE_AMOUNT" : "NEGATIVE_COUNT",
                            amountField ? "金额/消费类字段不能为负数" : "数量类字段不能为负数"));
                    continue;
                }
            }
            if (("NUMBER".equals(fieldType) || rateField) && rateField) {
                Double number = parseNumber(value);
                if (number == null || number < 0 || number > 100) {
                    anomalies.add(anomaly(rowId, row.get("value"), "INVALID_RATE", "比例/率字段应在 0 到 100 之间"));
                    continue;
                }
            }
            if (levelField && !isValidBusinessLevelValue(value)) {
                anomalies.add(anomaly(rowId, row.get("value"), "INVALID_LEVEL", "等级值不在允许范围内"));
            }
        }
        return anomalies;
    }

    private boolean containsAny(String text, String... keywords) {
        String safeText = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (safeText.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidBusinessLevelValue(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return List.of("A", "B", "C", "D", "S", "VIP", "SVIP",
                "高", "中", "低", "普通", "正常", "有效",
                "一级", "二级", "三级", "未分级").contains(normalized);
    }

    private List<Map<String, Object>> detectAnomaliesForOfficialSource(String tableName, String columnName) {
        String fieldType = resolveFieldType(tableName, columnName);
        if (!"NUMBER".equals(fieldType)) {
            return List.of();
        }
        String quotedColumn = quoteColumn(columnName);
        String physicalTable = datasourceService.physicalTableName(tableName);
        String sourceKey = tableName;
        String numericExpr = "CAST(NULLIF(TRIM(" + quotedColumn + "), '') AS DECIMAL(20,4))";
        String numericStatsSql = "SELECT " + String.join(", ",
                "AVG(" + numericExpr + ") AS avg_value",
                "STD(" + numericExpr + ") AS std_dev"
        ) + " FROM `" + physicalTable + "` " +
                "WHERE " + quotedColumn + " IS NOT NULL " +
                "AND TRIM(" + quotedColumn + ") <> '' " +
                "AND TRIM(" + quotedColumn + ") REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$'";
        Map<String, Object> numStats = firstRow(datasourceService.executeQueryWithoutAudit(sourceKey,
                numericStatsSql));
        Double avg = toNullableDouble(numStats.get("avg_value"));
        Double stdDev = toNullableDouble(numStats.get("std_dev"));
        if (avg == null || stdDev == null || stdDev == 0) {
            return List.of();
        }
        double lowerBound = avg - 3 * stdDev;
        double upperBound = avg + 3 * stdDev;
        List<Map<String, Object>> anomalies = datasourceService.executeQueryWithoutAudit(sourceKey,
                "SELECT " + quotedColumn + " AS value FROM `" + physicalTable + "` " +
                        "WHERE " + quotedColumn + " IS NOT NULL " +
                        "AND TRIM(" + quotedColumn + ") <> '' " +
                        "AND TRIM(" + quotedColumn + ") REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$' " +
                        "AND (" + numericExpr + " < " + lowerBound + " OR " + numericExpr + " > " + upperBound + ") " +
                        "LIMIT 100");
        anomalies = sqlAuditService.maskRows(tableName, anomalies);

        return anomalies.stream().map(row -> {
            Map<String, Object> anomaly = new LinkedHashMap<>();
            anomaly.put("value", row.get("value"));
            anomaly.put("type", "OUTLIER");
            anomaly.put("reason", "数值超出 3σ 范围 [" + String.format("%.2f", lowerBound) + ", " + String.format("%.2f", upperBound) + "]");
            return anomaly;
        }).toList();
    }

    private Map<String, Object> getFieldDistributionForUploadSource(String tableName, String columnName) {
        String quotedColumn = quoteColumn(columnName);

        List<Map<String, Object>> fieldMeta = jdbcTemplate.queryForList(
            "SELECT field_type AS fieldType FROM is_data_field WHERE table_name = ? AND column_name = ?",
            tableName, columnName
        );

        if (fieldMeta.isEmpty()) {
            return Map.of("columnName", columnName, "distribution", List.of());
        }

        String fieldType = Objects.toString(fieldMeta.get(0).get("fieldType"), "TEXT");
        if ("NUMBER".equals(fieldType)) {
            return getNumericDistribution(tableName, quotedColumn, columnName);
        }
        return getCategoricalDistribution(tableName, quotedColumn, columnName);
    }

    private Map<String, Object> getFieldDistributionForOfficialSource(String tableName, String columnName) {
        String fieldType = resolveFieldType(tableName, columnName);
        String quotedColumn = quoteColumn(columnName);
        String physicalTable = datasourceService.physicalTableName(tableName);
        String sourceKey = tableName;
        List<Map<String, Object>> distribution;
        if ("NUMBER".equals(fieldType)) {
            String numericExpr = "CAST(NULLIF(TRIM(" + quotedColumn + "), '') AS DECIMAL(20,4))";
            distribution = datasourceService.executeQueryWithoutAudit(sourceKey,
                    "WITH numeric_data AS (" +
                            " SELECT " + numericExpr + " AS numeric_value FROM `" + physicalTable + "` " +
                            " WHERE " + quotedColumn + " IS NOT NULL " +
                            "   AND TRIM(" + quotedColumn + ") <> '' " +
                            "   AND TRIM(" + quotedColumn + ") REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$'" +
                            "), stats AS (" +
                            " SELECT MIN(numeric_value) AS min_value, MAX(numeric_value) AS max_value FROM numeric_data" +
                            ") " +
                            "SELECT CASE " +
                            " WHEN stats.max_value = stats.min_value THEN 0 " +
                            " ELSE FLOOR((numeric_data.numeric_value - stats.min_value) / NULLIF((stats.max_value - stats.min_value) / 10, 0)) " +
                            " END AS bucket, COUNT(*) AS count " +
                            "FROM numeric_data CROSS JOIN stats " +
                            "GROUP BY bucket ORDER BY bucket LIMIT 20");
            return Map.of(
                    "columnName", columnName,
                    "type", "NUMERIC",
                    "distribution", distribution
            );
        }
        distribution = datasourceService.executeQueryWithoutAudit(sourceKey,
                "SELECT " + quotedColumn + " AS category, COUNT(*) AS count " +
                        "FROM `" + physicalTable + "` " +
                        "WHERE " + quotedColumn + " IS NOT NULL AND " + quotedColumn + " != '' " +
                        "GROUP BY " + quotedColumn + " " +
                        "ORDER BY count DESC LIMIT 20");
        distribution = applyMaskOnAlias(distribution, tableName, columnName, "category");
        return Map.of(
                "columnName", columnName,
                "type", "CATEGORICAL",
                "distribution", distribution
        );
    }

    private String resolveFieldType(String tableName, String columnName) {
        if (datasourceService.isOfficialSource(tableName)) {
            return datasourceService.listQueryFields(tableName).stream()
                    .filter(item -> columnName.equals(Objects.toString(item.get("columnName"), "")))
                    .map(item -> Objects.toString(item.get("fieldType"), "TEXT"))
                    .findFirst()
                    .orElse("TEXT");
        }
        List<Map<String, Object>> fieldMeta = jdbcTemplate.queryForList(
                "SELECT field_type AS fieldType FROM is_data_field WHERE table_name = ? AND column_name = ?",
                tableName, columnName
        );
        if (fieldMeta.isEmpty()) {
            return "TEXT";
        }
        return Objects.toString(fieldMeta.get(0).get("fieldType"), "TEXT");
    }

    private long readLongValue(List<Map<String, Object>> rows, String key) {
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        Object value = rows.get(0).get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return Math.round(Double.parseDouble(text));
        }
    }

    private Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> applyMaskOnAlias(List<Map<String, Object>> rows, String tableName,
                                                        String sourceColumn, String aliasColumn) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            if (!copy.containsKey(sourceColumn) && copy.containsKey(aliasColumn)) {
                copy.put(sourceColumn, copy.get(aliasColumn));
            }
            normalized.add(copy);
        }
        List<Map<String, Object>> masked = sqlAuditService.maskRows(tableName, normalized);
        for (Map<String, Object> row : masked) {
            if (row.containsKey(sourceColumn) && row.containsKey(aliasColumn)) {
                row.put(aliasColumn, row.get(sourceColumn));
            }
        }
        return masked;
    }
    
    private void addNumericStatistics(Map<String, Object> stats, String tableName, String quotedColumn) {
        addNumericStatistics(stats, tableName, quotedColumn, "1=1");
    }

    private void addNumericStatistics(Map<String, Object> stats, String tableName, String quotedColumn, String rowCondition) {
        String numericExpr = "CAST(NULLIF(TRIM(" + quotedColumn + "), '') AS DECIMAL(20,4))";
        String safeRowCondition = rowCondition == null || rowCondition.isBlank() ? "1=1" : rowCondition;
        try {
            Map<String, Object> numStats = jdbcTemplate.queryForMap(
                "SELECT " +
                "  MIN(" + numericExpr + ") AS minValue, " +
                "  MAX(" + numericExpr + ") AS maxValue, " +
                "  AVG(" + numericExpr + ") AS avgValue, " +
                "  STD(" + numericExpr + ") AS stdDev " +
                "FROM `" + tableName + "` " +
                "WHERE " + safeRowCondition + " " +
                "AND " + quotedColumn + " IS NOT NULL " +
                "AND TRIM(" + quotedColumn + ") <> '' " +
                "AND TRIM(" + quotedColumn + ") REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$'"
            );
            
            stats.put("min", numStats.get("minValue"));
            stats.put("max", numStats.get("maxValue"));
            stats.put("avg", numStats.get("avgValue"));
            stats.put("stdDev", numStats.get("stdDev"));
        } catch (Exception e) {
            log.debug("计算数值统计信息失败，table={}, column={}, reason={}", tableName, quotedColumn, e.getMessage());
        }
    }
    
    private void addDateStatistics(Map<String, Object> stats, String tableName, String quotedColumn) {
        try {
            Map<String, Object> dateStats = jdbcTemplate.queryForMap(
                "SELECT " +
                "  MIN(" + quotedColumn + ") AS minDate, " +
                "  MAX(" + quotedColumn + ") AS maxDate " +
                "FROM `" + tableName + "` " +
                "WHERE " + quotedColumn + " IS NOT NULL AND " + quotedColumn + " != ''"
            );
            
            stats.put("minDate", dateStats.get("minDate"));
            stats.put("maxDate", dateStats.get("maxDate"));
        } catch (Exception e) {
            log.warn("计算日期统计信息失败：{}", e.getMessage());
        }
    }
    
    private Map<String, Object> getNumericDistribution(String tableName, String quotedColumn, String columnName) {
        String numericExpr = "CAST(NULLIF(TRIM(" + quotedColumn + "), '') AS DECIMAL(20,4))";
        List<Map<String, Object>> distribution;
        try {
            distribution = jdbcTemplate.queryForList(
                "WITH numeric_data AS (" +
                "  SELECT " + numericExpr + " AS numeric_value FROM `" + tableName + "` " +
                "  WHERE " + quotedColumn + " IS NOT NULL " +
                "    AND TRIM(" + quotedColumn + ") <> '' " +
                "    AND TRIM(" + quotedColumn + ") REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$'" +
                "), stats AS (" +
                "  SELECT MIN(numeric_value) AS min_value, MAX(numeric_value) AS max_value FROM numeric_data" +
                ") " +
                "SELECT CASE " +
                "         WHEN stats.max_value = stats.min_value THEN 0 " +
                "         ELSE FLOOR((numeric_data.numeric_value - stats.min_value) / NULLIF((stats.max_value - stats.min_value) / 10, 0)) " +
                "       END AS bucket, " +
                "       COUNT(*) AS count " +
                "FROM numeric_data CROSS JOIN stats " +
                "GROUP BY bucket " +
                "ORDER BY bucket " +
                "LIMIT 20"
            );
        } catch (Exception e) {
            log.debug("计算数值分布失败，table={}, column={}, reason={}", tableName, quotedColumn, e.getMessage());
            distribution = List.of();
        }

        return Map.of(
            "columnName", columnName,
            "type", "NUMERIC",
            "distribution", distribution
        );
    }
    
    private Map<String, Object> getCategoricalDistribution(String tableName, String quotedColumn, String columnName) {
        List<Map<String, Object>> distribution = jdbcTemplate.queryForList(
            "SELECT " + quotedColumn + " AS category, COUNT(*) AS count " +
            "FROM `" + tableName + "` " +
            "WHERE " + quotedColumn + " IS NOT NULL AND " + quotedColumn + " != '' " +
            "GROUP BY " + quotedColumn + " " +
            "ORDER BY count DESC " +
            "LIMIT 20"
        );
        
        return Map.of(
            "columnName", columnName,
            "type", "CATEGORICAL",
            "distribution", distribution
        );
    }
    
    private Double toNullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(Objects.toString(value, "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String calculateMD5(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    private long uploadMaxBytes() {
        int maxMb = runtimeConfig != null ? runtimeConfig.getInt("upload.max.fileSizeMb", 100) : 100;
        return Math.max(1, maxMb) * 1024L * 1024L;
    }

    private List<String> uploadAllowedFormats() {
        if (runtimeConfig == null) {
            return List.of("xlsx", "xls", "csv");
        }
        List<String> formats = runtimeConfig.getStringList("upload.allowed.formats");
        return formats.isEmpty() ? List.of("xlsx", "xls", "csv") : formats;
    }

    private void ensureUploadRoleAllowed() {
        if (runtimeConfig == null) {
            return;
        }
        List<String> roles = runtimeConfig.getStringList("upload.permission.roles");
        if (roles.isEmpty()) {
            return;
        }
        String current = AuthContext.role();
        if (current == null || roles.stream().noneMatch(role -> role.equalsIgnoreCase(current))) {
            throw new IllegalArgumentException("当前角色无权上传数据");
        }
    }

    private boolean checkDuplicateByMD5(String md5) {
        if (md5 == null || md5.isBlank()) {
            return false;
        }
        
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_file_upload_history` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `file_md5` VARCHAR(64) NOT NULL,
                  `file_name` VARCHAR(255) NOT NULL,
                  `table_name` VARCHAR(128) NULL,
                  `uploaded_by` VARCHAR(64) NOT NULL,
                  `uploaded_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_file_md5` (`file_md5`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传历史记录';
                """);
            
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_file_upload_history WHERE file_md5 = ?",
                Integer.class,
                md5
            );
            
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("检查文件重复失败：{}", e.getMessage());
            return false;
        }
    }

    private record StoredMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) implements MultipartFile {
        static StoredMultipartFile from(MultipartFile file) throws IOException {
            return new StoredMultipartFile(file.getName(), file.getOriginalFilename(), file.getContentType(), file.getBytes());
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }
}
