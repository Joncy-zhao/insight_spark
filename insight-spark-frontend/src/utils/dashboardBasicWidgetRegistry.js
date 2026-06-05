/**
 * 基础组件注册表：画布渲染 + 右侧「组件设置」
 * 新增基础组件时在此登记，并保证画布组件带 .dbw-drag-grip 拖动手柄
 */

import DashboardTextWidget from '../components/dashboard/DashboardTextWidget.vue'
import DashboardTextWidgetInspector from '../components/dashboard/DashboardTextWidgetInspector.vue'
import DashboardVideoWidget from '../components/dashboard/DashboardVideoWidget.vue'
import DashboardVideoWidgetInspector from '../components/dashboard/DashboardVideoWidgetInspector.vue'
import {
  DASHBOARD_WIDGET_KIND_TEXT,
  defaultTextWidgetConfig,
  textConfigForItem
} from './dashboardWidgetText.js'
import {
  DASHBOARD_WIDGET_KIND_VIDEO,
  defaultVideoWidgetConfig,
  isVideoWidgetItem,
  videoConfigForItem
} from './dashboardWidgetVideo.js'

export const BASIC_WIDGET_REGISTRY = Object.freeze({
  [DASHBOARD_WIDGET_KIND_VIDEO]: Object.freeze({
    kind: DASHBOARD_WIDGET_KIND_VIDEO,
    label: '视频',
    widget: DashboardVideoWidget,
    inspector: DashboardVideoWidgetInspector,
    configForItem: videoConfigForItem,
    defaultConfig: defaultVideoWidgetConfig,
    isItem: isVideoWidgetItem
  }),
  [DASHBOARD_WIDGET_KIND_TEXT]: Object.freeze({
    kind: DASHBOARD_WIDGET_KIND_TEXT,
    label: '文本',
    widget: DashboardTextWidget,
    inspector: DashboardTextWidgetInspector,
    configForItem: textConfigForItem,
    defaultConfig: defaultTextWidgetConfig,
    isItem: (item) => String(item?.widgetKind || '').trim() === DASHBOARD_WIDGET_KIND_TEXT
  })
})

export function resolveBasicWidgetEntry(itemOrKind) {
  const kind =
    typeof itemOrKind === 'string'
      ? String(itemOrKind || '').trim()
      : String(itemOrKind?.widgetKind || '').trim()
  return BASIC_WIDGET_REGISTRY[kind] || null
}

export function basicWidgetLabelForItem(item) {
  return resolveBasicWidgetEntry(item)?.label || '基础'
}
