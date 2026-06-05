export const PALETTE_COMPONENT_MIME = 'application/x-dashboard-basic-component'

/**
 * 基础组件画布契约（文本 / 视频 / 后续图片等共用）：
 * - 左侧拖入或点击加入（默认 6×6）
 * - 画布内 .dbw-drag-grip 拖动手柄移动，四边缩放（见 dashboardBasicWidgetGrid.js）
 * - ⋮ 编辑 / 双击打开右侧「组件设置」（各组件 inspector 不同）
 */

/** 基础组件拖入画布时的默认占位（24 列栅格） */
export const DASHBOARD_BASIC_WIDGET_DEFAULT_W = 6
export const DASHBOARD_BASIC_WIDGET_DEFAULT_H = 6

/** 面板中已开放拖入/点击的基础组件 */
export const DASHBOARD_PALETTE_AVAILABLE_TYPES = Object.freeze(['video', 'text'])

export function isPaletteComponentAvailable(type) {
  return DASHBOARD_PALETTE_AVAILABLE_TYPES.includes(String(type || '').trim())
}

export const DASHBOARD_BASIC_COMPONENTS = Object.freeze([
  { type: 'text', label: '文本' },
  { type: 'image', label: '图片' },
  { type: 'video', label: '视频' },
  { type: 'webpage', label: '网页' },
  { type: 'carousel', label: '轮播图' },
  { type: 'time', label: '时间' }
])
