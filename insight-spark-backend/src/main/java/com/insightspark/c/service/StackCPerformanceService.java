package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import com.insightspark.service.SqlAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
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

    public Map<String, Object> overview() {
        Map<String, Object> jvm = jvmSnapshot();
        Map<String, Object> audit = sqlAuditService.stats();
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("available", false);
        cache.put("hint", "Redis 语义缓存未接入时，命中率可在接入后写入 is_system_config 或采集上报");

        Map<String, Object> alertDefaults = readPerfConfig();

        return Map.of(
                "jvm", jvm,
                "sqlAudit", audit,
                "cache", cache,
                "alertConfig", alertDefaults
        );
    }

    public List<Map<String, Object>> listSlowQueries(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        long slowMs = readSlowQueryThresholdMs();
        return jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, question, table_name AS tableName, engine,
                       generated_sql AS generatedSql, risk_level AS riskLevel,
                       slow_query AS slowQuery, execute_status AS executeStatus,
                       duration_ms AS durationMs, created_at AS createdAt
                FROM is_sql_audit_log
                WHERE slow_query = 1
                   OR (duration_ms IS NOT NULL AND duration_ms >= ?)
                ORDER BY COALESCE(duration_ms, 0) DESC, created_at DESC
                LIMIT
                """ + safe, slowMs);
    }

    public Map<String, Object> recordIntervention(long auditLogId, Map<String, Object> body) {
        String action = Objects.toString(body.getOrDefault("action", "ACK"), "ACK");
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        if (auditLogId <= 0) {
            throw new IllegalArgumentException("auditLogId 无效");
        }
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_sql_audit_log WHERE id = ?", Integer.class, auditLogId);
        if (cnt == null || cnt == 0) {
            throw new IllegalArgumentException("审计日志不存在");
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO is_perf_intervention(audit_log_id, action, operator_user_id, remark)
                    VALUES (?, ?, ?, ?)
                    """, auditLogId, action, AuthContext.userId(), remark);
        } catch (DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("is_perf_intervention")) {
                throw new IllegalStateException("请先执行 sql/insight_spark_schema_stack_c.sql 中 C.6 段落创建 is_perf_intervention 表");
            }
            throw e;
        }
        return Map.of("ok", true, "auditLogId", auditLogId);
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

    public Map<String, Object> getAlertConfig() {
        return readPerfConfig();
    }

    public void saveAlertConfig(Map<String, Object> body) {
        upsertConfig("perf.alert.slow_ms", Objects.toString(body.getOrDefault("slowQueryMs", "3000")), "NUMBER", "PERFORMANCE", "慢查询阈值(ms)");
        upsertConfig("perf.alert.cpu.percent", Objects.toString(body.getOrDefault("cpuPercent", "90")), "NUMBER", "PERFORMANCE", "CPU 告警阈值(%)");
    }

    private Map<String, Object> readPerfConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slowQueryMs", parseLongConfig("perf.alert.slow_ms", 3000L));
        m.put("cpuPercent", parseLongConfig("perf.alert.cpu.percent", 90L));
        return m;
    }

    private long parseLongConfig(String key, long def) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT config_value FROM is_system_config WHERE config_key = ?", key);
            if (rows.isEmpty() || rows.get(0).get("config_value") == null) {
                return def;
            }
            return Long.parseLong(String.valueOf(rows.get(0).get("config_value")).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private long readSlowQueryThresholdMs() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT threshold_value FROM is_sql_audit_rule WHERE rule_code = 'SLOW_QUERY' AND enabled = 1
                    """);
            if (!rows.isEmpty() && rows.get(0).get("threshold_value") != null) {
                Object v = rows.get(0).get("threshold_value");
                if (v instanceof Number n) {
                    return n.longValue() > 0 ? n.longValue() : 3000L;
                }
            }
        } catch (Exception ignored) {
        }
        return 3000L;
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

    private Map<String, Object> jvmSnapshot() {
        Runtime rt = Runtime.getRuntime();
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memBean.getHeapMemoryUsage().getUsed();
        long heapMax = memBean.getHeapMemoryUsage().getMax();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double load = osBean.getSystemLoadAverage();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("processors", rt.availableProcessors());
        jvm.put("heapUsedBytes", heapUsed);
        jvm.put("heapMaxBytes", heapMax > 0 ? heapMax : null);
        jvm.put("heapUsedPercent", heapMax > 0 ? Math.round(10000.0 * heapUsed / heapMax) / 100.0 : null);
        jvm.put("systemLoadAverage", load >= 0 ? load : null);
        jvm.put("note", "进程级 CPU 需 com.sun.management 扩展或宿主监控；此处为 JVM 堆与系统负载参考值");
        return jvm;
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
}
