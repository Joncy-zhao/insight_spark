package com.insightspark.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ChatQueryHistoryService {

    private static final int MAX_QUERY_TEXT_LENGTH = 4000;
    private static final int MAX_AUDIT_INFO_LENGTH = 4000;
    private static final int MAX_MODEL_NAME_LENGTH = 50;
    private static final int MAX_CHART_TYPE_LENGTH = 50;
    private static final int ADMIN_CONTEXT_MAX_TURNS = 6;
    private static final int ROUTE_AUDIT_SAMPLE_LIMIT = 1000;
    private static final double ROUTE_AUDIT_LOW_CONFIDENCE_THRESHOLD = 0.75D;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatBiService chatBiService;

    @Autowired
    @Lazy
    private AdvancedAnalysisService advancedAnalysisService;

    @Autowired
    @Lazy
    private AiChartRuleConfigService aiChartRuleConfigService;

    @Value("${insight.chat-history.cleanup-enabled:true}")
    private boolean historyCleanupEnabled;

    @Value("${insight.chat-history.deleted-retention-days:30}")
    private int deletedRetentionDays;

    @Value("${insight.chat-history.cleanup-batch-size:200}")
    private int historyCleanupBatchSize;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initHistoryTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_chat_query_history` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL,
                  `data_source_id` BIGINT NOT NULL,
                  `query_table_name` VARCHAR(128) NULL,
                  `query_text` TEXT NOT NULL,
                  `generated_sql` TEXT NULL,
                  `reasoning_process` JSON NULL,
                  `llm_model_used` VARCHAR(50) DEFAULT 'unknown',
                  `chart_type` VARCHAR(50) NULL,
                  `chart_snapshot` JSON NULL,
                  `execution_status` TINYINT(1) DEFAULT 1,
                  `risk_level` VARCHAR(20) DEFAULT 'SAFE',
                  `audit_info` TEXT NULL,
                  `execution_time_ms` INT NULL,
                  `is_hit_cache` TINYINT(1) DEFAULT 0,
                  `is_deleted` TINYINT(1) DEFAULT 0,
                  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_chat_history_user_time` (`user_id`, `created_at`),
                  INDEX `idx_chat_history_risk_level` (`risk_level`),
                  INDEX `idx_chat_history_data_source` (`data_source_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话查询与全量历史审计表';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_chat_history_admin_audit` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `action_type` VARCHAR(32) NOT NULL,
                  `history_id` BIGINT NULL,
                  `related_history_id` BIGINT NULL,
                  `operator_user_id` VARCHAR(64) NULL,
                  `operator_role` VARCHAR(32) NULL,
                  `target_user_id` VARCHAR(64) NULL,
                  `action_reason` VARCHAR(255) NULL,
                  `payload_json` JSON NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_chat_history_admin_audit_history` (`history_id`),
                  INDEX `idx_chat_history_admin_audit_action` (`action_type`, `created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员对话历史治理审计表';
                """);
        addColumnIfMissing("is_chat_query_history", "query_table_name", "`query_table_name` VARCHAR(128) NULL");
        addColumnIfMissing("is_chat_query_history", "conversation_id", "`conversation_id` BIGINT NULL");
        addColumnIfMissing("is_chat_query_history", "parent_history_id", "`parent_history_id` BIGINT NULL");
        addColumnIfMissing("is_chat_query_history", "turn_no", "`turn_no` INT NULL");
        addColumnIfMissing("is_chat_query_history", "message_role", "`message_role` VARCHAR(16) NULL DEFAULT 'ASSISTANT'");
        addColumnIfMissing("is_chat_query_history", "intent_type", "`intent_type` VARCHAR(64) NULL");
        addColumnIfMissing("is_chat_query_history", "context_json", "`context_json` JSON NULL");
        addColumnIfMissing("is_chat_query_history", "scope_json", "`scope_json` JSON NULL");
        addColumnIfMissing("is_chat_query_history", "artifact_type", "`artifact_type` VARCHAR(32) NULL DEFAULT 'CHART'");
        addColumnIfMissing("is_chat_query_history", "summary_text", "`summary_text` TEXT NULL");
        addColumnIfMissing("is_chat_query_history", "deleted_at", "`deleted_at` DATETIME NULL");
        addColumnIfMissing("is_chat_query_history", "deleted_by", "`deleted_by` VARCHAR(64) NULL");
        addColumnIfMissing("is_chat_query_history", "delete_reason", "`delete_reason` VARCHAR(255) NULL");
        addIndexIfMissing("is_chat_query_history", "idx_chat_history_deleted_at",
                "CREATE INDEX `idx_chat_history_deleted_at` ON `is_chat_query_history` (`is_deleted`, `deleted_at`)");
    }

    /**
     * 写入成功查询历史，返回自增主键 id（供「钉入看板」关联 chart_id）。失败时返回 null。
     */
    public Long recordSuccess(String question, String tableName, Map<String, Object> result, Long executionTimeMs) {
        try {
            String userId = resolveUserId();
            if (userId == null) {
                return null;
            }
            String resolvedTableName = resolveTableName(tableName, result);
            String sql = """
                    INSERT INTO is_chat_query_history(
                        user_id, data_source_id, query_table_name, query_text, generated_sql,
                        reasoning_process, llm_model_used, chart_type, chart_snapshot,
                        execution_status, risk_level, audit_info, execution_time_ms, is_hit_cache, is_deleted
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """;
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                int i = 1;
                ps.setString(i++, userId);
                ps.setLong(i++, resolveDatasourceId(resolvedTableName));
                String qtn = nullIfBlank(resolvedTableName);
                if (qtn == null) {
                    ps.setNull(i++, Types.VARCHAR);
                } else {
                    ps.setString(i++, qtn);
                }
                ps.setString(i++, safeText(question, MAX_QUERY_TEXT_LENGTH));
                String genSql = Objects.toString(result.get("sql"), null);
                if (genSql == null || genSql.isBlank()) {
                    ps.setNull(i++, Types.LONGVARCHAR);
                } else {
                    ps.setString(i++, genSql);
                }
                ps.setString(i++, toJson(result.getOrDefault("reasoningReplaySteps",
                        result.getOrDefault("reasoningLogs", List.of()))));
                ps.setString(i++, safeText(resolveModelName(result), MAX_MODEL_NAME_LENGTH));
                ps.setString(i++, safeText(Objects.toString(result.getOrDefault("chartType", ""), ""), MAX_CHART_TYPE_LENGTH));
                ps.setString(i++, toJson(buildChartSnapshot(resolvedTableName, result)));
                ps.setInt(i++, 1);
                ps.setString(i++, normalizeRiskLevel(Objects.toString(result.getOrDefault("riskLevel", "SAFE"), "SAFE")));
                ps.setString(i++, safeText(Objects.toString(result.getOrDefault("riskReason", "查询成功"), "查询成功"), MAX_AUDIT_INFO_LENGTH));
                if (executionTimeMs == null) {
                    ps.setNull(i++, Types.INTEGER);
                } else {
                    ps.setInt(i++, executionTimeMs.intValue());
                }
                ps.setInt(i++, resolveCacheHit(result) ? 1 : 0);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 批量拉取对话图表快照（同一用户），用于看板一次加载多个图表。
     */
    public List<Map<String, Object>> batchChartSnapshotsForCurrentUser(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String userId = resolveUserId();
        if (userId == null) {
            return List.of();
        }
        List<Long> limited = ids.stream().filter(Objects::nonNull).distinct().limit(50).collect(Collectors.toList());
        if (limited.isEmpty()) {
            return List.of();
        }
        String in = limited.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(limited);
        String sql = """
                SELECT id, chart_type AS chartType, chart_snapshot AS chartSnapshot, generated_sql AS generatedSql,
                       query_table_name AS queryTableName, query_text AS queryText
                FROM is_chat_query_history
                WHERE id IN (""" + in + ") AND is_deleted = 0";
        if (!AuthContext.isAdmin()) {
            sql += " AND user_id = ?";
            args.add(userId);
        }
        return jdbcTemplate.queryForList(sql, args.toArray()).stream()
                .map(this::hydrateLatestInteractiveOptionTemplate)
                .toList();
    }

    /**
     * 分享看板免登录加载图表：仅返回已钉入该看板的 chart_id 对应快照。
     */
    public List<Map<String, Object>> batchChartSnapshotsForSharedDashboard(List<Long> ids, long dashboardId) {
        if (ids == null || ids.isEmpty() || dashboardId <= 0) {
            return List.of();
        }
        List<Long> pinned = jdbcTemplate.queryForList("""
                        SELECT chart_id
                        FROM is_dashboard_component
                        WHERE dashboard_id = ? AND chart_id > 0
                        """, Long.class, dashboardId)
                .stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (pinned.isEmpty()) {
            return List.of();
        }
        List<Long> limited = ids.stream()
                .filter(Objects::nonNull)
                .filter(pinned::contains)
                .distinct()
                .limit(50)
                .collect(Collectors.toList());
        if (limited.isEmpty()) {
            return List.of();
        }
        String in = limited.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(limited);
        String sql = """
                SELECT id, chart_type AS chartType, chart_snapshot AS chartSnapshot, generated_sql AS generatedSql,
                       query_table_name AS queryTableName, query_text AS queryText
                FROM is_chat_query_history
                WHERE id IN (""" + in + ") AND is_deleted = 0";
        return jdbcTemplate.queryForList(sql, args.toArray()).stream()
                .map(this::hydrateLatestInteractiveOptionTemplate)
                .toList();
    }

    private Map<String, Object> hydrateLatestInteractiveOptionTemplate(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        Map<String, Object> snapshot = parseJsonMap(row.get("chartSnapshot"));
        if (snapshot.isEmpty()) {
            return item;
        }
        String ruleCode = Objects.toString(snapshot.get("chartRuleCode"), "").trim();
        if (ruleCode.isBlank()) {
            return item;
        }
        Map<String, Object> latestTemplate = aiChartRuleConfigService.latestOptionTemplate(
                ruleCode,
                Objects.toString(row.getOrDefault("chartType", snapshot.getOrDefault("chartType", "bar")), "bar"));
        if (latestTemplate.isEmpty()) {
            return item;
        }
        snapshot.put("optionTemplate", latestTemplate);
        item.put("chartSnapshot", snapshot);
        return item;
    }

    public void assertHistoryChartOwnedByCurrentUser(long historyId) {
        String userId = resolveUserId();
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }
        if (AuthContext.isAdmin()) {
            return;
        }
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_chat_query_history
                WHERE id = ? AND user_id = ? AND is_deleted = 0 AND execution_status = 1
                """, Integer.class, historyId, userId);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("图表记录不存在或无权钉入");
        }
    }

    public void recordFailure(String question, String tableName, String errorMessage, Long executionTimeMs) {
        try {
            String userId = resolveUserId();
            if (userId == null) {
                return;
            }
            String resolvedTableName = nullIfBlank(tableName);
            String riskLevel = resolveRiskLevelFromError(errorMessage);
            jdbcTemplate.update("""
                    INSERT INTO is_chat_query_history(
                        user_id, data_source_id, query_table_name, query_text, generated_sql,
                        reasoning_process, llm_model_used, chart_type, chart_snapshot,
                        execution_status, risk_level, audit_info, execution_time_ms, is_hit_cache, is_deleted
                    ) VALUES (?, ?, ?, ?, NULL, ?, 'unknown', NULL, ?, ?, ?, ?, ?, 0, 0)
                    """,
                    userId,
                    resolveDatasourceId(resolvedTableName),
                    resolvedTableName,
                    safeText(question, MAX_QUERY_TEXT_LENGTH),
                    toJson(List.of()),
                    toJson(Map.of("tableName", Objects.toString(resolvedTableName, ""))),
                    0,
                    riskLevel,
                    safeText(Objects.toString(errorMessage, "执行失败"), MAX_AUDIT_INFO_LENGTH),
                    executionTimeMs == null ? null : executionTimeMs.intValue()
            );
        } catch (Exception ignored) {
            // 历史记录写入失败不应影响主流程。
        }
    }

    public void recordCancelled(String question, String tableName, String cancelReason, Long executionTimeMs) {
        try {
            String userId = resolveUserId();
            if (userId == null) {
                return;
            }
            String resolvedTableName = nullIfBlank(tableName);
            String auditInfo = Objects.toString(cancelReason, "").trim();
            if (auditInfo.isBlank()) {
                auditInfo = "用户手动停止生成";
            }
            jdbcTemplate.update("""
                    INSERT INTO is_chat_query_history(
                        user_id, data_source_id, query_table_name, query_text, generated_sql,
                        reasoning_process, llm_model_used, chart_type, chart_snapshot,
                        execution_status, risk_level, audit_info, execution_time_ms, is_hit_cache, is_deleted
                    ) VALUES (?, ?, ?, ?, NULL, ?, 'cancelled', NULL, ?, ?, ?, ?, ?, 0, 0)
                    """,
                    userId,
                    resolveDatasourceId(resolvedTableName),
                    resolvedTableName,
                    safeText(question, MAX_QUERY_TEXT_LENGTH),
                    toJson(List.of()),
                    toJson(Map.of("tableName", Objects.toString(resolvedTableName, ""))),
                    2,
                    "SAFE",
                    safeText("用户中止：" + auditInfo, MAX_AUDIT_INFO_LENGTH),
                    executionTimeMs == null ? null : executionTimeMs.intValue()
            );
        } catch (Exception ignored) {
            // 历史记录写入失败不应影响主流程。
        }
    }

    public Map<String, Object> listHistoryPage(int page, int pageSize, String keyword) {
        return listHistoryPage(page, pageSize, keyword, null, null, null, null, null, null, null);
    }

    public Map<String, Object> listHistoryPage(int page, int pageSize, String keyword,
                                               String tableName, String chartType, String riskLevel,
                                               String executionStatus, String dateFrom, String dateTo,
                                               String sortDirection) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 50));
        String userId = resolveUserId();
        if (userId == null) {
            return buildHistoryPageResponse(safePage, safePageSize, keyword, 0L, List.of());
        }
        backfillOrphanAdvancedAlertHistory(userId);
        repairAdvancedAlertHistoryStatus(userId);

        String text = normalizeKeyword(keyword);
        String table = normalizeKeyword(tableName);
        String chart = normalizeKeyword(chartType);
        String risk = normalizeHistoryRiskLevel(riskLevel);
        Integer execution = normalizeExecutionStatus(executionStatus);
        String startAt = normalizeHistoryDateBoundary(dateFrom, false);
        String endAt = normalizeHistoryDateBoundary(dateTo, true);
        String orderBy = normalizeSortDirection(sortDirection);
        List<Object> whereArgs = new ArrayList<>();
        StringBuilder whereSql = buildHistoryWhereSql(userId, text, table, chart, risk, execution, startAt, endAt, whereArgs);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + whereSql, Long.class, whereArgs.toArray());
        long safeTotal = total == null ? 0L : total;
        int offset = (safePage - 1) * safePageSize;

        List<Object> queryArgs = new ArrayList<>(whereArgs);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        String querySql = """
                SELECT id, user_id AS userId, data_source_id AS dataSourceId, query_table_name AS queryTableName,
                       query_text AS queryText, generated_sql AS generatedSql, llm_model_used AS llmModelUsed,
                       chart_type AS chartType, chart_snapshot AS chartSnapshot, reasoning_process AS reasoningProcess,
                       execution_status AS executionStatus,
                       risk_level AS riskLevel, audit_info AS auditInfo, execution_time_ms AS executionTimeMs,
                       is_hit_cache AS isHitCache, conversation_id AS conversationId,
                       parent_history_id AS parentHistoryId, turn_no AS turnNo,
                       message_role AS messageRole, intent_type AS intentType,
                       artifact_type AS artifactType, summary_text AS summaryText,
                       context_json AS contextJson, scope_json AS scopeJson,
                       created_at AS createdAt
                 """ + whereSql + " ORDER BY created_at " + orderBy + " LIMIT ? OFFSET ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(querySql, queryArgs.toArray());

        List<Map<String, Object>> items = rows.stream().map(this::mapHistoryRow).toList();
        return buildHistoryPageResponse(safePage, safePageSize, text, safeTotal, items);
    }

    public List<Map<String, Object>> listHistory(int limit, String keyword) {
        Map<String, Object> page = listHistoryPage(1, limit, keyword);
        Object items = page.get("items");
        if (items instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((key, value) -> row.put(String.valueOf(key), value));
                    result.add(row);
                }
            }
            return result;
        }
        return List.of();
    }

    public void deleteHistory(Long historyId) {
        String userId = AuthContext.userId();
        jdbcTemplate.update("""
                UPDATE is_chat_query_history
                SET is_deleted = 1,
                    deleted_at = CURRENT_TIMESTAMP,
                    deleted_by = ?,
                    delete_reason = 'USER_DELETE'
                WHERE id = ? AND user_id = ? AND is_deleted = 0
                """, userId, historyId, userId);
    }

    public Map<String, Object> listAdminHistoryPage(int page, int pageSize, String keyword,
                                                    String userId, String tableName, String sourceType,
                                                    String chartType, String riskLevel, String executionStatus,
                                                    String modelType, String dateFrom, String dateTo,
                                                    Boolean cacheHit, Boolean slowQuery, String sortDirection) {
        backfillOrphanAdvancedAlertHistory();
        repairAdvancedAlertHistoryStatus("");
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        String text = normalizeKeyword(keyword);
        String normalizedUserId = normalizeKeyword(userId);
        String table = normalizeKeyword(tableName);
        String source = normalizeSourceType(sourceType);
        String chart = normalizeKeyword(chartType);
        String risk = normalizeHistoryRiskLevel(riskLevel);
        Integer execution = normalizeExecutionStatus(executionStatus);
        String model = normalizeKeyword(modelType);
        String startAt = normalizeHistoryDateBoundary(dateFrom, false);
        String endAt = normalizeHistoryDateBoundary(dateTo, true);
        String orderBy = normalizeSortDirection(sortDirection);
        List<Object> whereArgs = new ArrayList<>();
        StringBuilder whereSql = buildAdminHistoryWhereSql(text, normalizedUserId, table, source, chart, risk,
                execution, model, startAt, endAt, cacheHit, slowQuery, whereArgs);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + whereSql, Long.class, whereArgs.toArray());
        long safeTotal = total == null ? 0L : total;
        int offset = (safePage - 1) * safePageSize;

        List<Object> queryArgs = new ArrayList<>(whereArgs);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        String querySql = """
                SELECT h.id, h.user_id AS userId, h.data_source_id AS dataSourceId,
                       h.query_table_name AS queryTableName, h.query_text AS queryText,
                       h.generated_sql AS generatedSql, h.llm_model_used AS llmModelUsed,
                       h.chart_type AS chartType, h.chart_snapshot AS chartSnapshot,
                       h.reasoning_process AS reasoningProcess, h.execution_status AS executionStatus,
                       h.risk_level AS riskLevel, h.audit_info AS auditInfo,
                       h.execution_time_ms AS executionTimeMs, h.is_hit_cache AS isHitCache,
                       h.conversation_id AS conversationId, h.parent_history_id AS parentHistoryId,
                       h.turn_no AS turnNo, h.message_role AS messageRole, h.intent_type AS intentType,
                       h.artifact_type AS artifactType, h.summary_text AS summaryText,
                       h.context_json AS contextJson, h.scope_json AS scopeJson, h.created_at AS createdAt,
                       u.username AS username, u.nickname AS nickname,
                       EXISTS (
                           SELECT 1 FROM is_sql_audit_log a
                           WHERE a.user_id = h.user_id
                             AND a.question = h.query_text
                             AND COALESCE(a.table_name, '') = COALESCE(h.query_table_name, '')
                             AND a.created_at BETWEEN DATE_SUB(h.created_at, INTERVAL 30 SECOND)
                                                 AND DATE_ADD(h.created_at, INTERVAL 30 SECOND)
                             AND a.slow_query = 1
                           LIMIT 1
                       ) AS slowQuery
                """ + whereSql + " ORDER BY h.created_at " + orderBy + " LIMIT ? OFFSET ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(querySql, queryArgs.toArray());
        List<Map<String, Object>> items = rows.stream().map(this::mapAdminHistoryRow).toList();

        Map<String, Object> response = buildHistoryPageResponse(safePage, safePageSize, text, safeTotal, items);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("userId", normalizedUserId);
        filters.put("tableName", table);
        filters.put("sourceType", source);
        filters.put("chartType", chart);
        filters.put("riskLevel", risk);
        filters.put("executionStatus", executionStatus == null ? "" : executionStatus);
        filters.put("modelType", model);
        filters.put("dateFrom", startAt == null ? "" : startAt);
        filters.put("dateTo", endAt == null ? "" : endAt);
        filters.put("cacheHit", cacheHit);
        filters.put("slowQuery", slowQuery);
        filters.put("sortDirection", orderBy);
        response.put("filters", filters);
        response.put("summary", adminHistorySummary(text, normalizedUserId, table, source, chart, risk, execution,
                model, startAt, endAt));
        response.put("governance", buildAdminHistoryGovernance());
        return response;
    }

    public Map<String, Object> getAdminHistoryDetail(Long historyId) {
        Map<String, Object> row = findAdminHistoryRow(historyId);
        if (row == null) {
            throw new IllegalArgumentException("历史记录不存在");
        }
        Map<String, Object> item = mapAdminHistoryRow(row);
        List<Map<String, Object>> auditLogs = loadRelatedAuditLogs(item);
        item.put("auditLogs", auditLogs);
        item.put("auditSummary", buildAuditSummary(auditLogs));
        item.put("latestAuditLog", auditLogs.isEmpty() ? Map.of() : new LinkedHashMap<>(auditLogs.get(0)));
        item.put("cacheContext", buildCacheContext(auditLogs.isEmpty() ? Map.of() : auditLogs.get(0)));
        item.put("permissionCheck", buildPermissionCheck(item, auditLogs));
        item.put("operator", buildUserSummary(row));
        item.put("rerunSupported", Boolean.TRUE.equals(canRerun(item)));
        item.put("deletable", true);
        return item;
    }

    private void backfillOrphanAdvancedAlertHistory() {
        backfillOrphanAdvancedAlertHistory("");
    }

    private void backfillOrphanAdvancedAlertHistory(String targetUserId) {
        if (!tableExists("is_chat_conversation_artifact")
                || !tableExists("is_chat_conversation")
                || !tableExists("is_chat_conversation_turn")) {
            return;
        }
        try {
            String userFilter = Objects.toString(targetUserId, "").trim();
            List<Object> args = new ArrayList<>();
            String sql = """
                    SELECT a.id AS artifactId,
                           a.conversation_id AS conversationId,
                           a.turn_id AS turnId,
                           a.artifact_type AS artifactType,
                           a.artifact_json AS artifactJson,
                           a.chart_type AS chartType,
                           a.risk_level AS riskLevel,
                           a.created_at AS createdAt,
                           t.turn_no AS turnNo,
                           c.user_id AS userId
                      FROM is_chat_conversation_artifact a
                      JOIN is_chat_conversation c ON c.id = a.conversation_id
                      LEFT JOIN is_chat_conversation_turn t ON t.id = a.turn_id
                     WHERE (a.history_id IS NULL OR a.history_id = 0)
                        AND a.artifact_type = 'ADVANCED_ALERT'
                    """;
            if (!userFilter.isBlank()) {
                sql += " AND c.user_id = ?";
                args.add(userFilter);
            }
            sql += " ORDER BY a.id DESC LIMIT 50";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
            for (Map<String, Object> row : rows) {
                backfillOneAdvancedAlertHistory(row);
            }
        } catch (Exception ignored) {
            // 补录是兼容增强，失败不能影响管理员历史列表查询。
        }
    }

    private void backfillOneAdvancedAlertHistory(Map<String, Object> row) {
        Long artifactId = toLong(row.get("artifactId"));
        if (artifactId == null || artifactId <= 0) {
            return;
        }
        Map<String, Object> artifact = parseJsonMap(row.get("artifactJson"));
        String userId = Objects.toString(row.get("userId"), "").trim();
        if (userId.isBlank()) {
            return;
        }
        String tableName = firstNonBlank(artifact.get("tableName"), nestedValue(artifact, "params", "tableName"));
        String question = firstNonBlank(artifact.get("sourceQuestion"), artifact.get("question"), artifact.get("title"), artifact.get("summary"));
        String summaryText = firstNonBlank(artifact.get("summary"), artifact.get("message"), artifact.get("title"), "智能预警记录已生成");
        Map<String, Object> snapshot = new LinkedHashMap<>(artifact);
        snapshot.putIfAbsent("advancedAnalysisType", "alert");
        snapshot.putIfAbsent("type", "alert");
        snapshot.putIfAbsent("chartType", "alert");
        snapshot.putIfAbsent("message", summaryText);
        snapshot.putIfAbsent("tableName", tableName);
        snapshot.putIfAbsent("riskLevel", "WARN");
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO is_chat_query_history(
                            user_id, data_source_id, query_table_name, query_text, generated_sql,
                            reasoning_process, llm_model_used, chart_type, chart_snapshot,
                            execution_status, risk_level, audit_info, execution_time_ms, is_hit_cache, is_deleted,
                            conversation_id, parent_history_id, turn_no, message_role, intent_type,
                            artifact_type, summary_text
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                int i = 1;
                ps.setString(i++, safeText(userId, 64));
                ps.setLong(i++, resolveDatasourceId(tableName));
                if (tableName.isBlank()) {
                    ps.setNull(i++, Types.VARCHAR);
                } else {
                    ps.setString(i++, safeText(tableName, 255));
                }
                ps.setString(i++, safeText(question, MAX_QUERY_TEXT_LENGTH));
                ps.setNull(i++, Types.LONGVARCHAR);
                ps.setString(i++, toJson(firstPresent(artifact.get("thinkingLogs"), List.of())));
                ps.setString(i++, "advanced-analysis");
                ps.setString(i++, "alert");
                ps.setString(i++, toJson(snapshot));
                ps.setInt(i++, 1);
                ps.setString(i++, "WARN");
                ps.setString(i++, safeText(summaryText, MAX_AUDIT_INFO_LENGTH));
                ps.setNull(i++, Types.INTEGER);
                ps.setInt(i++, 0);
                ps.setLong(i++, toLong(row.get("conversationId")));
                ps.setNull(i++, Types.BIGINT);
                Long turnNo = toLong(row.get("turnNo"));
                if (turnNo == null) {
                    ps.setNull(i++, Types.INTEGER);
                } else {
                    ps.setInt(i++, turnNo.intValue());
                }
                ps.setString(i++, "ASSISTANT");
                ps.setString(i++, "ADVANCED_ALERT");
                ps.setString(i++, "ADVANCED_ALERT");
                ps.setString(i++, safeText(summaryText, MAX_AUDIT_INFO_LENGTH));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                jdbcTemplate.update("""
                        UPDATE is_chat_conversation_artifact
                           SET history_id = ?
                         WHERE id = ? AND (history_id IS NULL OR history_id = 0)
                        """, key.longValue(), artifactId);
            }
        } catch (Exception ignored) {
            // 单条补录失败跳过，继续处理其他记录。
        }
    }

    private void repairAdvancedAlertHistoryStatus(String targetUserId) {
        try {
            String userFilter = Objects.toString(targetUserId, "").trim();
            List<Object> args = new ArrayList<>();
            String sql = """
                    UPDATE is_chat_query_history
                       SET execution_status = 1,
                           execution_time_ms = NULL,
                           is_hit_cache = 0,
                           generated_sql = NULL,
                           llm_model_used = CASE
                               WHEN llm_model_used IS NULL OR llm_model_used = '' OR llm_model_used = 'unknown'
                               THEN 'advanced-analysis'
                               ELSE llm_model_used
                           END
                     WHERE is_deleted = 0
                       AND execution_status = 0
                       AND (
                           chart_type = 'alert'
                           OR artifact_type = 'ADVANCED_ALERT'
                           OR intent_type = 'ADVANCED_ALERT'
                           OR JSON_UNQUOTE(JSON_EXTRACT(chart_snapshot, '$.advancedAnalysisType')) = 'alert'
                           OR JSON_UNQUOTE(JSON_EXTRACT(chart_snapshot, '$.type')) = 'alert'
                       )
                    """;
            if (!userFilter.isBlank()) {
                sql += " AND user_id = ?";
                args.add(userFilter);
            }
            jdbcTemplate.update(sql, args.toArray());
        } catch (Exception ignored) {
            // 兼容修复失败不能影响历史列表正常读取。
        }
    }

    public Map<String, Object> getAdminHistoryContext(Long historyId) {
        Map<String, Object> row = findAdminHistoryRow(historyId);
        if (row == null) {
            throw new IllegalArgumentException("历史记录不存在");
        }
        Map<String, Object> current = mapAdminHistoryRow(row);
        Map<String, Object> artifactLink = loadArtifactLinkByHistoryId(historyId);
        long conversationId = toLong(current.get("conversationId"));
        if (conversationId <= 0) {
            conversationId = toLong(artifactLink.get("conversationId"));
        }
        Map<String, Object> currentContext = toObjectMap(current.get("context"));
        long currentTurnId = firstPositive(
                toLong(currentContext.get("assistantTurnId")),
                toLong(current.get("assistantTurnId")),
                toLong(artifactLink.get("turnId"))
        );
        long currentUserTurnId = firstPositive(
                toLong(currentContext.get("userTurnId")),
                toLong(current.get("userTurnId")),
                toLong(artifactLink.get("userTurnId"))
        );
        long currentTurnNo = firstPositive(toLong(current.get("turnNo")), toLong(artifactLink.get("turnNo")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("currentHistoryId", historyId);
        response.put("currentTurnId", currentTurnId > 0 ? currentTurnId : null);
        response.put("currentUserTurnId", currentUserTurnId > 0 ? currentUserTurnId : null);
        response.put("currentTurnNo", currentTurnNo > 0 ? currentTurnNo : null);
        response.put("fallbackMode", true);
        response.put("fallbackReason", "该历史记录暂无可关联的会话上下文");
        response.put("conversation", Map.of());
        response.put("turns", buildSingleHistoryContextTurns(current));
        response.put("totalTurns", 1);
        response.put("visibleTurns", 1);
        response.put("contextLimit", ADMIN_CONTEXT_MAX_TURNS);
        response.put("hasEarlierContext", false);
        response.put("hasLaterTurns", false);

        if (conversationId <= 0 || !tableExists("is_chat_conversation_turn")) {
            return response;
        }

        Map<String, Object> conversation = loadAdminConversation(conversationId);
        List<Map<String, Object>> turns = loadAdminConversationTurns(conversationId);
        if (turns.isEmpty()) {
            return response;
        }
        int currentTurnIndex = resolveAdminCurrentTurnIndex(turns, currentTurnId, currentTurnNo);
        if (currentTurnIndex < 0) {
            return response;
        }
        int contextEndExclusive = currentTurnIndex + 1;
        int contextStartIndex = Math.max(0, contextEndExclusive - ADMIN_CONTEXT_MAX_TURNS);
        List<Map<String, Object>> contextTurns = new ArrayList<>(turns.subList(contextStartIndex, contextEndExclusive));
        Map<Long, List<Map<String, Object>>> artifactsByTurn = loadAdminArtifactsByTurn(
                contextTurns.stream()
                        .map(turn -> toLong(turn.get("id")))
                        .filter(id -> id > 0)
                        .toList()
        );
        List<Map<String, Object>> hydratedTurns = new ArrayList<>();
        for (Map<String, Object> turn : contextTurns) {
            Map<String, Object> item = new LinkedHashMap<>(turn);
            long turnId = toLong(turn.get("id"));
            long turnNo = toLong(turn.get("turnNo"));
            List<Map<String, Object>> artifacts = artifactsByTurn.getOrDefault(turnId, List.of());
            artifacts = hydrateCurrentArtifactChartSnapshot(artifacts, historyId, current);
            boolean artifactMatchesCurrent = artifacts.stream()
                    .anyMatch(artifact -> toLong(artifact.get("historyId")) == toLong(historyId));
            boolean turnMatchesCurrent = currentTurnId > 0
                    ? turnId == currentTurnId
                    : currentTurnNo > 0 && turnNo == currentTurnNo;
            boolean promptMatchesCurrent = currentUserTurnId > 0
                    ? turnId == currentUserTurnId
                    : currentTurnNo > 1 && turnNo == currentTurnNo - 1
                    && "USER".equalsIgnoreCase(Objects.toString(turn.get("role"), ""));
            item.put("context", parseJsonMap(turn.get("contextJson")));
            item.put("roleLabel", conversationRoleLabel(turn.get("role")));
            item.put("artifacts", artifacts);
            item.put("isCurrent", artifactMatchesCurrent || turnMatchesCurrent);
            item.put("isCurrentPrompt", promptMatchesCurrent);
            hydratedTurns.add(item);
        }

        response.put("conversation", conversation);
        response.put("turns", hydratedTurns);
        response.put("totalTurns", turns.size());
        response.put("visibleTurns", hydratedTurns.size());
        response.put("contextLimit", ADMIN_CONTEXT_MAX_TURNS);
        response.put("hasEarlierContext", contextStartIndex > 0);
        response.put("hasLaterTurns", currentTurnIndex < turns.size() - 1);
        response.put("fallbackMode", false);
        response.put("fallbackReason", "");
        return response;
    }

    public Map<String, Object> adminHistoryAnalytics(String keyword, String userId, String tableName, String sourceType,
                                                     String chartType, String riskLevel, String executionStatus,
                                                     String modelType, String dateFrom, String dateTo,
                                                     Boolean cacheHit, Boolean slowQuery) {
        String text = normalizeKeyword(keyword);
        String normalizedUserId = normalizeKeyword(userId);
        String table = normalizeKeyword(tableName);
        String source = normalizeSourceType(sourceType);
        String chart = normalizeKeyword(chartType);
        String risk = normalizeHistoryRiskLevel(riskLevel);
        Integer execution = normalizeExecutionStatus(executionStatus);
        String model = normalizeKeyword(modelType);
        String startAt = normalizeHistoryDateBoundary(dateFrom, false);
        String endAt = normalizeHistoryDateBoundary(dateTo, true);
        List<Object> whereArgs = new ArrayList<>();
        StringBuilder whereSql = buildAdminHistoryWhereSql(text, normalizedUserId, table, source, chart, risk,
                execution, model, startAt, endAt, cacheHit, slowQuery, whereArgs);

        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("queryVolume", queryAdminTrendRows(whereSql, whereArgs, """
                SELECT DATE_FORMAT(h.created_at, '%Y-%m-%d') AS day,
                       COUNT(*) AS totalCount
                """, ""));
        trends.put("riskVolume", queryAdminTrendRows(whereSql, whereArgs, """
                SELECT DATE_FORMAT(h.created_at, '%Y-%m-%d') AS day,
                       SUM(CASE WHEN h.risk_level = 'BLOCKED' THEN 1 ELSE 0 END) AS blockedCount,
                       SUM(CASE WHEN h.risk_level = 'WARN' THEN 1 ELSE 0 END) AS warnCount,
                       SUM(CASE WHEN h.risk_level IN ('BLOCKED', 'WARN') THEN 1 ELSE 0 END) AS riskCount
                """, ""));
        trends.put("cacheVolume", queryAdminTrendRows(whereSql, whereArgs, """
                SELECT DATE_FORMAT(h.created_at, '%Y-%m-%d') AS day,
                       SUM(CASE WHEN h.is_hit_cache = 1 THEN 1 ELSE 0 END) AS hitCount,
                       SUM(CASE WHEN h.is_hit_cache = 0 THEN 1 ELSE 0 END) AS missCount,
                       ROUND(
                           SUM(CASE WHEN h.is_hit_cache = 1 THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0),
                           1
                       ) AS hitRate
                """, ""));
        trends.put("slowVolume", queryAdminTrendRows(whereSql, whereArgs, """
                SELECT DATE_FORMAT(h.created_at, '%Y-%m-%d') AS day,
                       COUNT(*) AS slowCount
                """, " AND " + slowQueryExistsClause("h")));

        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("summary", buildAdminPerformanceSummary(whereSql, whereArgs));
        performance.put("topSlowQueries", queryAdminSlowDetails(whereSql, whereArgs, 8));
        performance.put("slowQueryGroups", queryAdminSlowGroups(whereSql, whereArgs, 8));
        performance.put("cacheMissGroups", queryAdminCacheMissGroups(whereSql, whereArgs, 8));
        response.put("trends", trends);
        response.put("performance", performance);
        response.put("routeAudit", buildAdminRouteAuditAnalytics(whereSql, whereArgs));
        return response;
    }

    public Map<String, Object> rerunHistoryAsAdmin(Long historyId) {
        Map<String, Object> detail = getAdminHistoryDetail(historyId);
        if (isAdvancedAnalysisSnapshot(toObjectMap(detail.get("chartSnapshot")))) {
            return rerunAdvancedAnalysisHistoryAsAdmin(historyId, detail);
        }
        String question = Objects.toString(detail.get("question"), "").trim();
        String tableName = Objects.toString(detail.get("queryTableName"), "").trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException("该记录缺少原始问题，无法复跑");
        }
        ChatBiService.ChatQueryRequest request = new ChatBiService.ChatQueryRequest();
        request.setQuestion(question);
        request.setTableNames(tableName.isBlank() ? List.of() : List.of(tableName));
        Map<String, Object> filters = new LinkedHashMap<>();
        if (!tableName.isBlank()) {
            filters.put("tableName", tableName);
        }
        filters.put("rerunByAdmin", true);
        filters.put("originHistoryId", historyId);
        filters.put("originUserId", detail.get("userId"));
        filters.put("originConversationId", detail.get("conversationId"));
        filters.put("originTurnNo", detail.get("turnNo"));
        filters.put("originRiskLevel", detail.get("riskLevel"));
        filters.put("originModelCategory", detail.get("modelCategory"));
        filters.put("originIntentType", detail.get("intentType"));
        filters.put("originArtifactType", detail.get("artifactType"));
        filters.put("originSourceType", detail.get("sourceType"));
        filters.put("originScope", detail.getOrDefault("scope", Map.of()));
        filters.put("originContext", detail.getOrDefault("context", Map.of()));
        request.setFilters(filters);
        request.setMode("ADMIN_RERUN");
        Map<String, Object> result = chatBiService.executeChat(request);
        Long newHistoryId = recordSuccess(question, tableName, result, null);
        if (newHistoryId != null) {
            result.put("queryHistoryId", newHistoryId);
            attachAdminRerunLink(newHistoryId, historyId, detail, result);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("originHistoryId", historyId);
        response.put("newHistoryId", newHistoryId);
        response.put("question", question);
        response.put("tableName", tableName);
        response.put("engine", result.get("engine"));
        response.put("chartType", result.get("chartType"));
        response.put("riskLevel", result.get("riskLevel"));
        response.put("cacheHit", result.get("cacheHit"));
        response.put("message", Objects.toString(result.getOrDefault("message", "复跑完成"), "复跑完成"));
        response.put("result", result);
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("originHistoryId", historyId);
        auditPayload.put("newHistoryId", newHistoryId);
        auditPayload.put("question", question);
        auditPayload.put("tableName", tableName);
        auditPayload.put("engine", result.get("engine"));
        auditPayload.put("chartType", result.get("chartType"));
        auditPayload.put("riskLevel", result.get("riskLevel"));
        auditPayload.put("cacheHit", result.get("cacheHit"));
        recordHistoryAdminAudit("ADMIN_RERUN", historyId, newHistoryId,
                Objects.toString(detail.get("userId"), ""), "管理员复跑历史查询", auditPayload);
        return response;
    }

    private Map<String, Object> rerunAdvancedAnalysisHistoryAsAdmin(Long historyId, Map<String, Object> detail) {
        Map<String, Object> snapshot = toObjectMap(detail.get("chartSnapshot"));
        long planId = toLong(snapshot.get("advancedAnalysisPlanId"));
        if (planId <= 0) {
            Map<String, Object> action = toObjectMap(snapshot.get("advancedAnalysisAction"));
            planId = toLong(action.get("planId"));
        }
        if (planId <= 0) {
            if (isForecastSnapshot(snapshot)) {
                return rerunUnsavedForecastHistoryAsAdmin(historyId, detail);
            }
            throw new IllegalArgumentException("该推演/预警记录缺少高级分析方案 ID，无法按同类流程复跑");
        }
        Map<String, Object> rerunResult = advancedAnalysisService.recalculatePlanForAdminHistory(planId, detail);
        Long newHistoryId = toLong(rerunResult.get("newHistoryId"));
        if (newHistoryId != null && newHistoryId > 0) {
            attachAdminRerunLink(newHistoryId, historyId, detail, toObjectMap(rerunResult.get("result")));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("originHistoryId", historyId);
        response.put("newHistoryId", newHistoryId);
        response.put("question", detail.get("question"));
        response.put("tableName", detail.get("queryTableName"));
        response.put("engine", rerunResult.get("engine"));
        response.put("chartType", rerunResult.get("chartType"));
        response.put("riskLevel", rerunResult.getOrDefault("riskLevel", "SAFE"));
        response.put("cacheHit", rerunResult.get("cacheHit"));
        response.put("message", Objects.toString(rerunResult.getOrDefault("message", "高级分析复跑完成"), "高级分析复跑完成"));
        response.put("analysisType", rerunResult.get("analysisType"));
        response.put("planId", planId);
        response.put("result", rerunResult.getOrDefault("result", Map.of()));
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("originHistoryId", historyId);
        auditPayload.put("newHistoryId", newHistoryId);
        auditPayload.put("planId", planId);
        auditPayload.put("analysisType", rerunResult.get("analysisType"));
        auditPayload.put("question", detail.get("question"));
        auditPayload.put("tableName", detail.get("queryTableName"));
        recordHistoryAdminAudit("ADMIN_RERUN", historyId, newHistoryId,
                Objects.toString(detail.get("userId"), ""), "管理员复跑预测与情景模拟历史", auditPayload);
        return response;
    }

    private Map<String, Object> rerunUnsavedForecastHistoryAsAdmin(Long historyId, Map<String, Object> detail) {
        Map<String, Object> rerunResult = advancedAnalysisService.recalculateForecastSnapshotForAdminHistory(detail);
        Long newHistoryId = toLong(rerunResult.get("newHistoryId"));
        if (newHistoryId != null && newHistoryId > 0) {
            attachAdminRerunLink(newHistoryId, historyId, detail, toObjectMap(rerunResult.get("result")));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("originHistoryId", historyId);
        response.put("newHistoryId", newHistoryId);
        response.put("question", detail.get("question"));
        response.put("tableName", detail.get("queryTableName"));
        response.put("engine", rerunResult.get("engine"));
        response.put("chartType", rerunResult.get("chartType"));
        response.put("riskLevel", rerunResult.getOrDefault("riskLevel", "SAFE"));
        response.put("cacheHit", rerunResult.get("cacheHit"));
        response.put("message", Objects.toString(rerunResult.getOrDefault("message", "预测记录复跑完成"), "预测记录复跑完成"));
        response.put("analysisType", "forecast");
        response.put("result", rerunResult.getOrDefault("result", Map.of()));
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("originHistoryId", historyId);
        auditPayload.put("newHistoryId", newHistoryId);
        auditPayload.put("analysisType", "forecast");
        auditPayload.put("question", detail.get("question"));
        auditPayload.put("tableName", detail.get("queryTableName"));
        recordHistoryAdminAudit("ADMIN_RERUN", historyId, newHistoryId,
                Objects.toString(detail.get("userId"), ""), "管理员复跑未保存预测历史", auditPayload);
        return response;
    }

    public Map<String, Object> deleteAdminHistoryBatch(List<Long> ids) {
        return deleteAdminHistoryBatch(ids, null);
    }

    public Map<String, Object> deleteAdminHistoryBatch(List<Long> ids, String reason) {
        List<Long> safeIds = normalizeHistoryIds(ids);
        if (safeIds.isEmpty()) {
            return Map.of("deletedCount", 0, "ids", List.of());
        }
        List<Map<String, Object>> targetRows = loadHistoryRowsByIds(safeIds, true);
        if (targetRows.isEmpty()) {
            return Map.of("deletedCount", 0, "ids", List.of());
        }
        List<Long> actualIds = targetRows.stream()
                .map(row -> toLong(row.get("id")))
                .filter(id -> id > 0)
                .toList();
        String deleteReason = normalizeDeleteReason(reason, "管理员删除历史记录");
        String operatorUserId = resolveUserIdOrDefault("ADMIN");
        String inSql = actualIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(operatorUserId);
        args.add(deleteReason);
        args.addAll(actualIds);
        int updated = jdbcTemplate.update("""
                UPDATE is_chat_query_history
                   SET is_deleted = 1,
                       deleted_at = CURRENT_TIMESTAMP,
                       deleted_by = ?,
                       delete_reason = ?
                 WHERE id IN (""" + inSql + ") AND is_deleted = 0", args.toArray());
        String batchToken = "DELETE-" + System.currentTimeMillis();
        for (Map<String, Object> row : targetRows) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batchToken", batchToken);
            payload.put("historyId", row.get("id"));
            payload.put("question", row.get("queryText"));
            payload.put("queryTableName", row.get("queryTableName"));
            payload.put("createdAt", row.get("createdAt"));
            recordHistoryAdminAudit("ADMIN_DELETE", toLong(row.get("id")), null,
                    Objects.toString(row.get("userId"), ""), deleteReason, payload);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deletedCount", updated);
        response.put("auditCount", targetRows.size());
        response.put("ids", actualIds);
        response.put("batchToken", batchToken);
        response.put("retentionDays", normalizedDeletedRetentionDays());
        return response;
    }

    public byte[] exportAdminHistoryExcel(String keyword, String userId, String tableName, String sourceType,
                                          String chartType, String riskLevel, String executionStatus,
                                          String modelType, String dateFrom, String dateTo,
                                          Boolean cacheHit, Boolean slowQuery, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 2000));
        Map<String, Object> page = listAdminHistoryPage(1, safeLimit, keyword, userId, tableName, sourceType,
                chartType, riskLevel, executionStatus, modelType, dateFrom, dateTo, cacheHit, slowQuery, "DESC");
        List<Map<String, Object>> items = castHistoryItems(page.get("items"));
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Chat History");
            List<String> headers = List.of(
                    "时间", "用户ID", "用户名", "昵称", "原始问题", "数据源", "图表类型", "模型",
                    "SQL状态", "AI解析结果", "执行状态", "风险等级", "耗时(ms)", "缓存命中", "慢查询", "生成SQL", "审计说明"
            );
            List<String> keys = List.of(
                    "createdAt", "userId", "username", "nickname", "question", "queryTableName", "chartType", "llmModelUsed",
                    "sqlStatusLabel", "aiParseResultLabel", "executionStatusLabel", "riskLevel", "executionTimeMs", "isHitCacheLabel", "slowQueryLabel", "generatedSql", "riskReason"
            );
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < items.size(); r++) {
                Map<String, Object> item = items.get(r);
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < keys.size(); c++) {
                    row.createCell(c).setCellValue(Objects.toString(item.get(keys.get(c)), ""));
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i), 14000));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Excel export failed: " + e.getMessage(), e);
        }
    }

    public void attachConversationMetadata(Long historyId, Long conversationId, Long parentHistoryId, Integer turnNo,
                                           String messageRole, String intentType, Map<String, Object> context,
                                           Map<String, Object> scope, String artifactType, String summaryText) {
        if (historyId == null || conversationId == null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    UPDATE is_chat_query_history
                       SET conversation_id = ?,
                           parent_history_id = ?,
                           turn_no = ?,
                           message_role = ?,
                           intent_type = ?,
                           context_json = ?,
                           scope_json = ?,
                           artifact_type = ?,
                           summary_text = ?
                     WHERE id = ?
                    """,
                    conversationId,
                    parentHistoryId,
                    turnNo,
                    safeText(Objects.toString(messageRole, "ASSISTANT"), 16),
                    safeText(intentType, 64),
                    toJson(context == null ? Map.of() : context),
                    toJson(scope == null ? Map.of() : scope),
                    safeText(Objects.toString(artifactType, "CHART"), 32),
                    safeText(summaryText, MAX_AUDIT_INFO_LENGTH),
                    historyId
            );
        } catch (Exception ignored) {
            // 会话元数据是兼容增强，不应影响旧历史主链路。
        }
    }

    private Map<String, Object> loadArtifactLinkByHistoryId(Long historyId) {
        if (historyId == null || !tableExists("is_chat_conversation_artifact")) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT a.conversation_id AS conversationId,
                       a.turn_id AS turnId,
                       JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.userTurnId')) AS userTurnId,
                       t.turn_no AS turnNo
                  FROM is_chat_conversation_artifact a
                  LEFT JOIN is_chat_conversation_turn t ON t.id = a.turn_id
                 WHERE a.history_id = ?
                 ORDER BY a.id DESC
                 LIMIT 1
                """, historyId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> loadAdminConversation(long conversationId) {
        if (conversationId <= 0 || !tableExists("is_chat_conversation")) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.id, c.user_id AS userId, c.title, c.data_source_id AS dataSourceId,
                       c.scope_json AS scopeJson, c.business_model_id AS businessModelId,
                       c.summary, c.last_turn_id AS lastTurnId, c.status,
                       c.created_at AS createdAt, c.updated_at AS updatedAt,
                       u.username AS username, u.nickname AS nickname,
                       (SELECT COUNT(*) FROM is_chat_conversation_turn t
                         WHERE t.conversation_id = c.id) AS turnCount
                  FROM is_chat_conversation c
                  LEFT JOIN is_user u ON u.user_id = c.user_id
                 WHERE c.id = ? AND c.is_deleted = 0
                 LIMIT 1
                """, conversationId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> conversation = new LinkedHashMap<>(row);
        conversation.put("scope", parseJsonMap(row.get("scopeJson")));
        conversation.put("operatorLabel", buildUserDisplayName(row));
        conversation.put("turnCount", toNullableInt(row.get("turnCount")));
        return conversation;
    }

    private List<Map<String, Object>> loadAdminConversationTurns(long conversationId) {
        if (conversationId <= 0 || !tableExists("is_chat_conversation_turn")) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                SELECT id, conversation_id AS conversationId, parent_turn_id AS parentTurnId,
                       turn_no AS turnNo, role, message_text AS messageText,
                       intent_type AS intentType, context_json AS contextJson,
                       followup_mode AS followupMode, created_at AS createdAt
                  FROM is_chat_conversation_turn
                 WHERE conversation_id = ?
                 ORDER BY turn_no ASC, id ASC
                """, conversationId);
    }

    private Map<Long, List<Map<String, Object>>> loadAdminArtifactsByTurn(List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty() || !tableExists("is_chat_conversation_artifact")) {
            return Map.of();
        }
        List<Long> limited = turnIds.stream().filter(id -> id != null && id > 0).distinct().limit(100).toList();
        if (limited.isEmpty()) {
            return Map.of();
        }
        String in = limited.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, conversation_id AS conversationId, turn_id AS turnId, history_id AS historyId,
                       artifact_type AS artifactType, artifact_json AS artifactJson,
                       sql_text AS sqlText, chart_type AS chartType, risk_level AS riskLevel,
                       created_at AS createdAt
                  FROM is_chat_conversation_artifact
                 WHERE turn_id IN (""" + in + """
                 )
                 ORDER BY turn_id ASC, id ASC
                """, limited.toArray());
        Map<Long, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long turnId = toLong(row.get("turnId"));
            if (turnId <= 0) {
                continue;
            }
            result.computeIfAbsent(turnId, ignored -> new ArrayList<>()).add(mapAdminConversationArtifact(row));
        }
        return result;
    }

    private int resolveAdminCurrentTurnIndex(List<Map<String, Object>> turns, long currentTurnId, long currentTurnNo) {
        if (turns == null || turns.isEmpty()) {
            return -1;
        }
        if (currentTurnId > 0) {
            for (int i = 0; i < turns.size(); i++) {
                if (toLong(turns.get(i).get("id")) == currentTurnId) {
                    return i;
                }
            }
        }
        if (currentTurnNo > 0) {
            for (int i = 0; i < turns.size(); i++) {
                if (toLong(turns.get(i).get("turnNo")) == currentTurnNo) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Map<String, Object> mapAdminConversationArtifact(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        Map<String, Object> artifact = parseJsonMap(row.get("artifactJson"));
        String artifactType = Objects.toString(row.get("artifactType"), "").trim();
        item.put("artifactTypeLabel", artifactTypeLabel(artifactType));
        item.put("artifact", artifact);
        item.put("summary", artifactSummary(row, artifact));
        item.put("question", firstNonBlank(artifact.get("question"), artifact.get("sourceQuestion")));
        item.put("message", firstNonBlank(artifact.get("message"), artifact.get("summary"), artifact.get("title")));
        item.put("tableName", firstNonBlank(artifact.get("tableName"), row.get("tableName")));
        item.put("sqlText", Objects.toString(row.get("sqlText"), ""));
        item.put("hasSql", !Objects.toString(row.get("sqlText"), "").trim().isBlank());
        item.put("hasChart", "CHART".equalsIgnoreCase(artifactType) || artifact.containsKey("chartType") || artifact.containsKey("data"));
        item.put("previewRows", extractSnapshotPreviewRows(artifact));
        return item;
    }

    private List<Map<String, Object>> hydrateCurrentArtifactChartSnapshot(List<Map<String, Object>> artifacts,
                                                                          Long historyId,
                                                                          Map<String, Object> current) {
        if (artifacts == null || artifacts.isEmpty() || historyId == null) {
            return artifacts == null ? List.of() : artifacts;
        }
        Map<String, Object> snapshot = toObjectMap(current.get("chartSnapshot"));
        if (snapshot.isEmpty() || !snapshot.containsKey("data")) {
            return artifacts;
        }
        List<Map<String, Object>> result = new ArrayList<>(artifacts.size());
        for (Map<String, Object> artifact : artifacts) {
            if (toLong(artifact.get("historyId")) == toLong(historyId)) {
                Map<String, Object> item = new LinkedHashMap<>(artifact);
                item.put("chartSnapshot", snapshot);
                if (Objects.toString(item.get("chartType"), "").trim().isBlank()) {
                    item.put("chartType", current.get("chartType"));
                }
                result.add(item);
            } else {
                result.add(artifact);
            }
        }
        return result;
    }

    private List<Map<String, Object>> buildSingleHistoryContextTurns(Map<String, Object> current) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("id", null);
        turn.put("conversationId", current.get("conversationId"));
        turn.put("turnNo", current.get("turnNo"));
        turn.put("role", "ASSISTANT");
        turn.put("roleLabel", "助手");
        turn.put("messageText", firstNonBlank(current.get("summaryText"), current.get("riskReason"), "该历史记录仅保留了单条查询结果"));
        turn.put("intentType", current.get("intentType"));
        turn.put("createdAt", current.get("createdAt"));
        turn.put("context", current.getOrDefault("context", Map.of()));
        turn.put("isCurrent", true);
        Map<String, Object> snapshot = toObjectMap(current.get("chartSnapshot"));
        String artifactType = advancedArtifactTypeFromSnapshot(snapshot, current.get("artifactType"), current.get("intentType"));
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("id", null);
        artifact.put("historyId", current.get("id"));
        artifact.put("artifactType", artifactType);
        artifact.put("artifactTypeLabel", artifactTypeLabel(artifactType));
        artifact.put("question", current.get("question"));
        artifact.put("message", current.get("summaryText"));
        artifact.put("summary", firstNonBlank(current.get("question"), current.get("summaryText")));
        artifact.put("sqlText", current.get("generatedSql"));
        artifact.put("chartType", current.get("chartType"));
        artifact.put("chartSnapshot", current.get("chartSnapshot"));
        artifact.put("riskLevel", current.get("riskLevel"));
        artifact.put("hasSql", !Objects.toString(current.get("generatedSql"), "").trim().isBlank());
        artifact.put("hasChart", current.get("chartSnapshot") instanceof Map<?, ?> && !isAdvancedAnalysisSnapshot(snapshot));
        if (isAdvancedAnalysisSnapshot(snapshot)) {
            Map<String, Object> advancedArtifact = new LinkedHashMap<>(snapshot);
            String advancedType = inferAdvancedAnalysisType(snapshot, current.get("artifactType"), current.get("intentType"));
            if (!advancedType.isBlank()) {
                advancedArtifact.putIfAbsent("type", advancedType);
                advancedArtifact.putIfAbsent("advancedAnalysisType", advancedType);
            }
            advancedArtifact.putIfAbsent("title", firstNonBlank(current.get("summaryText"), current.get("question")));
            advancedArtifact.putIfAbsent("summary", firstNonBlank(current.get("summaryText"), current.get("riskReason")));
            advancedArtifact.putIfAbsent("tableName", current.get("queryTableName"));
            artifact.put("artifact", advancedArtifact);
        }
        turn.put("artifacts", List.of(artifact));
        return List.of(turn);
    }

    private String conversationRoleLabel(Object value) {
        String role = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        return switch (role) {
            case "USER" -> "用户";
            case "ASSISTANT" -> "助手";
            case "SYSTEM" -> "系统";
            default -> role.isBlank() ? "消息" : role;
        };
    }

    private String artifactTypeLabel(String artifactType) {
        String type = Objects.toString(artifactType, "").trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "SQL" -> "SQL";
            case "CHART" -> "图表";
            case "TABLE" -> "表格";
            case "TEXT" -> "文本";
            case "REPORT" -> "报告";
            case "ADVANCED_FORECAST" -> "时序预测";
            case "ADVANCED_WHAT_IF" -> "What-if";
            case "ADVANCED_ALERT" -> "智能预警";
            default -> type.isBlank() ? "产物" : type;
        };
    }

    private String advancedArtifactTypeFromSnapshot(Map<String, Object> snapshot, Object artifactType, Object intentType) {
        String advancedType = inferAdvancedAnalysisType(snapshot, artifactType, intentType);
        if ("forecast".equals(advancedType)) {
            return "ADVANCED_FORECAST";
        }
        if ("whatIf".equals(advancedType)) {
            return "ADVANCED_WHAT_IF";
        }
        if ("alert".equals(advancedType)) {
            return "ADVANCED_ALERT";
        }
        String fallback = Objects.toString(artifactType, "").trim();
        return fallback.isBlank() ? "CHART" : fallback;
    }

    private String inferAdvancedAnalysisType(Map<String, Object> snapshot, Object artifactType, Object intentType) {
        Map<String, Object> fieldMapping = toObjectMap(snapshot.get("fieldMapping"));
        Object[] candidates = {
                artifactType,
                intentType,
                snapshot.get("advancedAnalysisType"),
                snapshot.get("type"),
                fieldMapping.get("mappingType")
        };
        for (Object candidate : candidates) {
            String resolved = normalizeAdvancedAnalysisTypeValue(candidate);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        if (!toObjectMap(snapshot.get("forecastMeta")).isEmpty() || containsForecastRows(snapshot.get("data"))) {
            return "forecast";
        }
        if (!toObjectMap(snapshot.get("whatIfMeta")).isEmpty()
                || snapshot.get("scenarios") instanceof List<?>
                || snapshot.get("variables") instanceof List<?>) {
            return "whatIf";
        }
        if (isAlertSnapshotShape(snapshot)) {
            return "alert";
        }
        return "";
    }

    private String normalizeAdvancedAnalysisTypeValue(Object value) {
        String normalized = Objects.toString(value, "").trim().replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("forecast")) {
            return "forecast";
        }
        if (normalized.contains("whatif")) {
            return "whatIf";
        }
        if (normalized.contains("alert") || normalized.contains("warning") || normalized.contains("prewarning")) {
            return "alert";
        }
        return "";
    }

    private String artifactSummary(Map<String, Object> row, Map<String, Object> artifact) {
        String text = firstNonBlank(
                artifact.get("message"),
                artifact.get("summary"),
                artifact.get("title"),
                artifact.get("sourceQuestion"),
                artifact.get("question")
        );
        if (!text.isBlank()) {
            return safeText(text, 240);
        }
        String type = artifactTypeLabel(Objects.toString(row.get("artifactType"), ""));
        String chart = Objects.toString(row.get("chartType"), "").trim();
        return chart.isBlank() ? type + " 产物" : type + " · " + chart;
    }

    private long firstPositive(long... values) {
        for (long value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0L;
    }

    private Map<String, Object> mapHistoryRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        Map<String, Object> snapshot = parseJsonMap(row.get("chartSnapshot"));
        hydrateForecastSnapshotFromSavedPlan(row, snapshot);
        List<Map<String, Object>> reasoningReplaySteps = parseJsonStepList(row.get("reasoningProcess"));
        List<String> reasoningProcess = reasoningReplaySteps.isEmpty()
                ? parseJsonStringList(row.get("reasoningProcess"))
                : compactReasoningSteps(reasoningReplaySteps);
        item.put("chartSnapshot", snapshot);
        item.put("chartRuleCode", firstNonBlankValue(snapshot.get("chartRuleCode"), row.get("chartRuleCode")));
        item.put("chartRuleName", firstNonBlankValue(snapshot.get("chartRuleName"), row.get("chartRuleName")));
        item.put("chartScenarioType", firstNonBlankValue(snapshot.get("chartScenarioType"), row.get("chartScenarioType")));
        item.put("chartRecommendationStatus", firstNonBlankValue(snapshot.get("chartRecommendationStatus"), row.get("chartRecommendationStatus")));
        item.put("chartRecommendationExplain", firstNonBlankValue(snapshot.get("chartRecommendationExplain"), row.get("chartRecommendationExplain")));
        item.put("graphContext", snapshot.get("graphContext"));
        item.put("graphPath", snapshot.get("graphPath"));
        item.put("graphSqlHints", snapshot.get("graphSqlHints"));
        item.put("reasoningProcess", reasoningProcess);
        item.put("reasoningReplaySteps", reasoningReplaySteps);
        String queryTableName = Objects.toString(row.getOrDefault("queryTableName", ""), "").trim();
        if (queryTableName.isBlank()) {
            queryTableName = Objects.toString(snapshot.getOrDefault("tableName", ""), "");
            item.put("queryTableName", queryTableName);
        }
        item.put("tableName", queryTableName);
        item.put("question", row.get("queryText"));
        item.put("sql", row.get("generatedSql"));
        item.put("riskReason", row.get("auditInfo"));
        Integer executionStatus = resolveHistoryExecutionStatus(row, snapshot);
        item.put("executionStatus", executionStatus);
        item.put("executionTimeMs", toNullableInt(row.get("executionTimeMs")));
        item.put("isHitCache", toBooleanFlag(row.get("isHitCache")));
        item.put("sourceType", toLong(row.get("dataSourceId")) > 0 ? "OFFICIAL" : "UPLOAD");
        item.put("intentType", row.get("intentType"));
        item.put("artifactType", row.get("artifactType"));
        Map<String, Object> context = parseJsonMap(row.get("contextJson"));
        Map<String, Object> scope = parseJsonMap(row.get("scopeJson"));
        Map<String, Object> conversationMeta = resolveHistoryConversationMeta(row, context, snapshot);
        item.put("conversationId", conversationMeta.get("conversationId"));
        item.put("turnNo", conversationMeta.get("turnNo"));
        item.put("context", context);
        item.put("scope", scope);
        item.put("userTurnId", firstNonBlankValue(context.get("userTurnId"), conversationMeta.get("userTurnId")));
        item.put("assistantTurnId", firstNonBlankValue(context.get("assistantTurnId"), conversationMeta.get("assistantTurnId")));
        item.put("summaryText", row.get("summaryText"));
        item.put("engine", Objects.toString(context.getOrDefault("engine", ""), ""));
        item.put("sqlStatus", resolveSqlStatus(item));
        item.put("sqlStatusLabel", historySqlStatusLabel(Objects.toString(item.get("sqlStatus"), "")));
        item.put("aiParseResult", resolveAiParseResult(item));
        item.put("aiParseResultLabel", aiParseResultLabel(Objects.toString(item.get("aiParseResult"), "")));
        return item;
    }

    private Integer resolveHistoryExecutionStatus(Map<String, Object> row, Map<String, Object> snapshot) {
        Integer status = toNullableInt(row.get("executionStatus"));
        if (Objects.equals(status, 0) && isAlertHistoryRecord(row, snapshot)) {
            return 1;
        }
        return status;
    }

    private boolean isAlertHistoryRecord(Map<String, Object> row, Map<String, Object> snapshot) {
        String chartType = Objects.toString(row.get("chartType"), "").trim();
        String artifactType = Objects.toString(row.get("artifactType"), "").trim();
        String intentType = Objects.toString(row.get("intentType"), "").trim();
        if ("alert".equals(normalizeAdvancedAnalysisTypeValue(chartType))) {
            return true;
        }
        if ("alert".equals(normalizeAdvancedAnalysisTypeValue(artifactType))) {
            return true;
        }
        if ("alert".equals(normalizeAdvancedAnalysisTypeValue(intentType))) {
            return true;
        }
        return isAlertSnapshotShape(snapshot);
    }

    private Map<String, Object> buildChartSnapshot(String tableName, Map<String, Object> result) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tableName", tableName);
        snapshot.put("chartType", result.get("chartType"));
        putIfNotBlank(snapshot, "chartRuleCode", Objects.toString(result.get("chartRuleCode"), ""));
        putIfNotBlank(snapshot, "chartRuleName", Objects.toString(result.get("chartRuleName"), ""));
        putIfNotBlank(snapshot, "chartScenarioType", Objects.toString(result.get("chartScenarioType"), ""));
        putIfNotBlank(snapshot, "chartRecommendationStatus", Objects.toString(result.get("chartRecommendationStatus"), ""));
        putIfNotBlank(snapshot, "chartRecommendationExplain", Objects.toString(result.get("chartRecommendationExplain"), ""));
        snapshot.put("fieldMapping", result.get("fieldMapping"));
        snapshot.put("semanticEvidence", result.getOrDefault("semanticEvidence", List.of()));
        snapshot.put("graphContext", result.get("graphContext"));
        snapshot.put("graphPath", result.get("graphPath"));
        snapshot.put("graphSqlHints", result.get("graphSqlHints"));
        snapshot.put("message", result.get("message"));
        snapshot.put("sql", result.get("sql"));
        snapshot.put("data", result.get("data"));
        snapshot.put("riskLevel", result.getOrDefault("riskLevel", "SAFE"));
        snapshot.put("riskReason", result.getOrDefault("riskReason", ""));
        snapshot.put("sensitiveFields", result.getOrDefault("sensitiveFields", List.of()));
        snapshot.put("matchedRules", result.getOrDefault("matchedRules", List.of()));
        if (result.get("tableColumns") != null) {
            snapshot.put("tableColumns", result.get("tableColumns"));
        }
        if (result.get("tableRows") != null) {
            snapshot.put("tableRows", result.get("tableRows"));
        }
        if (result.get("chartEngine") != null) {
            snapshot.put("chartEngine", result.get("chartEngine"));
        }
        if (result.get("dimensions") != null) {
            snapshot.put("dimensions", result.get("dimensions"));
        }
        if (result.get("encode") != null) {
            snapshot.put("encode", result.get("encode"));
        }
        if (result.get("optionTemplate") != null) {
            snapshot.put("optionTemplate", result.get("optionTemplate"));
        }
        copySmartRouteSnapshotFields(snapshot, result);
        copyAdvancedAnalysisSnapshotFields(snapshot, result);
        attachForecastSnapshotMeta(snapshot, result);
        attachAlertSnapshotMeta(snapshot, result);
        return snapshot;
    }

    private void copySmartRouteSnapshotFields(Map<String, Object> snapshot, Map<String, Object> result) {
        List<String> keys = List.of(
                "smartRouteAudit",
                "smartRouted",
                "smartIntent",
                "smartConfidence",
                "smartFallbackUsed",
                "responseType",
                "actionPlan",
                "stepResults",
                "multiStepSummary",
                "requiresConfirmation",
                "alertRuleDraft"
        );
        for (String key : keys) {
            Object value = result.get(key);
            if (value != null) {
                snapshot.put(key, value);
            }
        }
    }

    private void copyAdvancedAnalysisSnapshotFields(Map<String, Object> snapshot, Map<String, Object> result) {
        List<String> keys = List.of(
                "advancedAnalysisPlanId",
                "advancedAnalysisPlanName",
                "advancedAnalysisPlanVersion",
                "advancedAnalysisType",
                "advancedAnalysisAction",
                "forecastMeta",
                "params",
                "series",
                "insights",
                "explanation",
                "alertMeta",
                "alertRule",
                "alertRuleCreated",
                "alertRuleDraft",
                "event",
                "ruleId",
                "eventId",
                "actualValue",
                "threshold",
                "baseline",
                "baselineValue",
                "zScore",
                "operator",
                "bucketName",
                "metricField",
                "timeField",
                "granularity",
                "detectionCycle",
                "channels",
                "reason"
        );
        for (String key : keys) {
            Object value = result.get(key);
            if (value != null) {
                snapshot.put(key, value);
            }
        }
    }

    private void attachForecastSnapshotMeta(Map<String, Object> snapshot, Map<String, Object> result) {
        if (!isForecastResultShape(result)) {
            return;
        }
        snapshot.putIfAbsent("advancedAnalysisType", "forecast");
        Map<String, Object> fieldMapping = toObjectMap(result.get("fieldMapping"));
        Map<String, Object> params = toObjectMap(result.get("params"));
        Map<String, Object> forecastMeta = new LinkedHashMap<>(toObjectMap(result.get("forecastMeta")));
        Map<String, Object> algorithmParams = new LinkedHashMap<>(toObjectMap(forecastMeta.get("algorithmParams")));
        toObjectMap(result.get("algorithmParams")).forEach(algorithmParams::putIfAbsent);
        toObjectMap(params.get("algorithmParams")).forEach(algorithmParams::putIfAbsent);
        putIfNotBlank(algorithmParams, "alpha", firstNonBlank(algorithmParams.get("alpha"), params.get("alpha")));
        putIfNotBlank(algorithmParams, "beta", firstNonBlank(algorithmParams.get("beta"), params.get("beta")));
        putIfNotBlank(algorithmParams, "gamma", firstNonBlank(algorithmParams.get("gamma"), params.get("gamma")));
        putIfNotBlank(algorithmParams, "seasonLength", firstNonBlank(algorithmParams.get("seasonLength"), params.get("seasonLength")));
        putIfNotBlank(forecastMeta, "algorithm",
                firstNonBlank(forecastMeta.get("algorithm"), result.get("algorithm"), params.get("algorithm"), algorithmParams.get("algorithm"), fieldMapping.get("algorithm")));
        putIfNotBlank(forecastMeta, "confidence",
                firstNonBlank(forecastMeta.get("confidence"), result.get("confidence"), params.get("confidence"), result.get("confidenceLevel"), "95%"));
        putIfNotBlank(forecastMeta, "granularity",
                firstNonBlank(forecastMeta.get("granularity"), result.get("granularity"), params.get("granularity"), fieldMapping.get("granularity"), result.get("timeRange")));
        putIfNotBlank(forecastMeta, "timeField",
                firstNonBlank(forecastMeta.get("timeField"), result.get("timeField"), params.get("timeField"), fieldMapping.get("timeField")));
        putIfNotBlank(forecastMeta, "metricField",
                firstNonBlank(forecastMeta.get("metricField"), result.get("metricField"), params.get("metricField"), fieldMapping.get("metricField"), fieldMapping.get("metric")));
        putIfNotBlank(forecastMeta, "filterExpression",
                firstNonBlank(forecastMeta.get("filterExpression"), result.get("filterExpression"), params.get("filterExpression"), fieldMapping.get("filterExpression")));
        if (!algorithmParams.isEmpty()) {
            forecastMeta.put("algorithmParams", algorithmParams);
        }
        if (!forecastMeta.isEmpty()) {
            snapshot.put("forecastMeta", forecastMeta);
        }
    }

    private void attachAlertSnapshotMeta(Map<String, Object> snapshot, Map<String, Object> result) {
        if (!isAlertResultShape(result) && !isAlertSnapshotShape(snapshot)) {
            return;
        }
        snapshot.putIfAbsent("advancedAnalysisType", "alert");
        Map<String, Object> params = new LinkedHashMap<>(toObjectMap(result.get("params")));
        Map<String, Object> alertMeta = new LinkedHashMap<>(toObjectMap(result.get("alertMeta")));
        putIfNotBlank(alertMeta, "ruleName",
                firstNonBlank(result.get("ruleName"), params.get("ruleName"), result.get("title")));
        putIfNotBlank(alertMeta, "metricField",
                firstNonBlank(result.get("metricField"), params.get("metricField"), snapshot.get("metricField")));
        putIfNotBlank(alertMeta, "timeField",
                firstNonBlank(result.get("timeField"), params.get("timeField"), snapshot.get("timeField")));
        putIfNotBlank(alertMeta, "operator",
                firstNonBlank(result.get("operator"), params.get("operator"), snapshot.get("operator")));
        putIfNotBlank(alertMeta, "detectionCycle",
                firstNonBlank(result.get("detectionCycle"), params.get("detectionCycle"), snapshot.get("detectionCycle")));
        putIfNotBlank(alertMeta, "status",
                firstNonBlank(result.get("status"), params.get("status"), snapshot.get("status")));
        Object threshold = firstPresent(result.get("threshold"), params.get("threshold"), snapshot.get("threshold"));
        if (threshold != null) {
            alertMeta.put("threshold", threshold);
            params.putIfAbsent("threshold", threshold);
        }
        Object channels = firstPresent(result.get("channels"), params.get("channels"), snapshot.get("channels"));
        if (channels != null) {
            alertMeta.put("channels", channels);
            params.putIfAbsent("channels", channels);
        }
        if (!params.isEmpty()) {
            snapshot.putIfAbsent("params", params);
        }
        if (!alertMeta.isEmpty()) {
            snapshot.put("alertMeta", alertMeta);
        }
    }

    private void hydrateForecastSnapshotFromSavedPlan(Map<String, Object> historyRow, Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        if (!containsForecastRows(snapshot.get("data")) && !toObjectMap(snapshot.get("forecastMeta")).isEmpty()) {
            return;
        }
        if (!containsForecastRows(snapshot.get("data"))) {
            return;
        }
        boolean needsHydrate = snapshot.get("advancedAnalysisPlanVersion") == null
                || toObjectMap(toObjectMap(snapshot.get("forecastMeta")).get("algorithmParams")).isEmpty();
        if (!needsHydrate) {
            return;
        }
        String historyId = Objects.toString(historyRow.get("id"), "").trim();
        if (historyId.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> plans = jdbcTemplate.queryForList("""
                    SELECT id AS planId, plan_name AS planName, plan_type AS planType, version_no AS versionNo,
                           request_json AS requestJson, result_json AS resultJson, field_mapping_json AS fieldMappingJson
                    FROM is_advanced_analysis_plan
                    WHERE status <> 'DELETED'
                      AND (user_id = ? OR ? = 'ADMIN')
                      AND (
                        JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.queryHistoryId')) = ?
                        OR JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.chartId')) = ?
                      )
                    ORDER BY updated_at DESC, id DESC
                    LIMIT 1
                    """, resolveUserId(), AuthContext.role(), historyId, historyId);
            if (plans.isEmpty()) {
                return;
            }
            Map<String, Object> plan = plans.get(0);
            snapshot.putIfAbsent("advancedAnalysisPlanId", plan.get("planId"));
            snapshot.putIfAbsent("advancedAnalysisPlanName", plan.get("planName"));
            snapshot.putIfAbsent("advancedAnalysisPlanVersion", plan.get("versionNo"));
            snapshot.putIfAbsent("advancedAnalysisType", firstNonBlank(plan.get("planType"), "forecast"));
            Map<String, Object> action = toObjectMap(snapshot.get("advancedAnalysisAction"));
            if (action.isEmpty()) {
                action = new LinkedHashMap<>();
                action.put("type", "advanced-analysis-plan-recalculate");
                action.put("label", "重新计算预测");
                action.put("planId", plan.get("planId"));
                action.put("planVersion", plan.get("versionNo"));
                snapshot.put("advancedAnalysisAction", action);
            }
            Map<String, Object> result = parseJsonMap(plan.get("resultJson"));
            Map<String, Object> request = parseJsonMap(plan.get("requestJson"));
            Map<String, Object> fieldMapping = parseJsonMap(plan.get("fieldMappingJson"));
            Map<String, Object> merged = new LinkedHashMap<>(result);
            merged.put("fieldMapping", fieldMapping);
            merged.putIfAbsent("params", toObjectMap(result.get("params")));
            Map<String, Object> params = toObjectMap(merged.get("params"));
            request.forEach(params::putIfAbsent);
            merged.put("params", params);
            attachForecastSnapshotMeta(snapshot, merged);
        } catch (Exception ignored) {
            // 历史详情增强失败不应影响历史记录正常展示。
        }
    }

    private boolean isForecastResultShape(Map<String, Object> result) {
        Map<String, Object> fieldMapping = toObjectMap(result.get("fieldMapping"));
        String type = firstNonBlank(result.get("type"), result.get("planType"), fieldMapping.get("mappingType"));
        if ("forecast".equalsIgnoreCase(type)) {
            return true;
        }
        if (!firstNonBlank(result.get("algorithm"), result.get("algorithmParams"), result.get("confidence")).isBlank()) {
            return true;
        }
        String textSignal = (Objects.toString(result.get("message"), "") + " "
                + Objects.toString(fieldMapping.get("dimension"), "") + " "
                + Objects.toString(fieldMapping.get("metric"), "")).toLowerCase(Locale.ROOT);
        return containsForecastRows(result.get("data")) && textSignal.matches(".*(预测|预估|forecast|holt|prophet).*");
    }

    private boolean isAlertResultShape(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        Map<String, Object> fieldMapping = toObjectMap(result.get("fieldMapping"));
        Map<String, Object> params = toObjectMap(result.get("params"));
        Object[] typeCandidates = {
                result.get("type"),
                result.get("planType"),
                result.get("advancedAnalysisType"),
                fieldMapping.get("mappingType")
        };
        for (Object candidate : typeCandidates) {
            if ("alert".equals(normalizeAdvancedAnalysisTypeValue(candidate))) {
                return true;
            }
        }
        String normalizedTypeText = firstNonBlank(typeCandidates).replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);
        if (normalizedTypeText.contains("warning") || normalizedTypeText.contains("prewarning")) {
            return true;
        }
        if (!toObjectMap(result.get("alertMeta")).isEmpty()
                || !toObjectMap(result.get("alertRule")).isEmpty()
                || !toObjectMap(result.get("alertRuleCreated")).isEmpty()
                || !toObjectMap(result.get("alertRuleDraft")).isEmpty()
                || result.containsKey("ruleId")
                || result.containsKey("eventId")) {
            return true;
        }
        boolean hasCondition = !firstNonBlank(result.get("operator"), params.get("operator")).isBlank()
                && (result.containsKey("threshold")
                || params.containsKey("threshold")
                || result.containsKey("actualValue")
                || result.containsKey("zScore")
                || result.containsKey("baseline")
                || result.containsKey("baselineValue"));
        boolean hasMetric = !firstNonBlank(result.get("metricField"), params.get("metricField"),
                result.get("metric"), fieldMapping.get("metric"), fieldMapping.get("metricKey")).isBlank();
        return hasCondition && hasMetric;
    }

    private boolean containsForecastRows(Object data) {
        if (!(data instanceof List<?> rows)) {
            return false;
        }
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> row = toObjectMap(raw);
            boolean hasForecastColumns = row.containsKey("history")
                    || row.containsKey("forecast")
                    || row.containsKey("upper")
                    || row.containsKey("lower")
                    || row.containsKey("phase");
            boolean hasForecastValues = row.get("forecast") != null
                    || row.get("upper") != null
                    || row.get("lower") != null
                    || "forecast".equalsIgnoreCase(Objects.toString(row.get("phase"), ""));
            if (hasForecastColumns && hasForecastValues) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            if (!key.isBlank()) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = Objects.toString(value, "").trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.trim().isBlank()) {
                continue;
            }
            return value;
        }
        return null;
    }

    private Object nestedValue(Map<String, Object> source, String parentKey, String childKey) {
        if (source == null) {
            return null;
        }
        Object parent = source.get(parentKey);
        if (parent instanceof Map<?, ?> map) {
            return map.get(childKey);
        }
        return null;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, Object> mapAdminHistoryRow(Map<String, Object> row) {
        Map<String, Object> item = mapHistoryRow(row);
        item.put("username", row.get("username"));
        item.put("nickname", row.get("nickname"));
        item.put("slowQuery", toBooleanFlag(row.get("slowQuery")));
        item.put("slowQueryLabel", toBooleanFlag(row.get("slowQuery")) ? "是" : "否");
        item.put("executionStatusLabel", historyExecutionLabel(toNullableInt(item.get("executionStatus"))));
        item.put("isHitCacheLabel", toBooleanFlag(row.get("isHitCache")) ? "命中" : "未命中");
        item.put("operatorLabel", buildUserDisplayName(row));
        Map<String, Object> snapshot = toObjectMap(item.get("chartSnapshot"));
        item.put("modelCategory", resolveAdminModelCategory(row, snapshot));
        item.put("snapshotPreviewRows", extractSnapshotPreviewRows(snapshot));
        item.put("snapshotMetrics", summarizeSnapshot(snapshot));
        return item;
    }

    private StringBuilder buildAdminHistoryWhereSql(String keyword, String userId, String tableName, String sourceType,
                                                    String chartType, String riskLevel, Integer executionStatus,
                                                    String modelType, String dateFrom, String dateTo,
                                                    Boolean cacheHit, Boolean slowQuery, List<Object> args) {
        StringBuilder sql = new StringBuilder("""
                FROM is_chat_query_history h
                LEFT JOIN is_user u ON u.user_id = h.user_id
                WHERE h.is_deleted = 0
                """);
        if (!keyword.isBlank()) {
            sql.append("""
                     AND (
                         h.query_text LIKE ?
                         OR h.generated_sql LIKE ?
                         OR h.query_table_name LIKE ?
                         OR h.audit_info LIKE ?
                         OR u.username LIKE ?
                         OR u.nickname LIKE ?
                         OR h.llm_model_used LIKE ?
                     )
                    """);
            String likeText = "%" + keyword + "%";
            args.add(likeText);
            args.add(likeText);
            args.add(likeText);
            args.add(likeText);
            args.add(likeText);
            args.add(likeText);
            args.add(likeText);
        }
        if (!userId.isBlank()) {
            sql.append(" AND (h.user_id = ? OR u.username LIKE ? OR u.nickname LIKE ?)");
            args.add(userId);
            args.add("%" + userId + "%");
            args.add("%" + userId + "%");
        }
        if (!tableName.isBlank()) {
            sql.append(" AND h.query_table_name LIKE ?");
            args.add("%" + tableName + "%");
        }
        if (!sourceType.isBlank()) {
            if ("OFFICIAL".equals(sourceType)) {
                sql.append(" AND h.data_source_id > 0");
            } else if ("UPLOAD".equals(sourceType)) {
                sql.append(" AND h.data_source_id = 0");
            }
        }
        if (!chartType.isBlank()) {
            sql.append(" AND h.chart_type = ?");
            args.add(chartType);
        }
        if (!riskLevel.isBlank()) {
            sql.append(" AND h.risk_level = ?");
            args.add(riskLevel);
        }
        if (executionStatus != null) {
            sql.append(" AND h.execution_status = ?");
            args.add(executionStatus);
        }
        if (!modelType.isBlank()) {
            sql.append(" AND UPPER(COALESCE(h.llm_model_used, '')) LIKE ?");
            args.add("%" + modelType.toUpperCase(Locale.ROOT) + "%");
        }
        if (dateFrom != null) {
            sql.append(" AND h.created_at >= ?");
            args.add(dateFrom);
        }
        if (dateTo != null) {
            sql.append(" AND h.created_at <= ?");
            args.add(dateTo);
        }
        if (cacheHit != null) {
            sql.append(" AND h.is_hit_cache = ?");
            args.add(cacheHit ? 1 : 0);
        }
        if (slowQuery != null) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM is_sql_audit_log a
                         WHERE a.user_id = h.user_id
                           AND a.question = h.query_text
                           AND COALESCE(a.table_name, '') = COALESCE(h.query_table_name, '')
                           AND a.created_at BETWEEN DATE_SUB(h.created_at, INTERVAL 30 SECOND)
                                               AND DATE_ADD(h.created_at, INTERVAL 30 SECOND)
                           AND a.slow_query = ?
                         LIMIT 1
                     )
                    """);
            args.add(slowQuery ? 1 : 0);
        }
        return sql;
    }

    private Map<String, Object> findAdminHistoryRow(Long historyId) {
        if (historyId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT h.id, h.user_id AS userId, h.data_source_id AS dataSourceId,
                       h.query_table_name AS queryTableName, h.query_text AS queryText,
                       h.generated_sql AS generatedSql, h.llm_model_used AS llmModelUsed,
                       h.chart_type AS chartType, h.chart_snapshot AS chartSnapshot,
                       h.reasoning_process AS reasoningProcess, h.execution_status AS executionStatus,
                       h.risk_level AS riskLevel, h.audit_info AS auditInfo,
                       h.execution_time_ms AS executionTimeMs, h.is_hit_cache AS isHitCache,
                       h.conversation_id AS conversationId, h.parent_history_id AS parentHistoryId,
                       h.turn_no AS turnNo, h.message_role AS messageRole, h.intent_type AS intentType,
                       h.artifact_type AS artifactType, h.summary_text AS summaryText,
                       h.context_json AS contextJson, h.scope_json AS scopeJson, h.created_at AS createdAt,
                       u.username AS username, u.nickname AS nickname,
                       EXISTS (
                           SELECT 1 FROM is_sql_audit_log a
                           WHERE a.user_id = h.user_id
                             AND a.question = h.query_text
                             AND COALESCE(a.table_name, '') = COALESCE(h.query_table_name, '')
                             AND a.created_at BETWEEN DATE_SUB(h.created_at, INTERVAL 30 SECOND)
                                                 AND DATE_ADD(h.created_at, INTERVAL 30 SECOND)
                             AND a.slow_query = 1
                           LIMIT 1
                       ) AS slowQuery
                FROM is_chat_query_history h
                LEFT JOIN is_user u ON u.user_id = h.user_id
                WHERE h.id = ? AND h.is_deleted = 0
                LIMIT 1
                """, historyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> loadRelatedAuditLogs(Map<String, Object> item) {
        String userId = Objects.toString(item.get("userId"), "").trim();
        String question = Objects.toString(item.get("question"), "").trim();
        String tableName = Objects.toString(item.get("queryTableName"), "").trim();
        Object createdAt = item.get("createdAt");
        if (userId.isBlank() || question.isBlank() || createdAt == null) {
            return List.of();
        }
        String redisStatusSelect = columnExists("is_sql_audit_log", "redis_status")
                ? "redis_status AS redisStatus,"
                : "'LOCAL' AS redisStatus,";
        String sql = """
                SELECT id, user_id AS userId, question, table_name AS tableName, engine,
                       generated_sql AS generatedSql, risk_level AS riskLevel,
                       risk_reason AS riskReason, matched_rules AS matchedRules,
                       sensitive_fields AS sensitiveFields, slow_query AS slowQuery,
                       execute_status AS executeStatus, duration_ms AS durationMs,
                       error_message AS errorMessage, generation_trace AS generationTrace,
                       kg_match_log AS kgMatchLog, cache_key AS cacheKey, cache_hit AS cacheHit,
                       cache_sql AS cacheSql, cache_audit_status AS cacheAuditStatus,
                """ + redisStatusSelect + """
                       mask_detail AS maskDetail, execution_guard AS executionGuard,
                       query_guard_action AS queryGuardAction, created_at AS createdAt
                FROM is_sql_audit_log
                WHERE user_id = ?
                  AND question = ?
                  AND COALESCE(table_name, '') = COALESCE(?, '')
                  AND created_at BETWEEN DATE_SUB(?, INTERVAL 30 SECOND)
                                      AND DATE_ADD(?, INTERVAL 30 SECOND)
                ORDER BY created_at DESC
                LIMIT 10
                """;
        return jdbcTemplate.queryForList(sql, userId, question, tableName, createdAt, createdAt);
    }

    private Map<String, Object> buildAuditSummary(List<Map<String, Object>> auditLogs) {
        if (auditLogs == null || auditLogs.isEmpty()) {
            return Map.of(
                    "count", 0,
                    "blockedCount", 0,
                    "warnCount", 0,
                    "slowCount", 0,
                    "latest", Map.of()
            );
        }
        int blocked = 0;
        int warn = 0;
        int slow = 0;
        for (Map<String, Object> log : auditLogs) {
            String riskLevel = Objects.toString(log.get("riskLevel"), "");
            if ("BLOCKED".equalsIgnoreCase(riskLevel)) {
                blocked++;
            } else if ("WARN".equalsIgnoreCase(riskLevel)) {
                warn++;
            }
            if (toBooleanFlag(log.get("slowQuery"))) {
                slow++;
            }
        }
        return Map.of(
                "count", auditLogs.size(),
                "blockedCount", blocked,
                "warnCount", warn,
                "slowCount", slow,
                "latest", auditLogs.get(0)
        );
    }

    private Map<String, Object> buildAdminHistoryGovernance() {
        Map<String, Object> governance = new LinkedHashMap<>();
        long pendingPurgeCount = 0L;
        try {
            Long count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM is_chat_query_history
                    WHERE is_deleted = 1
                    """, Long.class);
            pendingPurgeCount = count == null ? 0L : count;
        } catch (Exception ignored) {
            pendingPurgeCount = 0L;
        }
        governance.put("cleanupEnabled", historyCleanupEnabled);
        governance.put("deletedRetentionDays", normalizedDeletedRetentionDays());
        governance.put("cleanupBatchSize", normalizedCleanupBatchSize());
        governance.put("pendingPurgeCount", pendingPurgeCount);
        governance.put("policyText", "逻辑删除记录保留 " + normalizedDeletedRetentionDays()
                + " 天，系统每日自动清理未被看板引用的已删历史。");
        return governance;
    }

    private List<Map<String, Object>> queryAdminTrendRows(StringBuilder whereSql, List<Object> args,
                                                          String selectSql, String appendClause) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                selectSql + whereSql + appendClause
                        + " GROUP BY DATE_FORMAT(h.created_at, '%Y-%m-%d')"
                        + " ORDER BY DATE_FORMAT(h.created_at, '%Y-%m-%d') ASC",
                args.toArray()
        );
        return rows.size() <= 30 ? rows : rows.subList(rows.size() - 30, rows.size());
    }

    private Map<String, Object> buildAdminPerformanceSummary(StringBuilder whereSql, List<Object> args) {
        Map<String, Object> metrics = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS totalCount,
                       SUM(CASE WHEN h.is_hit_cache = 1 THEN 1 ELSE 0 END) AS cacheHitCount,
                       SUM(CASE WHEN h.is_hit_cache = 0 THEN 1 ELSE 0 END) AS cacheMissCount,
                       ROUND(AVG(CASE WHEN h.execution_time_ms IS NULL THEN 0 ELSE h.execution_time_ms END), 0) AS avgDurationMs,
                       MAX(COALESCE(h.execution_time_ms, 0)) AS maxDurationMs,
                       SUM(CASE WHEN h.risk_level IN ('BLOCKED', 'WARN') THEN 1 ELSE 0 END) AS riskCount
                """ + whereSql, args.toArray());

        Long slowCountRaw = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                """ + whereSql + " AND " + slowQueryExistsClause("h"), Long.class, args.toArray());
        long totalCount = toLong(metrics.get("totalCount"));
        long cacheHitCount = toLong(metrics.get("cacheHitCount"));
        long cacheMissCount = toLong(metrics.get("cacheMissCount"));
        long slowCount = slowCountRaw == null ? 0L : slowCountRaw;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCount", totalCount);
        summary.put("cacheHitCount", cacheHitCount);
        summary.put("cacheMissCount", cacheMissCount);
        summary.put("avgDurationMs", toLong(metrics.get("avgDurationMs")));
        summary.put("maxDurationMs", toLong(metrics.get("maxDurationMs")));
        summary.put("riskCount", toLong(metrics.get("riskCount")));
        summary.put("slowCount", slowCount);
        summary.put("slowQueryRate", totalCount == 0 ? 0 : Math.round(slowCount * 1000.0 / totalCount) / 10.0);
        summary.put("cacheHitRate", totalCount == 0 ? 0 : Math.round(cacheHitCount * 1000.0 / totalCount) / 10.0);
        summary.put("cacheMissRate", totalCount == 0 ? 0 : Math.round(cacheMissCount * 1000.0 / totalCount) / 10.0);
        return summary;
    }

    private List<Map<String, Object>> queryAdminSlowDetails(StringBuilder whereSql, List<Object> args, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safeLimit);
        return jdbcTemplate.queryForList("""
                SELECT h.id, h.user_id AS userId, h.query_text AS question,
                       h.query_table_name AS queryTableName, h.execution_time_ms AS executionTimeMs,
                       h.is_hit_cache AS isHitCache, h.risk_level AS riskLevel,
                       h.llm_model_used AS llmModelUsed, h.created_at AS createdAt
                """ + whereSql + " AND " + slowQueryExistsClause("h") + """
                ORDER BY COALESCE(h.execution_time_ms, 0) DESC, h.created_at DESC
                LIMIT ?
                """, queryArgs.toArray());
    }

    private List<Map<String, Object>> queryAdminSlowGroups(StringBuilder whereSql, List<Object> args, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safeLimit);
        return jdbcTemplate.queryForList("""
                SELECT h.query_text AS question, h.query_table_name AS queryTableName,
                       COUNT(*) AS hitCount,
                       ROUND(AVG(COALESCE(h.execution_time_ms, 0)), 0) AS avgDurationMs,
                       MAX(COALESCE(h.execution_time_ms, 0)) AS maxDurationMs,
                       MAX(h.created_at) AS lastSeenAt
                """ + whereSql + " AND " + slowQueryExistsClause("h") + """
                GROUP BY h.query_text, h.query_table_name
                ORDER BY COUNT(*) DESC, AVG(COALESCE(h.execution_time_ms, 0)) DESC, MAX(h.created_at) DESC
                LIMIT ?
                """, queryArgs.toArray());
    }

    private List<Map<String, Object>> queryAdminCacheMissGroups(StringBuilder whereSql, List<Object> args, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safeLimit);
        return jdbcTemplate.queryForList("""
                SELECT h.query_text AS question, h.query_table_name AS queryTableName,
                       COUNT(*) AS missCount,
                       ROUND(AVG(COALESCE(h.execution_time_ms, 0)), 0) AS avgDurationMs,
                       MAX(h.created_at) AS lastSeenAt
                """ + whereSql + """
                  AND h.is_hit_cache = 0
                GROUP BY h.query_text, h.query_table_name
                ORDER BY COUNT(*) DESC, AVG(COALESCE(h.execution_time_ms, 0)) DESC, MAX(h.created_at) DESC
                LIMIT ?
                """, queryArgs.toArray());
    }

    private Map<String, Object> buildAdminRouteAuditAnalytics(StringBuilder whereSql, List<Object> args) {
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(ROUTE_AUDIT_SAMPLE_LIMIT);
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList("""
                    SELECT h.id AS historyId,
                           h.user_id AS userId,
                           h.query_text AS question,
                           h.query_table_name AS queryTableName,
                           h.chart_snapshot AS chartSnapshot,
                           h.context_json AS contextJson,
                           (
                               SELECT a.artifact_json
                                 FROM is_chat_conversation_artifact a
                                WHERE a.history_id = h.id
                                  AND a.artifact_type IN ('CHART', 'ADVANCED_ALERT')
                                ORDER BY a.id DESC
                                LIMIT 1
                           ) AS artifactJson,
                           h.intent_type AS intentType,
                           h.artifact_type AS artifactType,
                           h.llm_model_used AS llmModelUsed,
                           h.created_at AS createdAt
                    """ + whereSql + """
                    ORDER BY h.created_at DESC
                    LIMIT ?
                    """, queryArgs.toArray());
        } catch (Exception ignored) {
            Map<String, Object> unavailable = defaultRouteAuditAnalytics();
            unavailable.put("available", false);
            unavailable.put("message", "智能路由观测数据暂不可用");
            return unavailable;
        }

        Map<String, RouteAuditGroup> intentGroups = new LinkedHashMap<>();
        Map<String, RouteAuditGroup> executorGroups = new LinkedHashMap<>();
        Map<String, RouteAuditGroup> actionGroups = new LinkedHashMap<>();
        Map<String, Long> failureReasons = new LinkedHashMap<>();
        Map<String, Long> missingSlotGroups = new LinkedHashMap<>();
        List<Map<String, Object>> lowConfidenceExamples = new ArrayList<>();
        List<Map<String, Object>> problemSamples = new ArrayList<>();

        long total = 0;
        long successCount = 0;
        long failureCount = 0;
        long fallbackCount = 0;
        long clarificationCount = 0;
        long confirmationCount = 0;
        long lowConfidenceCount = 0;
        long unknownConfidenceCount = 0;
        long durationTotal = 0;
        long durationCount = 0;
        double confidenceTotal = 0D;
        long confidenceCount = 0;

        for (Map<String, Object> row : rows) {
            Map<String, Object> audit = extractSmartRouteAudit(row);
            if (audit.isEmpty()) {
                continue;
            }
            total++;
            String primaryIntent = firstNonBlank(audit.get("primaryIntent"), row.get("intentType"), "UNKNOWN");
            String executor = firstNonBlank(audit.get("chosenExecutor"), row.get("llmModelUsed"), "未记录执行器");
            String outcome = firstNonBlank(audit.get("outcome"), "UNKNOWN").toUpperCase(Locale.ROOT);
            List<String> missingSlots = routeAuditStringList(audit.get("missingSlots"));
            boolean fallbackUsed = toBooleanFlag(audit.get("fallbackUsed"));
            boolean requiresConfirmation = toBooleanFlag(audit.get("requiresConfirmation"))
                    || "NEEDS_CONFIRMATION".equals(outcome);
            boolean clarification = "NEEDS_INPUT".equals(outcome) || !missingSlots.isEmpty();
            boolean success = routeAuditSuccessFlag(audit, outcome);
            boolean failed = !success;
            double confidence = toDouble(audit.get("confidence"), Double.NaN);
            long durationMs = Math.max(0L, toLong(audit.get("durationMs")));

            if (success) {
                successCount++;
            }
            if (failed) {
                failureCount++;
            }
            if (fallbackUsed) {
                fallbackCount++;
            }
            if (clarification) {
                clarificationCount++;
            }
            if (requiresConfirmation) {
                confirmationCount++;
            }
            if (durationMs > 0) {
                durationTotal += durationMs;
                durationCount++;
            }
            if (Double.isFinite(confidence) && confidence > 0D) {
                confidenceTotal += confidence;
                confidenceCount++;
                if (confidence < ROUTE_AUDIT_LOW_CONFIDENCE_THRESHOLD) {
                    lowConfidenceCount++;
                }
            } else {
                unknownConfidenceCount++;
            }

            addRouteAuditGroup(intentGroups, primaryIntent, success, fallbackUsed, clarification, failed, durationMs, confidence);
            addRouteAuditGroup(executorGroups, executor, success, fallbackUsed, clarification, failed, durationMs, confidence);
            for (String actionType : routeAuditActionTypes(audit, primaryIntent)) {
                addRouteAuditGroup(actionGroups, actionType, success, fallbackUsed, clarification, failed, durationMs, confidence);
            }
            for (String slot : missingSlots) {
                incrementCount(missingSlotGroups, slot);
            }
            if (failed) {
                incrementCount(failureReasons, firstNonBlank(audit.get("failureReason"), "未记录失败原因"));
            }

            Map<String, Object> sample = buildRouteAuditSample(row, audit, primaryIntent, executor,
                    outcome, fallbackUsed, confidence, durationMs, missingSlots);
            if (Double.isFinite(confidence) && confidence > 0D && confidence < ROUTE_AUDIT_LOW_CONFIDENCE_THRESHOLD
                    && lowConfidenceExamples.size() < 8) {
                lowConfidenceExamples.add(sample);
            }
            if ((failed || fallbackUsed || clarification || requiresConfirmation
                    || (Double.isFinite(confidence) && confidence > 0D
                    && confidence < ROUTE_AUDIT_LOW_CONFIDENCE_THRESHOLD)) && problemSamples.size() < 10) {
                problemSamples.add(sample);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRouted", total);
        summary.put("successCount", successCount);
        summary.put("failureCount", failureCount);
        summary.put("fallbackCount", fallbackCount);
        summary.put("clarificationCount", clarificationCount);
        summary.put("confirmationCount", confirmationCount);
        summary.put("lowConfidenceCount", lowConfidenceCount);
        summary.put("unknownConfidenceCount", unknownConfidenceCount);
        summary.put("avgDurationMs", durationCount == 0 ? 0 : Math.round(durationTotal * 1.0 / durationCount));
        summary.put("avgConfidence", confidenceCount == 0 ? 0 : roundDouble(confidenceTotal / confidenceCount, 2));
        summary.put("successRate", rate(successCount, total));
        summary.put("failureRate", rate(failureCount, total));
        summary.put("fallbackRate", rate(fallbackCount, total));
        summary.put("clarificationRate", rate(clarificationCount, total));
        summary.put("confirmationRate", rate(confirmationCount, total));
        summary.put("lowConfidenceRate", rate(lowConfidenceCount, total));
        summary.put("lowConfidenceThreshold", ROUTE_AUDIT_LOW_CONFIDENCE_THRESHOLD);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("available", true);
        response.put("sampleLimit", ROUTE_AUDIT_SAMPLE_LIMIT);
        response.put("sampledRows", rows.size());
        response.put("summary", summary);
        response.put("intentGroups", routeAuditGroupRows(intentGroups, "primaryIntent", 8));
        response.put("executorGroups", routeAuditGroupRows(executorGroups, "chosenExecutor", 8));
        response.put("actionGroups", routeAuditGroupRows(actionGroups, "actionType", 8));
        response.put("failureReasons", countRows(failureReasons, "reason", 8));
        response.put("missingSlotGroups", countRows(missingSlotGroups, "slot", 8));
        response.put("lowConfidenceExamples", lowConfidenceExamples);
        response.put("problemSamples", problemSamples);
        return response;
    }

    private Map<String, Object> defaultRouteAuditAnalytics() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRouted", 0);
        summary.put("successCount", 0);
        summary.put("failureCount", 0);
        summary.put("fallbackCount", 0);
        summary.put("clarificationCount", 0);
        summary.put("confirmationCount", 0);
        summary.put("lowConfidenceCount", 0);
        summary.put("unknownConfidenceCount", 0);
        summary.put("avgDurationMs", 0);
        summary.put("avgConfidence", 0);
        summary.put("successRate", 0);
        summary.put("failureRate", 0);
        summary.put("fallbackRate", 0);
        summary.put("clarificationRate", 0);
        summary.put("confirmationRate", 0);
        summary.put("lowConfidenceRate", 0);
        summary.put("lowConfidenceThreshold", ROUTE_AUDIT_LOW_CONFIDENCE_THRESHOLD);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("available", true);
        response.put("sampleLimit", ROUTE_AUDIT_SAMPLE_LIMIT);
        response.put("sampledRows", 0);
        response.put("summary", summary);
        response.put("intentGroups", List.of());
        response.put("executorGroups", List.of());
        response.put("actionGroups", List.of());
        response.put("failureReasons", List.of());
        response.put("missingSlotGroups", List.of());
        response.put("lowConfidenceExamples", List.of());
        response.put("problemSamples", List.of());
        return response;
    }

    private Map<String, Object> extractSmartRouteAudit(Map<String, Object> row) {
        Map<String, Object> snapshot = parseJsonMap(row.get("chartSnapshot"));
        Map<String, Object> audit = toObjectMap(snapshot.get("smartRouteAudit"));
        if (!audit.isEmpty()) {
            return audit;
        }
        Map<String, Object> context = parseJsonMap(row.get("contextJson"));
        audit = toObjectMap(context.get("smartRouteAudit"));
        if (!audit.isEmpty()) {
            return audit;
        }
        Map<String, Object> artifact = parseJsonMap(row.get("artifactJson"));
        audit = toObjectMap(artifact.get("smartRouteAudit"));
        if (!audit.isEmpty()) {
            return audit;
        }
        if (looksLikeSmartRouteAudit(snapshot)) {
            return snapshot;
        }
        if (looksLikeSmartRouteAudit(context)) {
            return context;
        }
        if (looksLikeSmartRouteAudit(artifact)) {
            return artifact;
        }
        return Map.of();
    }

    private boolean looksLikeSmartRouteAudit(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return value.containsKey("primaryIntent")
                && (value.containsKey("chosenExecutor")
                || value.containsKey("outcome")
                || value.containsKey("confidence")
                || value.containsKey("fallbackUsed"));
    }

    private boolean routeAuditSuccessFlag(Map<String, Object> audit, String outcome) {
        if (audit.containsKey("success")) {
            return toBooleanFlag(audit.get("success"));
        }
        String normalized = Objects.toString(outcome, "").trim().toUpperCase(Locale.ROOT);
        return !"FAILED".equals(normalized) && !"PARTIAL_FAILED".equals(normalized);
    }

    private List<String> routeAuditActionTypes(Map<String, Object> audit, String fallbackIntent) {
        Object rawActions = audit.get("actions");
        List<String> result = new ArrayList<>();
        if (rawActions instanceof List<?> actions) {
            for (Object action : actions) {
                if (action instanceof Map<?, ?> map) {
                    String type = firstNonBlank(map.get("type"), map.get("actionType"), fallbackIntent);
                    if (!type.isBlank()) {
                        result.add(type);
                    }
                } else {
                    String text = Objects.toString(action, "").trim();
                    if (!text.isBlank()) {
                        result.add(text);
                    }
                }
            }
        }
        if (result.isEmpty() && !Objects.toString(fallbackIntent, "").trim().isBlank()) {
            result.add(fallbackIntent);
        }
        return result.stream().distinct().toList();
    }

    private List<String> routeAuditStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = Objects.toString(item, "").trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return List.of();
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<Object> raw = objectMapper.readValue(text, new TypeReference<>() {});
                return routeAuditStringList(raw);
            } catch (Exception ignored) {
                // fall through to delimiter parsing
            }
        }
        return List.of(text.split("[,，、]")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private void addRouteAuditGroup(Map<String, RouteAuditGroup> groups, String key,
                                    boolean success, boolean fallbackUsed, boolean clarification,
                                    boolean failed, long durationMs, double confidence) {
        String resolvedKey = Objects.toString(key, "").trim();
        if (resolvedKey.isBlank()) {
            resolvedKey = "UNKNOWN";
        }
        groups.computeIfAbsent(resolvedKey, RouteAuditGroup::new)
                .accept(success, fallbackUsed, clarification, failed, durationMs, confidence);
    }

    private List<Map<String, Object>> routeAuditGroupRows(Map<String, RouteAuditGroup> groups,
                                                          String labelKey, int limit) {
        return groups.values().stream()
                .sorted((left, right) -> Long.compare(right.count, left.count))
                .limit(limit)
                .map(group -> routeAuditGroupRow(group, labelKey))
                .toList();
    }

    private Map<String, Object> routeAuditGroupRow(RouteAuditGroup group, String labelKey) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(labelKey, group.key);
        row.put("count", group.count);
        row.put("successCount", group.successCount);
        row.put("failureCount", group.failureCount);
        row.put("fallbackCount", group.fallbackCount);
        row.put("clarificationCount", group.clarificationCount);
        row.put("successRate", rate(group.successCount, group.count));
        row.put("failureRate", rate(group.failureCount, group.count));
        row.put("fallbackRate", rate(group.fallbackCount, group.count));
        row.put("clarificationRate", rate(group.clarificationCount, group.count));
        row.put("avgDurationMs", group.durationCount == 0 ? 0 : Math.round(group.durationTotal * 1.0 / group.durationCount));
        row.put("avgConfidence", group.confidenceCount == 0 ? 0 : roundDouble(group.confidenceTotal / group.confidenceCount, 2));
        return row;
    }

    private List<Map<String, Object>> countRows(Map<String, Long> counts, String labelKey, int limit) {
        return counts.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(labelKey, entry.getKey());
                    row.put("count", entry.getValue());
                    return row;
                })
                .toList();
    }

    private void incrementCount(Map<String, Long> counts, String key) {
        String resolvedKey = Objects.toString(key, "").trim();
        if (resolvedKey.isBlank()) {
            resolvedKey = "未记录";
        }
        counts.put(resolvedKey, counts.getOrDefault(resolvedKey, 0L) + 1L);
    }

    private Map<String, Object> buildRouteAuditSample(Map<String, Object> row,
                                                      Map<String, Object> audit,
                                                      String primaryIntent,
                                                      String executor,
                                                      String outcome,
                                                      boolean fallbackUsed,
                                                      double confidence,
                                                      long durationMs,
                                                      List<String> missingSlots) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("historyId", row.get("historyId"));
        sample.put("userId", row.get("userId"));
        sample.put("question", firstNonBlank(audit.get("question"), row.get("question")));
        sample.put("queryTableName", firstNonBlank(audit.get("tableName"), row.get("queryTableName")));
        sample.put("primaryIntent", primaryIntent);
        sample.put("chosenExecutor", executor);
        sample.put("outcome", outcome);
        sample.put("fallbackUsed", fallbackUsed);
        sample.put("confidence", Double.isFinite(confidence) && confidence > 0D ? roundDouble(confidence, 2) : null);
        sample.put("durationMs", durationMs > 0 ? durationMs : null);
        sample.put("missingSlots", missingSlots);
        sample.put("failureReason", firstNonBlank(audit.get("failureReason")));
        sample.put("createdAt", row.get("createdAt"));
        return sample;
    }

    private double rate(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private double roundDouble(double value, int scale) {
        if (!Double.isFinite(value)) {
            return 0D;
        }
        double factor = Math.pow(10, Math.max(0, scale));
        return Math.round(value * factor) / factor;
    }

    private double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String slowQueryExistsClause(String historyAlias) {
        String alias = Objects.toString(historyAlias, "h").trim();
        if (alias.isBlank()) {
            alias = "h";
        }
        return "EXISTS ("
                + " SELECT 1 FROM is_sql_audit_log a"
                + " WHERE a.user_id = " + alias + ".user_id"
                + " AND a.question = " + alias + ".query_text"
                + " AND COALESCE(a.table_name, '') = COALESCE(" + alias + ".query_table_name, '')"
                + " AND a.created_at BETWEEN DATE_SUB(" + alias + ".created_at, INTERVAL 30 SECOND)"
                + " AND DATE_ADD(" + alias + ".created_at, INTERVAL 30 SECOND)"
                + " AND a.slow_query = 1"
                + " LIMIT 1"
                + " )";
    }

    private void attachAdminRerunLink(Long newHistoryId, Long originHistoryId,
                                      Map<String, Object> originDetail, Map<String, Object> result) {
        if (newHistoryId == null || originHistoryId == null) {
            return;
        }
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("rerunByAdmin", true);
            context.put("originHistoryId", originHistoryId);
            context.put("originUserId", originDetail.get("userId"));
            context.put("originConversationId", originDetail.get("conversationId"));
            context.put("originTurnNo", originDetail.get("turnNo"));
            context.put("engine", result.get("engine"));
            context.put("sourceType", originDetail.get("sourceType"));
            context.put("cacheHit", result.get("cacheHit"));

            Map<String, Object> scope = originDetail.get("scope") instanceof Map<?, ?> scopeMap
                    ? castToStringObjectMap(scopeMap)
                    : new LinkedHashMap<>();
            String summaryText = "管理员复跑自历史记录 #" + originHistoryId + "，原用户 "
                    + Objects.toString(originDetail.get("userId"), "-") + "。";

            jdbcTemplate.update("""
                    UPDATE is_chat_query_history
                       SET conversation_id = ?,
                           parent_history_id = ?,
                           turn_no = ?,
                           message_role = ?,
                           intent_type = ?,
                           context_json = ?,
                           scope_json = ?,
                           artifact_type = ?,
                           summary_text = ?
                     WHERE id = ?
                    """,
                    originDetail.get("conversationId"),
                    originHistoryId,
                    originDetail.get("turnNo"),
                    "ADMIN",
                    safeText(Objects.toString(originDetail.get("intentType"), "ADMIN_RERUN"), 64),
                    toJson(context),
                    toJson(scope),
                    safeText(Objects.toString(originDetail.get("artifactType"), "CHART"), 32),
                    safeText(summaryText, MAX_AUDIT_INFO_LENGTH),
                    newHistoryId
            );
        } catch (Exception ignored) {
            // 复跑关联增强失败不应影响主流程
        }
    }

    private Map<String, Object> adminHistorySummary(String keyword, String userId, String tableName, String sourceType,
                                                    String chartType, String riskLevel, Integer executionStatus,
                                                    String modelType, String dateFrom, String dateTo) {
        List<Object> args = new ArrayList<>();
        StringBuilder whereSql = buildAdminHistoryWhereSql(keyword, userId, tableName, sourceType, chartType,
                riskLevel, executionStatus, modelType, dateFrom, dateTo, null, null, args);
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN h.execution_status = 1 THEN 1 ELSE 0 END) AS successCount,
                       SUM(CASE WHEN h.execution_status = 0 THEN 1 ELSE 0 END) AS failedCount,
                       SUM(CASE WHEN h.execution_status = 2 THEN 1 ELSE 0 END) AS cancelledCount,
                       SUM(CASE WHEN h.risk_level = 'BLOCKED' THEN 1 ELSE 0 END) AS blockedCount,
                       SUM(CASE WHEN h.risk_level = 'WARN' THEN 1 ELSE 0 END) AS warnCount,
                       SUM(CASE WHEN h.is_hit_cache = 1 THEN 1 ELSE 0 END) AS cacheHitCount,
                       SUM(CASE WHEN DATE(h.created_at) = CURRENT_DATE THEN 1 ELSE 0 END) AS todayCount,
                       ROUND(AVG(CASE WHEN h.execution_time_ms IS NULL THEN 0 ELSE h.execution_time_ms END), 0) AS avgDurationMs
                """ + whereSql, args.toArray());

        Map<String, Object> slow = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS slowCount
                """ + whereSql + """
                  AND EXISTS (
                      SELECT 1 FROM is_sql_audit_log a
                      WHERE a.user_id = h.user_id
                        AND a.question = h.query_text
                        AND COALESCE(a.table_name, '') = COALESCE(h.query_table_name, '')
                        AND a.created_at BETWEEN DATE_SUB(h.created_at, INTERVAL 30 SECOND)
                                             AND DATE_ADD(h.created_at, INTERVAL 30 SECOND)
                        AND a.slow_query = 1
                      LIMIT 1
                  )
                """, args.toArray());
        long total = toLong(totals.get("total"));
        long success = toLong(totals.get("successCount"));
        long cacheHits = toLong(totals.get("cacheHitCount"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("todayCount", toLong(totals.get("todayCount")));
        summary.put("successCount", success);
        summary.put("failedCount", toLong(totals.get("failedCount")));
        summary.put("cancelledCount", toLong(totals.get("cancelledCount")));
        summary.put("blockedCount", toLong(totals.get("blockedCount")));
        summary.put("warnCount", toLong(totals.get("warnCount")));
        summary.put("slowCount", toLong(slow.get("slowCount")));
        summary.put("cacheHitCount", cacheHits);
        summary.put("avgDurationMs", toLong(totals.get("avgDurationMs")));
        summary.put("successRate", total == 0 ? 0 : Math.round(success * 1000.0 / total) / 10.0);
        summary.put("cacheHitRate", total == 0 ? 0 : Math.round(cacheHits * 1000.0 / total) / 10.0);
        return summary;
    }

    private List<Long> normalizeHistoryIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .limit(500)
                .toList();
    }

    @Scheduled(cron = "${insight.chat-history.cleanup-cron:0 30 3 * * *}")
    public void cleanupDeletedHistoryOnSchedule() {
        cleanupDeletedHistory();
    }

    public Map<String, Object> cleanupDeletedHistory() {
        if (!historyCleanupEnabled) {
            return Map.of("enabled", false, "purgedCount", 0);
        }
        List<Map<String, Object>> candidates = loadDeletedHistoryPurgeCandidates(
                normalizedDeletedRetentionDays(), normalizedCleanupBatchSize());
        if (candidates.isEmpty()) {
            return Map.of("enabled", true, "purgedCount", 0);
        }
        List<Long> ids = candidates.stream()
                .map(row -> toLong(row.get("id")))
                .filter(id -> id > 0)
                .toList();
        String inSql = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM is_chat_query_history WHERE id IN (" + inSql + ")", ids.toArray());
        for (Map<String, Object> row : candidates) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("historyId", row.get("id"));
            payload.put("question", row.get("queryText"));
            payload.put("queryTableName", row.get("queryTableName"));
            payload.put("deletedAt", row.get("deletedAt"));
            payload.put("retentionDays", normalizedDeletedRetentionDays());
            recordSystemHistoryAudit("AUTO_PURGE", toLong(row.get("id")), null,
                    Objects.toString(row.get("userId"), ""),
                    "已超过逻辑删除保留期 " + normalizedDeletedRetentionDays() + " 天", payload);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", true);
        response.put("purgedCount", ids.size());
        response.put("ids", ids);
        return response;
    }

    private List<Map<String, Object>> castHistoryItems(Object items) {
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((key, value) -> row.put(String.valueOf(key), value));
                result.add(row);
            }
        }
        return result;
    }

    private Map<String, Object> buildUserSummary(Map<String, Object> row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", row.get("userId"));
        user.put("username", row.get("username"));
        user.put("nickname", row.get("nickname"));
        user.put("displayName", buildUserDisplayName(row));
        return user;
    }

    private List<Map<String, Object>> loadHistoryRowsByIds(List<Long> ids, boolean onlyActive) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String inSql = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT id, user_id AS userId, query_text AS queryText,
                       query_table_name AS queryTableName, created_at AS createdAt
                FROM is_chat_query_history
                WHERE id IN (""" + inSql + ")";
        if (onlyActive) {
            sql += " AND is_deleted = 0";
        }
        return jdbcTemplate.queryForList(sql, ids.toArray());
    }

    private List<Map<String, Object>> loadDeletedHistoryPurgeCandidates(int retentionDays, int limit) {
        if (retentionDays <= 0 || limit <= 0) {
            return List.of();
        }
        String dashboardClause = tableExists("is_dashboard_component")
                ? """
                   AND NOT EXISTS (
                       SELECT 1 FROM is_dashboard_component dc
                       WHERE dc.chart_id = h.id
                       LIMIT 1
                   )
                  """
                : "";
        return jdbcTemplate.queryForList("""
                SELECT h.id, h.user_id AS userId, h.query_text AS queryText,
                       h.query_table_name AS queryTableName, h.deleted_at AS deletedAt
                FROM is_chat_query_history h
                WHERE h.is_deleted = 1
                  AND h.deleted_at IS NOT NULL
                  AND h.deleted_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY)
                """ + dashboardClause + """
                ORDER BY h.deleted_at ASC
                LIMIT ?
                """, retentionDays, limit);
    }

    private String buildUserDisplayName(Map<String, Object> row) {
        String nickname = Objects.toString(row.get("nickname"), "").trim();
        String username = Objects.toString(row.get("username"), "").trim();
        String userId = Objects.toString(row.get("userId"), "").trim();
        if (!nickname.isBlank()) {
            return nickname;
        }
        if (!username.isBlank()) {
            return username;
        }
        return userId;
    }

    private String resolveModelCategory(String modelName) {
        String text = Objects.toString(modelName, "").trim().toUpperCase(Locale.ROOT);
        if (text.isBlank()) {
            return "UNKNOWN";
        }
        if (text.contains("GPT") || text.contains("AI")) {
            return "LLM";
        }
        if (text.contains("RULE")) {
            return "RULE_BASED";
        }
        return text;
    }

    private Map<String, Object> resolveHistoryConversationMeta(Map<String, Object> row,
                                                               Map<String, Object> context,
                                                               Map<String, Object> snapshot) {
        Map<String, Object> meta = new LinkedHashMap<>();
        long historyId = toLong(row.get("id"));
        long conversationId = toLong(row.get("conversationId"));
        long assistantTurnId = toLong(context.get("assistantTurnId"));
        long userTurnId = toLong(context.get("userTurnId"));
        Integer turnNo = toNullableInt(row.get("turnNo"));

        Map<String, Object> artifactMeta = resolveConversationMetaByHistoryId(historyId);
        conversationId = firstPositive(conversationId, toLong(artifactMeta.get("conversationId")));
        assistantTurnId = firstPositive(assistantTurnId, toLong(artifactMeta.get("assistantTurnId")));
        userTurnId = firstPositive(userTurnId, toLong(artifactMeta.get("userTurnId")));
        if (turnNo == null || turnNo <= 0) {
            turnNo = toNullableInt(artifactMeta.get("turnNo"));
        }

        if (conversationId <= 0 || assistantTurnId <= 0 || turnNo == null || turnNo <= 0) {
            Map<String, Object> planMeta = resolveConversationMetaByAdvancedPlan(historyId, snapshot);
            conversationId = firstPositive(conversationId, toLong(planMeta.get("conversationId")));
            assistantTurnId = firstPositive(assistantTurnId, toLong(planMeta.get("assistantTurnId")));
            userTurnId = firstPositive(userTurnId, toLong(planMeta.get("userTurnId")));
            if (turnNo == null || turnNo <= 0) {
                turnNo = toNullableInt(planMeta.get("turnNo"));
            }
        }

        if ((turnNo == null || turnNo <= 0) && conversationId > 0) {
            Integer contextTurnNo = resolveTurnNoByContext(conversationId, Map.of(
                    "assistantTurnId", assistantTurnId,
                    "userTurnId", userTurnId
            ));
            if (contextTurnNo != null && contextTurnNo > 0) {
                turnNo = contextTurnNo;
            }
        }

        meta.put("conversationId", conversationId > 0 ? conversationId : null);
        meta.put("assistantTurnId", assistantTurnId > 0 ? assistantTurnId : null);
        meta.put("userTurnId", userTurnId > 0 ? userTurnId : null);
        meta.put("turnNo", turnNo != null && turnNo > 0 ? turnNo : null);
        return meta;
    }

    private Map<String, Object> resolveConversationMetaByHistoryId(long historyId) {
        if (historyId <= 0) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT a.conversation_id AS conversationId,
                           a.turn_id AS assistantTurnId,
                           JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.userTurnId')) AS userTurnId,
                           t.turn_no AS turnNo
                      FROM is_chat_conversation_artifact a
                      INNER JOIN is_chat_conversation_turn t ON t.id = a.turn_id
                     WHERE a.history_id = ?
                     ORDER BY t.turn_no DESC, t.id DESC
                     LIMIT 1
                    """, historyId);
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> resolveConversationMetaByAdvancedPlan(long historyId, Map<String, Object> snapshot) {
        if (historyId <= 0 && (snapshot == null || snapshot.isEmpty())) {
            return Map.of();
        }
        long planId = toLong(snapshot == null ? null : snapshot.get("advancedAnalysisPlanId"));
        if (planId <= 0 && snapshot != null) {
            planId = toLong(toObjectMap(snapshot.get("advancedAnalysisAction")).get("planId"));
        }
        long artifactId = 0L;
        try {
            if (planId > 0) {
                List<Map<String, Object>> planRows = jdbcTemplate.queryForList("""
                        SELECT result_json AS resultJson, llm_json AS llmJson
                          FROM is_advanced_analysis_plan
                         WHERE id = ? AND status <> 'DELETED'
                         LIMIT 1
                        """, planId);
                if (!planRows.isEmpty()) {
                    Map<String, Object> result = parseJsonMap(planRows.get(0).get("resultJson"));
                    Map<String, Object> llm = parseJsonMap(planRows.get(0).get("llmJson"));
                    artifactId = firstPositive(
                            toLong(firstNonBlank(result.get("artifactId"), llm.get("artifactId"))),
                            0L
                    );
                }
            }
            if (artifactId > 0) {
                List<Map<String, Object>> artifactRows = jdbcTemplate.queryForList("""
                        SELECT a.conversation_id AS conversationId,
                               a.turn_id AS assistantTurnId,
                               JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.userTurnId')) AS userTurnId,
                               t.turn_no AS turnNo
                          FROM is_chat_conversation_artifact a
                          INNER JOIN is_chat_conversation_turn t ON t.id = a.turn_id
                         WHERE a.id = ?
                         LIMIT 1
                        """, artifactId);
                if (!artifactRows.isEmpty()) {
                    return artifactRows.get(0);
                }
            }
            List<Object> args = new ArrayList<>();
            StringBuilder where = new StringBuilder(" WHERE p.status <> 'DELETED' ");
            if (planId > 0) {
                where.append(" AND p.id = ? ");
                args.add(planId);
            } else {
                where.append("""
                         AND (
                           JSON_UNQUOTE(JSON_EXTRACT(p.result_json, '$.queryHistoryId')) = ?
                           OR JSON_UNQUOTE(JSON_EXTRACT(p.result_json, '$.chartId')) = ?
                         )
                        """);
                args.add(String.valueOf(historyId));
                args.add(String.valueOf(historyId));
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT a.conversation_id AS conversationId,
                           a.turn_id AS assistantTurnId,
                           JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.userTurnId')) AS userTurnId,
                           t.turn_no AS turnNo
                      FROM is_advanced_analysis_plan p
                      INNER JOIN is_chat_conversation_artifact a
                        ON JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.planId')) = CAST(p.id AS CHAR)
                        OR JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.advancedAnalysis.params.planId')) = CAST(p.id AS CHAR)
                      INNER JOIN is_chat_conversation_turn t ON t.id = a.turn_id
                    """ + where + """
                     ORDER BY a.created_at DESC, a.id DESC
                     LIMIT 1
                    """, args.toArray());
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private long firstPositive(long first, long second) {
        return first > 0 ? first : Math.max(second, 0);
    }

    private Object firstNonBlankValue(Object first, Object second) {
        String text = Objects.toString(first, "").trim();
        return text.isBlank() ? second : first;
    }

    private Integer resolveTurnNoByHistoryId(long historyId) {
        if (historyId <= 0) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT t.turn_no AS turnNo
                      FROM is_chat_conversation_artifact a
                      INNER JOIN is_chat_conversation_turn t ON t.id = a.turn_id
                     WHERE a.history_id = ?
                     ORDER BY t.turn_no DESC, t.id DESC
                     LIMIT 1
                    """, historyId);
            return rows.isEmpty() ? null : toNullableInt(rows.get(0).get("turnNo"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer resolveTurnNoByContext(long conversationId, Map<String, Object> context) {
        if (conversationId <= 0 || context == null || context.isEmpty()) {
            return null;
        }
        long assistantTurnId = toLong(context.get("assistantTurnId"));
        long userTurnId = toLong(context.get("userTurnId"));
        long turnId = assistantTurnId > 0 ? assistantTurnId : userTurnId;
        if (turnId <= 0) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT turn_no AS turnNo
                      FROM is_chat_conversation_turn
                     WHERE id = ? AND conversation_id = ?
                     LIMIT 1
                    """, turnId, conversationId);
            return rows.isEmpty() ? null : toNullableInt(rows.get(0).get("turnNo"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveAdminModelCategory(Map<String, Object> row, Map<String, Object> snapshot) {
        if (isAdvancedAnalysisSnapshot(snapshot)) {
            String modelCategory = resolveModelCategory(Objects.toString(row.get("llmModelUsed"), ""));
            return "UNKNOWN".equals(modelCategory) ? "LLM" : modelCategory;
        }
        return resolveModelCategory(Objects.toString(row.get("llmModelUsed"), ""));
    }

    private boolean isAdvancedAnalysisSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        Map<String, Object> fieldMapping = toObjectMap(snapshot.get("fieldMapping"));
        String type = firstNonBlank(
                snapshot.get("advancedAnalysisType"),
                snapshot.get("type"),
                fieldMapping.get("mappingType")
        );
        String normalized = type.replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("forecast")
                || normalized.contains("whatif")
                || normalized.contains("alert")
                || normalized.contains("warning")
                || normalized.contains("prewarning")
                || !toObjectMap(snapshot.get("forecastMeta")).isEmpty()
                || containsForecastRows(snapshot.get("data"))
                || !toObjectMap(snapshot.get("whatIfMeta")).isEmpty()
                || snapshot.get("scenarios") instanceof List<?>
                || snapshot.get("variables") instanceof List<?>
                || !toObjectMap(snapshot.get("alertMeta")).isEmpty()
                || snapshot.containsKey("alertRule")
                || snapshot.containsKey("ruleId")
                || isAlertSnapshotShape(snapshot);
    }

    private boolean isAlertSnapshotShape(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        boolean hasAlertObject = !toObjectMap(snapshot.get("alertMeta")).isEmpty()
                || !toObjectMap(snapshot.get("alertRule")).isEmpty()
                || !toObjectMap(snapshot.get("alertRuleCreated")).isEmpty()
                || !toObjectMap(snapshot.get("alertRuleDraft")).isEmpty()
                || snapshot.containsKey("ruleId")
                || snapshot.containsKey("eventId");
        if (hasAlertObject) {
            return true;
        }
        boolean hasCondition = !Objects.toString(snapshot.get("operator"), "").trim().isBlank()
                && (snapshot.containsKey("threshold")
                || snapshot.containsKey("actualValue")
                || snapshot.containsKey("zScore")
                || snapshot.containsKey("baseline")
                || snapshot.containsKey("baselineValue"));
        boolean hasMetric = !Objects.toString(snapshot.get("metricField"), "").trim().isBlank()
                || !Objects.toString(snapshot.get("metric"), "").trim().isBlank();
        return hasCondition && hasMetric;
    }

    private boolean isForecastSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        Map<String, Object> fieldMapping = toObjectMap(snapshot.get("fieldMapping"));
        String type = firstNonBlank(snapshot.get("advancedAnalysisType"), snapshot.get("type"), fieldMapping.get("mappingType"));
        String normalized = type.replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("forecast")
                || !toObjectMap(snapshot.get("forecastMeta")).isEmpty()
                || containsForecastRows(snapshot.get("data"));
    }

    private String resolveSqlStatus(Map<String, Object> item) {
        String sql = Objects.toString(item.get("generatedSql"), Objects.toString(item.get("sql"), "")).trim();
        Integer executionStatus = toNullableInt(item.get("executionStatus"));
        String riskLevel = Objects.toString(item.get("riskLevel"), "").trim().toUpperCase(Locale.ROOT);
        if (sql.isBlank()) {
            if (Objects.equals(executionStatus, 0)) {
                return "FAILED";
            }
            if (Objects.equals(executionStatus, 2)) {
                return "CANCELLED";
            }
            return "EMPTY";
        }
        if ("BLOCKED".equals(riskLevel)) {
            return "BLOCKED";
        }
        return "READY";
    }

    private String historySqlStatusLabel(String status) {
        return switch (Objects.toString(status, "").trim().toUpperCase(Locale.ROOT)) {
            case "BLOCKED" -> "已生成并拦截";
            case "FAILED" -> "生成失败";
            case "CANCELLED" -> "已取消";
            case "READY" -> "已生成";
            default -> "无 SQL";
        };
    }

    private String resolveAiParseResult(Map<String, Object> item) {
        String engine = Objects.toString(item.get("engine"), "").trim().toLowerCase(Locale.ROOT);
        String modelName = Objects.toString(item.get("llmModelUsed"), "").trim().toUpperCase(Locale.ROOT);
        String sql = Objects.toString(item.get("generatedSql"), Objects.toString(item.get("sql"), "")).trim();
        Integer executionStatus = toNullableInt(item.get("executionStatus"));
        if (engine.contains("redis-semantic-cache")) {
            return "CACHE_REUSED";
        }
        if (engine.contains("python-ai-service")) {
            return "AI_SUCCESS";
        }
        if (engine.contains("java-federal-join")) {
            return "FEDERAL_JOIN";
        }
        if (engine.contains("java-fallback")) {
            return "RULE_FALLBACK";
        }
        if (modelName.contains("GPT") || modelName.contains("AI")) {
            return "AI_SUCCESS";
        }
        if (modelName.contains("RULE")) {
            return "RULE_FALLBACK";
        }
        if (!sql.isBlank()) {
            return "PARSED";
        }
        if (Objects.equals(executionStatus, 0)) {
            return "PARSE_FAILED";
        }
        return "UNKNOWN";
    }

    private String aiParseResultLabel(String status) {
        return switch (Objects.toString(status, "").trim().toUpperCase(Locale.ROOT)) {
            case "CACHE_REUSED" -> "命中语义缓存";
            case "AI_SUCCESS" -> "AI 解析成功";
            case "RULE_FALLBACK" -> "规则兜底";
            case "FEDERAL_JOIN" -> "联邦关联直连";
            case "PARSE_FAILED" -> "解析失败";
            case "PARSED" -> "已完成解析";
            default -> "解析信息缺失";
        };
    }

    private Map<String, Object> buildCacheContext(Map<String, Object> auditLog) {
        if (auditLog == null || auditLog.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cacheContext = new LinkedHashMap<>();
        cacheContext.put("cacheHit", toBooleanFlag(auditLog.get("cacheHit")));
        cacheContext.put("cacheKey", auditLog.get("cacheKey"));
        cacheContext.put("cacheAuditStatus", auditLog.get("cacheAuditStatus"));
        cacheContext.put("redisStatus", auditLog.get("redisStatus"));
        cacheContext.put("cacheSql", auditLog.get("cacheSql"));
        cacheContext.put("queryGuardAction", auditLog.get("queryGuardAction"));
        return cacheContext;
    }

    private Map<String, Object> buildPermissionCheck(Map<String, Object> item, List<Map<String, Object>> auditLogs) {
        String queryTableName = Objects.toString(item.get("queryTableName"), "").trim();
        Map<String, Object> scope = item.get("scope") instanceof Map<?, ?> scopeMap
                ? castToStringObjectMap(scopeMap)
                : Map.of();
        String scopeTableName = Objects.toString(scope.getOrDefault("tableName", queryTableName), "").trim();
        String riskReason = Objects.toString(item.get("riskReason"), "").trim();
        boolean blocked = textContainsAny(riskReason, "未授权", "unauthorized", "forbidden", "访问未授权表");
        String blockedReason = riskReason;
        for (Map<String, Object> log : auditLogs) {
            String logRiskReason = Objects.toString(log.get("riskReason"), "").trim();
            if (hasMatchedRule(log, "TABLE_SCOPE") || textContainsAny(logRiskReason, "未授权", "unauthorized", "forbidden", "访问未授权表")) {
                blocked = true;
                blockedReason = logRiskReason.isBlank() ? blockedReason : logRiskReason;
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scopeTableName", scopeTableName);
        result.put("dataSourceId", scope.get("dataSourceId"));
        if (blocked) {
            result.put("status", "BLOCKED");
            result.put("label", "权限拦截");
            result.put("message", "未通过数据表权限范围校验");
            result.put("detail", blockedReason.isBlank() ? "当前查询访问了超出授权范围的数据表或作用域。" : blockedReason);
            return result;
        }
        if (!scope.isEmpty() || !scopeTableName.isBlank()) {
            result.put("status", "PASSED");
            result.put("label", "校验通过");
            result.put("message", "已按当前数据表范围完成只读权限校验");
            result.put("detail", scopeTableName.isBlank()
                    ? "已沿用查询链路中的权限控制与数据范围限制。"
                    : "当前授权作用域表：" + scopeTableName);
            return result;
        }
        result.put("status", "UNKNOWN");
        result.put("label", "记录缺失");
        result.put("message", "未记录独立权限校验结果");
        result.put("detail", "当前详情仍沿用查询链路的权限控制，但历史记录中没有保存更细粒度的权限校验快照。");
        return result;
    }

    private boolean hasMatchedRule(Map<String, Object> log, String ruleCode) {
        if (log == null || ruleCode == null || ruleCode.isBlank()) {
            return false;
        }
        String matchedRules = Objects.toString(log.get("matchedRules"), "");
        for (String part : matchedRules.split(",")) {
            if (ruleCode.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean textContainsAny(String text, String... keywords) {
        String value = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(Objects.toString(keyword, "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> castToStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<Map<String, Object>> extractSnapshotPreviewRows(Map<String, Object> snapshot) {
        Object raw = snapshot.get("data");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        if (containsForecastRows(raw)) {
            return extractForecastSnapshotPreviewRows(list);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list.stream().limit(6).toList()) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((key, value) -> row.put(String.valueOf(key), value));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> extractForecastSnapshotPreviewRows(List<?> list) {
        List<Map<String, Object>> historyRows = new ArrayList<>();
        List<Map<String, Object>> forecastRows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> source = castToStringObjectMap(map);
            boolean hasForecast = source.get("forecast") != null
                    || source.get("upper") != null
                    || source.get("lower") != null
                    || "forecast".equalsIgnoreCase(Objects.toString(source.get("phase"), ""));
            if (hasForecast) {
                forecastRows.add(normalizeForecastSnapshotPreviewRow(source));
            } else {
                historyRows.add(normalizeForecastSnapshotPreviewRow(source));
            }
        }
        int maxRows = 12;
        List<Map<String, Object>> rows = new ArrayList<>();
        int historyLimit = Math.min(historyRows.size(), Math.max(0, maxRows - Math.min(forecastRows.size(), 8)));
        if (historyLimit > 0) {
            rows.addAll(historyRows.subList(historyRows.size() - historyLimit, historyRows.size()));
        }
        if (forecastRows.size() <= maxRows - rows.size()) {
            rows.addAll(forecastRows);
        } else {
            int remaining = maxRows - rows.size();
            int head = Math.max(1, remaining / 2);
            int tail = Math.max(0, remaining - head);
            rows.addAll(forecastRows.subList(0, Math.min(head, forecastRows.size())));
            if (tail > 0 && forecastRows.size() > head) {
                rows.addAll(forecastRows.subList(Math.max(head, forecastRows.size() - tail), forecastRows.size()));
            }
        }
        return rows;
    }

    private Map<String, Object> normalizeForecastSnapshotPreviewRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("周期", firstNonBlank(row.get("name"), row.get("period"), row.get("dimension"), "-"));
        normalized.put("历史值", firstNonBlank(row.get("history"),
                "history".equalsIgnoreCase(Objects.toString(row.get("phase"), "")) ? row.get("value") : null));
        normalized.put("预测值", firstNonBlank(row.get("forecast"),
                "forecast".equalsIgnoreCase(Objects.toString(row.get("phase"), "")) ? row.get("value") : null));
        normalized.put("置信下界", firstNonBlank(row.get("lower"), row.get("lowerBound")));
        normalized.put("置信上界", firstNonBlank(row.get("upper"), row.get("upperBound")));
        normalized.put("阶段", forecastPhaseLabel(row));
        return normalized;
    }

    private String forecastPhaseLabel(Map<String, Object> row) {
        String phase = Objects.toString(row.get("phase"), "").trim().toLowerCase(Locale.ROOT);
        if ("history".equals(phase)) {
            return "历史";
        }
        if ("forecast".equals(phase)) {
            return "预测";
        }
        if (row.get("forecast") != null || row.get("upper") != null || row.get("lower") != null) {
            return "预测";
        }
        return "历史";
    }

    private Map<String, Object> summarizeSnapshot(Map<String, Object> snapshot) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> previewRows = extractSnapshotPreviewRows(snapshot);
        summary.put("rowCount", previewRows.size());
        summary.put("chartType", snapshot.get("chartType"));
        summary.put("tableName", snapshot.get("tableName"));
        summary.put("hasGraphContext", snapshot.get("graphContext") instanceof List<?> list && !list.isEmpty());
        summary.put("fieldMapping", snapshot.get("fieldMapping"));
        summary.put("advancedAnalysisType", snapshot.get("advancedAnalysisType"));
        summary.put("forecastMeta", snapshot.get("forecastMeta"));
        summary.put("advancedAnalysisPlanVersion", snapshot.get("advancedAnalysisPlanVersion"));
        return summary;
    }

    private Boolean canRerun(Map<String, Object> item) {
        return !Objects.toString(item.get("question"), "").trim().isBlank();
    }

    private String normalizeSourceType(String sourceType) {
        String text = Objects.toString(sourceType, "").trim().toUpperCase(Locale.ROOT);
        if ("UPLOAD".equals(text) || "OFFICIAL".equals(text)) {
            return text;
        }
        return "";
    }

    private int normalizedDeletedRetentionDays() {
        return Math.max(1, deletedRetentionDays);
    }

    private int normalizedCleanupBatchSize() {
        return Math.max(20, Math.min(historyCleanupBatchSize, 1000));
    }

    private String historyExecutionLabel(Integer status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case 1 -> "成功";
            case 0 -> "失败";
            case 2 -> "取消";
            default -> String.valueOf(status);
        };
    }

    private void recordHistoryAdminAudit(String actionType, Long historyId, Long relatedHistoryId,
                                         String targetUserId, String reason, Map<String, Object> payload) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO is_chat_history_admin_audit(
                        action_type, history_id, related_history_id, operator_user_id, operator_role,
                        target_user_id, action_reason, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    safeText(actionType, 32),
                    historyId,
                    relatedHistoryId,
                    safeText(resolveUserIdOrDefault("SYSTEM"), 64),
                    safeText(resolveRoleOrDefault("SYSTEM"), 32),
                    safeText(targetUserId, 64),
                    safeText(reason, 255),
                    toJson(payload == null ? Map.of() : payload)
            );
        } catch (Exception ignored) {
            // 治理留痕失败不应影响主流程
        }
    }

    private void recordSystemHistoryAudit(String actionType, Long historyId, Long relatedHistoryId,
                                          String targetUserId, String reason, Map<String, Object> payload) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO is_chat_history_admin_audit(
                        action_type, history_id, related_history_id, operator_user_id, operator_role,
                        target_user_id, action_reason, payload_json
                    ) VALUES (?, ?, ?, 'SYSTEM', 'SYSTEM', ?, ?, ?)
                    """,
                    safeText(actionType, 32),
                    historyId,
                    relatedHistoryId,
                    safeText(targetUserId, 64),
                    safeText(reason, 255),
                    toJson(payload == null ? Map.of() : payload)
            );
        } catch (Exception ignored) {
            // 系统清理留痕失败不应影响主流程
        }
    }

    private String normalizeDeleteReason(String reason, String fallback) {
        String text = Objects.toString(reason, "").trim();
        if (text.isBlank()) {
            return fallback;
        }
        return safeText(text, 255);
    }

    private StringBuilder buildHistoryWhereSql(String userId, String keyword, String tableName, String chartType,
                                               String riskLevel, Integer executionStatus,
                                               String dateFrom, String dateTo, List<Object> args) {
        StringBuilder sql = new StringBuilder("""
                FROM is_chat_query_history
                WHERE user_id = ? AND is_deleted = 0
                """);
        args.add(userId);
        if (!keyword.isBlank()) {
            sql.append("""
                     AND (
                         query_text LIKE ?
                         OR generated_sql LIKE ?
                         OR query_table_name LIKE ?
                         OR chart_snapshot LIKE ?
                     )
                    """);
            String likeText = "%" + keyword + "%";
            args.add(likeText);
            args.add(likeText);
            args.add(likeText);
            args.add(likeText);
        }
        if (!tableName.isBlank()) {
            sql.append(" AND query_table_name = ?");
            args.add(tableName);
        }
        if (!chartType.isBlank()) {
            sql.append(" AND chart_type = ?");
            args.add(chartType);
        }
        if (!riskLevel.isBlank()) {
            sql.append(" AND risk_level = ?");
            args.add(riskLevel);
        }
        if (executionStatus != null) {
            sql.append(" AND execution_status = ?");
            args.add(executionStatus);
        }
        if (dateFrom != null) {
            sql.append(" AND created_at >= ?");
            args.add(dateFrom);
        }
        if (dateTo != null) {
            sql.append(" AND created_at <= ?");
            args.add(dateTo);
        }
        return sql;
    }

    private Integer normalizeExecutionStatus(String executionStatus) {
        String text = Objects.toString(executionStatus, "").trim().toUpperCase();
        if (text.isBlank() || "ALL".equals(text)) {
            return null;
        }
        return switch (text) {
            case "1", "SUCCESS", "SUCCEEDED" -> 1;
            case "0", "FAIL", "FAILED", "ERROR" -> 0;
            case "2", "CANCEL", "CANCELLED", "CANCELED" -> 2;
            default -> null;
        };
    }

    private String normalizeSortDirection(String sortDirection) {
        String text = Objects.toString(sortDirection, "").trim().toUpperCase();
        return "ASC".equals(text) ? "ASC" : "DESC";
    }

    private String normalizeHistoryRiskLevel(String riskLevel) {
        String text = Objects.toString(riskLevel, "").trim().toUpperCase();
        if (text.isBlank() || "ALL".equals(text)) {
            return "";
        }
        if ("BLOCK".equals(text)) {
            return "BLOCKED";
        }
        if ("BLOCKED".equals(text) || "WARN".equals(text) || "SAFE".equals(text)) {
            return text;
        }
        return "";
    }

    private String normalizeHistoryDateBoundary(String value, boolean endOfDay) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return text + (endOfDay ? " 23:59:59" : " 00:00:00");
        }
        String normalized = text.replace('T', ' ');
        if (normalized.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")) {
            return normalized.substring(0, 19);
        }
        if (normalized.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}.*")) {
            return normalized.substring(0, 16) + (endOfDay ? ":59" : ":00");
        }
        if (normalized.length() >= 10 && normalized.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")) {
            return normalized.substring(0, 10) + (endOfDay ? " 23:59:59" : " 00:00:00");
        }
        return null;
    }

    private StringBuilder buildHistoryWhereSql(String userId, String keyword, List<Object> args) {
        return buildHistoryWhereSql(userId, keyword, "", "", "", null, null, null, args);
    }

    private Map<String, Object> buildHistoryPageResponse(int page, int pageSize, String keyword, long total,
                                                         List<Map<String, Object>> items) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("total", total);
        response.put("keyword", keyword == null ? "" : keyword);
        response.put("items", items);
        return response;
    }

    private String normalizeKeyword(String keyword) {
        return Objects.toString(keyword, "").trim();
    }

    private String resolveTableName(String inputTableName, Map<String, Object> result) {
        String resultTableName = Objects.toString(result.getOrDefault("tableName", ""), "").trim();
        if (!resultTableName.isBlank()) {
            return resultTableName;
        }
        return Objects.toString(inputTableName, "").trim();
    }

    private String resolveModelName(Map<String, Object> result) {
        for (String key : List.of("llmModelUsed", "modelName", "model", "modelId")) {
            String model = Objects.toString(result.getOrDefault(key, ""), "").trim();
            if (!model.isBlank()) {
                return model;
            }
        }
        String engine = Objects.toString(result.getOrDefault("engine", "unknown"), "unknown").trim();
        if ("java-fallback".equalsIgnoreCase(engine)) {
            return "RULE_BASED";
        }
        return engine.isBlank() ? "unknown" : engine;
    }

    private boolean resolveCacheHit(Map<String, Object> result) {
        Object value = result == null ? null : result.get("cacheHit");
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = Objects.toString(value, "").trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    private String resolveRiskLevelFromError(String errorMessage) {
        String text = Objects.toString(errorMessage, "").toLowerCase();
        if (text.contains("未通过")
                || text.contains("拦截")
                || text.contains("unauthorized")
                || text.contains("未授权")
                || text.contains("forbidden")) {
            return "BLOCKED";
        }
        return "WARN";
    }

    private String normalizeRiskLevel(String riskLevel) {
        String text = Objects.toString(riskLevel, "SAFE").trim().toUpperCase();
        if ("BLOCK".equals(text)) {
            return "BLOCKED";
        }
        if ("BLOCKED".equals(text) || "WARN".equals(text) || "SAFE".equals(text)) {
            return text;
        }
        return "SAFE";
    }

    private long resolveDatasourceId(String tableName) {
        String text = tableName == null ? "" : tableName.trim();
        if (!text.startsWith("official:")) {
            return 0L;
        }
        String[] parts = text.split(":", 3);
        if (parts.length < 2) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Map<String, Object> parseJsonMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> parseJsonStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = Objects.toString(item, "").trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        }
        try {
            List<Object> raw = objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
            List<String> result = new ArrayList<>();
            for (Object item : raw) {
                String text = Objects.toString(item, "").trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        } catch (Exception ignored) {
            String text = Objects.toString(value, "").trim();
            return text.isBlank() ? List.of() : List.of(text);
        }
    }

    private List<Map<String, Object>> parseJsonStepList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            int index = 1;
            for (Object item : list) {
                Map<String, Object> step = normalizeStepItem(item, index++);
                if (!step.isEmpty()) {
                    result.add(step);
                }
            }
            return result;
        }
        try {
            List<Object> raw = objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
            List<Map<String, Object>> result = new ArrayList<>();
            int index = 1;
            for (Object item : raw) {
                Map<String, Object> step = normalizeStepItem(item, index++);
                if (!step.isEmpty()) {
                    result.add(step);
                }
            }
            return result;
        } catch (Exception ignored) {
            String text = Objects.toString(value, "").trim();
            if (text.isBlank()) {
                return List.of();
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("title", "步骤 1");
            step.put("detail", text);
            return List.of(step);
        }
    }

    private Map<String, Object> normalizeStepItem(Object item, int index) {
        Map<String, Object> step = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> map) {
            String title = Objects.toString(map.get("title"), "").trim();
            String detail = Objects.toString(map.get("detail"), "").trim();
            if (title.isBlank() && detail.isBlank()) {
                String text = Objects.toString(map.get("text"), "").trim();
                if (!text.isBlank()) {
                    detail = text;
                } else {
                    text = Objects.toString(map.get("message"), "").trim();
                    if (!text.isBlank()) {
                        detail = text;
                    }
                }
            }
            if (title.isBlank()) {
                title = "步骤 " + index;
            }
            if (!title.isBlank()) {
                step.put("title", title);
            }
            if (!detail.isBlank()) {
                step.put("detail", detail);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey(), "").trim();
                if (key.isBlank() || "title".equals(key) || "detail".equals(key) || "text".equals(key) || "message".equals(key)) {
                    continue;
                }
                step.put(key, entry.getValue());
            }
            return step;
        }
        String text = Objects.toString(item, "").trim();
        if (text.isBlank()) {
            return Map.of();
        }
        step.put("title", "步骤 " + index);
        step.put("detail", text);
        return step;
    }

    private List<String> compactReasoningSteps(List<Map<String, Object>> steps) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            String title = Objects.toString(step.get("title"), "").trim();
            String detail = Objects.toString(step.get("detail"), "").trim();
            String text = title;
            if (!detail.isBlank()) {
                text = text.isBlank() ? detail : title + "：" + detail;
            }
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = Objects.toString(value, "0").trim();
        if (text.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Integer toNullableInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(text)) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean toBooleanFlag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = Objects.toString(value, "").trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1L : 0L;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String nullIfBlank(String value) {
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? null : text;
    }

    private String resolveUserId() {
        try {
            return AuthContext.userId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveUserIdOrDefault(String fallback) {
        String userId = resolveUserId();
        return userId == null || userId.isBlank() ? fallback : userId;
    }

    private String resolveRoleOrDefault(String fallback) {
        try {
            String role = AuthContext.role();
            return role == null || role.isBlank() ? fallback : role;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + definition);
        }
    }

    private void addIndexIfMissing(String tableName, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Integer.class, tableName, indexName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }

    private static final class RouteAuditGroup {
        private final String key;
        private long count;
        private long successCount;
        private long failureCount;
        private long fallbackCount;
        private long clarificationCount;
        private long durationTotal;
        private long durationCount;
        private double confidenceTotal;
        private long confidenceCount;

        private RouteAuditGroup(String key) {
            this.key = key;
        }

        private void accept(boolean success, boolean fallbackUsed, boolean clarification,
                            boolean failed, long durationMs, double confidence) {
            count++;
            if (success) {
                successCount++;
            }
            if (failed) {
                failureCount++;
            }
            if (fallbackUsed) {
                fallbackCount++;
            }
            if (clarification) {
                clarificationCount++;
            }
            if (durationMs > 0) {
                durationTotal += durationMs;
                durationCount++;
            }
            if (Double.isFinite(confidence) && confidence > 0D) {
                confidenceTotal += confidence;
                confidenceCount++;
            }
        }
    }
}
