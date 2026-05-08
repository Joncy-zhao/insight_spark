package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCAnnouncementService;
import com.insightspark.c.service.StackCDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c/workbench")
@CrossOrigin
public class StackCWorkbenchController {

    @Autowired
    private StackCAnnouncementService announcementService;

    @Autowired
    private StackCDashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        List<Map<String, Object>> announcements = announcementService.listForCurrentUser();
        List<Map<String, Object>> dashboards = dashboardService.listVisibleForCurrentUser();
        int dashLimit = Math.min(dashboards.size(), 8);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("announcements", announcements);
        data.put("recentDashboards", dashboards.subList(0, dashLimit));
        return ApiResponse.success(data);
    }
}
