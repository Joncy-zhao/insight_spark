/** 解析看板 layout_json：支持 v2 网格 items 与 v1 cards 共存 */

import { sanitizeSeriesItemStylesForApi, normalizeChartType } from './chartOptionFromSnapshot.js'
import { formatChartRecommendationExplain } from './chartRecommendationText.js'
import {
  chartUiFromGridItem,
  cloneChartFieldsForApi,
  ensureLayoutOrigin
} from './chartUiConfig.js'
import {
  DASHBOARD_GRID_COL_NUM,
  DASHBOARD_GRID_DEFAULT_ITEM_H,
  DASHBOARD_GRID_DEFAULT_ITEM_W,
  DASHBOARD_GRID_LEGACY_COL_NUM,
  dashboardCanvasStageMinHeight
} from './dashboardGridCanvas.js'
import {
  cloneWidgetFieldsFromItem,
  serializeWidgetFieldsForApi,
  isBasicWidgetItem
} from './dashboardWidgetVideo.js'

export { chartUiFromGridItem }
import { basicWidgetLabelForItem, resolveBasicWidgetEntry } from './dashboardBasicWidgetRegistry.js'

export {
  DASHBOARD_GRID_COL_NUM,
  DASHBOARD_GRID_ROW_HEIGHT,
  DASHBOARD_GRID_MARGIN,
  DASHBOARD_GRID_DEFAULT_ITEM_W,
  DASHBOARD_GRID_DEFAULT_ITEM_H,
  DASHBOARD_CANVAS_CHROME_PX,
  dashboardCanvasStageMinHeight
} from './dashboardGridCanvas.js'

export const CANVAS_BACKGROUND_TYPES = Object.freeze({
  NONE: 'none',
  COLOR: 'color',
  IMAGE: 'image',
  URL: 'url'
})

/** 画布背景图（本地上传）单张上限 5MB */
export const CANVAS_BACKGROUND_IMAGE_MAX_BYTES = 5 * 1024 * 1024
export const CANVAS_BACKGROUND_IMAGE_MAX_DATA_URL_LEN = Math.ceil(CANVAS_BACKGROUND_IMAGE_MAX_BYTES * (4 / 3)) + 64

const VALID_BACKGROUND_TYPES = new Set(Object.values(CANVAS_BACKGROUND_TYPES))

const DEFAULT_CANVAS_STYLE = Object.freeze({
  backgroundType: CANVAS_BACKGROUND_TYPES.COLOR,
  backgroundColor: '#f3f4f6',
  backgroundImageDataUrl: '',
  backgroundImageUrl: '',
  borderRadius: 8,
  padding: 8
})

function cssBackgroundUrl(value) {
  const v = String(value || '').trim()
  if (!v) return ''
  const safe = v.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
  return `url("${safe}")`
}

function inferBackgroundType(input, fallback = CANVAS_BACKGROUND_TYPES.COLOR) {
  if (!input || typeof input !== 'object') return fallback
  const t = String(input.backgroundType || '').trim()
  if (VALID_BACKGROUND_TYPES.has(t)) return t
  if (input.backgroundImageDataUrl) return CANVAS_BACKGROUND_TYPES.IMAGE
  if (input.backgroundImageUrl) return CANVAS_BACKGROUND_TYPES.URL
  if (input.backgroundColor) return CANVAS_BACKGROUND_TYPES.COLOR
  return fallback
}

function clamp(n, min, max) {
  const x = Number(n)
  if (!Number.isFinite(x)) return min
  return Math.min(max, Math.max(min, x))
}

/**
 * 设计看板画布外观（layout_json 根级 canvasStyle）
 * @param {object} [input]
 */
export function normalizeCanvasStyle(input) {
  const d = DEFAULT_CANVAS_STYLE
  if (!input || typeof input !== 'object') {
    return { ...d }
  }
  let padding = clamp(input.padding ?? d.padding, 0, 64)
  let borderRadius = clamp(input.borderRadius ?? d.borderRadius, 0, 48)
  // 旧版默认 16px 内边距 + 12px 圆角 → 收紧为助睿风格 8px
  if (input && Number(input.padding) === 16 && Number(input.borderRadius) === 12) {
    padding = 8
    borderRadius = 8
  }
  let backgroundType = inferBackgroundType(input, d.backgroundType)
  if (!('backgroundType' in input)) {
    backgroundType = CANVAS_BACKGROUND_TYPES.COLOR
  }
  const bgImage = String(input.backgroundImageDataUrl || '').trim()
  const bgUrl = String(input.backgroundImageUrl || '').trim().slice(0, 2048)
  return {
    backgroundType,
    backgroundColor: String(input.backgroundColor || d.backgroundColor).slice(0, 128),
    backgroundImageDataUrl:
      bgImage.length > CANVAS_BACKGROUND_IMAGE_MAX_DATA_URL_LEN ? '' : bgImage,
    backgroundImageUrl: bgUrl,
    borderRadius,
    padding
  }
}

/** 画布容器行内样式：无边框，圆角 + 内边距 + 至少一屏高 */
export function buildCanvasStageInlineStyle(canvasStyle) {
  const s = normalizeCanvasStyle(canvasStyle)
  const base = {
    border: 'none',
    borderRadius: `${s.borderRadius}px`,
    padding: `${s.padding}px`,
    minHeight: dashboardCanvasStageMinHeight(),
    boxSizing: 'border-box',
    width: '100%'
  }
  if (s.backgroundType === CANVAS_BACKGROUND_TYPES.NONE) {
    return { ...base, backgroundColor: 'transparent' }
  }
  if (s.backgroundType === CANVAS_BACKGROUND_TYPES.IMAGE && s.backgroundImageDataUrl) {
    return {
      ...base,
      backgroundColor: 'transparent',
      backgroundImage: cssBackgroundUrl(s.backgroundImageDataUrl),
      backgroundSize: 'cover',
      backgroundPosition: 'center',
      backgroundRepeat: 'no-repeat'
    }
  }
  if (s.backgroundType === CANVAS_BACKGROUND_TYPES.URL && s.backgroundImageUrl) {
    return {
      ...base,
      backgroundColor: 'transparent',
      backgroundImage: cssBackgroundUrl(s.backgroundImageUrl),
      backgroundSize: 'cover',
      backgroundPosition: 'center',
      backgroundRepeat: 'no-repeat'
    }
  }
  return { ...base, backgroundColor: s.backgroundColor }
}

export function parseDashboardLayout(layoutJson) {
  const text = String(layoutJson || '').trim()
  if (!text) {
    return {
      version: '1.0',
      items: [],
      cards: [],
      gridCols: DASHBOARD_GRID_LEGACY_COL_NUM,
      canvasStyle: normalizeCanvasStyle(),
      raw: null
    }
  }
  try {
    const root = JSON.parse(text)
    if (Array.isArray(root)) {
      return {
        version: '2.0',
        items: root,
        cards: [],
        gridCols: DASHBOARD_GRID_LEGACY_COL_NUM,
        canvasStyle: normalizeCanvasStyle(),
        raw: root
      }
    }
    if (root && typeof root === 'object') {
      const items = Array.isArray(root.items) ? root.items : []
      const cards = Array.isArray(root.cards) ? root.cards : []
      const gridCols = Number(root.gridCols) || DASHBOARD_GRID_LEGACY_COL_NUM
      return {
        version: String(root.version || '2.0'),
        items,
        cards,
        gridCols,
        canvasStyle: normalizeCanvasStyle(root.canvasStyle),
        raw: root
      }
    }
  } catch {
    // ignore
  }
  return {
    version: '1.0',
    items: [],
    cards: [],
    gridCols: DASHBOARD_GRID_LEGACY_COL_NUM,
    canvasStyle: normalizeCanvasStyle(),
    raw: null
  }
}

/** 与列表「图表卡片」统计一致：来自 layout_json.cards 的可渲染图表卡片 */
export function extractLegacyChartCards(layoutJson, scope = 'layout') {
  const p = parseDashboardLayout(layoutJson)
  const cards = Array.isArray(p.cards) ? p.cards : []
  return cards
    .map((raw, index) => {
      if (!raw || typeof raw !== 'object') return null
      const cardType = String(raw.type || 'chart').toLowerCase()
      const data = Array.isArray(raw.data) ? raw.data : []
      if (cardType !== 'chart' || !data.length) return null
      const id = String(raw.cardId || `${scope}-${index}`)
      return {
        _renderKey: `${scope}-${id}-${index}`,
        cardId: id,
        title: String(raw.title || `图表${index + 1}`),
        chartType: normalizeChartType(raw.chartType || 'bar'),
        tableName: String(raw.tableName || ''),
        sql: String(raw.sql || ''),
        data
      }
    })
    .filter(Boolean)
}

/** 列表展示用：网格中非基础组件（文本/视频等）的图表槽位数，不含 widgetKind 占位 */
export function countChartSlotsForDashboardRow(layoutJson) {
  const p = parseDashboardLayout(layoutJson)
  if (Array.isArray(p.items) && p.items.length > 0) {
    return p.items.filter((item) => !isBasicWidgetItem(item)).length
  }
  return extractLegacyChartCards(layoutJson, 'count').length
}

/** 列表展示用：layout 中带 widgetKind 的基础组件数量（文本、视频等） */
export function countBasicWidgetSlotsForDashboardRow(layoutJson) {
  const p = parseDashboardLayout(layoutJson)
  if (!Array.isArray(p.items) || !p.items.length) return 0
  return p.items.filter((item) => isBasicWidgetItem(item)).length
}

/**
 * 基础组件列表预览：从 layout items 提取 widgetKind 项
 * @param {string} layoutJson
 * @param {string} scope 渲染 key 前缀
 */
export function buildBasicWidgetPreviewCards(layoutJson, scope = 'widget-list') {
  const p = parseDashboardLayout(layoutJson)
  const items = Array.isArray(p.items) ? p.items : []
  return items
    .filter((item) => isBasicWidgetItem(item))
    .map((item, index) => {
      const entry = resolveBasicWidgetEntry(item)
      const config = entry ? entry.configForItem(item) : {}
      const cid = String(item.i ?? index).trim()
      const label = basicWidgetLabelForItem(item)
      return {
        _renderKey: `${scope}-${cid}-${index}`,
        itemId: cid,
        widgetKind: String(item.widgetKind || '').trim(),
        label,
        title: String(item.title || '').trim() || `${label} ${index + 1}`,
        config,
        layout: {
          x: Number(item.x) || 0,
          y: Number(item.y) || 0,
          w: Number(item.w) || 0,
          h: Number(item.h) || 0
        }
      }
    })
}

function buildUnavailablePreviewCard(item, index, scope, reason) {
  const cid = String(item?.i ?? index).trim()
  const title = String(item?.title || '').trim() || `图表 ${index + 1}`
  return {
    _previewKind: 'unavailable',
    _renderKey: `${scope}-grid-${cid}-${index}-na`,
    cardId: cid,
    title,
    chartType: 'bar',
    tableName: '',
    sql: '',
    data: [],
    unavailableMessage: String(reason || '图表暂不可用')
  }
}

export function cloneLayoutForGrid(items, gridCols = DASHBOARD_GRID_COL_NUM) {
  if (!Array.isArray(items)) return []
  const sourceCols = Number(gridCols) || DASHBOARD_GRID_LEGACY_COL_NUM
  const scale = sourceCols === DASHBOARD_GRID_COL_NUM ? 1 : DASHBOARD_GRID_COL_NUM / sourceCols
  const defaultW = scale === 1 ? DASHBOARD_GRID_DEFAULT_ITEM_W : 6
  return items.map((it) => {
    const rawW = Number(it.w) || defaultW
    const rawX = Number(it.x) || 0
    const row = {
      i: String(it.i),
      x: Math.max(0, Math.round(rawX * scale)),
      y: Number(it.y) || 0,
      w: Math.min(DASHBOARD_GRID_COL_NUM, Math.max(1, Math.round(rawW * scale))),
      h: Math.max(1, Number(it.h) || DASHBOARD_GRID_DEFAULT_ITEM_H),
      static: Boolean(it.static)
    }
    const t = String(it.title ?? '').trim()
    if (t) row.title = t
    Object.assign(row, cloneChartFieldsForApi(it))
    if (!row.layoutOrigin) {
      const lo = ensureLayoutOrigin(it)
      if (lo) row.layoutOrigin = lo
    }
    Object.assign(row, cloneWidgetFieldsFromItem(it))
    return row
  })
}

/**
 * 仅有组件表记录、layout_json 尚无 items 时，用 position_config / 默认栅格占位生成初始 items。
 * @param {Array<{ id?: number, chartId?: number, positionConfig?: string }>} components
 */
export function buildItemsFromComponents(components, gridCols = DASHBOARD_GRID_LEGACY_COL_NUM) {
  if (!Array.isArray(components) || !components.length) return []
  const sourceCols = Number(gridCols) || DASHBOARD_GRID_LEGACY_COL_NUM
  const scale = sourceCols === DASHBOARD_GRID_COL_NUM ? 1 : DASHBOARD_GRID_COL_NUM / sourceCols
  let yCursor = 0
  return components.map((c) => {
    const id = String(
      c.id ?? c.ID ?? c.componentId ?? c.component_id ?? c.dashboardComponentId ?? ''
    ).trim()
    let w = DASHBOARD_GRID_DEFAULT_ITEM_W
    let h = DASHBOARD_GRID_DEFAULT_ITEM_H
    const raw = c.positionConfig ?? c.position_config
    if (raw) {
      try {
        const pc = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (pc && typeof pc === 'object') {
          const legacyW = Math.max(1, Math.round(Number(pc.w) || 6))
          w = Math.min(DASHBOARD_GRID_COL_NUM, Math.max(1, Math.round(legacyW * scale)))
          h = Math.max(1, Math.round(Number(pc.h) || DASHBOARD_GRID_DEFAULT_ITEM_H))
        }
      } catch {
        // keep defaults
      }
    }
    const item = { i: id, x: 0, y: yCursor, w, h }
    yCursor += h
    return item
  })
}

/** 写入 is_dashboard.layout_json：v2 items，可选保留旧版 cards；根级 canvasStyle 为画布外观 */
export function serializeLayoutForApi(items, cards, canvasStyle) {
  const cleanItems = (Array.isArray(items) ? items : []).map((it) => {
    const o = {
      i: String(it.i),
      x: Math.round(Number(it.x) || 0),
      y: Math.round(Number(it.y) || 0),
      w: Math.min(DASHBOARD_GRID_COL_NUM, Math.max(1, Math.round(Number(it.w) || DASHBOARD_GRID_DEFAULT_ITEM_W))),
      h: Math.max(1, Math.round(Number(it.h) || DASHBOARD_GRID_DEFAULT_ITEM_H))
    }
    if (it.static) o.static = true
    const t = String(it.title ?? '').trim()
    if (t) o.title = t
    Object.assign(o, cloneChartFieldsForApi(it))
    Object.assign(o, serializeWidgetFieldsForApi(it))
    return o
  })
  const root = { version: '2.0', gridCols: DASHBOARD_GRID_COL_NUM, items: cleanItems }
  if (Array.isArray(cards) && cards.length) root.cards = cards
  const cs = normalizeCanvasStyle(canvasStyle)
  root.canvasStyle = {
    backgroundType: cs.backgroundType,
    borderRadius: cs.borderRadius,
    padding: cs.padding
  }
  if (cs.backgroundType === CANVAS_BACKGROUND_TYPES.COLOR) {
    root.canvasStyle.backgroundColor = cs.backgroundColor
  }
  if (cs.backgroundType === CANVAS_BACKGROUND_TYPES.IMAGE && cs.backgroundImageDataUrl) {
    root.canvasStyle.backgroundImageDataUrl = cs.backgroundImageDataUrl
  }
  if (cs.backgroundType === CANVAS_BACKGROUND_TYPES.URL && cs.backgroundImageUrl) {
    root.canvasStyle.backgroundImageUrl = cs.backgroundImageUrl
  }
  try {
    return JSON.stringify(root)
  } catch {
    return '{"version":"2.0","items":[]}'
  }
}

/**
 * 以 layout_json.items 为准；若某看板组件不在 items 中，则追加到网格底部。
 */
export function mergeGridItemsWithComponents(layoutItems, components, gridCols = DASHBOARD_GRID_COL_NUM) {
  const base =
    Array.isArray(layoutItems) && layoutItems.length > 0
      ? cloneLayoutForGrid(layoutItems, gridCols)
      : buildItemsFromComponents(components || [], gridCols)
  const existing = new Set(base.map((x) => String(x.i)))
  let bottom = 0
  for (const it of base) {
    bottom = Math.max(bottom, (Number(it.y) || 0) + (Number(it.h) || 1))
  }
  for (const c of components || []) {
    const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
    if (!id || existing.has(id)) continue
    base.push({ i: id, x: 0, y: bottom, w: DASHBOARD_GRID_DEFAULT_ITEM_W, h: DASHBOARD_GRID_DEFAULT_ITEM_H })
    bottom += 4
    existing.add(id)
  }
  return base
}

/** layout_json.items 上与 DashboardChart 一致的 UI 覆盖（柱色、柱宽、分项色） */
function parseMaybeJson(raw) {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try {
    const parsed = JSON.parse(String(raw))
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function compactText(value, max = 160) {
  const text = String(value || '').trim()
  if (!text) return ''
  return text.length > max ? `${text.slice(0, max)}...` : text
}

function buildChartSourceMeta(payload = {}, snapshot = {}, item = {}) {
  const ruleName = compactText(snapshot.chartRuleName || payload.chartRuleName)
  const ruleCode = compactText(snapshot.chartRuleCode || payload.chartRuleCode)
  const scenarioType = compactText(snapshot.chartScenarioType || payload.chartScenarioType)
  const explain = compactText(
    formatChartRecommendationExplain(snapshot.chartRecommendationExplain || payload.chartRecommendationExplain, {
      ruleCode,
      ruleName,
      scenarioType,
      status: snapshot.chartRecommendationStatus || payload.chartRecommendationStatus
    }),
    220
  )
  const hasRule = Boolean(ruleName || ruleCode || scenarioType)
  if (hasRule) {
    return {
      sourceType: 'AI_RULE_SNAPSHOT',
      sourceLabel: 'AI 推荐规则',
      snapshotLabel: '历史快照',
      ruleName,
      ruleCode,
      scenarioType,
      explain,
      detail: [ruleName || ruleCode, scenarioType].filter(Boolean).join(' · ')
    }
  }
  if (snapshot && Object.keys(snapshot).length) {
    return {
      sourceType: 'HISTORY_SNAPSHOT',
      sourceLabel: '历史快照',
      snapshotLabel: '历史快照',
      ruleName: '',
      ruleCode: '',
      scenarioType: '',
      explain: '',
      detail: compactText(snapshot.message || payload.queryText || item.title || '')
    }
  }
  return {
    sourceType: 'MANUAL',
    sourceLabel: '手动配置',
    snapshotLabel: '',
    ruleName: '',
    ruleCode: '',
    scenarioType: '',
    explain: '',
    detail: compactText(item.title || '')
  }
}

function toNumber(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function formatAxisValue(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return value
  if (Math.abs(n) >= 10000) return `${(n / 10000).toFixed(1)}w`
  return `${n}`
}

function alertAxisName(item, index) {
  const candidates = [
    item?.bucketName,
    item?.bucket,
    item?.period,
    item?.date,
    item?.time,
    item?.triggeredAt,
    item?.createdAt,
    item?.category,
    item?.x,
    item?.name
  ]
  for (const value of candidates) {
    const text = String(value ?? '').trim()
    if (text) return text
  }
  return `第${index + 1}期`
}

function alertSeriesValue(item) {
  return item?.value ?? item?.actualValue ?? item?.metricValue ?? item?.currentValue ?? item?.history ?? 0
}

function alertIsAbnormal(item, threshold = 0, operator = 'lt') {
  if (typeof item?.triggered === 'boolean') return item.triggered
  const value = toNumber(alertSeriesValue(item))
  const limit = Number(threshold)
  if (!Number.isFinite(value)) return false
  if (operator === 'gt') return Number.isFinite(limit) && value > limit
  if (operator === 'zscore') return Math.abs(Number(item?.zScore ?? item?.z_score ?? 0)) >= 3
  return Number.isFinite(limit) && value < limit
}

function alertSeriesPoint(item, threshold = 0, operator = 'lt') {
  const abnormal = alertIsAbnormal(item, threshold, operator)
  return {
    value: toNumber(alertSeriesValue(item)),
    abnormal,
    symbolSize: abnormal ? 11 : 6,
    itemStyle: abnormal
      ? {
          color: '#dc2626',
          borderColor: '#ffffff',
          borderWidth: 2,
          shadowBlur: 8,
          shadowColor: 'rgba(220, 38, 38, 0.35)'
        }
      : undefined,
    emphasis: { scale: abnormal ? 1.45 : 1.2 }
  }
}

function alertTooltipFormatter(params = []) {
  const items = Array.isArray(params) ? params : [params]
  const title = items[0]?.axisValueLabel || items[0]?.name || ''
  const lines = items.map(item => {
    const data = item?.data && typeof item.data === 'object' ? item.data : {}
    const value = data.value ?? item?.value ?? '--'
    const suffix = data.abnormal ? ' <span style="color:#dc2626;font-weight:600;">异常</span>' : ''
    return `${item?.marker || ''}${item?.seriesName || '检测值'}：${formatAxisValue(value)}${suffix}`
  })
  return [title, ...lines].filter(Boolean).join('<br/>')
}

function alertYAxisMax(rows = [], threshold = 0) {
  const values = rows
    .map(item => toNumber(alertSeriesValue(item)))
    .filter(value => Number.isFinite(value))
  const limit = Number(threshold)
  if (Number.isFinite(limit) && limit > 0) values.push(limit)
  if (!values.length) return undefined
  const max = Math.max(...values)
  if (max <= 0) return undefined
  return Math.ceil(max * 1.12)
}

function normalizeAdvancedAnalysisType(value) {
  const text = String(value || '').toLowerCase()
  if (text.includes('what')) return 'whatIf'
  if (text.includes('alert') || text.includes('warning')) return 'alert'
  return 'forecast'
}

export function buildAdvancedAnalysisOption(analysis) {
  const type = normalizeAdvancedAnalysisType(analysis?.type)
  const rows = Array.isArray(analysis?.series) ? analysis.series : []
  if (type === 'forecast') {
    const total = rows.length
    const forecastCount = rows.filter(item => item && item.forecast != null).length
    const visibleCount = Math.min(total, Math.max(24, forecastCount + 18))
    const startValue = total > 24 ? Math.max(0, total - visibleCount) : 0
    const endValue = Math.max(0, total - 1)
    return {
      tooltip: { trigger: 'axis', confine: true },
      legend: { top: 4, data: ['历史值', '预测值', '置信上界', '置信下界'] },
      grid: { left: 54, right: 24, top: 48, bottom: 48, containLabel: true },
      xAxis: {
        type: 'category',
        data: rows.map(item => item.name),
        axisLabel: { hideOverlap: true, interval: 'auto', rotate: rows.length > 48 ? 35 : 0 }
      },
      yAxis: { type: 'value', axisLabel: { formatter: formatAxisValue } },
      dataZoom: [
        { type: 'inside', startValue, endValue },
        { type: 'slider', height: 16, bottom: 10, startValue, endValue }
      ],
      series: [
        {
          name: '历史值',
          type: 'line',
          smooth: true,
          data: rows.map(item => item.history),
          connectNulls: false,
          showSymbol: false,
          lineStyle: { color: '#2563eb', width: 2 },
          itemStyle: { color: '#2563eb' }
        },
        {
          name: '预测值',
          type: 'line',
          smooth: true,
          data: rows.map(item => item.forecast),
          connectNulls: false,
          showSymbol: true,
          symbolSize: 7,
          sampling: 'lttb',
          lineStyle: { color: '#16a34a', width: 2, type: 'dashed' },
          itemStyle: { color: '#16a34a' }
        },
        {
          name: '置信上界',
          type: 'line',
          data: rows.map(item => item.upper),
          symbol: 'none',
          showSymbol: false,
          lineStyle: { color: '#93c5fd', width: 1 },
          areaStyle: { color: 'rgba(147, 197, 253, 0.18)' }
        },
        {
          name: '置信下界',
          type: 'line',
          data: rows.map(item => item.lower),
          symbol: 'none',
          showSymbol: false,
          lineStyle: { color: '#93c5fd', width: 1 }
        }
      ]
    }
  }
  if (type === 'whatIf') {
    return {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true },
      legend: { top: 4 },
      grid: { left: 54, right: 24, top: 48, bottom: 34, containLabel: true },
      xAxis: { type: 'category', data: rows.map(item => item.name) },
      yAxis: { type: 'value', axisLabel: { formatter: formatAxisValue } },
      series: [{
        name: '业务结果',
        type: 'bar',
        barMaxWidth: 34,
        data: rows.map(item => ({
          value: toNumber(item?.value),
          itemStyle: {
            color: toNumber(item?.value) < 0 ? '#f97316' : '#14b8a6',
            borderRadius: toNumber(item?.value) < 0 ? [0, 0, 5, 5] : [5, 5, 0, 0]
          }
        }))
      }]
    }
  }
  const threshold = toNumber(analysis?.params?.threshold ?? analysis?.threshold)
  const operator = String(analysis?.params?.operator ?? analysis?.operator ?? 'lt').toLowerCase()
  return {
    tooltip: { trigger: 'axis', confine: true, formatter: alertTooltipFormatter },
    legend: { top: 4, left: 'center', data: ['检测值'] },
    grid: { left: 54, right: 24, top: 58, bottom: 42, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map(alertAxisName),
      axisLabel: { hideOverlap: true, interval: 'auto', rotate: rows.length > 18 ? 25 : 0 }
    },
    yAxis: {
      type: 'value',
      max: alertYAxisMax(rows, threshold),
      axisLabel: { formatter: formatAxisValue }
    },
    series: [{
      name: '检测值',
      type: 'line',
      smooth: true,
      data: rows.map(item => alertSeriesPoint(item, threshold, operator)),
      markLine: threshold > 0
        ? {
            symbol: 'none',
            silent: true,
            data: [{ yAxis: threshold, name: '阈值' }],
            lineStyle: { color: '#ef4444', type: 'dashed' },
            label: { formatter: '阈值', color: '#ef4444' }
          }
        : undefined,
      lineStyle: { color: '#f97316', width: 2 },
      itemStyle: { color: '#f97316' },
      areaStyle: { color: 'rgba(249, 115, 22, 0.10)' }
    }]
  }
}

export function buildAdvancedAnalysisPreviewCard(comp, item, index, scope) {
  const artifactType = String(comp?.artifactType || comp?.artifact_type || '').toUpperCase()
  const artifact = parseMaybeJson(comp?.artifact)
  const rawAnalysis =
    artifact?.advancedAnalysis && typeof artifact.advancedAnalysis === 'object'
      ? artifact.advancedAnalysis
      : artifact
  const type = normalizeAdvancedAnalysisType(rawAnalysis?.type || artifact?.type || artifactType)
  const rows = Array.isArray(rawAnalysis?.series) ? rawAnalysis.series : []
  if (!artifactType.startsWith('ADVANCED_') && artifact?.module !== 'advancedAnalysis') {
    return null
  }
  if (!rows.length) {
    return null
  }
  const title =
    String(item?.title || '').trim() ||
    String(rawAnalysis?.title || artifact?.title || '').trim() ||
    (type === 'whatIf' ? 'What-if 推演' : type === 'alert' ? '智能预警' : '时序预测')
    return {
      _previewKind: 'advancedAnalysis',
    _renderKey: `${scope}-advanced-${String(comp?.id || comp?.componentId || index)}-${index}`,
    cardId: String(comp?.id || comp?.componentId || ''),
    title,
    chartType: type === 'whatIf' ? 'bar' : 'line',
    tableName: String(rawAnalysis?.tableName || artifact?.tableName || ''),
    sql: '',
      data: rows.map(row => ({
        name: String(row?.name || row?.label || ''),
        value: toNumber(row?.forecast ?? row?.value ?? row?.history)
      })),
      sourceMeta: {
        sourceType: 'ADVANCED_ANALYSIS',
        sourceLabel: '高级分析快照',
        snapshotLabel: '历史快照',
        detail: type === 'whatIf' ? 'What-if 推演' : type === 'alert' ? '智能预警' : '时序预测'
      },
      advancedAnalysis: rawAnalysis,
      option: buildAdvancedAnalysisOption(rawAnalysis),
      chartUi: chartUiFromGridItem(item)
  }
}

/**
 * 图表预览：旧版内嵌 cards + 网格 items 对应的对话图表（components + charts-batch 载荷）。
 * @param {string} layoutJson
 * @param {Array} components is_dashboard_component 列表
 * @param {Record<string, object>} chartPayloadById chartId -> charts-batch 单行
 * @param {string} scope 渲染 key 前缀
 */
export function buildUnifiedPreviewCards(layoutJson, components, chartPayloadById, scope = 'preview') {
  const legacy = extractLegacyChartCards(layoutJson, scope).map((c) => ({ ...c, _previewKind: 'legacy' }))
  const p = parseDashboardLayout(layoutJson)
  const mergedItems = mergeGridItemsWithComponents(
    Array.isArray(p.items) ? p.items : [],
    components || [],
    p.gridCols
  )
  const payloadMap = chartPayloadById && typeof chartPayloadById === 'object' ? chartPayloadById : {}
  const gridCards = []

  mergedItems.forEach((item, index) => {
    if (isBasicWidgetItem(item)) return

    const cid = String(item.i || '').trim()
    const comp = (components || []).find((c) => {
      const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
      return id === cid
    })
    if (!comp) {
      gridCards.push(buildUnavailablePreviewCard(item, index, scope, '画布组件未注册'))
      return
    }
    const advancedCard = buildAdvancedAnalysisPreviewCard(comp, item, index, scope)
    if (advancedCard) {
      gridCards.push(advancedCard)
      return
    }
    const rawChartId = comp.chartId ?? comp.chart_id ?? comp.CHART_ID
    if (rawChartId == null) {
      gridCards.push(buildUnavailablePreviewCard(item, index, scope, '未关联 chart_id'))
      return
    }
    const chartIdStr = String(rawChartId).trim()
    const payload = payloadMap[chartIdStr]
    if (!payload) {
      gridCards.push(buildUnavailablePreviewCard(item, index, scope, '图表数据暂不可用'))
      return
    }

    let snap = payload.chartSnapshot
    if (typeof snap === 'string') {
      try {
        snap = JSON.parse(snap)
      } catch {
        snap = {}
      }
    }
    if (!snap || typeof snap !== 'object') snap = {}

    const title =
      String(item.title || '').trim() ||
      String(snap.message || '').slice(0, 120) ||
      `图表 ${gridCards.length + 1}`

    gridCards.push({
      _previewKind: 'snapshot',
      _renderKey: `${scope}-grid-${cid}-${index}`,
      cardId: cid,
      title,
      chartType: normalizeChartType(payload.chartType || snap.chartType),
      tableName: String(snap.tableName || payload.queryTableName || ''),
      sql: String(snap.sql || payload.generatedSql || ''),
      payloadRow: payload,
      sourceMeta: buildChartSourceMeta(payload, snap, item),
      chartUi: chartUiFromGridItem(item)
    })
  })

  return [...legacy, ...gridCards]
}

/** 图表预览网格：1 张居中、2 张并排、≥3 每行最多 3 列 */
export function dashChartPreviewGridClass(count) {
  const n = Math.max(0, Number(count) || 0)
  if (n <= 1) return 'dash-chart-grid--single'
  if (n === 2) return 'dash-chart-grid--double'
  return 'dash-chart-grid--multi'
}

/**
 * 图表预览弹窗宽度（随列数收缩）；展开 SQL 时用全宽便于读代码。
 * @param {number} count 图表张数
 * @param {{ openSql?: boolean }} [options]
 */
export function previewChartDialogWidth(count, options = {}) {
  const { openSql = false } = options
  if (openSql) return '98%'
  const n = Math.max(0, Number(count) || 0)
  if (n === 0) return 'min(480px, calc(100vw - 24px))'
  const cols = n >= 3 ? 3 : Math.max(1, n)
  const cellW = 460
  const gap = 16
  const chrome = 88
  let w = cols * cellW + (cols - 1) * gap + chrome
  if (n === 1) w = Math.max(w, 820)
  return `min(${Math.ceil(w)}px, calc(100vw - 24px))`
}
