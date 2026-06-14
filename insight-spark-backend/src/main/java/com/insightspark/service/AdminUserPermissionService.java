package com.insightspark.service;

import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminUserPermissionService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PermissionService permissionService;

    @PostConstruct
    public void initTables() {
    }

    private void assertAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("仅管理员可操作");
        }
    }

    public Map<String, Object> overview() {
        assertAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_user", Integer.class));
        result.put("activeUserCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_user WHERE status = 'ACTIVE'", Integer.class));
        result.put("roleCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_role", Integer.class));
        result.put("bindingCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_user_role", Integer.class));
        result.put("dataGrantCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_data_permission", Integer.class));
        result.put("officialGrantCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_official_table_permission", Integer.class));
        result.put("dashboardGrantCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_dashboard_permission", Integer.class));
        result.put("permissionOverview", permissionService.getPermissionOverview());
        return result;
    }

    public List<Map<String, Object>> listUsers(String keyword) {
        assertAdmin();
        String kw = keyword == null ? "" : keyword.trim();
        List<Map<String, Object>> rows = kw.isBlank()
                ? jdbcTemplate.queryForList("""
                        SELECT id, user_id AS userId, username, nickname, phone, email, role, status, last_login_at AS lastLoginAt, created_at AS createdAt
                        FROM is_user ORDER BY created_at DESC
                        """)
                : jdbcTemplate.queryForList("""
                        SELECT id, user_id AS userId, username, nickname, phone, email, role, status, last_login_at AS lastLoginAt, created_at AS createdAt
                        FROM is_user
                        WHERE username LIKE ? OR nickname LIKE ? OR user_id LIKE ? OR phone LIKE ? OR email LIKE ?
                        ORDER BY created_at DESC
                        """, like(kw), like(kw), like(kw), like(kw), like(kw));
        rows.forEach(row -> row.put("roles", userRoles(Objects.toString(row.get("userId"), ""))));
        return rows;
    }

    public Map<String, Object> saveUser(Map<String, Object> payload) {
        assertAdmin();
        String userId = text(payload, "userId");
        String username = text(payload, "username");
        String nickname = text(payload, "nickname");
        String phone = optional(payload.get("phone"));
        String email = optional(payload.get("email"));
        String status = Objects.toString(payload.getOrDefault("status", "ACTIVE")).toUpperCase();
        String role = Objects.toString(payload.getOrDefault("role", "USER")).toUpperCase();
        String password = optional(payload.get("password"));
        if (userId.isBlank() || username.isBlank() || nickname.isBlank()) {
            throw new IllegalArgumentException("用户编号、用户名、昵称不能为空");
        }
        if (password != null && !password.isBlank()) {
            String salt = UUIDHelper.newSalt();
            String hash = UUIDHelper.hashPassword(password, salt);
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_user WHERE user_id = ?", Integer.class, userId);
            if (exists != null && exists > 0) {
                jdbcTemplate.update("""
                        UPDATE is_user SET username = ?, nickname = ?, phone = ?, email = ?, password_hash = ?, password_salt = ?, role = ?, status = ?
                        WHERE user_id = ?
                        """, username, nickname, nullIfBlank(phone), nullIfBlank(email), hash, salt, role, status, userId);
            } else {
                jdbcTemplate.update("""
                        INSERT INTO is_user(user_id, username, nickname, phone, email, password_hash, password_salt, role, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, userId, username, nickname, nullIfBlank(phone), nullIfBlank(email), hash, salt, role, status);
            }
        } else {
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_user WHERE user_id = ?", Integer.class, userId);
            if (exists != null && exists > 0) {
                jdbcTemplate.update("""
                        UPDATE is_user SET username = ?, nickname = ?, phone = ?, email = ?, role = ?, status = ?
                        WHERE user_id = ?
                        """, username, nickname, nullIfBlank(phone), nullIfBlank(email), role, status, userId);
            } else {
                throw new IllegalArgumentException("新增用户必须设置密码");
            }
        }
        bindPrimaryRole(userId, role);
        return previewUser(userId);
    }

    public void updateUserStatus(String userId, String status) {
        assertAdmin();
        jdbcTemplate.update("UPDATE is_user SET status = ? WHERE user_id = ?", Objects.toString(status, "ACTIVE").toUpperCase(), userId);
    }

    public void bindUserRoles(String userId, List<?> roles) {
        assertAdmin();
        jdbcTemplate.update("DELETE FROM is_user_role WHERE user_id = ?", userId);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object role : roles) {
            String code = Objects.toString(role, "").trim().toUpperCase();
            if (!code.isBlank()) normalized.add(code);
        }
        if (normalized.isEmpty()) normalized.add("USER");
        for (String role : normalized) {
            jdbcTemplate.update("INSERT IGNORE INTO is_user_role(user_id, role_code, source) VALUES (?, ?, 'ADMIN')", userId, role);
        }
    }

    public List<Map<String, Object>> listRoles() {
        assertAdmin();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT role_code AS roleCode, role_name AS roleName, parent_role_code AS parentRoleCode,
                       role_level AS roleLevel, data_scope AS dataScope, description, enabled, created_at AS createdAt
                FROM is_role ORDER BY role_level DESC, role_code ASC
                """);
        rows.forEach(row -> {
            String roleCode = Objects.toString(row.get("roleCode"), "");
            List<Map<String, Object>> permissions = jdbcTemplate.queryForList("""
                    SELECT permission_code AS permissionCode, permission_name AS permissionName,
                           permission_type AS permissionType, resource_scope AS resourceScope
                    FROM is_role_permission WHERE role_code = ? ORDER BY permission_type, permission_code
                    """, roleCode);
            row.put("permissions", permissions);
            row.put("permissionCount", permissions.size());
        });
        return rows;
    }

    public Map<String, Object> saveRole(Map<String, Object> payload) {
        assertAdmin();
        String roleCode = Objects.toString(payload.getOrDefault("roleCode", "")).trim().toUpperCase();
        String roleName = text(payload, "roleName");
        String parentRoleCode = optional(payload.get("parentRoleCode"));
        Integer roleLevel = toInt(payload.get("roleLevel"), 1);
        String dataScope = Objects.toString(payload.getOrDefault("dataScope", "SELF")).toUpperCase();
        String description = optional(payload.get("description"));
        boolean enabled = Boolean.parseBoolean(Objects.toString(payload.getOrDefault("enabled", true)));
        if (roleCode.isBlank() || roleName.isBlank()) {
            throw new IllegalArgumentException("角色编码和名称不能为空");
        }
        jdbcTemplate.update("""
                INSERT INTO is_role(role_code, role_name, parent_role_code, role_level, data_scope, description, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), parent_role_code = VALUES(parent_role_code),
                    role_level = VALUES(role_level), data_scope = VALUES(data_scope), description = VALUES(description), enabled = VALUES(enabled)
                """, roleCode, roleName, nullIfBlank(parentRoleCode), roleLevel, dataScope, description, enabled ? 1 : 0);
        return jdbcTemplate.queryForMap("SELECT role_code AS roleCode, role_name AS roleName, parent_role_code AS parentRoleCode, role_level AS roleLevel, data_scope AS dataScope, description, enabled FROM is_role WHERE role_code = ?", roleCode);
    }

    public void saveRolePermissions(String roleCode, List<?> permissions) {
        assertAdmin();
        String code = Objects.toString(roleCode, "").trim().toUpperCase();
        if (code.isBlank()) {
            throw new IllegalArgumentException("角色编码不能为空");
        }
        jdbcTemplate.update("DELETE FROM is_role_permission WHERE role_code = ?", code);
        for (Object item : permissions) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String permissionCode = Objects.toString(map.get("permissionCode"), "").trim();
            String permissionName = Objects.toString(map.containsKey("permissionName") ? map.get("permissionName") : permissionCode, "").trim();
            String permissionType = Objects.toString(map.containsKey("permissionType") ? map.get("permissionType") : "OPERATION", "").trim().toUpperCase();
            String resourceScope = optional(map.get("resourceScope"));
            if (!permissionCode.isBlank()) {
                jdbcTemplate.update("""
                        INSERT INTO is_role_permission(role_code, permission_code, permission_name, permission_type, resource_scope)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name), permission_type = VALUES(permission_type), resource_scope = VALUES(resource_scope)
                        """, code, permissionCode, permissionName.isBlank() ? permissionCode : permissionName, permissionType, resourceScope);
            }
        }
    }

    public Map<String, Object> permissionCatalog() {
        assertAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("menu", jdbcTemplate.queryForList("SELECT DISTINCT permission_code AS permissionCode, permission_name AS permissionName, resource_scope AS resourceScope, permission_type AS permissionType FROM is_role_permission WHERE permission_type = 'MENU' ORDER BY permission_code"));
        result.put("operation", jdbcTemplate.queryForList("SELECT DISTINCT permission_code AS permissionCode, permission_name AS permissionName, resource_scope AS resourceScope, permission_type AS permissionType FROM is_role_permission WHERE permission_type = 'OPERATION' ORDER BY permission_code"));
        result.put("data", jdbcTemplate.queryForList("SELECT DISTINCT permission_code AS permissionCode, permission_name AS permissionName, resource_scope AS resourceScope, permission_type AS permissionType FROM is_role_permission WHERE permission_type = 'DATA' ORDER BY permission_code"));
        return result;
    }

    public Map<String, Object> resources() {
        assertAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadTables", jdbcTemplate.queryForList("SELECT table_name AS tableName, display_name AS displayName, owner_id AS ownerId, status FROM is_data_table ORDER BY created_at DESC"));
        result.put("officialTables", jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', d.id, ':', t.table_name) AS tableName,
                       CONCAT(d.name, ' / ', COALESCE(NULLIF(t.table_comment, ''), t.table_name)) AS displayName,
                       d.name AS ownerId, d.status
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                ORDER BY d.created_at DESC, t.table_name ASC
                """));
        result.put("dashboards", jdbcTemplate.queryForList("SELECT CONCAT('dashboard:', id) AS tableName, name AS displayName, owner_user_id AS ownerId, status FROM is_dashboard ORDER BY created_at DESC"));
        List<Map<String, Object>> fields = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT table_name AS tableName, column_name AS columnName, display_name AS displayName, sensitive, 'UPLOAD' AS sourceType
                FROM is_data_field ORDER BY table_name, sort_order ASC
                """));
        fields.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', datasource_id, ':', table_name) AS tableName,
                       column_name AS columnName,
                       COALESCE(NULLIF(business_name, ''), NULLIF(column_comment, ''), column_name) AS displayName,
                       sensitive, 'OFFICIAL' AS sourceType
                FROM is_official_schema_field ORDER BY datasource_id, table_name, ordinal_position ASC
                """));
        result.put("fields", fields);
        return result;
    }

    public void grantData(Map<String, Object> payload) {
        assertAdmin();
        String scope = Objects.toString(payload.getOrDefault("scope", "TABLE")).toUpperCase();
        String targetType = Objects.toString(payload.getOrDefault("targetType", "USER")).toUpperCase();
        String targetId = text(payload, "targetId");
        String resource = text(payload, "resource");
        String permissionType = Objects.toString(payload.getOrDefault("permissionType", "READ")).toUpperCase();
        String expireAt = optional(payload.get("expireAt"));
        if (scope.equals("TABLE")) {
            grantByTargetType(targetType, targetId, resource, permissionType, expireAt, "ADMIN");
        } else if (scope.equals("OFFICIAL")) {
            grantOfficial(targetType, targetId, resource, permissionType, expireAt);
        } else if (scope.equals("DASHBOARD")) {
            grantDashboard(targetType, targetId, resource, permissionType, expireAt);
        } else {
            throw new IllegalArgumentException("不支持的资源类型");
        }
    }

    public void revokeData(Map<String, Object> payload) {
        assertAdmin();
        String scope = Objects.toString(payload.getOrDefault("scope", "TABLE")).toUpperCase();
        String targetType = Objects.toString(payload.getOrDefault("targetType", "USER")).toUpperCase();
        String targetId = text(payload, "targetId");
        String resource = text(payload, "resource");
        String permissionType = Objects.toString(payload.getOrDefault("permissionType", "READ")).toUpperCase();
        if (scope.equals("TABLE")) {
            jdbcTemplate.update("DELETE FROM is_data_permission WHERE user_id = ? AND table_name = ? AND permission_type = ?", targetId, resource, permissionType);
        } else if (scope.equals("OFFICIAL")) {
            jdbcTemplate.update("DELETE FROM is_official_table_permission WHERE principal_type = ? AND principal_id = ? AND table_name = ? AND permission_type = ?", targetType, targetId, parseOfficialTable(resource), permissionType);
        } else if (scope.equals("DASHBOARD")) {
            jdbcTemplate.update("DELETE FROM is_dashboard_permission WHERE user_id = ? AND dashboard_id = ? AND permission_type = ?", targetId, parseDashboardId(resource), permissionType);
        }
    }

    public List<Map<String, Object>> listDataGrants(String userId) {
        assertAdmin();
        String target = userId == null ? "" : userId.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (target.isBlank()) {
            rows.addAll(jdbcTemplate.queryForList("SELECT 'TABLE' AS scope, user_id AS targetId, table_name AS resource, permission_type AS permissionType, expire_at AS expireAt, created_at AS createdAt FROM is_data_permission ORDER BY created_at DESC"));
        } else {
            rows.addAll(jdbcTemplate.queryForList("SELECT 'TABLE' AS scope, user_id AS targetId, table_name AS resource, permission_type AS permissionType, expire_at AS expireAt, created_at AS createdAt FROM is_data_permission WHERE user_id = ? ORDER BY created_at DESC", target));
        }
        return rows;
    }

    public Map<String, Object> previewUser(String userId) {
        assertAdmin();
        String uid = Objects.toString(userId, "").trim();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> user = jdbcTemplate.queryForMap("SELECT user_id AS userId, username, nickname, role, status FROM is_user WHERE user_id = ? LIMIT 1", uid);
        result.put("user", user);
        result.put("roles", userRoles(uid));
        result.put("effectiveRoles", effectiveRoles(uid));
        result.put("permissions", rolePermissionsOfUser(uid));
        result.put("dataGrants", listUserGrants(uid));
        result.put("accessibleTables", permissionPreviewTables(uid));
        return result;
    }

    private List<String> userRoles(String userId) {
        List<String> roles = jdbcTemplate.queryForList("SELECT role_code AS roleCode FROM is_user_role WHERE user_id = ? ORDER BY created_at ASC", String.class, userId);
        if (roles.isEmpty()) {
            String fallback = jdbcTemplate.queryForObject("SELECT role FROM is_user WHERE user_id = ? LIMIT 1", String.class, userId);
            if (fallback != null && !fallback.isBlank()) roles.add(fallback);
        }
        return roles;
    }

    private List<String> effectiveRoles(String userId) {
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        for (String role : userRoles(userId)) {
            collectRole(role, effective, new LinkedHashSet<>());
        }
        return new ArrayList<>(effective);
    }

    private void collectRole(String role, Set<String> effective, Set<String> visiting) {
        String code = Objects.toString(role, "").trim();
        if (code.isBlank() || effective.contains(code) || visiting.contains(code)) return;
        visiting.add(code);
        effective.add(code);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT parent_role_code AS parentRoleCode FROM is_role WHERE role_code = ? AND enabled = 1 LIMIT 1", code);
        if (!rows.isEmpty()) collectRole(Objects.toString(rows.get(0).get("parentRoleCode"), ""), effective, visiting);
    }

    private List<Map<String, Object>> rolePermissionsOfUser(String userId) {
        List<String> roles = effectiveRoles(userId);
        if (roles.isEmpty()) return List.of();
        String in = String.join(",", java.util.Collections.nCopies(roles.size(), "?"));
        return jdbcTemplate.queryForList("SELECT role_code AS roleCode, permission_code AS permissionCode, permission_name AS permissionName, permission_type AS permissionType, resource_scope AS resourceScope FROM is_role_permission WHERE role_code IN (" + in + ") ORDER BY permission_type, permission_code", roles.toArray());
    }

    private List<Map<String, Object>> listUserGrants(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbcTemplate.queryForList("SELECT 'TABLE' AS scope, table_name AS resource, permission_type AS permissionType, expire_at AS expireAt FROM is_data_permission WHERE user_id = ? ORDER BY created_at DESC", userId));
        rows.addAll(jdbcTemplate.queryForList("SELECT 'OFFICIAL' AS scope, CONCAT(datasource_id, ':', table_name) AS resource, permission_type AS permissionType, expire_at AS expireAt FROM is_official_table_permission WHERE principal_type = 'USER' AND principal_id = ? ORDER BY created_at DESC", userId));
        rows.addAll(jdbcTemplate.queryForList("SELECT 'DASHBOARD' AS scope, CONCAT('dashboard:', dashboard_id) AS resource, permission_type AS permissionType, expire_at AS expireAt FROM is_dashboard_permission WHERE user_id = ? ORDER BY created_at DESC", userId));
        return rows;
    }

    private List<Map<String, Object>> permissionPreviewTables(String userId) {
        List<Map<String, Object>> tables = new ArrayList<>();
        tables.addAll(jdbcTemplate.queryForList("""
                SELECT table_name AS tableName, display_name AS displayName, owner_id AS ownerId, status, 'UPLOAD' AS sourceType
                FROM is_data_table
                WHERE status = 'ACTIVE' AND (owner_id = ? OR EXISTS (
                  SELECT 1 FROM is_data_permission p WHERE p.user_id = ? AND p.table_name = is_data_table.table_name AND p.permission_type = 'READ'
                ))
                ORDER BY created_at DESC
                """, userId, userId));
        tables.addAll(jdbcTemplate.queryForList("""
                SELECT CONCAT('official:', d.id, ':', t.table_name) AS tableName,
                       CONCAT(d.name, ' / ', COALESCE(NULLIF(t.table_comment, ''), t.table_name)) AS displayName,
                       d.name AS ownerId, d.status, 'OFFICIAL' AS sourceType
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                WHERE d.status = 'ENABLED' AND EXISTS (
                  SELECT 1 FROM is_official_table_permission p
                  WHERE p.datasource_id = d.id AND (p.table_name = t.table_name OR p.table_name = '*') AND p.principal_type = 'USER' AND p.principal_id = ? AND p.permission_type = 'READ'
                )
                ORDER BY d.created_at DESC, t.table_name ASC
                """, userId));
        return tables;
    }

    private void bindPrimaryRole(String userId, String role) {
        if (role == null || role.isBlank()) return;
        jdbcTemplate.update("DELETE FROM is_user_role WHERE user_id = ?", userId);
        jdbcTemplate.update("INSERT IGNORE INTO is_user_role(user_id, role_code, source) VALUES (?, ?, 'PRIMARY')", userId, role);
    }

    private void grantByTargetType(String targetType, String targetId, String resource, String permissionType, String expireAt, String source) {
        if (targetType.equals("ROLE")) {
            jdbcTemplate.update("INSERT IGNORE INTO is_role_permission(role_code, permission_code, permission_name, permission_type, resource_scope) VALUES (?, ?, ?, ?, ?)", targetId, resource + ':' + permissionType, resource, permissionType, resource);
        } else {
            jdbcTemplate.update("INSERT INTO is_data_permission(user_id, table_name, permission_type, source, expire_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE source = VALUES(source), expire_at = VALUES(expire_at), created_at = CURRENT_TIMESTAMP", targetId, resource, permissionType, source, expireAt);
        }
    }

    private void grantOfficial(String targetType, String targetId, String resource, String permissionType, String expireAt) {
        Long datasourceId = parseOfficialDatasourceId(resource);
        String tableName = parseOfficialTable(resource);
        jdbcTemplate.update("INSERT INTO is_official_table_permission(datasource_id, table_name, principal_type, principal_id, permission_type, expire_at) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE expire_at = VALUES(expire_at), created_at = CURRENT_TIMESTAMP", datasourceId, tableName, targetType, targetId, permissionType, expireAt);
    }

    private void grantDashboard(String targetType, String targetId, String resource, String permissionType, String expireAt) {
        jdbcTemplate.update("INSERT INTO is_dashboard_permission(dashboard_id, user_id, permission_type, source, expire_at) VALUES (?, ?, ?, 'ADMIN', ?) ON DUPLICATE KEY UPDATE expire_at = VALUES(expire_at), created_at = CURRENT_TIMESTAMP", parseDashboardId(resource), targetId, permissionType, expireAt);
    }

    private Long parseDashboardId(String resource) {
        return Long.parseLong(resource.replace("dashboard:", ""));
    }

    private Long parseOfficialDatasourceId(String resource) {
        return Long.parseLong(resource.split(":", 3)[1]);
    }

    private String parseOfficialTable(String resource) {
        return resource.split(":", 3)[2];
    }

    private List<String> rolePermissions(String roleCode) {
        return jdbcTemplate.queryForList("SELECT permission_code AS permissionCode FROM is_role_permission WHERE role_code = ?", String.class, roleCode);
    }

    private String text(Map<String, Object> payload, String key) {
        return Objects.toString(payload.get(key), "").trim();
    }

    private String optional(Object value) {
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? null : text;
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Integer toInt(Object value, Integer fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String like(String text) {
        return "%" + text + "%";
    }

    private static final class UUIDHelper {
        private static String newSalt() {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            return java.util.Base64.getEncoder().encodeToString(salt);
        }

        private static String hashPassword(String password, String salt) {
            try {
                javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(), java.util.Base64.getDecoder().decode(salt), 120000, 256);
                byte[] hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                return java.util.Base64.getEncoder().encodeToString(hash);
            } catch (Exception e) {
                throw new IllegalStateException("密码加密失败", e);
            }
        }
    }
}
