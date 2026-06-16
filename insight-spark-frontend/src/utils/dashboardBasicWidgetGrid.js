/**
 * 看板「基础组件」在 24 列栅格上的统一交互：
 * - 从左侧拖入 / 点击加入画布
 * - 画布内通过拖动手柄移动、四边/四角缩放
 * - 各组件仅「组件设置」侧栏内容不同
 */

import { DASHBOARD_GRID_COL_NUM } from './dashboardGridCanvas.js'
import { isBasicWidgetItem } from './dashboardWidgetVideo.js'
import { mergeLayoutConstraints } from './chartUiConfig.js'

export const DASHBOARD_BASIC_WIDGET_MIN_W = 2
export const DASHBOARD_BASIC_WIDGET_MIN_H = 2
export const DASHBOARD_BASIC_WIDGET_MAX_H = 48

/** 仅此手柄区域可拖动基础组件（勿把 .dbw-root 放入 ignore，否则手柄失效） */
export const DASHBOARD_BASIC_WIDGET_DRAG_HANDLE = '.dbw-drag-grip'

/** 拖动/缩放时忽略的子元素（操作栏、表单控件、媒体等） */
export const DASHBOARD_BASIC_WIDGET_DRAG_IGNORE =
  'input, textarea, button, .el-button, a, .el-input, .el-input__inner, video, .dbw-content, .dbw-chrome, .dbw-chrome-btn, .el-dropdown, .el-dropdown-menu, .el-dropdown-menu__item'

const CHART_DRAG_IGNORE =
  'input, textarea, button, .el-button, a, .el-input, .el-input__inner'

/**
 * @param {object} item layout item
 * @param {{ resizeOption?: object }} [options]
 */
export function basicWidgetGridItemProps(item, options = {}) {
  const isWidget = isBasicWidgetItem(item)
  const lc = mergeLayoutConstraints(item?.layoutConstraints)
  const lockSize = Boolean(lc.lockSize)
  const lockPosition = Boolean(lc.lockPosition) || Boolean(item.static)
  return {
    i: item.i,
    x: item.x,
    y: item.y,
    w: item.w,
    h: item.h,
    static: lockPosition,
    minW: isWidget ? DASHBOARD_BASIC_WIDGET_MIN_W : Math.max(2, lc.minW || 4),
    maxW: Math.min(DASHBOARD_GRID_COL_NUM, lc.maxW || DASHBOARD_GRID_COL_NUM),
    minH: isWidget ? DASHBOARD_BASIC_WIDGET_MIN_H : Math.max(2, lc.minH || 2),
    maxH: isWidget ? DASHBOARD_BASIC_WIDGET_MAX_H : Math.max(2, lc.maxH || 24),
    isResizable: options.isResizable !== false && !lockSize,
    isDraggable: options.isDraggable !== false && !lockPosition,
    resizeOption: options.resizeOption,
    dragAllowFrom: isWidget ? DASHBOARD_BASIC_WIDGET_DRAG_HANDLE : undefined,
    dragIgnoreFrom: isWidget ? DASHBOARD_BASIC_WIDGET_DRAG_IGNORE : CHART_DRAG_IGNORE,
    resizeIgnoreFrom: isWidget ? DASHBOARD_BASIC_WIDGET_DRAG_IGNORE : CHART_DRAG_IGNORE
  }
}
