package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCDashboardGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c/admin/dashboard-groups")
@CrossOrigin
public class StackCAdminDashboardGroupController {

    @Autowired
    private StackCDashboardGroupService groupService;

    @GetMapping("/tree")
    public ApiResponse<List<Map<String, Object>>> tree() {
        try {
            return ApiResponse.success(groupService.listAdminTree());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("分组已创建", groupService.createAdmin(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("分组已更新", groupService.updateAdmin(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        try {
            groupService.deleteAdmin(id);
            return ApiResponse.success("分组已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
