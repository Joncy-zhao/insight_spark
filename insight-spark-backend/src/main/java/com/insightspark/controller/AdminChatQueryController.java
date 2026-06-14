package com.insightspark.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.common.ApiResponse;
import com.insightspark.service.ChatBiService;
import com.insightspark.service.AdminChatQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/chat-query")
@CrossOrigin
public class AdminChatQueryController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AdminChatQueryService adminChatQueryService;

    @GetMapping("/datasources")
    public ApiResponse<List<Map<String, Object>>> listDatasources() {
        return ApiResponse.success(adminChatQueryService.listDatasources());
    }

    @GetMapping("/models")
    public ApiResponse<List<Map<String, Object>>> listModels() {
        return ApiResponse.success(adminChatQueryService.listModels());
    }

    @GetMapping("/templates")
    public ApiResponse<List<Map<String, Object>>> listTemplates() {
        return ApiResponse.success(adminChatQueryService.listTemplates());
    }

    @PostMapping("/templates")
    public ApiResponse<Map<String, Object>> saveTemplate(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(adminChatQueryService.saveTemplate(request == null ? Map.of() : request));
    }

    @PostMapping("/templates/{templateId}/delete")
    public ApiResponse<Void> deleteTemplate(@PathVariable Long templateId) {
        adminChatQueryService.deleteTemplate(templateId);
        return ApiResponse.success(null);
    }

    @PostMapping("/sessions/recent/rerun")
    public ApiResponse<Map<String, Object>> rerunLatest() {
        return ApiResponse.success(adminChatQueryService.rerunLatest());
    }

    @PostMapping("/sessions/{sessionId}/rerun")
    public ApiResponse<Map<String, Object>> rerunSession(@PathVariable Long sessionId) {
        return ApiResponse.success(adminChatQueryService.rerunSession(sessionId));
    }

    @PostMapping("/compare-models")
    public ApiResponse<Map<String, Object>> compareModels(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(adminChatQueryService.compareModels(request == null ? Map.of() : request));
    }

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> createSession(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(adminChatQueryService.createSession(request == null ? Map.of() : request));
    }

    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> listSessions(@RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int pageSize,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String status) {
        return ApiResponse.success(adminChatQueryService.listSessions(page, pageSize, keyword, status));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable Long sessionId) {
        return ApiResponse.success(adminChatQueryService.getSession(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/execute")
    public ApiResponse<Map<String, Object>> execute(@PathVariable Long sessionId,
                                                    @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>(request == null ? Map.of() : request);
        payload.put("sessionId", sessionId);
        return ApiResponse.success(adminChatQueryService.execute(payload));
    }

    @PostMapping("/sessions/{sessionId}/permission-check")
    public ApiResponse<Map<String, Object>> permissionCheck(@PathVariable Long sessionId,
                                                            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>(request == null ? Map.of() : request);
        payload.put("sessionId", sessionId);
        return ApiResponse.success(adminChatQueryService.permissionCheck(payload));
    }

    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> executeDirect(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(adminChatQueryService.execute(request == null ? Map.of() : request));
    }

    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamExecute(@PathVariable Long sessionId,
                              @RequestParam(required = false) String question,
                              @RequestParam(required = false) String tableName,
                              HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = response.getWriter();
        try {
            writeThinking(writer, "SESSION_CREATED", "测试会话创建",
                    "已创建管理员测试会话，正在进入与用户端一致的查询链路",
                    Map.of("sessionId", sessionId));
            writeThinking(writer, "QUESTION_PARSED", "自然语言解析",
                    question == null || question.isBlank()
                            ? "正在读取测试指令和数据源上下文"
                            : "已接收测试指令：" + question,
                    Map.of("question", question == null ? "" : question));
            ChatBiService.ProgressListener progressListener = (eventType, title, detail, metadata) -> {
                try {
                    writeThinking(writer, eventType, title, detail, metadata);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
            Map<String, Object> result = adminChatQueryService.execute(
                    Map.of("sessionId", sessionId),
                    Map.of("progressListener", progressListener)
            );
            writeSse(writer, "result", result);
        } catch (Exception e) {
            writeSse(writer, "error", Map.of("message", rootMessage(e)));
        }
    }

    @PostMapping("/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportSession(@PathVariable Long sessionId) {
        byte[] content = adminChatQueryService.exportSessionDocx(sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("admin-chat-query-session-" + sessionId + ".docx", java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .body(content);
    }

    @PostMapping("/sessions/{sessionId}/reasoning-export")
    public ResponseEntity<byte[]> exportReasoning(@PathVariable Long sessionId) {
        byte[] content = adminChatQueryService.exportReasoningDocx(sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("admin-chat-query-reasoning-" + sessionId + ".docx", java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .body(content);
    }

    private void writeSse(PrintWriter writer, String eventName, Object payload) throws IOException {
        writer.write("event: " + eventName + "\n");
        writer.write("data: " + objectMapper.writeValueAsString(payload) + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("SSE client disconnected");
        }
    }

    private void writeThinking(PrintWriter writer,
                               String eventType,
                               String title,
                               String detail,
                               Map<String, Object> metadata) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", Objects.toString(eventType, "STEP"));
        payload.put("title", Objects.toString(title, "处理中"));
        payload.put("detail", Objects.toString(detail, ""));
        payload.put("metadata", metadata == null ? Map.of() : metadata);
        payload.put("ts", System.currentTimeMillis());
        writeSse(writer, "thinking", payload);
    }

    private String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
