/** 解析看板 layout_json：支持 v2 网格 items 与 v1 cards 共存 */

import { sanitizeSeriesItemStylesForApi, normalizeChartType } from './chartOptionFromSnapshot.js'

const DEFAULT_CANVAS_STYLE = Object.freeze({
  backgroundColor: '#f3f4f6',
  borderColor: '#e5e7eb',
  borderWidth: 1,
  borderRadius: 12,
  padding: 16,
  minHeightVh: 60,
  /** 可选：本地静态图（Base64），画布右下角角标，无上传接口时仅前端读入 */
  brandLogoDataUrl: ''
})

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
    return { ...d, brandLogoDataUrl: '' }
  }
  const logo = String(input.brandLogoDataUrl || '').trim()
  return {
    backgroundColor: String(input.backgroundColor || d.backgroundColor).slice(0, 128),
    borderColor: String(input.borderColor || d.borderColor).slice(0, 128),
    borderWidth: clamp(input.borderWidth, 0, 16),
    borderRadius: clamp(input.borderRadius, 0, 48),
    padding: clamp(input.padding, 0, 64),
    minHeightVh: clamp(input.minHeightVh, 40, 92),
    brandLogoDataUrl: logo.length > 2_000_000 ? '' : logo
  }
}

export function parseDashboardLayout(layoutJson) {
  const text = String(layoutJson || '').trim()
  if (!text) {
    return { version: '1.0', items: [], cards: [], canvasStyle: normalizeCanvasStyle(), raw: null }
  }
  try {
    const root = JSON.parse(text)
    if (Array.isArray(root)) {
      return {
        version: '2.0',
        items: root,
        cards: [],
        canvasStyle: normalizeCanvasStyle(),
        raw: root
      }
    }
    if (root && typeof root === 'object') {
      const items = Array.isArray(root.items) ? root.items : []
      const cards = Array.isArray(root.cards) ? root.cards : []
      return {
        version: String(root.version || '2.0'),
        items,
        cards,
        canvasStyle: normalizeCanvasStyle(root.canvasStyle),
        raw: root
      }
    }
  } catch {
    // ignore
  }
  return { version: '1.0', items: [], cards: [], canvasStyle: normalizeCanvasStyle(), raw: null }
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
        chartType: String(raw.chartType || 'bar').toLowerCase(),
        tableName: String(raw.tableName || ''),
        sql: String(raw.sql || ''),
        data
      }
    })
    .filter(Boolean)
}

/** 列表展示用：优先 v2 items 数量，否则旧版 cards 中可渲染图表数 */
export function countChartSlotsForDashboardRow(layoutJson) {
  const p = parseDashboardLayout(layoutJson)
  if (Array.isArray(p.items) && p.items.length > 0) {
    return p.items.length
  }
  return extractLegacyChartCards(layoutJson, 'count').length
}

export function cloneLayoutForGrid(items) {
  if (!Array.isArray(items)) return []
  return items.map((it) => {
    const row = {
      i: String(it.i),
      x: Number(it.x) || 0,
      y: Number(it.y) || 0,
      w: Math.min(12, Math.max(1, Number(it.w) || 6)),
      h: Math.max(1, Number(it.h) || 4),
      static: Boolean(it.static)
    }
    const t = String(it.title ?? '').trim()
    if (t) row.title = t
    const bc = String(it.barColor ?? '').trim()
    if (bc) row.barColor = bc
    const bmw = Number(it.barMaxWidth)
    if (Number.isFinite(bmw) && bmw >= 8 && bmw <= 160) row.barMaxWidth = Math.round(bmw)
    const sis = sanitizeSeriesItemStylesForApi(it.seriesItemStyles)
    if (sis) row.seriesItemStyles = sis
    return row
  })
}

/**
 * 仅有组件表记录、layout_json 尚无 items 时，用 position_config / 默认栅格占位生成初始 items。
 * @param {Array<{ id?: number, chartId?: number, positionConfig?: string }>} components
 */
export function buildItemsFromComponents(components) {
  if (!Array.isArray(components) || !components.length) return []
  let yCursor = 0
  return components.map((c) => {
    const id = String(
      c.id ?? c.ID ?? c.componentId ?? c.component_id ?? c.dashboardComponentId ?? ''
    ).trim()
    let w = 6
    let h = 4
    const raw = c.positionConfig ?? c.position_config
    if (raw) {
      try {
        const pc = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (pc && typeof pc === 'object') {
          w = Math.min(12, Math.max(1, Math.round(Number(pc.w) || 6)))
          h = Math.max(1, Math.round(Number(pc.h) || 4))
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
      w: Math.min(12, Math.max(1, Math.round(Number(it.w) || 6))),
      h: Math.max(1, Math.round(Number(it.h) || 4))
    }
    if (it.static) o.static = true
    const t = String(it.title ?? '').trim()
    if (t) o.title = t
    const bc = String(it.barColor ?? '').trim()
    if (bc) o.barColor = bc
    const bmw = Number(it.barMaxWidth)
    if (Number.isFinite(bmw) && bmw >= 8 && bmw <= 160) o.barMaxWidth = Math.round(bmw)
    const sis = sanitizeSeriesItemStylesForApi(it.seriesItemStyles)
    if (sis) o.seriesItemStyles = sis
    return o
  })
  const root = { version: '2.0', items: cleanItems }
  if (Array.isArray(cards) && cards.length) root.cards = cards
  const cs = normalizeCanvasStyle(canvasStyle)
  root.canvasStyle = {
    backgroundColor: cs.backgroundColor,
    borderColor: cs.borderColor,
    borderWidth: cs.borderWidth,
    borderRadius: cs.borderRadius,
    padding: cs.padding,
    minHeightVh: cs.minHeightVh
  }
  if (cs.brandLogoDataUrl) root.canvasStyle.brandLogoDataUrl = cs.brandLogoDataUrl
  try {
    return JSON.stringify(root)
  } catch {
    return '{"version":"2.0","items":[]}'
  }
}

/**
 * 以 layout_json.items 为准；若某看板组件不在 items 中，则追加到网格底部。
 */
export function mergeGridItemsWithComponents(layoutItems, components) {
  const base =
    Array.isArray(layoutItems) && layoutItems.length > 0
      ? cloneLayoutForGrid(layoutItems)
      : buildItemsFromComponents(components || [])
  const existing = new Set(base.map((x) => String(x.i)))
  let bottom = 0
  for (const it of base) {
    bottom = Math.max(bottom, (Number(it.y) || 0) + (Number(it.h) || 1))
  }
  for (const c of components || []) {
    const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
    if (!id || existing.has(id)) continue
    base.push({ i: id, x: 0, y: bottom, w: 6, h: 4 })
    bottom += 4
    existing.add(id)
  }
  return base
}

/** layout_json.items 上与 DashboardChart 一致的 UI 覆盖（柱色、柱宽、分项色） */
export function chartUiFromGridItem(item) {
  if (!item || typeof item !== 'object') return {}
  const o = {}
  const c = String(item.barColor || '').trim()
  if (c) o.barColor = c
  const w = Number(item.barMaxWidth)
  if (Number.isFinite(w) && w >= 8) o.barMaxWidth = w
  const sis = item.seriesItemStyles
  if (sis && typeof sis === 'object' && Object.keys(sis).length) o.seriesItemStyles = sis
  return o
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
  const mergedItems = mergeGridItemsWithComponents(Array.isArray(p.items) ? p.items : [], components || [])
  const payloadMap = chartPayloadById && typeof chartPayloadById === 'object' ? chartPayloadById : {}
  const gridCards = []

  mergedItems.forEach((item, index) => {
    const cid = String(item.i || '').trim()
    const comp = (components || []).find((c) => {
      const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
      return id === cid
    })
    if (!comp) return
    const rawChartId = comp.chartId ?? comp.chart_id ?? comp.CHART_ID
    if (rawChartId == null) return
    const chartIdStr = String(rawChartId).trim()
    const payload = payloadMap[chartIdStr]
    if (!payload) return

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
