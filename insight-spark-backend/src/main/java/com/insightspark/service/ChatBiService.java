package com.insightspark.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.Locale;

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

    @Autowired
    private ObjectProvider<AdvancedAnalysisService> advancedAnalysisServiceProvider;

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

        SemanticSqlCorrection semanticCorrection = applySemanticSqlGuard(question, queryTableName, fields,
                generatedSql, chartType, fieldMapping, generationTrace);
        generatedSql = semanticCorrection.sql();
        chartType = semanticCorrection.chartType();
        fieldMapping = semanticCorrection.fieldMapping();

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
        Map<String, Object> chartRecommendation = Map.of();
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
            chartRecommendation = recommendConfiguredChart(question, fields, queryResult, chartType);
            chartType = Objects.toString(chartRecommendation.getOrDefault("chartType", chartType), chartType);
            if ("table".equalsIgnoreCase(chartType) && shouldRequeryDetailTable(queryResult)) {
                RuleBasedNl2SqlStrategy.FieldChoice detailChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
                String detailSql = sqlAuditService.ensureLimit(
                        ruleBasedNl2SqlStrategy.buildSql(queryTableName, detailChoice, "table"), 200);
                SqlAuditService.AuditResult detailAudit = sqlAuditService.inspect(detailSql, activeTable);
                if (!detailAudit.blocked()) {
                    ensureNotCancelled("明细表格重查前");
                    generatedSql = detailSql;
                    fieldMapping = fallbackFieldMapping(detailChoice);
                    queryResult = officialSource
                            ? datasourceService.executeQueryWithoutAudit(activeTable, generatedSql)
                            : queryUploadTable(activeTable, generatedSql, guard.maxRows());
                    queryResult = sqlAuditService.maskRowsWithReport(activeTable, queryResult).rows();
                    generationTrace.add("detailTableRequery=APPLIED");
                } else {
                    generationTrace.add("detailTableRequery=SKIPPED;reason=" + detailAudit.riskReason());
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
        response.put("chartType", chartType);
        response.put("fieldMapping", fieldMapping);
        if ("table".equalsIgnoreCase(chartType)) {
            response.put("tableColumns", buildTableColumns(queryResult, fieldMapping));
            response.put("tableRows", queryResult);
        }
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
        if (!Boolean.FALSE.equals(safeOptions.get("autoForecastEnabled"))) {
            applyAutoForecastIfNeeded(response, chartRecommendation, fieldMapping, activeTable, question, generationTrace);
        } else {
            generationTrace.add("autoForecast=SKIPPED;reason=SMART_QUERY_INTENT");
        }

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

    private void applyAutoForecastIfNeeded(Map<String, Object> response,
                                           Map<String, Object> recommendation,
                                           Map<String, Object> fieldMapping,
                                           String tableName,
                                           String question,
                                           List<String> generationTrace) {
        List<Map<String, Object>> rows = asMapList(response.get("data"));
        if (isExplicitDetailIntent(question)) {
            generationTrace.add("autoForecast=SKIPPED;reason=DETAIL_INTENT");
            return;
        }
        if (!isExplicitForecastIntent(question)) {
            generationTrace.add("autoForecast=SKIPPED;reason=NO_EXPLICIT_FORECAST_INTENT");
            return;
        }
        if (!isPreviousResultForecastIntent(question)) {
            generationTrace.add("autoForecast=SKIPPED;reason=PREFER_REAL_SOURCE_FORECAST");
            return;
        }
        if (!shouldAutoRunForecast(response, recommendation, rows)) {
            return;
        }
        List<Map<String, Object>> sourceSeries = buildForecastSourceSeries(rows);
        if (!looksLikeTemporalSeries(sourceSeries)) {
            generationTrace.add("autoForecast=SKIPPED;reason=NON_TEMPORAL_SERIES");
            return;
        }
        if (sourceSeries.size() < 3) {
            generationTrace.add("autoForecast=SKIPPED;reason=INSUFFICIENT_POINTS;points=" + sourceSeries.size());
            return;
        }
        if (isAllZeroSeries(sourceSeries)) {
            response.put("autoForecastRejected", true);
            response.put("autoForecastRejectReason", "上一轮查询结果未包含有效数值，建议改用原始数据源重新预测。");
            generationTrace.add("autoForecast=SKIPPED;reason=ALL_ZERO_SERIES");
            return;
        }
        String metricLabel = Objects.toString(firstNonBlank(
                fieldMapping == null ? null : fieldMapping.get("metric"),
                fieldMapping == null ? null : fieldMapping.get("metricKey"),
                response.get("metricField")), "").trim();
        if (metricLabel.isBlank()) {
            generationTrace.add("autoForecast=SKIPPED;reason=UNKNOWN_METRIC");
            return;
        }
        try {
            Map<String, Object> prediction = predictionConfig(response, recommendation);
            Map<String, Object> forecastRequest = new LinkedHashMap<>();
            forecastRequest.put("tableName", tableName);
            forecastRequest.put("metric", metricLabel);
            forecastRequest.put("series", sourceSeries);
            forecastRequest.put("horizon", prediction.getOrDefault("horizon", 3));
            forecastRequest.put("algorithm", prediction.getOrDefault("algorithm", "Holt-Winters"));
            forecastRequest.put("sourceQuestion", question);
            AdvancedAnalysisService advancedAnalysisService = advancedAnalysisServiceProvider.getIfAvailable();
            if (advancedAnalysisService == null) {
                generationTrace.add("autoForecast=SKIPPED;reason=ADVANCED_SERVICE_UNAVAILABLE");
                return;
            }
            Map<String, Object> forecastResult = advancedAnalysisService.forecastFromSeries(forecastRequest);
            mergeForecastResult(response, forecastResult, fieldMapping, generationTrace);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            generationTrace.add("autoForecast=SKIPPED;reason=" + safeTraceValue(e.getMessage()));
            log.warn("Auto forecast enrichment skipped: {}", e.getMessage());
        }
    }

    private boolean shouldAutoRunForecast(Map<String, Object> response,
                                          Map<String, Object> recommendation,
                                          List<Map<String, Object>> rows) {
        if (!"line".equalsIgnoreCase(Objects.toString(response.get("chartType"), ""))) {
            return false;
        }
        if (rows == null || rows.isEmpty() || containsForecastRows(rows)) {
            return false;
        }
        if (!isPredictionEnabled(response, recommendation)) {
            return false;
        }
        String scenario = Objects.toString(response.getOrDefault("chartScenarioType",
                recommendation == null ? "" : recommendation.getOrDefault("scenarioType", "")), "");
        return "TIME_SERIES".equalsIgnoreCase(scenario) || "line".equalsIgnoreCase(Objects.toString(
                recommendation == null ? "" : recommendation.getOrDefault("chartType", ""), ""));
    }

    private boolean isExplicitForecastIntent(String question) {
        String text = Objects.toString(question, "");
        String lower = text.toLowerCase(Locale.ROOT);
        return containsAny(lower, "预测", "预估", "推算", "未来", "后面", "下个月", "下季度", "下一季度",
                "后续", "大概会到", "会到多少", "继续涨", "继续跌", "往后推", "趋势延伸")
                || lower.contains("forecast") || lower.contains("prediction");
    }

    private boolean isPreviousResultForecastIntent(String question) {
        String text = Objects.toString(question, "").toLowerCase(Locale.ROOT);
        return containsAny(text, "刚才", "上一轮", "上一次", "这个图", "这张图", "当前图", "这个走势",
                "基于这个", "基于刚才", "按刚才", "按这个", "用刚才", "用这个");
    }

    private boolean isExplicitDetailIntent(String question) {
        String text = Objects.toString(question, "");
        String lower = text.toLowerCase(Locale.ROOT);
        boolean asksMultipleFields = text.contains("显示") && (text.contains("、") || text.contains("，")
                || text.contains(",") || text.contains("和"));
        return text.contains("明细") || text.contains("详情") || text.contains("列表")
                || text.contains("表格") || text.contains("列出") || asksMultipleFields
                || lower.contains("detail");
    }

    private boolean isPredictionEnabled(Map<String, Object> response, Map<String, Object> recommendation) {
        Map<String, Object> prediction = predictionConfig(response, recommendation);
        return boolValue(prediction.get("enabled"), false);
    }

    private Map<String, Object> predictionConfig(Map<String, Object> response, Map<String, Object> recommendation) {
        Map<String, Object> prediction = new LinkedHashMap<>();
        Object responseTemplate = response == null ? null : response.get("optionTemplate");
        if (responseTemplate instanceof Map<?, ?> templateMap) {
            Object raw = templateMap.get("prediction");
            if (raw instanceof Map<?, ?> predictionMap) {
                prediction.putAll(castToObjectMap(predictionMap));
            }
        }
        Object recommendationTemplate = recommendation == null ? null : recommendation.get("optionTemplate");
        if (recommendationTemplate instanceof Map<?, ?> templateMap) {
            Object raw = templateMap.get("prediction");
            if (raw instanceof Map<?, ?> predictionMap) {
                prediction.putAll(castToObjectMap(predictionMap));
            }
        }
        return prediction;
    }

    private boolean containsForecastRows(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            boolean hasForecastColumns = row.containsKey("history")
                    || row.containsKey("forecast")
                    || row.containsKey("upper")
                    || row.containsKey("lower")
                    || row.containsKey("phase");
            boolean hasForecastValues = row.get("forecast") != null
                    || row.get("upper") != null
                    || row.get("lower") != null
                    || "forecast".equalsIgnoreCase(Objects.toString(row.get("phase"), ""));
            if (hasForecastColumns && hasForecastValues) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildForecastSourceSeries(List<Map<String, Object>> rows) {
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            String name = Objects.toString(firstNonBlankValue(row, "name", "dim_name", "dimension"), "").trim();
            Double value = toDouble(firstNonBlankValue(row, "value", "history", "metric_value", "metric", "amount", "total"));
            if (name.isBlank() || value == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("value", value);
            series.add(item);
        }
        return series;
    }

    private boolean looksLikeTemporalSeries(List<Map<String, Object>> series) {
        if (series == null || series.size() < 3) {
            return false;
        }
        int temporalCount = 0;
        for (Map<String, Object> item : series) {
            String name = Objects.toString(item.get("name"), "").trim();
            if (name.matches("\\d{4}([-/.年]\\d{1,2})?([-/.月]\\d{1,2})?.*")
                    || name.matches("\\d{4}-Q[1-4]")
                    || name.matches("\\d{4}-W\\d{1,2}")) {
                temporalCount++;
            }
        }
        return temporalCount >= Math.max(3, (int) Math.ceil(series.size() * 0.6D));
    }

    private boolean isAllZeroSeries(List<Map<String, Object>> series) {
        if (series == null || series.isEmpty()) {
            return true;
        }
        int numericCount = 0;
        for (Map<String, Object> item : series) {
            Double value = toDouble(item == null ? null : item.get("value"));
            if (value == null) {
                continue;
            }
            numericCount++;
            if (Math.abs(value) > 0.000001D) {
                return false;
            }
        }
        return numericCount == 0 || numericCount == series.size();
    }

    private void mergeForecastResult(Map<String, Object> response,
                                     Map<String, Object> forecastResult,
                                     Map<String, Object> fieldMapping,
                                     List<String> generationTrace) {
        Object rawSeries = forecastResult.get("series");
        if (!(rawSeries instanceof List<?> list) || list.isEmpty()) {
            generationTrace.add("autoForecast=SKIPPED;reason=EMPTY_FORECAST_SERIES");
            return;
        }
        List<Map<String, Object>> forecastRows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(castToObjectMap(raw));
            Object history = row.get("history");
            Object forecast = row.get("forecast");
            if (forecast != null || row.get("upper") != null || row.get("lower") != null) {
                row.put("phase", "forecast");
                row.put("value", forecast);
            } else {
                row.put("phase", "history");
                row.put("value", history);
            }
            forecastRows.add(row);
        }
        if (forecastRows.isEmpty() || !containsForecastRows(forecastRows)) {
            generationTrace.add("autoForecast=SKIPPED;reason=NO_FORECAST_VALUES");
            return;
        }
        response.put("data", forecastRows);
        response.put("chartType", "line");
        response.put("dimensions", List.of("name", "history", "forecast", "upper", "lower", "anomaly"));
        response.put("encode", Map.of("x", "name", "y", "value"));
        response.put("advancedAnalysisType", "forecast");
        response.put("autoForecast", true);
        response.put("forecastMeta", forecastMeta(forecastResult));
        Map<String, Object> mergedFieldMapping = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        mergedFieldMapping.putIfAbsent("mappingType", "forecast");
        mergedFieldMapping.putIfAbsent("timeField", forecastResult.getOrDefault("timeField", "query_result_dimension"));
        mergedFieldMapping.putIfAbsent("metricField", forecastResult.getOrDefault("metricField", mergedFieldMapping.get("metric")));
        response.put("fieldMapping", mergedFieldMapping);
        mergeForecastOptionTemplate(response, forecastResult);
        Object explanation = forecastResult.get("explanation");
        if (explanation != null) {
            response.put("forecastExplanation", explanation);
        }
        response.put("message", Objects.toString(response.getOrDefault("message", "分析完成。"), "分析完成。")
                + " 已根据时序预测规则生成预测曲线和置信区间。");
        generationTrace.add("autoForecast=APPLIED;points=" + forecastRows.size()
                + ";algorithm=" + forecastResult.getOrDefault("algorithm", ""));
    }

    private Map<String, Object> forecastMeta(Map<String, Object> forecastResult) {
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "algorithm", forecastResult.get("algorithm"));
        putIfPresent(meta, "confidence", forecastResult.get("confidence"));
        putIfPresent(meta, "granularity", forecastResult.get("granularity"));
        putIfPresent(meta, "timeField", forecastResult.get("timeField"));
        putIfPresent(meta, "metricField", forecastResult.get("metricField"));
        putIfPresent(meta, "dataQuality", forecastResult.get("dataQuality"));
        putIfPresent(meta, "algorithmParams", forecastResult.get("algorithmParams"));
        putIfPresent(meta, "cacheHit", forecastResult.get("cacheHit"));
        return meta;
    }

    private void mergeForecastOptionTemplate(Map<String, Object> response, Map<String, Object> forecastResult) {
        if (!(forecastResult.get("optionTemplate") instanceof Map<?, ?> forecastTemplateMap)) {
            return;
        }
        Map<String, Object> current = response.get("optionTemplate") instanceof Map<?, ?> currentMap
                ? castToObjectMap(currentMap)
                : Map.of();
        response.put("optionTemplate", deepMergeMaps(current, castToObjectMap(forecastTemplateMap)));
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

    private record SemanticSqlCorrection(String sql, String chartType, Map<String, Object> fieldMapping) {
    }

    private SemanticSqlCorrection applySemanticSqlGuard(String question, String queryTableName,
            List<Map<String, Object>> fields, String generatedSql, String chartType,
            Map<String, Object> fieldMapping, List<String> generationTrace) {
        SemanticSqlCorrection dataProfileCorrection = applyGeoValueDataProfileGuard(question, queryTableName,
                fields, generatedSql, chartType, fieldMapping, generationTrace);
        if (!Objects.equals(dataProfileCorrection.sql(), generatedSql)) {
            return dataProfileCorrection;
        }

        SemanticSqlCorrection valueFilterCorrection = applyValueFilterDimensionConsistencyGuard(question, queryTableName,
                fields, generatedSql, chartType, fieldMapping, generationTrace);
        if (!Objects.equals(valueFilterCorrection.sql(), generatedSql)) {
            return valueFilterCorrection;
        }

        Set<String> macroRegionValues = extractMacroRegionValues(question);
        if (macroRegionValues.isEmpty() || fields == null || fields.isEmpty()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        Map<String, Object> regionField = findMacroRegionField(fields);
        if (regionField == null) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        String regionColumn = fieldColumn(regionField);
        boolean provinceBreakdown = asksProvinceBreakdown(question);
        Map<String, Object> provinceField = provinceBreakdown ? findProvinceField(fields) : null;
        Map<String, Object> dimensionField = provinceField == null ? regionField : provinceField;
        String dimensionColumn = fieldColumn(dimensionField);
        String currentDimension = Objects.toString(fieldMapping == null ? "" : fieldMapping.get("dimensionKey"), "").trim();
        if (regionColumn.isBlank() || dimensionColumn.isBlank()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        if (!provinceBreakdown && regionColumn.equals(currentDimension)) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        Map<String, Object> metricField = findMetricField(fields, fieldMapping, question);
        if (metricField == null) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        String metricColumn = fieldColumn(metricField);
        if (metricColumn.isBlank()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `").append(dimensionColumn).append("` AS dim_name, ")
                .append("SUM(CAST(NULLIF(`").append(metricColumn).append("`, '') AS DECIMAL(18,2))) AS metric_value ")
                .append("FROM `").append(queryTableName).append("` WHERE `").append(dimensionColumn)
                .append("` IS NOT NULL AND `").append(dimensionColumn).append("` <> '' ");
        sql.append("AND (");
        int index = 0;
        for (String value : macroRegionValues) {
            if (index++ > 0) {
                sql.append(" OR ");
            }
            String escapedValue = escapeSqlLiteral(value);
            sql.append("`").append(regionColumn).append("` = '").append(escapedValue).append("'")
                    .append(" OR `").append(regionColumn).append("` LIKE '%").append(escapedValue).append("%'");
        }
        sql.append(") ");
        appendRecentTimeFilter(sql, queryTableName, fields, question);
        sql.append("GROUP BY `").append(dimensionColumn).append("` ORDER BY metric_value DESC LIMIT 30");

        Map<String, Object> correctedMapping = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        correctedMapping.put("dimension", fieldDisplayName(dimensionField));
        correctedMapping.put("dimensionKey", dimensionColumn);
        correctedMapping.put("dimensionExpr", "`" + dimensionColumn + "`");
        correctedMapping.put("metric", fieldDisplayName(metricField));
        correctedMapping.put("metricKey", metricColumn);
        correctedMapping.put("metricExpr",
                "SUM(CAST(NULLIF(`" + metricColumn + "`, '') AS DECIMAL(18,2)))");

        String reason = provinceField == null
                ? "MACRO_REGION_FIELD_CORRECTION"
                : "MACRO_REGION_FILTER_WITH_PROVINCE_DIMENSION";
        generationTrace.add("semanticSqlGuard=APPLIED;reason=" + reason + ";from="
                + currentDimension + ";to=" + dimensionColumn + ";filter=" + regionColumn
                + ";values=" + String.join(",", macroRegionValues));
        return new SemanticSqlCorrection(sql.toString(), "bar", correctedMapping);
    }

    private SemanticSqlCorrection applyGeoValueDataProfileGuard(String question, String queryTableName,
            List<Map<String, Object>> fields, String generatedSql, String chartType,
            Map<String, Object> fieldMapping, List<String> generationTrace) {
        if (fields == null || fields.isEmpty()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        Set<String> values = extractGeoFilterValues(generatedSql);
        if (values.isEmpty()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        List<Map<String, Object>> geoFields = fields.stream()
                .filter(this::isGeoDimensionField)
                .toList();
        if (geoFields.isEmpty()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        Map<String, Object> metricField = findMetricField(fields, fieldMapping, question);
        if (metricField == null || fieldColumn(metricField).isBlank()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        List<GeoValueBinding> bindings = new ArrayList<>();
        for (String value : values) {
            GeoValueBinding binding = resolveGeoValueBinding(queryTableName, geoFields, value);
            if (binding != null) {
                bindings.add(binding);
            }
        }
        if (bindings.isEmpty() || bindings.size() < values.size()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        String existingDimension = parseSelectedDimensionColumn(generatedSql);
        String targetDimension = bindings.get(0).column();
        boolean sameColumn = bindings.stream().allMatch(binding -> binding.column().equals(targetDimension));
        boolean alreadyConsistent = sameColumn && targetDimension.equals(existingDimension)
                && generatedSql.contains("`" + targetDimension + "` =");
        if (alreadyConsistent && usesDataMaxRecentWindow(generatedSql)) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }

        String metricColumn = fieldColumn(metricField);
        StringBuilder sql = new StringBuilder();
        if (sameColumn) {
            sql.append("SELECT `").append(targetDimension).append("` AS dim_name, ")
                    .append("SUM(CAST(NULLIF(`").append(metricColumn).append("`, '') AS DECIMAL(18,2))) AS metric_value ")
                    .append("FROM `").append(queryTableName).append("` WHERE `").append(targetDimension)
                    .append("` IS NOT NULL AND `").append(targetDimension).append("` <> '' ");
            appendValueFilter(sql, targetDimension, values);
            appendRecentTimeFilter(sql, queryTableName, fields, question);
            sql.append("GROUP BY `").append(targetDimension).append("` ORDER BY metric_value DESC LIMIT 30");
        } else {
            sql.append("SELECT dim_name, SUM(metric_value) AS metric_value FROM (");
            for (int i = 0; i < bindings.size(); i++) {
                if (i > 0) {
                    sql.append(" UNION ALL ");
                }
                GeoValueBinding binding = bindings.get(i);
                sql.append("SELECT '").append(escapeSqlLiteral(binding.value())).append("' AS dim_name, ")
                        .append("SUM(CAST(NULLIF(`").append(metricColumn).append("`, '') AS DECIMAL(18,2))) AS metric_value ")
                        .append("FROM `").append(queryTableName).append("` WHERE ");
                appendSingleValuePredicate(sql, binding.column(), binding.value());
                appendRecentTimeFilter(sql, queryTableName, fields, question);
            }
            sql.append(") geo_compare GROUP BY dim_name ORDER BY metric_value DESC LIMIT 30");
        }

        Map<String, Object> dimensionField = sameColumn
                ? findFieldByColumn(fields, targetDimension)
                : Map.of("displayName", "对比对象", "columnName", "dim_name");
        Map<String, Object> correctedMapping = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        correctedMapping.put("dimension", sameColumn ? fieldDisplayName(dimensionField) : "对比对象");
        correctedMapping.put("dimensionKey", sameColumn ? targetDimension : "dim_name");
        correctedMapping.put("dimensionExpr", sameColumn ? "`" + targetDimension + "`" : "dim_name");
        correctedMapping.put("metric", fieldDisplayName(metricField));
        correctedMapping.put("metricKey", metricColumn);
        correctedMapping.put("metricExpr",
                "SUM(CAST(NULLIF(`" + metricColumn + "`, '') AS DECIMAL(18,2)))");

        generationTrace.add("semanticSqlGuard=APPLIED;reason=GEO_VALUE_DATA_PROFILE;bindings="
                + bindings.stream().map(binding -> binding.value() + "->" + binding.column())
                        .reduce((left, right) -> left + "," + right).orElse(""));
        return new SemanticSqlCorrection(sql.toString(), chartType == null || chartType.isBlank() ? "bar" : chartType,
                correctedMapping);
    }

    private record GeoValueBinding(String value, String column) {
    }

    private Set<String> extractGeoFilterValues(String sql) {
        Set<String> values = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)`[^`]+`\\s*(?:=|like)\\s*'([^']{1,80})'")
                .matcher(Objects.toString(sql, ""));
        while (matcher.find()) {
            String value = matcher.group(1).replace("%", "").trim();
            if (!value.isBlank() && !isSqlControlLiteral(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private GeoValueBinding resolveGeoValueBinding(String tableName, List<Map<String, Object>> geoFields, String value) {
        Map<String, Object> bestField = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map<String, Object> field : geoFields) {
            String column = fieldColumn(field);
            if (column.isBlank()) {
                continue;
            }
            int matchScore = geoValueExists(tableName, column, value, false) ? 1000
                    : geoValueExists(tableName, column, value, true) ? 500 : 0;
            if (matchScore <= 0) {
                continue;
            }
            int score = matchScore + geoFieldSpecificityScore(field);
            if (score > bestScore) {
                bestScore = score;
                bestField = field;
            }
        }
        return bestField == null ? null : new GeoValueBinding(value, fieldColumn(bestField));
    }

    private boolean geoValueExists(String tableName, String column, String value, boolean fuzzy) {
        String predicate = fuzzy
                ? "`" + column + "` LIKE '%" + escapeSqlLiteral(value) + "%'"
                : "`" + column + "` = '" + escapeSqlLiteral(value) + "'";
        String sql = "SELECT 1 FROM `" + tableName + "` WHERE " + predicate + " LIMIT 1";
        try {
            return !jdbcTemplate.queryForList(sql).isEmpty();
        } catch (Exception e) {
            log.debug("地理值探测失败 table={}, column={}, value={}, fuzzy={}: {}",
                    tableName, column, value, fuzzy, e.getMessage());
            return false;
        }
    }

    private int geoFieldSpecificityScore(Map<String, Object> field) {
        String haystack = fieldSemanticText(field);
        if (containsAny(haystack, "city", "城市", "市")) {
            return 40;
        }
        if (containsAny(haystack, "province", "prov", "state", "省份", "省市", "省")) {
            return 30;
        }
        if (containsAny(haystack, "region", "area", "zone", "district", "区域", "大区", "地区")) {
            return 20;
        }
        return 0;
    }

    private SemanticSqlCorrection applyValueFilterDimensionConsistencyGuard(String question, String queryTableName,
            List<Map<String, Object>> fields, String generatedSql, String chartType,
            Map<String, Object> fieldMapping, List<String> generationTrace) {
        if (!extractMacroRegionValues(question).isEmpty() || fields == null || fields.isEmpty()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        String dimensionColumn = parseSelectedDimensionColumn(generatedSql);
        if (dimensionColumn.isBlank() && fieldMapping != null) {
            dimensionColumn = Objects.toString(fieldMapping.get("dimensionKey"), "").trim();
        }
        if (dimensionColumn.isBlank()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        Map<String, Object> dimensionField = findFieldByColumn(fields, dimensionColumn);
        if (dimensionField == null || !isGeoDimensionField(dimensionField)) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        FilterValueMatch mismatchedFilter = findMismatchedGeoValueFilter(generatedSql, fields, dimensionColumn);
        if (mismatchedFilter == null || mismatchedFilter.values().isEmpty()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        Set<String> values = mismatchedFilter.values();
        Map<String, Object> metricField = findMetricField(fields, fieldMapping, question);
        if (metricField == null || fieldColumn(metricField).isBlank()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }

        String metricColumn = fieldColumn(metricField);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `").append(dimensionColumn).append("` AS dim_name, ")
                .append("SUM(CAST(NULLIF(`").append(metricColumn).append("`, '') AS DECIMAL(18,2))) AS metric_value ")
                .append("FROM `").append(queryTableName).append("` WHERE `").append(dimensionColumn)
                .append("` IS NOT NULL AND `").append(dimensionColumn).append("` <> '' ");
        appendValueFilter(sql, dimensionColumn, values);
        appendRecentTimeFilter(sql, queryTableName, fields, question);
        sql.append("GROUP BY `").append(dimensionColumn).append("` ORDER BY metric_value DESC LIMIT 30");

        Map<String, Object> correctedMapping = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        correctedMapping.put("dimension", fieldDisplayName(dimensionField));
        correctedMapping.put("dimensionKey", dimensionColumn);
        correctedMapping.put("dimensionExpr", "`" + dimensionColumn + "`");
        correctedMapping.put("metric", fieldDisplayName(metricField));
        correctedMapping.put("metricKey", metricColumn);
        correctedMapping.put("metricExpr",
                "SUM(CAST(NULLIF(`" + metricColumn + "`, '') AS DECIMAL(18,2)))");

        generationTrace.add("semanticSqlGuard=APPLIED;reason=VALUE_FILTER_DIMENSION_CONSISTENCY;fromFilter="
                + mismatchedFilter.column() + ";toFilter=" + dimensionColumn + ";values=" + String.join(",", values));
        return new SemanticSqlCorrection(sql.toString(), chartType == null || chartType.isBlank() ? "bar" : chartType,
                correctedMapping);
    }

    private record FilterValueMatch(String column, Set<String> values) {
    }

    private void appendValueFilter(StringBuilder sql, String column, Set<String> values) {
        sql.append("AND (");
        int index = 0;
        for (String value : values) {
            if (index++ > 0) {
                sql.append(" OR ");
            }
            String escapedValue = escapeSqlLiteral(value);
            sql.append("`").append(column).append("` = '").append(escapedValue).append("'")
                    .append(" OR `").append(column).append("` LIKE '%").append(escapedValue).append("%'");
        }
        sql.append(") ");
    }

    private void appendSingleValuePredicate(StringBuilder sql, String column, String value) {
        String escapedValue = escapeSqlLiteral(value);
        sql.append("(`").append(column).append("` = '").append(escapedValue).append("'")
                .append(" OR `").append(column).append("` LIKE '%").append(escapedValue).append("%') ");
    }

    private void appendRecentTimeFilter(StringBuilder sql, String tableName, List<Map<String, Object>> fields,
            String question) {
        Map<String, Object> timeField = findTimeField(fields);
        if (timeField == null || !hasRecentTimeSemantics(question)) {
            return;
        }
        String timeColumn = fieldColumn(timeField);
        if (timeColumn.isBlank()) {
            return;
        }
        sql.append("AND `").append(timeColumn).append("` >= DATE_SUB((SELECT MAX(`")
                .append(timeColumn).append("`) FROM `").append(tableName).append("`), INTERVAL 90 DAY) ");
    }

    private boolean usesDataMaxRecentWindow(String sql) {
        String normalized = Objects.toString(sql, "").toLowerCase(Locale.ROOT);
        return normalized.contains("select max(") && normalized.contains("interval 90 day");
    }

    private String escapeSqlLiteral(String value) {
        return Objects.toString(value, "").replace("'", "''");
    }

    private String parseSelectedDimensionColumn(String sql) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)select\\s+`([^`]+)`\\s+as\\s+(?:dim_name|name)")
                .matcher(Objects.toString(sql, ""));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private FilterValueMatch findMismatchedGeoValueFilter(String sql, List<Map<String, Object>> fields,
            String dimensionColumn) {
        Map<String, Set<String>> valuesByColumn = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)`([^`]+)`\\s*(?:=|like)\\s*'([^']{1,80})'")
                .matcher(Objects.toString(sql, ""));
        while (matcher.find()) {
            String column = matcher.group(1).trim();
            String value = matcher.group(2).replace("%", "").trim();
            if (column.equals(dimensionColumn) || value.isBlank() || isSqlControlLiteral(value)) {
                continue;
            }
            Map<String, Object> filterField = findFieldByColumn(fields, column);
            if (filterField == null || !isGeoDimensionField(filterField)) {
                continue;
            }
            valuesByColumn.computeIfAbsent(column, ignored -> new LinkedHashSet<>()).add(value);
        }
        return valuesByColumn.entrySet().stream()
                .findFirst()
                .map(entry -> new FilterValueMatch(entry.getKey(), entry.getValue()))
                .orElse(null);
    }

    private boolean isSqlControlLiteral(String value) {
        String text = Objects.toString(value, "").trim();
        return text.isBlank() || text.matches("[-+]?\\d+(?:\\.\\d+)?")
                || text.length() > 32
                || text.contains("%Y") || text.contains("%m") || text.contains("%d");
    }

    private Map<String, Object> findFieldByColumn(List<Map<String, Object>> fields, String column) {
        return fields.stream()
                .filter(field -> column.equals(fieldColumn(field)))
                .findFirst()
                .orElse(null);
    }

    private boolean isGeoDimensionField(Map<String, Object> field) {
        if ("NUMBER".equalsIgnoreCase(Objects.toString(field.get("fieldType"), ""))) {
            return false;
        }
        String haystack = fieldSemanticText(field);
        return containsAny(haystack, "city", "province", "prov", "state", "region", "area",
                "城市", "市", "省份", "省市", "省", "区域", "大区", "地区");
    }

    private Set<String> extractMacroRegionValues(String question) {
        String text = Objects.toString(question, "");
        Set<String> values = new LinkedHashSet<>();
        for (String value : List.of("华东", "华南", "华北", "华中", "中南", "西南", "西北", "东北", "东南", "港澳台")) {
            if (text.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, Object> findMacroRegionField(List<Map<String, Object>> fields) {
        return fields.stream()
                .filter(field -> !"NUMBER".equalsIgnoreCase(Objects.toString(field.get("fieldType"), "")))
                .map(field -> Map.entry(field, macroRegionFieldScore(field)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private boolean asksProvinceBreakdown(String question) {
        String text = Objects.toString(question, "");
        return text.contains("各省") || text.contains("省份") || text.contains("分省")
                || text.contains("省级") || text.contains("按省") || text.contains("省排名");
    }

    private Map<String, Object> findProvinceField(List<Map<String, Object>> fields) {
        return fields.stream()
                .filter(field -> !"NUMBER".equalsIgnoreCase(Objects.toString(field.get("fieldType"), "")))
                .map(field -> Map.entry(field, provinceFieldScore(field)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private int provinceFieldScore(Map<String, Object> field) {
        String haystack = fieldSemanticText(field);
        int score = 0;
        if (containsAny(haystack, "province", "prov", "state", "省份", "省市", "省")) {
            score += 120;
        }
        if (containsAny(haystack, "region", "area", "zone", "district", "区域", "大区", "片区")) {
            score -= 160;
        }
        if (containsAny(haystack, "city", "城市", "市")) {
            score -= 100;
        }
        return score;
    }

    private int macroRegionFieldScore(Map<String, Object> field) {
        String haystack = fieldSemanticText(field);
        int score = 0;
        if (containsAny(haystack, "region", "area", "zone", "district", "territory")) {
            score += 120;
        }
        if (containsAny(haystack, "区域", "大区", "片区", "战区", "地区")) {
            score += 110;
        }
        if (containsAny(haystack, "province", "prov", "state", "省份", "省市", "省")) {
            score -= 180;
        }
        if (containsAny(haystack, "city", "城市", "市")) {
            score -= 120;
        }
        return score;
    }

    private Map<String, Object> findMetricField(List<Map<String, Object>> fields, Map<String, Object> fieldMapping,
            String question) {
        String mappedMetric = Objects.toString(fieldMapping == null ? "" : fieldMapping.get("metricKey"), "").trim();
        if (!mappedMetric.isBlank()) {
            Optional<Map<String, Object>> mapped = fields.stream()
                    .filter(field -> mappedMetric.equals(fieldColumn(field)))
                    .filter(this::isNumericField)
                    .findFirst();
            if (mapped.isPresent()) {
                return mapped.get();
            }
        }
        return fields.stream()
                .filter(this::isNumericField)
                .map(field -> Map.entry(field, metricFieldScore(field, question)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseGet(() -> fields.stream().filter(this::isNumericField).findFirst().orElse(null));
    }

    private int metricFieldScore(Map<String, Object> field, String question) {
        String haystack = fieldSemanticText(field);
        String text = Objects.toString(question, "");
        int score = 0;
        if (containsAny(haystack, "sales_amt", "sales", "amount", "amt", "revenue", "gmv", "income")) {
            score += 120;
        }
        if (containsAny(haystack, "销售额", "销售", "金额", "收入", "营收", "订单金额")) {
            score += 120;
        }
        if (text.contains(fieldDisplayName(field)) || text.contains(fieldColumn(field))) {
            score += 80;
        }
        return score;
    }

    private Map<String, Object> findTimeField(List<Map<String, Object>> fields) {
        return fields.stream()
                .map(field -> Map.entry(field, timeFieldScore(field)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private int timeFieldScore(Map<String, Object> field) {
        String haystack = fieldSemanticText(field);
        int score = "DATE".equalsIgnoreCase(Objects.toString(field.get("fieldType"), "")) ? 120 : 0;
        if (containsAny(haystack, "date", "time", "day", "month", "year", "日期", "时间", "月份", "年月", "年度")) {
            score += 80;
        }
        return score;
    }

    private boolean hasRecentTimeSemantics(String question) {
        String text = Objects.toString(question, "");
        return text.contains("最近") || text.contains("近") || text.contains("近期") || text.contains("这段时间");
    }

    private boolean isNumericField(Map<String, Object> field) {
        return "NUMBER".equalsIgnoreCase(Objects.toString(field.get("fieldType"), ""));
    }

    private String fieldColumn(Map<String, Object> field) {
        return Objects.toString(field.get("columnName"), "").trim();
    }

    private String fieldDisplayName(Map<String, Object> field) {
        String displayName = Objects.toString(field.get("displayName"), "").trim();
        if (!displayName.isBlank()) {
            return displayName;
        }
        String sourceFieldName = Objects.toString(field.get("sourceFieldName"), "").trim();
        return sourceFieldName.isBlank() ? fieldColumn(field) : sourceFieldName;
    }

    private String fieldSemanticText(Map<String, Object> field) {
        return (Objects.toString(field.get("columnName"), "") + " "
                + Objects.toString(field.get("displayName"), "") + " "
                + Objects.toString(field.get("sourceFieldName"), "") + " "
                + Objects.toString(field.get("fieldComment"), "")).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
        if ("table".equalsIgnoreCase(chartType)) {
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

    private boolean shouldRequeryDetailTable(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return true;
        }
        Map<String, Object> first = rows.get(0);
        if (first == null || first.isEmpty()) {
            return true;
        }
        List<String> keys = first.keySet().stream().map(key -> Objects.toString(key, "").toLowerCase()).toList();
        return keys.contains("dim_name") || keys.contains("metric_value")
                || (keys.contains("name") && keys.contains("value") && keys.size() <= 3);
    }

    private String chartName(String chartType) {
        return chartType.equals("bar") ? "柱状图"
                : chartType.equals("pie") || chartType.equals("doughnut") ? "饼图"
                : chartType.equals("table") ? "表格" : "折线图";
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
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("dimension", fieldChoice.dimensionDisplayName());
        mapping.put("metric", fieldChoice.metricDisplayName() == null ? "记录数" : fieldChoice.metricDisplayName());
        mapping.put("dimensionKey", fieldChoice.dimensionColumn());
        mapping.put("metricKey", fieldChoice.metricColumn() == null ? "value" : fieldChoice.metricColumn());
        mapping.put("tableColumns", fieldChoice.tableColumns());
        mapping.put("fieldResolution", fieldChoice.resolutionLog());
        return mapping;
    }

    private List<Map<String, Object>> buildTableColumns(List<Map<String, Object>> rows, Map<String, Object> fieldMapping) {
        List<String> configured = fieldMapping.get("tableColumns") instanceof List<?> list
                ? list.stream().map(item -> Objects.toString(item, "").trim()).filter(item -> !item.isBlank()).toList()
                : List.of();
        List<String> keys = new ArrayList<>();
        if (!configured.isEmpty()) {
            keys.addAll(configured);
        }
        if (rows != null && !rows.isEmpty()) {
            for (String key : rows.get(0).keySet()) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys.stream()
                .filter(key -> rows == null || rows.isEmpty() || rows.get(0).containsKey(key))
                .map(key -> Map.<String, Object>of("prop", key, "label", resolveTableColumnLabel(key, fieldMapping)))
                .toList();
    }

    private String resolveTableColumnLabel(String key, Map<String, Object> fieldMapping) {
        if (fieldMapping.get("fieldResolution") instanceof Map<?, ?> resolution
                && resolution.get("tableColumnLabels") instanceof Map<?, ?> labels) {
            String label = Objects.toString(labels.get(key), "").trim();
            if (!label.isBlank()) {
                return label;
            }
        }
        return key;
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

    private Object firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.trim().isEmpty()) {
                continue;
            }
            return value;
        }
        return "";
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String text && text.trim().isEmpty())) {
            target.put(key, value);
        }
    }

    private boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "y", "on", "是", "启用").contains(text)) {
            return true;
        }
        if (List.of("false", "0", "no", "n", "off", "否", "停用").contains(text)) {
            return false;
        }
        return fallback;
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String raw = Objects.toString(value, "").trim();
        if (raw.isBlank()) {
            return null;
        }
        String normalized = raw
                .replace(",", "")
                .replace("，", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace("$", "")
                .replace("元", "")
                .trim();
        double multiplier = 1D;
        if (normalized.contains("%")) {
            multiplier = 0.01D;
            normalized = normalized.replace("%", "");
        }
        if (normalized.contains("亿")) {
            multiplier *= 100000000D;
            normalized = normalized.replace("亿", "");
        }
        if (normalized.contains("万")) {
            multiplier *= 10000D;
            normalized = normalized.replace("万", "");
        }
        if (normalized.contains("千")) {
            multiplier *= 1000D;
            normalized = normalized.replace("千", "");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith("k")) {
            multiplier *= 1000D;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (lower.endsWith("w")) {
            multiplier *= 10000D;
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[-+]?\\d+(?:\\.\\d+)?")
                .matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(matcher.group()) * multiplier;
            return Double.isFinite(parsed) ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String safeTraceValue(Object value) {
        String text = Objects.toString(value, "").replaceAll("[\\r\\n;]+", " ").trim();
        return text.isBlank() ? "UNKNOWN" : text.substring(0, Math.min(text.length(), 120));
    }

    private void ensureNotCancelled(String stage) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户已停止生成（" + stage + "）");
        }
    }
}
