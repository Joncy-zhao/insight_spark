import {
  calcDashboardGridMetrics,
  DASHBOARD_GRID_COL_NUM,
  DASHBOARD_GRID_MARGIN,
  DASHBOARD_GRID_ROW_HEIGHT
} from './dashboardGridCanvas.js'

function resolveLayoutEl(containerEl) {
  if (!containerEl) return null
  return containerEl.querySelector?.('.vgl-layout') || containerEl
}

/** 将屏幕坐标转换为栅格单元格左上角 (x, y) */
export function pixelToGridCell(clientX, clientY, containerEl, options = {}) {
  const layoutEl = resolveLayoutEl(containerEl)
  if (!layoutEl) return { x: 0, y: 0 }
  const rect = layoutEl.getBoundingClientRect()
  const relX = clientX - rect.left
  const relY = clientY - rect.top
  const metrics = calcDashboardGridMetrics(layoutEl.offsetWidth || rect.width, {
    colNum: options.colNum ?? DASHBOARD_GRID_COL_NUM,
    rowHeight: options.rowHeight ?? DASHBOARD_GRID_ROW_HEIGHT,
    margin: options.margin ?? DASHBOARD_GRID_MARGIN
  })
  const { stepX, stepY, marginX, marginY, colNum, colWidth, rowHeight } = metrics
  if (!stepX || !stepY) return { x: 0, y: 0 }
  const itemW = Math.max(1, Number(options.itemW) || 6)
  const itemH = Math.max(1, Number(options.itemH) || 6)
  let x = Math.floor((relX - marginX) / stepX)
  let y = Math.floor((relY - marginY) / stepY)
  x = Math.max(0, Math.min(colNum - itemW, x))
  y = Math.max(0, y)
  return { x, y, metrics, itemW, itemH, colWidth, rowHeight }
}

/** 栅格占位对应的像素矩形（用于拖拽落点预览） */
export function gridItemPixelRect(x, y, w, h, metrics) {
  if (!metrics) return null
  const { colWidth, rowHeight, marginX, marginY } = metrics
  const left = marginX + x * (colWidth + marginX)
  const top = marginY + y * (rowHeight + marginY)
  const width = w * colWidth + Math.max(0, w - 1) * marginX
  const height = h * rowHeight + Math.max(0, h - 1) * marginY
  return { left, top, width, height }
}

export function nextGridRowY(layoutItems) {
  let bottom = 0
  for (const it of layoutItems || []) {
    bottom = Math.max(bottom, (Number(it.y) || 0) + (Number(it.h) || 1))
  }
  return bottom
}
