package com.insightspark.service;

import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SqlAuditService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${insight.redis.enabled:true}")
    private boolean redisEnabled;

    @Value("${insight.redis.host:localhost}")
    private String redisHost;

    @Value("${insight.redis.port:6379}")
    private int redisPort;

    @Value("${insight.redis.password:}")
    private String redisPassword;

    @Value("${insight.redis.database:0}")
    private int redisDatabase;

    @Value("${insight.redis.ttl-seconds:3600}")
    private int redisTtlSeconds;

    private final Semaphore querySemaphore = new Semaphore(4, true);

    @PostConstruct
    public void initAuditTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_sql_audit_log` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL DEFAULT '',
                  `question` VARCHAR(1000) NOT NULL,
                  `table_name` VARCHAR(128) NULL,
                  `engine` VARCHAR(64) NULL,
                  `generated_sql` TEXT NOT NULL,
                  `risk_level` VARCHAR(32) NOT NULL,
                  `risk_reason` VARCHAR(1000) NULL,
                  `matched_rules` VARCHAR(1000) NULL,
                  `sensitive_fields` VARCHAR(1000) NULL,
                  `slow_query` TINYINT(1) NOT NULL DEFAULT 0,
                  `execute_status` VARCHAR(32) NOT NULL,
                  `duration_ms` BIGINT NULL,
                  `error_message` VARCHAR(1000) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_sql_audit_created_at` (`created_at`),
                  INDEX `idx_sql_audit_risk_level` (`risk_level`),
                  INDEX `idx_sql_audit_status` (`execute_status`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL安全审计日志';
                """);
        addColumnIfMissing("is_sql_audit_log", "matched_rules", "`matched_rules` VARCHAR(1000) NULL");
        addColumnIfMissing("is_sql_audit_log", "sensitive_fields", "`sensitive_fields` VARCHAR(1000) NULL");
        addColumnIfMissing("is_sql_audit_log", "slow_query", "`slow_query` TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing("is_sql_audit_log", "generation_trace", "`generation_trace` TEXT NULL");
        addColumnIfMissing("is_sql_audit_log", "kg_match_log", "`kg_match_log` TEXT NULL");
        addColumnIfMissing("is_sql_audit_log", "cache_key", "`cache_key` VARCHAR(255) NULL");
        addColumnIfMissing("is_sql_audit_log", "cache_hit", "`cache_hit` TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing("is_sql_audit_log", "cache_sql", "`cache_sql` TEXT NULL");
        addColumnIfMissing("is_sql_audit_log", "cache_audit_status", "`cache_audit_status` VARCHAR(32) NULL");
        addColumnIfMissing("is_sql_audit_log", "mask_detail", "`mask_detail` VARCHAR(1000) NULL");
        addColumnIfMissing("is_sql_audit_log", "execution_guard", "`execution_guard` VARCHAR(1000) NULL");
        addColumnIfMissing("is_sql_audit_log", "query_guard_action", "`query_guard_action` VARCHAR(32) NULL");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_sql_audit_rule` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `rule_code` VARCHAR(64) NOT NULL UNIQUE,
                  `rule_name` VARCHAR(128) NOT NULL,
                  `risk_level` VARCHAR(32) NOT NULL,
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `rule_desc` VARCHAR(1000) NULL,
                  `threshold_value` BIGINT NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL审计规则配置';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_sensitive_field_rule` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `field_keyword` VARCHAR(128) NOT NULL UNIQUE,
                  `mask_type` VARCHAR(32) NOT NULL DEFAULT 'MIDDLE',
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感字段识别与脱敏规则';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_semantic_cache_audit` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `cache_key` VARCHAR(255) NOT NULL,
                  `question` VARCHAR(1000) NULL,
                  `table_name` VARCHAR(128) NULL,
                  `cached_sql` TEXT NULL,
                  `hit_count` BIGINT NOT NULL DEFAULT 0,
                  `risk_level` VARCHAR(32) NOT NULL DEFAULT 'SAFE',
                  `risk_reason` VARCHAR(1000) NULL,
                  `redis_status` VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
                  `last_hit_at` DATETIME NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_semantic_cache_key` (`cache_key`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Redis语义缓存审计';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_data_row_policy` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `table_name` VARCHAR(128) NOT NULL,
                  `principal_type` VARCHAR(32) NOT NULL,
                  `principal_id` VARCHAR(128) NOT NULL,
                  `filter_expression` VARCHAR(1000) NOT NULL,
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_data_row_policy_table` (`table_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传表行级数据域策略';
                """);
        seedRules();
        seedSensitiveRules();
    }

    public AuditResult inspect(String sql, String expectedTableName) {
        if (sql == null || sql.trim().isEmpty()) {
            return AuditResult.blocked("空 SQL，已拦截", List.of(), List.of());
        }

        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        List<String> matchedRules = new ArrayList<>();

        Statement statement;
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements().size() != 1) {
                return AuditResult.blocked("仅允许提交一条 SQL", List.of("MULTI_STATEMENT"), List.of());
            }
            statement = statements.getStatements().get(0);
        } catch (Exception e) {
            return AuditResult.blocked("SQL 语法解析失败，已拦截：" + e.getMessage(), List.of("AST_PARSE"), List.of());
        }
        if (!(statement instanceof Select)) {
            return AuditResult.blocked("仅允许 SELECT 查询", List.of("ONLY_SELECT"), List.of());
        }
        if (expectedTableName != null && !expectedTableName.isBlank()) {
            List<String> tables = new TablesNamesFinder().getTableList(statement);
            String expected = normalizeTableName(expectedTableName);
            for (String table : tables) {
                if (!expected.equals(normalizeTableName(table))) {
                    return AuditResult.blocked("访问未授权表：" + table, List.of("TABLE_SCOPE"), List.of());
                }
            }
        }

        if (isRuleEnabled("ONLY_SELECT") && !normalized.startsWith("select")) {
            reasons.add("仅允许 SELECT 查询");
            matchedRules.add("ONLY_SELECT");
        }

        String[] blockedKeywords = {
                " drop ", " delete ", " update ", " insert ", " alter ", " truncate ",
                " create ", " replace ", " grant ", " revoke ", " execute "
        };
        String paddedSql = " " + normalized.replaceAll("\\s+", " ") + " ";
        if (isRuleEnabled("DANGEROUS_KEYWORD")) {
            for (String keyword : blockedKeywords) {
                if (paddedSql.contains(keyword)) {
                    reasons.add("包含危险关键字：" + keyword.trim().toUpperCase(Locale.ROOT));
                    matchedRules.add("DANGEROUS_KEYWORD");
                }
            }
        }

        if (isRuleEnabled("MULTI_STATEMENT") && normalized.contains(";") && normalized.indexOf(';') < normalized.length() - 1) {
            reasons.add("疑似多语句 SQL");
            matchedRules.add("MULTI_STATEMENT");
        }
        if (isRuleEnabled("SYSTEM_TABLE_BLOCK") && accessesSystemTable(normalized)) {
            reasons.add("禁止访问系统表、元数据表或审计底表");
            matchedRules.add("SYSTEM_TABLE_BLOCK");
        }

        if (!reasons.isEmpty()) {
            return AuditResult.blocked(String.join("；", reasons), matchedRules, List.of());
        }

        List<String> warningReasons = new ArrayList<>();
        if (isRuleEnabled("LIMIT_REQUIRED") && !normalized.contains(" limit ")) {
            warningReasons.add("未包含 LIMIT，可能产生大结果集");
            matchedRules.add("LIMIT_REQUIRED");
        }
        if (isRuleEnabled("NO_SELECT_STAR") && normalized.contains("select *")) {
            warningReasons.add("使用 SELECT *，建议限制字段范围");
            matchedRules.add("NO_SELECT_STAR");
        }
        List<String> sensitiveFields = findSensitiveFields(expectedTableName, normalized);
        log.debug("敏感字段检测结果 - 表名: {}, SQL: {}, 敏感字段: {}", expectedTableName, normalized, sensitiveFields);
        if (isRuleEnabled("SENSITIVE_FIELD") && !sensitiveFields.isEmpty()) {
            warningReasons.add("访问敏感字段：" + String.join("、", sensitiveFields));
            matchedRules.add("SENSITIVE_FIELD");
        }

        if (!warningReasons.isEmpty()) {
            return new AuditResult("WARN", String.join("；", warningReasons), false, matchedRules, sensitiveFields);
        }
        return new AuditResult("SAFE", "通过基础安全检测", false, matchedRules, sensitiveFields);
    }

    public void record(String question, String tableName, String engine, String sql, AuditResult auditResult,
                       String executeStatus, Long durationMs, String errorMessage) {
        recordDetailed(question, tableName, engine, sql, auditResult, executeStatus, durationMs, errorMessage, Map.of());
    }

    public void recordDetailed(String question, String tableName, String engine, String sql, AuditResult auditResult,
                               String executeStatus, Long durationMs, String errorMessage, Map<String, Object> details) {
        long slowThreshold = getRuleThreshold("SLOW_QUERY", 3000L);
        long breakerThreshold = getRuleThreshold("SLOW_QUERY_BREAKER", 8000L);
        boolean slowQuery = durationMs != null && durationMs > slowThreshold && isRuleEnabled("SLOW_QUERY");
        boolean breakerHit = durationMs != null && durationMs > breakerThreshold && isRuleEnabled("SLOW_QUERY_BREAKER");
        String riskLevel = auditResult.riskLevel();
        String riskReason = auditResult.riskReason();
        List<String> matchedRules = new ArrayList<>(auditResult.matchedRules());
        if (breakerHit && !"BLOCKED".equals(riskLevel)) {
            riskLevel = "BLOCKED";
            riskReason = "慢查询熔断风险，耗时 " + durationMs + " ms，超过阈值 " + breakerThreshold + " ms";
            matchedRules.add("SLOW_QUERY_BREAKER");
        }
        if (slowQuery && !"BLOCKED".equals(riskLevel)) {
            riskLevel = "WARN";
            riskReason = (riskReason == null || riskReason.isBlank() || "通过基础安全检测".equals(riskReason))
                    ? "慢查询，耗时 " + durationMs + " ms"
                    : riskReason + "；慢查询，耗时 " + durationMs + " ms";
            matchedRules.add("SLOW_QUERY");
        }

        jdbcTemplate.update("""
                INSERT INTO is_sql_audit_log(user_id, question, table_name, engine, generated_sql, risk_level,
                                             risk_reason, matched_rules, sensitive_fields, slow_query,
                                             execute_status, duration_ms, error_message, generation_trace,
                                             kg_match_log, cache_key, cache_hit, cache_sql, cache_audit_status,
                                             mask_detail, execution_guard, query_guard_action)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                AuthContext.userId(),
                safeText(question, 1000),
                tableName,
                engine,
                sql,
                riskLevel,
                safeText(riskReason, 1000),
                safeText(String.join(",", matchedRules), 1000),
                safeText(String.join(",", auditResult.sensitiveFields()), 1000),
                slowQuery,
                executeStatus,
                durationMs,
                safeText(errorMessage, 1000),
                safeText(stringDetail(details, "generationTrace"), 65535),
                safeText(stringDetail(details, "kgMatchLog"), 65535),
                safeText(stringDetail(details, "cacheKey"), 255),
                booleanDetail(details, "cacheHit"),
                safeText(stringDetail(details, "cacheSql"), 65535),
                safeText(stringDetail(details, "cacheAuditStatus"), 32),
                safeText(stringDetail(details, "maskDetail"), 1000),
                safeText(stringDetail(details, "executionGuard"), 1000),
                safeText(stringDetail(details, "queryGuardAction"), 32)
        );
        recordSemanticCache(question, tableName, sql, auditResult, executeStatus, details);
    }

    public Map<String, Object> submitSqlAudit(Map<String, Object> request) {
        String sql = Objects.toString(request.get("sql"), "");
        String tableName = Objects.toString(request.getOrDefault("tableName", ""), "");
        String question = Objects.toString(request.getOrDefault("question", "管理员提交 SQL 审计"));
        AuditResult result = inspect(sql, tableName);
        record(question, tableName, "manual-submit", sql, result,
                result.blocked() ? "BLOCKED" : "NOT_EXECUTED", 0L, result.blocked() ? result.riskReason() : null);
        return Map.of(
                "riskLevel", result.riskLevel(),
                "riskReason", result.riskReason(),
                "blocked", result.blocked(),
                "matchedRules", result.matchedRules(),
                "sensitiveFields", result.sensitiveFields()
        );
    }

    public List<Map<String, Object>> maskRows(String tableName, List<Map<String, Object>> rows) {
        return maskRowsWithReport(tableName, rows).rows();
    }

    public MaskReport maskRowsWithReport(String tableName, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty() || tableName == null || tableName.isBlank()) {
            return new MaskReport(rows, "");
        }
        Map<String, String> sensitiveColumns;
        if (tableName.startsWith("official:")) {
            String[] parts = tableName.split(":", 3);
            sensitiveColumns = parts.length == 3 ? sensitiveColumnMaskTypes("""
                    SELECT column_name AS columnName,
                           COALESCE(NULLIF(business_name, ''), NULLIF(column_comment, ''), column_name) AS displayName,
                           column_comment AS commentText, synonyms
                    FROM is_official_schema_field
                    WHERE datasource_id = ? AND table_name = ? AND sensitive = 1
                    """, Long.parseLong(parts[1]), parts[2]) : Map.of();
        } else {
            sensitiveColumns = sensitiveColumnMaskTypes("""
                    SELECT column_name AS columnName, display_name AS displayName,
                           field_comment AS commentText, synonyms
                    FROM is_data_field
                    WHERE table_name = ? AND sensitive = 1
                    """, tableName);
        }
        if (sensitiveColumns.isEmpty()) {
            return new MaskReport(rows, "");
        }
        List<Map<String, Object>> masked = new ArrayList<>();
        Map<String, Integer> hits = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (Map.Entry<String, String> rule : sensitiveColumns.entrySet()) {
                String column = rule.getKey();
                if (copy.containsKey(column)) {
                    Object before = copy.get(column);
                    String after = maskValue(before, rule.getValue());
                    copy.put(column, after);
                    hits.put(column, hits.getOrDefault(column, 0) + 1);
                    if (samples.size() < 5) {
                        samples.add(column + ": " + previewRawValue(before) + " -> " + after);
                    }
                }
            }
            masked.add(copy);
        }
        String detail = hits.entrySet().stream()
                .map(entry -> entry.getKey() + ":MASKED_ROWS=" + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        if (!samples.isEmpty()) {
            detail = detail + "; samples=[" + String.join(" | ", samples) + "]";
        }
        return new MaskReport(masked, detail);
    }

    public List<Map<String, Object>> listLogs(String riskLevel, String executeStatus, int limit) {
        return listLogs(riskLevel, executeStatus, null, null, null, null, null, limit);
    }

    public List<Map<String, Object>> listLogs(String riskLevel, String executeStatus, String userId, String tableName,
                                              Boolean cacheHit, Boolean slowQuery, String keyword, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id AS userId, question, table_name AS tableName, engine,
                       generated_sql AS generatedSql, risk_level AS riskLevel,
                       risk_reason AS riskReason, matched_rules AS matchedRules,
                       sensitive_fields AS sensitiveFields, slow_query AS slowQuery,
                       execute_status AS executeStatus,
                       duration_ms AS durationMs, error_message AS errorMessage,
                       generation_trace AS generationTrace, kg_match_log AS kgMatchLog,
                       cache_key AS cacheKey, cache_hit AS cacheHit, cache_sql AS cacheSql,
                       cache_audit_status AS cacheAuditStatus, mask_detail AS maskDetail,
                       execution_guard AS executionGuard, query_guard_action AS queryGuardAction,
                       created_at AS createdAt
                FROM is_sql_audit_log
                WHERE 1 = 1
                """);

        if (riskLevel != null && !riskLevel.isBlank()) {
            sql.append(" AND risk_level = ?");
            args.add(riskLevel);
        }
        if (executeStatus != null && !executeStatus.isBlank()) {
            sql.append(" AND execute_status = ?");
            args.add(executeStatus);
        }
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        if (tableName != null && !tableName.isBlank()) {
            sql.append(" AND table_name LIKE ?");
            args.add("%" + tableName + "%");
        }
        if (cacheHit != null) {
            sql.append(" AND cache_hit = ?");
            args.add(cacheHit ? 1 : 0);
        }
        if (slowQuery != null) {
            sql.append(" AND slow_query = ?");
            args.add(slowQuery ? 1 : 0);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (question LIKE ? OR generated_sql LIKE ? OR risk_reason LIKE ? OR matched_rules LIKE ?)");
            String like = "%" + keyword + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ").append(safeLimit);

        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listRules() {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList("""
                SELECT id, rule_code AS ruleCode, rule_name AS ruleName, risk_level AS riskLevel,
                       enabled, rule_desc AS ruleDesc, threshold_value AS thresholdValue,
                       updated_at AS updatedAt
                FROM is_sql_audit_rule
                ORDER BY id ASC
                """);
        // 将 enabled 字段转换为布尔值，保持前后端数据类型一致
        for (Map<String, Object> rule : rules) {
            if (rule.get("enabled") instanceof Number) {
                rule.put("enabled", ((Number) rule.get("enabled")).intValue() == 1);
            }
        }
        return rules;
    }

    public Map<String, Object> stats() {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN risk_level = 'BLOCKED' THEN 1 ELSE 0 END) AS blockedCount,
                       SUM(CASE WHEN risk_level = 'WARN' THEN 1 ELSE 0 END) AS warnCount,
                       SUM(CASE WHEN sensitive_fields IS NOT NULL AND sensitive_fields <> '' THEN 1 ELSE 0 END) AS sensitiveCount,
                       SUM(CASE WHEN slow_query = 1 THEN 1 ELSE 0 END) AS slowCount,
                       SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END) AS cacheHitCount,
                       ROUND(AVG(CASE WHEN duration_ms IS NULL THEN 0 ELSE duration_ms END), 0) AS avgDurationMs
                FROM is_sql_audit_log
                """);
        Map<String, Object> rules = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS ruleCount,
                       SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END) AS enabledRuleCount
                FROM is_sql_audit_rule
                """);
        return Map.of(
                "total", number(totals.get("total")),
                "blockedCount", number(totals.get("blockedCount")),
                "warnCount", number(totals.get("warnCount")),
                "sensitiveCount", number(totals.get("sensitiveCount")),
                "slowCount", number(totals.get("slowCount")),
                "cacheHitCount", number(totals.get("cacheHitCount")),
                "avgDurationMs", number(totals.get("avgDurationMs")),
                "ruleCount", number(rules.get("ruleCount")),
                "enabledRuleCount", number(rules.get("enabledRuleCount"))
        );
    }

    public void updateRuleStatus(String ruleCode, boolean enabled) {
        jdbcTemplate.update("UPDATE is_sql_audit_rule SET enabled = ? WHERE rule_code = ?", enabled, ruleCode);
    }

    public void updateRuleConfig(String ruleCode, Map<String, Object> request) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(request.getOrDefault("enabled", "true")));
        Long threshold = request.containsKey("thresholdValue") && request.get("thresholdValue") != null
                && !String.valueOf(request.get("thresholdValue")).isBlank()
                ? Long.parseLong(String.valueOf(request.get("thresholdValue")))
                : null;
        jdbcTemplate.update("""
                UPDATE is_sql_audit_rule
                SET enabled = ?, threshold_value = COALESCE(?, threshold_value)
                WHERE rule_code = ?
                """, enabled, threshold, ruleCode);
    }

    public byte[] exportLogsCsv(String riskLevel, String executeStatus, int limit) {
        List<Map<String, Object>> logs = listLogs(riskLevel, executeStatus, limit);
        StringBuilder csv = new StringBuilder("\uFEFF时间,用户,风险,状态,耗时,数据表,规则,敏感字段,问题,SQL,说明\n");
        for (Map<String, Object> log : logs) {
            csv.append(csvCell(Objects.toString(log.get("createdAt"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("userId"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("riskLevel"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("executeStatus"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("durationMs"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("tableName"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("matchedRules"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("sensitiveFields"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("question"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("generatedSql"), ""))).append(',')
                    .append(csvCell(Objects.toString(log.get("riskReason"), ""))).append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] exportLogsExcel(String riskLevel, String executeStatus, int limit) {
        List<Map<String, Object>> logs = listLogs(riskLevel, executeStatus, limit);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("SQL Audit Logs");
            List<String> headers = List.of(
                    "Time", "User", "Risk", "Status", "Duration(ms)", "Table", "Rules", "Sensitive Fields",
                    "Cache Hit", "Cache Key", "Cache SQL", "Cache Audit", "Mask Detail", "Execution Guard",
                    "Question", "SQL", "Risk Reason", "Generation Trace", "KG Match Log", "Error"
            );
            List<String> keys = List.of(
                    "createdAt", "userId", "riskLevel", "executeStatus", "durationMs", "tableName",
                    "matchedRules", "sensitiveFields", "cacheHit", "cacheKey", "cacheSql", "cacheAuditStatus",
                    "maskDetail", "executionGuard", "question", "generatedSql", "riskReason",
                    "generationTrace", "kgMatchLog", "errorMessage"
            );
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < logs.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> log = logs.get(r);
                for (int c = 0; c < keys.size(); c++) {
                    row.createCell(c).setCellValue(Objects.toString(log.get(keys.get(c)), ""));
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i), 12000));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Excel export failed: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> listSensitiveRules() {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList("""
                SELECT id, field_keyword AS fieldKeyword, mask_type AS maskType, enabled, created_at AS createdAt
                FROM is_sensitive_field_rule
                ORDER BY enabled DESC, field_keyword ASC
                """);
        for (Map<String, Object> rule : rules) {
            if (rule.get("enabled") instanceof Number number) {
                rule.put("enabled", number.intValue() == 1);
            }
        }
        return rules;
    }

    public Map<String, Object> saveSensitiveRule(Map<String, Object> request) {
        String keyword = Objects.toString(request.get("fieldKeyword"), "").trim();
        String maskType = Objects.toString(request.getOrDefault("maskType", "MIDDLE")).trim().toUpperCase(Locale.ROOT);
        boolean enabled = Boolean.parseBoolean(Objects.toString(request.getOrDefault("enabled", "true")));
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("fieldKeyword is required");
        }
        jdbcTemplate.update("""
                INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type), enabled = VALUES(enabled)
                """, keyword, maskType, enabled);
        return Map.of("fieldKeyword", keyword, "maskType", maskType, "enabled", enabled);
    }

    public void updateSensitiveRuleStatus(Long id, boolean enabled) {
        jdbcTemplate.update("UPDATE is_sensitive_field_rule SET enabled = ? WHERE id = ?", enabled, id);
    }

    public void deleteSensitiveRule(Long id) {
        jdbcTemplate.update("DELETE FROM is_sensitive_field_rule WHERE id = ?", id);
    }

    public Map<String, Object> cacheOverview() {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS cacheCount,
                       COALESCE(SUM(hit_count), 0) AS hitCount,
                       SUM(CASE WHEN redis_status = 'UP' THEN 1 ELSE 0 END) AS redisUpCount,
                       MAX(last_hit_at) AS lastHitAt
                FROM is_semantic_cache_audit
                """);
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total, SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END) AS auditHitCount
                FROM is_sql_audit_log
                """);
        long total = number(audit.get("total"));
        long hit = number(audit.get("auditHitCount"));
        return Map.of(
                "cacheCount", number(totals.get("cacheCount")),
                "hitCount", number(totals.get("hitCount")),
                "auditHitCount", hit,
                "hitRate", total == 0 ? 0 : Math.round(hit * 10000.0 / total) / 100.0,
                "redisStatus", number(totals.get("redisUpCount")) > 0 ? "UP" : "LOCAL",
                "lastHitAt", Objects.toString(totals.get("lastHitAt"), "")
        );
    }

    public String semanticCacheKey(String question, String tableName) {
        String raw = Objects.toString(tableName, "") + "\n" + Objects.toString(question, "").trim().toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "semantic:" + HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            return "semantic:" + Integer.toHexString(raw.hashCode());
        }
    }

    public Map<String, Object> findSemanticCache(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return Map.of();
        }
        String redisSql = redisGet(cacheKey);
        if (redisSql != null && !redisSql.isBlank()) {
            return Map.of(
                    "cacheKey", cacheKey,
                    "cachedSql", redisSql,
                    "riskLevel", "SAFE",
                    "riskReason", "Redis 命中，执行前重新审计通过后使用",
                    "redisStatus", "UP",
                    "hitCount", 1
            );
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT cache_key AS cacheKey, cached_sql AS cachedSql, risk_level AS riskLevel,
                       risk_reason AS riskReason, redis_status AS redisStatus, hit_count AS hitCount,
                       last_hit_at AS lastHitAt
                FROM is_semantic_cache_audit
                WHERE cache_key = ?
                """, cacheKey);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public AuditResult inspectCachedSql(String cacheKey, String tableName) {
        Map<String, Object> cache = findSemanticCache(cacheKey);
        if (cache.isEmpty()) {
            return new AuditResult("SAFE", "缓存未命中", false, List.of("CACHE_MISS"), List.of());
        }
        String cachedSql = Objects.toString(cache.get("cachedSql"), "");
        AuditResult result = inspect(cachedSql, tableName);
        if (result.blocked()) {
            jdbcTemplate.update("""
                    UPDATE is_semantic_cache_audit
                    SET risk_level = 'BLOCKED', risk_reason = ?, redis_status = 'QUARANTINED'
                    WHERE cache_key = ?
                    """, safeText(result.riskReason(), 1000), cacheKey);
        }
        return result;
    }

    public QueryGuardResult guardSqlBeforeExecution(String sql, String tableName, int requestedLimit) {
        String limitedSql = ensureLimit(sql, requestedLimit);
        int maxRows = Math.max(1, Math.min(requestedLimit, 1000));
        long timeoutMs = getRuleThreshold("QUERY_TIMEOUT_MS", 5000L);
        long maxScanRows = getRuleThreshold("MAX_SCAN_ROWS", 50000L);
        String action = "ALLOW";
        List<String> notes = new ArrayList<>();
        notes.add("timeoutMs=" + timeoutMs);
        notes.add("maxRows=" + maxRows);
        notes.add("maxScanRows=" + maxScanRows);
        notes.add("limitInjected=" + !Objects.equals(sql, limitedSql));
        if (sql != null && sql.toLowerCase(Locale.ROOT).contains("select *")) {
            action = "WARN";
            notes.add("selectStar=true");
        }
        Long estimatedRows = estimateRows(limitedSql);
        if (estimatedRows != null) {
            notes.add("explainRows=" + estimatedRows);
            if (estimatedRows > maxScanRows && isRuleEnabled("MAX_SCAN_ROWS")) {
                action = "BLOCKED";
                notes.add("explainBlocked=true");
            }
        }
        return new QueryGuardResult(limitedSql, action, String.join(";", notes), (int) Math.max(1, timeoutMs / 1000), maxRows);
    }

    public QueryPermit acquireQueryPermit(String guardLabel) {
        long queueTimeoutMs = getRuleThreshold("QUERY_QUEUE_TIMEOUT_MS", 2000L);
        try {
            boolean acquired = querySemaphore.tryAcquire(Math.max(1, queueTimeoutMs), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IllegalStateException("查询队列繁忙，已触发并发熔断：" + guardLabel);
            }
            return new QueryPermit(querySemaphore);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查询队列等待被中断", e);
        }
    }

    private long number(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = Objects.toString(value, "0");
        return text.isBlank() ? 0L : Math.round(Double.parseDouble(text));
    }

    private void seedRules() {
        insertRule("ONLY_SELECT", "只允许 SELECT", "BLOCKED", "禁止非查询语句进入 BI 分析链路", null);
        insertRule("DANGEROUS_KEYWORD", "危险关键字拦截", "BLOCKED", "拦截 DROP/DELETE/UPDATE/INSERT/ALTER 等破坏性关键字", null);
        insertRule("MULTI_STATEMENT", "多语句拦截", "BLOCKED", "拦截分号拼接的多语句 SQL", null);
        insertRule("TABLE_SCOPE", "授权表范围校验", "BLOCKED", "校验 SQL 是否仅访问当前授权数据表", null);
        insertRule("LIMIT_REQUIRED", "结果集 LIMIT 检查", "WARN", "缺少 LIMIT 时标记为大结果集风险", null);
        insertRule("NO_SELECT_STAR", "禁止 SELECT *", "WARN", "使用 SELECT * 时提示限制字段范围", null);
        insertRule("SENSITIVE_FIELD", "敏感字段访问识别", "WARN", "识别 SQL 是否访问敏感字段", null);
        insertRule("SLOW_QUERY", "慢查询识别", "WARN", "执行耗时超过阈值时标记为慢查询", 3000L);
        insertRule("SLOW_QUERY_BREAKER", "慢查询熔断阈值", "BLOCKED", "执行耗时超过阈值时记录熔断风险，提示管理员优化 SQL", 8000L);
        insertRule("QUERY_TIMEOUT_MS", "查询超时熔断", "BLOCKED", "执行前设置查询超时，超时由数据库驱动中断", 5000L);
        insertRule("MAX_SCAN_ROWS", "最大扫描行数", "BLOCKED", "执行前扫描行数阈值，接入 EXPLAIN 后用于直接拦截", 50000L);
        insertRule("QUERY_QUEUE_TIMEOUT_MS", "查询队列等待超时", "BLOCKED", "并发查询队列等待超过阈值时直接熔断", 2000L);
        insertRule("SYSTEM_TABLE_BLOCK", "系统表访问拦截", "BLOCKED", "禁止普通查询访问系统库、元数据库、审计底表和用户底表", null);
    }

    private void seedSensitiveRules() {
        insertSensitiveRule("phone", "MOBILE");
        insertSensitiveRule("mobile", "MOBILE");
        insertSensitiveRule("idcard", "ID_CARD");
        insertSensitiveRule("手机号", "MOBILE");
        insertSensitiveRule("身份证", "ID_CARD");
        insertSensitiveRule("amount", "MIDDLE");
        insertSensitiveRule("金额", "MIDDLE");
    }

    private void insertRule(String code, String name, String riskLevel, String desc, Long threshold) {
        jdbcTemplate.update("""
                INSERT INTO is_sql_audit_rule(rule_code, rule_name, risk_level, enabled, rule_desc, threshold_value)
                VALUES (?, ?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), risk_level = VALUES(risk_level),
                                        rule_desc = VALUES(rule_desc), threshold_value = VALUES(threshold_value)
                """, code, name, riskLevel, desc, threshold);
    }

    private void insertSensitiveRule(String keyword, String maskType) {
        jdbcTemplate.update("""
                INSERT INTO is_sensitive_field_rule(field_keyword, mask_type, enabled)
                VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE mask_type = VALUES(mask_type)
                """, keyword, maskType);
    }

    private boolean isRuleEnabled(String ruleCode) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT enabled FROM is_sql_audit_rule WHERE rule_code = ?",
                Integer.class,
                ruleCode
        );
        return rows.isEmpty() || Objects.equals(rows.get(0), 1);
    }

    public long getRuleThreshold(String ruleCode, long defaultValue) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT threshold_value FROM is_sql_audit_rule WHERE rule_code = ? AND threshold_value IS NOT NULL",
                Long.class,
                ruleCode
        );
        return rows.isEmpty() ? defaultValue : rows.get(0);
    }

    private String maskValue(Object value) {
        return maskValue(value, "MIDDLE");
    }

    private String maskValue(Object value, String maskType) {
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value, "");
        String type = Objects.toString(maskType, "MIDDLE").toUpperCase(Locale.ROOT);
        if ("MOBILE".equals(type) && text.length() >= 7) {
            return text.substring(0, 3) + "****" + text.substring(text.length() - 4);
        }
        if ("ID_CARD".equals(type) && text.length() >= 8) {
            return text.substring(0, 4) + "********" + text.substring(text.length() - 4);
        }
        if ("EMAIL".equals(type) && text.contains("@")) {
            String[] parts = text.split("@", 2);
            String name = parts[0];
            String maskedName = name.length() <= 2 ? "*".repeat(Math.max(1, name.length())) : name.charAt(0) + "***";
            return maskedName + "@" + parts[1];
        }
        if (text.length() <= 2) {
            return "*".repeat(text.length());
        }
        if (text.length() <= 6) {
            return text.charAt(0) + "***" + text.charAt(text.length() - 1);
        }
        return text.substring(0, 3) + "****" + text.substring(text.length() - 2);
    }

    private String previewRawValue(Object value) {
        if (value == null) {
            return "null";
        }
        String text = Objects.toString(value, "");
        if (text.length() <= 8) {
            return text;
        }
        return text.substring(0, 4) + "...(" + text.length() + " chars)";
    }

    private Map<String, String> sensitiveColumnMaskTypes(String sql, Object... args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> field : jdbcTemplate.queryForList(sql, args)) {
            if (!fieldSensitiveByMetaOrRule(field)) {
                continue;
            }
            String column = Objects.toString(field.get("columnName"), "").trim();
            if (!column.isBlank()) {
                out.put(column, maskTypeForField(field));
            }
        }
        return out;
    }

    private boolean fieldSensitiveByMetaOrRule(Map<String, Object> field) {
        Object sensitive = field.get("sensitive");
        if (sensitive instanceof Number number && number.intValue() == 1) {
            return true;
        }
        if (sensitive instanceof Boolean flag && flag) {
            return true;
        }
        return !matchingSensitiveRules(field).isEmpty();
    }

    private String maskTypeForField(Map<String, Object> field) {
        List<Map<String, Object>> rules = matchingSensitiveRules(field);
        return rules.isEmpty() ? "MIDDLE" : Objects.toString(rules.get(0).getOrDefault("maskType", "MIDDLE"), "MIDDLE");
    }

    private List<Map<String, Object>> matchingSensitiveRules(Map<String, Object> field) {
        String haystack = (Objects.toString(field.get("columnName"), "") + " "
                + Objects.toString(field.get("displayName"), "") + " "
                + Objects.toString(field.get("commentText"), "") + " "
                + Objects.toString(field.get("synonyms"), "")).toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rules = jdbcTemplate.queryForList("""
                SELECT field_keyword AS fieldKeyword, mask_type AS maskType
                FROM is_sensitive_field_rule
                WHERE enabled = 1
                ORDER BY LENGTH(field_keyword) DESC
                """);
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String keyword = Objects.toString(rule.get("fieldKeyword"), "").trim().toLowerCase(Locale.ROOT);
            if (!keyword.isBlank() && haystack.contains(keyword)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private List<String> findSensitiveFields(String tableName, String normalizedSql) {
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> fields;
        if (tableName.startsWith("official:")) {
            String[] parts = tableName.split(":", 3);
            fields = parts.length == 3
                    ? jdbcTemplate.queryForList("""
                        SELECT column_name AS columnName,
                               COALESCE(NULLIF(business_name, ''), NULLIF(column_comment, ''), column_name) AS displayName,
                               column_comment AS commentText, synonyms, sensitive
                        FROM is_official_schema_field
                        WHERE datasource_id = ? AND table_name = ?
                        """, Long.parseLong(parts[1]), parts[2])
                    : List.of();
        } else {
            fields = jdbcTemplate.queryForList("""
                    SELECT column_name AS columnName, display_name AS displayName,
                           field_comment AS commentText, synonyms, sensitive
                    FROM is_data_field
                    WHERE table_name = ?
                    """, tableName);
        }
        
        if (fields.isEmpty()) {
            return List.of();
        }
        
        // 如果是 SELECT *，返回所有敏感字段
        if (normalizedSql.contains("select *") || normalizedSql.matches("(?s).*select\\s+\\*.*")) {
            List<String> allSensitive = new ArrayList<>();
            for (Map<String, Object> field : fields) {
                String columnName = Objects.toString(field.get("columnName"), "");
                String displayName = Objects.toString(field.get("displayName"), columnName);
                if (!columnName.isBlank() && fieldSensitiveByMetaOrRule(field)) {
                    allSensitive.add(displayName + "(" + columnName + ")");
                }
            }
            return allSensitive;
        }
        
        List<String> matched = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            String displayName = Objects.toString(field.get("displayName"), columnName);
            
            if (columnName.isBlank()) {
                continue;
            }
            if (!fieldSensitiveByMetaOrRule(field)) {
                continue;
            }
            
            String lowerColumnName = columnName.toLowerCase(Locale.ROOT);
            boolean found = false;
            
            // 检查方式1：带反引号的字段名 `columnName`
            if (normalizedSql.contains("`" + lowerColumnName + "`")) {
                found = true;
            }
            
            // 检查方式2：不带反引号的字段名（单词边界匹配）
            if (!found && normalizedSql.matches("(?s).*\\b" + java.util.regex.Pattern.quote(lowerColumnName) + "\\b.*")) {
                found = true;
            }
            
            // 检查方式3：在聚合函数中的字段，如 SUM(columnName), AVG(columnName) 等
            if (!found) {
                String[] aggFunctions = {"sum", "avg", "count", "max", "min"};
                for (String func : aggFunctions) {
                    if (normalizedSql.contains(func + "(" + lowerColumnName + ")") ||
                        normalizedSql.contains(func + "(`" + lowerColumnName + "`)")) {
                        found = true;
                        break;
                    }
                }
            }
            
            // 检查方式4：字段名作为别名或在中文字段名下
            if (!found && !displayName.equals(columnName)) {
                String lowerDisplayName = displayName.toLowerCase(Locale.ROOT);
                if (normalizedSql.contains(lowerDisplayName) || 
                    normalizedSql.matches("(?s).*\\b" + java.util.regex.Pattern.quote(lowerDisplayName) + "\\b.*")) {
                    found = true;
                }
            }
            
            if (found) {
                matched.add(displayName + "(" + columnName + ")");
            }
        }
        return matched;
    }

    public String ensureLimit(String sql, int limit) {
        String normalized = sql == null ? "" : sql.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(" limit ")) {
            return sql;
        }
        return sql.trim().replaceAll(";+$", "") + " LIMIT " + Math.max(1, Math.min(limit, 1000));
    }

    private String normalizeTableName(String tableName) {
        String normalized = tableName == null ? "" : tableName.trim();
        if (normalized.startsWith("official:")) {
            String[] parts = normalized.split(":", 3);
            normalized = parts.length == 3 ? parts[2] : normalized;
        }
        return normalized.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private boolean accessesSystemTable(String normalizedSql) {
        String padded = " " + Objects.toString(normalizedSql, "").replaceAll("\\s+", " ") + " ";
        String[] blocked = {
                " information_schema.", " mysql.", " performance_schema.", " sys.",
                " pg_catalog.", " pg_toast.", " pg_information_schema.",
                " is_user ", " is_sql_audit_log ", " is_sql_audit_rule ",
                " is_sensitive_field_rule ", " is_semantic_cache_audit "
        };
        for (String item : blocked) {
            if (padded.contains(item)) {
                return true;
            }
        }
        return padded.matches("(?s).*\\b(from|join)\\s+[`\"]?(information_schema|mysql|performance_schema|sys|pg_catalog|pg_toast)\\b.*")
                || padded.matches("(?s).*\\b(from|join)\\s+[`\"]?(is_user|is_sql_audit_log|is_sql_audit_rule|is_sensitive_field_rule|is_semantic_cache_audit)[`\"]?\\b.*");
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + definition);
        }
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String stringDetail(Map<String, Object> details, String key) {
        if (details == null || !details.containsKey(key) || details.get(key) == null) {
            return null;
        }
        return Objects.toString(details.get(key), "");
    }

    private boolean booleanDetail(Map<String, Object> details, String key) {
        Object value = details == null ? null : details.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private void recordSemanticCache(String question, String tableName, String sql, AuditResult auditResult,
                                     String executeStatus,
                                     Map<String, Object> details) {
        String cacheKey = stringDetail(details, "cacheKey");
        if (cacheKey == null || cacheKey.isBlank()) {
            return;
        }
        boolean cacheHit = booleanDetail(details, "cacheHit");
        boolean cacheable = "SUCCESS".equals(executeStatus) && !auditResult.blocked() && sql != null
                && sql.trim().toLowerCase(Locale.ROOT).startsWith("select");
        String redisStatus = cacheable && redisSet(cacheKey, sql) ? "UP" : Objects.toString(details.getOrDefault("redisStatus", "LOCAL"));
        jdbcTemplate.update("""
                INSERT INTO is_semantic_cache_audit(cache_key, question, table_name, cached_sql, hit_count,
                                                    risk_level, risk_reason, redis_status, last_hit_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END)
                ON DUPLICATE KEY UPDATE question = VALUES(question),
                                        table_name = VALUES(table_name),
                                        cached_sql = VALUES(cached_sql),
                                        hit_count = hit_count + VALUES(hit_count),
                                        risk_level = VALUES(risk_level),
                                        risk_reason = VALUES(risk_reason),
                                        redis_status = VALUES(redis_status),
                                        last_hit_at = CASE WHEN VALUES(hit_count) > 0 THEN NOW() ELSE last_hit_at END
                """,
                cacheKey,
                safeText(question, 1000),
                tableName,
                sql,
                cacheHit ? 1 : 0,
                auditResult.riskLevel(),
                safeText(auditResult.riskReason(), 1000),
                redisStatus,
                cacheHit
        );
    }

    private Long estimateRows(String sql) {
        try {
            String normalized = Objects.toString(sql, "").trim().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("select")) {
                return null;
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("EXPLAIN " + sql);
            long total = 0L;
            for (Map<String, Object> row : rows) {
                Object value = row.get("rows");
                if (value == null) {
                    value = row.get("ROWS");
                }
                if (value instanceof Number number) {
                    total += number.longValue();
                }
            }
            return total > 0 ? total : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean redisSet(String key, String value) {
        if (!redisEnabled || key == null || key.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        try {
            return redisCommand("SETEX", key, String.valueOf(Math.max(60, redisTtlSeconds)), value) != null;
        } catch (Exception e) {
            log.debug("Redis SETEX failed: {}", e.getMessage());
            return false;
        }
    }

    private String redisGet(String key) {
        if (!redisEnabled || key == null || key.isBlank()) {
            return null;
        }
        try {
            String response = redisCommand("GET", key);
            return response == null || response.isBlank() ? null : response;
        } catch (Exception e) {
            log.debug("Redis GET failed: {}", e.getMessage());
            return null;
        }
    }

    private String redisCommand(String... args) throws Exception {
        try (Socket socket = new Socket(redisHost, redisPort)) {
            socket.setSoTimeout(1500);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            if (redisPassword != null && !redisPassword.isBlank()) {
                writeRedisCommand(out, "AUTH", redisPassword);
                readRedisReply(in);
            }
            if (redisDatabase > 0) {
                writeRedisCommand(out, "SELECT", String.valueOf(redisDatabase));
                readRedisReply(in);
            }
            writeRedisCommand(out, args);
            return readRedisReply(in);
        }
    }

    private void writeRedisCommand(OutputStream out, String... args) throws Exception {
        StringBuilder command = new StringBuilder("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = Objects.toString(arg, "").getBytes(StandardCharsets.UTF_8);
            command.append("$").append(bytes.length).append("\r\n")
                    .append(Objects.toString(arg, "")).append("\r\n");
        }
        out.write(command.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String readRedisReply(InputStream in) throws Exception {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        String line = readRedisLine(in);
        if (type == '+') {
            return line;
        }
        if (type == '-') {
            throw new IllegalStateException(line);
        }
        if (type == ':') {
            return line;
        }
        if (type == '$') {
            int length = Integer.parseInt(line);
            if (length < 0) {
                return null;
            }
            byte[] body = in.readNBytes(length);
            in.readNBytes(2);
            return new String(body, StandardCharsets.UTF_8);
        }
        return line;
    }

    private String readRedisLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
            }
            out.write(current);
            previous = current;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    public record MaskReport(List<Map<String, Object>> rows, String detail) {
    }

    public record QueryGuardResult(String sql, String action, String detail, int timeoutSeconds, int maxRows) {
    }

    public record QueryPermit(Semaphore semaphore) implements AutoCloseable {
        @Override
        public void close() {
            semaphore.release();
        }
    }

    public record AuditResult(String riskLevel, String riskReason, boolean blocked,
                              List<String> matchedRules, List<String> sensitiveFields) {
        static AuditResult blocked(String reason, List<String> matchedRules, List<String> sensitiveFields) {
            return new AuditResult("BLOCKED", reason, true, matchedRules, sensitiveFields);
        }
    }
}
