const SCENARIO_LABELS = {
  TIME_SERIES: '时序趋势',
  GROUP_COMPARE: '分组对比',
  RATIO: '占比分析',
  DETAIL: '明细数据',
  SCENARIO_SIMULATION: '情景推演',
  ADVANCED_ALERT: '智能预警',
  CUSTOM: '自定义规则',
  RADAR: '雷达分析',
  SCATTER: '散点分析',
  METRIC: '指标概览',
  MAP: '地图分析'
}

const STATUS_LABELS = {
  CONFIGURED: '已配置',
  FALLBACK: '兜底推荐',
  EXTENDED: '扩展推荐'
}

const FALLBACK_EXPLAIN_KEY = 'no configured rule matched, so the engine used a safe fallback.'

const normalizeTextKey = (value) => String(value || '')
  .replace(/\s+/g, ' ')
  .trim()
  .toLowerCase()

export const chartRecommendationScenarioLabel = (scenarioType) => {
  const value = String(scenarioType || '').trim().toUpperCase()
  return SCENARIO_LABELS[value] || value || '自动推荐'
}

export const formatChartRecommendationStatus = (status) => {
  const value = String(status || '').trim()
  if (!value) return ''
  const key = value.toUpperCase()
  return STATUS_LABELS[key] || value
}

export const formatChartRecommendationExplain = (explain, context = {}) => {
  const raw = String(explain || '').trim()
  if (!raw) return ''

  const key = normalizeTextKey(raw)
  if (key === FALLBACK_EXPLAIN_KEY || (key.includes('no configured rule matched') && key.includes('safe fallback'))) {
    const scenarioLabel = chartRecommendationScenarioLabel(context.scenarioType || context.chartScenarioType)
    return `未匹配到已配置的图表推荐规则，系统已使用${scenarioLabel}兜底方案生成图表。`
  }

  const matched = raw.match(/^Matched\s+([A-Z_]+)\s+by\s+(\d+)\s+fields?\s+and\s+(\d+)\s+rows?\.?$/i)
  if (matched) {
    const scenarioLabel = chartRecommendationScenarioLabel(matched[1] || context.scenarioType || context.chartScenarioType)
    return `已命中${scenarioLabel}推荐规则，基于 ${matched[2]} 个字段和 ${matched[3]} 行数据完成匹配。`
  }

  return raw
}
