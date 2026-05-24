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
  if (type === 'line' || type === 'pie') return type
  if (type.includes('饼')) return 'pie'
  if (type.includes('折')) return 'line'
  if (type.includes('柱')) return 'bar'
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

export function applyOptionTemplateDefaults(built, template) {
  if (!template || typeof template !== 'object') return built
  if (!built || typeof built !== 'object') return built
  const { series: ts, ...tRest } = template
  const { series: bs, ...bRest } = built
  const mergedTop = defaultsDeep({ ...bRest }, tRest)
  const out = { ...built, ...mergedTop }
  if (Array.isArray(bs)) {
    out.series = bs.map((seriesItem, i) =>
      ts?.[i] != null && typeof ts[i] === 'object' && typeof seriesItem === 'object'
        ? defaultsDeep({ ...seriesItem }, ts[i])
        : seriesItem
    )
  } else {
    out.series = bs
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
    option.dataZoom = [
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
    ]
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
    option.dataZoom = [
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
    ]
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

  let built
  if (snapHasEncode(snap) && !hasItemOverrides) {
    built = buildOptionFromEncodeDataset(snap, chartType, ui)
  } else {
    built = buildOptionLegacyFromPoints(snap, chartType, ui, itemOv)
  }

  return template ? applyOptionTemplateDefaults(built, template) : built
}
