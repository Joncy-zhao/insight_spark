package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.AiChartRuleConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/admin/chart-rules")
@CrossOrigin
public class AiChartRuleConfigController {

    @Autowired
    private AiChartRuleConfigService service;

    @GetMapping("/rules")
    public ApiResponse<List<Map<String, Object>>> listRules(@RequestParam(required = false) String scenarioType,
                                                            @RequestParam(required = false) Boolean enabled,
                                                            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.listRules(scenarioType, enabled, keyword));
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<Map<String, Object>> getRule(@PathVariable Long id) {
        return ApiResponse.success(service.getRule(id));
    }

    @PostMapping("/rules")
    public ApiResponse<Map<String, Object>> createRule(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(service.createRule(body));
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<Map<String, Object>> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(service.updateRule(id, body));
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PatchMapping("/rules/{id}/enabled")
    public ApiResponse<Void> updateEnabled(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
            service.updateEnabled(id, enabled);
            return ApiResponse.success(null);
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        }
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        try {
            service.deleteRule(id);
            return ApiResponse.success(null);
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        }
    }

    @GetMapping("/rules/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> listRuleVersions(@PathVariable Long id) {
        return ApiResponse.success(service.listRuleVersions(id));
    }

    @PostMapping("/rules/{id}/versions/{versionId}/rollback")
    public ApiResponse<Map<String, Object>> rollbackRuleVersion(@PathVariable Long id, @PathVariable Long versionId) {
        try {
            return ApiResponse.success(service.rollbackRuleVersion(id, versionId));
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/rules/test")
    public ApiResponse<Map<String, Object>> testRule(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(service.testRecommendation(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> getPreferences() {
        return ApiResponse.success(service.getPreferences());
    }

    @PutMapping("/preferences")
    public ApiResponse<Map<String, Object>> savePreferences(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(service.savePreferences(body));
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/render-config/schema")
    public ApiResponse<Map<String, Object>> renderConfigSchema() {
        return ApiResponse.success(service.renderConfigSchema());
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<Map<String, Object>>> auditLogs(@RequestParam(required = false) String action,
                                                            @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.success(service.auditLogs(action, limit));
    }

    @GetMapping("/export")
    public ApiResponse<Map<String, Object>> exportConfig() {
        return ApiResponse.success(service.exportConfig());
    }

    @PostMapping("/import/preview")
    public ApiResponse<Map<String, Object>> previewImport(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(service.previewImport(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importConfig(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(service.importConfig(body));
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
