<template>
  <div class="dashboard-preview-dialogs">
    <el-dialog
      v-model="chartListVisible"
      :title="chartListTitle"
      :width="chartListDialogWidth"
      :top="chartListSqlOpenKey ? '2vh' : '3vh'"
      :class="['chart-preview-dlg', { 'chart-preview-dlg--sql': Boolean(chartListSqlOpenKey) }]"
      destroy-on-close
    >
      <div class="preview-section-head">
        <span>看板图表</span>
        <el-tag size="small" type="info">{{ chartListCards.length }} 张</el-tag>
      </div>
      <div
        v-if="chartListCards.length"
        :class="['dash-chart-grid', dashChartPreviewGridClass(chartListCards.length)]"
      >
        <article
          v-for="(card, index) in chartListCards"
          :key="card._renderKey"
          :class="['chart-card', { 'chart-card--pie': normalizeChartType(card.chartType) === 'pie' }]"
        >
          <div class="chart-card-head">
            <h4>{{ card.title || `图表${index + 1}` }}</h4>
            <div class="chart-card-actions">
              <el-tag size="small">{{ chartTypeLabel(card.chartType) }}</el-tag>
              <el-button
                v-if="enableDiagnosis"
                size="small"
                type="primary"
                :loading="diagnosisLoading"
                :disabled="!card.tableName"
                @click.stop="generateDiagnosisFromCard(card)"
              >
                生成诊断报告
              </el-button>
            </div>
          </div>
          <div class="chart-sub">{{ card.tableName || '未指定数据表' }}</div>
          <div v-if="card.sourceMeta" class="chart-source">
            <el-tag size="small" effect="plain">{{ card.sourceMeta.sourceLabel }}</el-tag>
            <el-tag v-if="card.sourceMeta.snapshotLabel" size="small" type="info" effect="plain">{{ card.sourceMeta.snapshotLabel }}</el-tag>
            <span v-if="card.sourceMeta.detail">{{ card.sourceMeta.detail }}</span>
          </div>
          <div v-if="card._previewKind === 'unavailable'" class="chart-box chart-box--empty">
            <el-empty :description="card.unavailableMessage || '图表暂不可用'" :image-size="48" />
          </div>
          <div v-else :ref="setChartRef('chart-list', card._renderKey)" class="chart-box" />
          <details
            v-if="card.sql"
            class="sql-wrap"
            @toggle="onChartListSqlToggle(card._renderKey, $event)"
          >
            <summary>查看 SQL</summary>
            <pre class="sql-pre">{{ card.sql }}</pre>
          </details>
        </article>
      </div>
      <el-empty v-else description="当前看板暂无可渲染图表卡片" />
    </el-dialog>

    <el-dialog
      v-model="widgetListVisible"
      :title="widgetListTitle"
      :width="widgetListDialogWidth"
      top="3vh"
      class="widget-list-dlg"
      destroy-on-close
    >
      <div class="preview-section-head">
        <span>基础组件</span>
        <el-tag size="small" type="warning">{{ widgetListCards.length }} 个</el-tag>
      </div>
      <div
        v-if="widgetListCards.length"
        :class="['dash-widget-grid', dashChartPreviewGridClass(widgetListCards.length)]"
      >
        <article
          v-for="(card, index) in widgetListCards"
          :key="card._renderKey"
          class="widget-card"
        >
          <div class="widget-card-head">
            <h4>{{ card.title || `组件${index + 1}` }}</h4>
            <el-tag size="small" type="warning" effect="plain">{{ card.label }}</el-tag>
          </div>
          <div class="widget-card-meta">
            画布位置：第 {{ card.layout.y + 1 }} 行 · 宽 {{ card.layout.w }} × 高 {{ card.layout.h }}
          </div>
          <div class="widget-card-preview">
            <component
              :is="basicWidgetComponent(card.widgetKind)"
              v-if="basicWidgetComponent(card.widgetKind)"
              :config="card.config"
              :interactive="false"
            />
            <el-empty v-else description="未知组件类型" :image-size="40" />
          </div>
        </article>
      </div>
      <el-empty v-else description="当前看板暂无基础组件" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'
import {
  buildUnifiedPreviewCards,
  buildBasicWidgetPreviewCards,
  extractLegacyChartCards,
  dashChartPreviewGridClass,
  previewChartDialogWidth
} from '../../utils/dashboardGrid.js'
import { resolveBasicWidgetEntry } from '../../utils/dashboardBasicWidgetRegistry.js'
import { buildOptionFromHistoryRow } from '../../utils/chartOptionFromSnapshot.js'

const props = defineProps({
  apiBase: { type: String, default: 'http://localhost:8080' },
  enableDiagnosis: { type: Boolean, default: true }
})

const workbench = inject('workbench', null)
const diagnosisLoading = computed(() => Boolean(workbench?.diagnosisLoading?.value))

const chartListVisible = ref(false)
const chartListTitle = ref('看板图表')
const chartListCards = ref([])
const chartListContext = ref(null)
const chartListSqlOpenKey = ref(null)
const chartListDialogWidth = computed(() =>
  previewChartDialogWidth(chartListCards.value.length, { openSql: Boolean(chartListSqlOpenKey.value) })
)

const widgetListVisible = ref(false)
const widgetListTitle = ref('基础组件')
const widgetListCards = ref([])
const widgetListDialogWidth = computed(() => previewChartDialogWidth(widgetListCards.value.length))

const chartListChartRefs = new Map()
const chartInstances = new Map()

function chartRefMapForScope(scope) {
  if (scope === 'chart-list') return chartListChartRefs
  return null
}

function normalizeChartType(value) {
  const type = String(value || '').toLowerCase()
  if (type === 'line' || type === 'pie') return type
  return 'bar'
}

function chartTypeLabel(type) {
  if (type === 'line') return '折线图'
  if (type === 'pie') return '饼图'
  return '柱状图'
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

function buildChartOption(card) {
  const points = Array.isArray(card.data) ? card.data.map(normalizeChartItem) : []
  if (card.chartType === 'pie') {
    const many = points.length > 10
    return {
      tooltip: { trigger: 'item', confine: true },
      legend: many
        ? {
            type: 'scroll',
            orient: 'vertical',
            right: 6,
            top: '8%',
            bottom: '8%',
            width: 150,
            textStyle: { fontSize: 11 },
            itemWidth: 10,
            itemHeight: 10
          }
        : {
            type: 'scroll',
            bottom: 4,
            textStyle: { fontSize: 11 },
            itemWidth: 10,
            itemHeight: 10
          },
      series: [
        {
          type: 'pie',
          radius: many ? ['22%', '44%'] : ['36%', '62%'],
          center: many ? ['34%', '50%'] : ['50%', '48%'],
          avoidLabelOverlap: true,
          minShowLabelAngle: 6,
          minAngle: 2,
          label: many
            ? { show: false }
            : {
                formatter: '{b}\n{d}%',
                fontSize: 11,
                overflow: 'break',
                width: 100
              },
          labelLine: { show: !many, length: 12, length2: 8 },
          emphasis: {
            label: { show: true, fontSize: 12, formatter: '{b}\n{c} ({d}%)' }
          },
          data: points
        }
      ]
    }
  }

  const xAxisData = points.map((item) => item.name)
  const seriesData = points.map((item) => Number(item.value ?? 0))
  const useZoom = xAxisData.length > 14
  const endPct = xAxisData.length ? Math.min(100, Math.ceil((14 / xAxisData.length) * 100)) : 100

  const option = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true },
    grid: {
      left: 52,
      right: 16,
      top: 16,
      bottom: useZoom ? 88 : Math.min(120, 36 + (xAxisData.length > 10 ? 56 : 40))
    },
    xAxis: {
      type: 'category',
      data: xAxisData,
      axisLabel: { interval: 0, rotate: xAxisData.length > 8 ? 35 : 0, fontSize: 11 }
    },
    yAxis: { type: 'value', axisLabel: { fontSize: 11 } },
    series: [
      {
        type: card.chartType,
        smooth: card.chartType === 'line',
        data: seriesData,
        barMaxWidth: 36,
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
        bottom: 12,
        height: 22,
        start: 0,
        end: endPct
      },
      { type: 'inside', xAxisIndex: 0, start: 0, end: endPct }
    ]
  }

  return option
}

function onChartListSqlToggle(renderKey, evt) {
  const el = evt?.target
  const opened = el && typeof el.open === 'boolean' ? el.open : false
  if (opened) {
    chartListSqlOpenKey.value = renderKey
  } else if (chartListSqlOpenKey.value === renderKey) {
    chartListSqlOpenKey.value = null
  }
  nextTick(() => {
    requestAnimationFrame(() => {
      window.dispatchEvent(new Event('resize'))
      for (const [key, inst] of chartInstances.entries()) {
        if (!key.startsWith('chart-list:') || !inst?.resize) continue
        try {
          inst.resize()
        } catch {
          // ignore
        }
      }
    })
  })
}

function setChartRef(scope, key) {
  return (el) => {
    const map = chartRefMapForScope(scope)
    if (!map) return
    if (el) map.set(key, el)
    else map.delete(key)
  }
}

function disposeScopeCharts(scope) {
  const map = chartRefMapForScope(scope)
  if (!map) return
  const prefix = `${scope}:`
  for (const [key, instance] of chartInstances.entries()) {
    if (!key.startsWith(prefix)) continue
    try {
      instance.dispose()
    } catch {
      // ignore
    }
    chartInstances.delete(key)
  }
  map.clear()
}

async function renderScopeCharts(scope, cards) {
  const refs = chartRefMapForScope(scope)
  if (!refs) return
  await nextTick()
  const alive = new Set()

  cards.forEach((card) => {
    if (card._previewKind === 'unavailable') return
    const renderKey = String(card._renderKey || card.cardId || '')
    if (!renderKey) return
    const fullKey = `${scope}:${renderKey}`
    alive.add(fullKey)
    const container = refs.get(renderKey)
    if (!container) return

    let instance = chartInstances.get(fullKey)
    if (instance?.isDisposed?.()) {
      chartInstances.delete(fullKey)
      instance = null
    }
    if (instance && instance.getDom?.() !== container) {
      try {
        instance.dispose()
      } catch {
        // ignore
      }
      chartInstances.delete(fullKey)
      instance = null
    }
    if (!instance) {
      instance = echarts.getInstanceByDom(container) || echarts.init(container)
      chartInstances.set(fullKey, instance)
    }

    const option =
      card.option && typeof card.option === 'object'
        ? card.option
        : card.payloadRow != null
          ? buildOptionFromHistoryRow(card.payloadRow, card.chartUi || {})
          : buildChartOption(card)
    instance.setOption(option, true)
    instance.resize()
    requestAnimationFrame(() => {
      instance?.resize?.()
    })
  })

  for (const [key, instance] of chartInstances.entries()) {
    if (!key.startsWith(`${scope}:`) || alive.has(key)) continue
    try {
      instance.dispose()
    } catch {
      // ignore
    }
    chartInstances.delete(key)
  }
}

async function generateDiagnosisFromCard(card) {
  if (!props.enableDiagnosis || !workbench?.diagnoseFromDashboardCard) {
    ElMessage.warning('诊断报告模块尚未就绪')
    return
  }
  const key = `chart-list:${card?._renderKey || ''}`
  const instance = chartInstances.get(key)
  const imageDataUrl = instance?.getDataURL
    ? instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
    : ''
  await workbench.diagnoseFromDashboardCard(
    {
      ...card,
      dashboardId: chartListContext.value?.id || null,
      dashboardName: chartListContext.value?.name || '',
      cardTitle: card?.title || ''
    },
    imageDataUrl
  )
}

function basicWidgetComponent(widgetKind) {
  return resolveBasicWidgetEntry(widgetKind)?.widget || null
}

async function openWidgetList(row) {
  const count = Number(row?.basicWidgetCount || 0)
  if (!count) {
    ElMessage.info('当前看板暂无基础组件')
    return
  }
  widgetListTitle.value = row?.name ? `${row.name} - 基础组件` : '基础组件'
  const id = row?.id
  const scope = `widget-list-${id || 'temp'}`
  let layoutJson = row?.layoutJson
  if (id) {
    restoreSessionHeader()
    try {
      const dashRes = await axios.get(`${props.apiBase}/api/c/dashboards/${id}`)
      if (dashRes.data.code !== 200) throw new Error(dashRes.data.message || '加载看板失败')
      layoutJson = dashRes.data?.data?.layoutJson ?? layoutJson
    } catch (e) {
      ElMessage.error(e.message || '加载失败')
      layoutJson = row?.layoutJson
    }
  }
  widgetListCards.value = buildBasicWidgetPreviewCards(layoutJson, scope)
  widgetListVisible.value = true
}

async function openChartList(row) {
  const count = Number(row?.chartCardCount || 0)
  if (!count) {
    ElMessage.info('当前看板暂无图表')
    return
  }
  chartListContext.value = row ? { ...row } : null
  chartListTitle.value = row?.name ? `${row.name} - 图表列表` : '看板图表'
  const id = row?.id
  const scope = `chart-list-${id || 'temp'}`
  if (!id) {
    chartListCards.value = extractLegacyChartCards(row?.layoutJson, scope)
    chartListVisible.value = true
    await renderScopeCharts('chart-list', chartListCards.value)
    return
  }
  restoreSessionHeader()
  try {
    const [dashRes, compRes] = await Promise.all([
      axios.get(`${props.apiBase}/api/c/dashboards/${id}`),
      axios.get(`${props.apiBase}/api/c/dashboards/${id}/components`)
    ])
    if (dashRes.data.code !== 200) throw new Error(dashRes.data.message || '加载看板失败')
    if (compRes.data.code !== 200) throw new Error(compRes.data.message || '加载组件失败')
    const layoutJson = dashRes.data?.data?.layoutJson ?? row?.layoutJson
    const components = Array.isArray(compRes.data.data) ? compRes.data.data : []
    const chartIds = [
      ...new Set(
        components
          .map((c) => c.chartId ?? c.chart_id ?? c.CHART_ID)
          .filter((x) => x != null && String(x).trim() !== '')
          .map((x) => Number(x))
          .filter((n) => Number.isFinite(n) && n > 0)
      )
    ]
    const payloadMap = {}
    if (chartIds.length) {
      const batchRes = await axios.post(`${props.apiBase}/api/chat/history/charts-batch`, {
        ids: chartIds,
        dashboardId: id
      })
      if (batchRes.data.code !== 200) throw new Error(batchRes.data.message || '加载图表数据失败')
      const batchRows = Array.isArray(batchRes.data.data) ? batchRes.data.data : []
      for (const r of batchRows) {
        const hid = r.id != null ? String(r.id) : ''
        if (hid) payloadMap[hid] = r
      }
    }
    chartListCards.value = buildUnifiedPreviewCards(layoutJson, components, payloadMap, scope)
    chartListVisible.value = true
    await renderScopeCharts('chart-list', chartListCards.value)
  } catch (e) {
    ElMessage.error(e.message || '加载图表失败')
    chartListCards.value = extractLegacyChartCards(row?.layoutJson, scope)
    chartListVisible.value = true
    await renderScopeCharts('chart-list', chartListCards.value)
  }
}

function handleWindowResize() {
  for (const instance of chartInstances.values()) {
    instance?.resize?.()
  }
}

onMounted(() => {
  window.addEventListener('resize', handleWindowResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleWindowResize)
  disposeScopeCharts('chart-list')
})

defineExpose({ openChartList, openWidgetList })
</script>

<style scoped>
.preview-section-head {
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dash-chart-grid {
  --preview-cols: 1;
  --plot: clamp(300px, calc((100vw - 120px) / var(--preview-cols, 1)), 720px);
  display: grid;
  gap: 16px;
  justify-content: center;
  align-items: start;
  width: 100%;
  box-sizing: border-box;
}

.dash-chart-grid--single {
  --preview-cols: 1;
  grid-template-columns: minmax(0, 1fr);
  justify-items: center;
}

.dash-chart-grid--double {
  --preview-cols: 2;
  grid-template-columns: repeat(2, auto);
}

.dash-chart-grid--multi {
  --preview-cols: 3;
  grid-template-columns: repeat(3, auto);
}

.dash-widget-grid {
  display: grid;
  gap: 16px;
  justify-content: center;
  align-items: start;
  width: 100%;
  box-sizing: border-box;
}

.dash-widget-grid.dash-chart-grid--single {
  grid-template-columns: minmax(0, 1fr);
  justify-items: center;
}

.dash-widget-grid.dash-chart-grid--double {
  grid-template-columns: repeat(2, minmax(280px, 1fr));
}

.dash-widget-grid.dash-chart-grid--multi {
  grid-template-columns: repeat(3, minmax(240px, 1fr));
}

.widget-card {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 12px;
  background: #fff;
  display: flex;
  flex-direction: column;
  min-height: 0;
  width: 100%;
  max-width: min(100%, 480px);
  box-sizing: border-box;
}

.widget-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.widget-card-head h4 {
  margin: 0;
  font-size: 14px;
  color: #111827;
  line-height: 1.35;
  word-break: break-word;
}

.widget-card-meta {
  color: #6b7280;
  font-size: 12px;
  margin-bottom: 10px;
}

.widget-card-preview {
  min-height: 200px;
  height: 240px;
  border: 1px dashed #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #f9fafb;
  position: relative;
}

.widget-card-preview :deep(.dbw-root),
.widget-card-preview :deep(.dbw-content) {
  width: 100%;
  height: 100%;
}

.chart-card {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 12px;
  background: #fff;
  display: flex;
  flex-direction: column;
  min-height: 0;
  width: max-content;
  max-width: min(100%, calc(100vw - 32px));
  box-sizing: border-box;
}

.dash-chart-grid--single .chart-card {
  width: min(100%, 920px);
  max-width: 100%;
}

.chart-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.chart-card-head h4 {
  margin: 0;
  font-size: 14px;
  color: #111827;
  line-height: 1.35;
  word-break: break-word;
}

.chart-card-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.chart-sub {
  color: #6b7280;
  font-size: 12px;
  margin-bottom: 8px;
  word-break: break-all;
}

.chart-source {
  min-height: 24px;
  display: flex;
  align-items: center;
  gap: 6px;
  margin: -2px 0 8px;
  color: #475569;
  font-size: 12px;
  line-height: 1.4;
  overflow: hidden;
}

.chart-source span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-box {
  --s: min(var(--plot), calc((min(65vh, 640px)) * 360 / 260));
  width: min(100%, var(--s));
  aspect-ratio: 360 / 260;
  height: auto;
  flex: 0 0 auto;
  box-sizing: border-box;
  align-self: center;
}

.chart-box--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  aspect-ratio: auto;
  width: 100%;
}

.sql-wrap {
  margin-top: 10px;
  flex-shrink: 0;
}

.sql-wrap summary {
  cursor: pointer;
  color: #2563eb;
  font-size: 13px;
  font-weight: 500;
  user-select: none;
}

.sql-wrap pre,
.sql-pre {
  margin: 10px 0 0;
  max-height: min(28vh, 260px);
  overflow: auto;
  padding: 10px 12px;
  border-radius: 8px;
  background: #0f172a;
  color: #cbd5e1;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>

<style>
.chart-preview-dlg .el-dialog__body {
  padding: 12px 16px 20px;
  max-height: calc(94vh - 120px);
  overflow-y: auto;
  box-sizing: border-box;
}

.chart-preview-dlg.chart-preview-dlg--sql .el-dialog__body {
  max-height: calc(97vh - 88px);
}

.chart-preview-dlg.chart-preview-dlg--sql .dash-chart-grid {
  --plot: clamp(260px, calc((100vw - 140px) / var(--preview-cols, 1)), 600px);
}

.chart-preview-dlg.chart-preview-dlg--sql .chart-box {
  --s: min(var(--plot), calc((min(48vh, 480px)) * 360 / 260)) !important;
  width: min(100%, var(--s)) !important;
  aspect-ratio: 360 / 260 !important;
  height: auto !important;
  min-height: 0 !important;
  max-height: none !important;
}

.chart-preview-dlg.chart-preview-dlg--sql .sql-wrap pre,
.chart-preview-dlg.chart-preview-dlg--sql .sql-pre {
  max-height: min(58vh, 720px) !important;
  min-height: 140px;
}

.widget-list-dlg .el-dialog__body {
  padding: 12px 16px 20px;
  max-height: calc(94vh - 120px);
  overflow-y: auto;
  box-sizing: border-box;
}
</style>
