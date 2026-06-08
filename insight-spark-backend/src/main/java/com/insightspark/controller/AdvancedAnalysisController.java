package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.AdvancedAnalysisService;
import com.insightspark.service.BusinessSemanticService;
import com.insightspark.service.ChatConversationService;
import com.insightspark.service.PythonAiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/advanced-analysis")
@CrossOrigin
public class AdvancedAnalysisController {

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private AdvancedAnalysisService advancedAnalysisService;

    @Autowired
    private BusinessSemanticService businessSemanticService;

    @Autowired
    private ChatConversationService chatConversationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/parse")
    public ApiResponse<Map<String, Object>> parseIntent(@RequestBody Map<String, Object> request) {
        String question = text(request.get("question"));
        if (question.isBlank()) {
            return ApiResponse.badRequest("分析问题不能为空");
        }
        String tableName = text(request.get("tableName"));
        Map<String, Object> context = asMap(request.get("context"));
        Map<String, Object> result = applyBusinessSemanticAnalysis(question, tableName, context,
                pythonAiService.parseAdvancedAnalysisIntent(question, tableName, context)
                        .orElseGet(() -> fallbackParse(question)));
        return ApiResponse.success(result);
    }

    @PostMapping(value = "/parse-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void parseIntentStream(@RequestBody Map<String, Object> request, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        try {
            String question = text(request.get("question"));
            if (question.isBlank()) {
                writeAdvancedSse(writer, "error", Map.of("message", "分析问题不能为空"));
                return;
            }

            String tableName = text(request.get("tableName"));
            Map<String, Object> context = asMap(request.get("context"));
            writeAdvancedThinkingStep(writer, "收到指令", trimTo(question, 80));
            writeAdvancedThinkingStep(writer, "准备上下文", tableName.isBlank()
                    ? "未指定数据源，将结合最近一次对话上下文识别"
                    : "数据源：" + tableName);

            int fieldCount = collectionSize(context.get("fields"));
            int timeFieldCount = collectionSize(context.get("timeFields"));
            int numericFieldCount = collectionSize(context.get("numericFields"));
            writeAdvancedThinkingStep(writer, "读取字段元数据",
                    "字段 " + fieldCount + " 个，时间字段 " + timeFieldCount + " 个，数值字段 " + numericFieldCount + " 个");
            writeAdvancedThinkingStep(writer, "调用 LLM 识别意图", "正在判断预测、What-if 推演或预警规则，并抽取指标、公式与参数");

            Map<String, Object> result = applyBusinessSemanticAnalysis(question, tableName, context,
                    pythonAiService.parseAdvancedAnalysisIntent(question, tableName, context)
                            .orElseGet(() -> fallbackParse(question)));
            writeAdvancedThinkingStep(writer, "意图解析完成", summarizeIntentResult(result));
            writeAdvancedSse(writer, "result", result);
        } catch (Exception e) {
            try {
                writeAdvancedSse(writer, "error", Map.of("message", e.getMessage() == null ? "高级分析意图解析失败" : e.getMessage()));
            } catch (IOException ignored) {
                // Client has disconnected.
            }
        } finally {
            writer.close();
        }
    }

    @PostMapping("/field-meta")
    public ApiResponse<Map<String, Object>> fieldMeta(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.fieldMeta(request));
    }

    @PostMapping("/chat-records")
    public ApiResponse<Map<String, Object>> saveChatRecord(@RequestBody Map<String, Object> request) {
        String question = text(request == null ? null : request.get("question"));
        if (question.isBlank()) {
            return ApiResponse.badRequest("分析问题不能为空");
        }
        return ApiResponse.success(chatConversationService.recordAdvancedAnalysisResult(request == null ? Map.of() : request));
    }

    private void writeAdvancedThinkingStep(PrintWriter writer, String title, String detail) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("detail", detail);
        payload.put("ts", System.currentTimeMillis());
        writeAdvancedSse(writer, "thinking", payload);
        pauseForAdvancedSseProgress();
    }

    private void pauseForAdvancedSseProgress() throws IOException {
        try {
            Thread.sleep(180);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Advanced analysis SSE progress interrupted", e);
        }
    }

    private void writeAdvancedSse(PrintWriter writer, String eventName, Object payload) throws IOException {
        writer.write("event: " + eventName + "\n");
        writer.write("data: " + objectMapper.writeValueAsString(payload) + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("SSE client disconnected");
        }
    }

    private String summarizeIntentResult(Map<String, Object> result) {
        String intent = text(result.get("intent"));
        String metric = text(result.get("metric"));
        String intentLabel = switch (intent) {
            case "forecast" -> "时序预测";
            case "whatIf" -> "What-if 推演";
            case "alert" -> "智能预警";
            default -> "未识别";
        };
        return metric.isBlank() ? "识别结果：" + intentLabel : "识别结果：" + intentLabel + "，指标：" + metric;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyBusinessSemanticAnalysis(String question, String tableName,
                                                              Map<String, Object> context,
                                                              Map<String, Object> parsed) {
        Map<String, Object> result = new LinkedHashMap<>(parsed == null ? Map.of() : parsed);
        String intent = text(result.getOrDefault("intent", result.get("type"))).toLowerCase();
        if (!List.of("forecast", "prediction", "timeseriesforecast", "alert", "warning").contains(intent)) {
            return result;
        }
        if (businessSemanticService == null || text(tableName).isBlank()) {
            return result;
        }
        try {
            List<Map<String, Object>> fields = context.get("fields") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            BusinessSemanticService.BusinessAnalysisResolution resolution =
                    businessSemanticService.resolveAnalysis(question, tableName, context, fields);
            if (!resolution.matched() || text(resolution.metricColumn()).isBlank()) {
                return result;
            }
            result.put("metric", firstText(result.get("metric"), resolution.metricLabel()));
            result.put("metricLabel", firstText(result.get("metricLabel"), resolution.metricLabel()));
            result.put("metricField", resolution.metricColumn());
            result.put("targetMetricField", resolution.metricColumn());
            result.put("businessSemanticTrace", resolution.trace());
            if (!text(resolution.metricExpression()).isBlank()) {
                result.put("metricExpression", resolution.metricExpression());
            }
            if (!text(resolution.formula()).isBlank()) {
                result.put("formula", resolution.formula());
            }
        } catch (Exception ignored) {
            // Business semantic enrichment is best-effort; advanced analysis keeps its original fallback.
        }
        return result;
    }

    private String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private int collectionSize(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        return 0;
    }

    private String trimTo(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    @PostMapping("/forecast")
    public ApiResponse<Map<String, Object>> forecast(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.forecast(request));
    }

    @PostMapping("/forecast-series")
    public ApiResponse<Map<String, Object>> forecastFromSeries(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.forecastFromSeries(request));
    }

    @PostMapping("/what-if")
    public ApiResponse<Map<String, Object>> whatIf(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.whatIf(request));
    }

    @PostMapping("/explain")
    public ApiResponse<Map<String, Object>> explain(@RequestBody Map<String, Object> request) {
        String type = text(request.getOrDefault("type", request.get("analysisType")));
        String question = text(request.get("question"));
        Map<String, Object> result = asMap(request.get("result"));
        if (result.isEmpty()) {
            result = asMap(request.get("analysis"));
        }
        Map<String, Object> context = asMap(request.get("context"));
        return ApiResponse.success(explainWithFallback(type, question, result, context, request));
    }

    @PostMapping("/alert-rules")
    public ApiResponse<Object> saveAlertRule(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "save"));
        if ("list".equalsIgnoreCase(action)) {
            return ApiResponse.success(advancedAnalysisService.listAlertRules());
        }
        if ("detail".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警规则 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.getAlertRule(id));
        }
        if ("update".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警规则 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.updateAlertRule(id, request));
        }
        return ApiResponse.success(advancedAnalysisService.saveAlertRule(request));
    }

    @PostMapping("/alert-rules/status")
    public ApiResponse<Map<String, Object>> updateAlertRuleStatus(@RequestBody Map<String, Object> request) {
        long id = parseLong(request.get("id"));
        if (id <= 0) {
            return ApiResponse.badRequest("预警规则 ID 无效");
        }
        return ApiResponse.success(advancedAnalysisService.updateAlertRuleStatus(id, request));
    }

    @PostMapping("/alert-rules/delete")
    public ApiResponse<Map<String, Object>> deleteAlertRule(@RequestBody Map<String, Object> request) {
        long id = parseLong(request.get("id"));
        if (id <= 0) {
            return ApiResponse.badRequest("预警规则 ID 无效");
        }
        return ApiResponse.success(advancedAnalysisService.deleteAlertRule(id));
    }

    @PostMapping("/alert-events/run")
    public ApiResponse<Map<String, Object>> runAlertDetection(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.runAlertRuleDetection(request));
    }

    @PostMapping("/alert-events")
    public ApiResponse<Object> listAlertEvents(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "list"));
        if ("status".equalsIgnoreCase(action) || "update".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警事件 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.updateAlertEventStatus(id, request));
        }
        if ("detail".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警事件 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.getAlertEvent(id));
        }
        if ("explain".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警事件 ID 无效");
            }
            Map<String, Object> event = advancedAnalysisService.getAlertEvent(id);
            Map<String, Object> explainRequest = new LinkedHashMap<>();
            explainRequest.put("type", "alert");
            explainRequest.put("question", text(request.getOrDefault("question", "解释预警事件 #" + id)));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tableName", text(event.get("tableName")));
            params.put("metricField", text(event.get("metricField")));
            params.put("timeField", text(event.get("timeField")));
            params.put("operator", text(event.get("operator")));
            params.put("threshold", event.get("threshold"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "alert");
            result.put("event", event);
            result.put("params", params);
            result.put("reason", text(event.get("reason")));
            result.put("chartSnapshot", event.getOrDefault("chartSnapshot", Map.of()));
            explainRequest.put("result", result);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("source", "advanced-alert-event");
            context.put("eventId", id);
            context.put("ruleId", event.get("ruleId"));
            context.put("status", text(event.get("status")));
            explainRequest.put("context", context);
            Map<String, Object> explanation = explainWithFallback(
                    "alert",
                    text(explainRequest.get("question")),
                    asMap(explainRequest.get("result")),
                    asMap(explainRequest.get("context")),
                    explainRequest
            );
            Map<String, Object> saveRequest = new LinkedHashMap<>();
            saveRequest.put("explanation", explanation);
            saveRequest.put("explanationNote", text(request.get("explanationNote")));
            return ApiResponse.success(advancedAnalysisService.saveAlertEventExplanation(id, saveRequest));
        }
        return ApiResponse.success(advancedAnalysisService.listAlertEvents(request));
    }

    @PostMapping("/alert-push")
    public ApiResponse<Object> alertPush(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "list"));
        if ("config".equalsIgnoreCase(action) || "status".equalsIgnoreCase(action)) {
            return ApiResponse.success(advancedAnalysisService.alertPushConfigStatus());
        }
        if ("retry".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警推送记录 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.retryAlertPushLog(id));
        }
        return ApiResponse.success(advancedAnalysisService.listAlertPushLogs(request));
    }

    @PostMapping("/plans")
    public ApiResponse<Object> plans(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "save"));
        if ("list".equalsIgnoreCase(action)) {
            return ApiResponse.success(advancedAnalysisService.listPlans(request));
        }
        if ("detail".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.getPlan(id));
        }
        if ("recalculate".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.recalculatePlan(id));
        }
        if ("delete".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.deletePlan(id));
        }
        if ("rename".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.renamePlan(id, request));
        }
        if ("versions".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.listPlanVersions(id));
        }
        if ("compare".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.comparePlanVersions(id, request));
        }
        if ("compare-latest".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.latestPlanVersionDiff(id));
        }
        return ApiResponse.success(advancedAnalysisService.savePlan(request));
    }

    private Map<String, Object> fallbackParse(String question) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        String intent = "";
        if (normalized.matches(".*(预测|预估|未来|走势|forecast|prophet|holt).*")) {
            intent = "forecast";
        } else if (normalized.matches(".*(what-?if|如果|若|假设|提升|下降|降低|增长|推演|模拟|利润变化).*")) {
            intent = "whatIf";
        } else if (normalized.matches(".*(预警|提醒|告警|低于|高于|超过|异常|阈值|通知|钉钉|邮件|z-?score).*")) {
            intent = "alert";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent);
        result.put("metric", inferMetric(question));
        result.put("fallbackUsed", true);
        result.put("reasoning", intent.isBlank() ? "未识别为预测、推演或预警意图" : "AI 服务不可用，已使用后端规则兜底解析");
        return result;
    }

    private String inferMetric(String question) {
        String content = question == null ? "" : question;
        for (String candidate : new String[]{"销售额", "利润", "成本", "销量", "收入", "转化率", "退货率", "客单价"}) {
            if (content.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private Map<String, Object> explainWithFallback(String type,
                                                    String question,
                                                    Map<String, Object> result,
                                                    Map<String, Object> context,
                                                    Map<String, Object> fallbackRequest) {
        Map<String, Object> aiResult = pythonAiService.explainAdvancedAnalysis(type, question, result, context)
                .orElse(Map.of());
        Map<String, Object> fallback;
        try {
            fallback = advancedAnalysisService.fallbackResultExplanation(fallbackRequest);
        } catch (RuntimeException ignored) {
            fallback = new LinkedHashMap<>();
            fallback.put("source", "rule");
            fallback.put("sourceLabel", "规则解释");
            fallback.put("calculation", java.util.List.of("当前解释基于后端已返回的算法结果生成，AI 服务不可用或解释内容解析失败。"));
            fallback.put("suggestions", java.util.List.of("请优先核对业务公式口径、字段单位和变量调整方向，再结合结果卡片数值判断方案。"));
            fallback.put("guardrail", "explanation-only");
        }
        if (!text(aiResult.get("source")).equalsIgnoreCase("llm")) {
            return fallback;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(fallback);
        normalized.putAll(aiResult);
        normalized.put("source", "llm");
        normalized.put("sourceLabel", "AI 解释");
        normalized.put("guardrail", "explanation-only");
        return normalized;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long parseLong(Object value) {
        try {
            return Long.parseLong(text(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
