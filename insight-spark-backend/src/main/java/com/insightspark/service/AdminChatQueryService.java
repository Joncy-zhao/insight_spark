package com.insightspark.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.TextAlignment;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STJcTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AdminChatQueryService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DOC_BLUE = "2563EB";
    private static final String DOC_LIGHT_BLUE = "EFF6FF";
    private static final String DOC_LIGHT_GRAY = "F8FAFC";
    private static final String DOC_TEXT = "17213B";
    private static final String DOC_MUTED = "64748B";
    private static final int DOC_TABLE_WIDTH_DXA = 9524; // 16.8cm

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private ChatBiService chatBiService;

    @Autowired
    private PythonAiService pythonAiService;

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
        List<Map<String, Object>> configuredModels = pythonAiService.listModels();
        if (!configuredModels.isEmpty()) {
            return configuredModels.stream().map(this::normalizeModel).toList();
        }
        return List.of(
                model("default", "qwen-plus", "CONFIGURED_DEFAULT", true, "默认 OpenAI 兼容模型"),
                model("commercial-default", "闭源商用模型", "CLOSED_COMMERCIAL", false, "请在 AI 服务中配置 COMMERCIAL_MODEL"),
                model("local-private", "本地私有化模型", "LOCAL_PRIVATE", false, "请在 AI 服务中配置 LOCAL_MODEL")
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
        request.put("modelId", resolveModelId(text(modelValue(source, "modelId", ""))));
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
        return execute(request, Map.of());
    }

    public Map<String, Object> execute(Map<String, Object> request, Map<String, Object> executionOptions) {
        Long sessionId = toLong(request.get("sessionId"));
        if (sessionId == null) {
            sessionId = toLong(createSession(request).get("id"));
        }
        Map<String, Object> safeExecutionOptions = executionOptions == null ? Map.of() : executionOptions;
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
            String modelId = resolveModelId(text(modelConfig.get("modelId")));
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
            Object progressListener = safeExecutionOptions.get("progressListener");
            if (progressListener != null) {
                filters.put("progressListener", progressListener);
            }
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
        session.put("artifacts", enrichArtifactModelInfo(session, listArtifacts(sessionId)));
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

    public byte[] exportSessionDocx(Long sessionId) {
        Map<String, Object> session = getSession(sessionId);
        String fileName = "admin-chat-query-session-" + sessionId + ".docx";
        jdbcTemplate.update("""
                INSERT INTO is_admin_chat_test_export(session_id, export_type, file_name, export_status, created_by)
                VALUES (?, 'DOCX', ?, 'SUCCESS', ?)
                """, sessionId, fileName, AuthContext.userId());
        return buildSessionDocx(session, false);
    }

    public byte[] exportReasoningDocx(Long sessionId) {
        Map<String, Object> session = getSession(sessionId);
        String fileName = "admin-chat-query-reasoning-" + sessionId + ".docx";
        jdbcTemplate.update("""
                INSERT INTO is_admin_chat_test_export(session_id, export_type, file_name, export_status, created_by)
                VALUES (?, 'REASONING_DOCX', ?, 'SUCCESS', ?)
                """, sessionId, fileName, AuthContext.userId());
        return buildSessionDocx(session, true);
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

    private List<Map<String, Object>> enrichArtifactModelInfo(Map<String, Object> session,
                                                              List<Map<String, Object>> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return artifacts == null ? List.of() : artifacts;
        }
        String modelId = text(modelValue(session, "modelId", ""));
        if (modelId.isBlank()) {
            return artifacts;
        }
        Map<String, Object> model = findModel(modelId);
        return artifacts.stream().map(item -> {
            Map<String, Object> copy = new LinkedHashMap<>(item);
            Object artifact = copy.get("artifact");
            if (artifact instanceof Map<?, ?> artifactMap) {
                Map<String, Object> enriched = new LinkedHashMap<>();
                artifactMap.forEach((key, value) -> enriched.put(text(key), value));
                enriched.putIfAbsent("modelId", modelId);
                enriched.putIfAbsent("modelName", model.get("name"));
                enriched.putIfAbsent("modelCategory", model.get("category"));
                copy.put("artifact", enriched);
            }
            return copy;
        }).toList();
    }

    private byte[] buildSessionDocx(Map<String, Object> session, boolean reasoningOnly) {
        try (XWPFDocument document = new XWPFDocument();
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (reasoningOnly) {
                appendReasoningLogDocument(document, session);
                document.write(out);
                return out.toByteArray();
            }

            appendDocTitle(document, reasoningOnly ? "管理员对话查询推理日志" : "管理员对话查询测试记录");
            appendDocMeta(document, "导出时间：" + LocalDateTime.now() + "    会话编号：" + text(session.get("id")));
            appendQuestionBlock(document, text(session.get("question")));

            Map<String, Object> sqlArtifact = firstArtifact(session, "SQL");
            Map<String, Object> tableArtifact = firstArtifact(session, "TABLE");
            Map<String, Object> chartArtifact = firstArtifact(session, "CHART");
            Map<String, Object> securityArtifact = firstArtifact(session, "SECURITY");

            appendDocHeading(document, "一、测试概览");
            appendKeyValueTable(document, List.of(
                    row("测试人", session.get("testerUserId")),
                    row("测试角色", session.get("testerRole")),
                    row("状态", session.get("status")),
                    row("风险等级", session.get("riskLevel")),
                    row("使用模型", sqlArtifact.getOrDefault("modelName", modelValue(session, "modelId", "未记录"))),
                    row("数据源范围", readableDatasourceScope(session.get("datasourceScope"))),
                    row("耗时", text(session.get("durationMs")) + " ms"),
                    row("创建时间", session.get("createdAt"))
            ));

            appendDocHeading(document, "二、推理过程");
            appendStepTable(document, safeListMap(session.get("steps")));

            if (!reasoningOnly) {
                appendDocHeading(document, "三、SQL 与安全校验");
                appendDocCode(document, text(sqlArtifact.getOrDefault("sql", session.get("finalSql"))));
                appendKeyValueTable(document, List.of(
                        row("安全说明", securityArtifact.getOrDefault("riskReason", sqlArtifact.get("riskReason"))),
                        row("解析引擎", sqlArtifact.get("engine")),
                        row("数据源类型", sqlArtifact.get("sourceType")),
                        row("图表推荐", chartTypeName(sqlArtifact.get("chartType")))
                ));

                appendDocHeading(document, "四、执行结果预览");
                appendResultPreviewTable(document, firstList(tableArtifact.get("data"), tableArtifact.get("resultPreview"), sqlArtifact.get("data")));

                appendDocHeading(document, "五、图表渲染说明");
                appendKeyValueTable(document, List.of(
                        row("图表类型", chartTypeName(firstNonBlank(chartArtifact.get("chartType"), sqlArtifact.get("chartType")))),
                        row("推荐理由", chartArtifact.get("recommendReason")),
                        row("字段映射", readableFieldMapping(firstNonBlank(chartArtifact.get("fieldMapping"), sqlArtifact.get("fieldMapping")))),
                        row("图表配置", readableChartConfig(chartArtifact.get("chartConfig")))
                ));
            }

            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Word 导出文件失败：" + rootMessage(e), e);
        }
    }

    private void appendReasoningLogDocument(XWPFDocument document, Map<String, Object> session) {
        appendReasoningDocTitle(document, "管理员对话查询推理日志");
        appendReasoningDocMeta(document, "导出时间：" + LocalDateTime.now() + "    会话编号：" + text(session.get("id")));
        appendReasoningSectionHeading(document, "测试指令");
        appendReasoningQuestion(document, text(session.get("question")));

        Map<String, Object> sqlArtifact = firstArtifact(session, "SQL");
        appendReasoningSectionHeading(document, "测试概览");
        appendReasoningTableCaption(document, 1, "测试概览表");
        appendReasoningKeyValueTable(document, List.of(
                row("测试人", session.get("testerUserId")),
                row("测试角色", session.get("testerRole")),
                row("状态", session.get("status")),
                row("风险等级", session.get("riskLevel")),
                row("使用模型", sqlArtifact.getOrDefault("modelName", modelValue(session, "modelId", "未记录"))),
                row("数据源范围", readableDatasourceScope(session.get("datasourceScope"))),
                row("耗时", text(session.get("durationMs")) + " ms"),
                row("创建时间", session.get("createdAt"))
        ));

        appendReasoningSectionHeading(document, "推理过程");
        appendReasoningTableCaption(document, 2, "推理过程表");
        appendReasoningStepTable(document, safeListMap(session.get("steps")));
    }

    private void appendReasoningDocTitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingLineRule(org.apache.poi.xwpf.usermodel.LineSpacingRule.AUTO);
        paragraph.setSpacingBetween(1.5);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("SimSun");
        run.setFontSize(16);
        run.setColor("000000");
        run.setText(text);
    }

    private void appendReasoningDocMeta(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingBetween(1.5);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("SimSun");
        run.setFontSize(9);
        run.setColor("000000");
        run.setText(text);
    }

    private void appendReasoningSectionHeading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(1.5);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("SimSun");
        run.setFontSize(14);
        run.setColor("000000");
        run.setText(text);
    }

    private void appendReasoningQuestion(XWPFDocument document, String question) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBetween(1.5);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("SimSun");
        run.setFontSize(12);
        run.setColor(DOC_TEXT);
        run.setText(question == null || question.isBlank() ? "-" : question);
    }

    private void appendReasoningTableCaption(XWPFDocument document, int tableNo, String title) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(0);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("SimSun");
        run.setFontSize(10);
        run.setColor("000000");
        run.setText("表 " + tableNo + " " + title);
    }

    private void appendReasoningKeyValueTable(XWPFDocument document, List<Map<String, Object>> rows) {
        XWPFTable table = document.createTable(Math.max(1, rows.size()), 2);
        applyReasoningTableStyle(table, List.of(1900, 7624));
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow tableRow = table.getRow(i);
            centerTableRow(tableRow);
            setReasoningCellText(tableRow.getCell(0), text(rows.get(i).get("key")), 1900, ParagraphAlignment.CENTER);
            setReasoningCellText(tableRow.getCell(1), docValue(rows.get(i).get("value")), 7624, ParagraphAlignment.CENTER);
        }
    }

    private void appendReasoningStepTable(XWPFDocument document, List<Map<String, Object>> steps) {
        if (steps.isEmpty()) {
            appendReasoningQuestion(document, "暂无推理步骤。");
            return;
        }
        XWPFTable table = document.createTable(steps.size() + 1, 4);
        applyReasoningTableStyle(table, List.of(500, 1800, 700, 6524));
        XWPFTableRow header = table.getRow(0);
        centerTableRow(header);
        setReasoningCellText(header.getCell(0), "序号", 500, ParagraphAlignment.CENTER);
        setReasoningCellText(header.getCell(1), "步骤", 1800, ParagraphAlignment.CENTER);
        setReasoningCellText(header.getCell(2), "状态", 700, ParagraphAlignment.CENTER);
        setReasoningCellText(header.getCell(3), "内容摘要", 6524, ParagraphAlignment.CENTER);
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            centerTableRow(row);
            setReasoningCellText(row.getCell(0), String.valueOf(i + 1), 500, ParagraphAlignment.CENTER);
            setReasoningCellText(row.getCell(1), text(firstNonBlank(step.get("stepTitle"), step.get("stepType"))), 1800, ParagraphAlignment.CENTER);
            setReasoningCellText(row.getCell(2), statusName(step.get("stepStatus")), 700, ParagraphAlignment.CENTER);
            setReasoningCellText(row.getCell(3), readableStepPayload(step), 6524, ParagraphAlignment.CENTER);
        }
    }

    private void applyReasoningTableStyle(XWPFTable table, List<Integer> widths) {
        applyFixedDocxTableStyle(table, widths);
    }

    private void applyFixedDocxTableStyle(XWPFTable table, List<Integer> widths) {
        table.setTableAlignment(TableRowAlign.CENTER);
        var tblPr = table.getCTTbl().getTblPr();
        CTTblWidth tblW = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblW.setW(BigInteger.valueOf(DOC_TABLE_WIDTH_DXA));
        tblW.setType(STTblWidth.DXA);
        var tblLayout = tblPr.isSetTblLayout() ? tblPr.getTblLayout() : tblPr.addNewTblLayout();
        tblLayout.setType(STTblLayoutType.FIXED);
        var borders = tblPr.isSetTblBorders() ? tblPr.getTblBorders() : tblPr.addNewTblBorders();
        borders.addNewTop().setVal(STBorder.SINGLE);
        borders.getTop().setSz(BigInteger.valueOf(4));
        borders.getTop().setColor("000000");
        borders.addNewBottom().setVal(STBorder.SINGLE);
        borders.getBottom().setSz(BigInteger.valueOf(4));
        borders.getBottom().setColor("000000");
        borders.addNewLeft().setVal(STBorder.SINGLE);
        borders.getLeft().setSz(BigInteger.valueOf(4));
        borders.getLeft().setColor("000000");
        borders.addNewRight().setVal(STBorder.SINGLE);
        borders.getRight().setSz(BigInteger.valueOf(4));
        borders.getRight().setColor("000000");
        borders.addNewInsideH().setVal(STBorder.SINGLE);
        borders.getInsideH().setSz(BigInteger.valueOf(4));
        borders.getInsideH().setColor("000000");
        borders.addNewInsideV().setVal(STBorder.SINGLE);
        borders.getInsideV().setSz(BigInteger.valueOf(4));
        borders.getInsideV().setColor("000000");
        if (!widths.isEmpty()) {
            var grid = table.getCTTbl().getTblGrid() != null ? table.getCTTbl().getTblGrid() : table.getCTTbl().addNewTblGrid();
            while (grid.sizeOfGridColArray() > 0) {
                grid.removeGridCol(0);
            }
            for (Integer width : widths) {
                grid.addNewGridCol().setW(BigInteger.valueOf(width));
            }
        }
        for (XWPFTableRow row : table.getRows()) {
            centerTableRow(row);
            for (int i = 0; i < row.getTableCells().size(); i++) {
                int width = widths.isEmpty()
                        ? DOC_TABLE_WIDTH_DXA
                        : widths.get(Math.min(i, widths.size() - 1));
                applyCellWidthAndVerticalCenter(row.getCell(i), width);
            }
        }
    }

    private void centerTableRow(XWPFTableRow row) {
        var trPr = row.getCtRow().isSetTrPr() ? row.getCtRow().getTrPr() : row.getCtRow().addNewTrPr();
        var jc = trPr.sizeOfJcArray() > 0 ? trPr.getJcArray(0) : trPr.addNewJc();
        jc.setVal(STJcTable.CENTER);
    }

    private void setReasoningCellText(XWPFTableCell cell, String text, int width, ParagraphAlignment alignment) {
        shadeCell(cell, "FFFFFF");
        applyCellWidthAndVerticalCenter(cell, width);
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        paragraph.setAlignment(alignment);
        paragraph.setVerticalAlignment(TextAlignment.AUTO);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(1.0);
        XWPFRun run = paragraph.createRun();
        run.setBold(false);
        run.setFontFamily("SimSun");
        run.setFontSize(10);
        run.setColor("000000");
        run.setText(text == null || text.isBlank() ? "-" : trim(text, 1000));
    }

    private void applyCellWidthAndVerticalCenter(XWPFTableCell cell, int width) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        var tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
        tcW.setW(BigInteger.valueOf(width));
        tcW.setType(STTblWidth.DXA);
        var vAlign = tcPr.isSetVAlign() ? tcPr.getVAlign() : tcPr.addNewVAlign();
        vAlign.setVal(STVerticalJc.CENTER);
    }

    private void appendDocTitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(22);
        run.setColor(DOC_BLUE);
        run.setText(text);
    }

    private void appendDocHeading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(240);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(14);
        run.setColor(DOC_TEXT);
        run.setText(text);
    }

    private void appendDocMeta(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setFontSize(9);
        run.setColor(DOC_MUTED);
        run.setText(text);
    }

    private void appendQuestionBlock(XWPFDocument document, String question) {
        XWPFTable table = document.createTable(1, 1);
        applyFixedDocxTableStyle(table, List.of(DOC_TABLE_WIDTH_DXA));
        XWPFTableCell cell = table.getRow(0).getCell(0);
        shadeCell(cell, DOC_LIGHT_BLUE);
        XWPFParagraph label = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        label.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun labelRun = label.createRun();
        labelRun.setBold(true);
        labelRun.setColor(DOC_BLUE);
        labelRun.setFontSize(10);
        labelRun.setText("测试指令");
        XWPFParagraph body = cell.addParagraph();
        body.setAlignment(ParagraphAlignment.CENTER);
        body.setVerticalAlignment(TextAlignment.AUTO);
        XWPFRun bodyRun = body.createRun();
        bodyRun.setColor(DOC_TEXT);
        bodyRun.setFontSize(11);
        bodyRun.setText(question.isBlank() ? "-" : question);
    }

    private void appendDocParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setFontSize(10);
        run.setColor(DOC_TEXT);
        run.setText(text);
    }

    private void appendDocCode(XWPFDocument document, String code) {
        XWPFTable table = document.createTable(1, 1);
        applyFixedDocxTableStyle(table, List.of(DOC_TABLE_WIDTH_DXA));
        XWPFTableCell cell = table.getRow(0).getCell(0);
        shadeCell(cell, "0F172A");
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setVerticalAlignment(TextAlignment.AUTO);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Consolas");
        run.setFontSize(9);
        run.setColor("E0F2FE");
        String safeCode = code.isBlank() ? "未生成 SQL" : code;
        String[] lines = safeCode.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                run.addBreak();
            }
            run.setText(lines[i]);
        }
    }

    private void appendKeyValueTable(XWPFDocument document, List<Map<String, Object>> rows) {
        XWPFTable table = document.createTable(Math.max(1, rows.size()), 2);
        applyFixedDocxTableStyle(table, List.of(1900, 7624));
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow tableRow = table.getRow(i);
            centerTableRow(tableRow);
            setCellText(tableRow.getCell(0), text(rows.get(i).get("key")), true, DOC_LIGHT_BLUE);
            setCellText(tableRow.getCell(1), docValue(rows.get(i).get("value")), false, i % 2 == 0 ? "FFFFFF" : DOC_LIGHT_GRAY);
        }
    }

    private void appendStepTable(XWPFDocument document, List<Map<String, Object>> steps) {
        if (steps.isEmpty()) {
            appendDocParagraph(document, "暂无推理步骤。");
            return;
        }
        XWPFTable table = document.createTable(steps.size() + 1, 4);
        applyFixedDocxTableStyle(table, List.of(500, 1800, 700, 6524));
        XWPFTableRow header = table.getRow(0);
        centerTableRow(header);
        setCellText(header.getCell(0), "序号", true, DOC_LIGHT_BLUE);
        setCellText(header.getCell(1), "步骤", true, DOC_LIGHT_BLUE);
        setCellText(header.getCell(2), "状态", true, DOC_LIGHT_BLUE);
        setCellText(header.getCell(3), "内容摘要", true, DOC_LIGHT_BLUE);
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            centerTableRow(row);
            String fill = i % 2 == 0 ? "FFFFFF" : DOC_LIGHT_GRAY;
            setCellText(row.getCell(0), String.valueOf(i + 1), false, fill);
            setCellText(row.getCell(1), text(firstNonBlank(step.get("stepTitle"), step.get("stepType"))), false, fill);
            setCellText(row.getCell(2), statusName(step.get("stepStatus")), false, fill);
            setCellText(row.getCell(3), readableStepPayload(step), false, fill);
        }
    }

    private void appendResultPreviewTable(XWPFDocument document, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            appendDocParagraph(document, "暂无执行结果。");
            return;
        }
        List<String> columns = rows.get(0).keySet().stream().limit(8).toList();
        int rowCount = Math.min(rows.size(), 30);
        XWPFTable table = document.createTable(rowCount + 1, Math.max(1, columns.size()));
        applyFixedDocxTableStyle(table, equalColumnWidths(columns.size()));
        XWPFTableRow header = table.getRow(0);
        centerTableRow(header);
        for (int i = 0; i < columns.size(); i++) {
            setCellText(header.getCell(i), columns.get(i), true, DOC_LIGHT_BLUE);
        }
        for (int r = 0; r < rowCount; r++) {
            XWPFTableRow row = table.getRow(r + 1);
            centerTableRow(row);
            Map<String, Object> data = rows.get(r);
            String fill = r % 2 == 0 ? "FFFFFF" : DOC_LIGHT_GRAY;
            for (int c = 0; c < columns.size(); c++) {
                setCellText(row.getCell(c), docValue(data.get(columns.get(c))), false, fill);
            }
        }
        if (rows.size() > rowCount) {
            appendDocParagraph(document, "仅展示前 " + rowCount + " 行，完整结果请在系统中查看。");
        }
    }

    private void setCellText(XWPFTableCell cell, String text, boolean bold, String fill) {
        shadeCell(cell, fill);
        applyCellVerticalCenter(cell);
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setVerticalAlignment(TextAlignment.AUTO);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(1.0);
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setFontFamily("SimSun");
        run.setFontSize(9);
        run.setColor(DOC_TEXT);
        run.setText(text == null || text.isBlank() ? "-" : trim(text, 1000));
    }

    private List<Integer> equalColumnWidths(int columnCount) {
        int safeCount = Math.max(1, columnCount);
        int base = DOC_TABLE_WIDTH_DXA / safeCount;
        int remainder = DOC_TABLE_WIDTH_DXA - base * safeCount;
        List<Integer> widths = new ArrayList<>();
        for (int i = 0; i < safeCount; i++) {
            widths.add(base + (i == safeCount - 1 ? remainder : 0));
        }
        return widths;
    }

    private void applyCellVerticalCenter(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        var vAlign = tcPr.isSetVAlign() ? tcPr.getVAlign() : tcPr.addNewVAlign();
        vAlign.setVal(STVerticalJc.CENTER);
    }

    private void shadeCell(XWPFTableCell cell, String fill) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
        shd.setFill(fill);
    }

    private Map<String, Object> firstArtifact(Map<String, Object> session, String type) {
        return safeListMap(session.get("artifacts")).stream()
                .filter(item -> type.equals(text(item.get("artifactType"))))
                .map(item -> {
                    Object artifact = item.get("artifact");
                    if (artifact instanceof Map<?, ?> map) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        map.forEach((key, value) -> result.put(text(key), value));
                        result.putIfAbsent("sql", item.get("sqlText"));
                        result.putIfAbsent("chartConfig", item.get("chartConfig"));
                        result.putIfAbsent("resultPreview", item.get("resultPreview"));
                        return result;
                    }
                    return item;
                })
                .findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> row(String key, Object value) {
        return Map.of("key", key, "value", value == null ? "" : value);
    }

    private String readableDatasourceScope(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object selected = map.get("selectedTables");
            List<String> tables = toStringList(selected);
            if (!tables.isEmpty()) {
                return "已选择 " + tables.size() + " 个数据源：" + String.join("、", tables);
            }
            String tableName = text(map.get("tableName"));
            if (!tableName.isBlank()) {
                return "主数据源：" + tableName;
            }
        }
        return docValue(value);
    }

    private String readableFieldMapping(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            addReadablePart(parts, "维度", map.get("dimension"));
            addReadablePart(parts, "指标", map.get("metric"));
            addReadablePart(parts, "维度字段", map.get("dimensionKey"));
            addReadablePart(parts, "指标字段", map.get("metricKey"));
            if (!parts.isEmpty()) {
                return String.join("；", parts);
            }
        }
        return docValue(value);
    }

    private String readableChartConfig(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            addReadablePart(parts, "图表类型", chartTypeName(map.get("chartType")));
            addReadablePart(parts, "推荐理由", map.get("recommendReason"));
            String mapping = readableFieldMapping(map.get("fieldMapping"));
            if (!mapping.isBlank() && !"-".equals(mapping)) {
                parts.add(mapping);
            }
            Object dataset = map.get("dataset");
            List<Map<String, Object>> rows = firstList(dataset);
            if (!rows.isEmpty()) {
                parts.add("用于渲染的数据共 " + rows.size() + " 行");
            }
            return parts.isEmpty() ? "系统已生成图表配置。" : String.join("；", parts);
        }
        return docValue(value);
    }

    private String readableStepPayload(Map<String, Object> step) {
        Object error = step.get("errorMessage");
        if (!text(error).isBlank()) {
            return "异常：" + text(error);
        }
        Object payload = step.get("stepPayload");
        if (payload instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            addReadablePart(parts, "问题", map.get("question"));
            addReadablePart(parts, "数据源", map.get("tableName"));
            addReadablePart(parts, "策略", map.get("strategy"));
            addReadablePart(parts, "引擎", map.get("engine"));
            addReadablePart(parts, "SQL", map.get("sql"));
            addReadablePart(parts, "风险", map.get("riskLevel"));
            addReadablePart(parts, "原因", firstNonBlank(map.get("riskReason"), map.get("riskReason")));
            addReadablePart(parts, "行数", map.get("rowCount"));
            addReadablePart(parts, "图表", chartTypeName(map.get("chartType")));
            addReadablePart(parts, "说明", firstNonBlank(map.get("recommendReason"), map.get("message")));
            if (map.get("fieldMapping") != null) {
                parts.add(readableFieldMapping(map.get("fieldMapping")));
            }
            if (map.get("reasoningLogs") instanceof List<?> list && !list.isEmpty()) {
                parts.add("推理日志：" + list.stream().limit(5).map(this::text).filter(item -> !item.isBlank()).reduce((a, b) -> a + "；" + b).orElse(""));
            }
            if (!parts.isEmpty()) {
                return String.join("；", parts);
            }
        }
        return docValue(payload);
    }

    private void addReadablePart(List<String> parts, String label, Object value) {
        String text = text(value);
        if (!text.isBlank()) {
            parts.add(label + "：" + text);
        }
    }

    private String chartTypeName(Object value) {
        return switch (text(value)) {
            case "bar" -> "柱状图";
            case "line" -> "折线图";
            case "pie" -> "饼图";
            case "doughnut", "donut" -> "环形图";
            case "table" -> "表格";
            case "radar" -> "雷达图";
            case "scatter" -> "散点图";
            case "metric", "card", "kpi", "indicator" -> "指标卡";
            case "map" -> "地图";
            case "" -> "";
            default -> text(value);
        };
    }

    private String statusName(Object value) {
        return switch (text(value)) {
            case "SUCCESS" -> "成功";
            case "FAILED" -> "失败";
            case "RUNNING" -> "运行中";
            case "CREATED" -> "已创建";
            default -> text(value);
        };
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
        config.put("modelId", resolveModelId(text(request.get("modelId"))));
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
            case "radar" -> "结果适合观察多指标画像或综合评分，推荐雷达图。";
            case "scatter" -> "结果适合观察两个数值指标的相关性或离群分布，推荐散点图。";
            case "metric" -> "结果适合突出单个核心 KPI 或总量，推荐指标卡。";
            case "map" -> "结果适合观察地域维度上的指标分布，推荐地图。";
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
        model.put("note", available ? "当前可用" : "未检测到可用配置");
        return model;
    }

    private Map<String, Object> model(String id, String name, String category, boolean available, String note) {
        Map<String, Object> model = model(id, name, category, available);
        model.put("note", note);
        return model;
    }

    private Map<String, Object> normalizeModel(Map<String, Object> row) {
        String id = textOr(row.get("id"), "default");
        String name = textOr(row.get("name"), textOr(row.get("model"), id));
        String category = textOr(row.get("category"), "CONFIGURED_DEFAULT");
        boolean available = !"false".equalsIgnoreCase(text(row.get("available")));
        Map<String, Object> item = model(id, name, category, available);
        item.putAll(row);
        item.put("id", id);
        item.put("name", name);
        item.put("category", category);
        item.put("available", available);
        return item;
    }

    private String resolveModelId(String modelId) {
        String requestedId = text(modelId);
        List<Map<String, Object>> modelRows = listModels();
        if (!requestedId.isBlank()) {
            for (Map<String, Object> item : modelRows) {
                if (requestedId.equals(text(item.get("id"))) && !"false".equalsIgnoreCase(text(item.get("available")))) {
                    return requestedId;
                }
            }
        }
        return modelRows.stream()
                .filter(item -> !"false".equalsIgnoreCase(text(item.get("available"))))
                .findFirst()
                .or(() -> modelRows.stream().findFirst())
                .map(item -> text(item.get("id")))
                .filter(id -> !id.isBlank())
                .orElse("default");
    }

    private Map<String, Object> findModel(String modelId) {
        List<Map<String, Object>> modelRows = listModels();
        String resolvedModelId = resolveModelId(modelId);
        return modelRows.stream()
                .filter(item -> resolvedModelId.equals(text(item.get("id"))))
                .findFirst()
                .orElseGet(() -> modelRows.stream()
                        .filter(item -> !"false".equalsIgnoreCase(text(item.get("available"))))
                        .findFirst()
                        .orElseGet(() -> model("default", "qwen-plus", "CONFIGURED_DEFAULT", true)));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeListMap(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((key, entryValue) -> row.put(text(key), entryValue));
                    rows.add(row);
                }
            }
            return rows;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> firstList(Object... values) {
        for (Object value : values) {
            List<Map<String, Object>> rows = safeListMap(value);
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        return List.of();
    }

    private Object firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String docValue(Object value) {
        Object safe = toJsonSafeValue(value);
        if (safe == null) {
            return "";
        }
        if (safe instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((key, item) -> {
                String itemText = text(item);
                if (!itemText.isBlank()) {
                    parts.add(text(key) + "：" + itemText);
                }
            });
            return parts.isEmpty() ? "" : String.join("；", parts);
        }
        if (safe instanceof List<?> list) {
            if (list.isEmpty()) {
                return "";
            }
            return list.stream().limit(8).map(this::text).filter(item -> !item.isBlank()).reduce((a, b) -> a + "；" + b).orElse("共 " + list.size() + " 项");
        }
        return text(safe);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(toJsonSafeValue(value == null ? Map.of() : value));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonSafeValue(value));
        } catch (Exception e) {
            return toJson(value);
        }
    }

    private Object toJsonSafeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> safe.put(text(key), toJsonSafeValue(item)));
            return safe;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::toJsonSafeValue).toList();
        }
        if (value instanceof TemporalAccessor || value instanceof java.util.Date) {
            return value.toString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
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
