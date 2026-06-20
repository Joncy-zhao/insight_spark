function parseSnapshot(raw) {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(String(raw))
  } catch {
    return {}
  }
}

export function snapshotFromPayload(payload) {
  return parseSnapshot(payload?.chartSnapshot)
}

export function snapshotTableRows(payload) {
  const snap = snapshotFromPayload(payload)
  if (Array.isArray(snap.tableRows) && snap.tableRows.length) return snap.tableRows
  if (Array.isArray(snap.data)) return snap.data
  return []
}

export function snapshotTableColumns(payload) {
  const snap = snapshotFromPayload(payload)
  const configured = Array.isArray(snap.tableColumns) ? snap.tableColumns : []
  if (configured.length) {
    return configured
      .map((item) => {
        if (item && typeof item === 'object') {
          const prop = String(item.prop || item.column || item.key || '').trim()
          return {
            prop,
            label: String(item.label || item.name || prop).trim() || prop
          }
        }
        const prop = String(item || '').trim()
        return { prop, label: prop }
      })
      .filter((item) => item.prop)
  }
  const rows = snapshotTableRows(payload)
  const first = rows.find((row) => row && typeof row === 'object') || {}
  return Object.keys(first).map((key) => ({ prop: key, label: key }))
}

export function snapshotMetricDisplay(payload) {
  const snap = snapshotFromPayload(payload)
  const fm = snap.fieldMapping && typeof snap.fieldMapping === 'object' ? snap.fieldMapping : {}
  const label = String(
    fm.metric ||
    fm.metricLabel ||
    fm.metricField ||
    fm.metricKey ||
    snap.label ||
    snap.message ||
    '指标'
  ).trim() || '指标'
  const unit = String(fm.unit || snap.unit || '').trim()
  const compareLabel = String(fm.compareLabel || snap.compareLabel || '对比值').trim() || '对比值'
  const rows = Array.isArray(snap.data) ? snap.data : []
  const first = rows[0]
  if (first && typeof first === 'object') {
    const keys = Object.keys(first)
    const metricKey = String(fm.metricKey || fm.valueKey || fm.metricField || '').trim()
    const valueKey =
      (metricKey && keys.includes(metricKey) ? metricKey : '') ||
      keys.find((k) => ['value', 'count', 'amount', 'total', 'sales', 'metric', 'metric_value'].includes(k)) ||
      keys.find((k) => k !== 'name' && k !== 'label') ||
      keys[1] ||
      keys[0]
    const raw = Number(first[valueKey] ?? first.value ?? 0)
    const text = Number.isFinite(raw) ? formatMetricNumber(raw) : String(first[valueKey] ?? first.value ?? '—')
    const compareKey = String(fm.compareMetricKey || fm.compareKey || '').trim()
    const compareRawValue =
      first.compareValue ??
      first.compare_value ??
      (compareKey && Object.prototype.hasOwnProperty.call(first, compareKey) ? first[compareKey] : undefined)
    const compareRaw = Number(compareRawValue)
    const trendKey = String(fm.trendKey || '').trim()
    const explicitTrend = String(
      first.trend ??
      first.trend_direction ??
      (trendKey && Object.prototype.hasOwnProperty.call(first, trendKey) ? first[trendKey] : '') ??
      ''
    ).trim().toLowerCase()
    const trend = normalizeMetricTrend(explicitTrend, raw, compareRaw)
    return {
      label,
      value: text,
      raw: Number.isFinite(raw) ? raw : null,
      unit,
      compareLabel,
      compareValue: Number.isFinite(compareRaw) ? formatMetricNumber(compareRaw) : '',
      compareRaw: Number.isFinite(compareRaw) ? compareRaw : null,
      trend,
      note: rows.length > 1 || keys.length > 2 ? '已选取首个核心指标' : ''
    }
  }
  const raw = Number(first)
  return {
    label,
    value: Number.isFinite(raw) ? formatMetricNumber(raw) : '—',
    raw: Number.isFinite(raw) ? raw : null,
    unit,
    compareLabel,
    compareValue: '',
    compareRaw: null,
    trend: '',
    note: ''
  }
}

function normalizeMetricTrend(value, raw, compareRaw) {
  const text = String(value || '').toLowerCase()
  if (['up', 'rise', 'increase', 'positive', '增长', '上升'].includes(text)) return 'up'
  if (['down', 'fall', 'decrease', 'negative', '下降'].includes(text)) return 'down'
  if (['flat', 'same', 'stable', '持平'].includes(text)) return 'flat'
  if (Number.isFinite(raw) && Number.isFinite(compareRaw)) {
    if (raw > compareRaw) return 'up'
    if (raw < compareRaw) return 'down'
    return 'flat'
  }
  return ''
}

function formatMetricNumber(n) {
  const abs = Math.abs(n)
  if (abs >= 1e8) return `${(n / 1e8).toFixed(2)}亿`
  if (abs >= 1e4) return `${(n / 1e4).toFixed(2)}万`
  if (Number.isInteger(n)) return String(n)
  return n.toFixed(2)
}

export function effectiveChartTypeFromPayload(payload) {
  const snap = snapshotFromPayload(payload)
  const raw = payload?.chartType || snap.chartType || 'bar'
  return String(raw || 'bar').toLowerCase()
}
