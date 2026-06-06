<template>
  <el-drawer
    :model-value="modelValue"
    title="图表库"
    size="72%"
    destroy-on-close
    class="dcl-drawer"
    @update:model-value="emit('update:modelValue', $event)"
    @opened="onOpened"
    @closed="onClosed"
  >
    <div class="dcl-root">
      <div class="dcl-toolbar">
        <el-input
          v-model.trim="keyword"
          class="dcl-toolbar__keyword"
          placeholder="搜索图表名称"
          clearable
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <div class="dcl-toolbar__actions">
          <el-button type="primary" @click="search">
            <el-icon><Search /></el-icon>
            <span>搜索</span>
          </el-button>
          <el-button plain @click="resetSearch">
            <el-icon><Refresh /></el-icon>
            <span>重置</span>
          </el-button>
        </div>
      </div>

      <div class="dcl-summary">
        <div class="dcl-summary__left">
          <el-checkbox
            :model-value="isAllPageSelected"
            :indeterminate="isPageIndeterminate"
            :disabled="!selectableOnPage.length || readonly"
            @change="toggleSelectAllPage"
          >
            全选本页
          </el-checkbox>
          <span>共 {{ total }} 条</span>
          <span v-if="selectedIds.size" class="dcl-summary__picked">已选 {{ selectedIds.size }} 项</span>
        </div>
        <el-button
          type="primary"
          :disabled="!selectedPinableIds.length || readonly || bulkPinning"
          :loading="bulkPinning"
          @click="pinSelected"
        >
          钉入看板{{ selectedPinableIds.length ? `（${selectedPinableIds.length}）` : '' }}
        </el-button>
      </div>

      <div class="dcl-content" v-loading="loading">
        <template v-if="entries.length">
          <div class="dcl-list">
            <div
              v-for="entry in entries"
              :key="entry.id"
              :class="['dcl-card', { 'is-active': String(selectedId) === String(entry.id) }]"
              @click="selectEntry(entry)"
            >
              <div class="dcl-card__check" @click.stop>
                <el-checkbox
                  :model-value="isEntryChecked(entry)"
                  :disabled="isCheckboxDisabled(entry)"
                  @change="(checked) => toggleSelect(entry, checked)"
                />
              </div>
              <div class="dcl-card__body">
                <div class="dcl-card__head">
                  <div class="dcl-card__title">{{ entry.question || '未命名查询' }}</div>
                  <el-tag size="small" effect="light" :type="riskTagType(entry.riskLevel)">
                    {{ entry.riskLevel || 'SAFE' }}
                  </el-tag>
                </div>
                <div class="dcl-card__meta">
                  <span>{{ entry.tableName || '未指定数据源' }}</span>
                  <span>{{ historyChartTypeLabel(entry.chartType) }}</span>
                  <span>{{ historyExecutionStatusLabel(entry) }}</span>
                  <span>{{ formatHistoryExecutionTime(entry.executionTimeMs) }}</span>
                  <span>{{ formatChatHistoryTime(entry.createdAt) }}</span>
                </div>
                <div class="dcl-card__tags">
                  <el-tag size="small" effect="light" :type="historyExecutionStatusType(entry)">
                    {{ historyExecutionStatusLabel(entry) }}
                  </el-tag>
                  <el-tag size="small" effect="light" type="info">
                    {{ entry.isHitCache ? '命中缓存' : '未命中缓存' }}
                  </el-tag>
                  <el-tag v-if="entry.isPinnedOnBoard" size="small" effect="light" type="warning">
                    已钉入本看板
                  </el-tag>
                </div>
              </div>
            </div>
          </div>

          <div class="dcl-detail">
            <template v-if="selectedEntry">
              <div class="dcl-detail__head">
                <div>
                  <div class="dcl-detail__title">{{ selectedEntry.question || '未命名查询' }}</div>
                  <div class="dcl-detail__submeta">
                    <span>{{ selectedEntry.tableName || '未指定数据源' }}</span>
                    <span>{{ historyChartTypeLabel(selectedEntry.chartType) }}</span>
                    <span>{{ formatChatHistoryTime(selectedEntry.createdAt) }}</span>
                  </div>
                </div>
                <div class="dcl-detail__tags">
                  <el-tag size="small" effect="light" :type="historyExecutionStatusType(selectedEntry)">
                    {{ historyExecutionStatusLabel(selectedEntry) }}
                  </el-tag>
                  <el-tag v-if="selectedEntry.isPinnedOnBoard" size="small" effect="light" type="warning">
                    已钉入本看板
                  </el-tag>
                </div>
              </div>

              <div class="dcl-detail__section">
                <div class="dcl-detail__section-title">基本信息</div>
                <div class="dcl-detail__grid">
                  <div class="dcl-detail__kv">
                    <span class="dcl-detail__k">执行状态</span>
                    <span>{{ historyExecutionStatusLabel(selectedEntry) }}</span>
                  </div>
                  <div class="dcl-detail__kv">
                    <span class="dcl-detail__k">执行耗时</span>
                    <span>{{ formatHistoryExecutionTime(selectedEntry.executionTimeMs) }}</span>
                  </div>
                  <div class="dcl-detail__kv">
                    <span class="dcl-detail__k">图表行数</span>
                    <span>{{ selectedEntry.chartDataCount || 0 }}</span>
                  </div>
                  <div class="dcl-detail__kv">
                    <span class="dcl-detail__k">缓存命中</span>
                    <span>{{ selectedEntry.isHitCache ? '是' : '否' }}</span>
                  </div>
                </div>
              </div>

              <div class="dcl-detail__section">
                <div class="dcl-detail__section-title">字段映射</div>
                <div v-if="summarizeFieldMapping(selectedEntry.fieldMapping).length" class="dcl-detail__grid">
                  <div
                    v-for="item in summarizeFieldMapping(selectedEntry.fieldMapping)"
                    :key="item.label"
                    class="dcl-detail__kv"
                  >
                    <span class="dcl-detail__k">{{ item.label }}</span>
                    <span>{{ item.value }}</span>
                  </div>
                </div>
                <div v-else class="dcl-detail__placeholder">暂无字段映射</div>
              </div>

              <div class="dcl-detail__section">
                <div class="dcl-detail__section-title">图表预览</div>
                <div v-if="selectedEntry.hasChartSnapshot" class="dcl-detail__preview">
                  <DashboardChart :payload="selectedChartPayload" hide-title />
                </div>
                <div v-else class="dcl-detail__placeholder">暂无可预览的图表快照</div>
              </div>

              <div class="dcl-detail__section">
                <div class="dcl-detail__section-title">SQL</div>
                <pre v-if="selectedEntry.sql" class="dcl-detail__sql">{{ selectedEntry.sql }}</pre>
                <div v-else class="dcl-detail__placeholder">暂无 SQL</div>
              </div>

              <div class="dcl-detail__footer">
                <el-button
                  type="primary"
                  :disabled="selectedEntry.isPinnedOnBoard || readonly || !canPinEntry(selectedEntry)"
                  :loading="pinningId === selectedEntry.id"
                  @click="pinOne(selectedEntry)"
                >
                  {{ selectedEntry.isPinnedOnBoard ? '已钉入本看板' : '钉入看板' }}
                </el-button>
              </div>
            </template>
            <el-empty v-else description="请选择左侧图表查看详情" />
          </div>
        </template>

        <div v-else class="dcl-empty">
          <el-empty :description="emptyDescription" />
        </div>
      </div>

      <div class="dcl-pagination">
        <el-pagination
          layout="prev, pager, next, sizes"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[8, 16, 24, 32]"
          :total="total"
          :disabled="!entries.length && !total"
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { restoreSessionHeader } from '../../store/session'
import DashboardChart from './DashboardChart.vue'
import {
  normalizeChatHistoryItem,
  historyChartTypeLabel,
  historyExecutionStatusLabel,
  historyExecutionStatusType,
  formatHistoryExecutionTime,
  formatChatHistoryTime,
  summarizeFieldMapping,
  historyRowToChartPayload
} from '../../utils/chatHistoryItem.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  apiBase: { type: String, default: 'http://localhost:8080' },
  dashboardId: { type: [Number, String], default: null },
  readonly: { type: Boolean, default: false },
  isOnBoard: { type: Function, default: () => false }
})

const emit = defineEmits(['update:modelValue', 'pinned'])

const keyword = ref('')
const loading = ref(false)
const entries = ref([])
const page = ref(1)
const pageSize = ref(8)
const total = ref(0)
const selectedId = ref('')
const selectedIds = ref(new Set())
const pinningId = ref(null)
const bulkPinning = ref(false)

const selectedEntry = computed(
  () => entries.value.find((item) => String(item.id) === String(selectedId.value)) || null
)

const selectedChartPayload = computed(() => historyRowToChartPayload(selectedEntry.value))

const emptyDescription = computed(() =>
  String(keyword.value || '').trim() ? '没有找到匹配的图表' : '暂无图表，请先在对话查询中生成'
)

const selectableOnPage = computed(() => entries.value.filter((entry) => canSelectEntry(entry)))

const selectedPinableIds = computed(() =>
  [...selectedIds.value].filter((id) => {
    const entry = entries.value.find((item) => String(item.id) === String(id))
    return entry && canPinEntry(entry) && !entry.isPinnedOnBoard
  })
)

const isAllPageSelected = computed(() => {
  const list = selectableOnPage.value
  if (!list.length) return false
  return list.every((entry) => selectedIds.value.has(String(entry.id)))
})

const isPageIndeterminate = computed(() => {
  const list = selectableOnPage.value
  if (!list.length) return false
  const picked = list.filter((entry) => selectedIds.value.has(String(entry.id))).length
  return picked > 0 && picked < list.length
})

function riskTagType(level) {
  const text = String(level || 'SAFE').toUpperCase()
  if (text === 'BLOCKED') return 'danger'
  if (text === 'WARN') return 'warning'
  return 'success'
}

function canPinEntry(entry) {
  return Number(entry?.executionStatus) === 1 && entry?.hasChartSnapshot
}

function canSelectEntry(entry) {
  return canPinEntry(entry) && !entry.isPinnedOnBoard
}

function isEntryChecked(entry) {
  return Boolean(entry?.isPinnedOnBoard) || selectedIds.value.has(String(entry?.id))
}

function isCheckboxDisabled(entry) {
  if (props.readonly) return true
  if (entry?.isPinnedOnBoard) return true
  return !canPinEntry(entry)
}

function attachBoardFlags(items) {
  return items.map((item) => ({
    ...item,
    isPinnedOnBoard: props.isOnBoard(item.id)
  }))
}

async function loadList() {
  if (!props.modelValue) return
  loading.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${props.apiBase}/api/chat/history`, {
      params: {
        page: page.value,
        pageSize: pageSize.value,
        keyword: String(keyword.value || '').trim() || undefined,
        executionStatus: 'SUCCESS',
        sortDirection: 'DESC'
      }
    })
    if (res.data.code !== 200) throw new Error(res.data.message || '加载图表库失败')
    const data = res.data.data || {}
    const items = (Array.isArray(data.items) ? data.items : [])
      .map(normalizeChatHistoryItem)
      .filter((item) => item.question)
    entries.value = attachBoardFlags(items)
    total.value = Number(data.total) || 0
    page.value = Number(data.page) || page.value
    pageSize.value = Number(data.pageSize) || pageSize.value
    if (!entries.value.some((item) => String(item.id) === String(selectedId.value))) {
      selectedId.value = entries.value[0]?.id ? String(entries.value[0].id) : ''
    }
    pruneSelection()
  } catch (e) {
    ElMessage.error(e.message || '加载图表库失败')
    entries.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function pruneSelection() {
  const valid = new Set(entries.value.map((item) => String(item.id)))
  const next = new Set()
  for (const id of selectedIds.value) {
    if (valid.has(String(id))) next.add(String(id))
  }
  selectedIds.value = next
}

function search() {
  page.value = 1
  loadList()
}

function resetSearch() {
  keyword.value = ''
  page.value = 1
  loadList()
}

function onPageChange(nextPage) {
  page.value = nextPage
  loadList()
}

function onPageSizeChange(nextSize) {
  pageSize.value = nextSize
  page.value = 1
  loadList()
}

function selectEntry(entry) {
  selectedId.value = String(entry.id)
}

function toggleSelect(entry, checked) {
  if (entry?.isPinnedOnBoard || !canSelectEntry(entry)) return
  const id = String(entry.id)
  const next = new Set(selectedIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  selectedIds.value = next
}

function toggleSelectAllPage(checked) {
  const next = new Set(selectedIds.value)
  for (const entry of selectableOnPage.value) {
    const id = String(entry.id)
    if (checked) next.add(id)
    else next.delete(id)
  }
  selectedIds.value = next
}

async function pinChartIds(ids) {
  const dashboardId = props.dashboardId
  if (!dashboardId) {
    ElMessage.warning('看板未加载')
    return 0
  }
  if (props.readonly) {
    ElMessage.warning('他人已发布公共看板须先另存为副本，再进行编辑')
    return 0
  }
  let ok = 0
  for (const rawId of ids) {
    const chartId = Number(rawId)
    if (!Number.isFinite(chartId) || chartId <= 0) continue
    if (props.isOnBoard(chartId)) continue
    restoreSessionHeader()
    const res = await axios.post(`${props.apiBase}/api/c/dashboards/${dashboardId}/pin-chart`, {
      chartId
    })
    if (res.data.code !== 200) throw new Error(res.data.message || '钉入失败')
    ok += 1
  }
  return ok
}

async function pinOne(entry) {
  if (!canPinEntry(entry) || entry.isPinnedOnBoard) return
  pinningId.value = entry.id
  try {
    const count = await pinChartIds([entry.id])
    if (count > 0) {
      ElMessage.success('已钉入本看板')
      emit('pinned')
      await loadList()
    }
  } catch (e) {
    ElMessage.error(e.message || '钉入失败')
  } finally {
    pinningId.value = null
  }
}

async function pinSelected() {
  if (!selectedPinableIds.value.length) return
  bulkPinning.value = true
  try {
    const count = await pinChartIds(selectedPinableIds.value)
    if (count > 0) {
      ElMessage.success(`已钉入 ${count} 个图表`)
      selectedIds.value = new Set()
      emit('pinned')
      await loadList()
    } else {
      ElMessage.info('所选图表均已钉入本看板')
    }
  } catch (e) {
    ElMessage.error(e.message || '批量钉入失败')
  } finally {
    bulkPinning.value = false
  }
}

function onOpened() {
  page.value = 1
  loadList()
}

function onClosed() {
  keyword.value = ''
  entries.value = []
  selectedId.value = ''
  selectedIds.value = new Set()
  page.value = 1
  total.value = 0
}

watch(
  () => props.dashboardId,
  () => {
    if (props.modelValue && entries.value.length) {
      entries.value = attachBoardFlags(entries.value)
    }
  }
)
</script>

<style scoped>
.dcl-root {
  height: 100%;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 14px;
}
.dcl-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
}
.dcl-toolbar__keyword {
  flex: 1;
  min-width: 0;
}
.dcl-toolbar__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.dcl-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #64748b;
  font-size: 12px;
}
.dcl-summary__left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.dcl-summary__picked {
  color: #2563eb;
  font-weight: 600;
}
.dcl-content {
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(320px, 0.95fr) minmax(0, 1.25fr);
  gap: 14px;
  overflow: hidden;
}
.dcl-list {
  min-height: 0;
  overflow-y: auto;
  display: grid;
  align-content: start;
  gap: 12px;
  padding-right: 4px;
}
.dcl-card {
  display: flex;
  gap: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  padding: 12px;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.dcl-card:hover {
  border-color: #c8d7eb;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.07);
}
.dcl-card.is-active {
  border-color: #2563eb;
  box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.14), 0 10px 28px rgba(37, 99, 235, 0.08);
  background: linear-gradient(180deg, #f8fbff 0%, #f1f7ff 100%);
}
.dcl-card__check {
  padding-top: 2px;
}
.dcl-card__body {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 8px;
}
.dcl-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.dcl-card__title {
  color: #0f172a;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.45;
  word-break: break-word;
}
.dcl-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: #64748b;
  font-size: 12px;
}
.dcl-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.dcl-detail {
  min-height: 0;
  overflow-y: auto;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  padding: 16px;
  display: grid;
  align-content: start;
  gap: 14px;
}
.dcl-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.dcl-detail__title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.4;
}
.dcl-detail__submeta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-top: 6px;
  color: #64748b;
  font-size: 12px;
}
.dcl-detail__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.dcl-detail__section-title {
  font-size: 13px;
  font-weight: 700;
  color: #334155;
  margin-bottom: 8px;
}
.dcl-detail__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 12px;
}
.dcl-detail__kv {
  display: grid;
  gap: 2px;
  font-size: 13px;
  color: #0f172a;
}
.dcl-detail__k {
  color: #64748b;
  font-size: 12px;
}
.dcl-detail__placeholder {
  color: #94a3b8;
  font-size: 13px;
}
.dcl-detail__preview {
  min-height: 220px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 8px;
  background: #fafafa;
}
.dcl-detail__preview :deep(.dc-host) {
  min-height: 200px;
}
.dcl-detail__sql {
  margin: 0;
  padding: 12px;
  border-radius: 10px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow: auto;
}
.dcl-detail__footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
  border-top: 1px solid #eef2f7;
}
.dcl-empty {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 280px;
}
.dcl-pagination {
  display: flex;
  justify-content: center;
}
</style>
