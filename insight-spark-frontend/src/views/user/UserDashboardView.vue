<template>
  <section class="stack-c-user-dashboard">
    <div v-if="sharePreviewMode" class="share-canvas-full">
      <DashboardBoardViewer
        embedded
        :prefetch="sharePrefetch"
        :api-base="API_BASE"
        :show-embed-lead="false"
      />
    </div>

    <div v-else-if="shareTokenMode" class="share-preview">
      <el-result
        icon="warning"
        title="分享链接不可用"
        :sub-title="sharePreviewError || '链接无效、已过期或看板已下线'"
      />
    </div>

    <template v-else>
      <UserDashboardWorkspace
        ref="workspaceRef"
        :api-base="API_BASE"
        @preview="openPreview"
        @view-charts="openChartList"
        @view-widgets="openWidgetList"
        @share="openShareDialog"
      />

      <DashboardBoardViewer
        v-model="boardViewerVisible"
        :initial-row="boardViewerRow"
        :api-base="API_BASE"
        :show-lead="false"
        :show-header="false"
      />

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
            <div v-else-if="isMetricPreviewCard(card)" class="chart-box chart-box--metric">
              <div class="preview-metric-value">
                <span>{{ metricDisplayForCard(card).value }}</span>
                <small v-if="metricDisplayForCard(card).unit">{{ metricDisplayForCard(card).unit }}</small>
              </div>
              <div class="preview-metric-label">{{ metricDisplayForCard(card).label }}</div>
              <div v-if="metricDisplayForCard(card).compareValue" class="preview-metric-compare">
                {{ metricDisplayForCard(card).compareLabel }} {{ metricDisplayForCard(card).compareValue }}
              </div>
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

      <el-dialog v-model="shareVisible" title="分享看板" width="520px" destroy-on-close @closed="onShareDialogClosed">
        <p class="portal-hint">
          设置分享有效期后点击「生成链接」；不填过期时间则为永久有效。链接生成后请自行复制分享，重新生成后原链接立即失效。
        </p>
        <el-form label-position="top">
          <el-form-item label="过期时间（可选）">
            <el-date-picker
              v-model="shareExpireAt"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="不填则长期有效"
              clearable
              style="width: 100%;"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="sharing" @click="generateShareLink">生成链接</el-button>
          </el-form-item>
          <el-form-item v-if="shareLinkText" label="分享链接">
            <el-input v-model="shareLinkText" readonly>
              <template #append>
                <el-button @click="copyShareLinkText">复制链接</el-button>
              </template>
            </el-input>
          </el-form-item>
          <el-divider />
          <el-form-item label="团队协同">
            <p class="share-team-hint">将看板授权给团队，成员可在「业务批注与协同 → 我收到的看板」中阅览与批注。</p>
            <div class="share-team-actions">
              <el-button type="success" plain @click="goShareToTeam">选择团队并分发</el-button>
              <el-button type="primary" plain @click="goCollabWorkbench">进入协同批注</el-button>
            </div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="shareVisible = false">取消</el-button>
        </template>
      </el-dialog>
    </template>
  </section>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'
import UserDashboardWorkspace from '../../components/dashboard/UserDashboardWorkspace.vue'
import DashboardBoardViewer from '../../components/dashboard/DashboardBoardViewer.vue'
import {
  buildUnifiedPreviewCards,
  buildBasicWidgetPreviewCards,
  extractLegacyChartCards,
  dashChartPreviewGridClass,
  previewChartDialogWidth
} from '../../utils/dashboardGrid.js'
import { resolveBasicWidgetEntry } from '../../utils/dashboardBasicWidgetRegistry.js'
import { buildOptionFromHistoryRow, normalizeChartType } from '../../utils/chartOptionFromSnapshot.js'
import { snapshotMetricDisplay } from '../../utils/dashboardChartSnapshotView.js'
import { setCollabNav } from '../../utils/collabNav.js'

const API_BASE = 'http://localhost:8080'
const workbench = inject('workbench', null)
const diagnosisLoading = computed(() => Boolean(workbench?.diagnosisLoading?.value))

const workspaceRef = ref(null)
const rows = computed(() => workspaceRef.value?.rows?.value || workspaceRef.value?.rows || [])
const boardViewerVisible = ref(false)
const boardViewerRow = ref(null)
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
const widgetListDialogWidth = computed(() =>
  previewChartDialogWidth(widgetListCards.value.length)
)
const shareVisible = ref(false)
const sharing = ref(false)
const shareTargetId = ref(null)
const shareExpireAt = ref('')
const shareLinkText = ref('')
const sharePreviewMode = ref(false)
const sharePreview = ref(null)
const sharePrefetch = ref(null)
const sharePreviewError = ref('')
const shareTokenMode = ref(false)

const shareChartRefs = new Map()
const chartListChartRefs = new Map()
const chartInstances = new Map()

function chartRefMapForScope(scope) {
  if (scope === 'share') return shareChartRefs
  if (scope === 'chart-list') return chartListChartRefs
  return null
}

const parseShareTokenFromUrl = () => {
  try {
    const url = new URL(window.location.href)
    return String(url.searchParams.get('shareToken') || '').trim()
  } catch {
    return ''
  }
}

const formatDateTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString('zh-CN', { hour12: false })
}

const formatLayoutJson = (layoutJson) => {
  const text = String(layoutJson || '').trim()
  if (!text) return '{}'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

const parseLayout = (layoutJson) => {
  const text = String(layoutJson || '').trim()
  if (!text) return {}
  try {
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

const chartTypeLabel = (type) => {
  const normalized = normalizeChartType(type)
  if (normalized === 'line') return '折线图'
  if (normalized === 'pie') return '饼图'
  if (normalized === 'radar') return '雷达图'
  if (normalized === 'scatter') return '散点图'
  if (normalized === 'metric') return '指标卡'
  if (normalized === 'map') return '地图'
  if (normalized === 'table') return '表格'
  return '柱状图'
}

const isMetricPreviewCard = (card) => normalizeChartType(card?.chartType) === 'metric'

const metricDisplayForCard = (card) => {
  if (card?.payloadRow) return snapshotMetricDisplay(card.payloadRow)
  return snapshotMetricDisplay({
    chartType: 'metric',
    chartSnapshot: {
      chartType: 'metric',
      message: card?.title || '',
      fieldMapping: { metric: card?.title || '' },
      data: Array.isArray(card?.data) ? card.data : []
    }
  })
}

const toNumber = (value) => {
  const n = Number(value)
  return Number.isFinite(n) ? n : Number.NaN
}

const normalizeChartItem = (item) => {
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

const buildChartOption = (card) => {
  const chartType = normalizeChartType(card?.chartType)
  if (chartType === 'metric') return null
  if (['radar', 'scatter', 'map'].includes(chartType)) {
    const shared = buildOptionFromHistoryRow({
      chartType,
      chartSnapshot: {
        chartType,
        message: card?.title || '',
        fieldMapping: { metric: card?.title || '' },
        data: Array.isArray(card?.data) ? card.data : []
      }
    }, card?.chartUi || {})
    if (shared) return shared
  }
  const points = Array.isArray(card.data) ? card.data.map(normalizeChartItem) : []
  if (chartType === 'pie') {
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
  const numericMax = Math.max(...seriesData, 0)
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
      axisLabel: {
        interval: 0,
        rotate: xAxisData.length > 8 ? 40 : 22,
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
      min: 0,
      max: numericMax === 0 ? 1 : undefined,
      splitLine: { lineStyle: { color: '#eef2f7' } },
      axisLabel: { fontSize: 11 }
    },
    series: [
      {
        type: chartType,
        smooth: chartType === 'line',
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

const onChartListSqlToggle = (renderKey, evt) => {
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

const setChartRef = (scope, key) => (el) => {
  const map = chartRefMapForScope(scope)
  if (!map) return
  if (el) {
    map.set(key, el)
  } else {
    map.delete(key)
  }
}

const disposeScopeCharts = (scope) => {
  const map = chartRefMapForScope(scope)
  if (!map) return
  const prefix = `${scope}:`
  for (const [key, instance] of chartInstances.entries()) {
    if (!key.startsWith(prefix)) continue
    try {
      instance.dispose()
    } catch (error) {
      // ignore
    }
    chartInstances.delete(key)
  }
  map.clear()
}

const renderScopeCharts = async (scope, cards) => {
  const refs = chartRefMapForScope(scope)
  if (!refs) return
  await nextTick()
  const alive = new Set()

  cards.forEach((card) => {
    if (card._previewKind === 'unavailable') return
    const renderKey = String(card._renderKey || card.cardId || '')
    if (!renderKey) return
    const fullKey = `${scope}:${renderKey}`
    if (isMetricPreviewCard(card)) {
      const existing = chartInstances.get(fullKey)
      if (existing) {
        try {
          existing.dispose()
        } catch (error) {
          // ignore
        }
        chartInstances.delete(fullKey)
      }
      return
    }
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
      } catch (error) {
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
    if (!option) return
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
    } catch (error) {
      // ignore
    }
    chartInstances.delete(key)
  }
}

const generateDiagnosisFromCard = async (card) => {
  if (!workbench?.diagnoseFromDashboardCard) {
    ElMessage.warning('诊断报告模块尚未就绪')
    return
  }
  const key = `chart-list:${card?._renderKey || ''}`
  const instance = chartInstances.get(key)
  const imageDataUrl = instance?.getDataURL
    ? instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
    : ''
  await workbench.diagnoseFromDashboardCard({
    ...card,
    dashboardId: chartListContext.value?.id || null,
    dashboardName: chartListContext.value?.name || '',
    cardTitle: card?.title || ''
  }, imageDataUrl)
}

const restoreDiagnosisDashboardTarget = async (target) => {
  if (!target || target.route !== 'dashboard') return
  const dashboardId = Number(target.dashboardId || 0)
  const list = Array.isArray(rows.value) ? rows.value : []
  let row = dashboardId ? list.find(item => Number(item.id) === dashboardId) : null
  if (!row && target.dashboardName) {
    row = list.find(item => item.name === target.dashboardName)
  }
  if (!row && !list.length) {
    await workspaceRef.value?.reload?.()
    const refreshed = Array.isArray(rows.value) ? rows.value : []
    row = dashboardId ? refreshed.find(item => Number(item.id) === dashboardId) : null
  }
  if (!row) {
    ElMessage.warning('未找到诊断报告绑定的看板，已停留在我的看板列表')
    return
  }
  openPreview(row)
  ElMessage.success(target.cardTitle ? `已回溯到看板：${target.cardTitle}` : '已回溯到诊断报告绑定的看板')
}

const handleWindowResize = () => {
  for (const instance of chartInstances.values()) {
    instance?.resize?.()
  }
}

const openPreview = async (row) => {
  if (!row?.id) {
    ElMessage.warning('看板信息不完整，无法预览')
    return
  }
  restoreSessionHeader()
  try {
    await axios.post(`${API_BASE}/api/c/dashboards/${row.id}/record-view`)
    row.viewCount = Number(row.viewCount || 0) + 1
  } catch {
    // ignore
  }
  boardViewerRow.value = { ...row }
  boardViewerVisible.value = true
}

function tryConsumePendingDashboard() {
  const pending = workbench?.dashboardPendingOpen?.value
  if (!pending?.id) return
  openPreview(pending)
  if (workbench?.dashboardPendingOpen) {
    workbench.dashboardPendingOpen.value = null
  }
}

watch(
  () => workbench?.activeModule?.value,
  (mod) => {
    if (mod === 'dashboard') {
      nextTick(() => tryConsumePendingDashboard())
    }
  }
)

function basicWidgetComponent(widgetKind) {
  return resolveBasicWidgetEntry(widgetKind)?.widget || null
}

const openWidgetList = async (row) => {
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
      const dashRes = await axios.get(`${API_BASE}/api/c/dashboards/${id}`)
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

const openChartList = async (row) => {
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
      axios.get(`${API_BASE}/api/c/dashboards/${id}`),
      axios.get(`${API_BASE}/api/c/dashboards/${id}/components`)
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
      const batchRes = await axios.post(`${API_BASE}/api/chat/history/charts-batch`, {
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

const reloadWorkspace = async () => {
  await workspaceRef.value?.reload?.()
}

const onShareDialogClosed = () => {
  shareLinkText.value = ''
  shareTargetId.value = null
}

const openShareDialog = (row) => {
  shareTargetId.value = row.id
  shareExpireAt.value = row.shareExpireAt ? String(row.shareExpireAt).replace('T', ' ').slice(0, 19) : ''
  shareLinkText.value = ''
  shareVisible.value = true
}

const goShareToTeam = () => {
  const id = shareTargetId.value
  if (!id) return
  shareVisible.value = false
  setCollabNav({ tab: 'distribute', dashboardId: id })
  if (workbench?.activeModule) {
    workbench.activeModule.value = 'collaboration'
  }
}

const goCollabWorkbench = () => {
  const id = shareTargetId.value
  if (!id) return
  shareVisible.value = false
  setCollabNav({ tab: 'workbench', dashboardId: id })
  if (workbench?.activeModule) {
    workbench.activeModule.value = 'collaboration'
  }
}

const generateShareLink = async () => {
  if (!shareTargetId.value) return
  sharing.value = true
  restoreSessionHeader()
  try {
    const body = shareExpireAt.value ? { expireAt: shareExpireAt.value } : {}
    const res = await axios.post(`${API_BASE}/api/c/dashboards/${shareTargetId.value}/share/enable`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    shareLinkText.value = buildShareLink(res.data.data)
    ElMessage.success('链接已生成，原链接已失效，请复制下方链接')
    await reloadWorkspace()
  } catch (e) {
    ElMessage.error(e.message || '生成链接失败')
  } finally {
    sharing.value = false
  }
}

const copyShareLinkText = async () => {
  const link = String(shareLinkText.value || '').trim()
  if (!link) {
    ElMessage.warning('请先生成分享链接')
    return
  }
  try {
    await navigator.clipboard.writeText(link)
    ElMessage.success('分享链接已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

const disableShare = async (row) => {
  try {
    await ElMessageBox.confirm(`确定关闭看板「${row.name}」的分享链接？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.post(`${API_BASE}/api/c/dashboards/${row.id}/share/disable`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已关闭分享')
    await reloadWorkspace()
  } catch (e) {
    ElMessage.error(e.message || '关闭分享失败')
  }
}

const buildShareLink = (row) => {
  const token = String(row?.shareToken || '').trim()
  if (!token) return ''
  const url = new URL(window.location.href)
  url.searchParams.set('shareToken', token)
  return url.toString()
}

const copyShareLink = async (row) => {
  const link = buildShareLink(row)
  if (!link) {
    ElMessage.warning('当前看板尚未开启分享')
    return
  }
  try {
    await navigator.clipboard.writeText(link)
    ElMessage.success('分享链接已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

function buildSharePrefetch(payload) {
  if (!payload) return null
  const components = Array.isArray(payload.components) ? payload.components : []
  const chartPayloadById =
    payload.chartPayloadById && typeof payload.chartPayloadById === 'object'
      ? { ...payload.chartPayloadById }
      : {}
  const board = { ...payload }
  delete board.components
  delete board.chartPayloadById
  return { board, components, chartPayloadById }
}

const loadSharePreview = async (token) => {
  shareTokenMode.value = true
  const loading = ElLoading.service({ text: '加载分享看板中...' })
  try {
    const res = await axios.get(`${API_BASE}/api/c/dashboards/share`, { params: { token } })
    if (res.data.code !== 200) throw new Error(res.data.message)
    sharePreview.value = res.data.data
    sharePrefetch.value = buildSharePrefetch(res.data.data)
    sharePreviewMode.value = true
    sharePreviewError.value = ''
  } catch (e) {
    const message = e.message || '分享链接不可用'
    ElMessage.error(message)
    sharePreviewError.value = message
    sharePreviewMode.value = false
  } finally {
    loading.close()
  }
}

watch(chartListVisible, async (visible) => {
  if (visible) {
    chartListSqlOpenKey.value = null
    await nextTick()
    await nextTick()
    await renderScopeCharts('chart-list', chartListCards.value)
  } else {
    chartListSqlOpenKey.value = null
    disposeScopeCharts('chart-list')
  }
})

watch(
  () => workbench?.diagnosisRestoreTarget?.value,
  async (target) => {
    await restoreDiagnosisDashboardTarget(target)
  },
  { deep: true }
)

onMounted(async () => {
  window.addEventListener('resize', handleWindowResize)
  const shareToken = parseShareTokenFromUrl()
  if (shareToken) {
    await loadSharePreview(shareToken)
    return
  }
  await restoreDiagnosisDashboardTarget(workbench?.diagnosisRestoreTarget?.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleWindowResize)
  disposeScopeCharts('chart-list')
})
</script>

<style scoped>
.stack-c-user-dashboard {
  flex: 1;
  min-height: 0;
  height: 100%;
  box-sizing: border-box;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.stack-c-user-dashboard :deep(.adm-dashboard) {
  flex: 1;
  min-height: 0;
}

.portal-hint {
  color: #909399;
  font-size: 13px;
  margin: 0 0 12px;
}

.share-team-hint {
  margin: 0 0 10px;
  font-size: 13px;
  color: #909399;
  line-height: 1.5;
}

.share-team-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}

.share-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.share-expire {
  color: #909399;
  font-size: 12px;
}

.share-preview {
  max-width: 1100px;
  margin: 0 auto;
}

.share-canvas-full {
  flex: 1;
  min-height: 0;
  height: 100vh;
  width: 100%;
  overflow: auto;
  box-sizing: border-box;
}

.preview-section-head {
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dash-chart-grid {
  --preview-cols: 1;
  /* 随视口与列数放大，上限约 720px，避免单图永远只有 360px 过小 */
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

.dash-widget-grid.dash-chart-grid--single .widget-card {
  max-width: min(100%, 560px);
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

.widget-card-preview :deep(.dbw-root) {
  width: 100%;
  height: 100%;
}

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

/*
 * 自适应：在列数与视口下放大 --plot（见 .dash-chart-grid），再用 --s 同时受视口高度限制，
 * 保持 360:260 比例（饼图+图例用同一矩形画布，由 ECharts 布局，不横向拉扁圆环）。
 */
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

.chart-box--metric {
  min-height: 220px;
  padding: 18px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
}

.preview-metric-value {
  display: inline-flex;
  align-items: flex-end;
  justify-content: center;
  gap: 6px;
  color: #0f172a;
  font-size: 40px;
  line-height: 1.1;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  word-break: break-word;
}

.preview-metric-value small {
  padding-bottom: 3px;
  color: #64748b;
  font-size: 15px;
  font-weight: 700;
}

.preview-metric-label,
.preview-metric-compare {
  color: #64748b;
  font-size: 13px;
  line-height: 1.4;
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
  list-style-position: outside;
}

.sql-wrap summary:hover {
  color: #1d4ed8;
}

.sql-wrap pre,
.sql-pre {
  margin: 10px 0 0 0;
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

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 380px), 1fr));
  gap: 16px;
}

.preview-json {
  background: #0f172a;
  color: #cbd5e1;
  padding: 12px;
  border-radius: 8px;
  max-height: 420px;
  overflow: auto;
}

@media (max-width: 900px) {
  .card-grid {
    grid-template-columns: 1fr;
  }

  .dash-chart-grid--double,
  .dash-chart-grid--multi {
    grid-template-columns: auto;
    justify-items: center;
    --preview-cols: 1;
  }
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
