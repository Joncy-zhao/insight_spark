import { http, unwrap } from './http'

const base = '/api/admin/user-permission'

export const fetchAdminUserPermissionOverview = () => http.get(`${base}/overview`).then(unwrap)
export const fetchAdminUsers = (keyword) => http.get(`${base}/users`, { params: { keyword: keyword || undefined } }).then(unwrap)
export const saveAdminUser = (payload) => http.post(`${base}/users`, payload).then(unwrap)
export const updateAdminUserStatus = (userId, status) => http.post(`${base}/users/${userId}/status`, { status }).then(unwrap)
export const bindAdminUserRoles = (userId, roles) => http.post(`${base}/users/${userId}/roles`, { roles }).then(unwrap)
export const fetchAdminRoles = () => http.get(`${base}/roles`).then(unwrap)
export const saveAdminRole = (payload) => http.post(`${base}/roles`, payload).then(unwrap)
export const saveAdminRolePermissions = (roleCode, permissions) => http.post(`${base}/roles/${roleCode}/permissions`, { permissions }).then(unwrap)
export const fetchAdminPermissionCatalog = () => http.get(`${base}/permission-catalog`).then(unwrap)
export const fetchAdminPermissionResources = () => http.get(`${base}/resources`).then(unwrap)
export const fetchAdminDataGrants = (userId) => http.get(`${base}/data-grants`, { params: { userId: userId || undefined } }).then(unwrap)
export const grantAdminDataPermission = (payload) => http.post(`${base}/data-grants`, payload).then(unwrap)
export const revokeAdminDataPermission = (payload) => http.post(`${base}/data-grants/revoke`, payload).then(unwrap)
export const fetchAdminFieldRules = () => http.get(`${base}/field-rules`).then(unwrap)
export const saveAdminFieldRule = (payload) => http.post(`${base}/field-rules`, payload).then(unwrap)
export const fetchAdminPermissionPreview = (userId) => http.get(`${base}/preview/${userId}`).then(unwrap)
