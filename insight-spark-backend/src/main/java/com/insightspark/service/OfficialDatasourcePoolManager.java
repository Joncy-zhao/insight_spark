package com.insightspark.service;

import com.insightspark.core.security.DatasourcePasswordEncryptor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OfficialDatasourcePoolManager {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<Long, HikariDataSource> poolMap = new ConcurrentHashMap<>();

    public Connection getConnection(Long datasourceId) throws SQLException {
        return getOrCreatePool(datasourceId).getConnection();
    }

    public void rebuild(Long datasourceId) {
        remove(datasourceId);
        getOrCreatePool(datasourceId);
    }

    public void remove(Long datasourceId) {
        HikariDataSource oldPool = poolMap.remove(datasourceId);
        if (oldPool != null) {
            oldPool.close();
        }
    }

    public Map<String, Object> health(Long datasourceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasourceId", datasourceId);

        HikariDataSource pool = poolMap.get(datasourceId);
        if (pool == null) {
            result.put("poolCreated", false);
            result.put("status", "NOT_CREATED");
            return result;
        }

        HikariPoolMXBean mxBean = pool.getHikariPoolMXBean();
        result.put("poolCreated", true);
        result.put("poolName", pool.getPoolName());
        result.put("closed", pool.isClosed());
        result.put("maximumPoolSize", pool.getMaximumPoolSize());
        result.put("minimumIdle", pool.getMinimumIdle());
        result.put("connectionTimeout", pool.getConnectionTimeout());
        result.put("readOnly", pool.isReadOnly());

        if (mxBean != null) {
            result.put("activeConnections", mxBean.getActiveConnections());
            result.put("idleConnections", mxBean.getIdleConnections());
            result.put("totalConnections", mxBean.getTotalConnections());
            result.put("threadsAwaitingConnection", mxBean.getThreadsAwaitingConnection());
        }

        return result;
    }

    private HikariDataSource getOrCreatePool(Long datasourceId) {
        return poolMap.computeIfAbsent(datasourceId, this::createPool);
    }

    private HikariDataSource createPool(Long datasourceId) {
        Map<String, Object> ds = findDatasource(datasourceId);

        HikariConfig config = new HikariConfig();
        config.setPoolName("official-ds-" + datasourceId);
        config.setJdbcUrl(Objects.toString(ds.get("jdbc_url")));
        config.setUsername(Objects.toString(ds.get("username")));
        config.setPassword(DatasourcePasswordEncryptor.decrypt(Objects.toString(ds.get("password"))));

        int maxSize = number(ds.get("pool_max_size"), 10);
        int timeoutMs = number(ds.get("pool_timeout_ms"), 30000);

        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(timeoutMs);
        config.setValidationTimeout(Math.min(timeoutMs, 5000));
        config.setReadOnly(true);
        config.setConnectionTestQuery("SELECT 1");
        config.setInitializationFailTimeout(-1);

        return new HikariDataSource(config);
    }

    private Map<String, Object> findDatasource(Long datasourceId) {
        var rows = jdbcTemplate.queryForList("""
                SELECT id, name, db_type, host, port, database_name, username, password, jdbc_url, status,
                       pool_max_size, pool_timeout_ms, readonly_enforced
                FROM is_official_datasource
                WHERE id = ? AND status <> 'DELETED'
                """, datasourceId);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("数据源不存在：" + datasourceId);
        }

        return rows.get(0);
    }

    private int number(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}