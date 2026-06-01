package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.AdvancedAnalysisService;
import com.insightspark.service.PythonAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/advanced-analysis")
@CrossOrigin
public class AdvancedAnalysisController {

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private AdvancedAnalysisService advancedAnalysisService;

    @PostMapping("/parse")
    public ApiResponse<Map<String, Object>> parseIntent(@RequestBody Map<String, Object> request) {
        String question = text(request.get("question"));
        if (question.isBlank()) {
            return ApiResponse.badRequest("分析问题不能为空");
        }
        String tableName = text(request.get("tableName"));
        Map<String, Object> context = asMap(request.get("context"));
        Map<String, Object> result = pythonAiService.parseAdvancedAnalysisIntent(question, tableName, context)
                .orElseGet(() -> fallbackParse(question));
        return ApiResponse.success(result);
    }

    @PostMapping("/field-meta")
    public ApiResponse<Map<String, Object>> fieldMeta(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.fieldMeta(request));
    }

    @PostMapping("/forecast")
    public ApiResponse<Map<String, Object>> forecast(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.forecast(request));
    }

    @PostMapping("/forecast-series")
    public ApiResponse<Map<String, Object>> forecastFromSeries(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.forecastFromSeries(request));
    }

    @PostMapping("/what-if")
    public ApiResponse<Map<String, Object>> whatIf(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.whatIf(request));
    }

    @PostMapping("/alert-rules")
    public ApiResponse<Object> saveAlertRule(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "save"));
        if ("list".equalsIgnoreCase(action)) {
            return ApiResponse.success(advancedAnalysisService.listAlertRules());
        }
        if ("detail".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警规则 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.getAlertRule(id));
        }
        if ("update".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警规则 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.updateAlertRule(id, request));
        }
        return ApiResponse.success(advancedAnalysisService.saveAlertRule(request));
    }

    @PostMapping("/alert-rules/status")
    public ApiResponse<Map<String, Object>> updateAlertRuleStatus(@RequestBody Map<String, Object> request) {
        long id = parseLong(request.get("id"));
        if (id <= 0) {
            return ApiResponse.badRequest("预警规则 ID 无效");
        }
        return ApiResponse.success(advancedAnalysisService.updateAlertRuleStatus(id, request));
    }

    @PostMapping("/alert-rules/delete")
    public ApiResponse<Map<String, Object>> deleteAlertRule(@RequestBody Map<String, Object> request) {
        long id = parseLong(request.get("id"));
        if (id <= 0) {
            return ApiResponse.badRequest("预警规则 ID 无效");
        }
        return ApiResponse.success(advancedAnalysisService.deleteAlertRule(id));
    }

    @PostMapping("/alert-events/run")
    public ApiResponse<Map<String, Object>> runAlertDetection(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(advancedAnalysisService.runAlertRuleDetection(request));
    }

    @PostMapping("/alert-events")
    public ApiResponse<Object> listAlertEvents(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "list"));
        if ("status".equalsIgnoreCase(action) || "update".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警事件 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.updateAlertEventStatus(id, request));
        }
        if ("detail".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警事件 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.getAlertEvent(id));
        }
        return ApiResponse.success(advancedAnalysisService.listAlertEvents(request));
    }

    @PostMapping("/alert-push")
    public ApiResponse<Object> alertPush(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "list"));
        if ("config".equalsIgnoreCase(action) || "status".equalsIgnoreCase(action)) {
            return ApiResponse.success(advancedAnalysisService.alertPushConfigStatus());
        }
        if ("retry".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("预警推送记录 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.retryAlertPushLog(id));
        }
        return ApiResponse.success(advancedAnalysisService.listAlertPushLogs(request));
    }

    @PostMapping("/plans")
    public ApiResponse<Object> plans(@RequestBody Map<String, Object> request) {
        String action = text(request.getOrDefault("action", "save"));
        if ("list".equalsIgnoreCase(action)) {
            return ApiResponse.success(advancedAnalysisService.listPlans(request));
        }
        if ("detail".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.getPlan(id));
        }
        if ("recalculate".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.recalculatePlan(id));
        }
        if ("delete".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.deletePlan(id));
        }
        if ("rename".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.renamePlan(id, request));
        }
        if ("versions".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.listPlanVersions(id));
        }
        if ("compare".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.comparePlanVersions(id, request));
        }
        if ("compare-latest".equalsIgnoreCase(action)) {
            long id = parseLong(request.get("id"));
            if (id <= 0) {
                return ApiResponse.badRequest("方案 ID 无效");
            }
            return ApiResponse.success(advancedAnalysisService.latestPlanVersionDiff(id));
        }
        return ApiResponse.success(advancedAnalysisService.savePlan(request));
    }

    private Map<String, Object> fallbackParse(String question) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        String intent = "";
        if (normalized.matches(".*(预测|预估|未来|走势|forecast|prophet|holt).*")) {
            intent = "forecast";
        } else if (normalized.matches(".*(what-?if|如果|若|假设|提升|下降|降低|增长|推演|模拟|利润变化).*")) {
            intent = "whatIf";
        } else if (normalized.matches(".*(预警|提醒|告警|低于|高于|超过|异常|阈值|通知|钉钉|邮件|z-?score).*")) {
            intent = "alert";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent);
        result.put("metric", inferMetric(question));
        result.put("fallbackUsed", true);
        result.put("reasoning", intent.isBlank() ? "未识别为预测、推演或预警意图" : "AI 服务不可用，已使用后端规则兜底解析");
        return result;
    }

    private String inferMetric(String question) {
        String content = question == null ? "" : question;
        for (String candidate : new String[]{"销售额", "利润", "成本", "销量", "收入", "转化率", "退货率", "客单价"}) {
            if (content.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long parseLong(Object value) {
        try {
            return Long.parseLong(text(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
