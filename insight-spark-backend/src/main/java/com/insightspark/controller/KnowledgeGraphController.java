package com.insightspark.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.service.KnowledgeGraphService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-graph")
@CrossOrigin
public class KnowledgeGraphController {

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @PostMapping("/sync")
    public ApiResponse<Map<String, Object>> syncGraph() {
        return ApiResponse.success(knowledgeGraphService.syncGraph());
    }

    @PostMapping("/rebuild")
    public ApiResponse<Map<String, Object>> rebuildGraph() {
        Map<String, Object> result = knowledgeGraphService.syncGraph();
        return ApiResponse.success(Map.of(
                "nodeCount", result.get("nodeUpsertCount"),
                "edgeCount", result.get("edgeUpsertCount")
        ));
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(knowledgeGraphService.overview());
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(knowledgeGraphService.healthStatus());
    }

    @GetMapping("/graph")
    public ApiResponse<Map<String, Object>> graph(@RequestParam(defaultValue = "120") int limit) {
        return ApiResponse.success(knowledgeGraphService.graph(limit));
    }

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(knowledgeGraphService.searchBundle(keyword, limit));
    }

    @GetMapping("/search-bundle")
    public ApiResponse<Map<String, Object>> searchBundle(@RequestParam(required = false) String keyword,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(knowledgeGraphService.searchBundle(keyword, limit));
    }

    @GetMapping("/multi-hop")
    public ApiResponse<Map<String, Object>> multiHop(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String tableName,
                                                     @RequestParam(defaultValue = "3") int depth,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(knowledgeGraphService.multiHopSearch(keyword, tableName, depth, limit));
    }
}
