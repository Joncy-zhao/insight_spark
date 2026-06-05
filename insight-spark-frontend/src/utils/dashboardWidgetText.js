/** 看板文本基础组件：layout_json.items 上的 widgetKind / widgetConfig */

import {
  DASHBOARD_BASIC_WIDGET_DEFAULT_H,
  DASHBOARD_BASIC_WIDGET_DEFAULT_W
} from './dashboardBasicComponents.js'

export const DASHBOARD_WIDGET_KIND_TEXT = 'text'
export const TEXT_WIDGET_MAX_PLAIN_CHARS = 2000
export const TEXT_WIDGET_IMAGE_MAX_BYTES = 5 * 1024 * 1024

export const TEXT_ALIGN = Object.freeze({
  LEFT: 'left',
  CENTER: 'center',
  RIGHT: 'right',
  JUSTIFY: 'justify'
})

export const TEXT_VERTICAL_ALIGN = Object.freeze({
  TOP: 'top',
  MIDDLE: 'middle',
  BOTTOM: 'bottom'
})

export const TEXT_WRITING_MODE = Object.freeze({
  HORIZONTAL: 'horizontal',
  VERTICAL: 'vertical'
})

const DEFAULT_TEXT_WIDGET_CONFIG = Object.freeze({
  content: '',
  color: '#666666',
  fontSize: 14,
  fontWeight: 'normal',
  fontStyle: 'normal',
  textDecoration: '',
  textAlign: TEXT_ALIGN.CENTER,
  verticalAlign: TEXT_VERTICAL_ALIGN.MIDDLE,
  writingMode: TEXT_WRITING_MODE.HORIZONTAL,
  paddingTop: 0,
  paddingRight: 0,
  paddingBottom: 0,
  paddingLeft: 0,
  letterSpacing: 1
})

function clamp(n, min, max) {
  const x = Number(n)
  if (!Number.isFinite(x)) return min
  return Math.min(max, Math.max(min, x))
}

export function plainTextFromHtml(html) {
  if (typeof document === 'undefined') {
    return String(html || '').replace(/<[^>]+>/g, '')
  }
  const div = document.createElement('div')
  div.innerHTML = String(html || '')
  return (div.textContent || div.innerText || '').replace(/\u200B/g, '')
}

export function isTextContentEmpty(html) {
  return plainTextFromHtml(html).trim().length === 0
}

export function defaultTextWidgetConfig() {
  return { ...DEFAULT_TEXT_WIDGET_CONFIG }
}

function normalizeTextAlign(value) {
  const v = String(value || '').trim().toLowerCase()
  if (v === TEXT_ALIGN.LEFT) return TEXT_ALIGN.LEFT
  if (v === TEXT_ALIGN.RIGHT) return TEXT_ALIGN.RIGHT
  if (v === TEXT_ALIGN.JUSTIFY) return TEXT_ALIGN.JUSTIFY
  return TEXT_ALIGN.CENTER
}

function normalizeVerticalAlign(value) {
  const v = String(value || '').trim().toLowerCase()
  if (v === TEXT_VERTICAL_ALIGN.TOP) return TEXT_VERTICAL_ALIGN.TOP
  if (v === TEXT_VERTICAL_ALIGN.BOTTOM) return TEXT_VERTICAL_ALIGN.BOTTOM
  return TEXT_VERTICAL_ALIGN.MIDDLE
}

function normalizeWritingMode(value) {
  const v = String(value || '').trim().toLowerCase()
  return v === TEXT_WRITING_MODE.VERTICAL
    ? TEXT_WRITING_MODE.VERTICAL
    : TEXT_WRITING_MODE.HORIZONTAL
}

function normalizeTextDecoration(input) {
  const parts = []
  const raw = String(input || '').toLowerCase()
  if (raw.includes('underline')) parts.push('underline')
  if (raw.includes('line-through')) parts.push('line-through')
  return parts.join(' ')
}

export function normalizeTextWidgetConfig(input) {
  const d = DEFAULT_TEXT_WIDGET_CONFIG
  if (!input || typeof input !== 'object') {
    return { ...d }
  }
  const fontWeight = String(input.fontWeight || '').trim().toLowerCase() === 'bold' ? 'bold' : 'normal'
  const fontStyle = String(input.fontStyle || '').trim().toLowerCase() === 'italic' ? 'italic' : 'normal'
  const color = String(input.color || d.color).trim().slice(0, 32) || d.color
  return {
    content: String(input.content || ''),
    color: /^#[0-9a-fA-F]{3,8}$/.test(color) ? color : d.color,
    fontSize: clamp(input.fontSize ?? d.fontSize, 8, 120),
    fontWeight,
    fontStyle,
    textDecoration: normalizeTextDecoration(input.textDecoration),
    textAlign: normalizeTextAlign(input.textAlign),
    verticalAlign: normalizeVerticalAlign(input.verticalAlign),
    writingMode: normalizeWritingMode(input.writingMode),
    paddingTop: clamp(input.paddingTop ?? d.paddingTop, 0, 120),
    paddingRight: clamp(input.paddingRight ?? d.paddingRight, 0, 120),
    paddingBottom: clamp(input.paddingBottom ?? d.paddingBottom, 0, 120),
    paddingLeft: clamp(input.paddingLeft ?? d.paddingLeft, 0, 120),
    letterSpacing: clamp(input.letterSpacing ?? d.letterSpacing, 0, 48)
  }
}

export function textWidgetConfigEqual(a, b) {
  const x = normalizeTextWidgetConfig(a)
  const y = normalizeTextWidgetConfig(b)
  return (
    x.content === y.content &&
    x.color === y.color &&
    x.fontSize === y.fontSize &&
    x.fontWeight === y.fontWeight &&
    x.fontStyle === y.fontStyle &&
    x.textDecoration === y.textDecoration &&
    x.textAlign === y.textAlign &&
    x.verticalAlign === y.verticalAlign &&
    x.writingMode === y.writingMode &&
    x.paddingTop === y.paddingTop &&
    x.paddingRight === y.paddingRight &&
    x.paddingBottom === y.paddingBottom &&
    x.paddingLeft === y.paddingLeft &&
    x.letterSpacing === y.letterSpacing
  )
}

export function isTextWidgetItem(item) {
  return String(item?.widgetKind || '').trim() === DASHBOARD_WIDGET_KIND_TEXT
}

export function textConfigForItem(item) {
  if (!isTextWidgetItem(item)) return defaultTextWidgetConfig()
  return normalizeTextWidgetConfig(item.widgetConfig)
}

export function createTextWidgetItem({
  x = 0,
  y = 0,
  w = DASHBOARD_BASIC_WIDGET_DEFAULT_W,
  h = DASHBOARD_BASIC_WIDGET_DEFAULT_H
} = {}) {
  const id = `bw-text-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  return {
    i: id,
    x: Math.max(0, Math.round(Number(x) || 0)),
    y: Math.max(0, Math.round(Number(y) || 0)),
    w: Math.max(2, Math.round(Number(w) || DASHBOARD_BASIC_WIDGET_DEFAULT_W)),
    h: Math.max(2, Math.round(Number(h) || DASHBOARD_BASIC_WIDGET_DEFAULT_H)),
    widgetKind: DASHBOARD_WIDGET_KIND_TEXT,
    widgetConfig: defaultTextWidgetConfig()
  }
}

export function cloneTextWidgetFieldsFromItem(it) {
  if (!isTextWidgetItem(it)) return {}
  return {
    widgetKind: DASHBOARD_WIDGET_KIND_TEXT,
    widgetConfig: normalizeTextWidgetConfig(it.widgetConfig)
  }
}

export function serializeTextWidgetFieldsForApi(it) {
  if (!isTextWidgetItem(it)) return {}
  const cfg = normalizeTextWidgetConfig(it.widgetConfig)
  return {
    widgetKind: DASHBOARD_WIDGET_KIND_TEXT,
    widgetConfig: { ...cfg }
  }
}

export function textWidgetContentStyle(config) {
  const c = normalizeTextWidgetConfig(config)
  const justifyContent =
    c.verticalAlign === TEXT_VERTICAL_ALIGN.TOP
      ? 'flex-start'
      : c.verticalAlign === TEXT_VERTICAL_ALIGN.BOTTOM
        ? 'flex-end'
        : 'center'
  return {
    color: c.color,
    fontSize: `${c.fontSize}px`,
    fontWeight: c.fontWeight,
    fontStyle: c.fontStyle,
    textDecoration: c.textDecoration || 'none',
    textAlign: c.textAlign,
    letterSpacing: `${c.letterSpacing}px`,
    padding: `${c.paddingTop}px ${c.paddingRight}px ${c.paddingBottom}px ${c.paddingLeft}px`,
    writingMode: c.writingMode === TEXT_WRITING_MODE.VERTICAL ? 'vertical-rl' : 'horizontal-tb',
    display: 'flex',
    flexDirection: 'column',
    justifyContent,
    width: '100%',
    height: '100%',
    boxSizing: 'border-box',
    overflow: 'auto',
    wordBreak: 'break-word'
  }
}
