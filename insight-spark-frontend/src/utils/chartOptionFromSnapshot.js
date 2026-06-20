import * as echarts from 'echarts'
import chinaProvinceGeoJson from '../assets/maps/china-provinces.json' with { type: 'json' }

/** 从对话历史 chart_snapshot + chartType 构建 ECharts option（与看板预览逻辑对齐） */

function toNumber(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : Number.NaN
}

function buildValueAxisRange(values) {
  const finiteValues = (Array.isArray(values) ? values : [])
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value))

  if (!finiteValues.length) {
    return { min: 0, max: 1 }
  }

  const minValue = Math.min(...finiteValues)
  const maxValue = Math.max(...finiteValues)

  if (minValue >= 0) {
    return {
      min: 0,
      max: maxValue === 0 ? 1 : undefined
    }
  }

  if (maxValue <= 0) {
    return {
      min: undefined,
      max: 0
    }
  }

  return {
    min: undefined,
    max: undefined
  }
}

function normalizeChartItem(item) {
  if (!item || typeof item !== 'object') {
    return { name: String(item ?? ''), value: 0 }
  }
  const keys = Object.keys(item)
  const nameKey =
    keys.find((key) =>
      ['name', 'label', 'province', 'city', 'category', 'dimension', 'dim_name'].includes(key)
    ) || keys[0]
  const valueKey =
    keys.find((key) =>
      ['value', 'count', 'amount', 'total', 'sales', 'metric', 'metric_value'].includes(key)
    ) ||
    keys[1] ||
    keys[0]
  const nameValue = item.name ?? item.label ?? item.dim_name ?? item[nameKey] ?? ''
  const rawValue = item.value ?? item.metric_value ?? item[valueKey] ?? 0
  const numericValue = toNumber(rawValue)
  return {
    name: String(nameValue ?? ''),
    value: Number.isNaN(numericValue) ? 0 : numericValue
  }
}

export function normalizeChartType(value) {
  const type = String(value || '').toLowerCase()
  if (['line', 'pie', 'bar', 'table', 'radar', 'scatter', 'map', 'metric'].includes(type)) return type
  if (type === 'doughnut' || type === 'donut') return 'pie'
  if (type === 'card' || type === 'kpi' || type === 'indicator') return 'metric'
  if (type.includes('饼')) return 'pie'
  if (type.includes('环')) return 'pie'
  if (type.includes('折')) return 'line'
  if (type.includes('柱')) return 'bar'
  if (type.includes('表')) return 'table'
  if (type.includes('雷达')) return 'radar'
  if (type.includes('散点')) return 'scatter'
  if (type.includes('地图')) return 'map'
  if (type.includes('指标')) return 'metric'
  if (type.includes('kpi') || type.includes('card') || type.includes('indicator')) return 'metric'
  return 'bar'
}

function parseSnapshot(raw) {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(String(raw))
  } catch {
    return {}
  }
}

/** 看板 layout_json.items[].seriesItemStyles：按下标覆盖每个数据项的配色（柱/扇区/折线点） */
function normalizeSeriesItemStyles(raw) {
  if (!raw || typeof raw !== 'object') return {}
  const out = {}
  for (const [k, v] of Object.entries(raw)) {
    if (!/^\d+$/.test(String(k))) continue
    const i = parseInt(String(k), 10)
    if (!Number.isFinite(i) || i < 0 || i > 499) continue
    if (!v || typeof v !== 'object') continue
    const c = String(v.color || '').trim()
    if (c) out[i] = { color: c.slice(0, 80) }
  }
  return out
}

export function sanitizeSeriesItemStylesForApi(raw) {
  const n = normalizeSeriesItemStyles(raw)
  return Object.keys(n).length ? n : null
}

/** 用于抽屉列表：与渲染相同的维度名、指标值 */
export function normalizedChartDataPoints(row) {
  const snap = parseSnapshot(row?.chartSnapshot)
  const data = Array.isArray(snap.data) ? snap.data : []
  return data.map(normalizeChartItem)
}

function snapHasEncode(snap) {
  return snap.encode && typeof snap.encode === 'object' && Object.keys(snap.encode).length > 0
}

function isForecastSnapshot(snap) {
  const type = String(snap?.advancedAnalysisType || snap?.fieldMapping?.mappingType || '').trim()
  if (type === 'forecast' || snap?.forecastMeta) return true
  const rows = Array.isArray(snap?.data) ? snap.data : []
  return rows.some(row =>
    row && typeof row === 'object' && (
      Object.prototype.hasOwnProperty.call(row, 'history') ||
      Object.prototype.hasOwnProperty.call(row, 'forecast') ||
      Object.prototype.hasOwnProperty.call(row, 'upper') ||
      Object.prototype.hasOwnProperty.call(row, 'lower') ||
      Object.prototype.hasOwnProperty.call(row, 'phase')
    )
  )
}

function forecastPointHistoryValue(row) {
  if (!row || typeof row !== 'object') return null
  if (row.history != null) return row.history
  if (String(row.phase || '').toLowerCase() === 'history') return row.value ?? null
  return null
}

function forecastPointForecastValue(row) {
  if (!row || typeof row !== 'object') return null
  if (row.forecast != null) return row.forecast
  if (String(row.phase || '').toLowerCase() === 'forecast') return row.value ?? null
  return null
}

export function hasForecastSeriesRows(rows) {
  return Array.isArray(rows) && rows.some(row =>
    row && typeof row === 'object' && (
      Object.prototype.hasOwnProperty.call(row, 'history') ||
      Object.prototype.hasOwnProperty.call(row, 'forecast') ||
      Object.prototype.hasOwnProperty.call(row, 'upper') ||
      Object.prototype.hasOwnProperty.call(row, 'lower') ||
      String(row.phase || '').toLowerCase() === 'forecast'
    )
  )
}

function forecastLegendItem(options, key, defaultLabel, defaultShow = true) {
  const config = options?.legendConfig && typeof options.legendConfig === 'object'
    ? options.legendConfig
    : {}
  const raw = config[key] && typeof config[key] === 'object' ? config[key] : {}
  const show = raw.show == null ? defaultShow : Boolean(raw.show)
  const label = String(raw.label || defaultLabel).trim() || defaultLabel
  return { show, label }
}

export function buildForecastChartOption(rows, options = {}) {
  const rawRows = Array.isArray(rows) ? rows : []
  const source = rawRows
    .filter(row => row && typeof row === 'object' && row.name != null)
    .map(row => ({
      name: String(row.name ?? ''),
      history: forecastPointHistoryValue(row),
      forecast: forecastPointForecastValue(row),
      upper: row.upper ?? null,
      lower: row.lower ?? null,
      anomaly: row.anomaly ?? row.outlier ?? null
    }))
  const values = source.flatMap(row => [row.history, row.forecast, row.upper, row.lower])
    .map(value => Number(value))
    .filter(value => Number.isFinite(value))
  const valueAxisRange = buildValueAxisRange(values)
  const useZoom = source.length > 18
  const metricLabel = String(options.metricLabel || '预测值')
  const confidenceLabel = String(options.confidenceLabel || '95%')
  const historyLegend = forecastLegendItem(options, 'history', '历史值')
  const forecastLegend = forecastLegendItem(options, 'forecast', '预测值')
  const upperLegend = forecastLegendItem(options, 'upper', '置信上界')
  const lowerLegend = forecastLegendItem(options, 'lower', '置信下界')
  const anomalyLegend = forecastLegendItem(options, 'anomaly', '异常点', false)
  const legendData = [historyLegend, forecastLegend, upperLegend, lowerLegend, anomalyLegend]
    .filter(item => item.show)
    .map(item => item.label)
  const series = []
  if (historyLegend.show) {
    series.push({
      name: historyLegend.label,
      type: 'line',
      encode: { x: 'name', y: 'history' },
      showSymbol: false,
      connectNulls: false,
      lineStyle: { color: '#2563eb', width: 2 },
      itemStyle: { color: '#2563eb' }
    })
  }
  if (forecastLegend.show) {
    series.push({
      name: forecastLegend.label,
      type: 'line',
      encode: { x: 'name', y: 'forecast' },
      showSymbol: true,
      symbolSize: 6,
      connectNulls: false,
      lineStyle: { color: '#16a34a', width: 2, type: 'dashed' },
      itemStyle: { color: '#16a34a' }
    })
  }
  if (upperLegend.show) {
    series.push({
      name: upperLegend.label,
      type: 'line',
      encode: { x: 'name', y: 'upper' },
      showSymbol: false,
      connectNulls: false,
      lineStyle: { color: '#93c5fd', width: 1 },
      areaStyle: lowerLegend.show ? { color: 'rgba(147, 197, 253, 0.18)' } : undefined,
      tooltip: { valueFormatter: value => `${value}（${confidenceLabel} 上界）` }
    })
  }
  if (lowerLegend.show) {
    series.push({
      name: lowerLegend.label,
      type: 'line',
      encode: { x: 'name', y: 'lower' },
      showSymbol: false,
      connectNulls: false,
      lineStyle: { color: '#93c5fd', width: 1 },
      tooltip: { valueFormatter: value => `${value}（${confidenceLabel} 下界）` }
    })
  }
  if (anomalyLegend.show) {
    const anomalyData = source
      .filter(row => row.anomaly != null)
      .map(row => ({ name: row.name, value: [row.name, row.anomaly] }))
    series.push({
      name: anomalyLegend.label,
      type: 'scatter',
      encode: { x: 'name', y: 'anomaly' },
      data: anomalyData,
      symbolSize: 9,
      itemStyle: { color: '#ef4444' },
      tooltip: { valueFormatter: value => `${value}（异常点）` }
    })
  }
  return {
    animation: true,
    tooltip: { trigger: 'axis', confine: true },
    legend: {
      top: 2,
      data: legendData
    },
    grid: {
      left: 48,
      right: 18,
      top: 46,
      bottom: useZoom ? 70 : 48,
      containLabel: true
    },
    dataset: [{
      dimensions: ['name', 'history', 'forecast', 'upper', 'lower', 'anomaly'],
      source
    }],
    xAxis: {
      type: 'category',
      axisLabel: {
        interval: 0,
        rotate: source.length > 8 ? 38 : 20,
        hideOverlap: true,
        fontSize: 11,
        formatter: (value) => {
          const text = String(value ?? '')
          return text.length > 14 ? `${text.slice(0, 14)}…` : text
        }
      }
    },
    yAxis: {
      type: 'value',
      name: metricLabel,
      min: valueAxisRange.min,
      max: valueAxisRange.max,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: {
        fontSize: 11,
        formatter: (value) => {
          const n = Number(value)
          if (!Number.isFinite(n)) return value
          if (Math.abs(n) >= 10000) return `${(n / 10000).toFixed(1)}w`
          return `${n}`
        }
      }
    },
    series,
    dataZoom: useZoom
      ? [
          { type: 'slider', show: true, xAxisIndex: 0, bottom: 8, height: 22, start: 0, end: 100 },
          { type: 'inside', xAxisIndex: 0, start: 0, end: 100 }
        ]
      : undefined
  }
}

function buildForecastOptionFromSnapshot(snap) {
  const rows = Array.isArray(snap.data) ? snap.data : []
  const prediction = snap?.optionTemplate?.prediction || {}
  return buildForecastChartOption(rows, {
    metricLabel: snap?.fieldMapping?.metric || snap?.forecastMeta?.metricField || '预测值',
    confidenceLabel: snap?.forecastMeta?.confidence || prediction?.confidenceLabel || '95%',
    legendConfig: prediction?.legendConfig
  })
}

function buildDatasetFromSnapshot(snap) {
  const dims =
    Array.isArray(snap.dimensions) && snap.dimensions.length > 0
      ? snap.dimensions.map(String)
      : ['name', 'value']
  const rows = Array.isArray(snap.data) ? snap.data : []
  const source = rows.map((row) => {
    if (!row || typeof row !== 'object') return dims.map(() => null)
    return dims.map((d) => row[d])
  })
  return { dimensions: dims, source }
}

/** template 补 built 中缺失字段；series 按下标对每一项做 defaultsDeep */
function defaultsDeep(target, source) {
  if (source == null || typeof source !== 'object') return target
  if (target == null || typeof target !== 'object') return source
  if (Array.isArray(target)) return target
  const out = { ...target }
  for (const [k, sv] of Object.entries(source)) {
    if (!(k in out) || out[k] === undefined) {
      out[k] = sv
    } else if (
      sv != null &&
      typeof sv === 'object' &&
      !Array.isArray(sv) &&
      out[k] != null &&
      typeof out[k] === 'object' &&
      !Array.isArray(out[k])
    ) {
      out[k] = defaultsDeep(out[k], sv)
    }
  }
  return out
}

function normalizeTemplateTextStyle(template) {
  const raw = template?.textStyle
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const style = {}
  const fontFamily = String(raw.fontFamily || '').trim()
  const fontSize = Number(raw.fontSize)
  if (fontFamily) style.fontFamily = fontFamily
  if (Number.isFinite(fontSize) && fontSize >= 10 && fontSize <= 28) {
    style.fontSize = Math.round(fontSize)
  }
  return Object.keys(style).length ? style : null
}

function mergeTemplateTextStyle(target, textStyle) {
  if (!textStyle || !target || typeof target !== 'object' || Array.isArray(target)) return target
  return {
    ...target,
    textStyle: {
      ...(target.textStyle && typeof target.textStyle === 'object' && !Array.isArray(target.textStyle) ? target.textStyle : {}),
      ...textStyle
    }
  }
}

function applyTemplateTextStyleToAxis(axis, textStyle) {
  if (!textStyle || !axis) return axis
  const applyOne = item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return item
    return {
      ...item,
      axisLabel: {
        ...(item.axisLabel && typeof item.axisLabel === 'object' && !Array.isArray(item.axisLabel) ? item.axisLabel : {}),
        ...textStyle
      },
      nameTextStyle: {
        ...(item.nameTextStyle && typeof item.nameTextStyle === 'object' && !Array.isArray(item.nameTextStyle) ? item.nameTextStyle : {}),
        ...textStyle
      }
    }
  }
  return Array.isArray(axis) ? axis.map(applyOne) : applyOne(axis)
}

function applyTemplateTextStyleToSeries(series, textStyle) {
  if (!textStyle || !Array.isArray(series)) return series
  return series.map(item => {
    if (!item || typeof item !== 'object') return item
    return {
      ...item,
      label: {
        ...(item.label && typeof item.label === 'object' && !Array.isArray(item.label) ? item.label : {}),
        ...textStyle
      }
    }
  })
}

function applyTemplateTextStyle(option, template) {
  const textStyle = normalizeTemplateTextStyle(template)
  if (!textStyle || !option || typeof option !== 'object') return option
  return {
    ...option,
    textStyle: {
      ...(option.textStyle && typeof option.textStyle === 'object' && !Array.isArray(option.textStyle) ? option.textStyle : {}),
      ...textStyle
    },
    title: Array.isArray(option.title)
      ? option.title.map(item => mergeTemplateTextStyle(item, textStyle))
      : mergeTemplateTextStyle(option.title, textStyle),
    legend: Array.isArray(option.legend)
      ? option.legend.map(item => mergeTemplateTextStyle(item, textStyle))
      : mergeTemplateTextStyle(option.legend, textStyle),
    tooltip: mergeTemplateTextStyle(option.tooltip, textStyle),
    xAxis: applyTemplateTextStyleToAxis(option.xAxis, textStyle),
    yAxis: applyTemplateTextStyleToAxis(option.yAxis, textStyle),
    series: applyTemplateTextStyleToSeries(option.series, textStyle)
  }
}

export function applyOptionTemplateDefaults(built, template) {
  if (!template || typeof template !== 'object') return built
  if (!built || typeof built !== 'object') return built
  const { series: ts, ...tRest } = template
  const { series: bs, ...bRest } = built
  const mergedTop = defaultsDeep({ ...bRest }, tRest)
  const out = { ...built, ...mergedTop }
  if (Array.isArray(bs)) {
    const mergedSeries = bs.map((seriesItem, i) =>
      ts?.[i] != null && typeof ts[i] === 'object' && typeof seriesItem === 'object'
        ? defaultsDeep({ ...seriesItem }, ts[i])
        : seriesItem
    )
    if (Array.isArray(ts) && ts.length > bs.length) {
      mergedSeries.push(...ts.slice(bs.length).filter(item => item && typeof item === 'object'))
    }
    out.series = mergedSeries
  } else {
    out.series = bs
  }
  if (Array.isArray(template.color) && template.color.length) {
    out.color = template.color
  }
  return applyTemplateTextStyle(out, template)
}

function clampNumber(value, min, max, fallback) {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, Math.round(n)))
}

function boolValue(value, fallback = false) {
  if (value == null) return fallback
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  return String(value).toLowerCase() === 'true'
}

function mergeObjects(base, override) {
  if (!override || typeof override !== 'object' || Array.isArray(override)) return base
  const out = { ...(base && typeof base === 'object' && !Array.isArray(base) ? base : {}) }
  for (const [key, value] of Object.entries(override)) {
    if (value === undefined || value === null) continue
    if (
      value &&
      typeof value === 'object' &&
      !Array.isArray(value) &&
      out[key] &&
      typeof out[key] === 'object' &&
      !Array.isArray(out[key])
    ) {
      out[key] = mergeObjects(out[key], value)
    } else {
      out[key] = value
    }
  }
  return out
}

function normalizeDynamicConfig(template = {}) {
  const dynamic = template?.dynamic && typeof template.dynamic === 'object' ? template.dynamic : {}
  const dataZoom = template?.dataZoom && typeof template.dataZoom === 'object' && !Array.isArray(template.dataZoom)
    ? template.dataZoom
    : {}
  return {
    refreshIntervalSeconds: clampNumber(
      dynamic.refreshIntervalSeconds ?? template.refreshIntervalSeconds ?? template.dynamicRefreshInterval,
      0,
      3600,
      0
    ),
    incrementalRendering: boolValue(dynamic.incrementalRendering, false),
    progressive: clampNumber(dynamic.progressive, 0, 20000, 0),
    progressiveThreshold: clampNumber(dynamic.progressiveThreshold, 0, 100000, 3000),
    largeThreshold: clampNumber(dynamic.largeThreshold, 0, 100000, 2000),
    autoDataZoomThreshold: clampNumber(dynamic.autoDataZoomThreshold ?? dataZoom.threshold, 4, 500, 14),
    autoLegendScrollThreshold: clampNumber(dynamic.autoLegendScrollThreshold, 4, 500, 10),
    dataZoomStart: clampNumber(dynamic.dataZoomStart ?? dataZoom.start, 0, 100, 0),
    dataZoomEnd: clampNumber(dynamic.dataZoomEnd ?? dataZoom.end, 1, 100, 60)
  }
}

function normalizeTooltip(template, fallbackTrigger) {
  const raw = template?.tooltip
  if (raw === false) return { show: false, trigger: fallbackTrigger, confine: true }
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const trigger = ['axis', 'item'].includes(String(raw.trigger || '').toLowerCase())
      ? String(raw.trigger).toLowerCase()
      : fallbackTrigger
    return mergeObjects({ show: true, trigger, confine: true }, raw)
  }
  return null
}

function optionCategoryCount(option, fallback = 0) {
  const xAxis = Array.isArray(option?.xAxis) ? option.xAxis[0] : option?.xAxis
  if (Array.isArray(xAxis?.data)) return xAxis.data.length
  const dataset = Array.isArray(option?.dataset) ? option.dataset[0] : option?.dataset
  if (Array.isArray(dataset?.source)) return dataset.source.length
  const series = Array.isArray(option?.series) ? option.series : []
  const firstSeries = series.find(item => Array.isArray(item?.data))
  if (firstSeries?.data) return firstSeries.data.length
  return fallback
}

function optionHasCartesianAxis(option) {
  return Boolean(option?.xAxis && option?.yAxis)
}

function shouldEnableTemplateDataZoom(template, categoryCount, threshold) {
  const raw = template?.dataZoom
  if (Array.isArray(raw)) return true
  if (raw && typeof raw === 'object') {
    return raw.enabled == null ? true : boolValue(raw.enabled, true)
  }
  if (raw != null) return boolValue(raw, false)
  return categoryCount > threshold
}

function isTemplateDataZoomDisabled(template) {
  const raw = template?.dataZoom
  if (raw === false) return true
  return Boolean(raw && typeof raw === 'object' && !Array.isArray(raw) && raw.enabled === false)
}

function buildAutoDataZoom(dynamic, categoryCount) {
  const start = Math.min(dynamic.dataZoomStart, dynamic.dataZoomEnd - 1)
  const autoEnd = categoryCount > dynamic.autoDataZoomThreshold
    ? Math.min(100, Math.max(8, Math.ceil((dynamic.autoDataZoomThreshold / categoryCount) * 100)))
    : dynamic.dataZoomEnd
  const end = Math.max(start + 1, Math.min(dynamic.dataZoomEnd, autoEnd))
  return normalizeInteractiveDataZoom([
    { type: 'slider', show: true, xAxisIndex: 0, bottom: 8, height: 22, start, end },
    { type: 'inside', xAxisIndex: 0, start, end }
  ])
}

export function normalizeInteractiveDataZoom(dataZoom) {
  if (!Array.isArray(dataZoom)) return dataZoom
  return dataZoom.map((item) => {
    if (!item || typeof item !== 'object') return item
    return {
      ...item,
      disabled: false,
      zoomLock: false,
      brushSelect: false,
      realtime: item.realtime !== false
    }
  })
}

function normalizeLegendForOverflow(legend, template, categoryCount, dynamic, chartType) {
  const templateLegend = template?.legend && typeof template.legend === 'object' && !Array.isArray(template.legend)
    ? template.legend
    : {}
  const baseLegend = legend && typeof legend === 'object' && !Array.isArray(legend) ? legend : {}
  const merged = mergeObjects(baseLegend, templateLegend)
  const shouldScroll = categoryCount > dynamic.autoLegendScrollThreshold || String(chartType).toLowerCase() === 'pie'
  if (!shouldScroll) return Object.keys(merged).length ? merged : legend
  return {
    type: 'scroll',
    pageButtonGap: 6,
    pageIconSize: 10,
    ...merged
  }
}

function applyDynamicSeries(series, dynamic, categoryCount) {
  if (!Array.isArray(series)) return series
  return series.map(item => {
    if (!item || typeof item !== 'object') return item
    const next = { ...item }
    if (dynamic.incrementalRendering) {
      next.progressive = dynamic.progressive > 0 ? dynamic.progressive : 400
      next.progressiveThreshold = dynamic.progressiveThreshold
    }
    if (dynamic.largeThreshold > 0) {
      next.largeThreshold = dynamic.largeThreshold
      if (['bar', 'line', 'scatter'].includes(String(next.type || '').toLowerCase()) && categoryCount >= dynamic.largeThreshold) {
        next.large = true
      }
    }
    return next
  })
}

function applyTemplateAnimationDefaults(option, template) {
  const out = option
  const hasAnimationSetting = template?.animation != null || out.animation != null
  const enabled = hasAnimationSetting
    ? boolValue(template?.animation ?? out.animation, true)
    : true
  out.animation = enabled
  if (enabled) {
    out.animationDuration = clampNumber(
      template?.animationDuration ?? out.animationDuration,
      100,
      5000,
      1500
    )
    out.animationDurationUpdate = clampNumber(
      template?.animationDurationUpdate ?? out.animationDurationUpdate,
      100,
      5000,
      1200
    )
    out.animationEasing = template?.animationEasing || out.animationEasing || 'cubicOut'
    out.animationEasingUpdate = template?.animationEasingUpdate || out.animationEasingUpdate || 'cubicOut'
    out.animationThreshold = clampNumber(
      template?.animationThreshold ?? out.animationThreshold,
      0,
      100000,
      2000
    )
  } else {
    out.animationDuration = 0
    out.animationDurationUpdate = 0
  }
  return out
}

function applySeriesAnimationDefaults(series, enabled) {
  if (!Array.isArray(series)) return series
  return series.map(item => {
    if (!item || typeof item !== 'object') return item
    if (!enabled) {
      return {
        ...item,
        animation: false,
        animationDuration: 0,
        animationDurationUpdate: 0
      }
    }
    const next = { ...item }
    const type = String(next.type || '').toLowerCase()
    if (next.animation == null) next.animation = true
    if (next.animationDuration == null) {
      next.animationDuration = type === 'line' ? 1600 : type === 'pie' ? 1300 : 1450
    }
    if (next.animationDurationUpdate == null) next.animationDurationUpdate = 1200
    if (next.animationEasing == null) next.animationEasing = 'cubicOut'
    if (next.animationEasingUpdate == null) next.animationEasingUpdate = 'cubicOut'
    if (type === 'pie') {
      if (next.animationType == null) next.animationType = 'expansion'
      if (next.animationTypeUpdate == null) next.animationTypeUpdate = 'transition'
    }
    if (next.animationDelay == null) {
      next.animationDelay = index => {
        if (type === 'line') return 0
        if (type === 'pie') return Math.min(index * 28, 360)
        return Math.min(index * 32, 480)
      }
    }
    if (next.animationDelayUpdate == null) {
      next.animationDelayUpdate = index => Math.min(index * 12, 180)
    }
    return next
  })
}

function cloneChartOptionValue(value) {
  if (Array.isArray(value)) return value.map(cloneChartOptionValue)
  if (!value || typeof value !== 'object') return value
  const out = {}
  for (const [key, item] of Object.entries(value)) {
    out[key] = cloneChartOptionValue(item)
  }
  return out
}

function isLikelyCategoryDatasetKey(key) {
  return /^(name|label|category|dimension|date|time|month|year|quarter|week|day|x)$/i.test(String(key || ''))
}

function isNumericLike(value) {
  if (typeof value === 'number') return Number.isFinite(value)
  if (typeof value !== 'string') return false
  const text = value.trim()
  if (!text) return false
  return Number.isFinite(Number(text))
}

function zeroChartDataValue(value) {
  if (Array.isArray(value)) {
    return value.map((item, index) => (index === 0 ? item : (isNumericLike(item) ? 0 : item)))
  }
  return isNumericLike(value) ? 0 : value
}

function zeroSeriesDataItem(item) {
  if (item == null) return item
  if (isNumericLike(item)) return 0
  if (Array.isArray(item)) return zeroChartDataValue(item)
  if (typeof item === 'object') {
    const next = { ...item }
    if (Object.prototype.hasOwnProperty.call(next, 'value')) {
      next.value = zeroChartDataValue(next.value)
    }
    return next
  }
  return item
}

function zeroDatasetSourceRow(row) {
  if (Array.isArray(row)) {
    return row.map((item, index) => (index === 0 ? item : (isNumericLike(item) ? 0 : item)))
  }
  if (row && typeof row === 'object') {
    const next = { ...row }
    for (const [key, value] of Object.entries(next)) {
      if (!isLikelyCategoryDatasetKey(key) && isNumericLike(value)) {
        next[key] = 0
      }
    }
    return next
  }
  return row
}

function zeroDatasetSource(source) {
  return Array.isArray(source) ? source.map(zeroDatasetSourceRow) : source
}

export function getChartAnimationMeta(option) {
  const enabled = option?.animation !== false
  return {
    enabled,
    label: enabled ? '动画已开启' : '动画已关闭',
    duration: enabled ? Number(option?.animationDuration ?? 1500) || 1500 : 0,
    updateDuration: enabled ? Number(option?.animationDurationUpdate ?? 1200) || 1200 : 0,
    easing: enabled ? String(option?.animationEasing || 'cubicOut') : 'none',
    mode: enabled ? '数据从零值过渡到真实值' : '静态渲染'
  }
}

export function buildAnimationReplayStartOption(option) {
  if (!option || typeof option !== 'object' || option.animation === false) return null
  const start = cloneChartOptionValue(option)
  start.animation = false
  start.animationDuration = 0
  start.animationDurationUpdate = 0
  if (Array.isArray(start.series)) {
    start.series = start.series.map(series => {
      if (!series || typeof series !== 'object') return series
      const next = { ...series, animation: false, animationDuration: 0, animationDurationUpdate: 0 }
      if (Array.isArray(next.data)) {
        next.data = next.data.map(zeroSeriesDataItem)
      }
      return next
    })
  }
  if (Array.isArray(start.dataset)) {
    start.dataset = start.dataset.map(dataset => {
      if (!dataset || typeof dataset !== 'object') return dataset
      return { ...dataset, source: zeroDatasetSource(dataset.source) }
    })
  } else if (start.dataset && typeof start.dataset === 'object') {
    start.dataset = { ...start.dataset, source: zeroDatasetSource(start.dataset.source) }
  }
  return start
}

export function resolveDynamicRefreshInterval(optionOrTemplate) {
  const dynamic = normalizeDynamicConfig(optionOrTemplate || {})
  return dynamic.refreshIntervalSeconds >= 5 ? dynamic.refreshIntervalSeconds : 0
}

export function applyDynamicInteractionDefaults(option, template, context = {}) {
  if (!option || typeof option !== 'object') return option
  const sourceTemplate = template && typeof template === 'object' ? template : {}
  const dynamic = normalizeDynamicConfig(sourceTemplate)
  const chartType = normalizeChartType(context.chartType || sourceTemplate.type || option?.series?.[0]?.type)
  const categoryCount = optionCategoryCount(option, Number(context.categoryCount) || 0)
  const out = { ...option, dynamic: { ...(option.dynamic || {}), ...dynamic } }
  applyTemplateAnimationDefaults(out, sourceTemplate)
  const tooltip = normalizeTooltip(sourceTemplate, chartType === 'pie' ? 'item' : 'axis')
  if (tooltip) {
    out.tooltip = mergeObjects(out.tooltip, tooltip)
  } else if (out.tooltip && typeof out.tooltip === 'object') {
    out.tooltip = mergeObjects(out.tooltip, { confine: true })
  }
  out.legend = normalizeLegendForOverflow(out.legend, sourceTemplate, categoryCount, dynamic, chartType)
  if (optionHasCartesianAxis(out)) {
    if (shouldEnableTemplateDataZoom(sourceTemplate, categoryCount, dynamic.autoDataZoomThreshold)) {
      out.dataZoom = Array.isArray(sourceTemplate.dataZoom)
        ? normalizeInteractiveDataZoom(sourceTemplate.dataZoom)
        : buildAutoDataZoom(dynamic, categoryCount)
      if (out.grid && typeof out.grid === 'object' && !Array.isArray(out.grid)) {
        out.grid = { ...out.grid, bottom: Math.max(Number(out.grid.bottom) || 0, 72) }
      }
    } else if (isTemplateDataZoomDisabled(sourceTemplate)) {
      out.dataZoom = []
    } else if (Array.isArray(out.dataZoom) && out.dataZoom.length > 0) {
      out.dataZoom = normalizeInteractiveDataZoom(out.dataZoom)
    }
  }
  out.series = applySeriesAnimationDefaults(
    applyDynamicSeries(out.series, dynamic, categoryCount),
    out.animation !== false
  )
  if (chartType === 'map') {
    return sanitizeMapChartOption(out)
  }
  return out
}

function readBarUi(ui) {
  const barMaxW = Number(ui.barMaxWidth)
  const barW =
    Number.isFinite(barMaxW) && barMaxW >= 4 && barMaxW <= 160 ? Math.round(barMaxW) : 32
  const barColor = typeof ui.barColor === 'string' && ui.barColor.trim() ? ui.barColor.trim() : ''
  return { barW, barColor }
}

function buildRadarFromPoints(points, ui) {
  const values = points.map((p) => Number(p.value ?? 0))
  const maxVal = Math.max(...values, 0)
  const max = maxVal > 0 ? Math.ceil(maxVal * 1.15) : 100
  const { barColor } = readBarUi(ui)
  return {
    tooltip: { trigger: 'item', confine: true },
    radar: {
      indicator: points.map((p) => ({ name: p.name, max })),
      radius: '62%',
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisName: { fontSize: 11, color: '#64748b' }
    },
    series: [
      {
        type: 'radar',
        data: [{ value: values, name: '数据' }],
        areaStyle: { opacity: 0.25 },
        lineStyle: barColor ? { color: barColor, width: 2 } : { width: 2 },
        itemStyle: barColor ? { color: barColor } : undefined,
        symbolSize: 4
      }
    ]
  }
}

function normalizeGeoName(value) {
  const raw = String(value ?? '').trim()
  if (!raw) return ''
  return raw
    .replace(/^(中国|中华人民共和国)/, '')
    .replace(/(维吾尔自治区|壮族自治区|回族自治区|特别行政区|自治区|省|市|区域|大区|地区|盟|州)$/u, '')
    .trim() || raw
}

const INSIGHT_CHINA_PROVINCE_MAP_NAME = 'insight-china-provinces'
const INSIGHT_CHINA_REGION_NAMES = ['东北', '华北', '华东', '华中', '华南', '西南', '西北', '港澳台']
const INSIGHT_CHINA_REGION_PROVINCES = {
  华北: ['北京市', '天津市', '河北省', '山西省', '内蒙古自治区'],
  东北: ['辽宁省', '吉林省', '黑龙江省'],
  华东: ['上海市', '江苏省', '浙江省', '安徽省', '福建省', '江西省', '山东省'],
  华中: ['河南省', '湖北省', '湖南省'],
  华南: ['广东省', '广西壮族自治区', '海南省'],
  西南: ['重庆市', '四川省', '贵州省', '云南省', '西藏自治区'],
  西北: ['陕西省', '甘肃省', '青海省', '宁夏回族自治区', '新疆维吾尔自治区'],
  港澳台: ['香港特别行政区', '澳门特别行政区', '台湾省']
}
const INSIGHT_CHINA_REGION_LABEL_PROVINCE = {
  华北: '河北省',
  东北: '吉林省',
  华东: '安徽省',
  华中: '湖北省',
  华南: '广东省',
  西南: '四川省',
  西北: '甘肃省',
  港澳台: '台湾省'
}
const INSIGHT_CHINA_REGION_ALIAS_MAP = new Map([
  ['北京', '华北'], ['天津', '华北'], ['河北', '华北'], ['山西', '华北'], ['内蒙古', '华北'],
  ['辽宁', '东北'], ['吉林', '东北'], ['黑龙江', '东北'],
  ['上海', '华东'], ['江苏', '华东'], ['浙江', '华东'], ['安徽', '华东'], ['福建', '华东'], ['江西', '华东'], ['山东', '华东'],
  ['河南', '华中'], ['湖北', '华中'], ['湖南', '华中'],
  ['广东', '华南'], ['广西', '华南'], ['海南', '华南'],
  ['重庆', '西南'], ['四川', '西南'], ['贵州', '西南'], ['云南', '西南'], ['西藏', '西南'],
  ['陕西', '西北'], ['甘肃', '西北'], ['青海', '西北'], ['宁夏', '西北'], ['新疆', '西北'],
  ['香港', '港澳台'], ['澳门', '港澳台'], ['台湾', '港澳台']
])
const INSIGHT_CHINA_PROVINCE_GEOJSON = {
  ...chinaProvinceGeoJson,
  features: Array.isArray(chinaProvinceGeoJson?.features) ? chinaProvinceGeoJson.features : []
}
const INSIGHT_CHINA_PROVINCE_NAMES = INSIGHT_CHINA_PROVINCE_GEOJSON.features
  .map(feature => String(feature?.properties?.name || '').trim())
  .filter(Boolean)
const INSIGHT_CHINA_PROVINCE_BY_NORMALIZED_NAME = new Map()

for (const provinceName of INSIGHT_CHINA_PROVINCE_NAMES) {
  INSIGHT_CHINA_PROVINCE_BY_NORMALIZED_NAME.set(provinceName, provinceName)
  INSIGHT_CHINA_PROVINCE_BY_NORMALIZED_NAME.set(normalizeGeoName(provinceName), provinceName)
}

let insightChinaRegionMapRegistered = false

function ensureInsightChinaRegionMapRegistered() {
  if (insightChinaRegionMapRegistered) return true
  try {
    if (typeof echarts.getMap === 'function' && echarts.getMap(INSIGHT_CHINA_PROVINCE_MAP_NAME)) {
      insightChinaRegionMapRegistered = true
      return true
    }
    if (typeof echarts.registerMap !== 'function') return false
    echarts.registerMap(INSIGHT_CHINA_PROVINCE_MAP_NAME, INSIGHT_CHINA_PROVINCE_GEOJSON)
    insightChinaRegionMapRegistered = true
    return true
  } catch {
    return false
  }
}

function normalizeMapRegionName(value) {
  const text = normalizeGeoName(value).replace(/\s+/g, '')
  if (!text) return ''
  if (INSIGHT_CHINA_REGION_NAMES.includes(text)) return text
  for (const region of INSIGHT_CHINA_REGION_NAMES) {
    if (text.includes(region)) return region
  }
  for (const [alias, region] of INSIGHT_CHINA_REGION_ALIAS_MAP.entries()) {
    if (text === alias || text.includes(alias)) return region
  }
  return text
}

function normalizeMapProvinceName(value) {
  const raw = String(value ?? '').trim().replace(/\s+/g, '')
  if (!raw) return ''
  const normalized = normalizeGeoName(raw)
  return INSIGHT_CHINA_PROVINCE_BY_NORMALIZED_NAME.get(raw) ||
    INSIGHT_CHINA_PROVINCE_BY_NORMALIZED_NAME.get(normalized) ||
    ''
}

function expandMapPointToProvinceItems(point) {
  const rawName = String(point?.name ?? '').trim()
  const regionName = normalizeMapRegionName(rawName)
  const provinceName = normalizeMapProvinceName(rawName)
  const value = Number(point?.value)
  const safeValue = Number.isFinite(value) ? value : 0
  const base = {
    ...point,
    value: safeValue,
    sourceValue: Number.isFinite(Number(point?.sourceValue)) ? Number(point.sourceValue) : safeValue,
    sourceName: String(point?.sourceName || rawName).trim(),
    regionName: String(point?.regionName || (
      INSIGHT_CHINA_REGION_NAMES.includes(regionName)
        ? regionName
        : (provinceName ? normalizeMapRegionName(provinceName) : regionName)
    )).trim()
  }

  if ((point?.sourceName || point?.regionName) && provinceName) {
    return [{ ...base, name: provinceName }]
  }

  if (INSIGHT_CHINA_REGION_PROVINCES[regionName]) {
    return INSIGHT_CHINA_REGION_PROVINCES[regionName].map(province => ({
      ...base,
      name: province,
      sourceName: rawName || regionName,
      regionName
    }))
  }

  if (provinceName) {
    return [{ ...base, name: provinceName }]
  }

  return rawName ? [{ ...base, name: rawName }] : []
}

function normalizeMapPoints(points) {
  const aggregated = new Map()
  for (const point of Array.isArray(points) ? points : []) {
    const provinceItems = expandMapPointToProvinceItems(point)
    for (const item of provinceItems) {
      const name = item.name
      if (!name) continue
      const existing = aggregated.get(name)
      if (existing) {
        existing.value += Number(item.value) || 0
        existing.sourceName = existing.sourceName || item.sourceName
        existing.regionName = existing.regionName || item.regionName
        continue
      }
      aggregated.set(name, item)
    }
  }
  return Array.from(aggregated.values())
}

function buildMapRegionLabelMap(mapData) {
  const groups = new Map()
  for (const item of Array.isArray(mapData) ? mapData : []) {
    const regionName = String(item?.regionName || normalizeMapRegionName(item?.name)).trim()
    if (!regionName) continue
    const value = Number(item?.value)
    const sourceValue = Number(item?.sourceValue)
    const group = groups.get(regionName) || {
      regionName,
      provinceNames: [],
      values: [],
      sourceValues: []
    }
    group.provinceNames.push(String(item?.name || '').trim())
    if (Number.isFinite(value)) group.values.push(value)
    if (Number.isFinite(sourceValue)) group.sourceValues.push(sourceValue)
    groups.set(regionName, group)
  }

  const result = new Map()
  for (const group of groups.values()) {
    const preferredProvince = INSIGHT_CHINA_REGION_LABEL_PROVINCE[group.regionName]
    const provinceName = group.provinceNames.includes(preferredProvince)
      ? preferredProvince
      : group.provinceNames[0]
    if (!provinceName) continue
    const distinctSourceValues = [...new Set(group.sourceValues.map(value => Number(value).toPrecision(12)))]
    const displayValue = distinctSourceValues.length === 1
      ? group.sourceValues[0]
      : group.values.reduce((sum, value) => sum + value, 0)
    result.set(provinceName, {
      regionName: group.regionName,
      value: Number.isFinite(displayValue) ? displayValue : 0
    })
  }
  return result
}

function objectRowsFromSnapshot(snap) {
  return (Array.isArray(snap?.data) ? snap.data : [])
    .filter((row) => row && typeof row === 'object' && !Array.isArray(row))
}

function normalizeKeyCandidates(...groups) {
  return groups
    .flat()
    .map((value) => String(value || '').trim())
    .filter(Boolean)
}

function firstNumericObjectKey(rows, candidates = [], exclude = []) {
  const sourceRows = Array.isArray(rows) ? rows : []
  const first = sourceRows.find((row) => row && typeof row === 'object' && !Array.isArray(row)) || {}
  const keys = Object.keys(first)
  if (!keys.length) return ''
  const excluded = new Set(exclude.map((key) => String(key || '').trim()).filter(Boolean))
  const numericKeys = keys.filter((key) =>
    !excluded.has(key) &&
    sourceRows.some((row) => Number.isFinite(Number(row?.[key])))
  )
  if (!numericKeys.length) return ''
  const candidateSet = new Set(candidates.map((key) => String(key || '').trim()).filter(Boolean))
  return numericKeys.find((key) => candidateSet.has(key)) || numericKeys[0] || ''
}

function firstTextObjectKey(rows, candidates = [], exclude = []) {
  const first = (Array.isArray(rows) ? rows : [])
    .find((row) => row && typeof row === 'object' && !Array.isArray(row)) || {}
  const keys = Object.keys(first)
  if (!keys.length) return ''
  const excluded = new Set(exclude.map((key) => String(key || '').trim()).filter(Boolean))
  const candidateSet = new Set(candidates.map((key) => String(key || '').trim()).filter(Boolean))
  return keys.find((key) => !excluded.has(key) && candidateSet.has(key)) ||
    keys.find((key) => !excluded.has(key) && !Number.isFinite(Number(first[key]))) ||
    ''
}

function normalizeListCandidates(value) {
  if (Array.isArray(value)) {
    return value.map((item) => String(item || '').trim()).filter(Boolean)
  }
  return String(value || '')
    .split(/[,，、\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function metricLabelForKey(fm, key, index, metricKeys = []) {
  const labels = fm?.metricLabels
  if (labels && typeof labels === 'object' && !Array.isArray(labels)) {
    const label = String(labels[key] || '').trim()
    if (label) return label
  }
  if (Array.isArray(labels)) {
    const label = String(labels[index] || '').trim()
    if (label) return label
  }
  const metricNames = normalizeListCandidates(fm?.metricNames || fm?.metrics)
  if (metricNames[index]) return metricNames[index]
  const configuredIndex = metricKeys.indexOf(key)
  if (configuredIndex >= 0 && metricNames[configuredIndex]) return metricNames[configuredIndex]
  return key
}

function resolveRadarMetricKeysFromRows(rows, snap) {
  const sourceRows = Array.isArray(rows) ? rows : []
  const first = sourceRows.find((row) => row && typeof row === 'object' && !Array.isArray(row)) || {}
  const keys = Object.keys(first)
  if (!keys.length) return []
  const fm = snap?.fieldMapping && typeof snap.fieldMapping === 'object' ? snap.fieldMapping : {}
  const configured = normalizeListCandidates(fm.metricKeys)
  const dimensionCandidates = new Set(normalizeKeyCandidates(
    fm.dimensionKey,
    fm.dimension,
    snap?.encode?.itemName,
    snap?.encode?.x,
    'name',
    'dim_name',
    'dimension',
    'label'
  ))
  const result = []
  for (const key of configured) {
    if (keys.includes(key) && sourceRows.some((row) => Number.isFinite(Number(row?.[key])))) {
      result.push(key)
    }
  }
  for (const key of keys) {
    if (result.includes(key) || dimensionCandidates.has(key)) continue
    if (sourceRows.some((row) => Number.isFinite(Number(row?.[key])))) {
      result.push(key)
    }
  }
  return result
}

function buildRadarFromMetricRows(rows, snap, ui, itemOv = {}) {
  const sourceRows = (Array.isArray(rows) ? rows : [])
    .filter((row) => row && typeof row === 'object' && !Array.isArray(row))
  if (!sourceRows.length) return null
  const fm = snap?.fieldMapping && typeof snap.fieldMapping === 'object' ? snap.fieldMapping : {}
  const metricKeys = resolveRadarMetricKeysFromRows(sourceRows, snap)
  if (metricKeys.length < 3) return null

  const nameKey = firstTextObjectKey(sourceRows, normalizeKeyCandidates(
    fm.dimensionKey,
    fm.dimension,
    snap?.encode?.itemName,
    snap?.encode?.x,
    'name',
    'dim_name'
  ), metricKeys)
  const maxima = metricKeys.map((key) => {
    const values = sourceRows.map((row) => Number(row?.[key])).filter((value) => Number.isFinite(value))
    const max = values.length ? Math.max(...values, 0) : 0
    return max > 0 ? Number((max * 1.15).toPrecision(12)) : 1
  })
  const indicator = metricKeys.map((key, index) => ({
    name: metricLabelForKey(fm, key, index, metricKeys),
    max: maxima[index]
  }))
  const data = sourceRows.map((row, index) => {
    const name = String(row?.[nameKey] ?? row?.name ?? row?.dim_name ?? row?.dimension ?? index + 1)
    const item = {
      name,
      value: metricKeys.map((key) => {
        const value = Number(row?.[key])
        return Number.isFinite(value) ? value : 0
      })
    }
    const col = itemOv[index]?.color
    if (col) item.itemStyle = { color: col }
    return item
  })
  const { barColor } = readBarUi(ui)
  const singleSeriesColor = data.length <= 1 && barColor ? barColor : ''
  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params) => {
        const values = Array.isArray(params?.data?.value) ? params.data.value : []
        const lines = [params?.data?.name ? `${params.data.name}` : '']
        indicator.forEach((item, index) => {
          lines.push(`${item.name}: ${values[index] ?? '-'}`)
        })
        return lines.filter(Boolean).join('<br/>')
      }
    },
    legend: {
      type: 'scroll',
      bottom: 0,
      textStyle: { fontSize: 11 },
      itemWidth: 10,
      itemHeight: 10
    },
    radar: {
      indicator,
      radius: '58%',
      center: ['50%', '46%'],
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisName: { fontSize: 11, color: '#64748b' }
    },
    series: [
      {
        type: 'radar',
        data,
        areaStyle: { opacity: 0.12 },
        lineStyle: singleSeriesColor ? { color: singleSeriesColor, width: 2 } : { width: 2 },
        itemStyle: singleSeriesColor ? { color: singleSeriesColor } : undefined,
        symbolSize: 4
      }
    ]
  }
}

function buildScatterFromRows(rows, snap, ui, itemOv = {}) {
  const sourceRows = Array.isArray(rows) ? rows : []
  if (!sourceRows.length) return null
  const fm = snap?.fieldMapping && typeof snap.fieldMapping === 'object' ? snap.fieldMapping : {}
  const xCandidates = normalizeKeyCandidates(
    fm.xMetricKey,
    fm.xMetric,
    fm.xField,
    fm.metricX,
    snap?.encode?.x
  )
  const yCandidates = normalizeKeyCandidates(
    fm.yMetricKey,
    fm.yMetric,
    fm.yField,
    fm.metricY,
    snap?.encode?.y,
    snap?.encode?.value
  )
  const xKey = firstNumericObjectKey(sourceRows, xCandidates)
  const yKey = firstNumericObjectKey(sourceRows, yCandidates, [xKey])
  if (!xKey || !yKey || xKey === yKey) return null

  const groupKey = firstTextObjectKey(sourceRows, normalizeKeyCandidates(
    fm.groupKey,
    fm.group,
    fm.dimension,
    fm.dimensionKey,
    snap?.encode?.itemName,
    snap?.encode?.group
  ), [xKey, yKey])
  const sizeKey = firstNumericObjectKey(sourceRows, normalizeKeyCandidates(
    fm.sizeMetricKey,
    fm.sizeMetric,
    fm.sizeKey
  ), [xKey, yKey])
  const { barColor } = readBarUi(ui)
  const points = sourceRows.map((row, index) => {
    const x = Number(row?.[xKey])
    const y = Number(row?.[yKey])
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null
    const size = sizeKey ? Number(row?.[sizeKey]) : Number.NaN
    const value = Number.isFinite(size) ? [x, y, size] : [x, y]
    const item = {
      name: groupKey ? String(row?.[groupKey] ?? '') : String(row?.name ?? row?.label ?? index + 1),
      value
    }
    const col = itemOv[index]?.color
    if (col) item.itemStyle = { color: col }
    return item
  }).filter(Boolean)
  if (!points.length) return null

  const sizeValues = points
    .map((point) => Number(point.value?.[2]))
    .filter((value) => Number.isFinite(value))
  const minSize = sizeValues.length ? Math.min(...sizeValues) : 0
  const maxSize = sizeValues.length ? Math.max(...sizeValues) : 0
  const resolveSymbolSize = (value) => {
    const size = Number(Array.isArray(value) ? value[2] : NaN)
    if (!Number.isFinite(size) || maxSize === minSize) return 11
    return Math.max(8, Math.min(28, 8 + ((size - minSize) / (maxSize - minSize)) * 20))
  }

  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params) => {
        const arr = Array.isArray(params?.data?.value) ? params.data.value : []
        const rows = [
          params?.data?.name ? `${params.data.name}` : '',
          `${fm.xMetric || xKey}: ${arr[0] ?? '-'}`,
          `${fm.yMetric || yKey}: ${arr[1] ?? '-'}`
        ]
        if (sizeKey && arr.length > 2) rows.push(`${fm.sizeMetric || sizeKey}: ${arr[2]}`)
        return rows.filter(Boolean).join('<br/>')
      }
    },
    grid: { left: 54, right: 18, top: 18, bottom: 44, containLabel: true },
    xAxis: {
      type: 'value',
      name: fm.xMetric || xKey,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: fm.yMetric || yKey,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    series: [
      {
        type: 'scatter',
        data: points,
        symbolSize: sizeKey ? resolveSymbolSize : 11,
        itemStyle: barColor ? { color: barColor, opacity: 0.82 } : { opacity: 0.82 },
        large: points.length > 120,
        largeThreshold: 120
      }
    ]
  }
}

function sourceValueAt(row, index, name) {
  if (Array.isArray(row)) return row[index]
  return row?.[name]
}

function firstNumericDatasetIndex(dimensions, source, candidates = [], exclude = []) {
  const excluded = new Set(exclude)
  const numeric = dimensions
    .map((name, index) => ({ name, index }))
    .filter(({ index, name }) =>
      !excluded.has(index) &&
      source.some((row) => Number.isFinite(Number(sourceValueAt(row, index, name))))
    )
  if (!numeric.length) return -1
  const candidateSet = new Set(candidates.map((item) => String(item || '').trim()).filter(Boolean))
  return (numeric.find(({ name }) => candidateSet.has(name)) || numeric[0]).index
}

function buildScatterFromEncodeDataset(snap, dimensions, source, ui) {
  const enc = snap.encode || {}
  const fm = snap?.fieldMapping && typeof snap.fieldMapping === 'object' ? snap.fieldMapping : {}
  const xIndex = firstNumericDatasetIndex(dimensions, source, normalizeKeyCandidates(
    fm.xMetricKey,
    fm.xMetric,
    fm.xField,
    enc.x
  ))
  const yIndex = firstNumericDatasetIndex(dimensions, source, normalizeKeyCandidates(
    fm.yMetricKey,
    fm.yMetric,
    fm.yField,
    enc.y,
    enc.value
  ), [xIndex])
  if (xIndex < 0 || yIndex < 0 || xIndex === yIndex) return null
  const xName = dimensions[xIndex]
  const yName = dimensions[yIndex]
  const itemName = String(enc.itemName ?? enc.name ?? dimensions[0] ?? '')
  const itemIndex = dimensions.indexOf(itemName)
  const data = source.map((row, index) => {
    const x = Number(sourceValueAt(row, xIndex, xName))
    const y = Number(sourceValueAt(row, yIndex, yName))
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null
    return {
      name: itemIndex >= 0 ? String(sourceValueAt(row, itemIndex, itemName) ?? '') : String(index + 1),
      value: [x, y]
    }
  }).filter(Boolean)
  if (!data.length) return null
  const { barColor } = readBarUi(ui)
  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params) => {
        const arr = Array.isArray(params?.data?.value) ? params.data.value : []
        return [
          params?.data?.name ? `${params.data.name}` : '',
          `${fm.xMetric || xName}: ${arr[0] ?? '-'}`,
          `${fm.yMetric || yName}: ${arr[1] ?? '-'}`
        ].filter(Boolean).join('<br/>')
      }
    },
    grid: { left: 54, right: 18, top: 18, bottom: 44, containLabel: true },
    xAxis: {
      type: 'value',
      name: fm.xMetric || xName,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: fm.yMetric || yName,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    series: [
      {
        type: 'scatter',
        data,
        symbolSize: 11,
        itemStyle: barColor ? { color: barColor, opacity: 0.82 } : { opacity: 0.82 },
        large: data.length > 120,
        largeThreshold: 120
      }
    ]
  }
}

function buildScatterFromPoints(points, ui, itemOv = {}) {
  const xAxisData = points.map((p) => p.name)
  const seriesData = points.map((p, i) => {
    const v = Number(p.value ?? 0)
    const col = itemOv[i]?.color
    if (col) return { value: v, itemStyle: { color: col } }
    return v
  })
  const valueAxisRange = buildValueAxisRange(seriesData.map((d) => (typeof d === 'object' ? d.value : d)))
  const { barColor } = readBarUi(ui)
  const useZoom = xAxisData.length > 14
  const endPct = xAxisData.length ? Math.min(100, Math.ceil((14 / xAxisData.length) * 100)) : 100

  const option = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, confine: true },
    grid: {
      left: 48,
      right: 12,
      top: 14,
      bottom: useZoom ? 80 : Math.min(110, 32 + (xAxisData.length > 10 ? 52 : 38))
    },
    xAxis: {
      type: 'category',
      data: xAxisData,
      axisLabel: {
        interval: 0,
        rotate: xAxisData.length > 8 ? 38 : 20,
        hideOverlap: true,
        fontSize: 11
      }
    },
    yAxis: {
      type: 'value',
      min: valueAxisRange.min,
      max: valueAxisRange.max,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    series: [
      {
        type: 'scatter',
        data: seriesData,
        symbolSize: 10,
        itemStyle: barColor ? { color: barColor, opacity: 0.85 } : { opacity: 0.85 },
        large: seriesData.length > 80,
        largeThreshold: 80
      }
    ]
  }

  if (useZoom) {
    option.dataZoom = normalizeInteractiveDataZoom([
      { type: 'slider', show: true, xAxisIndex: 0, bottom: 8, height: 22, start: 0, end: endPct },
      { type: 'inside', xAxisIndex: 0, start: 0, end: endPct }
    ])
  }
  return option
}

function shortNumberText(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return String(value ?? '-')
  if (Math.abs(n) >= 100000000) return `${(n / 100000000).toFixed(1)}亿`
  if (Math.abs(n) >= 10000) return `${(n / 10000).toFixed(1)}万`
  return `${n}`
}

function buildMapFromPoints(points, snap, ui, itemOv = {}) {
  const mapData = normalizeMapPoints((Array.isArray(points) ? points : []).map((point, index) => {
    const item = { ...point }
    const col = itemOv[index]?.color
    if (col) item.itemStyle = { ...(item.itemStyle || {}), areaColor: col, color: col }
    return item
  }))
  if (!mapData.length) return null

  ensureInsightChinaRegionMapRegistered()
  const values = mapData.map(item => Number(item.value)).filter(Number.isFinite)
  const maxValue = values.length ? Math.max(...values, 0) : 1
  const minValue = values.length ? Math.min(...values, 0) : 0
  const fm = snap?.fieldMapping && typeof snap.fieldMapping === 'object' ? snap.fieldMapping : {}
  const metricLabel = String(fm.metric || fm.metricLabel || '指标值')
  const regionLabelMap = buildMapRegionLabelMap(mapData)
  const { barColor } = readBarUi(ui)

  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params) => {
        const name = String(params?.name || params?.data?.name || '')
        const sourceName = String(params?.data?.sourceName || '').trim()
        const regionName = String(params?.data?.regionName || '').trim()
        const value = params?.data?.value ?? params?.value
        return [
          name,
          regionName && regionName !== name ? `所属大区: ${regionName}` : '',
          sourceName && sourceName !== name && sourceName !== regionName ? `来源: ${sourceName}` : '',
          `${metricLabel}: ${shortNumberText(value)}`
        ].filter(Boolean).join('<br/>')
      }
    },
    visualMap: {
      min: Math.min(0, minValue),
      max: maxValue > minValue ? maxValue : Math.max(maxValue, 1),
      left: 8,
      bottom: 8,
      itemWidth: 10,
      itemHeight: 80,
      text: ['高', '低'],
      calculable: true,
      realtime: true,
      inRange: {
        color: barColor
          ? ['#eff6ff', barColor]
          : ['#eff6ff', '#93c5fd', '#2563eb']
      },
      textStyle: { color: '#64748b', fontSize: 11 }
    },
    series: [
      {
        name: metricLabel,
        type: 'map',
        map: INSIGHT_CHINA_PROVINCE_MAP_NAME,
        mapType: INSIGHT_CHINA_PROVINCE_MAP_NAME,
        roam: false,
        selectedMode: false,
        showLegendSymbol: false,
        data: mapData,
        layoutCenter: ['50%', '50%'],
        layoutSize: '106%',
        label: {
          show: true,
          color: '#0f172a',
          fontSize: 12,
          fontWeight: 700,
          lineHeight: 17,
          formatter: (params) => {
            const meta = regionLabelMap.get(String(params?.name || '').trim())
            if (!meta) return ''
            return `${meta.regionName}\n${metricLabel} ${shortNumberText(meta.value)}`
          }
        },
        itemStyle: {
          areaColor: '#f8fafc',
          borderColor: 'rgba(148, 163, 184, 0.46)',
          borderWidth: 0.8
        },
        emphasis: {
          label: { show: true, color: '#0f172a', fontWeight: 800, fontSize: 12 },
          itemStyle: { areaColor: '#f59e0b', borderColor: '#92400e', borderWidth: 1.1 }
        }
      }
    ]
  }
}

function buildMapFromEncodeDataset(snap, dimensions, source, ui) {
  const rows = source.map((row) => {
    const item = {}
    dimensions.forEach((name, index) => {
      item[name] = sourceValueAt(row, index, name)
    })
    return item
  })
  const fm = snap?.fieldMapping && typeof snap.fieldMapping === 'object' ? snap.fieldMapping : {}
  const enc = snap?.encode && typeof snap.encode === 'object' ? snap.encode : {}
  const geoKey = firstTextObjectKey(rows, normalizeKeyCandidates(
    fm.geoKey,
    fm.regionKey,
    fm.dimensionKey,
    fm.dimension,
    enc.itemName,
    enc.x,
    'name',
    'dim_name',
    'dimension',
    'region',
    'province',
    'city'
  ))
  const metricKey = firstNumericObjectKey(rows, normalizeKeyCandidates(
    fm.metricKey,
    fm.metric,
    enc.value,
    enc.y,
    'value',
    'metric_value'
  ), [geoKey])
  if (!geoKey || !metricKey) return null
  const points = rows.map(row => ({
    name: row?.[geoKey],
    value: row?.[metricKey]
  }))
  return buildMapFromPoints(points, snap, ui)
}

function sanitizeMapChartOption(option) {
  if (!option || typeof option !== 'object') return option
  ensureInsightChinaRegionMapRegistered()
  const out = { ...option }
  delete out.grid
  delete out.xAxis
  delete out.yAxis
  delete out.dataZoom
  out.tooltip = mergeObjects(out.tooltip, { trigger: 'item', confine: true })

  const sourceSeries = Array.isArray(out.series) ? out.series : []
  const mapSeriesIndex = sourceSeries.findIndex(series => String(series?.type || '').toLowerCase() === 'map')
  out.series = sourceSeries.map((series, index) => {
    if (!series || typeof series !== 'object') return series
    if (index !== 0 && index !== mapSeriesIndex && String(series.type || '').toLowerCase() !== 'map') return series
    const data = normalizeMapPoints(series.data)
    const regionLabelMap = buildMapRegionLabelMap(data)
    const metricLabel = String(series.name || '指标值')
    return mergeObjects(series, {
      type: 'map',
      map: INSIGHT_CHINA_PROVINCE_MAP_NAME,
      mapType: INSIGHT_CHINA_PROVINCE_MAP_NAME,
      showLegendSymbol: false,
      data,
      tooltip: { trigger: 'item' },
      label: {
        show: true,
        color: '#0f172a',
        fontSize: 12,
        fontWeight: 700,
        lineHeight: 17,
        formatter: (params) => {
          const meta = regionLabelMap.get(String(params?.name || '').trim())
          if (!meta) return ''
          return `${meta.regionName}\n${metricLabel} ${shortNumberText(meta.value)}`
        }
      }
    })
  })
  if (!out.series.length) {
    out.series = [{
      type: 'map',
      map: INSIGHT_CHINA_PROVINCE_MAP_NAME,
      mapType: INSIGHT_CHINA_PROVINCE_MAP_NAME,
      showLegendSymbol: false,
      data: []
    }]
  }
  if (out.geo && typeof out.geo === 'object' && !Array.isArray(out.geo)) {
    out.geo = mergeObjects(out.geo, { map: INSIGHT_CHINA_PROVINCE_MAP_NAME })
  }
  if (out.visualMap && typeof out.visualMap === 'object' && !Array.isArray(out.visualMap)) {
    out.visualMap = mergeObjects({ seriesIndex: 0 }, out.visualMap)
  }
  return out
}

function buildRadarFromEncodeDataset(snap, dimensions, source, ui) {
  const rows = source.map((row) => {
    const item = {}
    dimensions.forEach((name, index) => {
      item[name] = sourceValueAt(row, index, name)
    })
    return item
  })
  const multiMetricRadar = buildRadarFromMetricRows(rows, snap, ui)
  if (multiMetricRadar) return multiMetricRadar

  const enc = snap.encode || {}
  const nameKey = String(enc.x ?? enc.itemName ?? dimensions[0] ?? 'name')
  const valKey = String(enc.y ?? enc.value ?? dimensions[1] ?? 'value')
  const ni = dimensions.indexOf(nameKey)
  const vi = dimensions.indexOf(valKey)
  const points = source.map((row) => ({
    name: String(ni >= 0 ? row[nameKey] ?? row[ni] : row[dimensions[0]] ?? ''),
    value: Number(vi >= 0 ? row[valKey] ?? row[vi] : row[dimensions[1]] ?? 0)
  }))
  return points.length >= 3 ? buildRadarFromPoints(points, ui) : null
}

function buildOptionFromEncodeDataset(snap, chartType, ui) {
  const { dimensions, source } = buildDatasetFromSnapshot(snap)
  const enc = snap.encode || {}
  const { barW, barColor } = readBarUi(ui)
  const n = source.length
  const manyPie = n > 10

  if (chartType === 'radar') {
    const radarOption = buildRadarFromEncodeDataset(snap, dimensions, source, ui)
    if (radarOption) return radarOption
  }

  if (chartType === 'scatter') {
    const scatterOption = buildScatterFromEncodeDataset(snap, dimensions, source, ui)
    if (scatterOption) return scatterOption
  }

  if (chartType === 'map') {
    const mapOption = buildMapFromEncodeDataset(snap, dimensions, source, ui)
    if (mapOption) return mapOption
  }

  const effectiveChartType = ['radar', 'scatter', 'map'].includes(chartType) ? 'bar' : chartType

  if (effectiveChartType === 'pie') {
    const itemName = String(enc.itemName ?? 'name')
    const valDim = String(enc.value ?? 'value')
    return {
      tooltip: { trigger: 'item', confine: true },
      legend: manyPie
        ? {
            type: 'scroll',
            orient: 'vertical',
            right: 4,
            top: '6%',
            bottom: '6%',
            width: 140,
            textStyle: { fontSize: 11 },
            itemWidth: 10,
            itemHeight: 10
          }
        : {
            type: 'scroll',
            bottom: 2,
            textStyle: { fontSize: 11 },
            itemWidth: 10,
            itemHeight: 10
          },
      dataset: [{ dimensions, source }],
      series: [
        {
          type: 'pie',
          datasetIndex: 0,
          encode: {
            itemName: dimensions.indexOf(itemName) >= 0 ? itemName : dimensions[0],
            value: dimensions.indexOf(valDim) >= 0 ? valDim : dimensions[1]
          },
          radius: manyPie ? ['20%', '42%'] : ['34%', '58%'],
          center: manyPie ? ['32%', '50%'] : ['50%', '48%'],
          avoidLabelOverlap: true,
          minShowLabelAngle: 6,
          label: manyPie
            ? { show: false }
            : {
                formatter: '{b}\n{d}%',
                fontSize: 11,
                overflow: 'break',
                width: 96
              },
          labelLine: { show: !manyPie, length: 10, length2: 6 },
          emphasis: {
            label: { show: true, fontSize: 12, formatter: '{b}\n{c} ({d}%)' }
          }
        }
      ]
    }
  }

  const xKey = String(enc.x ?? 'name')
  const yKey = String(enc.y ?? 'value')
  const xi = dimensions.indexOf(xKey)
  const yi = dimensions.indexOf(yKey)
  const numericValues = []
  for (const row of source) {
    const v = yi >= 0 ? Number(row[yi]) : NaN
    if (Number.isFinite(v)) numericValues.push(v)
  }
  const valueAxisRange = buildValueAxisRange(numericValues)

  const useZoom = n > 14
  const endPct = n ? Math.min(100, Math.ceil((14 / n) * 100)) : 100

  const series0 = {
    type: effectiveChartType,
    datasetIndex: 0,
    encode: {
      x: xi >= 0 ? xKey : dimensions[0],
      y: yi >= 0 ? yKey : dimensions[1]
    },
    smooth: effectiveChartType === 'line',
    barMaxWidth: effectiveChartType === 'bar' ? barW : 32,
    large: n > 80,
    largeThreshold: 80
  }

  if (effectiveChartType === 'bar' && barColor) {
    series0.itemStyle = { color: barColor, borderRadius: [4, 4, 0, 0] }
  } else if (effectiveChartType === 'bar') {
    series0.itemStyle = { borderRadius: [4, 4, 0, 0] }
  }

  if (effectiveChartType === 'line' && barColor) {
    series0.lineStyle = { color: barColor, width: 2 }
  }

  if (effectiveChartType === 'scatter') {
    series0.symbolSize = 10
    series0.itemStyle = barColor ? { color: barColor, opacity: 0.85 } : { opacity: 0.85 }
  }

  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: effectiveChartType === 'scatter' ? 'cross' : 'shadow' },
      confine: true
    },
    grid: {
      left: 48,
      right: 12,
      top: 14,
      bottom: useZoom ? 80 : Math.min(110, 32 + (n > 10 ? 52 : 38))
    },
    dataset: [{ dimensions, source }],
    xAxis: {
      type: 'category',
      axisLabel: {
        interval: 0,
        rotate: n > 8 ? 38 : 20,
        hideOverlap: true,
        fontSize: 11,
        formatter: (value) => {
          const text = String(value ?? '')
          return text.length > 14 ? `${text.slice(0, 14)}…` : text
        }
      }
    },
    yAxis: {
      type: 'value',
      min: valueAxisRange.min,
      max: valueAxisRange.max,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    series: [series0]
  }

  if (useZoom) {
    option.dataZoom = normalizeInteractiveDataZoom([
      {
        type: 'slider',
        show: true,
        xAxisIndex: 0,
        bottom: 8,
        height: 22,
        start: 0,
        end: endPct
      },
      { type: 'inside', xAxisIndex: 0, start: 0, end: endPct }
    ])
  }

  return option
}

function buildOptionLegacyFromPoints(snap, chartType, ui, itemOv) {
  const data = Array.isArray(snap.data) ? snap.data : []
  const basePoints = data.map(normalizeChartItem)
  const points = basePoints

  if (chartType === 'radar') {
    const multiMetricRadar = buildRadarFromMetricRows(objectRowsFromSnapshot(snap), snap, ui, itemOv)
    if (multiMetricRadar) return multiMetricRadar
    if (points.length >= 3) {
      return buildRadarFromPoints(points, ui)
    }
  }

  if (chartType === 'scatter') {
    const scatterOption = buildScatterFromRows(objectRowsFromSnapshot(snap), snap, ui, itemOv)
    if (scatterOption) return scatterOption
  }

  if (chartType === 'map') {
    const mapOption = buildMapFromPoints(points, snap, ui, itemOv)
    if (mapOption) return mapOption
  }

  const effectiveChartType = ['radar', 'scatter', 'map'].includes(chartType) ? 'bar' : chartType

  if (effectiveChartType === 'pie') {
    const many = points.length > 10
    const pieData = points.map((p, i) => {
      const piece = { name: p.name, value: p.value }
      if (itemOv[i]?.color) piece.itemStyle = { color: itemOv[i].color }
      return piece
    })
    return {
      tooltip: { trigger: 'item', confine: true },
      legend: many
        ? {
            type: 'scroll',
            orient: 'vertical',
            right: 4,
            top: '6%',
            bottom: '6%',
            width: 140,
            textStyle: { fontSize: 11 },
            itemWidth: 10,
            itemHeight: 10
          }
        : {
            type: 'scroll',
            bottom: 2,
            textStyle: { fontSize: 11 },
            itemWidth: 10,
            itemHeight: 10
          },
      series: [
        {
          type: 'pie',
          radius: many ? ['20%', '42%'] : ['34%', '58%'],
          center: many ? ['32%', '50%'] : ['50%', '48%'],
          avoidLabelOverlap: true,
          minShowLabelAngle: 6,
          minAngle: 2,
          label: many
            ? { show: false }
            : {
                formatter: '{b}\n{d}%',
                fontSize: 11,
                overflow: 'break',
                width: 96
              },
          labelLine: { show: !many, length: 10, length2: 6 },
          emphasis: {
            label: { show: true, fontSize: 12, formatter: '{b}\n{c} ({d}%)' }
          },
          data: pieData
        }
      ]
    }
  }

  const xAxisData = points.map((p) => p.name)
  const seriesData = points.map((p) => Number(p.value ?? 0))
  const valueAxisRange = buildValueAxisRange(seriesData)
  const useZoom = xAxisData.length > 14
  const endPct = xAxisData.length ? Math.min(100, Math.ceil((14 / xAxisData.length) * 100)) : 100

  const { barW, barColor } = readBarUi(ui)
  const lineGlobal = effectiveChartType === 'line' && barColor ? barColor : ''

  const hasItemOverrides = Object.keys(itemOv).length > 0

  let mappedSeriesData = seriesData
  if (effectiveChartType === 'bar' && hasItemOverrides) {
    mappedSeriesData = seriesData.map((v, i) => {
      const per = itemOv[i]?.color
      const col = per || barColor || ''
      const style = { borderRadius: [4, 4, 0, 0] }
      if (col) style.color = col
      return { value: v, itemStyle: style }
    })
  } else if (effectiveChartType === 'line' && hasItemOverrides) {
    mappedSeriesData = seriesData.map((v, i) => {
      const col = itemOv[i]?.color
      if (col) return { value: v, itemStyle: { color: col } }
      return v
    })
  }

  const barSeriesItemStyle =
    effectiveChartType === 'bar' && !hasItemOverrides && barColor
      ? { color: barColor, borderRadius: [4, 4, 0, 0] }
      : effectiveChartType === 'bar' && !hasItemOverrides
        ? { borderRadius: [4, 4, 0, 0] }
        : undefined

  const option = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true },
    grid: {
      left: 48,
      right: 12,
      top: 14,
      bottom: useZoom ? 80 : Math.min(110, 32 + (xAxisData.length > 10 ? 52 : 38))
    },
    xAxis: {
      type: 'category',
      data: xAxisData,
      axisLabel: {
        interval: 0,
        rotate: xAxisData.length > 8 ? 38 : 20,
        hideOverlap: true,
        fontSize: 11,
        formatter: (value) => {
          const text = String(value ?? '')
          return text.length > 14 ? `${text.slice(0, 14)}…` : text
        }
      }
    },
    yAxis: {
      type: 'value',
      min: valueAxisRange.min,
      max: valueAxisRange.max,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    series: [
      {
        type: effectiveChartType,
        smooth: effectiveChartType === 'line',
        data: mappedSeriesData,
        barMaxWidth: effectiveChartType === 'bar' ? barW : 32,
        itemStyle: barSeriesItemStyle,
        lineStyle: effectiveChartType === 'line' && lineGlobal ? { color: lineGlobal, width: 2 } : undefined,
        large: seriesData.length > 80,
        largeThreshold: 80
      }
    ]
  }

  if (useZoom) {
    option.dataZoom = normalizeInteractiveDataZoom([
      {
        type: 'slider',
        show: true,
        xAxisIndex: 0,
        bottom: 8,
        height: 22,
        start: 0,
        end: endPct
      },
      { type: 'inside', xAxisIndex: 0, start: 0, end: endPct }
    ])
  }

  return option
}

function applyAxisPatch(axis, patchFn) {
  if (!axis) return axis
  if (Array.isArray(axis)) return axis.map((a) => patchFn(a || {}))
  return patchFn(axis || {})
}

/**
 * 将 layout_json.items[].chartStyle 应用到 ECharts option（通用 + 按类型）
 * @param {object} option
 * @param {object} style 已合并默认值的 chartStyle
 * @param {string} chartType
 */
export function applyChartUiStyle(option, style, chartType) {
  if (!option || !style || typeof style !== 'object') return option
  const out = { ...option }
  const t = String(chartType || '').toLowerCase()

  if (style.legendShow === false) {
    out.legend = mergeObjects(out.legend, { show: false })
  } else if (style.legendShow !== false && style.legendPosition) {
    const pos = String(style.legendPosition)
    const legendPatch = { show: true, textStyle: { fontSize: Number(style.legendFontSize) || 11 } }
    if (pos === 'bottom') Object.assign(legendPatch, { top: undefined, bottom: 0, left: 'center' })
    else if (pos === 'left') Object.assign(legendPatch, { orient: 'vertical', left: 0, top: 'middle' })
    else if (pos === 'right') Object.assign(legendPatch, { orient: 'vertical', right: 0, top: 'middle' })
    else Object.assign(legendPatch, { top: 0, left: 'center' })
    out.legend = mergeObjects(out.legend, legendPatch)
  }

  if (style.tooltipShow === false) {
    out.tooltip = mergeObjects(out.tooltip, { show: false })
  }

  if (['bar', 'line', 'radar', 'scatter'].includes(t) && style.axisXRotate != null) {
    const deg = Math.max(-90, Math.min(90, Math.round(Number(style.axisXRotate) || 0)))
    out.xAxis = applyAxisPatch(out.xAxis, (ax) =>
      mergeObjects(ax, {
        axisLabel: mergeObjects(ax.axisLabel, { rotate: deg, fontSize: Number(style.legendFontSize) || ax.axisLabel?.fontSize || 11 })
      })
    )
  }

  if (style.axisYShow === false) {
    out.yAxis = applyAxisPatch(out.yAxis, (ax) => mergeObjects(ax, { show: false }))
  }

  if (style.gridLineShow === false) {
    out.xAxis = applyAxisPatch(out.xAxis, (ax) => mergeObjects(ax, { splitLine: { show: false } }))
    out.yAxis = applyAxisPatch(out.yAxis, (ax) => mergeObjects(ax, { splitLine: { show: false } }))
  }

  if (style.dataLabelShow && Array.isArray(out.series)) {
    out.series = out.series.map((s) =>
      mergeObjects(s, {
        label: mergeObjects(s.label, { show: true, position: style.dataLabelPosition || 'top' })
      })
    )
  }

  const primary = String(style.primaryColor || '').trim()
  if (primary && Array.isArray(out.series) && out.series.length) {
    out.color = out.color || []
    if (!out.color.length) out.color = [primary]
  }

  if (t === 'line' && Array.isArray(out.series)) {
    out.series = out.series.map((s) => {
      const patch = {}
      if (style.lineSmooth) patch.smooth = true
      const lw = Number(style.lineWidth)
      if (Number.isFinite(lw) && lw > 0) patch.lineStyle = mergeObjects(s.lineStyle, { width: lw })
      const sym = Number(style.lineSymbolSize)
      if (Number.isFinite(sym) && sym > 0) patch.symbolSize = sym
      return mergeObjects(s, patch)
    })
  }

  if (t === 'pie' && Array.isArray(out.series)) {
    const inner = Math.max(0, Math.min(80, Number(style.pieInnerRadius) || 0))
    const pad = Math.max(0, Math.min(20, Number(style.piePadAngle) || 0))
    out.series = out.series.map((s) =>
      mergeObjects(s, {
        padAngle: pad / 10,
        radius: inner > 0 ? [`${inner}%`, '70%'] : s.radius
      })
    )
  }

  if (t === 'bar' && Array.isArray(out.series)) {
    const r = Math.max(0, Math.min(16, Number(style.barRadius) || 0))
    if (r > 0) {
      out.series = out.series.map((s) =>
        mergeObjects(s, { itemStyle: mergeObjects(s.itemStyle, { borderRadius: [r, r, 0, 0] }) })
      )
    }
  }

  if (t === 'scatter' && Array.isArray(out.series)) {
    const sym = Number(style.scatterSymbolSize)
    const op = Number(style.scatterOpacity)
    out.series = out.series.map((s) => {
      if (String(s.type || '').toLowerCase() !== 'scatter') return s
      const patch = {}
      if (Number.isFinite(sym) && sym > 0) patch.symbolSize = sym
      if (Number.isFinite(op) && op >= 0 && op <= 1) {
        patch.itemStyle = mergeObjects(s.itemStyle, { opacity: op })
      }
      return mergeObjects(s, patch)
    })
  }

  if (t === 'radar') {
    const areaOp = Number(style.radarAreaOpacity)
    const lw = Number(style.radarLineWidth)
    const sym = Number(style.radarSymbolSize)
    if (Array.isArray(out.series)) {
      out.series = out.series.map((s) => {
        if (String(s.type || '').toLowerCase() !== 'radar') return s
        const patch = {}
        if (Number.isFinite(areaOp) && areaOp >= 0 && areaOp <= 1) {
          patch.areaStyle = mergeObjects(s.areaStyle, { opacity: areaOp })
        }
        if (Number.isFinite(lw) && lw > 0) {
          patch.lineStyle = mergeObjects(s.lineStyle, { width: lw })
        }
        if (Number.isFinite(sym) && sym > 0) patch.symbolSize = sym
        return mergeObjects(s, patch)
      })
    }
    if (Number.isFinite(areaOp) && out.radar) {
      out.radar = mergeObjects(out.radar, {
        splitArea: mergeObjects(out.radar.splitArea, { show: areaOp > 0 })
      })
    }
  }

  if (t === 'map') {
    const area = String(style.mapAreaColor || '').trim()
    const border = String(style.mapBorderColor || '').trim()
    const emph = String(style.mapEmphasisColor || '').trim()
    if (area || border) {
      const geoPatch = (g) =>
        mergeObjects(g, {
          itemStyle: mergeObjects(g.itemStyle, {
            ...(area ? { areaColor: area } : {}),
            ...(border ? { borderColor: border } : {})
          })
        })
      out.geo = applyAxisPatch(out.geo, geoPatch)
      if (Array.isArray(out.series)) {
        out.series = out.series.map((s) => {
          if (String(s?.type || '').toLowerCase() !== 'map') return s
          return mergeObjects(s, {
            itemStyle: mergeObjects(s.itemStyle, {
              ...(area ? { areaColor: area } : {}),
              ...(border ? { borderColor: border } : {})
            })
          })
        })
      }
    }
    if (emph && Array.isArray(out.series)) {
      out.series = out.series.map((s) => {
        if (String(s.type || '').toLowerCase() !== 'map') return s
        return mergeObjects(s, {
          emphasis: mergeObjects(s.emphasis, {
            itemStyle: mergeObjects(s.emphasis?.itemStyle, { areaColor: emph })
          })
        })
      })
    }
  }

  return out
}

/**
 * @param row 来自 charts-batch 的单行：chartType, chartSnapshot, generatedSql, queryTableName, id
 * @param ui 看板网格项上的展示覆盖：barColor、barMaxWidth、seriesItemStyles（按下标逐项 itemStyle）
 */
export function buildOptionFromHistoryRow(row, ui = {}) {
  const snap = parseSnapshot(row?.chartSnapshot)
  const chartType = normalizeChartType(row?.chartType || snap.chartType)
  if (chartType === 'table' || chartType === 'metric') {
    return null
  }
  const itemOv = normalizeSeriesItemStyles(ui.seriesItemStyles)
  const hasItemOverrides = Object.keys(itemOv).length > 0
  const template =
    snap.optionTemplate && typeof snap.optionTemplate === 'object' ? snap.optionTemplate : null

  if (isForecastSnapshot(snap)) {
    const built = buildForecastOptionFromSnapshot(snap)
    return template
      ? applyDynamicInteractionDefaults(applyOptionTemplateDefaults(built, template), template, { chartType })
      : applyDynamicInteractionDefaults(built, null, { chartType })
  }

  let built
  if (snapHasEncode(snap) && !hasItemOverrides) {
    built = buildOptionFromEncodeDataset(snap, chartType, ui)
  } else {
    built = buildOptionLegacyFromPoints(snap, chartType, ui, itemOv)
  }

  const withTemplate = template ? applyOptionTemplateDefaults(built, template) : built
  let finalOption = applyDynamicInteractionDefaults(withTemplate, template, { chartType })
  const uiStyle = ui?.chartStyle && typeof ui.chartStyle === 'object' ? ui.chartStyle : {}
  if (Object.keys(uiStyle).length) {
    finalOption = applyChartUiStyle(finalOption, uiStyle, chartType)
  }
  if (Array.isArray(finalOption?.dataZoom) && finalOption.dataZoom.length) {
    finalOption.dataZoom = normalizeInteractiveDataZoom(finalOption.dataZoom)
  }
  return finalOption
}
