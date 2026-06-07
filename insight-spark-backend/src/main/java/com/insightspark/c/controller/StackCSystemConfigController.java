package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCCollabService;
import com.insightspark.c.service.StackCSystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c/admin/system-config")
@CrossOrigin
public class StackCSystemConfigController {

    @Autowired
    private StackCSystemConfigService systemConfigService;

    @Autowired
    private StackCCollabService collabService;

    @GetMapping("/user-candidates")
    public ApiResponse<List<Map<String, Object>>> userCandidates(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(collabService.listMentionCandidates(keyword));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(systemConfigService.listAll());
    }

    @GetMapping("/schema")
    public ApiResponse<Map<String, Object>> schema() {
        return ApiResponse.success(systemConfigService.listGroupedSchema());
    }

    @PutMapping
    public ApiResponse<Void> upsert(@RequestBody Map<String, Object> body) {
        try {
            systemConfigService.upsert(body);
            return ApiResponse.success("配置已保存", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/batch")
    public ApiResponse<Void> batchUpsert(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            systemConfigService.batchUpsert(items);
            return ApiResponse.success("配置已批量保存", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/modules/{moduleId}/reset")
    public ApiResponse<Void> resetModule(@PathVariable String moduleId) {
        try {
            systemConfigService.resetModule(moduleId);
            return ApiResponse.success("模块已恢复默认", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
