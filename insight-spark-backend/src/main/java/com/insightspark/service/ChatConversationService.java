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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatConversationService {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_MESSAGE_LENGTH = 8000;
    private static final int MAX_ADVANCED_SERIES_POINTS = 240;
    private static final int MAX_ADVANCED_PARAM_SERIES_POINTS = 120;
    private static final int MAX_ADVANCED_INSIGHTS = 20;
    private static final int MAX_ADVANCED_EXPLANATION_ITEMS = 12;
    private static final int MAX_ADVANCED_THINKING_LOGS = 20;
    private static final int MAX_ADVANCED_TEXT_LENGTH = 1200;
    private static final int MAX_ADVANCED_NESTED_LIST_ITEMS = 40;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private ChatQueryHistoryService chatQueryHistoryService;

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
        return listConversations(page, pageSize, keyword, status, null);
    }

    public Map<String, Object> listConversations(int page, int pageSize, String keyword, String status, String advancedType) {
        String userId = resolveUserId();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 50));
        if (userId == null) {
            return buildPage(safePage, safePageSize, keyword, 0L, List.of());
        }
        String text = Objects.toString(keyword, "").trim();
        String normalizedStatus = normalizeConversationStatus(status);
        String normalizedAdvancedType = normalizeAdvancedSessionType(advancedType);
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
        if (!normalizedAdvancedType.isBlank()) {
            where.append("""
                     AND EXISTS (
                       SELECT 1
                         FROM is_chat_conversation_artifact af
                        WHERE af.conversation_id = c.id
                          AND af.artifact_type = ?
                     )
                    """);
            args.add(normalizedAdvancedType);
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
                         WHERE t.conversation_id = c.id AND t.role = 'USER') AS turnCount,
                       (SELECT GROUP_CONCAT(DISTINCT a.artifact_type ORDER BY a.artifact_type SEPARATOR ',')
                          FROM is_chat_conversation_artifact a
                         WHERE a.conversation_id = c.id
                           AND a.artifact_type LIKE 'ADVANCED_%') AS advancedArtifactTypes,
                       (SELECT a.artifact_type
                          FROM is_chat_conversation_artifact a
                         WHERE a.conversation_id = c.id
                           AND a.artifact_type LIKE 'ADVANCED_%'
                         ORDER BY a.id DESC
                         LIMIT 1) AS latestAdvancedArtifactType
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
        if (result != null) {
            context.put("reasoningReplaySteps", result.getOrDefault("reasoningReplaySteps",
                    result.getOrDefault("reasoningLogs", List.of())));
            attachAlertEventContext(context, result);
        }
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
            if (!Boolean.parseBoolean(Objects.toString(result.getOrDefault("skipChartArtifact", false), "false"))) {
                artifactIds.add(insertArtifact(conversationId, turnId, historyId, "CHART",
                        buildChartArtifact(result, historyId),
                        sql,
                        Objects.toString(result.getOrDefault("chartType", ""), ""),
                        Objects.toString(result.getOrDefault("riskLevel", "SAFE"), "SAFE")));
            }
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

    public Map<String, Object> recordAdvancedAnalysisResult(Map<String, Object> request) {
        String userId = resolveUserId();
        if (userId == null) {
            return Map.of();
        }
        Map<String, Object> analysis = asMap(request == null ? null : request.get("analysis"));
        String question = Objects.toString(request == null ? null : request.get("question"),
                Objects.toString(analysis.get("sourceQuestion"), "")).trim();
        String tableName = Objects.toString(request == null ? null : request.get("tableName"),
                Objects.toString(analysis.get("tableName"), "")).trim();
        Long conversationId = ensureConversation(toLong(request == null ? null : request.get("conversationId")), question, tableName);
        if (conversationId == null || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of();
        }

        String analysisType = normalizeAdvancedAnalysisType(Objects.toString(analysis.get("type"),
                Objects.toString(request == null ? null : request.get("type"), "")));
        String intentType = advancedIntentType(analysisType);
        String artifactType = advancedArtifactType(analysisType);
        String clientMessageId = Objects.toString(request == null ? null : request.get("clientMessageId"), "").trim();
        if (!clientMessageId.isBlank()) {
            Map<String, Object> existing = findExistingAdvancedRecord(conversationId, clientMessageId);
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        Long parentTurnId = toLong(request == null ? null : request.get("parentTurnId"));
        Map<String, Object> llmIntent = asMap(request == null ? null : request.get("llmIntent"));
        List<Object> thinkingLogs = asList(request == null ? null : request.get("thinkingLogs"));
        List<Object> compactThinkingLogs = compactAdvancedThinkingLogs(thinkingLogs);

        Map<String, Object> userContext = new LinkedHashMap<>();
        userContext.put("module", "advancedAnalysis");
        userContext.put("analysisType", analysisType);
        userContext.put("clientMessageId", clientMessageId);
        userContext.put("tableName", tableName);
        userContext.put("llmIntent", compactAdvancedValue(llmIntent, MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
        int userTurnNo = nextTurnNo(conversationId);
        Long userTurnId = insertTurn(conversationId, parentTurnId, userTurnNo, "USER", question, intentType,
                userContext, parentTurnId == null ? "NEW" : "FOLLOWUP");
        updateConversationAfterTurn(conversationId, userTurnId, question, null);
        maybeRenameConversation(conversationId, userTurnNo, question, tableName);

        String message = Objects.toString(request == null ? null : request.get("message"),
                defaultAdvancedAssistantMessage(analysisType, analysis)).trim();
        Map<String, Object> assistantContext = new LinkedHashMap<>();
        assistantContext.put("module", "advancedAnalysis");
        assistantContext.put("analysisType", analysisType);
        assistantContext.put("clientMessageId", clientMessageId);
        assistantContext.put("question", question);
        assistantContext.put("tableName", tableName);
        assistantContext.put("thinkingLogs", compactThinkingLogs);
        assistantContext.put("llmIntent", compactAdvancedValue(llmIntent, MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
        assistantContext.put("planId", nestedValue(analysis, "params", "planId"));
        assistantContext.put("ruleId", nestedValue(analysis, "params", "ruleId"));
        assistantContext.put("eventId", nestedValue(analysis, "params", "eventId"));

        int assistantTurnNo = nextTurnNo(conversationId);
        Long assistantTurnId = insertTurn(conversationId, userTurnId, assistantTurnNo, "ASSISTANT", message,
                intentType, assistantContext, "REPLY");
        Long artifactId = insertArtifact(conversationId, assistantTurnId, null, artifactType,
                buildAdvancedAnalysisArtifact(analysis, request, conversationId, userTurnId, assistantTurnId, clientMessageId, thinkingLogs),
                null,
                advancedChartType(analysisType),
                "alert".equals(analysisType) ? "WARNING" : "SAFE");
        Long historyId = recordAdvancedAnalysisHistory(question, tableName, analysis, request, message, analysisType,
                intentType, artifactType, conversationId, userTurnId, assistantTurnId, assistantTurnNo, artifactId,
                clientMessageId, compactThinkingLogs);
        updateConversationAfterTurn(conversationId, assistantTurnId, question, message);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("userTurnId", userTurnId);
        payload.put("assistantTurnId", assistantTurnId);
        payload.put("artifactId", artifactId);
        payload.put("historyId", historyId);
        payload.put("artifactType", artifactType);
        payload.put("recorded", assistantTurnId != null && artifactId != null);
        return payload;
    }

    private Long recordAdvancedAnalysisHistory(String question,
                                               String tableName,
                                               Map<String, Object> analysis,
                                               Map<String, Object> request,
                                               String message,
                                               String analysisType,
                                               String intentType,
                                               String artifactType,
                                               Long conversationId,
                                               Long userTurnId,
                                               Long assistantTurnId,
                                               Integer assistantTurnNo,
                                               Long artifactId,
                                               String clientMessageId,
                                               List<Object> thinkingLogs) {
        try {
            Map<String, Object> safeAnalysis = analysis == null ? Map.of() : analysis;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", analysisType);
            result.put("advancedAnalysisType", analysisType);
            result.put("chartType", advancedChartType(analysisType));
            result.put("message", message);
            result.put("riskLevel", "alert".equals(analysisType) ? "WARN" : "SAFE");
            result.put("riskReason", message);
            result.put("params", safeAnalysis.getOrDefault("params", Map.of()));
            result.put("fieldMapping", safeAnalysis.getOrDefault("fieldMapping", Map.of()));
            result.put("series", safeAnalysis.getOrDefault("series", List.of()));
            result.put("data", firstPresent(safeAnalysis.get("data"), safeAnalysis.get("series"), List.of()));
            result.put("insights", safeAnalysis.getOrDefault("insights", List.of()));
            result.put("explanation", safeAnalysis.getOrDefault("explanation", Map.of()));
            result.put("optionTemplate", safeAnalysis.get("optionTemplate"));
            result.put("chartRecommendation", safeAnalysis.get("chartRecommendation"));
            result.put("ruleRecommendation", safeAnalysis.get("ruleRecommendation"));
            result.put("alertRuleDraft", safeAnalysis.get("alertRuleDraft"));
            result.put("alertRuleCreated", safeAnalysis.get("alertRuleCreated"));
            result.put("alertMeta", safeAnalysis.get("alertMeta"));
            result.put("ruleId", nestedValue(safeAnalysis, "params", "ruleId"));
            result.put("eventId", nestedValue(safeAnalysis, "params", "eventId"));
            result.put("conversationId", conversationId);
            result.put("userTurnId", userTurnId);
            result.put("assistantTurnId", assistantTurnId);
            result.put("artifactId", artifactId);
            result.put("clientMessageId", clientMessageId);
            result.put("reasoningReplaySteps", thinkingLogs == null ? List.of() : thinkingLogs);

            Map<String, Object> params = asMap(safeAnalysis.get("params"));
            putIfPresent(result, "metricField", firstNonBlank(safeAnalysis.get("metricField"), params.get("metricField")));
            putIfPresent(result, "timeField", firstNonBlank(safeAnalysis.get("timeField"), params.get("timeField")));
            putIfPresent(result, "operator", firstNonBlank(safeAnalysis.get("operator"), params.get("operator")));
            Object threshold = firstPresent(safeAnalysis.get("threshold"), params.get("threshold"));
            if (threshold != null) {
                result.put("threshold", threshold);
            }
            putIfPresent(result, "detectionCycle", firstNonBlank(safeAnalysis.get("detectionCycle"), params.get("detectionCycle")));
            Object channels = firstPresent(safeAnalysis.get("channels"), params.get("channels"), params.get("channel"));
            if (channels != null) {
                result.put("channels", channels);
            }
            putIfPresent(result, "status", firstNonBlank(safeAnalysis.get("status"), "alert".equals(analysisType) ? "CREATED" : "GENERATED"));

            String historyQuestion = firstNonBlank(question, safeAnalysis.get("sourceQuestion"), safeAnalysis.get("title"), message);
            Long historyId = chatQueryHistoryService.recordSuccess(historyQuestion, tableName, result, null);
            if (historyId == null || historyId <= 0) {
                return null;
            }

            Map<String, Object> context = new LinkedHashMap<>();
            context.put("module", "advancedAnalysis");
            context.put("analysisType", analysisType);
            context.put("clientMessageId", clientMessageId);
            context.put("conversationId", conversationId);
            context.put("userTurnId", userTurnId);
            context.put("assistantTurnId", assistantTurnId);
            context.put("artifactId", artifactId);
            context.put("engine", "advanced-analysis");
            context.put("ruleId", nestedValue(safeAnalysis, "params", "ruleId"));
            context.put("eventId", nestedValue(safeAnalysis, "params", "eventId"));
            chatQueryHistoryService.attachConversationMetadata(
                    historyId,
                    conversationId,
                    null,
                    assistantTurnNo,
                    "ASSISTANT",
                    intentType,
                    context,
                    Map.of("tableName", tableName),
                    artifactType,
                    message
            );
            if (artifactId != null) {
                jdbcTemplate.update("""
                        UPDATE is_chat_conversation_artifact
                           SET history_id = ?
                         WHERE id = ? AND conversation_id = ? AND turn_id = ?
                        """, historyId, artifactId, conversationId, assistantTurnId);
            }
            return historyId;
        } catch (Exception ignored) {
            return null;
        }
    }

    public Map<String, Object> markAlertRuleCreated(Map<String, Object> request) {
        String userId = resolveUserId();
        Long conversationId = toLong(request == null ? null : request.get("conversationId"));
        Long assistantTurnId = toLong(request == null ? null : request.get("assistantTurnId"));
        Long artifactId = toLong(request == null ? null : request.get("artifactId"));
        Map<String, Object> alertRuleCreated = asMap(request == null ? null : request.get("alertRuleCreated"));
        Map<String, Object> advancedAnalysis = asMap(request == null ? null : request.get("advancedAnalysis"));
        if (userId == null || conversationId == null || assistantTurnId == null || alertRuleCreated.isEmpty()
                || !isConversationOwnedByUser(conversationId, userId)) {
            return Map.of("updated", false);
        }

        List<Map<String, Object>> rows = artifactId == null
                ? jdbcTemplate.queryForList("""
                        SELECT id, history_id AS historyId, artifact_json AS artifactJson
                           FROM is_chat_conversation_artifact
                          WHERE conversation_id = ? AND turn_id = ? AND artifact_type IN ('CHART', 'ADVANCED_ALERT')
                          ORDER BY id DESC LIMIT 1
                        """, conversationId, assistantTurnId)
                : jdbcTemplate.queryForList("""
                        SELECT id, history_id AS historyId, artifact_json AS artifactJson
                           FROM is_chat_conversation_artifact
                          WHERE conversation_id = ? AND turn_id = ? AND id = ?
                          LIMIT 1
                        """, conversationId, assistantTurnId, artifactId);
        if (rows.isEmpty()) {
            return Map.of("updated", false);
        }

        Map<String, Object> row = rows.get(0);
        Long resolvedArtifactId = toLong(row.get("id"));
        Long historyId = toLong(row.get("historyId"));
        Map<String, Object> artifact = parseJsonMap(row.get("artifactJson"));
        mergeMultiStepPayloadFromRequest(artifact, request);
        artifact.put("alertRuleDraft", null);
        artifact.put("alertRuleCreated", compactAdvancedValue(alertRuleCreated, MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
        if (!advancedAnalysis.isEmpty()) {
            artifact.put("advancedAnalysis", compactAdvancedAnalysis(advancedAnalysis));
        }
        applyAlertRuleCreatedToMultiStepPayload(artifact, alertRuleCreated, advancedAnalysis);
        jdbcTemplate.update("""
                UPDATE is_chat_conversation_artifact
                   SET artifact_json = ?
                 WHERE id = ? AND conversation_id = ? AND turn_id = ?
                """, toJson(artifact), resolvedArtifactId, conversationId, assistantTurnId);
        jdbcTemplate.update("""
                UPDATE is_chat_conversation_turn
                   SET message_text = ?,
                       context_json = JSON_SET(
                           COALESCE(context_json, JSON_OBJECT()),
                           '$.alertRuleCreated', CAST(? AS JSON),
                           '$.stepResults', CAST(? AS JSON),
                           '$.multiStepSummary', CAST(? AS JSON),
                           '$.actionPlan', CAST(? AS JSON),
                           '$.requiresConfirmation', ?
                       )
                  WHERE id = ? AND conversation_id = ?
                """,
                safeText(Objects.toString(request == null ? null : request.get("message"),
                        "预警规则已创建，可在预警规则管理中查看和维护。"), MAX_MESSAGE_LENGTH),
                toJson(alertRuleCreated),
                toJson(artifact.getOrDefault("stepResults", List.of())),
                toJson(artifact.getOrDefault("multiStepSummary", Map.of())),
                toJson(artifact.getOrDefault("actionPlan", Map.of())),
                Boolean.TRUE.equals(artifact.get("requiresConfirmation")) ? 1 : 0,
                assistantTurnId,
                conversationId);
        updateAdvancedAlertHistoryCreated(historyId, alertRuleCreated, advancedAnalysis,
                Objects.toString(request == null ? null : request.get("message"),
                        "预警规则已创建，可在预警规则管理中查看和维护。"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("updated", true);
        payload.put("conversationId", conversationId);
        payload.put("assistantTurnId", assistantTurnId);
        payload.put("artifactId", resolvedArtifactId);
        payload.put("historyId", historyId);
        return payload;
    }

    private void updateAdvancedAlertHistoryCreated(Long historyId,
                                                   Map<String, Object> alertRuleCreated,
                                                   Map<String, Object> advancedAnalysis,
                                                   String message) {
        if (historyId == null || historyId <= 0) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT chart_snapshot AS chartSnapshot
                      FROM is_chat_query_history
                     WHERE id = ? AND user_id = ?
                     LIMIT 1
                    """, historyId, resolveUserId());
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> snapshot = parseJsonMap(rows.get(0).get("chartSnapshot"));
            snapshot.put("advancedAnalysisType", "alert");
            snapshot.put("type", "alert");
            snapshot.put("alertRuleDraft", null);
            snapshot.put("alertRuleCreated", compactAdvancedValue(alertRuleCreated, MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
            snapshot.put("status", firstNonBlank(advancedAnalysis.get("status"), alertRuleCreated.get("status"), "已启用"));
            snapshot.put("message", message);
            Map<String, Object> alertMeta = asMap(snapshot.get("alertMeta"));
            putIfPresent(alertMeta, "ruleName", firstNonBlank(alertRuleCreated.get("title"), advancedAnalysis.get("title")));
            putIfPresent(alertMeta, "metricField", firstNonBlank(alertRuleCreated.get("metricField"), nestedValue(advancedAnalysis, "params", "metricField")));
            putIfPresent(alertMeta, "timeField", firstNonBlank(alertRuleCreated.get("timeField"), nestedValue(advancedAnalysis, "params", "timeField")));
            putIfPresent(alertMeta, "operator", firstNonBlank(alertRuleCreated.get("operator"), nestedValue(advancedAnalysis, "params", "operator")));
            Object threshold = firstPresent(alertRuleCreated.get("threshold"), nestedValue(advancedAnalysis, "params", "threshold"));
            if (threshold != null) {
                alertMeta.put("threshold", threshold);
            }
            Object channels = firstPresent(alertRuleCreated.get("channels"), nestedValue(advancedAnalysis, "params", "channels"));
            if (channels != null) {
                alertMeta.put("channels", channels);
            }
            putIfPresent(alertMeta, "status", "ACTIVE");
            snapshot.put("alertMeta", alertMeta);
            applyAlertRuleCreatedToMultiStepPayload(snapshot, alertRuleCreated, advancedAnalysis);
            jdbcTemplate.update("""
                    UPDATE is_chat_query_history
                       SET chart_type = 'alert',
                           chart_snapshot = ?,
                           generated_sql = NULL,
                           llm_model_used = 'advanced-analysis',
                           execution_status = 1,
                           execution_time_ms = NULL,
                           is_hit_cache = 0,
                           risk_level = 'WARN',
                           audit_info = ?,
                           artifact_type = 'ADVANCED_ALERT',
                           intent_type = 'ADVANCED_ALERT',
                           summary_text = ?
                     WHERE id = ? AND user_id = ?
                    """,
                    toJson(snapshot),
                    safeText(message, MAX_SUMMARY_LENGTH),
                    safeText(message, MAX_SUMMARY_LENGTH),
                    historyId,
                    resolveUserId());
        } catch (Exception ignored) {
            // 历史快照增强失败不应影响预警规则保存。
        }
    }

    private void mergeMultiStepPayloadFromRequest(Map<String, Object> artifact, Map<String, Object> request) {
        if (artifact == null || request == null) {
            return;
        }
        List<Object> requestStepResults = asList(request.get("stepResults"));
        if (!requestStepResults.isEmpty()) {
            artifact.put("stepResults", requestStepResults);
        }
        Map<String, Object> requestActionPlan = asMap(request.get("actionPlan"));
        if (!requestActionPlan.isEmpty()) {
            artifact.put("actionPlan", requestActionPlan);
        }
        Map<String, Object> requestSummary = asMap(request.get("multiStepSummary"));
        if (!requestSummary.isEmpty()) {
            artifact.put("multiStepSummary", requestSummary);
        }
    }

    private void applyAlertRuleCreatedToMultiStepPayload(Map<String, Object> payload,
                                                         Map<String, Object> alertRuleCreated,
                                                         Map<String, Object> advancedAnalysis) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Map<String, Object> patch = alertRuleCreatedStepPatch(alertRuleCreated, advancedAnalysis);
        List<Object> stepResults = updateAlertRuleCreatedSteps(payload.get("stepResults"), patch);
        if (!stepResults.isEmpty()) {
            payload.put("stepResults", stepResults);
        }
        Map<String, Object> actionPlan = asMap(payload.get("actionPlan"));
        if (!actionPlan.isEmpty()) {
            List<Object> actions = updateAlertRuleCreatedSteps(actionPlan.get("actions"), patch);
            if (!actions.isEmpty()) {
                actionPlan.put("actions", actions);
            }
            actionPlan.put("requiresConfirmation", hasPendingConfirmation(actions));
            payload.put("actionPlan", actionPlan);
        }
        List<Object> summarySteps = !stepResults.isEmpty() ? stepResults : asList(actionPlan.get("actions"));
        if (!summarySteps.isEmpty()) {
            payload.put("multiStepSummary", buildMultiStepSummary(summarySteps, asMap(payload.get("multiStepSummary"))));
            payload.put("requiresConfirmation", hasPendingConfirmation(summarySteps));
        }
    }

    private Map<String, Object> alertRuleCreatedStepPatch(Map<String, Object> alertRuleCreated,
                                                          Map<String, Object> advancedAnalysis) {
        Map<String, Object> patch = new LinkedHashMap<>();
        Object ruleId = firstPresent(alertRuleCreated.get("id"), alertRuleCreated.get("ruleId"),
                advancedAnalysis.get("ruleId"), nestedValue(advancedAnalysis, "params", "ruleId"));
        patch.put("status", "COMPLETED");
        patch.put("message", ruleId == null
                ? "预警规则已创建，后续将按检测周期离线检测。"
                : "预警规则已创建，规则 #" + ruleId + " 已进入离线检测。");
        patch.put("requiresConfirmation", false);
        if (ruleId != null) {
            patch.put("ruleId", ruleId);
        }
        patch.put("alertRuleCreated", compactAdvancedValue(alertRuleCreated, MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
        return patch;
    }

    private List<Object> updateAlertRuleCreatedSteps(Object value, Map<String, Object> patch) {
        List<Object> steps = asList(value);
        if (steps.isEmpty()) {
            return List.of();
        }
        List<Object> updated = new ArrayList<>();
        for (Object item : steps) {
            Map<String, Object> step = asMap(item);
            if (step.isEmpty()) {
                updated.add(item);
                continue;
            }
            if (isAlertRuleStep(step)) {
                Map<String, Object> next = new LinkedHashMap<>(step);
                next.putAll(patch);
                updated.add(next);
            } else {
                updated.add(step);
            }
        }
        return updated;
    }

    private boolean isAlertRuleStep(Map<String, Object> step) {
        String type = firstNonBlank(step.get("type"), step.get("intent"), step.get("smartIntent"))
                .trim()
                .toUpperCase(Locale.ROOT);
        return "ALERT_RULE_CREATE_DRAFT".equals(type)
                || "ALERT_RULE_CREATE".equals(type)
                || "ALERT_RULE_DRAFT".equals(type);
    }

    private Map<String, Object> buildMultiStepSummary(List<Object> steps, Map<String, Object> existing) {
        Map<String, Object> summary = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        long completed = countStepsByStatus(steps, "COMPLETED");
        long needsConfirmation = countStepsByStatus(steps, "NEEDS_CONFIRMATION");
        long failed = countStepsByStatus(steps, "FAILED");
        long skipped = countStepsByStatus(steps, "SKIPPED");
        summary.put("total", steps.size());
        summary.put("completed", completed);
        summary.put("needsConfirmation", needsConfirmation);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        return summary;
    }

    private long countStepsByStatus(List<Object> steps, String status) {
        return steps.stream()
                .map(this::asMap)
                .filter(step -> status.equalsIgnoreCase(Objects.toString(step.get("status"), "").trim()))
                .count();
    }

    private boolean hasPendingConfirmation(List<Object> steps) {
        return steps.stream()
                .map(this::asMap)
                .anyMatch(step -> "NEEDS_CONFIRMATION".equalsIgnoreCase(
                        Objects.toString(step.get("status"), "").trim()));
    }

    private Map<String, Object> findExistingAdvancedRecord(Long conversationId, String clientMessageId) {
        if (conversationId == null || clientMessageId == null || clientMessageId.isBlank()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT a.id AS artifactId,
                           a.artifact_type AS artifactType,
                           a.conversation_id AS conversationId,
                           a.turn_id AS assistantTurnId,
                           JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.userTurnId')) AS userTurnId
                      FROM is_chat_conversation_artifact a
                     WHERE a.conversation_id = ?
                       AND a.artifact_type LIKE 'ADVANCED_%'
                       AND JSON_UNQUOTE(JSON_EXTRACT(a.artifact_json, '$.clientMessageId')) = ?
                     ORDER BY a.id DESC
                     LIMIT 1
                    """, conversationId, clientMessageId);
            if (rows.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> row = rows.get(0);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", toLong(row.get("conversationId")));
            payload.put("userTurnId", toLong(row.get("userTurnId")));
            payload.put("assistantTurnId", toLong(row.get("assistantTurnId")));
            payload.put("artifactId", toLong(row.get("artifactId")));
            payload.put("artifactType", Objects.toString(row.get("artifactType"), ""));
            payload.put("recorded", true);
            payload.put("duplicated", true);
            return payload;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> buildAdvancedAnalysisArtifact(Map<String, Object> analysis,
                                                              Map<String, Object> request,
                                                              Long conversationId,
                                                              Long userTurnId,
                                                              Long assistantTurnId,
                                                              String clientMessageId,
                                                              List<Object> thinkingLogs) {
        Map<String, Object> safeAnalysis = analysis == null ? Map.of() : analysis;
        String analysisType = normalizeAdvancedAnalysisType(Objects.toString(safeAnalysis.get("type"),
                Objects.toString(request == null ? null : request.get("type"), "")));
        Map<String, Object> compactAnalysis = compactAdvancedAnalysis(safeAnalysis);
        List<Object> compactThinkingLogs = compactAdvancedThinkingLogs(thinkingLogs);
        Map<String, Object> storagePolicy = buildAdvancedStoragePolicy(safeAnalysis, compactAnalysis,
                thinkingLogs, compactThinkingLogs);
        boolean snapshotTruncated = Boolean.TRUE.equals(storagePolicy.get("truncated"));
        if (snapshotTruncated) {
            compactAnalysis.put("snapshotTruncated", true);
            compactAnalysis.put("storagePolicy", storagePolicy);
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 1);
        artifact.put("module", "advancedAnalysis");
        artifact.put("type", analysisType);
        artifact.put("clientMessageId", clientMessageId);
        artifact.put("conversationId", conversationId);
        artifact.put("userTurnId", userTurnId);
        artifact.put("assistantTurnId", assistantTurnId);
        artifact.put("sourceQuestion", Objects.toString(request == null ? null : request.get("question"),
                Objects.toString(safeAnalysis.get("sourceQuestion"), "")));
        artifact.put("tableName", Objects.toString(request == null ? null : request.get("tableName"),
                Objects.toString(safeAnalysis.get("tableName"), "")));
        artifact.put("title", safeAnalysis.get("title"));
        artifact.put("summary", safeAnalysis.get("summary"));
        artifact.put("status", safeAnalysis.get("status"));
        artifact.put("params", compactAnalysis.getOrDefault("params", Map.of()));
        artifact.put("fieldMapping", compactAnalysis.getOrDefault("fieldMapping", Map.of()));
        artifact.put("series", compactAnalysis.getOrDefault("series", List.of()));
        artifact.put("insights", compactAnalysis.getOrDefault("insights", List.of()));
        artifact.put("explanation", compactAnalysis.getOrDefault("explanation", Map.of()));
        artifact.put("llmIntent", compactAdvancedValue(request == null ? Map.of() : asMap(request.get("llmIntent")),
                MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
        artifact.put("thinkingLogs", compactThinkingLogs);
        artifact.put("advancedAnalysis", compactAnalysis);
        artifact.put("planId", nestedValue(safeAnalysis, "params", "planId"));
        artifact.put("ruleId", nestedValue(safeAnalysis, "params", "ruleId"));
        artifact.put("eventId", nestedValue(safeAnalysis, "params", "eventId"));
        artifact.put("snapshotTruncated", snapshotTruncated);
        artifact.put("storagePolicy", storagePolicy);
        artifact.put("createdAtMillis", System.currentTimeMillis());
        return artifact;
    }

    private Map<String, Object> compactAdvancedAnalysis(Map<String, Object> analysis) {
        Map<String, Object> compact = new LinkedHashMap<>();
        if (analysis != null) {
            for (Map.Entry<String, Object> entry : analysis.entrySet()) {
                String key = Objects.toString(entry.getKey(), "").trim();
                if (key.isBlank()) {
                    continue;
                }
                if ("series".equals(key)) {
                    compact.put(key, compactAdvancedList(limitListEvenly(asList(entry.getValue()),
                            MAX_ADVANCED_SERIES_POINTS), MAX_ADVANCED_SERIES_POINTS, false));
                } else if ("insights".equals(key)) {
                    compact.put(key, compactAdvancedList(asList(entry.getValue()), MAX_ADVANCED_INSIGHTS, false));
                } else if ("explanation".equals(key)) {
                    compact.put(key, compactAdvancedExplanation(entry.getValue()));
                } else if ("params".equals(key)) {
                    compact.put(key, compactAdvancedParams(entry.getValue()));
                } else {
                    compact.put(key, compactAdvancedValue(entry.getValue(), MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
                }
            }
        }
        compact.putIfAbsent("series", List.of());
        compact.putIfAbsent("insights", List.of());
        compact.putIfAbsent("explanation", Map.of());
        compact.putIfAbsent("params", Map.of());
        return compact;
    }

    private Map<String, Object> compactAdvancedParams(Object value) {
        Map<String, Object> params = asMap(value);
        Map<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            if (key.isBlank()) {
                continue;
            }
            Object rawValue = entry.getValue();
            if (rawValue instanceof List<?> && isLargeAdvancedParamList(key)) {
                List<Object> source = asList(rawValue);
                List<Object> stored = compactAdvancedList(limitListEvenly(source, MAX_ADVANCED_PARAM_SERIES_POINTS),
                        MAX_ADVANCED_PARAM_SERIES_POINTS, false);
                compact.put(key, stored);
                if (source.size() > stored.size()) {
                    compact.put(key + "Truncated", true);
                    compact.put(key + "OriginalCount", source.size());
                    compact.put(key + "StoredCount", stored.size());
                }
            } else {
                compact.put(key, compactAdvancedValue(rawValue, MAX_ADVANCED_NESTED_LIST_ITEMS, 4));
            }
        }
        return compact;
    }

    private boolean isLargeAdvancedParamList(String key) {
        String lower = Objects.toString(key, "").trim().toLowerCase(Locale.ROOT);
        return lower.contains("series")
                || lower.contains("rows")
                || lower.contains("data")
                || lower.contains("sample")
                || lower.contains("snapshot");
    }

    private Map<String, Object> compactAdvancedExplanation(Object value) {
        Map<String, Object> explanation = asMap(value);
        Map<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : explanation.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            if (key.isBlank()) {
                continue;
            }
            if (entry.getValue() instanceof List<?>) {
                compact.put(key, compactAdvancedList(asList(entry.getValue()),
                        MAX_ADVANCED_EXPLANATION_ITEMS, false));
            } else {
                compact.put(key, compactAdvancedValue(entry.getValue(), MAX_ADVANCED_NESTED_LIST_ITEMS, 3));
            }
        }
        return compact;
    }

    private List<Object> compactAdvancedThinkingLogs(List<Object> thinkingLogs) {
        return compactAdvancedList(thinkingLogs == null ? List.of() : thinkingLogs,
                MAX_ADVANCED_THINKING_LOGS, false);
    }

    private List<Object> compactAdvancedList(List<Object> source, int maxItems, boolean evenly) {
        List<Object> rows = source == null ? List.of() : source;
        List<Object> limited = evenly ? limitListEvenly(rows, maxItems) : rows.stream().limit(maxItems).toList();
        return limited.stream()
                .map(item -> compactAdvancedValue(item, MAX_ADVANCED_NESTED_LIST_ITEMS, 4))
                .collect(Collectors.toList());
    }

    private List<Object> limitListEvenly(List<Object> source, int maxItems) {
        List<Object> rows = source == null ? List.of() : source;
        if (maxItems <= 0 || rows.isEmpty()) {
            return List.of();
        }
        if (rows.size() <= maxItems) {
            return new ArrayList<>(rows);
        }
        if (maxItems == 1) {
            return List.of(rows.get(rows.size() - 1));
        }
        Set<Integer> indices = new LinkedHashSet<>();
        double step = (rows.size() - 1) / (double) (maxItems - 1);
        for (int i = 0; i < maxItems; i++) {
            int index = (int) Math.round(i * step);
            indices.add(Math.max(0, Math.min(rows.size() - 1, index)));
        }
        for (int i = 0; indices.size() < maxItems && i < rows.size(); i++) {
            indices.add(i);
        }
        List<Integer> sorted = new ArrayList<>(indices);
        sorted.sort(Integer::compareTo);
        List<Object> result = new ArrayList<>();
        for (Integer index : sorted) {
            result.add(rows.get(index));
        }
        return result;
    }

    private Object compactAdvancedValue(Object value, int listLimit, int depth) {
        if (value == null) {
            return null;
        }
        if (depth <= 0) {
            return value instanceof Number || value instanceof Boolean
                    ? value
                    : safeText(Objects.toString(value, ""), MAX_ADVANCED_TEXT_LENGTH);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> compact = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey(), "").trim();
                if (!key.isBlank()) {
                    compact.put(key, compactAdvancedValue(entry.getValue(), listLimit, depth - 1));
                }
            }
            return compact;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .limit(Math.max(0, listLimit))
                    .map(item -> compactAdvancedValue(item, listLimit, depth - 1))
                    .collect(Collectors.toList());
        }
        if (value instanceof String text) {
            return safeText(text, MAX_ADVANCED_TEXT_LENGTH);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return safeText(Objects.toString(value, ""), MAX_ADVANCED_TEXT_LENGTH);
    }

    private Map<String, Object> buildAdvancedStoragePolicy(Map<String, Object> original,
                                                           Map<String, Object> compact,
                                                           List<Object> thinkingLogs,
                                                           List<Object> compactThinkingLogs) {
        int originalSeriesCount = asList(original == null ? null : original.get("series")).size();
        int storedSeriesCount = asList(compact == null ? null : compact.get("series")).size();
        int originalInsightCount = asList(original == null ? null : original.get("insights")).size();
        int storedInsightCount = asList(compact == null ? null : compact.get("insights")).size();
        int originalThinkingCount = thinkingLogs == null ? 0 : thinkingLogs.size();
        int storedThinkingCount = compactThinkingLogs == null ? 0 : compactThinkingLogs.size();
        boolean truncated = originalSeriesCount > storedSeriesCount
                || originalInsightCount > storedInsightCount
                || originalThinkingCount > storedThinkingCount
                || hasTruncatedAdvancedParams(compact == null ? null : compact.get("params"));

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", 1);
        policy.put("maxSeriesPoints", MAX_ADVANCED_SERIES_POINTS);
        policy.put("maxParamSeriesPoints", MAX_ADVANCED_PARAM_SERIES_POINTS);
        policy.put("maxInsights", MAX_ADVANCED_INSIGHTS);
        policy.put("maxExplanationItems", MAX_ADVANCED_EXPLANATION_ITEMS);
        policy.put("maxThinkingLogs", MAX_ADVANCED_THINKING_LOGS);
        policy.put("seriesOriginalCount", originalSeriesCount);
        policy.put("seriesStoredCount", storedSeriesCount);
        policy.put("insightsOriginalCount", originalInsightCount);
        policy.put("insightsStoredCount", storedInsightCount);
        policy.put("thinkingLogsOriginalCount", originalThinkingCount);
        policy.put("thinkingLogsStoredCount", storedThinkingCount);
        policy.put("truncated", truncated);
        return policy;
    }

    private boolean hasTruncatedAdvancedParams(Object paramsValue) {
        Map<String, Object> params = asMap(paramsValue);
        return params.entrySet().stream()
                .anyMatch(entry -> entry.getKey().endsWith("Truncated") && Boolean.TRUE.equals(entry.getValue()));
    }

    private String defaultAdvancedAssistantMessage(String analysisType, Map<String, Object> analysis) {
        String title = Objects.toString(analysis == null ? null : analysis.get("title"), "").trim();
        String label = switch (analysisType) {
            case "whatIf" -> "What-if 推演";
            case "alert" -> "智能预警";
            default -> "时序预测";
        };
        return title.isBlank() ? label + "已生成，请在卡片中查看详情。" : label + "已生成：" + title;
    }

    private String normalizeAdvancedAnalysisType(String type) {
        String value = Objects.toString(type, "").trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("what")) {
            return "whatIf";
        }
        if (lower.contains("alert") || lower.contains("warning")) {
            return "alert";
        }
        if (lower.contains("forecast") || lower.contains("predict")) {
            return "forecast";
        }
        if ("whatIf".equals(value)) {
            return "whatIf";
        }
        if ("alert".equals(value)) {
            return "alert";
        }
        return "forecast";
    }

    private String advancedIntentType(String analysisType) {
        return switch (analysisType) {
            case "whatIf" -> "ADVANCED_WHAT_IF";
            case "alert" -> "ADVANCED_ALERT";
            default -> "ADVANCED_FORECAST";
        };
    }

    private String advancedArtifactType(String analysisType) {
        return switch (analysisType) {
            case "whatIf" -> "ADVANCED_WHAT_IF";
            case "alert" -> "ADVANCED_ALERT";
            default -> "ADVANCED_FORECAST";
        };
    }

    private String advancedChartType(String analysisType) {
        return switch (analysisType) {
            case "whatIf" -> "whatIf";
            case "alert" -> "alert";
            default -> "forecast";
        };
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

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = Objects.toString(value, "").trim();
            if (!text.isBlank() && !"null".equalsIgnoreCase(text) && !"undefined".equalsIgnoreCase(text)) {
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

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof String text && text.trim().isBlank()) {
            return;
        }
        target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private void attachAlertEventContext(Map<String, Object> context, Map<String, Object> result) {
        String type = Objects.toString(firstPresent(result.get("responseType"), result.get("smartIntent"), result.get("intent")), "")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (!type.startsWith("ALERT_EVENT")) {
            return;
        }
        List<Map<String, Object>> events = alertEventCandidates(result);
        if (events.isEmpty()) {
            return;
        }
        Long eventId = null;
        List<Map<String, Object>> compactEvents = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            Long id = toLong(firstPresent(event.get("id"), event.get("eventId"), event.get("alertEventId")));
            if (id == null || id <= 0 || !seen.add(id)) {
                continue;
            }
            if (eventId == null) {
                eventId = id;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            putIfPresent(item, "ruleId", firstPresent(event.get("ruleId"), event.get("alertRuleId")));
            putIfPresent(item, "ruleName", firstPresent(event.get("ruleName"), event.get("ruleTitle"), event.get("title"), event.get("name")));
            putIfPresent(item, "status", event.get("status"));
            putIfPresent(item, "bucketName", firstPresent(event.get("bucketName"), event.get("bucket"), event.get("period")));
            if (compactEvents.size() < 5) {
                compactEvents.add(item);
            }
        }
        if (eventId == null) {
            return;
        }
        context.put("currentAlertEventId", eventId);
        context.put("lastAlertEventId", eventId);
        context.put("alertEvents", compactEvents);
    }

    private List<Map<String, Object>> alertEventCandidates(Map<String, Object> result) {
        List<Map<String, Object>> events = new ArrayList<>();
        appendAlertEventCandidates(events, result.get("alertEvent"));
        appendAlertEventCandidates(events, result.get("alertEvents"));
        appendAlertEventCandidates(events, result.get("data"));
        return events;
    }

    @SuppressWarnings("unchecked")
    private void appendAlertEventCandidates(List<Map<String, Object>> events, Object value) {
        if (value instanceof Map<?, ?> map) {
            events.add(new LinkedHashMap<>((Map<String, Object>) map));
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    events.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
        }
    }

    private String alertEventContextLine(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        Long eventId = toLong(firstPresent(
                context.get("currentAlertEventId"),
                context.get("lastAlertEventId"),
                context.get("alertEventId"),
                context.get("eventId")
        ));
        List<Map<String, Object>> events = asMapList(context.get("alertEvents"));
        if ((eventId == null || eventId <= 0) && !events.isEmpty()) {
            eventId = toLong(firstPresent(events.get(0).get("id"), events.get(0).get("eventId"), events.get(0).get("alertEventId")));
        }
        if (eventId == null || eventId <= 0) {
            return "";
        }
        Long currentEventId = eventId;
        Map<String, Object> current = events.stream()
                .filter(event -> currentEventId.equals(toLong(firstPresent(event.get("id"), event.get("eventId"), event.get("alertEventId")))))
                .findFirst()
                .orElse(events.isEmpty() ? Map.of() : events.get(0));
        String ruleName = firstNonBlank(current.get("ruleName"), current.get("ruleTitle"), current.get("title"), current.get("name"));
        String status = firstNonBlank(current.get("status"));
        StringBuilder line = new StringBuilder("最近预警事件").append(eventId);
        if (!ruleName.isBlank()) {
            line.append("，规则名：").append(safeText(ruleName, 80));
        }
        if (!status.isBlank()) {
            line.append("，状态：").append(status);
        }
        line.append("。");
        return line.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return rows;
    }

    private List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? List.of() : List.of(text);
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
                SELECT role, message_text AS messageText, context_json AS contextJson
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
            String alertContextLine = alertEventContextLine(parseJsonMap(row.get("contextJson")));
            if (!alertContextLine.isBlank()) {
                contextLines.add("ASSISTANT_CONTEXT: " + alertContextLine);
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
                    SELECT id, parent_turn_id AS parentTurnId, role, message_text AS messageText,
                           context_json AS contextJson
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

    public Map<String, Object> latestPinnableArtifactForTurn(Long turnId) {
        return latestPinnableArtifactForTurn(turnId, false);
    }

    public Map<String, Object> latestPinnableArtifactForTurn(Long turnId, boolean preferAdvanced) {
        String userId = resolveUserId();
        if (turnId == null || userId == null) {
            return Map.of();
        }
        String orderSql = preferAdvanced
                ? """
                          ORDER BY CASE
                                     WHEN a.artifact_type LIKE 'ADVANCED\\_%' THEN 0
                                     WHEN a.artifact_type = 'CHART' AND a.history_id IS NOT NULL THEN 1
                                     WHEN a.artifact_type = 'CHART' THEN 2
                                     ELSE 3
                                   END,
                                   a.id DESC
                        """
                : """
                          ORDER BY CASE
                                     WHEN a.artifact_type = 'CHART' AND a.history_id IS NOT NULL THEN 0
                                     WHEN a.artifact_type LIKE 'ADVANCED\\_%' THEN 1
                                     WHEN a.artifact_type = 'CHART' THEN 2
                                     ELSE 3
                                   END,
                                   a.id DESC
                        """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT a.id, a.conversation_id AS conversationId, a.turn_id AS turnId, a.history_id AS historyId,
                       a.artifact_type AS artifactType, a.artifact_json AS artifactJson,
                       a.sql_text AS sqlText, a.chart_type AS chartType, a.risk_level AS riskLevel,
                       a.created_at AS createdAt
                  FROM is_chat_conversation_artifact a
                  INNER JOIN is_chat_conversation c ON c.id = a.conversation_id
                 WHERE a.turn_id = ?
                   AND (a.artifact_type = 'CHART' OR a.artifact_type LIKE 'ADVANCED\\_%')
                   AND c.user_id = ? AND c.is_deleted = 0
                """ + orderSql + """
                 LIMIT 1
                """, turnId, userId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("artifact", parseJsonMap(row.get("artifactJson")));
        return row;
    }

    public Map<String, Object> latestChartArtifactForConversation(Long conversationId) {
        String userId = resolveUserId();
        if (conversationId == null || userId == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT a.id, a.conversation_id AS conversationId, a.turn_id AS turnId, a.history_id AS historyId,
                       a.artifact_type AS artifactType, a.artifact_json AS artifactJson,
                       a.sql_text AS sqlText, a.chart_type AS chartType, a.risk_level AS riskLevel,
                       a.created_at AS createdAt
                  FROM is_chat_conversation_artifact a
                  INNER JOIN is_chat_conversation c ON c.id = a.conversation_id
                 WHERE a.conversation_id = ? AND a.artifact_type = 'CHART'
                   AND c.user_id = ? AND c.is_deleted = 0
                   AND a.history_id IS NOT NULL
                 ORDER BY a.id DESC
                 LIMIT 20
                """, conversationId, userId);
        for (Map<String, Object> candidate : rows) {
            Map<String, Object> row = new LinkedHashMap<>(candidate);
            Map<String, Object> artifact = parseJsonMap(row.get("artifactJson"));
            if (isPinnableChartArtifact(row, artifact)) {
                row.put("artifact", artifact);
                return row;
            }
        }
        return Map.of();
    }

    private Map<String, Object> buildChartArtifact(Map<String, Object> result, Long historyId) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("historyId", historyId);
        artifact.put("responseType", result.get("responseType"));
        artifact.put("smartIntent", result.get("smartIntent"));
        artifact.put("dashboardActionStatus", result.get("dashboardActionStatus"));
        artifact.put("tableName", result.get("tableName"));
        artifact.put("chartType", result.get("chartType"));
        artifact.put("fieldMapping", result.get("fieldMapping"));
        artifact.put("message", result.get("message"));
        artifact.put("sql", result.get("sql"));
        artifact.put("data", result.get("data"));
        artifact.put("tableColumns", result.get("tableColumns"));
        artifact.put("alertEvent", result.get("alertEvent"));
        artifact.put("alertEvents", result.get("alertEvents"));
        artifact.put("alertEventSummary", result.get("alertEventSummary"));
        artifact.put("riskLevel", result.getOrDefault("riskLevel", "SAFE"));
        artifact.put("riskReason", result.getOrDefault("riskReason", ""));
        artifact.put("sensitiveFields", result.getOrDefault("sensitiveFields", List.of()));
        artifact.put("matchedRules", result.getOrDefault("matchedRules", List.of()));
        artifact.put("chartEngine", result.get("chartEngine"));
        artifact.put("dimensions", result.get("dimensions"));
        artifact.put("encode", result.get("encode"));
        artifact.put("optionTemplate", result.get("optionTemplate"));
        artifact.put("reasoningReplaySteps", result.getOrDefault("reasoningReplaySteps",
                result.getOrDefault("reasoningLogs", List.of())));
        artifact.put("actionPlan", result.get("actionPlan"));
        artifact.put("stepResults", result.get("stepResults"));
        artifact.put("multiStepSummary", result.get("multiStepSummary"));
        artifact.put("smartRouteAudit", result.get("smartRouteAudit"));
        artifact.put("alertRuleDraft", result.get("alertRuleDraft"));
        return artifact;
    }

    private boolean isPinnableChartArtifact(Map<String, Object> row, Map<String, Object> artifact) {
        String responseType = Objects.toString(artifact.get("responseType"), "").trim().toUpperCase(Locale.ROOT);
        String smartIntent = Objects.toString(artifact.get("smartIntent"), "").trim().toUpperCase(Locale.ROOT);
        String dashboardActionStatus = Objects.toString(artifact.get("dashboardActionStatus"), "").trim();
        if (!dashboardActionStatus.isBlank() || responseType.startsWith("DASHBOARD_") || smartIntent.startsWith("DASHBOARD_")) {
            return false;
        }
        if (Set.of("CLARIFICATION", "CLARIFY", "ALERT_RULE_DRAFT", "WHAT_IF_DRAFT").contains(responseType)) {
            return false;
        }
        Object data = artifact.get("data");
        if (data instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> first) {
            String name = Objects.toString(first.get("name"), "").trim().toUpperCase(Locale.ROOT);
            if (Set.of("DASHBOARD_PIN", "CLARIFY", "CLARIFICATION", "ALERT_RULE_DRAFT", "WHAT_IF_DRAFT").contains(name)) {
                return false;
            }
        }
        Long historyId = toLong(row.get("historyId"));
        return historyId != null && historyId > 0;
    }

    private Map<String, Object> mapConversationRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        item.put("scope", parseJsonMap(row.get("scopeJson")));
        item.put("turnCount", toInt(row.get("turnCount")));
        item.put("advancedTypes", advancedTypesFromArtifactCsv(row.get("advancedArtifactTypes")));
        item.put("latestAdvancedType", advancedTypeFromArtifactType(row.get("latestAdvancedArtifactType")));
        return item;
    }

    private List<Map<String, Object>> advancedTypesFromArtifactCsv(Object value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String part : text.split(",")) {
            String type = advancedTypeFromArtifactType(part);
            if (type.isBlank()) {
                continue;
            }
            boolean exists = result.stream().anyMatch(item -> Objects.equals(item.get("value"), type));
            if (!exists) {
                result.add(Map.of("value", type, "label", advancedTypeLabel(type)));
            }
        }
        return result;
    }

    private String advancedTypeFromArtifactType(Object artifactType) {
        String text = Objects.toString(artifactType, "").trim().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "ADVANCED_WHAT_IF" -> "whatIf";
            case "ADVANCED_ALERT" -> "alert";
            case "ADVANCED_FORECAST" -> "forecast";
            default -> "";
        };
    }

    private String advancedTypeLabel(String type) {
        return switch (type) {
            case "whatIf" -> "What-if 推演";
            case "alert" -> "智能预警";
            case "forecast" -> "时序预测";
            default -> "高级分析";
        };
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

    private String normalizeAdvancedSessionType(String advancedType) {
        String text = Objects.toString(advancedType, "").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.isBlank() || "all".equals(lower)) {
            return "";
        }
        if ("forecast".equals(lower) || "advanced_forecast".equals(lower)) {
            return "ADVANCED_FORECAST";
        }
        if ("whatif".equals(lower) || "what_if".equals(lower) || "advanced_what_if".equals(lower)) {
            return "ADVANCED_WHAT_IF";
        }
        if ("alert".equals(lower) || "advanced_alert".equals(lower)) {
            return "ADVANCED_ALERT";
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
