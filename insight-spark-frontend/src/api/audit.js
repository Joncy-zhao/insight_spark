import { http, unwrap } from './http'

export const fetchSqlAuditLogs = (params) => http.get('/api/audit/sql-logs', { params }).then(unwrap)
export const fetchSqlAuditRules = () => http.get('/api/audit/rules').then(unwrap)
export const updateSqlAuditRuleStatus = (ruleCode, enabled) =>
  http.post(`/api/audit/rules/${ruleCode}/status`, { enabled }).then(unwrap)
