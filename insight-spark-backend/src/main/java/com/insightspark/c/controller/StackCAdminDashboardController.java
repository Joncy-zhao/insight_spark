package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c/admin/dashboards")
@CrossOrigin
public class StackCAdminDashboardController {

    @Autowired
    private StackCDashboardService dashboardService;

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        try {
            return ApiResponse.success(dashboardService.statsForAdmin());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer isPublic,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            return ApiResponse.success(dashboardService.listForAdmin(keyword, isPublic, status, groupName, groupId, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/groups")
    public ApiResponse<List<String>> listGroups() {
        try {
            return ApiResponse.success(dashboardService.listAdminDashboardGroups());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("看板已创建", dashboardService.createForAdmin(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/duplicate")
    public ApiResponse<Map<String, Object>> duplicate(
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("已另存为公共看板", dashboardService.duplicateForAdmin(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
