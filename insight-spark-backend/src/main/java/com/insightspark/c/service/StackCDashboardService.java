package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.service.ChatQueryHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
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

    @Autowired
    private ChatQueryHistoryService chatQueryHistoryService;

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
                  AND (
                    owner_user_id = ?
                    OR is_public = 1
                    OR EXISTS (
                      SELECT 1 FROM is_dashboard_permission p
                      WHERE p.dashboard_id = is_dashboard.id
                        AND p.user_id = ?
                        AND p.permission_type IN ('READ', 'EDIT')
                        AND (p.expire_at IS NULL OR p.expire_at > NOW())
                    )
                  )
                ORDER BY updated_at DESC
                LIMIT 100
                """, uid, uid);
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
        assertEditorOrAdmin(existing);
        boolean ownerOrAdmin = isOwnerOrAdmin(existing);
        String name = body.containsKey("name") ? requireText(body, "name") : Objects.toString(existing.get("name"));
        String description = body.containsKey("description") ? Objects.toString(body.get("description"), null) : Objects.toString(existing.get("description"), null);
        String layoutJson = body.containsKey("layoutJson") ? Objects.toString(body.get("layoutJson")) : Objects.toString(existing.get("layoutJson"));
        int isPublic = ownerOrAdmin && body.containsKey("isPublic") ? parseTinyInt(body.get("isPublic"), 0) : parseTinyInt(existing.get("isPublic"), 0);
        String status = ownerOrAdmin && body.containsKey("status") ? Objects.toString(body.get("status"), "ACTIVE") : Objects.toString(existing.get("status"), "ACTIVE");
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
        assertEditorOrAdmin(dashboard);

        Object chartIdRaw = body == null ? null : body.get("chartId");
        if (chartIdRaw == null) {
            throw new IllegalArgumentException("缺少 chartId：请使用最新对话查询生成图表后再钉入");
        }
        long chartId;
        if (chartIdRaw instanceof Number n) {
            chartId = n.longValue();
        } else {
            chartId = Long.parseLong(String.valueOf(chartIdRaw).trim());
        }
        chatQueryHistoryService.assertHistoryChartOwnedByCurrentUser(chartId);

        KeyHolder compKeys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO is_dashboard_component (dashboard_id, chart_id, position_config)
                            VALUES (?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, id);
            ps.setLong(2, chartId);
            ps.setString(3, "{\"x\":0,\"y\":0,\"w\":6,\"h\":4}");
            return ps;
        }, compKeys);
        Number compKey = compKeys.getKey();
        if (compKey == null) {
            throw new IllegalStateException("钉入失败：未生成看板组件记录");
        }
        long componentId = compKey.longValue();

        Map<String, Object> layout = parseLayoutJson(Objects.toString(dashboard.getOrDefault("layoutJson", "{}"), "{}"));
        List<Map<String, Object>> items = extractGridItems(layout);
        int bottom = gridLayoutBottom(items);
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("i", String.valueOf(componentId));
        cell.put("x", 0);
        cell.put("y", bottom);
        cell.put("w", 6);
        cell.put("h", 4);
        items.add(cell);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("version", "2.0");
        merged.put("items", items);
        if (layout.get("cards") instanceof List<?> legacyCards && !legacyCards.isEmpty()) {
            merged.put("cards", layout.get("cards"));
        }
        copyCanvasStyleIfPresent(layout, merged);

        String layoutJson = toJson(merged);
        jdbcTemplate.update("""
                UPDATE is_dashboard
                SET layout_json = ?, updated_at = NOW()
                WHERE id = ?
                """, layoutJson, id);
        return getById(id);
    }

    /**
     * 从看板移除钉入组件：删除 is_dashboard_component 行，并从 layout_json.items 中去掉对应 i。
     */
    public Map<String, Object> removeDashboardComponent(long dashboardId, long componentId) {
        Map<String, Object> dashboard = getById(dashboardId);
        assertEditorOrAdmin(dashboard);
        Long cnt = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_component WHERE id = ? AND dashboard_id = ?
                """, Long.class, componentId, dashboardId);
        if (cnt == null || cnt == 0) {
            throw new IllegalArgumentException("组件不存在或不属于该看板");
        }
        jdbcTemplate.update("DELETE FROM is_dashboard_component WHERE id = ? AND dashboard_id = ?",
                componentId, dashboardId);

        Map<String, Object> layout = parseLayoutJson(Objects.toString(dashboard.getOrDefault("layoutJson", "{}"), "{}"));
        List<Map<String, Object>> items = extractGridItems(layout);
        String compIdStr = String.valueOf(componentId);
        items.removeIf(it -> compIdStr.equals(String.valueOf(it.get("i"))));

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("version", "2.0");
        merged.put("items", items);
        if (layout.get("cards") instanceof List<?> legacyCards && !legacyCards.isEmpty()) {
            merged.put("cards", layout.get("cards"));
        }
        copyCanvasStyleIfPresent(layout, merged);
        String layoutJson = toJson(merged);
        jdbcTemplate.update("""
                UPDATE is_dashboard
                SET layout_json = ?, updated_at = NOW()
                WHERE id = ?
                """, layoutJson, dashboardId);
        return getById(dashboardId);
    }

    public List<Map<String, Object>> listDashboardComponents(long dashboardId) {
        Map<String, Object> dashboard = getById(dashboardId);
        assertCanAccess(dashboard);
        return jdbcTemplate.queryForList("""
                SELECT id, dashboard_id AS dashboardId, chart_id AS chartId, position_config AS positionConfig
                FROM is_dashboard_component
                WHERE dashboard_id = ?
                ORDER BY id ASC
                """, dashboardId);
    }

    /**
     * 当前用户可访问的所有看板中，已钉入的对话图表（chart_id 去重），含所在看板名称汇总。
     */
    public List<Map<String, Object>> listPinnedChartsAcrossAccessibleDashboards() {
        String uid = AuthContext.userId();
        boolean admin = AuthContext.isAdmin();
        if (admin) {
            return jdbcTemplate.queryForList("""
                    SELECT dc.chart_id AS chart_id,
                           COUNT(DISTINCT dc.dashboard_id) AS dashboard_count,
                           GROUP_CONCAT(DISTINCT d.name ORDER BY d.name SEPARATOR '、') AS dashboard_names
                    FROM is_dashboard_component dc
                    INNER JOIN is_dashboard d ON d.id = dc.dashboard_id
                    WHERE d.status != 'ARCHIVED'
                    GROUP BY dc.chart_id
                    ORDER BY MAX(dc.id) DESC
                    LIMIT 500
                    """);
        }
        // 与 listVisibleForCurrentUser 一致：非 ARCHIVED 的本人或公开看板，避免漏掉非 ACTIVE 但仍可编辑的看板
        return jdbcTemplate.queryForList("""
                SELECT dc.chart_id AS chart_id,
                       COUNT(DISTINCT dc.dashboard_id) AS dashboard_count,
                       GROUP_CONCAT(DISTINCT d.name ORDER BY d.name SEPARATOR '、') AS dashboard_names
                FROM is_dashboard_component dc
                INNER JOIN is_dashboard d ON d.id = dc.dashboard_id
                WHERE d.status != 'ARCHIVED'
                  AND (
                    d.owner_user_id = ?
                    OR d.is_public = 1
                    OR EXISTS (
                      SELECT 1 FROM is_dashboard_permission p
                      WHERE p.dashboard_id = d.id
                        AND p.user_id = ?
                        AND p.permission_type IN ('READ', 'EDIT')
                        AND (p.expire_at IS NULL OR p.expire_at > NOW())
                    )
                  )
                GROUP BY dc.chart_id
                ORDER BY MAX(dc.id) DESC
                LIMIT 300
                """, uid, uid);
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
        long dashboardId = dashboardId(row);
        if (!AuthContext.userId().equals(owner)
                && isPublic != 1
                && !hasDashboardPermission(dashboardId, "READ")
                && !hasDashboardPermission(dashboardId, "EDIT")) {
            throw new IllegalArgumentException("无权访问该看板");
        }
    }

    private void assertOwnerOrAdmin(Map<String, Object> row) {
        if (!isOwnerOrAdmin(row)) {
            throw new IllegalArgumentException("仅所有者可修改或删除");
        }
    }

    private void assertEditorOrAdmin(Map<String, Object> row) {
        if (isOwnerOrAdmin(row) || hasDashboardPermission(dashboardId(row), "EDIT")) {
            return;
        }
        throw new IllegalArgumentException("当前用户无看板编辑权限");
    }

    private boolean isOwnerOrAdmin(Map<String, Object> row) {
        return AuthContext.isAdmin() || AuthContext.userId().equals(Objects.toString(row.get("ownerUserId")));
    }

    private boolean hasDashboardPermission(long dashboardId, String permissionType) {
        if (dashboardId <= 0) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_permission
                WHERE dashboard_id = ? AND user_id = ? AND permission_type = ?
                  AND (expire_at IS NULL OR expire_at > NOW())
                """, Integer.class, dashboardId, AuthContext.userId(), permissionType);
        return count != null && count > 0;
    }

    private long dashboardId(Map<String, Object> row) {
        Object value = row.get("id");
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(Objects.toString(value, "0"));
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
    private List<Map<String, Object>> extractGridItems(Map<String, Object> layout) {
        Object raw = layout.get("items");
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> map) {
                Map<String, Object> cell = new LinkedHashMap<>();
                map.forEach((k, v) -> cell.put(String.valueOf(k), v));
                out.add(cell);
            }
        }
        return out;
    }

    private int gridLayoutBottom(List<Map<String, Object>> items) {
        int m = 0;
        for (Map<String, Object> it : items) {
            int y = layoutInt(it.get("y"), 0);
            int h = layoutInt(it.get("h"), 1);
            m = Math.max(m, y + h);
        }
        return m;
    }

    private int layoutInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(v, "").trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("布局序列化失败");
        }
    }

    /** 保留设计看板画布样式（layout_json.canvasStyle），避免钉入/移除组件时丢失 */
    @SuppressWarnings("unchecked")
    private void copyCanvasStyleIfPresent(Map<String, Object> from, Map<String, Object> to) {
        Object cs = from.get("canvasStyle");
        if (cs instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            to.put("canvasStyle", copy);
        }
    }
}
