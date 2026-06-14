import { API_BASE, http, unwrap } from './http'

export const parseAdvancedAnalysisIntent = (payload) =>
  http.post('/api/advanced-analysis/parse', payload).then(unwrap)

const readToken = () => {
  const directToken = localStorage.getItem('token')
  if (directToken) return directToken
  try {
    return JSON.parse(localStorage.getItem('insight_auth') || 'null')?.token || ''
  } catch (error) {
    return ''
  }
}

const parseSseEvent = (chunk) => {
  const lines = chunk.split('\n')
  let eventName = 'message'
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  const dataText = dataLines.join('\n').trim()
  if (!dataText) return null
  let payload
  try {
    payload = JSON.parse(dataText)
  } catch (error) {
    payload = { message: dataText }
  }
  return { eventName, payload }
}

const waitForThinkingPaint = () =>
  new Promise(resolve => {
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(() => resolve())
      return
    }
    setTimeout(resolve, 0)
  })

export const streamAdvancedAnalysisIntent = async (payload = {}, { onThinking, signal } = {}) => {
  const token = readToken()
  const headers = {
    Accept: 'text/event-stream',
    'Content-Type': 'application/json'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(`${API_BASE}/api/advanced-analysis/parse-stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
    cache: 'no-store',
    signal
  })
  if (!response.ok || !response.body) {
    throw new Error(response.status === 401 ? '登录已失效，请重新登录' : `高级分析流式解析失败(${response.status})`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let result = null

  const consumeEvent = async (rawEvent) => {
    const parsed = parseSseEvent(rawEvent)
    if (!parsed) return
    if (parsed.eventName === 'thinking') {
      onThinking?.(parsed.payload)
      await waitForThinkingPaint()
      return
    }
    if (parsed.eventName === 'result') {
      result = parsed.payload
      return
    }
    if (parsed.eventName === 'error') {
      throw new Error(parsed.payload?.message || '高级分析流式解析失败')
    }
  }

  const parseBufferedEvents = () => {
    const normalized = buffer.replace(/\r\n/g, '\n')
    const chunks = normalized.split('\n\n')
    if (chunks.length <= 1) {
      buffer = normalized
      return []
    }
    buffer = chunks.pop() || ''
    return chunks
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const rawEvents = parseBufferedEvents()
    for (const rawEvent of rawEvents) {
      await consumeEvent(rawEvent)
    }
    if (result) return result
  }

  if (buffer.trim()) {
    await consumeEvent(buffer.trim())
  }
  if (!result) {
    throw new Error('高级分析流式解析结束，但未收到结果')
  }
  return result
}

export const fetchAdvancedAnalysisFieldMeta = (payload, config) =>
  http.post('/api/advanced-analysis/field-meta', payload, config).then(unwrap)

export const saveAdvancedAnalysisChatRecord = (payload) =>
  http.post('/api/advanced-analysis/chat-records', payload).then(unwrap)

export const runAdvancedForecast = (payload, config) =>
  http.post('/api/advanced-analysis/forecast', payload, config).then(unwrap)

export const runAdvancedForecastFromSeries = (payload, config) =>
  http.post('/api/advanced-analysis/forecast-series', payload, config).then(unwrap)

export const runAdvancedWhatIf = (payload, config) =>
  http.post('/api/advanced-analysis/what-if', payload, config).then(unwrap)

export const explainAdvancedAnalysisResult = (payload) =>
  http.post('/api/advanced-analysis/explain', payload, { timeout: 90000 }).then(unwrap)

export const saveAdvancedAlertRule = (payload, config) =>
  http.post('/api/advanced-analysis/alert-rules', payload, config).then(unwrap)

export const listAdvancedAlertRules = () =>
  http.post('/api/advanced-analysis/alert-rules', { action: 'list' }).then(unwrap)

export const getAdvancedAlertRule = (id) =>
  http.post('/api/advanced-analysis/alert-rules', { action: 'detail', id }).then(unwrap)

export const updateAdvancedAlertRule = (payload) =>
  http.post('/api/advanced-analysis/alert-rules', { ...payload, action: 'update' }).then(unwrap)

export const updateAdvancedAlertRuleStatus = (payload) =>
  http.post('/api/advanced-analysis/alert-rules/status', payload).then(unwrap)

export const batchUpdateAdvancedAlertRuleStatus = (payload) =>
  http.post('/api/advanced-analysis/alert-rules/status/batch', payload).then(unwrap)

export const deleteAdvancedAlertRule = (payload) =>
  http.post('/api/advanced-analysis/alert-rules/delete', payload).then(unwrap)

export const batchDeleteAdvancedAlertRules = (payload) =>
  http.post('/api/advanced-analysis/alert-rules/delete/batch', payload).then(unwrap)

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

export const batchDeleteAdvancedAnalysisPlans = (ids) =>
  http.post('/api/advanced-analysis/plans', { action: 'batchDelete', ids }).then(unwrap)

export const renameAdvancedAnalysisPlan = (payload) =>
  http.post('/api/advanced-analysis/plans', { ...payload, action: 'rename' }).then(unwrap)

export const listAdvancedAnalysisPlanVersions = (id) =>
  http.post('/api/advanced-analysis/plans', { action: 'versions', id }).then(unwrap)

export const compareAdvancedAnalysisPlanVersions = (payload) =>
  http.post('/api/advanced-analysis/plans', { ...payload, action: 'compare' }).then(unwrap)

export const compareLatestAdvancedAnalysisPlanVersions = (id) =>
  http.post('/api/advanced-analysis/plans', { action: 'compare-latest', id }).then(unwrap)
