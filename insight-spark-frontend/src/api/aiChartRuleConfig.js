import { http, unwrap } from './http'

const BASE = '/api/admin/chart-rules'

export const fetchChartRules = (params) => http.get(`${BASE}/rules`, { params }).then(unwrap)
export const createChartRule = (payload) => http.post(`${BASE}/rules`, payload).then(unwrap)
export const updateChartRule = (id, payload) => http.put(`${BASE}/rules/${id}`, payload).then(unwrap)
export const updateChartRuleEnabled = (id, enabled) => http.patch(`${BASE}/rules/${id}/enabled`, { enabled }).then(unwrap)
export const deleteChartRule = (id) => http.delete(`${BASE}/rules/${id}`).then(unwrap)
export const testChartRule = (payload) => http.post(`${BASE}/rules/test`, payload).then(unwrap)
export const fetchChartPreferences = () => http.get(`${BASE}/preferences`).then(unwrap)
export const saveChartPreferences = (payload) => http.put(`${BASE}/preferences`, payload).then(unwrap)
export const fetchRenderConfigSchema = () => http.get(`${BASE}/render-config/schema`).then(unwrap)
export const fetchChartRuleAuditLogs = (params) => http.get(`${BASE}/audit-logs`, { params }).then(unwrap)
