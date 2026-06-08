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
  if (type === 'line' || type === 'pie' || type === 'bar' || type === 'table') return type
  if (type === 'doughnut' || type === 'donut') return 'pie'
  if (type.includes('饼')) return 'pie'
  if (type.includes('环')) return 'pie'
  if (type.includes('折')) return 'line'
  if (type.includes('柱')) return 'bar'
  if (type.includes('表')) return 'table'
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
  return out
}

function readBarUi(ui) {
  const barMaxW = Number(ui.barMaxWidth)
  const barW =
    Number.isFinite(barMaxW) && barMaxW >= 4 && barMaxW <= 160 ? Math.round(barMaxW) : 32
  const barColor = typeof ui.barColor === 'string' && ui.barColor.trim() ? ui.barColor.trim() : ''
  return { barW, barColor }
}

function buildOptionFromEncodeDataset(snap, chartType, ui) {
  const { dimensions, source } = buildDatasetFromSnapshot(snap)
  const enc = snap.encode || {}
  const { barW, barColor } = readBarUi(ui)
  const n = source.length
  const manyPie = n > 10

  if (chartType === 'pie') {
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
    type: chartType,
    datasetIndex: 0,
    encode: {
      x: xi >= 0 ? xKey : dimensions[0],
      y: yi >= 0 ? yKey : dimensions[1]
    },
    smooth: chartType === 'line',
    barMaxWidth: chartType === 'bar' ? barW : 32,
    large: n > 80,
    largeThreshold: 80
  }

  if (chartType === 'bar' && barColor) {
    series0.itemStyle = { color: barColor, borderRadius: [4, 4, 0, 0] }
  } else if (chartType === 'bar') {
    series0.itemStyle = { borderRadius: [4, 4, 0, 0] }
  }

  if (chartType === 'line' && barColor) {
    series0.lineStyle = { color: barColor, width: 2 }
  }

  const option = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true },
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
  const points = data.map(normalizeChartItem)

  if (chartType === 'pie') {
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
  const lineGlobal = chartType === 'line' && barColor ? barColor : ''

  const hasItemOverrides = Object.keys(itemOv).length > 0

  let mappedSeriesData = seriesData
  if (chartType === 'bar' && hasItemOverrides) {
    mappedSeriesData = seriesData.map((v, i) => {
      const per = itemOv[i]?.color
      const col = per || barColor || ''
      const style = { borderRadius: [4, 4, 0, 0] }
      if (col) style.color = col
      return { value: v, itemStyle: style }
    })
  } else if (chartType === 'line' && hasItemOverrides) {
    mappedSeriesData = seriesData.map((v, i) => {
      const col = itemOv[i]?.color
      if (col) return { value: v, itemStyle: { color: col } }
      return v
    })
  }

  const barSeriesItemStyle =
    chartType === 'bar' && !hasItemOverrides && barColor
      ? { color: barColor, borderRadius: [4, 4, 0, 0] }
      : chartType === 'bar' && !hasItemOverrides
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
        type: chartType,
        smooth: chartType === 'line',
        data: mappedSeriesData,
        barMaxWidth: chartType === 'bar' ? barW : 32,
        itemStyle: barSeriesItemStyle,
        lineStyle: chartType === 'line' && lineGlobal ? { color: lineGlobal, width: 2 } : undefined,
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

/**
 * @param row 来自 charts-batch 的单行：chartType, chartSnapshot, generatedSql, queryTableName, id
 * @param ui 看板网格项上的展示覆盖：barColor、barMaxWidth、seriesItemStyles（按下标逐项 itemStyle）
 */
export function buildOptionFromHistoryRow(row, ui = {}) {
  const snap = parseSnapshot(row?.chartSnapshot)
  const chartType = normalizeChartType(row?.chartType || snap.chartType)
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
  const finalOption = applyDynamicInteractionDefaults(withTemplate, template, { chartType })
  if (Array.isArray(finalOption?.dataZoom) && finalOption.dataZoom.length) {
    finalOption.dataZoom = normalizeInteractiveDataZoom(finalOption.dataZoom)
  }
  return finalOption
}
