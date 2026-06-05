import { onBeforeUnmount, ref } from 'vue'

/** 设计看板栅格：与 grid-layout-plus col-num / row-height / margin 严格对齐 */
export const DASHBOARD_GRID_COL_NUM = 24
export const DASHBOARD_GRID_ROW_HEIGHT = 44
/** 卡片间距 + 画布外圈留白（与助睿类看板接近，避免 padding 叠加后过宽） */
export const DASHBOARD_GRID_MARGIN = [8, 8]
export const DASHBOARD_GRID_LEGACY_COL_NUM = 12
export const DASHBOARD_GRID_DEFAULT_ITEM_W = 12
export const DASHBOARD_GRID_DEFAULT_ITEM_H = 4

/** 全屏设计器内：顶栏 + 提示条 + 内边距等占用高度（用于画布至少铺满一屏） */
export const DASHBOARD_CANVAS_CHROME_PX = 168

/** 画布最小高度：至少一屏；内容超出时由 grid-layout 自动增高，可向下滚动延伸 */
export function dashboardCanvasStageMinHeight() {
  return `calc(100vh - ${DASHBOARD_CANVAS_CHROME_PX}px)`
}

function buildGridCellSvgDataUrl(cellW, cellH) {
  const w = Math.max(8, Math.round(cellW))
  const h = Math.max(8, Math.round(cellH))
  const rx = Math.min(8, Math.max(4, Math.floor(Math.min(w, h) * 0.14)))
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">` +
    `<rect x="1" y="1" width="${w - 2}" height="${h - 2}" rx="${rx}" ` +
    `fill="#ffffff" fill-opacity="0.72" stroke="#cbd5e1" stroke-width="1" stroke-dasharray="4 3"/>` +
    `</svg>`
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`
}

/**
 * 与 grid-layout-plus calcColWidth 一致：
 * colWidth = (containerWidth - marginX * (colNum + 1)) / colNum
 */
export function calcDashboardGridMetrics(containerWidth, options = {}) {
  const colNum = options.colNum ?? DASHBOARD_GRID_COL_NUM
  const rowHeight = options.rowHeight ?? DASHBOARD_GRID_ROW_HEIGHT
  const margin = options.margin ?? DASHBOARD_GRID_MARGIN
  const mx = Number(margin[0]) || 8
  const my = Number(margin[1]) || 8
  const width = Math.max(0, Number(containerWidth) || 0)
  const colWidth = width > 0 ? (width - mx * (colNum + 1)) / colNum : 0
  return {
    colNum,
    colWidth,
    rowHeight,
    marginX: mx,
    marginY: my,
    stepX: colWidth + mx,
    stepY: rowHeight + my,
    cellSvg: buildGridCellSvgDataUrl(colWidth, rowHeight)
  }
}

export function dashboardGridCanvasInlineStyle(metrics) {
  if (!metrics?.stepX || !metrics?.cellSvg) return {}
  return {
    '--dge-grid-cols': String(metrics.colNum ?? DASHBOARD_GRID_COL_NUM),
    '--dge-grid-margin-x': `${metrics.marginX}px`,
    '--dge-grid-margin-y': `${metrics.marginY}px`,
    '--dge-grid-row-height': `${metrics.rowHeight}px`,
    '--dge-grid-step-x': `${metrics.stepX}px`,
    '--dge-grid-step-y': `${metrics.stepY}px`,
    '--dge-grid-cell-bg': metrics.cellSvg
  }
}

/** 监听画布宽度变化，刷新 ::before 渐变格子尺寸；拖拽时切换 is-grid-interacting */
export function useDashboardGridCanvasBackground(gridWrapRef, options = {}) {
  const rowHeight = options.rowHeight ?? DASHBOARD_GRID_ROW_HEIGHT
  const gridCanvasStyle = ref({})
  let ro = null
  let mo = null
  let layoutEl = null
  let pointerTracking = false
  let pointerOrigin = null

  function syncGridInteractionClass() {
    if (!layoutEl) return
    // placeholder 节点始终存在，不能作为「交互中」判断依据
    const active = Boolean(
      layoutEl.querySelector('.vgl-item--dragging, .vgl-item--resizing')
    )
    layoutEl.classList.toggle('is-grid-interacting', active)
  }

  function onLayoutPointerDown(event) {
    if (!layoutEl || event.button !== 0) return
    const item = event.target?.closest?.('.vgl-item:not(.vgl-item--placeholder)')
    if (!item || !layoutEl.contains(item)) return
    if (event.target?.closest?.('button, .el-button, input, textarea, a, .el-input')) return
    pointerTracking = true
    pointerOrigin = { x: event.clientX, y: event.clientY }
  }

  function onLayoutPointerMove(event) {
    if (!pointerTracking || !pointerOrigin || !layoutEl) return
    const dx = event.clientX - pointerOrigin.x
    const dy = event.clientY - pointerOrigin.y
    if (dx * dx + dy * dy < 16) return
    layoutEl.classList.add('is-grid-interacting')
  }

  function onLayoutPointerUp() {
    pointerTracking = false
    pointerOrigin = null
    syncGridInteractionClass()
  }

  function isSameGridCanvasStyle(prev, next) {
    if (!prev || !next) return false
    const keys = [
      '--dge-grid-cols',
      '--dge-grid-margin-x',
      '--dge-grid-margin-y',
      '--dge-grid-row-height',
      '--dge-grid-step-x',
      '--dge-grid-step-y',
      '--dge-grid-cell-bg'
    ]
    return keys.every((k) => prev[k] === next[k])
  }

  function measure() {
    const wrap = gridWrapRef.value
    if (!wrap) return
    layoutEl = wrap.querySelector('.vgl-layout')
    if (!layoutEl) return
    const metrics = calcDashboardGridMetrics(layoutEl.offsetWidth, {
      rowHeight,
      margin: options.margin ?? DASHBOARD_GRID_MARGIN
    })
    const nextStyle = dashboardGridCanvasInlineStyle(metrics)
    if (!isSameGridCanvasStyle(gridCanvasStyle.value, nextStyle)) {
      gridCanvasStyle.value = nextStyle
    }
    syncGridInteractionClass()
    options.onRefresh?.()
  }

  function attach() {
    detach()
    const wrap = gridWrapRef.value
    if (!wrap) return
    measure()
    layoutEl = wrap.querySelector('.vgl-layout')
    if (layoutEl && typeof MutationObserver !== 'undefined') {
      mo = new MutationObserver(() => syncGridInteractionClass())
      mo.observe(layoutEl, {
        subtree: true,
        attributes: true,
        attributeFilter: ['class'],
        childList: true
      })
    }
    if (options.interactive && layoutEl) {
      layoutEl.addEventListener('pointerdown', onLayoutPointerDown, true)
      window.addEventListener('pointermove', onLayoutPointerMove, true)
      window.addEventListener('pointerup', onLayoutPointerUp, true)
      window.addEventListener('pointercancel', onLayoutPointerUp, true)
    }
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure)
      return
    }
    ro = new ResizeObserver(() => measure())
    ro.observe(wrap)
    if (layoutEl) ro.observe(layoutEl)
  }

  function detach() {
    mo?.disconnect()
    mo = null
    if (layoutEl) {
      layoutEl.removeEventListener('pointerdown', onLayoutPointerDown, true)
    }
    window.removeEventListener('pointermove', onLayoutPointerMove, true)
    window.removeEventListener('pointerup', onLayoutPointerUp, true)
    window.removeEventListener('pointercancel', onLayoutPointerUp, true)
    pointerTracking = false
    pointerOrigin = null
    layoutEl?.classList.remove('is-grid-interacting')
    layoutEl = null
    ro?.disconnect()
    ro = null
    window.removeEventListener('resize', measure)
  }

  onBeforeUnmount(detach)

  return { gridCanvasStyle, measure, attach, detach }
}
