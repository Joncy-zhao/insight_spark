package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.core.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.service.ChatBiService;
import com.insightspark.service.ChatQueryHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @PostMapping("/ask")
    public ApiResponse<Map<String, Object>> askQuestion(@RequestBody Map<String, String> request) {
        return executeQuestion(request.get("question"), request.get("tableName"), false);
    }

    @GetMapping("/ask")
    public ApiResponse<Map<String, Object>> askQuestionByGet(@RequestParam(required = false) String question,
                                                              @RequestParam(required = false) String tableName) {
        return executeQuestion(question, tableName, false);
    }

    @PostMapping("/ask-enhanced")
    public ApiResponse<Map<String, Object>> askQuestionEnhanced(@RequestBody Map<String, String> request) {
        return executeQuestion(request.get("question"), request.get("tableName"), true);
    }

    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> listHistory(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "8") int pageSize,
                                                        @RequestParam(required = false) String keyword) {
        return ApiResponse.success(chatQueryHistoryService.listHistoryPage(page, pageSize, keyword));
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
        return ApiResponse.success(chatQueryHistoryService.batchChartSnapshotsForCurrentUser(ids));
    }

    @GetMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void askQuestionStream(@RequestParam String question,
                                  @RequestParam(required = false) String tableName,
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

        AuthContext.UserPrincipal principal = AuthContext.get();
        long startedAt = System.currentTimeMillis();
        CompletableFuture<Map<String, Object>> queryFuture = new CompletableFuture<>();
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        Thread queryThread = new Thread(() -> {
            try {
                AuthContext.set(principal);
                queryFuture.complete(chatBiService.executeChat(question, tableName));
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
                    enrichEnhancedResponse(result, question, tableName);
                    Long historyId = chatQueryHistoryService.recordSuccess(question, tableName, result,
                            System.currentTimeMillis() - startedAt);
                    if (historyId != null) {
                        result.put("queryHistoryId", historyId);
                    }
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
            cancelQuery(queryFuture, queryThread);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chatQueryHistoryService.recordCancelled(question, tableName, "用户已停止生成",
                    System.currentTimeMillis() - startedAt);
            cancelQuery(queryFuture, queryThread);
        } catch (ExecutionException e) {
            String message = rootMessage(e);
            if (isCancellationMessage(message)) {
                chatQueryHistoryService.recordCancelled(question, tableName, message,
                        System.currentTimeMillis() - startedAt);
                if (!clientDisconnected.get()) {
                    tryWriteSse(writer, "cancelled", Map.of("message", message));
                }
                cancelQuery(queryFuture, queryThread);
                return;
            }
            chatQueryHistoryService.recordFailure(question, tableName, message, System.currentTimeMillis() - startedAt);
            writeSse(writer, "error", Map.of("message", "分析失败：" + message));
        } catch (IOException e) {
            clientDisconnected.set(true);
            chatQueryHistoryService.recordCancelled(question, tableName, "客户端已断开连接",
                    System.currentTimeMillis() - startedAt);
            cancelQuery(queryFuture, queryThread);
        } catch (Exception e) {
            chatQueryHistoryService.recordFailure(question, tableName, rootMessage(e), System.currentTimeMillis() - startedAt);
            writeSse(writer, "error", Map.of("message", "分析失败：" + e.getMessage()));
        } finally {
            if (!queryFuture.isDone()) {
                cancelQuery(queryFuture, queryThread);
            }
        }
    }

    private ApiResponse<Map<String, Object>> executeQuestion(String question, String tableName, boolean enhanced) {
        if (question == null || question.isBlank()) {
            return ApiResponse.badRequest("问题不能为空");
        }
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = chatBiService.executeChat(question, tableName);
            if (enhanced) {
                enrichEnhancedResponse(result, question, tableName);
            }
            Long historyId = chatQueryHistoryService.recordSuccess(question, tableName, result, System.currentTimeMillis() - startedAt);
            if (historyId != null) {
                result.put("queryHistoryId", historyId);
            }
            return ApiResponse.success(result);
        } catch (Exception e) {
            chatQueryHistoryService.recordFailure(question, tableName, rootMessage(e), System.currentTimeMillis() - startedAt);
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

    private void writeStep(PrintWriter writer, String title, String detail) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("detail", detail);
        payload.put("ts", System.currentTimeMillis());
        writeSse(writer, "thinking", payload);
    }

    private void writeSse(PrintWriter writer, String eventName, Object payload) throws IOException {
        writer.write("event: " + eventName + "\n");
        writer.write("data: " + objectMapper.writeValueAsString(payload) + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("SSE client disconnected");
        }
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
}
