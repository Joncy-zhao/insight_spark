package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.ChatQueryHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/chat-history")
@CrossOrigin
public class AdminChatHistoryController {

    @Autowired
    private ChatQueryHistoryService chatQueryHistoryService;

    @GetMapping
    public ApiResponse<Map<String, Object>> listHistory(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String userId,
                                                        @RequestParam(required = false) String tableName,
                                                        @RequestParam(required = false) String sourceType,
                                                        @RequestParam(required = false) String chartType,
                                                        @RequestParam(required = false) String riskLevel,
                                                        @RequestParam(required = false) String executionStatus,
                                                        @RequestParam(required = false) String modelType,
                                                        @RequestParam(required = false) String dateFrom,
                                                        @RequestParam(required = false) String dateTo,
                                                        @RequestParam(required = false) Boolean cacheHit,
                                                        @RequestParam(required = false) Boolean slowQuery,
                                                        @RequestParam(required = false) String sortDirection) {
        return ApiResponse.success(chatQueryHistoryService.listAdminHistoryPage(
                page, pageSize, keyword, userId, tableName, sourceType, chartType, riskLevel,
                executionStatus, modelType, dateFrom, dateTo, cacheHit, slowQuery, sortDirection
        ));
    }

    @GetMapping("/{historyId}")
    public ApiResponse<Map<String, Object>> getHistoryDetail(@PathVariable Long historyId) {
        return ApiResponse.success(chatQueryHistoryService.getAdminHistoryDetail(historyId));
    }

    @GetMapping("/{historyId}/context")
    public ApiResponse<Map<String, Object>> getHistoryContext(@PathVariable Long historyId) {
        return ApiResponse.success(chatQueryHistoryService.getAdminHistoryContext(historyId));
    }

    @GetMapping("/analytics")
    public ApiResponse<Map<String, Object>> analytics(@RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String userId,
                                                      @RequestParam(required = false) String tableName,
                                                      @RequestParam(required = false) String sourceType,
                                                      @RequestParam(required = false) String chartType,
                                                      @RequestParam(required = false) String riskLevel,
                                                      @RequestParam(required = false) String executionStatus,
                                                      @RequestParam(required = false) String modelType,
                                                      @RequestParam(required = false) String dateFrom,
                                                      @RequestParam(required = false) String dateTo,
                                                      @RequestParam(required = false) Boolean cacheHit,
                                                      @RequestParam(required = false) Boolean slowQuery) {
        return ApiResponse.success(chatQueryHistoryService.adminHistoryAnalytics(
                keyword, userId, tableName, sourceType, chartType, riskLevel,
                executionStatus, modelType, dateFrom, dateTo, cacheHit, slowQuery
        ));
    }

    @PostMapping("/{historyId}/rerun")
    public ApiResponse<Map<String, Object>> rerunHistory(@PathVariable Long historyId) {
        return ApiResponse.success(chatQueryHistoryService.rerunHistoryAsAdmin(historyId));
    }

    @DeleteMapping("/batch")
    public ApiResponse<Map<String, Object>> deleteBatch(@RequestBody(required = false) Map<String, Object> request) {
        List<Long> ids = new ArrayList<>();
        String reason = null;
        Object raw = request == null ? null : request.get("ids");
        if (request != null && request.get("reason") != null) {
            reason = String.valueOf(request.get("reason"));
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number) {
                    ids.add(number.longValue());
                } else if (item != null) {
                    try {
                        ids.add(Long.parseLong(String.valueOf(item).trim()));
                    } catch (NumberFormatException ignored) {
                        // skip invalid ids
                    }
                }
            }
        }
        return ApiResponse.success(chatQueryHistoryService.deleteAdminHistoryBatch(ids, reason));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportHistory(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String userId,
                                                @RequestParam(required = false) String tableName,
                                                @RequestParam(required = false) String sourceType,
                                                @RequestParam(required = false) String chartType,
                                                @RequestParam(required = false) String riskLevel,
                                                @RequestParam(required = false) String executionStatus,
                                                @RequestParam(required = false) String modelType,
                                                @RequestParam(required = false) String dateFrom,
                                                @RequestParam(required = false) String dateTo,
                                                @RequestParam(required = false) Boolean cacheHit,
                                                @RequestParam(required = false) Boolean slowQuery,
                                                @RequestParam(defaultValue = "1000") int limit) {
        byte[] content = chatQueryHistoryService.exportAdminHistoryExcel(
                keyword, userId, tableName, sourceType, chartType, riskLevel,
                executionStatus, modelType, dateFrom, dateTo, cacheHit, slowQuery, limit
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("admin-chat-history.xlsx", java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(content);
    }
}
