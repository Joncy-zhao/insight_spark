<template>
  <div class="lic-root">
    <div v-if="title" class="lic-title">{{ title }}</div>
    <div ref="host" class="lic-host" />
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  title: { type: String, default: '' },
  chartType: { type: String, default: 'bar' },
  data: { type: Array, default: () => [] }
})

const host = ref(null)
let chart = null

function normalizeChartType(value) {
  const t = String(value || '').toLowerCase()
  if (t === 'line' || t === 'pie') return t
  return 'bar'
}

function toNumber(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : Number.NaN
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

function buildOption() {
  const type = normalizeChartType(props.chartType)
  const points = Array.isArray(props.data) ? props.data.map(normalizeChartItem) : []
  if (type === 'pie') {
    return {
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [{ type: 'pie', radius: ['40%', '66%'], data: points }]
    }
  }
  const xAxisData = points.map((p) => p.name)
  const seriesData = points.map((p) => Number(p.value ?? 0))
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 48, right: 12, top: 12, bottom: 48 },
    xAxis: {
      type: 'category',
      data: xAxisData,
      axisLabel: { interval: 0, rotate: xAxisData.length > 6 ? 30 : 0, hideOverlap: true }
    },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef2f7' } } },
    series: [{ type, smooth: type === 'line', data: seriesData, barMaxWidth: 28 }]
  }
}

function render() {
  if (!host.value) return
  if (!chart) {
    chart = echarts.getInstanceByDom(host.value) || echarts.init(host.value)
  }
  chart.setOption(buildOption(), true)
  chart.resize()
}

watch(() => [props.chartType, props.data], render, { deep: true })

onMounted(() => {
  render()
  window.addEventListener('resize', render)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', render)
  if (chart) {
    try {
      chart.dispose()
    } catch {
      // ignore
    }
    chart = null
  }
})
</script>

<style scoped>
.lic-root {
  display: flex;
  flex-direction: column;
  height: 220px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 8px;
  background: #fff;
}
.lic-title {
  font-size: 12px;
  color: #374151;
  margin-bottom: 4px;
  line-height: 1.35;
  flex-shrink: 0;
}
.lic-host {
  flex: 1;
  min-height: 0;
  width: 100%;
}
</style>
