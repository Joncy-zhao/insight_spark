package com.insightspark.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.common.ApiResponse;
import com.insightspark.core.auth.AuthContext;
import com.insightspark.service.DiagnosisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnosis")
@CrossOrigin
public class DiagnosisController {

    @Autowired
    private DiagnosisService diagnosisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success("\u8bca\u65ad\u62a5\u544a\u5df2\u751f\u6210", diagnosisService.runDiagnosis(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping(value = "/run-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> runStream(@RequestBody Map<String, Object> request) {
        AuthContext.UserPrincipal principal = AuthContext.get();
        StreamingResponseBody stream = outputStream -> {
            try {
                AuthContext.set(principal);
                sendStreamEvent(outputStream, "progress", Map.of(
                        "percentage", 3,
                        "step", "\u4efb\u52a1\u63a5\u6536",
                        "log", Map.of(
                                "step", 0,
                                "title", "\u4efb\u52a1\u63a5\u6536",
                                "status", "running",
                                "detail", "\u8bca\u65ad\u62a5\u544a\u751f\u6210\u8bf7\u6c42\u5df2\u8fdb\u5165\u540e\u7aef\u961f\u5217\u3002"
                        )
                ));
                Map<String, Object> result = diagnosisService.runDiagnosis(request,
                        progress -> sendStreamEvent(outputStream, "progress", progress));
                Map<String, Object> completion = Map.of(
                        "percentage", 100,
                        "step", "\u62a5\u544a\u751f\u6210",
                        "message", "\u8bca\u65ad\u62a5\u544a\u5df2\u751f\u6210",
                        "result", result
                );
                sendStreamEvent(outputStream, "progress", Map.of(
                        "percentage", 100,
                        "step", "\u62a5\u544a\u751f\u6210",
                        "log", Map.of(
                                "step", 9,
                                "title", "\u62a5\u544a\u751f\u6210",
                                "status", "completed",
                                "detail", "\u8bca\u65ad\u62a5\u544a\u5df2\u751f\u6210\uff0c\u51c6\u5907\u8fd4\u56de\u524d\u7aef\u3002"
                        )
                ));
                sendStreamEvent(outputStream, "result", result);
                sendStreamEvent(outputStream, "complete", completion);
                sendStreamEvent(outputStream, "completed", completion);
                sendStreamEvent(outputStream, "done", completion);
                sendStreamEvent(outputStream, "FINISHED", completion);
            } catch (Exception e) {
                sendStreamEvent(outputStream, "error", Map.of(
                        "message", e.getMessage() == null ? "\u8bca\u65ad\u62a5\u544a\u751f\u6210\u5931\u8d25" : e.getMessage()
                ));
            } finally {
                AuthContext.clear();
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    private void sendStreamEvent(OutputStream outputStream, String event, Object data) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (data instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    payload.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else {
                payload.put("value", data);
            }
            String frame = "event: " + event + "\n"
                    + "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
            outputStream.write(frame.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception ignored) {
            // 客户端断开时不再继续写入，主流程由调用方捕获。
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
            return ApiResponse.success("\u8bca\u65ad\u62a5\u544a\u5df2\u5220\u9664", diagnosisService.deleteReport(reportId));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/reports/delete")
    public ApiResponse<Map<String, Object>> deleteReports(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success("\u8bca\u65ad\u62a5\u544a\u5df2\u5220\u9664", diagnosisService.deleteReports(request.get("ids")));
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

    @PostMapping("/encrypt-pdf")
    public ResponseEntity<byte[]> encryptPdf(@RequestParam("file") MultipartFile file,
                                             @RequestParam(required = false) String filename) {
        try {
            return buildExportResponse(diagnosisService.encryptVisualPdf(file.getBytes(), filename));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .header(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .body(("{\"code\":400,\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
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
