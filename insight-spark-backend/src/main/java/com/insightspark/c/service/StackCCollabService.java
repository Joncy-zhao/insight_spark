package com.insightspark.c.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCCollabService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StackCDashboardService dashboardService;

    @Autowired
    private StackCAnnotationService annotationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> getSummary(long dashboardId) {
        Map<String, Object> dashboard = dashboardService.getById(dashboardId);
        String uid = AuthContext.userId();
        Integer following = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_follow
                WHERE dashboard_id = ? AND user_id = ?
                """, Integer.class, dashboardId, uid);
        Integer followerCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_follow WHERE dashboard_id = ?
                """, Integer.class, dashboardId);
        List<Map<String, Object>> annotations = annotationService.listAnnotationsForDashboard(dashboardId);
        List<Map<String, Object>> comments = annotationService.listComments("DASHBOARD", dashboardId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dashboard", Map.of(
                "id", dashboard.get("id"),
                "name", dashboard.get("name"),
                "ownerUserId", dashboard.get("ownerUserId"),
                "isPublic", dashboard.get("isPublic"),
                "status", dashboard.get("status")
        ));
        summary.put("following", following != null && following > 0);
        summary.put("followerCount", followerCount == null ? 0 : followerCount);
        summary.put("annotationCount", annotations.size());
        summary.put("commentCount", comments.size());
        summary.put("nodes", extractLayoutNodes(dashboard));
        summary.put("canManageTeam", canManageTeam(dashboard));
        summary.put("teamPermissions", listTeamPermissions(dashboardId));
        return summary;
    }

    public boolean isFollowing(long dashboardId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_follow
                WHERE dashboard_id = ? AND user_id = ?
                """, Integer.class, dashboardId, AuthContext.userId());
        return count != null && count > 0;
    }

    public Map<String, Object> follow(long dashboardId) {
        dashboardService.getById(dashboardId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO is_dashboard_follow(dashboard_id, user_id) VALUES (?, ?)
                """, dashboardId, AuthContext.userId());
        return Map.of("following", true);
    }

    public Map<String, Object> unfollow(long dashboardId) {
        jdbcTemplate.update("""
                DELETE FROM is_dashboard_follow WHERE dashboard_id = ? AND user_id = ?
                """, dashboardId, AuthContext.userId());
        return Map.of("following", false);
    }

    public List<Map<String, Object>> listMentionCandidates(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isBlank()) {
            return jdbcTemplate.queryForList("""
                    SELECT user_id AS userId, username, nickname
                    FROM is_user
                    WHERE status = 'ACTIVE'
                    ORDER BY nickname ASC
                    LIMIT 30
                    """);
        }
        String like = "%" + kw + "%";
        return jdbcTemplate.queryForList("""
                SELECT user_id AS userId, username, nickname
                FROM is_user
                WHERE status = 'ACTIVE'
                  AND (username LIKE ? OR nickname LIKE ? OR user_id LIKE ?)
                ORDER BY nickname ASC
                LIMIT 30
                """, like, like, like);
    }

    public List<Map<String, Object>> listTeamPermissions(long dashboardId) {
        return jdbcTemplate.queryForList("""
                SELECT p.id, p.user_id AS userId, u.nickname, u.username,
                       p.permission_type AS permissionType, p.source, p.expire_at AS expireAt, p.created_at AS createdAt
                FROM is_dashboard_permission p
                LEFT JOIN is_user u ON u.user_id = p.user_id
                WHERE p.dashboard_id = ?
                ORDER BY p.created_at DESC
                """, dashboardId);
    }

    public Map<String, Object> grantTeamPermission(long dashboardId, Map<String, Object> body) {
        Map<String, Object> dashboard = dashboardService.getById(dashboardId);
        if (!canManageTeam(dashboard)) {
            throw new IllegalArgumentException("仅看板所有者或管理员可分配协作权限");
        }
        String userId = requireText(body, "userId");
        String permissionType = Objects.toString(body.getOrDefault("permissionType", "READ"), "READ").trim().toUpperCase();
        if (!"READ".equals(permissionType) && !"EDIT".equals(permissionType)) {
            throw new IllegalArgumentException("permissionType 仅支持 READ / EDIT");
        }
        jdbcTemplate.update("""
                INSERT INTO is_dashboard_permission(dashboard_id, user_id, permission_type, source, expire_at)
                VALUES (?, ?, ?, 'COLLAB', NULL)
                ON DUPLICATE KEY UPDATE source = VALUES(source), expire_at = VALUES(expire_at)
                """, dashboardId, userId, permissionType);
        return Map.of("ok", true);
    }

    public void revokeTeamPermission(long dashboardId, String userId, String permissionType) {
        Map<String, Object> dashboard = dashboardService.getById(dashboardId);
        if (!canManageTeam(dashboard)) {
            throw new IllegalArgumentException("仅看板所有者或管理员可撤销协作权限");
        }
        jdbcTemplate.update("""
                DELETE FROM is_dashboard_permission
                WHERE dashboard_id = ? AND user_id = ? AND permission_type = ?
                """, dashboardId, userId, permissionType);
    }

    public String buildMarkdownReport(long dashboardId) {
        Map<String, Object> dashboard = dashboardService.getById(dashboardId);
        List<Map<String, Object>> annotations = annotationService.listAnnotationsForDashboard(dashboardId);
        List<Map<String, Object>> comments = annotationService.listComments("DASHBOARD", dashboardId);
        List<Map<String, Object>> nodes = extractLayoutNodes(dashboard);
        Map<String, String> nodeLabels = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            nodeLabels.put(node.get("targetType") + ":" + node.get("targetId"), Objects.toString(node.get("label"), ""));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 看板协作汇报 · ").append(dashboard.get("name")).append("\n\n");
        sb.append("- 看板 ID：").append(dashboardId).append("\n");
        sb.append("- 所有者：").append(dashboard.get("ownerUserId")).append("\n");
        sb.append("- 批注数：").append(annotations.size()).append("\n");
        sb.append("- 评论数：").append(comments.size()).append("\n");
        sb.append("- 导出时间：").append(java.time.LocalDateTime.now()).append("\n\n");

        sb.append("## 批注\n\n");
        if (annotations.isEmpty()) {
            sb.append("_暂无批注_\n\n");
        } else {
            for (Map<String, Object> ann : annotations) {
                String key = ann.get("targetType") + ":" + ann.get("targetId");
                String nodeLabel = nodeLabels.getOrDefault(key, "整板");
                sb.append("### ").append(displayUser(ann)).append(" · ").append(nodeLabel).append("\n");
                if (ann.get("tag") != null && !String.valueOf(ann.get("tag")).isBlank()) {
                    sb.append("标签：`").append(ann.get("tag")).append("`\n\n");
                }
                if (ann.get("bindJson") != null && !String.valueOf(ann.get("bindJson")).isBlank()) {
                    sb.append("绑定维度：```json\n").append(ann.get("bindJson")).append("\n```\n\n");
                }
                sb.append(String.valueOf(ann.get("content"))).append("\n\n");
                sb.append("_").append(ann.get("createdAt")).append("_\n\n");
            }
        }

        sb.append("## 评论\n\n");
        if (comments.isEmpty()) {
            sb.append("_暂无评论_\n\n");
        } else {
            for (Map<String, Object> c : comments) {
                sb.append("- **").append(displayUser(c)).append("** (").append(c.get("createdAt")).append(")：");
                sb.append(String.valueOf(c.get("content"))).append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractLayoutNodes(Map<String, Object> dashboard) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(Map.of(
                "targetType", "DASHBOARD",
                "targetId", dashboard.get("id"),
                "label", "整板",
                "kind", "dashboard"
        ));
        String layoutJson = Objects.toString(dashboard.get("layoutJson"), "{}");
        try {
            Map<String, Object> layout = objectMapper.readValue(layoutJson, new TypeReference<>() {
            });
            Object items = layout.get("items");
            if (items instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    raw.forEach((k, v) -> item.put(String.valueOf(k), v));
                    Object id = item.get("i");
                    if (id == null) {
                        continue;
                    }
                    String kind = Objects.toString(item.get("kind"), Objects.toString(item.get("type"), "widget"));
                    String label = Objects.toString(item.get("title"), "").trim();
                    if (label.isBlank()) {
                        label = layoutItemFallbackLabel(kind, id);
                    }
                    nodes.add(Map.of(
                            "targetType", "COMPONENT",
                            "targetId", String.valueOf(id),
                            "label", label,
                            "kind", kind
                    ));
                }
            }
        } catch (Exception ignored) {
            // layout parse failure is non-fatal
        }
        long dashboardId = Long.parseLong(String.valueOf(dashboard.get("id")));
        try {
            for (Map<String, Object> comp : dashboardService.listDashboardComponents(dashboardId)) {
                Object compId = comp.get("id");
                if (compId == null) {
                    continue;
                }
                String chartType = Objects.toString(comp.get("artifactChartType"), "chart");
                nodes.add(Map.of(
                        "targetType", "COMPONENT",
                        "targetId", String.valueOf(compId),
                        "label", "图表组件 #" + compId + " (" + chartType + ")",
                        "kind", "chart"
                ));
            }
        } catch (Exception ignored) {
            // access check may fail for some callers; summary still works for board-level
        }
        return nodes;
    }

    private static String layoutItemFallbackLabel(String kind, Object id) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        String suffix = id == null ? "" : String.valueOf(id);
        return switch (k) {
            case "video" -> "视频组件";
            case "text" -> "文本组件";
            case "image" -> "图片组件";
            case "link" -> "链接组件";
            case "chart" -> "图表组件" + (suffix.isBlank() ? "" : " · " + suffix);
            case "dashboard" -> "整板";
            default -> {
                if (suffix.startsWith("bw-")) {
                    int dash = suffix.indexOf('-', 3);
                    String sub = dash > 0 ? suffix.substring(3, dash) : suffix.substring(3);
                    yield switch (sub) {
                        case "video" -> "视频组件";
                        case "text" -> "文本组件";
                        case "image" -> "图片组件";
                        case "link" -> "链接组件";
                        default -> "基础组件 · " + suffix;
                    };
                }
                yield (kind.isBlank() ? "组件" : kind) + (suffix.isBlank() ? "" : " · " + suffix);
            }
        };
    }

    private boolean canManageTeam(Map<String, Object> dashboard) {
        if (AuthContext.isAdmin()) {
            return true;
        }
        return AuthContext.userId().equals(Objects.toString(dashboard.get("ownerUserId")));
    }

    private static String displayUser(Map<String, Object> row) {
        String nickname = Objects.toString(row.get("nickname"), "").trim();
        if (!nickname.isBlank()) {
            return nickname;
        }
        return Objects.toString(row.get("userId"), "用户");
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return String.valueOf(v).trim();
    }
}
