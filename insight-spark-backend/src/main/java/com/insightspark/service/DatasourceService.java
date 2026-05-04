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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DatasourceService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

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
                                                   status, pool_max_size, pool_timeout_ms, readonly_enforced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISABLED', ?, ?, 1)
                """, name, dbType, host, port, databaseName, username, password, jdbcUrl,
                parseInt(request.get("poolMaxSize"), 10), parseInt(request.get("poolTimeoutMs"), 30000));
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
                    pool_max_size = ?, pool_timeout_ms = ?
                WHERE id = ?
                """, name, dbType, host, port, databaseName, username, password, jdbcUrl,
                parseInt(request.get("poolMaxSize"), 10), parseInt(request.get("poolTimeoutMs"), 30000), datasourceId);
        return findDatasourcePublic(datasourceId);
    }

    public void deleteDatasource(Long datasourceId) {
        jdbcTemplate.update("UPDATE is_official_datasource SET status = 'DELETED' WHERE id = ?", datasourceId);
    }

    public void updateStatus(Long datasourceId, String status) {
        String nextStatus = status == null ? "" : status.trim().toUpperCase();
        if (!nextStatus.equals("ENABLED") && !nextStatus.equals("DISABLED")) {
            throw new IllegalArgumentException("状态只能是 ENABLED 或 DISABLED");
        }
        jdbcTemplate.update("UPDATE is_official_datasource SET status = ? WHERE id = ?", nextStatus, datasourceId);
    }

    public List<Map<String, Object>> listDatasources() {
        return jdbcTemplate.queryForList("""
                SELECT id, name, db_type AS dbType, host, port, database_name AS databaseName,
                       username, jdbc_url AS jdbcUrl, status, pool_max_size AS poolMaxSize,
                       pool_timeout_ms AS poolTimeoutMs, readonly_enforced AS readonlyEnforced,
                       last_test_status AS lastTestStatus, last_test_message AS lastTestMessage,
                       last_sync_at AS lastSyncAt, created_at AS createdAt
                FROM is_official_datasource
                WHERE status <> 'DELETED'
                ORDER BY created_at DESC
                """);
    }

    public Map<String, Object> testConnection(Long datasourceId) {
        Map<String, Object> datasource = findDatasource(datasourceId);
        long startedAt = System.currentTimeMillis();
        try (Connection connection = openConnection(datasource);
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
        jdbcTemplate.update("DELETE FROM is_official_schema_field WHERE datasource_id = ?", datasourceId);
        jdbcTemplate.update("DELETE FROM is_official_schema_table WHERE datasource_id = ?", datasourceId);

        try (Connection connection = openConnection(datasource);
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
                                                                 column_comment, is_nullable, column_key, ordinal_position, sensitive)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, datasourceId, columns.getString("table_name"), columnName,
                            columns.getString("data_type"), columns.getString("column_comment"),
                            columns.getString("is_nullable"), columns.getString("column_key"),
                            columns.getInt("ordinal_position"), isSensitiveColumn(columnName));
                    fieldCount++;
                }
            }
            jdbcTemplate.update("UPDATE is_official_datasource SET last_sync_at = NOW() WHERE id = ?", datasourceId);
            if (knowledgeGraphService != null) {
                knowledgeGraphService.syncGraph();
            }
            return Map.of("tableCount", tableCount, "fieldCount", fieldCount);
        } catch (Exception e) {
            throw new IllegalArgumentException("Schema 解析失败：" + e.getMessage());
        }
    }

    public List<Map<String, Object>> listSchemaTables(Long datasourceId) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, table_name AS tableName, table_comment AS tableComment,
                       table_rows AS tableRows, created_at AS createdAt
                FROM is_official_schema_table
                WHERE datasource_id = ?
                ORDER BY table_name ASC
                """, datasourceId);
    }

    public List<Map<String, Object>> listSchemaFields(Long datasourceId, String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, table_name AS tableName, column_name AS columnName,
                       data_type AS dataType, column_comment AS columnComment, is_nullable AS isNullable,
                       column_key AS columnKey, ordinal_position AS ordinalPosition, business_name AS businessName,
                       `sensitive`
                FROM is_official_schema_field
                WHERE datasource_id = ? AND table_name = ?
                ORDER BY ordinal_position ASC
                """, datasourceId, tableName);
    }

    public void updateFieldMeta(Long fieldId, Map<String, Object> request) {
        jdbcTemplate.update("""
                UPDATE is_official_schema_field
                SET business_name = ?, `sensitive` = ?
                WHERE id = ?
                """, Objects.toString(request.getOrDefault("businessName", "")),
                Boolean.parseBoolean(Objects.toString(request.getOrDefault("sensitive", "false"))), fieldId);
    }

    public List<Map<String, Object>> listPermissions(Long datasourceId) {
        return jdbcTemplate.queryForList("""
                SELECT id, datasource_id AS datasourceId, principal_type AS principalType,
                       principal_id AS principalId, permission_type AS permissionType,
                       expire_at AS expireAt, created_at AS createdAt
                FROM is_official_datasource_permission
                WHERE datasource_id = ?
                ORDER BY created_at DESC
                """, datasourceId);
    }

    public Map<String, Object> grantPermission(Long datasourceId, Map<String, Object> request) {
        String principalType = Objects.toString(request.getOrDefault("principalType", "USER")).toUpperCase();
        String principalId = requiredString(request, "principalId");
        String permissionType = Objects.toString(request.getOrDefault("permissionType", "READ")).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO is_official_datasource_permission(datasource_id, principal_type, principal_id, permission_type)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE created_at = CURRENT_TIMESTAMP
                """, datasourceId, principalType, principalId, permissionType);
        return Map.of("datasourceId", datasourceId, "principalType", principalType,
                "principalId", principalId, "permissionType", permissionType);
    }

    public void revokePermission(Long permissionId) {
        jdbcTemplate.update("DELETE FROM is_official_datasource_permission WHERE id = ?", permissionId);
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
        jdbcTemplate.update("""
                INSERT INTO is_federal_relation(datasource_id, left_table, left_field, right_source_type, right_table, right_field, relation_type)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, datasourceId, leftTable, leftField, rightSourceType, rightTable, rightField, relationType);
        return Map.of("datasourceId", datasourceId, "leftTable", leftTable, "leftField", leftField,
                "rightSourceType", rightSourceType, "rightTable", rightTable, "rightField", rightField,
                "relationType", relationType);
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
                "SELECT * FROM `" + uploadTableName + "` LIMIT " + safeLimit
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

        Map<String, Object> datasource = findDatasource(datasourceId);
        String quote = "POSTGRESQL".equalsIgnoreCase(Objects.toString(datasource.get("db_type"))) ? "\"" : "`";
        String inValues = keys.stream().map(this::sqlString).reduce((a, b) -> a + "," + b).orElse("''");
        String officialSql = "SELECT * FROM " + quote + officialTable + quote
                + " WHERE " + quote + officialField + quote + " IN (" + inValues + ") LIMIT " + safeLimit;
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
                    SELECT 1 FROM is_official_datasource_permission p
                    WHERE p.datasource_id = d.id AND p.permission_type = 'READ'
                      AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                  ))
                ORDER BY d.created_at DESC, t.table_name ASC
                """, AuthContext.role(), AuthContext.userId(), AuthContext.role());
    }

    public List<Map<String, Object>> listQueryFields(String sourceKey) {
        OfficialSource source = parseSourceKey(sourceKey);
        return jdbcTemplate.queryForList("""
                SELECT column_name AS sourceFieldName, column_name AS columnName,
                       CASE
                         WHEN data_type IN ('int','bigint','decimal','double','float','tinyint','smallint','mediumint') THEN 'NUMBER'
                         WHEN data_type IN ('date','datetime','timestamp','time','year') THEN 'DATE'
                         ELSE 'TEXT'
                       END AS fieldType,
                       COALESCE(NULLIF(business_name, ''), NULLIF(column_comment, ''), column_name) AS displayName,
                       column_comment AS fieldComment, `sensitive`, ordinal_position AS sortOrder
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
        return executeQuery(sourceKey, "SELECT * FROM `" + source.tableName() + "` LIMIT " + safeLimit + " OFFSET " + offset);
    }

    public long countQueryTable(String sourceKey) {
        List<Map<String, Object>> rows = executeQuery(sourceKey, "SELECT COUNT(*) AS total FROM `" + parseSourceKey(sourceKey).tableName() + "`");
        if (rows.isEmpty() || rows.get(0).get("total") == null) {
            return 0;
        }
        return Long.parseLong(String.valueOf(rows.get(0).get("total")));
    }

    public List<Map<String, Object>> executeQuery(String sourceKey, String sql) {
        OfficialSource source = parseSourceKey(sourceKey);
        Map<String, Object> datasource = findDatasource(source.datasourceId());
        try (Connection connection = openConnection(datasource);
             Statement statement = connection.createStatement();
             ResultSet resultSet = executeWithTimeout(statement, sql)) {
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
            return rows;
        } catch (Exception e) {
            throw new IllegalArgumentException("官方数据源查询失败：" + e.getMessage());
        }
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
        String uploadField = String.valueOf(relation.get("rightField"));

        String aggSql = "SELECT `" + uploadField + "` AS join_id, SUM(CAST(NULLIF(`amount`, '') AS DECIMAL(18,2))) AS total_amount " +
                "FROM `" + uploadTableName + "` GROUP BY `" + uploadField + "` LIMIT 200";
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
        List<Map<String, Object>> officialRows = executeQuery(sourceKey,
                "SELECT `" + officialField + "` AS join_id, * FROM `" + officialTable + "` WHERE `" + officialField + "` IN (" + inClause + ") LIMIT 500");

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

    private Connection openConnection(Map<String, Object> datasource) throws Exception {
        return DriverManager.getConnection(
                String.valueOf(datasource.get("jdbc_url")),
                String.valueOf(datasource.get("username")),
                DatasourcePasswordEncryptor.decrypt(String.valueOf(datasource.get("password")))
        );
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
        statement.setQueryTimeout(5);
        return statement.executeQuery(sql);
    }

    private String requiredString(Map<String, Object> request, String key) {
        String value = Objects.toString(request.get(key), "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return value;
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
