export function parseMaybeJson(value) {
  if (value == null || value === '') return null
  if (typeof value === 'object') return value
  try {
    return JSON.parse(String(value))
  } catch {
    return null
  }
}

export function normalizeChatHistoryItem(item) {
  const rawSnapshot = parseMaybeJson(item?.chartSnapshot)
  const snapshot = rawSnapshot && typeof rawSnapshot === 'object' ? rawSnapshot : {}
  const question = String(
    item?.question || item?.queryText || snapshot?.sourceQuestion || snapshot?.message || ''
  ).trim()
  const tableName = String(item?.tableName || item?.queryTableName || snapshot?.tableName || '').trim()
  const chartType = String(item?.chartType || snapshot?.chartType || '').trim()
  const chartData = Array.isArray(snapshot?.data) ? snapshot.data : []
  const rawExecutionStatus = Number(item?.executionStatus ?? snapshot?.executionStatus)
  const rawExecutionTimeMs = Number(item?.executionTimeMs ?? snapshot?.executionTimeMs)
  const rawCacheFlag = item?.isHitCache ?? snapshot?.isHitCache
  return {
    id: String(item?.id || `${tableName}::${question}`),
    question,
    tableName,
    chartType,
    riskLevel: String(item?.riskLevel || snapshot?.riskLevel || 'SAFE').trim().toUpperCase(),
    sql: String(item?.sql || item?.generatedSql || snapshot?.sql || snapshot?.generatedSql || '').trim(),
    fieldMapping: snapshot?.fieldMapping && typeof snapshot.fieldMapping === 'object' ? snapshot.fieldMapping : {},
    chartSnapshot: snapshot,
    executionStatus: Number.isFinite(rawExecutionStatus) ? rawExecutionStatus : null,
    executionTimeMs: Number.isFinite(rawExecutionTimeMs) && rawExecutionTimeMs >= 0 ? rawExecutionTimeMs : null,
    isHitCache:
      rawCacheFlag === true ||
      Number(rawCacheFlag) === 1 ||
      String(rawCacheFlag || '')
        .trim()
        .toLowerCase() === 'true',
    hasChartSnapshot: chartData.length > 0,
    chartDataCount: chartData.length,
    createdAt: item?.createdAt || new Date().toISOString(),
    isPinnedOnBoard: Boolean(item?.isPinnedOnBoard)
  }
}

export function historyChartTypeLabel(type) {
  const text = String(type || '').trim()
  if (text === 'bar') return '柱状图'
  if (text === 'line') return '折线图'
  if (text === 'pie') return '饼图'
  if (text === 'table') return '表格'
  return text || '图表'
}

export function historyExecutionStatusLabel(entry) {
  const status = Number(entry?.executionStatus)
  if (status === 1) return '执行成功'
  if (status === 0) return '执行失败'
  if (status === 2) return '已取消'
  return '未知'
}

export function historyExecutionStatusType(entry) {
  const status = Number(entry?.executionStatus)
  if (status === 1) return 'success'
  if (status === 0) return 'danger'
  if (status === 2) return 'warning'
  return 'info'
}

export function formatHistoryExecutionTime(value) {
  const duration = Number(value)
  if (!Number.isFinite(duration) || duration < 0) return '未知'
  if (duration < 1000) return `${duration} ms`
  if (duration < 60000) return `${(duration / 1000).toFixed(duration >= 10000 ? 0 : 1)} s`
  return `${(duration / 60000).toFixed(1)} min`
}

export function formatChatHistoryTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

export function summarizeFieldMapping(mapping) {
  if (!mapping || typeof mapping !== 'object') return []
  return [
    { label: '维度', value: String(mapping.dimension || mapping.dimensionKey || '').trim() },
    { label: '指标', value: String(mapping.metric || mapping.metricKey || '').trim() }
  ].filter((item) => item.value)
}

export function historyRowToChartPayload(entry) {
  if (!entry) return null
  return {
    id: entry.id,
    queryText: entry.question,
    chartType: entry.chartType,
    chartSnapshot: entry.chartSnapshot,
    generatedSql: entry.sql,
    queryTableName: entry.tableName
  }
}
