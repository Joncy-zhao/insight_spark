package com.insightspark.service;

import com.insightspark.c.service.StackCDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SmartChatService.class);

    @Autowired
    private ChatBiService chatBiService;

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private AdvancedAnalysisService advancedAnalysisService;

    @Autowired
    private BusinessModelAgentService businessModelAgentService;

    @Autowired
    private BusinessSemanticService businessSemanticService;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private StackCDashboardService stackCDashboardService;

    @FunctionalInterface
    public interface ThinkingEmitter {
        void emit(String eventType, String title, String detail, Map<String, Object> metadata);

        static ThinkingEmitter noop() {
            return (eventType, title, detail, metadata) -> {
            };
        }
    }

    public Map<String, Object> executeSmart(ChatBiService.ChatQueryRequest request) {
        return executeSmart(request, ThinkingEmitter.noop());
    }

    public Map<String, Object> executeSmart(ChatBiService.ChatQueryRequest request, ThinkingEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        ThinkingEmitter trace = emitter == null ? ThinkingEmitter.noop() : emitter;
        ChatBiService.ChatQueryRequest safeRequest = request == null ? new ChatBiService.ChatQueryRequest() : request;
        String question = text(safeRequest.getQuestion());
        String rawQuestion = text(safeRequest.getFilters() == null ? null : safeRequest.getFilters().get("rawQuestion"));
        String userQuestion = rawQuestion.isBlank() ? question : rawQuestion;
        String routingQuestion = shouldRouteByRawFollowUpQuestion(safeRequest, question, rawQuestion)
                ? rawQuestion
                : question;
        String actionQuestion = question;
        String tableName = resolveTableName(safeRequest);
        emit(trace, "INPUT_RECEIVED", "收到问题", userQuestion.isBlank()
                ? "已进入智能对话处理"
                : "正在处理：" + trimTo(userQuestion, 80), Map.of("tableName", tableName));
        emit(trace, "CONTEXT_READY", "读取上下文", tableName.isBlank()
                ? "未指定数据源，将结合会话上下文和默认数据源判断"
                : "已选择数据源 " + tableName, Map.of("tableName", tableName));
        emit(trace, "PLAN_CHECKING", "判断任务结构", "正在判断这是单一查询还是需要查询、预测、预警、看板等多步骤编排",
                Map.of("tableName", tableName));
        SmartActionPlan actionPlan = buildMultiStepPlan(routingQuestion, tableName, safeRequest, trace);
        if (actionPlan.isMultiStep()) {
            emit(trace, "PLAN_READY", "生成执行计划", describeActionPlan(actionPlan),
                    Map.of("actions", actionPlan.actions().stream().map(SmartActionStep::type).toList()));
            Map<String, Object> result = executeMultiStepPlan(actionPlan, safeRequest, question, actionQuestion, tableName, trace);
            SmartIntent intent = new SmartIntent("MULTI_STEP", actionPlan.confidence(), actionPlan.requiresConfirmation(),
                    actionPlan.reasoning(), actionPlan.slots(), actionPlan.fallbackUsed());
            attachSmartMetadata(result, intent, actionQuestion, tableName);
            result.put("actionPlan", actionPlan.toMap(castRows(result.get("stepResults"))));
            result.put("thinkingLogs", multiStepThinkingLogs(actionPlan, castRows(result.get("stepResults"))));
            attachSmartRouteAudit(result, safeRequest, actionQuestion, tableName, "multi-step-orchestrator", startedAt);
            return result;
        }
        SmartIntent intent = route(routingQuestion, tableName, trace, safeRequest.getFilters()).withContext(safeRequest.getFilters());
        if (shouldUseRawAlertRuleActionQuestion(intent, actionQuestion, rawQuestion)) {
            actionQuestion = rawQuestion;
        }
        emit(trace, "ROUTE_DECIDED", "识别意图", describeIntent(intent),
                Map.of("intent", intent.primaryIntent(), "confidence", intent.confidence(), "fallbackUsed", intent.fallbackUsed()));
        Map<String, Object> result;
        switch (intent.primaryIntent()) {
            case "FORECAST" -> result = executeForecast(actionQuestion, tableName, intent, trace);
            case "ALERT_RULE_CREATE" -> result = buildAlertRuleDraft(actionQuestion, tableName, intent, trace);
            case "ALERT_EVENT_QUERY" -> result = executeAlertEventQuery(actionQuestion, tableName, intent, trace);
            case "ALERT_EVENT_EXPLAIN" -> result = executeAlertEventExplain(actionQuestion, tableName, intent, trace);
            case "ALERT_EVENT_ACK", "ALERT_EVENT_CLOSE", "ALERT_EVENT_REOPEN" ->
                    result = executeAlertEventLifecycleAction(actionQuestion, tableName, intent, trace);
            case "ALERT_RULE_DETECT" -> result = executeAlertRuleDetectAction(actionQuestion, tableName, intent, trace);
            case "ALERT_RULE_UPDATE", "ALERT_RULE_DISABLE", "ALERT_RULE_ENABLE", "ALERT_RULE_DELETE" ->
                    result = executeAlertRuleLifecycleAction(actionQuestion, tableName, intent, trace);
            case "WHAT_IF" -> result = buildWhatIfDraft(actionQuestion, tableName, intent);
            case "BUSINESS_MODEL_CREATE", "BUSINESS_MODEL_PATCH", "BUSINESS_MODEL_APPLY", "BUSINESS_MODEL_PUBLISH" ->
                    result = executeBusinessModelAgent(userQuestion, tableName, intent, trace);
            case "DASHBOARD_PIN" -> result = executeDashboardPin(actionQuestion, tableName, intent, safeRequest, trace);
            case "DASHBOARD_CREATE", "CHART_RULE_UPDATE", "FIELD_SEMANTIC_FIX",
                    "FEDERATED_QUERY", "PERMISSION_POLICY_CREATE", "AUDIT_QUERY", "REPORT_GENERATE",
                    "TASK_STATUS_QUERY", "COLLABORATION_INVITE", "CLARIFY" ->
                    result = buildClarificationResult(actionQuestion, tableName, intent);
            default -> result = executeQuery(safeRequest, question, tableName, intent, trace);
        }
        attachSmartMetadata(result, intent, userQuestion, tableName);
        emit(trace, "RESULT_READY", "整理结果", trimTo(firstText(result.get("message"), "智能处理完成"), 120),
                Map.of("responseType", firstText(result.get("responseType"), intent.primaryIntent())));
        attachSmartRouteAudit(result, safeRequest, actionQuestion, tableName, chosenExecutor(intent.primaryIntent()), startedAt);
        return result;
    }

    private boolean shouldRouteByRawFollowUpQuestion(ChatBiService.ChatQueryRequest request,
                                                     String executionQuestion,
                                                     String rawQuestion) {
        if (request == null || rawQuestion.isBlank() || rawQuestion.equals(executionQuestion)) {
            return false;
        }
        if (request.getParentTurnId() != null) {
            return true;
        }
        return !inferAlertLifecycleIntent(rawQuestion, rawQuestion.toLowerCase(Locale.ROOT)).isBlank();
    }

    private boolean shouldUseRawAlertRuleActionQuestion(SmartIntent intent,
                                                        String executionQuestion,
                                                        String rawQuestion) {
        if (intent == null || rawQuestion.isBlank() || rawQuestion.equals(executionQuestion)) {
            return false;
        }
        String primaryIntent = text(intent.primaryIntent());
        if (!primaryIntent.startsWith("ALERT_RULE_")) {
            return false;
        }
        String rawIntent = inferAlertLifecycleIntent(rawQuestion, rawQuestion.toLowerCase(Locale.ROOT));
        return primaryIntent.equals(rawIntent) && alertRuleId(rawQuestion, Map.of()) != null;
    }

    private SmartIntent route(String question, String tableName) {
        return route(question, tableName, ThinkingEmitter.noop());
    }

    private SmartIntent route(String question, String tableName, ThinkingEmitter trace) {
        return route(question, tableName, trace, Map.of());
    }

    private SmartIntent route(String question, String tableName, ThinkingEmitter trace, Map<String, Object> requestContext) {
        String q = text(question);
        String lower = q.toLowerCase(Locale.ROOT);
        emit(trace, "ROUTE_CONTEXT_PREPARE", "准备语义路由", "正在读取字段类型、时间字段和数值指标，用于判断用户意图",
                Map.of("tableName", tableName));
        Map<String, Object> context = withModelContext(buildAdvancedContext(tableName), requestContext);
        emit(trace, "ROUTE_MODEL_CALL", "调用语义路由", "正在判断是否为查询、预测、预警、看板或业务模型操作",
                Map.of("fieldCount", collectionSize(context.get("fields")),
                        "timeFieldCount", collectionSize(context.get("timeFields")),
                        "numericFieldCount", collectionSize(context.get("numericFields"))));
        Optional<Map<String, Object>> smartRoute = pythonAiService.smartChatRoute(question, tableName, context);
        if (smartRoute != null && smartRoute.isPresent()) {
            Map<String, Object> routed = smartRoute.get();
            String primaryIntent = normalizeSmartIntent(routed.get("primaryIntent"));
            double confidence = readDouble(routed.get("confidence"), 0.0D);
            if (isStatisticalOutlierQuery(q) && (isAlertLikeIntent(primaryIntent) || isPredictiveIntent(primaryIntent))) {
                emit(trace, "ROUTE_MODEL_RESULT_ADJUSTED", "Adjust route",
                        "Scatter/correlation outlier analysis is a SQL chart query, not an alert or forecast workflow.",
                        Map.of("originalIntent", primaryIntent, "adjustedIntent", "QUERY_SQL"));
                return new SmartIntent("QUERY_SQL", Math.max(confidence, 0.76D), false,
                        "Statistical outlier analysis should be handled by Text-to-SQL chart query.",
                        mapValue(routed.get("slots")), false);
            }
            if ("BUSINESS_MODEL_CREATE".equals(primaryIntent) && hasBusinessModelMutationIntent(q) && !hasBusinessModelCreateIntent(q)) {
                emit(trace, "ROUTE_MODEL_RESULT_ADJUSTED", "修正语义路由",
                        "用户本轮表达的是业务模型维护，不是新建模型，已切换为维护业务模型",
                        Map.of("originalIntent", primaryIntent, "adjustedIntent", "BUSINESS_MODEL_PATCH"));
                return new SmartIntent("BUSINESS_MODEL_PATCH", Math.max(confidence, 0.72D), false,
                        "AI 路由倾向创建模型，但本轮动作语义为字段绑定/口径维护", mapValue(routed.get("slots")),
                        readBoolean(routed.get("fallbackUsed")));
            }
            String alertLifecycleIntent = inferAlertLifecycleIntent(q, lower);
            if (!alertLifecycleIntent.isBlank() && !alertLifecycleIntent.equals(primaryIntent)) {
                emit(trace, "ROUTE_MODEL_RESULT_ADJUSTED", "修正语义路由",
                        "用户表达的是预警事件或规则处置，不应进入" + intentLabel(primaryIntent) + "，已切换为" + intentLabel(alertLifecycleIntent),
                        Map.of("originalIntent", primaryIntent, "adjustedIntent", alertLifecycleIntent));
                return new SmartIntent(alertLifecycleIntent, Math.max(confidence, 0.72D),
                        alertIntentRequiresConfirmation(alertLifecycleIntent),
                        alertLifecycleReason(alertLifecycleIntent), inferAlertLifecycleSlots(q, tableName),
                        readBoolean(routed.get("fallbackUsed")));
            }
            String alertRuleCreateIntent = inferAlertRuleCreateIntent(q, lower);
            if ("QUERY_SQL".equals(primaryIntent) && !alertRuleCreateIntent.isBlank()) {
                emit(trace, "ROUTE_MODEL_RESULT_ADJUSTED", "修正语义路由",
                        "用户表达的是预警规则创建，不应进入普通 SQL 查询，已切换为预警规则草稿",
                        Map.of("originalIntent", primaryIntent, "adjustedIntent", alertRuleCreateIntent));
                return new SmartIntent(alertRuleCreateIntent, Math.max(confidence, 0.72D), true,
                        "识别到预警规则创建语义", mapValue(routed.get("slots")),
                        readBoolean(routed.get("fallbackUsed")));
            }
            if ("FORECAST".equals(primaryIntent) && isHistoricalTrendQuery(q) && !hasForecastIntent(q, lower)) {
                emit(trace, "ROUTE_MODEL_RESULT_ADJUSTED", "修正语义路由",
                        "用户表达的是历史时间范围内的趋势查看，不包含未来外推语义，已按普通查询处理",
                        Map.of("originalIntent", primaryIntent, "adjustedIntent", "QUERY_SQL"));
                return new SmartIntent("QUERY_SQL", Math.max(confidence, 0.72D), false,
                        "AI 路由倾向预测，但语义校验判定为历史趋势查询", mapValue(routed.get("slots")), false);
            }
            if (!"CLARIFY".equals(primaryIntent) && confidence >= 0.55D) {
                emit(trace, "ROUTE_MODEL_RESULT", "语义路由完成",
                        "AI 路由识别为 " + intentLabel(primaryIntent) + "，置信度 " + Math.round(confidence * 100) + "%",
                        Map.of("intent", primaryIntent, "confidence", confidence));
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

        emit(trace, "ROUTE_FALLBACK", "语义校验", "AI 路由未给出高置信结果，正在用本地语义校验补充判断",
                Map.of("tableName", tableName));
        if (isBusinessModelIntent(q)) {
            return new SmartIntent(inferBusinessModelIntent(q), 0.62D, false,
                    "AI 总路由不可用，保守兜底识别到业务模型语义", Map.of(), true);
        }
        if (isDashboardIntent(q)) {
            return new SmartIntent(q.contains("新建") ? "DASHBOARD_CREATE" : "DASHBOARD_PIN", 0.6D, true,
                    "AI 总路由不可用，保守兜底识别到看板资产操作", Map.of(), true);
        }
        String alertLifecycleIntent = inferAlertLifecycleIntent(q, lower);
        if (!alertLifecycleIntent.isBlank()) {
            return new SmartIntent(alertLifecycleIntent, 0.72D, alertIntentRequiresConfirmation(alertLifecycleIntent),
                    alertLifecycleReason(alertLifecycleIntent), inferAlertLifecycleSlots(q, tableName), true);
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

        emit(trace, "ADVANCED_ROUTE_CHECK", "检查高级分析意图", "正在判断是否需要进入预测、预警或情景推演能力",
                Map.of("tableName", tableName));
        Optional<Map<String, Object>> advanced = pythonAiService.parseAdvancedAnalysisIntent(question, tableName, context);
        if (advanced != null && advanced.isPresent()) {
            String intent = normalizeAdvancedIntent(advanced.get().get("intent"));
            if (isStatisticalOutlierQuery(q) && (isAlertLikeIntent(intent) || isPredictiveIntent(intent))) {
                emit(trace, "ADVANCED_ROUTE_RESULT_ADJUSTED", "Adjust advanced route",
                        "Outlier distribution analysis should stay in SQL chart query, not forecast.",
                        Map.of("originalIntent", intent, "adjustedIntent", "QUERY_SQL"));
                return new SmartIntent("QUERY_SQL", 0.76D, false,
                        "Statistical outlier analysis should not create an alert or forecast workflow.",
                        advanced.get(), false);
            }
            if ("FORECAST".equals(intent) && isHistoricalTrendQuery(q) && !hasForecastIntent(q, lower)) {
                emit(trace, "ADVANCED_ROUTE_RESULT_ADJUSTED", "修正高级分析识别",
                        "用户要查看历史走势，没有要求预测未来，已回到普通查询",
                        Map.of("originalIntent", intent, "adjustedIntent", "QUERY_SQL"));
                return new SmartIntent("QUERY_SQL", 0.72D, false,
                        "高级分析解析倾向预测，但语义校验判定为历史趋势查询", advanced.get(), false);
            }
            if (!"NONE".equals(intent)) {
                emit(trace, "ADVANCED_ROUTE_RESULT", "高级分析识别完成",
                        "识别为 " + intentLabel(intent), Map.of("intent", intent));
                return new SmartIntent(intent, 0.82D, false,
                        "AI 高级分析语义解析命中 " + intent,
                        advanced.get(), false);
            }
        }

        if (isAlertIntent(q, lower)) {
            return new SmartIntent("ALERT_RULE_CREATE", 0.72D, true,
                    "规则兜底识别到预警/提醒语义", Map.of(), true);
        }
        if (hasForecastIntent(q, lower)) {
            return new SmartIntent("FORECAST", 0.74D, false,
                    "规则兜底识别到预测语义", Map.of(), true);
        }
        if (containsAny(q, "如果", "假设", "推演", "模拟", "提升", "下降", "降低", "增长", "what-if")
                || lower.contains("what-if") || lower.contains("whatif")) {
            return new SmartIntent("WHAT_IF", 0.68D, true,
                    "规则兜底识别到情景推演语义", Map.of(), true);
        }
        String lateAlertLifecycleIntent = inferAlertLifecycleIntent(q, lower);
        if (!lateAlertLifecycleIntent.isBlank()) {
            return new SmartIntent(lateAlertLifecycleIntent, 0.68D, alertIntentRequiresConfirmation(lateAlertLifecycleIntent),
                    alertLifecycleReason(lateAlertLifecycleIntent), inferAlertLifecycleSlots(q, tableName), true);
        }
        return new SmartIntent("QUERY_SQL", 0.86D, false,
                "默认进入 Text-to-SQL 查询", Map.of(), true);
    }

    private Map<String, Object> executeQuery(ChatBiService.ChatQueryRequest request, String question,
                                             String tableName, SmartIntent intent) {
        return executeQuery(request, question, tableName, intent, ThinkingEmitter.noop());
    }

    private Map<String, Object> executeQuery(ChatBiService.ChatQueryRequest request, String question,
                                             String tableName, SmartIntent intent, ThinkingEmitter trace) {
        Map<String, Object> filters = new LinkedHashMap<>(request.getFilters() == null ? Map.of() : request.getFilters());
        filters.put("autoForecastEnabled", false);
        filters.put("progressListener", (ChatBiService.ProgressListener) (eventType, title, detail, metadata) ->
                emit(trace, eventType, title, detail, metadata));
        request.setFilters(filters);
        emit(trace, "QUERY_START", "准备查询", tableName.isBlank()
                ? "正在根据问题生成安全的只读查询"
                : "将在 " + tableName + " 上生成只读查询", Map.of("tableName", tableName));
        Map<String, Object> result;
        try {
            result = chatBiService.executeChat(request);
        } finally {
            filters.remove("progressListener");
            request.setFilters(filters);
        }
        if (result.get("filters") instanceof Map<?, ?> rawFilters) {
            Map<String, Object> visibleFilters = new LinkedHashMap<>();
            rawFilters.forEach((key, value) -> {
                if (!"progressListener".equals(Objects.toString(key, ""))) {
                    visibleFilters.put(Objects.toString(key, ""), value);
                }
            });
            result.put("filters", visibleFilters);
        }
        result.put("responseType", "QUERY_SQL");
        result.put("smartRouted", true);
        result.put("message", firstText(result.get("message"), "已按数据查询意图完成分析。"));
        emit(trace, "SQL_GENERATED", "生成查询语句", describeSqlResult(result),
                Map.of("chartType", firstText(result.get("chartType")), "engine", firstText(result.get("engine"))));
        emit(trace, "QUERY_FINISHED", "查询完成", describeQueryResult(result),
                Map.of("rowCount", castRows(result.get("data")).size(), "riskLevel", firstText(result.get("riskLevel"))));
        return result;
    }

    private Map<String, Object> executeForecast(String question, String tableName, SmartIntent intent) {
        return executeForecast(question, tableName, intent, ThinkingEmitter.noop());
    }

    private Map<String, Object> executeForecast(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        List<Map<String, Object>> fields = safeFields(tableName);
        BusinessSemanticService.BusinessAnalysisResolution businessResolution =
                resolveBusinessAnalysis(question, tableName, intent.slots(), fields);
        String timeField = chooseTimeField(fields, question, firstText(
                intent.slots().get("timeField"), intent.slots().get("dateField"), intent.slots().get("dimensionField")));
        String metricField = firstText(businessResolution.metricColumn(), chooseMetricField(fields, question, firstText(
                intent.slots().get("metricField"), intent.slots().get("metric"), intent.slots().get("targetMetric"))));
        if (timeField.isBlank() || metricField.isBlank()) {
            SmartIntent clarification = intent.withClarification("FORECAST", "预测需要时间字段和数值指标，请先选择或补充字段。");
            emit(trace, "NEEDS_INPUT", "预测信息不足", "缺少时间字段或数值指标，已转为补充信息提示",
                    Map.of("missingSlots", clarification.missingSlots()));
            return buildClarificationResult(question, tableName, clarification);
        }
        String granularity = normalizeForecastGranularity(firstText(
                intent.slots().get("granularity"), intent.slots().get("timeGranularity"), inferGranularity(question)));
        int horizon = normalizeHorizon(intent.slots().get("horizon"), question, granularity);
        String algorithm = firstText(intent.slots().get("algorithm"), "Holt-Winters");
        if (businessResolution.matched()) {
            emit(trace, "BUSINESS_SEMANTIC_APPLIED", "应用业务模型语义",
                    "预测指标已按业务模型解析为 " + firstText(businessResolution.metricLabel(), businessResolution.metricColumn()),
                    businessResolution.trace());
        }
        emit(trace, "FORECAST_SOURCE_READY", "确认预测数据源",
                "使用时间字段 " + timeField + " 和指标 " + metricField + " 重新聚合原始数据",
                withBusinessTrace(metadataOf("timeField", timeField, "metricField", metricField, "tableName", tableName),
                        businessResolution));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tableName", tableName);
        request.put("timeField", timeField);
        request.put("metricField", metricField);
        if (businessResolution.matched()) {
            request.put("metricLabel", firstText(businessResolution.metricLabel(), metricField));
            request.put("businessSemanticTrace", businessResolution.trace());
            if (businessResolution.formulaApplied() && !text(businessResolution.metricExpression()).isBlank()) {
                request.put("metricExpression", businessResolution.metricExpression());
                request.put("formula", businessResolution.formula());
            }
        }
        request.put("granularity", granularity);
        request.put("horizon", horizon);
        request.put("algorithm", algorithm);
        request.put("sourceQuestion", question);
        Map<String, Object> forecast = new LinkedHashMap<>(advancedAnalysisService.forecast(request));
        if (businessResolution.matched()) {
            forecast.put("businessSemanticTrace", businessResolution.trace());
            forecast.put("metricLabel", firstText(businessResolution.metricLabel(), forecast.get("metricLabel"), metricField));
        }
        emit(trace, "FORECAST_FINISHED", "预测完成",
                "已生成 " + horizon + " 期" + granularityLabel(granularity) + "预测，算法 " + algorithm,
                withBusinessTrace(metadataOf("horizon", horizon, "granularity", granularity, "algorithm", algorithm),
                        businessResolution));
        return advancedToChatResult(forecast, "FORECAST", "已根据语义直接触发时序预测。");
    }

    private Map<String, Object> buildAlertRuleDraft(String question, String tableName, SmartIntent intent) {
        return buildAlertRuleDraft(question, tableName, intent, ThinkingEmitter.noop());
    }

    private Map<String, Object> buildAlertRuleDraft(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        List<Map<String, Object>> fields = safeFields(tableName);
        BusinessSemanticService.BusinessAnalysisResolution businessResolution =
                resolveBusinessAnalysis(question, tableName, intent.slots(), fields);
        String timeField = firstText(intent.slots().get("timeField"), chooseField(fields, "DATE", question));
        String metricField = firstText(businessResolution.metricColumn(), intent.slots().get("metricField"),
                chooseMetricField(fields, question), text(intent.slots().get("metric")));
        Object threshold = firstPresent(intent.slots().get("threshold"), inferThreshold(question));
        String operator = firstText(intent.slots().get("operator"), inferOperator(question));
        String channel = firstText(intent.slots().get("channel"), inferChannel(question));

        List<String> missing = new ArrayList<>();
        if (metricField.isBlank()) missing.add("metricField");
        if (timeField.isBlank()) missing.add("timeField");
        if (!"zscore".equals(operator) && threshold == null) missing.add("threshold");
        if (!missing.isEmpty()) {
            SmartIntent clarification = intent.withMissing(missing, "预警规则缺少必要参数：" + String.join(", ", missing));
            emit(trace, "NEEDS_INPUT", "预警信息不足", "缺少 " + String.join("、", missing) + "，已转为补充信息提示",
                    Map.of("missingSlots", missing));
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
        if (businessResolution.matched()) {
            draft.put("metricLabel", firstText(businessResolution.metricLabel(), metricField));
            draft.put("businessSemanticTrace", businessResolution.trace());
            if (businessResolution.formulaApplied()) {
                draft.put("formula", businessResolution.formula());
                draft.put("metricExpression", businessResolution.metricExpression());
            }
        }

        Map<String, Object> result = draftCard("ALERT_RULE_DRAFT", "已识别为预警规则创建意图，已生成规则草稿，请确认后再创建。", draft);
        result.put("requiresConfirmation", true);
        result.put("sideEffectMode", "DRAFT_ONLY");
        result.put("chartType", "table");
        if (businessResolution.matched()) {
            result.put("businessSemanticTrace", businessResolution.trace());
            result.put("fieldMapping", Map.of(
                    "metric", firstText(businessResolution.metricLabel(), metricField),
                    "metricKey", metricField,
                    "metricField", metricField,
                    "timeField", timeField
            ));
        }
        emit(trace, "ALERT_DRAFT_READY", "生成预警草稿",
                "当 " + metricField + " " + operatorLabel(operator) + " " + threshold + " 时提醒，等待确认后创建",
                withBusinessTrace(metadataOf("metricField", metricField, "timeField", timeField,
                        "operator", operator, "threshold", threshold), businessResolution));
        return result;
    }

    private Map<String, Object> executeAlertEventQuery(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        Map<String, Object> request = new LinkedHashMap<>();
        Long ruleId = alertRuleId(question, intent.slots());
        if (ruleId != null && ruleId > 0) {
            request.put("ruleId", ruleId);
        }
        emit(trace, "ALERT_EVENT_QUERY", "查询预警事件",
                ruleId == null ? "正在查询最近触发的预警事件" : "正在查询规则 #" + ruleId + " 的预警事件",
                metadataOf("ruleId", ruleId));
        List<Map<String, Object>> events = advancedAnalysisService.listAlertEvents(request);
        List<Map<String, Object>> rows = events.stream()
                .map(this::compactAlertEventRow)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", "ALERT_EVENT_QUERY");
        result.put("message", rows.isEmpty() ? "暂未查询到已触发的预警事件。" : "已查询到 " + rows.size() + " 条预警事件。");
        result.put("chartType", "table");
        result.put("data", rows);
        result.put("dimensions", List.of("id", "ruleName", "ruleId", "status", "metricField", "actualValue", "threshold", "bucketName", "createdAt"));
        result.put("tableColumns", alertEventTableColumns());
        result.put("alertEvents", events);
        result.put("alertEventSummary", Map.of(
                "total", rows.size(),
                "open", rows.stream().filter(row -> "OPEN".equals(text(row.get("status")))).count(),
                "ack", rows.stream().filter(row -> "ACK".equals(text(row.get("status")))).count(),
                "closed", rows.stream().filter(row -> "CLOSED".equals(text(row.get("status")))).count()
        ));
        result.put("smartRouted", true);
        result.put("requiresConfirmation", false);
        return result;
    }

    private List<Map<String, Object>> alertEventTableColumns() {
        return List.of(
                Map.of("prop", "id", "label", "ID"),
                Map.of("prop", "ruleName", "label", "规则名"),
                Map.of("prop", "status", "label", "状态"),
                Map.of("prop", "bucketName", "label", "触发周期"),
                Map.of("prop", "createdAt", "label", "触发时间")
        );
    }

    private List<Map<String, Object>> alertRuleDetectionTableColumns() {
        return List.of(
                Map.of("prop", "ruleId", "label", "规则ID"),
                Map.of("prop", "ruleName", "label", "规则名"),
                Map.of("prop", "checkedRules", "label", "检查规则数"),
                Map.of("prop", "createdEvents", "label", "新增事件"),
                Map.of("prop", "refreshedEvents", "label", "刷新快照"),
                Map.of("prop", "skippedRules", "label", "跳过规则数"),
                Map.of("prop", "status", "label", "规则状态")
        );
    }

    private Map<String, Object> alertRuleDetectionSummaryRow(Long ruleId, Map<String, Object> rule, Map<String, Object> detection) {
        Map<String, Object> row = new LinkedHashMap<>();
        putIfMeaningful(row, "ruleId", ruleId);
        putIfMeaningful(row, "ruleName", firstText(rule.get("ruleName"), rule.get("name"), rule.get("title")));
        putIfMeaningful(row, "status", rule.get("status"));
        putIfMeaningful(row, "tableName", rule.get("tableName"));
        putIfMeaningful(row, "metricField", rule.get("metricField"));
        putIfMeaningful(row, "checkedRules", detection.getOrDefault("checkedRules", 0));
        putIfMeaningful(row, "createdEvents", detection.getOrDefault("createdEvents", 0));
        putIfMeaningful(row, "refreshedEvents", detection.getOrDefault("refreshedEvents", 0));
        putIfMeaningful(row, "skippedRules", detection.getOrDefault("skippedRules", 0));
        putIfMeaningful(row, "scope", detection.get("scope"));
        return row;
    }

    private String alertRuleDetectionMessage(Long ruleId, Map<String, Object> rule, Map<String, Object> detection) {
        int checked = (int) Math.round(readDouble(detection.get("checkedRules"), 0D));
        int skipped = (int) Math.round(readDouble(detection.get("skippedRules"), 0D));
        int created = (int) Math.round(readDouble(detection.get("createdEvents"), 0D));
        int refreshed = (int) Math.round(readDouble(detection.get("refreshedEvents"), 0D));
        String ruleName = firstText(rule.get("ruleName"), "预警规则 #" + ruleId);
        String status = firstText(rule.get("status"));
        if (checked <= 0 && !status.isBlank() && !"ACTIVE".equalsIgnoreCase(status)) {
            return ruleName + " 当前状态为 " + status + "，本次未执行检测；请先启用规则后再检测。";
        }
        return ruleName + " 检测完成：检查 " + checked + " 条规则，新增 " + created
                + " 条预警事件，刷新 " + refreshed + " 条快照，跳过 " + skipped + " 条规则。";
    }

    private Map<String, Object> executeAlertEventExplain(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        Long eventId = alertEventId(question, intent.slots());
        if (eventId == null || eventId <= 0) {
            return buildClarificationResult(question, tableName,
                    intent.withMissing(List.of("eventId"), "解释预警事件需要明确事件 ID，或从当前上下文带入最近一条预警事件。"));
        }
        emit(trace, "ALERT_EVENT_EXPLAIN", "解释预警原因", "正在读取预警事件 #" + eventId + " 的触发证据",
                metadataOf("eventId", eventId));
        Map<String, Object> event = advancedAnalysisService.getAlertEvent(eventId);
        Map<String, Object> explanation = alertEventExplanation(event);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", "ALERT_EVENT_EXPLAIN");
        result.put("message", firstText(explanation.get("summary"), "已生成预警事件触发原因说明。"));
        result.put("chartType", "alert");
        result.put("data", List.of(compactAlertEventRow(event)));
        result.put("dimensions", List.of("id", "ruleId", "status", "metricField", "actualValue", "threshold", "bucketName", "createdAt"));
        result.put("alertEvent", event);
        result.put("alertExplanation", explanation);
        result.put("smartRouted", true);
        result.put("requiresConfirmation", false);
        return result;
    }

    private Map<String, Object> executeAlertEventLifecycleAction(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        Long eventId = alertEventId(question, intent.slots());
        if (eventId == null || eventId <= 0) {
            return buildClarificationResult(question, tableName,
                    intent.withMissing(List.of("eventId"), "确认或关闭预警事件需要明确事件 ID。"));
        }
        String status = switch (intent.primaryIntent()) {
            case "ALERT_EVENT_CLOSE" -> "CLOSED";
            case "ALERT_EVENT_REOPEN" -> "OPEN";
            default -> "ACK";
        };
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("eventId", eventId);
        draft.put("status", status);
        draft.put("sourceQuestion", question);
        draft.put("handleNote", alertHandleNote(question));
        draft.put("sideEffect", "UPDATE_ALERT_EVENT_STATUS");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", status);
        request.put("handleNote", draft.get("handleNote"));
        emit(trace, "ALERT_EVENT_STATUS_UPDATE", "更新预警事件状态",
                "正在将预警事件 #" + eventId + " 更新为 " + status, metadataOf("eventId", eventId, "status", status));
        Map<String, Object> event = advancedAnalysisService.updateAlertEventStatus(eventId, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", intent.primaryIntent());
        result.put("message", switch (status) {
            case "CLOSED" -> "预警事件已关闭。";
            case "OPEN" -> "预警事件已重开。";
            default -> "预警事件已确认。";
        });
        result.put("chartType", "alert");
        result.put("data", List.of(compactAlertEventRow(event)));
        result.put("dimensions", List.of("id", "ruleId", "status", "metricField", "actualValue", "threshold", "bucketName", "createdAt"));
        result.put("alertEvent", event);
        result.put("userConfirmed", true);
        result.put("smartRouted", true);
        result.put("requiresConfirmation", false);
        return result;
    }

    private Map<String, Object> executeAlertRuleLifecycleAction(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        Long ruleId = alertRuleId(question, intent.slots());
        if (ruleId == null || ruleId <= 0) {
            return buildClarificationResult(question, tableName,
                    intent.withMissing(List.of("ruleId"), "修改、停用或删除预警规则需要明确规则 ID。"));
        }
        Map<String, Object> draft = alertRuleActionDraft(question, tableName, intent, ruleId);
        if ("ALERT_RULE_DELETE".equals(intent.primaryIntent()) && !userConfirmed(intent)) {
            return alertActionDraft("ALERT_RULE_ACTION_DRAFT",
                    alertRuleDraftMessage(intent.primaryIntent(), ruleId),
                    draft);
        }
        emit(trace, "ALERT_RULE_ACTION_EXECUTE", "执行预警规则操作",
                alertRuleExecuteMessage(intent.primaryIntent(), ruleId), metadataOf("ruleId", ruleId, "intent", intent.primaryIntent()));
        Map<String, Object> rule;
        if ("ALERT_RULE_DELETE".equals(intent.primaryIntent())) {
            rule = advancedAnalysisService.deleteAlertRule(ruleId);
        } else if ("ALERT_RULE_DISABLE".equals(intent.primaryIntent())) {
            rule = advancedAnalysisService.updateAlertRuleStatus(ruleId, Map.of("status", "DISABLED"));
        } else if ("ALERT_RULE_ENABLE".equals(intent.primaryIntent())) {
            rule = advancedAnalysisService.updateAlertRuleStatus(ruleId, Map.of("status", "ACTIVE"));
        } else {
            Map<String, Object> request = new LinkedHashMap<>(mapValue(draft.get("patch")));
            request.put("id", ruleId);
            rule = advancedAnalysisService.updateAlertRule(ruleId, request);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", intent.primaryIntent());
        result.put("message", alertRuleDoneMessage(intent.primaryIntent()));
        result.put("chartType", "alert");
        result.put("data", List.of(compactAlertRuleRow(rule)));
        result.put("dimensions", List.of("id", "ruleName", "status", "metricField", "threshold", "detectionCycle"));
        result.put("alertRule", rule);
        result.put("userConfirmed", true);
        result.put("smartRouted", true);
        result.put("requiresConfirmation", false);
        return result;
    }

    private Map<String, Object> executeAlertRuleDetectAction(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        Long ruleId = alertRuleId(question, intent.slots());
        if (ruleId == null || ruleId <= 0) {
            return buildClarificationResult(question, tableName,
                    intent.withMissing(List.of("ruleId"), "检测预警规则需要明确规则 ID。"));
        }
        emit(trace, "ALERT_RULE_DETECT", "执行预警检测",
                "正在对预警规则 #" + ruleId + " 执行一次手动检测",
                metadataOf("ruleId", ruleId));
        Map<String, Object> rule = advancedAnalysisService.getAlertRule(ruleId);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ruleId", ruleId);
        request.put("force", true);
        Map<String, Object> detection = advancedAnalysisService.runAlertRuleDetection(request);
        List<Map<String, Object>> events = castRows(detection.get("events"));
        List<Map<String, Object>> eventRows = events.stream()
                .map(this::compactAlertEventRow)
                .toList();
        Map<String, Object> summary = alertRuleDetectionSummaryRow(ruleId, rule, detection);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", "ALERT_RULE_DETECT");
        result.put("message", alertRuleDetectionMessage(ruleId, rule, detection));
        result.put("chartType", "table");
        result.put("data", eventRows.isEmpty() ? List.of(summary) : eventRows);
        result.put("dimensions", eventRows.isEmpty()
                ? List.of("ruleId", "ruleName", "checkedRules", "createdEvents", "refreshedEvents", "skippedRules", "status")
                : List.of("id", "ruleName", "ruleId", "status", "metricField", "actualValue", "threshold", "bucketName", "createdAt"));
        result.put("tableColumns", eventRows.isEmpty() ? alertRuleDetectionTableColumns() : alertEventTableColumns());
        result.put("alertRule", rule);
        result.put("alertEvents", events);
        result.put("alertDetectionSummary", summary);
        result.put("checkedRules", detection.getOrDefault("checkedRules", 0));
        result.put("skippedRules", detection.getOrDefault("skippedRules", 0));
        result.put("createdEvents", detection.getOrDefault("createdEvents", 0));
        result.put("refreshedEvents", detection.getOrDefault("refreshedEvents", 0));
        result.put("smartRouted", true);
        result.put("requiresConfirmation", false);
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
        return executeBusinessModelAgent(question, tableName, intent, ThinkingEmitter.noop());
    }

    private Map<String, Object> executeBusinessModelAgent(String question, String tableName, SmartIntent intent, ThinkingEmitter trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("tableName", tableName);
        payload.put("selectedTableName", tableName);
        Map<String, Object> slots = intent.slots() == null ? Map.of() : intent.slots();
        putIfPresent(payload, "activeBusinessModelId", slots.get("activeBusinessModelId"));
        putIfPresent(payload, "lastCreatedBusinessModelId", slots.get("lastCreatedBusinessModelId"));
        putIfPresent(payload, "lastAppliedBusinessModelId", slots.get("lastAppliedBusinessModelId"));
        putIfPresent(payload, "selectedTableName", slots.get("selectedTableName"));
        putIfPresent(payload, "modelId", slots.get("modelId"));
        putIfPresent(payload, "modelName", slots.get("modelName"));
        putIfPresent(payload, "modelCategory", slots.get("modelCategory"));
        emit(trace, "MODEL_CONTEXT_READY", "定位业务模型",
                tableName.isBlank() ? "正在结合当前会话模型上下文执行维护指令" : "正在基于 " + tableName + " 执行业务模型维护",
                Map.of("intent", intent.primaryIntent(), "tableName", tableName));
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
        emit(trace, "MODEL_OPERATION_FINISHED", "模型操作完成",
                describeBusinessModelResult(agent), Map.of("intent", firstText(agent.get("intent"), intent.primaryIntent())));
        return result;
    }

    private Map<String, Object> executeDashboardPin(String question,
                                                    String tableName,
                                                    SmartIntent intent,
                                                    ChatBiService.ChatQueryRequest request) {
        return executeDashboardPin(question, tableName, intent, request, ThinkingEmitter.noop());
    }

    private Map<String, Object> executeDashboardPin(String question,
                                                    String tableName,
                                                    SmartIntent intent,
                                                    ChatBiService.ChatQueryRequest request,
                                                    ThinkingEmitter trace) {
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
            emit(trace, "NEEDS_INPUT", "缺少可钉入图表", "当前会话没有可钉入的图表结果，需要先完成一次图表查询",
                    metadataOf("conversationId", conversationId));
            return result;
        }
        emit(trace, "DASHBOARD_SOURCE_RESOLVING", "定位图表来源",
                "正在优先使用当前选中图表，缺失时回退到会话最近图表", metadataOf("conversationId", conversationId));
        Map<String, Object> source = explicitPinSource(request, question, tableName);
        if (source.isEmpty()) {
            Map<String, Object> artifact = chatConversationService.latestChartArtifactForConversation(conversationId);
            if (artifact != null && !artifact.isEmpty()) {
                source = sourceFromArtifact(artifact, question, tableName);
            }
        }
        if (source.isEmpty()) {
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
            emit(trace, "NEEDS_INPUT", "缺少可钉入图表", "未找到当前图表或历史图表，需要先完成一次图表查询",
                    metadataOf("conversationId", conversationId));
            return result;
        }
        emit(trace, "DASHBOARD_TARGET_RESOLVING", "识别目标看板",
                "已找到图表来源，正在根据用户语义匹配可编辑且已发布的目标看板",
                Map.of("source", compactPinSource(source)));

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
        emit(trace, "DASHBOARD_PIN_FINISHED", "看板动作完成",
                trimTo(firstText(result.get("message"), "看板钉入动作已处理"), 120),
                metadataOf("requiresConfirmation", readBoolean(result.get("requiresConfirmation")),
                        "status", firstText(result.get("status"), result.get("dashboardActionStatus"))));
        return result;
    }

    private SmartActionPlan buildMultiStepPlan(String question, String tableName, ChatBiService.ChatQueryRequest request) {
        return buildMultiStepPlan(question, tableName, request, ThinkingEmitter.noop());
    }

    private SmartActionPlan buildMultiStepPlan(String question,
                                               String tableName,
                                               ChatBiService.ChatQueryRequest request,
                                               ThinkingEmitter trace) {
        String q = text(question);
        if (q.isBlank() || isBusinessModelIntent(q)) {
            return SmartActionPlan.empty();
        }
        boolean queryTask = hasQueryTask(q);
        boolean forecastTask = hasForecastTask(q);
        boolean alertTask = hasAlertTask(q);
        boolean dashboardPinTask = isDashboardIntent(q);
        emit(trace, "PLAN_SEMANTIC_FLAGS", "识别动作线索",
                "查询：" + yesNo(queryTask) + "，预测：" + yesNo(forecastTask)
                        + "，预警：" + yesNo(alertTask) + "，看板：" + yesNo(dashboardPinTask),
                Map.of("queryTask", queryTask, "forecastTask", forecastTask, "alertTask", alertTask, "dashboardPinTask", dashboardPinTask));
        if (dashboardPinTask && !hasAnalyticContentBeforeDashboardAction(q)) {
            return SmartActionPlan.empty();
        }
        boolean hasAnalyticTask = queryTask || forecastTask || alertTask;
        if (dashboardPinTask && !hasAnalyticTask) {
            return SmartActionPlan.empty();
        }
        if (!isPotentialMultiStepQuestion(q, queryTask, forecastTask, alertTask, dashboardPinTask)) {
            return SmartActionPlan.empty();
        }
        emit(trace, "PLAN_CONTEXT_PREPARE", "准备编排上下文", "检测到复合语义，正在读取字段信息并准备动作编排",
                Map.of("tableName", tableName));
        Map<String, Object> context = withModelContext(buildAdvancedContext(tableName),
                request == null ? Map.of() : request.getFilters());
        emit(trace, "PLAN_MODEL_CALL", "调用编排模型", "正在生成多步骤动作计划和依赖顺序",
                Map.of("fieldCount", collectionSize(context.get("fields"))));
        Optional<Map<String, Object>> smartRoute = pythonAiService.smartChatRoute(question, tableName, context);
        if (smartRoute != null && smartRoute.isPresent()) {
            emit(trace, "PLAN_MODEL_RESULT", "编排模型返回", "已收到 AI 动作编排结果，正在做语义校验和缺失动作补全",
                    Map.of("hasActions", smartRoute.get().containsKey("actions")));
            SmartActionPlan aiPlan = actionPlanFromAiRoute(question, tableName, smartRoute.get(), request);
            aiPlan = completePlanWithSemanticTasks(question, tableName, aiPlan, queryTask, forecastTask, alertTask, request);
            if (dashboardPinTask) {
                aiPlan = withDeferredDashboardPin(aiPlan, question);
            }
            if (aiPlan.isMultiStep()) {
                return aiPlan;
            }
        }

        if (!forecastTask && !(dashboardPinTask && queryTask)) {
            return SmartActionPlan.empty();
        }

        List<SmartActionStep> actions = new ArrayList<>();
        Map<String, Object> slots = inferPlanSlots(question, tableName, request);
        if (dashboardPinTask) {
            slots.put("deferredDashboardPin", true);
            slots.put("dashboardPinQuestion", dashboardPinQuestionForPlan(question));
        }
        if (queryTask) {
            actions.add(new SmartActionStep("query_1", "QUERY_SQL", List.of(),
                    queryQuestionForPlan(question), Map.of(), false));
        }
        if (forecastTask) {
            List<String> dependsOn = actions.stream()
                    .filter(action -> "QUERY_SQL".equals(action.type()))
                    .map(SmartActionStep::id)
                    .toList();
            actions.add(new SmartActionStep("forecast_1", "FORECAST", dependsOn,
                    forecastQuestionForPlan(question), slots, false));
        }
        if (alertTask) {
            List<String> dependsOn = actions.stream()
                    .filter(action -> "FORECAST".equals(action.type()))
                    .map(SmartActionStep::id)
                    .toList();
            actions.add(new SmartActionStep("alert_1", "ALERT_RULE_CREATE_DRAFT", dependsOn,
                    alertQuestionForPlan(question), slots, true));
        }
        if (actions.size() <= 1) {
            return SmartActionPlan.empty();
        }
        boolean requiresConfirmation = alertTask || dashboardPinTask || actions.stream().anyMatch(SmartActionStep::requiresConfirmation);
        return new SmartActionPlan("MULTI_STEP", actions, slots, 0.74D, requiresConfirmation,
                "识别到复合 BI 任务，按动作依赖顺序编排执行", true);
    }

    private SmartActionPlan completePlanWithSemanticTasks(String question,
                                                          String tableName,
                                                          SmartActionPlan plan,
                                                          boolean queryTask,
                                                          boolean forecastTask,
                                                          boolean alertTask,
                                                          ChatBiService.ChatQueryRequest request) {
        SmartActionPlan source = plan == null ? SmartActionPlan.empty() : plan;
        List<SmartActionStep> actions = new ArrayList<>();
        Map<String, Object> canonicalSlots = mergeCanonicalSlots(source.slots(), inferPlanSlots(question, tableName, request));
        for (SmartActionStep action : source.actions()) {
            actions.add(new SmartActionStep(
                    action.id(),
                    action.type(),
                    action.dependsOn(),
                    stepQuestionForPlan(action.type(), question, action.question()),
                    mergeCanonicalSlots(action.slots(), canonicalSlots),
                    action.requiresConfirmation(),
                    action.confidence()
            ));
        }

        if (queryTask && actions.stream().noneMatch(action -> "QUERY_SQL".equals(action.type()))) {
            actions.add(0, new SmartActionStep("query_1", "QUERY_SQL", List.of(),
                    queryQuestionForPlan(question), canonicalSlots, false));
        }
        if (forecastTask && actions.stream().noneMatch(action -> "FORECAST".equals(action.type()))) {
            List<String> dependsOn = actions.stream()
                    .filter(action -> "QUERY_SQL".equals(action.type()))
                    .map(SmartActionStep::id)
                    .toList();
            actions.add(new SmartActionStep("forecast_1", "FORECAST", dependsOn,
                    forecastQuestionForPlan(question), canonicalSlots, false));
        }
        if (alertTask && actions.stream().noneMatch(action -> "ALERT_RULE_CREATE_DRAFT".equals(action.type()))) {
            List<String> dependsOn = actions.stream()
                    .filter(action -> "FORECAST".equals(action.type()))
                    .map(SmartActionStep::id)
                    .toList();
            actions.add(new SmartActionStep("alert_1", "ALERT_RULE_CREATE_DRAFT", dependsOn,
                    alertQuestionForPlan(question), canonicalSlots, true));
        }
        actions = normalizePlanDependencies(actions);
        if (actions.size() <= 1) {
            return SmartActionPlan.empty();
        }
        return new SmartActionPlan(
                firstText(source.primaryIntent(), "MULTI_STEP"),
                actions,
                canonicalSlots,
                source.confidence() > 0D ? source.confidence() : 0.76D,
                source.requiresConfirmation() || alertTask || actions.stream().anyMatch(SmartActionStep::requiresConfirmation),
                firstText(source.reasoning(), "按用户原句语义校验并补全复合任务动作"),
                source.fallbackUsed()
        );
    }

    private List<SmartActionStep> normalizePlanDependencies(List<SmartActionStep> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<String> queryIds = actions.stream()
                .filter(action -> "QUERY_SQL".equals(action.type()))
                .map(SmartActionStep::id)
                .toList();
        List<String> forecastIds = actions.stream()
                .filter(action -> "FORECAST".equals(action.type()))
                .map(SmartActionStep::id)
                .toList();
        List<SmartActionStep> normalized = new ArrayList<>();
        for (SmartActionStep action : actions) {
            List<String> dependsOn = new ArrayList<>(action.dependsOn());
            if ("FORECAST".equals(action.type()) && !queryIds.isEmpty()) {
                for (String id : queryIds) {
                    if (!dependsOn.contains(id)) {
                        dependsOn.add(id);
                    }
                }
            } else if ("ALERT_RULE_CREATE_DRAFT".equals(action.type()) && !forecastIds.isEmpty()) {
                for (String id : forecastIds) {
                    if (!dependsOn.contains(id)) {
                        dependsOn.add(id);
                    }
                }
            }
            normalized.add(new SmartActionStep(action.id(), action.type(), dependsOn,
                    action.question(), action.slots(), action.requiresConfirmation(), action.confidence()));
        }
        return normalized;
    }

    private SmartActionPlan actionPlanFromAiRoute(String question,
                                                  String tableName,
                                                  Map<String, Object> routed,
                                                  ChatBiService.ChatQueryRequest request) {
        Object rawActions = routed == null ? null : routed.get("actions");
        if (!(rawActions instanceof List<?> list) || list.size() <= 1) {
            return SmartActionPlan.empty();
        }
        List<SmartActionStep> actions = new ArrayList<>();
        Map<String, Object> planSlots = new LinkedHashMap<>(mapValue(routed.get("slots")));
        int queryIndex = 1;
        int forecastIndex = 1;
        int alertIndex = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> action.put(String.valueOf(key), value));
            String type = normalizeActionType(firstText(action.get("type"), action.get("intent"), action.get("actionType")));
            if (type.isBlank()) {
                continue;
            }
            if (!List.of("QUERY_SQL", "FORECAST", "ALERT_RULE_CREATE_DRAFT").contains(type)) {
                return SmartActionPlan.empty();
            }
            Map<String, Object> slots = new LinkedHashMap<>(planSlots);
            slots.putAll(mapValue(action.get("slots")));
            String id;
            List<String> dependsOn = stringList(action.get("dependsOn"));
            boolean requiresConfirmation = readBoolean(action.get("requiresConfirmation"));
            double actionConfidence = readDouble(action.get("confidence"), 0D);
            if ("QUERY_SQL".equals(type)) {
                id = firstText(action.get("id"), "query_" + queryIndex++);
                actions.add(new SmartActionStep(id, type, dependsOn,
                        queryQuestionForPlan(firstText(action.get("question"), question)), slots, requiresConfirmation,
                        actionConfidence));
            } else if ("FORECAST".equals(type)) {
                id = firstText(action.get("id"), "forecast_" + forecastIndex++);
                if (dependsOn.isEmpty() && actions.stream().anyMatch(step -> "QUERY_SQL".equals(step.type()))) {
                    dependsOn = actions.stream().filter(step -> "QUERY_SQL".equals(step.type())).map(SmartActionStep::id).toList();
                }
                actions.add(new SmartActionStep(id, type, dependsOn,
                        forecastQuestionForPlan(firstText(action.get("question"), question)), slots, requiresConfirmation,
                        actionConfidence));
            } else {
                id = firstText(action.get("id"), "alert_" + alertIndex++);
                if (dependsOn.isEmpty() && actions.stream().anyMatch(step -> "FORECAST".equals(step.type()))) {
                    dependsOn = actions.stream().filter(step -> "FORECAST".equals(step.type())).map(SmartActionStep::id).toList();
                }
                actions.add(new SmartActionStep(id, type, dependsOn,
                        alertQuestionForPlan(firstText(action.get("question"), question)), slots, true,
                        actionConfidence));
            }
        }
        boolean hasForecast = actions.stream().anyMatch(action -> "FORECAST".equals(action.type()));
        boolean hasQueryOrAlert = actions.stream().anyMatch(action -> "QUERY_SQL".equals(action.type())
                || "ALERT_RULE_CREATE_DRAFT".equals(action.type()));
        if (actions.size() <= 1 || !hasForecast || !hasQueryOrAlert) {
            return SmartActionPlan.empty();
        }
        List<String> missingSlots = stringList(routed.get("missingSlots"));
        boolean needClarification = readBoolean(routed.get("needClarification")) || !missingSlots.isEmpty();
        return new SmartActionPlan("MULTI_STEP", actions, planSlots,
                readDouble(routed.get("confidence"), 0.78D),
                actions.stream().anyMatch(SmartActionStep::requiresConfirmation) || needClarification,
                firstText(routed.get("reasoning"), "AI 生成多步骤动作编排"),
                readBoolean(routed.get("fallbackUsed")),
                needClarification,
                missingSlots);
    }

    private Map<String, Object> executeMultiStepPlan(SmartActionPlan plan,
                                                     ChatBiService.ChatQueryRequest request,
                                                     String executionQuestion,
                                                     String actionQuestion,
                                                     String tableName) {
        return executeMultiStepPlan(plan, request, executionQuestion, actionQuestion, tableName, ThinkingEmitter.noop());
    }

    private Map<String, Object> executeMultiStepPlan(SmartActionPlan plan,
                                                     ChatBiService.ChatQueryRequest request,
                                                     String executionQuestion,
                                                     String actionQuestion,
                                                     String tableName,
                                                     ThinkingEmitter trace) {
        List<Map<String, Object>> stepResults = new ArrayList<>();
        Map<String, Map<String, Object>> resultByStep = new LinkedHashMap<>();
        Map<String, Object> queryResult = null;
        Map<String, Object> forecastResult = null;
        Map<String, Object> alertResult = null;

        for (SmartActionStep action : plan.actions()) {
            List<String> blockedBy = action.dependsOn().stream()
                    .filter(id -> !isCompletedStep(resultByStep.get(id)))
                    .toList();
            if (!blockedBy.isEmpty()) {
                Map<String, Object> skipped = stepResult(action, "SKIPPED",
                        "依赖步骤未完成，已跳过该动作：" + String.join(", ", blockedBy), null, null);
                stepResults.add(skipped);
                resultByStep.put(action.id(), skipped);
                emit(trace, "STEP_SKIPPED", stepTitle(action, "跳过动作"),
                        firstText(skipped.get("message")), Map.of("stepId", action.id(), "type", action.type()));
                continue;
            }
            try {
                Map<String, Object> current;
                emit(trace, "STEP_STARTED", stepTitle(action, "开始执行"), describeActionStart(action),
                        Map.of("stepId", action.id(), "type", action.type(), "dependsOn", action.dependsOn()));
                if ("QUERY_SQL".equals(action.type())) {
                    ChatBiService.ChatQueryRequest queryRequest = copyRequestForStep(request,
                            firstText(action.question(), executionQuestion), tableName);
                    current = executeQuery(queryRequest, firstText(action.question(), actionQuestion), tableName,
                            new SmartIntent("QUERY_SQL", plan.confidence(), false,
                                    "多步骤编排中的查询动作", action.slots(), plan.fallbackUsed()), trace);
                    queryResult = current;
                } else if ("FORECAST".equals(action.type())) {
                    current = executeForecast(firstText(action.question(), actionQuestion), tableName,
                            new SmartIntent("FORECAST", plan.confidence(), false,
                                    "多步骤编排中的预测动作", action.slots(), plan.fallbackUsed()), trace);
                    if ("CLARIFICATION".equalsIgnoreCase(text(current.get("responseType")))) {
                        Map<String, Object> needsInput = stepResult(action, "NEEDS_INPUT",
                                firstText(current.get("message"), "预测参数不完整"), current, null);
                        stepResults.add(needsInput);
                        resultByStep.put(action.id(), needsInput);
                        emit(trace, "STEP_NEEDS_INPUT", stepTitle(action, "需要补充"), firstText(needsInput.get("message")),
                                Map.of("stepId", action.id(), "type", action.type()));
                        continue;
                    }
                    forecastResult = current;
                } else if ("ALERT_RULE_CREATE_DRAFT".equals(action.type())) {
                    current = buildAlertRuleDraft(firstText(action.question(), actionQuestion), tableName,
                            new SmartIntent("ALERT_RULE_CREATE", plan.confidence(), true,
                                    "多步骤编排中的预警规则草稿动作", action.slots(), plan.fallbackUsed()), trace);
                    alertResult = current;
                } else {
                    current = buildClarificationResult(actionQuestion, tableName,
                            new SmartIntent("CLARIFY", plan.confidence(), true,
                                    "暂不支持的编排动作：" + action.type(), action.slots(), plan.fallbackUsed()));
                }
                String status = stepStatusFromResult(current);
                Map<String, Object> finished = stepResult(action, status,
                        firstText(current.get("message"), "动作执行完成"), current, null);
                stepResults.add(finished);
                resultByStep.put(action.id(), finished);
                emit(trace, "STEP_FINISHED", stepTitle(action, "动作完成"),
                        firstText(finished.get("message")), Map.of("stepId", action.id(), "type", action.type(), "status", status));
            } catch (Exception e) {
                Map<String, Object> failed = stepResult(action, "FAILED",
                        "动作执行失败：" + firstText(e.getMessage(), e.getClass().getSimpleName()), null, e);
                stepResults.add(failed);
                resultByStep.put(action.id(), failed);
                emit(trace, "STEP_FAILED", stepTitle(action, "动作失败"),
                        firstText(failed.get("message")), Map.of("stepId", action.id(), "type", action.type()));
            }
        }

        Map<String, Object> result = aggregateMultiStepResult(plan, actionQuestion, tableName,
                queryResult, forecastResult, alertResult, stepResults);
        result.put("responseType", "MULTI_STEP");
        result.put("smartRouted", true);
        result.put("stepResults", stepResults);
        result.put("multiStep", true);
        result.put("requiresConfirmation", stepResults.stream()
                .anyMatch(step -> "NEEDS_CONFIRMATION".equals(text(step.get("status")))));
        if (alertResult != null) {
            result.put("alertRuleDraft", alertResult.getOrDefault("draft", Map.of()));
            result.put("sideEffectMode", "DRAFT_ONLY");
        }
        emit(trace, "PLAN_FINISHED", "复合任务完成", trimTo(firstText(result.get("message")), 120),
                Map.of("summary", result.get("multiStepSummary")));
        return result;
    }

    private Map<String, Object> aggregateMultiStepResult(SmartActionPlan plan,
                                                         String question,
                                                         String tableName,
                                                         Map<String, Object> queryResult,
                                                         Map<String, Object> forecastResult,
                                                         Map<String, Object> alertResult,
                                                         List<Map<String, Object>> stepResults) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> visualSource = forecastResult != null ? forecastResult : queryResult;
        if (visualSource != null) {
            result.putAll(visualSource);
            if (forecastResult != null && queryResult != null) {
                inheritQueryVisualPolicy(result, queryResult);
            }
        } else if (alertResult != null) {
            result.putAll(alertResult);
        } else {
            result.putAll(draftCard("MULTI_STEP", "多步骤任务未能完成，请根据步骤结果补充信息后重试。", Map.of(
                    "primaryIntent", "MULTI_STEP",
                    "question", question,
                    "tableName", tableName
            )));
        }

        long completed = stepResults.stream().filter(step -> "COMPLETED".equals(text(step.get("status")))).count();
        long needsConfirmation = stepResults.stream().filter(step -> "NEEDS_CONFIRMATION".equals(text(step.get("status")))).count();
        long failed = stepResults.stream().filter(step -> "FAILED".equals(text(step.get("status")))).count();
        long skipped = stepResults.stream().filter(step -> "SKIPPED".equals(text(step.get("status")))).count();

        String message;
        if (failed > 0 || skipped > 0) {
            message = "多步骤任务已部分完成：完成 " + completed + " 步，失败 " + failed + " 步，跳过 " + skipped + " 步。";
        } else if (needsConfirmation > 0) {
            message = "多步骤任务已完成可自动执行部分，并生成需要确认的草稿。";
        } else {
            message = "多步骤任务已按查询、预测等依赖顺序完成。";
        }
        result.put("message", message);
        result.put("queryQuestion", question);
        result.put("queryTableName", tableName);
        result.putIfAbsent("tableName", tableName);
        result.putIfAbsent("chartType", forecastResult != null ? "line" : firstText(result.get("chartType"), "bar"));
        result.putIfAbsent("data", List.of());
        result.putIfAbsent("dimensions", forecastResult != null
                ? List.of("name", "history", "forecast", "upper", "lower", "value", "anomaly")
                : List.of("name", "value"));
        if (queryResult != null) {
            result.put("queryStepResult", compactStepPayload(queryResult));
        }
        if (forecastResult != null) {
            result.put("forecastStepResult", compactStepPayload(forecastResult));
        }
        if (alertResult != null) {
            result.put("alertStepResult", compactStepPayload(alertResult));
        }
        result.put("multiStepSummary", Map.of(
                "total", plan.actions().size(),
                "completed", completed,
                "needsConfirmation", needsConfirmation,
                "failed", failed,
                "skipped", skipped
        ));
        attachDeferredDashboardPin(result, plan, question);
        return result;
    }

    private Map<String, Object> stepResult(SmartActionStep action,
                                           String status,
                                           String message,
                                           Map<String, Object> payload,
                                           Exception error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", action.id());
        result.put("type", action.type());
        result.put("dependsOn", action.dependsOn());
        result.put("status", status);
        result.put("message", message);
        result.put("confidence", action.confidence());
        result.put("requiresConfirmation", action.requiresConfirmation()
                || (payload != null && readBoolean(payload.get("requiresConfirmation"))));
        if (payload != null) {
            result.put("responseType", payload.get("responseType"));
            result.put("chartType", payload.get("chartType"));
            result.put("dataCount", payload.get("data") instanceof List<?> rows ? rows.size() : 0);
            result.put("payload", compactStepPayload(payload));
        }
        if (error != null) {
            result.put("error", firstText(error.getMessage(), error.getClass().getSimpleName()));
        }
        return result;
    }

    private void attachDeferredDashboardPin(Map<String, Object> result, SmartActionPlan plan, String question) {
        if (result == null || plan == null || !readBoolean(plan.slots().get("deferredDashboardPin"))) {
            return;
        }
        Map<String, Object> deferred = new LinkedHashMap<>();
        deferred.put("type", "DASHBOARD_PIN");
        deferred.put("question", firstText(plan.slots().get("dashboardPinQuestion"), question));
        deferred.put("source", "CURRENT_RESULT_AFTER_PERSIST");
        deferred.put("requiresPersistedChart", true);
        result.put("deferredDashboardPin", deferred);
    }

    private Map<String, Object> compactStepPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("responseType", "message", "sql", "chartType", "advancedAnalysisType",
                "forecastMeta", "draft", "fieldMapping", "queryTableName", "tableName", "sideEffectMode",
                "chartRecommendation", "chartRuleCode", "chartRuleName", "chartScenarioType",
                "chartRecommendationStatus", "chartRecommendationExplain", "voiceSummary", "optionTemplate",
                "businessSemanticTrace")) {
            if (payload.containsKey(key)) {
                compact.put(key, payload.get(key));
            }
        }
        Object data = payload.get("data");
        if (data instanceof List<?> rows) {
            compact.put("dataCount", rows.size());
            compact.put("dataPreview", rows.stream().limit(5).toList());
        }
        return compact;
    }

    private void inheritQueryVisualPolicy(Map<String, Object> result, Map<String, Object> queryResult) {
        if (result == null || queryResult == null || queryResult.isEmpty()) {
            return;
        }
        for (String key : List.of("chartRecommendation", "chartRuleCode", "chartRuleName", "chartScenarioType",
                "chartRecommendationStatus", "chartRecommendationExplain", "voiceSummary")) {
            Object value = queryResult.get(key);
            if (hasMeaningfulValue(value) && !hasMeaningfulValue(result.get(key))) {
                result.put(key, value);
            }
        }
        Object queryTemplate = queryResult.get("optionTemplate");
        if (queryTemplate instanceof Map<?, ?> queryTemplateMap) {
            Map<String, Object> inherited = toObjectMap(queryTemplateMap);
            Map<String, Object> current = result.get("optionTemplate") instanceof Map<?, ?> currentMap
                    ? toObjectMap(currentMap)
                    : Map.of();
            result.put("optionTemplate", deepMergeMaps(current, inherited));
        }
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return !text.toString().trim().isBlank();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private Map<String, Object> toObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = text(entry.getKey());
            if (!key.isBlank()) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    private Map<String, Object> deepMergeMaps(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (overlay == null || overlay.isEmpty()) {
            return merged;
        }
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            String key = text(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            Object current = merged.get(key);
            Object incoming = entry.getValue();
            if ("series".equals(key) && current instanceof List<?> currentList && incoming instanceof List<?> incomingList) {
                merged.put(key, mergeListByIndex(currentList, incomingList));
            } else if (current instanceof Map<?, ?> currentMap && incoming instanceof Map<?, ?> incomingMap) {
                merged.put(key, deepMergeMaps(toObjectMap(currentMap), toObjectMap(incomingMap)));
            } else if (incoming != null) {
                merged.put(key, incoming);
            }
        }
        return merged;
    }

    private List<Object> mergeListByIndex(List<?> base, List<?> overlay) {
        List<Object> merged = new ArrayList<>();
        int size = Math.max(base.size(), overlay.size());
        for (int i = 0; i < size; i++) {
            Object left = i < base.size() ? base.get(i) : null;
            Object right = i < overlay.size() ? overlay.get(i) : null;
            if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
                merged.add(deepMergeMaps(toObjectMap(leftMap), toObjectMap(rightMap)));
            } else if (right != null) {
                merged.add(right);
            } else if (left != null) {
                merged.add(left);
            }
        }
        return merged;
    }

    private boolean isCompletedStep(Map<String, Object> step) {
        if (step == null || step.isEmpty()) {
            return false;
        }
        String status = text(step.get("status"));
        return "COMPLETED".equals(status) || "NEEDS_CONFIRMATION".equals(status);
    }

    private String stepStatusFromResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return "FAILED";
        }
        if ("CLARIFICATION".equalsIgnoreCase(text(result.get("responseType")))) {
            return "NEEDS_INPUT";
        }
        if (readBoolean(result.get("requiresConfirmation"))) {
            return "NEEDS_CONFIRMATION";
        }
        return "COMPLETED";
    }

    private ChatBiService.ChatQueryRequest copyRequestForStep(ChatBiService.ChatQueryRequest source,
                                                               String question,
                                                               String tableName) {
        ChatBiService.ChatQueryRequest request = new ChatBiService.ChatQueryRequest();
        request.setQuestion(question);
        request.setConversationId(source == null ? null : source.getConversationId());
        request.setParentTurnId(source == null ? null : source.getParentTurnId());
        request.setTableNames(tableName == null || tableName.isBlank() ? List.of() : List.of(tableName));
        request.setMode(source == null ? "CHAT" : firstText(source.getMode(), "CHAT"));
        Map<String, Object> filters = new LinkedHashMap<>(source == null || source.getFilters() == null ? Map.of() : source.getFilters());
        if (!text(tableName).isBlank()) {
            filters.put("tableName", tableName);
        }
        request.setFilters(filters);
        return request;
    }

    private List<String> multiStepThinkingLogs(SmartActionPlan plan, List<Map<String, Object>> stepResults) {
        List<String> logs = new ArrayList<>();
        logs.add("统一语义路由：识别意图 MULTI_STEP");
        logs.add("动作编排：" + plan.actions().stream()
                .map(action -> action.id() + "=" + action.type()
                        + (action.dependsOn().isEmpty() ? "" : " dependsOn " + String.join(",", action.dependsOn())))
                .reduce((left, right) -> left + " -> " + right)
                .orElse("无动作"));
        for (Map<String, Object> step : stepResults) {
            logs.add("步骤结果：" + step.get("id") + "/" + step.get("type") + "=" + step.get("status")
                    + "，" + firstText(step.get("message")));
        }
        logs.add("执行策略：查询和预测可直接执行，预警规则仅生成草稿并等待确认");
        return logs;
    }

    private void emit(ThinkingEmitter emitter, String eventType, String title, String detail, Map<String, Object> metadata) {
        if (emitter == null) {
            return;
        }
        String safeTitle = firstText(title, eventType, "处理中");
        String safeDetail = firstText(detail, "正在处理当前步骤");
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        emitter.emit(firstText(eventType, "STEP"), safeTitle, safeDetail, safeMetadata);
    }

    private String describeIntent(SmartIntent intent) {
        String label = intentLabel(intent.primaryIntent());
        String confidence = intent.confidence() > 0D ? "，置信度 " + Math.round(intent.confidence() * 100) + "%" : "";
        String suffix = intent.requiresConfirmation() ? "，后续需要确认" : "，可自动执行";
        return "识别为" + label + confidence + suffix;
    }

    private int collectionSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private String describeActionPlan(SmartActionPlan plan) {
        String actions = plan.actions().stream()
                .map(action -> intentLabel(action.type()))
                .reduce((left, right) -> left + " -> " + right)
                .orElse("待确认动作");
        String reason = firstText(plan.reasoning());
        return reason.isBlank() ? "将按 " + actions + " 的顺序执行" : reason + "：" + actions;
    }

    private String describeActionStart(SmartActionStep action) {
        String question = trimTo(firstText(action.question()), 80);
        String prefix = switch (action.type()) {
            case "QUERY_SQL" -> "开始执行查询";
            case "FORECAST" -> "开始执行预测";
            case "ALERT_RULE_CREATE_DRAFT" -> "开始生成预警草稿";
            default -> "开始执行动作";
        };
        return question.isBlank() ? prefix : prefix + "：" + question;
    }

    private String stepTitle(SmartActionStep action, String fallback) {
        return switch (action.type()) {
            case "QUERY_SQL" -> fallback + "：查询";
            case "FORECAST" -> fallback + "：预测";
            case "ALERT_RULE_CREATE_DRAFT" -> fallback + "：预警";
            default -> fallback + "：" + intentLabel(action.type());
        };
    }

    private String describeSqlResult(Map<String, Object> result) {
        Map<String, Object> mapping = mapValue(result.get("fieldMapping"));
        String dimension = firstText(mapping.get("dimension"), mapping.get("dimensionKey"), "维度");
        String metric = firstText(mapping.get("metric"), mapping.get("metricKey"), "指标");
        String chartType = humanChartType(firstText(result.get("chartType")));
        String engine = firstText(result.get("engine"));
        String engineText = engine.isBlank() ? "" : "，引擎 " + engine;
        return "已按 " + dimension + " 聚合 " + metric + "，准备渲染" + chartType + engineText;
    }

    private String describeQueryResult(Map<String, Object> result) {
        int rows = castRows(result.get("data")).size();
        String riskLevel = firstText(result.get("riskLevel"));
        String riskText = riskLevel.isBlank() ? "" : "，安全审计 " + riskLevel;
        return rows > 0 ? "返回 " + rows + " 行结果" + riskText : "查询完成但没有返回数据" + riskText;
    }

    private String describeBusinessModelResult(Map<String, Object> agent) {
        String intent = firstText(agent.get("intent"));
        int bindingCount = castRows(agent.get("fieldBindingResults")).size();
        String bindingText = bindingCount > 0 ? "，完成 " + bindingCount + " 项字段/口径处理" : "";
        return trimTo(firstText(agent.get("message"), "业务模型处理完成"), 100)
                + (intent.isBlank() ? "" : "（" + intentLabel(intent) + "）") + bindingText;
    }

    private Map<String, Object> compactPinSource(Map<String, Object> source) {
        Map<String, Object> compact = new LinkedHashMap<>();
        putIfPresent(compact, "artifactId", source.get("artifactId"));
        putIfPresent(compact, "turnId", source.get("turnId"));
        putIfPresent(compact, "chartId", firstPresent(source.get("historyId"), source.get("chartId")));
        putIfPresent(compact, "title", source.get("title"));
        putIfPresent(compact, "tableName", source.get("tableName"));
        return compact;
    }

    private Map<String, Object> compactAlertEventRow(Map<String, Object> event) {
        Map<String, Object> row = new LinkedHashMap<>();
        putIfMeaningful(row, "id", event.get("id"));
        putIfMeaningful(row, "ruleId", event.get("ruleId"));
        putIfMeaningful(row, "ruleName", firstText(event.get("ruleName"), event.get("name"), event.get("title")));
        putIfMeaningful(row, "status", firstText(event.get("status"), "OPEN"));
        putIfMeaningful(row, "tableName", event.get("tableName"));
        putIfMeaningful(row, "metricField", event.get("metricField"));
        putIfMeaningful(row, "timeField", event.get("timeField"));
        putIfMeaningful(row, "bucketName", event.get("bucketName"));
        putIfMeaningful(row, "actualValue", event.get("actualValue"));
        putIfMeaningful(row, "threshold", event.get("threshold"));
        putIfMeaningful(row, "operator", event.get("operator"));
        putIfMeaningful(row, "reason", event.get("reason"));
        putIfMeaningful(row, "chartSnapshot", event.get("chartSnapshot"));
        putIfMeaningful(row, "chartSnapshotJson", event.get("chartSnapshotJson"));
        putIfMeaningful(row, "createdAt", event.get("createdAt"));
        putIfMeaningful(row, "ackAt", event.get("ackAt"));
        putIfMeaningful(row, "closedAt", event.get("closedAt"));
        return row;
    }

    private Map<String, Object> compactAlertRuleRow(Map<String, Object> rule) {
        Map<String, Object> row = new LinkedHashMap<>();
        putIfMeaningful(row, "id", rule.get("id"));
        putIfMeaningful(row, "ruleName", firstText(rule.get("ruleName"), rule.get("name"), rule.get("title")));
        putIfMeaningful(row, "status", rule.get("status"));
        putIfMeaningful(row, "tableName", rule.get("tableName"));
        putIfMeaningful(row, "metricField", rule.get("metricField"));
        putIfMeaningful(row, "timeField", rule.get("timeField"));
        putIfMeaningful(row, "operator", rule.get("operator"));
        putIfMeaningful(row, "threshold", firstPresent(rule.get("threshold"), rule.get("thresholdValue")));
        putIfMeaningful(row, "detectionCycle", rule.get("detectionCycle"));
        putIfMeaningful(row, "channels", rule.get("channels"));
        return row;
    }

    private Map<String, Object> alertEventExplanation(Map<String, Object> event) {
        Map<String, Object> stored = mapValue(event.get("llmExplanation"));
        if (!stored.isEmpty()) {
            return stored;
        }
        String operator = operatorLabel(firstText(event.get("operator")));
        String metric = firstText(event.get("metricField"), "指标");
        String actual = firstText(event.get("actualValue"), "当前值");
        String threshold = firstText(event.get("threshold"), "阈值");
        String bucket = firstText(event.get("bucketName"), "当前检测周期");
        String reason = firstText(event.get("reason"));
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("source", "rule");
        explanation.put("sourceLabel", "规则解释");
        explanation.put("title", "预警触发原因");
        explanation.put("summary", reason.isBlank()
                ? bucket + " 的 " + metric + " 为 " + actual + "，已满足" + operator + " " + threshold + " 的触发条件。"
                : reason);
        explanation.put("evidence", List.of(
                "指标：" + metric,
                "实际值：" + actual,
                "阈值：" + threshold,
                "比较关系：" + operator,
                "检测周期：" + bucket
        ));
        return explanation;
    }

    private Map<String, Object> alertActionDraft(String responseType, String message, Map<String, Object> draft) {
        Map<String, Object> result = draftCard(responseType, message, draft);
        result.put("chartType", "alert");
        result.put("requiresConfirmation", true);
        result.put("sideEffectMode", "DRAFT_ONLY");
        result.put("actionPreview", draft);
        return result;
    }

    private Map<String, Object> alertRuleActionDraft(String question, String tableName, SmartIntent intent, Long ruleId) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if ("ALERT_RULE_UPDATE".equals(intent.primaryIntent())) {
            Object threshold = firstPresent(intent.slots().get("threshold"), inferThreshold(question));
            if (threshold != null) {
                patch.put("threshold", threshold);
            }
            if (containsAny(question, "高于", "超过", "大于", "突破", "低于", "小于", "跌破", "异常", "波动", "zscore", "Z-Score")) {
                patch.put("operator", inferOperator(question));
            }
            String channel = inferChannel(question);
            if (!"both".equals(channel) || containsAny(question, "通知", "渠道", "邮件", "邮箱", "钉钉", "email", "dingtalk")) {
                patch.put("channels", "both".equals(channel) ? List.of("email", "dingtalk") : List.of(channel));
            }
            if (containsAny(question, "每天", "每日", "日度")) {
                patch.put("detectionCycle", "daily");
            } else if (containsAny(question, "每周", "周度")) {
                patch.put("detectionCycle", "weekly");
            } else if (containsAny(question, "每月", "月度")) {
                patch.put("detectionCycle", "monthly");
            } else if (containsAny(question, "每小时", "小时")) {
                patch.put("detectionCycle", "hourly");
            }
        } else if ("ALERT_RULE_DISABLE".equals(intent.primaryIntent())) {
            patch.put("status", "DISABLED");
        } else if ("ALERT_RULE_ENABLE".equals(intent.primaryIntent())) {
            patch.put("status", "ACTIVE");
        } else if ("ALERT_RULE_DELETE".equals(intent.primaryIntent())) {
            patch.put("status", "DELETED");
        }
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("ruleId", ruleId);
        draft.put("action", intent.primaryIntent());
        draft.put("patch", patch);
        draft.put("sourceQuestion", question);
        draft.put("sideEffect", "UPDATE_ALERT_RULE");
        return draft;
    }

    private String alertRuleDraftMessage(String intent, Long ruleId) {
        return switch (text(intent)) {
            case "ALERT_RULE_DELETE" -> "已生成删除预警规则草稿，确认后会删除规则 #" + ruleId + "。";
            case "ALERT_RULE_DISABLE" -> "已生成停用预警规则草稿，确认后会停用规则 #" + ruleId + "。";
            case "ALERT_RULE_ENABLE" -> "已生成启用预警规则草稿，确认后会启用规则 #" + ruleId + "。";
            default -> "已生成修改预警规则草稿，确认后会更新规则 #" + ruleId + "。";
        };
    }

    private String alertRuleExecuteMessage(String intent, Long ruleId) {
        return switch (text(intent)) {
            case "ALERT_RULE_DELETE" -> "正在删除预警规则 #" + ruleId;
            case "ALERT_RULE_DISABLE" -> "正在停用预警规则 #" + ruleId;
            case "ALERT_RULE_ENABLE" -> "正在启用预警规则 #" + ruleId;
            default -> "正在更新预警规则 #" + ruleId;
        };
    }

    private String alertRuleDoneMessage(String intent) {
        return switch (text(intent)) {
            case "ALERT_RULE_DELETE" -> "预警规则已删除。";
            case "ALERT_RULE_DISABLE" -> "预警规则已停用。";
            case "ALERT_RULE_ENABLE" -> "预警规则已启用。";
            default -> "预警规则已更新。";
        };
    }

    private Long alertEventId(String question, Map<String, Object> slots) {
        Long fromSlots = toLong(firstPresent(
                slots.get("alertEventId"),
                slots.get("eventId"),
                slots.get("currentAlertEventId"),
                slots.get("lastAlertEventId")
        ));
        if (fromSlots != null && fromSlots > 0) {
            return fromSlots;
        }
        return extractLabeledId(question, List.of("预警事件", "报警事件", "告警事件", "事件", "报警", "告警", "预警"));
    }

    private Long alertRuleId(String question, Map<String, Object> slots) {
        Long fromSlots = toLong(firstPresent(
                slots.get("alertRuleId"),
                slots.get("ruleId"),
                slots.get("currentAlertRuleId"),
                slots.get("lastAlertRuleId")
        ));
        if (fromSlots != null && fromSlots > 0) {
            return fromSlots;
        }
        return extractLabeledId(question, List.of("预警规则", "报警规则", "告警规则", "规则"));
    }

    private Long extractLabeledId(String question, List<String> labels) {
        String compact = text(question).replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return null;
        }
        List<String> escaped = labels.stream()
                .map(java.util.regex.Pattern::quote)
                .toList();
        String labelPattern = String.join("|", escaped);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:" + labelPattern + "|#|ID|id|编号)[#：:=]?(\\d+)")
                .matcher(compact);
        if (matcher.find()) {
            return toLong(matcher.group(1));
        }
        java.util.regex.Matcher leadingMatcher = java.util.regex.Pattern
                .compile("(\\d+)(?:" + labelPattern + ")")
                .matcher(compact);
        if (leadingMatcher.find()) {
            return toLong(leadingMatcher.group(1));
        }
        return null;
    }

    private boolean userConfirmed(SmartIntent intent) {
        Map<String, Object> slots = intent == null ? Map.of() : intent.slots();
        return readBoolean(firstPresent(slots.get("userConfirmed"), slots.get("confirmed"), slots.get("confirmationAccepted")));
    }

    private String alertHandleNote(String question) {
        String note = text(question);
        return note.length() > 120 ? note.substring(0, 120) : note;
    }

    private String operatorLabel(String operator) {
        return switch (text(operator)) {
            case "lt", "<", "below" -> "低于";
            case "lte", "<=" -> "不高于";
            case "gt", ">", "above" -> "高于";
            case "gte", ">=" -> "不低于";
            case "eq", "=" -> "等于";
            case "zscore" -> "出现异常波动";
            default -> firstText(operator, "满足条件");
        };
    }

    private String granularityLabel(String granularity) {
        return switch (text(granularity).toLowerCase(Locale.ROOT)) {
            case "day", "daily" -> "日度";
            case "week", "weekly" -> "周度";
            case "quarter", "quarterly" -> "季度";
            case "year", "yearly" -> "年度";
            default -> "月度";
        };
    }

    private String humanChartType(String chartType) {
        return switch (text(chartType).toLowerCase(Locale.ROOT)) {
            case "bar" -> "柱状图";
            case "line" -> "折线图";
            case "pie" -> "饼图";
            case "doughnut", "donut" -> "环形图";
            case "table" -> "表格";
            case "radar" -> "雷达图";
            case "scatter" -> "散点图";
            case "metric", "card", "kpi", "indicator" -> "指标卡";
            case "map" -> "地图";
            default -> "柱状图";
        };
    }

    private String intentLabel(String intent) {
        return switch (text(intent).toUpperCase(Locale.ROOT)) {
            case "QUERY_SQL" -> "数据查询";
            case "FORECAST" -> "时序预测";
            case "ALERT_RULE_CREATE", "ALERT_RULE_CREATE_DRAFT" -> "预警规则";
            case "ALERT_RULE_UPDATE" -> "修改预警规则";
            case "ALERT_RULE_DISABLE" -> "停用预警规则";
            case "ALERT_RULE_ENABLE" -> "启用预警规则";
            case "ALERT_RULE_DETECT" -> "检测预警规则";
            case "ALERT_RULE_DELETE" -> "删除预警规则";
            case "ALERT_EVENT_QUERY" -> "预警事件查询";
            case "ALERT_EVENT_EXPLAIN" -> "预警原因解释";
            case "ALERT_EVENT_ACK" -> "确认预警事件";
            case "ALERT_EVENT_CLOSE" -> "关闭预警事件";
            case "ALERT_EVENT_REOPEN" -> "重开预警事件";
            case "WHAT_IF" -> "情景推演";
            case "DASHBOARD_PIN" -> "钉入看板";
            case "DASHBOARD_CREATE" -> "创建看板";
            case "BUSINESS_MODEL_CREATE", "CREATE_MODEL" -> "创建业务模型";
            case "BUSINESS_MODEL_PATCH", "PATCH_MODEL", "BIND_FIELDS" -> "维护业务模型";
            case "BUSINESS_MODEL_APPLY", "APPLY_ENTERPRISE_MODEL" -> "套用业务模型";
            case "BUSINESS_MODEL_PUBLISH", "PUBLISH_MODEL", "UNPUBLISH_MODEL" -> "发布业务模型";
            case "MULTI_STEP" -> "复合任务";
            case "CLARIFY", "CLARIFICATION" -> "补充确认";
            default -> firstText(intent, "智能动作");
        };
    }

    private String trimTo(String value, int maxLength) {
        String text = text(value);
        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength).trim();
    }

    private Map<String, Object> explicitPinSource(ChatBiService.ChatQueryRequest request, String question, String tableName) {
        Map<String, Object> filters = request == null || request.getFilters() == null ? Map.of() : request.getFilters();
        Long artifactId = toLong(filters.get("pinArtifactId"));
        Long turnId = toLong(filters.get("pinTurnId"));
        Long chartId = toLong(filters.get("pinChartId"));
        if ((artifactId == null || artifactId <= 0) && (turnId == null || turnId <= 0) && (chartId == null || chartId <= 0)) {
            return Map.of();
        }
        Map<String, Object> source = new LinkedHashMap<>();
        if (artifactId != null && artifactId > 0) {
            source.put("artifactId", artifactId);
        }
        if (turnId != null && turnId > 0) {
            source.put("turnId", turnId);
        }
        if (chartId != null && chartId > 0) {
            source.put("historyId", chartId);
            source.put("chartId", chartId);
        }
        source.put("title", firstText(filters.get("pinTitle"), filters.get("pinSourceQuestion"), question));
        source.put("sourceQuestion", firstText(filters.get("pinSourceQuestion"), filters.get("pinTitle"), question));
        source.put("tableName", firstText(filters.get("pinTableName"), tableName));
        source.put("explicitSource", true);
        return source;
    }

    private Map<String, Object> sourceFromArtifact(Map<String, Object> artifact, String question, String tableName) {
        if (artifact == null || artifact.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("artifactId", artifact.get("id"));
        source.put("turnId", artifact.get("turnId"));
        source.put("historyId", artifact.get("historyId"));
        source.put("chartId", artifact.get("historyId"));
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
        return source;
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
                "metric", firstText(advanced.get("metricLabel"), advanced.get("metricField"), advanced.get("targetMetric")),
                "metricKey", firstText(advanced.get("metricField"), advanced.get("targetMetric")),
                "metricField", firstText(advanced.get("metricField"), advanced.get("targetMetric")),
                "metricExpression", firstText(advanced.get("metricExpression")),
                "dimension", firstText(advanced.get("timeField"), "时间"),
                "dimensionKey", firstText(advanced.get("timeField"), "name")
        ));
        if (advanced.containsKey("businessSemanticTrace")) {
            result.put("businessSemanticTrace", advanced.get("businessSemanticTrace"));
        }
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
        boolean effectiveRequiresConfirmation = result.containsKey("requiresConfirmation")
                ? readBoolean(result.get("requiresConfirmation"))
                : intent.requiresConfirmation();
        List<String> effectiveMissingSlots = intent.missingSlots().isEmpty()
                ? stringList(mapValue(result.get("draft")).get("missingSlots"))
                : intent.missingSlots();
        result.put("smartIntent", intent.primaryIntent());
        result.put("smartConfidence", intent.confidence());
        result.put("smartReasoning", intent.reasoning());
        result.put("smartFallbackUsed", intent.fallbackUsed());
        result.put("queryQuestion", question);
        result.put("queryTableName", tableName);
        result.put("thinkingLogs", List.of(
                "统一语义路由：识别意图 " + intent.primaryIntent(),
                "动作计划校验：" + (effectiveMissingSlots.isEmpty() ? "参数完整或已生成草稿" : "缺少 " + String.join(", ", effectiveMissingSlots)),
                "执行策略：" + (effectiveRequiresConfirmation ? "生成草稿/等待确认" : "直接调用现有业务服务")
        ));
        result.put("actionPlan", Map.of(
                "primaryIntent", intent.primaryIntent(),
                "confidence", intent.confidence(),
                "needClarification", !effectiveMissingSlots.isEmpty(),
                "requiresConfirmation", effectiveRequiresConfirmation,
                "missingSlots", effectiveMissingSlots,
                "reasoning", intent.reasoning(),
                "slots", intent.slots(),
                "actions", List.of(Map.of(
                        "id", "action_1",
                        "type", intent.primaryIntent(),
                        "dependsOn", List.of(),
                        "slots", intent.slots(),
                        "confidence", normalizeConfidenceStatic(intent.confidence(), 0.7D),
                        "requiresConfirmation", effectiveRequiresConfirmation
                ))
        ));
    }

    private void attachSmartRouteAudit(Map<String, Object> result,
                                       ChatBiService.ChatQueryRequest request,
                                       String question,
                                       String tableName,
                                       String chosenExecutor,
                                       long startedAt) {
        if (result == null) {
            return;
        }
        Map<String, Object> actionPlan = mapValue(result.get("actionPlan"));
        List<String> missingSlots = stringList(actionPlan.get("missingSlots"));
        String primaryIntent = firstText(actionPlan.get("primaryIntent"), result.get("smartIntent"), result.get("responseType"));
        String outcome = routeAuditOutcome(result);
        boolean success = routeAuditSuccess(outcome);
        Map<String, Object> filters = request == null || request.getFilters() == null ? Map.of() : request.getFilters();
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("question", question);
        audit.put("conversationId", request == null ? null : request.getConversationId());
        audit.put("parentTurnId", request == null ? null : request.getParentTurnId());
        audit.put("tableName", tableName);
        audit.put("primaryIntent", primaryIntent);
        audit.put("actions", compactRouteAuditActions(actionPlan, primaryIntent));
        audit.put("confidence", readDouble(firstPresent(actionPlan.get("confidence"), result.get("smartConfidence")), 0D));
        audit.put("fallbackUsed", readBoolean(firstPresent(result.get("smartFallbackUsed"), result.get("fallbackUsed"))));
        audit.put("missingSlots", missingSlots);
        audit.put("requiresConfirmation", readBoolean(firstPresent(actionPlan.get("requiresConfirmation"), result.get("requiresConfirmation"))));
        audit.put("chosenExecutor", firstText(chosenExecutor, chosenExecutor(primaryIntent)));
        audit.put("success", success);
        audit.put("failureReason", success ? "" : routeAuditFailureReason(result));
        audit.put("userConfirmed", readBoolean(firstPresent(filters.get("userConfirmed"), filters.get("confirmed"), filters.get("confirmationAccepted"))));
        audit.put("outcome", outcome);
        audit.put("responseType", firstText(result.get("responseType"), primaryIntent));
        audit.put("durationMs", Math.max(0L, System.currentTimeMillis() - startedAt));
        result.put("smartRouteAudit", audit);
        log.info("smartRouteAudit={}", audit);
    }

    private List<Map<String, Object>> compactRouteAuditActions(Map<String, Object> actionPlan, String primaryIntent) {
        List<Map<String, Object>> actions = castRows(actionPlan.get("actions"));
        if (actions.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("id", "action_1");
            fallback.put("type", firstText(primaryIntent, "QUERY_SQL"));
            fallback.put("dependsOn", List.of());
            return List.of(fallback);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> action : actions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", firstText(action.get("id")));
            item.put("type", firstText(action.get("type")));
            item.put("dependsOn", stringList(action.get("dependsOn")));
            item.put("confidence", readDouble(action.get("confidence"), 0D));
            item.put("requiresConfirmation", readBoolean(action.get("requiresConfirmation")));
            putIfPresent(item, "status", action.get("status"));
            putIfPresent(item, "message", action.get("message"));
            result.add(item);
        }
        return result;
    }

    private String routeAuditOutcome(Map<String, Object> result) {
        List<Map<String, Object>> steps = castRows(result.get("stepResults"));
        if (!steps.isEmpty()) {
            long completed = steps.stream().filter(step -> "COMPLETED".equals(text(step.get("status")))).count();
            boolean failed = steps.stream().anyMatch(step -> "FAILED".equals(text(step.get("status"))));
            boolean skipped = steps.stream().anyMatch(step -> "SKIPPED".equals(text(step.get("status"))));
            boolean needsInput = steps.stream().anyMatch(step -> "NEEDS_INPUT".equals(text(step.get("status"))));
            boolean needsConfirmation = steps.stream().anyMatch(step -> "NEEDS_CONFIRMATION".equals(text(step.get("status"))));
            if (failed) {
                return completed > 0 || skipped ? "PARTIAL_FAILED" : "FAILED";
            }
            if (needsInput) {
                return "NEEDS_INPUT";
            }
            if (needsConfirmation) {
                return "NEEDS_CONFIRMATION";
            }
            if (skipped) {
                return "PARTIAL";
            }
            return "COMPLETED";
        }
        String responseType = text(result.get("responseType")).toUpperCase(Locale.ROOT);
        if ("CLARIFICATION".equals(responseType) || "CLARIFY".equals(responseType)) {
            return "NEEDS_INPUT";
        }
        if ("0".equals(text(result.get("executionStatus"))) || "FAILED".equals(text(result.get("status")).toUpperCase(Locale.ROOT))) {
            return "FAILED";
        }
        if (readBoolean(result.get("requiresConfirmation"))) {
            return "NEEDS_CONFIRMATION";
        }
        return "COMPLETED";
    }

    private boolean routeAuditSuccess(String outcome) {
        String normalized = text(outcome);
        return !"FAILED".equals(normalized) && !"PARTIAL_FAILED".equals(normalized);
    }

    private String routeAuditFailureReason(Map<String, Object> result) {
        for (Map<String, Object> step : castRows(result.get("stepResults"))) {
            if ("FAILED".equals(text(step.get("status")))) {
                return firstText(step.get("error"), step.get("message"), "步骤执行失败");
            }
        }
        return firstText(result.get("failureReason"), result.get("error"), result.get("message"), "智能路由执行失败");
    }

    private String chosenExecutor(String intent) {
        return switch (text(intent).toUpperCase(Locale.ROOT)) {
            case "MULTI_STEP" -> "multi-step-orchestrator";
            case "QUERY_SQL" -> "chat-bi-sql";
            case "FORECAST", "ADVANCED_FORECAST" -> "advanced-analysis-forecast";
            case "ALERT_RULE_CREATE", "ALERT_RULE_CREATE_DRAFT", "ALERT_RULE_DRAFT" -> "alert-rule-draft";
            case "ALERT_RULE_UPDATE", "ALERT_RULE_DISABLE", "ALERT_RULE_ENABLE", "ALERT_RULE_DELETE" -> "alert-rule-governance";
            case "ALERT_RULE_DETECT" -> "alert-rule-detection";
            case "ALERT_EVENT_QUERY", "ALERT_EVENT_EXPLAIN" -> "advanced-alert-event";
            case "ALERT_EVENT_ACK", "ALERT_EVENT_CLOSE", "ALERT_EVENT_REOPEN" -> "advanced-alert-event-lifecycle";
            case "WHAT_IF", "WHAT_IF_DRAFT" -> "what-if-draft";
            case "BUSINESS_MODEL_CREATE", "BUSINESS_MODEL_PATCH", "BUSINESS_MODEL_APPLY", "BUSINESS_MODEL_PUBLISH" -> "business-model-agent";
            case "DASHBOARD_PIN", "DASHBOARD_CREATE" -> "dashboard-service";
            case "CLARIFY", "CLARIFICATION" -> "clarification";
            default -> "smart-chat-router";
        };
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

    private Map<String, Object> businessModelContextFromRequest(ChatBiService.ChatQueryRequest request) {
        if (request == null || request.getFilters() == null || request.getFilters().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("activeBusinessModelId", "lastCreatedBusinessModelId", "lastAppliedBusinessModelId")) {
            Object value = request.getFilters().get(key);
            if (value != null && !text(value).isBlank()) {
                context.put(key, value);
            }
        }
        return context.isEmpty() ? Map.of() : context;
    }

    private BusinessSemanticService.BusinessAnalysisResolution resolveBusinessAnalysis(String question,
                                                                                       String tableName,
                                                                                       Map<String, Object> slots,
                                                                                       List<Map<String, Object>> fields) {
        BusinessSemanticService.BusinessAnalysisResolution fromSlots = businessResolutionFromSlots(slots);
        if (fromSlots.matched()) {
            return fromSlots;
        }
        if (businessSemanticService == null || text(tableName).isBlank()) {
            return BusinessSemanticService.BusinessAnalysisResolution.empty(Map.of("enabled", false));
        }
        Map<String, Object> options = new LinkedHashMap<>(slots == null ? Map.of() : slots);
        try {
            return businessSemanticService.resolveAnalysis(question, tableName, options, fields);
        } catch (Exception ignored) {
            return BusinessSemanticService.BusinessAnalysisResolution.empty(Map.of("enabled", false));
        }
    }

    private BusinessSemanticService.BusinessAnalysisResolution businessResolutionFromSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty() || !(slots.get("businessSemanticTrace") instanceof Map<?, ?> rawTrace)) {
            return BusinessSemanticService.BusinessAnalysisResolution.empty(Map.of("enabled", false));
        }
        Map<String, Object> trace = toObjectMap(rawTrace);
        String metricColumn = firstText(trace.get("analysisMetricField"), trace.get("metricColumn"),
                trace.get("resolvedMetricField"), slots.get("metricField"));
        if (metricColumn.isBlank()) {
            return BusinessSemanticService.BusinessAnalysisResolution.empty(trace);
        }
        String metricLabel = firstText(slots.get("metricLabel"), trace.get("analysisMetricLabel"), trace.get("matchedMetric"), metricColumn);
        String metricExpression = firstText(slots.get("metricExpression"), trace.get("analysisMetricExpression"));
        String formula = firstText(slots.get("formula"), trace.get("analysisFormula"));
        boolean formulaApplied = readBoolean(trace.get("formulaApplied")) || !metricExpression.isBlank();
        return new BusinessSemanticService.BusinessAnalysisResolution(
                true,
                metricLabel,
                metricColumn,
                firstText(trace.get("resolvedMetricField"), metricColumn),
                metricExpression,
                formula,
                formulaApplied,
                trace
        );
    }

    private Map<String, Object> withBusinessTrace(Map<String, Object> metadata,
                                                  BusinessSemanticService.BusinessAnalysisResolution resolution) {
        if (resolution == null || !resolution.matched()) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.put("businessSemanticTrace", resolution.trace());
        return merged;
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
            case "QUERY", "SQL", "TEXT_TO_SQL", "QUERY_SQL" -> "QUERY_SQL";
            case "FORECAST", "PREDICTION", "TIME_SERIES_FORECAST" -> "FORECAST";
            case "ALERT", "WARNING", "ALERT_CREATE" -> "ALERT_RULE_CREATE";
            case "ALERT_EVENT_QUERY", "ALERT_EVENT_SEARCH", "ALERT_QUERY", "ALARM_QUERY" -> "ALERT_EVENT_QUERY";
            case "ALERT_EVENT_EXPLAIN", "ALERT_EXPLAIN", "ALARM_EXPLAIN" -> "ALERT_EVENT_EXPLAIN";
            case "ALERT_EVENT_ACK", "ALERT_ACK", "ALARM_ACK", "ALERT_EVENT_CONFIRM" -> "ALERT_EVENT_ACK";
            case "ALERT_EVENT_CLOSE", "ALERT_CLOSE", "ALARM_CLOSE", "ALERT_EVENT_RESOLVE" -> "ALERT_EVENT_CLOSE";
            case "ALERT_EVENT_REOPEN", "ALERT_REOPEN", "ALARM_REOPEN", "ALERT_EVENT_OPEN" -> "ALERT_EVENT_REOPEN";
            case "ALERT_RULE_UPDATE", "ALERT_RULE_EDIT" -> "ALERT_RULE_UPDATE";
            case "ALERT_RULE_DISABLE", "ALERT_RULE_STOP" -> "ALERT_RULE_DISABLE";
            case "ALERT_RULE_ENABLE", "ALERT_RULE_REOPEN", "ALERT_RULE_START" -> "ALERT_RULE_ENABLE";
            case "ALERT_RULE_DETECT", "ALERT_RULE_RUN", "ALERT_RULE_CHECK", "ALERT_RULE_TEST" -> "ALERT_RULE_DETECT";
            case "ALERT_RULE_DELETE", "ALERT_RULE_REMOVE" -> "ALERT_RULE_DELETE";
            case "WHATIF", "WHAT_IF", "SCENARIO", "SIMULATION" -> "WHAT_IF";
            case "BUSINESS_MODEL_CREATE", "BUSINESS_MODEL_PATCH", "BUSINESS_MODEL_APPLY",
                    "BUSINESS_MODEL_PUBLISH", "DASHBOARD_PIN", "DASHBOARD_CREATE",
                    "CHART_RULE_UPDATE", "FIELD_SEMANTIC_FIX", "FEDERATED_QUERY",
                    "PERMISSION_POLICY_CREATE", "AUDIT_QUERY", "REPORT_GENERATE",
                    "TASK_STATUS_QUERY", "COLLABORATION_INVITE" -> intent;
            default -> "CLARIFY";
        };
    }

    private String normalizeActionType(String value) {
        String action = text(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (action) {
            case "QUERY", "SQL", "TEXT_TO_SQL", "QUERY_SQL" -> "QUERY_SQL";
            case "FORECAST", "PREDICTION", "TIME_SERIES_FORECAST" -> "FORECAST";
            case "ALERT", "WARNING", "ALERT_RULE_CREATE", "ALERT_CREATE", "ALERT_RULE_DRAFT",
                    "ALERT_RULE_CREATE_DRAFT" -> "ALERT_RULE_CREATE_DRAFT";
            default -> "";
        };
    }

    private Map<String, Object> inferPlanSlots(String question, String tableName) {
        return inferPlanSlots(question, tableName, null);
    }

    private Map<String, Object> inferPlanSlots(String question, String tableName, ChatBiService.ChatQueryRequest request) {
        List<Map<String, Object>> fields = safeFields(tableName);
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.putAll(businessModelContextFromRequest(request));
        String forecastQuestion = forecastQuestionForPlan(question);
        String alertQuestion = alertQuestionForPlan(question);
        String timeField = chooseTimeField(fields, question, "");
        BusinessSemanticService.BusinessAnalysisResolution businessResolution =
                resolveBusinessAnalysis(question, tableName, slots, fields);
        String metricField = firstText(businessResolution.metricColumn(), chooseMetricField(fields, question));
        if (!timeField.isBlank()) {
            slots.put("timeField", timeField);
        }
        if (!metricField.isBlank()) {
            slots.put("metricField", metricField);
            slots.put("metric", metricField);
        }
        if (businessResolution.matched()) {
            slots.put("metricLabel", firstText(businessResolution.metricLabel(), metricField));
            slots.put("businessSemanticTrace", businessResolution.trace());
            if (businessResolution.formulaApplied()) {
                slots.put("metricExpression", businessResolution.metricExpression());
                slots.put("formula", businessResolution.formula());
            }
        }
        Object threshold = inferThreshold(alertQuestion);
        if (threshold != null) {
            slots.put("threshold", threshold);
        }
        slots.put("operator", inferOperator(alertQuestion));
        slots.put("channel", inferChannel(alertQuestion));
        String granularity = normalizeForecastGranularity(inferGranularity(question));
        slots.put("granularity", granularity);
        slots.put("horizon", normalizeHorizon(null, forecastQuestion, granularity));
        return slots;
    }

    private boolean hasQueryTask(String question) {
        String q = text(question);
        return isExplicitQueryIntent(q)
                || isHistoricalTrendQuery(q)
                || containsAny(q, "查", "看", "查看", "查询", "统计", "分析", "给我看", "画", "展示")
                || containsAny(q, "趋势", "走势", "排名", "排行", "对比", "分布", "明细", "各省", "各市", "各区域");
    }

    private boolean hasForecastTask(String question) {
        String q = text(question);
        String lower = q.toLowerCase(Locale.ROOT);
        return hasForecastIntent(q, lower);
    }

    private boolean hasAlertTask(String question) {
        String q = text(question);
        String lower = q.toLowerCase(Locale.ROOT);
        if (isStatisticalOutlierQuery(q)) {
            return false;
        }
        return containsAny(q, "预警", "告警", "警报", "提醒", "通知", "低于", "高于", "超过", "跌破", "阈值", "阀值", "异常")
                || lower.contains("alert") || lower.contains("warning");
    }

    private boolean isPotentialMultiStepQuestion(String question,
                                                 boolean queryTask,
                                                 boolean forecastTask,
                                                 boolean alertTask,
                                                 boolean dashboardPinTask) {
        String q = text(question);
        if (!forecastTask && !(dashboardPinTask && queryTask)) {
            return false;
        }
        if (dashboardPinTask && queryTask) {
            return true;
        }
        if (queryTask && containsAny(q, "并", "然后", "再", "同时", "顺便", "接着", "之后", "并且")) {
            return true;
        }
        if (queryTask && alertTask) {
            return true;
        }
        if (queryTask && containsAny(q, "查", "看", "查询", "统计", "分析", "展示")
                && containsAny(q, "预测", "预估", "推算", "未来", "下个月", "后面", "往后")) {
            return true;
        }
        return alertTask && containsAny(q, "如果", "低于", "高于", "超过", "跌破", "提醒", "预警", "通知");
    }

    private boolean hasAnalyticContentBeforeDashboardAction(String question) {
        String q = text(question);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(钉|保存到|保存至|存到|放到|放入|加入|添加到|挂到|挂入).*(看板|仪表盘|大屏|驾驶舱)")
                .matcher(q);
        String before = matcher.find() ? q.substring(0, matcher.start()) : q;
        if (before.contains("当前图") || before.contains("刚才") || before.contains("上一轮")
                || before.contains("这个图") || before.contains("这张图")) {
            before = before
                    .replace("当前图表", "")
                    .replace("当前图", "")
                    .replace("刚才这个图表", "")
                    .replace("刚才这个图", "")
                    .replace("上一轮图表", "")
                    .replace("上一轮这个图", "")
                    .replace("这个图表", "")
                    .replace("这个图", "")
                    .replace("这张图表", "")
                    .replace("这张图", "");
        }
        return containsAny(before, "销售额", "收入", "利润", "订单", "销量", "金额", "指标", "数据",
                "走势", "趋势", "排名", "排行", "对比", "分布", "占比", "统计", "分析", "查询", "查看", "预测", "预估", "推算");
    }

    private SmartActionPlan withDeferredDashboardPin(SmartActionPlan plan, String question) {
        if (plan == null || plan.actions().isEmpty()) {
            return plan;
        }
        Map<String, Object> slots = new LinkedHashMap<>(plan.slots());
        slots.put("deferredDashboardPin", true);
        slots.put("dashboardPinQuestion", dashboardPinQuestionForPlan(question));
        return new SmartActionPlan(
                plan.primaryIntent(),
                plan.actions(),
                slots,
                plan.confidence(),
                plan.requiresConfirmation(),
                plan.reasoning(),
                plan.fallbackUsed(),
                plan.needClarification(),
                plan.missingSlots()
        );
    }

    private String queryQuestionForPlan(String question) {
        String q = text(question);
        int splitAt = q.length();
        java.util.regex.Matcher forecastMatcher = java.util.regex.Pattern
                .compile("(预测|预估|推算|趋势延伸|往后推|未来|后面|往后|下个月|下季度|下一季度|后续)")
                .matcher(q);
        if (forecastMatcher.find()) {
            splitAt = Math.min(splitAt, forecastMatcher.start());
        }
        java.util.regex.Matcher alertMatcher = java.util.regex.Pattern
                .compile("(如果|若|假设|预警|告警|警报|提醒|通知)")
                .matcher(q);
        if (alertMatcher.find()) {
            splitAt = Math.min(splitAt, alertMatcher.start());
        }
        String query = q.substring(0, splitAt)
                .replaceAll("(并|然后|同时|顺便|接着|之后|并且)?[，,；;。\\s]+$", "")
                .trim();
        if (query.length() >= 4) {
            return query;
        }
        return q;
    }

    private String forecastQuestionForPlan(String question) {
        String q = text(question);
        if (q.isBlank()) {
            return q;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(预测|预估|推算|未来|后面|往后|下个月|下季度|下一季度|后续|大概会|继续涨|继续跌|趋势延伸|往后推).*$")
                .matcher(q);
        String forecast = matcher.find() ? q.substring(matcher.start()) : q;
        forecast = forecast
                .replaceAll("(，|,|；|;|。)?\\s*(如果|若|假设|低于|高于|超过|跌破|预警|告警|提醒|通知).*$", "")
                .replaceAll("[，,；;。\\s]+$", "")
                .trim();
        return forecast.length() >= 3 ? forecast : q;
    }

    private String alertQuestionForPlan(String question) {
        String q = text(question);
        if (q.isBlank()) {
            return q;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(如果|若|假设|低于|高于|超过|跌破|预警|告警|提醒|通知|阈值|阀值|异常).*$")
                .matcher(q);
        String alert = matcher.find() ? q.substring(matcher.start()) : q;
        alert = alert.replaceAll("^(并|然后|再|同时|顺便|接着|之后|并且)[，,\\s]*", "").trim();
        return alert.length() >= 3 ? alert : q;
    }

    private String dashboardPinQuestionForPlan(String question) {
        String q = text(question);
        if (q.isBlank()) {
            return q;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(把|将|帮我把|再帮我把|并把|然后把|顺便把)?.*(图表|图|卡片|结果).*(钉|保存到|保存至|存到|放到|放入|加入|添加到|挂到|挂入).*(看板|仪表盘|大屏|驾驶舱).*")
                .matcher(q);
        if (matcher.find()) {
            String pin = q.substring(matcher.start())
                    .replaceAll("^(并|然后|再|同时|顺便|接着|之后|并且)[，,\\s]*", "")
                    .trim();
            return pin.length() >= 3 ? pin : q;
        }
        return q;
    }

    private String stepQuestionForPlan(String type, String originalQuestion, String stepQuestion) {
        String source = firstText(stepQuestion, originalQuestion);
        return switch (type) {
            case "QUERY_SQL" -> queryQuestionForPlan(source);
            case "FORECAST" -> forecastQuestionForPlan(source);
            case "ALERT_RULE_CREATE_DRAFT" -> alertQuestionForPlan(source);
            default -> source;
        };
    }

    private Map<String, Object> mergeCanonicalSlots(Map<String, Object> lowPriority, Map<String, Object> highPriority) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (lowPriority != null) {
            lowPriority.forEach((key, value) -> {
                String normalizedKey = text(key);
                if (!normalizedKey.isBlank() && value != null) {
                    merged.put(normalizedKey, value);
                }
            });
        }
        if (highPriority != null) {
            highPriority.forEach((key, value) -> {
                String normalizedKey = text(key);
                if (!normalizedKey.isBlank() && value != null && !text(value).isBlank()) {
                    merged.put(normalizedKey, value);
                }
            });
        }
        return merged.isEmpty() ? Map.of() : merged;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = text(item);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = text(value);
        if (text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : text.split("[,，、;；\\s]+")) {
            String normalized = text(item);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
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
        return fields.stream()
                .filter(this::isNumericField)
                .sorted((left, right) -> Integer.compare(metricFieldScore(right, question), metricFieldScore(left, question)))
                .map(field -> text(field.get("columnName")))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
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

    private int metricFieldScore(Map<String, Object> field, String question) {
        String q = text(question).toLowerCase(Locale.ROOT);
        String label = fieldAliasTokens(field).stream()
                .reduce("", (left, right) -> left + " " + right)
                .toLowerCase(Locale.ROOT);
        int score = 20;
        for (String token : fieldAliasTokens(field)) {
            String normalized = text(token).toLowerCase(Locale.ROOT);
            if (!normalized.isBlank() && q.contains(normalized)) {
                score += Math.min(60, normalized.length() * 8);
            }
        }
        if (containsAny(label, "销售额", "收入", "营收", "金额", "销售金额", "含税收入", "sales", "amount", "amt", "revenue")) {
            score += containsAny(q, "销售额", "收入", "营收", "金额") ? 45 : 18;
        }
        if (containsAny(label, "利润", "毛利", "profit", "margin")) {
            score += containsAny(q, "利润", "毛利") ? 45 : 12;
        }
        if (containsAny(label, "销量", "数量", "订单量", "qty", "quantity", "count")) {
            score += containsAny(q, "销量", "数量", "订单量") ? 45 : 8;
        }
        if (isIdentifierLikeField(field)) {
            score -= 120;
        }
        return score;
    }

    private boolean isIdentifierLikeField(Map<String, Object> field) {
        String label = fieldAliasTokens(field).stream()
                .reduce("", (left, right) -> left + " " + right)
                .toLowerCase(Locale.ROOT);
        return containsAny(label, "id", "_id", "编号", "单号", "序号", "行号", "主键", "流水号", "订单号", "客户号", "rows_id", "row_id");
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
        if (containsAny(q, "每日", "每天", "按日", "日度", "逐日")) return "day";
        if (containsAny(q, "每周", "按周", "周度", "逐周")) return "week";
        if (containsAny(q, "每月", "按月", "月度", "逐月")) return "month";
        if (containsAny(q, "每季度", "按季度", "季度粒度", "逐季")) return "quarter";
        if (containsAny(q, "每年", "按年", "年度", "逐年")) return "year";
        ForecastRange range = explicitForecastRange(q);
        if (range != null && List.of("day", "week", "quarter", "year").contains(range.unit())) {
            return range.unit();
        }
        return "month";
    }

    private int normalizeHorizon(Object parsed, String question) {
        return normalizeHorizon(parsed, question, inferGranularity(question));
    }

    private int normalizeHorizon(Object parsed, String question, String granularity) {
        String value = text(parsed);
        String q = text(question);
        String normalizedGranularity = normalizeForecastGranularity(granularity);
        Integer parsedCount = parsePositiveInteger(value);
        if (parsedCount != null) {
            return clampForecastHorizon(parsedCount);
        }
        ForecastRange questionRange = explicitForecastRange(q);
        if (questionRange != null) {
            return clampForecastHorizon(horizonByGranularity(questionRange, normalizedGranularity));
        }
        ForecastRange parsedRange = explicitForecastRange(value);
        if (parsedRange != null) {
            return clampForecastHorizon(horizonByGranularity(parsedRange, normalizedGranularity));
        }
        if (containsAny(q, "下个月", "未来一个月", "后面一个月", "往后一个月")) {
            return clampForecastHorizon(horizonByGranularity(new ForecastRange(1, "month"), normalizedGranularity));
        }
        if (containsAny(q, "下季度", "下一季度", "后面一个季度", "未来一个季度")) {
            return clampForecastHorizon(horizonByGranularity(new ForecastRange(1, "quarter"), normalizedGranularity));
        }
        if (containsAny(q, "半年", "六个月")) {
            return clampForecastHorizon(horizonByGranularity(new ForecastRange(6, "month"), normalizedGranularity));
        }
        if (containsAny(q, "一年", "12个月")) {
            return clampForecastHorizon(horizonByGranularity(new ForecastRange(1, "year"), normalizedGranularity));
        }
        return clampForecastHorizon(defaultForecastHorizon(normalizedGranularity));
    }

    private ForecastRange explicitForecastRange(String text) {
        String source = text(text);
        if (source.isBlank()) {
            return null;
        }
        if (containsAny(source, "下个月", "未来一个月", "后面一个月", "往后一个月")) {
            return new ForecastRange(1, "month");
        }
        if (containsAny(source, "下季度", "下一季度", "未来一个季度", "后面一个季度")) {
            return new ForecastRange(1, "quarter");
        }
        if (containsAny(source, "半年", "六个月")) {
            return new ForecastRange(6, "month");
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:(未来|后面|往后|之后|接下来|下|后续)\\s*)?([一二两三四五六七八九十百\\d]+)\\s*(个)?\\s*(月|个月|季度|季|年|天|日|周)")
                .matcher(source);
        while (matcher.find()) {
            int value = chineseOrArabicNumber(matcher.group(2));
            if (value <= 0) {
                continue;
            }
            return new ForecastRange(value, normalizeForecastRangeUnit(matcher.group(4)));
        }
        return null;
    }

    private String normalizeForecastRangeUnit(String unit) {
        String value = text(unit);
        if (value.contains("年")) return "year";
        if (value.contains("季")) return "quarter";
        if (value.contains("周")) return "week";
        if (value.contains("天") || value.contains("日")) return "day";
        return "month";
    }

    private String normalizeForecastGranularity(String granularity) {
        String value = text(granularity).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "day", "daily" -> "day";
            case "week", "weekly" -> "week";
            case "quarter", "quarterly" -> "quarter";
            case "year", "yearly" -> "year";
            default -> "month";
        };
    }

    private int horizonByGranularity(ForecastRange range, String granularity) {
        String target = normalizeForecastGranularity(granularity);
        if (range.unit().equals(target)) {
            return range.value();
        }
        if ("month".equals(target)) {
            return switch (range.unit()) {
                case "quarter" -> range.value() * 3;
                case "year" -> range.value() * 12;
                case "week" -> Math.max(1, (int) Math.ceil(range.value() * 7D / 30D));
                case "day" -> Math.max(1, (int) Math.ceil(range.value() / 30D));
                default -> range.value();
            };
        }
        if ("quarter".equals(target)) {
            return switch (range.unit()) {
                case "month" -> Math.max(1, (int) Math.ceil(range.value() / 3D));
                case "year" -> range.value() * 4;
                case "week" -> Math.max(1, (int) Math.ceil(range.value() * 7D / 91D));
                case "day" -> Math.max(1, (int) Math.ceil(range.value() / 91D));
                default -> range.value();
            };
        }
        if ("year".equals(target)) {
            return switch (range.unit()) {
                case "month" -> Math.max(1, (int) Math.ceil(range.value() / 12D));
                case "quarter" -> Math.max(1, (int) Math.ceil(range.value() / 4D));
                case "week" -> Math.max(1, (int) Math.ceil(range.value() * 7D / 365D));
                case "day" -> Math.max(1, (int) Math.ceil(range.value() / 365D));
                default -> range.value();
            };
        }
        int days = rangeToDays(range);
        return switch (target) {
            case "day" -> Math.max(1, days);
            case "week" -> Math.max(1, (int) Math.ceil(days / 7D));
            default -> range.value();
        };
    }

    private int rangeToDays(ForecastRange range) {
        return switch (range.unit()) {
            case "day" -> range.value();
            case "week" -> range.value() * 7;
            case "quarter" -> range.value() * 91;
            case "year" -> range.value() * 365;
            default -> range.value() * 30;
        };
    }

    private int defaultForecastHorizon(String granularity) {
        return switch (normalizeForecastGranularity(granularity)) {
            case "day" -> 30;
            case "week" -> 12;
            case "quarter" -> 4;
            case "year" -> 3;
            default -> 3;
        };
    }

    private int clampForecastHorizon(int value) {
        return Math.max(1, Math.min(value, 60));
    }

    private Integer parsePositiveInteger(String value) {
        String text = text(value);
        if (text.isBlank() || !text.matches("\\d+")) {
            return null;
        }
        int parsed = Integer.parseInt(text);
        return parsed > 0 ? parsed : null;
    }

    private int chineseOrArabicNumber(String value) {
        String text = text(value);
        if (text.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        return switch (text) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> {
                if (text.startsWith("十")) {
                    yield 10 + chineseOrArabicNumber(text.substring(1));
                }
                if (text.endsWith("十")) {
                    yield chineseOrArabicNumber(text.substring(0, text.length() - 1)) * 10;
                }
                if (text.contains("十")) {
                    String[] parts = text.split("十", 2);
                    yield chineseOrArabicNumber(parts[0]) * 10 + chineseOrArabicNumber(parts[1]);
                }
                yield 0;
            }
        };
    }

    private String inferOperator(String question) {
        String q = text(question);
        if (containsAny(q, "高于", "超过", "大于", "突破")) return "gt";
        if (containsAny(q, "异常", "波动", "zscore", "Z-Score")) return "zscore";
        return "lt";
    }

    private Object inferThreshold(String question) {
        String normalized = text(question).replace(",", "");
        java.util.regex.Matcher contextualMatcher = java.util.regex.Pattern
                .compile("(?:阈值|阀值|门槛|报警线|预警线|改成|改为|调整为|设置为|设为|变成|降到|升到|提高到|低于|高于|超过|跌破)[^\\d]{0,12}(\\d+(?:\\.\\d+)?)(\\s*)(亿|万|千|k|K)?")
                .matcher(normalized);
        if (contextualMatcher.find()) {
            return parseAmount(contextualMatcher.group(1), contextualMatcher.group(3));
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)(\\s*)(亿|万|千|k|K)?").matcher(normalized);
        while (matcher.find()) {
            String prefix = normalized.substring(0, matcher.start());
            if (prefix.endsWith("预警规则") || prefix.endsWith("报警规则") || prefix.endsWith("告警规则")
                    || prefix.endsWith("规则") || prefix.endsWith("预警事件") || prefix.endsWith("报警事件")
                    || prefix.endsWith("告警事件") || prefix.endsWith("事件") || prefix.endsWith("报警")
                    || prefix.endsWith("告警") || prefix.endsWith("预警") || prefix.endsWith("#")
                    || prefix.endsWith("ID") || prefix.endsWith("id") || prefix.endsWith("编号")) {
                continue;
            }
            return parseAmount(matcher.group(1), matcher.group(3));
        }
        return null;
    }

    private double parseAmount(String number, String unit) {
        double value = Double.parseDouble(number);
        if ("亿".equals(unit)) value *= 100000000D;
        else if ("万".equals(unit)) value *= 10000D;
        else if ("千".equals(unit) || "k".equals(unit) || "K".equals(unit)) value *= 1000D;
        return value;
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
                "企业模型库", "套用", "发布模型", "含税", "不含税", "统一用", "统一按", "理解为", "视为", "归到", "归为",
                "算作", "当作")
                || q.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}(指标|维度).*(绑定到|绑定为|绑定至|映射到|映射为|对应到|对应为)\\s*[A-Za-z_][A-Za-z0-9_]*.*")
                || q.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}\\s*(=|＝)\\s*.*[A-Za-z_][A-Za-z0-9_]*.*")
                || q.matches(".*(以后|后续|之后).*(统一用|统一按|就按|按|按照)\\s*[A-Za-z_][A-Za-z0-9_]*.*")
                || q.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}(按|按照).*(除以|乘以|加上|减去|/|\\*|\\+|-).*")
                || hasBusinessDictionaryAliasIntent(q);
    }

    private boolean isStatisticalOutlierQuery(String q) {
        String text = text(q);
        String lower = text.toLowerCase(Locale.ROOT);
        boolean chartOrCorrelation = containsAny(text, "散点", "散点图", "相关", "相关性", "关系", "分布", "离群", "异常点", "异常分布点")
                || lower.contains("scatter") || lower.contains("correlation") || lower.contains("outlier");
        boolean analyticAction = containsAny(text, "分析", "查看", "看看", "展示", "用", "是否存在", "找出")
                || lower.contains("analyze") || lower.contains("show");
        boolean alertAction = containsAny(text, "预警", "告警", "报警", "警报", "提醒", "通知", "规则", "阈值", "阀值",
                "低于", "高于", "超过", "跌破", "邮件", "钉钉")
                || lower.contains("alert") || lower.contains("warning");
        boolean forecastAction = containsAny(text, "预测", "预估", "推算", "未来", "后续", "下个月", "下季度",
                "走势外推", "趋势延伸", "往后推")
                || lower.contains("forecast") || lower.contains("prediction");
        return chartOrCorrelation && analyticAction && !alertAction && !forecastAction;
    }

    private boolean isAlertLikeIntent(String intent) {
        String normalized = text(intent).toUpperCase(Locale.ROOT);
        return normalized.startsWith("ALERT") || normalized.contains("WARNING") || normalized.contains("ANOMALY");
    }

    private boolean isPredictiveIntent(String intent) {
        String normalized = text(intent).toUpperCase(Locale.ROOT);
        return "FORECAST".equals(normalized)
                || normalized.contains("PREDICTION")
                || normalized.contains("TIME_SERIES_FORECAST");
    }

    private boolean isExplicitQueryIntent(String q) {
        if (isStatisticalOutlierQuery(q)) {
            return true;
        }
        return containsAny(q, "排名", "排行", "排行榜", "Top", "top", "明细", "列表", "分布", "占比", "对比", "各省", "各市", "各区域")
                || (containsAny(q, "看一下", "查看", "查询", "统计") && !containsAny(q, "预测", "预警", "告警", "报警", "警报", "推演", "模拟"));
    }

    private boolean isHistoricalTrendQuery(String q) {
        String text = text(q);
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasHistoricalScope = containsAny(text, "今年", "去年", "历史", "最近", "近", "本月", "上月", "本季度", "上季度", "本年", "今年以来", "上半年", "下半年")
                || text.matches(".*近\\s*[一二两三四五六七八九十百\\d]+\\s*(天|日|周|月|季度|季|年).*")
                || text.matches(".*\\d{4}\\s*年.*");
        boolean hasTrendQuery = containsAny(text, "每个月", "每月", "按月", "月度", "每日", "每天", "按日", "日度",
                "每周", "按周", "周度", "每季度", "按季度", "季度", "每年", "按年", "年度", "走势", "趋势", "变化");
        return hasHistoricalScope && hasTrendQuery && !hasForecastIntent(text, lower);
    }

    private boolean hasForecastIntent(String q, String lower) {
        String text = text(q);
        String normalizedLower = firstText(lower, text.toLowerCase(Locale.ROOT));
        return containsAny(text, "预测", "预估", "推算", "未来", "后面", "往后", "下个月", "下季度", "下一季度",
                "后续", "大概会", "会到多少", "继续涨", "继续跌", "趋势延伸", "往后推")
                || normalizedLower.contains("forecast") || normalizedLower.contains("prediction");
    }

    private boolean isAlertIntent(String q, String lower) {
        if (isStatisticalOutlierQuery(q)) {
            return false;
        }
        return containsAny(q, "预警", "告警", "报警", "警报", "提醒", "通知", "低于", "高于", "超过", "跌破", "阈值", "阀值", "异常")
                || lower.contains("alert") || lower.contains("warning");
    }

    private String inferAlertLifecycleIntent(String q, String lower) {
        String text = text(q);
        if (isStatisticalOutlierQuery(text)) {
            return "";
        }
        boolean ruleDetectAction = containsAny(text, "检测", "手动检测", "立即检测", "执行检测", "跑一下", "跑一次", "触发检测", "重新检测");
        boolean ruleDetectExecution = ruleDetectAction && (!text.contains("检测周期")
                || containsAny(text, "手动检测", "立即检测", "执行检测", "跑一下", "跑一次", "触发检测", "重新检测")
                || text.startsWith("检测"));
        boolean ruleDetectText = containsAny(text, "规则") && ruleDetectExecution;
        if (!isAlertIntent(text, lower) && !containsAny(text, "报警", "告警", "警报") && !ruleDetectText) {
            return "";
        }
        boolean ruleText = containsAny(text, "规则", "阈值", "阀值", "检测周期", "通知渠道", "渠道");
        boolean createRuleText = containsAny(text, "提醒我", "通知我", "创建", "新建", "新增", "建一个")
                || (containsAny(text, "低于", "高于", "超过", "跌破") && containsAny(text, "提醒", "通知", "预警", "告警"));
        boolean eventActionText = containsAny(text, "预警事件", "报警事件", "告警事件", "这个报警", "这条报警", "该报警",
                "报警", "告警", "警报", "事件");
        if (ruleText && containsAny(text, "删除", "删掉", "移除")) {
            return "ALERT_RULE_DELETE";
        }
        if (ruleText && containsAny(text, "停用", "禁用", "暂停", "关闭", "关闭规则")) {
            return "ALERT_RULE_DISABLE";
        }
        if (ruleText && containsAny(text, "启用", "开启", "重开", "重新开启", "恢复", "恢复启用")) {
            return "ALERT_RULE_ENABLE";
        }
        if (ruleText && containsAny(text, "修改", "调整", "改成", "改为", "更新", "设置")) {
            return "ALERT_RULE_UPDATE";
        }
        if (ruleText && ruleDetectExecution) {
            return "ALERT_RULE_DETECT";
        }
        if (eventActionText && containsAny(text, "重开", "重新打开", "重新开启", "恢复待处理", "恢复为待处理", "恢复处理")) {
            return "ALERT_EVENT_REOPEN";
        }
        if (eventActionText && containsAny(text, "关闭", "处理完成", "已处理", "解决", "关闭这个报警", "关闭这条报警")) {
            return "ALERT_EVENT_CLOSE";
        }
        if (eventActionText && containsAny(text, "确认", "认领", "ack", "ACK")) {
            return "ALERT_EVENT_ACK";
        }
        if (createRuleText) {
            return "";
        }
        if (containsAny(text, "为什么", "原因", "解释", "怎么触发", "为何触发")) {
            return "ALERT_EVENT_EXPLAIN";
        }
        if (containsAny(text, "最近", "哪些", "列表", "记录", "事件", "已触发", "触发过", "查看", "查询", "报警", "告警", "警报")) {
            return "ALERT_EVENT_QUERY";
        }
        return "";
    }

    private String inferAlertRuleCreateIntent(String q, String lower) {
        String text = text(q);
        if (isStatisticalOutlierQuery(text)) {
            return "";
        }
        if (!isAlertIntent(text, lower)) {
            return "";
        }
        boolean explicitRuleCreate = containsAny(text, "提醒我", "通知我", "帮我提醒", "邮件提醒", "钉钉提醒")
                || (containsAny(text, "创建", "新建", "新增", "建一个", "配置", "设置")
                && containsAny(text, "预警", "告警", "报警", "警报", "规则"))
                || (containsAny(text, "低于", "高于", "超过", "跌破", "阈值", "阀值")
                && containsAny(text, "提醒", "通知", "预警", "告警", "报警", "警报"));
        return explicitRuleCreate ? "ALERT_RULE_CREATE" : "";
    }

    private boolean alertIntentRequiresConfirmation(String intent) {
        return switch (text(intent)) {
            case "ALERT_RULE_DELETE" -> true;
            default -> false;
        };
    }

    private String alertLifecycleReason(String intent) {
        return switch (text(intent)) {
            case "ALERT_EVENT_QUERY" -> "识别到已触发预警事件查询语义";
            case "ALERT_EVENT_EXPLAIN" -> "识别到预警事件原因解释语义";
            case "ALERT_EVENT_ACK" -> "识别到预警事件确认语义，执行前需要确认事件 ID";
            case "ALERT_EVENT_CLOSE" -> "识别到预警事件关闭语义，执行前需要确认事件 ID";
            case "ALERT_EVENT_REOPEN" -> "识别到预警事件重开语义，执行前需要确认事件 ID";
            case "ALERT_RULE_UPDATE" -> "识别到预警规则修改语义，直接执行规则参数更新";
            case "ALERT_RULE_DISABLE" -> "识别到预警规则停用语义，直接停用指定规则";
            case "ALERT_RULE_ENABLE" -> "识别到预警规则启用语义，直接启用指定规则";
            case "ALERT_RULE_DELETE" -> "识别到预警规则删除语义，执行前需要确认规则 ID";
            case "ALERT_RULE_DETECT" -> "识别到预警规则手动检测语义，直接执行一次规则检测";
            default -> "识别到预警相关语义";
        };
    }

    private Map<String, Object> inferAlertLifecycleSlots(String question, String tableName) {
        Map<String, Object> slots = new LinkedHashMap<>();
        if (!text(tableName).isBlank()) {
            slots.put("tableName", tableName);
        }
        Long eventId = alertEventId(question, Map.of());
        if (eventId != null && eventId > 0) {
            slots.put("eventId", eventId);
        }
        Long ruleId = alertRuleId(question, Map.of());
        if (ruleId != null && ruleId > 0) {
            slots.put("ruleId", ruleId);
        }
        Object threshold = inferThreshold(question);
        if (threshold != null) {
            slots.put("threshold", threshold);
        }
        if (containsAny(question, "低于", "高于", "超过", "跌破", "异常", "波动")) {
            slots.put("operator", inferOperator(question));
        }
        return slots;
    }

    private boolean isDashboardIntent(String q) {
        return containsAny(q, "经营驾驶舱", "新建看板", "创建看板")
                || (containsAny(q, "看板", "仪表盘", "大屏")
                && containsAny(q, "钉", "放到", "放入", "加入", "添加", "保存", "挂到", "新建", "创建"));
    }

    private String inferBusinessModelIntent(String q) {
        if (containsAny(q, "套用", "应用", "复用")) return "BUSINESS_MODEL_APPLY";
        if (containsAny(q, "发布", "企业模型库")) return "BUSINESS_MODEL_PUBLISH";
        if (hasBusinessModelCreateIntent(q)) return "BUSINESS_MODEL_CREATE";
        return "BUSINESS_MODEL_PATCH";
    }

    private boolean hasBusinessModelCreateIntent(String q) {
        String text = text(q);
        if (text.isBlank()) {
            return false;
        }
        if (containsAny(text, "新增模型", "新增业务模型", "增加模型", "增加业务模型", "添加模型", "添加业务模型",
                "创建模型", "创建业务模型", "新建模型", "新建业务模型", "建立模型", "建立业务模型",
                "搭建模型", "搭建业务模型", "构建模型", "构建业务模型", "生成模型", "生成业务模型")) {
            return true;
        }
        boolean lifecycleVerb = containsAny(text, "创建", "新建", "生成", "建立", "搭建", "构建", "做一个", "做个", "建一个");
        boolean namesModel = containsAny(text, "业务模型", "模型");
        if (lifecycleVerb && namesModel) {
            return true;
        }
        if (containsAny(text, "建模")) {
            return !hasBusinessModelContentMutationIntent(text);
        }
        boolean analysisObject = containsAny(text, "分析模型", "分析主题", "分析专题");
        return lifecycleVerb && analysisObject && !hasBusinessModelContentMutationIntent(text);
    }

    private boolean hasBusinessModelMutationIntent(String q) {
        String text = text(q);
        boolean hasMutationVerb = containsAny(text,
                "修改", "更新", "编辑", "调整", "补充", "完善", "新增", "增加", "添加", "创建", "新建", "改一下", "改成", "修正",
                "删除", "移除", "去掉", "取消",
                "绑定到", "绑定为", "绑定至",
                "映射到", "映射为", "映射至",
                "对应到", "对应为", "对应至",
                "改绑", "重新绑定",
                "口径", "含税", "不含税", "统一用", "统一按", "就按",
                "作为", "理解为", "视为", "归到", "归为", "归入", "等同于", "算作", "当作",
                "按", "按照", "除以", "乘以", "加上", "减去", "计算", "来算");
        boolean hasBusinessTarget = containsAny(text,
                "模型", "业务字典", "字典", "词典", "同义词", "术语", "映射",
                "业务公式", "指标公式", "公式", "指标", "维度", "字段", "销售额", "收入", "利润", "GMV", "毛利率");
        boolean hasFormulaExpression = text.matches(".*[一-龥A-Za-z0-9_]+\\s*(=|＝)\\s*.*")
                || text.matches(".*[一-龥A-Za-z0-9_]+\\s*(按|按照).*(除以|乘以|加上|减去|/|\\*|\\+|-).*")
                || text.matches(".*[一-龥A-Za-z0-9_]+率.*(除以|/).*");
        return (hasMutationVerb && hasBusinessTarget) || hasFormulaExpression || hasBusinessDictionaryAliasIntent(text);
    }

    private boolean hasBusinessModelContentMutationIntent(String q) {
        String text = text(q);
        if (text.isBlank()) {
            return false;
        }
        boolean explicitFieldBinding = containsAny(text,
                "字段绑定", "绑定字段", "字段修正", "改绑", "重新绑定",
                "绑定到", "绑定为", "绑定至",
                "映射到", "映射为", "映射至",
                "对应到", "对应为", "对应至");
        boolean explicitDictionary = containsAny(text,
                "新增业务字典", "增加业务字典", "添加业务字典", "创建业务字典",
                "新增字典", "增加字典", "添加字典", "创建字典",
                "新增词典", "新增同义词", "新增术语",
                "作为", "理解为", "视为", "归到", "归为", "归入", "等同于", "同义词", "别名")
                || hasBusinessDictionaryAliasIntent(text);
        boolean explicitFormula = containsAny(text,
                "新增业务公式", "增加业务公式", "添加业务公式", "创建业务公式",
                "新增指标公式", "增加指标公式", "添加指标公式", "创建指标公式",
                "新增公式", "增加公式", "添加公式", "创建公式");
        boolean explicitScope = containsAny(text, "口径", "以后", "统一用", "统一按", "改成按", "改为按",
                "按含税", "按不含税", "就按", "算作", "当作", "来算");
        boolean formulaExpression = text.matches(".*[一-龥A-Za-z0-9_]+\\s*(=|＝)\\s*.*")
                || text.matches(".*[一-龥A-Za-z0-9_]+\\s*(按|按照).*(除以|乘以|加上|减去|/|\\*|\\+|-).*")
                || text.matches(".*[一-龥A-Za-z0-9_]+率.*(除以|/).*");
        return explicitFieldBinding || explicitDictionary || explicitFormula || explicitScope || formulaExpression;
    }

    private boolean hasBusinessDictionaryAliasIntent(String q) {
        String text = text(q);
        if (text.isBlank()) {
            return false;
        }
        if (containsAny(text, "同义词", "别名", "业务术语", "术语", "叫法", "理解为", "视为", "等同于", "归到", "归为", "归入")) {
            return true;
        }
        return text.matches(".*(?:以后|后续|之后)?(?:用户|大家)?(?:说|提到|输入)?\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}\\s*[，,]?\\s*(?:就)?(?:统一\\S?按|统一用|按|按照)\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}(?:理解|处理|统计|计算|衡量|来看|来查|来分析).*")
                || text.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}\\s*(?:统一\\S?按|统一用|按|按照)\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,30}(?:理解|处理|统计|计算|衡量).*");
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

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        String text = text(value);
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }

    private Map<String, Object> withModelContext(Map<String, Object> context, Map<String, Object> requestContext) {
        Map<String, Object> merged = new LinkedHashMap<>(context == null ? Map.of() : context);
        if (requestContext == null || requestContext.isEmpty()) {
            return merged;
        }
        for (String key : List.of("modelId", "modelName", "modelCategory", "temperature", "timeoutSeconds")) {
            Object value = requestContext.get(key);
            if (value != null && !text(value).isBlank()) {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private void putIfMeaningful(Map<String, Object> target, String key, Object value) {
        if (value != null && !text(value).isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, Object> metadataOf(Object... keyValues) {
        if (keyValues == null || keyValues.length < 2) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = text(keyValues[i]);
            Object value = keyValues[i + 1];
            if (!key.isBlank() && value != null) {
                result.put(key, value);
            }
        }
        return result.isEmpty() ? Map.of() : result;
    }

    private record SmartActionStep(String id,
                                   String type,
                                   List<String> dependsOn,
                                   String question,
                                   Map<String, Object> slots,
                                   boolean requiresConfirmation,
                                   double confidence) {
        private SmartActionStep(String id,
                                String type,
                                List<String> dependsOn,
                                String question,
                                Map<String, Object> slots,
                                boolean requiresConfirmation) {
            this(id, type, dependsOn, question, slots, requiresConfirmation, 0D);
        }

        private SmartActionStep {
            id = textStatic(id).isBlank() ? type + "_1" : id;
            type = textStatic(type);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            question = textStatic(question);
            slots = copyNonNullMap(slots);
            confidence = normalizeConfidenceStatic(confidence, defaultActionConfidenceStatic(type));
        }

        private Map<String, Object> toMap(Map<String, Object> stepResult) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("dependsOn", dependsOn);
            map.put("question", question);
            map.put("slots", slots);
            map.put("confidence", confidence);
            map.put("requiresConfirmation", requiresConfirmation);
            if (stepResult != null && !stepResult.isEmpty()) {
                map.put("status", stepResult.get("status"));
                map.put("message", stepResult.get("message"));
            }
            return map;
        }
    }

    private record SmartActionPlan(String primaryIntent,
                                   List<SmartActionStep> actions,
                                   Map<String, Object> slots,
                                   double confidence,
                                   boolean requiresConfirmation,
                                   String reasoning,
                                   boolean fallbackUsed,
                                   boolean needClarification,
                                   List<String> missingSlots) {
        private SmartActionPlan(String primaryIntent,
                                List<SmartActionStep> actions,
                                Map<String, Object> slots,
                                double confidence,
                                boolean requiresConfirmation,
                                String reasoning,
                                boolean fallbackUsed) {
            this(primaryIntent, actions, slots, confidence, requiresConfirmation, reasoning,
                    fallbackUsed, false, List.of());
        }

        private static SmartActionPlan empty() {
            return new SmartActionPlan("", List.of(), Map.of(), 0D, false, "", false, false, List.of());
        }

        private SmartActionPlan {
            primaryIntent = textStatic(primaryIntent);
            actions = actions == null ? List.of() : List.copyOf(actions);
            slots = copyNonNullMap(slots);
            confidence = normalizeConfidenceStatic(confidence, actions.isEmpty() ? 0D : 0.78D);
            reasoning = textStatic(reasoning);
            missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
            needClarification = needClarification || !missingSlots.isEmpty();
            requiresConfirmation = requiresConfirmation || needClarification;
        }

        private boolean isMultiStep() {
            return actions.size() > 1 || readBooleanStatic(slots.get("deferredDashboardPin"));
        }

        private Map<String, Object> toMap(List<Map<String, Object>> stepResults) {
            Map<String, Map<String, Object>> resultById = new LinkedHashMap<>();
            if (stepResults != null) {
                for (Map<String, Object> step : stepResults) {
                    resultById.put(textStatic(step.get("id")), step);
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("primaryIntent", primaryIntent);
            map.put("confidence", confidence);
            map.put("needClarification", needClarification);
            map.put("requiresConfirmation", requiresConfirmation);
            map.put("missingSlots", missingSlots);
            map.put("reasoning", reasoning);
            map.put("slots", slots);
            map.put("actions", actions.stream()
                    .map(action -> action.toMap(resultById.get(action.id())))
                    .toList());
            return map;
        }
    }

    private static String textStatic(Object value) {
        return Objects.toString(value, "").trim();
    }

    private static boolean readBooleanStatic(Object value) {
        if (value instanceof Boolean bool) return bool;
        String text = textStatic(value).toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private static double normalizeConfidenceStatic(double value, double fallback) {
        double normalized = value > 0D ? value : fallback;
        if (normalized <= 0D) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, Math.round(normalized * 100.0D) / 100.0D));
    }

    private static double defaultActionConfidenceStatic(String type) {
        return switch (textStatic(type).toUpperCase(Locale.ROOT)) {
            case "QUERY_SQL" -> 0.82D;
            case "FORECAST" -> 0.78D;
            case "ALERT_RULE_CREATE_DRAFT" -> 0.74D;
            case "ALERT_EVENT_QUERY", "ALERT_EVENT_EXPLAIN" -> 0.76D;
            case "ALERT_EVENT_ACK", "ALERT_EVENT_CLOSE", "ALERT_EVENT_REOPEN",
                    "ALERT_RULE_UPDATE", "ALERT_RULE_DISABLE", "ALERT_RULE_ENABLE", "ALERT_RULE_DELETE",
                    "ALERT_RULE_DETECT" -> 0.72D;
            default -> 0.7D;
        };
    }

    private record ForecastRange(int value, String unit) {
        private ForecastRange {
            value = Math.max(1, value);
            unit = textStatic(unit).isBlank() ? "month" : unit;
        }
    }

    private static Map<String, Object> copyNonNullMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = textStatic(key);
            if (!normalizedKey.isBlank() && value != null) {
                result.put(normalizedKey, value);
            }
        });
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
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
            for (String key : List.of(
                    "selectedTableName", "activeBusinessModelId", "lastCreatedBusinessModelId", "lastAppliedBusinessModelId",
                    "modelId", "modelName", "modelCategory",
                    "userConfirmed", "confirmed", "confirmationAccepted",
                    "eventId", "alertEventId", "currentAlertEventId", "lastAlertEventId",
                    "ruleId", "alertRuleId", "currentAlertRuleId", "lastAlertRuleId")) {
                Object value = context.get(key);
                if (value != null && !Objects.toString(value, "").trim().isBlank()) {
                    merged.put(key, value);
                }
            }
            return new SmartIntent(primaryIntent, confidence, requiresConfirmation, reasoning, merged, fallbackUsed, missingSlots);
        }
    }
}
