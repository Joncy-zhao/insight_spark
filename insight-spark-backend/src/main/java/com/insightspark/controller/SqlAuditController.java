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
                                                              @RequestParam(required = false) String userId,
                                                              @RequestParam(required = false) String tableName,
                                                              @RequestParam(required = false) Boolean cacheHit,
                                                              @RequestParam(required = false) Boolean slowQuery,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(sqlAuditService.listLogs(riskLevel, executeStatus, userId, tableName,
                cacheHit, slowQuery, keyword, limit));
    }

    @GetMapping("/rules")
    public ApiResponse<List<Map<String, Object>>> listRules() {
        return ApiResponse.success(sqlAuditService.listRules());
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.success(sqlAuditService.stats());
    }

    @GetMapping("/cache/overview")
    public ApiResponse<Map<String, Object>> cacheOverview() {
        return ApiResponse.success(sqlAuditService.cacheOverview());
    }

    @GetMapping("/cache/audits")
    public ApiResponse<List<Map<String, Object>>> listCacheAudits(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(sqlAuditService.listCacheAudits(limit));
    }

    @PostMapping("/cache/{cacheKey}/quarantine")
    public ApiResponse<Void> quarantineCache(@PathVariable String cacheKey, @RequestBody Map<String, Object> request) {
        sqlAuditService.quarantineCache(cacheKey, String.valueOf(request.getOrDefault("reason", "管理员隔离违规缓存")));
        return ApiResponse.success(null);
    }

    @GetMapping("/sensitive-rules")
    public ApiResponse<List<Map<String, Object>>> listSensitiveRules() {
        return ApiResponse.success(sqlAuditService.listSensitiveRules());
    }

    @PostMapping("/sensitive-rules")
    public ApiResponse<Map<String, Object>> saveSensitiveRule(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(sqlAuditService.saveSensitiveRule(request));
    }

    @PostMapping("/sensitive-rules/{id}/status")
    public ApiResponse<Void> updateSensitiveRuleStatus(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(request.getOrDefault("enabled", "true")));
        sqlAuditService.updateSensitiveRuleStatus(id, enabled);
        return ApiResponse.success(null);
    }

    @PostMapping("/sensitive-rules/{id}/delete")
    public ApiResponse<Void> deleteSensitiveRule(@PathVariable Long id) {
        sqlAuditService.deleteSensitiveRule(id);
        return ApiResponse.success(null);
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

    @PostMapping("/sql-logs/{id}/review")
    public ApiResponse<Void> reviewLog(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        sqlAuditService.reviewLog(id, request);
        return ApiResponse.success(null);
    }

    @GetMapping("/data-row-policies")
    public ApiResponse<List<Map<String, Object>>> listDataRowPolicies(@RequestParam(required = false) String tableName) {
        return ApiResponse.success(sqlAuditService.listDataRowPolicies(tableName));
    }

    @PostMapping("/data-row-policies")
    public ApiResponse<Map<String, Object>> saveDataRowPolicy(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(sqlAuditService.saveDataRowPolicy(request));
    }

    @PostMapping("/data-row-policies/{id}/delete")
    public ApiResponse<Void> deleteDataRowPolicy(@PathVariable Long id) {
        sqlAuditService.deleteDataRowPolicy(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/sql-logs/export")
    public ResponseEntity<byte[]> exportSqlLogs(@RequestParam(required = false) String riskLevel,
                                                @RequestParam(required = false) String executeStatus,
                                                @RequestParam(defaultValue = "500") int limit) {
        byte[] content = sqlAuditService.exportLogsExcel(riskLevel, executeStatus, limit);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("sql-audit-logs.xlsx", java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(content);
    }
}
