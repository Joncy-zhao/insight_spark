import {
  DASHBOARD_BASIC_WIDGET_DEFAULT_H,
  DASHBOARD_BASIC_WIDGET_DEFAULT_W
} from './dashboardBasicComponents.js'

export const DASHBOARD_WIDGET_KIND_IMAGE = 'image'

export const IMAGE_OBJECT_FIT = Object.freeze({
  COVER: 'cover',
  STRETCH: 'stretch'
})

export const IMAGE_WIDGET_MAX_BYTES = 2 * 1024 * 1024
export const IMAGE_WIDGET_MAX_DATA_URL_LEN = Math.ceil(IMAGE_WIDGET_MAX_BYTES * (4 / 3)) + 64
export const IMAGE_WIDGET_ACCEPT = 'image/png,image/jpeg,image/webp'

const DEFAULT_IMAGE_WIDGET_CONFIG = Object.freeze({
  src: '',
  rounded: false,
  border: false,
  objectFit: IMAGE_OBJECT_FIT.COVER
})

export function defaultImageWidgetConfig() {
  return { ...DEFAULT_IMAGE_WIDGET_CONFIG }
}

export function normalizeImageWidgetConfig(input) {
  const d = DEFAULT_IMAGE_WIDGET_CONFIG
  if (!input || typeof input !== 'object') return { ...d }
  const src = String(input.src || '').trim()
  const objectFit =
    String(input.objectFit || '').trim() === IMAGE_OBJECT_FIT.STRETCH
      ? IMAGE_OBJECT_FIT.STRETCH
      : IMAGE_OBJECT_FIT.COVER
  return {
    src: src.length > IMAGE_WIDGET_MAX_DATA_URL_LEN ? '' : src,
    rounded: Boolean(input.rounded),
    border: Boolean(input.border),
    objectFit
  }
}

export function imageWidgetConfigEqual(a, b) {
  const x = normalizeImageWidgetConfig(a)
  const y = normalizeImageWidgetConfig(b)
  return (
    x.src === y.src &&
    x.rounded === y.rounded &&
    x.border === y.border &&
    x.objectFit === y.objectFit
  )
}

export function isImageWidgetItem(item) {
  return String(item?.widgetKind || '').trim() === DASHBOARD_WIDGET_KIND_IMAGE
}

export function imageConfigForItem(item) {
  if (!isImageWidgetItem(item)) return defaultImageWidgetConfig()
  return normalizeImageWidgetConfig(item.widgetConfig)
}

export function createImageWidgetItem({
  x = 0,
  y = 0,
  w = DASHBOARD_BASIC_WIDGET_DEFAULT_W,
  h = DASHBOARD_BASIC_WIDGET_DEFAULT_H
} = {}) {
  const id = `bw-image-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  return {
    i: id,
    x: Math.max(0, Math.round(Number(x) || 0)),
    y: Math.max(0, Math.round(Number(y) || 0)),
    w: Math.max(2, Math.round(Number(w) || DASHBOARD_BASIC_WIDGET_DEFAULT_W)),
    h: Math.max(2, Math.round(Number(h) || DASHBOARD_BASIC_WIDGET_DEFAULT_H)),
    widgetKind: DASHBOARD_WIDGET_KIND_IMAGE,
    widgetConfig: defaultImageWidgetConfig()
  }
}

export function serializeImageWidgetFieldsForApi(it) {
  if (!isImageWidgetItem(it)) return {}
  const cfg = normalizeImageWidgetConfig(it.widgetConfig)
  const out = {
    widgetKind: DASHBOARD_WIDGET_KIND_IMAGE,
    widgetConfig: {
      rounded: cfg.rounded,
      border: cfg.border,
      objectFit: cfg.objectFit
    }
  }
  if (cfg.src) out.widgetConfig.src = cfg.src
  return out
}

export function imageWidgetMediaStyle(config) {
  const c = normalizeImageWidgetConfig(config)
  return {
    objectFit: c.objectFit === IMAGE_OBJECT_FIT.STRETCH ? 'fill' : 'cover'
  }
}
