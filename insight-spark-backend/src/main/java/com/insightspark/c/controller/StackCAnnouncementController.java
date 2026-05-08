package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCAnnouncementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c")
@CrossOrigin
public class StackCAnnouncementController {

    @Autowired
    private StackCAnnouncementService announcementService;

    @GetMapping("/announcements")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(announcementService.listForCurrentUser());
    }

    @PostMapping("/admin/announcements")
    public ApiResponse<Map<String, Object>> createAdmin(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("公告已发布", announcementService.createAnnouncement(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
