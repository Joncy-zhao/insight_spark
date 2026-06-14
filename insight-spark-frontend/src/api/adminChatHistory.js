import { http, unwrap } from './http'

export const fetchAdminChatHistory = (params) => http.get('/api/admin/chat-history', { params }).then(unwrap)

export const fetchAdminChatHistoryDetail = (historyId) =>
  http.get(`/api/admin/chat-history/${historyId}`).then(unwrap)

export const fetchAdminChatHistoryContext = (historyId) =>
  http.get(`/api/admin/chat-history/${historyId}/context`).then(unwrap)

export const fetchAdminChatHistoryAnalytics = (params) =>
  http.get('/api/admin/chat-history/analytics', { params }).then(unwrap)

export const rerunAdminChatHistory = (historyId) =>
  http.post(`/api/admin/chat-history/${historyId}/rerun`).then(unwrap)

export const deleteAdminChatHistoryBatch = (ids, reason) =>
  http.delete('/api/admin/chat-history/batch', { data: { ids, reason } }).then(unwrap)
