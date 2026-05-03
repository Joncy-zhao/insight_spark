package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.SqlAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/audit")
@CrossOrigin
public class SqlAuditController {

    @Autowired
    private SqlAuditService sqlAuditService;

    @GetMapping("/sql-logs")
    public ApiResponse<List<Map<String, Object>>> listSqlLogs(@RequestParam(required = false) String riskLevel,
                                                              @RequestParam(required = false) String executeStatus,
                                                              @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(sqlAuditService.listLogs(riskLevel, executeStatus, limit));
    }

    @GetMapping("/rules")
    public ApiResponse<List<Map<String, Object>>> listRules() {
        return ApiResponse.success(sqlAuditService.listRules());
    }

    @PostMapping("/rules/{ruleCode}/status")
    public ApiResponse<Void> updateRuleStatus(@PathVariable String ruleCode, @RequestBody Map<String, Object> request) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(request.getOrDefault("enabled", "true")));
        sqlAuditService.updateRuleStatus(ruleCode, enabled);
        return ApiResponse.success(null);
    }

    @PostMapping("/rules/{ruleCode}/config")
    public ApiResponse<Void> updateRuleConfig(@PathVariable String ruleCode, @RequestBody Map<String, Object> request) {
        sqlAuditService.updateRuleConfig(ruleCode, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/submit")
    public ApiResponse<Map<String, Object>> submitAudit(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(sqlAuditService.submitSqlAudit(request));
    }

    @GetMapping("/sql-logs/export")
    public ResponseEntity<byte[]> exportSqlLogs(@RequestParam(required = false) String riskLevel,
                                                @RequestParam(required = false) String executeStatus,
                                                @RequestParam(defaultValue = "500") int limit) {
        byte[] content = sqlAuditService.exportLogsCsv(riskLevel, executeStatus, limit);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("sql-audit-logs.csv", java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(content);
    }
}
