package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCAnnouncementService;
import com.insightspark.c.service.StackCDashboardService;
import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        List<Map<String, Object>> announcements = announcementService.listForCurrentUser();
        List<Map<String, Object>> dashboards = dashboardService.listVisibleForCurrentUser();
        int dashLimit = Math.min(dashboards.size(), 8);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("announcements", announcements);
        data.put("recentDashboards", dashboards.subList(0, dashLimit));
        data.put("userStats", userStats());
        data.put("userCharts", userCharts());
        data.put("adminCharts", adminCharts());
        data.put("recentActivities", recentActivities());
        return ApiResponse.success(data);
    }

    private Map<String, Object> userStats() {
        String uid = AuthContext.userId();
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayQueryCount", safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ? AND DATE(created_at) = ?", uid, today));
        stats.put("todayChartCount", safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ? AND chart_snapshot IS NOT NULL AND DATE(created_at) = ?", uid, today));
        stats.put("todayUploadCount", safeCount("SELECT COUNT(*) FROM is_data_table WHERE owner_id = ? AND DATE(created_at) = ?", uid, today));
        stats.put("personalQueryCount", safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ?", uid));
        stats.put("personalChartCount", safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ? AND chart_snapshot IS NOT NULL", uid));
        stats.put("dashboardCount", safeCount("SELECT COUNT(*) FROM is_dashboard WHERE owner_user_id = ? AND status != 'ARCHIVED'", uid));
        stats.put("uploadCount", safeCount("SELECT COUNT(*) FROM is_data_table WHERE owner_id = ? AND status = 'ACTIVE'", uid));
        stats.put("permissionCount", safeCount("SELECT COUNT(*) FROM is_dashboard_permission WHERE user_id = ? AND (expire_at IS NULL OR expire_at > NOW())", uid));
        stats.put("expiringPermissionCount", safeCount("SELECT COUNT(*) FROM is_dashboard_permission WHERE user_id = ? AND expire_at IS NOT NULL AND expire_at BETWEEN NOW() AND DATE_ADD(NOW(), INTERVAL 7 DAY)", uid));
        return stats;
    }

    private Map<String, Object> userCharts() {
        String uid = AuthContext.userId();
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("dailyTrend", dailySeries(uid));
        charts.put("moduleUsage", List.of(
                chartPoint("对话", safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ?", uid)),
                chartPoint("看板", safeCount("SELECT COUNT(*) FROM is_dashboard WHERE owner_user_id = ? AND status != 'ARCHIVED'", uid)),
                chartPoint("上传", safeCount("SELECT COUNT(*) FROM is_data_table WHERE owner_id = ? AND status = 'ACTIVE'", uid)),
                chartPoint("权限", safeCount("SELECT COUNT(*) FROM is_dashboard_permission WHERE user_id = ? AND (expire_at IS NULL OR expire_at > NOW())", uid))
        ));
        charts.put("resourceUsage", dailyResourceSeries(uid));
        charts.put("workDistribution", List.of(
                chartPoint("查询", safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ?", uid)),
                chartPoint("看板", safeCount("SELECT COUNT(*) FROM is_dashboard WHERE owner_user_id = ? AND status != 'ARCHIVED'", uid)),
                chartPoint("上传", safeCount("SELECT COUNT(*) FROM is_data_table WHERE owner_id = ? AND status = 'ACTIVE'", uid)),
                chartPoint("授权", safeCount("SELECT COUNT(*) FROM is_dashboard_permission WHERE user_id = ? AND (expire_at IS NULL OR expire_at > NOW())", uid))
        ));
        return charts;
    }

    private Map<String, Object> adminCharts() {
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("platformTrend", dailySeries(null));
        charts.put("dashboardStatus", List.of(
                chartPoint("公共", safeCount("SELECT COUNT(*) FROM is_dashboard WHERE is_public = 1 AND status != 'ARCHIVED'")),
                chartPoint("私密", safeCount("SELECT COUNT(*) FROM is_dashboard WHERE is_public = 0 AND status != 'ARCHIVED'")),
                chartPoint("启用", safeCount("SELECT COUNT(*) FROM is_dashboard WHERE status = 'ACTIVE'")),
                chartPoint("停用", safeCount("SELECT COUNT(*) FROM is_dashboard WHERE status = 'DISABLED'"))
        ));
        charts.put("datasourceHealth", List.of(
                chartPoint("启用", safeCount("SELECT COUNT(*) FROM is_official_datasource WHERE status = 'ENABLED'")),
                chartPoint("停用", safeCount("SELECT COUNT(*) FROM is_official_datasource WHERE status != 'ENABLED'")),
                chartPoint("探测正常", safeCount("SELECT COUNT(*) FROM is_official_datasource WHERE last_test_status IN ('OK', 'SUCCESS')")),
                chartPoint("探测异常", safeCount("SELECT COUNT(*) FROM is_official_datasource WHERE last_test_status IS NOT NULL AND last_test_status NOT IN ('OK', 'SUCCESS')"))
        ));
        charts.put("securityRisk", List.of(
                chartPoint("慢查询", safeCount("SELECT COUNT(*) FROM is_sql_audit_log WHERE duration_ms >= 1000")),
                chartPoint("拦截", safeCount("SELECT COUNT(*) FROM is_sql_audit_log WHERE permit = 0 OR blocked = 1 OR decision = 'BLOCK'")),
                chartPoint("敏感命中", safeCount("SELECT COUNT(*) FROM is_sql_audit_log WHERE risk_level IN ('HIGH', 'CRITICAL')")),
                chartPoint("文件上传", safeCount("SELECT COUNT(*) FROM is_data_table WHERE status = 'ACTIVE'"))
        ));
        return charts;
    }

    private List<Map<String, Object>> dailySeries(String uid) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String date = day.format(DateTimeFormatter.ISO_LOCAL_DATE);
            long queries = uid == null
                    ? safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE DATE(created_at) = ?", date)
                    : safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ? AND DATE(created_at) = ?", uid, date);
            long charts = uid == null
                    ? safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE chart_snapshot IS NOT NULL AND DATE(created_at) = ?", date)
                    : safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ? AND chart_snapshot IS NOT NULL AND DATE(created_at) = ?", uid, date);
            long uploads = uid == null
                    ? safeCount("SELECT COUNT(*) FROM is_data_table WHERE DATE(created_at) = ?", date)
                    : safeCount("SELECT COUNT(*) FROM is_data_table WHERE owner_id = ? AND DATE(created_at) = ?", uid, date);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date);
            item.put("label", day.getMonthValue() + "/" + day.getDayOfMonth());
            item.put("queries", queries);
            item.put("charts", charts);
            item.put("uploads", uploads);
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> dailyResourceSeries(String uid) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String date = day.format(DateTimeFormatter.ISO_LOCAL_DATE);
            long files = safeCount("SELECT COUNT(*) FROM is_data_table WHERE owner_id = ? AND DATE(created_at) <= ? AND status = 'ACTIVE'", uid, date);
            long queries = safeCount("SELECT COUNT(*) FROM is_chat_query_history WHERE user_id = ? AND DATE(created_at) <= ?", uid, date);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date);
            item.put("label", day.getMonthValue() + "/" + day.getDayOfMonth());
            item.put("storage", Math.min(100, files * 8));
            item.put("compute", Math.min(100, queries * 3));
            rows.add(item);
        }
        return rows;
    }

    private Map<String, Object> chartPoint(String name, long value) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("name", name);
        point.put("value", value);
        return point;
    }

    private List<Map<String, Object>> recentActivities() {
        String uid = AuthContext.userId();
        if (uid == null || uid.isBlank()) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList("""
                    SELECT 'QUERY' AS activityType, query_text AS title, created_at AS occurredAt
                    FROM is_chat_query_history
                    WHERE user_id = ?
                    ORDER BY created_at DESC
                    LIMIT 6
                    """, uid);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private long safeCount(String sql, Object... args) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
