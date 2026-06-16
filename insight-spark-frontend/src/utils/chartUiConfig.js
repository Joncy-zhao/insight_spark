import { sanitizeSeriesItemStylesForApi } from './chartOptionFromSnapshot.js'

export const DEFAULT_CHART_STYLE = {
  titleFontSize: 13,
  titleFontWeight: 600,
  titleColor: '#111827',
  titleAlign: 'left',
  legendShow: true,
  legendPosition: 'top',
  legendFontSize: 11,
  tooltipShow: true,
  tooltipFormat: 'auto',
  dataLabelShow: false,
  dataLabelPosition: 'top',
  axisXRotate: 0,
  axisYShow: true,
  gridLineShow: true,
  barRadius: 4,
  lineSmooth: false,
  lineWidth: 2,
  lineSymbolSize: 4,
  pieInnerRadius: 0,
  piePadAngle: 0,
  scatterSymbolSize: 10,
  scatterOpacity: 0.85,
  radarAreaOpacity: 0.25,
  radarLineWidth: 2,
  radarSymbolSize: 4,
  tableHeaderFontSize: 12,
  tableBodyFontSize: 12,
  tableStripe: true,
  metricValueFontSize: 36,
  metricValueColor: '',
  metricLabelFontSize: 13,
  mapAreaColor: '#e8f4fc',
  mapBorderColor: '#b0c4de',
  mapEmphasisColor: '#5470c6',
  primaryColor: '',
  secondaryColor: '',
  backgroundColor: '',
  fillMode: 'stretch',
  autoResizeAxis: true,
  cardBorderRadius: 8,
  cardShadow: true
}

export const DEFAULT_LAYOUT_CONSTRAINTS = {
  minW: 4,
  minH: 2,
  maxW: 24,
  maxH: 24,
  gridSnap: false,
  gridCellSize: 1,
  lockSize: false,
  lockPosition: false
}

/** 与 ECharts 默认 theme 调色板对齐 */
export const ECHARTS_DEFAULT_PALETTE = [
  '#5470c6',
  '#91cc75',
  '#fac858',
  '#ee6666',
  '#73c0de',
  '#3ba272',
  '#fc8452',
  '#9a60b4',
  '#ea7ccc'
]

/**
 * 解析某数据项在图表上当前实际显示的颜色（与 buildOptionFromHistoryRow 逻辑对齐）
 * @param {number} idx 数据项下标
 * @param {{ barColor?: string, seriesItemStyles?: object }} ui
 * @param {string} chartType bar | line | pie
 */
export function resolveSeriesItemDisplayColor(idx, ui = {}, chartType = 'bar') {
  const i = Number(idx)
  if (!Number.isFinite(i) || i < 0) return ECHARTS_DEFAULT_PALETTE[0]

  const sis = ui?.seriesItemStyles
  if (sis && typeof sis === 'object') {
    const raw = sis[String(i)] ?? sis[i]
    const custom = raw?.color
    if (custom != null && String(custom).trim()) return String(custom).trim()
  }

  const globalBar = String(ui?.barColor || '').trim()
  const t = String(chartType || 'bar').toLowerCase()
  const hasAnyItemOverride =
    sis && typeof sis === 'object' && Object.keys(sis).some((k) => sis[k]?.color)

  if (t === 'bar' || t === 'line') {
    // 柱/折线：有全局统一色时，未单独配色的项也沿用该色
    if (globalBar) return globalBar
    // 单系列且无分项覆盖：整图同色（encode 路径）
    if (!hasAnyItemOverride) return ECHARTS_DEFAULT_PALETTE[0]
    // 有分项覆盖但该项未设且无全局色：ECharts 按项默认调色
    return ECHARTS_DEFAULT_PALETTE[i % ECHARTS_DEFAULT_PALETTE.length]
  }

  if (t === 'pie') {
    return ECHARTS_DEFAULT_PALETTE[i % ECHARTS_DEFAULT_PALETTE.length]
  }

  return ECHARTS_DEFAULT_PALETTE[i % ECHARTS_DEFAULT_PALETTE.length]
}

export function mergeChartStyle(raw) {
  const base = { ...DEFAULT_CHART_STYLE }
  if (!raw || typeof raw !== 'object') return base
  for (const [k, v] of Object.entries(raw)) {
    if (Object.prototype.hasOwnProperty.call(base, k) && v !== undefined && v !== null) {
      base[k] = v
    }
  }
  return base
}

export function mergeLayoutConstraints(raw) {
  const base = { ...DEFAULT_LAYOUT_CONSTRAINTS }
  if (!raw || typeof raw !== 'object') return base
  for (const [k, v] of Object.entries(raw)) {
    if (Object.prototype.hasOwnProperty.call(base, k) && v !== undefined && v !== null) {
      base[k] = v
    }
  }
  return base
}

export function ensureLayoutOrigin(item) {
  if (!item || typeof item !== 'object') return null
  if (item.layoutOrigin && typeof item.layoutOrigin === 'object') {
    return {
      x: Number(item.layoutOrigin.x) || 0,
      y: Number(item.layoutOrigin.y) || 0,
      w: Number(item.layoutOrigin.w) || 12,
      h: Number(item.layoutOrigin.h) || 4
    }
  }
  return {
    x: Number(item.x) || 0,
    y: Number(item.y) || 0,
    w: Number(item.w) || 12,
    h: Number(item.h) || 4
  }
}

export function sanitizeChartStyleForApi(raw) {
  const merged = mergeChartStyle(raw)
  const out = {}
  for (const [k, v] of Object.entries(merged)) {
    if (v !== DEFAULT_CHART_STYLE[k]) out[k] = v
  }
  return Object.keys(out).length ? out : null
}

export function sanitizeLayoutConstraintsForApi(raw) {
  const merged = mergeLayoutConstraints(raw)
  const out = {}
  for (const [k, v] of Object.entries(merged)) {
    if (v !== DEFAULT_LAYOUT_CONSTRAINTS[k]) out[k] = v
  }
  return Object.keys(out).length ? out : null
}

export function sanitizeLayoutOriginForApi(raw) {
  if (!raw || typeof raw !== 'object') return null
  const o = {
    x: Math.round(Number(raw.x) || 0),
    y: Math.round(Number(raw.y) || 0),
    w: Math.max(1, Math.round(Number(raw.w) || 12)),
    h: Math.max(1, Math.round(Number(raw.h) || 4))
  }
  return o
}

/** 从 layout item 构建传给 ECharts 的 ui 覆盖对象 */
export function chartUiFromGridItem(item) {
  if (!item || typeof item !== 'object') return {}
  const o = {}
  const c = String(item.barColor || '').trim()
  if (c) o.barColor = c
  const w = Number(item.barMaxWidth)
  if (Number.isFinite(w) && w >= 8) o.barMaxWidth = w
  const sis = item.seriesItemStyles
  if (sis && typeof sis === 'object' && Object.keys(sis).length) o.seriesItemStyles = sis
  const style = mergeChartStyle(item.chartStyle)
  o.chartStyle = style
  return o
}

/** 克隆 layout item 上的样式相关字段 */
export function cloneChartStyleFields(item) {
  if (!item) return {}
  const o = {}
  if (item.barColor) o.barColor = item.barColor
  if (item.barMaxWidth != null) o.barMaxWidth = item.barMaxWidth
  if (item.seriesItemStyles) o.seriesItemStyles = { ...item.seriesItemStyles }
  if (item.chartStyle) o.chartStyle = { ...mergeChartStyle(item.chartStyle) }
  if (item.title) o.title = item.title
  return o
}

/** 克隆 layout item 上的布局相关字段 */
export function cloneLayoutFields(item) {
  if (!item) return {}
  return {
    x: item.x,
    y: item.y,
    w: item.w,
    h: item.h,
    static: item.static,
    layoutConstraints: item.layoutConstraints ? { ...mergeLayoutConstraints(item.layoutConstraints) } : undefined,
    layoutOrigin: item.layoutOrigin ? { ...item.layoutOrigin } : undefined
  }
}

export function cloneChartFieldsForApi(item) {
  const o = {}
  const bc = String(item?.barColor ?? '').trim()
  if (bc) o.barColor = bc
  const bmw = Number(item?.barMaxWidth)
  if (Number.isFinite(bmw) && bmw >= 8 && bmw <= 160) o.barMaxWidth = Math.round(bmw)
  const sis = sanitizeSeriesItemStylesForApi(item?.seriesItemStyles)
  if (sis) o.seriesItemStyles = sis
  const cs = sanitizeChartStyleForApi(item?.chartStyle)
  if (cs) o.chartStyle = cs
  const lc = sanitizeLayoutConstraintsForApi(item?.layoutConstraints)
  if (lc) o.layoutConstraints = lc
  const lo = sanitizeLayoutOriginForApi(item?.layoutOrigin)
  if (lo) o.layoutOrigin = lo
  return o
}
