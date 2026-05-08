package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Base64;

@Service
public class StackCDashboardService {

    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    public Map<String, Object> enableShare(long id, Map<String, Object> body) {
        Map<String, Object> existing = getById(id);
        assertOwnerOrAdmin(existing);
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(existing.get("status"), "ACTIVE"))) {
            throw new IllegalArgumentException("仅 ACTIVE 状态看板可开启分享");
        }

        LocalDateTime expireAt = parseExpireAt(body == null ? null : body.get("expireAt"));
        String shareToken = generateShareToken();
        jdbcTemplate.update("""
                UPDATE is_dashboard
                SET share_token = ?, share_expire_at = ?, updated_at = NOW()
                WHERE id = ?
                """, shareToken, expireAt == null ? null : Timestamp.valueOf(expireAt), id);
        return getById(id);
    }

    public Map<String, Object> disableShare(long id) {
        Map<String, Object> existing = getById(id);
        assertOwnerOrAdmin(existing);
        jdbcTemplate.update("""
                UPDATE is_dashboard
                SET share_token = NULL, share_expire_at = NULL, updated_at = NOW()
                WHERE id = ?
                """, id);
        return getById(id);
    }

    public Map<String, Object> getByShareToken(String shareToken) {
        String token = Objects.toString(shareToken, "").trim();
        if (token.isBlank()) {
            throw new IllegalArgumentException("分享链接无效");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, owner_user_id AS ownerUserId, name, description, layout_json AS layoutJson,
                       is_public AS isPublic, status, share_token AS shareToken, share_expire_at AS shareExpireAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM is_dashboard
                WHERE share_token = ?
                LIMIT 1
                """, token);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("分享链接不存在或已失效");
        }
        Map<String, Object> row = rows.get(0);
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(row.get("status"), "ACTIVE"))) {
            throw new IllegalArgumentException("分享看板当前不可用");
        }
        LocalDateTime expireAt = parseRowDateTime(row.get("shareExpireAt"));
        if (expireAt != null && expireAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("分享链接已过期");
        }
        return row;
    }

    public Map<String, Object> pinChart(long id, Map<String, Object> body) {
        Map<String, Object> dashboard = getById(id);
        assertOwnerOrAdmin(dashboard);

        Map<String, Object> chartCard = buildChartCard(body);
        Map<String, Object> layout = parseLayoutJson(Objects.toString(dashboard.getOrDefault("layoutJson", "{}"), "{}"));
        List<Map<String, Object>> cards = ensureCardList(layout);
        cards.add(chartCard);
        layout.put("cards", cards);
        layout.putIfAbsent("version", "1.0");

        String layoutJson = toJson(layout);
        jdbcTemplate.update("""
                UPDATE is_dashboard
                SET layout_json = ?, updated_at = NOW()
                WHERE id = ?
                """, layoutJson, id);
        return getById(id);
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

    private String generateShareToken() {
        for (int i = 0; i < 8; i++) {
            byte[] bytes = new byte[18];
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM is_dashboard WHERE share_token = ?",
                    Integer.class,
                    token
            );
            if (exists == null || exists == 0) {
                return token;
            }
        }
        throw new IllegalStateException("生成分享 token 失败，请重试");
    }

    private LocalDateTime parseExpireAt(Object expireAt) {
        if (expireAt == null) {
            return null;
        }
        String text = String.valueOf(expireAt).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            if (text.contains("T")) {
                return LocalDateTime.parse(text.replace("Z", ""));
            }
            return LocalDateTime.parse(text, DATETIME_FORMAT);
        } catch (Exception e) {
            throw new IllegalArgumentException("过期时间格式错误，应为 yyyy-MM-dd HH:mm:ss 或 ISO-8601");
        }
    }

    private LocalDateTime parseRowDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.replace(" ", "T"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> buildChartCard(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("缺少图表数据");
        }
        String title = Objects.toString(body.getOrDefault("title", "新图表卡片"), "").trim();
        if (title.isBlank()) {
            title = "新图表卡片";
        }
        String chartType = Objects.toString(body.getOrDefault("chartType", "bar"), "bar");
        String tableName = Objects.toString(body.getOrDefault("tableName", ""), "");
        String sql = Objects.toString(body.getOrDefault("sql", ""), "");
        Object data = body.get("data");
        if (!(data instanceof List<?>)) {
            throw new IllegalArgumentException("图表数据格式错误");
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardId", "card_" + System.currentTimeMillis());
        card.put("title", title);
        card.put("type", "chart");
        card.put("chartType", chartType);
        card.put("tableName", tableName);
        card.put("sql", sql);
        card.put("fieldMapping", body.getOrDefault("fieldMapping", Map.of()));
        card.put("data", data);
        card.put("createdAt", LocalDateTime.now().toString());
        return card;
    }

    private Map<String, Object> parseLayoutJson(String layoutJson) {
        String text = Objects.toString(layoutJson, "").trim();
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ensureCardList(Map<String, Object> layout) {
        Object cardsRaw = layout.get("cards");
        if (cardsRaw instanceof List<?> list) {
            List<Map<String, Object>> cards = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> card = new LinkedHashMap<>();
                    map.forEach((k, v) -> card.put(String.valueOf(k), v));
                    cards.add(card);
                }
            }
            return cards;
        }
        return new ArrayList<>();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("布局序列化失败");
        }
    }
}
