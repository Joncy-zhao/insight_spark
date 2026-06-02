package com.insightspark.service;

import com.insightspark.core.auth.AuthContext;
import com.insightspark.core.security.DatasourcePasswordEncryptor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DatasourceService {

    private static final Pattern SAFE_ROW_POLICY = Pattern.compile("^[\\p{L}\\p{N}_\\s`\"'.=<>!%(),:-]+$");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[\\p{L}\\p{N}_.$]+$");
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int MAX_QUERY_ROWS = 500;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    private OfficialDatasourcePoolManager poolManager;

    @Autowired
    private SqlAuditService sqlAuditService;

    @PostConstruct
    public void initDatasourceTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_official_datasource` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `name` VARCHAR(255) NOT NULL,
                  `db_type` VARCHAR(32) NOT NULL DEFAULT 'MYSQL',
                  `host` VARCHAR(255) NOT NULL,
                  `port` INT NOT NULL,
                  `database_name` VARCHAR(128) NOT NULL,
                  `username` VARCHAR(128) NOT NULL,
                  `password` VARCHAR(255) NOT NULL,
                  `jdbc_url` VARCHAR(1000) NOT NULL,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'DISABLED',
                  `last_test_status` VARCHAR(32) NULL,
                  `last_test_message` VARCHAR(1000) NULL,
                  `last_sync_at` DATETIME NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源配置';
                """);
        addColumnIfMissing("is_official_datasource", "pool_max_size", "`pool_max_size` INT NOT NULL DEFAULT 10");
        addColumnIfMissing("is_official_datasource", "pool_timeout_ms", "`pool_timeout_ms` INT NOT NULL DEFAULT 30000");
        addColumnIfMissing("is_official_datasource", "readonly_enforced", "`readonly_enforced` TINYINT(1) NOT NULL DEFAULT 1");
        addColumnIfMissing("is_official_datasource", "kg_sync_rule", "`kg_sync_rule` VARCHAR(1000) NULL");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_official_schema_table` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `datasource_id` BIGINT NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `table_comment` VARCHAR(512) NULL,
                  `table_rows` BIGINT NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_official_schema_table` (`datasource_id`, `table_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源表结构';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_official_schema_field` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `datasource_id` BIGINT NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `column_name` VARCHAR(128) NOT NULL,
                  `data_type` VARCHAR(64) NOT NULL,
                  `column_comment` VARCHAR(512) NULL,
                  `is_nullable` VARCHAR(8) NULL,
                  `column_key` VARCHAR(16) NULL,
                  `ordinal_position` INT NOT NULL,
                  `business_name` VARCHAR(255) NULL,
                  `sensitive` TINYINT(1) NOT NULL DEFAULT 0,
                  UNIQUE KEY `uk_official_schema_field` (`datasource_id`, `table_name`, `column_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源字段结构';
                """);
        addColumnIfMissing("is_official_schema_field", "business_desc", "`business_desc` VARCHAR(1000) NULL");
        addColumnIfMissing("is_official_schema_field", "synonyms", "`synonyms` VARCHAR(1000) NULL");
        addColumnIfMissing("is_official_schema_field", "kg_sync_enabled", "`kg_sync_enabled` TINYINT(1) NOT NULL DEFAULT 1");
        addColumnIfMissing("is_official_schema_field", "kg_sync_rule", "`kg_sync_rule` VARCHAR(1000) NULL");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_official_schema_relation` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `datasource_id` BIGINT NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `column_name` VARCHAR(128) NOT NULL,
                  `referenced_table_name` VARCHAR(128) NOT NULL,
                  `referenced_column_name` VARCHAR(128) NOT NULL,
                  `constraint_name` VARCHAR(255) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_official_schema_relation` (`datasource_id`, `table_name`, `column_name`, `referenced_table_name`, `referenced_column_name`),
                  INDEX `idx_official_schema_relation_ds` (`datasource_id`, `table_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源外键关系';
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_official_row_policy` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `datasource_id` BIGINT NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `principal_type` VARCHAR(32) NOT NULL,
                  `principal_id` VARCHAR(128) NOT NULL,
                  `filter_expression` VARCHAR(1000) NOT NULL,
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_official_row_policy_ds` (`datasource_id`, `table_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源行级隔离规则';
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_neo4j_runtime_config` (
                  `id` BIGINT PRIMARY KEY,
                  `uri` VARCHAR(512) NOT NULL DEFAULT 'http://localhost:7474',
                  `username` VARCHAR(128) NOT NULL DEFAULT 'neo4j',
                  `password` VARCHAR(255) NOT NULL DEFAULT '',
                  `database_name` VARCHAR(128) NOT NULL DEFAULT 'neo4j',
                  `sync_rule` VARCHAR(1000) NULL,
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Neo4j知识图谱运行配置';
                """);
        jdbcTemplate.update("""
                INSERT INTO is_neo4j_runtime_config(id, uri, username, password, database_name, sync_rule, enabled)
                VALUES (1, 'http://localhost:7474', 'neo4j', 'nisibusisa250', 'neo4j', '同步官方数据源表、字段、业务含义、同义词和联邦关系', 1)
                ON DUPLICATE KEY UPDATE id = id
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_official_datasource_permission` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `datasource_id` BIGINT NOT NULL,
                  `principal_type` VARCHAR(32) NOT NULL,
                  `principal_id` VARCHAR(128) NOT NULL,
                  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
                  `expire_at` DATETIME NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_official_ds_permission` (`datasource_id`, `principal_type`, `principal_id`, `permission_type`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方数据源用户角色授权';
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_official_table_permission` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `datasource_id` BIGINT NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `principal_type` VARCHAR(32) NOT NULL,
                  `principal_id` VARCHAR(128) NOT NULL,
                  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
                  `expire_at` DATETIME NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_official_table_permission` (`datasource_id`, `table_name`, `principal_type`, `principal_id`, `permission_type`),
                  INDEX `idx_official_table_permission_principal` (`principal_type`, `principal_id`, `permission_type`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='official table permission';
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_federal_relation` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `datasource_id` BIGINT NOT NULL,
                  `left_table` VARCHAR(128) NOT NULL,
                  `left_field` VARCHAR(128) NOT NULL,
                  `right_source_type` VARCHAR(32) NOT NULL,
                  `right_table` VARCHAR(128) NOT NULL,
                  `right_field` VARCHAR(128) NOT NULL,
                  `relation_type` VARCHAR(32) NOT NULL DEFAULT 'LEFT_JOIN',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_federal_relation_ds` (`datasource_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Excel与官方库联邦关联配置';
                """);
    }

    public Map<String, Object> createDatasource(Map<String, Object> request) {
        String name = requiredString(request, "name");
        String dbType = normalizeDbType(Objects.toString(request.getOrDefault("dbType", "MYSQL")));
        String host = requiredString(request, "host");
        int port = parseInt(request.get("port"), "POSTGRESQL".equals(dbType) ? 5432 : 3306);
        String databaseName = requiredString(request, "databaseName");
        String username = requiredString(request, "username");
        String password = DatasourcePasswordEncryptor.encrypt(requiredString(request, "password"));
        String jdbcUrl = buildJdbcUrl(dbType, host, port, databaseName);
        jdbcTemplate.update("""
                INSERT INTO is_official_datasource(name, db_type, host, port, database_name, username, password, jdbc_url,
                                                   status, pool_max_size, pool_timeout_ms, readonly_enforced, kg_sync_rule)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISABLED', ?, ?, 1, ?)
                """, name, dbType, host, port, databaseName, username, password, jdbcUrl,
                parseInt(request.get("poolMaxSize"), 10), parseInt(request.get("poolTimeoutMs"), 30000),
                Objects.toString(request.getOrDefault("kgSyncRule", "")));
        return latestDatasource();
    }

    public Map<String, Object> updateDatasource(Long datasourceId, Map<String, Object> request) {
        Map<String, Object> current = findDatasource(datasourceId);
        String dbType = normalizeDbType(textOr(request.get("dbType"), current.get("db_type")));
        String name = textOr(request.get("name"), current.get("name"));
        String host = textOr(request.get("host"), current.get("host"));
        int port = parseInt(request.get("port"), ((Number) current.get("port")).intValue());
        String databaseName = textOr(request.get("databaseName"), current.get("database_name"));
        String username = textOr(request.get("username"), current.get("username"));
        String password = textOr(request.get("password"), current.get("password"));
        if (!password.startsWith("ENC:")) {
            password = DatasourcePasswordEncryptor.encrypt(password);
        }
        String jdbcUrl = buildJdbcUrl(dbType, host, port, databaseName);
        jdbcTemplate.update("""
                UPDATE is_official_datasource
                SET name = ?, db_type = ?, host = ?, port = ?, database_name = ?, username = ?, password = ?, jdbc_url = ?,
                    pool_max_size = ?, pool_timeout_ms = ?, kg_sync_rule = ?
                WHERE id = ?
                """, name, dbType, host, port, databaseName, username, password, jdbcUrl,
                parseInt(request.get("poolMaxSize"), 10), parseInt(request.get("poolTimeoutMs"), 30000),
                Objects.toString(request.getOrDefault("kgSyncRule", current.getOrDefault("kg_sync_rule", ""))), datasourceId);
        
        poolManager.rebuild(datasourceId);
        
        return findDatasourcePublic(datasourceId);
    }

    public void deleteDatasource(Long datasourceId) {
        jdbcTemplate.update("UPDATE is_official_datasource SET status = 'DELETED' WHERE id = ?", datasourceId);
        poolManager.remove(datasourceId);
    }

    public void updateStatus(Long datasourceId, String status) {
        String nextStatus = status == null ? "" : status.trim().toUpperCase();
        if (!nextStatus.equals("ENABLED") && !nextStatus.equals("DISABLED")) {
            throw new IllegalArgumentException("状态只能是 ENABLED 或 DISABLED");
        }
        jdbcTemplate.update("UPDATE is_official_datasource SET status = ? WHERE id = ?", nextStatus, datasourceId);
        
        if ("DISABLED".equals(nextStatus)) {
            poolManager.remove(datasourceId);
        } else {
            poolManager.rebuild(datasourceId);
        }
    }

    public List<Map<String, Object>> listDatasources() {
        return jdbcTemplate.queryForList("""
                SELECT id, name, db_type AS dbType, host, port, database_name AS databaseName,
                       username, jdbc_url AS jdbcUrl, status, pool_max_size AS poolMaxSize,
                       pool_timeout_ms AS poolTimeoutMs, readonly_enforced AS readonlyEnforced,
                       kg_sync_rule AS kgSyncRule,
                       last_test_status AS lastTestStatus, last_test_message AS lastTestMessage,
                       last_sync_at AS lastSyncAt, created_at AS createdAt
                FROM is_official_datasource
                WHERE status <> 'DELETED'
                ORDER BY created_at DESC
                """);
    }

    public void assertCanAccessOfficialSource(String sourceKey) {
        OfficialSource source = parseSourceKey(sourceKey);
        Map<String, Object> datasource = findDatasource(source.datasourceId());
        String status = Objects.toString(datasource.get("status"), "");
        if (!"ENABLED".equals(status) && !AuthContext.isAdmin()) {
            throw new IllegalArgumentException("官方数据源未启用或无访问权限：" + sourceKey);
        }
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_official_schema_table
                WHERE datasource_id = ? AND table_name = ?
                """, Integer.class, source.datasourceId(), source.tableName());
        if (tableCount == null || tableCount == 0) {
            throw new IllegalArgumentException("官方数据表不存在或尚未解析 Schema：" + sourceKey);
        }
        if (AuthContext.isAdmin()) {
            return;
        }
        if (!hasOfficialTablePermission(source, "READ")) {
            throw new IllegalArgumentException("当前用户无权访问官方数据源：" + sourceKey);
        }
    }

    public Map<String, Object> testConnection(Long datasourceId) {
        long startedAt = System.currentTimeMillis();

        try {
            poolManager.rebuild(datasourceId);

            try (Connection connection = poolManager.getConnection(datasourceId);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {

                resultSet.next();
                long durationMs = System.currentTimeMillis() - startedAt;
                String message = "连接成功，耗时 " + durationMs + " ms";

                jdbcTemplate.update("""
                        UPDATE is_official_datasource
                        SET last_test_status = 'SUCCESS', last_test_message = ?
                        WHERE id = ?
                        """, message, datasourceId);

                return Map.of("status", "SUCCESS", "message", message, "durationMs", durationMs);
            }
        } catch (Exception e) {
            jdbcTemplate.update("""
                    UPDATE is_official_datasource
                    SET last_test_status = 'FAILED', last_test_message = ?
                    WHERE id = ?
                    """, e.getMessage(), datasourceId);

            return Map.of("status", "FAILED", "message", e.getMessage());
        }
    }

    public Map<String, Object> syncSchema(Long datasourceId) {
        Map<String, Object> datasource = findDatasource(datasourceId);
        String databaseName = String.valueOf(datasource.get("database_name"));
        String dbType = Objects.toString(datasource.get("db_type"), "MYSQL").toUpperCase();
        int tableCount = 0;
        int fieldCount = 0;
        int relationCount = 0;
        jdbcTemplate.update("DELETE FROM is_official_schema_field WHERE datasource_id = ?", datasourceId);
        jdbcTemplate.update("DELETE FROM is_official_schema_table WHERE datasource_id = ?", datasourceId);
        jdbcTemplate.update("DELETE FROM is_official_schema_relation WHERE datasource_id = ?", datasourceId);

        try (Connection connection = openConnection(datasourceId);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (ResultSet tables = statement.executeQuery(buildTableMetaSql(dbType, databaseName))) {
                while (tables.next()) {
                    jdbcTemplate.update("""
                            INSERT INTO is_official_schema_table(datasource_id, table_name, table_comment, table_rows)
                            VALUES (?, ?, ?, ?)
                            """, datasourceId, tables.getString("table_name"),
                            tables.getString("table_comment"), tables.getLong("table_rows"));
                    tableCount++;
                }
            }
            try (ResultSet columns = statement.executeQuery(buildColumnMetaSql(dbType, databaseName))) {
                while (columns.next()) {
                    String columnName = columns.getString("column_name");
                    jdbcTemplate.update("""
                            INSERT INTO is_official_schema_field(datasource_id, table_name, column_name, data_type,
                                                                 column_comment, is_nullable, column_key, ordinal_position, `sensitive`)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, datasourceId, columns.getString("table_name"), columnName,
                            columns.getString("data_type"), columns.getString("column_comment"),
                            columns.getString("is_nullable"), columns.getString("column_key"),
                            columns.getInt("ordinal_position"), isSensitiveColumn(columnName));
                    fieldCount++;
                }
            }
            try (ResultSet relations = statement.executeQuery(buildForeignKeyMetaSql(dbType, databaseName))) {
                while (relations.next()) {
                    jdbcTemplate.update("""
                            INSERT INTO is_official_schema_relation(datasource_id, table_name, column_name,
                                                                    referenced_table_name, referenced_column_name, constraint_name)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON DUPLICATE KEY UPDATE constraint_name = VALUES(constraint_name)
                            """, datasourceId, relations.getString("table_name"), relations.getString("column_name"),
                            relations.getString("referenced_table_name"), relations.getString("referenced_column_name"),
                            relations.getString("constraint_name"));
                    relationCount++;
                }
            }
            jdbcTemplate.update("UPDATE is_official_datasource SET last_sync_at = NOW() WHERE id = ?", datasourceId);
            if (knowledgeGraphService != null) {
                knowledgeGraphService.syncGraph();
            }
            return Map.of("tableCount", tableCount, "fieldCount", fieldCount, "relationCount", relationCount);
        } catch (Exception e) {
            throw new IllegalArgumentException("Schema 解析失败：" + e.getMessage());
        }
    }

    public Map<String, Object> syncKnowledgeGraph() {
        if (knowledgeGraphService == null) {
            return Map.of("enabled", false, "message", "知识图谱服务未启用");
        }
        Map<String, Object> result = knowledgeGraphService.syncGraph();
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("enabled", true);
        return out;
    }

    public List<Map<String, Object>> listSchemaTables(Long datasourceId) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, table_name AS tableName, table_comment AS tableComment,
                       table_rows AS tableRows,
                       (
                           SELECT COUNT(*)
                           FROM is_official_schema_field f
                           WHERE f.datasource_id = t.datasource_id
                             AND f.table_name = t.table_name
                       ) AS fieldCount,
                       created_at AS createdAt
                FROM is_official_schema_table
                t
                WHERE datasource_id = ?
                ORDER BY table_name ASC
                """, datasourceId);
    }

    public List<Map<String, Object>> listSchemaFields(Long datasourceId, String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, table_name AS tableName, column_name AS columnName,
                       data_type AS dataType, column_comment AS columnComment, is_nullable AS isNullable,
                       column_key AS columnKey, ordinal_position AS ordinalPosition, business_name AS businessName,
                       business_desc AS businessDesc, synonyms, kg_sync_enabled AS kgSyncEnabled,
                       kg_sync_rule AS kgSyncRule, `sensitive`
                FROM is_official_schema_field
                WHERE datasource_id = ? AND table_name = ?
                ORDER BY ordinal_position ASC
                """, datasourceId, tableName);
    }

    public List<Map<String, Object>> listSchemaRelations(Long datasourceId) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, table_name AS tableName, column_name AS columnName,
                       referenced_table_name AS referencedTableName, referenced_column_name AS referencedColumnName,
                       constraint_name AS constraintName, created_at AS createdAt
                FROM is_official_schema_relation
                WHERE datasource_id = ?
                ORDER BY table_name ASC, column_name ASC
                """, datasourceId);
    }

    public void updateFieldMeta(Long fieldId, Map<String, Object> request) {
        jdbcTemplate.update("""
                UPDATE is_official_schema_field
                SET business_name = ?, business_desc = ?, synonyms = ?, kg_sync_enabled = ?,
                    kg_sync_rule = ?, `sensitive` = ?
                WHERE id = ?
                """, Objects.toString(request.getOrDefault("businessName", "")),
                Objects.toString(request.getOrDefault("businessDesc", "")),
                Objects.toString(request.getOrDefault("synonyms", "")),
                parseBooleanFlag(request.getOrDefault("kgSyncEnabled", true)),
                Objects.toString(request.getOrDefault("kgSyncRule", "")),
                parseBooleanFlag(request.getOrDefault("sensitive", false)), fieldId);
    }

    private boolean parseBooleanFlag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = Objects.toString(value, "").trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
    }

    public List<Map<String, Object>> listPermissions(Long datasourceId) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, table_name AS tableName,
                       CASE WHEN table_name = '*' THEN 'DATASOURCE' ELSE 'TABLE' END AS scopeType,
                       principal_type AS principalType, principal_id AS principalId, permission_type AS permissionType,
                       expire_at AS expireAt, created_at AS createdAt
                FROM is_official_table_permission
                WHERE datasource_id = ?
                ORDER BY created_at DESC, table_name ASC
                """, datasourceId);
    }

    public Map<String, Object> grantPermission(Long datasourceId, Map<String, Object> request) {
        String tableName = Objects.toString(request.getOrDefault("tableName", "*"), "").trim();
        if (tableName.isBlank()) {
            tableName = "*";
        }
        if (!"*".equals(tableName)) {
            assertOfficialTableExists(datasourceId, tableName);
        }
        String principalType = normalizePrincipalType(request.get("principalType"));
        String principalId = requiredString(request, "principalId");
        String permissionType = normalizeOfficialPermissionType(request.get("permissionType"));
        jdbcTemplate.update("""
                INSERT INTO is_official_table_permission(datasource_id, table_name, principal_type, principal_id, permission_type)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE created_at = CURRENT_TIMESTAMP
                """, datasourceId, tableName, principalType, principalId, permissionType);
        if ("*".equals(tableName)) {
            jdbcTemplate.update("""
                    INSERT INTO is_official_datasource_permission(datasource_id, principal_type, principal_id, permission_type)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE created_at = CURRENT_TIMESTAMP
                    """, datasourceId, principalType, principalId, permissionType);
        }
        return Map.of("datasourceId", datasourceId, "tableName", tableName, "principalType", principalType,
                "principalId", principalId, "permissionType", permissionType);
    }

    public void revokePermission(Long permissionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT datasource_id AS datasourceId, table_name AS tableName,
                       principal_type AS principalType, principal_id AS principalId, permission_type AS permissionType
                FROM is_official_table_permission
                WHERE id = ?
                """, permissionId);
        jdbcTemplate.update("DELETE FROM is_official_table_permission WHERE id = ?", permissionId);
        if (!rows.isEmpty() && "*".equals(Objects.toString(rows.get(0).get("tableName"), ""))) {
            Map<String, Object> row = rows.get(0);
            jdbcTemplate.update("""
                    DELETE FROM is_official_datasource_permission
                    WHERE datasource_id = ? AND principal_type = ? AND principal_id = ? AND permission_type = ?
                    """, row.get("datasourceId"), row.get("principalType"), row.get("principalId"), row.get("permissionType"));
        }
    }

    public List<Map<String, Object>> listFederalRelations(Long datasourceId) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, left_table AS leftTable, left_field AS leftField,
                       right_source_type AS rightSourceType, right_table AS rightTable, right_field AS rightField,
                       relation_type AS relationType, created_at AS createdAt
                FROM is_federal_relation
                WHERE datasource_id = ?
                ORDER BY created_at DESC
                """, datasourceId);
    }

    public Map<String, Object> saveFederalRelation(Long datasourceId, Map<String, Object> request) {
        String leftTable = requiredString(request, "leftTable");
        String leftField = requiredString(request, "leftField");
        String rightSourceType = Objects.toString(request.getOrDefault("rightSourceType", "UPLOAD")).toUpperCase();
        String rightTable = requiredString(request, "rightTable");
        String rightField = requiredString(request, "rightField");
        String relationType = Objects.toString(request.getOrDefault("relationType", "LEFT_JOIN")).toUpperCase();
        validateFederalRelation(datasourceId, leftTable, leftField, rightSourceType, rightTable, rightField, relationType);
        jdbcTemplate.update("""
                DELETE FROM is_federal_relation
                WHERE datasource_id = ? AND left_table = ? AND left_field = ? AND right_source_type = ?
                  AND right_table = ? AND right_field = ?
                """, datasourceId, leftTable, leftField, rightSourceType, rightTable, rightField);
        jdbcTemplate.update("""
                INSERT INTO is_federal_relation(datasource_id, left_table, left_field, right_source_type, right_table, right_field, relation_type)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, datasourceId, leftTable, leftField, rightSourceType, rightTable, rightField, relationType);
        return Map.of("datasourceId", datasourceId, "leftTable", leftTable, "leftField", leftField,
                "rightSourceType", rightSourceType, "rightTable", rightTable, "rightField", rightField,
                "relationType", relationType);
    }

    public Map<String, Object> validateFederalRelation(Long datasourceId, Map<String, Object> request) {
        String leftTable = requiredString(request, "leftTable");
        String leftField = requiredString(request, "leftField");
        String rightSourceType = Objects.toString(request.getOrDefault("rightSourceType", "UPLOAD")).toUpperCase();
        String rightTable = requiredString(request, "rightTable");
        String rightField = requiredString(request, "rightField");
        String relationType = Objects.toString(request.getOrDefault("relationType", "LEFT_JOIN")).toUpperCase();
        validateFederalRelation(datasourceId, leftTable, leftField, rightSourceType, rightTable, rightField, relationType);
        return Map.of("valid", true, "message", "联邦关联校验通过", "datasourceId", datasourceId,
                "leftTable", leftTable, "leftField", leftField, "rightSourceType", rightSourceType,
                "rightTable", rightTable, "rightField", rightField, "relationType", relationType);
    }

    public void deleteFederalRelation(Long relationId) {
        jdbcTemplate.update("DELETE FROM is_federal_relation WHERE id = ?", relationId);
    }

    public List<Map<String, Object>> executeFederalJoin(String uploadTableName, int limit) {
        List<Map<String, Object>> relations = jdbcTemplate.queryForList("""
                SELECT datasource_id AS datasourceId, left_table AS leftTable, left_field AS leftField,
                       right_table AS rightTable, right_field AS rightField
                FROM is_federal_relation
                WHERE right_table = ? OR left_table = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, uploadTableName, uploadTableName);
        if (relations.isEmpty()) {
            throw new IllegalArgumentException("未找到当前上传表的联邦关联配置");
        }
        Map<String, Object> relation = relations.get(0);
        Long datasourceId = ((Number) relation.get("datasourceId")).longValue();
        String officialTable = Objects.toString(relation.get("leftTable"));
        String officialField = Objects.toString(relation.get("leftField"));
        String uploadField = resolveUploadColumn(uploadTableName, Objects.toString(relation.get("rightField")));
        int safeLimit = Math.max(1, Math.min(limit, 200));

        List<Map<String, Object>> uploadRows = jdbcTemplate.queryForList(
                "SELECT * FROM " + quoteLocalIdentifier(uploadTableName) + " LIMIT " + safeLimit
        );
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> row : uploadRows) {
            String key = Objects.toString(row.get(uploadField), "").trim();
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return uploadRows;
        }

        String inValues = keys.stream().map(this::sqlString).reduce((a, b) -> a + "," + b).orElse("''");
        String officialSql = "SELECT * FROM " + quoteIdentifier(datasourceId, officialTable)
                + " WHERE " + quoteIdentifier(datasourceId, officialField) + " IN (" + inValues + ") LIMIT " + safeLimit;
        List<Map<String, Object>> officialRows = executeQuery("official:" + datasourceId + ":" + officialTable, officialSql);
        Map<String, Map<String, Object>> officialByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : officialRows) {
            officialByKey.put(Objects.toString(row.get(officialField), ""), row);
        }

        List<Map<String, Object>> joined = new ArrayList<>();
        for (Map<String, Object> uploadRow : uploadRows) {
            Map<String, Object> row = new LinkedHashMap<>(uploadRow);
            Map<String, Object> officialRow = officialByKey.get(Objects.toString(uploadRow.get(uploadField), ""));
            if (officialRow != null) {
                for (Map.Entry<String, Object> entry : officialRow.entrySet()) {
                    row.put("official_" + entry.getKey(), entry.getValue());
                }
            }
            joined.add(row);
        }
        return joined;
    }

    public List<Map<String, Object>> listEnabledQueryTables() {
        List<String> roles = effectiveRolesForCurrentUser();
        String rolePlaceholders = placeholders(roles.size());
        List<Object> args = new ArrayList<>();
        args.add(AuthContext.role());
        args.add(AuthContext.userId());
        args.addAll(roles);
        return jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', d.id, ':', t.table_name) AS tableName,
                       CONCAT(d.name, ' / ', COALESCE(NULLIF(t.table_comment, ''), t.table_name)) AS displayName,
                       d.name AS sourceName, d.id AS datasourceId, t.table_name AS physicalTableName,
                       'OFFICIAL' AS sourceType, COALESCE(t.table_rows, 0) AS rowCount,
                       (SELECT COUNT(*) FROM is_official_schema_field f
                        WHERE f.datasource_id = d.id AND f.table_name = t.table_name) AS fieldCount,
                       t.created_at AS createdAt
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                WHERE d.status = 'ENABLED'
                  AND (? = 'ADMIN' OR EXISTS (
                    SELECT 1 FROM is_official_table_permission p
                    WHERE p.datasource_id = d.id AND (p.table_name = t.table_name OR p.table_name = '*')
                      AND p.permission_type = 'READ'
                      AND (p.expire_at IS NULL OR p.expire_at > NOW())
                      AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id IN (""" + rolePlaceholders + """
                      )))
                  ))
                ORDER BY d.created_at DESC, t.table_name ASC
                """, args.toArray());
    }

    public List<Map<String, Object>> listQueryFields(String sourceKey) {
        assertCanAccessOfficialSource(sourceKey);
        OfficialSource source = parseSourceKey(sourceKey);
        return jdbcTemplate.queryForList("""
                SELECT column_name AS sourceFieldName, column_name AS columnName,
                       CASE
                         WHEN data_type IN ('int','bigint','decimal','double','float','tinyint','smallint','mediumint') THEN 'NUMBER'
                         WHEN data_type IN ('date','datetime','timestamp','time','year') THEN 'DATE'
                         ELSE 'TEXT'
                       END AS fieldType,
                       COALESCE(NULLIF(business_name, ''), NULLIF(column_comment, ''), column_name) AS displayName,
                       column_comment AS fieldComment, business_desc AS businessDesc, synonyms,
                       kg_sync_enabled AS kgSyncEnabled, kg_sync_rule AS kgSyncRule,
                       `sensitive`, ordinal_position AS sortOrder
                FROM is_official_schema_field
                WHERE datasource_id = ? AND table_name = ?
                ORDER BY ordinal_position ASC
                """, source.datasourceId(), source.tableName());
    }

    public List<Map<String, Object>> previewQueryTable(String sourceKey, int limit) {
        return previewQueryTable(sourceKey, 1, limit);
    }

    public List<Map<String, Object>> previewQueryTable(String sourceKey, int page, int pageSize) {
        OfficialSource source = parseSourceKey(sourceKey);
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safeLimit;
        String table = quoteIdentifier(source.datasourceId(), source.tableName());
        return executeQuery(sourceKey, "SELECT * FROM " + table + " LIMIT " + safeLimit + " OFFSET " + offset);
    }

    public long countQueryTable(String sourceKey) {
        OfficialSource source = parseSourceKey(sourceKey);
        String table = quoteIdentifier(source.datasourceId(), source.tableName());
        List<Map<String, Object>> rows = executeQuery(sourceKey, "SELECT COUNT(*) AS total FROM " + table);
        if (rows.isEmpty() || rows.get(0).get("total") == null) {
            return 0;
        }
        return Long.parseLong(String.valueOf(rows.get(0).get("total")));
    }

    public List<Map<String, Object>> executeQuery(String sourceKey, String sql) {
        return executeQueryInternal(sourceKey, sql, true);
    }

    public List<Map<String, Object>> executeQueryWithoutAudit(String sourceKey, String sql) {
        return executeQueryInternal(sourceKey, sql, false);
    }

    public List<Map<String, Object>> listRowPolicies(Long datasourceId) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, table_name AS tableName,
                       principal_type AS principalType, principal_id AS principalId,
                       filter_expression AS filterExpression, enabled, created_at AS createdAt
                FROM is_official_row_policy
                WHERE datasource_id = ?
                ORDER BY created_at DESC
                """, datasourceId);
    }

    public Map<String, Object> saveRowPolicy(Long datasourceId, Map<String, Object> request) {
        String tableName = Objects.toString(request.getOrDefault("tableName", "*")).trim();
        if (tableName.isBlank()) {
            tableName = "*";
        }
        if (!"*".equals(tableName)) {
            assertOfficialTableExists(datasourceId, tableName);
        }
        String principalType = normalizePrincipalType(request.get("principalType"));
        String principalId = requiredString(request, "principalId");
        String filterExpression = sanitizeRowPolicy(requiredString(request, "filterExpression"));
        boolean enabled = parseBooleanFlag(request.getOrDefault("enabled", true));
        jdbcTemplate.update("""
                INSERT INTO is_official_row_policy(datasource_id, table_name, principal_type, principal_id, filter_expression, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, datasourceId, tableName, principalType, principalId, filterExpression, enabled);
        return Map.of("datasourceId", datasourceId, "tableName", tableName, "principalType", principalType,
                "principalId", principalId, "filterExpression", filterExpression, "enabled", enabled);
    }

    public void deleteRowPolicy(Long policyId) {
        jdbcTemplate.update("DELETE FROM is_official_row_policy WHERE id = ?", policyId);
    }

    public Map<String, Object> getNeo4jConfig() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT uri, username, database_name AS databaseName, sync_rule AS syncRule,
                       enabled, updated_at AS updatedAt
                FROM is_neo4j_runtime_config
                WHERE id = 1
                """);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> saveNeo4jConfig(Map<String, Object> request) {
        String uri = Objects.toString(request.getOrDefault("uri", "http://localhost:7474")).trim();
        String username = Objects.toString(request.getOrDefault("username", "neo4j")).trim();
        String password = Objects.toString(request.getOrDefault("password", "")).trim();
        String databaseName = Objects.toString(request.getOrDefault("databaseName", "neo4j")).trim();
        String syncRule = Objects.toString(request.getOrDefault("syncRule", "")).trim();
        boolean enabled = parseBooleanFlag(request.getOrDefault("enabled", true));
        jdbcTemplate.update("""
                UPDATE is_neo4j_runtime_config
                SET uri = ?, username = ?, password = CASE WHEN ? = '' THEN password ELSE ? END,
                    database_name = ?, sync_rule = ?, enabled = ?
                WHERE id = 1
                """, uri, username, password, password, databaseName, syncRule, enabled);
        return getNeo4jConfig();
    }

    public Map<String, Object> generateFederalSql(Long datasourceId, Map<String, Object> request) {
        String uploadTable = requiredString(request, "uploadTable");
        String question = Objects.toString(request.getOrDefault("question", "联邦跨库分析")).trim();
        List<Map<String, Object>> relations = jdbcTemplate.queryForList("""
                SELECT left_table AS leftTable, left_field AS leftField,
                       right_table AS rightTable, right_field AS rightField, relation_type AS relationType
                FROM is_federal_relation
                WHERE datasource_id = ? AND right_table = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, datasourceId, uploadTable);
        if (relations.isEmpty()) {
            throw new IllegalArgumentException("未找到上传表的联邦关联配置：" + uploadTable);
        }
        Map<String, Object> relation = relations.get(0);
        String officialTable = Objects.toString(relation.get("leftTable"));
        String officialField = Objects.toString(relation.get("leftField"));
        String uploadField = resolveUploadColumn(uploadTable, Objects.toString(relation.get("rightField")));
        String uploadColumn = quoteLocalIdentifier(uploadField);
        String uploadSql = "SELECT " + uploadColumn + " AS join_id, COUNT(*) AS upload_count FROM "
                + quoteLocalIdentifier(uploadTable) + " GROUP BY " + uploadColumn + " LIMIT 200";
        String officialColumn = quoteIdentifier(datasourceId, officialField);
        String officialSql = "SELECT " + officialColumn + " AS join_id, * FROM " + quoteIdentifier(datasourceId, officialTable)
                + " WHERE " + officialColumn + " IN (:joinIds) LIMIT 500";
        String executionPlan = "Agent 将先执行上传表聚合 SQL，提取 join_id 集合，再对官方库执行只读 SELECT，最后在 Java 内存中按 join_id 合并结果。";
        return Map.of(
                "question", question,
                "datasourceId", datasourceId,
                "uploadTable", uploadTable,
                "officialTable", officialTable,
                "joinKey", uploadField + " = " + officialField,
                "uploadSql", uploadSql,
                "officialSql", officialSql,
                "executionPlan", executionPlan,
                "readonly", true
        );
    }

    private List<Map<String, Object>> executeQueryInternal(String sourceKey, String sql, boolean needAudit) {
        OfficialSource source = parseSourceKey(sourceKey);
        assertCanAccessOfficialSource(sourceKey);

        String dialectSql = adaptSqlDialect(source, sql);
        SqlAuditService.AuditResult auditResult = sqlAuditService.inspect(dialectSql, sourceKey);
        if (auditResult.blocked()) {
            if (needAudit) {
                sqlAuditService.record("官方数据源查询", sourceKey, "official-datasource", dialectSql,
                        auditResult, "BLOCKED", 0L, auditResult.riskReason());
            }
            throw new IllegalArgumentException("SQL 安全审计未通过：" + auditResult.riskReason());
        }

        String guardedSql = applyRowPolicies(source, dialectSql);
        String safeSql = sqlAuditService.ensureLimit(guardedSql, MAX_QUERY_ROWS);
        String executionGuard = "queryTimeoutSeconds=" + QUERY_TIMEOUT_SECONDS
                + ";maxRows=" + MAX_QUERY_ROWS
                + ";rowPolicyApplied=" + !Objects.equals(guardedSql, dialectSql);
        long startedAt = System.currentTimeMillis();

        try (SqlAuditService.QueryPermit ignored = sqlAuditService.acquireQueryPermit("official-datasource");
             Connection connection = openConnection(source.datasourceId());
             Statement statement = connection.createStatement();
             ResultSet resultSet = executeWithTimeout(statement, safeSql)) {

            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();

            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
                }
                rows.add(row);
            }

            long durationMs = System.currentTimeMillis() - startedAt;
            if (needAudit) {
                sqlAuditService.record("官方数据源查询", sourceKey, "official-datasource", safeSql,
                        auditResult, "SUCCESS", durationMs, null);
            }

            return rows;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            if (needAudit) {
                sqlAuditService.record("官方数据源查询", sourceKey, "official-datasource", safeSql,
                        auditResult, "FAILED", durationMs, e.getMessage());
            }
            throw new IllegalArgumentException("官方数据源查询失败：" + e.getMessage());
        }
    }

    private String applyRowPolicies(OfficialSource source, String sql) {
        if (AuthContext.isAdmin()) {
            return sql;
        }
        List<String> roles = effectiveRolesForCurrentUser();
        String rolePlaceholders = placeholders(roles.size());
        List<Object> args = new ArrayList<>();
        args.add(source.datasourceId());
        args.add(source.tableName());
        args.add(AuthContext.userId());
        args.addAll(roles);
        List<String> filters = jdbcTemplate.queryForList("""
                SELECT filter_expression
                FROM is_official_row_policy
                WHERE datasource_id = ? AND (table_name = ? OR table_name = '*') AND enabled = 1
                  AND ((principal_type = 'USER' AND principal_id = ?)
                    OR (principal_type = 'ROLE' AND principal_id IN (""" + rolePlaceholders + """
                    )))
                ORDER BY created_at ASC
                """, String.class, args.toArray());
        if (filters.isEmpty()) {
            return sql;
        }
        String combined = filters.stream()
                .map(this::sanitizeRowPolicy)
                .filter(item -> !item.isBlank())
                .map(item -> "(" + item + ")")
                .reduce((a, b) -> a + " AND " + b)
                .orElse("");
        if (combined.isBlank()) {
            return sql;
        }
        return appendWhereClause(sql, combined);
    }

    private String appendWhereClause(String sql, String filter) {
        String trimmed = sql.trim().replaceAll(";+$", "");
        String lower = trimmed.toLowerCase();
        int insertAt = findClauseIndex(lower, " group by ");
        if (insertAt < 0) insertAt = findClauseIndex(lower, " order by ");
        if (insertAt < 0) insertAt = findClauseIndex(lower, " limit ");
        String head = insertAt < 0 ? trimmed : trimmed.substring(0, insertAt);
        String tail = insertAt < 0 ? "" : trimmed.substring(insertAt);
        String headLower = head.toLowerCase();
        String connector = headLower.contains(" where ") ? " AND " : " WHERE ";
        return head + connector + filter + tail;
    }

    private int findClauseIndex(String lowerSql, String clause) {
        return lowerSql.indexOf(clause);
    }


    public List<Map<String, Object>> federatedAggregateJoin(String uploadTableName, String question) {
        List<Map<String, Object>> relations = jdbcTemplate.queryForList("""
                SELECT datasource_id AS datasourceId, left_table AS leftTable, left_field AS leftField,
                       right_table AS rightTable, right_field AS rightField
                FROM is_federal_relation
                WHERE right_source_type = 'UPLOAD' AND right_table = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, uploadTableName);
        if (relations.isEmpty()) {
            throw new IllegalArgumentException("未找到该上传表的联邦关联配置：" + uploadTableName);
        }
        Map<String, Object> relation = relations.get(0);
        long datasourceId = Long.parseLong(String.valueOf(relation.get("datasourceId")));
        String officialTable = String.valueOf(relation.get("leftTable"));
        String officialField = String.valueOf(relation.get("leftField"));
        String uploadField = resolveUploadColumn(uploadTableName, String.valueOf(relation.get("rightField")));

        String uploadColumn = quoteLocalIdentifier(uploadField);
        String aggSql = "SELECT " + uploadColumn + " AS join_id, SUM(CAST(NULLIF("
                + quoteLocalIdentifier("amount") + ", '') AS DECIMAL(18,2))) AS total_amount "
                + "FROM " + quoteLocalIdentifier(uploadTableName) + " GROUP BY " + uploadColumn + " LIMIT 200";
        List<Map<String, Object>> uploadRows = jdbcTemplate.queryForList(aggSql);
        if (uploadRows.isEmpty()) {
            return List.of();
        }
        List<String> joinIds = uploadRows.stream()
                .map(row -> Objects.toString(row.get("join_id"), "").trim())
                .filter(v -> !v.isBlank())
                .distinct()
                .limit(200)
                .toList();
        if (joinIds.isEmpty()) {
            return List.of();
        }

        String sourceKey = "official:" + datasourceId + ":" + officialTable;
        String inClause = joinIds.stream().map(id -> "'" + escapeSql(id) + "'").reduce((a,b)->a+","+b).orElse("''");
        String officialColumn = quoteIdentifier(datasourceId, officialField);
        List<Map<String, Object>> officialRows = executeQuery(sourceKey,
                "SELECT " + officialColumn + " AS join_id, * FROM " + quoteIdentifier(datasourceId, officialTable)
                        + " WHERE " + officialColumn + " IN (" + inClause + ") LIMIT 500");

        Map<String, Map<String, Object>> officialIndex = new LinkedHashMap<>();
        for (Map<String, Object> row : officialRows) {
            officialIndex.put(Objects.toString(row.get("join_id"), ""), row);
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> row : uploadRows) {
            String id = Objects.toString(row.get("join_id"), "");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("join_id", id);
            out.put("total_amount", row.get("total_amount"));
            Map<String, Object> official = officialIndex.get(id);
            if (official != null) {
                for (Map.Entry<String, Object> entry : official.entrySet()) {
                    if (!"join_id".equals(entry.getKey())) {
                        out.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            merged.add(out);
        }
        return merged;
    }

    public String physicalTableName(String sourceKey) {
        return parseSourceKey(sourceKey).tableName();
    }

    public boolean isOfficialSource(String sourceKey) {
        return sourceKey != null && sourceKey.startsWith("official:");
    }

    public Map<String, Object> health(Long datasourceId) {
        return poolManager.health(datasourceId);
    }

    private Map<String, Object> latestDatasource() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, name, db_type AS dbType, host, port, database_name AS databaseName,
                       username, jdbc_url AS jdbcUrl, status, created_at AS createdAt
                FROM is_official_datasource
                ORDER BY created_at DESC
                LIMIT 1
                """);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> findDatasourcePublic(Long datasourceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, name, db_type AS dbType, host, port, database_name AS databaseName,
                       username, jdbc_url AS jdbcUrl, status, pool_max_size AS poolMaxSize,
                       pool_timeout_ms AS poolTimeoutMs, readonly_enforced AS readonlyEnforced,
                       created_at AS createdAt
                FROM is_official_datasource
                WHERE id = ?
                """, datasourceId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> findDatasource(Long datasourceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
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

    private Connection openConnection(Long datasourceId) throws Exception {
        Connection connection = poolManager.getConnection(datasourceId);
        connection.setReadOnly(true);
        return connection;
    }

    private String buildJdbcUrl(String dbType, String host, int port, String databaseName) {
        if ("MYSQL".equalsIgnoreCase(dbType)) {
            return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                    + "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai";
        }
        if ("POSTGRESQL".equalsIgnoreCase(dbType) || "POSTGRES".equalsIgnoreCase(dbType)) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + databaseName;
        }
        throw new IllegalArgumentException("不支持的数据源类型：" + dbType);
    }


    private String buildTableMetaSql(String dbType, String databaseName) {
        if ("POSTGRESQL".equalsIgnoreCase(dbType) || "POSTGRES".equalsIgnoreCase(dbType)) {
            return "SELECT t.table_name, COALESCE(obj_description((quote_ident(t.table_schema) || '.' || quote_ident(t.table_name))::regclass), '') AS table_comment,\n" +
                   "       0 AS table_rows\n" +
                   "FROM information_schema.tables t\n" +
                   "WHERE t.table_catalog = '" + escapeSql(databaseName) + "' AND t.table_schema = 'public' AND t.table_type = 'BASE TABLE'\n" +
                   "ORDER BY t.table_name";
        }
        return "SELECT table_name, table_comment, table_rows\n" +
               "FROM information_schema.tables\n" +
               "WHERE table_schema = '" + escapeSql(databaseName) + "' AND table_type = 'BASE TABLE' ORDER BY table_name";
    }

    private String buildColumnMetaSql(String dbType, String databaseName) {
        if ("POSTGRESQL".equalsIgnoreCase(dbType) || "POSTGRES".equalsIgnoreCase(dbType)) {
            return "SELECT c.table_name, c.column_name, c.data_type,\n" +
                   "       COALESCE(col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position), '') AS column_comment,\n" +
                   "       c.is_nullable,\n" +
                   "       CASE WHEN tc.constraint_type = 'PRIMARY KEY' THEN 'PRI' ELSE '' END AS column_key,\n" +
                   "       c.ordinal_position\n" +
                   "FROM information_schema.columns c\n" +
                   "LEFT JOIN information_schema.key_column_usage kcu\n" +
                   "  ON c.table_catalog = kcu.table_catalog AND c.table_schema = kcu.table_schema\n" +
                   " AND c.table_name = kcu.table_name AND c.column_name = kcu.column_name\n" +
                   "LEFT JOIN information_schema.table_constraints tc\n" +
                   "  ON kcu.constraint_catalog = tc.constraint_catalog\n" +
                   " AND kcu.constraint_schema = tc.constraint_schema\n" +
                   " AND kcu.constraint_name = tc.constraint_name\n" +
                   "WHERE c.table_catalog = '" + escapeSql(databaseName) + "' AND c.table_schema = 'public'\n" +
                   "ORDER BY c.table_name, c.ordinal_position";
        }
        return "SELECT table_name, column_name, data_type, column_comment, is_nullable, column_key, ordinal_position\n" +
               "FROM information_schema.columns\n" +
               "WHERE table_schema = '" + escapeSql(databaseName) + "' ORDER BY table_name, ordinal_position";
    }

    private String buildForeignKeyMetaSql(String dbType, String databaseName) {
        if ("POSTGRESQL".equalsIgnoreCase(dbType) || "POSTGRES".equalsIgnoreCase(dbType)) {
            return "SELECT tc.table_name, kcu.column_name, ccu.table_name AS referenced_table_name,\n" +
                   "       ccu.column_name AS referenced_column_name, tc.constraint_name\n" +
                   "FROM information_schema.table_constraints tc\n" +
                   "JOIN information_schema.key_column_usage kcu\n" +
                   "  ON tc.constraint_catalog = kcu.constraint_catalog AND tc.constraint_schema = kcu.constraint_schema\n" +
                   " AND tc.constraint_name = kcu.constraint_name\n" +
                   "JOIN information_schema.constraint_column_usage ccu\n" +
                   "  ON ccu.constraint_catalog = tc.constraint_catalog AND ccu.constraint_schema = tc.constraint_schema\n" +
                   " AND ccu.constraint_name = tc.constraint_name\n" +
                   "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_catalog = '" + escapeSql(databaseName) + "'\n" +
                   "  AND tc.table_schema = 'public'\n" +
                   "ORDER BY tc.table_name, kcu.ordinal_position";
        }
        return "SELECT table_name, column_name, referenced_table_name, referenced_column_name, constraint_name\n" +
               "FROM information_schema.key_column_usage\n" +
               "WHERE table_schema = '" + escapeSql(databaseName) + "' AND referenced_table_name IS NOT NULL\n" +
               "ORDER BY table_name, ordinal_position";
    }

    private String normalizeDbType(String dbType) {
        String normalized = dbType == null ? "MYSQL" : dbType.trim().toUpperCase();
        if ("POSTGRES".equals(normalized)) {
            normalized = "POSTGRESQL";
        }
        if (!"MYSQL".equals(normalized) && !"POSTGRESQL".equals(normalized)) {
            throw new IllegalArgumentException("数据库类型仅支持 MYSQL / POSTGRESQL");
        }
        return normalized;
    }

    private ResultSet executeWithTimeout(Statement statement, String sql) throws Exception {
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        statement.setMaxRows(MAX_QUERY_ROWS);
        return statement.executeQuery(sql);
    }

    private String requiredString(Map<String, Object> request, String key) {
        String value = Objects.toString(request.get(key), "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return value;
    }

    private String sanitizeRowPolicy(String expression) {
        String text = Objects.toString(expression, "").trim();
        String lower = text.toLowerCase();
        if (text.isBlank()) {
            return "";
        }
        if (!SAFE_ROW_POLICY.matcher(text).matches()
                || lower.contains(";")
                || lower.contains("--")
                || lower.contains("/*")
                || lower.matches(".*\\b(drop|delete|update|insert|alter|truncate|create|grant|revoke|execute|select|from|join|union|where|having|group|order|limit)\\b.*")) {
            throw new IllegalArgumentException("行级隔离表达式仅支持安全的只读 WHERE 条件");
        }
        return text;
    }

    private String textOr(Object value, Object fallback) {
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? Objects.toString(fallback, "") : text;
    }

    private int parseInt(Object value, int defaultValue) {
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? defaultValue : Integer.parseInt(text);
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
        if (tableCount == null || tableCount == 0) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + definition);
        }
    }

    private int isSensitiveColumn(String columnName) {
        String lower = columnName.toLowerCase();
        return lower.contains("phone") || lower.contains("mobile") || lower.contains("idcard")
                || columnName.contains("手机") || columnName.contains("身份证") ? 1 : 0;
    }

    private String escapeSql(String value) {
        return value.replace("'", "''");
    }

    private String sqlString(String value) {
        return "'" + escapeSql(value) + "'";
    }

    private void assertOfficialTableExists(Long datasourceId, String tableName) {
        validateIdentifier("官方表", tableName);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_official_schema_table
                WHERE datasource_id = ? AND table_name = ?
                """, Integer.class, datasourceId, tableName);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("官方表不存在或尚未解析 Schema：" + tableName);
        }
    }

    private String normalizePrincipalType(Object value) {
        String normalized = Objects.toString(value, "USER").trim().toUpperCase();
        if (normalized.isBlank()) {
            normalized = "USER";
        }
        if (!List.of("USER", "ROLE").contains(normalized)) {
            throw new IllegalArgumentException("授权对象类型只能是 USER 或 ROLE");
        }
        return normalized;
    }

    private String normalizeOfficialPermissionType(Object value) {
        String normalized = Objects.toString(value, "READ").trim().toUpperCase();
        if (normalized.isBlank() || List.of("READ", "VIEW", "SELECT", "EDIT").contains(normalized)) {
            return "READ";
        }
        throw new IllegalArgumentException("官方数据源仅支持只读 READ 权限");
    }

    private void validateFederalRelation(Long datasourceId,
                                         String leftTable,
                                         String leftField,
                                         String rightSourceType,
                                         String rightTable,
                                         String rightField,
                                         String relationType) {
        validateIdentifier("官方表", leftTable);
        validateIdentifier("官方字段", leftField);
        validateIdentifier("右侧表", rightTable);
        validateDisplayValue("右侧字段", rightField);

        if (!List.of("LEFT_JOIN", "INNER_JOIN").contains(relationType)) {
            throw new IllegalArgumentException("联邦关联类型仅支持 LEFT_JOIN 或 INNER_JOIN");
        }
        if (!"UPLOAD".equals(rightSourceType)) {
            throw new IllegalArgumentException("右侧来源目前仅支持 UPLOAD");
        }

        assertOfficialTableExists(datasourceId, leftTable);
        Integer officialFieldCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_official_schema_field
                WHERE datasource_id = ? AND table_name = ? AND column_name = ?
                """, Integer.class, datasourceId, leftTable, leftField);
        if (officialFieldCount == null || officialFieldCount == 0) {
            throw new IllegalArgumentException("官方表关联字段不存在：" + leftTable + "." + leftField);
        }

        Integer uploadTableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_data_table
                WHERE table_name = ? AND status IN ('ACTIVE', 'PENDING_CLEANING')
                """, Integer.class, rightTable);
        if (uploadTableCount == null || uploadTableCount == 0) {
            throw new IllegalArgumentException("上传表不存在或不可用：" + rightTable);
        }
        String resolvedUploadColumn = resolveUploadColumn(rightTable, rightField);
        validateIdentifier("上传字段", resolvedUploadColumn);
    }

    private List<String> effectiveRolesForCurrentUser() {
        return effectiveRolesFor(assignedRolesFor(AuthContext.userId(), AuthContext.role()));
    }

    private List<String> assignedRolesFor(String userId, String fallbackRole) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (tableExists("is_user_role")) {
            jdbcTemplate.queryForList("""
                    SELECT role_code AS roleCode
                    FROM is_user_role
                    WHERE user_id = ?
                    ORDER BY created_at ASC
                    """, userId).forEach(row -> {
                String role = Objects.toString(row.get("roleCode"), "").trim();
                if (!role.isBlank()) {
                    roles.add(role);
                }
            });
        }
        String fallback = Objects.toString(fallbackRole, "").trim();
        if (!fallback.isBlank()) {
            roles.add(fallback);
        }
        if (roles.isEmpty()) {
            roles.add("USER");
        }
        return new ArrayList<>(roles);
    }

    private List<String> effectiveRolesFor(List<String> assignedRoles) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String role : assignedRoles) {
            collectRoleWithParents(role, resolved, new LinkedHashSet<>());
        }
        if (resolved.isEmpty()) {
            resolved.add("USER");
        }
        return new ArrayList<>(resolved);
    }

    private void collectRoleWithParents(String roleCode, Set<String> resolved, Set<String> visiting) {
        String role = Objects.toString(roleCode, "").trim();
        if (role.isBlank() || resolved.contains(role) || visiting.contains(role)) {
            return;
        }
        visiting.add(role);
        resolved.add(role);
        if (tableExists("is_role")) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT parent_role_code AS parentRoleCode
                    FROM is_role
                    WHERE role_code = ? AND enabled = 1
                    LIMIT 1
                    """, role);
            if (!rows.isEmpty()) {
                collectRoleWithParents(Objects.toString(rows.get(0).get("parentRoleCode"), ""), resolved, visiting);
            }
        }
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = ?
                    """, Integer.class, tableName);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(1, count), "?"));
    }

    private String quoteIdentifier(Long datasourceId, String identifier) {
        Map<String, Object> datasource = findDatasource(datasourceId);
        String dbType = Objects.toString(datasource.get("db_type"), "MYSQL");
        return quoteIdentifierForDb(dbType, identifier);
    }

    private String quoteLocalIdentifier(String identifier) {
        return quoteIdentifierForDb("MYSQL", identifier);
    }

    private String quoteIdentifierForDb(String dbType, String identifier) {
        validateIdentifier("标识符", identifier);
        boolean postgres = "POSTGRESQL".equalsIgnoreCase(dbType) || "POSTGRES".equalsIgnoreCase(dbType);
        String quote = postgres ? "\"" : "`";
        String escapedQuote = postgres ? "\"\"" : "``";
        String[] parts = identifier.split("\\.");
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank() || !SAFE_IDENTIFIER.matcher(part).matches()) {
                throw new IllegalArgumentException("不安全的数据库标识符：" + identifier);
            }
            quoted.add(quote + part.replace(quote, escapedQuote) + quote);
        }
        return String.join(".", quoted);
    }

    private String adaptSqlDialect(OfficialSource source, String sql) {
        String text = Objects.toString(sql, "").trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        Map<String, Object> datasource = findDatasource(source.datasourceId());
        String dbType = Objects.toString(datasource.get("db_type"), "MYSQL");
        if ("POSTGRESQL".equalsIgnoreCase(dbType) || "POSTGRES".equalsIgnoreCase(dbType)) {
            return text.replace('`', '"');
        }
        return text;
    }

    private void validateIdentifier(String label, String value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank() || !SAFE_IDENTIFIER.matcher(text).matches()
                || text.contains("..")
                || text.contains("*")) {
            throw new IllegalArgumentException(label + "包含不安全字符：" + value);
        }
    }

    private void validateDisplayValue(String label, String value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()
                || text.contains(";")
                || text.contains("--")
                || text.contains("/*")
                || text.contains("*/")) {
            throw new IllegalArgumentException(label + "包含不安全字符：" + value);
        }
    }

    private String resolveUploadColumn(String tableName, String fieldName) {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM is_data_field
                WHERE table_name = ?
                  AND (column_name = ? OR source_field_name = ? OR display_name = ?)
                LIMIT 1
                """, String.class, tableName, fieldName, fieldName, fieldName);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("上传表关联字段不存在：" + fieldName);
        }
        return columns.get(0);
    }

    private boolean hasOfficialTablePermission(OfficialSource source, String permissionType) {
        List<String> roles = effectiveRolesForCurrentUser();
        String rolePlaceholders = placeholders(roles.size());
        List<Object> args = new ArrayList<>();
        args.add(source.datasourceId());
        args.add(source.tableName());
        args.add(permissionType);
        args.add(AuthContext.userId());
        args.addAll(roles);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_official_table_permission
                WHERE datasource_id = ? AND (table_name = ? OR table_name = '*') AND permission_type = ?
                  AND (expire_at IS NULL OR expire_at > NOW())
                  AND ((principal_type = 'USER' AND principal_id = ?)
                    OR (principal_type = 'ROLE' AND principal_id IN (""" + rolePlaceholders + """
                    )))
                """, Integer.class, args.toArray());
        if (tableCount != null && tableCount > 0) {
            return true;
        }
        List<Object> dsArgs = new ArrayList<>();
        dsArgs.add(source.datasourceId());
        dsArgs.add(permissionType);
        dsArgs.add(AuthContext.userId());
        dsArgs.addAll(roles);
        Integer datasourceCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_official_datasource_permission
                WHERE datasource_id = ? AND permission_type = ?
                  AND (expire_at IS NULL OR expire_at > NOW())
                  AND ((principal_type = 'USER' AND principal_id = ?)
                    OR (principal_type = 'ROLE' AND principal_id IN (""" + rolePlaceholders + """
                    )))
                """, Integer.class, dsArgs.toArray());
        return datasourceCount != null && datasourceCount > 0;
    }

    private OfficialSource parseSourceKey(String sourceKey) {
        if (sourceKey == null || !sourceKey.startsWith("official:")) {
            throw new IllegalArgumentException("无效的官方数据源标识：" + sourceKey);
        }
        String[] parts = sourceKey.split(":", 3);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("无效的官方数据源标识：" + sourceKey);
        }
        return new OfficialSource(Long.parseLong(parts[1]), parts[2]);
    }

    private record OfficialSource(Long datasourceId, String tableName) {
    }
}
