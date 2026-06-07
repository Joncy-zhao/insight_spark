package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightspark.service.ChatConversationService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Base64;

@Service
public class StackCDashboardService {

    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatQueryHistoryService chatQueryHistoryService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private StackCDashboardGroupService dashboardGroupService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DASHBOARD_BASE_COLUMNS = """
            id, owner_user_id AS ownerUserId,
            COALESCE(NULLIF(TRIM(author_user_id), ''), owner_user_id) AS authorUserId,
            source_dashboard_id AS sourceDashboardId, save_as_user_id AS saveAsUserId,
            publisher_user_id AS publisherUserId,
            name, description, group_id AS groupId, group_name AS groupName,
            layout_json AS layoutJson, is_public AS isPublic, status,
            share_token AS shareToken, share_expire_at AS shareExpireAt,
            view_count AS viewCount,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    private static final String DASHBOARD_MINE_LIST_COLUMNS = """
            d.id, d.owner_user_id AS ownerUserId,
            COALESCE(NULLIF(TRIM(d.author_user_id), ''), d.owner_user_id) AS authorUserId,
            au.username AS authorUsername, au.nickname AS authorNickname,
            d.source_dashboard_id AS sourceDashboardId,
            d.save_as_user_id AS saveAsUserId, su.username AS saveAsUsername, su.nickname AS saveAsNickname,
            d.publisher_user_id AS publisherUserId, pu.username AS publisherUsername, pu.nickname AS publisherNickname,
            d.name, d.description, d.group_id AS groupId, d.group_name AS groupName, d.layout_json AS layoutJson,
            d.is_public AS isPublic, d.status, d.share_token AS shareToken, d.share_expire_at AS shareExpireAt,
            d.view_count AS viewCount, d.created_at AS createdAt, d.updated_at AS updatedAt
            """;

    private static final String DASHBOARD_ADMIN_COLUMNS = """
            d.id, d.owner_user_id AS ownerUserId,
            COALESCE(NULLIF(TRIM(d.author_user_id), ''), d.owner_user_id) AS authorUserId,
            au.username AS authorUsername, au.nickname AS authorNickname,
            d.source_dashboard_id AS sourceDashboardId,
            d.save_as_user_id AS saveAsUserId, su.username AS saveAsUsername, su.nickname AS saveAsNickname,
            d.publisher_user_id AS publisherUserId, pu.username AS publisherUsername, pu.nickname AS publisherNickname,
            d.name, d.description, d.group_id AS groupId, d.group_name AS groupName, d.layout_json AS layoutJson,
            d.is_public AS isPublic, d.status, d.share_token AS shareToken, d.share_expire_at AS shareExpireAt,
            d.view_count AS viewCount, d.created_at AS createdAt, d.updated_at AS updatedAt
            """;

    private static final String DASHBOARD_USER_JOINS = """
            LEFT JOIN is_user au ON au.user_id = COALESCE(NULLIF(TRIM(d.author_user_id), ''), d.owner_user_id)
            LEFT JOIN is_user su ON su.user_id = d.save_as_user_id
            LEFT JOIN is_user pu ON pu.user_id = d.publisher_user_id
            """;

    public List<Map<String, Object>> listVisibleForCurrentUser() {
        String uid = AuthContext.userId();
        boolean admin = AuthContext.isAdmin();
        if (admin) {
            return jdbcTemplate.queryForList("""
                    SELECT %s
                    FROM is_dashboard
                    WHERE status != 'ARCHIVED'
                    ORDER BY updated_at DESC
                    LIMIT 200
                    """.formatted(DASHBOARD_BASE_COLUMNS));
        }
        return jdbcTemplate.queryForList("""
                SELECT %s
                FROM is_dashboard
                """.formatted(DASHBOARD_BASE_COLUMNS) + """
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
                    OR EXISTS (
                      SELECT 1 FROM is_dashboard_team_permission dp
                      INNER JOIN is_team_member tm ON tm.team_id = dp.team_id AND tm.user_id = ?
                      WHERE dp.dashboard_id = is_dashboard.id
                        AND dp.permission_type IN ('READ', 'EDIT')
                    )
                  )
                ORDER BY updated_at DESC
                LIMIT 100
                """, uid, uid, uid);
    }

    public List<Map<String, Object>> listEditableForCurrentUser() {
        List<Map<String, Object>> visible = listVisibleForCurrentUser();
        List<Map<String, Object>> editable = new ArrayList<>();
        for (Map<String, Object> row : visible) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            boolean canManage = canManageDashboard(item);
            item.put("canManage", canManage);
            if (canManage) {
                editable.add(item);
            }
        }
        return editable;
    }

    public Map<String, Object> smartPinChart(String question, Map<String, Object> source) {
        Map<String, Object> target = source == null ? Map.of() : source;
        Long artifactId = toLong(target.get("artifactId"));
        Long turnId = toLong(target.get("turnId"));
        Long chartId = toLong(target.get("historyId"));
        if ((chartId == null || chartId <= 0) && (artifactId == null || artifactId <= 0) && (turnId == null || turnId <= 0)) {
            throw new IllegalArgumentException("当前会话暂无可钉入的图表结果，请先完成一次图表查询");
        }

        List<Map<String, Object>> dashboards = listEditableForCurrentUser();
        if (dashboards.isEmpty()) {
            Map<String, Object> result = dashboardPinDraft(
                    "暂无可编辑看板，请先到“我的看板”创建或申请编辑权限",
                    "NO_EDITABLE_DASHBOARD",
                    null,
                    dashboards,
                    true
            );
            result.put("source", target);
            return result;
        }

        DashboardMatch match = resolveDashboardMatch(question, dashboards);
        if (match.status() != DashboardMatchStatus.UNIQUE || match.dashboard() == null) {
            String message = match.status() == DashboardMatchStatus.AMBIGUOUS
                    ? "已识别为钉入看板，但目标看板不唯一，请选择后确认"
                    : "已识别为钉入看板，请选择目标看板后确认";
            Map<String, Object> result = dashboardPinDraft(
                    message,
                    match.status().name(),
                    match.keyword(),
                    match.candidates(),
                    true
            );
            result.put("source", target);
            return result;
        }

        Map<String, Object> dashboard = match.dashboard();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (artifactId != null && artifactId > 0) {
            payload.put("artifactId", artifactId);
        } else if (turnId != null && turnId > 0) {
            payload.put("turnId", turnId);
        } else {
            payload.put("chartId", chartId);
        }
        payload.put("title", firstText(
                target.get("title"),
                target.get("sourceQuestion"),
                target.get("message"),
                "图表卡片"
        ));
        Map<String, Object> pinnedDashboard = pinChart(dashboardId(dashboard), payload);
        Map<String, Object> result = dashboardPinDraft(
                "已将当前图表钉入「" + Objects.toString(dashboard.get("name"), "目标看板") + "」",
                "PINNED",
                match.keyword(),
                List.of(dashboard),
                false
        );
        result.put("dashboardId", dashboard.get("id"));
        result.put("dashboardName", dashboard.get("name"));
        result.put("source", target);
        result.put("pinnedDashboard", pinnedDashboard);
        result.put("pinPayload", payload);
        return result;
    }

    public Map<String, Object> listForAdmin(
            String keyword,
            Integer isPublic,
            String status,
            String groupName,
            Long groupId,
            int page,
            int pageSize) {
        assertAdmin();
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safePageSize;

        StringBuilder where = new StringBuilder(" WHERE d.status != 'ARCHIVED' ");
        List<Object> args = new ArrayList<>();
        appendAdminListFilters(where, args, keyword, isPublic, status, groupName, groupId);

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM is_dashboard d
                """ + DASHBOARD_USER_JOINS + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT %s
                FROM is_dashboard d
                """.formatted(DASHBOARD_ADMIN_COLUMNS) + DASHBOARD_USER_JOINS + where + """
                ORDER BY d.updated_at DESC
                LIMIT ? OFFSET ?
                """, queryArgs.toArray());
        List<Map<String, Object>> enriched = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("canManage", canManageDashboard(row));
            enrichAdminGroupDisplay(row);
            enriched.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", enriched);
        result.put("total", totalCount);
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        return result;
    }

    public Map<String, Object> statsForAdmin() {
        assertAdmin();
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT
                  COUNT(*) AS totalCount,
                  SUM(CASE WHEN d.is_public = 1 THEN 1 ELSE 0 END) AS publicCount,
                  SUM(CASE WHEN d.is_public = 0 THEN 1 ELSE 0 END) AS privateCount,
                  COALESCE(SUM(d.view_count), 0) AS totalViews
                FROM is_dashboard d
                WHERE d.status != 'ARCHIVED'
                """);
        List<Map<String, Object>> topByViews = jdbcTemplate.queryForList("""
                SELECT d.id, d.name, d.view_count AS viewCount, d.is_public AS isPublic
                FROM is_dashboard d
                WHERE d.status != 'ARCHIVED'
                ORDER BY d.view_count DESC, d.updated_at DESC
                LIMIT 5
                """);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", toLong(totals.get("totalCount")));
        result.put("publicCount", toLong(totals.get("publicCount")));
        result.put("privateCount", toLong(totals.get("privateCount")));
        result.put("totalViews", toLong(totals.get("totalViews")));
        result.put("topByViews", topByViews);
        return result;
    }

    private void enrichAdminGroupDisplay(Map<String, Object> row) {
        enrichGroupDisplayForViewer(row);
    }

    /** 分组仅对所有者可见；他人查看公共看板时不暴露分组归属 */
    private void enrichGroupDisplayForViewer(Map<String, Object> row) {
        if (!isOwner(row)) {
            row.put("groupIsPrivate", true);
            row.remove("groupPath");
            row.remove("groupName");
            return;
        }
        Long gid = parseNullableLong(row.get("groupId"));
        if (gid == null || gid <= 0) {
            return;
        }
        if (dashboardGroupService.isPlatformGroup(gid)) {
            String path = dashboardGroupService.resolveGroupPath(gid);
            if (path != null && !path.isBlank()) {
                row.put("groupPath", path);
            }
            return;
        }
        row.put("groupIsPrivate", true);
        row.remove("groupPath");
        row.remove("groupName");
    }

    public Map<String, Object> listForCurrentUserPrivate(
            String keyword, Long groupId, Integer isPublic, String status, int page, int pageSize) {
        String uid = AuthContext.userId();
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("未登录");
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safePageSize;

        final boolean publicScope = groupId != null && groupId == -2L;
        final boolean admin = AuthContext.isAdmin();
        StringBuilder where;
        List<Object> args = new ArrayList<>();
        if (publicScope) {
            where = new StringBuilder("""
                     WHERE d.is_public = 1
                       AND d.status = 'ACTIVE'
                    """);
        } else if (admin) {
            where = new StringBuilder("""
                     WHERE d.status != 'ARCHIVED'
                    """);
        } else {
            final boolean allScope = groupId == null;
            if (allScope) {
                where = new StringBuilder("""
                         WHERE d.status != 'ARCHIVED'
                           AND (d.owner_user_id = ?
                                OR (d.is_public = 1 AND d.status = 'ACTIVE'))
                        """);
                args.add(uid);
            } else {
                where = new StringBuilder("""
                         WHERE d.owner_user_id = ?
                           AND d.status != 'ARCHIVED'
                        """);
                args.add(uid);
            }
        }

        String kw = Objects.toString(keyword, "").trim();
        if (!kw.isBlank()) {
            if (admin && !publicScope) {
                where.append("""
                         AND (
                           d.name LIKE ?
                           OR d.author_user_id LIKE ?
                           OR au.username LIKE ?
                           OR au.nickname LIKE ?
                         )
                        """);
                String like = "%" + kw + "%";
                args.add(like);
                args.add(like);
                args.add(like);
                args.add(like);
            } else {
                where.append(" AND d.name LIKE ? ");
                args.add("%" + kw + "%");
            }
        }
        if (groupId != null && !publicScope) {
            if (groupId < 0) {
                where.append(" AND d.group_id IS NULL ");
            } else {
                where.append(" AND d.group_id = ? ");
                args.add(groupId);
            }
        }
        if (isPublic != null && !publicScope) {
            where.append(" AND d.is_public = ? ");
            args.add(isPublic);
        }
        String statusFilter = Objects.toString(status, "").trim().toUpperCase();
        if (!publicScope && !statusFilter.isBlank() && !"ALL".equals(statusFilter)) {
            if ("ACTIVE".equals(statusFilter) || "DISABLED".equals(statusFilter)) {
                where.append(" AND d.status = ? ");
                args.add(statusFilter);
            }
        }

        Long total = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM is_dashboard d
                """ + DASHBOARD_USER_JOINS + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(safePageSize);
        queryArgs.add(offset);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT %s
                FROM is_dashboard d
                """.formatted(DASHBOARD_MINE_LIST_COLUMNS) + DASHBOARD_USER_JOINS + where + """
                ORDER BY d.updated_at DESC
                LIMIT ? OFFSET ?
                """, queryArgs.toArray());

        List<Map<String, Object>> enriched = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("canManage", canManageDashboard(row));
            enrichGroupDisplayForViewer(row);
            enriched.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", enriched);
        result.put("total", totalCount);
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        return result;
    }

    public List<String> listAdminDashboardGroups() {
        assertAdmin();
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT TRIM(group_name) AS groupName
                FROM is_dashboard
                WHERE status != 'ARCHIVED'
                  AND group_name IS NOT NULL
                  AND TRIM(group_name) != ''
                ORDER BY groupName
                """, String.class);
    }

    public Map<String, Object> getById(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT %s
                FROM is_dashboard d
                """.formatted(DASHBOARD_MINE_LIST_COLUMNS) + DASHBOARD_USER_JOINS + """
                WHERE d.id = ?
                LIMIT 1
                """, id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("\u770b\u677f\u4e0d\u5b58\u5728");
        }
        Map<String, Object> row = rows.get(0);
        assertCanAccess(row);
        row.put("canManage", canManageDashboard(row));
        enrichGroupDisplayForViewer(row);
        return row;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String name = requireText(body, "name");
        String description = body.get("description") == null ? null : String.valueOf(body.get("description"));
        String layoutJson = Objects.toString(body.getOrDefault("layoutJson", "{}"));
        String uid = AuthContext.userId();
        int isPublic = parseTinyInt(body.get("isPublic"), 0);
        Long groupId = parseNullableLong(body.get("groupId"));
        dashboardGroupService.assertGroupAllowedForDashboard(groupId, uid, isPublic == 1);
        String groupName = resolveGroupNameForWrite(groupId, body.get("groupName"));
        String status;
        if (body.containsKey("status")) {
            status = Objects.toString(body.get("status"), AuthContext.isAdmin() ? "ACTIVE" : "DISABLED").trim().toUpperCase();
        } else {
            status = AuthContext.isAdmin() ? "ACTIVE" : "DISABLED";
        }
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            status = AuthContext.isAdmin() ? "ACTIVE" : "DISABLED";
        }
        String publisherUserId = "ACTIVE".equals(status) ? uid : null;
        jdbcTemplate.update("""
                INSERT INTO is_dashboard(owner_user_id, author_user_id, save_as_user_id, publisher_user_id, name, description, group_id, group_name, layout_json, is_public, status)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                """, uid, uid, publisherUserId, name, description, groupId, groupName, layoutJson, isPublic, status);
        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return getById(newId == null ? 0L : newId);
    }

    /** 管理员在看板管理页新建看板：默认私密、待发布，开放类型在保存布局时决定。 */
    public Map<String, Object> createForAdmin(Map<String, Object> body) {
        assertAdmin();
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        if (!payload.containsKey("isPublic")) {
            payload.put("isPublic", false);
        }
        if (!payload.containsKey("status")) {
            payload.put("status", "DISABLED");
        }
        return create(payload);
    }

    public Map<String, Object> update(long id, Map<String, Object> body) {
        Map<String, Object> existing = getById(id);
        assertCanMutateDashboard(existing);
        boolean canManage = canManageDashboard(existing);
        String name = body.containsKey("name") ? requireText(body, "name") : Objects.toString(existing.get("name"));
        String description = body.containsKey("description") ? Objects.toString(body.get("description"), null) : Objects.toString(existing.get("description"), null);
        String layoutJson = body.containsKey("layoutJson") ? Objects.toString(body.get("layoutJson")) : Objects.toString(existing.get("layoutJson"));
        int isPublic = canManage && body.containsKey("isPublic") ? parseTinyInt(body.get("isPublic"), 0) : parseTinyInt(existing.get("isPublic"), 0);
        String status = canManage && body.containsKey("status") ? Objects.toString(body.get("status"), "ACTIVE") : Objects.toString(existing.get("status"), "ACTIVE");
        Long groupId = canManage && body.containsKey("groupId")
                ? parseNullableLong(body.get("groupId"))
                : parseNullableLong(existing.get("groupId"));
        String groupName = canManage
                ? resolveGroupNameForWrite(groupId, body.containsKey("groupName") ? body.get("groupName") : existing.get("groupName"))
                : normalizeGroupName(existing.get("groupName"));
        if (!canManage) {
            groupId = parseNullableLong(existing.get("groupId"));
            isPublic = parseTinyInt(existing.get("isPublic"), 0);
        }
        if (canManage) {
            dashboardGroupService.assertGroupAllowedForDashboard(
                    groupId,
                    Objects.toString(existing.get("ownerUserId"), AuthContext.userId()),
                    isPublic == 1);
        }
        if (canManage && !isOwner(existing) && AuthContext.isAdmin()) {
            String existingStatus = Objects.toString(existing.get("status"), "DISABLED").trim().toUpperCase();
            if (body.containsKey("status")
                    && "DISABLED".equals(Objects.toString(body.get("status"), "").trim().toUpperCase())
                    && "ACTIVE".equals(existingStatus)) {
                name = Objects.toString(existing.get("name"));
                description = Objects.toString(existing.get("description"), null);
                layoutJson = Objects.toString(existing.get("layoutJson"));
                groupId = parseNullableLong(existing.get("groupId"));
                groupName = normalizeGroupName(existing.get("groupName"));
                isPublic = parseTinyInt(existing.get("isPublic"), 0);
                status = "DISABLED";
            } else {
                throw new IllegalArgumentException("他人看板不可编辑，请使用另存为后操作");
            }
        }
        status = status == null ? "ACTIVE" : status.trim().toUpperCase();
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status) && !"ARCHIVED".equals(status)) {
            status = Objects.toString(existing.get("status"), "ACTIVE");
        }
        String publisherUserId = resolvePublisherOnUpdate(existing, status);
        if ("DISABLED".equalsIgnoreCase(status)) {
            jdbcTemplate.update("""
                    UPDATE is_dashboard SET name = ?, description = ?, group_id = ?, group_name = ?, layout_json = ?, is_public = ?, status = ?, publisher_user_id = ?, share_token = NULL, share_expire_at = NULL, updated_at = NOW()
                    WHERE id = ?
                    """, name, description, groupId, groupName, layoutJson, isPublic, status, publisherUserId, id);
        } else {
            jdbcTemplate.update("""
                    UPDATE is_dashboard SET name = ?, description = ?, group_id = ?, group_name = ?, layout_json = ?, is_public = ?, status = ?, publisher_user_id = ?, updated_at = NOW()
                    WHERE id = ?
                    """, name, description, groupId, groupName, layoutJson, isPublic, status, publisherUserId, id);
        }
        return getById(id);
    }

    /**
     * 将公共看板另存为当前用户的个人看板（可携带编辑后的 layout_json）。
     */
    public Map<String, Object> duplicateForCurrentUser(long sourceId, Map<String, Object> body) {
        Map<String, Object> source = getById(sourceId);
        String uid = AuthContext.userId();
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("\u672a\u767b\u5f55");
        }
        if (isOwner(source) && !isPublicDashboard(source)) {
            throw new IllegalArgumentException("\u5df2\u662f\u60a8\u7684\u770b\u677f\uff0c\u8bf7\u76f4\u63a5\u4fdd\u5b58");
        }
        if (!isPublicDashboard(source)) {
            throw new IllegalArgumentException("\u4ec5\u53ef\u53e6\u5b58\u5df2\u53d1\u5e03\u7684\u516c\u5171\u770b\u677f");
        }
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(source.get("status"), ""))) {
            throw new IllegalArgumentException("\u4ec5\u53ef\u53e6\u5b58\u5df2\u53d1\u5e03\u7684\u516c\u5171\u770b\u677f");
        }

        String name = body != null && body.containsKey("name")
                ? requireText(body, "name")
                : Objects.toString(source.get("name"), "\u770b\u677f") + " \u526f\u672c";
        String description = body != null && body.containsKey("description")
                ? Objects.toString(body.get("description"), null)
                : Objects.toString(source.get("description"), null);
        String layoutJson = body != null && body.containsKey("layoutJson")
                ? Objects.toString(body.get("layoutJson"))
                : Objects.toString(source.get("layoutJson"), "{}");
        Long groupId = body != null && body.containsKey("groupId")
                ? parseNullableLong(body.get("groupId"))
                : null;
        boolean publish = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("publish", "false")));
        String status = publish ? "ACTIVE" : "DISABLED";
        String authorUserId = resolveAuthorUserId(source);
        String publisherUserId = publish ? uid : null;
        boolean isPublic = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("isPublic", "false")));

        dashboardGroupService.assertGroupAllowedForDashboard(groupId, uid, isPublic);
        String groupName = resolveGroupNameForWrite(groupId, body == null ? null : body.get("groupName"));

        Map<String, Object> layout = parseLayoutJson(layoutJson);
        List<Map<String, Object>> layoutItems = extractGridItems(layout);
        Set<String> referencedComponentIds = new HashSet<>();
        for (Map<String, Object> item : layoutItems) {
            String itemId = Objects.toString(item.get("i"), "").trim();
            if (itemId.matches("\\d+")) {
                referencedComponentIds.add(itemId);
            }
        }

        jdbcTemplate.update("""
                INSERT INTO is_dashboard(owner_user_id, author_user_id, source_dashboard_id, save_as_user_id, publisher_user_id, name, description, group_id, group_name, layout_json, is_public, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, ?)
                """, uid, authorUserId, sourceId, uid, publisherUserId, name, description, groupId, groupName, isPublic ? 1 : 0, status);
        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (newId == null || newId <= 0) {
            throw new IllegalStateException("\u53e6\u5b58\u5931\u8d25");
        }

        List<Map<String, Object>> sourceComponents = jdbcTemplate.queryForList("""
                SELECT id, chart_id AS chartId, artifact_id AS artifactId, turn_id AS turnId, position_config AS positionConfig
                FROM is_dashboard_component
                WHERE dashboard_id = ?
                ORDER BY id ASC
                """, sourceId);

        Map<String, String> componentIdMap = new LinkedHashMap<>();
        for (Map<String, Object> comp : sourceComponents) {
            String oldId = Objects.toString(comp.get("id"), "").trim();
            if (!referencedComponentIds.isEmpty() && !referencedComponentIds.contains(oldId)) {
                continue;
            }
            long newComponentId = insertDashboardComponentRow(newId, comp);
            componentIdMap.put(oldId, String.valueOf(newComponentId));
        }

        for (Map<String, Object> item : layoutItems) {
            String itemId = Objects.toString(item.get("i"), "").trim();
            if (componentIdMap.containsKey(itemId)) {
                item.put("i", componentIdMap.get(itemId));
            }
        }
        layout.put("items", layoutItems);
        String finalLayoutJson = serializeLayoutJson(layout);
        jdbcTemplate.update("UPDATE is_dashboard SET layout_json = ? WHERE id = ?", finalLayoutJson, newId);
        return getById(newId);
    }

    /**
     * 管理员将已发布公共看板另存为副本，可归入平台分组（可携带编辑后的 layout_json）。
     */
    public Map<String, Object> duplicateForAdmin(long sourceId, Map<String, Object> body) {
        assertAdmin();
        Map<String, Object> source = getById(sourceId);
        String uid = AuthContext.userId();
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("\u672a\u767b\u5f55");
        }
        if (!isPublicDashboard(source)) {
            throw new IllegalArgumentException("\u4ec5\u53ef\u53e6\u5b58\u5df2\u53d1\u5e03\u7684\u516c\u5171\u770b\u677f");
        }
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(source.get("status"), ""))) {
            throw new IllegalArgumentException("\u4ec5\u53ef\u53e6\u5b58\u5df2\u53d1\u5e03\u7684\u516c\u5171\u770b\u677f");
        }

        String name = body != null && body.containsKey("name")
                ? requireText(body, "name")
                : Objects.toString(source.get("name"), "\u770b\u677f") + " \u526f\u672c";
        String description = body != null && body.containsKey("description")
                ? Objects.toString(body.get("description"), null)
                : Objects.toString(source.get("description"), null);
        String layoutJson = body != null && body.containsKey("layoutJson")
                ? Objects.toString(body.get("layoutJson"))
                : Objects.toString(source.get("layoutJson"), "{}");
        Long groupId = body != null && body.containsKey("groupId")
                ? parseNullableLong(body.get("groupId"))
                : null;
        boolean publish = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("publish", "false")));
        String status = publish ? "ACTIVE" : "DISABLED";
        String authorUserId = resolveAuthorUserId(source);
        String publisherUserId = publish ? uid : null;
        boolean isPublic = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("isPublic", "true")));

        dashboardGroupService.assertGroupAllowedForDashboard(groupId, uid, isPublic);
        String groupName = resolveGroupNameForWrite(groupId, body == null ? null : body.get("groupName"));

        Map<String, Object> layout = parseLayoutJson(layoutJson);
        List<Map<String, Object>> layoutItems = extractGridItems(layout);
        Set<String> referencedComponentIds = new HashSet<>();
        for (Map<String, Object> item : layoutItems) {
            String itemId = Objects.toString(item.get("i"), "").trim();
            if (itemId.matches("\\d+")) {
                referencedComponentIds.add(itemId);
            }
        }

        jdbcTemplate.update("""
                INSERT INTO is_dashboard(owner_user_id, author_user_id, source_dashboard_id, save_as_user_id, publisher_user_id, name, description, group_id, group_name, layout_json, is_public, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, ?)
                """, uid, authorUserId, sourceId, uid, publisherUserId, name, description, groupId, groupName, isPublic ? 1 : 0, status);
        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (newId == null || newId <= 0) {
            throw new IllegalStateException("\u53e6\u5b58\u5931\u8d25");
        }

        List<Map<String, Object>> sourceComponents = jdbcTemplate.queryForList("""
                SELECT id, chart_id AS chartId, artifact_id AS artifactId, turn_id AS turnId, position_config AS positionConfig
                FROM is_dashboard_component
                WHERE dashboard_id = ?
                ORDER BY id ASC
                """, sourceId);

        Map<String, String> componentIdMap = new LinkedHashMap<>();
        for (Map<String, Object> comp : sourceComponents) {
            String oldId = Objects.toString(comp.get("id"), "").trim();
            if (!referencedComponentIds.isEmpty() && !referencedComponentIds.contains(oldId)) {
                continue;
            }
            long newComponentId = insertDashboardComponentRow(newId, comp);
            componentIdMap.put(oldId, String.valueOf(newComponentId));
        }

        for (Map<String, Object> item : layoutItems) {
            String itemId = Objects.toString(item.get("i"), "").trim();
            if (componentIdMap.containsKey(itemId)) {
                item.put("i", componentIdMap.get(itemId));
            }
        }
        layout.put("items", layoutItems);
        String finalLayoutJson = serializeLayoutJson(layout);
        jdbcTemplate.update("UPDATE is_dashboard SET layout_json = ? WHERE id = ?", finalLayoutJson, newId);
        return getById(newId);
    }

    private long insertDashboardComponentRow(long dashboardId, Map<String, Object> comp) {
        KeyHolder compKeys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO is_dashboard_component (dashboard_id, chart_id, artifact_id, turn_id, position_config)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dashboardId);
            ps.setLong(2, layoutLong(comp.get("chartId"), 0L));
            Long artifactId = parseNullableLong(comp.get("artifactId"));
            if (artifactId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, artifactId);
            }
            Long turnId = parseNullableLong(comp.get("turnId"));
            if (turnId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, turnId);
            }
            ps.setString(5, Objects.toString(comp.get("positionConfig"), "{\"x\":0,\"y\":0,\"w\":6,\"h\":4}"));
            return ps;
        }, compKeys);
        Number compKey = compKeys.getKey();
        if (compKey == null) {
            throw new IllegalStateException("\u590d\u5236\u7ec4\u4ef6\u5931\u8d25");
        }
        return compKey.longValue();
    }

    private String serializeLayoutJson(Map<String, Object> layout) {
        try {
            return objectMapper.writeValueAsString(layout);
        } catch (Exception e) {
            throw new IllegalStateException("\u5e03\u5c40\u5e8f\u5217\u5316\u5931\u8d25");
        }
    }

    private long layoutLong(Object value, long def) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(Objects.toString(value, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
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
            throw new IllegalArgumentException("\u4ec5ACTIVE\u72b6\u6001\u770b\u677f\u53ef\u5f00\u542f\u5206\u4eab");
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

    public void recordView(long id) {
        Map<String, Object> row = getById(id);
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(row.get("status"), "ACTIVE"))) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE is_dashboard
                SET view_count = view_count + 1, updated_at = updated_at
                WHERE id = ?
                """, id);
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
            throw new IllegalArgumentException("\u5206\u4eab\u94fe\u63a5\u65e0\u6548");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT %s
                FROM is_dashboard
                WHERE share_token = ?
                LIMIT 1
                """.formatted(DASHBOARD_BASE_COLUMNS), token);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("\u5206\u4eab\u94fe\u63a5\u4e0d\u5b58\u5728\u6216\u5df2\u5931\u6548");
        }
        Map<String, Object> row = rows.get(0);
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(row.get("status"), "ACTIVE"))) {
            throw new IllegalArgumentException("\u5206\u4eab\u770b\u677f\u5f53\u524d\u4e0d\u53ef\u7528");
        }
        LocalDateTime expireAt = parseRowDateTime(row.get("shareExpireAt"));
        if (expireAt != null && expireAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("\u5206\u4eab\u94fe\u63a5\u5df2\u8fc7\u671f");
        }
        long dashboardId = ((Number) row.get("id")).longValue();
        List<Map<String, Object>> components = queryDashboardComponents(dashboardId);
        List<Long> chartIds = new ArrayList<>();
        for (Map<String, Object> component : components) {
            Object rawChartId = component.get("chartId");
            if (rawChartId instanceof Number n && n.longValue() > 0) {
                chartIds.add(n.longValue());
            }
        }
        List<Map<String, Object>> charts = chatQueryHistoryService.batchChartSnapshotsForSharedDashboard(chartIds, dashboardId);
        Map<String, Object> chartPayloadById = new LinkedHashMap<>();
        for (Map<String, Object> chart : charts) {
            Object id = chart.get("id");
            if (id != null) {
                chartPayloadById.put(String.valueOf(id), chart);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>(row);
        payload.put("components", components);
        payload.put("chartPayloadById", chartPayloadById);
        return payload;
    }

    public Map<String, Object> pinChart(long id, Map<String, Object> body) {
        Map<String, Object> dashboard = getById(id);
        assertCanMutateDashboard(dashboard);

        PinnedTarget target = resolvePinnedTarget(body);
        Long existingComponentId = existingPinnedComponentId(id, target);
        if (existingComponentId != null) {
            return getById(id);
        }
        KeyHolder compKeys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO is_dashboard_component (dashboard_id, chart_id, artifact_id, turn_id, position_config)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, id);
            ps.setLong(2, target.chartId());
            if (target.artifactId() == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, target.artifactId());
            }
            if (target.turnId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, target.turnId());
            }
            ps.setString(5, "{\"x\":0,\"y\":0,\"w\":6,\"h\":4}");
            return ps;
        }, compKeys);
        Number compKey = compKeys.getKey();
        if (compKey == null) {
            throw new IllegalStateException("\u9489\u5165\u5931\u8d25\uff1a\u672a\u751f\u6210\u770b\u677f\u7ec4\u4ef6\u8bb0\u5f55");
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
        String title = resolvePinnedTitle(body, target);
        if (!title.isBlank()) {
            cell.put("title", title);
        }
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

    private Long existingPinnedComponentId(long dashboardId, PinnedTarget target) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id
                FROM is_dashboard_component
                WHERE dashboard_id = ?
                  AND chart_id = ?
                  AND ((artifact_id IS NULL AND ? IS NULL) OR artifact_id = ?)
                  AND ((turn_id IS NULL AND ? IS NULL) OR turn_id = ?)
                ORDER BY id ASC
                LIMIT 1
                """,
                dashboardId,
                target.chartId(),
                target.artifactId(),
                target.artifactId(),
                target.turnId(),
                target.turnId());
        if (rows.isEmpty()) {
            return null;
        }
        return toLong(rows.get(0).get("id"));
    }

    /**
     * 从看板移除钉入组件：删除 is_dashboard_component 行，并从 layout_json.items 中去掉对�?i�?
     */
    public Map<String, Object> removeDashboardComponent(long dashboardId, long componentId) {
        Map<String, Object> dashboard = getById(dashboardId);
        assertCanMutateDashboard(dashboard);
        Long cnt = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_component WHERE id = ? AND dashboard_id = ?
                """, Long.class, componentId, dashboardId);
        if (cnt == null || cnt == 0) {
            throw new IllegalArgumentException("\u7ec4\u4ef6\u4e0d\u5b58\u5728\u6216\u4e0d\u5c5e\u4e8e\u8be5\u770b\u677f");
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
        return queryDashboardComponents(dashboardId);
    }

    private List<Map<String, Object>> queryDashboardComponents(long dashboardId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT dc.id, dc.dashboard_id AS dashboardId, dc.chart_id AS chartId,
                       dc.artifact_id AS artifactId, dc.turn_id AS turnId,
                       dc.position_config AS positionConfig,
                       a.artifact_type AS artifactType, a.artifact_json AS artifactJson,
                       a.chart_type AS artifactChartType, a.risk_level AS artifactRiskLevel
                FROM is_dashboard_component dc
                LEFT JOIN is_chat_conversation_artifact a ON a.id = dc.artifact_id
                WHERE dc.dashboard_id = ?
                ORDER BY dc.id ASC
                """, dashboardId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("artifact", parseLayoutJson(Objects.toString(row.get("artifactJson"), "{}")));
            item.remove("artifactJson");
            result.add(item);
        }
        return result;
    }

    /**
     * 当前用户可访问的所有看板中，已钉入的对话图表（chart_id 去重），含所在看板名称汇总�?
     */
    public List<Map<String, Object>> listPinnedChartsAcrossAccessibleDashboards() {
        String uid = AuthContext.userId();
        boolean admin = AuthContext.isAdmin();
        if (admin) {
            return jdbcTemplate.queryForList("""
                    SELECT dc.chart_id AS chart_id,
                           COUNT(DISTINCT dc.dashboard_id) AS dashboard_count,
                           GROUP_CONCAT(DISTINCT d.name ORDER BY d.name SEPARATOR ', ') AS dashboard_names
                    FROM is_dashboard_component dc
                    INNER JOIN is_dashboard d ON d.id = dc.dashboard_id
                    WHERE d.status != 'ARCHIVED'
                      AND dc.chart_id > 0
                    GROUP BY dc.chart_id
                    ORDER BY MAX(dc.id) DESC
                    LIMIT 500
                    """);
        }
        // �?listVisibleForCurrentUser 一致：�?ARCHIVED 的本人或公开看板，避免漏掉非 ACTIVE 但仍可编辑的看板
        return jdbcTemplate.queryForList("""
                SELECT dc.chart_id AS chart_id,
                       COUNT(DISTINCT dc.dashboard_id) AS dashboard_count,
                       GROUP_CONCAT(DISTINCT d.name ORDER BY d.name SEPARATOR ', ') AS dashboard_names
                FROM is_dashboard_component dc
                INNER JOIN is_dashboard d ON d.id = dc.dashboard_id
                WHERE d.status != 'ARCHIVED'
                  AND dc.chart_id > 0
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
        String owner = Objects.toString(row.get("ownerUserId"));
        boolean isOwner = AuthContext.userId().equals(owner);
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(row.get("status")))) {
            if (!isOwner) {
                throw new IllegalArgumentException("\u770b\u677f\u4e0d\u53ef\u7528");
            }
        }
        int isPublic = parseTinyInt(row.get("isPublic"), 0);
        long dashboardId = dashboardId(row);
        if (!isOwner
                && isPublic != 1
                && !hasDashboardPermission(dashboardId, "READ")
                && !hasDashboardPermission(dashboardId, "EDIT")
                && !hasTeamDashboardAccess(dashboardId, "READ")
                && !hasTeamDashboardAccess(dashboardId, "EDIT")) {
            throw new IllegalArgumentException("\u65e0\u6743\u8bbf\u95ee\u8be5\u770b\u677f");
        }
    }

    private void assertOwnerOrAdmin(Map<String, Object> row) {
        if (!canManageDashboard(row)) {
            throw new IllegalArgumentException("\u4ec5\u6240\u6709\u8005\u53ef\u4fee\u6539\u6216\u5220\u9664");
        }
    }

    private void assertEditorOrAdmin(Map<String, Object> row) {
        if (!canManageDashboard(row)) {
            throw new IllegalArgumentException("\u5f53\u524d\u7528\u6237\u65e0\u770b\u677f\u7f16\u8f91\u6743\u9650");
        }
    }

    /** 公共看板仅所有者可改；他人须先另存为私密看板 */
    private void assertCanMutateDashboard(Map<String, Object> row) {
        if (isPublicDashboard(row) && !isOwner(row) && !AuthContext.isAdmin()) {
            throw new IllegalArgumentException("\u4ed6\u4eba\u516c\u5171\u770b\u677f\u4e0d\u53ef\u76f4\u63a5\u4fee\u6539\uff0c\u8bf7\u53e6\u5b58\u4e3a\u79c1\u5bc6\u770b\u677f\u540e\u518d\u7f16\u8f91");
        }
        assertEditorOrAdmin(row);
    }

    private boolean isOwner(Map<String, Object> row) {
        return AuthContext.userId().equals(Objects.toString(row.get("ownerUserId")));
    }

    /** 原作者：副本继承来源看板的 author，原生看板为 owner */
    private String resolveAuthorUserId(Map<String, Object> row) {
        String author = Objects.toString(row.get("authorUserId"), "").trim();
        if (!author.isBlank()) {
            return author;
        }
        return Objects.toString(row.get("ownerUserId"), "").trim();
    }

    /** 发布者：执行发布（ACTIVE）时锁定，取消发布清空 */
    private String resolvePublisherOnUpdate(Map<String, Object> existing, String newStatus) {
        String existingStatus = Objects.toString(existing.get("status"), "DISABLED").trim().toUpperCase();
        String status = Objects.toString(newStatus, "DISABLED").trim().toUpperCase();
        if ("DISABLED".equals(status)) {
            return null;
        }
        if ("ACTIVE".equals(status) && !"ACTIVE".equals(existingStatus)) {
            return AuthContext.userId();
        }
        String pub = Objects.toString(existing.get("publisherUserId"), "").trim();
        return pub.isBlank() ? null : pub;
    }

    private boolean isPublicDashboard(Map<String, Object> row) {
        return parseTinyInt(row.get("isPublic"), 0) == 1;
    }

    /**
     * 可管理：本人看板；公共看板仅创建者（管理员可代管）；他人私有看板须被授予 EDIT。
     */
    private boolean canManageDashboard(Map<String, Object> row) {
        if (isOwner(row)) {
            return true;
        }
        if (isPublicDashboard(row)) {
            return AuthContext.isAdmin();
        }
        if (AuthContext.isAdmin()) {
            return false;
        }
        long id = dashboardId(row);
        return hasDashboardPermission(id, "EDIT") || hasTeamDashboardAccess(id, "EDIT");
    }

    private boolean isOwnerOrAdmin(Map<String, Object> row) {
        return canManageDashboard(row);
    }

    private boolean hasTeamDashboardAccess(long dashboardId, String permissionType) {
        String uid = AuthContext.userId();
        if ("EDIT".equals(permissionType)) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM is_dashboard_team_permission dp
                    INNER JOIN is_team_member tm ON tm.team_id = dp.team_id AND tm.user_id = ?
                    WHERE dp.dashboard_id = ? AND dp.permission_type = 'EDIT'
                    """, Integer.class, uid, dashboardId);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_team_permission dp
                INNER JOIN is_team_member tm ON tm.team_id = dp.team_id AND tm.user_id = ?
                WHERE dp.dashboard_id = ? AND dp.permission_type IN ('READ', 'EDIT')
                """, Integer.class, uid, dashboardId);
        return count != null && count > 0;
    }

    private boolean hasDashboardPermission(long dashboardId, String permissionType) {
        if (dashboardId <= 0) {
            return false;
        }
        String uid = AuthContext.userId();
        if ("READ".equals(permissionType)) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM is_dashboard_permission
                    WHERE dashboard_id = ? AND user_id = ?
                      AND permission_type IN ('READ', 'EDIT')
                      AND (expire_at IS NULL OR expire_at > NOW())
                    """, Integer.class, dashboardId, uid);
            if (count != null && count > 0) {
                return true;
            }
            return hasTeamDashboardAccess(dashboardId, "READ");
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_dashboard_permission
                WHERE dashboard_id = ? AND user_id = ? AND permission_type = ?
                  AND (expire_at IS NULL OR expire_at > NOW())
                """, Integer.class, dashboardId, uid, permissionType);
        if (count != null && count > 0) {
            return true;
        }
        return hasTeamDashboardAccess(dashboardId, permissionType);
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

    private void assertAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("\u4ec5\u7ba1\u7406\u5458\u53ef\u8bbf\u95ee");
        }
    }

    private void appendAdminListFilters(
            StringBuilder where,
            List<Object> args,
            String keyword,
            Integer isPublic,
            String status,
            String groupName,
            Long groupId) {
        String kw = Objects.toString(keyword, "").trim();
        if (!kw.isBlank()) {
            where.append("""
                     AND (
                       d.name LIKE ?
                       OR d.author_user_id LIKE ?
                       OR au.username LIKE ?
                       OR au.nickname LIKE ?
                     )
                    """);
            String like = "%" + kw + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (isPublic != null) {
            where.append(" AND d.is_public = ? ");
            args.add(isPublic);
        }
        String statusText = Objects.toString(status, "").trim();
        if (!statusText.isBlank() && !"ALL".equalsIgnoreCase(statusText)) {
            where.append(" AND d.status = ? ");
            args.add(statusText.toUpperCase());
        }
        if (groupId != null) {
            if (groupId < 0) {
                where.append(" AND d.group_id IS NULL ");
            } else {
                where.append(" AND d.group_id = ? ");
                args.add(groupId);
            }
        } else {
            String group = Objects.toString(groupName, "").trim();
            if (!group.isBlank() && !"ALL".equalsIgnoreCase(group)) {
                if ("__UNASSIGNED__".equals(group)) {
                    where.append(" AND d.group_id IS NULL AND (d.group_name IS NULL OR TRIM(d.group_name) = '') ");
                } else {
                    where.append(" AND d.group_name = ? ");
                    args.add(group);
                }
            }
        }
    }

    private String resolveGroupNameForWrite(Long groupId, Object legacyGroupName) {
        if (groupId != null && groupId > 0) {
            String fromTree = dashboardGroupService.resolveGroupName(groupId);
            if (fromTree != null && !fromTree.isBlank()) {
                return fromTree;
            }
        }
        return normalizeGroupName(legacyGroupName);
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

    private static String normalizeGroupName(Object value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        return text.length() > 128 ? text.substring(0, 128) : text;
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("\u7f3a\u5c11\u5fc5\u586b\u9879\uff1a" + key);
        }
        return String.valueOf(v).trim();
    }

    private Map<String, Object> dashboardPinDraft(String message,
                                                  String status,
                                                  String keyword,
                                                  List<Map<String, Object>> candidates,
                                                  boolean requiresConfirmation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", "DASHBOARD_PIN");
        result.put("message", message);
        result.put("chartType", "table");
        result.put("data", List.of(Map.of(
                "name", "DASHBOARD_PIN",
                "value", message
        )));
        result.put("dimensions", List.of("name", "value"));
        result.put("handled", !requiresConfirmation);
        result.put("dashboardActionStatus", status);
        result.put("dashboardKeyword", Objects.toString(keyword, ""));
        result.put("dashboardCandidates", simplifyDashboards(candidates));
        result.put("requiresConfirmation", requiresConfirmation);
        result.put("smartRouted", true);
        result.put("skipChartArtifact", true);
        return result;
    }

    private List<Map<String, Object>> simplifyDashboards(List<Map<String, Object>> dashboards) {
        if (dashboards == null || dashboards.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> dashboard : dashboards) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", dashboard.get("id"));
            item.put("name", dashboard.get("name"));
            item.put("isPublic", parseTinyInt(dashboard.get("isPublic"), 0) == 1);
            item.put("canManage", true);
            Object groupName = firstPresent(dashboard.get("groupPath"), dashboard.get("groupName"));
            if (groupName != null) {
                item.put("groupName", groupName);
            }
            result.add(item);
        }
        return result;
    }

    private DashboardMatch resolveDashboardMatch(String question, List<Map<String, Object>> dashboards) {
        if (dashboards == null || dashboards.isEmpty()) {
            return new DashboardMatch(DashboardMatchStatus.NONE, "", null, List.of());
        }
        String keyword = extractDashboardKeyword(question);
        String normalizedKeyword = normalizeDashboardName(keyword);
        if (normalizedKeyword.isBlank()) {
            if (dashboards.size() == 1) {
                return new DashboardMatch(DashboardMatchStatus.UNIQUE, keyword, dashboards.get(0), dashboards);
            }
            return new DashboardMatch(DashboardMatchStatus.MISSING_TARGET, keyword, null, dashboards);
        }
        if (isWeakDashboardKeyword(keyword, normalizedKeyword)) {
            return new DashboardMatch(DashboardMatchStatus.MISSING_TARGET, keyword, null, dashboards);
        }
        List<Map<String, Object>> scored = new ArrayList<>();
        int bestScore = 0;
        for (Map<String, Object> dashboard : dashboards) {
            int score = dashboardMatchScore(question, normalizedKeyword, dashboard);
            if (score > 0) {
                Map<String, Object> item = new LinkedHashMap<>(dashboard);
                item.put("matchScore", score);
                scored.add(item);
                bestScore = Math.max(bestScore, score);
            }
        }
        if (scored.isEmpty()) {
            if (dashboards.size() == 1) {
                return new DashboardMatch(DashboardMatchStatus.UNIQUE, keyword, dashboards.get(0), dashboards);
            }
            return new DashboardMatch(DashboardMatchStatus.NOT_FOUND, keyword, null, dashboards);
        }
        int threshold = Math.max(1, bestScore - 8);
        List<Map<String, Object>> candidates = scored.stream()
                .filter(item -> layoutInt(item.get("matchScore"), 0) >= threshold)
                .sorted(Comparator.comparingInt((Map<String, Object> item) -> layoutInt(item.get("matchScore"), 0)).reversed())
                .toList();
        if (candidates.size() == 1) {
            return new DashboardMatch(DashboardMatchStatus.UNIQUE, keyword, candidates.get(0), candidates);
        }
        List<Map<String, Object>> exact = candidates.stream()
                .filter(item -> dashboardMatchScore(question, normalizedKeyword, item) >= 100)
                .toList();
        if (exact.size() == 1) {
            return new DashboardMatch(DashboardMatchStatus.UNIQUE, keyword, exact.get(0), exact);
        }
        return new DashboardMatch(DashboardMatchStatus.AMBIGUOUS, keyword, null, candidates);
    }

    private int dashboardMatchScore(String question, String normalizedKeyword, Map<String, Object> dashboard) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return 0;
        }
        String rawName = Objects.toString(dashboard.get("name"), "").trim();
        String name = normalizeDashboardName(rawName);
        String group = normalizeDashboardName(firstPresent(dashboard.get("groupPath"), dashboard.get("groupName")));
        String q = normalizeDashboardName(question);
        int score = 0;
        if (!name.isBlank()) {
            if (name.equals(normalizedKeyword)) {
                score = Math.max(score, 120);
            } else if (name.contains(normalizedKeyword)) {
                score = Math.max(score, 96 + Math.min(12, normalizedKeyword.length()));
            } else if (normalizedKeyword.contains(name)) {
                score = Math.max(score, 88 + Math.min(10, name.length()));
            }
            if (!q.isBlank() && q.contains(name)) {
                score = Math.max(score, 102);
            }
        }
        if (!group.isBlank()) {
            if (group.equals(normalizedKeyword)) {
                score = Math.max(score, 92);
            } else if (group.contains(normalizedKeyword) || normalizedKeyword.contains(group)) {
                score = Math.max(score, 78);
            }
        }
        return score;
    }

    private boolean isWeakDashboardKeyword(String rawKeyword, String normalizedKeyword) {
        String raw = Objects.toString(rawKeyword, "").trim();
        String keyword = Objects.toString(normalizedKeyword, "").trim();
        if (keyword.isBlank()) {
            return true;
        }
        if (containsAny(raw, "看板", "驾驶舱", "仪表盘", "大屏")) {
            return false;
        }
        return keyword.length() <= 2
                && !keyword.endsWith("看板")
                && !keyword.endsWith("驾驶舱")
                && !keyword.endsWith("仪表盘")
                && !keyword.endsWith("大屏");
    }

    private String extractDashboardKeyword(String question) {
        String q = Objects.toString(question, "").trim();
        if (q.isBlank()) {
            return "";
        }
        String[] patterns = {
                "(?:钉入|钉到|钉在|保存到|保存至|存到|放到|放入|加入|添加到|挂到|挂入)(.+)",
                "(?:到|至|进)(.+?)(?:看板|仪表盘|大屏|驾驶舱)",
                "(.+?)(?:看板|仪表盘|大屏|驾驶舱)"
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(q);
            if (matcher.find()) {
                String value = cleanDashboardKeyword(matcher.group(1));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return cleanDashboardKeyword(q);
    }

    private String cleanDashboardKeyword(String value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return "";
        }
        return text.replaceAll("[，。！？、,.!?；;：:\\s]+$", "")
                .replaceAll("^(把|将|请|帮我|麻烦|当前|这张|这个|刚才|上一轮|上一个|图表|图|卡片|结果|分析结果)+", "")
                .replaceAll("(当前|这张|这个|刚才|上一轮|上一个|图表|图|卡片|结果|分析结果)$", "")
                .trim();
    }

    private String normalizeDashboardName(Object value) {
        return Objects.toString(value, "").toLowerCase()
                .replaceAll("[`\"'“”‘’\\[\\]（）(){}<>《》]", "")
                .replaceAll("(我的|当前|这张|这个|刚才|上一轮|上一个|图表|图|卡片|结果|分析结果)", "")
                .replaceAll("(看板|仪表盘|大屏|驾驶舱|dashboard)", "")
                .replaceAll("[\\s_\\-，。！？、,.!?；;：:/\\\\]+", "")
                .trim();
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null && !Objects.toString(value, "").trim().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        Object value = firstPresent(values);
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private boolean containsAny(String text, String... candidates) {
        String source = Objects.toString(text, "");
        for (String candidate : candidates) {
            String item = Objects.toString(candidate, "").trim();
            if (!item.isBlank() && source.contains(item)) {
                return true;
            }
        }
        return false;
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
        throw new IllegalStateException("\u751f\u6210\u5206\u4eab token \u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5");
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
            throw new IllegalArgumentException("\u8fc7\u671f\u65f6\u95f4\u683c\u5f0f\u9519\u8bef\uff0c\u5e94\u4e3ayyyy-MM-dd HH:mm:ss \u6216 ISO-8601");
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
            throw new IllegalArgumentException("\u5e03\u5c40\u5e8f\u5217\u5316\u5931\u8d25");
        }
    }

    /** 保留设计看板画布样式（layout_json.canvasStyle），避免钉入/移除组件时丢�?*/
    @SuppressWarnings("unchecked")
    private void copyCanvasStyleIfPresent(Map<String, Object> from, Map<String, Object> to) {
        Object cs = from.get("canvasStyle");
        if (cs instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            to.put("canvasStyle", copy);
        }
    }

    private PinnedTarget resolvePinnedTarget(Map<String, Object> body) {
        Long chartId = toLong(body == null ? null : body.get("chartId"));
        Long artifactId = toLong(body == null ? null : body.get("artifactId"));
        Long turnId = toLong(body == null ? null : body.get("turnId"));
        if (artifactId != null) {
            Map<String, Object> artifact = chatConversationService.getArtifactForCurrentUser(artifactId);
            if (artifact.isEmpty()) {
                throw new IllegalArgumentException("\u6307\u5b9a\u7684\u5bf9\u8bdd\u4ea7\u7269\u4e0d\u5b58\u5728\u6216\u65e0\u6743\u8bbf\u95ee");
            }
            Long resolvedHistoryId = toLong(artifact.get("historyId"));
            if (resolvedHistoryId == null || resolvedHistoryId <= 0) {
                String artifactType = Objects.toString(artifact.get("artifactType"), "").trim().toUpperCase();
                if (artifactType.startsWith("ADVANCED_")) {
                    return new PinnedTarget(0L, artifactId, toLong(artifact.get("turnId")));
                }
                throw new IllegalArgumentException("\u8be5\u5bf9\u8bdd\u4ea7\u7269\u5c1a\u672a\u5173\u8054\u5386\u53f2\u56fe\u8868\uff0c\u6682\u65f6\u65e0\u6cd5\u9489\u5165\u770b\u677f");
            }
            chatQueryHistoryService.assertHistoryChartOwnedByCurrentUser(resolvedHistoryId);
            return new PinnedTarget(resolvedHistoryId, artifactId, toLong(artifact.get("turnId")));
        }
        if (turnId != null) {
            Map<String, Object> artifact = chatConversationService.latestChartArtifactForTurn(turnId);
            if (artifact.isEmpty()) {
                throw new IllegalArgumentException("\u6307\u5b9a\u8f6e\u6b21\u6682\u65e0\u53ef\u9489\u5165\u7684\u56fe\u8868\u4ea7\u7269");
            }
            Long resolvedArtifactId = toLong(artifact.get("id"));
            Long resolvedHistoryId = toLong(artifact.get("historyId"));
            if (resolvedHistoryId == null || resolvedHistoryId <= 0) {
                throw new IllegalArgumentException("\u8be5\u8f6e\u6b21\u56fe\u8868\u5c1a\u672a\u5173\u8054\u5386\u53f2\u56fe\u8868\uff0c\u6682\u65f6\u65e0\u6cd5\u9489\u5165\u770b\u677f");
            }
            chatQueryHistoryService.assertHistoryChartOwnedByCurrentUser(resolvedHistoryId);
            return new PinnedTarget(resolvedHistoryId, resolvedArtifactId, turnId);
        }
        if (chartId == null || chartId <= 0) {
            throw new IllegalArgumentException("\u7f3a\u5c11 chartId / artifactId / turnId");
        }
        chatQueryHistoryService.assertHistoryChartOwnedByCurrentUser(chartId);
        return new PinnedTarget(chartId, null, null);
    }

    private String resolvePinnedTitle(Map<String, Object> body, PinnedTarget target) {
        String explicitTitle = sanitizePinnedTitle(body == null ? null : body.get("title"));
        if (!explicitTitle.isBlank()) {
            return explicitTitle;
        }
        Map<String, Object> history = historySnapshot(target.chartId());
        String question = sanitizePinnedTitle(history.get("queryText"));
        if (!question.isBlank()) {
            return question;
        }
        Map<String, Object> snapshot = parseLayoutJson(Objects.toString(history.get("chartSnapshot"), "{}"));
        String fieldTitle = buildFieldMappingTitle(snapshot);
        if (!fieldTitle.isBlank()) {
            return fieldTitle;
        }
        String messageTitle = sanitizePinnedTitle(snapshot.get("message"));
        if (!messageTitle.isBlank()) {
            return messageTitle;
        }
        return "图表 " + target.chartId();
    }

    private Map<String, Object> historySnapshot(Long chartId) {
        if (chartId == null || chartId <= 0) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT query_text AS queryText, chart_snapshot AS chartSnapshot
                FROM is_chat_query_history
                WHERE id = ?
                LIMIT 1
                """, chartId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private String buildFieldMappingTitle(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return "";
        }
        Object rawFieldMapping = snapshot.get("fieldMapping");
        if (!(rawFieldMapping instanceof Map<?, ?> mapping)) {
            return "";
        }
        String dimension = sanitizePinnedTitle(mapping.get("dimension"));
        String metric = sanitizePinnedTitle(mapping.get("metric"));
        String chartType = humanizeChartType(snapshot.get("chartType"));
        if (!dimension.isBlank() && !metric.isBlank()) {
            return compactTitle(dimension + chartType + metric);
        }
        if (!dimension.isBlank()) {
            return compactTitle(dimension + chartType);
        }
        if (!metric.isBlank()) {
            return compactTitle(metric + chartType);
        }
        return "";
    }

    private String humanizeChartType(Object chartType) {
        String text = Objects.toString(chartType, "").trim().toLowerCase();
        return switch (text) {
            case "pie" -> "占比";
            case "line" -> "趋势";
            case "bar" -> "统计";
            case "scatter" -> "分布";
            default -> "图";
        };
    }

    private String sanitizePinnedTitle(Object value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return "";
        }
        text = text.replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("^分析完成[。.:：\\s]*", "")
                .replaceAll("^已基于字段\\s*", "")
                .trim();
        return compactTitle(text);
    }

    private String compactTitle(String value) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return "";
        }
        if (text.length() > 80) {
            text = text.substring(0, 80).trim();
        }
        return text;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record PinnedTarget(Long chartId, Long artifactId, Long turnId) {
    }

    private record DashboardMatch(DashboardMatchStatus status,
                                  String keyword,
                                  Map<String, Object> dashboard,
                                  List<Map<String, Object>> candidates) {
    }

    private enum DashboardMatchStatus {
        UNIQUE,
        NONE,
        MISSING_TARGET,
        NOT_FOUND,
        AMBIGUOUS
    }
}
