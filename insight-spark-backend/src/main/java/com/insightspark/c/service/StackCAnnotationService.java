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

    public List<Map<String, Object>> listAnnotations(String targetType, long targetId) {
        return jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, target_type AS targetType, target_id AS targetId, dashboard_id AS dashboardId,
                       bind_json AS bindJson, content, tag, created_at AS createdAt
                FROM is_annotation
                WHERE target_type = ? AND target_id = ? AND is_deleted = 0
                ORDER BY created_at ASC
                """, targetType, targetId);
    }

    public Map<String, Object> createAnnotation(Map<String, Object> body) {
        String targetType = requireText(body, "targetType");
        long targetId = parseLong(body.get("targetId"), "targetId");
        String content = requireText(body, "content");
        Long dashboardId = body.get("dashboardId") == null ? null : parseLong(body.get("dashboardId"), "dashboardId");
        String tag = body.get("tag") == null ? null : String.valueOf(body.get("tag"));
        String bindJson = body.get("bindJson") == null ? null : String.valueOf(body.get("bindJson"));
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_annotation(user_id, target_type, target_id, dashboard_id, bind_json, content, tag)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, uid, targetType, targetId, dashboardId, bindJson, content, tag);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("id", id == null ? 0L : id);
    }

    public void deleteAnnotation(long id) {
        Map<String, Object> row = fetchAnnotation(id);
        if (!AuthContext.isAdmin() && !AuthContext.userId().equals(Objects.toString(row.get("userId")))) {
            throw new IllegalArgumentException("仅作者或管理员可删除");
        }
        jdbcTemplate.update("UPDATE is_annotation SET is_deleted = 1 WHERE id = ?", id);
    }

    public List<Map<String, Object>> listComments(String targetType, long targetId) {
        return jdbcTemplate.queryForList("""
                SELECT id, parent_id AS parentId, user_id AS userId, target_type AS targetType, target_id AS targetId,
                       content, mentions_json AS mentionsJson, created_at AS createdAt
                FROM is_comment
                WHERE target_type = ? AND target_id = ? AND is_deleted = 0
                ORDER BY created_at ASC
                """, targetType, targetId);
    }

    public Map<String, Object> createComment(Map<String, Object> body) {
        Long parentId = body.get("parentId") == null ? null : parseLong(body.get("parentId"), "parentId");
        String targetType = requireText(body, "targetType");
        long targetId = parseLong(body.get("targetId"), "targetId");
        String content = requireText(body, "content");
        String mentionsJson = body.get("mentionsJson") == null ? null : String.valueOf(body.get("mentionsJson"));
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_comment(parent_id, user_id, target_type, target_id, content, mentions_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """, parentId, uid, targetType, targetId, content, mentionsJson);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("id", id == null ? 0L : id);
    }

    public void deleteComment(long id) {
        Map<String, Object> row = fetchComment(id);
        if (!AuthContext.isAdmin() && !AuthContext.userId().equals(Objects.toString(row.get("userId")))) {
            throw new IllegalArgumentException("仅作者或管理员可删除");
        }
        jdbcTemplate.update("UPDATE is_comment SET is_deleted = 1 WHERE id = ?", id);
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
                "SELECT id, user_id AS userId FROM is_comment WHERE id = ? AND is_deleted = 0", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("评论不存在");
        }
        return rows.get(0);
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
