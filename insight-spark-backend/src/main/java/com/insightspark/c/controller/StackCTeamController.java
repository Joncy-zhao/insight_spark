package com.insightspark.c.controller;

import com.insightspark.common.ApiResponse;
import com.insightspark.c.service.StackCTeamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/c/teams")
@CrossOrigin
public class StackCTeamController {

    @Autowired
    private StackCTeamService teamService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listMyTeams() {
        return ApiResponse.success(teamService.listMyTeams());
    }

    @GetMapping("/received-dashboards")
    public ApiResponse<List<Map<String, Object>>> listReceivedDashboards() {
        return ApiResponse.success(teamService.listReceivedDashboards());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createTeam(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("团队已创建", teamService.createTeam(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateTeam(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("团队已更新", teamService.updateTeam(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTeam(@PathVariable long id) {
        try {
            teamService.deleteTeam(id);
            return ApiResponse.success("团队已解散", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}/members")
    public ApiResponse<List<Map<String, Object>>> listMembers(@PathVariable long id) {
        try {
            return ApiResponse.success(teamService.listMembers(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}/member-candidates")
    public ApiResponse<List<Map<String, Object>>> memberCandidates(
            @PathVariable long id,
            @RequestParam(required = false) String keyword) {
        try {
            return ApiResponse.success(teamService.listMemberCandidates(id, keyword));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/members")
    public ApiResponse<Map<String, Object>> addMember(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("成员已加入", teamService.addMember(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}/members")
    public ApiResponse<Void> removeMember(@PathVariable long id, @RequestParam String userId) {
        try {
            teamService.removeMember(id, userId);
            return ApiResponse.success("成员已移除", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}/dashboards")
    public ApiResponse<List<Map<String, Object>>> listTeamDashboards(@PathVariable long id) {
        try {
            return ApiResponse.success(teamService.listTeamDashboards(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/dashboards/{dashboardId}/teams")
    public ApiResponse<List<Map<String, Object>>> listDashboardTeams(@PathVariable long dashboardId) {
        try {
            return ApiResponse.success(teamService.listDashboardTeams(dashboardId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/dashboards/{dashboardId}/grant")
    public ApiResponse<Map<String, Object>> grantDashboardToTeam(
            @PathVariable long dashboardId,
            @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success("已授权团队", teamService.grantDashboardToTeam(dashboardId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/dashboards/{dashboardId}/grant")
    public ApiResponse<Void> revokeDashboardFromTeam(
            @PathVariable long dashboardId,
            @RequestParam long teamId,
            @RequestParam(defaultValue = "READ") String permissionType) {
        try {
            teamService.revokeDashboardFromTeam(dashboardId, teamId, permissionType);
            return ApiResponse.success("已撤销团队授权", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
