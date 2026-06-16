/** 批注标签预设（企业级，无 emoji） */
import { normalizeSelectionRect, parseSelectionImage } from './collabBoxSelect.js'

export const ANNOTATION_TAG_PRESETS = [
  { value: '异常说明', label: '异常说明', tone: 'danger', accent: '#dc2626', badge: '#dc2626', bg: '#fef2f2', border: '#fecaca' },
  { value: '经验总结', label: '经验总结', tone: 'success', accent: '#16a34a', badge: '#16a34a', bg: '#f0fdf4', border: '#bbf7d0' },
  { value: '待跟进', label: '待跟进', tone: 'warning', accent: '#d97706', badge: '#d97706', bg: '#fffbeb', border: '#fde68a' }
]

const PRESET_MAP = new Map(ANNOTATION_TAG_PRESETS.map((p) => [p.value, p]))

export function annotationTagPreset(tag) {
  const key = String(tag || '').trim()
  return PRESET_MAP.get(key) || {
    value: key || '批注',
    label: key || '批注',
    tone: 'info',
    accent: '#64748b',
    bg: '#f8fafc',
    border: '#e2e8f0',
    badge: '#64748b'
  }
}

/** @deprecated 便签风格，保留兼容 */
export function annotationStickyStyle(tag) {
  return annotationCardStyle(tag)
}

export function annotationCardStyle(tag) {
  const p = annotationTagPreset(tag)
  return {
    background: p.bg,
    borderColor: p.border,
    borderLeftColor: p.accent,
    '--ann-accent': p.accent
  }
}

export function isAnnotationHidden(ann) {
  return ann?.isHidden === true || ann?.isHidden === 1 || String(ann?.isHidden) === '1'
}

export function buildCommentTree(comments) {
  const list = Array.isArray(comments) ? comments : []
  const map = new Map()
  for (const c of list) {
    map.set(c.id, { ...c, replies: [] })
  }
  const roots = []
  for (const c of list) {
    const node = map.get(c.id)
    const pid = c.parentId
    if (pid != null && map.has(Number(pid))) {
      map.get(Number(pid)).replies.push(node)
    } else {
      roots.push(node)
    }
  }
  return roots
}

export function parseAnnotationBind(ann) {
  if (!ann?.bindJson) return {}
  try {
    const bind = typeof ann.bindJson === 'string' ? JSON.parse(ann.bindJson) : ann.bindJson
    return bind && typeof bind === 'object' ? { ...bind } : {}
  } catch {
    return {}
  }
}

function parseChartSnapshot(raw) {
  if (raw == null) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(String(raw))
  } catch {
    return {}
  }
}

/** 从图表快照 / fieldMapping 提取维度、指标、时间等语义绑定 */
export function extractSemanticBindFromChartPayload(payload, options = {}) {
  if (!payload || typeof payload !== 'object') return {}
  const snap = parseChartSnapshot(payload.chartSnapshot)
  const adv = snap.advancedAnalysis || payload.advancedAnalysis || {}
  const mapping = {
    ...(snap.fieldMapping || {}),
    ...(payload.fieldMapping || {}),
    ...(adv.fieldMapping || {})
  }
  const bind = {}
  const dimension = String(mapping.dimension || mapping.name || adv.dimension || '').trim()
  const dimensionKey = String(
    mapping.dimensionKey || mapping.dimensionExpr || snap.dimensionKey || adv.dimensionField || ''
  ).trim()
  const metric = String(mapping.metric || mapping.value || adv.metric || '').trim()
  const metricKey = String(
    mapping.metricKey || mapping.metricExpr || mapping.metricField || snap.metricKey || adv.metricField || ''
  ).trim()
  let timeField = String(
    mapping.timeField || mapping.timeKey || snap.timeField || payload.timeField || adv.timeField || ''
  ).trim()
  let time = String(mapping.time || mapping.timeLabel || '').trim()

  const chartType = String(
    options.chartType || payload.chartType || snap.chartType || adv.chartType || ''
  ).toLowerCase()
  if (!timeField && (chartType === 'line' || chartType === 'area' || chartType === 'linechart')) {
    if (dimensionKey) timeField = dimensionKey
    if (dimension && !time) time = dimension
  }

  if (dimension) bind.dimension = dimension
  if (dimensionKey) bind.dimensionKey = dimensionKey
  if (metric) bind.metric = metric
  if (metricKey) bind.metricKey = metricKey
  if (timeField) bind.timeField = timeField
  if (time) bind.time = time

  const tableName = String(
    payload.tableName || snap.tableName || payload.queryTableName || adv.tableName || ''
  ).trim()
  if (tableName) bind.tableName = tableName

  const chartId = payload.id ?? payload.chartId
  if (chartId != null && chartId !== '') bind.chartId = chartId
  if (chartType) bind.chartType = chartType

  return bind
}

/** 构建看板组件 itemId -> 图表语义上下文 */
export function buildChartContextMap(gridLayout, components, chartPayloadById = {}) {
  const componentById = new Map()
  for (const c of components || []) {
    const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
    if (id) componentById.set(id, c)
  }
  const map = {}
  for (const item of gridLayout || []) {
    const itemId = item?.i == null ? '' : String(item.i)
    if (!itemId) continue
    const comp = componentById.get(itemId)
    let payload = null
    let chartId = null
    if (comp) {
      const rawCid = comp.chartId ?? comp.chart_id ?? comp.CHART_ID
      if (rawCid != null && Number(rawCid) > 0) {
        chartId = rawCid
        payload = chartPayloadById[String(rawCid)] || null
      }
      if (!payload && (comp.artifactId ?? comp.artifact_id)) {
        payload = {
          chartSnapshot: comp.artifact || {},
          fieldMapping: comp.artifact?.fieldMapping || {},
          chartType: comp.artifactChartType || comp.artifact?.chartType,
          tableName: comp.artifact?.tableName
        }
      }
    }
    const semanticBind = extractSemanticBindFromChartPayload(payload, { chartType: item?.chartType })
    if (Object.keys(semanticBind).length || chartId) {
      map[itemId] = { chartId, semanticBind }
    }
  }
  return map
}

/** 组装批注 bindJson（组件位置 + 维度/指标/时间 + 框选区域） */
export function buildAnnotationBindJson(node, chartContext, options = {}) {
  const payload = {
    nodeLabel: node?.label,
    nodeKind: node?.kind
  }
  if (node?.targetType === 'COMPONENT' && node?.kind !== 'dashboard') {
    const tid = String(node.targetId)
    if (/^\d+$/.test(tid)) payload.componentId = Number(tid)
    else payload.layoutItemId = tid
  }
  const semantic = chartContext?.semanticBind || chartContext || {}
  for (const key of [
    'dimension', 'dimensionKey', 'metric', 'metricKey',
    'timeField', 'time', 'tableName', 'chartId', 'chartType'
  ]) {
    const val = semantic[key]
    if (val != null && String(val).trim() !== '') payload[key] = val
  }
  const selectionRect = options?.selectionRect
  if (selectionRect) {
    const norm = normalizeSelectionRect(selectionRect)
    if (norm) payload.selectionRect = norm
  }
  const selectionImage = options?.selectionImage
  if (typeof selectionImage === 'string' && selectionImage.startsWith('data:image/')) {
    payload.selectionImage = selectionImage
  }
  return JSON.stringify(payload)
}

/** UI 展示：维度 / 指标 / 时间 标签 */
export function formatAnnotationBindChips(bind) {
  const b = typeof bind === 'string' ? parseAnnotationBind({ bindJson: bind }) : (bind || {})
  const chips = []
  if (b.dimension) chips.push({ key: 'dimension', label: '维度', value: b.dimension })
  if (b.metric) chips.push({ key: 'metric', label: '指标', value: b.metric })
  const timeLabel = b.time || b.timeField
  if (timeLabel) chips.push({ key: 'time', label: '时间', value: timeLabel })
  return chips
}

export function selectionImageForAnnotation(ann) {
  return parseSelectionImage(parseAnnotationBind(ann))
}

export function hasSemanticBind(bind) {
  const b = typeof bind === 'string' ? parseAnnotationBind({ bindJson: bind }) : (bind || {})
  return formatAnnotationBindChips(b).length > 0 || !!parseSelectionImage(b)
}

export { parseSelectionImage } from './collabBoxSelect.js'

export function resolveAnnotationLayoutItemId(ann) {
  if (!ann) return null
  const bind = parseAnnotationBind(ann)
  if (bind.layoutItemId) return String(bind.layoutItemId)
  if (bind.componentId) return String(bind.componentId)
  if (ann.targetType === 'COMPONENT' && ann.targetId != null) return String(ann.targetId)
  return null
}

/** 画布组件 id -> { count, primaryTag, tags[] } */
export function buildCanvasItemBadges(annotations) {
  const map = {}
  for (const ann of Array.isArray(annotations) ? annotations : []) {
    if (isAnnotationHidden(ann)) continue
    const itemId = resolveAnnotationLayoutItemId(ann)
    if (!itemId) continue
    if (!map[itemId]) map[itemId] = { count: 0, tags: [] }
    map[itemId].count += 1
    const tag = String(ann.tag || '').trim()
    if (tag && !map[itemId].tags.includes(tag)) map[itemId].tags.push(tag)
  }
  for (const entry of Object.values(map)) {
    entry.primaryTag = entry.tags[0] || ''
  }
  return map
}

export function isBoardLevelAnnotation(ann, dashboardId) {
  if (!ann || ann.targetType !== 'DASHBOARD') return false
  const did = Number(dashboardId)
  if (!Number.isFinite(did)) return false
  if (Number(ann.targetId) !== did && Number(ann.dashboardId) !== did) return false
  if (!ann.bindJson) return true
  const bind = parseAnnotationBind(ann)
  return !bind.layoutItemId && !bind.componentId
}

export function countAnnotationsForNode(ann, node, dashboardId) {
  if (!node || !ann) return false
  if (isAnnotationHidden(ann)) return false
  if (node.kind === 'dashboard') {
    return isBoardLevelAnnotation(ann, dashboardId)
  }
  if (ann.targetType === 'COMPONENT' && String(ann.targetId) === String(node.targetId)) return true
  const bind = parseAnnotationBind(ann)
  if (bind.layoutItemId && String(bind.layoutItemId) === String(node.targetId)) return true
  if (bind.componentId && String(bind.componentId) === String(node.targetId)) return true
  return bind.nodeLabel === node.label
}

export function userInitials(row) {
  const name = String(row?.nickname || row?.username || row?.userId || '用').trim()
  if (!name) return '用'
  if (/[\u4e00-\u9fff]/.test(name)) return name.slice(0, 1)
  const parts = name.split(/\s+/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}

export function wasAnnotationEdited(ann) {
  if (!ann?.updatedAt || !ann?.createdAt) return false
  return String(ann.updatedAt).slice(0, 19) !== String(ann.createdAt).slice(0, 19)
}

function componentMapFromList(components) {
  const map = new Map()
  for (const c of components || []) {
    const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
    if (id) map.set(id, c)
  }
  return map
}

function collabNodeKindForItem(item, componentById) {
  if (isBasicWidgetKind(item)) return String(item.kind || item.type || 'basic')
  const comp = componentById.get(String(item?.i))
  if (comp?.artifactId ?? comp?.artifact_id) return 'advanced'
  const cid = comp?.chartId ?? comp?.chart_id ?? comp?.CHART_ID
  if (cid != null && Number(cid) > 0) return 'chart'
  return String(item?.kind || item?.type || 'widget')
}

function isBasicWidgetKind(item) {
  const k = String(item?.kind || item?.type || '').toLowerCase()
  return ['video', 'text', 'image', 'link'].includes(k) || String(item?.i || '').startsWith('bw-')
}

function collabNodeLabelForItem(item, slotIndex, componentById, chartPayloadById, legacyPreviewCards) {
  const title = String(item?.title || '').trim()
  if (title) return title
  const leg = legacyPreviewCards?.[slotIndex]
  if (leg?.title) return String(leg.title).trim()
  const comp = componentById.get(String(item?.i))
  const cid = comp?.chartId ?? comp?.chart_id ?? comp?.CHART_ID
  if (cid != null) {
    const payload = chartPayloadById?.[String(cid)]
    const snap = parseChartSnapshot(payload?.chartSnapshot)
    const msg = String(snap?.message || payload?.queryText || '').trim()
    if (msg) return msg.slice(0, 120)
  }
  const kind = String(item?.kind || item?.type || 'widget')
  if (kind === 'chart' || (cid != null && Number(cid) > 0)) {
    return `图表 · ${item.i}`
  }
  return String(item?.i || '组件')
}

/** 与画布渲染一致，生成协作批注节点列表 */
export function buildCollabNodesFromBoard(board, gridLayout, components, chartPayloadById = {}, legacyPreviewCards = []) {
  const nodes = [{
    targetType: 'DASHBOARD',
    targetId: board?.id,
    label: '整板',
    kind: 'dashboard'
  }]
  if (!board?.id || !Array.isArray(gridLayout) || !gridLayout.length) return nodes
  const componentById = componentMapFromList(components)
  for (let i = 0; i < gridLayout.length; i += 1) {
    const item = gridLayout[i]
    if (item?.i == null) continue
    nodes.push({
      targetType: 'COMPONENT',
      targetId: String(item.i),
      label: collabNodeLabelForItem(item, i, componentById, chartPayloadById, legacyPreviewCards),
      kind: collabNodeKindForItem(item, componentById)
    })
  }
  return nodes
}
