<template>
  <el-dialog
    v-model="innerVisible"
    fullscreen
    destroy-on-close
    class="dge-dialog"
    append-to-body
    @closed="onClosed"
  >
    <template #header>
      <div class="dge-head">
        <div class="dge-title">
          <span>设计看板 · {{ board?.name || '看板' }}</span>
          <el-tag size="small" type="info">grid-layout-plus · 12 列</el-tag>
        </div>
        <div class="dge-actions">
          <el-button v-if="board" @click="canvasStyleDialogOpen = true">画布样式</el-button>
          <el-button
            v-if="board && gridLayout.length"
            :loading="exportingPng"
            @click="exportCanvasPng"
          >
            导出 PNG
          </el-button>
          <el-button
            v-if="board && gridLayout.length"
            :loading="exportingPdf"
            @click="exportCanvasPdf"
          >
            导出 PDF
          </el-button>
          <el-button type="primary" :loading="saving" @click="saveLayout">保存布局</el-button>
          <el-button @click="innerVisible = false">关闭</el-button>
        </div>
      </div>
    </template>

    <div v-if="board" class="dge-body">
      <p v-if="gridLayout.length" class="dge-hint">
        左侧为<strong>您可访问的全部看板</strong>里已钉入的对话图表（按历史图表 ID 去重，并汇总所在看板名称）；可将其中任一图表<strong>加入当前画布</strong>排布。卡片标题区<strong>双击</strong>可改业务名称；右上角可<strong>移除</strong>。
        在标题区、图表区等<strong>任意区域</strong>按住拖拽即可<strong>在画布上自由摆放</strong>（12 列栅格 + 行高对齐；已关闭垂直压缩）；卡片<strong>四边及四角</strong>均可拖拽调整占位（列宽 w / 行高 h，对齐栅格）。也可在「图表组件」里用数字精确改 w、h。点<strong>图表组件</strong>可设<strong>柱色、柱宽、分项配色</strong>等，并查看问题 / SQL / 字段映射。修改后请点「保存布局」写入
        <code>layout_json.items</code>。
      </p>
      <template v-if="!gridLayout.length">
        <el-alert type="warning" show-icon :closable="false" class="dge-alert">
          <template #title>为什么列表里显示有图表，这里却是空的？</template>
          <p class="dge-alert-p">
            「图表卡片」列统计的是 <strong>layout_json.cards</strong>（旧版把数据嵌在 JSON 里）。
            网格编排依赖 <strong>is_dashboard_component</strong> 表 + <strong>layout_json.items</strong>
            （钉入成功后由后端写入）。若只有 cards、没有组件表记录，就会出现「有数字但不能拖格子」。
          </p>
          <p v-if="legacyPreviewCards.length" class="dge-alert-p">
            下面是你当前 JSON 里嵌的图表（只读预览）。请在左侧<strong>已钉图表</strong>点「加入画布」，或到<strong>对话查询</strong>先<strong>钉入看板</strong>（带
            <code>chartId</code>）。
          </p>
          <p v-else class="dge-alert-p">
            当前既没有 <code>items</code>，也没有可渲染的 <code>cards</code>。请先在<strong>对话查询</strong>将图表<strong>钉入本看板</strong>，再在左侧列表加入画布。
          </p>
        </el-alert>
        <el-alert
          v-if="legacyPreviewCards.length"
          type="info"
          show-icon
          :closable="false"
          class="dge-legacy-tip"
          title="当前为旧版「内嵌数据」预览，不支持拖拽改位置。"
          description="请从左侧「已钉入看板的图表」加入画布，或先在对话查询中钉入看板。"
        />
        <div class="dge-empty-workbench">
          <aside class="dge-side">
            <div class="dge-side-head">各看板已钉图表</div>
            <div class="dge-side-search">
              <el-input
                v-model="historyKeyword"
                clearable
                size="small"
                placeholder="筛选问题 / 表名 / 看板名 / 历史 ID"
              />
            </div>
            <div v-loading="sideListLoading" class="dge-side-list">
              <div v-for="h in displayedPinnedLibrary" :key="h.id" class="dge-side-row">
                <div class="dge-side-row-main">
                  <span class="dge-hid">#{{ h.id }}</span>
                  <el-tag size="small" type="info">{{ h.chartType || 'bar' }}</el-tag>
                  <el-tag v-if="isChartInGridLayout(h.id)" size="small" type="success">已在画布</el-tag>
                </div>
                <div class="dge-side-q">{{ h.question || h.queryText || '（无摘要）' }}</div>
                <div v-if="h.pinnedDashboardNames" class="dge-side-meta">已在看板：{{ h.pinnedDashboardNames }}</div>
                <el-button
                  size="small"
                  type="primary"
                  :disabled="isChartInGridLayout(h.id)"
                  :loading="pinningId === h.id"
                  @click="pinHistoryChart(h)"
                >
                  {{ isChartInGridLayout(h.id) ? '已在画布' : '加入画布' }}
                </el-button>
              </div>
              <el-empty
                v-if="!sideListLoading && !displayedPinnedLibrary.length"
                description="暂无任何看板钉入图表，请先在对话查询中钉入某一看板"
              />
            </div>
          </aside>
          <div v-if="legacyPreviewCards.length" class="dge-legacy-grid">
            <LegacyInlineChart
              v-for="c in legacyPreviewCards"
              :key="c._renderKey"
              :title="c.title"
              :chart-type="c.chartType"
              :data="c.data"
            />
          </div>
        </div>
      </template>
      <div v-else class="dge-workbench">
        <aside class="dge-side">
          <div class="dge-side-head">各看板已钉图表</div>
          <div class="dge-side-search">
            <el-input
              v-model="historyKeyword"
              clearable
              size="small"
              placeholder="筛选问题 / 表名 / 看板名 / 历史 ID"
            />
          </div>
          <div v-loading="sideListLoading" class="dge-side-list">
            <div v-for="h in displayedPinnedLibrary" :key="h.id" class="dge-side-row">
              <div class="dge-side-row-main">
                <span class="dge-hid">#{{ h.id }}</span>
                <el-tag size="small" type="info">{{ h.chartType || 'bar' }}</el-tag>
                <el-tag v-if="isChartInGridLayout(h.id)" size="small" type="success">已在画布</el-tag>
              </div>
              <div class="dge-side-q">{{ h.question || h.queryText || '（无摘要）' }}</div>
              <div v-if="h.pinnedDashboardNames" class="dge-side-meta">已在看板：{{ h.pinnedDashboardNames }}</div>
              <el-button
                size="small"
                type="primary"
                :disabled="isChartInGridLayout(h.id)"
                :loading="pinningId === h.id"
                @click="pinHistoryChart(h)"
              >
                {{ isChartInGridLayout(h.id) ? '已在画布' : '加入画布' }}
              </el-button>
            </div>
            <el-empty
              v-if="!sideListLoading && !displayedPinnedLibrary.length"
              description="暂无任何看板钉入图表，请先在对话查询中钉入某一看板"
            />
          </div>
        </aside>
        <div class="dge-canvas-col">
          <div
            ref="exportStageRef"
            class="dge-export-stage"
            :style="canvasStageInlineStyle"
          >
          <div class="dge-grid-wrap">
        <GridLayout
          v-model:layout="gridLayout"
          :col-num="12"
          :row-height="rowHeight"
          :margin="[12, 12]"
          :is-draggable="true"
          :is-resizable="true"
          :vertical-compact="false"
          class="dge-grid"
          @layout-updated="onLayoutUpdated"
        >
              <GridItem
                v-for="item in gridLayout"
                :key="item.i"
                v-bind="itemProps(item)"
                class="dge-grid-item"
                @resized="onGridItemResized"
                @container-resized="onGridItemResized"
              >
                <div class="dge-card">
                  <div class="dge-card-meta">
                    <div
                      class="dge-card-titlewrap"
                      title="双击修改标题"
                      @dblclick.stop="startEditTitle(item)"
                    >
                      <el-input
                        v-if="editingItemId === String(item.i)"
                        :id="'dge-title-inp-' + item.i"
                        v-model="titleDraft"
                        size="small"
                        maxlength="120"
                        show-word-limit
                        @blur="commitTitleEdit(item)"
                        @keydown.enter.prevent="commitTitleEdit(item)"
                      />
                      <span v-else class="dge-card-title">{{ displayTitleForItem(item) }}</span>
                    </div>
                    <div class="dge-card-actions">
                      <span v-if="chartIdForItem(item)" class="dge-chart-id">历史 {{ chartIdForItem(item) }}</span>
                      <span v-if="artifactIdForItem(item)" class="dge-chart-id">Artifact {{ artifactIdForItem(item) }}</span>
                      <span v-if="turnIdForItem(item)" class="dge-chart-id">Turn {{ turnIdForItem(item) }}</span>
                      <el-button
                        v-if="chartIdForItem(item)"
                        type="primary"
                        link
                        size="small"
                        @click.stop="openChartInspector(item)"
                      >
                        图表组件
                      </el-button>
                      <el-button
                        type="danger"
                        link
                        size="small"
                        :loading="removingId === String(item.i)"
                        @click.stop="removeGridItem(item)"
                      >
                        移除
                      </el-button>
                    </div>
                  </div>
                  <div class="dge-chart-host">
                    <DashboardChart
                      v-if="payloadForItem(item)"
                      :payload="payloadForItem(item)"
                      :chart-ui="chartUiForItem(item)"
                      hide-title
                    />
                    <div v-else class="dge-chart-fallback">
                      {{ chartIdForItem(item) ? '图表数据暂不可用' : '未关联 chart_id' }}
                    </div>
                  </div>
                </div>
              </GridItem>
            </GridLayout>
          </div>
            <img
              v-if="canvasStyle.brandLogoDataUrl"
              :src="canvasStyle.brandLogoDataUrl"
              class="dge-stage-logo"
              alt=""
            />
          </div>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="canvasStyleDialogOpen"
      title="画布样式"
      width="480px"
      append-to-body
      destroy-on-close
    >
      <el-form label-position="top" size="small">
        <el-form-item label="背景色">
          <el-color-picker v-model="canvasStyle.backgroundColor" show-alpha />
        </el-form-item>
        <el-form-item label="边框颜色">
          <el-color-picker v-model="canvasStyle.borderColor" show-alpha />
        </el-form-item>
        <el-form-item label="边框宽度 (px)">
          <el-slider v-model="canvasStyle.borderWidth" :min="0" :max="12" show-input />
        </el-form-item>
        <el-form-item label="圆角 (px)">
          <el-slider v-model="canvasStyle.borderRadius" :min="0" :max="32" show-input />
        </el-form-item>
        <el-form-item label="内边距 (px)">
          <el-slider v-model="canvasStyle.padding" :min="0" :max="48" show-input />
        </el-form-item>
        <el-form-item label="画布最小高度 (vh)">
          <el-slider v-model="canvasStyle.minHeightVh" :min="40" :max="85" show-input />
        </el-form-item>
        <el-form-item label="角标图（静态，无接口时本地读入 Base64，随布局保存）">
          <div class="dge-logo-row">
            <el-upload
              :show-file-list="false"
              accept="image/*"
              :auto-upload="false"
              :on-change="onLogoFileChange"
            >
              <el-button size="small">选择图片</el-button>
            </el-upload>
            <el-button size="small" :disabled="!canvasStyle.brandLogoDataUrl" @click="clearLogo">
              清除
            </el-button>
          </div>
          <div v-if="canvasStyle.brandLogoDataUrl" class="dge-logo-preview">
            <img :src="canvasStyle.brandLogoDataUrl" alt="角标预览" />
          </div>
          <p class="dge-form-hint">整页背景图暂未开放；此处仅右下角角标。单张建议 &lt; 1.5MB。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="canvasStyleDialogOpen = false">完成</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="inspectorOpen"
      title="图表组件（对话查询产物）"
      direction="rtl"
      size="min(520px, 92vw)"
      destroy-on-close
      class="dge-inspector-drawer"
      @closed="onInspectorDrawerClosed"
    >
      <template v-if="inspectorPayload">
        <p class="dge-inspector-lead">
          <strong>系列分项组件</strong>指图中每一个数据图形单元（每根柱、每个扇区、每个折线点等），可单独配色；<strong>整图默认样式</strong>（如柱宽、统一柱色）与<strong>卡片占位</strong>一并写入 <code>layout_json.items</code>，保存布局后生效。下方 SQL / 字段映射等为对话历史只读。
        </p>
        <template v-if="inspectorItem">
          <div
            v-if="inspectorSupportsSeriesComponents && inspectorSeriesPoints.length"
            class="dge-inspector-block dge-inspector-card-style"
          >
            <div class="dge-inspector-label">分项组件（逐项配色）</div>
            <p class="dge-inspector-sub">
              共 {{ inspectorSeriesPoints.length }} 项；未改动的项沿用 ECharts 默认调色。清空表示撤销该项覆盖。
            </p>
            <div class="dge-series-list">
              <div
                v-for="(pt, idx) in inspectorSeriesPoints"
                :key="idx"
                class="dge-series-item-row"
              >
                <div class="dge-series-item-meta">
                  <span class="dge-series-item-name" :title="pt.name">{{ pt.name }}</span>
                  <span class="dge-series-item-val">{{ formatSeriesPointValue(pt.value) }}</span>
                </div>
                <el-color-picker
                  :model-value="seriesItemColorPickerModel(idx)"
                  show-alpha
                  size="small"
                  @change="(c) => onSeriesItemColorChange(idx, c)"
                />
              </div>
            </div>
            <el-button text type="primary" size="small" @click="clearAllSeriesItemStyles">
              清除全部分项配色
            </el-button>
          </div>
          <div v-if="inspectorIsBar" class="dge-inspector-block dge-inspector-card-style">
            <div class="dge-inspector-label">柱图 · 整图默认（无分项覆盖时生效）</div>
            <div class="dge-inspector-row">
              <span class="dge-inspector-k">柱颜色</span>
              <el-color-picker
                :model-value="inspectorBarColorModel"
                show-alpha
                @change="onInspectorBarColorChange"
              />
              <el-button text type="primary" size="small" @click="resetInspectorBarStyle">恢复默认样式</el-button>
            </div>
            <div class="dge-inspector-row dge-inspector-row--slider">
              <span class="dge-inspector-k">柱宽度上限</span>
              <el-slider
                :model-value="inspectorBarMaxWidthModel"
                :min="8"
                :max="72"
                :step="2"
                class="dge-inspector-slider"
                @update:model-value="(v) => patchInspectorGridItem({ barMaxWidth: v })"
              />
            </div>
          </div>
          <div class="dge-inspector-block dge-inspector-card-style">
            <div class="dge-inspector-label">卡片占位（网格）</div>
            <div class="dge-inspector-row">
              <span class="dge-inspector-k">宽度 w（列）</span>
              <el-input-number
                :model-value="inspectorItem.w"
                :min="2"
                :max="12"
                size="small"
                controls-position="right"
                @change="(v) => patchInspectorGridItem({ w: v })"
              />
            </div>
            <div class="dge-inspector-row">
              <span class="dge-inspector-k">高度 h（行）</span>
              <el-input-number
                :model-value="inspectorItem.h"
                :min="2"
                :max="24"
                size="small"
                controls-position="right"
                @change="(v) => patchInspectorGridItem({ h: v })"
              />
            </div>
          </div>
        </template>
        <el-descriptions :column="1" border size="small" class="dge-inspector-desc">
          <el-descriptions-item label="历史 ID">{{ chartIdForItem(inspectorItem) }}</el-descriptions-item>
          <el-descriptions-item label="Artifact ID">{{ artifactIdForItem(inspectorItem) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Turn ID">{{ turnIdForItem(inspectorItem) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="图表类型">{{ inspectorChartType }}</el-descriptions-item>
          <el-descriptions-item label="数据表">{{ inspectorTableName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="结果行数">{{ inspectorDataRows }}</el-descriptions-item>
        </el-descriptions>
        <div class="dge-inspector-block">
          <div class="dge-inspector-label">原始问题</div>
          <el-input :model-value="inspectorQuestion" type="textarea" :rows="3" readonly />
        </div>
        <div class="dge-inspector-block">
          <div class="dge-inspector-label">字段映射 fieldMapping（维度 / 指标等）</div>
          <pre class="dge-inspector-pre">{{ inspectorFieldMappingText }}</pre>
        </div>
        <div class="dge-inspector-block">
          <div class="dge-inspector-label">SQL（快照优先，否则 generated_sql）</div>
          <el-input :model-value="inspectorSql" type="textarea" :rows="12" readonly class="dge-inspector-sql" />
        </div>
      </template>
      <el-empty v-else description="无图表载荷" />
    </el-drawer>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import axios from 'axios'
import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GridLayout, GridItem } from 'grid-layout-plus'
import { restoreSessionHeader } from '../../store/session'
import {
  parseDashboardLayout,
  mergeGridItemsWithComponents,
  serializeLayoutForApi,
  extractLegacyChartCards,
  normalizeCanvasStyle
} from '../../utils/dashboardGrid.js'
import DashboardChart from '../../components/dashboard/DashboardChart.vue'
import LegacyInlineChart from '../../components/dashboard/LegacyInlineChart.vue'
import {
  normalizeChartType,
  normalizedChartDataPoints
} from '../../utils/chartOptionFromSnapshot.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  initialRow: { type: Object, default: null },
  apiBase: { type: String, default: 'http://localhost:8080' }
})

const emit = defineEmits(['update:modelValue', 'saved'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const board = ref(null)
const gridLayout = ref([])
const legacyCards = ref([])
const components = ref([])
const chartPayloadById = ref({})
/** 当前用户所有可访问看板中已钉入的 chart 汇总（来自 GET /pinned-charts） */
const allPinnedSummaries = ref([])
const saving = ref(false)
const rowHeight = 44

/** 筛选左侧「各看板已钉图表」列表（仅客户端过滤） */
const historyKeyword = ref('')
const sideListLoading = ref(false)
const pinningId = ref(null)
const removingId = ref(null)

const editingItemId = ref(null)
const titleDraft = ref('')

const inspectorOpen = ref(false)
const inspectorItem = ref(null)

const canvasStyle = reactive(normalizeCanvasStyle())
const canvasStyleDialogOpen = ref(false)
const exportStageRef = ref(null)
const exportingPng = ref(false)
const exportingPdf = ref(false)

const canvasStageInlineStyle = computed(() => {
  const s = canvasStyle
  const bw = Math.max(0, Math.min(16, Number(s.borderWidth) || 0))
  const br = Math.max(0, Math.min(48, Number(s.borderRadius) || 0))
  const pad = Math.max(0, Math.min(64, Number(s.padding) || 0))
  const vh = Math.max(40, Math.min(92, Number(s.minHeightVh) || 60))
  return {
    backgroundColor: s.backgroundColor,
    border: bw > 0 ? `${bw}px solid ${s.borderColor}` : 'none',
    borderRadius: `${br}px`,
    padding: `${pad}px`,
    minHeight: `max(${vh}vh, 480px)`,
    boxSizing: 'border-box',
    width: '100%'
  }
})

function clearLogo() {
  canvasStyle.brandLogoDataUrl = ''
}

function onLogoFileChange(uploadFile) {
  const raw = uploadFile?.raw
  if (!raw) return
  if (!String(raw.type || '').startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (raw.size > 1.5 * 1024 * 1024) {
    ElMessage.warning('图片较大，建议压缩到 1.5MB 以下再试（将存入 layout_json）')
  }
  const reader = new FileReader()
  reader.onload = () => {
    const url = String(reader.result || '')
    if (url.length > 1_800_000) {
      ElMessage.error('图片过大，请换更小的文件')
      return
    }
    canvasStyle.brandLogoDataUrl = url
    ElMessage.success('角标图已读入')
  }
  reader.readAsDataURL(raw)
}

function exportBaseName() {
  const name = String(board.value?.name || '看板').replace(/[/\\?%*:|"<>]/g, '-')
  return `${name}-画布`
}

async function captureExportStage() {
  const el = exportStageRef.value
  if (!el) {
    ElMessage.warning('当前没有可导出的画布')
    return null
  }
  await nextTick()
  await new Promise((r) => requestAnimationFrame(r))
  try {
    return await html2canvas(el, {
      scale: 2,
      useCORS: true,
      allowTaint: true,
      backgroundColor: canvasStyle.backgroundColor || '#f3f4f6',
      logging: false
    })
  } catch (e) {
    ElMessage.error(e?.message || '截图失败')
    return null
  }
}

function downloadDataUrl(dataUrl, filename) {
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

async function exportCanvasPng() {
  if (exportingPng.value) return
  exportingPng.value = true
  try {
    const canvas = await captureExportStage()
    if (!canvas) return
    downloadDataUrl(canvas.toDataURL('image/png'), `${exportBaseName()}.png`)
    ElMessage.success('已导出 PNG')
  } finally {
    exportingPng.value = false
  }
}

async function exportCanvasPdf() {
  if (exportingPdf.value) return
  exportingPdf.value = true
  try {
    const canvas = await captureExportStage()
    if (!canvas) return
    const dataUrl = canvas.toDataURL('image/png')
    const cw = canvas.width
    const ch = canvas.height
    const pdf = new jsPDF({
      unit: 'mm',
      format: 'a4',
      orientation: cw >= ch ? 'l' : 'p'
    })
    const pageW = pdf.internal.pageSize.getWidth()
    const pageH = pdf.internal.pageSize.getHeight()
    const mmPerPx = 25.4 / 96
    let rw = cw * mmPerPx
    let rh = ch * mmPerPx
    const scale = Math.min(pageW / rw, pageH / rh, 1)
    rw *= scale
    rh *= scale
    pdf.addImage(dataUrl, 'PNG', 0, 0, rw, rh)
    pdf.save(`${exportBaseName()}.pdf`)
    ElMessage.success('已导出 PDF（单页适配 A4）')
  } catch (e) {
    ElMessage.error(e?.message || '导出 PDF 失败')
  } finally {
    exportingPdf.value = false
  }
}

function parseChartSnapshot(raw) {
  if (raw == null) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(String(raw))
  } catch {
    return {}
  }
}

function openChartInspector(item) {
  inspectorItem.value = item
  inspectorOpen.value = true
}

function onInspectorDrawerClosed() {
  inspectorItem.value = null
}

const inspectorPayload = computed(() => {
  const it = inspectorItem.value
  if (!it) return null
  return payloadForItem(it)
})

const inspectorSnap = computed(() => parseChartSnapshot(inspectorPayload.value?.chartSnapshot))

const inspectorChartType = computed(() => {
  const p = inspectorPayload.value
  const t = String(p?.chartType || inspectorSnap.value.chartType || '').trim()
  return t || '—'
})

const inspectorTableName = computed(() => {
  const p = inspectorPayload.value
  const snap = inspectorSnap.value
  return String(snap.tableName || p?.queryTableName || '').trim()
})

const inspectorQuestion = computed(() => String(inspectorPayload.value?.queryText || '').trim())

const inspectorSql = computed(() => {
  const p = inspectorPayload.value
  if (!p) return ''
  const snap = inspectorSnap.value
  const fromSnap = String(snap.sql || '').trim()
  if (fromSnap) return fromSnap
  return String(p.generatedSql || '').trim()
})

const inspectorFieldMappingText = computed(() => {
  const fm = inspectorSnap.value.fieldMapping
  if (fm && typeof fm === 'object' && Object.keys(fm).length) {
    return JSON.stringify(fm, null, 2)
  }
  return '（本条历史未写入 fieldMapping，可在对话查询链路中核对 BI 返回结构）'
})

const inspectorDataRows = computed(() => {
  const d = inspectorSnap.value.data
  return Array.isArray(d) ? d.length : 0
})

const inspectorNormalizedChartType = computed(() =>
  normalizeChartType(
    inspectorPayload.value?.chartType ||
      inspectorSnap.value.chartType ||
      inspectorChartType.value
  )
)

const inspectorSeriesPoints = computed(() => {
  const p = inspectorPayload.value
  if (!p) return []
  return normalizedChartDataPoints(p)
})

const inspectorSupportsSeriesComponents = computed(() =>
  ['bar', 'pie', 'line'].includes(inspectorNormalizedChartType.value)
)

const SERIES_ITEM_PALETTE = [
  '#5470c6',
  '#91cc75',
  '#fac858',
  '#ee6666',
  '#73c0de',
  '#3ba272',
  '#fc8452',
  '#9a60b4',
  '#ea7ccc'
]

function formatSeriesPointValue(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v ?? '')
  return n.toLocaleString()
}

function seriesItemColorPickerModel(idx) {
  const it = inspectorItem.value
  const stored = it?.seriesItemStyles?.[String(idx)]?.color
  if (stored != null && String(stored).trim() !== '') return String(stored).trim()
  return SERIES_ITEM_PALETTE[idx % SERIES_ITEM_PALETTE.length]
}

function onSeriesItemColorChange(idx, val) {
  const it = inspectorItem.value
  if (!it) return
  const base =
    it.seriesItemStyles && typeof it.seriesItemStyles === 'object' ? { ...it.seriesItemStyles } : {}
  if (val == null || val === '') delete base[String(idx)]
  else base[String(idx)] = { color: String(val).trim() }
  patchInspectorGridItem({
    seriesItemStyles: Object.keys(base).length ? base : null
  })
}

function clearAllSeriesItemStyles() {
  patchInspectorGridItem({ seriesItemStyles: null })
}

const inspectorIsBar = computed(() => {
  const t = String(inspectorChartType.value || '').toLowerCase()
  return t === 'bar' || t.includes('柱')
})

const inspectorBarColorModel = computed(() => {
  const c = String(inspectorItem.value?.barColor || '').trim()
  return c || '#5470c6'
})

const inspectorBarMaxWidthModel = computed(() => {
  const w = Number(inspectorItem.value?.barMaxWidth)
  return Number.isFinite(w) && w >= 8 ? w : 32
})

function patchInspectorGridItem(patch) {
  const it = inspectorItem.value
  if (!it) return
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(it.i))
  if (idx < 0) return
  const cur = gridLayout.value[idx]
  const next = { ...cur }
  if ('barColor' in patch) {
    const c = patch.barColor
    if (c == null || String(c).trim() === '') delete next.barColor
    else next.barColor = String(c).trim()
  }
  if ('barMaxWidth' in patch) {
    const n = Number(patch.barMaxWidth)
    if (!Number.isFinite(n) || n < 8) delete next.barMaxWidth
    else next.barMaxWidth = Math.min(160, Math.round(n))
  }
  if ('w' in patch && patch.w != null) {
    next.w = Math.min(12, Math.max(2, Math.round(Number(patch.w))))
  }
  if ('h' in patch && patch.h != null) {
    next.h = Math.min(24, Math.max(2, Math.round(Number(patch.h))))
  }
  if ('seriesItemStyles' in patch) {
    const s = patch.seriesItemStyles
    if (s == null || (typeof s === 'object' && Object.keys(s).length === 0)) delete next.seriesItemStyles
    else next.seriesItemStyles = { ...s }
  }
  gridLayout.value.splice(idx, 1, next)
  inspectorItem.value = next
  onLayoutUpdated()
}

function onInspectorBarColorChange(val) {
  if (val == null || val === '') patchInspectorGridItem({ barColor: null })
  else patchInspectorGridItem({ barColor: val })
}

function resetInspectorBarStyle() {
  const it = inspectorItem.value
  if (!it) return
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(it.i))
  if (idx < 0) return
  const cur = gridLayout.value[idx]
  const next = { ...cur }
  delete next.barColor
  delete next.barMaxWidth
  gridLayout.value.splice(idx, 1, next)
  inspectorItem.value = next
  onLayoutUpdated()
}

const legacyPreviewCards = computed(() =>
  extractLegacyChartCards(board.value?.layoutJson, `dge-${board.value?.id || 'x'}`)
)

/**
 * 左侧图表库：数据仅来自 allPinnedSummaries（全库接口或「逐看板拉组件」汇总），即<strong>全部看板</strong>已钉图表，不含仅当前看板兜底。
 */
const pinnedLibraryRows = computed(() => {
  const rows = []
  for (const s of allPinnedSummaries.value || []) {
    const rawId = s.chart_id ?? s.chartId ?? s.CHART_ID ?? s.chartid
    if (rawId == null) continue
    const idStr = String(rawId).trim()
    if (!idStr) continue
    const p = chartPayloadById.value[idStr]
    const snap = p ? parseChartSnapshot(p.chartSnapshot) : {}
    const question = readableTitleFromPayload(p) || `图表 #${idStr}`
    const dnames = String(
      s.dashboardNames ?? s.dashboard_names ?? s.dashboardnames ?? ''
    ).trim()
    const dcount = Number(s.dashboardCount ?? s.dashboard_count ?? s.dashboardcount) || 0
    rows.push({
      id: Number(rawId),
      chartType: normalizeChartType(p?.chartType || snap.chartType || 'bar'),
      question,
      queryText: p?.queryText,
      tableName: String(snap.tableName || p?.queryTableName || '').trim(),
      pinnedDashboardNames: dnames,
      pinnedDashboardCount: dcount,
      executionStatus: 1
    })
  }
  rows.sort((a, b) => b.id - a.id)
  return rows
})

const displayedPinnedLibrary = computed(() => {
  const kw = String(historyKeyword.value || '').trim().toLowerCase()
  if (!kw) return pinnedLibraryRows.value
  return pinnedLibraryRows.value.filter((h) => {
    const q = String(h.question || '').toLowerCase()
    const t = String(h.tableName || '').toLowerCase()
    const d = String(h.pinnedDashboardNames || '').toLowerCase()
    return q.includes(kw) || t.includes(kw) || d.includes(kw) || String(h.id).includes(kw)
  })
})

/** 四边及四角均可缩放（interact.js 使用网格项边缘）；角部可同时改变 w 与 h */
function allSidesResizeOption() {
  return {
    edges: { top: true, right: true, bottom: true, left: true }
  }
}

const itemProps = (item) => {
  return {
    i: item.i,
    x: item.x,
    y: item.y,
    w: item.w,
    h: item.h,
    static: Boolean(item.static),
    minW: 2,
    maxW: 12,
    minH: 2,
    maxH: 24,
    resizeOption: allSidesResizeOption(),
    dragIgnoreFrom: 'input, textarea, button, .el-button, a, .el-input, .el-input__inner',
    resizeIgnoreFrom: 'input, textarea, button, .el-button, .el-input'
  }
}

const componentByItemId = computed(() => {
  const m = new Map()
  for (const c of components.value || []) {
    const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
    if (id) m.set(id, c)
  }
  return m
})

function chartIdForItem(item) {
  const c = componentByItemId.value.get(String(item.i))
  if (!c) return ''
  const raw = c.chartId ?? c.chart_id ?? c.CHART_ID
  return raw != null ? String(raw) : ''
}

function artifactIdForItem(item) {
  const c = componentByItemId.value.get(String(item?.i))
  if (!c) return ''
  const raw = c.artifactId ?? c.artifact_id ?? c.ARTIFACT_ID
  return raw != null && String(raw).trim() !== '' ? String(raw) : ''
}

function turnIdForItem(item) {
  const c = componentByItemId.value.get(String(item?.i))
  if (!c) return ''
  const raw = c.turnId ?? c.turn_id ?? c.TURN_ID
  return raw != null && String(raw).trim() !== '' ? String(raw) : ''
}

function payloadForItem(item) {
  const cid = chartIdForItem(item)
  if (!cid) return null
  return chartPayloadById.value[cid] || null
}

function compactReadableTitle(value, max = 80) {
  const text = String(value || '')
    .replace(/[\r\n]+/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/^分析完成[。.:：\s]*/u, '')
    .trim()
  if (!text) return ''
  return text.length > max ? text.slice(0, max).trim() : text
}

function parseFieldMappingFromPayload(payload) {
  const snap = parseChartSnapshot(payload?.chartSnapshot)
  const fm = snap?.fieldMapping
  return fm && typeof fm === 'object' ? fm : {}
}

function chartTypeTitleSuffix(chartType) {
  const text = String(chartType || '').trim().toLowerCase()
  if (text === 'pie') return '占比'
  if (text === 'line') return '趋势'
  if (text === 'bar') return '统计'
  if (text === 'scatter') return '分布'
  return '图'
}

function readableTitleFromPayload(payload) {
  if (!payload) return ''
  const question = compactReadableTitle(payload.queryText)
  if (question) return question
  const snap = parseChartSnapshot(payload.chartSnapshot)
  const fm = parseFieldMappingFromPayload(payload)
  const dimension = compactReadableTitle(fm.dimension, 30)
  const metric = compactReadableTitle(fm.metric, 30)
  const suffix = chartTypeTitleSuffix(payload.chartType || snap.chartType)
  if (dimension && metric) return compactReadableTitle(`${dimension}${suffix}${metric}`)
  if (dimension) return compactReadableTitle(`${dimension}${suffix}`)
  if (metric) return compactReadableTitle(`${metric}${suffix}`)
  const message = compactReadableTitle(snap?.message)
  if (message) return message
  return ''
}

/** layout_json.items 上的展示覆盖，传给 ECharts（整图 + 分项 seriesItemStyles） */
function chartUiForItem(item) {
  const o = {}
  const c = String(item.barColor || '').trim()
  if (c) o.barColor = c
  const w = Number(item.barMaxWidth)
  if (Number.isFinite(w) && w >= 8) o.barMaxWidth = w
  const sis = item.seriesItemStyles
  if (sis && typeof sis === 'object' && Object.keys(sis).length) o.seriesItemStyles = sis
  return o
}

function defaultTitleFromPayload(item) {
  const p = payloadForItem(item)
  return readableTitleFromPayload(p) || `图表 #${item.i}`
}

function displayTitleForItem(item) {
  const t = String(item.title || '').trim()
  if (t) return t
  return defaultTitleFromPayload(item)
}

function isChartOnBoard(historyId) {
  const id = String(historyId)
  return (components.value || []).some((c) => String(c.chartId ?? c.chart_id) === id)
}

/** 当前网格布局中是否已有该历史图表对应的组件卡片 */
function isChartInGridLayout(historyId) {
  const id = String(historyId)
  for (const item of gridLayout.value || []) {
    const comp = componentByItemId.value.get(String(item.i))
    if (!comp) continue
    const cid = comp.chartId ?? comp.chart_id ?? comp.CHART_ID
    if (cid != null && String(cid) === id) return true
  }
  return false
}

function startEditTitle(item) {
  editingItemId.value = String(item.i)
  titleDraft.value = displayTitleForItem(item)
  nextTick(() => {
    const wrap = document.getElementById(`dge-title-inp-${item.i}`)
    const input = wrap?.querySelector?.('input')
    input?.focus?.()
    input?.select?.()
  })
}

function commitTitleEdit(item) {
  if (editingItemId.value !== String(item.i)) return
  const v = String(titleDraft.value || '').trim()
  const def = defaultTitleFromPayload(item)
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(item.i))
  if (idx >= 0) {
    const copy = { ...gridLayout.value[idx] }
    if (v && v !== def) copy.title = v
    else delete copy.title
    gridLayout.value.splice(idx, 1, copy)
  }
  editingItemId.value = null
  titleDraft.value = ''
  onLayoutUpdated()
}

function onGridItemResized() {
  onLayoutUpdated()
}

function onLayoutUpdated() {
  nextTick(() => {
    window.dispatchEvent(new Event('resize'))
    requestAnimationFrame(() => window.dispatchEvent(new Event('resize')))
  })
}

async function pinHistoryChart(row) {
  const chartId = row?.id
  if (chartId == null) {
    ElMessage.warning('无效的图表')
    return
  }
  if (isChartOnBoard(chartId)) {
    if (isChartInGridLayout(chartId)) {
      ElMessage.info('该图表已在当前画布中')
      return
    }
    pinningId.value = chartId
    try {
      await loadBoard()
      ElMessage.success('已同步到画布')
    } catch (e) {
      ElMessage.error(e.message || '同步失败')
    } finally {
      pinningId.value = null
    }
    return
  }
  if (Number(row.executionStatus ?? row.execution_status) !== 1) {
    ElMessage.warning('仅成功执行的查询可加入看板')
    return
  }
  pinningId.value = chartId
  restoreSessionHeader()
  try {
    const res = await axios.post(`${props.apiBase}/api/c/dashboards/${board.value.id}/pin-chart`, {
      chartId: Number(chartId)
    })
    if (res.data.code !== 200) throw new Error(res.data.message || '加入失败')
    ElMessage.success('已加入画布')
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message || '加入失败')
  } finally {
    pinningId.value = null
  }
}

async function removeGridItem(item) {
  const compId = String(item.i)
  try {
    await ElMessageBox.confirm('从看板移除此图表组件？（可稍后在左侧历史再次加入）', '确认移除', {
      type: 'warning'
    })
  } catch {
    return
  }
  removingId.value = compId
  restoreSessionHeader()
  try {
    const res = await axios.delete(
      `${props.apiBase}/api/c/dashboards/${board.value.id}/components/${compId}`
    )
    if (res.data.code !== 200) throw new Error(res.data.message || '移除失败')
    ElMessage.success('已移除')
    await loadBoard()
  } catch (e) {
    ElMessage.error(e.message || '移除失败')
  } finally {
    removingId.value = null
  }
}

async function loadAllPinnedSummaries() {
  restoreSessionHeader()
  const res = await axios.get(`${props.apiBase}/api/c/dashboards/pinned-charts`)
  if (res.data.code !== 200) throw new Error(res.data.message || '加载各看板已钉图表失败')
  allPinnedSummaries.value = Array.isArray(res.data.data) ? res.data.data : []
}

/**
 * 当 /pinned-charts 失败或返回空时：遍历「看板列表 + 各看板 components」汇总全部已钉 chart（与后端 SQL 语义一致，纯前端兜底）。
 */
async function loadPinnedSummariesByScanningDashboards() {
  restoreSessionHeader()
  const listRes = await axios.get(`${props.apiBase}/api/c/dashboards`)
  if (listRes.data.code !== 200) return
  const list = Array.isArray(listRes.data.data) ? listRes.data.data : []
  const byChart = new Map()
  const concurrency = 6
  for (let i = 0; i < list.length; i += concurrency) {
    const chunk = list.slice(i, i + concurrency)
    await Promise.all(
      chunk.map(async (b) => {
        const bid = b.id ?? b.ID
        const bname = String(b.name ?? '').trim() || `看板 ${bid}`
        if (bid == null) return
        try {
          const cr = await axios.get(`${props.apiBase}/api/c/dashboards/${bid}/components`)
          if (cr.data.code !== 200) return
          const comps = Array.isArray(cr.data.data) ? cr.data.data : []
          for (const c of comps) {
            const cid = c.chart_id ?? c.chartId ?? c.CHART_ID
            if (cid == null) continue
            const key = String(cid).trim()
            if (!key) continue
            if (!byChart.has(key)) byChart.set(key, new Set())
            byChart.get(key).add(bname)
          }
        } catch {
          // 单个看板无权或无组件时跳过
        }
      })
    )
  }
  const rows = []
  for (const [chartIdStr, names] of byChart) {
    const arr = [...names].sort()
    rows.push({
      chart_id: Number(chartIdStr),
      dashboard_count: arr.length,
      dashboard_names: arr.join('、')
    })
  }
  rows.sort((a, b) => b.chart_id - a.chart_id)
  allPinnedSummaries.value = rows
}

async function loadChartPayloads() {
  const idSet = new Set()
  for (const c of components.value || []) {
    const raw = c.chartId ?? c.chart_id ?? c.CHART_ID
    if (raw == null) continue
    const n = Number(raw)
    if (Number.isFinite(n)) idSet.add(n)
  }
  for (const s of allPinnedSummaries.value || []) {
    const raw = s.chart_id ?? s.chartId ?? s.CHART_ID ?? s.chartid
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
  const res = await axios.post(`${props.apiBase}/api/chat/history/charts-batch`, { ids: unique })
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
  try {
    await loadAllPinnedSummaries()
  } catch {
    allPinnedSummaries.value = []
  }
  if (!allPinnedSummaries.value.length) {
    try {
      await loadPinnedSummariesByScanningDashboards()
    } catch {
      // 保持空列表
    }
  }
  const parsed = parseDashboardLayout(board.value?.layoutJson)
  legacyCards.value = Array.isArray(parsed.cards) ? parsed.cards : []
  Object.assign(canvasStyle, normalizeCanvasStyle(parsed.canvasStyle))
  gridLayout.value = mergeGridItemsWithComponents(parsed.items, components.value)
  await loadChartPayloads()
  await nextTick()
  onLayoutUpdated()
}

async function saveLayout() {
  if (!board.value?.id) return
  saving.value = true
  restoreSessionHeader()
  try {
    const layoutJson = serializeLayoutForApi(gridLayout.value, legacyCards.value, canvasStyle)
    const res = await axios.put(`${props.apiBase}/api/c/dashboards/${board.value.id}`, {
      name: board.value.name,
      description: board.value.description || null,
      layoutJson,
      isPublic: Boolean(board.value.isPublic)
    })
    if (res.data.code !== 200) throw new Error(res.data.message || '保存失败')
    ElMessage.success('布局已保存')
    emit('saved')
    innerVisible.value = false
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function onClosed() {
  board.value = null
  gridLayout.value = []
  legacyCards.value = []
  components.value = []
  chartPayloadById.value = {}
  allPinnedSummaries.value = []
  historyKeyword.value = ''
  editingItemId.value = null
  inspectorOpen.value = false
  inspectorItem.value = null
  canvasStyleDialogOpen.value = false
  Object.assign(canvasStyle, normalizeCanvasStyle())
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open || !props.initialRow?.id) return
    sideListLoading.value = true
    try {
      await loadBoard()
    } catch (e) {
      ElMessage.error(e.message || '加载失败')
      innerVisible.value = false
    } finally {
      sideListLoading.value = false
    }
  }
)
</script>

<style scoped>
.dge-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.dge-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}
.dge-actions {
  display: flex;
  gap: 8px;
}
.dge-body {
  min-height: 200px;
  --vgl-resizer-size: 14px;
  --vgl-resizer-border-color: #3b82f6;
  --vgl-resizer-border-width: 2px;
}
.dge-hint {
  margin: 0 0 12px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.55;
  color: #374151;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
}
.dge-legacy-tip {
  margin-bottom: 12px;
}
.dge-alert {
  margin-bottom: 16px;
}
.dge-alert-p {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.65;
  color: #606266;
}
.dge-empty-workbench {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.dge-workbench {
  display: flex;
  gap: 16px;
  align-items: stretch;
  min-height: 62vh;
}
.dge-side {
  width: 300px;
  flex-shrink: 0;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafafa;
  padding: 10px;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 140px);
}
.dge-side-head {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 10px;
  color: #111827;
}
.dge-side-search {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.dge-side-search .el-input {
  flex: 1;
}
.dge-side-list {
  flex: 1;
  overflow-y: auto;
  min-height: 120px;
}
.dge-side-row {
  padding: 8px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.dge-side-row-main {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.dge-hid {
  font-size: 11px;
  color: #9ca3af;
}
.dge-side-q {
  font-size: 12px;
  color: #374151;
  line-height: 1.45;
  margin-bottom: 4px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.dge-side-meta {
  font-size: 11px;
  color: #9ca3af;
  line-height: 1.4;
  margin-bottom: 6px;
  word-break: break-word;
}
.dge-load-more {
  width: 100%;
  margin-top: 8px;
}
.dge-canvas-col {
  flex: 1;
  min-width: 0;
}
.dge-export-stage {
  position: relative;
  width: 100%;
}
.dge-stage-logo {
  position: absolute;
  right: 16px;
  bottom: 16px;
  max-width: 120px;
  max-height: 48px;
  object-fit: contain;
  pointer-events: none;
  z-index: 2;
}
.dge-logo-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.dge-logo-preview {
  margin-top: 8px;
  padding: 8px;
  background: #f9fafb;
  border: 1px dashed #e5e7eb;
  border-radius: 8px;
  max-width: 200px;
}
.dge-logo-preview img {
  display: block;
  max-width: 100%;
  max-height: 80px;
  object-fit: contain;
}
.dge-form-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}
.dge-legacy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
  flex: 1;
  min-width: 280px;
}
.dge-grid-wrap {
  width: 100%;
}
.dge-grid {
  min-height: max(55vh, 480px);
}
.dge-grid-item {
  touch-action: none;
}
.dge-card {
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}
.dge-card-meta {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: #6b7280;
  border-bottom: 1px solid #f3f4f6;
  background: #fafafa;
}
.dge-card-titlewrap {
  flex: 1;
  min-width: 0;
  cursor: text;
}
.dge-card-title {
  font-weight: 600;
  color: #111827;
  font-size: 13px;
  line-height: 1.4;
  word-break: break-word;
}
.dge-card-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dge-chart-id {
  font-size: 11px;
  color: #9ca3af;
}
.dge-chart-host {
  flex: 1;
  min-height: 0;
  padding: 6px 8px 8px;
}
.dge-chart-fallback {
  height: 100%;
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
  font-size: 13px;
}
.dge-inspector-lead {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.55;
  color: #4b5563;
}
.dge-inspector-desc {
  margin-bottom: 16px;
}
.dge-inspector-block {
  margin-bottom: 16px;
}
.dge-inspector-label {
  font-size: 12px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 6px;
}
.dge-inspector-pre {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.45;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow: auto;
}
.dge-inspector-sql :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
.dge-inspector-card-style {
  padding: 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.dge-inspector-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.dge-inspector-row:last-child {
  margin-bottom: 0;
}
.dge-inspector-row--slider {
  align-items: center;
}
.dge-inspector-k {
  width: 112px;
  flex-shrink: 0;
  font-size: 13px;
  color: #374151;
}
.dge-inspector-slider {
  flex: 1;
  min-width: 160px;
}
.dge-inspector-sub {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.5;
  color: #6b7280;
}
.dge-series-list {
  max-height: min(48vh, 360px);
  overflow-y: auto;
  margin-bottom: 10px;
  padding-right: 4px;
}
.dge-series-item-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  margin-bottom: 6px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.dge-series-item-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dge-series-item-name {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dge-series-item-val {
  font-size: 12px;
  color: #6b7280;
}
</style>

<style>
.dge-dialog .el-dialog__body {
  padding-top: 8px;
}

.dge-grid .vgl-item .dge-card {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
}

/* 改用四边 interact 缩放，隐藏库自带右下角小块，避免与边缘拖拽重复 */
.dge-grid .vgl-item__resizer {
  display: none !important;
}
</style>
