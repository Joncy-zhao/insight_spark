package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCDashboardGroupService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listAdminTree() {
        assertAdmin();
        return listTreeByOwner(null);
    }

    public List<Map<String, Object>> listUserTree(String userId) {
        requireUserId(userId);
        return listTreeByOwner(userId);
    }

    public Map<String, Object> createAdmin(Map<String, Object> body) {
        assertAdmin();
        return create(body, null);
    }

    public Map<String, Object> createUser(Map<String, Object> body, String userId) {
        requireUserId(userId);
        return create(body, userId);
    }

    public Map<String, Object> updateAdmin(long id, Map<String, Object> body) {
        assertAdmin();
        return update(id, body, null);
    }

    public Map<String, Object> updateUser(long id, Map<String, Object> body, String userId) {
        requireUserId(userId);
        return update(id, body, userId);
    }

    public void deleteAdmin(long id) {
        assertAdmin();
        delete(id, null);
    }

    public void deleteUser(long id, String userId) {
        requireUserId(userId);
        delete(id, userId);
    }

    public void assertGroupAllowedForDashboard(Long groupId, String dashboardOwnerUserId, boolean isPublic) {
        if (groupId == null || groupId <= 0) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, owner_user_id AS ownerUserId
                FROM is_dashboard_group WHERE id = ?
                """, groupId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("分组不存在");
        }
        String groupOwner = Objects.toString(rows.get(0).get("ownerUserId"), "").trim();
        if (isPublic) {
            if (!groupOwner.isBlank()) {
                throw new IllegalArgumentException("公共看板只能归入平台分组");
            }
            return;
        }
        String owner = Objects.toString(dashboardOwnerUserId, "").trim();
        if (groupOwner.isBlank() || !groupOwner.equals(owner)) {
            throw new IllegalArgumentException("个人看板只能归入本人分组");
        }
    }

    public String resolveGroupPath(long groupId) {
        if (groupId <= 0) {
            return null;
        }
        List<String> names = new ArrayList<>();
        Long current = groupId;
        int guard = 0;
        while (current != null && current > 0 && guard++ < 32) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, parent_id AS parentId, name
                    FROM is_dashboard_group WHERE id = ?
                    """, current);
            if (rows.isEmpty()) {
                break;
            }
            Map<String, Object> row = rows.get(0);
            names.add(Objects.toString(row.get("name"), ""));
            current = parseNullableLong(row.get("parentId"));
        }
        Collections.reverse(names);
        String joined = String.join(" / ", names.stream().filter(s -> !s.isBlank()).toList());
        return joined.isBlank() ? null : joined;
    }

    public String resolveGroupName(long groupId) {
        if (groupId <= 0) {
            return null;
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM is_dashboard_group WHERE id = ?", String.class, groupId);
        return names.isEmpty() ? null : names.get(0);
    }

    private List<Map<String, Object>> listTreeByOwner(String ownerUserId) {
        List<Map<String, Object>> flat;
        if (ownerUserId == null) {
            flat = jdbcTemplate.queryForList("""
                    SELECT id, parent_id AS parentId, name, sort_order AS sortOrder,
                           created_at AS createdAt, updated_at AS updatedAt
                    FROM is_dashboard_group
                    WHERE owner_user_id IS NULL
                    ORDER BY sort_order ASC, name ASC, id ASC
                    """);
        } else {
            flat = jdbcTemplate.queryForList("""
                    SELECT id, parent_id AS parentId, name, sort_order AS sortOrder,
                           created_at AS createdAt, updated_at AS updatedAt
                    FROM is_dashboard_group
                    WHERE owner_user_id = ?
                    ORDER BY sort_order ASC, name ASC, id ASC
                    """, ownerUserId);
        }
        return buildTree(flat);
    }

    private Map<String, Object> create(Map<String, Object> body, String ownerUserId) {
        String name = requireText(body, "name");
        Long parentId = parseNullableLong(body.get("parentId"));
        if (parentId != null) {
            assertGroupExists(parentId, ownerUserId);
        }
        int sortOrder = parseSortOrder(body.get("sortOrder"));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO is_dashboard_group(owner_user_id, parent_id, name, sort_order)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (ownerUserId == null) {
                ps.setNull(1, java.sql.Types.VARCHAR);
            } else {
                ps.setString(1, ownerUserId);
            }
            if (parentId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, parentId);
            }
            ps.setString(3, name);
            ps.setInt(4, sortOrder);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        return getById(id, ownerUserId);
    }

    private Map<String, Object> update(long id, Map<String, Object> body, String ownerUserId) {
        Map<String, Object> existing = getById(id, ownerUserId);
        String name = body.containsKey("name") ? requireText(body, "name") : Objects.toString(existing.get("name"));
        Long parentId = body.containsKey("parentId")
                ? parseNullableLong(body.get("parentId"))
                : parseNullableLong(existing.get("parentId"));
        if (parentId != null) {
            if (parentId == id) {
                throw new IllegalArgumentException("分组不能设为自己的父级");
            }
            assertGroupExists(parentId, ownerUserId);
            assertNotDescendant(id, parentId, ownerUserId);
        }
        int sortOrder = body.containsKey("sortOrder")
                ? parseSortOrder(body.get("sortOrder"))
                : parseSortOrder(existing.get("sortOrder"));
        jdbcTemplate.update("""
                UPDATE is_dashboard_group
                SET parent_id = ?, name = ?, sort_order = ?, updated_at = NOW()
                WHERE id = ?
                """, parentId, name, sortOrder, id);
        syncDashboardGroupNames(id, name);
        return getById(id, ownerUserId);
    }

    private void delete(long id, String ownerUserId) {
        getById(id, ownerUserId);
        Integer childCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_dashboard_group WHERE parent_id = ?", Integer.class, id);
        if (childCount != null && childCount > 0) {
            throw new IllegalArgumentException("请先删除子分组");
        }
        Integer boardCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM is_dashboard WHERE group_id = ? AND status != 'ARCHIVED'", Integer.class, id);
        if (boardCount != null && boardCount > 0) {
            throw new IllegalArgumentException("分组下仍有看板，无法删除");
        }
        jdbcTemplate.update("DELETE FROM is_dashboard_group WHERE id = ?", id);
    }

    private Map<String, Object> getById(long id, String ownerUserId) {
        List<Map<String, Object>> rows;
        if (ownerUserId == null) {
            rows = jdbcTemplate.queryForList("""
                    SELECT id, parent_id AS parentId, name, sort_order AS sortOrder,
                           created_at AS createdAt, updated_at AS updatedAt
                    FROM is_dashboard_group
                    WHERE id = ? AND owner_user_id IS NULL
                    """, id);
        } else {
            rows = jdbcTemplate.queryForList("""
                    SELECT id, parent_id AS parentId, name, sort_order AS sortOrder,
                           created_at AS createdAt, updated_at AS updatedAt
                    FROM is_dashboard_group
                    WHERE id = ? AND owner_user_id = ?
                    """, id, ownerUserId);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("分组不存在");
        }
        return rows.get(0);
    }

    private void syncDashboardGroupNames(long groupId, String name) {
        jdbcTemplate.update("""
                UPDATE is_dashboard SET group_name = ?, updated_at = NOW()
                WHERE group_id = ?
                """, name, groupId);
    }

    private List<Map<String, Object>> buildTree(List<Map<String, Object>> flat) {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : flat) {
            Map<String, Object> node = new LinkedHashMap<>(row);
            node.put("children", new ArrayList<Map<String, Object>>());
            byId.put(toLong(row.get("id")), node);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : flat) {
            long id = toLong(row.get("id"));
            Long parentId = parseNullableLong(row.get("parentId"));
            Map<String, Object> node = byId.get(id);
            if (parentId == null || parentId <= 0 || !byId.containsKey(parentId)) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) byId.get(parentId).get("children");
                children.add(node);
            }
        }
        return roots;
    }

    private void assertGroupExists(long id, String ownerUserId) {
        Integer count;
        if (ownerUserId == null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM is_dashboard_group WHERE id = ? AND owner_user_id IS NULL",
                    Integer.class, id);
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM is_dashboard_group WHERE id = ? AND owner_user_id = ?",
                    Integer.class, id, ownerUserId);
        }
        if (count == null || count == 0) {
            throw new IllegalArgumentException("父分组不存在");
        }
    }

    private void assertNotDescendant(long groupId, long candidateParentId, String ownerUserId) {
        Long current = candidateParentId;
        int guard = 0;
        while (current != null && current > 0 && guard++ < 32) {
            if (current == groupId) {
                throw new IllegalArgumentException("不能将分组移动到其子分组下");
            }
            List<Long> parents;
            if (ownerUserId == null) {
                parents = jdbcTemplate.queryForList(
                        "SELECT parent_id FROM is_dashboard_group WHERE id = ? AND owner_user_id IS NULL",
                        Long.class, current);
            } else {
                parents = jdbcTemplate.queryForList(
                        "SELECT parent_id FROM is_dashboard_group WHERE id = ? AND owner_user_id = ?",
                        Long.class, current, ownerUserId);
            }
            current = parents.isEmpty() ? null : parents.get(0);
        }
    }

    private void assertAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("仅管理员可访问");
        }
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("未登录");
        }
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        String text = String.valueOf(v).trim();
        if (text.length() > 128) {
            return text.substring(0, 128);
        }
        return text;
    }

    private static Long parseNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            long n = number.longValue();
            return n <= 0 ? null : n;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            long n = Long.parseLong(text);
            return n <= 0 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseSortOrder(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(value, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(Objects.toString(value, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
