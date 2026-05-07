package com.insightspark.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PythonAiService {

    private static final Logger log = LoggerFactory.getLogger(PythonAiService.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${insight.ai-service-url:http://localhost:8000}")
    private String aiServiceUrl;

    public Optional<Map<String, Object>> textToSql(String question, String tableName, List<Map<String, Object>> fields) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("tableName", tableName);
        request.put("fields", fields);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/text-to-sql",
                    request,
                    Map.class
            );
            if (response == null || !response.containsKey("sql")) {
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (HttpClientErrorException e) {
            log.warn("Python AI 服务拒绝本次请求，使用 Java 内置 Text-to-SQL 兜底逻辑：{}", e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Python AI 服务不可用，使用 Java 内置 Text-to-SQL 兜底逻辑：{}", e.getMessage());
            return Optional.empty();
        }
    }

    public Map<String, Object> diagnose(String tableName, String metricField, List<String> dimensionFields,
                                        String timeField, List<Map<String, Object>> rows) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tableName", tableName);
        request.put("metricField", metricField);
        request.put("dimensionFields", dimensionFields);
        request.put("timeField", timeField);
        request.put("rows", rows);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/diagnose",
                    request,
                    Map.class
            );
            if (response == null) {
                throw new IllegalArgumentException("Python AI 诊断服务返回为空");
            }
            return response;
        } catch (RestClientException e) {
            log.error("Python AI 诊断服务调用失败：{}", e.getMessage());
            throw new IllegalArgumentException("Python AI 诊断服务不可用，请确认 8000 端口服务已启动");
        }
    }

    public Optional<Map<String, Object>> graphRagDiagnose(String question, String tableName, String metricField,
                                                         List<String> dimensionFields,
                                                         String timeField,
                                                         Map<String, Object> graphPath,
                                                         List<Map<String, Object>> docEvidence,
                                                         List<Map<String, Object>> queryRows,
                                                         String detailLevel,
                                                         String anomalyType) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("tableName", tableName);
        request.put("metricField", metricField);
        request.put("dimensionFields", dimensionFields);
        request.put("timeField", timeField);
        request.put("queryRows", queryRows);
        request.put("rows", queryRows);
        request.put("graphPath", graphPath);
        request.put("graphContext", graphPath.getOrDefault("nodes", List.of()));
        request.put("docEvidence", docEvidence);
        request.put("docChunks", docEvidence);
        request.put("detailLevel", detailLevel == null || detailLevel.isBlank() ? "detailed" : detailLevel);
        request.put("anomalyType", anomalyType == null || anomalyType.isBlank() ? "fluctuation" : anomalyType);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/graphrag/diagnose",
                    request,
                    Map.class
            );
            return response == null ? Optional.empty() : Optional.of(response);
        } catch (RestClientException e) {
            log.warn("Python GraphRAG 璇婃柇鏈嶅姟涓嶅彲鐢紝浣跨敤甯歌璇婃柇锛歿}", e.getMessage());
            return Optional.empty();
        }
    }
}
