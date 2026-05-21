package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.DataUploadService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
@CrossOrigin
public class DataUploadController {

    @Autowired
    private DataUploadService dataUploadService;

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(required = false) String displayName) {
        try {
            return ApiResponse.success("文件解析入库成功", dataUploadService.processFileWithTask(file, displayName));
        } catch (Exception e) {
            return ApiResponse.error("文件解析异常，请检查文件格式。错误详情: " + e.getMessage());
        }
    }

    public ApiResponse<Map<String, Object>> uploadExcel(MultipartFile file) {
        return uploadExcel(file, null);
    }

    @PostMapping("/upload-batch")
    public ApiResponse<Map<String, Object>> uploadBatch(@RequestParam("files") MultipartFile[] files,
                                                        @RequestParam(defaultValue = "SAME_HEADER") String mergeMode,
                                                        @RequestParam(required = false) String joinKey,
                                                        @RequestParam(required = false) String modelRequirement,
                                                        @RequestParam(required = false) String displayName) {
        try {
            return ApiResponse.success("多文件解析、合并与建模完成",
                    dataUploadService.processFilesWithTask(Arrays.asList(files), mergeMode, joinKey, modelRequirement, displayName));
        } catch (Exception e) {
            return ApiResponse.error("批量上传异常: " + e.getMessage());
        }
    }

    public ApiResponse<Map<String, Object>> uploadBatch(MultipartFile[] files, String mergeMode, String joinKey, String modelRequirement) {
        return uploadBatch(files, mergeMode, joinKey, modelRequirement, null);
    }

    @PostMapping("/upload-async")
    public ApiResponse<Map<String, Object>> uploadExcelAsync(@RequestParam("file") MultipartFile file,
                                                             @RequestParam(required = false) String displayName) {
        try {
            return ApiResponse.success("上传任务已创建", dataUploadService.startAsyncProcessFile(file, displayName));
        } catch (Exception e) {
            return ApiResponse.error("上传任务创建失败: " + e.getMessage());
        }
    }

    public ApiResponse<Map<String, Object>> uploadExcelAsync(MultipartFile file) {
        return uploadExcelAsync(file, null);
    }

    @PostMapping("/upload-batch-async")
    public ApiResponse<Map<String, Object>> uploadBatchAsync(@RequestParam("files") MultipartFile[] files,
                                                             @RequestParam(defaultValue = "SAME_HEADER") String mergeMode,
                                                             @RequestParam(required = false) String joinKey,
                                                             @RequestParam(required = false) String modelRequirement,
                                                             @RequestParam(required = false) String displayName) {
        try {
            return ApiResponse.success("批量上传任务已创建",
                    dataUploadService.startAsyncProcessFiles(Arrays.asList(files), mergeMode, joinKey, modelRequirement, displayName));
        } catch (Exception e) {
            return ApiResponse.error("批量上传任务创建失败: " + e.getMessage());
        }
    }

    @GetMapping("/upload-task/{taskId}")
    public ApiResponse<Map<String, Object>> uploadTask(@PathVariable String taskId) {
        try {
            return ApiResponse.success(dataUploadService.getUploadTask(taskId));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables")
    public ApiResponse<List<Map<String, Object>>> getTables() {
        return ApiResponse.success(dataUploadService.listTables());
    }

    @GetMapping("/tables/{tableName}/fields")
    public ApiResponse<List<Map<String, Object>>> getFields(@PathVariable String tableName) {
        try {
            return ApiResponse.success(dataUploadService.listFields(tableName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/fields/{columnName}")
    public ApiResponse<Map<String, Object>> updateField(@PathVariable String tableName,
                                                        @PathVariable String columnName,
                                                        @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(dataUploadService.updateFieldMeta(tableName, columnName, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/preview")
    public ApiResponse<List<Map<String, Object>>> preview(@PathVariable String tableName,
                                                          @RequestParam(defaultValue = "10") int limit) {
        try {
            return ApiResponse.success(dataUploadService.preview(tableName, limit));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/preview-page")
    public ApiResponse<Map<String, Object>> previewPage(@PathVariable String tableName,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "10") int pageSize) {
        try {
            return ApiResponse.success(dataUploadService.previewPage(tableName, page, pageSize));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/export")
    public void exportTable(@PathVariable String tableName, HttpServletResponse response) throws Exception {
        String filename = tableName + ".csv";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.getOutputStream().write(dataUploadService.exportTableCsv(tableName));
    }

    @PostMapping("/tables/{tableName}/rename")
    public ApiResponse<Map<String, Object>> rename(@PathVariable String tableName, @RequestBody Map<String, String> request) {
        try {
            return ApiResponse.success(dataUploadService.renameTable(tableName, request.get("displayName")));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/delete")
    public ApiResponse<Void> delete(@PathVariable String tableName) {
        try {
            dataUploadService.deleteTable(tableName);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/auto-apply-model")
    public ApiResponse<Map<String, Object>> autoApplyModel(@PathVariable String tableName) {
        try {
            return ApiResponse.success(dataUploadService.autoApplyBestBusinessModelForTable(tableName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/business-models")
    public ApiResponse<List<Map<String, Object>>> businessModels(@RequestParam(defaultValue = "false") boolean enterpriseOnly) {
        return ApiResponse.success(dataUploadService.listBusinessModels(enterpriseOnly));
    }

    @GetMapping("/business-models/{modelId}")
    public ApiResponse<Map<String, Object>> businessModelDetail(@PathVariable Long modelId) {
        try {
            return ApiResponse.success(dataUploadService.getBusinessModelDetail(modelId));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/templates/upload")
    public ApiResponse<Map<String, Object>> uploadTemplate(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.success("分析模板上传成功", dataUploadService.uploadTemplate(file));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/templates")
    public ApiResponse<List<Map<String, Object>>> templates() {
        return ApiResponse.success(dataUploadService.listTemplates());
    }

    @PostMapping("/business-models")
    public ApiResponse<Map<String, Object>> createBusinessModel(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(dataUploadService.createBusinessModel(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/business-model/from-template")
    public ApiResponse<Map<String, Object>> createBusinessModelFromTemplate(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(dataUploadService.createBusinessModelFromTemplate(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/business-models/{modelId}/publish")
    public ApiResponse<Void> publishBusinessModel(@PathVariable Long modelId, @RequestBody Map<String, Object> request) {
        boolean published = Boolean.parseBoolean(String.valueOf(request.getOrDefault("published", "true")));
        dataUploadService.publishBusinessModel(modelId, published);
        return ApiResponse.success(null);
    }

    @PostMapping("/business-models/{modelId}/apply")
    public ApiResponse<Map<String, Object>> applyBusinessModel(@PathVariable Long modelId, @RequestBody Map<String, String> request) {
        try {
            return ApiResponse.success(dataUploadService.applyBusinessModel(modelId, request.get("tableName")));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/business-models/{modelId}/update")
    public ApiResponse<Map<String, Object>> updateBusinessModel(@PathVariable Long modelId, @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(dataUploadService.updateBusinessModel(modelId, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/business-models/{modelId}/delete")
    public ApiResponse<Void> deleteBusinessModel(@PathVariable Long modelId) {
        try {
            dataUploadService.deleteBusinessModel(modelId);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/quality")
    public ApiResponse<Map<String, Object>> getDataQuality(@PathVariable String tableName) {
        try {
            return ApiResponse.success(dataUploadService.getDataQuality(tableName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/fields/{columnName}/statistics")
    public ApiResponse<Map<String, Object>> getFieldStatistics(@PathVariable String tableName, @PathVariable String columnName) {
        try {
            return ApiResponse.success(dataUploadService.getFieldStatistics(tableName, columnName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/fields/{columnName}/anomalies")
    public ApiResponse<List<Map<String, Object>>> detectAnomalies(@PathVariable String tableName, @PathVariable String columnName) {
        try {
            return ApiResponse.success(dataUploadService.detectAnomalies(tableName, columnName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/fields/{columnName}/distribution")
    public ApiResponse<Map<String, Object>> getFieldDistribution(@PathVariable String tableName, @PathVariable String columnName) {
        try {
            return ApiResponse.success(dataUploadService.getFieldDistribution(tableName, columnName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/fields/{columnName}/batch-replace")
    public ApiResponse<Map<String, Object>> batchReplace(@PathVariable String tableName,
                                                         @PathVariable String columnName,
                                                         @RequestBody Map<String, String> request) {
        try {
            return ApiResponse.success(dataUploadService.batchReplace(tableName, columnName, request.get("oldValue"), request.get("newValue")));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/tables/{tableName}/cleaning-strategy")
    public ApiResponse<Map<String, Object>> generateCleaningStrategy(@PathVariable String tableName) {
        try {
            return ApiResponse.success(dataUploadService.generateCleaningStrategy(tableName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/apply-cleaning-strategy")
    public ApiResponse<Map<String, Object>> applyCleaningStrategy(@PathVariable String tableName,
                                                                  @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(dataUploadService.applyCleaningStrategy(tableName, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/activate-cleaned")
    public ApiResponse<Map<String, Object>> activateCleanedTable(@PathVariable String tableName,
                                                                 @RequestBody(required = false) Map<String, Object> request) {
        try {
            boolean skipCleaning = request != null && Boolean.parseBoolean(String.valueOf(request.getOrDefault("skipCleaning", false)));
            return ApiResponse.success(dataUploadService.activateCleanedTable(tableName, skipCleaning));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/delete-rows")
    public ApiResponse<Map<String, Object>> deleteRows(@PathVariable String tableName, @RequestBody Map<String, Object> request) {
        try {
            List<Long> rowIds = ((List<?>) request.get("rowIds")).stream()
                    .map(id -> Long.parseLong(String.valueOf(id)))
                    .toList();
            return ApiResponse.success(dataUploadService.deleteRows(tableName, rowIds));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/fields/{columnName}/delete")
    public ApiResponse<Map<String, Object>> deleteColumn(@PathVariable String tableName, @PathVariable String columnName) {
        try {
            return ApiResponse.success(dataUploadService.deleteColumn(tableName, columnName));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/fields/{columnName}/transform")
    public ApiResponse<Map<String, Object>> transformData(@PathVariable String tableName,
                                                          @PathVariable String columnName,
                                                          @RequestBody Map<String, Object> request) {
        try {
            String transformType = String.valueOf(request.get("transformType"));
            Map<String, Object> options = (Map<String, Object>) request.getOrDefault("options", Map.of());
            return ApiResponse.success(dataUploadService.transformData(tableName, columnName, transformType, options));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/tables/{tableName}/rows/{rowId}/fields/{columnName}")
    public ApiResponse<Map<String, Object>> updateCell(@PathVariable String tableName,
                                                       @PathVariable Long rowId,
                                                       @PathVariable String columnName,
                                                       @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(dataUploadService.updateCell(tableName, rowId, columnName, request.get("value")));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/validate-file")
    public ApiResponse<Map<String, Object>> validateFile(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.success(dataUploadService.validateFile(file));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/check-duplicate")
    public ApiResponse<Map<String, Object>> checkDuplicate(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> validation = dataUploadService.validateFile(file);
            return ApiResponse.success(Map.of(
                    "isDuplicate", validation.get("isDuplicate"),
                    "md5", validation.get("md5"),
                    "fileName", validation.get("fileName")
            ));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
