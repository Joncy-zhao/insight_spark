package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.AdminUserPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/user-permission")
@CrossOrigin
public class AdminUserPermissionController {

    @Autowired
    private AdminUserPermissionService service;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> users(@RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.listUsers(keyword));
    }

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> saveUser(@RequestBody Map<String, Object> payload) {
        try {
            return ApiResponse.success(service.saveUser(payload));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/users/{userId}/status")
    public ApiResponse<Void> updateUserStatus(@PathVariable String userId, @RequestBody Map<String, Object> payload) {
        service.updateUserStatus(userId, String.valueOf(payload.getOrDefault("status", "ACTIVE")));
        return ApiResponse.success(null);
    }

    @PostMapping("/users/{userId}/roles")
    public ApiResponse<Void> bindUserRoles(@PathVariable String userId, @RequestBody Map<String, Object> payload) {
        service.bindUserRoles(userId, (List<?>) payload.getOrDefault("roles", List.of()));
        return ApiResponse.success(null);
    }

    @GetMapping("/roles")
    public ApiResponse<List<Map<String, Object>>> roles() {
        return ApiResponse.success(service.listRoles());
    }

    @PostMapping("/roles")
    public ApiResponse<Map<String, Object>> saveRole(@RequestBody Map<String, Object> payload) {
        try {
            return ApiResponse.success(service.saveRole(payload));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/roles/{roleCode}/permissions")
    public ApiResponse<Void> saveRolePermissions(@PathVariable String roleCode, @RequestBody Map<String, Object> payload) {
        service.saveRolePermissions(roleCode, (List<?>) payload.getOrDefault("permissions", List.of()));
        return ApiResponse.success(null);
    }

    @GetMapping("/permission-catalog")
    public ApiResponse<Map<String, Object>> permissionCatalog() {
        return ApiResponse.success(service.permissionCatalog());
    }

    @GetMapping("/resources")
    public ApiResponse<Map<String, Object>> resources() {
        return ApiResponse.success(service.resources());
    }

    @PostMapping("/data-grants")
    public ApiResponse<Void> grantData(@RequestBody Map<String, Object> payload) {
        service.grantData(payload);
        return ApiResponse.success(null);
    }

    @PostMapping("/data-grants/revoke")
    public ApiResponse<Void> revokeData(@RequestBody Map<String, Object> payload) {
        service.revokeData(payload);
        return ApiResponse.success(null);
    }

    @GetMapping("/data-grants")
    public ApiResponse<List<Map<String, Object>>> dataGrants(@RequestParam(required = false) String userId) {
        return ApiResponse.success(service.listDataGrants(userId));
    }

    @GetMapping("/preview/{userId}")
    public ApiResponse<Map<String, Object>> preview(@PathVariable String userId) {
        return ApiResponse.success(service.previewUser(userId));
    }
}
