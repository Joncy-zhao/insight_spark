<template>
  <div class="dc-root">
    <div v-if="title && !hideTitle" class="dc-title">{{ title }}</div>
    <div
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
import { buildOptionFromHistoryRow, normalizeInteractiveDataZoom, resolveDynamicRefreshInterval } from '../../utils/chartOptionFromSnapshot.js'

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

function bindResizeObserver() {
  if (typeof ResizeObserver === 'undefined') return
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

const render = () => {
  if (!host.value) return
  if (!hasData.value) {
    chart?.clear?.()
    return
  }
  if (!chart) {
    chart = echarts.getInstanceByDom(host.value) || echarts.init(host.value)
  }
  const option = props.payload?.option && typeof props.payload.option === 'object'
    ? props.payload.option
    : buildOptionFromHistoryRow(props.payload, props.chartUi || {})
  if (Array.isArray(option?.dataZoom) && option.dataZoom.length) {
    option.dataZoom = normalizeInteractiveDataZoom(option.dataZoom)
  }
  chart.setOption(option, true)
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

watch(() => [props.payload, props.chartUi], render, { deep: true })

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
.dc-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
  font-size: 13px;
}
</style>
