package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.core.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.c.service.StackCDashboardService;
import com.insightspark.service.BusinessModelAgentService;
import com.insightspark.service.ChatBiService;
import com.insightspark.service.ChatConversationService;
import com.insightspark.service.ChatQueryHistoryService;
import com.insightspark.service.SmartChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ChatBiService chatBiService;

    @Autowired
    private ChatQueryHistoryService chatQueryHistoryService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private BusinessModelAgentService businessModelAgentService;

    @Autowired
    private StackCDashboardService stackCDashboardService;

    @Autowired
    private SmartChatService smartChatService;

    @PostMapping("/ask")
    public ApiResponse<Map<String, Object>> askQuestion(@RequestBody Map<String, Object> request) {
        return executeQuestion(text(request.get("question")), text(request.get("tableName")), false,
                toLong(request.get("conversationId")), toLong(request.get("parentTurnId")));
    }

    @GetMapping("/ask")
    public ApiResponse<Map<String, Object>> askQuestionByGet(@RequestParam(required = false) String question,
                                                              @RequestParam(required = false) String tableName,
                                                              @RequestParam(required = false) Long conversationId,
                                                              @RequestParam(required = false) Long parentTurnId) {
        return executeQuestion(question, tableName, false, conversationId, parentTurnId);
    }

    @PostMapping("/ask-enhanced")
    public ApiResponse<Map<String, Object>> askQuestionEnhanced(@RequestBody Map<String, Object> request) {
        return executeQuestion(text(request.get("question")), text(request.get("tableName")), true,
                toLong(request.get("conversationId")), toLong(request.get("parentTurnId")));
    }

    @PostMapping("/ask-smart")
    public ApiResponse<Map<String, Object>> askQuestionSmart(@RequestBody Map<String, Object> request) {
        return executeSmartQuestion(text(request.get("question")), text(request.get("tableName")),
                toLong(request.get("conversationId")), toLong(request.get("parentTurnId")),
                request == null ? Map.of() : request);
    }

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(chatConversationService.createConversation(request == null ? Map.of() : request));
    }

    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> listSessions(@RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int pageSize,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String advancedType) {
        return ApiResponse.success(chatConversationService.listConversations(page, pageSize, keyword, status, advancedType));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable Long sessionId) {
        return ApiResponse.success(chatConversationService.getConversation(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/rename")
    public ApiResponse<Map<String, Object>> renameSession(@PathVariable Long sessionId,
                                                          @RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(chatConversationService.renameConversation(sessionId,
                text(request == null ? null : request.get("title"))));
    }

    @PostMapping("/sessions/{sessionId}/status")
    public ApiResponse<Map<String, Object>> updateSessionStatus(@PathVariable Long sessionId,
                                                                @RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(chatConversationService.updateConversationStatus(sessionId,
                text(request == null ? null : request.get("status"))));
    }

    @PostMapping("/sessions/{sessionId}/delete")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        chatConversationService.deleteConversation(sessionId);
        return ApiResponse.success(null);
    }

    @GetMapping("/sessions/{sessionId}/turns")
    public ApiResponse<List<Map<String, Object>>> listSessionTurns(@PathVariable Long sessionId) {
        return ApiResponse.success(chatConversationService.listTurns(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> sendSessionMessage(@PathVariable Long sessionId,
                                                               @RequestBody Map<String, Object> request) {
        boolean enhanced = !"false".equalsIgnoreCase(text(request.get("enhanced")));
        return executeQuestion(text(request.get("question")), text(request.get("tableName")), enhanced,
                sessionId, toLong(request.get("parentTurnId")));
    }

    @PostMapping("/sessions/{sessionId}/summary")
    public ApiResponse<Map<String, Object>> refreshSessionSummary(@PathVariable Long sessionId,
                                                                  @RequestBody(required = false) Map<String, Object> request) {
        String summary = text(request == null ? null : request.get("summary"));
        return ApiResponse.success(chatConversationService.refreshConversationSummary(sessionId, summary));
    }

    @PostMapping("/business-model-agent")
    public ApiResponse<Map<String, Object>> handleBusinessModelAgent(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(businessModelAgentService.handleQuestion(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(rootMessage(e));
        }
    }

    @GetMapping(value = "/business-model-agent-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void handleBusinessModelAgentStream(@RequestParam String question,
                                               @RequestParam(required = false) String tableName,
                                               @RequestParam(required = false) String selectedTableName,
                                               @RequestParam(required = false) String activeBusinessModelId,
                                               @RequestParam(required = false) String lastCreatedBusinessModelId,
                                               @RequestParam(required = false) String lastAppliedBusinessModelId,
                                               HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        if (question == null || question.isBlank()) {
            writeSse(writer, "error", Map.of("message", "问题不能为空"));
            return;
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("tableName", tableName);
        request.put("selectedTableName", selectedTableName);
        if (activeBusinessModelId != null && !activeBusinessModelId.isBlank()) {
            request.put("activeBusinessModelId", activeBusinessModelId);
        }
        if (lastCreatedBusinessModelId != null && !lastCreatedBusinessModelId.isBlank()) {
            request.put("lastCreatedBusinessModelId", lastCreatedBusinessModelId);
        }
        if (lastAppliedBusinessModelId != null && !lastAppliedBusinessModelId.isBlank()) {
            request.put("lastAppliedBusinessModelId", lastAppliedBusinessModelId);
        }

        String[] titles = {"收到指令", "定位模型", "语义拆解", "执行修改", "刷新结果"};
        String[] details = {
                "已接收业务模型维护指令，正在识别新增、修改或删除动作",
                "结合当前模型上下文与数据源信息定位目标业务模型",
                "调用语义补丁能力拆解字典映射、业务公式和删除动作",
                "正在写回业务模型，并合并最新字典与业务公式",
                "模型修改已完成，正在返回最新结果并刷新维护面板"
        };
        AuthContext.UserPrincipal principal = AuthContext.get();
        CompletableFuture<Map<String, Object>> modelFuture = new CompletableFuture<>();
        Thread modelThread = new Thread(() -> {
            try {
                AuthContext.set(principal);
                modelFuture.complete(businessModelAgentService.handleQuestion(request));
            } catch (Exception e) {
                modelFuture.completeExceptionally(e);
            } finally {
                AuthContext.clear();
            }
        });
        modelThread.setName("business-model-agent-stream");
        modelThread.start();
        try {
            int stepIndex = 0;
            writeStep(writer, titles[stepIndex], details[stepIndex]);
            stepIndex++;
            while (true) {
                try {
                    Map<String, Object> result = modelFuture.get(stepIndex < titles.length ? 450 : 1000, TimeUnit.MILLISECONDS);
                    List<String> reasoning = new ArrayList<>();
                    Object rawReasoning = result.get("reasoning");
                    if (rawReasoning instanceof List<?> list) {
                        for (Object item : list) {
                            String line = item == null ? "" : String.valueOf(item).trim();
                            if (!line.isBlank()) {
                                reasoning.add(line);
                            }
                        }
                    }
                    while (stepIndex < titles.length - 1) {
                        writeStep(writer, titles[stepIndex], details[stepIndex]);
                        stepIndex++;
                        pauseForSseProgress();
                    }
                    for (String line : reasoning.stream().limit(4).toList()) {
                        writeStep(writer, "语义结果", line);
                        pauseForSseProgress();
                    }
                    writeStep(writer, titles[titles.length - 1], details[details.length - 1]);
                    writeSse(writer, "result", result);
                    return;
                } catch (TimeoutException timeout) {
                    if (stepIndex < titles.length - 1) {
                        writeStep(writer, titles[stepIndex], details[stepIndex]);
                        stepIndex++;
                    } else {
                        writeStep(writer, "执行中", "已定位到模型，正在等待语义解析与保存结果");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            modelFuture.cancel(true);
            modelThread.interrupt();
            writeSse(writer, "error", Map.of("message", "业务模型处理已中断"));
        } catch (ExecutionException e) {
            writeSse(writer, "error", Map.of("message", rootMessage(e)));
        } catch (Exception e) {
            writeSse(writer, "error", Map.of("message", rootMessage(e)));
        } finally {
            if (!modelFuture.isDone()) {
                modelFuture.cancel(true);
                modelThread.interrupt();
            }
        }
    }

    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> listHistory(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "8") int pageSize,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String tableName,
                                                        @RequestParam(required = false) String chartType,
                                                        @RequestParam(required = false) String riskLevel,
                                                        @RequestParam(required = false) String executionStatus,
                                                        @RequestParam(required = false) String dateFrom,
                                                        @RequestParam(required = false) String dateTo,
                                                        @RequestParam(required = false) String sortDirection) {
        return ApiResponse.success(chatQueryHistoryService.listHistoryPage(
                page, pageSize, keyword, tableName, chartType, riskLevel, executionStatus, dateFrom, dateTo, sortDirection));
    }

    @PostMapping("/history/{historyId}/delete")
    public ApiResponse<Void> deleteHistory(@PathVariable Long historyId) {
        chatQueryHistoryService.deleteHistory(historyId);
        return ApiResponse.success(null);
    }

    @PostMapping("/history/charts-batch")
    public ApiResponse<List<Map<String, Object>>> batchChartSnapshots(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return ApiResponse.badRequest("请提供 ids 数组");
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                ids.add(n.longValue());
            } else if (o != null) {
                try {
                    ids.add(Long.parseLong(String.valueOf(o).trim()));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        if (ids.isEmpty()) {
            return ApiResponse.badRequest("ids 格式无效");
        }
        Long dashboardId = toLong(body == null ? null : body.get("dashboardId"));
        if (dashboardId != null && dashboardId > 0) {
            try {
                stackCDashboardService.getById(dashboardId);
            } catch (IllegalArgumentException e) {
                return ApiResponse.badRequest(e.getMessage());
            }
            return ApiResponse.success(
                    chatQueryHistoryService.batchChartSnapshotsForSharedDashboard(ids, dashboardId));
        }
        return ApiResponse.success(chatQueryHistoryService.batchChartSnapshotsForCurrentUser(ids));
    }

    @GetMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void askQuestionStream(@RequestParam String question,
                                   @RequestParam(required = false) String tableName,
                                   @RequestParam(required = false) Long conversationId,
                                   @RequestParam(required = false) Long parentTurnId,
                                   @RequestParam(required = false) String selectedTableName,
                                   @RequestParam(required = false) String activeBusinessModelId,
                                   @RequestParam(required = false) String lastCreatedBusinessModelId,
                                   @RequestParam(required = false) String lastAppliedBusinessModelId,
                                   HttpServletResponse response) throws IOException {
        streamQuestion(question, tableName, conversationId, parentTurnId,
                businessModelContext(selectedTableName, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId),
                response);
    }

    @GetMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void askSessionQuestionStream(@PathVariable Long sessionId,
                                          @RequestParam String question,
                                          @RequestParam(required = false) String tableName,
                                          @RequestParam(required = false) Long parentTurnId,
                                          @RequestParam(required = false) String selectedTableName,
                                          @RequestParam(required = false) String activeBusinessModelId,
                                          @RequestParam(required = false) String lastCreatedBusinessModelId,
                                          @RequestParam(required = false) String lastAppliedBusinessModelId,
                                          HttpServletResponse response) throws IOException {
        streamQuestion(question, tableName, sessionId, parentTurnId,
                businessModelContext(selectedTableName, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId),
                response);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void postSessionQuestionStream(@PathVariable Long sessionId,
                                           @RequestBody Map<String, Object> request,
                                           HttpServletResponse response) throws IOException {
        streamQuestion(text(request.get("question")), text(request.get("tableName")),
                sessionId, toLong(request.get("parentTurnId")), request == null ? Map.of() : request, response);
    }

    private void streamQuestion(String question, String tableName, Long conversationId, Long parentTurnId,
                                Map<String, Object> requestContext, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        if (question == null || question.isBlank()) {
            writeSse(writer, "error", Map.of("message", "问题不能为空"));
            return;
        }

        AuthContext.UserPrincipal principal = AuthContext.get();
        Long activeConversationId = safeEnsureConversation(conversationId, question, tableName);
        Map<String, Object> userTurn = safeRecordUserTurn(activeConversationId, parentTurnId,
                question, tableName, Map.of("transport", "SSE"));
        Long userTurnId = toLong(userTurn.get("id"));
        String executionQuestion = safeBuildExecutionQuestion(activeConversationId, userTurnId, question);
        Map<String, Object> executionContext = new LinkedHashMap<>(requestContext == null ? Map.of() : requestContext);
        executionContext.put("rawQuestion", question);
        long startedAt = System.currentTimeMillis();
        CompletableFuture<Map<String, Object>> queryFuture = new CompletableFuture<>();
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        Thread queryThread = new Thread(() -> {
            try {
                AuthContext.set(principal);
                queryFuture.complete(smartChatService.executeSmart(buildChatQueryRequest(
                        executionQuestion, tableName, activeConversationId, parentTurnId, executionContext
                )));
            } catch (Exception e) {
                queryFuture.completeExceptionally(e);
            } finally {
                AuthContext.clear();
            }
        });
        queryThread.setName("chat-stream-query");
        queryThread.start();
        String[] titles = {"收到问题", "图谱导航", "语义改写", "SQL生成", "安全检测", "执行查询"};
        String[] details = {
                "已接收业务问题，正在识别时间、指标、维度等关键词",
                "结合知识图谱匹配候选数据表、字段别名与业务同义词",
                "将自然语言问题改写为结构化分析意图，准备生成 SQL",
                "基于字段映射与图表意图生成只读 SQL，并补充排序/聚合条件",
                "执行 SQL 风险审计、限制返回条数并评估敏感字段脱敏策略",
                "查询执行中，正在等待结果返回并生成图表配置"
        };

        try {
            // Always emit the first thinking step so the frontend can show live progress immediately.
            int stepIndex = 1;
            writeStep(writer, titles[0], details[0]);
            while (true) {
                try {
                    Map<String, Object> result = queryFuture.get(stepIndex < titles.length ? 450 : 1000, TimeUnit.MILLISECONDS);
                    while (stepIndex < titles.length) {
                        writeStep(writer, titles[stepIndex], details[stepIndex]);
                        stepIndex++;
                        pauseForSseProgress();
                    }
                    enrichEnhancedResponse(result, question, tableName);
                    attachHistoryReplaySteps(result, question, tableName);
                    Long historyId = chatQueryHistoryService.recordSuccess(question, tableName, result,
                            System.currentTimeMillis() - startedAt);
                    if (historyId != null) {
                        result.put("queryHistoryId", historyId);
                    }
                    Map<String, Object> assistantTurn = safeRecordAssistantResult(
                            activeConversationId, userTurnId, question, result, historyId);
                    attachConversationResponse(result, activeConversationId, userTurnId, assistantTurn);
                    attachHistoryConversationMetadata(historyId, activeConversationId, userTurn, assistantTurn,
                            question, tableName, result);
                    writeSse(writer, "result", result);
                    return;
                } catch (TimeoutException timeout) {
                    if (stepIndex < titles.length) {
                        writeStep(writer, titles[stepIndex], details[stepIndex]);
                        stepIndex++;
                    } else {
                        writeStep(writer, "结果整理", "已完成查询，正在整理图表数据与返回内容");
                    }
                }
            }
        } catch (CancellationException e) {
            chatQueryHistoryService.recordCancelled(question, tableName, rootMessage(e),
                    System.currentTimeMillis() - startedAt);
            safeRecordAssistantFailure(activeConversationId, userTurnId, question, rootMessage(e));
            cancelQuery(queryFuture, queryThread);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeRecordAssistantFailure(activeConversationId, userTurnId, question, "USER_STOPPED");
            chatQueryHistoryService.recordCancelled(question, tableName, "用户已停止生成",
                    System.currentTimeMillis() - startedAt);
            cancelQuery(queryFuture, queryThread);
        } catch (ExecutionException e) {
            String message = rootMessage(e);
            if (isCancellationMessage(message)) {
                chatQueryHistoryService.recordCancelled(question, tableName, message,
                        System.currentTimeMillis() - startedAt);
                safeRecordAssistantFailure(activeConversationId, userTurnId, question, message);
                if (!clientDisconnected.get()) {
                    tryWriteSse(writer, "cancelled", Map.of("message", message));
                }
                cancelQuery(queryFuture, queryThread);
                return;
            }
            chatQueryHistoryService.recordFailure(question, tableName, message, System.currentTimeMillis() - startedAt);
            safeRecordAssistantFailure(activeConversationId, userTurnId, question, message);
            writeSse(writer, "error", Map.of("message", "分析失败：" + message));
        } catch (IOException e) {
            clientDisconnected.set(true);
            chatQueryHistoryService.recordCancelled(question, tableName, "客户端已断开连接",
                    System.currentTimeMillis() - startedAt);
            cancelQuery(queryFuture, queryThread);
        } catch (Exception e) {
            chatQueryHistoryService.recordFailure(question, tableName, rootMessage(e), System.currentTimeMillis() - startedAt);
            safeRecordAssistantFailure(activeConversationId, userTurnId, question, rootMessage(e));
            writeSse(writer, "error", Map.of("message", "分析失败：" + e.getMessage()));
        } finally {
            if (!queryFuture.isDone()) {
                cancelQuery(queryFuture, queryThread);
            }
        }
    }

    private ApiResponse<Map<String, Object>> executeQuestion(String question, String tableName, boolean enhanced,
                                                             Long conversationId, Long parentTurnId) {
        if (question == null || question.isBlank()) {
            return ApiResponse.badRequest("问题不能为空");
        }
        long startedAt = System.currentTimeMillis();
        Long activeConversationId = safeEnsureConversation(conversationId, question, tableName);
        Map<String, Object> userTurn = safeRecordUserTurn(activeConversationId, parentTurnId,
                question, tableName, Map.of("transport", "HTTP"));
        Long userTurnId = toLong(userTurn.get("id"));
        String executionQuestion = safeBuildExecutionQuestion(activeConversationId, userTurnId, question);
        try {
            Map<String, Object> result = chatBiService.executeChat(buildChatQueryRequest(
                    executionQuestion, tableName, activeConversationId, parentTurnId
            ));
            if (enhanced) {
                enrichEnhancedResponse(result, question, tableName);
            }
            attachHistoryReplaySteps(result, question, tableName);
            Long historyId = chatQueryHistoryService.recordSuccess(question, tableName, result, System.currentTimeMillis() - startedAt);
            if (historyId != null) {
                result.put("queryHistoryId", historyId);
            }
            Map<String, Object> assistantTurn = safeRecordAssistantResult(
                    activeConversationId, userTurnId, question, result, historyId);
            attachConversationResponse(result, activeConversationId, userTurnId, assistantTurn);
            attachHistoryConversationMetadata(historyId, activeConversationId, userTurn, assistantTurn,
                    question, tableName, result);
            return ApiResponse.success(result);
        } catch (Exception e) {
            chatQueryHistoryService.recordFailure(question, tableName, rootMessage(e), System.currentTimeMillis() - startedAt);
            safeRecordAssistantFailure(activeConversationId, userTurnId, question, rootMessage(e));
            return ApiResponse.error("分析失败：" + e.getMessage());
        }
    }

    private void enrichEnhancedResponse(Map<String, Object> result, String question, String tableName) {
        String engine = String.valueOf(result.getOrDefault("engine", "unknown"));
        boolean fallbackUsed = engine.startsWith("java-fallback")
                || Boolean.parseBoolean(String.valueOf(result.getOrDefault("fallbackUsed", false)));
        boolean aiAvailable = !fallbackUsed && result.get("sql") != null;

        result.put("analysisMode", "ENHANCED_SAFE_COMPAT");
        result.put("fallbackUsed", fallbackUsed);
        result.put("aiAvailable", aiAvailable);
        result.put("responseProfile", fallbackUsed ? "RULE_BASED" : "AI_READY");
        result.put("queryQuestion", question);
        result.put("queryTableName", tableName);
        result.put("resultSource", fallbackUsed ? "RULES" : "AI_OR_RULES");
    }

    private void attachConversationResponse(Map<String, Object> result, Long conversationId, Long userTurnId,
                                            Map<String, Object> assistantTurn) {
        if (result == null) {
            return;
        }
        result.put("conversationId", conversationId);
        result.put("userTurnId", userTurnId);
        result.put("assistantTurnId", toLong(assistantTurn.get("id")));
        result.put("turnNo", assistantTurn.get("turnNo"));
        result.put("artifactId", assistantTurn.get("artifactId"));
        result.put("artifactIds", assistantTurn.getOrDefault("artifactIds", List.of()));
    }

    private ChatBiService.ChatQueryRequest buildChatQueryRequest(String question, String tableName,
                                                                 Long conversationId, Long parentTurnId) {
        return buildChatQueryRequest(question, tableName, conversationId, parentTurnId, Map.of());
    }

    private ChatBiService.ChatQueryRequest buildChatQueryRequest(String question, String tableName,
                                                                 Long conversationId, Long parentTurnId,
                                                                 Map<String, Object> requestContext) {
        ChatBiService.ChatQueryRequest request = new ChatBiService.ChatQueryRequest();
        request.setQuestion(question);
        request.setConversationId(conversationId);
        request.setParentTurnId(parentTurnId);
        request.setTableNames(tableName == null || tableName.isBlank() ? List.of() : List.of(tableName));
        Map<String, Object> filters = new LinkedHashMap<>();
        if (tableName != null && !tableName.isBlank()) {
            filters.put("tableName", tableName);
        }
        putIfPresent(filters, "selectedTableName", requestContext == null ? null : requestContext.get("selectedTableName"));
        putIfPresent(filters, "activeBusinessModelId", requestContext == null ? null : requestContext.get("activeBusinessModelId"));
        putIfPresent(filters, "lastCreatedBusinessModelId", requestContext == null ? null : requestContext.get("lastCreatedBusinessModelId"));
        putIfPresent(filters, "lastAppliedBusinessModelId", requestContext == null ? null : requestContext.get("lastAppliedBusinessModelId"));
        putIfPresent(filters, "rawQuestion", requestContext == null ? null : requestContext.get("rawQuestion"));
        request.setFilters(filters);
        request.setMode("CHAT");
        return request;
    }

    private void attachHistoryConversationMetadata(Long historyId, Long conversationId, Map<String, Object> userTurn,
                                                   Map<String, Object> assistantTurn, String question,
                                                   String tableName, Map<String, Object> result) {
        if (historyId == null || conversationId == null) {
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userTurnId", toLong(userTurn.get("id")));
        context.put("assistantTurnId", toLong(assistantTurn.get("id")));
        context.put("artifactId", assistantTurn.get("artifactId"));
        context.put("tableName", tableName);
        context.put("engine", result == null ? null : result.get("engine"));
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("tableName", tableName);
        scope.put("dataSourceId", result == null ? null : result.get("dataSourceId"));
        chatQueryHistoryService.attachConversationMetadata(
                historyId,
                conversationId,
                null,
                toInt(assistantTurn.get("turnNo")),
                "ASSISTANT",
                Objects.toString(userTurn.getOrDefault("intentType", "QUERY"), "QUERY"),
                context,
                scope,
                "CHART",
                Objects.toString(question, "")
        );
    }

    private void attachHistoryReplaySteps(Map<String, Object> result, String question, String tableName) {
        if (result == null) {
            return;
        }
        result.put("reasoningReplaySteps", buildHistoryReplaySteps(question, tableName, result));
    }

    private List<Map<String, Object>> buildHistoryReplaySteps(String question, String tableName,
                                                              Map<String, Object> result) {
        List<Map<String, Object>> steps = new ArrayList<>();
        String safeQuestion = trimTo(Objects.toString(question, ""), 80);
        String safeTableName = trimTo(Objects.toString(tableName, ""), 80);
        String chartType = Objects.toString(result == null ? null : result.get("chartType"), "bar");
        String chartLabel = humanizeChartType(chartType);
        String riskLevel = Objects.toString(result == null ? null : result.get("riskLevel"), "SAFE");
        String riskReason = trimTo(Objects.toString(result == null ? null : result.get("riskReason"), ""), 120);
        int dataCount = countResultRows(result == null ? null : result.get("data"));
        List<String> reasoningLogs = toStringList(result == null ? null : result.get("reasoningLogs"));
        String smartIntent = Objects.toString(result == null ? null : result.get("smartIntent"), "");
        boolean smartNonQuery = !smartIntent.isBlank() && !"QUERY_SQL".equalsIgnoreCase(smartIntent);

        steps.add(step("收到问题", safeQuestion.isBlank()
                ? "已接收用户查询，开始进入分析流程"
                : "已接收用户查询：" + safeQuestion));
        steps.add(step("图谱导航", safeTableName.isBlank()
                ? "正在结合数据源与字段元信息定位分析对象"
                : "已定位数据表 " + safeTableName + "，正在结合图谱与字段元信息分析"));
        steps.add(step("语义改写", smartNonQuery
                ? "已将自然语言问题拆解为智能动作意图：" + smartIntent
                : "已将自然语言问题拆解为结构化分析意图，准备生成 SQL"));
        steps.add(step(smartNonQuery ? "动作编排" : "SQL 生成", smartNonQuery
                ? "已根据语义路由选择预测、预警、建模或草稿生成等业务动作"
                : "已生成 " + chartLabel + " 所需 SQL，并完成字段映射"));
        steps.add(step("安全检测", smartNonQuery
                ? "已执行参数完整性、确认策略和副作用保护检查"
                : "SQL 安全审计 " + riskLevel + (riskReason.isBlank() ? "" : "，" + riskReason)));
        steps.add(step("执行查询", dataCount > 0
                ? "查询执行完成，返回 " + dataCount + " 行结果"
                : "查询已完成，返回结果待整理"));
        if (!reasoningLogs.isEmpty()) {
            steps.add(step("推理摘要", String.join("；", reasoningLogs.stream().limit(4).toList())));
        }
        steps.add(step("结果整理", trimTo(Objects.toString(result == null ? null : result.get("message"), "分析完成"), 120)));
        return steps;
    }

    private Map<String, Object> step(String title, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("title", title);
        step.put("detail", detail);
        return step;
    }

    private String humanizeChartType(String chartType) {
        String text = Objects.toString(chartType, "").trim().toLowerCase();
        return switch (text) {
            case "line" -> "折线图";
            case "pie" -> "饼图";
            case "table" -> "表格";
            default -> "柱状图";
        };
    }

    private int countResultRows(Object data) {
        if (data instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private List<String> toStringList(Object value) {
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
        return text.isBlank() ? List.of() : List.of(text);
    }

    private String trimTo(String value, int maxLength) {
        String text = Objects.toString(value, "").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength).trim();
    }

    private ApiResponse<Map<String, Object>> executeSmartQuestion(String question, String tableName,
                                                                  Long conversationId, Long parentTurnId,
                                                                  Map<String, Object> requestContext) {
        if (question == null || question.isBlank()) {
            return ApiResponse.badRequest("问题不能为空");
        }
        long startedAt = System.currentTimeMillis();
        Long activeConversationId = safeEnsureConversation(conversationId, question, tableName);
        Map<String, Object> userTurn = safeRecordUserTurn(activeConversationId, parentTurnId,
                question, tableName, Map.of("transport", "HTTP_SMART"));
        Long userTurnId = toLong(userTurn.get("id"));
        String executionQuestion = safeBuildExecutionQuestion(activeConversationId, userTurnId, question);
        Map<String, Object> executionContext = new LinkedHashMap<>(requestContext == null ? Map.of() : requestContext);
        executionContext.put("rawQuestion", question);
        try {
            Map<String, Object> result = smartChatService.executeSmart(buildChatQueryRequest(
                    executionQuestion, tableName, activeConversationId, parentTurnId, executionContext
            ));
            enrichEnhancedResponse(result, question, tableName);
            attachHistoryReplaySteps(result, question, tableName);
            Long historyId = chatQueryHistoryService.recordSuccess(question, tableName, result, System.currentTimeMillis() - startedAt);
            if (historyId != null) {
                result.put("queryHistoryId", historyId);
            }
            Map<String, Object> assistantTurn = safeRecordAssistantResult(
                    activeConversationId, userTurnId, question, result, historyId);
            attachConversationResponse(result, activeConversationId, userTurnId, assistantTurn);
            attachHistoryConversationMetadata(historyId, activeConversationId, userTurn, assistantTurn,
                    question, tableName, result);
            return ApiResponse.success(result);
        } catch (Exception e) {
            chatQueryHistoryService.recordFailure(question, tableName, rootMessage(e), System.currentTimeMillis() - startedAt);
            safeRecordAssistantFailure(activeConversationId, userTurnId, question, rootMessage(e));
            return ApiResponse.error("智能分析失败：" + e.getMessage());
        }
    }

    private Map<String, Object> businessModelContext(String selectedTableName,
                                                     String activeBusinessModelId,
                                                     String lastCreatedBusinessModelId,
                                                     String lastAppliedBusinessModelId) {
        Map<String, Object> context = new LinkedHashMap<>();
        putIfPresent(context, "selectedTableName", selectedTableName);
        putIfPresent(context, "activeBusinessModelId", activeBusinessModelId);
        putIfPresent(context, "lastCreatedBusinessModelId", lastCreatedBusinessModelId);
        putIfPresent(context, "lastAppliedBusinessModelId", lastAppliedBusinessModelId);
        return context;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        String text = text(value);
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }
    private Long safeEnsureConversation(Long conversationId, String question, String tableName) {
        try {
            return chatConversationService.ensureConversation(conversationId, question, tableName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> safeRecordUserTurn(Long conversationId, Long parentTurnId, String question,
                                                   String tableName, Map<String, Object> context) {
        try {
            return chatConversationService.recordUserTurn(conversationId, parentTurnId, question, tableName, context);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> safeRecordAssistantResult(Long conversationId, Long parentTurnId, String question,
                                                          Map<String, Object> result, Long historyId) {
        try {
            return chatConversationService.recordAssistantResult(conversationId, parentTurnId, question, result, historyId);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void safeRecordAssistantFailure(Long conversationId, Long parentTurnId, String question, String message) {
        try {
            chatConversationService.recordAssistantFailure(conversationId, parentTurnId, question, message);
        } catch (Exception ignored) {
            // Conversation metadata is best-effort and must not break legacy query flow.
        }
    }

    private String safeBuildExecutionQuestion(Long conversationId, Long userTurnId, String question) {
        try {
            return chatConversationService.buildExecutionQuestion(conversationId, userTurnId, question);
        } catch (Exception ignored) {
            return question;
        }
    }

    private void writeStep(PrintWriter writer, String title, String detail) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("detail", detail);
        payload.put("ts", System.currentTimeMillis());
        writeSse(writer, "thinking", payload);
    }

    private void pauseForSseProgress() throws InterruptedException {
        Thread.sleep(180);
    }

    private void writeSse(PrintWriter writer, String eventName, Object payload) throws IOException {
        Object safePayload = toJsonSafeValue(payload);
        writer.write("event: " + eventName + "\n");
        writer.write("data: " + objectMapper.writeValueAsString(safePayload) + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("SSE client disconnected");
        }
    }

    private Object toJsonSafeValue(Object value) {
        return toJsonSafeValue(value, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private Object toJsonSafeValue(Object value, Set<Object> visiting) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof TemporalAccessor || value instanceof Date) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            if (!visiting.add(value)) {
                return "[Circular]";
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                safe.put(String.valueOf(entry.getKey()), toJsonSafeValue(entry.getValue(), visiting));
            }
            visiting.remove(value);
            return safe;
        }
        if (value instanceof Collection<?> collection) {
            if (!visiting.add(value)) {
                return List.of("[Circular]");
            }
            List<Object> safe = new ArrayList<>();
            for (Object item : collection) {
                safe.add(toJsonSafeValue(item, visiting));
            }
            visiting.remove(value);
            return safe;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            if (!visiting.add(value)) {
                return List.of("[Circular]");
            }
            int length = Array.getLength(value);
            List<Object> safe = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                safe.add(toJsonSafeValue(Array.get(value, i), visiting));
            }
            visiting.remove(value);
            return safe;
        }
        return String.valueOf(value);
    }

    private void cancelQuery(CompletableFuture<Map<String, Object>> queryFuture, Thread queryThread) {
        queryFuture.cancel(true);
        queryThread.interrupt();
    }

    private void tryWriteSse(PrintWriter writer, String eventName, Object payload) {
        try {
            writeSse(writer, eventName, payload);
        } catch (IOException ignored) {
            // 客户端已断开时无需继续抛错，交由上层关闭流程。
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof ExecutionException || current instanceof CompletionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private boolean isCancellationMessage(String message) {
        String text = message == null ? "" : message.toLowerCase();
        return text.contains("停止生成") || text.contains("cancel") || text.contains("canceled")
                || text.contains("cancelled");
    }
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
