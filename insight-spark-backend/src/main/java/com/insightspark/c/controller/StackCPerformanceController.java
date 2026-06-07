package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCPerformanceService;
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
@RequestMapping("/api/c/admin/performance")
@CrossOrigin
public class StackCPerformanceController {

    @Autowired
    private StackCPerformanceService performanceService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(performanceService.overview());
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.success(performanceService.getGovernanceConfig());
    }

    @GetMapping("/bottleneck-report")
    public ApiResponse<Map<String, Object>> bottleneckReport() {
        return ApiResponse.success(performanceService.bottleneckReport());
    }

    @GetMapping("/slow-queries")
    public ApiResponse<List<Map<String, Object>>> slowQueries(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(performanceService.listSlowQueries(limit));
    }

    @GetMapping("/cache-entries")
    public ApiResponse<List<Map<String, Object>>> cacheEntries(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(performanceService.listCacheEntries(limit));
    }

    @PostMapping("/cache/clear")
    public ApiResponse<Map<String, Object>> clearCache() {
        int cleared = performanceService.clearSemanticCache();
        return ApiResponse.success("已清理语义缓存", Map.of("cleared", cleared));
    }

    @PostMapping("/slow-queries/{auditLogId}/intervention")
    public ApiResponse<Map<String, Object>> intervention(@PathVariable long auditLogId,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.success("已记录处置",
                    performanceService.recordIntervention(auditLogId, body == null ? Map.of() : body));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/slow-queries/{auditLogId}/terminate")
    public ApiResponse<Map<String, Object>> terminate(@PathVariable long auditLogId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.success("已终止并记录",
                    performanceService.terminateSlowQuery(auditLogId, body == null ? Map.of() : body));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/batch-tasks")
    public ApiResponse<List<Map<String, Object>>> batchTasks(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(performanceService.listBatchTasks(limit));
    }

    @GetMapping("/interventions")
    public ApiResponse<List<Map<String, Object>>> interventions(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(performanceService.listInterventions(limit));
    }

    @GetMapping("/alert-config")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> alertConfig() {
        Object alert = performanceService.getGovernanceConfig().get("alert");
        return ApiResponse.success(alert instanceof Map ? (Map<String, Object>) alert : Map.of());
    }

    @PutMapping("/alert-config")
    public ApiResponse<Void> saveAlertConfig(@RequestBody Map<String, Object> body) {
        try {
            performanceService.saveAlertConfig(body);
            return ApiResponse.success("已保存", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/slow-query-governance")
    public ApiResponse<Void> saveSlowQueryGovernance(@RequestBody Map<String, Object> body) {
        try {
            performanceService.saveSlowQueryGovernance(body);
            return ApiResponse.success("已保存", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/cache-config")
    public ApiResponse<Void> saveCacheConfig(@RequestBody Map<String, Object> body) {
        try {
            performanceService.saveCacheConfig(body);
            return ApiResponse.success("已保存", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/batch-config")
    public ApiResponse<Void> saveBatchConfig(@RequestBody Map<String, Object> body) {
        try {
            performanceService.saveBatchConfig(body);
            return ApiResponse.success("已保存", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/db-pressure-config")
    public ApiResponse<Void> saveDbPressureConfig(@RequestBody Map<String, Object> body) {
        try {
            performanceService.saveDbPressureConfig(body);
            return ApiResponse.success("已保存", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/resource-config")
    public ApiResponse<Void> saveResourceConfig(@RequestBody Map<String, Object> body) {
        try {
            performanceService.saveResourceConfig(body);
            return ApiResponse.success("已保存", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
