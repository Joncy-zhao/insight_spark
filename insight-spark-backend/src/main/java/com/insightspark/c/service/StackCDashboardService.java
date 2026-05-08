package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCDashboardService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listVisibleForCurrentUser() {
        String uid = AuthContext.userId();
        boolean admin = AuthContext.isAdmin();
        if (admin) {
            return jdbcTemplate.queryForList("""
                    SELECT id, owner_user_id AS ownerUserId, name, description, layout_json AS layoutJson,
                           is_public AS isPublic, status, share_token AS shareToken, share_expire_at AS shareExpireAt,
                           created_at AS createdAt, updated_at AS updatedAt
                    FROM is_dashboard
                    WHERE status != 'ARCHIVED'
                    ORDER BY updated_at DESC
                    LIMIT 200
                    """);
        }
        return jdbcTemplate.queryForList("""
                SELECT id, owner_user_id AS ownerUserId, name, description, layout_json AS layoutJson,
                       is_public AS isPublic, status, share_token AS shareToken, share_expire_at AS shareExpireAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_dashboard
                WHERE status = 'ACTIVE'
                  AND (owner_user_id = ? OR is_public = 1)
                ORDER BY updated_at DESC
                LIMIT 100
                """, uid);
    }

    public Map<String, Object> getById(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, owner_user_id AS ownerUserId, name, description, layout_json AS layoutJson,
                       is_public AS isPublic, status, share_token AS shareToken, share_expire_at AS shareExpireAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_dashboard WHERE id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("看板不存在");
        }
        Map<String, Object> row = rows.get(0);
        assertCanAccess(row);
        return row;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String name = requireText(body, "name");
        String description = body.get("description") == null ? null : String.valueOf(body.get("description"));
        String layoutJson = Objects.toString(body.getOrDefault("layoutJson", "{}"));
        int isPublic = parseTinyInt(body.get("isPublic"), 0);
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_dashboard(owner_user_id, name, description, layout_json, is_public, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, uid, name, description, layoutJson, isPublic);
        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return getById(newId == null ? 0L : newId);
    }

    public Map<String, Object> update(long id, Map<String, Object> body) {
        Map<String, Object> existing = getById(id);
        assertOwnerOrAdmin(existing);
        String name = body.containsKey("name") ? requireText(body, "name") : Objects.toString(existing.get("name"));
        String description = body.containsKey("description") ? Objects.toString(body.get("description"), null) : Objects.toString(existing.get("description"), null);
        String layoutJson = body.containsKey("layoutJson") ? Objects.toString(body.get("layoutJson")) : Objects.toString(existing.get("layoutJson"));
        int isPublic = body.containsKey("isPublic") ? parseTinyInt(body.get("isPublic"), 0) : parseTinyInt(existing.get("isPublic"), 0);
        String status = body.containsKey("status") ? Objects.toString(body.get("status"), "ACTIVE") : Objects.toString(existing.get("status"), "ACTIVE");
        jdbcTemplate.update("""
                UPDATE is_dashboard SET name = ?, description = ?, layout_json = ?, is_public = ?, status = ?, updated_at = NOW()
                WHERE id = ?
                """, name, description, layoutJson, isPublic, status, id);
        return getById(id);
    }

    public void delete(long id) {
        Map<String, Object> existing = getById(id);
        assertOwnerOrAdmin(existing);
        jdbcTemplate.update("DELETE FROM is_dashboard WHERE id = ?", id);
    }

    private void assertCanAccess(Map<String, Object> row) {
        if (AuthContext.isAdmin()) {
            return;
        }
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(row.get("status")))) {
            throw new IllegalArgumentException("看板不可用");
        }
        String owner = Objects.toString(row.get("ownerUserId"));
        int isPublic = parseTinyInt(row.get("isPublic"), 0);
        if (!AuthContext.userId().equals(owner) && isPublic != 1) {
            throw new IllegalArgumentException("无权访问该看板");
        }
    }

    private void assertOwnerOrAdmin(Map<String, Object> row) {
        if (AuthContext.isAdmin()) {
            return;
        }
        if (!AuthContext.userId().equals(Objects.toString(row.get("ownerUserId")))) {
            throw new IllegalArgumentException("仅所有者可修改或删除");
        }
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return String.valueOf(v).trim();
    }

    private static int parseTinyInt(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0 ? 1 : 0;
        }
        return "1".equals(String.valueOf(v)) || Boolean.parseBoolean(String.valueOf(v)) ? 1 : 0;
    }
}
