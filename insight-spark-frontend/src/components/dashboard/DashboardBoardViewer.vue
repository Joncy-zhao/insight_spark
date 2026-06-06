<template>
  <el-dialog
    v-if="!embedded"
    v-model="innerVisible"
    fullscreen
    destroy-on-close
    :class="['dbv-dialog', { 'dbv-dialog--canvas-only': !showHeader }]"
    append-to-body
    @closed="onClosed"
  >
    <template v-if="showHeader" #header>
      <div class="dbv-head">
        <div class="dbv-title">
          <span>查看看板 · {{ board?.name || '看板' }}</span>
          <el-tag size="small" type="info">画布预览 · 只读</el-tag>
        </div>
        <el-button @click="innerVisible = false">关闭</el-button>
      </div>
    </template>

    <DashboardBoardBody
      v-model:grid-layout="gridLayout"
      :board="board"
      :legacy-preview-cards="legacyPreviewCards"
      :canvas-stage-inline-style="canvasStageInlineStyle"
      :row-height="rowHeight"
      :chart-payload-by-id="chartPayloadById"
      :components="components"
      :loading="loading"
      :show-lead="showLead"
    />
  </el-dialog>

  <div v-else class="dbv-embedded-root" v-loading="loading">
    <DashboardBoardBody
      v-model:grid-layout="gridLayout"
      :board="board"
      :legacy-preview-cards="legacyPreviewCards"
      :canvas-stage-inline-style="canvasStageInlineStyle"
      :row-height="rowHeight"
      :chart-payload-by-id="chartPayloadById"
      :components="components"
      :loading="loading"
      :show-lead="showEmbedLead"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'
import {
  extractLegacyChartCards,
  mergeGridItemsWithComponents,
  normalizeCanvasStyle,
  parseDashboardLayout,
  buildCanvasStageInlineStyle
} from '../../utils/dashboardGrid.js'
import DashboardBoardBody from './DashboardBoardBody.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 至少包含 id；可选 name、layoutJson（加载后会以接口为准） */
  initialRow: { type: Object, default: null },
  apiBase: { type: String, default: 'http://localhost:8080' },
  /** 内嵌渲染（无弹窗），用于分享页等 */
  embedded: { type: Boolean, default: false },
  /** 是否展示与设计器一致的说明条（分享页可关） */
  showEmbedLead: { type: Boolean, default: true },
  /** 弹窗模式是否展示说明条（管理员查看看板可关） */
  showLead: { type: Boolean, default: true },
  /** 弹窗模式是否展示自定义标题栏（预览/分享仅保留右上角关闭） */
  showHeader: { type: Boolean, default: true },
  /**
   * 预填充数据则跳过接口拉取：{ board, components, chartPayloadById }
   * chartPayloadById: Record<string, historyRow>
   */
  prefetch: { type: Object, default: null }
})

const emit = defineEmits(['update:modelValue'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const board = ref(null)
const gridLayout = ref([])
const components = ref([])
const chartPayloadById = ref({})
const canvasStyle = reactive(normalizeCanvasStyle())
const rowHeight = 44

const canvasStageInlineStyle = computed(() => buildCanvasStageInlineStyle(canvasStyle))

const legacyPreviewCards = computed(() => {
  if (!board.value?.layoutJson) return []
  return extractLegacyChartCards(board.value.layoutJson, 'board-viewer')
})

function applyPrefetch(p) {
  if (!p?.board) return
  board.value = p.board
  components.value = Array.isArray(p.components) ? p.components : []
  chartPayloadById.value =
    p.chartPayloadById && typeof p.chartPayloadById === 'object' ? { ...p.chartPayloadById } : {}
  const parsed = parseDashboardLayout(board.value?.layoutJson)
  Object.assign(canvasStyle, normalizeCanvasStyle(parsed.canvasStyle))
  gridLayout.value = mergeGridItemsWithComponents(
    Array.isArray(parsed.items) ? parsed.items : [],
    components.value,
    parsed.gridCols
  )
}

function resetState() {
  board.value = null
  gridLayout.value = []
  components.value = []
  chartPayloadById.value = {}
  Object.assign(canvasStyle, normalizeCanvasStyle())
}

async function loadChartPayloads() {
  const idSet = new Set()
  for (const c of components.value || []) {
    const raw = c.chartId ?? c.chart_id ?? c.CHART_ID
    if (raw == null) continue
    const n = Number(raw)
    if (Number.isFinite(n)) idSet.add(n)
  }
  const unique = [...idSet]
  if (!unique.length) {
    chartPayloadById.value = {}
    return
  }
  restoreSessionHeader()
  const res = await axios.post(`${props.apiBase}/api/chat/history/charts-batch`, {
    ids: unique,
    dashboardId: board.value?.id ?? props.initialRow?.id
  })
  if (res.data.code !== 200) throw new Error(res.data.message || '批量加载图表失败')
  const rows = Array.isArray(res.data.data) ? res.data.data : []
  const map = {}
  for (const row of rows) {
    const id = row.id != null ? String(row.id) : ''
    if (id) map[id] = row
  }
  chartPayloadById.value = map
}

async function loadBoard() {
  const id = props.initialRow?.id
  if (!id) return
  restoreSessionHeader()
  const [dashRes, compRes] = await Promise.all([
    axios.get(`${props.apiBase}/api/c/dashboards/${id}`),
    axios.get(`${props.apiBase}/api/c/dashboards/${id}/components`)
  ])
  if (dashRes.data.code !== 200) throw new Error(dashRes.data.message || '加载看板失败')
  if (compRes.data.code !== 200) throw new Error(compRes.data.message || '加载组件失败')
  board.value = dashRes.data.data
  components.value = Array.isArray(compRes.data.data) ? compRes.data.data : []
  const parsed = parseDashboardLayout(board.value?.layoutJson)
  Object.assign(canvasStyle, normalizeCanvasStyle(parsed.canvasStyle))
  gridLayout.value = mergeGridItemsWithComponents(
    Array.isArray(parsed.items) ? parsed.items : [],
    components.value,
    parsed.gridCols
  )
  await loadChartPayloads()
  await nextTick()
  window.dispatchEvent(new Event('resize'))
}

function onClosed() {
  resetState()
}

watch(
  () => props.prefetch,
  async (p) => {
    if (!props.embedded || !p?.board) return
    loading.value = true
    try {
      applyPrefetch(p)
      await nextTick()
      window.dispatchEvent(new Event('resize'))
    } finally {
      loading.value = false
    }
  },
  { deep: true, immediate: true }
)

watch(
  () => props.modelValue,
  async (open) => {
    if (props.embedded) return
    if (!open || !props.initialRow?.id) return
    if (props.prefetch?.board) {
      applyPrefetch(props.prefetch)
      await nextTick()
      window.dispatchEvent(new Event('resize'))
      return
    }
    loading.value = true
    try {
      await loadBoard()
    } catch (e) {
      ElMessage.error(e.message || '加载失败')
      innerVisible.value = false
    } finally {
      loading.value = false
    }
  }
)
</script>

<style scoped>
.dbv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}
.dbv-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}
.dbv-embedded-root {
  min-height: 200px;
  width: 100%;
}
</style>

<style>
.dbv-dialog.is-fullscreen {
  display: flex;
  flex-direction: column;
}
.dbv-dialog.is-fullscreen .el-dialog__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
  padding-top: 8px;
}
.dbv-dialog--canvas-only.is-fullscreen .el-dialog__header {
  padding: 8px 12px;
  margin: 0;
}
.dbv-dialog--canvas-only.is-fullscreen .el-dialog__title {
  display: none;
}
.dbv-dialog--canvas-only.is-fullscreen .el-dialog__body {
  padding-top: 0;
}
</style>
