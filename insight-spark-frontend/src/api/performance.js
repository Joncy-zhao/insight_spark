import axios from 'axios'
import { restoreSessionHeader } from '../store/session'

const API_BASE = 'http://localhost:8080'

function unwrap(res) {
  if (res.data.code !== 200) throw new Error(res.data.message || '请求失败')
  return res.data.data
}

async function get(path, params) {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}${path}`, { params }))
}

async function put(path, body) {
  restoreSessionHeader()
  return unwrap(await axios.put(`${API_BASE}${path}`, body))
}

async function post(path, body) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}${path}`, body))
}

export function fetchPerfOverview() {
  return get('/api/c/admin/performance/overview')
}

export function fetchPerfConfig() {
  return get('/api/c/admin/performance/config')
}

export function fetchPerfSlowQueries(limit = 80) {
  return get('/api/c/admin/performance/slow-queries', { limit })
}

export function fetchPerfBatchTasks(limit = 30) {
  return get('/api/c/admin/performance/batch-tasks', { limit })
}

export function fetchPerfInterventions(limit = 40) {
  return get('/api/c/admin/performance/interventions', { limit })
}

export function fetchPerfCacheEntries(limit = 30) {
  return get('/api/c/admin/performance/cache-entries', { limit })
}

export function fetchPerfBottleneckReport() {
  return get('/api/c/admin/performance/bottleneck-report')
}

export function savePerfAlertConfig(body) {
  return put('/api/c/admin/performance/alert-config', body)
}

export function savePerfSlowQueryGovernance(body) {
  return put('/api/c/admin/performance/slow-query-governance', body)
}

export function savePerfCacheConfig(body) {
  return put('/api/c/admin/performance/cache-config', body)
}

export function savePerfBatchConfig(body) {
  return put('/api/c/admin/performance/batch-config', body)
}

export function savePerfDbPressureConfig(body) {
  return put('/api/c/admin/performance/db-pressure-config', body)
}

export function savePerfResourceConfig(body) {
  return put('/api/c/admin/performance/resource-config', body)
}

export function clearPerfSemanticCache() {
  return post('/api/c/admin/performance/cache/clear', {})
}

export function ackPerfSlowQuery(id, remark) {
  return post(`/api/c/admin/performance/slow-queries/${id}/intervention`, { action: 'ACK', remark })
}

export function terminatePerfSlowQuery(id, remark) {
  return post(`/api/c/admin/performance/slow-queries/${id}/terminate`, { remark })
}
