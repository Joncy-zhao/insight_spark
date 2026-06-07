package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCCollabService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c/collab")
@CrossOrigin
public class StackCCollabController {

    @Autowired
    private StackCCollabService collabService;

    @GetMapping("/dashboards/{id}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable long id) {
        try {
            return ApiResponse.success(collabService.getSummary(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/dashboards/{id}/follow")
    public ApiResponse<Map<String, Object>> followStatus(@PathVariable long id) {
        return ApiResponse.success(Map.of("following", collabService.isFollowing(id)));
    }

    @PostMapping("/dashboards/{id}/follow")
    public ApiResponse<Map<String, Object>> follow(@PathVariable long id) {
        try {
            return ApiResponse.success("已关注看板", collabService.follow(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/dashboards/{id}/follow")
    public ApiResponse<Map<String, Object>> unfollow(@PathVariable long id) {
        return ApiResponse.success("已取消关注", collabService.unfollow(id));
    }

    @GetMapping("/mention-candidates")
    public ApiResponse<List<Map<String, Object>>> mentionCandidates(@RequestParam(required = false) String keyword) {
        return ApiResponse.success(collabService.listMentionCandidates(keyword));
    }

    @GetMapping("/dashboards/{id}/team-permissions")
    public ApiResponse<List<Map<String, Object>>> teamPermissions(@PathVariable long id) {
        try {
            return ApiResponse.success(collabService.listTeamPermissions(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/dashboards/{id}/team-permissions")
    public ApiResponse<Map<String, Object>> grantTeamPermission(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("已分配协作权限", collabService.grantTeamPermission(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/dashboards/{id}/team-permissions")
    public ApiResponse<Void> revokeTeamPermission(
            @PathVariable long id,
            @RequestParam String userId,
            @RequestParam(defaultValue = "READ") String permissionType) {
        try {
            collabService.revokeTeamPermission(id, userId, permissionType);
            return ApiResponse.success("已撤销", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/dashboards/{id}/report")
    public ResponseEntity<byte[]> exportReport(@PathVariable long id) {
        try {
            String markdown = collabService.buildMarkdownReport(id);
            byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dashboard-" + id + "-collab.md\"")
                    .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }
}
