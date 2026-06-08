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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatBiService {

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String eventType, String title, String detail, Map<String, Object> metadata);

        static ProgressListener noop() {
            return (eventType, title, detail, metadata) -> {
            };
        }
    }

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
    private BusinessSemanticService businessSemanticService;

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
        response.put("filters", publicFilters(safeRequest.getFilters()));
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
        ProgressListener progress = progressListener(safeOptions.get("progressListener"));
        String selectedModelId = Objects.toString(safeOptions.getOrDefault("modelId", "gpt-4"), "gpt-4").trim();
        String selectedModelName = Objects.toString(safeOptions.getOrDefault("modelName", selectedModelId), selectedModelId).trim();

        String activeTable = (tableName == null || tableName.isBlank()) ? dataUploadService.latestTableName()
                : tableName;
        emitProgress(progress, "DATA_SOURCE_READY", "确认数据源",
                "将基于 " + activeTable + " 执行本次查询", Map.of("tableName", activeTable));
        List<String> generationTrace = new ArrayList<>();
        generationTrace.add("activeTable=" + activeTable);
        generationTrace.add("selectedModel=" + selectedModelId);
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
        emitProgress(progress, "FIELD_META_READY", "读取字段元数据",
                "已加载 " + fields.size() + " 个字段，正在匹配指标、维度和时间口径", Map.of("fieldCount", fields.size()));
        ensureNotCancelled("字段元信息加载");
        BusinessSemanticService.BusinessSemanticContext businessSemanticContext =
                businessSemanticService.resolveContext(activeTable, safeOptions, fields);
        BusinessSemanticService.BusinessSemanticPlan businessSemanticPlan =
                businessSemanticService.resolvePlan(question, businessSemanticContext);
        Map<String, Object> businessSemanticTrace = new LinkedHashMap<>(businessSemanticPlan.trace());
        generationTrace.add(businessSemanticContext.available()
                ? "businessSemanticContext=LOADED;modelId=" + businessSemanticContext.modelId()
                        + ";source=" + businessSemanticContext.source()
                : "businessSemanticContext=EMPTY");
        generationTrace.add(businessSemanticPlan.hasSemanticConstraint()
                ? "businessSemanticPlan=MATCHED;" + businessSemanticTrace
                : "businessSemanticPlan=NO_MATCH");
        String cacheKey = sqlAuditService.semanticCacheKey(question, activeTable,
                businessSemanticContext.available()
                        ? "businessModel=" + businessSemanticContext.modelId()
                                + ";version=" + businessSemanticContext.modelVersion()
                        : "businessModel=none");
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
        graphContext = filterGraphContextForCurrentQuery(question, activeTable, fields, "", graphContext);
        emitProgress(progress, "GRAPH_CONTEXT_READY", "匹配语义上下文",
                graphFallbackUsed
                        ? "图谱上下文不足，已回退到本地字段语义，共 " + graphContext.size() + " 个候选"
                        : "已匹配 " + graphContext.size() + " 个图谱语义候选",
                Map.of("candidateCount", graphContext.size(), "fallbackUsed", graphFallbackUsed));
        ensureNotCancelled("图谱上下文准备");
        List<Map<String, Object>> previewRows = dataUploadService.preview(activeTable, 1, 8);
        ensureNotCancelled("样例数据预览");
        Map<String, Object> graphSqlHints = new LinkedHashMap<>(
                knowledgeGraphService.buildSqlMappingHints(question, activeTable, graphContext));
        graphSqlHints.put("businessSemanticTrace", businessSemanticTrace);
        Map<String, Object> modelOptions = new LinkedHashMap<>(safeOptions);
        modelOptions.put("businessSemanticTrace", businessSemanticTrace);
        emitProgress(progress, "NL2SQL_START", "生成查询语义",
                cacheHit ? "命中语义缓存，正在复用已审计 SQL" : "正在结合字段、样例数据和图谱提示生成 SQL",
                Map.of("cacheHit", cacheHit));
        Optional<Map<String, Object>> aiResult = cacheHit ? Optional.empty() : pythonAiService.textToSql(question, queryTableName, fields,
                previewRows, graphPath, graphSqlHints, modelOptions);
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
        BusinessSemanticService.BusinessSqlCorrection businessCorrection =
                businessSemanticService.enforceSql(question, queryTableName, generatedSql, chartType, fieldMapping,
                        businessSemanticPlan);
        generatedSql = businessCorrection.sql();
        chartType = businessCorrection.chartType();
        fieldMapping = businessCorrection.fieldMapping();
        businessSemanticTrace = new LinkedHashMap<>(businessCorrection.trace());
        if (!Objects.toString(businessCorrection.reason(), "").isBlank()) {
            generationTrace.add("businessSemanticGuard=" + businessCorrection.reason()
                    + ";changed=" + businessCorrection.changed());
        }
        SemanticSqlCorrection sortCorrection = applySortIntentGuard(question, generatedSql, chartType, fieldMapping,
                generationTrace);
        generatedSql = sortCorrection.sql();
        chartType = sortCorrection.chartType();
        fieldMapping = sortCorrection.fieldMapping();
        fieldMapping = alignFieldMappingWithSql(question, fields, generatedSql, fieldMapping, generationTrace);
        graphContext = filterGraphContextForCurrentQuery(question, activeTable, fields, generatedSql, graphContext);

        log.info("Generated SQL: {}", generatedSql);
        emitProgress(progress, "SQL_GENERATED", "生成查询语句",
                "已生成 " + chartName(chartType) + " 所需 SQL，维度 "
                        + Objects.toString(fieldMapping.getOrDefault("dimension", "未知维度"), "未知维度")
                        + "，指标 " + Objects.toString(fieldMapping.getOrDefault("metric", "记录数"), "记录数"),
                Map.of("chartType", chartType, "engine", engine));
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
        emitProgress(progress, "SQL_AUDITED", "完成安全审计",
                "SQL 审计 " + auditResult.riskLevel() + "，执行保护策略 " + guard.action(),
                Map.of("riskLevel", auditResult.riskLevel(), "guardAction", guard.action()));
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> queryResult;
        boolean fallbackExecuted = false;
        Map<String, Object> chartRecommendation = Map.of();
        Integer previousTimeout = jdbcTemplate.getQueryTimeout();
        try (SqlAuditService.QueryPermit ignored = sqlAuditService.acquireQueryPermit("chat-bi")) {
            jdbcTemplate.setQueryTimeout(guard.timeoutSeconds());
            try {
                ensureNotCancelled("执行查询前");
                emitProgress(progress, "QUERY_EXECUTING", "执行查询",
                        "正在数据库中执行只读查询，最大返回 " + guard.maxRows() + " 行",
                        Map.of("maxRows", guard.maxRows(), "timeoutSeconds", guard.timeoutSeconds()));
                queryResult = officialSource
                        ? datasourceService.executeQueryWithinPermit(activeTable, generatedSql)
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
                BusinessSemanticService.BusinessSqlCorrection retryBusinessCorrection =
                        businessSemanticService.enforceSql(question, queryTableName, generatedSql, chartType, fieldMapping,
                                businessSemanticPlan);
                generatedSql = retryBusinessCorrection.sql();
                chartType = retryBusinessCorrection.chartType();
                fieldMapping = retryBusinessCorrection.fieldMapping();
                businessSemanticTrace = new LinkedHashMap<>(retryBusinessCorrection.trace());
                if (!Objects.toString(retryBusinessCorrection.reason(), "").isBlank()) {
                    generationTrace.add("businessSemanticGuardRetry=" + retryBusinessCorrection.reason()
                            + ";changed=" + retryBusinessCorrection.changed());
                }
                SemanticSqlCorrection retrySortCorrection = applySortIntentGuard(question, generatedSql,
                        chartType, fieldMapping, generationTrace);
                generatedSql = retrySortCorrection.sql();
                chartType = retrySortCorrection.chartType();
                fieldMapping = retrySortCorrection.fieldMapping();
                engine = "java-fallback-exec-retry";
                fallbackReason = "AI_SQL_EXEC_FAILED";
                fallbackExecuted = true;
                ensureNotCancelled("兜底重试执行前");
                queryResult = officialSource
                        ? datasourceService.executeQueryWithinPermit(activeTable, generatedSql)
                        : queryUploadTable(activeTable, generatedSql, guard.maxRows());
                ensureNotCancelled("兜底重试执行后");
            }
            ensureNotCancelled("结果加工前");
            fieldMapping = alignFieldMappingWithSqlAndRows(question, fields, generatedSql, queryResult, fieldMapping,
                    generationTrace);
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
                    SemanticSqlCorrection recoverySortCorrection = applySortIntentGuard(question, generatedSql,
                            chartType, fieldMapping, generationTrace);
                    generatedSql = recoverySortCorrection.sql();
                    chartType = recoverySortCorrection.chartType();
                    fieldMapping = recoverySortCorrection.fieldMapping();
                    queryResult = (List<Map<String, Object>>) recovery.getOrDefault("data", queryResult);
                }
            }
            chartRecommendation = recommendConfiguredChart(question, fields, queryResult, chartType);
            chartType = Objects.toString(chartRecommendation.getOrDefault("chartType", chartType), chartType);
            Map<String, Object> chartPolicyMeta = new LinkedHashMap<>();
            chartPolicyMeta.put("chartType", chartType);
            chartPolicyMeta.put("recommendationStatus", Objects.toString(chartRecommendation.get("status"), ""));
            emitProgress(progress, "CHART_POLICY_READY", "匹配图表偏好",
                    "已根据管理员图表偏好选择 " + chartName(chartType),
                    chartPolicyMeta);
            if ("table".equalsIgnoreCase(chartType) && shouldRequeryDetailTable(queryResult)
                    && !businessSemanticPlan.hasSemanticConstraint()) {
                RuleBasedNl2SqlStrategy.FieldChoice detailChoice = ruleBasedNl2SqlStrategy.chooseFields(question, fields);
                String detailSql = sqlAuditService.ensureLimit(
                        ruleBasedNl2SqlStrategy.buildSql(queryTableName, detailChoice, "table"), 200);
                SqlAuditService.AuditResult detailAudit = sqlAuditService.inspect(detailSql, activeTable);
                if (!detailAudit.blocked()) {
                    ensureNotCancelled("明细表格重查前");
                    generatedSql = detailSql;
                    fieldMapping = fallbackFieldMapping(detailChoice);
                    queryResult = officialSource
                            ? datasourceService.executeQueryWithinPermit(activeTable, generatedSql)
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
            emitProgress(progress, "QUERY_RESULT_READY", "查询结果就绪",
                    "已返回 " + queryResult.size() + " 行结果，用时 " + durationMs + "ms",
                    Map.of("rowCount", queryResult.size(), "durationMs", durationMs));
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
        response.put("semanticEvidence", buildSemanticEvidence(question, fields, fieldMapping, generatedSql,
                graphContext, graphSqlHints, businessSemanticTrace));
        response.put("chartSortMode", Objects.toString(fieldMapping.getOrDefault("chartSortMode", "name"), "name"));
        response.put("sortIntent", Objects.toString(fieldMapping.getOrDefault("sortIntent", "NAME_ASC"), "NAME_ASC"));
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
        response.put("businessSemanticTrace", businessSemanticTrace);
        response.put("graphFallbackUsed", graphFallbackUsed);
        response.put("graphFallbackReason", graphFallbackReason);
        response.put("riskLevel", auditResult.riskLevel());
        response.put("riskReason", auditResult.riskReason());
        response.put("sensitiveFields", auditResult.sensitiveFields());
        response.put("matchedRules", auditResult.matchedRules());
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

    private List<Map<String, Object>> buildSemanticEvidence(String question, List<Map<String, Object>> fields,
            Map<String, Object> fieldMapping, String generatedSql, List<Map<String, Object>> graphContext,
            Map<String, Object> graphSqlHints, Map<String, Object> businessSemanticTrace) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String metricKey = Objects.toString(fieldMapping == null ? "" : fieldMapping.get("metricKey"), "").trim();
        String dimensionKey = Objects.toString(fieldMapping == null ? "" : fieldMapping.get("dimensionKey"), "").trim();
        String timeKey = semanticTimeFieldKey(question, fields, fieldMapping, generatedSql);

        addFieldEvidence(evidence, seen, "指标", metricKey, fields, fieldMapping, question, generatedSql,
                "本次聚合指标", businessSemanticTrace, graphSqlHints);
        if (!dimensionKey.isBlank() && !dimensionKey.equals(metricKey) && !isMetricAlias(dimensionKey)) {
            String role = dimensionKey.equals(timeKey) || isTimeSeriesLikeQuestion(question) && dimensionKey.equals(timeKey)
                    ? "时间字段"
                    : "维度";
            addFieldEvidence(evidence, seen, role, dimensionKey, fields, fieldMapping, question, generatedSql,
                    "本次分组维度", businessSemanticTrace, graphSqlHints);
        }
        if (!timeKey.isBlank() && !timeKey.equals(metricKey) && !timeKey.equals(dimensionKey)) {
            addFieldEvidence(evidence, seen, "时间字段", timeKey, fields, fieldMapping, question, generatedSql,
                    hasRecentTimeSemantics(question) ? "用于最近时间窗口过滤" : "用于时间趋势排序/聚合",
                    businessSemanticTrace, graphSqlHints);
        }
        addFormulaEvidence(evidence, seen, fieldMapping, businessSemanticTrace, graphSqlHints);
        addDetailFieldEvidence(evidence, seen, question, fields, generatedSql, graphSqlHints);

        if (evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("matched")))
                .limit(6)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private void addFieldEvidence(List<Map<String, Object>> evidence, Set<String> seen, String role, String column,
            List<Map<String, Object>> fields, Map<String, Object> fieldMapping, String question, String generatedSql,
            String fallbackReason, Map<String, Object> businessSemanticTrace, Map<String, Object> graphSqlHints) {
        String fieldColumn = Objects.toString(column, "").trim();
        if (fieldColumn.isBlank() || !seen.add(role + ":" + fieldColumn)) {
            return;
        }
        Map<String, Object> field = findFieldByColumn(fields, fieldColumn);
        String label = semanticFieldLabel(role, fieldColumn, field, fieldMapping);
        List<String> reasons = semanticEvidenceReasons(role, question, generatedSql, fieldColumn, label, field,
                fallbackReason, businessSemanticTrace, graphSqlHints);
        if (reasons.isEmpty()) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("label", label);
        item.put("field", fieldColumn);
        item.put("fieldType", field == null ? "" : Objects.toString(field.get("fieldType"), ""));
        item.put("matched", true);
        item.put("reason", String.join("；", reasons));
        item.put("source", semanticEvidenceSource(fieldColumn, graphSqlHints));
        if (fieldMapping != null) {
            if ("指标".equals(role)) {
                putIfPresent(item, "expression", fieldMapping.get("metricExpr"));
                putIfPresent(item, "formula", fieldMapping.get("formula"));
            } else {
                putIfPresent(item, "expression", fieldMapping.get("dimensionExpr"));
            }
        }
        evidence.add(item);
    }

    private void addDetailFieldEvidence(List<Map<String, Object>> evidence, Set<String> seen, String question,
            List<Map<String, Object>> fields, String generatedSql, Map<String, Object> graphSqlHints) {
        if (fields == null || fields.isEmpty() || Objects.toString(generatedSql, "").isBlank()) {
            return;
        }
        boolean detailSql = isDetailProjectionSql(generatedSql);
        for (Map<String, Object> field : fields) {
            String column = fieldColumn(field);
            if (column.isBlank() || !sqlReferencesColumn(generatedSql, column)) {
                continue;
            }
            String label = fieldDisplayName(field);
            String semanticTerm = matchedQuestionFieldTerm("明细字段", question, column, label, field);
            boolean sensitive = isSensitiveField(field);
            if (!sensitive && (!detailSql || semanticTerm.isBlank())) {
                continue;
            }
            String role = sensitive ? "敏感字段" : "明细字段";
            if (!seen.add(role + ":" + column)) {
                continue;
            }
            String sourceFieldName = Objects.toString(field.get("sourceFieldName"), "").trim();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("label", label);
            item.put("field", column);
            item.put("fieldType", Objects.toString(field.get("fieldType"), ""));
            item.put("matched", true);
            item.put("sensitive", sensitive);
            item.put("sourceFieldName", sourceFieldName);
            List<String> reasons = new ArrayList<>();
            String term = semanticTerm.isBlank() ? label : semanticTerm;
            if (!term.isBlank()) {
                reasons.add("用户提到“" + term + "”，本次 SQL 使用 `" + column + "`");
            } else {
                reasons.add("本次 SQL 使用 `" + column + "`");
            }
            if (!sourceFieldName.isBlank() && !sourceFieldName.equals(column)) {
                reasons.add("字段映射：" + sourceFieldName + " -> " + column);
            }
            if (sensitive) {
                reasons.add("字段已标记 sensitive=true，按敏感字段访问纳入审计");
            }
            String graphReason = graphCandidateReason(column, graphSqlHints);
            if (!graphReason.isBlank()) {
                reasons.add(graphReason);
            }
            item.put("reason", String.join("；", reasons.stream().distinct().limit(4).toList()));
            item.put("source", sensitive ? "SQL 审计 + 字段映射" : semanticEvidenceSource(column, graphSqlHints));
            evidence.add(item);
        }
    }

    private void addFormulaEvidence(List<Map<String, Object>> evidence, Set<String> seen,
            Map<String, Object> fieldMapping, Map<String, Object> businessSemanticTrace,
            Map<String, Object> graphSqlHints) {
        String formula = Objects.toString(fieldMapping == null ? "" : firstNonBlank(
                fieldMapping.get("formula"),
                businessSemanticTrace == null ? "" : businessSemanticTrace.get("analysisFormula")), "").trim();
        if (formula.isBlank() || !seen.add("公式:" + formula)) {
            return;
        }
        String metric = Objects.toString(fieldMapping.getOrDefault("metric",
                businessSemanticTrace == null ? "" : businessSemanticTrace.get("matchedMetric")), "").trim();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", "指标公式");
        item.put("label", metric.isBlank() ? "业务公式" : metric);
        item.put("field", Objects.toString(fieldMapping.getOrDefault("metricKey", ""), ""));
        item.put("matched", true);
        item.put("reason", "业务模型定义了该指标公式，本次查询按公式口径生成 SQL。");
        item.put("formula", formula);
        item.put("source", graphSqlHints == null || graphSqlHints.isEmpty() ? "业务模型" : "业务模型 + GraphRAG");
        evidence.add(item);
    }

    private List<String> semanticEvidenceReasons(String role, String question, String generatedSql, String column,
            String label, Map<String, Object> field, String fallbackReason, Map<String, Object> businessSemanticTrace,
            Map<String, Object> graphSqlHints) {
        List<String> reasons = new ArrayList<>();
        String text = Objects.toString(question, "");
        String lower = text.toLowerCase(Locale.ROOT);
        String colLower = column.toLowerCase(Locale.ROOT);
        if (!label.isBlank() && text.contains(label)) {
            reasons.add("用户提到「" + label + "」，命中 " + column);
        } else if (lower.contains(colLower)) {
            reasons.add("用户提到字段「" + column + "」");
        } else {
            String semanticTerm = matchedQuestionFieldTerm(role, text, column, label, field);
            if (!semanticTerm.isBlank()) {
                String target = label.isBlank() ? column : label;
                reasons.add("用户语义「" + semanticTerm + "」匹配「" + target + "」，命中 " + column);
            }
        }
        String matchedMetric = Objects.toString(businessSemanticTrace == null ? "" : businessSemanticTrace.get("matchedMetric"), "").trim();
        String metricColumn = Objects.toString(businessSemanticTrace == null ? "" : businessSemanticTrace.get("metricColumn"), "").trim();
        if (!matchedMetric.isBlank() && column.equals(metricColumn)) {
            reasons.add("业务模型将「" + matchedMetric + "」解析为 " + column);
        }
        String matchedDimension = Objects.toString(businessSemanticTrace == null ? "" : businessSemanticTrace.get("matchedDimension"), "").trim();
        String dimensionColumn = Objects.toString(businessSemanticTrace == null ? "" : businessSemanticTrace.get("dimensionColumn"), "").trim();
        if (!matchedDimension.isBlank() && column.equals(dimensionColumn)) {
            reasons.add("业务模型将「" + matchedDimension + "」解析为 " + column);
        }
        String graphReason = graphCandidateReason(column, graphSqlHints);
        if (!graphReason.isBlank()) {
            reasons.add(graphReason);
        }
        String sql = Objects.toString(generatedSql, "");
        if (shouldUseSqlEvidenceFallback(role, text, column, label, field) && sql.contains("`" + column + "`")
                && reasons.stream().noneMatch(reason -> reason.contains("SQL"))) {
            reasons.add(fallbackReason + "，SQL 已引用 `" + column + "`");
        }
        return reasons.stream().distinct().limit(3).toList();
    }

    private boolean shouldUseSqlEvidenceFallback(String role, String question, String column, String label,
            Map<String, Object> field) {
        if ("指标".equals(role)) {
            return true;
        }
        if ("时间字段".equals(role)) {
            return hasTimeEvidenceIntent(question) || (field != null && timeFieldScore(field) > 0);
        }
        if ("维度".equals(role)) {
            return hasDimensionEvidenceIntent(question, column, label, field);
        }
        return false;
    }

    private boolean hasDimensionEvidenceIntent(String question, String column, String label, Map<String, Object> field) {
        String text = Objects.toString(question, "");
        String lower = text.toLowerCase(Locale.ROOT);
        if (!matchedQuestionFieldTerm("维度", text, column, label, field).isBlank()) {
            return true;
        }
        return containsAny(lower, "按", "各", "每个", "每一", "分别", "分组", "分布", "拆分", "排名",
                "排行", "榜单", "前", "后", "最高", "最低", "最多", "最少", "对比", "比较", "相比",
                "vs", "top", "rank", "ranking", "by ");
    }

    private boolean hasTimeEvidenceIntent(String question) {
        String text = Objects.toString(question, "");
        return isTimeSeriesLikeQuestion(text) || hasRecentTimeSemantics(text)
                || containsAny(text, "今年", "去年", "前年", "明年", "本年", "本月", "上月", "下月",
                        "昨天", "今天", "明天", "近月", "近年", "同比", "环比");
    }

    private String matchedQuestionFieldTerm(String role, String question, String column, String label,
            Map<String, Object> field) {
        String text = Objects.toString(question, "");
        String lower = text.toLowerCase(Locale.ROOT);
        for (String candidate : List.of(label, column,
                field == null ? "" : fieldDisplayName(field),
                field == null ? "" : Objects.toString(field.get("sourceFieldName"), "").trim())) {
            String term = Objects.toString(candidate, "").trim();
            if (!term.isBlank() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                return term;
            }
        }
        return matchedSemanticAliasTerm(role, text, field);
    }

    private String matchedSemanticAliasTerm(String role, String question, Map<String, Object> field) {
        String lower = Objects.toString(question, "").toLowerCase(Locale.ROOT);
        String haystack = field == null ? "" : fieldSemanticText(field);
        if ("指标".equals(role)) {
            if (containsAny(haystack, "sales_amt", "sales", "amount", "amt", "revenue", "gmv", "income",
                    "销售额", "销售", "金额", "收入", "营收", "订单金额")
                    && containsAny(lower, "销售额", "销售", "收入", "营收", "订单金额", "gmv", "流水",
                            "revenue", "sales", "income")) {
                return firstMatchedTerm(lower, "销售额", "销售", "收入", "营收", "订单金额", "gmv", "流水",
                        "revenue", "sales", "income");
            }
            if (containsAny(haystack, "profit", "gross", "margin", "利润", "毛利", "毛利率")
                    && containsAny(lower, "利润", "毛利", "毛利率", "profit", "margin")) {
                return firstMatchedTerm(lower, "利润", "毛利", "毛利率", "profit", "margin");
            }
            if (containsAny(haystack, "qty", "quantity", "volume", "count", "销量", "数量", "订单数")
                    && containsAny(lower, "销量", "数量", "订单数", "件数", "qty", "quantity", "volume", "count")) {
                return firstMatchedTerm(lower, "销量", "数量", "订单数", "件数", "qty", "quantity", "volume", "count");
            }
        }
        if ("时间字段".equals(role) || (field != null && timeFieldScore(field) > 0)) {
            if (hasTimeEvidenceIntent(question)) {
                return firstMatchedTerm(lower, "每个月", "每月", "按月", "月度", "月份", "趋势", "走势",
                        "季度", "年度", "同比", "环比", "最近", "近期", "今年", "去年", "本年", "日期", "时间");
            }
        }
        if ("维度".equals(role)) {
            if (containsAny(haystack, "province", "prov", "state", "省份", "省市", "省")
                    && containsAny(lower, "各省", "省份", "省级", "按省", "分省", "省排名", "province")) {
                return firstMatchedTerm(lower, "各省", "省份", "省级", "按省", "分省", "省排名", "province");
            }
            if (containsAny(haystack, "city", "城市", "地市")
                    && containsAny(lower, "城市", "地市", "市级", "按城市", "分城市", "city")) {
                return firstMatchedTerm(lower, "城市", "地市", "市级", "按城市", "分城市", "city");
            }
            if (containsAny(haystack, "region", "area", "zone", "district", "区域", "大区", "地区")
                    && containsAny(lower, "区域", "大区", "地区", "华东", "华南", "华北", "华中", "中南",
                            "西南", "西北", "东北", "region", "area")) {
                return firstMatchedTerm(lower, "区域", "大区", "地区", "华东", "华南", "华北", "华中", "中南",
                        "西南", "西北", "东北", "region", "area");
            }
            if (containsAny(haystack, "product", "sku", "item", "产品", "商品", "品类", "类别")
                    && containsAny(lower, "产品", "商品", "品类", "类别", "sku", "product")) {
                return firstMatchedTerm(lower, "产品", "商品", "品类", "类别", "sku", "product");
            }
            if (containsAny(haystack, "customer", "client", "客户", "顾客")
                    && containsAny(lower, "客户", "顾客", "customer", "client")) {
                return firstMatchedTerm(lower, "客户", "顾客", "customer", "client");
            }
        }
        return "";
    }

    private String firstMatchedTerm(String text, String... candidates) {
        String lower = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            String term = Objects.toString(candidate, "").trim();
            if (!term.isBlank() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                return term;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String graphCandidateReason(String column, Map<String, Object> graphSqlHints) {
        if (graphSqlHints == null || graphSqlHints.isEmpty()) {
            return "";
        }
        Object candidates = graphSqlHints.get("fieldCandidates");
        if (!(candidates instanceof List<?> list)) {
            return "";
        }
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> candidate = castToObjectMap(map);
            String candidateColumn = Objects.toString(firstNonBlank(
                    candidate.get("columnName"), candidate.get("field"), candidate.get("column")), "").trim();
            if (!column.equals(candidateColumn)) {
                continue;
            }
            String matchReason = Objects.toString(candidate.getOrDefault("matchReason", ""), "").trim();
            return matchReason.isBlank()
                    ? "GraphRAG 候选字段命中 " + column
                    : "GraphRAG 候选字段命中 " + column + "（" + matchReason + "）";
        }
        return "";
    }

    private String semanticEvidenceSource(String column, Map<String, Object> graphSqlHints) {
        return graphCandidateReason(column, graphSqlHints).isBlank() ? "字段映射" : "GraphRAG + 字段映射";
    }

    private String semanticFieldLabel(String role, String column, Map<String, Object> field,
            Map<String, Object> fieldMapping) {
        if ("指标".equals(role) && fieldMapping != null) {
            String metric = Objects.toString(fieldMapping.get("metric"), "").trim();
            if (!metric.isBlank() && !isMetricAlias(metric)) {
                return metric;
            }
        }
        if ("维度".equals(role) && fieldMapping != null) {
            String dimension = Objects.toString(fieldMapping.get("dimension"), "").trim();
            if (!dimension.isBlank() && !"dim_name".equalsIgnoreCase(dimension) && !"name".equalsIgnoreCase(dimension)) {
                return dimension;
            }
        }
        if ("时间字段".equals(role) && fieldMapping != null) {
            String dimensionKey = Objects.toString(fieldMapping.get("dimensionKey"), "").trim();
            String dimension = Objects.toString(fieldMapping.get("dimension"), "").trim();
            if (column.equals(dimensionKey) && !dimension.isBlank()
                    && !"dim_name".equalsIgnoreCase(dimension) && !"name".equalsIgnoreCase(dimension)) {
                return dimension;
            }
        }
        return field == null ? column : fieldDisplayName(field);
    }

    private boolean isDetailProjectionSql(String sql) {
        String lower = Objects.toString(sql, "").toLowerCase(Locale.ROOT);
        return lower.startsWith("select ")
                && !lower.contains(" group by ")
                && !lower.matches("(?s).*\\b(sum|avg|count|max|min)\\s*\\(.*");
    }

    private boolean sqlReferencesColumn(String sql, String column) {
        String col = Objects.toString(column, "").trim();
        if (col.isBlank()) {
            return false;
        }
        String text = Objects.toString(sql, "");
        if (text.contains("`" + col + "`") || text.contains("\"" + col + "\"")) {
            return true;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(col) + "(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS).matcher(text).find();
    }

    private boolean isSensitiveField(Map<String, Object> field) {
        return "true".equals(formatSensitive(field == null ? null : field.get("sensitive")));
    }

    private List<Map<String, Object>> filterGraphContextForCurrentQuery(String question, String tableName,
            List<Map<String, Object>> fields, String generatedSql, List<Map<String, Object>> graphContext) {
        if (graphContext == null || graphContext.isEmpty()) {
            return List.of();
        }
        String table = Objects.toString(tableName, "").trim();
        Set<String> sqlColumns = referencedSqlColumns(generatedSql);
        List<String> terms = questionTerms(question);
        return graphContext.stream()
                .map(node -> Map.entry(node, graphContextScore(node, table, fields, sqlColumns, terms)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .map(Map.Entry::getKey)
                .toList();
    }

    private int graphContextScore(Map<String, Object> node, String tableName, List<Map<String, Object>> fields,
            Set<String> sqlColumns, List<String> questionTerms) {
        String nodeKey = Objects.toString(node.get("nodeKey"), "");
        String sourceId = Objects.toString(node.get("sourceId"), "");
        String label = Objects.toString(node.get("label"), "");
        String content = Objects.toString(node.get("content"), "");
        String type = Objects.toString(node.get("nodeType"), "").toUpperCase(Locale.ROOT);
        String sourceType = Objects.toString(node.get("sourceType"), "");
        String haystack = (nodeKey + " " + sourceId + " " + label + " " + content).toLowerCase(Locale.ROOT);
        int score = 0;
        if (!tableName.isBlank() && (nodeKey.contains(tableName) || sourceId.contains(tableName))) {
            score += 90;
        } else if ("TAG".equals(type)) {
            score += 15;
        } else if ("OFFICIAL".equalsIgnoreCase(sourceType)) {
            score -= 100;
        }
        for (String column : sqlColumns) {
            if (!column.isBlank() && haystack.contains(column.toLowerCase(Locale.ROOT))) {
                score += 70;
            }
        }
        List<Map<String, Object>> safeFields = fields == null ? List.of() : fields;
        for (Map<String, Object> field : safeFields) {
            String column = fieldColumn(field);
            if (sqlColumns.contains(column) && containsAny(haystack, column.toLowerCase(Locale.ROOT),
                    fieldDisplayName(field).toLowerCase(Locale.ROOT),
                    Objects.toString(field.get("sourceFieldName"), "").toLowerCase(Locale.ROOT))) {
                score += 45;
            }
        }
        for (String term : questionTerms) {
            if (!term.isBlank() && haystack.contains(term.toLowerCase(Locale.ROOT))) {
                score += 25;
            }
        }
        if (looksLikeMojibake(label) || looksLikeMojibake(content)) {
            score -= 120;
        }
        return score;
    }

    private Set<String> referencedSqlColumns(String sql) {
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("`([^`]+)`").matcher(Objects.toString(sql, ""));
        while (matcher.find()) {
            String col = matcher.group(1).trim();
            if (!col.isBlank() && !col.toLowerCase(Locale.ROOT).startsWith("biz_data_")) {
                columns.add(col);
            }
        }
        return columns;
    }

    private List<String> questionTerms(String question) {
        String text = Objects.toString(question, "").toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        Matcher matcher = Pattern.compile("[a-z0-9_]+|[\\u4e00-\\u9fa5]{2,}").matcher(text);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        return terms;
    }

    private boolean looksLikeMojibake(String value) {
        String text = Objects.toString(value, "");
        return text.contains("锛") || text.contains("鏁") || text.contains("瀹") || text.contains("绋")
                || text.contains("ç") || text.contains("æ") || text.contains("�");
    }

    private String semanticTimeFieldKey(String question, List<Map<String, Object>> fields,
            Map<String, Object> fieldMapping, String generatedSql) {
        String dimensionKey = Objects.toString(fieldMapping == null ? "" : fieldMapping.get("dimensionKey"), "").trim();
        Map<String, Object> dimensionField = findFieldByColumn(fields, dimensionKey);
        if (dimensionField != null && timeFieldScore(dimensionField) > 0) {
            return dimensionKey;
        }
        Map<String, Object> timeField = findTimeField(fields);
        String timeColumn = timeField == null ? "" : fieldColumn(timeField);
        if (!timeColumn.isBlank() && (isTimeSeriesLikeQuestion(question) || hasRecentTimeSemantics(question)
                || Objects.toString(generatedSql, "").contains("`" + timeColumn + "`"))) {
            return timeColumn;
        }
        return "";
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
            return applyTopNAfterSemanticGuard(question, queryTableName, fields, dataProfileCorrection, generationTrace);
        }

        SemanticSqlCorrection valueFilterCorrection = applyValueFilterDimensionConsistencyGuard(question, queryTableName,
                fields, generatedSql, chartType, fieldMapping, generationTrace);
        if (!Objects.equals(valueFilterCorrection.sql(), generatedSql)) {
            return applyTopNAfterSemanticGuard(question, queryTableName, fields, valueFilterCorrection, generationTrace);
        }

        Set<String> macroRegionValues = extractMacroRegionValues(question);
        if (macroRegionValues.isEmpty() || fields == null || fields.isEmpty()) {
            return applyTopNIntentGuard(question, queryTableName, fields, generatedSql, chartType, fieldMapping,
                    generationTrace);
        }
        Map<String, Object> regionField = findMacroRegionField(fields);
        if (regionField == null) {
            return applyTopNIntentGuard(question, queryTableName, fields, generatedSql, chartType, fieldMapping,
                    generationTrace);
        }
        String regionColumn = fieldColumn(regionField);
        boolean provinceBreakdown = asksProvinceBreakdown(question);
        Map<String, Object> provinceField = provinceBreakdown ? findProvinceField(fields) : null;
        Map<String, Object> dimensionField = provinceField == null ? regionField : provinceField;
        String dimensionColumn = fieldColumn(dimensionField);
        String currentDimension = Objects.toString(fieldMapping == null ? "" : fieldMapping.get("dimensionKey"), "").trim();
        if (regionColumn.isBlank() || dimensionColumn.isBlank()) {
            return applyTopNIntentGuard(question, queryTableName, fields, generatedSql, chartType, fieldMapping,
                    generationTrace);
        }
        if (!provinceBreakdown && regionColumn.equals(currentDimension)) {
            return applyTopNIntentGuard(question, queryTableName, fields, generatedSql, chartType, fieldMapping,
                    generationTrace);
        }
        Map<String, Object> metricField = findMetricField(fields, fieldMapping, question);
        if (metricField == null) {
            return applyTopNIntentGuard(question, queryTableName, fields, generatedSql, chartType, fieldMapping,
                    generationTrace);
        }
        String metricColumn = fieldColumn(metricField);
        if (metricColumn.isBlank()) {
            return applyTopNIntentGuard(question, queryTableName, fields, generatedSql, chartType, fieldMapping,
                    generationTrace);
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
        return applyTopNAfterSemanticGuard(question, queryTableName, fields,
                new SemanticSqlCorrection(sql.toString(), "bar", correctedMapping), generationTrace);
    }

    private record SqlSelectMapping(String dimensionExpression,
                                    String dimensionAlias,
                                    String dimensionColumn,
                                    String metricExpression,
                                    String metricAlias,
                                    String metricColumn) {
    }

    private SemanticSqlCorrection applyTopNAfterSemanticGuard(String question, String queryTableName,
            List<Map<String, Object>> fields, SemanticSqlCorrection correction, List<String> generationTrace) {
        if (correction == null) {
            return new SemanticSqlCorrection("", "bar", Map.of());
        }
        return applyTopNIntentGuard(question, queryTableName, fields, correction.sql(), correction.chartType(),
                correction.fieldMapping(), generationTrace);
    }

    private SemanticSqlCorrection applySortIntentGuard(String question, String generatedSql, String chartType,
            Map<String, Object> fieldMapping, List<String> generationTrace) {
        Map<String, Object> correctedMapping = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        SortIntent intent = resolveSortIntent(question);
        correctedMapping.put("chartSortMode", intent.chartSortMode());
        correctedMapping.put("sortIntent", intent.mode().name());
        correctedMapping.put("sortExplicit", intent.explicit());
        correctedMapping.put("sortReason", intent.reason());

        String sql = Objects.toString(generatedSql, "").trim();
        if ("table".equalsIgnoreCase(chartType) || sql.isBlank()
                || !sql.toLowerCase(Locale.ROOT).startsWith("select") || !hasGroupBy(sql)) {
            return new SemanticSqlCorrection(generatedSql, chartType, correctedMapping);
        }
        TopNIntent topNIntent = extractTopNIntent(question);
        String correctedSql = topNIntent == null ? enforceSortOrder(sql, intent) : enforceOrderAndLimit(sql, topNIntent);
        if (!correctedSql.equals(sql) && generationTrace != null) {
            generationTrace.add("semanticSqlGuard=APPLIED;reason=SORT_INTENT;mode=" + intent.mode().name()
                    + ";explicit=" + intent.explicit());
        }
        return new SemanticSqlCorrection(correctedSql, chartType, correctedMapping);
    }

    private SortIntent resolveSortIntent(String question) {
        String text = Objects.toString(question, "").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return SortIntent.nameDefault();
        }
        TopNIntent topNIntent = extractTopNIntent(text);
        if (topNIntent != null) {
            return new SortIntent(topNIntent.ascending() ? SortIntentMode.VALUE_ASC : SortIntentMode.VALUE_DESC,
                    true, "TOP_N_INTENT");
        }
        boolean nameSort = containsAny(text, "按名称排序", "名称排序", "名字排序", "按维度排序", "维度排序",
                "按城市排序", "按省份排序", "按区域排序", "按地区排序", "按品类排序", "字母序", "字典序")
                || lower.contains("sort by name") || lower.contains("order by name");
        if (nameSort) {
            return new SortIntent(SortIntentMode.NAME_ASC, true, "NAME_SORT");
        }
        boolean ascending = containsAny(text, "升序", "正序", "从低到高", "由低到高", "从小到大", "由小到大",
                "最低", "最少", "最小", "倒数", "后几", "后十")
                || lower.contains("ascending") || lower.contains(" asc")
                || lower.contains("least") || lower.contains("bottom");
        if (ascending) {
            return new SortIntent(SortIntentMode.VALUE_ASC, true, "VALUE_ASCENDING_SEMANTIC");
        }
        boolean descending = containsAny(text, "降序", "倒序", "从高到低", "由高到低", "从大到小", "由大到小",
                "最高", "最多", "最大", "排名", "排行", "榜单", "排序")
                || lower.contains("descending") || lower.contains(" desc")
                || lower.contains("top") || lower.contains("ranking") || lower.contains("rank");
        if (descending) {
            return new SortIntent(SortIntentMode.VALUE_DESC, true, "VALUE_DESCENDING_SEMANTIC");
        }
        return SortIntent.nameDefault();
    }

    private String enforceSortOrder(String sql, SortIntent intent) {
        String normalized = Objects.toString(sql, "").replaceAll(";+$", "").trim();
        if (normalized.isBlank()) {
            return normalized;
        }
        Matcher limitMatcher = Pattern.compile("(?is)\\s+limit\\s+\\d+\\s*$").matcher(normalized);
        String limitClause = "";
        String withoutLimit = normalized;
        if (limitMatcher.find()) {
            limitClause = normalized.substring(limitMatcher.start()).trim();
            withoutLimit = normalized.substring(0, limitMatcher.start()).trim();
        }
        String orderExpr = intent.mode() == SortIntentMode.NAME_ASC
                ? chooseDimensionOrderExpression(withoutLimit)
                : chooseTopNOrderExpression(withoutLimit);
        String direction = intent.mode() == SortIntentMode.VALUE_DESC ? "DESC" : "ASC";
        String withoutOrder = withoutLimit.replaceAll("(?is)\\s+order\\s+by\\s+.+$", "").trim();
        return withoutOrder + " ORDER BY " + orderExpr + " " + direction
                + (limitClause.isBlank() ? "" : " " + limitClause);
    }

    private String chooseDimensionOrderExpression(String sql) {
        SqlSelectMapping mapping = parseSqlSelectMapping(sql);
        if (mapping != null && !mapping.dimensionAlias().isBlank()) {
            return mapping.dimensionAlias();
        }
        String lower = Objects.toString(sql, "").toLowerCase(Locale.ROOT);
        if (Pattern.compile("(?is)\\bselect\\s+dim_name\\b").matcher(sql).find()) {
            return "dim_name";
        }
        if (Pattern.compile("(?is)\\bselect\\s+name\\b").matcher(sql).find()) {
            return "name";
        }
        if (lower.contains(" as dim_name")) {
            return "dim_name";
        }
        if (lower.contains(" as name")) {
            return "name";
        }
        String dimensionColumn = parseSelectedDimensionColumn(sql);
        if (!dimensionColumn.isBlank()) {
            return "`" + dimensionColumn + "`";
        }
        Matcher groupMatcher = Pattern.compile("(?is)\\bgroup\\s+by\\s+(.+?)(?:\\border\\s+by\\b|\\blimit\\b|$)")
                .matcher(Objects.toString(sql, ""));
        if (groupMatcher.find()) {
            String groupExpr = groupMatcher.group(1).trim();
            if (!groupExpr.isBlank() && !groupExpr.contains(",")) {
                return groupExpr;
            }
        }
        return "name";
    }

    private boolean hasGroupBy(String sql) {
        return Pattern.compile("(?is)\\bgroup\\s+by\\b").matcher(Objects.toString(sql, "")).find();
    }

    private SemanticSqlCorrection applyTopNIntentGuard(String question, String queryTableName,
            List<Map<String, Object>> fields, String generatedSql, String chartType,
            Map<String, Object> fieldMapping, List<String> generationTrace) {
        TopNIntent intent = extractTopNIntent(question);
        if (intent == null || fields == null || fields.isEmpty()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        if ("line".equalsIgnoreCase(chartType) || isTimeSeriesLikeQuestion(question)) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        String sql = Objects.toString(generatedSql, "").trim();
        if (sql.isBlank() || !sql.toLowerCase(Locale.ROOT).startsWith("select")) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        Map<String, Object> dimensionField = findDimensionField(fields, fieldMapping, question);
        Map<String, Object> metricField = findMetricField(fields, fieldMapping, question);
        String expectedDimension = dimensionField == null ? "" : fieldColumn(dimensionField);
        String currentDimension = parseSelectedDimensionColumn(sql);
        boolean dimensionMatches = expectedDimension.isBlank() || currentDimension.isBlank()
                || expectedDimension.equals(currentDimension);
        String correctedSql = enforceOrderAndLimit(sql, intent);
        if (dimensionMatches && !correctedSql.equals(sql)) {
            generationTrace.add("semanticSqlGuard=APPLIED;reason=TOP_N_INTENT;limit="
                    + intent.limit() + ";direction=" + (intent.ascending() ? "ASC" : "DESC")
                    + ";mode=ORDER_LIMIT_REWRITE");
            return new SemanticSqlCorrection(correctedSql, chartType, fieldMapping);
        }

        if (dimensionField == null || fieldColumn(dimensionField).isBlank() || metricField == null
                || fieldColumn(metricField).isBlank()) {
            return new SemanticSqlCorrection(generatedSql, chartType, fieldMapping);
        }
        String dimensionColumn = fieldColumn(dimensionField);
        String metricColumn = fieldColumn(metricField);
        String rebuiltSql = "SELECT `" + dimensionColumn + "` AS dim_name, "
                + "SUM(CAST(NULLIF(`" + metricColumn + "`, '') AS DECIMAL(18,2))) AS metric_value "
                + "FROM `" + queryTableName + "` WHERE `" + dimensionColumn + "` IS NOT NULL AND `"
                + dimensionColumn + "` <> '' " + reusableTopNWhereClause(sql, currentDimension, dimensionColumn)
                + "GROUP BY `" + dimensionColumn + "` ORDER BY metric_value "
                + (intent.ascending() ? "ASC" : "DESC") + " LIMIT " + intent.limit();

        Map<String, Object> correctedMapping = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        correctedMapping.put("dimension", fieldDisplayName(dimensionField));
        correctedMapping.put("dimensionKey", dimensionColumn);
        correctedMapping.put("dimensionExpr", "`" + dimensionColumn + "`");
        correctedMapping.put("metric", fieldDisplayName(metricField));
        correctedMapping.put("metricKey", metricColumn);
        correctedMapping.put("metricExpr",
                "SUM(CAST(NULLIF(`" + metricColumn + "`, '') AS DECIMAL(18,2)))");
        generationTrace.add("semanticSqlGuard=APPLIED;reason=TOP_N_INTENT;limit="
                + intent.limit() + ";direction=" + (intent.ascending() ? "ASC" : "DESC")
                + ";mode=REBUILD_AGGREGATION");
        return new SemanticSqlCorrection(rebuiltSql, "bar", correctedMapping);
    }

    private String reusableTopNWhereClause(String sql, String oldDimensionColumn, String newDimensionColumn) {
        Matcher matcher = Pattern.compile("(?is)\\bwhere\\b(.+?)(?:\\bgroup\\s+by\\b|\\border\\s+by\\b|\\blimit\\b|$)")
                .matcher(Objects.toString(sql, ""));
        if (!matcher.find()) {
            return "";
        }
        String where = matcher.group(1).trim();
        if (where.isBlank()) {
            return "";
        }
        String oldDim = Objects.toString(oldDimensionColumn, "").trim();
        String newDim = Objects.toString(newDimensionColumn, "").trim();
        List<String> conditions = new ArrayList<>();
        for (String part : where.split("(?i)\\s+and\\s+")) {
            String condition = part.trim();
            if (condition.isBlank()) {
                continue;
            }
            if (isDimensionBlankCheck(condition, oldDim) || isDimensionBlankCheck(condition, newDim)) {
                continue;
            }
            conditions.add(condition);
        }
        if (conditions.isEmpty()) {
            return "";
        }
        return "AND " + String.join(" AND ", conditions) + " ";
    }

    private boolean isDimensionBlankCheck(String condition, String dimensionColumn) {
        String column = Objects.toString(dimensionColumn, "").trim();
        if (column.isBlank()) {
            return false;
        }
        String normalized = Objects.toString(condition, "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        String quoted = ("`" + column + "`").toLowerCase(Locale.ROOT);
        return normalized.equals(quoted + " is not null")
                || normalized.equals(quoted + " <> ''")
                || normalized.equals(quoted + " != ''")
                || normalized.equals("trim(" + quoted + ") <> ''")
                || normalized.equals("trim(" + quoted + ") != ''");
    }

    private String enforceOrderAndLimit(String sql, TopNIntent intent) {
        String normalized = sql.replaceAll(";+$", "").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.contains(" group by ") && !lower.contains(" order by ")) {
            return normalized;
        }
        String withoutLimit = normalized.replaceAll("(?is)\\s+limit\\s+\\d+\\s*$", "").trim();
        String orderExpr = chooseTopNOrderExpression(withoutLimit);
        String withoutOrder = withoutLimit.replaceAll("(?is)\\s+order\\s+by\\s+.+$", "").trim();
        return withoutOrder + " ORDER BY " + orderExpr + " " + (intent.ascending() ? "ASC" : "DESC")
                + " LIMIT " + intent.limit();
    }

    private String chooseTopNOrderExpression(String sql) {
        String lower = Objects.toString(sql, "").toLowerCase(Locale.ROOT);
        if (lower.contains(" as metric_value")) {
            return "metric_value";
        }
        if (lower.contains(" as value")) {
            return "value";
        }
        Matcher alias = Pattern.compile("(?is)\\b(sum|count|avg|min|max)\\s*\\([^)]*\\)\\s+as\\s+`?([a-zA-Z_][\\w]*)`?")
                .matcher(sql);
        if (alias.find()) {
            return alias.group(2);
        }
        return "metric_value";
    }

    private TopNIntent extractTopNIntent(String question) {
        String text = Objects.toString(question, "").trim();
        if (text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean ascending = containsAny(text, "最低", "最少", "倒数", "末尾", "后几", "后十", "least", "bottom");
        Integer limit = null;
        Matcher topMatcher = Pattern.compile("(?i)(?:top|前|排名前|排行前|最高的前|最大的前|最低的前|最少的前)\\s*([0-9]{1,4})")
                .matcher(text);
        if (topMatcher.find()) {
            limit = parseBoundedTopN(topMatcher.group(1));
        }
        if (limit == null) {
            Matcher suffixMatcher = Pattern.compile("(?i)([0-9]{1,4})\\s*(?:个|名|条|项|位)?\\s*(?:最高|最大|最多|最低|最少|top|排名|排行)")
                    .matcher(text);
            if (suffixMatcher.find()) {
                limit = parseBoundedTopN(suffixMatcher.group(1));
            }
        }
        if (limit == null) {
            Matcher cnMatcher = Pattern.compile("(?:前|排名前|排行前|最高的前|最大的前|最低的前|最少的前)([零一二两三四五六七八九十百千万〇]{1,8})")
                    .matcher(text);
            if (cnMatcher.find()) {
                limit = parseBoundedTopN(cnMatcher.group(1));
            }
        }
        if (limit == null) {
            Matcher cnSuffixMatcher = Pattern.compile("([零一二两三四五六七八九十百千万〇]{1,8})(?:个|名|条|项|位)?(?:最高|最大|最多|最低|最少|排名|排行)")
                    .matcher(text);
            if (cnSuffixMatcher.find()) {
                limit = parseBoundedTopN(cnSuffixMatcher.group(1));
            }
        }
        if (limit == null && (lower.contains("top") || containsAny(text, "前几", "最高", "最多", "最低", "最少"))) {
            limit = 10;
        }
        if (limit == null) {
            return null;
        }
        return new TopNIntent(limit, ascending);
    }

    private Integer parseBoundedTopN(String raw) {
        int value = parseFlexiblePositiveInteger(raw);
        if (value <= 0) {
            return null;
        }
        return Math.min(value, 200);
    }

    private int parseFlexiblePositiveInteger(String raw) {
        String text = Objects.toString(raw, "").trim();
        if (text.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return parseChinesePositiveInteger(text);
        }
    }

    private int parseChinesePositiveInteger(String raw) {
        String text = Objects.toString(raw, "").trim();
        if (text.isBlank()) {
            return 0;
        }
        int total = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = chineseDigitValue(text.charAt(i));
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = chineseUnitValue(text.charAt(i));
            if (unit <= 0) {
                return 0;
            }
            if (unit == 10000) {
                section = (section + (number == 0 ? 1 : number)) * unit;
                total += section;
                section = 0;
            } else {
                section += (number == 0 ? 1 : number) * unit;
            }
            number = 0;
        }
        return total + section + number;
    }

    private int chineseDigitValue(char ch) {
        return switch (ch) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private int chineseUnitValue(char ch) {
        return switch (ch) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            case '万' -> 10000;
            default -> 0;
        };
    }

    private record TopNIntent(int limit, boolean ascending) {
    }

    private enum SortIntentMode {
        NAME_ASC,
        VALUE_ASC,
        VALUE_DESC
    }

    private record SortIntent(SortIntentMode mode, boolean explicit, String reason) {
        private String chartSortMode() {
            return switch (mode) {
                case VALUE_ASC -> "asc";
                case VALUE_DESC -> "desc";
                case NAME_ASC -> "name";
            };
        }

        private static SortIntent nameDefault() {
            return new SortIntent(SortIntentMode.NAME_ASC, false, "DEFAULT_NAME_ASC");
        }
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

    private Map<String, Object> alignFieldMappingWithSql(String question, List<Map<String, Object>> fields,
            String sql, Map<String, Object> fieldMapping, List<String> generationTrace) {
        SqlSelectMapping selectMapping = parseSqlSelectMapping(sql);
        if (selectMapping == null || (selectMapping.dimensionExpression().isBlank()
                && selectMapping.metricExpression().isBlank())) {
            return fieldMapping == null ? Map.of() : fieldMapping;
        }
        Map<String, Object> aligned = new LinkedHashMap<>(fieldMapping == null ? Map.of() : fieldMapping);
        boolean changed = applySqlSelectMapping(question, fields, selectMapping, aligned);
        if (changed && generationTrace != null) {
            generationTrace.add("fieldMappingAligned=APPLIED;dimensionExpr="
                    + safeTraceValue(selectMapping.dimensionExpression()) + ";metricExpr="
                    + safeTraceValue(selectMapping.metricExpression()));
        }
        return aligned;
    }

    private Map<String, Object> alignFieldMappingWithSqlAndRows(String question, List<Map<String, Object>> fields,
            String sql, List<Map<String, Object>> rows, Map<String, Object> fieldMapping,
            List<String> generationTrace) {
        Map<String, Object> aligned = new LinkedHashMap<>(alignFieldMappingWithSql(question, fields, sql, fieldMapping,
                generationTrace));
        if (rows == null || rows.isEmpty()) {
            return aligned;
        }
        Map<String, Object> firstRow = rows.get(0);
        String dimensionKey = Objects.toString(aligned.get("dimensionKey"), "").trim();
        if (!dimensionKey.isBlank() && !firstRow.containsKey(dimensionKey)
                && firstRow.keySet().stream().anyMatch(key -> isDimensionAlias(key))) {
            aligned.put("dimensionKey", firstRow.keySet().stream().filter(this::isDimensionAlias).findFirst()
                    .orElse(dimensionKey));
        }
        String metricKey = Objects.toString(aligned.get("metricKey"), "").trim();
        if (!metricKey.isBlank() && !firstRow.containsKey(metricKey)
                && firstRow.keySet().stream().anyMatch(key -> isMetricAlias(key))) {
            aligned.put("metricKey", firstRow.keySet().stream().filter(this::isMetricAlias).findFirst()
                    .orElse(metricKey));
        }
        return aligned;
    }

    private boolean applySqlSelectMapping(String question, List<Map<String, Object>> fields,
            SqlSelectMapping selectMapping, Map<String, Object> aligned) {
        boolean changed = false;
        String dimensionExpression = selectMapping.dimensionExpression();
        if (!dimensionExpression.isBlank()) {
            String previousKey = Objects.toString(aligned.get("dimensionKey"), "").trim();
            String dimensionKey = firstTextValue(selectMapping.dimensionAlias(), selectMapping.dimensionColumn(), previousKey);
            String dimensionLabel = sqlDimensionLabel(question, fields, selectMapping, previousKey);
            if (!dimensionKey.equals(previousKey)) {
                aligned.put("dimensionKey", dimensionKey);
                changed = true;
            }
            if (!dimensionLabel.isBlank() && !dimensionLabel.equals(Objects.toString(aligned.get("dimension"), ""))) {
                aligned.put("dimension", dimensionLabel);
                changed = true;
            }
            aligned.put("dimensionExpr", dimensionExpression);
        }
        String metricExpression = selectMapping.metricExpression();
        if (!metricExpression.isBlank()) {
            String previousKey = Objects.toString(aligned.get("metricKey"), "").trim();
            String metricKey = firstTextValue(selectMapping.metricAlias(), selectMapping.metricColumn(), previousKey);
            String metricLabel = sqlMetricLabel(fields, selectMapping, previousKey);
            if (!metricKey.equals(previousKey)) {
                aligned.put("metricKey", metricKey);
                changed = true;
            }
            if (!metricLabel.isBlank() && !metricLabel.equals(Objects.toString(aligned.get("metric"), ""))) {
                aligned.put("metric", metricLabel);
                changed = true;
            }
            aligned.put("metricExpr", metricExpression);
        }
        return changed;
    }

    private SqlSelectMapping parseSqlSelectMapping(String sql) {
        String source = Objects.toString(sql, "");
        String selectClause = extractSelectClause(source);
        if (selectClause.isBlank()) {
            return new SqlSelectMapping("", "", "", "", "", "");
        }
        String dimensionExpression = "";
        String dimensionAlias = "";
        String metricExpression = "";
        String metricAlias = "";
        for (String item : splitSelectItems(selectClause)) {
            Matcher aliasMatcher = Pattern.compile("(?is)^(.+?)\\s+as\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*$")
                    .matcher(item);
            if (!aliasMatcher.find()) {
                continue;
            }
            String expression = cleanSqlExpression(aliasMatcher.group(1));
            String alias = cleanSqlIdentifier(aliasMatcher.group(2));
            if (isDimensionAlias(alias) && dimensionExpression.isBlank()) {
                dimensionExpression = expression;
                dimensionAlias = alias;
            } else if (isMetricAlias(alias) && metricExpression.isBlank()) {
                metricExpression = expression;
                metricAlias = alias;
            }
        }
        return new SqlSelectMapping(
                dimensionExpression,
                dimensionAlias,
                firstBacktickColumn(dimensionExpression),
                metricExpression,
                metricAlias,
                lastBacktickColumn(metricExpression));
    }

    private String extractSelectClause(String sql) {
        Matcher matcher = Pattern.compile("(?is)^\\s*select\\s+(.+?)\\s+from\\b").matcher(Objects.toString(sql, ""));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private List<String> splitSelectItems(String selectClause) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        String source = Objects.toString(selectClause, "");
        for (int i = 0; i < source.length(); i += 1) {
            char ch = source.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                depth += 1;
                current.append(ch);
                continue;
            }
            if (ch == ')' && depth > 0) {
                depth -= 1;
                current.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                String item = current.toString().trim();
                if (!item.isBlank()) {
                    items.add(item);
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String item = current.toString().trim();
        if (!item.isBlank()) {
            items.add(item);
        }
        return items;
    }

    private String sqlDimensionLabel(String question, List<Map<String, Object>> fields,
            SqlSelectMapping selectMapping, String fallbackColumn) {
        String expr = selectMapping.dimensionExpression().toLowerCase(Locale.ROOT);
        String sourceColumn = firstTextValue(selectMapping.dimensionColumn(), fallbackColumn);
        Map<String, Object> sourceField = findFieldByColumn(fields, sourceColumn);
        String sourceLabel = sourceField == null ? sourceColumn : fieldDisplayName(sourceField);
        if (expr.contains("date_format") || expr.contains("year(") || expr.contains("quarter(")) {
            String q = Objects.toString(question, "");
            if (containsAny(q, "每个月", "每月", "按月", "月度", "月份")) {
                return firstTextValue(sourceLabel, "时间") + "（按月）";
            }
            if (containsAny(q, "每天", "每日", "按日", "日度")) {
                return firstTextValue(sourceLabel, "时间") + "（按日）";
            }
            if (containsAny(q, "每周", "按周", "周度")) {
                return firstTextValue(sourceLabel, "时间") + "（按周）";
            }
            if (containsAny(q, "季度", "按季度")) {
                return firstTextValue(sourceLabel, "时间") + "（按季度）";
            }
            if (containsAny(q, "每年", "按年", "年度")) {
                return firstTextValue(sourceLabel, "时间") + "（按年）";
            }
            return firstTextValue(sourceLabel, "时间维度");
        }
        return firstTextValue(sourceLabel, selectMapping.dimensionAlias(), fallbackColumn);
    }

    private String sqlMetricLabel(List<Map<String, Object>> fields, SqlSelectMapping selectMapping, String fallbackColumn) {
        String sourceColumn = firstTextValue(selectMapping.metricColumn(), fallbackColumn);
        Map<String, Object> sourceField = findFieldByColumn(fields, sourceColumn);
        return firstTextValue(sourceField == null ? "" : fieldDisplayName(sourceField), sourceColumn,
                selectMapping.metricAlias(), fallbackColumn);
    }

    private String cleanSqlExpression(String expression) {
        return Objects.toString(expression, "").replaceAll("\\s+", " ").trim();
    }

    private String cleanSqlIdentifier(String identifier) {
        return Objects.toString(identifier, "").replace("`", "").trim();
    }

    private String firstBacktickColumn(String expression) {
        Matcher matcher = Pattern.compile("`([^`]+)`").matcher(Objects.toString(expression, ""));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String lastBacktickColumn(String expression) {
        Matcher matcher = Pattern.compile("`([^`]+)`").matcher(Objects.toString(expression, ""));
        String result = "";
        while (matcher.find()) {
            result = matcher.group(1).trim();
        }
        return result;
    }

    private boolean isDimensionAlias(String key) {
        String normalized = Objects.toString(key, "").trim().toLowerCase(Locale.ROOT);
        return "dim_name".equals(normalized) || "name".equals(normalized) || "dimension".equals(normalized);
    }

    private boolean isMetricAlias(String key) {
        String normalized = Objects.toString(key, "").trim().toLowerCase(Locale.ROOT);
        return "metric_value".equals(normalized) || "value".equals(normalized) || "metric".equals(normalized);
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

    private Map<String, Object> findDimensionField(List<Map<String, Object>> fields, Map<String, Object> fieldMapping,
            String question) {
        List<Map<String, Object>> candidates = fields.stream()
                .filter(field -> !isNumericField(field))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        Optional<Map<String, Object>> semantic = candidates.stream()
                .map(field -> Map.entry(field, dimensionFieldScore(field, question)))
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
        if (semantic.isPresent()) {
            return semantic.get();
        }
        String mappedDimension = Objects.toString(fieldMapping == null ? "" : fieldMapping.get("dimensionKey"), "").trim();
        if (!mappedDimension.isBlank()) {
            Optional<Map<String, Object>> mapped = candidates.stream()
                    .filter(field -> mappedDimension.equals(fieldColumn(field)))
                    .findFirst();
            if (mapped.isPresent()) {
                return mapped.get();
            }
        }
        return candidates.get(0);
    }

    private int dimensionFieldScore(Map<String, Object> field, String question) {
        String haystack = fieldSemanticText(field);
        String text = Objects.toString(question, "");
        int score = 0;
        if (containsAny(haystack, "city", "城市", "市") && containsAny(text, "城市", "市", "city")) {
            score += 140;
        }
        if (containsAny(haystack, "province", "prov", "state", "省份", "省市", "省")
                && containsAny(text, "省份", "各省", "省", "province")) {
            score += 135;
        }
        if (containsAny(haystack, "region", "area", "zone", "区域", "大区")
                && containsAny(text, "区域", "大区", "region", "area")) {
            score += 130;
        }
        if (containsAny(haystack, "product", "sku", "item", "产品", "商品", "品类", "类别")
                && containsAny(text, "产品", "商品", "品类", "类别", "product", "sku")) {
            score += 125;
        }
        if (containsAny(haystack, "customer", "client", "客户", "顾客")
                && containsAny(text, "客户", "顾客", "customer", "client")) {
            score += 120;
        }
        String displayName = fieldDisplayName(field);
        String column = fieldColumn(field);
        if (!displayName.isBlank() && text.contains(displayName)) {
            score += 100;
        }
        if (!column.isBlank() && text.toLowerCase(Locale.ROOT).contains(column.toLowerCase(Locale.ROOT))) {
            score += 80;
        }
        return score;
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

    private boolean isTimeSeriesLikeQuestion(String question) {
        String text = Objects.toString(question, "");
        return containsAny(text, "趋势", "走势", "变化", "按月", "每月", "月份", "月度", "按日", "每日",
                "日期", "时间", "按周", "每周", "季度", "年度");
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

    private String firstTextValue(Object... values) {
        Object value = firstNonBlank(values);
        return Objects.toString(value, "").trim();
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

    private ProgressListener progressListener(Object value) {
        return value instanceof ProgressListener listener ? listener : ProgressListener.noop();
    }

    private void emitProgress(ProgressListener listener,
                              String eventType,
                              String title,
                              String detail,
                              Map<String, Object> metadata) {
        if (listener == null) {
            return;
        }
        listener.onProgress(
                Objects.toString(eventType, "STEP"),
                Objects.toString(title, "处理中"),
                Objects.toString(detail, ""),
                metadata == null ? Map.of() : metadata
        );
    }

    private Map<String, Object> publicFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        filters.forEach((key, value) -> {
            if (!"progressListener".equals(Objects.toString(key, ""))) {
                result.put(key, value);
            }
        });
        return result;
    }

    private void ensureNotCancelled(String stage) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户已停止生成（" + stage + "）");
        }
    }
}
