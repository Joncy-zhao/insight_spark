package com.insightspark.core.migration;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class SqlMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlMigrationRunner.class);

    private static final List<String> MIGRATIONS = List.of(
            "db/migration/chat_conversation_migration_20260524.sql",
            "db/migration/dashboard_component_artifact_turn_migration_20260524.sql",
            "db/migration/admin_chat_history_migration_20260527.sql",
            "db/migration/admin_chat_query_lab_migration_20260527.sql",
            "db/migration/admin_chat_query_template_migration_20260528.sql"
    );

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public SqlMigrationRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void runMigrations() {
        ensureMigrationTable();
        for (String scriptPath : MIGRATIONS) {
            if (isApplied(scriptPath)) {
                continue;
            }
            executeMigration(scriptPath);
        }
    }

    private void ensureMigrationTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_schema_migration` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  `script_name` VARCHAR(255) NOT NULL,
                  `applied_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_schema_migration_script` (`script_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='启动期 SQL migration 执行记录';
                """);
    }

    private boolean isApplied(String scriptPath) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_schema_migration WHERE script_name = ?
                """, Integer.class, scriptPath);
        return count != null && count > 0;
    }

    private void executeMigration(String scriptPath) {
        Resource resource = new ClassPathResource(scriptPath);
        if (!resource.exists()) {
            throw new IllegalStateException("Migration script not found: " + scriptPath);
        }
        validateSafeMigration(resource, scriptPath);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, resource);
            jdbcTemplate.update("""
                    INSERT INTO is_schema_migration(script_name, applied_at)
                    VALUES (?, ?)
                    """, scriptPath, Timestamp.from(Instant.now()));
            log.info("Applied SQL migration: {}", scriptPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply SQL migration: " + scriptPath, e);
        }
    }

    private void validateSafeMigration(Resource resource, String scriptPath) {
        try {
            String script = readSql(resource);
            for (String statement : splitStatements(script)) {
                validateStatement(statement, scriptPath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read migration script: " + scriptPath, e);
        }
    }

    private String readSql(Resource resource) throws IOException {
        try (Reader reader = new EncodedResource(resource, "UTF-8").getReader();
             StringWriter writer = new StringWriter()) {
            reader.transferTo(writer);
            return writer.toString();
        }
    }

    private List<String> splitStatements(String script) {
        String normalized = script == null ? "" : script.replace("\r\n", "\n");
        String[] rawParts = normalized.split(";");
        List<String> statements = new ArrayList<>();
        for (String raw : rawParts) {
            String cleaned = stripComments(raw).trim();
            if (!cleaned.isBlank()) {
                statements.add(cleaned);
            }
        }
        return statements;
    }

    private String stripComments(String sql) {
        StringBuilder builder = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
                continue;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private void validateStatement(String statement, String scriptPath) {
        String normalized = statement.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return;
        }
        blockDangerousStatement(normalized, scriptPath, statement);
        if (normalized.startsWith("USE ")) {
            return;
        }
        if (normalized.startsWith("CREATE TABLE IF NOT EXISTS")) {
            return;
        }
        if (normalized.startsWith("CREATE INDEX ") || normalized.startsWith("CREATE UNIQUE INDEX ")) {
            return;
        }
        if (normalized.startsWith("SET @DDL =")) {
            return;
        }
        if (normalized.startsWith("PREPARE ")) {
            return;
        }
        if (normalized.startsWith("EXECUTE ")) {
            return;
        }
        if (normalized.startsWith("DEALLOCATE PREPARE ")) {
            return;
        }
        if (normalized.startsWith("ALTER TABLE `IS_CHAT_QUERY_HISTORY` ADD COLUMN ")) {
            return;
        }
        if (normalized.startsWith("ALTER TABLE `IS_SQL_AUDIT_LOG` ADD COLUMN ")) {
            return;
        }
        if (normalized.startsWith("ALTER TABLE `IS_DASHBOARD_COMPONENT` ADD COLUMN ")) {
            return;
        }
        if (normalized.startsWith("SELECT IF(") || normalized.startsWith("SELECT 1")) {
            return;
        }
        throw new IllegalStateException("Unsafe migration statement blocked in " + scriptPath + ": " + statement);
    }

    private void blockDangerousStatement(String normalized, String scriptPath, String statement) {
        List<String> blockedPrefixes = List.of(
                "DROP ",
                "TRUNCATE ",
                "DELETE ",
                "UPDATE ",
                "INSERT ",
                "REPLACE ",
                "RENAME ",
                "ALTER TABLE `IS_CHAT_QUERY_HISTORY` DROP ",
                "ALTER TABLE `IS_CHAT_QUERY_HISTORY` MODIFY ",
                "ALTER TABLE `IS_CHAT_QUERY_HISTORY` CHANGE ",
                "ALTER TABLE `IS_CHAT_QUERY_HISTORY` RENAME "
        );
        for (String prefix : blockedPrefixes) {
            if (normalized.startsWith(prefix)) {
                throw new IllegalStateException("Dangerous migration statement blocked in " + scriptPath + ": " + statement);
            }
        }
    }
}
