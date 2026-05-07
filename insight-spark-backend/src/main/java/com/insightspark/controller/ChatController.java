package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.ChatBiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatController {

    @Autowired
    private ChatBiService chatBiService;

    @PostMapping("/ask")
    public ApiResponse<Map<String, Object>> askQuestion(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String tableName = request.get("tableName");
        if (question == null || question.isBlank()) {
            return ApiResponse.badRequest("问题不能为空");
        }
        try {
            return ApiResponse.success(chatBiService.executeChat(question, tableName));
        } catch (Exception e) {
            return ApiResponse.error("分析失败：" + e.getMessage());
        }
    }

    @GetMapping("/ask")
    public ApiResponse<Map<String, Object>> askQuestionByGet(@RequestParam(required = false) String question,
                                                              @RequestParam(required = false) String tableName) {
        if (question == null || question.isBlank()) {
            return ApiResponse.badRequest("问题不能为空");
        }
        try {
            return ApiResponse.success(chatBiService.executeChat(question, tableName));
        } catch (Exception e) {
            return ApiResponse.error("分析失败：" + e.getMessage());
        }
    }

    @PostMapping("/ask-enhanced")
    public ApiResponse<Map<String, Object>> askQuestionEnhanced(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String tableName = request.get("tableName");
        if (question == null || question.isBlank()) {
            return ApiResponse.badRequest("问题不能为空");
        }
        try {
            Map<String, Object> result = chatBiService.executeChat(question, tableName);
            enrichEnhancedResponse(result, question, tableName);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("分析失败：" + e.getMessage());
        }
    }

    private void enrichEnhancedResponse(Map<String, Object> result, String question, String tableName) {
        String engine = String.valueOf(result.getOrDefault("engine", "unknown"));
        boolean fallbackUsed = "java-fallback".equals(engine);
        boolean aiAvailable = !fallbackUsed && result.get("sql") != null;

        result.put("analysisMode", "ENHANCED_SAFE_COMPAT");
        result.put("fallbackUsed", fallbackUsed);
        result.put("aiAvailable", aiAvailable);
        result.put("responseProfile", fallbackUsed ? "RULE_BASED" : "AI_READY");
        result.put("queryQuestion", question);
        result.put("queryTableName", tableName);
        result.put("resultSource", fallbackUsed ? "RULES" : "AI_OR_RULES");
    }
}
