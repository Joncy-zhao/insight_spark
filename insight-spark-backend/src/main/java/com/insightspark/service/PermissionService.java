package com.insightspark.service;

import com.insightspark.core.auth.AuthContext;
import com.insightspark.core.auth.RbacConstants;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                  `resource_type` VARCHAR(32) NOT NULL DEFAULT 'TABLE',
                  `resource_name` VARCHAR(255) NULL,
                  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
                  `reason` VARCHAR(1000) NOT NULL,
                  `scope_desc` VARCHAR(1000) NULL,
                  `expire_at` DATETIME NULL,
                  `attachment_name` VARCHAR(255) NULL,
                  `attachment_content_type` VARCHAR(128) NULL,
                  `attachment_size` BIGINT NULL,
                  `attachment_content` LONGTEXT NULL,
                  `attachment_note` VARCHAR(1000) NULL,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                  `reviewer_id` VARCHAR(64) NULL,
                  `review_comment` VARCHAR(1000) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `reviewed_at` DATETIME NULL,
                  INDEX `idx_permission_request_status` (`status`),
                  INDEX `idx_permission_request_applicant` (`applicant_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限申请记录';
                """);
        addColumnIfMissing("is_permission_request", "resource_type", "VARCHAR(32) NOT NULL DEFAULT 'TABLE'");
        addColumnIfMissing("is_permission_request", "resource_name", "VARCHAR(255) NULL");
        addColumnIfMissing("is_permission_request", "scope_desc", "VARCHAR(1000) NULL");
        addColumnIfMissing("is_permission_request", "expire_at", "DATETIME NULL");
        addColumnIfMissing("is_permission_request", "attachment_name", "VARCHAR(255) NULL");
        addColumnIfMissing("is_permission_request", "attachment_content_type", "VARCHAR(128) NULL");
        addColumnIfMissing("is_permission_request", "attachment_size", "BIGINT NULL");
        addColumnIfMissing("is_permission_request", "attachment_content", "LONGTEXT NULL");
        addColumnIfMissing("is_permission_request", "attachment_note", "VARCHAR(1000) NULL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_dashboard_permission` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `dashboard_id` BIGINT NOT NULL,
                  `user_id` VARCHAR(64) NOT NULL,
                  `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ',
                  `source` VARCHAR(32) NOT NULL DEFAULT 'REQUEST',
                  `expire_at` DATETIME NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_dashboard_permission_user_board_type` (`dashboard_id`, `user_id`, `permission_type`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公共看板访问授权';
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
        addColumnIfMissing("is_official_table_permission", "source", "VARCHAR(32) NOT NULL DEFAULT 'ADMIN'");
        initRbacTables();
        initComplianceDocuments();
        migrateApprovedOfficialRequests();
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
                  AND (expire_at IS NULL OR expire_at > NOW())
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
                           file_size AS fileSize, status, created_at AS createdAt, 'ADMIN' AS accessSource, 'UPLOAD' AS sourceType,
                           'VIEW,EDIT' AS permissionScope, NULL AS expireAt
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
                       'UPLOAD' AS sourceType,
                       CASE WHEN t.owner_id = ? THEN 'VIEW,EDIT'
                            WHEN pe.id IS NOT NULL THEN 'VIEW,EDIT'
                            ELSE 'VIEW' END AS permissionScope,
                       p.expire_at AS expireAt
                FROM is_data_table t
                LEFT JOIN is_data_permission p ON p.table_name = t.table_name
                     AND p.user_id = ? AND p.permission_type = 'READ'
                     AND (p.expire_at IS NULL OR p.expire_at > NOW())
                LEFT JOIN is_data_permission pe ON pe.table_name = t.table_name
                     AND pe.user_id = ? AND pe.permission_type = 'EDIT'
                     AND (pe.expire_at IS NULL OR pe.expire_at > NOW())
                WHERE t.status = 'ACTIVE' AND (t.owner_id = ? OR p.id IS NOT NULL)
                ORDER BY t.created_at DESC
                """, currentUserId(), currentUserId(), currentUserId(), currentUserId(), currentUserId());
    }

    public List<Map<String, Object>> listRequestableTables() {
        List<Map<String, Object>> rows = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT t.id, t.source_name AS sourceName, t.display_name AS displayName, t.table_name AS tableName,
                       t.owner_id AS ownerId, t.row_count AS rowCount, t.field_count AS fieldCount,
                       'UPLOAD' AS sourceType, 'TABLE' AS resourceType,
                       CASE WHEN pr.id IS NULL THEN 'READ' ELSE 'EDIT' END AS suggestedPermissionType,
                       t.created_at AS createdAt
                FROM is_data_table t
                LEFT JOIN is_data_permission pr ON pr.table_name = t.table_name
                     AND pr.user_id = ? AND pr.permission_type = 'READ'
                     AND (pr.expire_at IS NULL OR pr.expire_at > NOW())
                WHERE t.status = 'ACTIVE' AND t.owner_id <> ?
                  AND NOT EXISTS (
                    SELECT 1 FROM is_data_permission p
                    WHERE p.table_name = t.table_name AND p.user_id = ? AND p.permission_type = 'EDIT'
                      AND (p.expire_at IS NULL OR p.expire_at > NOW())
                  )
                ORDER BY t.created_at DESC
                """, currentUserId(), currentUserId(), currentUserId()));
        rows.addAll(listRequestableOfficialTables());
        rows.addAll(listRequestablePublicDashboards());
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
                SELECT COUNT(*) FROM is_official_table_permission
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
        Map<String, Object> rbacProfile = getRbacProfile();
        overview.put("rbacProfile", rbacProfile);
        overview.put("effectiveRoles", rbacProfile.getOrDefault("effectiveRoles", List.of(currentRole())));
        overview.put("effectivePermissions", rbacProfile.getOrDefault("effectivePermissions", List.of()));
        overview.put("dataScope", AuthContext.isAdmin() ? "管理员可查看全部数据" : "本人上传数据 + 已审批授权数据");
        overview.put("sensitiveRule", "敏感字段按字段标记识别，查询展示默认脱敏。");
        overview.put("roleLevel", AuthContext.isSuperAdmin() ? "L4 超级管理员" : AuthContext.isAdmin() ? "L3 管理员" : "L1 普通用户");
        overview.put("roleDescription", AuthContext.isSuperAdmin()
                ? "超级管理员拥有管理员端全部菜单与操作权限，不受角色权限勾选限制。"
                : AuthContext.isAdmin()
                ? "管理员继承普通用户全部能力，并拥有数据源、权限审批、审计治理和知识图谱管理权限。"
                : "普通用户可访问本人上传数据、已授权官方库与公共看板，可提交额外权限申请。");
        overview.put("menuPermissions", menuPermissionLabelsFor(currentUserId(), currentRole()));
        overview.put("inheritance", AuthContext.isAdmin()
                ? "ADMIN 继承 USER 基础权限，并叠加治理与审批权限。"
                : "USER 为基础角色，不继承其他角色；后续子角色会继承 USER 的上传、查询和申请能力。");
        overview.put("rowPolicy", "系统按 owner_user_id / owner_id 与授权关系强制过滤数据；未授权用户无法查看其他用户上传的数据行。");
        overview.put("complianceTips", List.of(
                "仅在已授权业务目的内访问和导出数据，禁止绕过审批共享敏感信息。",
                "手机号、身份证、金额等敏感字段默认按规则脱敏展示，导出与截图需遵守最小必要原则。",
                "违规访问、转存或传播数据会触发审计追踪，并可能导致账号冻结与内部合规处理。"
        ));
        return overview;
    }

    public Map<String, Object> getRbacProfile() {
        List<String> assignedRoles = assignedRolesFor(currentUserId(), currentRole());
        List<String> effectiveRoles = effectiveRolesFor(assignedRoles);
        List<Map<String, Object>> roleDetails = effectiveRoles.isEmpty()
                ? List.of()
                : jdbcTemplate.queryForList("""
                        SELECT role_code AS roleCode, role_name AS roleName, parent_role_code AS parentRoleCode,
                               role_level AS roleLevel, data_scope AS dataScope, description, enabled
                        FROM is_role
                        WHERE role_code IN (%s)
                        ORDER BY role_level DESC, role_code ASC
                        """.formatted(placeholders(effectiveRoles.size())), effectiveRoles.toArray());
        List<Map<String, Object>> permissions = effectiveRoles.isEmpty()
                ? List.of()
                : jdbcTemplate.queryForList("""
                        SELECT role_code AS roleCode, permission_code AS permissionCode,
                               permission_name AS permissionName, permission_type AS permissionType,
                               resource_scope AS resourceScope
                        FROM is_role_permission
                        WHERE role_code IN (%s)
                        ORDER BY permission_type ASC, permission_code ASC
                        """.formatted(placeholders(effectiveRoles.size())), effectiveRoles.toArray());
        return Map.of(
                "assignedRoles", assignedRoles,
                "effectiveRoles", effectiveRoles,
                "roleDetails", roleDetails,
                "effectivePermissions", permissions,
                "inheritanceMode", "ROLE_PARENT_CHAIN"
        );
    }

    public List<String> effectivePermissionCodesFor(String userId, String fallbackRole) {
        if (isSuperAdminUser(userId, fallbackRole)) {
            return listAllPermissionCodes();
        }
        List<String> effectiveRoles = effectiveRolesFor(assignedRolesFor(userId, fallbackRole));
        if (effectiveRoles.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT permission_code
                FROM is_role_permission
                WHERE role_code IN (%s)
                ORDER BY permission_code
                """.formatted(placeholders(effectiveRoles.size())), String.class, effectiveRoles.toArray());
    }

    public List<String> menuPermissionLabelsFor(String userId, String fallbackRole) {
        if (isSuperAdminUser(userId, fallbackRole)) {
            return jdbcTemplate.queryForList("""
                    SELECT DISTINCT permission_name
                    FROM is_role_permission
                    WHERE permission_type = 'MENU' AND resource_scope = 'ADMIN'
                    ORDER BY permission_name
                    """, String.class);
        }
        List<String> effectiveRoles = effectiveRolesFor(assignedRolesFor(userId, fallbackRole));
        if (effectiveRoles.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT permission_name
                FROM is_role_permission
                WHERE role_code IN (%s) AND permission_type = 'MENU'
                ORDER BY permission_name
                """.formatted(placeholders(effectiveRoles.size())), String.class, effectiveRoles.toArray());
    }

    public boolean hasPermissionFor(String userId, String fallbackRole, String permissionCode) {
        if (isSuperAdminUser(userId, fallbackRole)) {
            return true;
        }
        String code = Objects.toString(permissionCode, "").trim();
        if (code.isBlank()) {
            return true;
        }
        return effectivePermissionCodesFor(userId, fallbackRole).contains(code);
    }

    public boolean isSuperAdminUser(String userId, String fallbackRole) {
        if (RbacConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(Objects.toString(fallbackRole, ""))) {
            return true;
        }
        return effectiveRolesFor(assignedRolesFor(userId, fallbackRole)).stream()
                .anyMatch(role -> RbacConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(role));
    }

    public boolean currentUserIsSuperAdmin() {
        return isSuperAdminUser(currentUserId(), currentRole());
    }

    private List<String> listAllPermissionCodes() {
        List<String> codes = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT DISTINCT permission_code
                FROM is_role_permission
                ORDER BY permission_code
                """, String.class));
        if (!codes.contains(RbacConstants.SUPER_ADMIN_PERMISSION)) {
            codes.add(RbacConstants.SUPER_ADMIN_PERMISSION);
        }
        return codes;
    }

    public boolean currentUserHasPermission(String permissionCode) {
        return hasPermissionFor(currentUserId(), currentRole(), permissionCode);
    }

    public List<Map<String, Object>> listRowPoliciesForCurrentUser() {
        List<Map<String, Object>> policies = new ArrayList<>();
        if (AuthContext.isAdmin()) {
            policies.add(new LinkedHashMap<>(Map.of(
                    "sourceType", "GLOBAL",
                    "tableName", "*",
                    "displayName", "管理员全局数据范围",
                    "principalType", "ROLE",
                    "principalId", "ADMIN",
                    "filterExpression", "NO_FILTER",
                    "policyDesc", "管理员角色可查看全部上传数据、官方库数据与审批记录。"
            )));
        } else {
            policies.add(new LinkedHashMap<>(Map.of(
                    "sourceType", "UPLOAD",
                    "tableName", "is_data_table",
                    "displayName", "本人上传数据表",
                    "principalType", "USER",
                    "principalId", currentUserId(),
                    "filterExpression", "owner_id = '" + currentUserId() + "'",
                    "policyDesc", "普通用户只能直接访问 owner_id 等于当前用户的数据表；他人上传表必须走授权记录。"
            )));
            policies.add(new LinkedHashMap<>(Map.of(
                    "sourceType", "UPLOAD_GRANT",
                    "tableName", "is_data_permission",
                    "displayName", "他人上传表授权",
                    "principalType", "USER",
                    "principalId", currentUserId(),
                    "filterExpression", "is_data_permission.user_id = '" + currentUserId() + "' AND expire_at > NOW()",
                    "policyDesc", "审批通过后才允许查看或编辑授权范围内的数据表。"
            )));
        }
        if (tableExists("is_official_row_policy")) {
            List<String> effectiveRoles = effectiveRolesFor(assignedRolesFor(currentUserId(), currentRole()));
            String roleIn = placeholders(Math.max(1, effectiveRoles.size()));
            List<Object> args = new ArrayList<>();
            args.add(currentRole());
            args.add(currentUserId());
            args.addAll(effectiveRoles.isEmpty() ? List.of(currentRole()) : effectiveRoles);
            policies.addAll(jdbcTemplate.queryForList("""
                    SELECT 'OFFICIAL' AS sourceType,
                           CONCAT('official:', p.datasource_id, ':', p.table_name) AS tableName,
                           CONCAT(COALESCE(d.name, 'official'), ' / ', COALESCE(NULLIF(t.table_comment, ''), p.table_name)) AS displayName,
                           p.principal_type AS principalType, p.principal_id AS principalId,
                           p.filter_expression AS filterExpression,
                           CASE WHEN p.enabled = 1 THEN 'ENABLED' ELSE 'DISABLED' END AS status,
                           '官方库行级策略会在查询 SQL 上追加过滤条件。' AS policyDesc,
                           p.created_at AS createdAt
                    FROM is_official_row_policy p
                    LEFT JOIN is_official_datasource d ON d.id = p.datasource_id
                    LEFT JOIN is_official_schema_table t ON t.datasource_id = p.datasource_id AND t.table_name = p.table_name
                    WHERE (? = 'ADMIN' OR (p.enabled = 1 AND ((p.principal_type = 'USER' AND p.principal_id = ?)
                       OR (p.principal_type = 'ROLE' AND p.principal_id IN (%s)))))
                    ORDER BY p.created_at DESC
                    """.formatted(roleIn), args.toArray()));
        }
        return policies;
    }

    public Map<String, Object> getComplianceDocument() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT title, version, content, updated_by AS updatedBy, updated_at AS updatedAt
                FROM is_compliance_document
                WHERE doc_key = 'DATA_SECURITY'
                LIMIT 1
                """);
        if (rows.isEmpty()) {
            return Map.of("title", "企业数据安全合规文档", "version", "v1.0", "content", "");
        }
        return rows.get(0);
    }

    public Map<String, Object> submitRequest(Map<String, Object> request) {
        String tableName = requiredString(request, "tableName", "请选择申请资源");
        String reason = requiredString(request, "reason", "请填写申请理由");
        String resourceType = normalizeResourceType(Objects.toString(request.getOrDefault("resourceType", "")), tableName);
        String permissionType = normalizePermissionType(Objects.toString(request.getOrDefault("permissionType", "READ")));
        String scopeDesc = optionalString(request.get("scopeDesc"));
        String attachmentName = optionalString(request.get("attachmentName"));
        String attachmentContentType = optionalString(request.get("attachmentContentType"));
        String attachmentContent = optionalString(request.get("attachmentContent"));
        String attachmentNote = optionalString(request.get("attachmentNote"));
        long attachmentSize = parseLong(request.get("attachmentSize"));
        Timestamp expireAt = parseExpireAt(request.get("expireAt"));
        String resourceName = resolveResourceName(resourceType, tableName);

        if ("TABLE".equals(resourceType) && "READ".equals(permissionType) && canAccessTable(tableName)) {
            throw new IllegalArgumentException("当前用户已经拥有该数据表访问权限");
        }
        if ("TABLE".equals(resourceType) && "EDIT".equals(permissionType) && hasDataPermission(tableName, "EDIT")) {
            throw new IllegalArgumentException("当前用户已经拥有该数据表编辑权限");
        }
        if ("OFFICIAL".equals(resourceType) && hasOfficialDatasourcePermission(tableName, permissionType)) {
            throw new IllegalArgumentException("当前用户已经拥有该官方库访问权限");
        }
        if ("DASHBOARD".equals(resourceType) && hasDashboardPermission(tableName, permissionType)) {
            throw new IllegalArgumentException("当前用户已经拥有该公共看板权限");
        }

        Integer pendingCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_permission_request
                WHERE applicant_id = ? AND table_name = ? AND resource_type = ? AND permission_type = ? AND status = 'PENDING'
                """, Integer.class, currentUserId(), tableName, resourceType, permissionType);
        if (pendingCount != null && pendingCount > 0) {
            throw new IllegalArgumentException("该资源已有待审批申请，请勿重复提交");
        }
        jdbcTemplate.update("""
                INSERT INTO is_permission_request(applicant_id, table_name, resource_type, resource_name,
                                                  permission_type, reason, scope_desc, expire_at,
                                                  attachment_name, attachment_content_type, attachment_size,
                                                  attachment_content, attachment_note, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, currentUserId(), tableName, resourceType, resourceName, permissionType, reason,
                scopeDesc, expireAt, attachmentName, attachmentContentType, attachmentSize,
                attachmentContent, attachmentNote);
        return latestRequestForUser();
    }

    public void reviewRequest(Long requestId, String action, String comment) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        if (!normalizedAction.equals("APPROVED") && !normalizedAction.equals("REJECTED")) {
            throw new IllegalArgumentException("审批动作只能是 APPROVED 或 REJECTED");
        }
        List<Map<String, Object>> requests = jdbcTemplate.queryForList("""
                SELECT id, applicant_id AS applicantId, table_name AS tableName,
                       resource_type AS resourceType, permission_type AS permissionType,
                       expire_at AS expireAt, status
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
            String resourceType = normalizeResourceType(Objects.toString(request.get("resourceType"), ""), approvedTableName);
            String permissionType = normalizePermissionType(Objects.toString(request.get("permissionType"), "READ"));
            Object expireAt = request.get("expireAt");
            if ("OFFICIAL".equals(resourceType)) {
                grantOfficialDatasourcePermission(approvedTableName, request.get("applicantId"), "READ", expireAt);
                if ("EDIT".equals(permissionType)) {
                    grantOfficialDatasourcePermission(approvedTableName, request.get("applicantId"), "EDIT", expireAt);
                }
            } else if ("DASHBOARD".equals(resourceType)) {
                grantDashboardPermission(approvedTableName, request.get("applicantId"), "READ", expireAt);
                if ("EDIT".equals(permissionType)) {
                    grantDashboardPermission(approvedTableName, request.get("applicantId"), "EDIT", expireAt);
                }
            } else {
                grantDataPermission(approvedTableName, request.get("applicantId"), "READ", expireAt);
                if ("EDIT".equals(permissionType)) {
                    grantDataPermission(approvedTableName, request.get("applicantId"), "EDIT", expireAt);
                }
            }
        }
    }

    private void grantOfficialDatasourcePermission(String tableName, Object applicantId, String permissionType, Object expireAt) {
        String physicalTableName = parseOfficialTableName(tableName);
        jdbcTemplate.update("""
                INSERT INTO is_official_table_permission(datasource_id, table_name, principal_type, principal_id, permission_type, expire_at, source)
                VALUES (?, ?, 'USER', ?, ?, ?, 'REQUEST')
                ON DUPLICATE KEY UPDATE expire_at = VALUES(expire_at), source = VALUES(source), created_at = CURRENT_TIMESTAMP
                """, parseOfficialDatasourceId(tableName), physicalTableName, applicantId, permissionType, expireAt);
    }

    private void grantDashboardPermission(String tableName, Object applicantId, String permissionType, Object expireAt) {
        jdbcTemplate.update("""
                INSERT INTO is_dashboard_permission(dashboard_id, user_id, permission_type, source, expire_at)
                VALUES (?, ?, ?, 'REQUEST', ?)
                ON DUPLICATE KEY UPDATE source = VALUES(source), expire_at = VALUES(expire_at), created_at = CURRENT_TIMESTAMP
                """, parseDashboardId(tableName), applicantId, permissionType, expireAt);
    }

    private void grantDataPermission(String tableName, Object applicantId, String permissionType, Object expireAt) {
        jdbcTemplate.update("""
                INSERT INTO is_data_permission(user_id, table_name, permission_type, source, expire_at)
                VALUES (?, ?, ?, 'REQUEST', ?)
                ON DUPLICATE KEY UPDATE source = VALUES(source), expire_at = VALUES(expire_at), created_at = CURRENT_TIMESTAMP
                """, applicantId, tableName, permissionType, expireAt);
    }

    public List<Map<String, Object>> listAccessibleOfficialTables() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', d.id, ':', t.table_name) AS tableName,
                       CONCAT(d.name, ' / ', COALESCE(NULLIF(t.table_comment, ''), t.table_name)) AS displayName,
                       d.name AS datasourceName, d.id AS datasourceId, t.table_name AS physicalTableName,
                       t.table_comment AS tableComment,
                       COALESCE(t.table_rows, 0) AS rowCount,
                       (SELECT COUNT(*) FROM is_official_schema_field f WHERE f.datasource_id = d.id AND f.table_name = t.table_name) AS fieldCount,
                       'OFFICIAL' AS sourceType, p.created_at AS grantedAt, p.expire_at AS expireAt,
                       CASE WHEN EXISTS (
                         SELECT 1 FROM is_official_table_permission pe
                         WHERE pe.datasource_id = d.id AND (pe.table_name = t.table_name OR pe.table_name = '*')
                           AND pe.permission_type = 'EDIT'
                           AND (pe.expire_at IS NULL OR pe.expire_at > NOW())
                           AND ((pe.principal_type = 'USER' AND pe.principal_id = ?) OR (pe.principal_type = 'ROLE' AND pe.principal_id = ?))
                       ) THEN 'VIEW,EDIT' ELSE 'VIEW' END AS permissionScope
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                JOIN is_official_table_permission p ON p.datasource_id = d.id AND (p.table_name = t.table_name OR p.table_name = '*')
                WHERE d.status = 'ENABLED' AND p.permission_type = 'READ'
                  AND (p.expire_at IS NULL OR p.expire_at > NOW())
                  AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                ORDER BY d.created_at DESC, t.table_name ASC
                """, currentUserId(), currentRole(), currentUserId(), currentRole());
        normalizeOfficialDisplayNames(rows);
        return rows;
    }

    public List<Map<String, Object>> listRequestableOfficialTables() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', d.id, ':', t.table_name) AS tableName,
                       CONCAT(d.name, ' / ', COALESCE(NULLIF(t.table_comment, ''), t.table_name)) AS displayName,
                       d.name AS ownerId, d.name AS datasourceName, t.table_name AS physicalTableName,
                       t.table_comment AS tableComment, COALESCE(t.table_rows, 0) AS rowCount,
                       (SELECT COUNT(*) FROM is_official_schema_field f WHERE f.datasource_id = d.id AND f.table_name = t.table_name) AS fieldCount,
                       'OFFICIAL' AS sourceType, 'OFFICIAL' AS resourceType,
                       CASE WHEN pr.id IS NULL THEN 'READ' ELSE 'EDIT' END AS suggestedPermissionType,
                       t.created_at AS createdAt
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                LEFT JOIN is_official_table_permission pr ON pr.datasource_id = d.id AND (pr.table_name = t.table_name OR pr.table_name = '*')
                  AND pr.permission_type = 'READ'
                  AND (pr.expire_at IS NULL OR pr.expire_at > NOW())
                  AND ((pr.principal_type = 'USER' AND pr.principal_id = ?) OR (pr.principal_type = 'ROLE' AND pr.principal_id = ?))
                WHERE d.status = 'ENABLED'
                  AND NOT EXISTS (
                    SELECT 1 FROM is_official_table_permission p
                    WHERE p.datasource_id = d.id AND (p.table_name = t.table_name OR p.table_name = '*')
                      AND p.permission_type = 'EDIT'
                      AND (p.expire_at IS NULL OR p.expire_at > NOW())
                      AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                  )
                ORDER BY d.created_at DESC, t.table_name ASC
                """, currentUserId(), currentRole(), currentUserId(), currentRole());
        normalizeOfficialDisplayNames(rows);
        return rows;
    }

    public List<Map<String, Object>> listRequestablePublicDashboards() {
        return jdbcTemplate.queryForList("""
                SELECT CONCAT('dashboard:', d.id) AS tableName,
                       d.name AS displayName, d.owner_user_id AS ownerId,
                       0 AS rowCount, 0 AS fieldCount,
                       'DASHBOARD' AS sourceType, 'DASHBOARD' AS resourceType,
                       CASE WHEN pr.id IS NULL THEN 'READ' ELSE 'EDIT' END AS suggestedPermissionType,
                       d.created_at AS createdAt
                FROM is_dashboard d
                LEFT JOIN is_dashboard_permission pr ON pr.dashboard_id = d.id
                     AND pr.user_id = ? AND pr.permission_type = 'READ'
                     AND (pr.expire_at IS NULL OR pr.expire_at > NOW())
                WHERE d.status = 'ACTIVE' AND d.is_public = 1 AND d.owner_user_id <> ?
                  AND NOT EXISTS (
                    SELECT 1 FROM is_dashboard_permission p
                    WHERE p.dashboard_id = d.id AND p.user_id = ? AND p.permission_type = 'EDIT'
                      AND (p.expire_at IS NULL OR p.expire_at > NOW())
                  )
                ORDER BY d.created_at DESC
                """, currentUserId(), currentUserId(), currentUserId());
    }

    public List<Map<String, Object>> listSensitiveFieldPermissions() {
        List<Map<String, Object>> fields = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT t.display_name AS tableDisplayName, f.table_name AS tableName,
                       f.display_name AS displayName, f.column_name AS columnName,
                       'UPLOAD' AS sourceType, 'MASKED' AS accessMode,
                       '已授权访问，查询结果按脱敏规则展示' AS reason
                FROM is_data_field f
                JOIN is_data_table t ON t.table_name = f.table_name
                LEFT JOIN is_data_permission p ON p.table_name = t.table_name AND p.user_id = ? AND p.permission_type = 'READ'
                     AND (p.expire_at IS NULL OR p.expire_at > NOW())
                WHERE f.sensitive = 1 AND t.status = 'ACTIVE' AND (? = 'ADMIN' OR t.owner_id = ? OR p.id IS NOT NULL)
                ORDER BY t.created_at DESC, f.sort_order ASC
                """, currentUserId(), currentRole(), currentUserId()));
        fields.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT(d.name, ' / ', s.table_name) AS tableDisplayName,
                       CONCAT('official:', d.id, ':', s.table_name) AS tableName,
                       COALESCE(NULLIF(s.business_name, ''), NULLIF(s.column_comment, ''), s.column_name) AS displayName,
                       s.column_name AS columnName, 'OFFICIAL' AS sourceType, 'MASKED' AS accessMode,
                       '已授权访问，查询结果按脱敏规则展示' AS reason
                FROM is_official_schema_field s
                JOIN is_official_datasource d ON d.id = s.datasource_id
                JOIN is_official_table_permission p ON p.datasource_id = d.id AND (p.table_name = s.table_name OR p.table_name = '*')
                WHERE s.sensitive = 1 AND d.status = 'ENABLED' AND p.permission_type = 'READ'
                  AND (p.expire_at IS NULL OR p.expire_at > NOW())
                  AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                ORDER BY d.created_at DESC, s.table_name, s.ordinal_position
                """, currentUserId(), currentRole()));
        if (!AuthContext.isAdmin()) {
            fields.addAll(jdbcTemplate.queryForList("""
                    SELECT t.display_name AS tableDisplayName, f.table_name AS tableName,
                           f.display_name AS displayName, f.column_name AS columnName,
                           'UPLOAD' AS sourceType, 'NO_ACCESS' AS accessMode,
                           '未获得该数据表访问权限，行级隔离已阻止查看' AS reason
                    FROM is_data_field f
                    JOIN is_data_table t ON t.table_name = f.table_name
                    LEFT JOIN is_data_permission p ON p.table_name = t.table_name AND p.user_id = ? AND p.permission_type = 'READ'
                         AND (p.expire_at IS NULL OR p.expire_at > NOW())
                    WHERE f.sensitive = 1 AND t.status = 'ACTIVE' AND t.owner_id <> ? AND p.id IS NULL
                    ORDER BY t.created_at DESC, f.sort_order ASC
                    LIMIT 50
                    """, currentUserId(), currentUserId()));
            fields.addAll(jdbcTemplate.queryForList("""
                    SELECT CONCAT(d.name, ' / ', s.table_name) AS tableDisplayName,
                           CONCAT('official:', d.id, ':', s.table_name) AS tableName,
                           COALESCE(NULLIF(s.business_name, ''), NULLIF(s.column_comment, ''), s.column_name) AS displayName,
                           s.column_name AS columnName, 'OFFICIAL' AS sourceType, 'NO_ACCESS' AS accessMode,
                           '未获得官方库授权或授权已过期' AS reason
                    FROM is_official_schema_field s
                    JOIN is_official_datasource d ON d.id = s.datasource_id
                    WHERE s.sensitive = 1 AND d.status = 'ENABLED'
                      AND NOT EXISTS (
                        SELECT 1 FROM is_official_table_permission p
                        WHERE p.datasource_id = d.id AND (p.table_name = s.table_name OR p.table_name = '*')
                          AND p.permission_type = 'READ'
                          AND (p.expire_at IS NULL OR p.expire_at > NOW())
                          AND ((p.principal_type = 'USER' AND p.principal_id = ?) OR (p.principal_type = 'ROLE' AND p.principal_id = ?))
                      )
                    ORDER BY d.created_at DESC, s.table_name, s.ordinal_position
                    LIMIT 50
                    """, currentUserId(), currentRole()));
        }
        return fields;
    }

    public List<Map<String, Object>> listMyRequests() {
        return jdbcTemplate.queryForList("""
                SELECT r.id, r.applicant_id AS applicantId, r.table_name AS tableName,
                       COALESCE(r.resource_name, t.display_name, r.table_name) AS displayName,
                       r.resource_type AS resourceType, r.permission_type AS permissionType,
                       r.reason, r.scope_desc AS scopeDesc, r.expire_at AS expireAt,
                       r.attachment_name AS attachmentName,
                       r.attachment_content_type AS attachmentContentType,
                       r.attachment_size AS attachmentSize,
                       r.attachment_note AS attachmentNote,
                       r.status, r.reviewer_id AS reviewerId,
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
                       COALESCE(r.resource_name, t.display_name, r.table_name) AS displayName,
                       t.owner_id AS ownerId, r.resource_type AS resourceType,
                       r.permission_type AS permissionType, r.reason, r.scope_desc AS scopeDesc,
                       r.expire_at AS expireAt, r.attachment_name AS attachmentName,
                       r.attachment_content_type AS attachmentContentType,
                       r.attachment_size AS attachmentSize,
                       r.attachment_note AS attachmentNote, r.status,
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
                       COALESCE(r.resource_name, t.display_name, r.table_name) AS displayName,
                       r.resource_type AS resourceType, r.permission_type AS permissionType,
                       r.reason, r.scope_desc AS scopeDesc, r.expire_at AS expireAt,
                       r.attachment_name AS attachmentName,
                       r.attachment_content_type AS attachmentContentType,
                       r.attachment_size AS attachmentSize,
                       r.attachment_note AS attachmentNote,
                       r.status, r.created_at AS createdAt
                FROM is_permission_request r
                LEFT JOIN is_data_table t ON t.table_name = r.table_name
                WHERE r.applicant_id = ?
                ORDER BY r.created_at DESC
                LIMIT 1
                """, currentUserId());
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void initRbacTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_role` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `role_code` VARCHAR(64) NOT NULL UNIQUE,
                  `role_name` VARCHAR(128) NOT NULL,
                  `parent_role_code` VARCHAR(64) NULL,
                  `role_level` INT NOT NULL DEFAULT 1,
                  `data_scope` VARCHAR(32) NOT NULL DEFAULT 'SELF',
                  `description` VARCHAR(1000) NULL,
                  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_is_role_parent` (`parent_role_code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC role definition';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_user_role` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL,
                  `role_code` VARCHAR(64) NOT NULL,
                  `source` VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_is_user_role` (`user_id`, `role_code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC user role binding';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_role_permission` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `role_code` VARCHAR(64) NOT NULL,
                  `permission_code` VARCHAR(128) NOT NULL,
                  `permission_name` VARCHAR(255) NOT NULL,
                  `permission_type` VARCHAR(32) NOT NULL,
                  `resource_scope` VARCHAR(255) NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_is_role_permission` (`role_code`, `permission_code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC role permission binding';
                """);
        seedRole("USER", "普通用户", null, 1, "SELF", "本人上传数据、已授权官方数据与公共看板申请能力");
        seedRole("ADMIN", "管理员", "USER", 3, "ALL", "继承普通用户权限，并拥有全局配置、审批和治理权限");
        seedRole("SUPER_ADMIN", "超级管理员", "ADMIN", 4, "ALL", "拥有管理员端全部菜单与操作权限，不受 RBAC 勾选限制");
        bindExistingUserRoles();
        promoteBuiltInSuperAdmin();
        seedRolePermission("USER", "menu:user-workbench", "用户工作台", "MENU", "USER");
        seedRolePermission("USER", "menu:chat-analysis", "对话分析", "MENU", "USER");
        seedRolePermission("USER", "menu:data-upload", "数据上传", "MENU", "USER");
        seedRolePermission("USER", "menu:dashboard", "我的看板", "MENU", "USER");
        seedRolePermission("USER", "menu:diagnosis", "智能诊断", "MENU", "USER");
        seedRolePermission("USER", "menu:permission-center", "数据权限中心", "MENU", "USER");
        seedRolePermission("USER", "data:self-upload", "本人上传数据", "DATA", "SELF");
        seedRolePermission("USER", "data:granted-official-table", "已授权官方库表", "DATA", "GRANTED_TABLE");
        seedRolePermission("ADMIN", "menu:permission-approval", "权限审批", "MENU", "ADMIN");
        seedRolePermission("ADMIN", "menu:datasource-admin", "数据源管理", "MENU", "ADMIN");
        seedRolePermission("ADMIN", "menu:sql-audit", "SQL 审计", "MENU", "ADMIN");
        seedRolePermission("ADMIN", "data:all", "全量数据", "DATA", "ALL");
        seedRolePermission("ADMIN", "operation:rbac-manage", "用户与角色权限管理", "OPERATION", "ADMIN");
        seedRolePermission("SUPER_ADMIN", RbacConstants.SUPER_ADMIN_PERMISSION, "超级管理员", "OPERATION", "ALL");
    }

    private void promoteBuiltInSuperAdmin() {
        if (!tableExists("is_user")) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE is_user
                SET role = ?, nickname = CASE WHEN nickname = '管理员' THEN '超级管理员' ELSE nickname END
                WHERE username = 'admin' AND role IN ('ADMIN', 'SUPER_ADMIN')
                """, RbacConstants.SUPER_ADMIN_ROLE);
        jdbcTemplate.update("""
                INSERT IGNORE INTO is_user_role(user_id, role_code, source)
                SELECT user_id, ?, 'SYSTEM'
                FROM is_user
                WHERE username = 'admin'
                """, RbacConstants.SUPER_ADMIN_ROLE);
    }

    private void initComplianceDocuments() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_compliance_document` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `doc_key` VARCHAR(64) NOT NULL UNIQUE,
                  `title` VARCHAR(255) NOT NULL,
                  `version` VARCHAR(32) NOT NULL DEFAULT 'v1.0',
                  `content` LONGTEXT NOT NULL,
                  `updated_by` VARCHAR(64) NULL,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='enterprise compliance document';
                """);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_compliance_document WHERE doc_key = 'DATA_SECURITY'",
                Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO is_compliance_document(doc_key, title, version, content, updated_by)
                    VALUES ('DATA_SECURITY', '企业数据安全合规文档', 'v1.0', ?, 'system')
                    """, """
                    1. 数据仅可用于申请时声明的业务目的，禁止转发给未授权人员、群组或外部系统。
                    2. 手机号、身份证、金额、订单号等敏感字段必须按系统脱敏规则展示和导出。
                    3. 官方库访问以表级授权为最小边界；未授权表不可用于对话查询、预览或导出。
                    4. 普通用户只能访问本人上传数据和审批通过的数据；管理员操作必须保留审计痕迹。
                    5. 违规访问、复制、截图传播或绕过审批使用数据，将触发账号冻结和内部合规处理。
                    """);
        }
    }

    private void seedRole(String code, String name, String parentCode, int level, String dataScope, String description) {
        jdbcTemplate.update("""
                INSERT INTO is_role(role_code, role_name, parent_role_code, role_level, data_scope, description, enabled)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), parent_role_code = VALUES(parent_role_code),
                    role_level = VALUES(role_level), data_scope = VALUES(data_scope), description = VALUES(description), enabled = 1
                """, code, name, parentCode, level, dataScope, description);
    }

    private void seedRolePermission(String roleCode, String permissionCode, String permissionName, String permissionType, String resourceScope) {
        jdbcTemplate.update("""
                INSERT INTO is_role_permission(role_code, permission_code, permission_name, permission_type, resource_scope)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name),
                    permission_type = VALUES(permission_type), resource_scope = VALUES(resource_scope)
                """, roleCode, permissionCode, permissionName, permissionType, resourceScope);
    }

    private void bindExistingUserRoles() {
        if (!tableExists("is_user")) {
            return;
        }
        jdbcTemplate.update("""
                INSERT IGNORE INTO is_user_role(user_id, role_code, source)
                SELECT user_id, role, 'LEGACY_ROLE_COLUMN'
                FROM is_user
                WHERE role IS NOT NULL AND role <> ''
                """);
    }

    private void migrateApprovedOfficialRequests() {
        if (!tableExists("is_permission_request") || !tableExists("is_official_schema_table")) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO is_official_table_permission(datasource_id, table_name, principal_type, principal_id,
                                                         permission_type, expire_at, created_at)
                SELECT CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(r.table_name, ':', 2), ':', -1) AS UNSIGNED),
                       SUBSTRING_INDEX(r.table_name, ':', -1),
                       'USER',
                       r.applicant_id,
                       'READ',
                       r.expire_at,
                       COALESCE(r.reviewed_at, r.created_at)
                FROM is_permission_request r
                JOIN is_official_schema_table t
                  ON t.datasource_id = CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(r.table_name, ':', 2), ':', -1) AS UNSIGNED)
                 AND t.table_name = SUBSTRING_INDEX(r.table_name, ':', -1)
                WHERE r.resource_type = 'OFFICIAL' AND r.status = 'APPROVED'
                  AND r.table_name LIKE 'official:%:%'
                ON DUPLICATE KEY UPDATE expire_at = VALUES(expire_at), created_at = VALUES(created_at)
                """);
        jdbcTemplate.update("""
                INSERT INTO is_official_table_permission(datasource_id, table_name, principal_type, principal_id,
                                                         permission_type, expire_at, created_at)
                SELECT CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(r.table_name, ':', 2), ':', -1) AS UNSIGNED),
                       SUBSTRING_INDEX(r.table_name, ':', -1),
                       'USER',
                       r.applicant_id,
                       'EDIT',
                       r.expire_at,
                       COALESCE(r.reviewed_at, r.created_at)
                FROM is_permission_request r
                JOIN is_official_schema_table t
                  ON t.datasource_id = CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(r.table_name, ':', 2), ':', -1) AS UNSIGNED)
                 AND t.table_name = SUBSTRING_INDEX(r.table_name, ':', -1)
                WHERE r.resource_type = 'OFFICIAL' AND r.status = 'APPROVED' AND r.permission_type = 'EDIT'
                  AND r.table_name LIKE 'official:%:%'
                ON DUPLICATE KEY UPDATE expire_at = VALUES(expire_at), created_at = VALUES(created_at)
                """);
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

    private String parseOfficialTableName(String sourceKey) {
        String[] parts = sourceKey.split(":", 3);
        if (parts.length != 3 || parts[2].isBlank()) {
            throw new IllegalArgumentException("无效的官方数据表标识：" + sourceKey);
        }
        return parts[2];
    }

    private Long parseDashboardId(String sourceKey) {
        String[] parts = sourceKey.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效的公共看板标识：" + sourceKey);
        }
        return Long.parseLong(parts[1]);
    }

    private String resolveResourceName(String resourceType, String tableName) {
        List<Map<String, Object>> rows;
        if ("OFFICIAL".equals(resourceType)) {
            rows = jdbcTemplate.queryForList("""
                    SELECT d.name AS datasourceName, t.table_name AS physicalTableName, t.table_comment AS tableComment
                    FROM is_official_datasource d
                    JOIN is_official_schema_table t ON t.datasource_id = d.id
                    WHERE d.id = ? AND t.table_name = ? AND d.status = 'ENABLED'
                    LIMIT 1
                    """, parseOfficialDatasourceId(tableName), parseOfficialTableName(tableName));
        } else if ("DASHBOARD".equals(resourceType)) {
            rows = jdbcTemplate.queryForList("""
                    SELECT name AS displayName FROM is_dashboard
                    WHERE id = ? AND status = 'ACTIVE' AND is_public = 1
                    LIMIT 1
                    """, parseDashboardId(tableName));
        } else {
            rows = jdbcTemplate.queryForList("""
                    SELECT display_name AS displayName FROM is_data_table
                    WHERE table_name = ? AND status = 'ACTIVE'
                    LIMIT 1
                    """, tableName);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("申请资源不存在或不可申请");
        }
        if ("OFFICIAL".equals(resourceType)) {
            Map<String, Object> row = rows.get(0);
            return officialDisplayName(
                    Objects.toString(row.get("datasourceName"), ""),
                    Objects.toString(row.get("physicalTableName"), tableName),
                    Objects.toString(row.get("tableComment"), "")
            );
        }
        return Objects.toString(rows.get(0).get("displayName"), tableName);
    }

    private void normalizeOfficialDisplayNames(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            row.put("displayName", officialDisplayName(
                    Objects.toString(row.get("datasourceName"), Objects.toString(row.get("ownerId"), "")),
                    Objects.toString(row.get("physicalTableName"), ""),
                    Objects.toString(row.get("tableComment"), "")
            ));
        }
    }

    private String officialDisplayName(String datasourceName, String tableName, String tableComment) {
        String label = usableText(tableComment) ? tableComment.trim() : tableName;
        if (datasourceName == null || datasourceName.isBlank()) {
            return label;
        }
        return datasourceName + " / " + label;
    }

    private boolean usableText(String value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return false;
        }
        return !looksMojibake(text);
    }

    private boolean looksMojibake(String text) {
        String markers = "锛銆鐨绋鍙鎴鎵嬈冨簱鏉冮檺瑙ｆ瀽涓婁紶";
        long hits = text.chars()
                .filter(ch -> markers.indexOf(ch) >= 0)
                .count();
        return hits >= 2 || text.contains("�");
    }

    private boolean hasOfficialDatasourcePermission(String tableName, String permissionType) {
        Long datasourceId = parseOfficialDatasourceId(tableName);
        String physicalTableName = parseOfficialTableName(tableName);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_official_table_permission
                WHERE datasource_id = ? AND (table_name = ? OR table_name = '*') AND permission_type = ?
                  AND (expire_at IS NULL OR expire_at > NOW())
                  AND ((principal_type = 'USER' AND principal_id = ?) OR (principal_type = 'ROLE' AND principal_id = ?))
                """, Integer.class, datasourceId, physicalTableName, permissionType, currentUserId(), currentRole());
        return count != null && count > 0;
    }

    private boolean hasDataPermission(String tableName, String permissionType) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_data_permission
                WHERE table_name = ? AND user_id = ? AND permission_type = ?
                  AND (expire_at IS NULL OR expire_at > NOW())
                """, Integer.class, tableName, currentUserId(), permissionType);
        return count != null && count > 0;
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

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(Math.max(1, count), "?"));
    }

    private boolean hasDashboardPermission(String tableName, String permissionType) {
        Long dashboardId = parseDashboardId(tableName);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard d
                LEFT JOIN is_dashboard_permission p ON p.dashboard_id = d.id
                     AND p.user_id = ? AND p.permission_type = ?
                     AND (p.expire_at IS NULL OR p.expire_at > NOW())
                WHERE d.id = ? AND d.status = 'ACTIVE' AND (d.owner_user_id = ? OR p.id IS NOT NULL)
                """, Integer.class, currentUserId(), permissionType, dashboardId, currentUserId());
        return count != null && count > 0;
    }

    private String normalizeResourceType(String value, String tableName) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isBlank()) {
            if (tableName.startsWith("official:")) {
                normalized = "OFFICIAL";
            } else if (tableName.startsWith("dashboard:")) {
                normalized = "DASHBOARD";
            } else {
                normalized = "TABLE";
            }
        }
        if (!List.of("TABLE", "OFFICIAL", "DASHBOARD").contains(normalized)) {
            throw new IllegalArgumentException("不支持的申请资源类型：" + value);
        }
        return normalized;
    }

    private String normalizePermissionType(String value) {
        String normalized = value == null ? "READ" : value.trim().toUpperCase();
        if ("VIEW".equals(normalized)) {
            normalized = "READ";
        }
        if (!List.of("READ", "EDIT").contains(normalized)) {
            throw new IllegalArgumentException("申请权限范围只能是 READ 或 EDIT");
        }
        return normalized;
    }

    private String requiredString(Map<String, Object> request, String key, String message) {
        String value = optionalString(request.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String optionalString(Object value) {
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = optionalString(value);
        if (text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Timestamp parseExpireAt(Object value) {
        String text = optionalString(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            if (text.length() <= 10) {
                return Timestamp.valueOf(LocalDate.parse(text).atTime(23, 59, 59));
            }
            return Timestamp.valueOf(LocalDateTime.parse(text.replace(" ", "T")).withNano(0));
        } catch (Exception e) {
            throw new IllegalArgumentException("有效期格式无效，请使用 YYYY-MM-DD");
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SHOW COLUMNS FROM `" + tableName + "` LIKE ?", columnName);
        if (columns.isEmpty()) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + definition);
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
