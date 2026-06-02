import { http, unwrap } from './http'

export const parseAdvancedAnalysisIntent = (payload) =>
  http.post('/api/advanced-analysis/parse', payload).then(unwrap)

export const fetchAdvancedAnalysisFieldMeta = (payload) =>
  http.post('/api/advanced-analysis/field-meta', payload).then(unwrap)

export const runAdvancedForecast = (payload) =>
  http.post('/api/advanced-analysis/forecast', payload).then(unwrap)

export const runAdvancedForecastFromSeries = (payload) =>
  http.post('/api/advanced-analysis/forecast-series', payload).then(unwrap)

export const runAdvancedWhatIf = (payload) =>
  http.post('/api/advanced-analysis/what-if', payload).then(unwrap)

export const explainAdvancedAnalysisResult = (payload) =>
  http.post('/api/advanced-analysis/explain', payload, { timeout: 90000 }).then(unwrap)

export const saveAdvancedAlertRule = (payload) =>
  http.post('/api/advanced-analysis/alert-rules', payload).then(unwrap)

export const listAdvancedAlertRules = () =>
  http.post('/api/advanced-analysis/alert-rules', { action: 'list' }).then(unwrap)

export const getAdvancedAlertRule = (id) =>
  http.post('/api/advanced-analysis/alert-rules', { action: 'detail', id }).then(unwrap)

export const updateAdvancedAlertRule = (payload) =>
  http.post('/api/advanced-analysis/alert-rules', { ...payload, action: 'update' }).then(unwrap)

export const updateAdvancedAlertRuleStatus = (payload) =>
  http.post('/api/advanced-analysis/alert-rules/status', payload).then(unwrap)

export const deleteAdvancedAlertRule = (payload) =>
  http.post('/api/advanced-analysis/alert-rules/delete', payload).then(unwrap)

export const runAdvancedAlertDetection = (payload = {}) =>
  http.post('/api/advanced-analysis/alert-events/run', payload).then(unwrap)

export const listAdvancedAlertEvents = (payload = {}) =>
  http.post('/api/advanced-analysis/alert-events', payload).then(unwrap)

export const getAdvancedAlertEvent = (id) =>
  http.post('/api/advanced-analysis/alert-events', { action: 'detail', id }).then(unwrap)

export const updateAdvancedAlertEventStatus = (payload = {}) =>
  http.post('/api/advanced-analysis/alert-events', { ...payload, action: 'status' }).then(unwrap)

export const explainAdvancedAlertEvent = (payload = {}) =>
  http.post('/api/advanced-analysis/alert-events', { ...payload, action: 'explain' }, { timeout: 90000 }).then(unwrap)

export const listAdvancedAlertPushLogs = (payload = {}) =>
  http.post('/api/advanced-analysis/alert-push', { ...payload, action: 'list' }).then(unwrap)

export const fetchAdvancedAlertPushConfig = () =>
  http.post('/api/advanced-analysis/alert-push', { action: 'config' }).then(unwrap)

export const retryAdvancedAlertPush = (id) =>
  http.post('/api/advanced-analysis/alert-push', { action: 'retry', id }).then(unwrap)

export const saveAdvancedAnalysisPlan = (payload) =>
  http.post('/api/advanced-analysis/plans', payload).then(unwrap)

export const listAdvancedAnalysisPlans = (payload = {}) =>
  http.post('/api/advanced-analysis/plans', { ...payload, action: 'list' }).then(unwrap)

export const getAdvancedAnalysisPlan = (id) =>
  http.post('/api/advanced-analysis/plans', { action: 'detail', id }).then(unwrap)

export const recalculateAdvancedAnalysisPlan = (id) =>
  http.post('/api/advanced-analysis/plans', { action: 'recalculate', id }).then(unwrap)

export const deleteAdvancedAnalysisPlan = (id) =>
  http.post('/api/advanced-analysis/plans', { action: 'delete', id }).then(unwrap)

export const renameAdvancedAnalysisPlan = (payload) =>
  http.post('/api/advanced-analysis/plans', { ...payload, action: 'rename' }).then(unwrap)

export const listAdvancedAnalysisPlanVersions = (id) =>
  http.post('/api/advanced-analysis/plans', { action: 'versions', id }).then(unwrap)

export const compareAdvancedAnalysisPlanVersions = (payload) =>
  http.post('/api/advanced-analysis/plans', { ...payload, action: 'compare' }).then(unwrap)

export const compareLatestAdvancedAnalysisPlanVersions = (id) =>
  http.post('/api/advanced-analysis/plans', { action: 'compare-latest', id }).then(unwrap)
