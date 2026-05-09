package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c/dashboards")
@CrossOrigin
public class StackCDashboardController {

    @Autowired
    private StackCDashboardService dashboardService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(dashboardService.listVisibleForCurrentUser());
    }

    /**
     * 当前用户可访问的所有看板中，已钉入的图表（按 chart_id 去重），供网格编辑器等选择。
     * 必须声明在 /{id} 之前，避免 path 被当成 id。
     */
    @GetMapping("/pinned-charts")
    public ApiResponse<List<Map<String, Object>>> listPinnedChartsAcrossDashboards() {
        return ApiResponse.success(dashboardService.listPinnedChartsAcrossAccessibleDashboards());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id) {
        try {
            return ApiResponse.success(dashboardService.getById(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}/components")
    public ApiResponse<List<Map<String, Object>>> listComponents(@PathVariable long id) {
        try {
            return ApiResponse.success(dashboardService.listDashboardComponents(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("看板已创建", dashboardService.create(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("看板已更新", dashboardService.update(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        try {
            dashboardService.delete(id);
            return ApiResponse.success("已删除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/share/enable")
    public ApiResponse<Map<String, Object>> enableShare(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.success("分享链接已启用", dashboardService.enableShare(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/share/disable")
    public ApiResponse<Map<String, Object>> disableShare(@PathVariable long id) {
        try {
            return ApiResponse.success("分享链接已关闭", dashboardService.disableShare(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/share")
    public ApiResponse<Map<String, Object>> getByShareToken(@RequestParam String token) {
        try {
            return ApiResponse.success(dashboardService.getByShareToken(token));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/pin-chart")
    public ApiResponse<Map<String, Object>> pinChart(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("图表已钉入看板", dashboardService.pinChart(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}/components/{componentId}")
    public ApiResponse<Map<String, Object>> removeComponent(@PathVariable long id, @PathVariable long componentId) {
        try {
            return ApiResponse.success("已从看板移除", dashboardService.removeDashboardComponent(id, componentId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
