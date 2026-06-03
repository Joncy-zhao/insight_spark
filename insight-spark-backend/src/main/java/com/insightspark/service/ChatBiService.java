package com.insightspark.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

@Service
public class ChatBiService {

    public static class ChatQueryRequest {
        private Long conversationId;
        private Long parentTurnId;
        private List<String> tableNames;
        private Map<String, Object> filters;
        private String question;
        private String mode;

        public Long getConversationId() {
            return conversationId;
        }

        public void setConversationId(Long conversationId) {
            this.conversationId = conversationId;
        }

        public Long getParentTurnId() {
            return parentTurnId;
        }

        public void setParentTurnId(Long parentTurnId) {
            this.parentTurnId = parentTurnId;
        }

        public List<String> getTableNames() {
            return tableNames;
        }

        public void setTableNames(List<String> tableNames) {
            this.tableNames = tableNames;
        }

        public Map<String, Object> getFilters() {
            return filters;
        }

        public void setFilters(Map<String, Object> filters) {
            this.filters = filters;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ChatBiService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private DatasourceService datasourceService;

    @Autowired
    private PythonAiService pythonAiService;

    @Autowired
    private SqlAuditService sqlAuditService;

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    private RuleBasedNl2SqlStrategy ruleBasedNl2SqlStrategy;

    @Autowired
    private AiChartRuleConfigService aiChartRuleConfigService;

    public Map<String, Object> executeChat(ChatQueryRequest request) {
        ChatQueryRequest safeRequest = request == null ? new ChatQueryRequest() : request;
        String question = Objects.toString(safeRequest.getQuestion(), "").trim();
        String tableName = resolvePreferredTableName(safeRequest);
        Map<String, Object> response = executeChat(question, tableName, safeRequest.getFilters());
        response.put("question", question);
        response.put("conversationId", safeRequest.getConversationId());
        response.put("parentTurnId", safeRequest.getParentTurnId());
        response.put("tableNames", safeRequest.getTableNames() == null ? List.of() : safeRequest.getTableNames());
        response.put("filters", safeRequest.getFilters() == null ? Map.of() : safeRequest.getFilters());
        response.put("mode", Objects.toString(safeRequest.getMode(), ""));
        return response;
    }

    public Map<String, Object> executeChat(String question, String tableName) {
        return executeChat(question, tableName, Map.of());
    }

    public Map<String, Object> executeChat(String question, String tableName, Map<String, Object> executionOptions) {
        log.info("Received chat question: {}", question);
        ensureNotCancelled("请求初始化");
        Map<String, Object> safeOptions = executionOptions == null ? Map.of() : executionOptions;
        String selectedModelId = Objects.toString(safeOptions.getOrDefault("modelId", "gpt-4"), "gpt-4").trim();
        String selectedModelName = Objects.toString(safeOptions.getOrDefault("modelName", selectedModelId), selectedModelId).trim();

        String activeTable = (tableName == null || tableName.isBlank()) ? dataUploadService.latestTableName()
                : tableName;
        List<String> generationTrace = new ArrayList<>();
        generationTrace.add("activeTable=" + activeTable);
        generationTrace.add("selectedModel=" + selectedModelId);
        String cacheKey = sqlAuditService.semanticCacheKey(question, activeTable);
        Map<String, Object> cachedSqlAudit = sqlAuditService.findSemanticCache(cacheKey);
        boolean cacheHit = !cachedSqlAudit.isEmpty();
        if (cacheHit) {
            SqlAuditService.AuditResult cacheAuditResult = sqlAuditService.inspectCachedSql(cacheKey, activeTable);
            if (cacheAuditResult.blocked()) {
                cacheHit = false;
                cachedSqlAudit = Map.of();
                generationTrace.add("semanticCache=REJECTED;" + cacheAuditResult.riskReason());
            } else {
                generationTrace.add("semanticCache=HIT;cacheAudit=" + cacheAuditResult.riskLevel());
            }
        } else {
            generationTrace.add("semanticCache=MISS");
        }
        boolean officialSource = datasourceService.isOfficialSource(activeTable);
        String queryTableName = officialSource ? datasourceService.physicalTableName(activeTable) : activeTable;
        dataUploadService.assertKnownTable(activeTable);
        ensureNotCancelled("数据源校验");

        if (!officialSource && question.contains("关联官方")) {
            ensureNotCancelled("联邦数据关联");
            List<Map<String, Object>> joinedRows = datasourceService.executeFederalJoin(activeTable, 200);
            Map<String, Object> response = new HashMap<>();
            response.put("tableName", activeTable);
            response.put("physicalTableName", queryTableName);
            response.put("sourceType", "FEDERAL_JOIN");
            response.put("sql", "FEDERAL_IN_MEMORY_JOIN(" + activeTable + ")");
            response.put("data", joinedRows);
            response.put("chartType", "bar");
            response.put("fieldMapping", Map.of("join", "Excel 与官方库关联"));
            response.put("engine", "java-federal-join");
            response.put("reasoningLogs", List.of(
                    "federalJoin=direct",
                    "sourceType=FEDERAL_JOIN",
                    "tableName=" + activeTable
            ));
            response.put("reasoningProcess", response.get("reasoningLogs"));
            response.put("riskLevel", "SAFE");
            response.put("riskReason", "已按联邦关联配置执行只读分步查询并在 Java 内存中合并");
            response.put("message", "已根据联邦关联配置合并上传表与官方库信息。");
            return response;
        }

        List<Map<String, Object>> fields = dataUploadService.listFields(activeTable);
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("当前数据表没有字段元信息，请在“数据上传”页面重新上传文件，或选择字段数大于 0 的数据表。");
        }
        ensureNotCancelled("字段元信息加载");
        Map<String, Object> graphPath = knowledgeGraphService.retrieveMultiHopContextSafely(question, activeTable);
        List<Map<String, Object>> graphContext = asMapList(graphPath.get("ragContext"));
        generationTrace.add("kgContextNodes=" + graphContext.size());
        Object neo4jFallback = graphPath.get("neo4jFallback");
        boolean graphFallbackUsed = Boolean.TRUE.equals(neo4jFallback)
                || "true".equalsIgnoreCase(Objects.toString(neo4jFallback, ""));
        String graphFallbackReason = graphFallbackUsed
                ? Objects.toString(graphPath.getOrDefault("neo4jError", ""), "")
                : null;
        if (!graphFallbackUsed && graphContext.isEmpty() && !knowledgeGraphService.hasGraphData()) {
            ensureNotCancelled("图谱补全同步");
            knowledgeGraphService.syncGraph();
            graphPath = knowledgeGraphService.retrieveMultiHopContextSafely(question, activeTable);
            graphContext = asMapList(graphPath.get("ragContext"));
            generationTrace.add("kgSync=triggered;kgContextNodes=" + graphContext.size());
            neo4jFallback = graphPath.get("neo4jFallback");
            graphFallbackUsed = Boolean.TRUE.equals(neo4jFallback)
                    || "true".equalsIgnoreCase(Objects.toString(neo4jFallback, ""));
            graphFallbackReason = graphFallbackUsed
                    ? Objects.toString(graphPath.getOrDefault("neo4jError", ""), "")
                    : graphFallbackReason;
        }
        if (graphContext.isEmpty()) {
            graphContext = buildLocalFieldContext(activeTable, fields);
            if (!graphFallbackUsed) {
                graphFallbackUsed = true;
                graphFallbackReason = "Graph context is empty, fallback to local field context";
            }
        }
        ensureNotCancelled("图谱上下文准备");
        List<Map<String, Object>> previewRows = dataUploadService.preview(activeTable, 1, 8);
        ensureNotCancelled("样例数据预览");
        Map<String, Object> graphSqlHints = knowledgeGraphService.buildSqlMappingHints(question, activeTable, graphContext);
        Optional<Map<String, Object>> aiResult = cacheHit ? Optional.empty() : pythonAiService.textToSql(question, queryTableName, fields,
                previewRows, graphPath, graphSqlHints, safeOptions);
        ensureNotCancelled("SQL 生成");

        String generatedSql;
        String chartType;
        Map<String, Object> fieldMapping;
        String engine;
        String fallbackReason = null;

        if (cacheHit) {
            generatedSql = Objects.toString(cachedSqlAudit.getOrDefault("cachedSql", ""), "").trim();
            RuleBasedNl2SqlStrategy.FieldChoice fieldChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
            chartType = ruleBasedNl2SqlStrategy.chooseChartType(question, fieldChoice.dimensionType());
            fieldMapping = fallbackFieldMapping(fieldChoice);
            engine = "redis-semantic-cache";
            generationTrace.add("nl2sqlEngine=redis-semantic-cache");
        } else if (aiResult.isPresent()) {
            try {
                Map<String, Object> ai = aiResult.get();
                generatedSql = Objects.toString(ai.get("sql"), "").trim();
                chartType = Objects.toString(ai.getOrDefault("chartType", "bar"), "bar").trim();
                fieldMapping = safeFieldMapping(ai.get("fieldMapping"));
                if (generatedSql.isBlank()) {
                    throw new IllegalArgumentException("AI 返回 SQL 为空");
                }
                if (chartType.isBlank()) {
                    chartType = "bar";
                }
                engine = "python-ai-service";
                generationTrace.add("nl2sqlEngine=python-ai-service");
            } catch (Exception parseEx) {
                log.warn("AI 返回内容解析失败，切换 Java 兜底策略: {}", parseEx.getMessage());
                RuleBasedNl2SqlStrategy.FieldChoice fieldChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
                chartType = ruleBasedNl2SqlStrategy.chooseChartType(question, fieldChoice.dimensionType());
                generatedSql = ruleBasedNl2SqlStrategy.buildSql(queryTableName, fieldChoice, chartType);
                fieldMapping = fallbackFieldMapping(fieldChoice);
                engine = "java-fallback-ai-parse";
                fallbackReason = "AI_RESPONSE_INVALID";
                generationTrace.add("nl2sqlEngine=java-fallback-ai-parse;reason=" + fallbackReason);
            }
        } else {
            RuleBasedNl2SqlStrategy.FieldChoice fieldChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
            chartType = ruleBasedNl2SqlStrategy.chooseChartType(question, fieldChoice.dimensionType());
            generatedSql = ruleBasedNl2SqlStrategy.buildSql(queryTableName, fieldChoice, chartType);
            fieldMapping = fallbackFieldMapping(fieldChoice);
            engine = "java-fallback";
            fallbackReason = "AI_UNAVAILABLE";
            generationTrace.add("nl2sqlEngine=java-fallback;reason=" + fallbackReason);
        }

        log.info("Generated SQL: {}", generatedSql);
        ensureNotCancelled("SQL 审计前");

        SqlAuditService.AuditResult auditResult = sqlAuditService.inspect(generatedSql, activeTable);
        if (auditResult.blocked()) {
            sqlAuditService.recordDetailed(question, activeTable, engine, generatedSql, auditResult,
                    "BLOCKED", 0L, auditResult.riskReason(), auditDetails(generationTrace, graphContext,
                            graphSqlHints, cacheKey, cacheHit, cachedSqlAudit, generatedSql, "",
                            "notExecuted=true", "BLOCKED"));
            throw new IllegalArgumentException("SQL 安全审计未通过：" + auditResult.riskReason());
        }

        SqlAuditService.QueryGuardResult guard = sqlAuditService.guardSqlBeforeExecution(generatedSql, activeTable, 200);
        generatedSql = guard.sql();
        generationTrace.add("queryGuard=" + guard.action() + ";" + guard.detail());
        if ("BLOCKED".equals(guard.action())) {
            SqlAuditService.AuditResult guardAudit = SqlAuditService.AuditResult.blocked("执行前熔断：" + guard.detail(),
                    List.of("MAX_SCAN_ROWS"), auditResult.sensitiveFields());
            sqlAuditService.recordDetailed(question, activeTable, engine, generatedSql, guardAudit,
                    "BLOCKED", 0L, guardAudit.riskReason(), auditDetails(generationTrace, graphContext,
                            graphSqlHints, cacheKey, cacheHit, cachedSqlAudit, generatedSql, "",
                            guard.detail(), guard.action()));
            throw new IllegalArgumentException("SQL 执行前熔断：" + guard.detail());
        }
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> queryResult;
        boolean fallbackExecuted = false;
        Integer previousTimeout = jdbcTemplate.getQueryTimeout();
        try (SqlAuditService.QueryPermit ignored = sqlAuditService.acquireQueryPermit("chat-bi")) {
            jdbcTemplate.setQueryTimeout(guard.timeoutSeconds());
            try {
                ensureNotCancelled("执行查询前");
                queryResult = officialSource
                        ? datasourceService.executeQueryWithoutAudit(activeTable, generatedSql)
                        : queryUploadTable(activeTable, generatedSql, guard.maxRows());
                ensureNotCancelled("执行查询后");
            } catch (RuntimeException executionError) {
                if (engine.startsWith("java-fallback")) {
                    throw executionError;
                }
                log.warn("AI SQL 执行失败，尝试 Java 规则兜底重试: {}", executionError.getMessage());
                ensureNotCancelled("兜底重试前");
                RuleBasedNl2SqlStrategy.FieldChoice fieldChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
                String fallbackChartType = ruleBasedNl2SqlStrategy.chooseChartType(question, fieldChoice.dimensionType());
                String fallbackSql = ruleBasedNl2SqlStrategy.buildSql(queryTableName, fieldChoice, fallbackChartType);
                String limitedFallbackSql = sqlAuditService.ensureLimit(fallbackSql, 200);
                SqlAuditService.AuditResult fallbackAudit = sqlAuditService.inspect(limitedFallbackSql, activeTable);
                if (fallbackAudit.blocked()) {
                    throw executionError;
                }
                generatedSql = limitedFallbackSql;
                chartType = fallbackChartType;
                fieldMapping = fallbackFieldMapping(fieldChoice);
                engine = "java-fallback-exec-retry";
                fallbackReason = "AI_SQL_EXEC_FAILED";
                fallbackExecuted = true;
                ensureNotCancelled("兜底重试执行前");
                queryResult = officialSource
                        ? datasourceService.executeQueryWithoutAudit(activeTable, generatedSql)
                        : queryUploadTable(activeTable, generatedSql, guard.maxRows());
                ensureNotCancelled("兜底重试执行后");
            }
            ensureNotCancelled("结果加工前");
            queryResult = attachDimensionKey(queryResult, fieldMapping);
            SqlAuditService.MaskReport maskReport = sqlAuditService.maskRowsWithReport(activeTable, queryResult);
            queryResult = maskReport.rows();
            if (isAllNullChartRows(queryResult)) {
                ensureNotCancelled("空结果恢复前");
                Map<String, Object> recovery = rebuildQueryFromTableProfile(activeTable, queryTableName, question,
                        fields, chartType);
                if (recovery != null) {
                    generatedSql = Objects.toString(recovery.getOrDefault("sql", generatedSql));
                    chartType = Objects.toString(recovery.getOrDefault("chartType", chartType));
                    fieldMapping = (Map<String, Object>) recovery.getOrDefault("fieldMapping", fieldMapping);
                    queryResult = (List<Map<String, Object>>) recovery.getOrDefault("data", queryResult);
                }
            }
            queryResult = normalizeChartRows(queryResult, chartType, fieldMapping);
            ensureNotCancelled("结果归一化后");
            long durationMs = System.currentTimeMillis() - startedAt;
            generationTrace.add("executeStatus=SUCCESS;durationMs=" + durationMs);
            sqlAuditService.recordDetailed(question, activeTable, engine, generatedSql, auditResult,
                    "SUCCESS", durationMs, null, auditDetails(generationTrace, graphContext, graphSqlHints, cacheKey,
                            cacheHit, cachedSqlAudit, generatedSql, maskReport.detail(),
                            guard.detail(), guard.action()));
        } catch (RuntimeException e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            generationTrace.add("executeStatus=FAILED;durationMs=" + durationMs + ";error=" + e.getMessage());
            sqlAuditService.recordDetailed(question, activeTable, engine, generatedSql, auditResult,
                    "FAILED", durationMs, e.getMessage(), auditDetails(generationTrace, graphContext, graphSqlHints,
                            cacheKey, cacheHit, cachedSqlAudit, generatedSql, "",
                            guard.detail(), guard.action()));
            throw e;
        } finally {
            jdbcTemplate.setQueryTimeout(previousTimeout);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("tableName", activeTable);
        response.put("physicalTableName", queryTableName);
        response.put("sourceType", officialSource ? "OFFICIAL" : "UPLOAD");
        response.put("sql", generatedSql);
        response.put("data", queryResult);
        Map<String, Object> chartRecommendation = recommendConfiguredChart(question, fields, queryResult, chartType);
        chartType = Objects.toString(chartRecommendation.getOrDefault("chartType", chartType), chartType);
        response.put("chartType", chartType);
        response.put("fieldMapping", fieldMapping);
        response.put("engine", engine);
        response.put("modelId", selectedModelId);
        response.put("modelName", selectedModelName);
        response.put("modelCategory", safeOptions.getOrDefault("modelCategory", ""));
        response.put("cacheHit", cacheHit);
        response.put("fallbackUsed", engine.startsWith("java-fallback") || fallbackExecuted);
        response.put("fallbackReason", fallbackReason);
        response.put("graphContext", graphContext);
        response.put("graphPath", graphPath);
        response.put("graphSqlHints", graphSqlHints);
        response.put("graphFallbackUsed", graphFallbackUsed);
        response.put("graphFallbackReason", graphFallbackReason);
        response.put("riskLevel", auditResult.riskLevel());
        response.put("riskReason", auditResult.riskReason());
        response.put("reasoningLogs", generationTrace);
        response.put("reasoningProcess", generationTrace);
        response.put("message", "分析完成。已基于字段「" + fieldMapping.getOrDefault("dimension", "未知维度")
                + "」和指标「" + fieldMapping.getOrDefault("metric", "记录数")
                + "」生成" + chartName(chartType) + "。");

        attachChartEncodingSpec(response, chartType, aiResult.orElse(null));
        applyConfiguredChartRecommendation(response, chartRecommendation);

        return response;
    }

    private Map<String, Object> recommendConfiguredChart(String question, List<Map<String, Object>> fields,
                                                         List<Map<String, Object>> rows, String fallbackChartType) {
        try {
            Map<String, Object> recommendation = new LinkedHashMap<>(
                    aiChartRuleConfigService.recommendForChatResult(question, fields, rows));
            recommendation.putIfAbsent("status", "CONFIGURED");
            return recommendation;
        } catch (Exception e) {
            log.warn("AI chart recommendation config failed, fallback to generated chartType {}: {}",
                    fallbackChartType, e.getMessage());
            return Map.of(
                    "chartType", fallbackChartType,
                    "status", "FALLBACK",
                    "explain", "AI chart recommendation config unavailable, using generated chart type."
            );
        }
    }

    @SuppressWarnings("unchecked")
    private void applyConfiguredChartRecommendation(Map<String, Object> response, Map<String, Object> recommendation) {
        if (recommendation == null || recommendation.isEmpty()) {
            return;
        }
        response.put("chartRecommendation", recommendation);
        response.put("chartRuleCode", recommendation.getOrDefault("ruleCode", ""));
        response.put("chartRuleName", recommendation.getOrDefault("ruleName", ""));
        response.put("chartScenarioType", recommendation.getOrDefault("scenarioType", ""));
        response.put("chartRecommendationStatus", recommendation.getOrDefault("status", ""));
        response.put("chartRecommendationExplain", recommendation.getOrDefault("explain", ""));
        response.put("voiceSummary", recommendation.getOrDefault("voiceSummary", Map.of()));
        Object template = recommendation.get("optionTemplate");
        if (template instanceof Map<?, ?> templateMap) {
            Map<String, Object> current = response.get("optionTemplate") instanceof Map<?, ?> currentMap
                    ? castToObjectMap(currentMap)
                    : Map.of();
            response.put("optionTemplate", deepMergeMaps(current, castToObjectMap(templateMap)));
        }
    }

    /**
     * 写入 chart_snapshot 用的 encode + optionTemplate（与前端 ECharts dataset 对齐）。
     * 查询结果经 normalize 后为 name/value 列；联邦快捷路径未调用此方法。
     */
    private void attachChartEncodingSpec(Map<String, Object> response, String chartType, Map<String, Object> aiRaw) {
        response.put("chartEngine", "echarts");
        response.put("dimensions", List.of("name", "value"));
        Map<String, Object> encode = defaultEncodeForChartType(chartType);
        if (aiRaw != null && aiRaw.get("encode") instanceof Map<?, ?> em) {
            encode.putAll(castToObjectMap(em));
        }
        response.put("encode", encode);
        Map<String, Object> template = defaultOptionTemplateForChartType(chartType);
        if (aiRaw != null && aiRaw.get("optionTemplate") instanceof Map<?, ?> tm) {
            template = deepMergeMaps(template, castToObjectMap(tm));
        }
        response.put("optionTemplate", template);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = Objects.toString(e.getKey(), "").trim();
            if (!k.isBlank()) {
                out.put(k, e.getValue());
            }
        }
        return out;
    }

    /** template 为底，overlay 覆盖同名键；嵌套 Map 递归合并；series 按下标合并每一项 */
    private Map<String, Object> deepMergeMaps(Map<String, Object> template, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(template);
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            Object base = out.get(k);
            if ("series".equals(k) && base instanceof List<?> bl && v instanceof List<?> vl) {
                List<Object> merged = new ArrayList<>();
                int n = Math.max(bl.size(), vl.size());
                for (int i = 0; i < n; i++) {
                    Object b = i < bl.size() ? bl.get(i) : null;
                    Object o = i < vl.size() ? vl.get(i) : null;
                    if (b instanceof Map<?, ?> bm && o instanceof Map<?, ?> om) {
                        merged.add(deepMergeMaps(castToObjectMap(bm), castToObjectMap(om)));
                    } else if (o != null) {
                        merged.add(o);
                    } else if (b != null) {
                        merged.add(b);
                    }
                }
                out.put(k, merged);
            } else if (base instanceof Map<?, ?> bm && v instanceof Map<?, ?> vm) {
                out.put(k, deepMergeMaps(castToObjectMap(bm), castToObjectMap(vm)));
            } else if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    private Map<String, Object> defaultEncodeForChartType(String chartType) {
        Map<String, Object> m = new LinkedHashMap<>();
        if ("pie".equalsIgnoreCase(chartType)) {
            m.put("itemName", "name");
            m.put("value", "value");
        } else {
            m.put("x", "name");
            m.put("y", "value");
        }
        return m;
    }

    private String resolvePreferredTableName(ChatQueryRequest request) {
        if (request == null) {
            return "";
        }
        List<String> tableNames = request.getTableNames();
        if (tableNames != null) {
            for (String tableName : tableNames) {
                String normalized = Objects.toString(tableName, "").trim();
                if (!normalized.isBlank()) {
                    return normalized;
                }
            }
        }
        Map<String, Object> filters = request.getFilters();
        if (filters != null) {
            String candidate = Objects.toString(filters.get("tableName"), "").trim();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private Map<String, Object> defaultOptionTemplateForChartType(String chartType) {
        Map<String, Object> template = new LinkedHashMap<>();
        if ("pie".equalsIgnoreCase(chartType)) {
            template.put("tooltip", Map.of("trigger", "item", "confine", true));
            Map<String, Object> s0 = new LinkedHashMap<>();
            s0.put("minAngle", 2);
            template.put("series", List.of(s0));
        } else if ("line".equalsIgnoreCase(chartType)) {
            template.put("tooltip", Map.of("trigger", "axis", "confine", true));
            template.put("grid", mapOfGrid());
            Map<String, Object> s0 = new LinkedHashMap<>();
            s0.put("smooth", true);
            template.put("series", List.of(s0));
        } else {
            template.put("tooltip", Map.of("trigger", "axis", "axisPointer", Map.of("type", "shadow"), "confine", true));
            template.put("grid", mapOfGrid());
            Map<String, Object> s0 = new LinkedHashMap<>();
            s0.put("barMaxWidth", 32);
            Map<String, Object> itemStyle = new LinkedHashMap<>();
            itemStyle.put("borderRadius", List.of(4, 4, 0, 0));
            s0.put("itemStyle", itemStyle);
            template.put("series", List.of(s0));
        }
        return template;
    }

    private Map<String, Object> mapOfGrid() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("left", 48);
        g.put("right", 12);
        g.put("top", 14);
        g.put("bottom", 56);
        return g;
    }

    private boolean isAllNullChartRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        return rows.stream().allMatch(row -> isEmptyChartValue(row, "name", "dim_name", "dimension")
                && isEmptyChartValue(row, "value", "metric_value", "metric"));
    }

    private boolean isEmptyChartValue(Map<String, Object> row, String... keys) {
        Object value = null;
        for (String key : keys) {
            if (row.containsKey(key)) {
                value = row.get(key);
                break;
            }
        }
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private List<Map<String, Object>> buildLocalFieldContext(String tableName, List<Map<String, Object>> fields) {
        List<Map<String, Object>> context = new ArrayList<>();
        if (tableName != null && !tableName.isBlank()) {
            Map<String, Object> tableNode = new HashMap<>();
            tableNode.put("nodeKey", "local_table:" + tableName);
            tableNode.put("nodeType", "TABLE");
            tableNode.put("label", tableName);
            tableNode.put("sourceType", "LOCAL");
            tableNode.put("sourceId", tableName);
            tableNode.put("content", "本地字段元信息");
            context.add(tableNode);
        }
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            String displayName = Objects.toString(field.get("displayName"), columnName);
            String fieldType = Objects.toString(field.get("fieldType"), "TEXT");
            String fieldComment = Objects.toString(field.get("fieldComment"), "").trim();
            String sensitive = formatSensitive(field.get("sensitive"));
            String content = "字段类型：" + fieldType + "；敏感：" + sensitive;
            if (!fieldComment.isBlank()) {
                content = content + "；" + fieldComment;
            }
            Map<String, Object> fieldNode = new HashMap<>();
            fieldNode.put("nodeKey", "local_field:" + tableName + ":" + columnName);
            fieldNode.put("nodeType", "FIELD");
            fieldNode.put("label", displayName);
            fieldNode.put("sourceType", "LOCAL");
            fieldNode.put("sourceId", tableName + "." + columnName);
            fieldNode.put("content", content);
            context.add(fieldNode);
        }
        return context;
    }

    private String formatSensitive(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "true" : "false";
        }
        if (value instanceof Number number) {
            return number.intValue() != 0 ? "true" : "false";
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return "false";
        }
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text)
                        ? "true"
                        : "false";
    }

    private Map<String, Object> auditDetails(List<String> generationTrace, List<Map<String, Object>> graphContext,
                                             Map<String, Object> graphSqlHints, String cacheKey, boolean cacheHit,
                                             Map<String, Object> cachedSqlAudit, String generatedSql,
                                             String maskDetail, String executionGuard, String queryGuardAction) {
        String kgLabels = graphContext == null ? "" : graphContext.stream()
                .limit(12)
                .map(node -> Objects.toString(node.getOrDefault("label", node.getOrDefault("nodeKey", "")), ""))
                .filter(text -> !text.isBlank())
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
        String hintKeys = graphSqlHints == null ? "" : graphSqlHints.keySet().stream()
                .map(Objects::toString)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("generationTrace", String.join("\n", generationTrace));
        details.put("kgMatchLog", "nodes=" + (graphContext == null ? 0 : graphContext.size())
                + "; labels=" + kgLabels + "; hintKeys=" + hintKeys);
        details.put("cacheKey", cacheKey);
        details.put("cacheHit", cacheHit);
        details.put("cacheSql", cacheHit ? Objects.toString(cachedSqlAudit.getOrDefault("cachedSql", generatedSql), generatedSql) : generatedSql);
        details.put("cacheAuditStatus", cacheHit ? Objects.toString(cachedSqlAudit.getOrDefault("riskLevel", "HIT")) : "MISS");
        details.put("redisStatus", cacheHit ? Objects.toString(cachedSqlAudit.getOrDefault("redisStatus", "LOCAL")) : "LOCAL");
        details.put("maskDetail", maskDetail);
        details.put("executionGuard", executionGuard);
        details.put("queryGuardAction", queryGuardAction);
        return details;
    }

    private Map<String, Object> rebuildQueryFromTableProfile(String activeTable, String queryTableName, String question,
            List<Map<String, Object>> fields, String chartType) {
        List<Map<String, Object>> previewRows = queryTablePreview(activeTable, queryTableName, 20);
        if (previewRows.isEmpty()) {
            return null;
        }
        Map<String, Object> best = inferBestColumnsFromPreview(question, fields, previewRows);
        String dimensionKey = Objects.toString(best.getOrDefault("dimensionKey", ""));
        String metricKey = Objects.toString(best.getOrDefault("metricKey", ""));
        if (dimensionKey.isBlank() || metricKey.isBlank()) {
            return null;
        }
        String sql = "SELECT `" + dimensionKey + "` AS dim_name, SUM(CAST(NULLIF(`" + metricKey
                + "`, '') AS DECIMAL(18,2))) AS metric_value "
                + "FROM `" + queryTableName + "` WHERE `" + dimensionKey + "` IS NOT NULL AND `" + dimensionKey
                + "` <> '' "
                + "GROUP BY `" + dimensionKey + "` ORDER BY metric_value DESC LIMIT 30";
        sql = sqlAuditService.applyDataRowPolicies(activeTable, sql);
        List<Map<String, Object>> data = jdbcTemplate.queryForList(sql);
        data = attachDimensionKey(data, Map.of("dimensionKey", dimensionKey));
        data = sqlAuditService.maskRows(activeTable, data);
        return Map.of(
                "sql", sql,
                "chartType", chartType,
                "fieldMapping", Map.of(
                        "dimension", best.getOrDefault("dimension", dimensionKey),
                        "metric", best.getOrDefault("metric", metricKey),
                        "dimensionKey", dimensionKey,
                        "metricKey", metricKey),
                "data", data);
    }

    private List<Map<String, Object>> queryTablePreview(String activeTable, String tableName, int limit) {
        String sql = "SELECT * FROM `" + tableName + "` LIMIT " + Math.max(1, Math.min(limit, 20));
        sql = sqlAuditService.applyDataRowPolicies(activeTable, sql);
        return jdbcTemplate.queryForList(sql);
    }

    private Map<String, Object> inferBestColumnsFromPreview(String question, List<Map<String, Object>> fields,
            List<Map<String, Object>> previewRows) {
        Map<String, Object> result = new HashMap<>();
        if (fields == null || fields.isEmpty() || previewRows.isEmpty()) {
            return result;
        }
        List<String> orderedColumns = new java.util.ArrayList<>(previewRows.get(0).keySet());
        String dimensionKey = null;
        String metricKey = null;
        for (Map<String, Object> field : fields) {
            String columnName = Objects.toString(field.get("columnName"), "");
            if (dimensionKey == null && isQuestionLikelyDimension(question, field)) {
                dimensionKey = columnName;
            }
            if (metricKey == null && isQuestionLikelyMetric(question, field)) {
                metricKey = columnName;
            }
        }
        if (dimensionKey == null) {
            dimensionKey = orderedColumns.stream().filter(col -> isMostlyText(previewRows, col)).findFirst()
                    .orElse(orderedColumns.get(0));
        }
        String finalDimensionKey = dimensionKey;
        if (metricKey == null) {
            metricKey = orderedColumns.stream()
                    .filter(col -> isMostlyNumeric(previewRows, col) && !col.equals(finalDimensionKey))
                    .findFirst()
                    .orElseGet(() -> orderedColumns.stream()
                            .filter(col -> !col.equals(finalDimensionKey))
                            .findFirst()
                            .orElse(finalDimensionKey));
        }
        result.put("dimensionKey", dimensionKey);
        result.put("metricKey", metricKey);
        result.put("dimension", dimensionKey);
        result.put("metric", metricKey);
        return result;
    }

    private boolean isQuestionLikelyDimension(String question, Map<String, Object> field) {
        String display = Objects.toString(field.get("displayName"), "");
        String comment = Objects.toString(field.get("fieldComment"), "");
        String column = Objects.toString(field.get("columnName"), "");
        String type = Objects.toString(field.get("fieldType"), "TEXT");
        if ("NUMBER".equals(type)) {
            return false;
        }
        return question.contains(display) || question.contains(column) || question.contains(comment)
                || display.contains("省") || display.contains("地区") || display.contains("分类") || display.contains("品类")
                || display.contains("城市")
                || column.matches(".*(province|region|city|category|type|name|kind|class).*?");
    }

    private boolean isQuestionLikelyMetric(String question, Map<String, Object> field) {
        String display = Objects.toString(field.get("displayName"), "");
        String comment = Objects.toString(field.get("fieldComment"), "");
        String column = Objects.toString(field.get("columnName"), "");
        String type = Objects.toString(field.get("fieldType"), "TEXT");
        if (!"NUMBER".equals(type)) {
            return false;
        }
        return question.contains(display) || question.contains(column) || question.contains(comment)
                || display.contains("销售") || display.contains("金额") || display.contains("数量") || display.contains("总额")
                || column.matches(".*(sales|amount|amt|total|count|qty|price|profit|revenue).*?");
    }

    private boolean isMostlyText(List<Map<String, Object>> rows, String column) {
        int sample = Math.min(rows.size(), 10);
        if (sample == 0)
            return false;
        int textCount = 0;
        for (int i = 0; i < sample; i++) {
            Object value = rows.get(i).get(column);
            if (value != null && !isNumericValue(value)) {
                textCount++;
            }
        }
        return textCount >= Math.max(1, sample / 2);
    }

    private boolean isMostlyNumeric(List<Map<String, Object>> rows, String column) {
        int sample = Math.min(rows.size(), 10);
        if (sample == 0)
            return false;
        int numericCount = 0;
        for (int i = 0; i < sample; i++) {
            if (isNumericValue(rows.get(i).get(column))) {
                numericCount++;
            }
        }
        return numericCount >= Math.max(1, sample / 2);
    }

    private boolean isNumericValue(Object value) {
        if (value == null)
            return false;
        if (value instanceof Number)
            return true;
        try {
            Double.parseDouble(Objects.toString(value).replace(",", "").trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> normalizeChartRows(List<Map<String, Object>> rows, String chartType,
            Map<String, Object> fieldMapping) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        if (rows.stream().allMatch(row -> row.containsKey("name") && row.containsKey("value"))) {
            return rows;
        }
        String dimensionKey = Objects.toString(fieldMapping.getOrDefault("dimensionKey", ""));
        String metricKey = Objects.toString(fieldMapping.getOrDefault("metricKey", ""));
        if (dimensionKey.isBlank() || metricKey.isBlank()) {
            Map<String, Object> firstRow = rows.get(0);
            if (dimensionKey.isBlank()) {
                dimensionKey = firstRow.keySet().stream().findFirst().orElse("name");
            }
            if (metricKey.isBlank()) {
                metricKey = firstRow.keySet().stream().skip(1).findFirst().orElse("value");
            }
        }
        String finalDimensionKey = dimensionKey;
        String finalMetricKey = metricKey;
        return rows.stream().map(row -> {
            Map<String, Object> normalized = new HashMap<>();
            Object dimensionValue = row.containsKey(finalDimensionKey) ? row.get(finalDimensionKey)
                    : row.getOrDefault("dim_name",
                            row.getOrDefault("name", row.getOrDefault("dimension", row.get(finalDimensionKey))));
            Object metricValue = row.containsKey(finalMetricKey) ? row.get(finalMetricKey)
                    : row.getOrDefault("metric_value",
                            row.getOrDefault("value", row.getOrDefault("metric", row.get(finalMetricKey))));
            normalized.put("name", dimensionValue);
            normalized.put("value", metricValue);
            return normalized;
        }).toList();
    }

    private String chartName(String chartType) {
        return chartType.equals("bar") ? "柱状图" : chartType.equals("pie") ? "饼图" : "折线图";
    }

    private List<Map<String, Object>> queryUploadTable(String sql) {
        ensureNotCancelled("上传表查询前");
        jdbcTemplate.setQueryTimeout(5);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        ensureNotCancelled("上传表查询后");
        return rows;
    }
    private List<Map<String, Object>> queryUploadTable(String tableName, String sql, int maxRows) {
        ensureNotCancelled("上传表查询前");
        Integer previousTimeout = jdbcTemplate.getQueryTimeout();
        try {
            jdbcTemplate.setQueryTimeout(5);
            int safeMaxRows = Math.max(1, Math.min(maxRows, 1000));
            String guardedSql = sqlAuditService.applyDataRowPolicies(tableName,
                    sqlAuditService.ensureLimit(sql, safeMaxRows));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(guardedSql);
            ensureNotCancelled("上传表查询后");
            return rows.size() <= safeMaxRows ? rows : rows.subList(0, safeMaxRows);
        } finally {
            jdbcTemplate.setQueryTimeout(previousTimeout);
        }
    }

    private Map<String, Object> safeFieldMapping(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> safe = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = Objects.toString(entry.getKey(), "").trim();
            if (!key.isBlank()) {
                safe.put(key, entry.getValue());
            }
        }
        return safe;
    }

    private Map<String, Object> fallbackFieldMapping(RuleBasedNl2SqlStrategy.FieldChoice fieldChoice) {
        return Map.of(
                "dimension", fieldChoice.dimensionDisplayName(),
                "metric", fieldChoice.metricDisplayName() == null ? "记录数" : fieldChoice.metricDisplayName(),
                "dimensionKey", fieldChoice.dimensionColumn(),
                "metricKey", fieldChoice.metricColumn() == null ? "value" : fieldChoice.metricColumn());
    }

    private List<Map<String, Object>> attachDimensionKey(List<Map<String, Object>> rows, Map<String, Object> fieldMapping) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        String dimensionKey = Objects.toString(fieldMapping.getOrDefault("dimensionKey", ""), "").trim();
        if (dimensionKey.isBlank()) {
            return rows;
        }
        List<Map<String, Object>> annotated = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new HashMap<>(row);
            if (!copy.containsKey(dimensionKey)) {
                Object aliasValue = firstNonBlankValue(copy, "dim_name", "name", "dimension");
                if (aliasValue != null) {
                    copy.put(dimensionKey, aliasValue);
                }
            }
            annotated.add(copy);
        }
        return annotated;
    }

    private Object firstNonBlankValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                Object value = row.get(key);
                if (value == null) {
                    continue;
                }
                if (value instanceof String text && text.trim().isEmpty()) {
                    continue;
                }
                return value;
            }
        }
        return null;
    }

    private void ensureNotCancelled(String stage) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户已停止生成（" + stage + "）");
        }
    }
}
