package com.insightspark.service;

import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class SqlAuditService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                                             execute_status, duration_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                safeText(errorMessage, 1000)
        );
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
        if (rows == null || rows.isEmpty() || tableName == null || tableName.isBlank()) {
            return rows;
        }
        List<String> sensitiveColumns;
        if (tableName.startsWith("official:")) {
            String[] parts = tableName.split(":", 3);
            sensitiveColumns = parts.length == 3
                    ? jdbcTemplate.queryForList("""
                        SELECT column_name
                        FROM is_official_schema_field
                        WHERE datasource_id = ? AND table_name = ? AND sensitive = 1
                        """, String.class, Long.parseLong(parts[1]), parts[2])
                    : List.of();
        } else {
            sensitiveColumns = jdbcTemplate.queryForList("""
                    SELECT column_name
                    FROM is_data_field
                    WHERE table_name = ? AND sensitive = 1
                    """, String.class, tableName);
        }
        if (sensitiveColumns.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> masked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String column : sensitiveColumns) {
                if (copy.containsKey(column)) {
                    copy.put(column, maskValue(copy.get(column)));
                }
            }
            masked.add(copy);
        }
        return masked;
    }

    public List<Map<String, Object>> listLogs(String riskLevel, String executeStatus, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id AS userId, question, table_name AS tableName, engine,
                       generated_sql AS generatedSql, risk_level AS riskLevel,
                       risk_reason AS riskReason, matched_rules AS matchedRules,
                       sensitive_fields AS sensitiveFields, slow_query AS slowQuery,
                       execute_status AS executeStatus,
                       duration_ms AS durationMs, error_message AS errorMessage,
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
        sql.append(" ORDER BY created_at DESC LIMIT ").append(safeLimit);

        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listRules() {
        return jdbcTemplate.queryForList("""
                SELECT id, rule_code AS ruleCode, rule_name AS ruleName, risk_level AS riskLevel,
                       enabled, rule_desc AS ruleDesc, threshold_value AS thresholdValue,
                       updated_at AS updatedAt
                FROM is_sql_audit_rule
                ORDER BY id ASC
                """);
    }

    public Map<String, Object> stats() {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN risk_level = 'BLOCKED' THEN 1 ELSE 0 END) AS blockedCount,
                       SUM(CASE WHEN risk_level = 'WARN' THEN 1 ELSE 0 END) AS warnCount,
                       SUM(CASE WHEN sensitive_fields IS NOT NULL AND sensitive_fields <> '' THEN 1 ELSE 0 END) AS sensitiveCount,
                       SUM(CASE WHEN slow_query = 1 THEN 1 ELSE 0 END) AS slowCount,
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
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value, "");
        if (text.length() <= 2) {
            return "*".repeat(text.length());
        }
        if (text.length() <= 6) {
            return text.charAt(0) + "***" + text.charAt(text.length() - 1);
        }
        return text.substring(0, 3) + "****" + text.substring(text.length() - 2);
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
                               COALESCE(NULLIF(business_name, ''), NULLIF(column_comment, ''), column_name) AS displayName
                        FROM is_official_schema_field
                        WHERE datasource_id = ? AND table_name = ? AND `sensitive` = 1
                        """, Long.parseLong(parts[1]), parts[2])
                    : List.of();
        } else {
            fields = jdbcTemplate.queryForList("""
                    SELECT column_name AS columnName, display_name AS displayName
                    FROM is_data_field
                    WHERE table_name = ? AND `sensitive` = 1
                    """, tableName);
        }
        List<String> matched = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            String displayName = Objects.toString(field.get("displayName"), columnName);
            if (!columnName.isBlank()
                    && (normalizedSql.contains("`" + columnName.toLowerCase(Locale.ROOT) + "`")
                    || normalizedSql.matches("(?s).*\\b" + java.util.regex.Pattern.quote(columnName.toLowerCase(Locale.ROOT)) + "\\b.*"))) {
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

    public record AuditResult(String riskLevel, String riskReason, boolean blocked,
                              List<String> matchedRules, List<String> sensitiveFields) {
        static AuditResult blocked(String reason, List<String> matchedRules, List<String> sensitiveFields) {
            return new AuditResult("BLOCKED", reason, true, matchedRules, sensitiveFields);
        }
    }
}
