package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCAnnotationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/c")
@CrossOrigin
public class StackCAnnotationController {

    @Autowired
    private StackCAnnotationService annotationService;

    @GetMapping("/annotations")
    public ApiResponse<List<Map<String, Object>>> listAnnotations(
            @RequestParam String targetType,
            @RequestParam long targetId) {
        return ApiResponse.success(annotationService.listAnnotations(targetType, targetId));
    }

    @PostMapping("/annotations")
    public ApiResponse<Map<String, Object>> createAnnotation(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("批注已保存", annotationService.createAnnotation(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/annotations/{id}")
    public ApiResponse<Void> deleteAnnotation(@PathVariable long id) {
        try {
            annotationService.deleteAnnotation(id);
            return ApiResponse.success("已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/comments")
    public ApiResponse<List<Map<String, Object>>> listComments(
            @RequestParam String targetType,
            @RequestParam long targetId) {
        return ApiResponse.success(annotationService.listComments(targetType, targetId));
    }

    @PostMapping("/comments")
    public ApiResponse<Map<String, Object>> createComment(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("评论已发布", annotationService.createComment(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/comments/{id}")
    public ApiResponse<Void> deleteComment(@PathVariable long id) {
        try {
            annotationService.deleteComment(id);
            return ApiResponse.success("已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
