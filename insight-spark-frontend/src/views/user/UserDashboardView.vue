<template>
  <section class="stack-c-user-dashboard">
    <div v-if="sharePreviewMode" class="share-preview">
      <h3>{{ sharePreview?.name || '分享看板' }}</h3>
      <p class="portal-hint">当前为分享预览模式（免登录链接访问）。</p>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="名称">{{ sharePreview?.name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="描述">{{ sharePreview?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ sharePreview?.status || '-' }}</el-descriptions-item>
        <el-descriptions-item label="到期时间">{{ formatDateTime(sharePreview?.shareExpireAt) || '长期有效' }}</el-descriptions-item>
      </el-descriptions>

      <el-divider />
      <div class="preview-section-head">
        <span>看板图表</span>
        <el-tag size="small" type="info">{{ shareCards.length }} 张</el-tag>
      </div>
      <div v-if="shareCards.length" class="card-grid">
        <article
          v-for="(card, index) in shareCards"
          :key="card._renderKey"
          class="chart-card"
        >
          <div class="chart-card-head">
            <h4>{{ card.title || `图表${index + 1}` }}</h4>
            <el-tag size="small">{{ chartTypeLabel(card.chartType) }}</el-tag>
          </div>
          <div class="chart-sub">{{ card.tableName || '未指定数据表' }}</div>
          <div :ref="setChartRef('share', card._renderKey)" class="chart-box" />
        </article>
      </div>
      <el-empty v-else description="分享看板暂无图表卡片" />

      <el-divider />
      <el-collapse>
        <el-collapse-item title="布局 JSON 预览" name="layout-json">
          <pre class="preview-json">{{ formatLayoutJson(sharePreview?.layoutJson) }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div v-else-if="shareTokenMode" class="share-preview">
      <el-result
        icon="warning"
        title="分享链接不可用"
        :sub-title="sharePreviewError || '链接无效、已过期或看板已下线'"
      />
    </div>

    <template v-else>
      <p class="portal-hint">
        用户端 · 我的看板（独立页面）。
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

      <el-dialog v-model="previewVisible" :title="previewTitle" width="90%" top="4vh" destroy-on-close>
        <div class="preview-section-head">
          <span>看板图表</span>
          <el-tag size="small" type="info">{{ previewCards.length }} 张</el-tag>
        </div>
        <div v-if="previewCards.length" class="card-grid">
          <article
            v-for="(card, index) in previewCards"
            :key="card._renderKey"
            class="chart-card"
          >
            <div class="chart-card-head">
              <h4>{{ card.title || `图表${index + 1}` }}</h4>
              <el-tag size="small">{{ chartTypeLabel(card.chartType) }}</el-tag>
            </div>
            <div class="chart-sub">{{ card.tableName || '未指定数据表' }}</div>
            <div :ref="setChartRef('preview', card._renderKey)" class="chart-box" />
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
    </template>
  </section>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const rows = ref([])
const loadingList = ref(false)
const previewVisible = ref(false)
const previewTitle = ref('看板图表预览')
const previewCards = ref([])
const shareCards = ref([])

const editVisible = ref(false)
const saving = ref(false)
const editId = ref(null)
const form = reactive({ name: '', description: '', layoutJson: '{}', isPublic: false })

const shareVisible = ref(false)
const sharing = ref(false)
const shareTargetId = ref(null)
const shareExpireAt = ref('')
const sharePreviewMode = ref(false)
const sharePreview = ref(null)
const sharePreviewError = ref('')
const shareTokenMode = ref(false)

const previewChartRefs = new Map()
const shareChartRefs = new Map()
const chartInstances = new Map()

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

const extractChartCards = (layoutJson, scope = 'row') => {
  const layout = parseLayout(layoutJson)
  const cards = Array.isArray(layout.cards) ? layout.cards : []
  return cards
    .map((raw, index) => {
      if (!raw || typeof raw !== 'object') return null
      const cardType = String(raw.type || 'chart').toLowerCase()
      const data = Array.isArray(raw.data) ? raw.data : []
      if (cardType !== 'chart' || !data.length) return null
      const id = String(raw.cardId || `${scope}-${index}`)
      return {
        _renderKey: `${scope}-${id}-${index}`,
        cardId: id,
        title: String(raw.title || `图表${index + 1}`),
        chartType: normalizeChartType(raw.chartType),
        tableName: String(raw.tableName || ''),
        sql: String(raw.sql || ''),
        data
      }
    })
    .filter(Boolean)
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

const setChartRef = (scope, key) => (el) => {
  const map = scope === 'share' ? shareChartRefs : previewChartRefs
  if (el) {
    map.set(key, el)
  } else {
    map.delete(key)
  }
}

const disposeScopeCharts = (scope) => {
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
  if (scope === 'share') {
    shareChartRefs.clear()
  } else {
    previewChartRefs.clear()
  }
}

const renderScopeCharts = async (scope, cards) => {
  await nextTick()
  const refs = scope === 'share' ? shareChartRefs : previewChartRefs
  const alive = new Set()

  cards.forEach((card) => {
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

    instance.setOption(buildChartOption(card), true)
    instance.resize()
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

const handleWindowResize = () => {
  for (const instance of chartInstances.values()) {
    instance?.resize?.()
  }
}

const openPreview = async (row) => {
  previewTitle.value = row?.name ? `${row.name} - 图表预览` : '看板图表预览'
  previewCards.value = extractChartCards(row?.layoutJson, `preview-${row?.id || 'temp'}`)
  previewVisible.value = true
  await renderScopeCharts('preview', previewCards.value)
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
      chartCardCount: extractChartCards(row?.layoutJson, `count-${row?.id || ''}`).length
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

const loadSharePreview = async (token) => {
  shareTokenMode.value = true
  const loading = ElLoading.service({ text: '加载分享看板中...' })
  try {
    const res = await axios.get(`${API_BASE}/api/c/dashboards/share`, { params: { token } })
    if (res.data.code !== 200) throw new Error(res.data.message)
    sharePreview.value = res.data.data
    shareCards.value = extractChartCards(sharePreview.value?.layoutJson, `share-${sharePreview.value?.id || 'board'}`)
    sharePreviewMode.value = true
    sharePreviewError.value = ''
    await renderScopeCharts('share', shareCards.value)
  } catch (e) {
    const message = e.message || '分享链接不可用'
    ElMessage.error(message)
    sharePreviewError.value = message
    sharePreviewMode.value = false
  } finally {
    loading.close()
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
    await renderScopeCharts('preview', previewCards.value)
  } else {
    disposeScopeCharts('preview')
  }
})

watch(shareCards, async (cards) => {
  if (!sharePreviewMode.value) return
  await renderScopeCharts('share', cards)
}, { deep: true })

onMounted(async () => {
  window.addEventListener('resize', handleWindowResize)
  const shareToken = parseShareTokenFromUrl()
  if (shareToken) {
    await loadSharePreview(shareToken)
    return
  }
  await loadList()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleWindowResize)
  disposeScopeCharts('preview')
  disposeScopeCharts('share')
})
</script>

<style scoped>
.stack-c-user-dashboard {
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

.share-preview {
  max-width: 1100px;
  margin: 0 auto;
}

.preview-section-head {
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 12px;
}

.chart-card {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 10px;
  background: #fff;
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

.chart-box {
  width: 100%;
  height: 300px;
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
}
</style>
