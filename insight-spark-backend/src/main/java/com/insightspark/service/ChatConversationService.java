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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ChatConversationService {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_MESSAGE_LENGTH = 8000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PythonAiService pythonAiService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initConversationTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_chat_conversation` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL,
                  `title` VARCHAR(255) NOT NULL,
                  `data_source_id` BIGINT NOT NULL DEFAULT 0,
                  `scope_json` JSON NULL,
                  `business_model_id` BIGINT NULL,
                  `summary` TEXT NULL,
                  `last_turn_id` BIGINT NULL,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_chat_conv_user_time` (`user_id`, `updated_at`),
                  INDEX `idx_chat_conv_status` (`status`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_chat_conversation_turn` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `conversation_id` BIGINT NOT NULL,
                  `parent_turn_id` BIGINT NULL,
                  `turn_no` INT NOT NULL,
                  `role` VARCHAR(16) NOT NULL,
                  `message_text` TEXT NOT NULL,
                  `intent_type` VARCHAR(64) NULL,
                  `context_json` JSON NULL,
                  `followup_mode` VARCHAR(32) NOT NULL DEFAULT 'NEW',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_chat_turn_conversation` (`conversation_id`, `turn_no`),
                  INDEX `idx_chat_turn_parent` (`parent_turn_id`),
                  INDEX `idx_chat_turn_role` (`role`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话轮次';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_chat_conversation_artifact` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `conversation_id` BIGINT NOT NULL,
                  `turn_id` BIGINT NOT NULL,
                  `history_id` BIGINT NULL,
                  `artifact_type` VARCHAR(32) NOT NULL,
                  `artifact_json` JSON NULL,
                  `sql_text` TEXT NULL,
                  `chart_type` VARCHAR(50) NULL,
                  `risk_level` VARCHAR(20) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_chat_artifact_conversation` (`conversation_id`, `created_at`),
                  INDEX `idx_chat_artifact_turn` (`turn_id`),
                  INDEX `idx_chat_artifact_history` (`history_id`),
                  INDEX `idx_chat_artifact_type` (`artifact_type`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话产物';
                """);
    }

    public Map<String, Object> createConversation(Map<String, Object> request) {
        String userId = resolveUserId();
        if (userId == null) {
            return Map.of();
        }
        String rawTitle = Objects.toString(request == null ? null : request.get("title"), "").trim();
        String tableName = Objects.toString(request == null ? null : request.get("tableName"), "").trim();
        String title = rawTitle.isBlank() ? "新对话" : safeText(rawTitle, MAX_TITLE_LENGTH);
        Map<String, Object> scope = new LinkedHashMap<>();
        if (!tableName.isBlank()) {
            scope.put("tableName", tableName);
        }
        Number businessModelId = parseNumber(request == null ? null : request.get("businessModelId"));
        Long id = insertConversation(userId, title, resolveDatasourceId(tableName), scope, businessModelId);
        return id == null ? Map.of() : getConversation(id);
    }

    public Long ensureConversation(Long conversationId, String question, String tableName) {
        String userId = resolveUserId();
        if (userId == null) {
            return null;
        }
        if (conversationId != null && isConversationOwnedByUser(conversationId, userId)) {
            return conversationId;
        }
        String title = titleFromQuestion(question);
        Map<String, Object> scope = new LinkedHashMap<>();
        if (tableName != null && !tableName.isBlank()) {
            scope.put("tableName", tableName);
        }
        return insertConversation(userId, title, resolveDatasourceId(tableName), scope, null);
    }

    public Map<String, Object> getConversation(Long conversationId) {
        String userId = resolveUserId();
        if (userId == null || conversationId == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.id, c.user_id AS userId, c.title, c.data_source_id AS dataSourceId, c.scope_json AS scopeJson,
                       c.business_model_id AS businessModelId, c.summary, c.last_turn_id AS lastTurnId,
                       c.status, c.created_at AS createdAt, c.updated_at AS updatedAt,
                       (SELECT COUNT(*) FROM is_chat_conversation_turn t
                         WHERE t.conversation_id = c.id AND t.role = 'USER') AS turnCount
                  FROM is_chat_conversation c
                 WHERE c.id = ? AND c.user_id = ? AND c.is_deleted = 0
                 LIMIT 1
                """, conversationId, userId);
        return rows.isEmpty() ? Map.of() : mapConversationRow(rows.get(0));
    }

    public Map<String, Object> listConversations(int page, int pageSize, String keyword) {
        return listConversations(page, pageSize, keyword, null);
    }

    public Map<String, Object> listConversations(int page, int pageSize, String keyword, String status) {
        String userId = resolveUserId();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 50));
        if (userId == null) {
            return buildPage(safePage, safePageSize, keyword, 0L, List.of());
        }
        String text = Objects.toString(keyword, "").trim();
        String normalizedStatus = normalizeConversationStatus(status);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                FROM is_chat_conversation c
                WHERE c.user_id = ? AND c.is_deleted = 0
                """);
        args.add(userId);
        if (!normalizedStatus.isBlank() && !"ALL".equals(normalizedStatus)) {
            where.append(" AND c.status = ?");
            args.add(normalizedStatus);
        }
        if (!text.isBlank()) {
            where.append(" AND (c.title LIKE ? OR c.summary LIKE ?)");
            args.add("%" + text + "%");
            args.add("%" + text + "%");
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add((safePage - 1) * safePageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.id, c.user_id AS userId, c.title, c.data_source_id AS dataSourceId,
                       c.scope_json AS scopeJson, c.business_model_id AS businessModelId,
                       c.summary, c.last_turn_id AS lastTurnId, c.status,
                       c.created_at AS createdAt, c.updated_at AS updatedAt,
                       (SELECT COUNT(*) FROM is_chat_conversation_turn t
                         WHERE t.conversation_id = c.id AND t.role = 'USER') AS turnCount
                """ + where + """
                 ORDER BY c.updated_at DESC, c.id DESC
                 LIMIT ? OFFSET ?
                """, queryArgs.toArray());
        List<Map<String, Object>> items = rows.stream().map(this::mapConversationRow).toList();
        return buildPage(safePage, safePageSize, text, total == null ? 0L : total, items);
    }

    public List<Map<String, Object>> listTurns(Long conversationId) {
        String userId = resolveUserId();
        if (userId == null || conversationId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return List.of();
        }
        List<Map<String, Object>> turns = jdbcTemplate.queryForList("""
                SELECT id, conversation_id AS conversationId, parent_turn_id AS parentTurnId, turn_no AS turnNo,
                       role, message_text AS messageText, intent_type AS intentType, context_json AS contextJson,
                       followup_mode AS followupMode, created_at AS createdAt
                  FROM is_chat_conversation_turn
                 WHERE conversation_id = ?
                 ORDER BY turn_no ASC, id ASC
                """, conversationId);
        if (turns.isEmpty()) {
            return List.of();
        }
        List<Long> turnIds = turns.stream()
                .map(row -> toLong(row.get("id")))
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<Map<String, Object>>> artifacts = artifactsByTurn(turnIds);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : turns) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            Long turnId = toLong(row.get("id"));
            item.put("context", parseJsonMap(row.get("contextJson")));
            item.put("artifacts", artifacts.getOrDefault(turnId, List.of()));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> refreshConversationSummary(Long conversationId, String summaryOverride) {
        String userId = resolveUserId();
        if (userId == null || conversationId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of();
        }
        String summary = Objects.toString(summaryOverride, "").trim();
        if (summary.isBlank()) {
            summary = buildConversationSummary(conversationId);
        }
        if (!summary.isBlank()) {
            jdbcTemplate.update("""
                    UPDATE is_chat_conversation
                       SET summary = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND user_id = ? AND is_deleted = 0
                    """, safeText(summary, MAX_SUMMARY_LENGTH), conversationId, userId);
        }
        return getConversation(conversationId);
    }

    public Map<String, Object> renameConversation(Long conversationId, String title) {
        String userId = resolveUserId();
        if (userId == null || conversationId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of();
        }
        String normalizedTitle = Objects.toString(title, "")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalizedTitle.isBlank()) {
            throw new IllegalArgumentException("会话标题不能为空");
        }
        jdbcTemplate.update("""
                UPDATE is_chat_conversation
                   SET title = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND user_id = ? AND is_deleted = 0
                """, safeText(normalizedTitle, MAX_TITLE_LENGTH), conversationId, userId);
        return getConversation(conversationId);
    }

    public Map<String, Object> updateConversationStatus(Long conversationId, String status) {
        String userId = resolveUserId();
        if (userId == null || conversationId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of();
        }
        String normalizedStatus = normalizeConversationStatus(status);
        if (!"ACTIVE".equals(normalizedStatus) && !"ARCHIVED".equals(normalizedStatus)) {
            throw new IllegalArgumentException("会话状态仅支持 ACTIVE 或 ARCHIVED");
        }
        jdbcTemplate.update("""
                UPDATE is_chat_conversation
                   SET status = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND user_id = ? AND is_deleted = 0
                """, normalizedStatus, conversationId, userId);
        return getConversation(conversationId);
    }

    public void deleteConversation(Long conversationId) {
        String userId = resolveUserId();
        if (userId == null || conversationId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE is_chat_conversation
                   SET is_deleted = 1,
                       status = 'DELETED',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND user_id = ? AND is_deleted = 0
                """, conversationId, userId);
    }

    public Map<String, Object> recordUserTurn(Long conversationId, Long parentTurnId, String question,
                                              String tableName, Map<String, Object> context) {
        if (conversationId == null) {
            return Map.of();
        }
        String userId = resolveUserId();
        if (userId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of();
        }
        int turnNo = nextTurnNo(conversationId);
        String intentType = inferIntentType(question);
        String followupMode = isFollowupQuestion(question) ? "FOLLOWUP" : "NEW";
        Map<String, Object> safeContext = new LinkedHashMap<>();
        if (context != null) {
            safeContext.putAll(context);
        }
        if (tableName != null && !tableName.isBlank()) {
            safeContext.put("tableName", tableName);
        }
        Long turnId = insertTurn(conversationId, parentTurnId, turnNo, "USER", question, intentType,
                safeContext, followupMode);
        updateConversationAfterTurn(conversationId, turnId, question, null);
        maybeRenameConversation(conversationId, turnNo, question, tableName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", turnId);
        result.put("turnNo", turnNo);
        result.put("intentType", intentType);
        result.put("followupMode", followupMode);
        return result;
    }

    public Map<String, Object> recordAssistantResult(Long conversationId, Long parentTurnId, String question,
                                                     Map<String, Object> result, Long historyId) {
        if (conversationId == null) {
            return Map.of();
        }
        String userId = resolveUserId();
        if (userId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of();
        }
        int turnNo = nextTurnNo(conversationId);
        String message = Objects.toString(result == null ? null : result.get("message"), "分析完成").trim();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("historyId", historyId);
        context.put("question", question);
        context.put("tableName", result == null ? null : result.get("tableName"));
        context.put("engine", result == null ? null : result.get("engine"));
        Long turnId = insertTurn(conversationId, parentTurnId, turnNo, "ASSISTANT", message,
                "ANSWER", context, "REPLY");
        List<Long> artifactIds = new ArrayList<>();
        if (result != null) {
            String sql = Objects.toString(result.get("sql"), "").trim();
            if (!sql.isBlank()) {
                Map<String, Object> sqlArtifact = new LinkedHashMap<>();
                sqlArtifact.put("question", Objects.toString(question, ""));
                sqlArtifact.put("tableName", result.get("tableName"));
                artifactIds.add(insertArtifact(conversationId, turnId, historyId, "SQL",
                        sqlArtifact,
                        sql,
                        Objects.toString(result.getOrDefault("chartType", ""), ""),
                        Objects.toString(result.getOrDefault("riskLevel", "SAFE"), "SAFE")));
            }
            artifactIds.add(insertArtifact(conversationId, turnId, historyId, "CHART",
                    buildChartArtifact(result, historyId),
                    sql,
                    Objects.toString(result.getOrDefault("chartType", ""), ""),
                    Objects.toString(result.getOrDefault("riskLevel", "SAFE"), "SAFE")));
        }
        updateConversationAfterTurn(conversationId, turnId, question, message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", turnId);
        payload.put("turnNo", turnNo);
        payload.put("artifactIds", artifactIds.stream().filter(Objects::nonNull).toList());
        if (!artifactIds.isEmpty()) {
            payload.put("artifactId", artifactIds.get(artifactIds.size() - 1));
        }
        return payload;
    }

    public Map<String, Object> recordAssistantFailure(Long conversationId, Long parentTurnId, String question,
                                                      String message) {
        if (conversationId == null) {
            return Map.of();
        }
        String userId = resolveUserId();
        if (userId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of();
        }
        int turnNo = nextTurnNo(conversationId);
        Map<String, Object> context = Map.of("question", Objects.toString(question, ""));
        Long turnId = insertTurn(conversationId, parentTurnId, turnNo, "ASSISTANT",
                Objects.toString(message, "分析失败"), "ERROR", context, "REPLY");
        updateConversationAfterTurn(conversationId, turnId, question, message);
        return Map.of("id", turnId, "turnNo", turnNo);
    }

    public String buildExecutionQuestion(Long conversationId, Long currentUserTurnId, String question) {
        String raw = Objects.toString(question, "").trim();
        if (conversationId == null || raw.isBlank()) {
            return raw;
        }
        String userId = resolveUserId();
        if (userId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return raw;
        }
        Long branchParentTurnId = findParentTurnId(conversationId, currentUserTurnId);
        boolean explicitBranch = branchParentTurnId != null;
        if (!explicitBranch && !isFollowupQuestion(raw)) {
            return raw;
        }
        List<Map<String, Object>> rows = explicitBranch
                ? loadBranchContextTurns(conversationId, branchParentTurnId, 6)
                : jdbcTemplate.queryForList("""
                SELECT role, message_text AS messageText
                  FROM is_chat_conversation_turn
                 WHERE conversation_id = ? AND (? IS NULL OR id <> ?)
                 ORDER BY turn_no DESC, id DESC
                 LIMIT 6
                """, conversationId, currentUserTurnId, currentUserTurnId);
        if (rows.isEmpty()) {
            return raw;
        }
        List<String> contextLines = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            String role = Objects.toString(row.get("role"), "").trim();
            String text = safeText(Objects.toString(row.get("messageText"), "").trim(), 500);
            if (!text.isBlank()) {
                contextLines.add(role + ": " + text);
            }
        }
        if (contextLines.isEmpty()) {
            return raw;
        }
        return """
                已有对话上下文：
                %s

                本轮用户追问：
                %s

                请在上下文一致时继承前文的指标、维度、时间范围和筛选条件；如果本轮明确指定了新范围，以本轮为准。
                """.formatted(String.join("\n", contextLines), raw);
    }

    private Long findParentTurnId(Long conversationId, Long turnId) {
        if (conversationId == null || turnId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT parent_turn_id AS parentTurnId
                  FROM is_chat_conversation_turn
                 WHERE conversation_id = ? AND id = ?
                 LIMIT 1
                """, conversationId, turnId);
        if (rows.isEmpty()) {
            return null;
        }
        return toLong(rows.get(0).get("parentTurnId"));
    }

    private List<Map<String, Object>> loadBranchContextTurns(Long conversationId, Long parentTurnId, int limit) {
        if (conversationId == null || parentTurnId == null || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Long> visited = new ArrayList<>();
        Long currentTurnId = parentTurnId;
        while (currentTurnId != null && rows.size() < limit) {
            if (visited.contains(currentTurnId)) {
                break;
            }
            visited.add(currentTurnId);
            List<Map<String, Object>> found = jdbcTemplate.queryForList("""
                    SELECT id, parent_turn_id AS parentTurnId, role, message_text AS messageText
                      FROM is_chat_conversation_turn
                     WHERE conversation_id = ? AND id = ?
                     LIMIT 1
                    """, conversationId, currentTurnId);
            if (found.isEmpty()) {
                break;
            }
            Map<String, Object> row = found.get(0);
            rows.add(row);
            currentTurnId = toLong(row.get("parentTurnId"));
        }
        return rows;
    }

    private Long insertConversation(String userId, String title, long datasourceId, Map<String, Object> scope,
                                    Number businessModelId) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO is_chat_conversation(user_id, title, data_source_id, scope_json, business_model_id, status, is_deleted)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, userId);
                ps.setString(2, safeText(title, MAX_TITLE_LENGTH));
                ps.setLong(3, datasourceId);
                ps.setString(4, toJson(scope == null ? Map.of() : scope));
                if (businessModelId == null) {
                    ps.setNull(5, Types.BIGINT);
                } else {
                    ps.setLong(5, businessModelId.longValue());
                }
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long insertTurn(Long conversationId, Long parentTurnId, int turnNo, String role, String message,
                            String intentType, Map<String, Object> context, String followupMode) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO is_chat_conversation_turn(
                            conversation_id, parent_turn_id, turn_no, role, message_text,
                            intent_type, context_json, followup_mode
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, conversationId);
                if (parentTurnId == null) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, parentTurnId);
                }
                ps.setInt(3, turnNo);
                ps.setString(4, role);
                ps.setString(5, safeText(message, MAX_MESSAGE_LENGTH));
                ps.setString(6, intentType);
                ps.setString(7, toJson(context == null ? Map.of() : context));
                ps.setString(8, followupMode == null ? "NEW" : followupMode);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long insertArtifact(Long conversationId, Long turnId, Long historyId, String artifactType,
                                Map<String, Object> artifactJson, String sqlText, String chartType, String riskLevel) {
        if (turnId == null) {
            return null;
        }
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO is_chat_conversation_artifact(
                            conversation_id, turn_id, history_id, artifact_type, artifact_json,
                            sql_text, chart_type, risk_level
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, conversationId);
                ps.setLong(2, turnId);
                if (historyId == null) {
                    ps.setNull(3, Types.BIGINT);
                } else {
                    ps.setLong(3, historyId);
                }
                ps.setString(4, artifactType);
                ps.setString(5, toJson(artifactJson == null ? Map.of() : artifactJson));
                if (sqlText == null || sqlText.isBlank()) {
                    ps.setNull(6, Types.LONGVARCHAR);
                } else {
                    ps.setString(6, sqlText);
                }
                ps.setString(7, safeText(chartType, 50));
                ps.setString(8, safeText(riskLevel, 20));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void updateConversationAfterTurn(Long conversationId, Long lastTurnId, String question, String answer) {
        if (conversationId == null) {
            return;
        }
        String summary = buildSummary(question, answer);
        jdbcTemplate.update("""
                UPDATE is_chat_conversation
                   SET last_turn_id = COALESCE(?, last_turn_id),
                       status = 'ACTIVE',
                       summary = CASE WHEN ? IS NULL OR ? = '' THEN summary ELSE ? END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND is_deleted = 0
                """, lastTurnId, summary, summary, safeText(summary, MAX_SUMMARY_LENGTH), conversationId);
    }

    private void maybeRenameConversation(Long conversationId, int turnNo, String question, String tableName) {
        if (conversationId == null || turnNo != 1) {
            return;
        }
        String currentTitle = conversationTitle(conversationId);
        if (!shouldRefreshTitle(currentTitle)) {
            return;
        }
        String nextTitle = suggestConversationTitle(question, tableName);
        if (nextTitle.isBlank()) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE is_chat_conversation
                   SET title = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND is_deleted = 0
                """, safeText(nextTitle, MAX_TITLE_LENGTH), conversationId);
    }

    private Map<Long, List<Map<String, Object>>> artifactsByTurn(List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return Map.of();
        }
        String in = turnIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, conversation_id AS conversationId, turn_id AS turnId, history_id AS historyId,
                       artifact_type AS artifactType, artifact_json AS artifactJson, sql_text AS sqlText,
                       chart_type AS chartType, risk_level AS riskLevel, created_at AS createdAt
                  FROM is_chat_conversation_artifact
                 WHERE turn_id IN (""" + in + """
                 )
                 ORDER BY id ASC
                """, turnIds.toArray());
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("artifact", parseJsonMap(row.get("artifactJson")));
            Long turnId = toLong(row.get("turnId"));
            grouped.computeIfAbsent(turnId, ignored -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    public Map<String, Object> getArtifactForCurrentUser(Long artifactId) {
        String userId = resolveUserId();
        if (artifactId == null || userId == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT a.id, a.conversation_id AS conversationId, a.turn_id AS turnId, a.history_id AS historyId,
                       a.artifact_type AS artifactType, a.artifact_json AS artifactJson,
                       a.sql_text AS sqlText, a.chart_type AS chartType, a.risk_level AS riskLevel,
                       a.created_at AS createdAt
                  FROM is_chat_conversation_artifact a
                  INNER JOIN is_chat_conversation c ON c.id = a.conversation_id
                 WHERE a.id = ? AND c.user_id = ? AND c.is_deleted = 0
                 LIMIT 1
                """, artifactId, userId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("artifact", parseJsonMap(row.get("artifactJson")));
        return row;
    }

    public Map<String, Object> latestChartArtifactForTurn(Long turnId) {
        String userId = resolveUserId();
        if (turnId == null || userId == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT a.id, a.conversation_id AS conversationId, a.turn_id AS turnId, a.history_id AS historyId,
                       a.artifact_type AS artifactType, a.artifact_json AS artifactJson,
                       a.sql_text AS sqlText, a.chart_type AS chartType, a.risk_level AS riskLevel,
                       a.created_at AS createdAt
                  FROM is_chat_conversation_artifact a
                  INNER JOIN is_chat_conversation c ON c.id = a.conversation_id
                 WHERE a.turn_id = ? AND a.artifact_type = 'CHART'
                   AND c.user_id = ? AND c.is_deleted = 0
                 ORDER BY a.id DESC
                 LIMIT 1
                """, turnId, userId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("artifact", parseJsonMap(row.get("artifactJson")));
        return row;
    }

    private Map<String, Object> buildChartArtifact(Map<String, Object> result, Long historyId) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("historyId", historyId);
        artifact.put("tableName", result.get("tableName"));
        artifact.put("chartType", result.get("chartType"));
        artifact.put("fieldMapping", result.get("fieldMapping"));
        artifact.put("message", result.get("message"));
        artifact.put("sql", result.get("sql"));
        artifact.put("data", result.get("data"));
        artifact.put("chartEngine", result.get("chartEngine"));
        artifact.put("dimensions", result.get("dimensions"));
        artifact.put("encode", result.get("encode"));
        artifact.put("optionTemplate", result.get("optionTemplate"));
        return artifact;
    }

    private Map<String, Object> mapConversationRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        item.put("scope", parseJsonMap(row.get("scopeJson")));
        item.put("turnCount", toInt(row.get("turnCount")));
        return item;
    }

    private Map<String, Object> buildPage(int page, int pageSize, String keyword, long total,
                                          List<Map<String, Object>> items) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("keyword", keyword == null ? "" : keyword);
        response.put("total", total);
        response.put("items", items);
        return response;
    }

    private boolean isConversationOwnedByUser(Long conversationId, String userId) {
        if (conversationId == null || userId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_chat_conversation
                 WHERE id = ? AND user_id = ? AND is_deleted = 0
                """, Integer.class, conversationId, userId);
        return count != null && count > 0;
    }

    private String conversationTitle(Long conversationId) {
        if (conversationId == null) {
            return "";
        }
        List<String> rows = jdbcTemplate.query("""
                SELECT title
                  FROM is_chat_conversation
                 WHERE id = ? AND is_deleted = 0
                 LIMIT 1
                """, (rs, rowNum) -> rs.getString("title"), conversationId);
        return rows.isEmpty() ? "" : Objects.toString(rows.get(0), "").trim();
    }

    private String normalizeConversationStatus(String status) {
        String text = Objects.toString(status, "").trim().toUpperCase(Locale.ROOT);
        if ("ACTIVE".equals(text) || "ARCHIVED".equals(text) || "DELETED".equals(text) || "ALL".equals(text)) {
            return text;
        }
        return "";
    }

    private int nextTurnNo(Long conversationId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(turn_no), 0) FROM is_chat_conversation_turn WHERE conversation_id = ?
                """, Integer.class, conversationId);
        return (max == null ? 0 : max) + 1;
    }

    private String inferIntentType(String question) {
        String text = Objects.toString(question, "").toLowerCase(Locale.ROOT);
        if (text.contains("为什么") || text.contains("原因") || text.contains("why")) {
            return "EXPLAIN";
        }
        if (text.contains("对比") || text.contains("同比") || text.contains("环比") || text.contains("compare")) {
            return "COMPARE";
        }
        if (text.contains("明细") || text.contains("详情") || text.contains("下钻") || text.contains("detail")) {
            return "DRILLDOWN";
        }
        if (isFollowupQuestion(text)) {
            return "FOLLOWUP";
        }
        return "QUERY";
    }

    private boolean isFollowupQuestion(String question) {
        String text = Objects.toString(question, "").trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        return text.contains("再")
                || text.contains("继续")
                || text.contains("换成")
                || text.contains("改为")
                || text.contains("只看")
                || text.contains("上面")
                || text.contains("刚才")
                || text.contains("这个")
                || text.contains("前面")
                || text.contains("上一")
                || text.contains("基于")
                || text.contains("在此基础")
                || text.contains("同样")
                || text.contains("看明细")
                || text.contains("展开明细")
                || text.contains("why")
                || text.contains("again");
    }

    private String titleFromQuestion(String question) {
        String text = fallbackConversationTitle(question);
        if (text.isBlank()) {
            return "新对话";
        }
        return safeText(text, MAX_TITLE_LENGTH);
    }

    private String suggestConversationTitle(String question, String tableName) {
        String normalizedQuestion = normalizeTitleSource(question);
        if (!normalizedQuestion.isBlank()) {
            try {
                String aiTitle = pythonAiService.generateConversationTitle(normalizedQuestion, tableName)
                        .map(this::sanitizeGeneratedTitle)
                        .orElse("");
                if (!aiTitle.isBlank()) {
                    return aiTitle;
                }
            } catch (Exception ignored) {
                // Naming is best-effort and must not affect query flow.
            }
        }
        return fallbackConversationTitle(normalizedQuestion);
    }

    private boolean shouldRefreshTitle(String title) {
        String text = Objects.toString(title, "").trim();
        return text.isBlank() || "新对话".equals(text) || text.length() > 24;
    }

    private String fallbackConversationTitle(String question) {
        String normalized = normalizeTitleSource(question);
        if (normalized.isBlank()) {
            return "新对话";
        }
        String compact = normalized
                .replaceAll("[，。！？；：,.!?;:]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (compact.isBlank()) {
            return "新对话";
        }
        String[] markers = {"按", "按月", "按周", "按天", "查看", "统计", "分析", "查询", "看看", "想看", "请问", "帮我", "我想", "给我"};
        for (String marker : markers) {
            if (compact.startsWith(marker) && compact.length() > marker.length()) {
                compact = compact.substring(marker.length()).trim();
                break;
            }
        }
        String[] parts = compact.split("\\s+");
        if (parts.length > 1) {
            String joined = String.join(" ", Arrays.copyOf(parts, Math.min(parts.length, 4)));
            compact = joined.trim();
        }
        if (compact.length() > 18) {
            compact = compact.substring(0, 18).trim();
        }
        return compact.isBlank() ? "新对话" : compact;
    }

    private String sanitizeGeneratedTitle(String title) {
        String text = Objects.toString(title, "").trim();
        if (text.isBlank()) {
            return "";
        }
        text = text.replaceAll("[\\r\\n]+", " ")
                .replaceAll("^[\"'“”‘’【\\[]+", "")
                .replaceAll("[\"'“”‘’】\\]]+$", "")
                .replaceAll("^标题[:：]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() > 18) {
            text = text.substring(0, 18).trim();
        }
        if (text.isBlank() || "新对话".equals(text)) {
            return "";
        }
        return text;
    }

    private String normalizeTitleSource(String question) {
        return Objects.toString(question, "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildSummary(String question, String answer) {
        String q = Objects.toString(question, "").trim();
        String a = Objects.toString(answer, "").trim();
        if (q.isBlank() && a.isBlank()) {
            return "";
        }
        String text = a.isBlank() ? "最近问题：" + q : "最近问题：" + q + "\n最近回答：" + a;
        return safeText(text, MAX_SUMMARY_LENGTH);
    }

    private String buildConversationSummary(Long conversationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT role, message_text AS messageText
                  FROM is_chat_conversation_turn
                 WHERE conversation_id = ?
                 ORDER BY turn_no DESC, id DESC
                 LIMIT 8
                """, conversationId);
        if (rows.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            String role = Objects.toString(row.get("role"), "").trim();
            String message = Objects.toString(row.get("messageText"), "").trim();
            if (message.isBlank()) {
                continue;
            }
            String speaker = "USER".equalsIgnoreCase(role) ? "用户" : "助手";
            lines.add(speaker + ": " + safeText(message, 300));
        }
        return safeText(String.join("\n", lines), MAX_SUMMARY_LENGTH);
    }

    private long resolveDatasourceId(String tableName) {
        String text = Objects.toString(tableName, "").trim();
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

    private Number parseNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveUserId() {
        try {
            return AuthContext.userId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private Map<String, Object> parseJsonMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
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
}
