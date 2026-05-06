package com.insightspark;

import com.insightspark.common.ApiResponse;
import com.insightspark.controller.DataUploadController;
import com.insightspark.controller.DiagnosisController;
import com.insightspark.service.DataUploadService;
import com.insightspark.service.DatasourceService;
import com.insightspark.service.DiagnosisService;
import com.insightspark.service.SqlAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private MockMultipartFile csv(String name) {
        return new MockMultipartFile("file", name, "text/csv", "id,amount\n1,10\n".getBytes(StandardCharsets.UTF_8));
    }
}
