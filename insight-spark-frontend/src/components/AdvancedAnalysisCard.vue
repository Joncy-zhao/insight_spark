<template>
  <article class="advanced-card">
    <header class="advanced-card__header">
      <div class="advanced-card__title-wrap">
        <div class="advanced-card__eyebrow">{{ typeLabel }}</div>
        <h3>{{ analysis.title }}</h3>
        <p>{{ analysis.summary }}</p>
      </div>
      <el-tag effect="light" :type="tagType">{{ statusLabel }}</el-tag>
    </header>

    <section class="advanced-card__meta">
      <div>
        <span>数据源</span>
        <strong>{{ analysis.tableName || '当前对话上下文' }}</strong>
      </div>
      <div>
        <span>指标</span>
        <strong>{{ analysis.metric || '自动推断' }}</strong>
      </div>
      <div>
        <span>时间范围</span>
        <strong>{{ analysis.timeRange || '近 12 期' }}</strong>
      </div>
    </section>

    <section v-if="analysis.type === 'forecast'" class="advanced-card__controls">
      <el-form label-position="top">
        <div class="advanced-card__form-grid">
          <el-form-item label="预测周期">
            <el-input :model-value="horizonDisplay" disabled />
          </el-form-item>
          <el-form-item label="算法">
            <el-select v-model="draft.algorithm" @change="emitRecalculate">
              <el-option label="Prophet-like" value="Prophet" />
              <el-option label="Holt-Winters" value="Holt-Winters" />
            </el-select>
          </el-form-item>
          <el-form-item label="置信区间">
            <el-select v-model="draft.confidence" @change="emitRecalculate">
              <el-option label="95%" value="95%" />
              <el-option label="90%" value="90%" />
            </el-select>
          </el-form-item>
        </div>
        <div v-if="draft.algorithm === 'Holt-Winters'" class="advanced-card__form-grid advanced-card__form-grid--params">
          <el-form-item label="Alpha">
            <el-input-number v-model="draft.alpha" :min="0.01" :max="0.99" :step="0.01" @change="emitRecalculate" />
          </el-form-item>
          <el-form-item label="Beta">
            <el-input-number v-model="draft.beta" :min="0.01" :max="0.99" :step="0.01" @change="emitRecalculate" />
          </el-form-item>
          <el-form-item label="Gamma">
            <el-input-number v-model="draft.gamma" :min="0.01" :max="0.99" :step="0.01" @change="emitRecalculate" />
          </el-form-item>
          <el-form-item label="季节周期">
            <el-input-number v-model="draft.seasonLength" :min="0" :max="60" @change="emitRecalculate" />
          </el-form-item>
        </div>
      </el-form>
    </section>

    <section v-if="analysis.type === 'whatIf'" class="advanced-card__controls">
      <div class="advanced-card__variable-list">
        <div
          v-for="(variable, index) in draft.variables"
          :key="`${variable.name}-${index}`"
          class="advanced-card__variable"
        >
          <el-input v-model="variable.name" placeholder="变量名称" @change="emitRecalculate" />
          <el-select v-model="variable.mode" class="advanced-card__variable-mode" @change="emitRecalculate">
            <el-option label="百分比" value="percent" />
            <el-option label="绝对值" value="absolute" />
            <el-option label="固定值" value="set" />
          </el-select>
          <el-input-number
            v-model="variable.change"
            class="advanced-card__variable-change"
            :step="1"
            controls-position="right"
            @change="emitRecalculate"
          />
          <span class="advanced-card__variable-percent">{{ whatIfModeUnit(variable.mode) }}</span>
        </div>
      </div>
      <div v-if="whatIfScenarioRows.length" class="advanced-card__scenario-grid">
        <div
          v-for="item in whatIfScenarioRows"
          :key="item.name"
          class="advanced-card__scenario-item"
        >
          <span>{{ item.name }}</span>
          <strong>{{ item.valueText }}</strong>
        </div>
      </div>
      <div v-if="whatIfSensitivityRows.length" class="advanced-card__sensitivity">
        <div class="advanced-card__sensitivity-head">
          <span>敏感性排序</span>
          <strong>变量影响</strong>
        </div>
        <div
          v-for="item in whatIfSensitivityRows"
          :key="item.key"
          class="advanced-card__sensitivity-row"
        >
          <div class="advanced-card__sensitivity-meta">
            <span>{{ item.name }}</span>
            <small>{{ item.direction }}</small>
          </div>
          <div class="advanced-card__sensitivity-track">
            <i :style="{ width: item.width, backgroundColor: item.color }"></i>
          </div>
          <strong>{{ item.valueText }}</strong>
        </div>
      </div>
    </section>

    <section v-if="analysis.type === 'alert'" class="advanced-card__controls">
      <el-form label-position="top">
        <div class="advanced-card__form-grid">
          <el-form-item label="判断条件">
            <el-select v-model="draft.operator" @change="emitRecalculate">
              <el-option label="低于" value="lt" />
              <el-option label="高于" value="gt" />
              <el-option label="异常波动" value="zscore" />
            </el-select>
          </el-form-item>
          <el-form-item label="阈值">
            <el-input-number v-model="draft.threshold" :min="0" @change="emitRecalculate" />
          </el-form-item>
          <el-form-item label="通知方式">
            <el-select v-model="draft.channel" @change="emitRecalculate">
              <el-option label="邮件" value="email" />
              <el-option label="钉钉" value="dingtalk" />
              <el-option label="邮件 + 钉钉" value="both" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
    </section>

    <div ref="chartRef" class="advanced-card__chart"></div>

    <section v-if="analysis.type === 'forecast'" class="advanced-card__explain">
      <div class="advanced-card__explain-item">
        <div class="advanced-card__explain-title">
          <span>算法说明</span>
          <el-tooltip placement="top" effect="dark" :show-after="150">
            <template #content>
              <div class="advanced-card__help-popover">
                <div class="advanced-card__help-popover-title">{{ forecastAlgorithmHelpTitle }}</div>
                <div
                  v-for="item in forecastAlgorithmHelpItems"
                  :key="item.label"
                  class="advanced-card__help-popover-item"
                >
                  <strong>{{ item.label }}</strong>
                  <span>{{ item.description }}</span>
                </div>
              </div>
            </template>
            <el-icon class="advanced-card__help-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
        <p>{{ forecastAlgorithmText }}</p>
      </div>
      <div v-if="forecastQualityRows.length" class="advanced-card__quality-grid">
        <div
          v-for="item in forecastQualityRows"
          :key="item.label"
          class="advanced-card__quality-item"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      </div>
      <div v-if="forecastQualityMessage" class="advanced-card__quality-note">
        {{ forecastQualityMessage }}
      </div>
    </section>

    <section v-if="analysis.type !== 'forecast' || forecastInsightRows.length" class="advanced-card__insights">
      <div
        v-for="item in (analysis.type === 'forecast' ? forecastInsightRows : analysis.insights)"
        :key="item.label"
        class="advanced-card__insight"
      >
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </section>

    <footer class="advanced-card__actions">
      <el-button v-if="props.showSaveAction" size="small" type="primary" plain @click="$emit('save', analysis)">保存方案</el-button>
      <el-button
        v-if="props.showPinAction && analysis.type === 'forecast'"
        size="small"
        type="success"
        plain
        @click="$emit('pin', analysis)"
      >
        钉入看板
      </el-button>
      <el-button size="small" plain @click="exportImage">导出图表</el-button>
      <el-button size="small" plain @click="emitRecalculate">重新计算</el-button>
      <el-button v-if="analysis.type === 'alert'" size="small" plain type="warning" @click="$emit('manage-alerts')">规则管理</el-button>
    </footer>
  </article>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import * as echarts from 'echarts'

const props = defineProps({
  analysis: {
    type: Object,
    required: true
  },
  showSaveAction: {
    type: Boolean,
    default: true
  },
  showPinAction: {
    type: Boolean,
    default: true
  }
})

const emit = defineEmits(['recalculate', 'save', 'pin', 'manage-alerts'])

const chartRef = ref(null)
let chartInstance = null

const draft = reactive({
  horizon: props.analysis?.params?.horizon || 3,
  algorithm: props.analysis?.params?.algorithm || 'Prophet',
  confidence: props.analysis?.params?.confidence || '95%',
  alpha: props.analysis?.params?.alpha ?? props.analysis?.params?.algorithmParams?.alpha ?? 0.55,
  beta: props.analysis?.params?.beta ?? props.analysis?.params?.algorithmParams?.beta ?? 0.28,
  gamma: props.analysis?.params?.gamma ?? props.analysis?.params?.algorithmParams?.gamma ?? 0.20,
  seasonLength: props.analysis?.params?.seasonLength ?? props.analysis?.params?.algorithmParams?.seasonLength ?? 0,
  variables: (props.analysis?.params?.variables || []).map(item => ({ ...item })),
  operator: props.analysis?.params?.operator || 'lt',
  threshold: props.analysis?.params?.threshold ?? 100000,
  channel: props.analysis?.params?.channel || 'both'
})

const typeLabel = computed(() => {
  if (props.analysis.type === 'forecast') return '时序预测'
  if (props.analysis.type === 'whatIf') return 'What-if 推演'
  return '离线智能预警'
})

const statusLabel = computed(() => {
  if (props.analysis.type === 'alert') return props.analysis.status || '待确认'
  return props.analysis.status || '已生成'
})

const whatIfModeUnit = (mode) => {
  if (mode === 'absolute') return '绝对值'
  if (mode === 'set') return '固定值'
  return '%'
}

const horizonDisplay = computed(() => {
  const horizon = Number(draft.horizon)
  if (!Number.isFinite(horizon)) return '自动'
  const unit = String(props.analysis?.timeRange || '').trim()
  if (unit === 'day') return `${horizon} 天`
  if (unit === 'week') return `${horizon} 周`
  if (unit === 'quarter') return `${horizon} 个季度`
  if (unit === 'year') return `${horizon} 年`
  return `${horizon} 期`
})

const tagType = computed(() => {
  if (props.analysis.type === 'alert') return 'warning'
  if (props.analysis.type === 'whatIf') return 'success'
  return 'primary'
})

const whatIfSensitivityRows = computed(() => {
  const variables = Array.isArray(draft.variables) ? draft.variables : []
  const rows = variables
    .map((item, index) => {
      const correlation = Number(item.estimatedCorrelation ?? item.correlation ?? 0)
      const change = Number(item.change ?? 0)
      const impact = Number.isFinite(correlation) && Number.isFinite(change) ? change * correlation : 0
      return {
        key: `${item.field || item.name || 'variable'}-${index}`,
        name: String(item.name || item.field || `变量${index + 1}`),
        impact
      }
    })
    .filter(item => Number.isFinite(item.impact) && item.impact !== 0)
    .sort((a, b) => Math.abs(b.impact) - Math.abs(a.impact))
  const max = Math.max(...rows.map(item => Math.abs(item.impact)), 1)
  return rows.map(item => ({
    ...item,
    width: `${Math.max(8, Math.min(100, Math.abs(item.impact) / max * 100))}%`,
    color: item.impact >= 0 ? '#14b8a6' : '#f97316',
    direction: item.impact >= 0 ? '正向影响' : '反向影响',
    valueText: `${item.impact >= 0 ? '+' : ''}${item.impact.toFixed(2)}%`
  }))
})

const whatIfScenarioRows = computed(() => {
  if (props.analysis?.type !== 'whatIf') return []
  const rows = Array.isArray(props.analysis?.series) ? props.analysis.series : []
  return rows
    .filter(item => ['基准方案', '保守方案', '中性方案', '乐观方案', '推荐方案', '模拟方案'].includes(String(item?.name || '')))
    .map(item => ({
      name: item.name,
      valueText: formatAxisValue(item.value)
    }))
})

const formatQualityNumber = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return value ?? '-'
  return Math.abs(number) >= 10000 ? `${(number / 10000).toFixed(2)}w` : number.toFixed(2)
}

const forecastAlgorithmText = computed(() => {
  const algorithm = String(draft.algorithm || props.analysis?.params?.algorithm || '').trim()
  if (algorithm === 'Holt-Winters') {
    return `Holt-Winters 指数平滑，alpha=${draft.alpha}，beta=${draft.beta}，gamma=${draft.gamma}，季节周期=${draft.seasonLength || '自动'}。`
  }
  return `Prophet-like 趋势拟合，使用趋势项与季节项生成预测；当前为轻量实现，非 Python Prophet 服务。`
})

const forecastAlgorithmHelpTitle = computed(() => {
  const algorithm = String(draft.algorithm || props.analysis?.params?.algorithm || '').trim()
  return algorithm === 'Holt-Winters' ? 'Holt-Winters 参数说明' : 'Prophet-like 参数说明'
})

const forecastAlgorithmHelpItems = computed(() => {
  const algorithm = String(draft.algorithm || props.analysis?.params?.algorithm || '').trim()
  if (algorithm === 'Holt-Winters') {
    return [
      { label: '预测周期', description: '控制向前推演多少期，周期越长，未来点越多。' },
      { label: 'Alpha', description: '控制对最新数据的敏感度，越大越重视近期变化。' },
      { label: 'Beta', description: '控制趋势项更新速度，越大越容易跟随趋势变化。' },
      { label: 'Gamma', description: '控制季节项更新速度，越大越重视季节波动。' },
      { label: '季节周期', description: '表示波动多久重复一次，用来让算法记住周期规律。按月销售额常用 12，按日数据有周规律可用 7；设对后，预测会更贴近真实业务节奏。' },
      { label: '置信区间', description: '控制结果展示的置信范围，数值越高，区间越宽。' }
    ]
  }
  return [
    { label: '预测周期', description: '控制向前推演多少期，周期越长，未来点越多。' },
    { label: '置信区间', description: '控制结果展示的置信范围，数值越高，区间越宽。' },
    { label: '季节周期', description: '表示波动多久重复一次，用来拟合周期性变化。按月销售额常用 12，按日数据有周规律可用 7。' }
  ]
})

const forecastQualityRows = computed(() => {
  const quality = props.analysis?.dataQuality || {}
  return [
    { label: '历史点数', value: quality.points },
    { label: '均值', value: formatQualityNumber(quality.average) },
    { label: '标准差', value: formatQualityNumber(quality.stdDev) }
  ].filter(item => item.value !== undefined && item.value !== null && item.value !== '')
})

const forecastQualityMessage = computed(() => props.analysis?.dataQuality?.message || '')

const forecastInsightRows = computed(() => {
  const insights = Array.isArray(props.analysis?.insights) ? props.analysis.insights : []
  return insights.filter(item => !['历史点数', '真实序列点数'].includes(String(item?.label || '').trim()))
})

const formatAxisValue = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(1)}w`
  return `${number}`
}

const getForecastZoomRange = (rows) => {
  const total = Array.isArray(rows) ? rows.length : 0
  if (total <= 24) {
    return { startValue: 0, endValue: Math.max(0, total - 1) }
  }
  const forecastCount = rows.filter(item => item && item.forecast != null).length
  const visibleCount = Math.min(total, Math.max(24, forecastCount + 18))
  return {
    startValue: Math.max(0, total - visibleCount),
    endValue: total - 1
  }
}

const buildForecastOption = () => {
  const rows = props.analysis.series || []
  const zoomRange = getForecastZoomRange(rows)
  return {
    tooltip: { trigger: 'axis' },
    legend: { top: 4, data: ['历史值', '预测值', '置信上界', '置信下界'] },
    grid: { left: 54, right: 24, top: 48, bottom: 42, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map(item => item.name),
      axisLabel: {
        hideOverlap: true,
        interval: 'auto',
        rotate: rows.length > 48 ? 35 : 0
      }
    },
    yAxis: { type: 'value', axisLabel: { formatter: formatAxisValue } },
    dataZoom: [
      { type: 'inside', startValue: zoomRange.startValue, endValue: zoomRange.endValue },
      { type: 'slider', height: 16, bottom: 8, startValue: zoomRange.startValue, endValue: zoomRange.endValue }
    ],
    series: [
      {
        name: '历史值',
        type: 'line',
        smooth: true,
        data: rows.map(item => item.history),
        connectNulls: false,
        showSymbol: false,
        lineStyle: { color: '#2563eb', width: 2 },
        itemStyle: { color: '#2563eb' }
      },
      {
        name: '预测值',
        type: 'line',
        smooth: true,
        data: rows.map(item => item.forecast),
        connectNulls: false,
        showSymbol: true,
        symbolSize: 8,
        sampling: 'lttb',
        lineStyle: { color: '#16a34a', width: 2, type: 'dashed' },
        itemStyle: { color: '#16a34a' }
      },
      {
        name: '置信上界',
        type: 'line',
        data: rows.map(item => item.upper),
        symbol: 'none',
        showSymbol: false,
        lineStyle: { color: '#93c5fd', width: 1 },
        areaStyle: { color: 'rgba(147, 197, 253, 0.18)' }
      },
      {
        name: '置信下界',
        type: 'line',
        data: rows.map(item => item.lower),
        symbol: 'none',
        showSymbol: false,
        lineStyle: { color: '#93c5fd', width: 1 }
      }
    ]
  }
}

const buildWhatIfOption = () => {
  const rows = props.analysis.series || []
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { top: 4 },
    grid: { left: 54, right: 24, top: 48, bottom: 32 },
    xAxis: { type: 'category', data: rows.map(item => item.name) },
    yAxis: { type: 'value', axisLabel: { formatter: formatAxisValue } },
    series: [
      {
        name: '业务结果',
        type: 'bar',
        barMaxWidth: 32,
        data: rows.map(item => item.value),
        itemStyle: { color: '#14b8a6', borderRadius: [5, 5, 0, 0] }
      }
    ]
  }
}

const buildAlertOption = () => {
  const rows = props.analysis.series || []
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 54, right: 24, top: 34, bottom: 34 },
    xAxis: { type: 'category', data: rows.map(item => item.name) },
    yAxis: { type: 'value', axisLabel: { formatter: formatAxisValue } },
    series: [
      {
        name: '检测值',
        type: 'line',
        smooth: true,
        data: rows.map(item => item.value),
        markLine: {
          symbol: 'none',
          data: [{ yAxis: draft.threshold, name: '阈值' }],
          lineStyle: { color: '#ef4444', type: 'dashed' },
          label: { formatter: '阈值' }
        },
        lineStyle: { color: '#f97316', width: 2 },
        itemStyle: { color: '#f97316' },
        areaStyle: { color: 'rgba(249, 115, 22, 0.10)' }
      }
    ]
  }
}

const renderChart = async () => {
  await nextTick()
  if (!chartRef.value) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  const option = props.analysis.type === 'forecast'
    ? buildForecastOption()
    : props.analysis.type === 'whatIf'
      ? buildWhatIfOption()
      : buildAlertOption()
  chartInstance.setOption(option, true)
  chartInstance.resize()
}

const emitRecalculate = () => {
  emit('recalculate', {
    analysis: props.analysis,
    params: {
      horizon: draft.horizon,
      algorithm: draft.algorithm,
      confidence: draft.confidence,
      alpha: draft.alpha,
      beta: draft.beta,
      gamma: draft.gamma,
      seasonLength: draft.seasonLength,
      variables: draft.variables.map(item => ({ ...item })),
      operator: draft.operator,
      threshold: draft.threshold,
      channel: draft.channel
    }
  })
}

const exportImage = () => {
  if (!chartInstance) return
  const url = chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  const link = document.createElement('a')
  link.href = url
  link.download = `${props.analysis.title || 'advanced-analysis'}.png`
  link.click()
}

watch(() => props.analysis, renderChart, { deep: true })

onMounted(renderChart)

onBeforeUnmount(() => {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
.advanced-card {
  display: grid;
  gap: 12px;
  width: min(760px, 100%);
  margin-top: 10px;
  padding: 14px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}
.advanced-card__header,
.advanced-card__actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.advanced-card__title-wrap {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.advanced-card__eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
}
.advanced-card h3 {
  margin: 0;
  color: #0f172a;
  font-size: 16px;
  line-height: 1.4;
}
.advanced-card p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}
.advanced-card__meta,
.advanced-card__insights {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.advanced-card__meta div,
.advanced-card__insight {
  min-width: 0;
  display: grid;
  gap: 4px;
  padding: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}
.advanced-card__meta span,
.advanced-card__insight span {
  color: #64748b;
  font-size: 12px;
}
.advanced-card__meta strong,
.advanced-card__insight strong {
  color: #0f172a;
  font-size: 13px;
  word-break: break-word;
}
.advanced-card__controls {
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fbfdff;
}
.advanced-card__form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.advanced-card__form-grid--params {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 10px;
}
.advanced-card__controls :deep(.el-form-item) {
  margin-bottom: 0;
}
.advanced-card__controls :deep(.el-input.is-disabled .el-input__wrapper) {
  background: #f8fafc;
  box-shadow: inset 0 0 0 1px #dbe4f0;
}
.advanced-card__variable-list {
  display: grid;
  gap: 8px;
}
.advanced-card__variable {
  display: flex;
  align-items: center;
  gap: 8px;
}
.advanced-card__variable :deep(.el-input) {
  flex: 1 1 auto;
  min-width: 0;
}
.advanced-card__variable-mode {
  flex: 0 0 112px;
}
.advanced-card__variable-change {
  flex: 0 0 120px;
}
.advanced-card__variable-percent {
  flex: 0 0 52px;
  width: 52px;
  text-align: center;
  line-height: 1;
  white-space: nowrap;
  color: #64748b;
}
.advanced-card__scenario-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}
.advanced-card__scenario-item {
  display: grid;
  gap: 5px;
  padding: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}
.advanced-card__scenario-item span {
  color: #64748b;
  font-size: 12px;
}
.advanced-card__scenario-item strong {
  color: #0f172a;
  font-size: 14px;
  line-height: 1.3;
  word-break: break-word;
}
.advanced-card__sensitivity {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e2e8f0;
}
.advanced-card__sensitivity-head,
.advanced-card__sensitivity-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(120px, 1.2fr) 76px;
  align-items: center;
  gap: 10px;
}
.advanced-card__sensitivity-head {
  font-size: 12px;
  color: #64748b;
}
.advanced-card__sensitivity-head strong {
  justify-self: end;
  grid-column: 3;
  font-size: 12px;
  color: #64748b;
}
.advanced-card__sensitivity-meta {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.advanced-card__sensitivity-meta span {
  overflow: hidden;
  color: #0f172a;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.advanced-card__sensitivity-meta small {
  color: #64748b;
  font-size: 12px;
}
.advanced-card__sensitivity-track {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e2e8f0;
}
.advanced-card__sensitivity-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}
.advanced-card__sensitivity-row > strong {
  justify-self: end;
  color: #0f172a;
  font-size: 13px;
}
.advanced-card__chart {
  width: 100%;
  height: 280px;
}
.advanced-card__explain {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}
.advanced-card__explain-item {
  display: grid;
  gap: 4px;
}
.advanced-card__explain-title {
  display: flex;
  align-items: center;
  gap: 6px;
}
.advanced-card__explain-item span,
.advanced-card__quality-item span {
  color: #64748b;
  font-size: 12px;
}
.advanced-card__help-icon {
  color: #2563eb;
  cursor: help;
  font-size: 14px;
}
.advanced-card__help-popover {
  display: grid;
  gap: 8px;
  max-width: 260px;
}
.advanced-card__help-popover-title {
  font-weight: 700;
  font-size: 13px;
}
.advanced-card__help-popover-item {
  display: grid;
  gap: 2px;
}
.advanced-card__help-popover-item strong {
  font-size: 12px;
}
.advanced-card__help-popover-item span {
  font-size: 12px;
  line-height: 1.5;
  color: #e2e8f0;
}
.advanced-card__explain-item p {
  margin: 0;
  color: #0f172a;
  font-size: 13px;
  line-height: 1.6;
}
.advanced-card__quality-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.advanced-card__quality-item {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #ffffff;
}
.advanced-card__quality-item strong {
  overflow: hidden;
  color: #0f172a;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.advanced-card__quality-note {
  color: #475569;
  font-size: 12px;
  line-height: 1.5;
}
.advanced-card__actions {
  justify-content: flex-end;
}
.advanced-card__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

@media (max-width: 720px) {
  .advanced-card__meta,
  .advanced-card__insights,
  .advanced-card__form-grid {
    grid-template-columns: 1fr;
  }
  .advanced-card__variable {
    flex-wrap: wrap;
    align-items: stretch;
  }
  .advanced-card__variable :deep(.el-input),
  .advanced-card__variable-mode,
  .advanced-card__variable-change,
  .advanced-card__variable-percent {
    flex: 1 1 100%;
    width: 100%;
  }
  .advanced-card__scenario-grid {
    grid-template-columns: 1fr;
  }
  .advanced-card__sensitivity-head,
  .advanced-card__sensitivity-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .advanced-card__sensitivity-head strong,
  .advanced-card__sensitivity-row > strong {
    justify-self: start;
  }
  .advanced-card__quality-grid {
    grid-template-columns: 1fr;
  }
  .advanced-card__chart {
    height: 240px;
  }
}
</style>
