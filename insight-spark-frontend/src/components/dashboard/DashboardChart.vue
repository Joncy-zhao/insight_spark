<template>
  <div class="dc-root">
    <div v-if="title && !hideTitle" class="dc-title">{{ title }}</div>

    <div v-if="isTableView" class="dc-table-wrap">
      <el-table
        :data="tableRows"
        border
        :stripe="tableStripe"
        height="100%"
        table-layout="fixed"
        empty-text="暂无明细数据"
        :class="['dc-table', { 'dc-table--compact': tableBodyFontSize <= 11 }]"
        :style="tableStyleVars"
      >
        <el-table-column
          v-for="column in tableColumns"
          :key="column.prop"
          :prop="column.prop"
          :label="column.label"
          min-width="96"
          show-overflow-tooltip
        />
      </el-table>
    </div>

    <div v-else-if="isMetricView" class="dc-metric" :style="metricStyleVars">
      <div class="dc-metric-value">
        <span>{{ metricDisplay.value }}</span>
        <small v-if="metricDisplay.unit">{{ metricDisplay.unit }}</small>
      </div>
      <div class="dc-metric-label">{{ metricDisplay.label }}</div>
      <div v-if="metricDisplay.compareValue || metricDisplay.trend" class="dc-metric-compare">
        <span v-if="metricDisplay.trend" :class="['dc-metric-trend', `dc-metric-trend--${metricDisplay.trend}`]">
          {{ metricTrendText(metricDisplay.trend) }}
        </span>
        <span v-if="metricDisplay.compareValue">{{ metricDisplay.compareLabel }} {{ metricDisplay.compareValue }}</span>
      </div>
      <div v-if="metricDisplay.note" class="dc-metric-note">{{ metricDisplay.note }}</div>
    </div>

    <div
      v-else
      ref="host"
      class="dc-host"
      @mousedown.stop
      @touchstart.stop
      @pointerdown.stop
    />

    <div v-if="!hasData" class="dc-empty">暂无图表数据</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import {
  buildOptionFromHistoryRow,
  normalizeChartType,
  normalizeInteractiveDataZoom,
  resolveDynamicRefreshInterval
} from '../../utils/chartOptionFromSnapshot.js'
import { mergeChartStyle } from '../../utils/chartUiConfig.js'
import {
  snapshotMetricDisplay,
  snapshotTableColumns,
  snapshotTableRows
} from '../../utils/dashboardChartSnapshotView.js'

const props = defineProps({
  /** charts-batch 返回的单行 */
  payload: { type: Object, default: null },
  /** 看板 layout_json.items 上的展示覆盖：barColor、barMaxWidth 等 */
  chartUi: { type: Object, default: null },
  /** 看板卡片业务标题（优先于快照里的 message） */
  displayTitle: { type: String, default: '' },
  /** 为 true 时不展示顶部标题（由外层卡片展示） */
  hideTitle: { type: Boolean, default: false }
})

const emit = defineEmits(['refresh'])

const host = ref(null)
let chart = null
let resizeObserver = null
let refreshTimer = null

const chartType = computed(() => {
  const snap = props.payload?.chartSnapshot
  const obj = typeof snap === 'object' && snap ? snap : {}
  return normalizeChartType(props.payload?.chartType || obj.chartType)
})

const chartStyle = computed(() => mergeChartStyle(props.chartUi?.chartStyle))

const isTableView = computed(() => chartType.value === 'table')
const isMetricView = computed(() => chartType.value === 'metric')
const isEchartsView = computed(() => !isTableView.value && !isMetricView.value)

const tableRows = computed(() => snapshotTableRows(props.payload))
const tableColumns = computed(() => snapshotTableColumns(props.payload))
const tableStripe = computed(() => chartStyle.value.tableStripe !== false)
const tableBodyFontSize = computed(() => Number(chartStyle.value.tableBodyFontSize) || 12)
const tableHeaderFontSize = computed(() => Number(chartStyle.value.tableHeaderFontSize) || 12)

const tableStyleVars = computed(() => ({
  '--dc-table-header-size': `${tableHeaderFontSize.value}px`,
  '--dc-table-body-size': `${tableBodyFontSize.value}px`
}))

const metricDisplay = computed(() => snapshotMetricDisplay(props.payload))
const metricStyleVars = computed(() => ({
  '--dc-metric-value-size': `${Number(chartStyle.value.metricValueFontSize) || 36}px`,
  '--dc-metric-label-size': `${Number(chartStyle.value.metricLabelFontSize) || 13}px`,
  '--dc-metric-value-color': chartStyle.value.metricValueColor || '#0f172a'
}))

function metricTrendText(trend) {
  const text = String(trend || '').toLowerCase()
  if (text === 'up') return '上升'
  if (text === 'down') return '下降'
  if (text === 'flat') return '持平'
  return ''
}

function bindResizeObserver() {
  if (typeof ResizeObserver === 'undefined' || !isEchartsView.value) return
  resizeObserver?.disconnect()
  const el = host.value
  if (!el) return
  resizeObserver = new ResizeObserver(() => {
    chart?.resize?.()
  })
  resizeObserver.observe(el)
}

const title = computed(() => {
  const custom = String(props.displayTitle || '').trim()
  if (custom) return custom.slice(0, 120)
  const snap = props.payload?.chartSnapshot
  const obj = typeof snap === 'object' && snap ? snap : {}
  return String(obj.message || props.payload?.queryText || '').slice(0, 120) || ''
})

const hasData = computed(() => {
  if (isTableView.value) return tableRows.value.length > 0
  if (isMetricView.value) return metricDisplay.value.value !== '—'
  if (props.payload?.option && typeof props.payload.option === 'object') return true
  const snap = props.payload?.chartSnapshot
  if (typeof snap === 'object' && snap && Array.isArray(snap.data)) return snap.data.length > 0
  try {
    const o = typeof snap === 'string' ? JSON.parse(snap) : {}
    return Array.isArray(o.data) && o.data.length > 0
  } catch {
    return false
  }
})

function disposeChart() {
  if (chart) {
    try {
      chart.dispose()
    } catch {
      // ignore
    }
    chart = null
  }
}

const render = () => {
  if (!isEchartsView.value) {
    disposeChart()
    return
  }
  if (!host.value) return
  if (!hasData.value) {
    chart?.clear?.()
    return
  }
  if (!chart) {
    chart = echarts.getInstanceByDom(host.value) || echarts.init(host.value)
  }
  const ui = props.chartUi || {}
  const option = buildOptionFromHistoryRow(props.payload, ui)
  if (!option) {
    chart?.clear?.()
    return
  }
  if (Array.isArray(option?.dataZoom) && option.dataZoom.length) {
    option.dataZoom = normalizeInteractiveDataZoom(option.dataZoom)
  }
  chart.setOption(option, { notMerge: true })
  chart.resize()
  scheduleDynamicRefresh(resolveDynamicRefreshInterval(option))
}

function scheduleDynamicRefresh(seconds) {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
  const intervalSeconds = Number(seconds) || 0
  if (intervalSeconds < 5) return
  refreshTimer = window.setInterval(() => emit('refresh'), intervalSeconds * 1000)
}

watch(() => [props.payload, props.chartUi, chartType.value], () => {
  nextTick(() => {
    render()
    bindResizeObserver()
  })
}, { deep: true })

watch(host, () => {
  nextTick(() => bindResizeObserver())
})

onMounted(() => {
  render()
  window.addEventListener('resize', render)
  nextTick(() => bindResizeObserver())
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
  window.removeEventListener('resize', render)
  disposeChart()
})
</script>

<style scoped>
.dc-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.dc-title {
  font-size: 12px;
  color: #374151;
  margin-bottom: 4px;
  flex-shrink: 0;
  line-height: 1.3;
}
.dc-host {
  flex: 1;
  min-height: 120px;
  width: 100%;
}
.dc-table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.dc-table :deep(.el-table__header th.el-table__cell) {
  font-size: var(--dc-table-header-size, 12px);
  padding: 6px 0;
}
.dc-table :deep(.el-table__body td.el-table__cell) {
  font-size: var(--dc-table-body-size, 12px);
  padding: 5px 0;
}
.dc-table--compact :deep(.el-table__body td.el-table__cell) {
  padding: 3px 0;
}
.dc-metric {
  flex: 1;
  min-height: 120px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  text-align: center;
}
.dc-metric-value {
  font-size: var(--dc-metric-value-size, 36px);
  font-weight: 700;
  color: var(--dc-metric-value-color, #0f172a);
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  word-break: break-all;
  display: inline-flex;
  align-items: flex-end;
  justify-content: center;
  gap: 6px;
  flex-wrap: wrap;
}
.dc-metric-value small {
  padding-bottom: 3px;
  font-size: 0.42em;
  font-weight: 700;
  color: #64748b;
}
.dc-metric-label {
  font-size: var(--dc-metric-label-size, 13px);
  color: #64748b;
  line-height: 1.4;
}
.dc-metric-compare {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
  line-height: 1.35;
}
.dc-metric-trend {
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}
.dc-metric-trend--up {
  color: #047857;
  background: #ecfdf5;
}
.dc-metric-trend--down {
  color: #b91c1c;
  background: #fef2f2;
}
.dc-metric-trend--flat {
  color: #475569;
  background: #f1f5f9;
}
.dc-metric-note {
  color: #94a3b8;
  font-size: 11px;
  line-height: 1.35;
}
.dc-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
  font-size: 13px;
}
</style>
