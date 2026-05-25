package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.DatasourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
@CrossOrigin
public class DatasourceController {

    @Autowired
    private DatasourceService datasourceService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(datasourceService.listDatasources());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success("数据源已保存", datasourceService.createDatasource(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{datasourceId}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long datasourceId) {
        return ApiResponse.success(datasourceService.testConnection(datasourceId));
    }

    @PostMapping("/{datasourceId}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long datasourceId, @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(datasourceService.updateDatasource(datasourceId, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{datasourceId}/delete")
    public ApiResponse<Void> delete(@PathVariable Long datasourceId) {
        datasourceService.deleteDatasource(datasourceId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{datasourceId}/sync-schema")
    public ApiResponse<Map<String, Object>> syncSchema(@PathVariable Long datasourceId) {
        try {
            return ApiResponse.success("Schema 解析完成", datasourceService.syncSchema(datasourceId));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{datasourceId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long datasourceId, @RequestBody Map<String, String> request) {
        try {
            datasourceService.updateStatus(datasourceId, request.get("status"));
            return ApiResponse.success("状态已更新", null);
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{datasourceId}/health")
    public ApiResponse<Map<String, Object>> health(@PathVariable Long datasourceId) {
        return ApiResponse.success(datasourceService.health(datasourceId));
    }

    @GetMapping("/{datasourceId}/schema/tables")
    public ApiResponse<List<Map<String, Object>>> schemaTables(@PathVariable Long datasourceId) {
        return ApiResponse.success(datasourceService.listSchemaTables(datasourceId));
    }

    @GetMapping("/{datasourceId}/schema/tables/{tableName}/fields")
    public ApiResponse<List<Map<String, Object>>> schemaFields(@PathVariable Long datasourceId,
                                                               @PathVariable String tableName) {
        return ApiResponse.success(datasourceService.listSchemaFields(datasourceId, tableName));
    }

    @PostMapping("/schema/fields/{fieldId}")
    public ApiResponse<Void> updateField(@PathVariable Long fieldId, @RequestBody Map<String, Object> request) {
        datasourceService.updateFieldMeta(fieldId, request);
        return ApiResponse.success("字段配置已更新", null);
    }

    @GetMapping("/{datasourceId}/permissions")
    public ApiResponse<List<Map<String, Object>>> permissions(@PathVariable Long datasourceId) {
        return ApiResponse.success(datasourceService.listPermissions(datasourceId));
    }

    @PostMapping("/{datasourceId}/permissions")
    public ApiResponse<Map<String, Object>> grantPermission(@PathVariable Long datasourceId, @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(datasourceService.grantPermission(datasourceId, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/permissions/{permissionId}/revoke")
    public ApiResponse<Void> revokePermission(@PathVariable Long permissionId) {
        datasourceService.revokePermission(permissionId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{datasourceId}/federal-relations")
    public ApiResponse<List<Map<String, Object>>> federalRelations(@PathVariable Long datasourceId) {
        return ApiResponse.success(datasourceService.listFederalRelations(datasourceId));
    }

    @PostMapping("/{datasourceId}/federal-relations")
    public ApiResponse<Map<String, Object>> saveFederalRelation(@PathVariable Long datasourceId, @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(datasourceService.saveFederalRelation(datasourceId, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{datasourceId}/federal-sql")
    public ApiResponse<Map<String, Object>> generateFederalSql(@PathVariable Long datasourceId, @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(datasourceService.generateFederalSql(datasourceId, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{datasourceId}/row-policies")
    public ApiResponse<List<Map<String, Object>>> rowPolicies(@PathVariable Long datasourceId) {
        return ApiResponse.success(datasourceService.listRowPolicies(datasourceId));
    }

    @PostMapping("/{datasourceId}/row-policies")
    public ApiResponse<Map<String, Object>> saveRowPolicy(@PathVariable Long datasourceId, @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(datasourceService.saveRowPolicy(datasourceId, request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/row-policies/{policyId}/delete")
    public ApiResponse<Void> deleteRowPolicy(@PathVariable Long policyId) {
        datasourceService.deleteRowPolicy(policyId);
        return ApiResponse.success(null);
    }

    @GetMapping("/neo4j-config")
    public ApiResponse<Map<String, Object>> neo4jConfig() {
        return ApiResponse.success(datasourceService.getNeo4jConfig());
    }

    @PostMapping("/neo4j-config")
    public ApiResponse<Map<String, Object>> saveNeo4jConfig(@RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(datasourceService.saveNeo4jConfig(request));
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/sync-knowledge-graph")
    public ApiResponse<Map<String, Object>> syncKnowledgeGraph() {
        try {
            return ApiResponse.success("知识图谱同步完成", datasourceService.syncKnowledgeGraph());
        } catch (Exception e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
