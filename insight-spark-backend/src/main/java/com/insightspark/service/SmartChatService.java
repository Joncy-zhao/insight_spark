package com.insightspark.service;

import com.insightspark.c.service.StackCDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class SmartChatService {

    @Autowired
    private ChatBiService chatBiService;

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private AdvancedAnalysisService advancedAnalysisService;

    @Autowired
    private BusinessModelAgentService businessModelAgentService;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private StackCDashboardService stackCDashboardService;

    public Map<String, Object> executeSmart(ChatBiService.ChatQueryRequest request) {
        ChatBiService.ChatQueryRequest safeRequest = request == null ? new ChatBiService.ChatQueryRequest() : request;
        String question = text(safeRequest.getQuestion());
        String rawQuestion = text(safeRequest.getFilters() == null ? null : safeRequest.getFilters().get("rawQuestion"));
        String actionQuestion = rawQuestion.isBlank() ? question : rawQuestion;
        String tableName = resolveTableName(safeRequest);
        SmartIntent intent = route(actionQuestion, tableName).withContext(safeRequest.getFilters());
        Map<String, Object> result;
        switch (intent.primaryIntent()) {
            case "FORECAST" -> result = executeForecast(actionQuestion, tableName, intent);
            case "ALERT_RULE_CREATE" -> result = buildAlertRuleDraft(actionQuestion, tableName, intent);
            case "WHAT_IF" -> result = buildWhatIfDraft(actionQuestion, tableName, intent);
            case "BUSINESS_MODEL_CREATE", "BUSINESS_MODEL_PATCH", "BUSINESS_MODEL_APPLY", "BUSINESS_MODEL_PUBLISH" ->
                    result = executeBusinessModelAgent(actionQuestion, tableName, intent);
            case "DASHBOARD_PIN" -> result = executeDashboardPin(actionQuestion, tableName, intent, safeRequest);
            case "DASHBOARD_CREATE", "CHART_RULE_UPDATE", "FIELD_SEMANTIC_FIX",
                    "FEDERATED_QUERY", "PERMISSION_POLICY_CREATE", "AUDIT_QUERY", "REPORT_GENERATE",
                    "TASK_STATUS_QUERY", "COLLABORATION_INVITE", "CLARIFY" ->
                    result = buildClarificationResult(actionQuestion, tableName, intent);
            default -> result = executeQuery(safeRequest, question, tableName, intent);
        }
        attachSmartMetadata(result, intent, actionQuestion, tableName);
        return result;
    }

    private SmartIntent route(String question, String tableName) {
        String q = text(question);
        String lower = q.toLowerCase(Locale.ROOT);
        Map<String, Object> context = buildAdvancedContext(tableName);
        Optional<Map<String, Object>> smartRoute = pythonAiService.smartChatRoute(question, tableName, context);
        if (smartRoute != null && smartRoute.isPresent()) {
            Map<String, Object> routed = smartRoute.get();
            String primaryIntent = normalizeSmartIntent(routed.get("primaryIntent"));
            double confidence = readDouble(routed.get("confidence"), 0.0D);
            if (!"CLARIFY".equals(primaryIntent) && confidence >= 0.55D) {
                return new SmartIntent(
                        primaryIntent,
                        confidence,
                        readBoolean(routed.get("requiresConfirmation")),
                        firstText(routed.get("reasoning"), "AI 全局语义路由命中 " + primaryIntent),
                        mapValue(routed.get("slots")),
                        readBoolean(routed.get("fallbackUsed"))
                );
            }
        }

        if (isBusinessModelIntent(q)) {
            return new SmartIntent(inferBusinessModelIntent(q), 0.62D, false,
                    "AI 总路由不可用，保守兜底识别到业务模型语义", Map.of(), true);
        }
        if (isDashboardIntent(q)) {
            return new SmartIntent(q.contains("新建") ? "DASHBOARD_CREATE" : "DASHBOARD_PIN", 0.6D, true,
                    "AI 总路由不可用，保守兜底识别到看板资产操作", Map.of(), true);
        }
        if (isExplicitQueryIntent(q)) {
            return new SmartIntent("QUERY_SQL", 0.62D, false,
                    "AI 总路由不可用，保守兜底识别到查询/排名/分布语义", Map.of(), true);
        }
        if (isHistoricalTrendQuery(q)) {
            return new SmartIntent("QUERY_SQL", 0.62D, false,
                    "AI 总路由不可用，保守兜底识别到历史趋势查询", Map.of(), true);
        }
        if (isAlertIntent(q, lower)) {
            return new SmartIntent("ALERT_RULE_CREATE", 0.72D, true,
                    "AI 总路由不可用，保守兜底识别到预警/提醒语义", Map.of(), true);
        }

        Optional<Map<String, Object>> advanced = pythonAiService.parseAdvancedAnalysisIntent(question, tableName, context);
        if (advanced != null && advanced.isPresent()) {
            String intent = normalizeAdvancedIntent(advanced.get().get("intent"));
            if (!"NONE".equals(intent)) {
                return new SmartIntent(intent, 0.82D, false,
                        "AI 高级分析语义解析命中 " + intent,
                        advanced.get(), false);
            }
        }

        if (isAlertIntent(q, lower)) {
            return new SmartIntent("ALERT_RULE_CREATE", 0.72D, true,
                    "规则兜底识别到预警/提醒语义", Map.of(), true);
        }
        if (containsAny(q, "预测", "预估", "未来", "走势", "趋势延伸", "大概能到")
                || lower.contains("forecast") || lower.contains("prediction")) {
            return new SmartIntent("FORECAST", 0.74D, false,
                    "规则兜底识别到预测语义", Map.of(), true);
        }
        if (containsAny(q, "如果", "假设", "推演", "模拟", "提升", "下降", "降低", "增长", "what-if")
                || lower.contains("what-if") || lower.contains("whatif")) {
            return new SmartIntent("WHAT_IF", 0.68D, true,
                    "规则兜底识别到情景推演语义", Map.of(), true);
        }
        if (containsAny(q, "权限", "只能看", "开放给", "角色", "授权")) {
            return new SmartIntent("PERMISSION_POLICY_CREATE", 0.65D, true,
                    "识别到权限配置语义，必须确认后执行", Map.of(), true);
        }
        if (containsAny(q, "审计", "危险查询", "慢查询", "为什么被拦截")) {
            return new SmartIntent("AUDIT_QUERY", 0.68D, false,
                    "识别到审计/安全治理语义，当前阶段生成澄清入口", Map.of(), true);
        }
        if (containsAny(q, "诊断", "报告", "原因分析", "下降原因")) {
            return new SmartIntent("REPORT_GENERATE", 0.66D, true,
                    "识别到诊断报告语义，当前阶段生成待确认动作", Map.of(), true);
        }
        return new SmartIntent("QUERY_SQL", 0.86D, false,
                "默认进入 Text-to-SQL 查询", Map.of(), true);
    }

    private Map<String, Object> executeQuery(ChatBiService.ChatQueryRequest request, String question,
                                             String tableName, SmartIntent intent) {
        Map<String, Object> filters = new LinkedHashMap<>(request.getFilters() == null ? Map.of() : request.getFilters());
        filters.put("autoForecastEnabled", false);
        request.setFilters(filters);
        Map<String, Object> result = chatBiService.executeChat(request);
        result.put("responseType", "QUERY_SQL");
        result.put("smartRouted", true);
        result.put("message", firstText(result.get("message"), "已按数据查询意图完成分析。"));
        return result;
    }

    private Map<String, Object> executeForecast(String question, String tableName, SmartIntent intent) {
        List<Map<String, Object>> fields = safeFields(tableName);
        String timeField = chooseTimeField(fields, question, firstText(
                intent.slots().get("timeField"), intent.slots().get("dateField"), intent.slots().get("dimensionField")));
        String metricField = chooseMetricField(fields, question, firstText(
                intent.slots().get("metricField"), intent.slots().get("metric"), intent.slots().get("targetMetric")));
        if (timeField.isBlank() || metricField.isBlank()) {
            SmartIntent clarification = intent.withClarification("FORECAST", "预测需要时间字段和数值指标，请先选择或补充字段。");
            return buildClarificationResult(question, tableName, clarification);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tableName", tableName);
        request.put("timeField", timeField);
        request.put("metricField", metricField);
        request.put("granularity", inferGranularity(question));
        request.put("horizon", normalizeHorizon(intent.slots().get("horizon"), question));
        request.put("algorithm", firstText(intent.slots().get("algorithm"), "Holt-Winters"));
        request.put("sourceQuestion", question);
        Map<String, Object> forecast = advancedAnalysisService.forecast(request);
        return advancedToChatResult(forecast, "FORECAST", "已根据语义直接触发时序预测。");
    }

    private Map<String, Object> buildAlertRuleDraft(String question, String tableName, SmartIntent intent) {
        List<Map<String, Object>> fields = safeFields(tableName);
        String timeField = firstText(intent.slots().get("timeField"), chooseField(fields, "DATE", question));
        String metricField = firstText(intent.slots().get("metricField"), chooseMetricField(fields, question), text(intent.slots().get("metric")));
        Object threshold = firstPresent(intent.slots().get("threshold"), inferThreshold(question));
        String operator = firstText(intent.slots().get("operator"), inferOperator(question));
        String channel = firstText(intent.slots().get("channel"), inferChannel(question));

        List<String> missing = new ArrayList<>();
        if (metricField.isBlank()) missing.add("metricField");
        if (timeField.isBlank()) missing.add("timeField");
        if (!"zscore".equals(operator) && threshold == null) missing.add("threshold");
        if (!missing.isEmpty()) {
            SmartIntent clarification = intent.withMissing(missing, "预警规则缺少必要参数：" + String.join(", ", missing));
            return buildClarificationResult(question, tableName, clarification);
        }

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("tableName", tableName);
        draft.put("metricField", metricField);
        draft.put("timeField", timeField);
        draft.put("operator", operator);
        draft.put("threshold", threshold);
        draft.put("granularity", inferGranularity(question));
        draft.put("channels", List.of(channel));
        draft.put("sourceQuestion", question);

        Map<String, Object> result = draftCard("ALERT_RULE_DRAFT", "已识别为预警规则创建意图，已生成规则草稿，请确认后再创建。", draft);
        result.put("requiresConfirmation", true);
        result.put("sideEffectMode", "DRAFT_ONLY");
        result.put("chartType", "table");
        return result;
    }

    private Map<String, Object> buildWhatIfDraft(String question, String tableName, SmartIntent intent) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("tableName", tableName);
        draft.put("targetMetric", firstText(intent.slots().get("targetMetric"), intent.slots().get("metric")));
        draft.put("variables", intent.slots().getOrDefault("variables", List.of()));
        draft.put("formula", firstText(intent.slots().get("formula")));
        draft.put("sourceQuestion", question);
        Map<String, Object> result = draftCard("WHAT_IF_DRAFT", "已识别为 What-if 情景推演意图，当前生成参数草稿，请确认指标、变量和变化幅度。", draft);
        result.put("requiresConfirmation", true);
        return result;
    }

    private Map<String, Object> executeBusinessModelAgent(String question, String tableName, SmartIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("tableName", tableName);
        payload.put("selectedTableName", tableName);
        Map<String, Object> slots = intent.slots() == null ? Map.of() : intent.slots();
        putIfPresent(payload, "activeBusinessModelId", slots.get("activeBusinessModelId"));
        putIfPresent(payload, "lastCreatedBusinessModelId", slots.get("lastCreatedBusinessModelId"));
        putIfPresent(payload, "lastAppliedBusinessModelId", slots.get("lastAppliedBusinessModelId"));
        putIfPresent(payload, "selectedTableName", slots.get("selectedTableName"));
        Map<String, Object> agent = businessModelAgentService.handleQuestion(payload);
        Map<String, Object> result = new LinkedHashMap<>(agent);
        result.put("responseType", intent.primaryIntent());
        result.put("chartType", "table");
        result.putIfAbsent("data", List.of(Map.of(
                "name", "业务模型智能体",
                "value", firstText(agent.get("message"), "业务模型处理完成")
        )));
        result.putIfAbsent("dimensions", List.of("name", "value"));
        result.putIfAbsent("message", firstText(agent.get("message"), "业务模型处理完成"));
        result.put("smartRouted", true);
        return result;
    }

    private Map<String, Object> executeDashboardPin(String question,
                                                    String tableName,
                                                    SmartIntent intent,
                                                    ChatBiService.ChatQueryRequest request) {
        Long conversationId = request == null ? null : request.getConversationId();
        if (chatConversationService == null || stackCDashboardService == null) {
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("primaryIntent", "DASHBOARD_PIN");
            draft.put("question", question);
            draft.put("tableName", tableName);
            draft.put("conversationId", conversationId);
            Map<String, Object> result = draftCard(
                    "DASHBOARD_PIN",
                    "当前会话暂无可钉入的图表结果，请先完成一次图表查询",
                    draft
            );
            result.put("responseType", "CLARIFICATION");
            result.put("requiresConfirmation", true);
            result.put("dashboardActionStatus", "NO_CHART");
            result.put("skipChartArtifact", true);
            return result;
        }
        Map<String, Object> artifact = chatConversationService.latestChartArtifactForConversation(conversationId);
        if (artifact == null || artifact.isEmpty()) {
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("primaryIntent", "DASHBOARD_PIN");
            draft.put("question", question);
            draft.put("tableName", tableName);
            draft.put("conversationId", conversationId);
            Map<String, Object> result = draftCard(
                    "DASHBOARD_PIN",
                    "当前会话暂无可钉入的图表结果，请先完成一次图表查询",
                    draft
            );
            result.put("responseType", "CLARIFICATION");
            result.put("requiresConfirmation", true);
            result.put("dashboardActionStatus", "NO_CHART");
            result.put("skipChartArtifact", true);
            return result;
        }

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("artifactId", artifact.get("id"));
        source.put("turnId", artifact.get("turnId"));
        source.put("historyId", artifact.get("historyId"));
        source.put("chartType", artifact.get("chartType"));
        source.put("sql", artifact.get("sqlText"));
        if (artifact.get("artifact") instanceof Map<?, ?> rawSnapshot) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            rawSnapshot.forEach((key, value) -> snapshot.put(String.valueOf(key), value));
            source.put("title", firstText(
                    snapshot.get("sourceQuestion"),
                    snapshot.get("question"),
                    snapshot.get("message"),
                    question
            ));
            source.put("sourceQuestion", firstText(snapshot.get("sourceQuestion"), snapshot.get("question")));
            source.put("message", snapshot.get("message"));
            source.put("tableName", firstText(snapshot.get("tableName"), tableName));
        }

        Map<String, Object> result = stackCDashboardService.smartPinChart(question, source);
        result.put("responseType", "DASHBOARD_PIN");
        result.put("smartRouted", true);
        result.putIfAbsent("chartType", "table");
        result.putIfAbsent("data", List.of(Map.of(
                "name", "DASHBOARD_PIN",
                "value", firstText(result.get("message"), "看板钉入动作已处理")
        )));
        result.putIfAbsent("dimensions", List.of("name", "value"));
        result.put("requiresConfirmation", readBoolean(result.get("requiresConfirmation")));
        return result;
    }

    private Map<String, Object> buildClarificationResult(String question, String tableName, SmartIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("primaryIntent", intent.primaryIntent());
        payload.put("missingSlots", intent.missingSlots());
        payload.put("requiresConfirmation", intent.requiresConfirmation());
        payload.put("reasoning", intent.reasoning());
        payload.put("question", question);
        payload.put("tableName", tableName);
        String message = clarificationMessage(intent);
        Map<String, Object> result = draftCard("CLARIFY", message, payload);
        result.put("responseType", "CLARIFICATION");
        result.put("requiresConfirmation", intent.requiresConfirmation());
        return result;
    }

    private Map<String, Object> advancedToChatResult(Map<String, Object> advanced, String responseType, String message) {
        List<Map<String, Object>> series = castRows(advanced.get("series"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(advanced);
        result.put("responseType", responseType);
        result.put("advancedAnalysisType", text(advanced.get("type")));
        result.put("autoForecast", "FORECAST".equals(responseType));
        result.put("data", series);
        result.put("series", series);
        result.put("advancedAnalysisResult", advanced);
        result.put("forecastMeta", Map.of(
                "tableName", firstText(advanced.get("tableName")),
                "metricField", firstText(advanced.get("metricField"), advanced.get("targetMetric")),
                "timeField", firstText(advanced.get("timeField")),
                "granularity", firstText(advanced.get("granularity")),
                "algorithm", firstText(advanced.get("algorithm"))
        ));
        result.put("chartType", "line");
        result.put("dimensions", List.of("name", "history", "forecast", "upper", "lower", "value", "anomaly"));
        result.put("fieldMapping", Map.of(
                "mappingType", text(advanced.get("type")),
                "metric", firstText(advanced.get("metricField"), advanced.get("targetMetric")),
                "metricKey", firstText(advanced.get("metricField"), advanced.get("targetMetric")),
                "dimension", firstText(advanced.get("timeField"), "时间"),
                "dimensionKey", firstText(advanced.get("timeField"), "name")
        ));
        result.put("message", message);
        result.put("smartRouted", true);
        return result;
    }

    private Map<String, Object> draftCard(String responseType, String message, Map<String, Object> draft) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", responseType);
        result.put("message", message);
        result.put("chartType", "table");
        result.put("data", List.of(Map.of(
                "name", responseType,
                "value", message
        )));
        result.put("dimensions", List.of("name", "value"));
        result.put("draft", draft);
        result.put("smartRouted", true);
        return result;
    }

    private void attachSmartMetadata(Map<String, Object> result, SmartIntent intent, String question, String tableName) {
        result.put("smartIntent", intent.primaryIntent());
        result.put("smartConfidence", intent.confidence());
        result.put("smartReasoning", intent.reasoning());
        result.put("smartFallbackUsed", intent.fallbackUsed());
        result.put("queryQuestion", question);
        result.put("queryTableName", tableName);
        result.put("thinkingLogs", List.of(
                "统一语义路由：识别意图 " + intent.primaryIntent(),
                "动作计划校验：" + (intent.missingSlots().isEmpty() ? "参数完整或已生成草稿" : "缺少 " + String.join(", ", intent.missingSlots())),
                "执行策略：" + (Boolean.TRUE.equals(result.get("requiresConfirmation")) ? "生成草稿/等待确认" : "直接调用现有业务服务")
        ));
        result.put("actionPlan", Map.of(
                "primaryIntent", intent.primaryIntent(),
                "confidence", intent.confidence(),
                "requiresConfirmation", intent.requiresConfirmation(),
                "missingSlots", intent.missingSlots(),
                "slots", intent.slots()
        ));
    }

    private Map<String, Object> buildAdvancedContext(String tableName) {
        List<Map<String, Object>> fields = safeFields(tableName);
        List<Map<String, Object>> timeFields = fields.stream()
                .filter(this::isTimeField)
                .toList();
        List<Map<String, Object>> numericFields = fields.stream()
                .filter(this::isNumericField)
                .toList();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("fields", fields);
        context.put("timeFields", timeFields);
        context.put("numericFields", numericFields);
        return context;
    }

    private List<Map<String, Object>> safeFields(String tableName) {
        String resolved = text(tableName);
        if (resolved.isBlank()) {
            return List.of();
        }
        try {
            return dataUploadService.listFields(resolved);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String resolveTableName(ChatBiService.ChatQueryRequest request) {
        if (request != null && request.getFilters() != null) {
            String tableName = text(request.getFilters().get("tableName"));
            if (!tableName.isBlank()) return tableName;
        }
        if (request != null && request.getTableNames() != null && !request.getTableNames().isEmpty()) {
            String tableName = text(request.getTableNames().get(0));
            if (!tableName.isBlank()) return tableName;
        }
        try {
            return text(dataUploadService.latestTableName());
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeAdvancedIntent(Object value) {
        String intent = text(value);
        return switch (intent) {
            case "forecast", "timeSeriesForecast", "prediction" -> "FORECAST";
            case "whatIf", "simulation", "scenario" -> "WHAT_IF";
            case "alert", "warning", "anomaly" -> "ALERT_RULE_CREATE";
            default -> "NONE";
        };
    }

    private String normalizeSmartIntent(Object value) {
        String intent = text(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (intent) {
            case "QUERY", "SQL", "TEXT_TO_SQL" -> "QUERY_SQL";
            case "FORECAST", "PREDICTION", "TIME_SERIES_FORECAST" -> "FORECAST";
            case "ALERT", "WARNING", "ALERT_CREATE" -> "ALERT_RULE_CREATE";
            case "WHATIF", "WHAT_IF", "SCENARIO", "SIMULATION" -> "WHAT_IF";
            case "BUSINESS_MODEL_CREATE", "BUSINESS_MODEL_PATCH", "BUSINESS_MODEL_APPLY",
                    "BUSINESS_MODEL_PUBLISH", "DASHBOARD_PIN", "DASHBOARD_CREATE",
                    "CHART_RULE_UPDATE", "FIELD_SEMANTIC_FIX", "FEDERATED_QUERY",
                    "PERMISSION_POLICY_CREATE", "AUDIT_QUERY", "REPORT_GENERATE",
                    "TASK_STATUS_QUERY", "COLLABORATION_INVITE", "QUERY_SQL" -> intent;
            default -> "CLARIFY";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private boolean readBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        String text = text(value).toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private double readDouble(Object value, double fallback) {
        try {
            return Double.parseDouble(text(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String chooseTimeField(List<Map<String, Object>> fields, String question, String requested) {
        String resolved = resolveFieldAlias(fields, requested, this::isTimeField);
        if (!resolved.isBlank()) return resolved;
        String exact = chooseFieldByText(fields, this::isTimeField, question);
        if (!exact.isBlank()) return exact;
        return fields.stream()
                .filter(this::isTimeField)
                .sorted((left, right) -> Integer.compare(timeFieldScore(right, question), timeFieldScore(left, question)))
                .map(field -> text(field.get("columnName")))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String chooseMetricField(List<Map<String, Object>> fields, String question, String requested) {
        String resolved = resolveFieldAlias(fields, requested, this::isNumericField);
        if (!resolved.isBlank()) return resolved;
        String exact = chooseFieldByText(fields, "NUMBER", question);
        if (!exact.isBlank()) return exact;
        return chooseField(fields, "NUMBER", question);
    }

    private String chooseMetricField(List<Map<String, Object>> fields, String question) {
        return chooseMetricField(fields, question, "");
    }

    private String chooseField(List<Map<String, Object>> fields, String type, String question) {
        return fields.stream()
                .filter(field -> type.equalsIgnoreCase(text(field.get("fieldType"))))
                .map(field -> text(field.get("columnName")))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String chooseFieldByText(List<Map<String, Object>> fields, String type, String question) {
        return chooseFieldByText(fields, field -> type.equalsIgnoreCase(text(field.get("fieldType"))), question);
    }

    private String chooseFieldByText(List<Map<String, Object>> fields,
                                     java.util.function.Predicate<Map<String, Object>> predicate,
                                     String question) {
        String q = text(question).toLowerCase(Locale.ROOT);
        for (Map<String, Object> field : fields) {
            if (!predicate.test(field)) continue;
            String column = text(field.get("columnName"));
            if (!column.isBlank() && fieldAliasTokens(field).stream().anyMatch(token -> !token.isBlank() && q.contains(token))) {
                return column;
            }
        }
        return "";
    }

    private String resolveFieldAlias(List<Map<String, Object>> fields, String requested,
                                     java.util.function.Predicate<Map<String, Object>> predicate) {
        String normalized = normalizeFieldToken(requested);
        if (normalized.isBlank()) {
            return "";
        }
        for (Map<String, Object> field : fields) {
            if (!predicate.test(field)) continue;
            for (String token : fieldAliasTokens(field)) {
                if (normalized.equals(normalizeFieldToken(token))) {
                    return text(field.get("columnName"));
                }
            }
        }
        return "";
    }

    private List<String> fieldAliasTokens(Map<String, Object> field) {
        List<String> tokens = new ArrayList<>();
        tokens.add(text(field.get("columnName")));
        tokens.add(text(field.get("displayName")));
        tokens.add(text(field.get("sourceFieldName")));
        tokens.add(text(field.get("fieldComment")));
        tokens.add(text(field.get("businessName")));
        Object synonyms = field.get("synonyms");
        if (synonyms instanceof Iterable<?> iterable) {
            for (Object item : iterable) tokens.add(text(item));
        } else {
            String raw = text(synonyms);
            if (!raw.isBlank()) {
                for (String item : raw.split("[,，、;；\\s]+")) tokens.add(text(item));
            }
        }
        return tokens.stream().filter(token -> !token.isBlank()).toList();
    }

    private boolean isTimeField(Map<String, Object> field) {
        String type = firstText(field.get("fieldType"), field.get("dataType")).toUpperCase(Locale.ROOT);
        if ("DATE".equals(type) || "DATETIME".equals(type) || "TIMESTAMP".equals(type)) {
            return true;
        }
        String label = fieldAliasTokens(field).stream().reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
        if (label.matches(".*\\b(id|order_id|code|no|number)\\b.*")
                || containsAny(label, "编号", "单号", "订单号", "客户号")) {
            return false;
        }
        return containsAny(label, "日期", "时间", "年月", "月份", "年度", "date", "time", "month", "year", "day");
    }

    private boolean isNumericField(Map<String, Object> field) {
        String type = firstText(field.get("fieldType"), field.get("dataType")).toUpperCase(Locale.ROOT);
        return "NUMBER".equals(type) || "DECIMAL".equals(type) || "NUMERIC".equals(type)
                || "DOUBLE".equals(type) || "FLOAT".equals(type) || "INT".equals(type) || "INTEGER".equals(type);
    }

    private int timeFieldScore(Map<String, Object> field, String question) {
        String label = fieldAliasTokens(field).stream().reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
        int score = "DATE".equalsIgnoreCase(text(field.get("fieldType"))) ? 100 : 20;
        if (containsAny(label, "订单日期", "业务日期", "交易日期", "销售日期", "date")) score += 25;
        if (containsAny(label, "创建", "上传", "更新时间")) score -= 10;
        if (containsAny(text(question), "订单") && containsAny(label, "订单日期", "order_date")) score += 12;
        return score;
    }

    private String normalizeFieldToken(Object value) {
        return text(value).toLowerCase(Locale.ROOT)
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "")
                .replace("[", "")
                .replace("]", "")
                .replace(" ", "")
                .replace("_", "");
    }

    private String inferGranularity(String question) {
        String q = text(question);
        if (containsAny(q, "每年", "按年", "年度", "年")) return "year";
        if (containsAny(q, "每季度", "按季度", "季度")) return "quarter";
        if (containsAny(q, "每日", "每天", "按日", "日")) return "day";
        return "month";
    }

    private int normalizeHorizon(Object parsed, String question) {
        String value = text(parsed);
        String q = text(question);
        Integer parsedNumber = firstNumber(value);
        if (parsedNumber != null) return Math.max(1, Math.min(parsedNumber, 36));
        Integer questionNumber = firstNumber(q);
        if (questionNumber != null) return Math.max(1, Math.min(questionNumber, 36));
        if (q.contains("下个月") || q.contains("未来一个月")) return 1;
        if (q.contains("半年") || q.contains("六个月")) return 6;
        if (q.contains("一年") || q.contains("12个月")) return 12;
        return 3;
    }

    private String inferOperator(String question) {
        String q = text(question);
        if (containsAny(q, "高于", "超过", "大于", "突破")) return "gt";
        if (containsAny(q, "异常", "波动", "zscore", "Z-Score")) return "zscore";
        return "lt";
    }

    private Object inferThreshold(String question) {
        String normalized = text(question).replace(",", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)(\\s*)(万|千|k|K)?").matcher(normalized);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(3);
            if ("万".equals(unit)) value *= 10000D;
            else if ("千".equals(unit) || "k".equals(unit) || "K".equals(unit)) value *= 1000D;
            return value;
        }
        return null;
    }

    private String inferChannel(String question) {
        String q = text(question).toLowerCase(Locale.ROOT);
        if (q.contains("钉钉") || q.contains("dingtalk")) return "dingtalk";
        if (q.contains("邮件") || q.contains("邮箱") || q.contains("email")) return "email";
        return "both";
    }

    private boolean isBusinessModelIntent(String q) {
        return containsAny(q, "建模", "业务模型", "模型", "业务字典", "业务公式", "字段绑定", "绑定字段", "字段修正",
                "绑定到", "绑定为", "绑定至", "映射到", "映射为", "对应到", "对应为", "公式", "口径", "同义词",
                "企业模型库", "套用", "发布模型", "含税", "不含税", "统一用", "统一按", "算作", "当作")
                || q.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}(指标|维度).*(绑定到|绑定为|绑定至|映射到|映射为|对应到|对应为)\\s*[A-Za-z_][A-Za-z0-9_]*.*")
                || q.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}\\s*(=|＝)\\s*.*[A-Za-z_][A-Za-z0-9_]*.*")
                || q.matches(".*(以后|后续|之后).*(统一用|统一按|就按|按|按照)\\s*[A-Za-z_][A-Za-z0-9_]*.*")
                || q.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}(按|按照).*(除以|乘以|加上|减去|/|\\*|\\+|-).*");
    }

    private boolean isExplicitQueryIntent(String q) {
        return containsAny(q, "排名", "排行", "排行榜", "Top", "top", "明细", "列表", "分布", "占比", "对比", "各省", "各市", "各区域")
                || (containsAny(q, "看一下", "查看", "查询", "统计") && !containsAny(q, "预测", "预警", "告警", "推演", "模拟"));
    }

    private boolean isHistoricalTrendQuery(String q) {
        return containsAny(q, "今年", "去年", "历史", "最近", "近")
                && containsAny(q, "每个月", "按月", "月度", "走势", "趋势", "变化")
                && !containsAny(q, "预测", "预估", "推算", "未来", "下个月", "后面", "大概会");
    }

    private boolean isAlertIntent(String q, String lower) {
        return containsAny(q, "预警", "告警", "警报", "提醒", "通知", "低于", "高于", "超过", "跌破", "阈值", "异常")
                || lower.contains("alert") || lower.contains("warning");
    }

    private boolean isDashboardIntent(String q) {
        return containsAny(q, "经营驾驶舱", "新建看板", "创建看板")
                || (containsAny(q, "看板", "仪表盘", "大屏")
                && containsAny(q, "钉", "放到", "放入", "加入", "添加", "保存", "挂到", "新建", "创建"));
    }

    private String inferBusinessModelIntent(String q) {
        if (containsAny(q, "套用", "应用", "复用")) return "BUSINESS_MODEL_APPLY";
        if (containsAny(q, "发布", "企业模型库")) return "BUSINESS_MODEL_PUBLISH";
        if (containsAny(q, "创建", "新建", "生成", "建立", "搭建", "建模")) return "BUSINESS_MODEL_CREATE";
        return "BUSINESS_MODEL_PATCH";
    }

    private String clarificationMessage(SmartIntent intent) {
        if (!intent.missingSlots().isEmpty()) {
            return "智能路由已识别为 " + intent.primaryIntent() + "，但还缺少参数：" + String.join(", ", intent.missingSlots()) + "。请补充后我再执行。";
        }
        if (intent.requiresConfirmation()) {
            return "智能路由已识别为 " + intent.primaryIntent() + "，该操作需要确认或当前阶段仅生成草稿：" + intent.reasoning();
        }
        return "智能路由已识别为 " + intent.primaryIntent() + "，但还需要更多上下文才能安全执行：" + intent.reasoning();
    }

    private Integer firstNumber(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(text(text));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRows(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
            return rows;
        }
        return List.of();
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null && !text(value).isBlank()) return value;
        }
        return null;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private boolean containsAny(String text, String... candidates) {
        String source = text(text);
        for (String candidate : candidates) {
            if (!text(candidate).isBlank() && source.contains(candidate)) return true;
        }
        return false;
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        String text = text(value);
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }

    private record SmartIntent(String primaryIntent,
                               double confidence,
                               boolean requiresConfirmation,
                               String reasoning,
                               Map<String, Object> slots,
                               boolean fallbackUsed,
                               List<String> missingSlots) {
        private SmartIntent(String primaryIntent, double confidence, boolean requiresConfirmation,
                            String reasoning, Map<String, Object> slots, boolean fallbackUsed) {
            this(primaryIntent, confidence, requiresConfirmation, reasoning,
                    slots == null ? Map.of() : slots, fallbackUsed, List.of());
        }

        private SmartIntent withMissing(List<String> missingSlots, String reasoning) {
            return new SmartIntent(primaryIntent, confidence, true, reasoning, slots, fallbackUsed, missingSlots);
        }

        private SmartIntent withClarification(String intent, String reasoning) {
            return new SmartIntent(intent, confidence, true, reasoning, slots, fallbackUsed, List.of("timeField", "metricField"));
        }

        private SmartIntent withContext(Map<String, Object> context) {
            if (context == null || context.isEmpty()) {
                return this;
            }
            Map<String, Object> merged = new LinkedHashMap<>(slots == null ? Map.of() : slots);
            for (String key : List.of("selectedTableName", "activeBusinessModelId", "lastCreatedBusinessModelId", "lastAppliedBusinessModelId")) {
                Object value = context.get(key);
                if (value != null && !Objects.toString(value, "").trim().isBlank()) {
                    merged.put(key, value);
                }
            }
            return new SmartIntent(primaryIntent, confidence, requiresConfirmation, reasoning, merged, fallbackUsed, missingSlots);
        }
    }
}
