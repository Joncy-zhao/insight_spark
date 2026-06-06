package com.insightspark;

import com.insightspark.common.ApiResponse;
import com.insightspark.controller.DataUploadController;
import com.insightspark.controller.DiagnosisController;
import com.insightspark.service.AiChartRuleConfigService;
import com.insightspark.service.AdvancedAnalysisService;
import com.insightspark.service.ChatBiService;
import com.insightspark.service.DataUploadService;
import com.insightspark.service.DatasourceService;
import com.insightspark.service.DiagnosisService;
import com.insightspark.service.PythonAiService;
import com.insightspark.service.SqlAuditService;
import com.insightspark.service.SmartChatService;
import com.insightspark.service.BusinessModelAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.AbstractList;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2AcceptanceTests {

    @Test
    void uploadCsvReturnsTaskIdAndSuccessPayload() throws Exception {
        DataUploadService service = org.mockito.Mockito.mock(DataUploadService.class);
        org.mockito.Mockito.when(service.processFileWithTask(org.mockito.Mockito.any()))
                .thenReturn(Map.of("taskId", "task-1", "tableName", "biz_data_1", "rowCount", 1, "fieldCount", 2));
        DataUploadController controller = new DataUploadController();
        ReflectionTestUtils.setField(controller, "dataUploadService", service);

        ApiResponse<Map<String, Object>> response = controller.uploadExcel(csv("orders.csv"));

        assertEquals(200, response.getCode());
        assertEquals("task-1", response.getData().get("taskId"));
        assertEquals("biz_data_1", response.getData().get("tableName"));
    }

    @Test
    void uploadSixFilesFailsFast() throws Exception {
        DataUploadService service = org.mockito.Mockito.mock(DataUploadService.class);
        org.mockito.Mockito.when(service.processFilesWithTask(org.mockito.Mockito.anyList(), org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.any(), org.mockito.Mockito.any()))
                .thenThrow(new IllegalArgumentException("批量上传最多支持 5 个文件"));
        DataUploadController controller = new DataUploadController();
        ReflectionTestUtils.setField(controller, "dataUploadService", service);

        MultipartFile[] files = {csv("1.csv"), csv("2.csv"), csv("3.csv"), csv("4.csv"), csv("5.csv"), csv("6.csv")};
        ApiResponse<Map<String, Object>> response = controller.uploadBatch(files, "SAME_HEADER", null, null);

        assertEquals(500, response.getCode());
        assertTrue(response.getMessage().contains("5 个文件"));
    }

    @Test
    void dropSqlIsBlockedByAstAudit() {
        SqlAuditService service = new SqlAuditService();

        SqlAuditService.AuditResult result = service.inspect("DROP TABLE biz_data_1", "biz_data_1");

        assertTrue(result.blocked());
        assertEquals("BLOCKED", result.riskLevel());
    }

    @Test
    void macroRegionValuesCorrectProvinceDimensionToRegionField() {
        ChatBiService service = new ChatBiService();
        List<Map<String, Object>> fields = List.of(
                field("col_003", "订单日期", "order_date", "DATE"),
                field("col_010", "省份", "province", "TEXT"),
                field("col_012", "区域", "region", "TEXT"),
                field("col_017", "销售额", "sales_amt", "NUMBER")
        );
        String badSql = "SELECT `col_010` AS dim_name, SUM(CAST(NULLIF(`col_017`, '') AS DECIMAL(18,2))) AS metric_value "
                + "FROM `biz_data_1778420417028500` WHERE `col_010` IN ('华东', '华南') GROUP BY `col_010`";

        Object correction = ReflectionTestUtils.invokeMethod(service, "applySemanticSqlGuard",
                "对比一下华东和华南最近的销售表现", "biz_data_1778420417028500", fields, badSql, "bar",
                Map.of("dimensionKey", "col_010", "metricKey", "col_017"), new ArrayList<String>());

        String sql = ReflectionTestUtils.invokeMethod(correction, "sql");
        Map<?, ?> fieldMapping = ReflectionTestUtils.invokeMethod(correction, "fieldMapping");
        assertNotNull(sql);
        assertTrue(sql.contains("`col_012` AS dim_name"));
        assertTrue(sql.contains("`col_012` = '华东'"));
        assertTrue(sql.contains("`col_012` = '华南'"));
        assertTrue(sql.contains("`col_003` >= DATE_SUB((SELECT MAX(`col_003`) FROM `biz_data_1778420417028500`), INTERVAL 90 DAY)"));
        assertEquals("col_012", fieldMapping.get("dimensionKey"));
    }

    @Test
    void macroRegionFilterDoesNotOverrideExplicitProvinceBreakdown() {
        ChatBiService service = new ChatBiService();
        List<Map<String, Object>> fields = List.of(
                field("col_003", "订单日期", "order_date", "DATE"),
                field("col_010", "省份", "province", "TEXT"),
                field("col_012", "区域", "region", "TEXT"),
                field("col_017", "销售额", "sales_amt", "NUMBER")
        );
        String badSql = "SELECT `col_010` AS dim_name, SUM(CAST(NULLIF(`col_017`, '') AS DECIMAL(18,2))) AS metric_value "
                + "FROM `biz_data_1778420417028500` WHERE `col_010` IN ('华东') GROUP BY `col_010`";

        Object correction = ReflectionTestUtils.invokeMethod(service, "applySemanticSqlGuard",
                "看一下华东各省销售额", "biz_data_1778420417028500", fields, badSql, "bar",
                Map.of("dimensionKey", "col_010", "metricKey", "col_017"), new ArrayList<String>());

        String sql = ReflectionTestUtils.invokeMethod(correction, "sql");
        Map<?, ?> fieldMapping = ReflectionTestUtils.invokeMethod(correction, "fieldMapping");
        assertNotNull(sql);
        assertTrue(sql.contains("`col_010` AS dim_name"));
        assertTrue(sql.contains("`col_012` = '华东'"));
        assertTrue(sql.contains("GROUP BY `col_010`"));
        assertEquals("col_010", fieldMapping.get("dimensionKey"));
    }

    @Test
    void cityValuesAreFilteredByCityFieldInsteadOfRegionField() {
        ChatBiService service = new ChatBiService();
        List<Map<String, Object>> fields = List.of(
                field("col_003", "订单日期", "order_date", "DATE"),
                field("col_007", "城市", "city", "TEXT"),
                field("col_012", "区域", "region", "TEXT"),
                field("col_017", "销售额", "sales_amt", "NUMBER")
        );
        String badSql = "SELECT `col_007` AS dim_name, SUM(CAST(NULLIF(`col_017`, '') AS DECIMAL(18,2))) AS metric_value "
                + "FROM `biz_data_1778420417028500` WHERE `col_007` IS NOT NULL AND `col_007` <> '' "
                + "AND (`col_012` = '上海' OR `col_012` LIKE '%上海%' OR `col_012` = '西安' OR `col_012` LIKE '%西安%') "
                + "AND `col_003` >= DATE_SUB(CURDATE(), INTERVAL 90 DAY) GROUP BY `col_007`";

        Object correction = ReflectionTestUtils.invokeMethod(service, "applySemanticSqlGuard",
                "对比一下西安和上海最近的销售表现", "biz_data_1778420417028500", fields, badSql, "bar",
                Map.of("dimensionKey", "col_007", "metricKey", "col_017"), new ArrayList<String>());

        String sql = ReflectionTestUtils.invokeMethod(correction, "sql");
        Map<?, ?> fieldMapping = ReflectionTestUtils.invokeMethod(correction, "fieldMapping");
        assertNotNull(sql);
        assertTrue(sql.contains("`col_007` AS dim_name"));
        assertTrue(sql.contains("`col_007` = '上海'"));
        assertTrue(sql.contains("`col_007` = '西安'"));
        assertFalse(sql.contains("`col_012` = '上海'"));
        assertTrue(sql.contains("GROUP BY `col_007`"));
        assertEquals("col_007", fieldMapping.get("dimensionKey"));
    }

    @Test
    void mixedGeoValuesUseDataProfileBindingsForComparableObjects() {
        ChatBiService service = new ChatBiService();
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        org.mockito.Mockito.when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        org.mockito.Mockito.when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("`col_010` = '广东'")))
                .thenReturn(List.of(Map.of("1", 1)));
        org.mockito.Mockito.when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("`col_007` = '上海'")))
                .thenReturn(List.of(Map.of("1", 1)));
        List<Map<String, Object>> fields = List.of(
                field("col_003", "订单日期", "order_date", "DATE"),
                field("col_007", "城市", "city", "TEXT"),
                field("col_010", "省份", "province", "TEXT"),
                field("col_012", "区域", "region", "TEXT"),
                field("col_017", "销售额", "sales_amt", "NUMBER")
        );
        String badSql = "SELECT `col_007` AS dim_name, SUM(CAST(NULLIF(`col_017`, '') AS DECIMAL(18,2))) AS metric_value "
                + "FROM `biz_data_1778420417028500` WHERE `col_007` IS NOT NULL AND `col_007` <> '' "
                + "AND (`col_012` = '上海' OR `col_012` = '广东') "
                + "AND `col_003` >= DATE_SUB(CURDATE(), INTERVAL 90 DAY) GROUP BY `col_007`";

        Object correction = ReflectionTestUtils.invokeMethod(service, "applySemanticSqlGuard",
                "对比一下广东和上海最近的销售表现", "biz_data_1778420417028500", fields, badSql, "bar",
                Map.of("dimensionKey", "col_007", "metricKey", "col_017"), new ArrayList<String>());

        String sql = ReflectionTestUtils.invokeMethod(correction, "sql");
        Map<?, ?> fieldMapping = ReflectionTestUtils.invokeMethod(correction, "fieldMapping");
        assertNotNull(sql);
        assertTrue(sql.contains("UNION ALL"));
        assertTrue(sql.contains("'广东' AS dim_name"));
        assertTrue(sql.contains("`col_010` = '广东'"));
        assertTrue(sql.contains("'上海' AS dim_name"));
        assertTrue(sql.contains("`col_007` = '上海'"));
        assertTrue(sql.contains("SELECT MAX(`col_003`)"));
        assertEquals("dim_name", fieldMapping.get("dimensionKey"));
    }

    @Test
    void datasourceConnectionFailureReturnsFailedStatus() {
        DatasourceService service = new DatasourceService();
        try {
            ReflectionTestUtils.invokeMethod(service, "normalizeDbType", "SQLSERVER");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("MYSQL / POSTGRESQL"));
            return;
        }
        throw new AssertionError("unsupported dbType should fail");
    }

    @Test
    void diagnosisExportProducesDownloadableWordFile() {
        DiagnosisService service = org.mockito.Mockito.mock(DiagnosisService.class);
        org.mockito.Mockito.when(service.exportReport(1L, "word"))
                .thenReturn(new DiagnosisService.ExportFile("report.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "docx".getBytes(StandardCharsets.UTF_8)));
        DiagnosisController controller = new DiagnosisController();
        ReflectionTestUtils.setField(controller, "diagnosisService", service);

        var response = controller.exportReport(1L, "word");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getContentDisposition().getFilename());
        assertFalse(response.getBody() == null || response.getBody().length == 0);
    }

    @Test
    void uploadTaskQueryReturnsProgressSnapshot() {
        DataUploadService service = org.mockito.Mockito.mock(DataUploadService.class);
        org.mockito.Mockito.when(service.getUploadTask("task-1"))
                .thenReturn(Map.of("taskId", "task-1", "status", "SUCCESS", "progress", 100));
        DataUploadController controller = new DataUploadController();
        ReflectionTestUtils.setField(controller, "dataUploadService", service);

        ApiResponse<Map<String, Object>> response = controller.uploadTask("task-1");

        assertEquals(200, response.getCode());
        assertEquals(100, response.getData().get("progress"));
    }

    @Test
    void asyncUploadReturnsWaitingTaskImmediately() throws Exception {
        DataUploadService service = org.mockito.Mockito.mock(DataUploadService.class);
        org.mockito.Mockito.when(service.startAsyncProcessFile(org.mockito.Mockito.any()))
                .thenReturn(Map.of("taskId", "task-async", "status", "WAITING", "progress", 0));
        DataUploadController controller = new DataUploadController();
        ReflectionTestUtils.setField(controller, "dataUploadService", service);

        ApiResponse<Map<String, Object>> response = controller.uploadExcelAsync(csv("orders.csv"));

        assertEquals(200, response.getCode());
        assertEquals("task-async", response.getData().get("taskId"));
        assertEquals("WAITING", response.getData().get("status"));
    }

    @Test
    void aiChartRuleUnsafeRenderConfigIsBlocked() {
        AiChartRuleConfigService service = new AiChartRuleConfigService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(
                service,
                "validateRuleConfig",
                "{}",
                "{\"tooltip\":{\"formatter\":\"function(){return 1}\"}}"
        ));
    }

    @Test
    void aiChartRuleTestPayloadRejectsTooManyRows() {
        AiChartRuleConfigService service = new AiChartRuleConfigService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        List<Map<String, Object>> rows = new AbstractList<>() {
            @Override
            public Map<String, Object> get(int index) {
                return Map.of("部门", "华东", "销售额", index);
            }

            @Override
            public int size() {
                return 501;
            }

            @Override
            public String toString() {
                return "[501 rows]";
            }
        };

        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(
                service,
                "validateTestPayload",
                Map.of(
                        "fields", List.of(
                                Map.of("name", "部门", "type", "string"),
                                Map.of("name", "销售额", "type", "number")
                        ),
                        "rows", rows
                )
        ));
    }

    @Test
    void smartChatRoutesForecastIntentToAdvancedAnalysis() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        AdvancedAnalysisService advancedAnalysisService = org.mockito.Mockito.mock(AdvancedAnalysisService.class);
        ChatBiService chatBiService = org.mockito.Mockito.mock(ChatBiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "advancedAnalysisService", advancedAnalysisService);
        ReflectionTestUtils.setField(service, "chatBiService", chatBiService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of("intent", "forecast", "metricField", "sales_amt", "timeField", "order_date")));
        org.mockito.Mockito.when(advancedAnalysisService.forecast(org.mockito.Mockito.anyMap())).thenReturn(Map.of(
                "type", "forecast",
                "tableName", "sales_order",
                "metricField", "sales_amt",
                "timeField", "order_date",
                "series", List.of(Map.of("name", "2026-06", "history", 100), Map.of("name", "2026-07", "forecast", 120))
        ));

        Map<String, Object> result = service.executeSmart(chatRequest("预测下个月销售额", "sales_order"));

        assertEquals("FORECAST", result.get("smartIntent"));
        assertEquals("FORECAST", result.get("responseType"));
        assertTrue(Boolean.TRUE.equals(result.get("smartRouted")));
    }

    @Test
    void smartChatValidatesForecastSlotsAgainstFieldMetadata() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        AdvancedAnalysisService advancedAnalysisService = org.mockito.Mockito.mock(AdvancedAnalysisService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "advancedAnalysisService", advancedAnalysisService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(List.of(
                field("order_id", "订单编号", "order_id", "TEXT"),
                field("order_date", "订单日期", "order_date", "DATE"),
                field("sales_amt", "销售额", "sales_amt", "NUMBER")
        ));
        org.mockito.Mockito.when(pythonAiService.smartChatRoute(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of(
                        "primaryIntent", "FORECAST",
                        "confidence", 0.9,
                        "requiresConfirmation", false,
                        "slots", Map.of("timeField", "order_id", "metricField", "sales_amt"),
                        "reasoning", "预测意图"
                )));
        org.mockito.Mockito.when(advancedAnalysisService.forecast(org.mockito.Mockito.anyMap())).thenReturn(Map.of(
                "type", "forecast",
                "tableName", "sales_order",
                "metricField", "sales_amt",
                "timeField", "order_date",
                "series", List.of(Map.of("name", "2026-06", "history", 100), Map.of("name", "2026-07", "forecast", 120))
        ));

        service.executeSmart(chatRequest("照现在的趋势，下个月收入大概会到多少", "sales_order"));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(advancedAnalysisService).forecast(captor.capture());
        assertEquals("order_date", captor.getValue().get("timeField"));
        assertEquals("sales_amt", captor.getValue().get("metricField"));
    }

    @Test
    void forecastFromSeriesReadsHistoryAndMetricValueColumns() {
        AdvancedAnalysisService service = new AdvancedAnalysisService();

        Map<String, Object> result = service.forecastFromSeries(Map.of(
                "tableName", "query_result",
                "metric", "销售额",
                "horizon", 1,
                "series", List.of(
                        Map.of("name", "2026-01", "history", 120000),
                        Map.of("name", "2026-02", "metric_value", 150000),
                        Map.of("name", "2026-03", "value", 180000)
                )
        ));

        List<Map<String, Object>> series = (List<Map<String, Object>>) result.get("series");
        assertEquals(120000D, series.get(0).get("history"));
        assertEquals(150000D, series.get(1).get("history"));
        assertTrue(series.stream().anyMatch(row -> row.get("forecast") != null && !Double.valueOf(0D).equals(row.get("forecast"))));
    }

    @Test
    void smartChatBuildsAlertDraftInsteadOfSavingDirectly() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of("intent", "alert", "metricField", "sales_amt", "timeField", "order_date", "threshold", 800000, "operator", "lt")));

        Map<String, Object> result = service.executeSmart(chatRequest("销售额低于80万邮件提醒我", "sales_order"));

        assertEquals("ALERT_RULE_CREATE", result.get("smartIntent"));
        assertEquals("ALERT_RULE_DRAFT", result.get("responseType"));
        assertTrue(Boolean.TRUE.equals(result.get("requiresConfirmation")));
        assertEquals("DRAFT_ONLY", result.get("sideEffectMode"));
    }

    @Test
    void smartChatKeepsLegacySqlQueryFallback() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ChatBiService chatBiService = org.mockito.Mockito.mock(ChatBiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "chatBiService", chatBiService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of("intent", "none")));
        org.mockito.Mockito.when(chatBiService.executeChat(org.mockito.Mockito.any(ChatBiService.ChatQueryRequest.class))).thenReturn(new java.util.HashMap<>(Map.of(
                "message", "分析完成",
                "sql", "SELECT 1",
                "chartType", "bar",
                "data", List.of(Map.of("name", "华东", "value", 1))
        )));

        Map<String, Object> result = service.executeSmart(chatRequest("看一下各省销售额排名", "sales_order"));

        assertEquals("QUERY_SQL", result.get("smartIntent"));
        assertEquals("QUERY_SQL", result.get("responseType"));
        assertEquals("SELECT 1", result.get("sql"));
    }

    @Test
    void smartChatDisablesAutoForecastForExplicitRankingQuery() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ChatBiService chatBiService = org.mockito.Mockito.mock(ChatBiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "chatBiService", chatBiService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(chatBiService.executeChat(org.mockito.Mockito.any(ChatBiService.ChatQueryRequest.class))).thenReturn(new java.util.HashMap<>(Map.of(
                "message", "分析完成",
                "sql", "SELECT province, SUM(sales_amt) FROM sales_order GROUP BY province",
                "chartType", "bar",
                "data", List.of(Map.of("name", "广东", "value", 1))
        )));

        Map<String, Object> result = service.executeSmart(chatRequest("看一下各省销售额排名", "sales_order"));

        assertEquals("QUERY_SQL", result.get("smartIntent"));
        org.mockito.ArgumentCaptor<ChatBiService.ChatQueryRequest> captor = org.mockito.ArgumentCaptor.forClass(ChatBiService.ChatQueryRequest.class);
        org.mockito.Mockito.verify(chatBiService).executeChat(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getFilters().get("autoForecastEnabled"));
    }

    @Test
    void smartChatGlobalRouterKeepsRankingQueryOutOfForecastModule() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ChatBiService chatBiService = org.mockito.Mockito.mock(ChatBiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "chatBiService", chatBiService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.smartChatRoute(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of(
                        "primaryIntent", "QUERY_SQL",
                        "confidence", 0.91,
                        "requiresConfirmation", false,
                        "slots", Map.of("autoForecastEnabled", false),
                        "reasoning", "全局语义路由识别为排名查询"
                )));
        org.mockito.Mockito.when(pythonAiService.parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of("intent", "forecast")));
        org.mockito.Mockito.when(chatBiService.executeChat(org.mockito.Mockito.any(ChatBiService.ChatQueryRequest.class))).thenReturn(new java.util.HashMap<>(Map.of(
                "message", "分析完成",
                "sql", "SELECT province, SUM(sales_amt) FROM sales_order GROUP BY province",
                "chartType", "bar",
                "data", List.of(Map.of("name", "广东", "value", 1))
        )));

        Map<String, Object> result = service.executeSmart(chatRequest("看一下各省销售额排名", "sales_order"));

        assertEquals("QUERY_SQL", result.get("smartIntent"));
        assertEquals("QUERY_SQL", result.get("responseType"));
        org.mockito.Mockito.verify(pythonAiService, org.mockito.Mockito.never())
                .parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap());
        org.mockito.ArgumentCaptor<ChatBiService.ChatQueryRequest> captor = org.mockito.ArgumentCaptor.forClass(ChatBiService.ChatQueryRequest.class);
        org.mockito.Mockito.verify(chatBiService).executeChat(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getFilters().get("autoForecastEnabled"));
    }

    @Test
    void smartChatGlobalRouterSendsFieldBindingToBusinessModelAgent() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        com.insightspark.service.BusinessModelAgentService agentService = org.mockito.Mockito.mock(com.insightspark.service.BusinessModelAgentService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "businessModelAgentService", agentService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.smartChatRoute(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of(
                        "primaryIntent", "BUSINESS_MODEL_PATCH",
                        "confidence", 0.88,
                        "requiresConfirmation", false,
                        "slots", Map.of("businessTerm", "销售额", "physicalField", "sales_amt"),
                        "reasoning", "全局语义路由识别为字段绑定"
                )));
        org.mockito.Mockito.when(agentService.handleQuestion(org.mockito.Mockito.anyMap())).thenReturn(new java.util.HashMap<>(Map.of(
                "handled", true,
                "message", "业务模型已更新",
                "intent", "BIND_FIELDS"
        )));

        Map<String, Object> result = service.executeSmart(chatRequest("把销售额绑定到 sales_amt", "sales_order"));

        assertEquals("BUSINESS_MODEL_PATCH", result.get("smartIntent"));
        assertEquals("BUSINESS_MODEL_PATCH", result.get("responseType"));
        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(agentService).handleQuestion(captor.capture());
        assertEquals("把销售额绑定到 sales_amt", captor.getValue().get("question"));
        assertEquals("sales_order", captor.getValue().get("selectedTableName"));
    }

    @Test
    void smartChatTreatsCurrentYearMonthlyTrendAsSqlQuery() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ChatBiService chatBiService = org.mockito.Mockito.mock(ChatBiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "chatBiService", chatBiService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.smartChatRoute(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(chatBiService.executeChat(org.mockito.Mockito.any(ChatBiService.ChatQueryRequest.class))).thenReturn(new java.util.HashMap<>(Map.of(
                "message", "分析完成",
                "sql", "SELECT DATE_FORMAT(order_date, '%Y-%m'), SUM(sales_amt) FROM sales_order GROUP BY 1",
                "chartType", "line",
                "data", List.of(Map.of("name", "2026-01", "value", 1))
        )));

        Map<String, Object> result = service.executeSmart(chatRequest("今年每个月销售额走势给我看一下", "sales_order"));

        assertEquals("QUERY_SQL", result.get("smartIntent"));
        assertEquals("QUERY_SQL", result.get("responseType"));
        org.mockito.Mockito.verify(pythonAiService, org.mockito.Mockito.never())
                .parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap());
    }

    @Test
    void chatBiDoesNotAutoForecastOrdinaryTrendQueryResult() {
        ChatBiService service = new ChatBiService();
        Map<String, Object> response = new java.util.HashMap<>(Map.of(
                "chartType", "line",
                "data", List.of(
                        Map.of("name", "2026-01", "value", 100),
                        Map.of("name", "2026-02", "value", 120),
                        Map.of("name", "2026-03", "value", 140)
                ),
                "optionTemplate", Map.of("prediction", Map.of("enabled", true))
        ));
        List<String> trace = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(service, "applyAutoForecastIfNeeded",
                response,
                Map.of("chartType", "line", "scenarioType", "TIME_SERIES",
                        "optionTemplate", Map.of("prediction", Map.of("enabled", true))),
                Map.of("metric", "销售额", "metricKey", "sales_amt"),
                "sales_order",
                "今年每个月销售额走势给我看一下",
                trace);

        assertFalse(Boolean.TRUE.equals(response.get("autoForecast")));
        assertTrue(trace.stream().anyMatch(item -> item.contains("NO_EXPLICIT_FORECAST_INTENT")));
    }

    @Test
    void chatBiRejectsPreviousResultForecastWhenSeriesAllZero() {
        ChatBiService service = new ChatBiService();
        Map<String, Object> response = new java.util.HashMap<>(Map.of(
                "chartType", "line",
                "data", List.of(
                        Map.of("name", "2026-01", "value", 0),
                        Map.of("name", "2026-02", "value", 0),
                        Map.of("name", "2026-03", "value", 0)
                ),
                "optionTemplate", Map.of("prediction", Map.of("enabled", true))
        ));
        List<String> trace = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(service, "applyAutoForecastIfNeeded",
                response,
                Map.of("chartType", "line", "scenarioType", "TIME_SERIES",
                        "optionTemplate", Map.of("prediction", Map.of("enabled", true))),
                Map.of("metric", "销售额", "metricKey", "sales_amt"),
                "sales_order",
                "基于刚才这个图预测后面三个月销售额",
                trace);

        assertFalse(Boolean.TRUE.equals(response.get("autoForecast")));
        assertEquals(Boolean.TRUE, response.get("autoForecastRejected"));
        assertEquals("上一轮查询结果未包含有效数值，建议改用原始数据源重新预测。", response.get("autoForecastRejectReason"));
        assertTrue(trace.stream().anyMatch(item -> item.contains("ALL_ZERO_SERIES")));
    }

    @Test
    void smartChatPrefersBusinessModelIntentOverAdvancedForecastMisclassification() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        com.insightspark.service.BusinessModelAgentService agentService = org.mockito.Mockito.mock(com.insightspark.service.BusinessModelAgentService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        ReflectionTestUtils.setField(service, "businessModelAgentService", agentService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of("intent", "forecast")));
        org.mockito.Mockito.when(agentService.handleQuestion(org.mockito.Mockito.anyMap())).thenReturn(new java.util.HashMap<>(Map.of(
                "handled", true,
                "message", "业务模型已更新",
                "intent", "PATCH_MODEL"
        )));

        Map<String, Object> result = service.executeSmart(chatRequest("以后销售额就按含税收入算", "sales_order"));

        assertEquals("BUSINESS_MODEL_PATCH", result.get("smartIntent"));
        assertEquals("BUSINESS_MODEL_PATCH", result.get("responseType"));
        org.mockito.Mockito.verify(pythonAiService, org.mockito.Mockito.never())
                .parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap());
    }

    @Test
    void smartChatPrefersDashboardIntentOverAdvancedForecastMisclassification() {
        SmartChatService service = smartChatService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(salesFields());
        org.mockito.Mockito.when(pythonAiService.parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(Map.of("intent", "forecast")));

        Map<String, Object> result = service.executeSmart(chatRequest("把刚才这个图钉到销售看板", "sales_order"));

        assertEquals("DASHBOARD_PIN", result.get("smartIntent"));
        assertEquals("CLARIFICATION", result.get("responseType"));
        org.mockito.Mockito.verify(pythonAiService, org.mockito.Mockito.never())
                .parseAdvancedAnalysisIntent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyMap());
    }

    @Test
    void businessModelScopeUpdateOnlyTouchesMentionedMetric() {
        BusinessModelAgentService service = new BusinessModelAgentService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        String modelJson = """
                {"metricDefinitions":[
                  {"name":"收入","field":"sales_amt","aggregation":"SUM","formula":"sales_amt"},
                  {"name":"销售额","field":"sales_amt","aggregation":"SUM","formula":"sales_amt"}
                ],"dimensionSystem":[{"name":"省份","field":"province"}],"dictionaryEntries":[]}
                """;
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(false)).thenReturn(List.of(
                Map.of("id", 1L, "modelName", "销售区域排名模型", "tableName", "sales_order", "updatedAt", "2026-06-06")
        ));
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(true)).thenReturn(List.of());
        org.mockito.Mockito.when(dataUploadService.getBusinessModelDetail(1L)).thenReturn(Map.of(
                "id", 1L,
                "modelName", "销售区域排名模型",
                "modelRequirement", "销售分析",
                "tableName", "sales_order",
                "modelJson", modelJson
        ));
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(List.of(
                field("sales_amt", "销售额", "sales_amt", "NUMBER"),
                field("tax_amt", "含税金额", "tax_amt", "NUMBER"),
                field("province", "省份", "province", "TEXT")
        ));
        org.mockito.Mockito.when(dataUploadService.preview("sales_order", 1, 5)).thenReturn(List.of());
        org.mockito.Mockito.when(pythonAiService.businessModelPatch(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList()
        )).thenReturn(java.util.Optional.of(Map.of(
                "intent", "BIND_FIELDS",
                "operations", List.of(
                        Map.of("targetType", "fieldBinding", "bindingType", "metricDefinition", "name", "收入", "field", "tax_amt"),
                        Map.of("targetType", "fieldBinding", "bindingType", "metricDefinition", "name", "销售额", "field", "sales_amt"),
                        Map.of("targetType", "fieldBinding", "bindingType", "dimensionDefinition", "name", "省份", "field", "province")
                ),
                "reasoning", List.of("误返回了多个绑定")
        )));
        org.mockito.Mockito.when(dataUploadService.updateBusinessModel(org.mockito.Mockito.eq(1L), org.mockito.Mockito.anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> request = invocation.getArgument(1);
                    return Map.of("id", 1L, "modelName", request.get("modelName"), "modelJson", "{}");
                });

        Map<String, Object> result = service.handleQuestion(Map.of(
                "question", "以后报表里的收入统一用含税金额",
                "tableName", "sales_order",
                "activeBusinessModelId", 1L
        ));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(dataUploadService).updateBusinessModel(org.mockito.Mockito.eq(1L), captor.capture());
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) captor.getValue().get("metricDefinitions");
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) captor.getValue().get("dimensionSystem");
        Map<String, Object> income = metrics.stream().filter(item -> "收入".equals(item.get("name"))).findFirst().orElseThrow();
        Map<String, Object> sales = metrics.stream().filter(item -> "销售额".equals(item.get("name"))).findFirst().orElseThrow();
        assertEquals("tax_amt", income.get("formula"));
        assertEquals("sales_amt", sales.get("formula"));
        assertEquals(1, dimensions.size());
        assertEquals("PATCH_MODEL", result.get("intent"));
        List<Map<String, Object>> fieldBindingResults = (List<Map<String, Object>>) result.get("fieldBindingResults");
        assertEquals(1, fieldBindingResults.size());
        assertEquals("METRIC_SCOPE_UPDATE", fieldBindingResults.get(0).get("semanticAction"));
    }

    @Test
    void businessModelScopeUpdateRequiresUniqueScopeField() {
        BusinessModelAgentService service = new BusinessModelAgentService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(false)).thenReturn(List.of(
                Map.of("id", 1L, "modelName", "销售区域排名模型", "tableName", "sales_order", "updatedAt", "2026-06-06")
        ));
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(true)).thenReturn(List.of());
        org.mockito.Mockito.when(dataUploadService.getBusinessModelDetail(1L)).thenReturn(Map.of(
                "id", 1L,
                "modelName", "销售区域排名模型",
                "modelRequirement", "销售分析",
                "tableName", "sales_order",
                "modelJson", "{\"metricDefinitions\":[{\"name\":\"收入\",\"field\":\"sales_amt\",\"aggregation\":\"SUM\",\"formula\":\"sales_amt\"}],\"dictionaryEntries\":[],\"dimensionSystem\":[]}"
        ));
        org.mockito.Mockito.when(dataUploadService.listFields("sales_order")).thenReturn(List.of(
                field("tax_amt1", "含税金额", "tax_amt1", "NUMBER"),
                field("tax_amt2", "含税金额", "tax_amt2", "NUMBER")
        ));
        org.mockito.Mockito.when(dataUploadService.preview("sales_order", 1, 5)).thenReturn(List.of());
        org.mockito.Mockito.when(pythonAiService.businessModelPatch(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList()
        )).thenReturn(java.util.Optional.of(Map.of(
                "intent", "PATCH_MODEL",
                "operations", List.of(Map.of("targetType", "metricDefinition", "name", "收入", "formula", "含税金额"))
        )));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.handleQuestion(Map.of(
                "question", "以后报表里的收入统一用含税金额",
                "tableName", "sales_order",
                "activeBusinessModelId", 1L
        )));
        assertTrue(ex.getMessage().contains("无法唯一确认"));
        org.mockito.Mockito.verify(dataUploadService, org.mockito.Mockito.never())
                .updateBusinessModel(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyMap());
    }

    @Test
    void emptyBusinessModelPatchSupportsBindingDimensionFormulaAndNewScope() {
        BusinessModelAgentService service = new BusinessModelAgentService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(false)).thenReturn(List.of(
                Map.of("id", 2L, "modelName", "亏损订单模型", "tableName", "loss_order", "updatedAt", "2026-06-06")
        ));
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(true)).thenReturn(List.of());
        org.mockito.Mockito.when(dataUploadService.getBusinessModelDetail(2L)).thenReturn(Map.of(
                "id", 2L,
                "modelName", "亏损订单模型",
                "modelRequirement", "亏损订单分析",
                "tableName", "loss_order",
                "modelJson", "{\"metricDefinitions\":[],\"dictionaryEntries\":[],\"dimensionSystem\":[]}"
        ));
        org.mockito.Mockito.when(dataUploadService.listFields("loss_order")).thenReturn(lossOrderFields());
        org.mockito.Mockito.when(dataUploadService.preview("loss_order", 1, 5)).thenReturn(List.of());
        org.mockito.Mockito.when(pythonAiService.businessModelPatch(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList()
        )).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(dataUploadService.updateBusinessModel(org.mockito.Mockito.eq(2L), org.mockito.Mockito.anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> request = invocation.getArgument(1);
                    return Map.of("id", 2L, "modelName", request.get("modelName"), "modelJson", "{}");
                });

        service.handleQuestion(modelPatchRequest("把利润这个指标绑定到 profit"));
        service.handleQuestion(modelPatchRequest("把城市维度绑定到 city"));
        service.handleQuestion(modelPatchRequest("新增指标公式：毛利率 = profit / sales_amt"));
        service.handleQuestion(modelPatchRequest("以后收入统一用 sales_amt"));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(dataUploadService, org.mockito.Mockito.times(4))
                .updateBusinessModel(org.mockito.Mockito.eq(2L), captor.capture());
        List<Map<String, Object>> profitMetrics = (List<Map<String, Object>>) captor.getAllValues().get(0).get("metricDefinitions");
        List<Map<String, Object>> dimensionSystem = (List<Map<String, Object>>) captor.getAllValues().get(1).get("dimensionSystem");
        List<Map<String, Object>> formulaMetrics = (List<Map<String, Object>>) captor.getAllValues().get(2).get("metricDefinitions");
        List<Map<String, Object>> incomeMetrics = (List<Map<String, Object>>) captor.getAllValues().get(3).get("metricDefinitions");
        assertEquals(1, profitMetrics.size());
        assertTrue(profitMetrics.stream().anyMatch(item -> "利润".equals(item.get("name")) && "profit".equals(item.get("field"))));
        assertEquals(1, dimensionSystem.size());
        assertEquals("城市", dimensionSystem.get(0).get("name"));
        assertEquals("city", dimensionSystem.get(0).get("field"));
        assertEquals(1, formulaMetrics.size());
        assertTrue(formulaMetrics.stream().anyMatch(item -> "毛利率".equals(item.get("name")) && "profit / sales_amt".equals(item.get("formula"))));
        assertFalse(formulaMetrics.stream().anyMatch(item -> "销售额".equals(item.get("name"))));
        assertEquals(1, incomeMetrics.size());
        assertTrue(incomeMetrics.stream().anyMatch(item -> "收入".equals(item.get("name")) && "sales_amt".equals(item.get("formula"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dimensionBindingDoesNotUpdateUnmentionedMetrics() {
        BusinessModelAgentService service = new BusinessModelAgentService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(false)).thenReturn(List.of(
                Map.of("id", 3L, "modelName", "订单销售分析模型", "tableName", "loss_order", "updatedAt", "2026-06-06")
        ));
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(true)).thenReturn(List.of());
        org.mockito.Mockito.when(dataUploadService.getBusinessModelDetail(3L)).thenReturn(Map.of(
                "id", 3L,
                "modelName", "订单销售分析模型",
                "modelRequirement", "订单销售分析",
                "tableName", "loss_order",
                "modelJson", "{\"metricDefinitions\":[{\"name\":\"销售额\",\"field\":\"sales_amt\",\"aggregation\":\"SUM\",\"formula\":\"sales_amt\"}],\"dictionaryEntries\":[],\"dimensionSystem\":[]}"
        ));
        org.mockito.Mockito.when(dataUploadService.listFields("loss_order")).thenReturn(lossOrderFields());
        org.mockito.Mockito.when(dataUploadService.preview("loss_order", 1, 5)).thenReturn(List.of());
        org.mockito.Mockito.when(pythonAiService.businessModelPatch(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList()
        )).thenReturn(java.util.Optional.of(Map.of(
                "intent", "BIND_FIELDS",
                "operations", List.of(
                        Map.of("targetType", "fieldBinding", "bindingType", "dimensionDefinition", "name", "省份", "field", "province"),
                        Map.of("targetType", "metricDefinition", "name", "销售额", "field", "sales_amt", "aggregation", "SUM", "formula", "sales_amt")
                )
        )));
        org.mockito.Mockito.when(dataUploadService.updateBusinessModel(org.mockito.Mockito.eq(3L), org.mockito.Mockito.anyMap()))
                .thenAnswer(invocation -> Map.of("id", 3L, "modelJson", "{}"));

        Map<String, Object> result = service.handleQuestion(Map.of(
                "question", "把省份维度绑定到 province",
                "tableName", "loss_order",
                "activeBusinessModelId", 3L
        ));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(dataUploadService).updateBusinessModel(org.mockito.Mockito.eq(3L), captor.capture());
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) captor.getValue().get("metricDefinitions");
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) captor.getValue().get("dimensionSystem");
        List<Map<String, Object>> fieldBindingResults = (List<Map<String, Object>>) result.get("fieldBindingResults");
        assertEquals(1, metrics.size());
        assertEquals("销售额", metrics.get(0).get("name"));
        assertEquals("sales_amt", metrics.get(0).get("field"));
        assertEquals(1, dimensions.size());
        assertEquals("省份", dimensions.get(0).get("name"));
        assertEquals("province", dimensions.get(0).get("field"));
        assertEquals(1, fieldBindingResults.size());
        assertEquals("DIMENSION_BINDING", fieldBindingResults.get(0).get("semanticAction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopeUpdateFallsBackFromQuestionWhenAiPatchIsEmpty() {
        BusinessModelAgentService service = new BusinessModelAgentService();
        DataUploadService dataUploadService = org.mockito.Mockito.mock(DataUploadService.class);
        PythonAiService pythonAiService = org.mockito.Mockito.mock(PythonAiService.class);
        ReflectionTestUtils.setField(service, "dataUploadService", dataUploadService);
        ReflectionTestUtils.setField(service, "pythonAiService", pythonAiService);
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(false)).thenReturn(List.of(
                Map.of("id", 4L, "modelName", "订单销售分析模型", "tableName", "loss_order", "updatedAt", "2026-06-06")
        ));
        org.mockito.Mockito.when(dataUploadService.listBusinessModels(true)).thenReturn(List.of());
        org.mockito.Mockito.when(dataUploadService.getBusinessModelDetail(4L)).thenReturn(Map.of(
                "id", 4L,
                "modelName", "订单销售分析模型",
                "modelRequirement", "订单销售分析",
                "tableName", "loss_order",
                "modelJson", "{\"metricDefinitions\":[],\"dictionaryEntries\":[],\"dimensionSystem\":[]}"
        ));
        org.mockito.Mockito.when(dataUploadService.listFields("loss_order")).thenReturn(lossOrderFields());
        org.mockito.Mockito.when(dataUploadService.preview("loss_order", 1, 5)).thenReturn(List.of());
        org.mockito.Mockito.when(pythonAiService.businessModelPatch(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList(), org.mockito.Mockito.anyList()
        )).thenReturn(java.util.Optional.of(Map.of("intent", "PATCH_MODEL", "operations", List.of())));
        org.mockito.Mockito.when(dataUploadService.updateBusinessModel(org.mockito.Mockito.eq(4L), org.mockito.Mockito.anyMap()))
                .thenAnswer(invocation -> Map.of("id", 4L, "modelJson", "{}"));

        service.handleQuestion(Map.of(
                "question", "以后收入统一用 sales_amt",
                "tableName", "loss_order",
                "activeBusinessModelId", 4L
        ));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(dataUploadService).updateBusinessModel(org.mockito.Mockito.eq(4L), captor.capture());
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) captor.getValue().get("metricDefinitions");
        assertEquals(1, metrics.size());
        assertEquals("收入", metrics.get(0).get("name"));
        assertEquals("sales_amt", metrics.get(0).get("field"));
        assertEquals("sales_amt", metrics.get(0).get("formula"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dimensionSystemSanitizerPreservesResolvedFieldBindings() {
        DataUploadService service = org.mockito.Mockito.spy(new DataUploadService());
        org.mockito.Mockito.doReturn(lossOrderFields()).when(service).listFields("loss_order");
        List<Map<String, Object>> rawDimensions = List.of(
                Map.of("name", "城市", "field", "city"),
                Map.of("name", "省份", "columnName", "province"),
                Map.of("name", "区域", "sourceFieldName", "region")
        );

        List<Map<String, Object>> dimensions = ReflectionTestUtils.invokeMethod(
                service,
                "sanitizeDimensionSystem",
                rawDimensions,
                "loss_order"
        );

        assertNotNull(dimensions);
        assertEquals(3, dimensions.size());
        assertEquals("city", dimensions.get(0).get("field"));
        assertEquals("province", dimensions.get(1).get("field"));
        assertEquals("region", dimensions.get(2).get("field"));
    }

    private MockMultipartFile csv(String name) {
        return new MockMultipartFile("file", name, "text/csv", "id,amount\n1,10\n".getBytes(StandardCharsets.UTF_8));
    }

    private SmartChatService smartChatService() {
        SmartChatService service = new SmartChatService();
        ReflectionTestUtils.setField(service, "businessModelAgentService", org.mockito.Mockito.mock(com.insightspark.service.BusinessModelAgentService.class));
        ReflectionTestUtils.setField(service, "advancedAnalysisService", org.mockito.Mockito.mock(AdvancedAnalysisService.class));
        ReflectionTestUtils.setField(service, "chatBiService", org.mockito.Mockito.mock(ChatBiService.class));
        ReflectionTestUtils.setField(service, "pythonAiService", org.mockito.Mockito.mock(PythonAiService.class));
        ReflectionTestUtils.setField(service, "dataUploadService", org.mockito.Mockito.mock(DataUploadService.class));
        return service;
    }

    private ChatBiService.ChatQueryRequest chatRequest(String question, String tableName) {
        ChatBiService.ChatQueryRequest request = new ChatBiService.ChatQueryRequest();
        request.setQuestion(question);
        request.setTableNames(List.of(tableName));
        request.setFilters(Map.of("tableName", tableName));
        request.setMode("CHAT");
        return request;
    }

    private Map<String, Object> modelPatchRequest(String question) {
        return Map.of(
                "question", question,
                "tableName", "loss_order",
                "activeBusinessModelId", 2L
        );
    }

    private Map<String, Object> field(String columnName, String displayName, String sourceFieldName, String fieldType) {
        return Map.of(
                "columnName", columnName,
                "displayName", displayName,
                "sourceFieldName", sourceFieldName,
                "fieldType", fieldType
        );
    }

    private List<Map<String, Object>> salesFields() {
        return List.of(
                Map.of("columnName", "order_date", "displayName", "订单日期", "fieldType", "DATE"),
                Map.of("columnName", "sales_amt", "displayName", "销售额", "fieldType", "NUMBER"),
                Map.of("columnName", "province", "displayName", "省份", "fieldType", "TEXT")
        );
    }

    private List<Map<String, Object>> lossOrderFields() {
        return List.of(
                field("order_date", "订单日期", "order_date", "DATE"),
                field("city", "城市", "city", "TEXT"),
                field("province", "省份", "province", "TEXT"),
                field("region", "区域", "region", "TEXT"),
                field("sales_amt", "销售额", "sales_amt", "NUMBER"),
                field("qty", "销量", "qty", "NUMBER"),
                field("profit", "利润", "profit", "NUMBER"),
                field("cus_name", "客户名称", "cus_name", "TEXT")
        );
    }
}
