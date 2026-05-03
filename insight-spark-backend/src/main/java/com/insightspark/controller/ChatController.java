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
}
