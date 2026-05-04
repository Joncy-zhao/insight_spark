package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.KnowledgeDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/docs")
@CrossOrigin
public class KnowledgeDocumentController {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.success("知识文档上传并切片成功", knowledgeDocumentService.upload(file));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(knowledgeDocumentService.listDocs());
    }

    @PostMapping("/{id}/index")
    public ApiResponse<Map<String, Object>> index(@PathVariable Long id) {
        return ApiResponse.success(knowledgeDocumentService.index(id));
    }
}
