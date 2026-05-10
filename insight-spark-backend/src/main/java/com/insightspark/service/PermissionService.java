package com.insightspark.service;

import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PermissionService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public String currentUserId() {
        return AuthContext.userId();
    }

    public String currentRole() {
        return AuthContext.role();
    }

    @PostConstruct
    public void initPermissionTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_data_permission` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
                  `source` VARCHAR(32) NOT NULL DEFAULT 'GRANT',
                  `expire_at` DATETIME NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_data_permission_user_table_type` (`user_id`, `table_name`, `permission_type`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表访问授权';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_permission_request` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `applicant_id` VARCHAR(64) NOT NULL,
                  `table_name` VARCHAR(128) NOT NULL,
                  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
                  `reason` VARCHAR(1000) NOT NULL,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                  `reviewer_id` VARCHAR(64) NULL,
                  `review_comment` VARCHAR(1000) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `reviewed_at` DATETIME NULL,
                  INDEX `idx_permission_request_status` (`status`),
                  INDEX `idx_permission_request_applicant` (`applicant_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限申请记录';
                """);
    }

    public boolean canAccessTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        if (AuthContext.isAdmin()) {
            return true;
        }
        Integer owned = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_data_table
                WHERE table_name = ? AND owner_id = ? AND status = 'ACTIVE'
                """, Integer.class, tableName, currentUserId());
        if (owned != null && owned > 0) {
            return true;
        }
        Integer granted = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_data_permission
                WHERE table_name = ? AND user_id = ? AND permission_type = 'READ'
                """, Integer.class, tableName, currentUserId());
        return granted != null && granted > 0;
    }

    public void assertCanAccessTable(String tableName) {
        if (!canAccessTable(tableName)) {
            throw new IllegalArgumentException("当前用户无权访问数据表：" + tableName);
        }
    }

    public List<Map<String, Object>> listAccessibleTables() {
        if (AuthContext.isAdmin()) {
            return jdbcTemplate.queryForList("""
                    SELECT id, source_name AS sourceName, display_name AS displayName, table_name AS tableName,
                           owner_id AS ownerId, row_count AS rowCount, field_count AS fieldCount,
                           file_size AS fileSize, status, created_at AS createdAt, 'ADMIN' AS accessSource, 'UPLOAD' AS sourceType
                    FROM is_data_table
                    WHERE status = 'ACTIVE'
                    ORDER BY created_at DESC
                    """);
        }
        return jdbcTemplate.queryForList("""
                SELECT t.id, t.source_name AS sourceName, t.display_name AS displayName, t.table_name AS tableName,
                       t.owner_id AS ownerId, t.row_count AS rowCount, t.field_count AS fieldCount,
                       t.file_size AS fileSize, t.status, t.created_at AS createdAt,
                       CASE WHEN t.owner_id = ? THEN 'OWNER' ELSE 'GRANTED' END AS accessSource,
                       'UPLOAD' AS sourceType
                FROM is_data_table t
                LEFT JOIN is_data_permission p ON p.table_name = t.table_name
                     AND p.user_id = ? AND p.permission_type = 'READ'
                WHERE t.status = 'ACTIVE' AND (t.owner_id = ? OR p.id IS NOT NULL)
                ORDER BY t.created_at DESC
                """, currentUserId(), currentUserId(), currentUserId());
    }

    public List<Map<String, Object>> listRequestableTables() {
        List<Map<String, Object>> rows = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT t.id, t.source_name AS sourceName, t.display_name AS displayName, t.table_name AS tableName,
                       t.owner_id AS ownerId, t.row_count AS rowCount, t.field_count AS fieldCount,
                       'UPLOAD' AS sourceType, t.created_at AS createdAt
                FROM is_data_table t
                WHERE t.status = 'ACTIVE' AND t.owner_id <> ?
                  AND NOT EXISTS (
                    SELECT 1 FROM is_data_permission p
                    WHERE p.table_name = t.table_name AND p.user_id = ? AND p.permission_type = 'READ'
                  )
                ORDER BY t.created_at DESC
                """, currentUserId(), currentUserId()));
        rows.addAll(listRequestableOfficialTables());
        return rows;
    }

    public Map<String, Object> getPermissionOverview() {
        Integer ownedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_data_table WHERE owner_id = ? AND status = 'ACTIVE'",
                Integer.class, currentUserId());
        Integer grantedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_data_permission WHERE user_id = ? AND permission_type = 'READ'",
                Integer.class, currentUserId());
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_permission_request WHERE applicant_id = ? AND status = 'PENDING'",
                Integer.class, currentUserId());
        Integer officialCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_official_datasource_permission
                WHERE principal_type = 'USER' AND principal_id = ? AND permission_type = 'READ'
                """, Integer.class, currentUserId());
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("userId", currentUserId());
        overview.put("role", currentRole());
        overview.put("ownedTableCount", valueOrZero(ownedCount));
        overview.put("grantedTableCount", valueOrZero(grantedCount));
        overview.put("pendingRequestCount", valueOrZero(pendingCount));
        overview.put("officialDatasourceCount", valueOrZero(officialCount));
        overview.put("sensitiveFieldCount", listSensitiveFieldPermissions().size());
        overview.put("dataScope", AuthContext.isAdmin() ? "管理员可查看全部数据" : "本人上传数据 + 已审批授权数据");
        overview.put("sensitiveRule", "敏感字段按字段标记识别，查询展示默认脱敏。");
        return overview;
    }

    public Map<String, Object> submitRequest(String tableName, String reason) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("请选择申请的数据表");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("请填写申请理由");
        }
        boolean official = tableName.startsWith("official:");
        Integer tableCount = official ? officialTableCount(tableName) : jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_data_table WHERE table_name = ? AND status = 'ACTIVE'",
                Integer.class, tableName);
        if (tableCount == null || tableCount == 0) {
            throw new IllegalArgumentException("申请的数据表不存在");
        }
        if (!official && canAccessTable(tableName)) {
            throw new IllegalArgumentException("当前用户已经拥有该数据表访问权限");
        }
        Integer pendingCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_permission_request
                WHERE applicant_id = ? AND table_name = ? AND status = 'PENDING'
                """, Integer.class, currentUserId(), tableName);
        if (pendingCount != null && pendingCount > 0) {
            throw new IllegalArgumentException("该数据表已有待审批申请，请勿重复提交");
        }
        jdbcTemplate.update("""
                INSERT INTO is_permission_request(applicant_id, table_name, permission_type, reason, status)
                VALUES (?, ?, 'READ', ?, 'PENDING')
                """, currentUserId(), tableName, reason);
        return latestRequestForUser();
    }

    public void reviewRequest(Long requestId, String action, String comment) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        if (!normalizedAction.equals("APPROVED") && !normalizedAction.equals("REJECTED")) {
            throw new IllegalArgumentException("审批动作只能是 APPROVED 或 REJECTED");
        }
        List<Map<String, Object>> requests = jdbcTemplate.queryForList("""
                SELECT id, applicant_id AS applicantId, table_name AS tableName, status
                FROM is_permission_request WHERE id = ?
                """, requestId);
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("申请记录不存在");
        }
        Map<String, Object> request = requests.get(0);
        if (!"PENDING".equals(String.valueOf(request.get("status")))) {
            throw new IllegalArgumentException("该申请已审批，请勿重复处理");
        }
        jdbcTemplate.update("""
                UPDATE is_permission_request
                SET status = ?, reviewer_id = ?, review_comment = ?, reviewed_at = NOW()
                WHERE id = ?
                """, normalizedAction, currentUserId(), comment == null ? "" : comment, requestId);
        if (normalizedAction.equals("APPROVED")) {
            String approvedTableName = Objects.toString(request.get("tableName"), "");
            if (approvedTableName.startsWith("official:")) {
                jdbcTemplate.update("""
                        INSERT INTO is_official_datasource_permission(datasource_id, principal_type, principal_id, permission_type)
                        VALUES (?, 'USER', ?, 'READ')
                        ON DUPLICATE KEY UPDATE created_at = CURRENT_TIMESTAMP
                        """, parseOfficialDatasourceId(approvedTableName), request.get("applicantId"));
            } else {
                jdbcTemplate.update("""
                        INSERT INTO is_data_permission(user_id, table_name, permission_type, source)
                        VALUES (?, ?, 'READ', 'REQUEST')
                        ON DUPLICATE KEY UPDATE source = VALUES(source), created_at = CURRENT_TIMESTAMP
                        """, request.get("applicantId"), approvedTableName);
            }
        }
    }

    public List<Map<String, Object>> listAccessibleOfficialTables() {
        return jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', d.id, ':', t.table_name) AS tableName,
                       CONCAT(d.name, ' / ', COALESCE(NULLIF(t.table_comment, ''), t.table_name)) AS displayName,
                       d.name AS datasourceName, d.id AS datasourceId, t.table_name AS physicalTableName,
                       COALESCE(t.table_rows, 0) AS rowCount,
                       (SELECT COUNT(*) FROM is_official_schema_field f WHERE f.datasource_id = d.id AND f.table_name = t.table_name) AS fieldCount,
                       'OFFICIAL' AS sourceType, p.created_at AS grantedAt, p.expire_at AS expireAt
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                JOIN is_official_datasource_permission p ON p.datasource_id = d.id
                WHERE d.status = 'ENABLED' AND p.permission_type = 'READ'
                  AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                ORDER BY d.created_at DESC, t.table_name ASC
                """, currentUserId(), currentRole());
    }

    public List<Map<String, Object>> listRequestableOfficialTables() {
        return jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', d.id, ':', t.table_name) AS tableName,
                       CONCAT(d.name, ' / ', COALESCE(NULLIF(t.table_comment, ''), t.table_name)) AS displayName,
                       d.name AS ownerId, COALESCE(t.table_rows, 0) AS rowCount,
                       (SELECT COUNT(*) FROM is_official_schema_field f WHERE f.datasource_id = d.id AND f.table_name = t.table_name) AS fieldCount,
                       'OFFICIAL' AS sourceType, t.created_at AS createdAt
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                WHERE d.status = 'ENABLED'
                  AND NOT EXISTS (
                    SELECT 1 FROM is_official_datasource_permission p
                    WHERE p.datasource_id = d.id AND p.permission_type = 'READ'
                      AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                  )
                ORDER BY d.created_at DESC, t.table_name ASC
                """, currentUserId(), currentRole());
    }

    public List<Map<String, Object>> listSensitiveFieldPermissions() {
        List<Map<String, Object>> fields = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT t.display_name AS tableDisplayName, f.table_name AS tableName,
                       f.display_name AS displayName, f.column_name AS columnName,
                       'UPLOAD' AS sourceType, 'MASKED' AS accessMode
                FROM is_data_field f
                JOIN is_data_table t ON t.table_name = f.table_name
                LEFT JOIN is_data_permission p ON p.table_name = t.table_name AND p.user_id = ? AND p.permission_type = 'READ'
                WHERE f.sensitive = 1 AND t.status = 'ACTIVE' AND (? = 'ADMIN' OR t.owner_id = ? OR p.id IS NOT NULL)
                ORDER BY t.created_at DESC, f.sort_order ASC
                """, currentUserId(), currentRole(), currentUserId()));
        fields.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT(d.name, ' / ', s.table_name) AS tableDisplayName,
                       CONCAT('official:', d.id, ':', s.table_name) AS tableName,
                       COALESCE(NULLIF(s.business_name, ''), NULLIF(s.column_comment, ''), s.column_name) AS displayName,
                       s.column_name AS columnName, 'OFFICIAL' AS sourceType, 'MASKED' AS accessMode
                FROM is_official_schema_field s
                JOIN is_official_datasource d ON d.id = s.datasource_id
                JOIN is_official_datasource_permission p ON p.datasource_id = d.id
                WHERE s.sensitive = 1 AND d.status = 'ENABLED' AND p.permission_type = 'READ'
                  AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                ORDER BY d.created_at DESC, s.table_name, s.ordinal_position
                """, currentUserId(), currentRole()));
        return fields;
    }

    public List<Map<String, Object>> listMyRequests() {
        return jdbcTemplate.queryForList("""
                SELECT r.id, r.applicant_id AS applicantId, r.table_name AS tableName,
                       COALESCE(t.display_name, r.table_name) AS displayName,
                       r.permission_type AS permissionType, r.reason, r.status, r.reviewer_id AS reviewerId,
                       r.review_comment AS reviewComment, r.created_at AS createdAt, r.reviewed_at AS reviewedAt
                FROM is_permission_request r
                LEFT JOIN is_data_table t ON t.table_name = r.table_name
                WHERE r.applicant_id = ?
                ORDER BY r.created_at DESC
                """, currentUserId());
    }

    public List<Map<String, Object>> listAllRequests(String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.applicant_id AS applicantId, r.table_name AS tableName,
                       COALESCE(t.display_name, r.table_name) AS displayName,
                       t.owner_id AS ownerId, r.permission_type AS permissionType, r.reason, r.status,
                       r.reviewer_id AS reviewerId, r.review_comment AS reviewComment,
                       r.created_at AS createdAt, r.reviewed_at AS reviewedAt
                FROM is_permission_request r
                LEFT JOIN is_data_table t ON t.table_name = r.table_name
                WHERE 1 = 1
                """);
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY r.created_at DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private Map<String, Object> latestRequestForUser() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.id, r.applicant_id AS applicantId, r.table_name AS tableName,
                       COALESCE(t.display_name, r.table_name) AS displayName,
                       r.permission_type AS permissionType, r.reason, r.status, r.created_at AS createdAt
                FROM is_permission_request r
                LEFT JOIN is_data_table t ON t.table_name = r.table_name
                WHERE r.applicant_id = ?
                ORDER BY r.created_at DESC
                LIMIT 1
                """, currentUserId());
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Integer officialTableCount(String sourceKey) {
        String[] parts = sourceKey.split(":", 3);
        if (parts.length != 3) {
            return 0;
        }
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                WHERE d.id = ? AND t.table_name = ? AND d.status = 'ENABLED'
                """, Integer.class, Long.parseLong(parts[1]), parts[2]);
    }

    private Long parseOfficialDatasourceId(String sourceKey) {
        String[] parts = sourceKey.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("无效的官方数据源标识：" + sourceKey);
        }
        return Long.parseLong(parts[1]);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
