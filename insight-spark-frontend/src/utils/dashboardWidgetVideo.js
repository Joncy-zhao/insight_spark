/** 看板视频基础组件：layout_json.items 上的 widgetKind / widgetConfig */

import {
  DASHBOARD_BASIC_WIDGET_DEFAULT_H,
  DASHBOARD_BASIC_WIDGET_DEFAULT_W
} from './dashboardBasicComponents.js'
import {
  cloneTextWidgetFieldsFromItem,
  DASHBOARD_WIDGET_KIND_TEXT,
  serializeTextWidgetFieldsForApi
} from './dashboardWidgetText.js'

export const DASHBOARD_WIDGET_KIND_VIDEO = 'video'

export const VIDEO_OBJECT_FIT = Object.freeze({
  COVER: 'cover',
  STRETCH: 'stretch'
})

/** 视频文件上传上限 20MB（Base64 存入 layout_json） */
export const VIDEO_WIDGET_MAX_BYTES = 20 * 1024 * 1024
export const VIDEO_WIDGET_MAX_DATA_URL_LEN = Math.ceil(VIDEO_WIDGET_MAX_BYTES * (4 / 3)) + 64

const DEFAULT_VIDEO_WIDGET_CONFIG = Object.freeze({
  src: '',
  rounded: false,
  border: false,
  objectFit: VIDEO_OBJECT_FIT.COVER,
  autoplay: true,
  interval: 2000,
  showIndicator: true,
  indicatorColor: '#9ca3af'
})

function clamp(n, min, max) {
  const x = Number(n)
  if (!Number.isFinite(x)) return min
  return Math.min(max, Math.max(min, x))
}

export function defaultVideoWidgetConfig() {
  return { ...DEFAULT_VIDEO_WIDGET_CONFIG }
}

export function videoWidgetConfigEqual(a, b) {
  const x = normalizeVideoWidgetConfig(a)
  const y = normalizeVideoWidgetConfig(b)
  return (
    x.src === y.src &&
    x.rounded === y.rounded &&
    x.border === y.border &&
    x.objectFit === y.objectFit &&
    x.autoplay === y.autoplay &&
    x.interval === y.interval &&
    x.showIndicator === y.showIndicator &&
    x.indicatorColor === y.indicatorColor
  )
}

export function normalizeVideoWidgetConfig(input) {
  const d = DEFAULT_VIDEO_WIDGET_CONFIG
  if (!input || typeof input !== 'object') {
    return { ...d }
  }
  const src = String(input.src || '').trim()
  const objectFit =
    String(input.objectFit || '').trim() === VIDEO_OBJECT_FIT.STRETCH
      ? VIDEO_OBJECT_FIT.STRETCH
      : VIDEO_OBJECT_FIT.COVER
  return {
    src: src.length > VIDEO_WIDGET_MAX_DATA_URL_LEN ? '' : src,
    rounded: Boolean(input.rounded),
    border: Boolean(input.border),
    objectFit,
    autoplay: input.autoplay !== false,
    interval: clamp(input.interval ?? d.interval, 500, 60000),
    showIndicator: input.showIndicator !== false,
    indicatorColor: String(input.indicatorColor || d.indicatorColor).slice(0, 32)
  }
}

export function isVideoWidgetItem(item) {
  return String(item?.widgetKind || '').trim() === DASHBOARD_WIDGET_KIND_VIDEO
}

export function isBasicWidgetItem(item) {
  return Boolean(String(item?.widgetKind || '').trim())
}

export function videoConfigForItem(item) {
  if (!isVideoWidgetItem(item)) return defaultVideoWidgetConfig()
  return normalizeVideoWidgetConfig(item.widgetConfig)
}

export function createVideoWidgetItem({
  x = 0,
  y = 0,
  w = DASHBOARD_BASIC_WIDGET_DEFAULT_W,
  h = DASHBOARD_BASIC_WIDGET_DEFAULT_H
} = {}) {
  const id = `bw-video-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  return {
    i: id,
    x: Math.max(0, Math.round(Number(x) || 0)),
    y: Math.max(0, Math.round(Number(y) || 0)),
    w: Math.max(2, Math.round(Number(w) || DASHBOARD_BASIC_WIDGET_DEFAULT_W)),
    h: Math.max(2, Math.round(Number(h) || DASHBOARD_BASIC_WIDGET_DEFAULT_H)),
    widgetKind: DASHBOARD_WIDGET_KIND_VIDEO,
    widgetConfig: defaultVideoWidgetConfig()
  }
}

export function cloneWidgetFieldsFromItem(it) {
  const kind = String(it.widgetKind || '').trim()
  if (!kind) return {}
  if (kind === DASHBOARD_WIDGET_KIND_VIDEO) {
    return {
      widgetKind: kind,
      widgetConfig: normalizeVideoWidgetConfig(it.widgetConfig)
    }
  }
  if (kind === DASHBOARD_WIDGET_KIND_TEXT) {
    return cloneTextWidgetFieldsFromItem(it)
  }
  return { widgetKind: kind }
}

export function serializeWidgetFieldsForApi(it) {
  const kind = String(it.widgetKind || '').trim()
  if (!kind) return {}
  if (kind === DASHBOARD_WIDGET_KIND_VIDEO) {
    const cfg = normalizeVideoWidgetConfig(it.widgetConfig)
    const out = {
      widgetKind: kind,
      widgetConfig: {
        rounded: cfg.rounded,
        border: cfg.border,
        objectFit: cfg.objectFit,
        autoplay: cfg.autoplay,
        interval: cfg.interval,
        showIndicator: cfg.showIndicator,
        indicatorColor: cfg.indicatorColor
      }
    }
    if (cfg.src) out.widgetConfig.src = cfg.src
    return out
  }
  if (kind === DASHBOARD_WIDGET_KIND_TEXT) {
    return serializeTextWidgetFieldsForApi(it)
  }
  return { widgetKind: kind }
}
