package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.PermissionService;
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
@RequestMapping("/api/permission")
@CrossOrigin
public class PermissionController {

    @Autowired
    private PermissionService permissionService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(permissionService.getPermissionOverview());
    }

    @GetMapping("/accessible-tables")
    public ApiResponse<List<Map<String, Object>>> accessibleTables() {
        return ApiResponse.success(permissionService.listAccessibleTables());
    }

    @GetMapping("/accessible-official-tables")
    public ApiResponse<List<Map<String, Object>>> accessibleOfficialTables() {
        return ApiResponse.success(permissionService.listAccessibleOfficialTables());
    }

    @GetMapping("/requestable-tables")
    public ApiResponse<List<Map<String, Object>>> requestableTables() {
        return ApiResponse.success(permissionService.listRequestableTables());
    }

    @GetMapping("/sensitive-fields")
    public ApiResponse<List<Map<String, Object>>> sensitiveFields() {
        return ApiResponse.success(permissionService.listSensitiveFieldPermissions());
    }

    @GetMapping("/my-requests")
    public ApiResponse<List<Map<String, Object>>> myRequests() {
        return ApiResponse.success(permissionService.listMyRequests());
    }

    @PostMapping("/requests")
    public ApiResponse<Map<String, Object>> submitRequest(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success("权限申请已提交", permissionService.submitRequest(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/admin/requests")
    public ApiResponse<List<Map<String, Object>>> allRequests(@RequestParam(required = false) String status) {
        return ApiResponse.success(permissionService.listAllRequests(status));
    }

    @PostMapping("/admin/requests/{requestId}/review")
    public ApiResponse<Void> review(@PathVariable Long requestId, @RequestBody Map<String, String> request) {
        try {
            permissionService.reviewRequest(requestId, request.get("action"), request.get("comment"));
            return ApiResponse.success("审批完成", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
