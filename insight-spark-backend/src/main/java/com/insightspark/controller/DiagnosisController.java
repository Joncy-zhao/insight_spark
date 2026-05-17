package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.DiagnosisService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnosis")
@CrossOrigin
public class DiagnosisController {

    @Autowired
    private DiagnosisService diagnosisService;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success("诊断报告已生成", diagnosisService.runDiagnosis(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/reports")
    public ApiResponse<List<Map<String, Object>>> listReports() {
        return ApiResponse.success(diagnosisService.listReports());
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<Map<String, Object>> getReport(@PathVariable Long reportId) {
        try {
            return ApiResponse.success(diagnosisService.getReport(reportId));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/reports/{reportId}/delete")
    public ApiResponse<Map<String, Object>> deleteReport(@PathVariable Long reportId) {
        try {
            return ApiResponse.success("诊断报告已删除", diagnosisService.deleteReport(reportId));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/reports/delete")
    public ApiResponse<Map<String, Object>> deleteReports(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success("诊断报告已删除", diagnosisService.deleteReports(request.get("ids")));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    public ResponseEntity<byte[]> exportReport(@PathVariable Long reportId,
                                               @org.springframework.web.bind.annotation.RequestParam(defaultValue = "markdown") String format) {
        return buildExportResponse(diagnosisService.exportReport(reportId, format));
    }

    @GetMapping("/reports/{reportId}/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable Long reportId,
                                               @org.springframework.web.bind.annotation.RequestParam(defaultValue = "markdown") String format,
                                               @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean includeSnapshots,
                                               @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean includeReasoningLogs,
                                               @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean enablePdfEncryption) {
        return buildExportResponse(diagnosisService.exportReport(reportId, format, Map.of(
                "includeSnapshots", includeSnapshots,
                "includeReasoningLogs", includeReasoningLogs,
                "enablePdfEncryption", enablePdfEncryption
        )));
    }

    private ResponseEntity<byte[]> buildExportResponse(DiagnosisService.ExportFile exportFile) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(exportFile.filename(), java.nio.charset.StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CONTENT_TYPE, exportFile.contentType())
                .body(exportFile.content());
    }
}
