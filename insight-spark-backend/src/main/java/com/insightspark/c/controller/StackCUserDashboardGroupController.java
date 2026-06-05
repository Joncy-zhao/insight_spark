package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCDashboardGroupService;
import com.insightspark.core.auth.AuthContext;
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
@RequestMapping("/api/c/dashboard-groups")
@CrossOrigin
public class StackCUserDashboardGroupController {

    @Autowired
    private StackCDashboardGroupService groupService;

    @GetMapping("/tree")
    public ApiResponse<List<Map<String, Object>>> tree() {
        try {
            return ApiResponse.success(groupService.listUserTree(AuthContext.userId()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("分组已创建", groupService.createUser(body, AuthContext.userId()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("分组已更新", groupService.updateUser(id, body, AuthContext.userId()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        try {
            groupService.deleteUser(id, AuthContext.userId());
            return ApiResponse.success("分组已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
