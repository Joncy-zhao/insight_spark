/** 批注标签预设（经验沉淀模板入口） */
export const ANNOTATION_TAG_PRESETS = [
  { value: '异常说明', label: '异常说明', emoji: '⚠', tone: 'danger', stickyBg: '#fef2f2', stickyBorder: '#fecaca', badge: '#ef4444' },
  { value: '经验总结', label: '经验总结', emoji: '✦', tone: 'success', stickyBg: '#f0fdf4', stickyBorder: '#bbf7d0', badge: '#22c55e' },
  { value: '待跟进', label: '待跟进', emoji: '◎', tone: 'warning', stickyBg: '#fffbeb', stickyBorder: '#fde68a', badge: '#f59e0b' }
]

const PRESET_MAP = new Map(ANNOTATION_TAG_PRESETS.map((p) => [p.value, p]))

export function annotationTagPreset(tag) {
  const key = String(tag || '').trim()
  return PRESET_MAP.get(key) || {
    value: key || '批注',
    label: key || '批注',
    emoji: '✎',
    tone: 'info',
    stickyBg: '#f8fafc',
    stickyBorder: '#e2e8f0',
    badge: '#64748b'
  }
}

export function annotationStickyStyle(tag) {
  const p = annotationTagPreset(tag)
  return {
    background: p.stickyBg,
    borderColor: p.stickyBorder,
    '--sticky-accent': p.badge
  }
}

export function resolveAnnotationLayoutItemId(ann) {
  if (!ann) return null
  if (ann.bindJson) {
    try {
      const bind = typeof ann.bindJson === 'string' ? JSON.parse(ann.bindJson) : ann.bindJson
      if (bind.layoutItemId) return String(bind.layoutItemId)
      if (bind.componentId) return String(bind.componentId)
    } catch {
      /* ignore */
    }
  }
  if (ann.targetType === 'COMPONENT' && ann.targetId != null) return String(ann.targetId)
  return null
}

/** 画布组件 id -> { count, primaryTag, tags[] } */
export function buildCanvasItemBadges(annotations) {
  const map = {}
  for (const ann of Array.isArray(annotations) ? annotations : []) {
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

/** 整板级批注：绑定看板整体，不挂在某个组件上 */
export function isBoardLevelAnnotation(ann, dashboardId) {
  if (!ann || ann.targetType !== 'DASHBOARD') return false
  const did = Number(dashboardId)
  if (!Number.isFinite(did)) return false
  if (Number(ann.targetId) !== did && Number(ann.dashboardId) !== did) return false
  if (!ann.bindJson) return true
  try {
    const bind = typeof ann.bindJson === 'string' ? JSON.parse(ann.bindJson) : ann.bindJson
    const keys = Object.keys(bind || {})
    return keys.length === 0 || (keys.length === 1 && bind.dimension)
  } catch {
    return false
  }
}

export function countAnnotationsForNode(ann, node, dashboardId) {
  if (!node || !ann) return false
  if (node.kind === 'dashboard') {
    return isBoardLevelAnnotation(ann, dashboardId)
  }
  if (ann.targetType === 'COMPONENT' && String(ann.targetId) === String(node.targetId)) return true
  if (!ann.bindJson) return false
  try {
    const bind = typeof ann.bindJson === 'string' ? JSON.parse(ann.bindJson) : ann.bindJson
    if (bind.layoutItemId && String(bind.layoutItemId) === String(node.targetId)) return true
    if (bind.componentId && String(bind.componentId) === String(node.targetId)) return true
    return bind.nodeLabel === node.label
  } catch {
    return false
  }
}

export function userInitials(row) {
  const name = String(row?.nickname || row?.username || row?.userId || '用').trim()
  if (!name) return '用'
  if (/[\u4e00-\u9fff]/.test(name)) return name.slice(0, 1)
  const parts = name.split(/\s+/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}
