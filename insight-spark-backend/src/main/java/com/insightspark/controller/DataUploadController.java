package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.DataUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

@RestController
@RequestMapping("/api/data")
@CrossOrigin // 允许前端跨域访问
public class DataUploadController {

    @Autowired
    private DataUploadService dataUploadService;

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.success("文件解析入库成功", dataUploadService.processFile(file));
        } catch (Exception e) {
            return ApiResponse.error("文件解析异常，请检查文件格式。错误详情: " + e.getMessage());
        }
    }

    @PostMapping("/upload-batch")
    public ApiResponse<Map<String, Object>> uploadBatch(@RequestParam("files") MultipartFile[] files,
                                                        @RequestParam(defaultValue = "SAME_HEADER") String mergeMode,
                                                        @RequestParam(required = false) String joinKey,
                                                        @RequestParam(required = false) String modelRequirement) {
        try {
            return ApiResponse.success("多文件解析、合并与建模完成",
                    dataUploadService.processFiles(Arrays.asList(files), mergeMode, joinKey, modelRequirement));
        } catch (Exception e) {
            return ApiResponse.error("批量上传异常：" + e.getMessage());
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

    @GetMapping("/business-models")
    public ApiResponse<List<Map<String, Object>>> businessModels(@RequestParam(defaultValue = "false") boolean enterpriseOnly) {
        return ApiResponse.success(dataUploadService.listBusinessModels(enterpriseOnly));
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
}
