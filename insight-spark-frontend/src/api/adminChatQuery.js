import { API_BASE, attachAuthHeader, http, unwrap } from './http'

export const fetchAdminChatQueryDatasources = () =>
  http.get('/api/admin/chat-query/datasources').then(unwrap)

export const fetchAdminChatQueryModels = () =>
  http.get('/api/admin/chat-query/models').then(unwrap)

export const fetchAdminChatQueryTemplates = () =>
  http.get('/api/admin/chat-query/templates').then(unwrap)

export const saveAdminChatQueryTemplate = (payload) =>
  http.post('/api/admin/chat-query/templates', payload).then(unwrap)

export const deleteAdminChatQueryTemplate = (templateId) =>
  http.post(`/api/admin/chat-query/templates/${templateId}/delete`).then(unwrap)

export const createAdminChatQuerySession = (payload) =>
  http.post('/api/admin/chat-query/sessions', payload).then(unwrap)

export const executeAdminChatQuery = (sessionId, payload = {}) =>
  http.post(`/api/admin/chat-query/sessions/${sessionId}/execute`, payload).then(unwrap)

export const runAdminPermissionCheck = (sessionId, payload = {}) =>
  http.post(`/api/admin/chat-query/sessions/${sessionId}/permission-check`, payload).then(unwrap)

export const fetchAdminChatQuerySession = (sessionId) =>
  http.get(`/api/admin/chat-query/sessions/${sessionId}`).then(unwrap)

export const fetchAdminChatQuerySessions = (params) =>
  http.get('/api/admin/chat-query/sessions', { params }).then(unwrap)

export const rerunLatestAdminChatQuery = () =>
  http.post('/api/admin/chat-query/sessions/recent/rerun').then(unwrap)

export const rerunAdminChatQuerySession = (sessionId) =>
  http.post(`/api/admin/chat-query/sessions/${sessionId}/rerun`).then(unwrap)

export const compareAdminChatQueryModels = (payload) =>
  http.post('/api/admin/chat-query/compare-models', payload).then(unwrap)

export const exportAdminChatQuerySessionUrl = (sessionId) =>
  `${API_BASE}/api/admin/chat-query/sessions/${sessionId}/export`

export const exportAdminChatQueryReasoningUrl = (sessionId) =>
  `${API_BASE}/api/admin/chat-query/sessions/${sessionId}/reasoning-export`

export const streamAdminChatQuerySession = async (sessionId, params, handlers = {}) => {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      search.set(key, String(value))
    }
  })
  const headers = {}
  attachAuthHeader({ headers })
  headers.Accept = 'text/event-stream'
  const response = await fetch(`${API_BASE}/api/admin/chat-query/sessions/${sessionId}/stream?${search.toString()}`, {
    method: 'GET',
    headers,
    cache: 'no-store'
  })
  if (!response.ok || !response.body) {
    throw new Error(`流式测试请求失败：${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  const consumeChunk = (chunk) => {
    const lines = chunk.split('\n')
    const eventLine = lines.find((line) => line.startsWith('event:'))
    const dataLines = lines.filter((line) => line.startsWith('data:'))
    const eventName = eventLine ? eventLine.replace(/^event:\s*/, '').trim() : 'message'
    const rawData = dataLines.map((line) => line.replace(/^data:\s*/, '')).join('\n').trim() || '{}'
    let payload = {}
    try {
      payload = JSON.parse(rawData)
    } catch {
      payload = { raw: rawData }
    }
    handlers.onEvent?.(eventName, payload)
  }
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const normalized = buffer.replace(/\r\n/g, '\n')
    const chunks = normalized.split('\n\n')
    buffer = chunks.pop() || ''
    chunks.forEach(consumeChunk)
  }
  if (buffer.trim()) {
    consumeChunk(buffer.trim())
  }
  handlers.onDone?.()
}
