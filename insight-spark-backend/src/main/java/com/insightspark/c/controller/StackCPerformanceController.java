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

    @GetMapping("/slow-queries")
    public ApiResponse<List<Map<String, Object>>> slowQueries(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(performanceService.listSlowQueries(limit));
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

    @GetMapping("/batch-tasks")
    public ApiResponse<List<Map<String, Object>>> batchTasks(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(performanceService.listBatchTasks(limit));
    }

    @GetMapping("/interventions")
    public ApiResponse<List<Map<String, Object>>> interventions(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(performanceService.listInterventions(limit));
    }

    @GetMapping("/alert-config")
    public ApiResponse<Map<String, Object>> alertConfig() {
        return ApiResponse.success(performanceService.getAlertConfig());
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
}
