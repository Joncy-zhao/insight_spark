package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCTeamService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StackCDashboardService dashboardService;

    public List<Map<String, Object>> listMyTeams() {
        String uid = AuthContext.userId();
        return jdbcTemplate.queryForList("""
                SELECT t.id, t.name, t.description, t.owner_user_id AS ownerUserId,
                       u.nickname AS ownerNickname, t.created_at AS createdAt, t.updated_at AS updatedAt,
                       (SELECT COUNT(*) FROM is_team_member m WHERE m.team_id = t.id) AS memberCount,
                       tm.member_role AS myRole
                FROM is_team t
                INNER JOIN is_team_member tm ON tm.team_id = t.id AND tm.user_id = ?
                LEFT JOIN is_user u ON u.user_id = t.owner_user_id
                ORDER BY t.updated_at DESC
                """, uid);
    }

    public Map<String, Object> createTeam(Map<String, Object> body) {
        String name = requireText(body, "name");
        String description = body.get("description") == null ? null : String.valueOf(body.get("description")).trim();
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_team(name, description, owner_user_id) VALUES (?, ?, ?)
                """, name, description, uid);
        Long teamId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long id = teamId == null ? 0L : teamId;
        jdbcTemplate.update("""
                INSERT INTO is_team_member(team_id, user_id, member_role) VALUES (?, ?, 'OWNER')
                """, id, uid);
        return fetchTeam(id);
    }

    public Map<String, Object> updateTeam(long teamId, Map<String, Object> body) {
        assertTeamAdmin(teamId);
        String name = requireText(body, "name");
        String description = body.get("description") == null ? null : String.valueOf(body.get("description")).trim();
        jdbcTemplate.update("""
                UPDATE is_team SET name = ?, description = ?, updated_at = NOW() WHERE id = ?
                """, name, description, teamId);
        return fetchTeam(teamId);
    }

    public void deleteTeam(long teamId) {
        Map<String, Object> team = fetchTeam(teamId);
        if (!AuthContext.isAdmin() && !AuthContext.userId().equals(Objects.toString(team.get("ownerUserId")))) {
            throw new IllegalArgumentException("仅团队创建者或管理员可解散团队");
        }
        jdbcTemplate.update("DELETE FROM is_team WHERE id = ?", teamId);
    }

    public List<Map<String, Object>> listMembers(long teamId) {
        assertTeamMember(teamId);
        return jdbcTemplate.queryForList("""
                SELECT m.id, m.team_id AS teamId, m.user_id AS userId, m.member_role AS memberRole,
                       m.created_at AS createdAt, u.username, u.nickname
                FROM is_team_member m
                LEFT JOIN is_user u ON u.user_id = m.user_id
                WHERE m.team_id = ?
                ORDER BY FIELD(m.member_role, 'OWNER', 'ADMIN', 'MEMBER'), m.created_at ASC
                """, teamId);
    }

    public Map<String, Object> addMember(long teamId, Map<String, Object> body) {
        assertTeamAdmin(teamId);
        String userId = requireText(body, "userId");
        if (AuthContext.userId().equals(userId)) {
            throw new IllegalArgumentException("不能将自己加入团队");
        }
        List<Map<String, Object>> userRows = jdbcTemplate.queryForList("""
                SELECT user_id AS userId, role FROM is_user WHERE user_id = ? AND status = 'ACTIVE'
                """, userId);
        if (userRows.isEmpty()) {
            throw new IllegalArgumentException("用户不存在或已停用");
        }
        if ("ADMIN".equalsIgnoreCase(Objects.toString(userRows.get(0).get("role")))) {
            throw new IllegalArgumentException("不能将管理员账号加入协作团队");
        }
        String role = Objects.toString(body.getOrDefault("memberRole", "MEMBER"), "MEMBER").trim().toUpperCase();
        if (!List.of("ADMIN", "MEMBER").contains(role)) {
            role = "MEMBER";
        }
        jdbcTemplate.update("""
                INSERT INTO is_team_member(team_id, user_id, member_role) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE member_role = VALUES(member_role)
                """, teamId, userId, role);
        return Map.of("ok", true);
    }

    public List<Map<String, Object>> listMemberCandidates(long teamId, String keyword) {
        assertTeamAdmin(teamId);
        String uid = AuthContext.userId();
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isBlank()) {
            return jdbcTemplate.queryForList("""
                    SELECT u.user_id AS userId, u.username, u.nickname
                    FROM is_user u
                    WHERE u.status = 'ACTIVE'
                      AND u.role != 'ADMIN'
                      AND u.user_id != ?
                      AND NOT EXISTS (
                        SELECT 1 FROM is_team_member m
                        WHERE m.team_id = ? AND m.user_id = u.user_id
                      )
                    ORDER BY u.nickname ASC
                    LIMIT 30
                    """, uid, teamId);
        }
        String like = "%" + kw + "%";
        return jdbcTemplate.queryForList("""
                SELECT u.user_id AS userId, u.username, u.nickname
                FROM is_user u
                WHERE u.status = 'ACTIVE'
                  AND u.role != 'ADMIN'
                  AND u.user_id != ?
                  AND NOT EXISTS (
                    SELECT 1 FROM is_team_member m
                    WHERE m.team_id = ? AND m.user_id = u.user_id
                  )
                  AND (u.username LIKE ? OR u.nickname LIKE ? OR u.user_id LIKE ?)
                ORDER BY u.nickname ASC
                LIMIT 30
                """, uid, teamId, like, like, like);
    }

    public void removeMember(long teamId, String userId) {
        assertTeamAdmin(teamId);
        Map<String, Object> team = fetchTeam(teamId);
        if (Objects.toString(team.get("ownerUserId")).equals(userId)) {
            throw new IllegalArgumentException("不能移除团队创建者");
        }
        jdbcTemplate.update("DELETE FROM is_team_member WHERE team_id = ? AND user_id = ?", teamId, userId);
    }

    public List<Map<String, Object>> listTeamDashboards(long teamId) {
        assertTeamMember(teamId);
        return jdbcTemplate.queryForList("""
                SELECT dp.id, dp.dashboard_id AS dashboardId, dp.team_id AS teamId,
                       dp.permission_type AS permissionType, dp.granted_by AS grantedBy, dp.created_at AS createdAt,
                       d.name AS dashboardName, d.owner_user_id AS ownerUserId, d.is_public AS isPublic, d.status
                FROM is_dashboard_team_permission dp
                INNER JOIN is_dashboard d ON d.id = dp.dashboard_id
                WHERE dp.team_id = ?
                ORDER BY dp.created_at DESC
                """, teamId);
    }

    public List<Map<String, Object>> listDashboardTeams(long dashboardId) {
        Map<String, Object> dashboard = dashboardService.getById(dashboardId);
        assertCanDistributeDashboard(dashboard);
        return jdbcTemplate.queryForList("""
                SELECT dp.id, dp.dashboard_id AS dashboardId, dp.team_id AS teamId,
                       dp.permission_type AS permissionType, dp.granted_by AS grantedBy, dp.created_at AS createdAt,
                       t.name AS teamName, t.owner_user_id AS teamOwnerUserId
                FROM is_dashboard_team_permission dp
                INNER JOIN is_team t ON t.id = dp.team_id
                WHERE dp.dashboard_id = ?
                ORDER BY dp.created_at DESC
                """, dashboardId);
    }

    public Map<String, Object> grantDashboardToTeam(long dashboardId, Map<String, Object> body) {
        Map<String, Object> dashboard = dashboardService.getById(dashboardId);
        assertCanDistributeDashboard(dashboard);
        long teamId = parseLong(body.get("teamId"), "teamId");
        assertTeamMember(teamId);
        String permissionType = Objects.toString(body.getOrDefault("permissionType", "READ"), "READ").trim().toUpperCase();
        if (!"READ".equals(permissionType) && !"EDIT".equals(permissionType)) {
            throw new IllegalArgumentException("permissionType 仅支持 READ / EDIT");
        }
        jdbcTemplate.update("""
                INSERT INTO is_dashboard_team_permission(dashboard_id, team_id, permission_type, granted_by)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE granted_by = VALUES(granted_by), created_at = CURRENT_TIMESTAMP
                """, dashboardId, teamId, permissionType, AuthContext.userId());
        return Map.of("ok", true);
    }

    public void revokeDashboardFromTeam(long dashboardId, long teamId, String permissionType) {
        Map<String, Object> dashboard = dashboardService.getById(dashboardId);
        assertCanDistributeDashboard(dashboard);
        jdbcTemplate.update("""
                DELETE FROM is_dashboard_team_permission
                WHERE dashboard_id = ? AND team_id = ? AND permission_type = ?
                """, dashboardId, teamId, permissionType);
    }

    public List<Map<String, Object>> listReceivedDashboards() {
        String uid = AuthContext.userId();
        if (AuthContext.isAdmin()) {
            return jdbcTemplate.queryForList("""
                    SELECT d.id, d.name, d.description, d.owner_user_id AS ownerUserId,
                           d.is_public AS isPublic, d.status, d.updated_at AS updatedAt,
                           'ADMIN' AS accessSource, 'EDIT' AS permissionType, NULL AS teamId, NULL AS teamName
                    FROM is_dashboard d
                    WHERE d.status = 'ACTIVE'
                    ORDER BY d.updated_at DESC
                    LIMIT 200
                    """);
        }
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT d.id, d.name, d.description, d.owner_user_id AS ownerUserId,
                       d.is_public AS isPublic, d.status, d.updated_at AS updatedAt,
                       src.accessSource, src.permissionType, src.teamId, src.teamName
                FROM is_dashboard d
                INNER JOIN (
                  SELECT dp.dashboard_id, 'TEAM' AS accessSource, dp.permission_type AS permissionType,
                         t.id AS teamId, t.name AS teamName
                  FROM is_dashboard_team_permission dp
                  INNER JOIN is_team_member tm ON tm.team_id = dp.team_id AND tm.user_id = ?
                  INNER JOIN is_team t ON t.id = dp.team_id
                  UNION ALL
                  SELECT p.dashboard_id, 'USER' AS accessSource, p.permission_type AS permissionType,
                         NULL AS teamId, NULL AS teamName
                  FROM is_dashboard_permission p
                  WHERE p.user_id = ?
                    AND (p.expire_at IS NULL OR p.expire_at > NOW())
                ) src ON src.dashboard_id = d.id
                WHERE d.status = 'ACTIVE'
                  AND d.owner_user_id != ?
                ORDER BY d.updated_at DESC
                LIMIT 200
                """, uid, uid, uid);
    }

    public boolean hasTeamDashboardAccess(long dashboardId, String permissionType) {
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

    private Map<String, Object> fetchTeam(long teamId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT t.id, t.name, t.description, t.owner_user_id AS ownerUserId,
                       u.nickname AS ownerNickname, t.created_at AS createdAt, t.updated_at AS updatedAt
                FROM is_team t
                LEFT JOIN is_user u ON u.user_id = t.owner_user_id
                WHERE t.id = ?
                """, teamId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("团队不存在");
        }
        return rows.get(0);
    }

    private void assertTeamMember(long teamId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM is_team_member WHERE team_id = ? AND user_id = ?
                """, Integer.class, teamId, AuthContext.userId());
        if (AuthContext.isAdmin()) {
            return;
        }
        if (count == null || count == 0) {
            throw new IllegalArgumentException("无权访问该团队");
        }
    }

    private void assertTeamAdmin(long teamId) {
        if (AuthContext.isAdmin()) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT member_role AS memberRole FROM is_team_member
                WHERE team_id = ? AND user_id = ?
                """, teamId, AuthContext.userId());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("无权管理该团队");
        }
        String role = Objects.toString(rows.get(0).get("memberRole"), "");
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalArgumentException("仅团队管理员可执行此操作");
        }
    }

    private void assertCanDistributeDashboard(Map<String, Object> dashboard) {
        if (AuthContext.isAdmin()) {
            return;
        }
        String status = Objects.toString(dashboard.get("status"), "").trim().toUpperCase();
        if (!"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("仅已发布看板可分发团队授权");
        }
        if (isPublicDashboard(dashboard)) {
            return;
        }
        String uid = Objects.toString(AuthContext.userId(), "").trim();
        String owner = Objects.toString(dashboard.get("ownerUserId"), "").trim();
        String saveAs = Objects.toString(dashboard.get("saveAsUserId"), "").trim();
        if (uid.equals(owner) || (!saveAs.isEmpty() && uid.equals(saveAs))) {
            return;
        }
        throw new IllegalArgumentException("仅看板所有者、另存人或系统管理员可分配私密看板的团队授权");
    }

    private static boolean isPublicDashboard(Map<String, Object> row) {
        return parseTinyInt(row.get("isPublic"), 0) == 1;
    }

    private static int parseTinyInt(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return def;
        }
        if ("true".equalsIgnoreCase(s)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(s)) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
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
