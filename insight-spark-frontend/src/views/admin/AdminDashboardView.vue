<template>
  <section class="stack-c-admin-dashboard">
    <p class="portal-hint">
      管理员端 · 看板管理（独立页面）。
    </p>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建看板</el-button>
      <el-button @click="loadList">刷新列表</el-button>
    </div>

    <el-table :data="rows" border v-loading="loadingList" empty-text="暂无看板">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="图表卡片" width="96">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.chartCardCount || 0 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="公开" width="80">
        <template #default="{ row }">{{ row.isPublic ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column prop="ownerUserId" label="所有者" width="120" />
      <el-table-column label="分享状态" min-width="220">
        <template #default="{ row }">
          <div class="share-cell">
            <el-tag :type="row.shareToken ? 'success' : 'info'" size="small">
              {{ row.shareToken ? '已分享' : '未分享' }}
            </el-tag>
            <span v-if="row.shareToken" class="share-expire">
              {{ formatDateTime(row.shareExpireAt) || '长期有效' }}
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="420" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPreview(row)">查看图表</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="!row.shareToken" link type="success" @click="openShareDialog(row)">开启分享</el-button>
          <el-button v-else link type="warning" @click="copyShareLink(row)">复制链接</el-button>
          <el-button v-if="row.shareToken" link type="danger" @click="disableShare(row)">关闭分享</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="previewVisible" :title="previewTitle" :width="previewDialogWidth" top="4vh" destroy-on-close>
      <div class="preview-section-head">
        <span>看板图表</span>
        <el-tag size="small" type="info">{{ previewCards.length }} 张</el-tag>
      </div>
      <div
        v-if="previewCards.length"
        :class="['dash-chart-grid', dashChartPreviewGridClass(previewCards.length)]"
      >
        <article
          v-for="(card, index) in previewCards"
          :key="card._renderKey"
          :class="['chart-card', { 'chart-card--pie': normalizeChartType(card.chartType) === 'pie' }]"
        >
          <div class="chart-card-head">
            <h4>{{ card.title || `图表${index + 1}` }}</h4>
            <el-tag size="small">{{ chartTypeLabel(card.chartType) }}</el-tag>
          </div>
          <div class="chart-sub">{{ card.tableName || '未指定数据表' }}</div>
          <div v-if="card.sourceMeta" class="chart-source">
            <el-tag size="small" effect="plain">{{ card.sourceMeta.sourceLabel }}</el-tag>
            <el-tag v-if="card.sourceMeta.snapshotLabel" size="small" type="info" effect="plain">{{ card.sourceMeta.snapshotLabel }}</el-tag>
            <span v-if="card.sourceMeta.detail">{{ card.sourceMeta.detail }}</span>
          </div>
          <div :ref="setChartRef(card._renderKey)" class="chart-box" />
          <details v-if="card.sql" class="sql-wrap">
            <summary>查看 SQL</summary>
            <pre>{{ card.sql }}</pre>
          </details>
        </article>
      </div>
      <el-empty v-else description="当前看板暂无可渲染图表卡片" />
    </el-dialog>

    <el-dialog v-model="editVisible" :title="editId ? '编辑看板' : '新建看板'" width="640px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="看板名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="是否公开看板">
          <el-switch v-model="form.isPublic" />
        </el-form-item>
        <el-form-item label="布局 JSON（画布与图表配置）">
          <el-input v-model="form.layoutJson" type="textarea" :rows="10" placeholder="{}" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shareVisible" title="开启分享链接" width="480px" destroy-on-close>
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
      </el-form>
      <template #footer>
        <el-button @click="shareVisible = false">取消</el-button>
        <el-button type="primary" :loading="sharing" @click="enableShare">确认开启</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'
import {
  buildUnifiedPreviewCards,
  extractLegacyChartCards,
  countChartSlotsForDashboardRow,
  dashChartPreviewGridClass,
  previewChartDialogWidth
} from '../../utils/dashboardGrid.js'
import { buildOptionFromHistoryRow } from '../../utils/chartOptionFromSnapshot.js'

const API_BASE = 'http://localhost:8080'

const rows = ref([])
const loadingList = ref(false)
const previewVisible = ref(false)
const previewTitle = ref('看板图表预览')
const previewCards = ref([])

const previewDialogWidth = computed(() => previewChartDialogWidth(previewCards.value.length))

const editVisible = ref(false)
const saving = ref(false)
const editId = ref(null)
const form = reactive({ name: '', description: '', layoutJson: '{}', isPublic: false })

const shareVisible = ref(false)
const sharing = ref(false)
const shareTargetId = ref(null)
const shareExpireAt = ref('')

const previewChartRefs = new Map()
const chartInstances = new Map()

const formatDateTime = (value) => {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString('zh-CN', { hour12: false })
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

const normalizeChartType = (value) => {
  const type = String(value || '').toLowerCase()
  if (type === 'line' || type === 'pie') return type
  return 'bar'
}

const chartTypeLabel = (type) => {
  if (type === 'line') return '折线图'
  if (type === 'pie') return '饼图'
  return '柱状图'
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
  const nameKey = keys.find((key) => ['name', 'label', 'province', 'city', 'category', 'dimension', 'dim_name'].includes(key)) || keys[0]
  const valueKey = keys.find((key) => ['value', 'count', 'amount', 'total', 'sales', 'metric', 'metric_value'].includes(key)) || keys[1] || keys[0]
  const nameValue = item.name ?? item.label ?? item.dim_name ?? item[nameKey] ?? ''
  const rawValue = item.value ?? item.metric_value ?? item[valueKey] ?? 0
  const numericValue = toNumber(rawValue)
  return {
    name: String(nameValue ?? ''),
    value: Number.isNaN(numericValue) ? 0 : numericValue
  }
}

const buildChartOption = (card) => {
  const points = Array.isArray(card.data) ? card.data.map(normalizeChartItem) : []
  if (card.chartType === 'pie') {
    return {
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [{
        type: 'pie',
        radius: ['40%', '66%'],
        data: points
      }]
    }
  }

  const xAxisData = points.map((item) => item.name)
  const seriesData = points.map((item) => Number(item.value ?? 0))
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' }
    },
    grid: { left: 56, right: 16, top: 20, bottom: 70 },
    xAxis: {
      type: 'category',
      data: xAxisData,
      axisLabel: {
        interval: 0,
        rotate: xAxisData.length > 8 ? 35 : 20,
        hideOverlap: true,
        formatter: (value) => {
          const text = String(value ?? '')
          return text.length > 10 ? `${text.slice(0, 10)}...` : text
        }
      }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#eef2f7' } }
    },
    series: [{
      type: card.chartType,
      smooth: card.chartType === 'line',
      data: seriesData,
      barMaxWidth: 28
    }]
  }
}

const setChartRef = (key) => (el) => {
  if (el) {
    previewChartRefs.set(key, el)
  } else {
    previewChartRefs.delete(key)
  }
}

const disposePreviewCharts = () => {
  for (const instance of chartInstances.values()) {
    try {
      instance.dispose()
    } catch {
      // ignore
    }
  }
  chartInstances.clear()
  previewChartRefs.clear()
}

const renderPreviewCharts = async (cards) => {
  await nextTick()
  const alive = new Set()

  cards.forEach((card) => {
    const key = String(card._renderKey || card.cardId || '')
    if (!key) return
    alive.add(key)
    const container = previewChartRefs.get(key)
    if (!container) return

    let instance = chartInstances.get(key)
    if (instance?.isDisposed?.()) {
      chartInstances.delete(key)
      instance = null
    }
    if (instance && instance.getDom?.() !== container) {
      try {
        instance.dispose()
      } catch {
        // ignore
      }
      chartInstances.delete(key)
      instance = null
    }
    if (!instance) {
      instance = echarts.getInstanceByDom(container) || echarts.init(container)
      chartInstances.set(key, instance)
    }

    const option =
      card.payloadRow != null
        ? buildOptionFromHistoryRow(card.payloadRow, card.chartUi || {})
        : buildChartOption(card)
    instance.setOption(option, true)
    instance.resize()
  })

  for (const [key, instance] of chartInstances.entries()) {
    if (alive.has(key)) continue
    try {
      instance.dispose()
    } catch {
      // ignore
    }
    chartInstances.delete(key)
  }
}

const handleWindowResize = () => {
  for (const instance of chartInstances.values()) {
    instance?.resize?.()
  }
}

const openPreview = async (row) => {
  previewTitle.value = row?.name ? `${row.name} - 图表预览` : '看板图表预览'
  const id = row?.id
  const scope = `preview-${id || 'temp'}`
  if (!id) {
    previewCards.value = extractLegacyChartCards(row?.layoutJson, scope)
    previewVisible.value = true
    await renderPreviewCharts(previewCards.value)
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
          .filter((n) => Number.isFinite(n))
      )
    ]
    const payloadMap = {}
    if (chartIds.length) {
      const batchRes = await axios.post(`${API_BASE}/api/chat/history/charts-batch`, { ids: chartIds })
      if (batchRes.data.code !== 200) throw new Error(batchRes.data.message || '加载图表数据失败')
      const rows = Array.isArray(batchRes.data.data) ? batchRes.data.data : []
      for (const r of rows) {
        const hid = r.id != null ? String(r.id) : ''
        if (hid) payloadMap[hid] = r
      }
    }
    previewCards.value = buildUnifiedPreviewCards(layoutJson, components, payloadMap, scope)
    previewVisible.value = true
    await renderPreviewCharts(previewCards.value)
  } catch (e) {
    ElMessage.error(e.message || '加载预览失败')
    previewCards.value = extractLegacyChartCards(row?.layoutJson, scope)
    previewVisible.value = true
    await renderPreviewCharts(previewCards.value)
  }
}

const loadList = async () => {
  loadingList.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/dashboards`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    const list = Array.isArray(res.data.data) ? res.data.data : []
    rows.value = list.map((row) => ({
      ...row,
      chartCardCount: countChartSlotsForDashboardRow(row?.layoutJson)
    }))
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingList.value = false
  }
}

const openShareDialog = (row) => {
  shareTargetId.value = row.id
  shareExpireAt.value = row.shareExpireAt ? String(row.shareExpireAt).replace('T', ' ').slice(0, 19) : ''
  shareVisible.value = true
}

const enableShare = async () => {
  if (!shareTargetId.value) return
  sharing.value = true
  restoreSessionHeader()
  try {
    const body = shareExpireAt.value ? { expireAt: shareExpireAt.value } : {}
    const res = await axios.post(`${API_BASE}/api/c/dashboards/${shareTargetId.value}/share/enable`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已开启分享')
    shareVisible.value = false
    await loadList()
    copyShareLink(res.data.data)
  } catch (e) {
    ElMessage.error(e.message || '开启分享失败')
  } finally {
    sharing.value = false
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
    await loadList()
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

const openCreate = () => {
  editId.value = null
  form.name = ''
  form.description = ''
  form.layoutJson = '{}'
  form.isPublic = false
  editVisible.value = true
}

const openEdit = (row) => {
  editId.value = row.id
  form.name = row.name
  form.description = row.description || ''
  form.layoutJson = row.layoutJson || '{}'
  form.isPublic = Boolean(row.isPublic)
  editVisible.value = true
}

const save = async () => {
  saving.value = true
  restoreSessionHeader()
  try {
    const body = {
      name: form.name,
      description: form.description || null,
      layoutJson: form.layoutJson || '{}',
      isPublic: form.isPublic
    }
    const res = editId.value
      ? await axios.put(`${API_BASE}/api/c/dashboards/${editId.value}`, body)
      : await axios.post(`${API_BASE}/api/c/dashboards`, body)

    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已保存')
    editVisible.value = false
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除看板「${row.name}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.delete(`${API_BASE}/api/c/dashboards/${row.id}`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

watch(previewVisible, async (visible) => {
  if (visible) {
    await renderPreviewCharts(previewCards.value)
  } else {
    disposePreviewCharts()
  }
})

onMounted(async () => {
  window.addEventListener('resize', handleWindowResize)
  await loadList()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleWindowResize)
  disposePreviewCharts()
})
</script>

<style scoped>
.stack-c-admin-dashboard {
  padding: 0 4px;
}

.portal-hint {
  color: #909399;
  font-size: 13px;
  margin: 0 0 12px;
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

.chart-card {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 10px;
  background: #fff;
  display: flex;
  flex-direction: column;
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
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.chart-card-head h4 {
  margin: 0;
  font-size: 14px;
  color: #111827;
}

.chart-sub {
  color: #6b7280;
  font-size: 12px;
  margin-bottom: 8px;
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

.sql-wrap {
  margin-top: 8px;
}

.sql-wrap summary {
  cursor: pointer;
  color: #374151;
  font-size: 12px;
}

.sql-wrap pre {
  margin: 8px 0 0 0;
  max-height: 140px;
  overflow: auto;
  padding: 8px;
  border-radius: 6px;
  background: #0f172a;
  color: #cbd5e1;
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 900px) {
  .dash-chart-grid--double,
  .dash-chart-grid--multi {
    grid-template-columns: auto;
    justify-items: center;
    --preview-cols: 1;
  }
}
</style>
