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
            <el-input-number v-model="draft.horizon" :min="1" :max="60" @change="emitRecalculate" />
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
          <el-input-number v-model="variable.change" :min="-100" :max="100" :step="1" @change="emitRecalculate" />
          <span>%</span>
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

    <section class="advanced-card__insights">
      <div
        v-for="item in analysis.insights"
        :key="item.label"
        class="advanced-card__insight"
      >
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </section>

    <footer class="advanced-card__actions">
      <el-button size="small" type="primary" plain @click="$emit('save', analysis)">保存方案</el-button>
      <el-button
        v-if="analysis.type === 'forecast'"
        size="small"
        type="success"
        plain
        @click="$emit('pin', analysis)"
      >
        钉入看板
      </el-button>
      <el-button size="small" plain @click="exportImage">导出图表</el-button>
      <el-button size="small" plain @click="emitRecalculate">重新计算</el-button>
    </footer>
  </article>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  analysis: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['recalculate', 'save', 'pin'])

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

const tagType = computed(() => {
  if (props.analysis.type === 'alert') return 'warning'
  if (props.analysis.type === 'whatIf') return 'success'
  return 'primary'
})

const formatAxisValue = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(1)}w`
  return `${number}`
}

const buildForecastOption = () => {
  const rows = props.analysis.series || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { top: 4, data: ['历史值', '预测值', '置信上界', '置信下界'] },
    grid: { left: 54, right: 24, top: 48, bottom: 42 },
    xAxis: { type: 'category', data: rows.map(item => item.name) },
    yAxis: { type: 'value', axisLabel: { formatter: formatAxisValue } },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 8 }],
    series: [
      {
        name: '历史值',
        type: 'line',
        smooth: true,
        data: rows.map(item => item.history),
        connectNulls: false,
        lineStyle: { color: '#2563eb', width: 2 },
        itemStyle: { color: '#2563eb' }
      },
      {
        name: '预测值',
        type: 'line',
        smooth: true,
        data: rows.map(item => item.forecast),
        connectNulls: false,
        lineStyle: { color: '#16a34a', width: 2, type: 'dashed' },
        itemStyle: { color: '#16a34a' }
      },
      {
        name: '置信上界',
        type: 'line',
        data: rows.map(item => item.upper),
        symbol: 'none',
        lineStyle: { color: '#93c5fd', width: 1 },
        areaStyle: { color: 'rgba(147, 197, 253, 0.18)' }
      },
      {
        name: '置信下界',
        type: 'line',
        data: rows.map(item => item.lower),
        symbol: 'none',
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
.advanced-card__variable-list {
  display: grid;
  gap: 8px;
}
.advanced-card__variable {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 132px 20px;
  align-items: center;
  gap: 8px;
}
.advanced-card__chart {
  width: 100%;
  height: 280px;
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
    grid-template-columns: 1fr;
  }
  .advanced-card__chart {
    height: 240px;
  }
}
</style>
