package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import com.insightspark.service.SqlAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCPerformanceService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlAuditService sqlAuditService;

    @Autowired
    private StackCRuntimeConfigProvider runtimeConfig;

    @Value("${insight.ai-service-url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${insight.redis.enabled:true}")
    private boolean redisEnabledDefault;

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jvm", jvmSnapshot());
        result.put("disk", diskSnapshot());
        result.put("sqlAudit", sqlAuditService.stats());
        result.put("cache", sqlAuditService.cacheOverview());
        result.put("engines", engineStatus());
        result.put("datasource", datasourceSnapshot());
        result.put("alertConfig", readAlertConfig());
        result.put("dbPressure", dbPressureSnapshot());
        return result;
    }

    public Map<String, Object> getGovernanceConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("alert", readAlertConfig());
        cfg.put("slowQuery", readSlowQueryGovernance());
        cfg.put("cache", readCacheConfig());
        cfg.put("batch", readBatchConfig());
        cfg.put("dbPressure", readDbPressureConfig());
        cfg.put("resource", readResourceConfig());
        cfg.put("auditRules", sqlAuditService.listRules());
        return cfg;
    }

    public void saveAlertConfig(Map<String, Object> body) {
        upsertConfig("perf.alert.slow_ms", str(body.get("slowQueryMs"), "3000"), "NUMBER", "PERFORMANCE", "慢查询阈值(ms)");
        upsertConfig("perf.alert.cpu.percent", str(body.get("cpuPercent"), "90"), "NUMBER", "PERFORMANCE", "CPU 告警阈值(%)");
        upsertConfig("perf.alert.queryTimeoutMs", str(body.get("queryTimeoutMs"), "30000"), "NUMBER", "PERFORMANCE", "查询响应超时(ms)");
        syncSqlRule("SLOW_QUERY", str(body.get("slowQueryMs"), "3000"), true);
        runtimeConfig.invalidateCache();
    }

    public void saveSlowQueryGovernance(Map<String, Object> body) {
        long slowMs = parseLong(body.get("slowQueryMs"), 3000L);
        long breakerMs = parseLong(body.get("circuitThresholdMs"), 8000L);
        boolean circuitEnabled = parseBool(body.get("circuitEnabled"), false);
        upsertConfig("perf.alert.slow_ms", String.valueOf(slowMs), "NUMBER", "PERFORMANCE", "慢查询阈值(ms)");
        upsertConfig("perf.slow.query.circuit.enabled", String.valueOf(circuitEnabled), "STRING", "PERFORMANCE", "慢查询熔断");
        upsertConfig("perf.slow.query.circuit.threshold", String.valueOf(breakerMs), "NUMBER", "PERFORMANCE", "慢查询熔断阈值(ms)");
        syncSqlRule("SLOW_QUERY", String.valueOf(slowMs), true);
        syncSqlRule("SLOW_QUERY_BREAKER", String.valueOf(breakerMs), circuitEnabled);
        runtimeConfig.invalidateCache();
    }

    public void saveCacheConfig(Map<String, Object> body) {
        upsertConfig("perf.redis.cache.enabled", String.valueOf(parseBool(body.get("enabled"), false)), "STRING", "PERFORMANCE", "Redis 语义缓存");
        upsertConfig("perf.redis.cache.ttlSeconds", str(body.get("ttlSeconds"), "3600"), "NUMBER", "PERFORMANCE", "缓存 TTL");
        runtimeConfig.invalidateCache();
    }

    public void saveBatchConfig(Map<String, Object> body) {
        upsertConfig("perf.batch.task.maxConcurrency", str(body.get("maxConcurrency"), "3"), "NUMBER", "PERFORMANCE", "批处理并发");
        upsertConfig("perf.batch.task.timeoutSeconds", str(body.get("timeoutSeconds"), "600"), "NUMBER", "PERFORMANCE", "批处理超时");
        upsertConfig("perf.dashboard.prewarm.enabled", String.valueOf(parseBool(body.get("prewarmEnabled"), false)), "STRING", "PERFORMANCE", "看板预热");
        upsertConfig("perf.dashboard.prewarm.cron", str(body.get("prewarmCron"), "0 0 6 * * ?"), "STRING", "PERFORMANCE", "预热 Cron");
        runtimeConfig.invalidateCache();
    }

    public void saveDbPressureConfig(Map<String, Object> body) {
        upsertConfig("perf.db.pool.maxSize", str(body.get("poolMaxSize"), "20"), "NUMBER", "PERFORMANCE", "连接池上限");
        upsertConfig("perf.db.query.maxConcurrent", str(body.get("maxConcurrentPerUser"), "4"), "NUMBER", "PERFORMANCE", "单用户并发");
        upsertConfig("perf.db.access.maxPerMinute", str(body.get("maxAccessPerMinute"), "120"), "NUMBER", "PERFORMANCE", "访问频次/分钟");
        runtimeConfig.invalidateCache();
    }

    public void saveResourceConfig(Map<String, Object> body) {
        upsertConfig("perf.resource.priority.text2sql", str(body.get("text2sql"), "90"), "NUMBER", "PERFORMANCE", "Text-to-SQL 优先级");
        upsertConfig("perf.resource.priority.graphrag", str(body.get("graphrag"), "85"), "NUMBER", "PERFORMANCE", "GraphRAG 优先级");
        upsertConfig("perf.resource.priority.upload", str(body.get("upload"), "40"), "NUMBER", "PERFORMANCE", "上传批处理优先级");
        upsertConfig("perf.resource.priority.dashboard", str(body.get("dashboard"), "60"), "NUMBER", "PERFORMANCE", "看板渲染优先级");
        runtimeConfig.invalidateCache();
    }

    public Map<String, Object> bottleneckReport() {
        Map<String, Object> audit = sqlAuditService.stats();
        Map<String, Object> cache = sqlAuditService.cacheOverview();
        Map<String, Object> jvm = jvmSnapshot();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        long slowCount = number(audit.get("slowCount"));
        long blocked = number(audit.get("blockedCount"));
        double hitRate = parseDouble(cache.get("hitRate"), 0);
        Double heapPct = jvm.get("heapUsedPercent") instanceof Number n ? n.doubleValue() : null;

        if (slowCount > 10) {
            suggestions.add(suggestion("SLOW_QUERY", "HIGH", "慢查询偏多（" + slowCount + " 条）",
                    "建议检查 is_sql_audit_log 高频 SQL，启用慢查询熔断并优化索引。"));
        }
        if (hitRate < 20 && number(cache.get("cacheCount")) > 5) {
            suggestions.add(suggestion("CACHE", "MEDIUM", "语义缓存命中率偏低（" + hitRate + "%）",
                    "建议开启 Redis 语义缓存并适当提高 TTL，或清理无效缓存条目。"));
        }
        if (heapPct != null && heapPct > 85) {
            suggestions.add(suggestion("JVM", "HIGH", "JVM 堆内存占用 " + heapPct + "%",
                    "建议扩容堆内存或排查内存泄漏，并降低批处理并发。"));
        }
        if (blocked > 0) {
            suggestions.add(suggestion("SECURITY", "MEDIUM", "存在 " + blocked + " 条拦截 SQL",
                    "检查 SQL 审计规则与白名单配置，避免误拦截正常查询。"));
        }
        try {
            Integer dsErrors = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM is_official_datasource WHERE status <> 'DELETED' AND last_health_status = 'DOWN'
                    """, Integer.class);
            if (dsErrors != null && dsErrors > 0) {
                suggestions.add(suggestion("DATASOURCE", "HIGH", dsErrors + " 个数据源健康检查异常",
                        "请在数据源管理中检查连接与池配置。"));
            }
        } catch (Exception ignored) {
        }
        if (suggestions.isEmpty()) {
            suggestions.add(suggestion("OK", "LOW", "未发现明显瓶颈",
                    "当前指标正常，可继续观察慢查询与缓存命中率趋势。"));
        }

        String reportId = "PERF-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now());
        String overallLevel = suggestions.stream()
                .map(s -> Objects.toString(s.get("severity"), "LOW"))
                .anyMatch("HIGH"::equals) ? "HIGH"
                : suggestions.stream().anyMatch(s -> "MEDIUM".equals(s.get("severity"))) ? "MEDIUM" : "LOW";
        String conclusion = buildReportConclusion(overallLevel, slowCount, hitRate, heapPct, blocked, suggestions);

        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(reportSection("执行摘要", conclusion));
        sections.add(reportSection("指标概览", String.format(
                "慢查询 %d 条；语义缓存命中率 %.2f%%；JVM 堆占用 %s；SQL 拦截 %d 条。",
                slowCount, hitRate, heapPct == null ? "未知" : heapPct + "%", blocked)));
        sections.add(reportSection("诊断结论", suggestions.stream()
                .map(s -> "- [" + s.get("severity") + "] " + s.get("title") + "：" + s.get("detail"))
                .reduce((a, b) -> a + "\n" + b).orElse("无")));
        List<Map<String, Object>> topUsers = topQueryUsers(5);
        if (!topUsers.isEmpty()) {
            sections.add(reportSection("高频用户", topUsers.stream()
                    .map(u -> String.format("%s：%s 次查询，均耗时 %sms，最大 %sms",
                            u.get("userId"), u.get("queryCount"), u.get("avgDurationMs"), u.get("maxDurationMs")))
                    .reduce((a, b) -> a + "\n" + b).orElse("无")));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportId", reportId);
        report.put("title", "性能瓶颈诊断报告");
        report.put("overallLevel", overallLevel);
        report.put("conclusion", conclusion);
        report.put("generatedAt", java.time.LocalDateTime.now().toString());
        report.put("generatedAtDisplay", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(java.time.LocalDateTime.now()));
        report.put("summary", Map.of(
                "slowCount", slowCount,
                "cacheHitRate", hitRate,
                "heapUsedPercent", heapPct == null ? 0 : heapPct,
                "blockedCount", blocked
        ));
        report.put("sections", sections);
        report.put("suggestions", suggestions);
        report.put("topSlowUsers", topUsers);
        return report;
    }

    private Map<String, Object> reportSection(String title, String content) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("title", title);
        section.put("content", content);
        return section;
    }

    private String buildReportConclusion(String overallLevel, long slowCount, double hitRate,
                                         Double heapPct, long blocked, List<Map<String, Object>> suggestions) {
        if ("HIGH".equals(overallLevel)) {
            return "系统存在较高性能风险，请优先处理标记为 HIGH 的项目（共 "
                    + suggestions.stream().filter(s -> "HIGH".equals(s.get("severity"))).count()
                    + " 项）。";
        }
        if ("MEDIUM".equals(overallLevel)) {
            return "系统整体可用，但存在可优化空间：慢查询 " + slowCount + " 条，缓存命中率 "
                    + hitRate + "%，建议按下方优化建议逐项调整。";
        }
        if (slowCount == 0 && hitRate >= 20 && (heapPct == null || heapPct < 70) && blocked == 0) {
            return "当前未发现明显性能瓶颈，各项指标处于正常范围，建议持续观察趋势。";
        }
        return "当前无高危瓶颈，建议关注缓存命中率与慢查询趋势，并按需微调治理参数。";
    }

    public int clearSemanticCache() {
        List<Map<String, Object>> keys = jdbcTemplate.queryForList("SELECT cache_key AS cacheKey FROM is_semantic_cache_audit");
        for (Map<String, Object> row : keys) {
            try {
                sqlAuditService.quarantineCache(Objects.toString(row.get("cacheKey"), ""), "管理员手动清理缓存");
            } catch (Exception ignored) {
            }
        }
        return jdbcTemplate.update("DELETE FROM is_semantic_cache_audit");
    }

    public List<Map<String, Object>> listSlowQueries(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        long slowMs = readSlowQueryThresholdMs();
        return jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, question, table_name AS tableName, engine,
                       generated_sql AS generatedSql, risk_level AS riskLevel,
                       slow_query AS slowQuery, execute_status AS executeStatus,
                       duration_ms AS durationMs, cache_key AS cacheKey, created_at AS createdAt
                FROM is_sql_audit_log
                WHERE slow_query = 1
                   OR (duration_ms IS NOT NULL AND duration_ms >= ?)
                ORDER BY COALESCE(duration_ms, 0) DESC, created_at DESC
                LIMIT
                """ + safe, slowMs);
    }

    public Map<String, Object> recordIntervention(long auditLogId, Map<String, Object> body) {
        return recordIntervention(auditLogId, body, false);
    }

    public Map<String, Object> terminateSlowQuery(long auditLogId, Map<String, Object> body) {
        return recordIntervention(auditLogId, body, true);
    }

    private Map<String, Object> recordIntervention(long auditLogId, Map<String, Object> body, boolean terminate) {
        String action = terminate ? "TERMINATE" : Objects.toString(body.getOrDefault("action", "ACK"), "ACK");
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        if (auditLogId <= 0) {
            throw new IllegalArgumentException("auditLogId 无效");
        }
        Map<String, Object> log = jdbcTemplate.queryForMap("""
                SELECT id, cache_key AS cacheKey FROM is_sql_audit_log WHERE id = ?
                """, auditLogId);
        if (terminate) {
            String cacheKey = Objects.toString(log.get("cacheKey"), "").trim();
            if (!cacheKey.isBlank()) {
                try {
                    sqlAuditService.quarantineCache(cacheKey, "管理员终止异常查询并隔离缓存");
                } catch (Exception ignored) {
                }
            }
            remark = remark == null ? "管理员标记终止异常查询" : remark;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO is_perf_intervention(audit_log_id, action, operator_user_id, remark)
                    VALUES (?, ?, ?, ?)
                    """, auditLogId, action, AuthContext.userId(), remark);
        } catch (DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("is_perf_intervention")) {
                throw new IllegalStateException("请先执行 Stack C  schema 初始化创建 is_perf_intervention 表");
            }
            throw e;
        }
        return Map.of("ok", true, "auditLogId", auditLogId, "action", action);
    }

    public List<Map<String, Object>> listBatchTasks(int limit) {
        int safe = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.queryForList("""
                SELECT task_id AS taskId, status, progress, message, created_at AS createdAt, updated_at AS updatedAt
                FROM is_file_process_task
                ORDER BY updated_at DESC
                LIMIT
                """ + safe);
    }

    public List<Map<String, Object>> listInterventions(int limit) {
        int safe = Math.max(1, Math.min(limit, 100));
        try {
            return jdbcTemplate.queryForList("""
                    SELECT id, audit_log_id AS auditLogId, action, operator_user_id AS operatorUserId,
                           remark, created_at AS createdAt
                    FROM is_perf_intervention
                    ORDER BY created_at DESC
                    LIMIT
                    """ + safe);
        } catch (DataAccessException e) {
            return new ArrayList<>();
        }
    }

    public List<Map<String, Object>> listCacheEntries(int limit) {
        return sqlAuditService.listCacheAudits(limit);
    }

    private Map<String, Object> readAlertConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slowQueryMs", runtimeConfig.getInt("perf.alert.slow_ms", 3000));
        m.put("cpuPercent", runtimeConfig.getInt("perf.alert.cpu.percent", 90));
        m.put("queryTimeoutMs", runtimeConfig.getInt("perf.alert.queryTimeoutMs", 30000));
        return m;
    }

    private Map<String, Object> readSlowQueryGovernance() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slowQueryMs", runtimeConfig.getInt("perf.alert.slow_ms", 3000));
        m.put("circuitEnabled", runtimeConfig.getBoolean("perf.slow.query.circuit.enabled", false));
        m.put("circuitThresholdMs", runtimeConfig.getInt("perf.slow.query.circuit.threshold", 8000));
        return m;
    }

    private Map<String, Object> readCacheConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", runtimeConfig.getBoolean("perf.redis.cache.enabled", redisEnabledDefault));
        m.put("ttlSeconds", runtimeConfig.getInt("perf.redis.cache.ttlSeconds", 3600));
        return m;
    }

    private Map<String, Object> readBatchConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxConcurrency", runtimeConfig.getInt("perf.batch.task.maxConcurrency", 3));
        m.put("timeoutSeconds", runtimeConfig.getInt("perf.batch.task.timeoutSeconds", 600));
        m.put("prewarmEnabled", runtimeConfig.getBoolean("perf.dashboard.prewarm.enabled", false));
        m.put("prewarmCron", runtimeConfig.getString("perf.dashboard.prewarm.cron", "0 0 6 * * ?"));
        return m;
    }

    private Map<String, Object> readDbPressureConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolMaxSize", runtimeConfig.getInt("perf.db.pool.maxSize", 20));
        m.put("maxConcurrentPerUser", runtimeConfig.getInt("perf.db.query.maxConcurrent", 4));
        m.put("maxAccessPerMinute", runtimeConfig.getInt("perf.db.access.maxPerMinute", 120));
        return m;
    }

    private Map<String, Object> readResourceConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text2sql", runtimeConfig.getInt("perf.resource.priority.text2sql", 90));
        m.put("graphrag", runtimeConfig.getInt("perf.resource.priority.graphrag", 85));
        m.put("upload", runtimeConfig.getInt("perf.resource.priority.upload", 40));
        m.put("dashboard", runtimeConfig.getInt("perf.resource.priority.dashboard", 60));
        return m;
    }

    private Map<String, Object> dbPressureSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxConcurrentPerUser", runtimeConfig.getInt("perf.db.query.maxConcurrent", 4));
        m.put("maxAccessPerMinute", runtimeConfig.getInt("perf.db.access.maxPerMinute", 120));
        m.put("topUsersLastHour", topQueryUsers(8));
        m.put("runtime", sqlAuditService.dbPressureRuntime());
        return m;
    }

    private List<Map<String, Object>> topQueryUsers(int limit) {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT user_id AS userId, COUNT(*) AS queryCount,
                           ROUND(AVG(COALESCE(duration_ms, 0)), 0) AS avgDurationMs,
                           MAX(duration_ms) AS maxDurationMs
                    FROM is_sql_audit_log
                    WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
                    GROUP BY user_id
                    ORDER BY queryCount DESC
                    LIMIT ?
                    """, Math.max(1, Math.min(limit, 20)));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> datasourceSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Map<String, Object> agg = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS total,
                           COALESCE(ROUND(AVG(pool_max_size)), 10) AS avgPoolMax,
                           SUM(CASE WHEN last_health_status = 'UP' THEN 1 ELSE 0 END) AS healthyCount
                    FROM is_official_datasource
                    WHERE status <> 'DELETED'
                    """);
            m.put("datasourceCount", number(agg.get("total")));
            m.put("avgPoolMax", number(agg.get("avgPoolMax")));
            m.put("healthyCount", number(agg.get("healthyCount")));
        } catch (Exception e) {
            m.put("datasourceCount", 0);
            m.put("avgPoolMax", 10);
            m.put("healthyCount", 0);
        }
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            m.put("jdbcConnected", conn.isValid(2));
        } catch (Exception e) {
            m.put("jdbcConnected", false);
        }
        m.put("configuredPoolMax", runtimeConfig.getInt("perf.db.pool.maxSize", 20));
        return m;
    }

    private List<Map<String, Object>> engineStatus() {
        List<Map<String, Object>> engines = new ArrayList<>();
        engines.add(engine("Text-to-SQL", "NL2SQL", pingUrl(aiServiceUrl + "/health"), "Python AI 服务"));
        engines.add(engine("GraphRAG", "GRAPH", pingUrl(aiServiceUrl + "/health"), "知识图谱 + AI 推理"));
        engines.add(engine("SQL 审计", "AUDIT", jdbcUp(), "is_sql_audit_log 写入正常"));
        engines.add(engine("语义缓存", "CACHE", redisEnabledDefault ? "CONFIGURED" : "LOCAL", "Redis 语义缓存引擎"));
        return engines;
    }

    private Map<String, Object> engine(String name, String code, String status, String note) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", name);
        e.put("code", code);
        e.put("status", status);
        e.put("healthy", "UP".equals(status) || "CONFIGURED".equals(status));
        e.put("note", note);
        return e;
    }

    private String pingUrl(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 500 ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String jdbcUp() {
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            return conn.isValid(2) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private Map<String, Object> diskSnapshot() {
        File root = new File(".");
        long total = root.getTotalSpace();
        long free = root.getFreeSpace();
        long used = total - free;
        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("path", root.getAbsolutePath());
        disk.put("totalBytes", total > 0 ? total : null);
        disk.put("freeBytes", total > 0 ? free : null);
        disk.put("usedBytes", total > 0 ? used : null);
        disk.put("usedPercent", total > 0 ? Math.round(10000.0 * used / total) / 100.0 : null);
        return disk;
    }

    private Map<String, Object> jvmSnapshot() {
        Runtime rt = Runtime.getRuntime();
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memBean.getHeapMemoryUsage().getUsed();
        long heapMax = memBean.getHeapMemoryUsage().getMax();
        long nonHeap = memBean.getNonHeapMemoryUsage().getUsed();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double load = osBean.getSystemLoadAverage();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("processors", rt.availableProcessors());
        jvm.put("heapUsedBytes", heapUsed);
        jvm.put("heapMaxBytes", heapMax > 0 ? heapMax : null);
        jvm.put("nonHeapUsedBytes", nonHeap);
        jvm.put("heapUsedPercent", heapMax > 0 ? Math.round(10000.0 * heapUsed / heapMax) / 100.0 : null);
        jvm.put("systemLoadAverage", load >= 0 ? load : null);
        return jvm;
    }

    private long readSlowQueryThresholdMs() {
        return runtimeConfig.getInt("perf.alert.slow_ms", 3000);
    }

    private void syncSqlRule(String ruleCode, String thresholdMs, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        if (thresholdMs != null && !thresholdMs.isBlank()) {
            body.put("thresholdValue", Long.parseLong(thresholdMs.trim()));
        }
        sqlAuditService.updateRuleConfig(ruleCode, body);
        if ("SLOW_QUERY".equals(ruleCode)) {
            upsertConfig("perf.alert.slow_ms", thresholdMs, "NUMBER", "PERFORMANCE", "慢查询阈值(ms)");
        }
    }

    private void upsertConfig(String key, String value, String type, String category, String desc) {
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_system_config(config_key, config_value, value_type, category, description, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  config_value = VALUES(config_value),
                  value_type = VALUES(value_type),
                  category = VALUES(category),
                  description = VALUES(description),
                  updated_by = VALUES(updated_by),
                  updated_at = CURRENT_TIMESTAMP
                """, key, value, type, category, desc, uid);
    }

    private Map<String, Object> suggestion(String type, String severity, String title, String detail) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("severity", severity);
        s.put("title", title);
        s.put("detail", detail);
        return s;
    }

    private static long number(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double parseDouble(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean parseBool(Object v, boolean def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }

    private static String str(Object v, String def) {
        if (v == null || String.valueOf(v).isBlank()) {
            return def;
        }
        return String.valueOf(v).trim();
    }
}
