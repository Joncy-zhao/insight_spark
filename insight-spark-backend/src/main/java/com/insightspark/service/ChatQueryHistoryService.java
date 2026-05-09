package com.insightspark.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ChatQueryHistoryService {

    private static final int MAX_QUERY_TEXT_LENGTH = 4000;
    private static final int MAX_AUDIT_INFO_LENGTH = 4000;
    private static final int MAX_MODEL_NAME_LENGTH = 50;
    private static final int MAX_CHART_TYPE_LENGTH = 50;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        addColumnIfMissing("is_chat_query_history", "query_table_name", "`query_table_name` VARCHAR(128) NULL");
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
                ps.setString(i++, toJson(result.getOrDefault("reasoningLogs", List.of())));
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
                ps.setInt(i++, 0);
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
        return jdbcTemplate.queryForList(sql, args.toArray());
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
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 50));
        String userId = resolveUserId();
        if (userId == null) {
            return buildHistoryPageResponse(safePage, safePageSize, keyword, 0L, List.of());
        }

        String text = normalizeKeyword(keyword);
        List<Object> whereArgs = new ArrayList<>();
        StringBuilder whereSql = buildHistoryWhereSql(userId, text, whereArgs);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + whereSql, Long.class, whereArgs.toArray());
        long safeTotal = total == null ? 0L : total;
        int offset = (safePage - 1) * safePageSize;

        List<Object> queryArgs = new ArrayList<>(whereArgs);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, data_source_id AS dataSourceId, query_table_name AS queryTableName,
                       query_text AS queryText, generated_sql AS generatedSql, llm_model_used AS llmModelUsed,
                       chart_type AS chartType, chart_snapshot AS chartSnapshot, execution_status AS executionStatus,
                       risk_level AS riskLevel, audit_info AS auditInfo, execution_time_ms AS executionTimeMs,
                       is_hit_cache AS isHitCache, created_at AS createdAt
                """ + whereSql + """
                 ORDER BY created_at DESC
                 LIMIT ? OFFSET ?
                """, queryArgs.toArray());

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
                SET is_deleted = 1
                WHERE id = ? AND user_id = ?
                """, historyId, userId);
    }

    private Map<String, Object> mapHistoryRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        Map<String, Object> snapshot = parseJsonMap(row.get("chartSnapshot"));
        String queryTableName = Objects.toString(row.getOrDefault("queryTableName", ""), "").trim();
        if (queryTableName.isBlank()) {
            queryTableName = Objects.toString(snapshot.getOrDefault("tableName", ""), "");
            item.put("queryTableName", queryTableName);
        }
        item.put("tableName", queryTableName);
        item.put("question", row.get("queryText"));
        item.put("executionStatus", toInt(row.get("executionStatus")));
        item.put("isHitCache", toInt(row.get("isHitCache")));
        item.put("sourceType", toInt(row.get("dataSourceId")) > 0 ? "OFFICIAL" : "UPLOAD");
        return item;
    }

    private Map<String, Object> buildChartSnapshot(String tableName, Map<String, Object> result) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tableName", tableName);
        snapshot.put("chartType", result.get("chartType"));
        snapshot.put("fieldMapping", result.get("fieldMapping"));
        snapshot.put("message", result.get("message"));
        snapshot.put("sql", result.get("sql"));
        snapshot.put("data", result.get("data"));
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
        return snapshot;
    }

    private StringBuilder buildHistoryWhereSql(String userId, String keyword, List<Object> args) {
        StringBuilder sql = new StringBuilder("""
                FROM is_chat_query_history
                WHERE user_id = ? AND is_deleted = 0
                """);
        args.add(userId);
        if (!keyword.isBlank()) {
            sql.append(" AND query_text LIKE ?");
            args.add("%" + keyword + "%");
        }
        return sql;
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
        String model = Objects.toString(result.getOrDefault("llmModelUsed", ""), "").trim();
        if (!model.isBlank()) {
            return model;
        }
        String engine = Objects.toString(result.getOrDefault("engine", "unknown"), "unknown").trim();
        if ("python-ai-service".equalsIgnoreCase(engine)) {
            return "GPT-4";
        }
        if ("java-fallback".equalsIgnoreCase(engine)) {
            return "RULE_BASED";
        }
        return engine.isBlank() ? "unknown" : engine;
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

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + definition);
        }
    }
}
