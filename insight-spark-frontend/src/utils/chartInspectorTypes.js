import { normalizeChartType } from './chartOptionFromSnapshot.js'

/** 标准图表白名单：命中后展示完整专属配置面板 */
export const STANDARD_CHART_TYPES = [
  'bar',
  'line',
  'pie',
  'table',
  'radar',
  'scatter',
  'map',
  'metric'
]

export const CHART_TYPE_OPTIONS = [
  { value: 'auto', label: '自动识别' },
  { value: 'bar', label: '柱状图' },
  { value: 'line', label: '折线图' },
  { value: 'pie', label: '饼图 / 环形图' },
  { value: 'table', label: '数据表格' },
  { value: 'radar', label: '雷达图' },
  { value: 'scatter', label: '散点图' },
  { value: 'metric', label: '指标卡' },
  { value: 'map', label: '地图' },
  { value: 'custom', label: '自定义图表' }
]

const SERIES_COLOR_TYPES = new Set(['bar', 'line', 'pie'])
const AXIS_TYPES = new Set(['bar', 'line', 'radar', 'scatter'])
const BAR_TYPES = new Set(['bar'])
const LINE_TYPES = new Set(['line'])
const PIE_TYPES = new Set(['pie'])
const TABLE_TYPES = new Set(['table'])
const METRIC_TYPES = new Set(['metric'])
const RADAR_TYPES = new Set(['radar'])
const SCATTER_TYPES = new Set(['scatter'])
const MAP_TYPES = new Set(['map'])

/**
 * 是否在「图表专属样式」面板中展示专属控件（非空面板）
 */
export function chartHasSpecificStylePanel(chartType) {
  return (
    chartIsBar(chartType) ||
    chartIsLine(chartType) ||
    chartIsPie(chartType) ||
    chartIsTable(chartType) ||
    chartIsMetric(chartType) ||
    chartIsRadar(chartType) ||
    chartIsScatter(chartType) ||
    chartIsMap(chartType)
  )
}

export function chartIsMetric(chartType) {
  return METRIC_TYPES.has(chartType)
}

export function chartIsRadar(chartType) {
  return RADAR_TYPES.has(chartType)
}

export function chartIsScatter(chartType) {
  return SCATTER_TYPES.has(chartType)
}

export function chartIsMap(chartType) {
  return MAP_TYPES.has(chartType)
}

/**
 * @param {string} rawType chartType 原始值
 * @param {string} [manualOverride] 用户手动指定，'auto' 表示自动
 * @returns {{ mode: 'standard'|'fallback', chartType: string, label: string }}
 */
export function resolveChartInspectorMode(rawType, manualOverride = 'auto') {
  if (manualOverride && manualOverride !== 'auto') {
    if (manualOverride === 'custom') {
      return { mode: 'fallback', chartType: normalizeChartType(rawType), label: '自定义图表' }
    }
    const t = normalizeChartType(manualOverride)
    const opt = CHART_TYPE_OPTIONS.find((o) => o.value === t)
    return { mode: 'standard', chartType: t, label: opt?.label || t }
  }
  const t = normalizeChartType(rawType)
  if (STANDARD_CHART_TYPES.includes(t)) {
    const opt = CHART_TYPE_OPTIONS.find((o) => o.value === t)
    return { mode: 'standard', chartType: t, label: opt?.label || t }
  }
  return { mode: 'fallback', chartType: t, label: '自定义图表' }
}

export function chartSupportsSeriesColors(chartType) {
  return SERIES_COLOR_TYPES.has(chartType)
}

export function chartSupportsAxisSettings(chartType) {
  return AXIS_TYPES.has(chartType)
}

export function chartIsBar(chartType) {
  return BAR_TYPES.has(chartType)
}

export function chartIsLine(chartType) {
  return LINE_TYPES.has(chartType)
}

export function chartIsPie(chartType) {
  return PIE_TYPES.has(chartType)
}

export function chartIsTable(chartType) {
  return TABLE_TYPES.has(chartType)
}
