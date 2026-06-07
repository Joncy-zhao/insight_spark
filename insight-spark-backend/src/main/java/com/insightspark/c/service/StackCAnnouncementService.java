package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCAnnouncementService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listForCurrentUser() {
        String role = AuthContext.role();
        return jdbcTemplate.queryForList("""
                SELECT id, title, content, audience, pinned, priority, publish_status AS publishStatus,
                       published_at AS publishedAt, expire_at AS expireAt, created_by AS createdBy, created_at AS createdAt
                FROM is_system_announcement
                WHERE publish_status = 'PUBLISHED'
                  AND (expire_at IS NULL OR expire_at > NOW())
                  AND (
                    audience = 'ALL'
                    OR (audience = 'USER' AND (? = 'USER' OR ? = 'ADMIN'))
                    OR (audience = 'ADMIN' AND ? = 'ADMIN')
                  )
                ORDER BY pinned DESC, priority DESC, COALESCE(published_at, created_at) DESC
                LIMIT 50
                """, role, role, role);
    }

    public List<Map<String, Object>> listForAdmin() {
        return jdbcTemplate.queryForList("""
                SELECT id, title, content, audience, pinned, priority, publish_status AS publishStatus,
                       published_at AS publishedAt, expire_at AS expireAt, created_by AS createdBy, created_at AS createdAt
                FROM is_system_announcement
                ORDER BY pinned DESC, priority DESC, COALESCE(published_at, created_at) DESC
                LIMIT 100
                """);
    }

    public Map<String, Object> createAnnouncement(Map<String, Object> body) {
        String title = requireText(body, "title");
        String content = requireText(body, "content");
        String audience = Objects.toString(body.getOrDefault("audience", "ALL")).toUpperCase();
        if (!List.of("ALL", "USER", "ADMIN").contains(audience)) {
            throw new IllegalArgumentException("audience 只能是 ALL / USER / ADMIN");
        }
        int pinned = body.get("pinned") instanceof Number n ? n.intValue() : (Boolean.TRUE.equals(body.get("pinned")) ? 1 : 0);
        int priority = body.get("priority") instanceof Number n ? n.intValue() : 0;
        String publishStatus = Objects.toString(body.getOrDefault("publishStatus", "PUBLISHED")).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO is_system_announcement(title, content, audience, pinned, priority, publish_status, published_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)
                """, title, content, audience, pinned, priority, publishStatus, AuthContext.userId());
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("id", id == null ? 0L : id);
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return String.valueOf(v).trim();
    }
}
