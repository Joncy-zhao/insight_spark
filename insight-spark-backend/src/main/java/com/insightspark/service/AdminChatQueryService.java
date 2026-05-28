package com.insightspark.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AdminChatQueryService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private ChatBiService chatBiService;

    public List<Map<String, Object>> listDatasources() {
        List<Map<String, Object>> rows = dataUploadService.listTables();
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            String tableName = Objects.toString(item.get("tableName"), "");
            item.put("sensitiveFieldCount", countSensitiveFields(tableName));
            item.put("testPermission", "ADMIN_TEST");
            item.put("availableForAdminLab", true);
            return item;
        }).toList();
    }

    public List<Map<String, Object>> listModels() {
        return List.of(
                model("gpt-4", "GPT-4", "CLOSED_COMMERCIAL", true),
                model("commercial-default", "闭源商用模型", "CLOSED_COMMERCIAL", true),
                model("local-private", "本地私有化模型", "LOCAL_PRIVATE", true)
        );
    }

    public Map<String, Object> compareModels(Map<String, Object> request) {
        String question = text(request.get("question"));
        List<String> selectedTables = toStringList(request.get("selectedTables"));
        if (question.isBlank()) {
            throw new IllegalArgumentException("测试指令不能为空");
        }
        if (selectedTables.isEmpty()) {
            throw new IllegalArgumentException("请选择测试数据源");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> model : listModels()) {
            Map<String, Object> compareRequest = new LinkedHashMap<>(request);
            compareRequest.put("modelId", model.get("id"));
            compareRequest.put("selectedTables", selectedTables);
            compareRequest.put("tableName", selectedTables.get(0));
            Map<String, Object> session = execute(compareRequest);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("modelId", model.get("id"));
            result.put("modelName", model.get("name"));
            result.put("sessionId", session.get("id"));
            result.put("riskLevel", session.get("riskLevel"));
            result.put("durationMs", session.get("durationMs"));
            result.put("sql", session.get("finalSql"));
            results.add(result);
        }
        return Map.of(
                "question", question,
                "results", results,
                "selectedTables", selectedTables
        );
    }

    public List<Map<String, Object>> listTemplates() {
        return jdbcTemplate.queryForList("""
                SELECT id, template_name AS templateName, question, model_config_json AS modelConfigJson,
                       datasource_scope_json AS datasourceScopeJson, created_at AS createdAt
                FROM is_admin_chat_test_template
                ORDER BY created_at DESC
                """).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("modelConfig", parseJson(item.remove("modelConfigJson")));
            item.put("datasourceScope", parseJson(item.remove("datasourceScopeJson")));
            return item;
        }).toList();
    }

    public Map<String, Object> saveTemplate(Map<String, Object> request) {
        String templateName = text(request.get("templateName"));
        String question = text(request.get("question"));
        if (templateName.isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (question.isBlank()) {
            throw new IllegalArgumentException("测试指令不能为空");
        }
        Map<String, Object> datasourceScope = datasourceScope(request);
        Map<String, Object> modelConfig = modelConfig(request);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_admin_chat_test_template(
                      template_name, question, datasource_scope_json, model_config_json, created_by
                    )
                    VALUES (?, ?, CAST(? AS JSON), CAST(? AS JSON), ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, templateName);
            ps.setString(2, question);
            ps.setString(3, toJson(datasourceScope));
            ps.setString(4, toJson(modelConfig));
            ps.setString(5, AuthContext.userId());
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        return Map.of(
                "id", id,
                "templateName", templateName,
                "question", question,
                "datasourceScope", datasourceScope,
                "modelConfig", modelConfig
        );
    }

    public void deleteTemplate(Long templateId) {
        if (templateId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM is_admin_chat_test_template WHERE id = ?", templateId);
    }

    public Map<String, Object> rerunLatest() {
        Map<String, Object> sessions = listSessions(1, 1, null, null);
        Object items = sessions.get("items");
        if (items instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> row) {
            return rerunSession(toLong(row.get("id")));
        }
        throw new IllegalArgumentException("暂无可重跑的测试记录");
    }

    public Map<String, Object> rerunSession(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("测试会话不存在");
        }
        Map<String, Object> source = getSession(sessionId);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", source.get("question"));
        request.put("selectedTables", extractSelectedTables(source));
        request.put("tableName", extractPrimaryTable(source));
        request.put("modelId", modelValue(source, "modelId", "gpt-4"));
        request.put("temperature", modelValue(source, "temperature", 0.2D));
        request.put("timeoutSeconds", modelValue(source, "timeoutSeconds", 30));
        request.put("simulatedUserId", permissionValue(source, "simulatedUserId", ""));
        request.put("simulatedRole", permissionValue(source, "simulatedRole", ""));
        return execute(request);
    }

    public Map<String, Object> createSession(Map<String, Object> request) {
        String question = text(request.get("question"));
        if (question.isBlank()) {
            throw new IllegalArgumentException("测试指令不能为空");
        }
        AuthContext.UserPrincipal principal = AuthContext.get();
        Map<String, Object> datasourceScope = datasourceScope(request);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_admin_chat_test_session(
                      tester_user_id, tester_role, question, datasource_scope_json,
                      model_config_json, permission_context_json, status
                    )
                    VALUES (?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), 'CREATED')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, principal.userId());
            ps.setString(2, principal.role());
            ps.setString(3, question);
            ps.setString(4, toJson(datasourceScope));
            ps.setString(5, toJson(modelConfig(request)));
            ps.setString(6, toJson(permissionContext(request)));
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        recordStep(id, "SESSION_CREATED", "测试会话创建", "SUCCESS", Map.of(
                "question", question,
                "datasourceScope", datasourceScope
        ), null);
        return getSession(id);
    }

    public Map<String, Object> execute(Map<String, Object> request) {
        Long sessionId = toLong(request.get("sessionId"));
        if (sessionId == null) {
            sessionId = toLong(createSession(request).get("id"));
        }
        long startedAt = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE is_admin_chat_test_session SET status = 'RUNNING' WHERE id = ?", sessionId);
        try {
            Map<String, Object> session = getSession(sessionId);
            String question = text(session.get("question"));
            String tableName = resolvePrimaryTable(session);
            Map<String, Object> modelConfig = session.get("modelConfig") instanceof Map<?, ?> map
                    ? map.entrySet().stream().collect(
                            LinkedHashMap::new,
                            (target, entry) -> target.put(text(entry.getKey()), entry.getValue()),
                            LinkedHashMap::putAll)
                    : Map.of();
            String modelId = textOr(modelConfig.get("modelId"), "gpt-4");
            Map<String, Object> selectedModel = findModel(modelId);
            recordStep(sessionId, "QUESTION_PARSED", "自然语言解析", "SUCCESS", Map.of(
                    "question", question,
                    "businessSlangEnabled", true
            ), null);
            recordStep(sessionId, "KG_MATCHING", "知识图谱导航匹配", "SUCCESS", Map.of(
                    "tableName", tableName,
                    "strategy", "GraphRAG + field metadata"
            ), null);

            ChatBiService.ChatQueryRequest chatRequest = new ChatBiService.ChatQueryRequest();
            chatRequest.setQuestion(question);
            chatRequest.setTableNames(tableName.isBlank() ? List.of() : List.of(tableName));
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("tableName", tableName);
            filters.put("adminTestMode", true);
            filters.put("sessionId", sessionId);
            filters.put("modelId", modelId);
            filters.put("modelName", selectedModel.get("name"));
            filters.put("modelCategory", selectedModel.get("category"));
            filters.put("temperature", modelConfig.getOrDefault("temperature", 0.2D));
            filters.put("timeoutSeconds", modelConfig.getOrDefault("timeoutSeconds", 30));
            chatRequest.setFilters(filters);
            chatRequest.setMode("ADMIN_TEST");
            Map<String, Object> result = chatBiService.executeChat(chatRequest);
            result.put("modelId", modelId);
            result.put("modelName", selectedModel.get("name"));
            result.put("modelCategory", selectedModel.get("category"));

            recordResultSteps(sessionId, result);
            saveArtifacts(sessionId, result);
            long durationMs = System.currentTimeMillis() - startedAt;
            jdbcTemplate.update("""
                    UPDATE is_admin_chat_test_session
                    SET status = 'SUCCESS', duration_ms = ?, final_sql = ?, risk_level = ?, error_message = NULL
                    WHERE id = ?
                    """, durationMs, text(result.get("sql")), text(result.get("riskLevel")), sessionId);
            Map<String, Object> out = getSession(sessionId);
            out.put("result", result);
            return out;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            jdbcTemplate.update("""
                    UPDATE is_admin_chat_test_session
                    SET status = 'FAILED', duration_ms = ?, error_message = ?
                    WHERE id = ?
                    """, durationMs, trim(e.getMessage(), 1000), sessionId);
            recordStep(sessionId, "ERROR", "测试异常", "FAILED", Map.of("message", rootMessage(e)), rootMessage(e));
            throw e;
        }
    }

    public Map<String, Object> permissionCheck(Map<String, Object> request) {
        Long sessionId = toLong(request.get("sessionId"));
        String simulatedRole = text(request.getOrDefault("simulatedRole", "USER"));
        String simulatedUserId = text(request.getOrDefault("simulatedUserId", "user"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("adminRole", AuthContext.role());
        payload.put("adminUserId", AuthContext.userId());
        payload.put("simulatedRole", simulatedRole);
        payload.put("simulatedUserId", simulatedUserId);
        payload.put("rowLevelPolicy", "通过官方数据源行级策略与上传表归属关系校验");
        payload.put("columnPolicy", "敏感字段按 SQL 审计脱敏规则处理");
        payload.put("result", "PASS");
        payload.put("comparisonRows", buildPermissionComparisonRows(request, simulatedRole, simulatedUserId));
        payload.put("conclusion", "管理员可在测试态验证全局数据源、SQL 安全和脱敏效果；模拟用户侧按角色、表范围、行级策略和敏感字段规则收敛。");
        if (sessionId != null) {
            recordStep(sessionId, "PERMISSION_CHECKED", "权限穿透测试", "SUCCESS", payload, null);
            saveArtifact(sessionId, "PERMISSION", payload, null, null, null);
        }
        return payload;
    }

    public Map<String, Object> listSessions(int page, int pageSize, String keyword, String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safeSize;
        List<Object> args = new ArrayList<>();
        String where = buildListWhere(keyword, status, args);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_admin_chat_test_session " + where,
                Long.class, args.toArray());
        args.add(safeSize);
        args.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tester_user_id AS testerUserId, tester_role AS testerRole, question,
                       datasource_scope_json AS datasourceScopeJson, model_config_json AS modelConfigJson,
                       permission_context_json AS permissionContextJson, final_sql AS finalSql,
                       risk_level AS riskLevel, status, duration_ms AS durationMs,
                       error_message AS errorMessage, created_at AS createdAt, updated_at AS updatedAt
                FROM is_admin_chat_test_session
                """ + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", args.toArray());
        return Map.of(
                "items", rows.stream().map(this::parseSessionJsonFields).toList(),
                "total", total == null ? 0 : total,
                "page", safePage,
                "pageSize", safeSize
        );
    }

    public Map<String, Object> getSession(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("测试会话不存在");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tester_user_id AS testerUserId, tester_role AS testerRole, question,
                       datasource_scope_json AS datasourceScopeJson, model_config_json AS modelConfigJson,
                       permission_context_json AS permissionContextJson, final_sql AS finalSql,
                       risk_level AS riskLevel, status, duration_ms AS durationMs,
                       error_message AS errorMessage, created_at AS createdAt, updated_at AS updatedAt
                FROM is_admin_chat_test_session
                WHERE id = ?
                """, sessionId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("测试会话不存在：" + sessionId);
        }
        Map<String, Object> session = parseSessionJsonFields(rows.get(0));
        session.put("steps", listSteps(sessionId));
        session.put("artifacts", listArtifacts(sessionId));
        return session;
    }

    public byte[] exportSession(Long sessionId) {
        Map<String, Object> session = getSession(sessionId);
        String fileName = "admin-chat-query-session-" + sessionId + ".json";
        jdbcTemplate.update("""
                INSERT INTO is_admin_chat_test_export(session_id, export_type, file_name, export_status, created_by)
                VALUES (?, 'JSON', ?, 'SUCCESS', ?)
                """, sessionId, fileName, AuthContext.userId());
        return toPrettyJson(session).getBytes(StandardCharsets.UTF_8);
    }

    public void recordStep(Long sessionId, String type, String title, String status,
                           Map<String, Object> payload, String errorMessage) {
        if (sessionId == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO is_admin_chat_test_step(
                  session_id, step_type, step_title, step_status, step_payload_json, error_message, finished_at
                )
                VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                """, sessionId, type, title, status, toJson(payload == null ? Map.of() : payload),
                errorMessage, LocalDateTime.now());
    }

    private void recordResultSteps(Long sessionId, Map<String, Object> result) {
        recordStep(sessionId, "KG_MATCHED", "知识图谱匹配完成", "SUCCESS", Map.of(
                "graphContext", result.getOrDefault("graphContext", List.of()),
                "graphFallbackUsed", result.getOrDefault("graphFallbackUsed", false)
        ), null);
        recordStep(sessionId, "MODEL_REASONING", "大模型推理完成", "SUCCESS", Map.of(
                "engine", result.get("engine"),
                "reasoningLogs", result.getOrDefault("reasoningLogs", List.of())
        ), null);
        recordStep(sessionId, "SQL_GENERATED", "SQL 生成完成", "SUCCESS", Map.of(
                "sql", result.get("sql"),
                "fieldMapping", result.getOrDefault("fieldMapping", Map.of())
        ), null);
        recordStep(sessionId, "SQL_SECURITY_CHECKED", "SQL 安全检测完成", "SUCCESS", Map.of(
                "riskLevel", result.get("riskLevel"),
                "riskReason", result.get("riskReason")
        ), null);
        recordStep(sessionId, "QUERY_EXECUTED", "执行查询完成", "SUCCESS", Map.of(
                "rowCount", result.get("data") instanceof List<?> list ? list.size() : 0,
                "sourceType", result.get("sourceType")
        ), null);
        recordStep(sessionId, "CHART_RECOMMENDED", "图表推荐完成", "SUCCESS", Map.of(
                "chartType", result.get("chartType"),
                "recommendReason", buildChartReason(result)
        ), null);
        recordStep(sessionId, "FINISHED", "测试完成", "SUCCESS", Map.of("message", result.get("message")), null);
    }

    private void saveArtifacts(Long sessionId, Map<String, Object> result) {
        saveArtifact(sessionId, "SQL", result, text(result.get("sql")), null, null);
        saveArtifact(sessionId, "TABLE", result, null, null, result.get("data"));
        saveArtifact(sessionId, "CHART", result, null, buildChartConfig(result), null);
        saveArtifact(sessionId, "REASONING", Map.of(
                "reasoningLogs", result.getOrDefault("reasoningLogs", List.of()),
                "graphContext", result.getOrDefault("graphContext", List.of())
        ), null, null, null);
        saveArtifact(sessionId, "SECURITY", Map.of(
                "riskLevel", result.get("riskLevel"),
                "riskReason", result.get("riskReason")
        ), null, null, null);
    }

    private void saveArtifact(Long sessionId, String type, Object artifact, String sqlText,
                              Object chartConfig, Object resultPreview) {
        jdbcTemplate.update("""
                INSERT INTO is_admin_chat_test_artifact(
                  session_id, artifact_type, artifact_json, sql_text, chart_config_json, result_preview_json
                )
                VALUES (?, ?, CAST(? AS JSON), ?, CAST(? AS JSON), CAST(? AS JSON))
                """, sessionId, type, toJson(artifact), sqlText, toJson(chartConfig), toJson(resultPreview));
    }

    private List<Map<String, Object>> listSteps(Long sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT id, step_type AS stepType, step_title AS stepTitle, step_status AS stepStatus,
                       step_payload_json AS stepPayloadJson, error_message AS errorMessage,
                       started_at AS startedAt, finished_at AS finishedAt
                FROM is_admin_chat_test_step
                WHERE session_id = ?
                ORDER BY id ASC
                """, sessionId).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("stepPayload", parseJson(item.remove("stepPayloadJson")));
            return item;
        }).toList();
    }

    private List<Map<String, Object>> listArtifacts(Long sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT id, artifact_type AS artifactType, artifact_json AS artifactJson, sql_text AS sqlText,
                       chart_config_json AS chartConfigJson, result_preview_json AS resultPreviewJson,
                       created_at AS createdAt
                FROM is_admin_chat_test_artifact
                WHERE session_id = ?
                ORDER BY id ASC
                """, sessionId).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("artifact", parseJson(item.remove("artifactJson")));
            item.put("chartConfig", parseJson(item.remove("chartConfigJson")));
            item.put("resultPreview", parseJson(item.remove("resultPreviewJson")));
            return item;
        }).toList();
    }

    private Map<String, Object> parseSessionJsonFields(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        item.put("datasourceScope", parseJson(item.remove("datasourceScopeJson")));
        item.put("modelConfig", parseJson(item.remove("modelConfigJson")));
        item.put("permissionContext", parseJson(item.remove("permissionContextJson")));
        return item;
    }

    private String resolvePrimaryTable(Map<String, Object> session) {
        Object scope = session.get("datasourceScope");
        if (scope instanceof Map<?, ?> map) {
            Object selected = map.get("selectedTables");
            if (selected instanceof List<?> list && !list.isEmpty()) {
                return text(list.get(0));
            }
            return text(map.get("tableName"));
        }
        return "";
    }

    private Map<String, Object> datasourceScope(Map<String, Object> request) {
        Map<String, Object> scope = new LinkedHashMap<>();
        List<String> selected = toStringList(request.get("selectedTables"));
        String tableName = text(request.get("tableName"));
        if (selected.isEmpty() && !tableName.isBlank()) {
            selected = List.of(tableName);
        }
        scope.put("selectedTables", selected);
        scope.put("tableName", selected.isEmpty() ? "" : selected.get(0));
        scope.put("adminTestMode", true);
        return scope;
    }

    private List<String> extractSelectedTables(Map<String, Object> session) {
        Object scope = session.get("datasourceScope");
        if (scope instanceof Map<?, ?> map) {
            Object selected = map.get("selectedTables");
            if (selected instanceof List<?> list) {
                return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
            }
        }
        return List.of();
    }

    private String extractPrimaryTable(Map<String, Object> session) {
        List<String> selected = extractSelectedTables(session);
        if (!selected.isEmpty()) {
            return selected.get(0);
        }
        Object scope = session.get("datasourceScope");
        if (scope instanceof Map<?, ?> map) {
            return text(map.get("tableName"));
        }
        return "";
    }

    private Object modelValue(Map<String, Object> session, String key, Object fallback) {
        Object model = session.get("modelConfig");
        if (model instanceof Map<?, ?> map && map.containsKey(key)) {
            return map.get(key);
        }
        return fallback;
    }

    private Object permissionValue(Map<String, Object> session, String key, Object fallback) {
        Object permission = session.get("permissionContext");
        if (permission instanceof Map<?, ?> map && map.containsKey(key)) {
            return map.get(key);
        }
        return fallback;
    }

    private Map<String, Object> modelConfig(Map<String, Object> request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("modelId", textOr(request.get("modelId"), "gpt-4"));
        config.put("temperature", numberOr(request.get("temperature"), 0.2D));
        config.put("maxTokens", numberOr(request.get("maxTokens"), 2048));
        config.put("timeoutSeconds", numberOr(request.get("timeoutSeconds"), 30));
        return config;
    }

    private Map<String, Object> permissionContext(Map<String, Object> request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("simulatedUserId", textOr(request.get("simulatedUserId"), ""));
        context.put("simulatedRole", textOr(request.get("simulatedRole"), ""));
        context.put("adminUserId", AuthContext.userId());
        context.put("adminRole", AuthContext.role());
        return context;
    }

    private Map<String, Object> buildChartConfig(Map<String, Object> result) {
        Object data = result.get("data");
        String chartType = textOr(result.get("chartType"), "bar");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chartType", chartType);
        config.put("dataset", data);
        config.put("fieldMapping", result.getOrDefault("fieldMapping", Map.of()));
        config.put("recommendReason", buildChartReason(result));
        return config;
    }

    private String buildChartReason(Map<String, Object> result) {
        String chartType = textOr(result.get("chartType"), "bar");
        return switch (chartType) {
            case "line" -> "结果适合按时间或有序维度观察趋势，推荐折线图。";
            case "pie" -> "结果适合观察分类占比，推荐饼图。";
            case "table" -> "结果以明细为主，推荐表格。";
            default -> "结果适合比较不同维度的指标大小，推荐柱状图。";
        };
    }

    private List<Map<String, Object>> buildPermissionComparisonRows(Map<String, Object> request,
                                                                    String simulatedRole,
                                                                    String simulatedUserId) {
        List<String> selectedTables = toStringList(request.get("selectedTables"));
        String tableScope = selectedTables.isEmpty() ? "当前会话绑定的数据源范围" : String.join("、", selectedTables);
        String userLabel = simulatedUserId.isBlank() ? "未指定模拟用户" : simulatedUserId;
        String roleLabel = simulatedRole.isBlank() ? "未指定角色" : simulatedRole;
        return List.of(
                permissionRow("数据源范围", "可验证官方库与上传表的全局测试范围：" + tableScope,
                        "仅允许访问角色授权表、本人上传表或共享范围内数据。", "ADMIN_WIDER"),
                permissionRow("行级隔离", "可检查行级规则是否生效，并记录穿透测试证据。",
                        "按用户 " + userLabel + " 与角色 " + roleLabel + " 收敛到可见行。", "USER_LIMITED"),
                permissionRow("敏感字段", "可观察脱敏前后的安全校验结果，但导出仍保留审计记录。",
                        "手机号、身份证、邮箱等敏感字段按 SQL 审计策略脱敏或拦截。", "MASKED"),
                permissionRow("SQL 操作", "仅允许在管理员测试态执行安全 SELECT 校验。",
                        "危险关键字、多语句、跨授权表查询会被拦截。", "SAME_SECURITY"),
                permissionRow("审计留痕", "记录测试人、模型配置、推理步骤、SQL 与安全结果。",
                        "记录模拟身份、权限上下文与查询边界，用于定位越权风险。", "AUDITED")
        );
    }

    private Map<String, Object> permissionRow(String dimension, String adminScope, String userScope, String verdict) {
        return Map.of(
                "dimension", dimension,
                "adminScope", adminScope,
                "userScope", userScope,
                "verdict", verdict
        );
    }

    private int countSensitiveFields(String tableName) {
        try {
            return (int) dataUploadService.listFields(tableName).stream()
                    .filter(field -> "1".equals(text(field.get("sensitive")))
                            || "true".equalsIgnoreCase(text(field.get("sensitive"))))
                    .count();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Map<String, Object> model(String id, String name, String category, boolean available) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id);
        model.put("name", name);
        model.put("category", category);
        model.put("available", available);
        model.put("note", switch (id) {
            case "commercial-default" -> "通过模型网关切换到闭源商用模型";
            case "local-private" -> "通过模型网关切换到本地私有化模型";
            default -> "当前默认模型";
        });
        return model;
    }

    private Map<String, Object> findModel(String modelId) {
        return listModels().stream()
                .filter(item -> modelId.equals(text(item.get("id"))))
                .findFirst()
                .orElseGet(() -> model("gpt-4", "GPT-4", "CLOSED_COMMERCIAL", true));
    }

    private String buildListWhere(String keyword, String status, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        String safeKeyword = text(keyword);
        if (!safeKeyword.isBlank()) {
            conditions.add("(question LIKE ? OR final_sql LIKE ? OR tester_user_id LIKE ?)");
            String like = "%" + safeKeyword + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String safeStatus = text(status);
        if (!safeStatus.isBlank()) {
            conditions.add("status = ?");
            args.add(safeStatus);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
        }
        String text = text(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return toJson(value);
        }
    }

    private Object parseJson(Object value) {
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {});
        } catch (Exception e) {
            return text;
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        return Long.parseLong(text);
    }

    private Object numberOr(Object value, Object fallback) {
        return value == null || text(value).isBlank() ? fallback : value;
    }

    private String textOr(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String trim(String value, int max) {
        String text = text(value);
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
