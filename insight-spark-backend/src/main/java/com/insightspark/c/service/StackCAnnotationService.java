package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCAnnotationService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StackCDashboardService dashboardService;

    public List<Map<String, Object>> listAnnotations(String targetType, long targetId) {
        return jdbcTemplate.queryForList("""
                SELECT a.id, a.user_id AS userId, u.nickname, a.target_type AS targetType, a.target_id AS targetId,
                       a.dashboard_id AS dashboardId, a.bind_json AS bindJson, a.content, a.tag, a.created_at AS createdAt
                FROM is_annotation a
                LEFT JOIN is_user u ON u.user_id = a.user_id
                WHERE a.target_type = ? AND a.target_id = ? AND a.is_deleted = 0
                ORDER BY a.created_at ASC
                """, targetType, targetId);
    }

    public List<Map<String, Object>> listAnnotationsForDashboard(long dashboardId) {
        return listAnnotationsForDashboard(dashboardId, false);
    }

    public List<Map<String, Object>> listAnnotationsForDashboard(long dashboardId, boolean includeHidden) {
        assertCanCollaborate(dashboardId);
        String hiddenClause = includeHidden ? "" : " AND a.is_hidden = 0 ";
        return jdbcTemplate.queryForList("""
                SELECT a.id, a.user_id AS userId, u.nickname, a.target_type AS targetType, a.target_id AS targetId,
                       a.dashboard_id AS dashboardId, a.bind_json AS bindJson, a.content, a.tag,
                       a.is_hidden AS isHidden, a.created_at AS createdAt, a.updated_at AS updatedAt
                FROM is_annotation a
                LEFT JOIN is_user u ON u.user_id = a.user_id
                WHERE a.is_deleted = 0
                  AND (a.dashboard_id = ? OR (a.target_type = 'DASHBOARD' AND a.target_id = ?))
                """ + hiddenClause + """
                ORDER BY a.created_at ASC
                """, dashboardId, dashboardId);
    }

    public Map<String, Object> createAnnotation(Map<String, Object> body) {
        String targetType = requireText(body, "targetType");
        long targetId = parseLong(body.get("targetId"), "targetId");
        String content = requireText(body, "content");
        Long dashboardId = body.get("dashboardId") == null ? null : parseLong(body.get("dashboardId"), "dashboardId");
        String tag = body.get("tag") == null ? null : String.valueOf(body.get("tag"));
        String bindJson = body.get("bindJson") == null ? null : String.valueOf(body.get("bindJson"));
        long dashboardRef = resolveDashboardId(targetType, targetId, dashboardId);
        assertCanCollaborate(dashboardRef);
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_annotation(user_id, target_type, target_id, dashboard_id, bind_json, content, tag)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, uid, targetType, targetId, dashboardId, bindJson, content, tag);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return fetchAnnotationRow(id == null ? 0L : id);
    }

    public void deleteAnnotation(long id) {
        Map<String, Object> row = fetchAnnotationRow(id);
        Long dashId = row.get("dashboardId") == null ? null : parseLongQuiet(row.get("dashboardId"));
        if (dashId != null && dashId <= 0) {
            dashId = null;
        }
        assertCanCollaborate(resolveDashboardId(
                Objects.toString(row.get("targetType"), ""),
                parseLongQuiet(row.get("targetId")),
                dashId));
        if (!AuthContext.isAdmin() && !AuthContext.userId().equals(Objects.toString(row.get("userId")))) {
            throw new IllegalArgumentException("仅作者或管理员可删除");
        }
        jdbcTemplate.update("UPDATE is_annotation SET is_deleted = 1 WHERE id = ?", id);
    }

    public Map<String, Object> updateAnnotation(long id, Map<String, Object> body) {
        Map<String, Object> row = fetchAnnotationRow(id);
        Long dashId = row.get("dashboardId") == null ? null : parseLongQuiet(row.get("dashboardId"));
        if (dashId != null && dashId <= 0) {
            dashId = null;
        }
        assertCanCollaborate(resolveDashboardId(
                Objects.toString(row.get("targetType"), ""),
                parseLongQuiet(row.get("targetId")),
                dashId));
        if (!AuthContext.isAdmin() && !AuthContext.userId().equals(Objects.toString(row.get("userId")))) {
            throw new IllegalArgumentException("仅作者或管理员可编辑");
        }
        String content = body.get("content") == null ? null : String.valueOf(body.get("content")).trim();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("批注内容不能为空");
        }
        String tag = body.get("tag") == null ? null : String.valueOf(body.get("tag")).trim();
        if (tag != null && tag.isBlank()) {
            tag = null;
        }
        jdbcTemplate.update("""
                UPDATE is_annotation SET content = ?, tag = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, content, tag, id);
        return fetchAnnotationRow(id);
    }

    public Map<String, Object> setAnnotationHidden(long id, boolean hidden) {
        Map<String, Object> row = fetchAnnotationRow(id);
        Long dashId = row.get("dashboardId") == null ? null : parseLongQuiet(row.get("dashboardId"));
        if (dashId != null && dashId <= 0) {
            dashId = null;
        }
        assertCanCollaborate(resolveDashboardId(
                Objects.toString(row.get("targetType"), ""),
                parseLongQuiet(row.get("targetId")),
                dashId));
        if (!AuthContext.isAdmin() && !AuthContext.userId().equals(Objects.toString(row.get("userId")))) {
            throw new IllegalArgumentException("仅作者或管理员可隐藏批注");
        }
        jdbcTemplate.update("UPDATE is_annotation SET is_hidden = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", hidden ? 1 : 0, id);
        return fetchAnnotationRow(id);
    }

    public List<Map<String, Object>> listComments(String targetType, long targetId) {
        if ("DASHBOARD".equalsIgnoreCase(targetType)) {
            assertCanCollaborate(targetId);
        }
        return jdbcTemplate.queryForList("""
                SELECT c.id, c.parent_id AS parentId, c.user_id AS userId, u.nickname, u.username,
                       c.target_type AS targetType, c.target_id AS targetId,
                       c.content, c.mentions_json AS mentionsJson, c.created_at AS createdAt, c.updated_at AS updatedAt
                FROM is_comment c
                LEFT JOIN is_user u ON u.user_id = c.user_id
                WHERE c.target_type = ? AND c.target_id = ? AND c.is_deleted = 0
                ORDER BY c.created_at ASC
                """, targetType, targetId);
    }

    public Map<String, Object> createComment(Map<String, Object> body) {
        Long parentId = body.get("parentId") == null ? null : parseLong(body.get("parentId"), "parentId");
        String targetType = requireText(body, "targetType");
        long targetId = parseLong(body.get("targetId"), "targetId");
        String content = requireText(body, "content");
        String mentionsJson = body.get("mentionsJson") == null ? null : String.valueOf(body.get("mentionsJson"));
        if ("DASHBOARD".equalsIgnoreCase(targetType)) {
            assertCanCollaborate(targetId);
        }
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_comment(parent_id, user_id, target_type, target_id, content, mentions_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """, parentId, uid, targetType, targetId, content, mentionsJson);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return fetchCommentRow(id == null ? 0L : id);
    }

    public void deleteComment(long id) {
        Map<String, Object> row = fetchComment(id);
        if (!AuthContext.isAdmin() && !AuthContext.userId().equals(Objects.toString(row.get("userId")))) {
            throw new IllegalArgumentException("仅作者或管理员可删除");
        }
        jdbcTemplate.update("UPDATE is_comment SET is_deleted = 1 WHERE id = ?", id);
    }

    public Map<String, Object> fetchCommentRow(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.id, c.parent_id AS parentId, c.user_id AS userId, u.nickname, u.username,
                       c.target_type AS targetType, c.target_id AS targetId,
                       c.content, c.mentions_json AS mentionsJson, c.created_at AS createdAt, c.updated_at AS updatedAt
                FROM is_comment c
                LEFT JOIN is_user u ON u.user_id = c.user_id
                WHERE c.id = ? AND c.is_deleted = 0
                """, id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("评论不存在");
        }
        return rows.get(0);
    }

    public Map<String, Object> peekAnnotationMeta(long id) {
        return fetchAnnotationRow(id);
    }

    private Map<String, Object> fetchAnnotationRow(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT a.id, a.user_id AS userId, u.nickname, a.target_type AS targetType, a.target_id AS targetId,
                       a.dashboard_id AS dashboardId, a.bind_json AS bindJson, a.content, a.tag,
                       a.is_hidden AS isHidden, a.created_at AS createdAt, a.updated_at AS updatedAt
                FROM is_annotation a
                LEFT JOIN is_user u ON u.user_id = a.user_id
                WHERE a.id = ? AND a.is_deleted = 0
                """, id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批注不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> fetchAnnotation(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, user_id AS userId FROM is_annotation WHERE id = ? AND is_deleted = 0", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批注不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> fetchComment(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, user_id AS userId, target_type AS targetType, target_id AS targetId FROM is_comment WHERE id = ? AND is_deleted = 0", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("评论不存在");
        }
        return rows.get(0);
    }

    public Map<String, Object> peekCommentMeta(long id) {
        return fetchComment(id);
    }

    private void assertCanCollaborate(long dashboardId) {
        if (dashboardId <= 0) {
            throw new IllegalArgumentException("看板不存在");
        }
        dashboardService.getById(dashboardId);
    }

    private long resolveDashboardId(String targetType, long targetId, Long dashboardId) {
        if (dashboardId != null && dashboardId > 0) {
            return dashboardId;
        }
        if ("DASHBOARD".equalsIgnoreCase(targetType)) {
            return targetId;
        }
        throw new IllegalArgumentException("缺少 dashboardId");
    }

    private static long parseLongQuiet(Object v) {
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return String.valueOf(v).trim();
    }

    private static long parseLong(Object v, String key) {
        if (v == null) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须为数字");
        }
    }
}
