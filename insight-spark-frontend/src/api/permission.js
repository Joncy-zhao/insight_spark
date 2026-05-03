import { http, unwrap } from './http'

export const fetchPermissionOverview = () => http.get('/api/permission/overview').then(unwrap)
export const fetchAccessibleTables = () => http.get('/api/permission/accessible-tables').then(unwrap)
export const fetchRequestableTables = () => http.get('/api/permission/requestable-tables').then(unwrap)
export const fetchMyPermissionRequests = () => http.get('/api/permission/my-requests').then(unwrap)
export const createPermissionRequest = (payload) => http.post('/api/permission/requests', payload).then(unwrap)
export const fetchAdminPermissionRequests = (status) => http.get('/api/permission/admin/requests', {
  params: { status: status || undefined }
}).then(unwrap)
export const reviewPermissionRequest = (requestId, payload) =>
  http.post(`/api/permission/admin/requests/${requestId}/review`, payload).then(unwrap)
