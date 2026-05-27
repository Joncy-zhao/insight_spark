package com.insightspark.service;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PythonAiService {

    private static final Logger log = LoggerFactory.getLogger(PythonAiService.class);

    private final RestTemplate restTemplate;

    @Value("${insight.ai-service-url:http://localhost:8000}")
    private String aiServiceUrl;

    public PythonAiService() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(120000);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    public Optional<Map<String, Object>> textToSql(String question, String tableName, List<Map<String, Object>> fields,
                                                   List<Map<String, Object>> previewRows,
                                                   Map<String, Object> graphPath,
                                                   Map<String, Object> graphSqlHints) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("tableName", tableName);
        request.put("fields", fields);
        request.put("previewRows", previewRows);
        request.put("graphPath", graphPath == null ? Map.of() : graphPath);
        request.put("graphContext", graphPath == null ? List.of() : graphPath.getOrDefault("ragContext", List.of()));
        request.put("graphSqlHints", graphSqlHints == null ? Map.of() : graphSqlHints);

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
            log.warn("Python AI 拒绝 Text-to-SQL 请求: {}", e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Python AI 不可用，回退到 Java Text-to-SQL: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> businessModelSemantic(String question, String requirement, String tableName,
                                                               List<Map<String, Object>> fields,
                                                               List<Map<String, Object>> previewRows) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("requirement", requirement);
        request.put("tableName", tableName);
        request.put("fields", fields);
        request.put("previewRows", previewRows == null ? List.of() : previewRows);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/business-model-semantic",
                    request,
                    Map.class
            );
            return response == null ? Optional.empty() : Optional.of(response);
        } catch (HttpClientErrorException e) {
            log.warn("Python AI 业务模型语义解析失败: {}", e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Python AI 业务模型语义解析不可用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> businessModelPatch(String question,
                                                            String tableName,
                                                            String modelName,
                                                            String modelRequirement,
                                                            List<Map<String, Object>> dictionaryEntries,
                                                            List<Map<String, Object>> metricDefinitions,
                                                            List<Map<String, Object>> dimensionSystem,
                                                            List<Map<String, Object>> fields,
                                                            List<Map<String, Object>> previewRows) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("tableName", tableName);
        request.put("modelName", modelName);
        request.put("modelRequirement", modelRequirement);
        request.put("dictionaryEntries", dictionaryEntries == null ? List.of() : dictionaryEntries);
        request.put("metricDefinitions", metricDefinitions == null ? List.of() : metricDefinitions);
        request.put("dimensionSystem", dimensionSystem == null ? List.of() : dimensionSystem);
        request.put("fields", fields == null ? List.of() : fields);
        request.put("previewRows", previewRows == null ? List.of() : previewRows);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/business-model-patch",
                    request,
                    Map.class
            );
            return response == null ? Optional.empty() : Optional.of(response);
        } catch (HttpClientErrorException e) {
            log.warn("Python AI 业务模型修改失败: {}", e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Python AI 业务模型修改不可用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> generateConversationTitle(String openingQuestion, String tableName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", openingQuestion);
        request.put("tableName", tableName);
        request.put("maxLength", 18);
        request.put("style", "short_session_title");

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/conversation-title",
                    request,
                    Map.class
            );
            if (response == null) {
                return Optional.empty();
            }
            String title = Optional.ofNullable(response.get("title"))
                    .map(String::valueOf)
                    .orElse("");
            title = title.trim();
            return title.isBlank() ? Optional.empty() : Optional.of(title);
        } catch (HttpClientErrorException e) {
            log.warn("Python AI 会话命名失败: {}", e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Python AI 会话命名不可用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> textToSpeech(String text, String voiceGender, String locale, String voiceLocale, Double rate) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("text", text);
        request.put("voiceGender", voiceGender);
        request.put("locale", locale);
        request.put("voiceLocale", voiceLocale);
        request.put("rate", rate);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/tts",
                    request,
                    Map.class
            );
            if (response == null || !response.containsKey("audioBase64")) {
                throw new IllegalArgumentException("Python AI TTS 服务未返回音频数据");
            }
            return Optional.of(response);
        } catch (HttpClientErrorException e) {
            String detail = e.getResponseBodyAsString();
            log.warn("Python AI TTS 请求失败: {}", detail);
            if (e.getStatusCode().value() == 404) {
                throw new IllegalArgumentException("Python AI TTS 接口不存在，请重启 AI 服务并确认 /ai/tts 已生效");
            }
            throw new IllegalArgumentException(extractPythonAiError(detail, "Python AI TTS 请求失败"));
        } catch (RestClientException e) {
            log.warn("Python AI TTS 服务不可用: {}", e.getMessage());
            throw new IllegalArgumentException("Python AI TTS 服务不可用，请确认 8000 端口服务已启动并加载最新代码");
        }
    }

    public Optional<Map<String, Object>> textToSpeechUrl(String text, String voiceGender, String locale, String voiceLocale, Double rate) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("text", text);
        request.put("voiceGender", voiceGender);
        request.put("locale", locale);
        request.put("voiceLocale", voiceLocale);
        request.put("rate", rate);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/tts-url",
                    request,
                    Map.class
            );
            if (response == null || !response.containsKey("audioUrl")) {
                throw new IllegalArgumentException("Python AI TTS 服务未返回音频地址");
            }
            return Optional.of(response);
        } catch (HttpClientErrorException e) {
            String detail = e.getResponseBodyAsString();
            log.warn("Python AI TTS URL 请求失败: {}", detail);
            if (e.getStatusCode().value() == 404) {
                throw new IllegalArgumentException("Python AI TTS URL 接口不存在，请重启 AI 服务并确认 /ai/tts-url 已生效");
            }
            throw new IllegalArgumentException(extractPythonAiError(detail, "Python AI TTS URL 请求失败"));
        } catch (RestClientException e) {
            log.warn("Python AI TTS URL 服务不可用: {}", e.getMessage());
            throw new IllegalArgumentException("Python AI TTS URL 服务不可用，请确认 8000 端口服务已启动并加载最新代码");
        }
    }

    public Optional<Map<String, Object>> recognizeSpeech(String audioBase64, String locale) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("audioBase64", audioBase64);
        request.put("locale", locale);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/ai/asr",
                    request,
                    Map.class
            );
            if (response == null || !response.containsKey("text")) {
                throw new IllegalArgumentException("Python AI ASR 服务未返回识别文本");
            }
            return Optional.of(response);
        } catch (HttpClientErrorException e) {
            String detail = e.getResponseBodyAsString();
            log.warn("Python AI ASR 请求失败: {}", detail);
            if (e.getStatusCode().value() == 404) {
                throw new IllegalArgumentException("Python AI ASR 接口不存在，请重启 AI 服务并确认 /ai/asr 已生效");
            }
            throw new IllegalArgumentException(extractPythonAiError(detail, "Python AI ASR 请求失败"));
        } catch (RestClientException e) {
            log.warn("Python AI ASR 服务不可用: {}", e.getMessage());
            throw new IllegalArgumentException("Python AI ASR 服务不可用，请确认 8000 端口服务已启动并加载最新代码");
        }
    }

    public void streamTextToSpeech(Map<String, Object> payload, HttpServletResponse response) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        RequestCallback requestCallback = restTemplate.httpEntityCallback(entity, Map.class);
        ResponseExtractor<Void> responseExtractor = aiResponse -> copyAudioStream(aiResponse, response);

        try {
            restTemplate.execute(
                    aiServiceUrl + "/ai/tts-stream",
                    HttpMethod.POST,
                    requestCallback,
                    responseExtractor
            );
        } catch (HttpClientErrorException e) {
            throw new IllegalArgumentException(extractPythonAiError(e.getResponseBodyAsString(), "Python AI 实时 TTS 请求失败"));
        } catch (RestClientException e) {
            throw new IllegalArgumentException("Python AI 实时 TTS 服务不可用，请确认 8000 端口服务已启动并加载最新代码");
        }
    }

    private Void copyAudioStream(ClientHttpResponse aiResponse, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setStatus(aiResponse.getStatusCode().value());
        servletResponse.setContentType("audio/pcm");
        servletResponse.setCharacterEncoding("UTF-8");
        servletResponse.setHeader("Cache-Control", "no-store");
        copyHeaderIfPresent(aiResponse, servletResponse, "X-Audio-Format");
        copyHeaderIfPresent(aiResponse, servletResponse, "X-Audio-Sample-Rate");
        copyHeaderIfPresent(aiResponse, servletResponse, "X-Audio-Channels");
        servletResponse.flushBuffer();

        ServletOutputStream outputStream = servletResponse.getOutputStream();
        try (InputStream inputStream = aiResponse.getBody()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                outputStream.flush();
                servletResponse.flushBuffer();
            }
        }
        return null;
    }

    private void copyHeaderIfPresent(ClientHttpResponse source, HttpServletResponse target, String headerName) {
        String value = source.getHeaders().getFirst(headerName);
        if (value != null && !value.isBlank()) {
            target.setHeader(headerName, value);
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
            log.error("Python AI 诊断服务调用失败: {}", e.getMessage());
            throw new IllegalArgumentException("Python AI 诊断服务不可用，请确认 8000 端口服务已启动");
        }
    }

    public Optional<Map<String, Object>> graphRagDiagnose(String question, String tableName, String metricField,
                                                          List<String> dimensionFields,
                                                          String timeField,
                                                          Map<String, Object> graphPath,
                                                          List<Map<String, Object>> docEvidence,
                                                          List<Map<String, Object>> queryRows,
                                                          Map<String, String> fieldLabels,
                                                          String detailLevel,
                                                          String anomalyType) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("tableName", tableName);
        request.put("metricField", metricField);
        request.put("fieldLabels", fieldLabels == null ? Map.of() : fieldLabels);
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
            log.warn("Python GraphRAG 诊断不可用: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractPythonAiError(String responseBody, String fallback) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallback;
        }
        String body = responseBody.trim();
        String detailKey = "\"detail\":";
        int detailIndex = body.indexOf(detailKey);
        if (detailIndex >= 0) {
            String detail = body.substring(detailIndex + detailKey.length()).trim();
            if (detail.startsWith("\"")) {
                int endIndex = detail.indexOf('"', 1);
                if (endIndex > 1) {
                    return detail.substring(1, endIndex);
                }
            }
        }
        String messageKey = "\"message\":";
        int messageIndex = body.indexOf(messageKey);
        if (messageIndex >= 0) {
            String message = body.substring(messageIndex + messageKey.length()).trim();
            if (message.startsWith("\"")) {
                int endIndex = message.indexOf('"', 1);
                if (endIndex > 1) {
                    return message.substring(1, endIndex);
                }
            }
        }
        return fallback;
    }
}
