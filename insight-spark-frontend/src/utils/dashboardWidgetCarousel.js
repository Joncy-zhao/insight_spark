import {
  DASHBOARD_BASIC_WIDGET_DEFAULT_H,
  DASHBOARD_BASIC_WIDGET_DEFAULT_W
} from './dashboardBasicComponents.js'
import { IMAGE_OBJECT_FIT, IMAGE_WIDGET_ACCEPT, IMAGE_WIDGET_MAX_BYTES, IMAGE_WIDGET_MAX_DATA_URL_LEN } from './dashboardWidgetImage.js'

export const DASHBOARD_WIDGET_KIND_CAROUSEL = 'carousel'

export { IMAGE_OBJECT_FIT as CAROUSEL_OBJECT_FIT, IMAGE_WIDGET_ACCEPT as CAROUSEL_IMAGE_ACCEPT, IMAGE_WIDGET_MAX_BYTES as CAROUSEL_IMAGE_MAX_BYTES }

const DEFAULT_CAROUSEL_WIDGET_CONFIG = Object.freeze({
  images: [],
  rounded: false,
  border: false,
  objectFit: IMAGE_OBJECT_FIT.COVER,
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

export function defaultCarouselWidgetConfig() {
  return {
    ...DEFAULT_CAROUSEL_WIDGET_CONFIG,
    images: []
  }
}

function normalizeImages(input) {
  if (!Array.isArray(input)) return []
  return input
    .map((item) => String(item || '').trim())
    .filter((src) => src && src.length <= IMAGE_WIDGET_MAX_DATA_URL_LEN)
    .slice(0, 12)
}

export function normalizeCarouselWidgetConfig(input) {
  const d = DEFAULT_CAROUSEL_WIDGET_CONFIG
  if (!input || typeof input !== 'object') {
    return defaultCarouselWidgetConfig()
  }
  const objectFit =
    String(input.objectFit || '').trim() === IMAGE_OBJECT_FIT.STRETCH
      ? IMAGE_OBJECT_FIT.STRETCH
      : IMAGE_OBJECT_FIT.COVER
  return {
    images: normalizeImages(input.images),
    rounded: Boolean(input.rounded),
    border: Boolean(input.border),
    objectFit,
    autoplay: input.autoplay !== false,
    interval: clamp(input.interval ?? d.interval, 500, 60000),
    showIndicator: input.showIndicator !== false,
    indicatorColor: String(input.indicatorColor || d.indicatorColor).slice(0, 32)
  }
}

export function carouselWidgetConfigEqual(a, b) {
  const x = normalizeCarouselWidgetConfig(a)
  const y = normalizeCarouselWidgetConfig(b)
  return (
    x.images.join('|') === y.images.join('|') &&
    x.rounded === y.rounded &&
    x.border === y.border &&
    x.objectFit === y.objectFit &&
    x.autoplay === y.autoplay &&
    x.interval === y.interval &&
    x.showIndicator === y.showIndicator &&
    x.indicatorColor === y.indicatorColor
  )
}

export function isCarouselWidgetItem(item) {
  return String(item?.widgetKind || '').trim() === DASHBOARD_WIDGET_KIND_CAROUSEL
}

export function carouselConfigForItem(item) {
  if (!isCarouselWidgetItem(item)) return defaultCarouselWidgetConfig()
  return normalizeCarouselWidgetConfig(item.widgetConfig)
}

export function createCarouselWidgetItem({
  x = 0,
  y = 0,
  w = DASHBOARD_BASIC_WIDGET_DEFAULT_W,
  h = DASHBOARD_BASIC_WIDGET_DEFAULT_H
} = {}) {
  const id = `bw-carousel-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  return {
    i: id,
    x: Math.max(0, Math.round(Number(x) || 0)),
    y: Math.max(0, Math.round(Number(y) || 0)),
    w: Math.max(2, Math.round(Number(w) || DASHBOARD_BASIC_WIDGET_DEFAULT_W)),
    h: Math.max(2, Math.round(Number(h) || DASHBOARD_BASIC_WIDGET_DEFAULT_H)),
    widgetKind: DASHBOARD_WIDGET_KIND_CAROUSEL,
    widgetConfig: defaultCarouselWidgetConfig()
  }
}

export function serializeCarouselWidgetFieldsForApi(it) {
  if (!isCarouselWidgetItem(it)) return {}
  const cfg = normalizeCarouselWidgetConfig(it.widgetConfig)
  return {
    widgetKind: DASHBOARD_WIDGET_KIND_CAROUSEL,
    widgetConfig: { ...cfg }
  }
}

export function carouselWidgetMediaStyle(config) {
  const c = normalizeCarouselWidgetConfig(config)
  return {
    objectFit: c.objectFit === IMAGE_OBJECT_FIT.STRETCH ? 'fill' : 'cover'
  }
}
