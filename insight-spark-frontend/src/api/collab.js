import axios from 'axios'
import { authToken, restoreSessionHeader } from '../store/session'

const API_BASE = 'http://localhost:8080'

function unwrap(res) {
  if (res.data.code !== 200) throw new Error(res.data.message || '请求失败')
  return res.data.data
}

export async function fetchCollabDashboards() {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/dashboards`))
}

export async function fetchCollabSummary(dashboardId) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/collab/dashboards/${dashboardId}/summary`))
}

export async function fetchAnnotationsByDashboard(dashboardId) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/annotations/by-dashboard/${dashboardId}`))
}

export async function fetchComments(targetType, targetId) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/comments`, { params: { targetType, targetId } }))
}

export async function createAnnotation(payload) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/annotations`, payload))
}

export async function deleteAnnotation(id) {
  restoreSessionHeader()
  return unwrap(await axios.delete(`${API_BASE}/api/c/annotations/${id}`))
}

export async function createComment(payload) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/comments`, payload))
}

export async function deleteComment(id) {
  restoreSessionHeader()
  return unwrap(await axios.delete(`${API_BASE}/api/c/comments/${id}`))
}

export async function followDashboard(id) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/collab/dashboards/${id}/follow`))
}

export async function unfollowDashboard(id) {
  restoreSessionHeader()
  return unwrap(await axios.delete(`${API_BASE}/api/c/collab/dashboards/${id}/follow`))
}

export async function fetchMentionCandidates(keyword = '') {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/collab/mention-candidates`, { params: { keyword } }))
}

export async function fetchTeamPermissions(dashboardId) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/collab/dashboards/${dashboardId}/team-permissions`))
}

export async function grantTeamPermission(dashboardId, payload) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/collab/dashboards/${dashboardId}/team-permissions`, payload))
}

export async function revokeTeamPermission(dashboardId, userId, permissionType = 'READ') {
  restoreSessionHeader()
  return unwrap(await axios.delete(`${API_BASE}/api/c/collab/dashboards/${dashboardId}/team-permissions`, {
    params: { userId, permissionType }
  }))
}

export async function downloadCollabReport(dashboardId) {
  restoreSessionHeader()
  const res = await axios.get(`${API_BASE}/api/c/collab/dashboards/${dashboardId}/report`, {
    responseType: 'blob'
  })
  const blob = new Blob([res.data], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `dashboard-${dashboardId}-collab.md`
  a.click()
  URL.revokeObjectURL(url)
}

export function collabWebSocketUrl() {
  const token = encodeURIComponent(String(authToken.value || localStorage.getItem('token') || ''))
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://localhost:8080/ws/collab?token=${token}`
}

// —— 团队 ——
export async function fetchMyTeams() {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/teams`))
}

export async function createTeam(payload) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/teams`, payload))
}

export async function updateTeam(teamId, payload) {
  restoreSessionHeader()
  return unwrap(await axios.put(`${API_BASE}/api/c/teams/${teamId}`, payload))
}

export async function deleteTeam(teamId) {
  restoreSessionHeader()
  return unwrap(await axios.delete(`${API_BASE}/api/c/teams/${teamId}`))
}

export async function fetchTeamMemberCandidates(teamId, keyword = '') {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/teams/${teamId}/member-candidates`, { params: { keyword } }))
}

export async function fetchTeamMembers(teamId) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/teams/${teamId}/members`))
}

export async function addTeamMember(teamId, payload) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/teams/${teamId}/members`, payload))
}

export async function removeTeamMember(teamId, userId) {
  restoreSessionHeader()
  return unwrap(await axios.delete(`${API_BASE}/api/c/teams/${teamId}/members`, { params: { userId } }))
}

export async function fetchTeamDashboards(teamId) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/teams/${teamId}/dashboards`))
}

export async function fetchReceivedDashboards() {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/teams/received-dashboards`))
}

export async function fetchDashboardTeams(dashboardId) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/teams/dashboards/${dashboardId}/teams`))
}

export async function grantDashboardToTeam(dashboardId, payload) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/teams/dashboards/${dashboardId}/grant`, payload))
}

export async function revokeDashboardTeamGrant(dashboardId, teamId, permissionType = 'READ') {
  restoreSessionHeader()
  return unwrap(await axios.delete(`${API_BASE}/api/c/teams/dashboards/${dashboardId}/grant`, {
    params: { teamId, permissionType }
  }))
}
