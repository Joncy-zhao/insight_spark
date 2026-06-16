package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCAnnotationService;
import com.insightspark.c.websocket.CollabWebSocketBroadcaster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/c")
@CrossOrigin
public class StackCAnnotationController {

    @Autowired
    private StackCAnnotationService annotationService;

    @Autowired
    private CollabWebSocketBroadcaster collabWebSocketBroadcaster;

    @GetMapping("/annotations")
    public ApiResponse<List<Map<String, Object>>> listAnnotations(
            @RequestParam String targetType,
            @RequestParam long targetId) {
        return ApiResponse.success(annotationService.listAnnotations(targetType, targetId));
    }

    @GetMapping("/annotations/by-dashboard/{dashboardId}")
    public ApiResponse<List<Map<String, Object>>> listAnnotationsByDashboard(
            @PathVariable long dashboardId,
            @RequestParam(defaultValue = "false") boolean includeHidden) {
        return ApiResponse.success(annotationService.listAnnotationsForDashboard(dashboardId, includeHidden));
    }

    @PostMapping("/annotations")
    public ApiResponse<Map<String, Object>> createAnnotation(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> created = annotationService.createAnnotation(body);
            long dashboardId = resolveDashboardBroadcastId(created);
            if (dashboardId > 0) {
                collabWebSocketBroadcaster.broadcastAnnotationCreated(dashboardId, created);
            }
            return ApiResponse.success("批注已保存", created);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/annotations/{id}")
    public ApiResponse<Void> deleteAnnotation(@PathVariable long id) {
        try {
            Map<String, Object> meta = annotationService.peekAnnotationMeta(id);
            long dashboardId = resolveDashboardBroadcastId(meta);
            annotationService.deleteAnnotation(id);
            if (dashboardId > 0) {
                collabWebSocketBroadcaster.broadcastAnnotationDeleted(dashboardId, id);
            }
            return ApiResponse.success("已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/annotations/{id}")
    public ApiResponse<Map<String, Object>> updateAnnotation(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> updated = annotationService.updateAnnotation(id, body);
            long dashboardId = resolveDashboardBroadcastId(updated);
            if (dashboardId > 0) {
                collabWebSocketBroadcaster.broadcastAnnotationUpdated(dashboardId, updated);
            }
            return ApiResponse.success("批注已更新", updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PatchMapping("/annotations/{id}/hidden")
    @PutMapping("/annotations/{id}/hidden")
    public ApiResponse<Map<String, Object>> setAnnotationHidden(
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        try {
            boolean hidden = Boolean.TRUE.equals(body.get("hidden"))
                    || "true".equalsIgnoreCase(String.valueOf(body.getOrDefault("hidden", false)));
            Map<String, Object> updated = annotationService.setAnnotationHidden(id, hidden);
            long dashboardId = resolveDashboardBroadcastId(updated);
            if (dashboardId > 0) {
                collabWebSocketBroadcaster.broadcastAnnotationUpdated(dashboardId, updated);
            }
            return ApiResponse.success(hidden ? "批注已隐藏" : "批注已显示", updated);
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
            Map<String, Object> created = annotationService.createComment(body);
            String targetType = Objects.toString(body.get("targetType"), "");
            long targetId = Long.parseLong(String.valueOf(body.get("targetId")));
            collabWebSocketBroadcaster.broadcastCommentCreated(targetType, targetId, created);
            return ApiResponse.success("评论已发布", created);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/comments/{id}")
    public ApiResponse<Void> deleteComment(@PathVariable long id) {
        try {
            Map<String, Object> meta = annotationService.peekCommentMeta(id);
            annotationService.deleteComment(id);
            collabWebSocketBroadcaster.broadcastCommentDeleted(
                    Objects.toString(meta.get("targetType")),
                    Long.parseLong(String.valueOf(meta.get("targetId"))),
                    id);
            return ApiResponse.success("已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    private long resolveDashboardBroadcastId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return 0L;
        }
        Object dash = row.get("dashboardId");
        if (dash != null) {
            try {
                long id = Long.parseLong(String.valueOf(dash));
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if ("DASHBOARD".equalsIgnoreCase(Objects.toString(row.get("targetType"), ""))) {
            try {
                return Long.parseLong(String.valueOf(row.get("targetId")));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}
