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
          <span class="dge-title-badge">设计看板</span>
          <div class="dge-title-main">
            <div class="dge-title-board-row">
              <h2 class="dge-title-board" :title="boardDisplayName">{{ boardDisplayName }}</h2>
              <div v-if="board" class="dge-title-tags">
                <el-tag size="small" :type="boardIsPublicTag ? 'warning' : 'info'" effect="plain">
                  {{ boardVisibilityText }}
                </el-tag>
                <el-tag v-if="boardIsCopyTag" size="small" effect="plain">副本</el-tag>
                <el-tag v-if="saveAsMode" size="small" type="warning" effect="plain">只读 · 须另存为</el-tag>
                <el-tooltip
                  v-if="saveAsMode"
                  placement="bottom"
                  :show-after="200"
                  popper-class="dge-help-popper"
                >
                  <template #content>
                    <p class="dge-help-tip">{{ saveAsReadonlyTip }}</p>
                  </template>
                  <span class="dge-help" tabindex="0" aria-label="只读说明">?</span>
                </el-tooltip>
              </div>
            </div>
          </div>
        </div>
        <div class="dge-actions">
          <el-button
            v-if="board && !saveAsMode"
            class="dge-palette-trigger"
            :type="componentPaletteOpen ? 'primary' : 'default'"
            @click="componentPaletteOpen = !componentPaletteOpen"
          >
            基础组件
          </el-button>
          <el-button v-if="board && !saveAsMode" @click="openCanvasStyleDialog">画布样式</el-button>
          <el-button
            v-if="board && !saveAsMode"
            type="primary"
            :loading="saving"
            @click="saveLayout"
          >
            保存布局
          </el-button>
          <el-button
            v-if="board && saveAsMode"
            type="primary"
            :loading="saving"
            @click="openSaveAsDialog"
          >
            另存为
          </el-button>
          <el-button @click="innerVisible = false">关闭</el-button>
        </div>
      </div>
    </template>

    <div v-if="board" class="dge-body">
      <div
        ref="workbenchRef"
        class="dge-workbench"
        :class="{
          'is-side-collapsed': !sidePanelOpen,
          'is-side-resizing': isResizingSide,
          'is-save-as-readonly': saveAsMode
        }"
      >
        <aside
          v-show="sidePanelOpen"
          class="dge-side"
          :style="{ width: `${sidePanelWidth}px` }"
        >
          <div class="dge-side-header">
            <div class="dge-side-head-text">
              <span class="dge-side-head-title">
                已钉图表
                <el-tooltip
                  v-if="!gridLayout.length"
                  placement="right"
                  :show-after="200"
                  popper-class="dge-help-popper"
                >
                  <template #content>
                    <p class="dge-help-tip">{{ emptyCanvasTip }}</p>
                  </template>
                  <span class="dge-help" tabindex="0" aria-label="画布为空说明">?</span>
                </el-tooltip>
              </span>
              <span class="dge-side-head-sub">共 {{ displayedPinnedLibrary.length }} 项</span>
            </div>
            <button type="button" class="dge-side-lib-btn" @click="openChartLibrary">
              <el-icon :size="14"><Collection /></el-icon>
              <span>图表库</span>
            </button>
          </div>
          <div class="dge-side-search">
            <el-input
              v-model="historyKeyword"
              clearable
              size="small"
              placeholder="搜索图表名称"
              :prefix-icon="Search"
            />
          </div>
          <div v-loading="sideListLoading" class="dge-side-list">
            <article
              v-for="h in displayedPinnedLibrary"
              :key="h.id"
              :class="['dge-side-card', { 'is-on-canvas': isChartInGridLayout(h.id) }]"
            >
              <div class="dge-side-card-top">
                <span :class="['dge-side-type', `dge-side-type--${h.chartType || 'bar'}`]">
                  {{ sideChartTypeLabel(h.chartType) }}
                </span>
                <span v-if="isChartInGridLayout(h.id)" class="dge-side-status">画布中</span>
              </div>
              <h4 class="dge-side-card-title" :title="h.question || h.queryText">
                {{ h.question || h.queryText || '（无摘要）' }}
              </h4>
              <p v-if="h.tableName" class="dge-side-card-meta">{{ h.tableName }}</p>
              <button
                type="button"
                class="dge-side-card-btn"
                :class="isChartInGridLayout(h.id) ? 'is-remove' : 'is-add'"
                :disabled="saveAsMode || pinningId === h.id"
                @click="onPinnedLibraryAction(h)"
              >
                <span v-if="pinningId === h.id" class="dge-side-card-btn-loading">处理中…</span>
                <template v-else>
                  <el-icon :size="14">
                    <Remove v-if="isChartInGridLayout(h.id)" />
                    <Plus v-else />
                  </el-icon>
                  <span>{{ isChartInGridLayout(h.id) ? '从画布移除' : '加入画布' }}</span>
                </template>
              </button>
            </article>
            <el-empty
              v-if="!sideListLoading && !displayedPinnedLibrary.length"
              :image-size="72"
              description="暂未钉入图表"
              class="dge-side-empty"
            >
              <p class="dge-side-empty-tip">请先在对话查询中将图表钉入本看板</p>
            </el-empty>
          </div>
        </aside>
        <div
          class="dge-side-rail"
          role="separator"
          :title="sidePanelOpen ? '收起图表库' : '展开图表库'"
        >
          <button
            type="button"
            class="dge-side-rail-toggle"
            :aria-label="sidePanelOpen ? '收起图表库' : '展开图表库'"
            @click="toggleSidePanel"
          >
            <el-icon :size="12">
              <DArrowLeft v-if="sidePanelOpen" />
              <DArrowRight v-else />
            </el-icon>
          </button>
          <div
            class="dge-side-rail-grip"
            title="拖拽调整宽度，左拖至底可收起图表库"
            @mousedown="onSideResizeStart"
          >
            <span v-for="dot in 6" :key="dot" class="dge-side-rail-dot" />
          </div>
        </div>
        <div class="dge-main-col">
          <div v-if="!gridLayout.length && legacyPreviewCards.length" class="dge-legacy-grid">
            <LegacyInlineChart
              v-for="c in legacyPreviewCards"
              :key="c._renderKey"
              :title="c.title"
              :chart-type="c.chartType"
              :data="c.data"
            />
          </div>
          <div v-else class="dge-canvas-col">
          <div
            ref="exportStageRef"
            class="dge-export-stage"
            :class="{ 'is-palette-drop-active': paletteDropActive }"
            :style="canvasStageInlineStyle"
          >
          <div ref="gridWrapRef" class="dge-grid-wrap">
            <div
              v-if="paletteDropPreview.visible"
              class="dge-drop-preview"
              :style="paletteDropPreview.style"
            />
        <GridLayout
          v-if="gridLayout.length"
          v-model:layout="gridLayout"
          :col-num="DASHBOARD_GRID_COL_NUM"
          :row-height="rowHeight"
          :margin="gridMargin"
          :is-draggable="!saveAsMode"
          :is-resizable="!saveAsMode"
          :vertical-compact="false"
          class="dge-grid dashboard-grid-canvas"
          :style="gridCanvasStyle"
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
                <div
                  v-if="isBasicWidgetItem(item)"
                  class="dge-widget-shell"
                  :class="{ 'is-selected': selectedItemId === String(item.i) }"
                  @click.stop="selectGridItem(item)"
                >
                  <component
                    :is="basicWidgetViewForItem(item)"
                    :config="basicWidgetConfigForItem(item)"
                    :pinned="Boolean(item.static)"
                    :interactive="!saveAsMode"
                    @edit="openWidgetInspectorById(String(item.i))"
                    @remove="removeGridItemById(String(item.i))"
                    @pin="toggleWidgetPin(item)"
                  />
                </div>
                <DashboardChartGridItem
                  v-else
                  :selected="selectedItemId === String(item.i)"
                  :interactive="!saveAsMode"
                  :pinned="Boolean(item.static)"
                  :editing="editingItemId === String(item.i)"
                  :title-draft="titleDraft"
                  :display-title="displayTitleForItem(item)"
                  :title-style="cardTitleStyleForItem(item)"
                  :card-style="cardChromeStyleForItem(item)"
                  :title-input-id="'dge-title-inp-' + item.i"
                  :can-edit="Boolean(payloadForItem(item))"
                  @select="selectGridItem(item)"
                  @pin="toggleWidgetPin(item)"
                  @edit="openChartInspector(item)"
                  @remove="removeGridItem(item)"
                  @start-edit-title="startEditTitle(item)"
                  @commit-title="commitTitleEdit(item)"
                  @update:title-draft="titleDraft = $event"
                >
                  <DashboardChart
                    v-if="payloadForItem(item)"
                    :key="chartRenderKeyForItem(item)"
                    :payload="payloadForItem(item)"
                    :chart-ui="chartUiForItem(item)"
                    hide-title
                    @refresh="refreshChartPayloadsFromDynamicConfig"
                  />
                  <div v-else class="dge-chart-fallback">
                    {{ artifactIdForItem(item) ? '高级分析图表数据暂不可用' : (chartIdForItem(item) ? '图表数据暂不可用' : '未关联 chart_id') }}
                  </div>
                </DashboardChartGridItem>
              </GridItem>
            </GridLayout>
            <div v-else class="dge-empty-canvas-drop">
              <p>从左侧拖动或点击「基础组件」加入画布</p>
            </div>
          </div>
          </div>
        </div>
        </div>
      </div>
    </div>

    <DashboardComponentPalette
      v-if="!saveAsMode"
      v-model="componentPaletteOpen"
      @pick-basic="onPickBasicComponent"
      @drag-start="onPaletteDragStart"
      @drag-end="onPaletteDragEnd"
    />

    <component
      :is="activeBasicWidgetInspector"
      v-if="activeBasicWidgetInspector"
      v-model="widgetInspectorOpen"
      :config="basicWidgetInspectorConfig"
      :widget-title="basicWidgetInspectorTitle"
      @update:config="onBasicWidgetInspectorConfigUpdate"
      @update:widget-title="onBasicWidgetTitleDraft"
      @commit:widget-title="commitBasicWidgetTitle"
      @closed="onWidgetInspectorClosed"
    />

    <el-dialog
      v-model="basicWidgetNameDialogVisible"
      title="命名组件"
      width="440px"
      append-to-body
      :close-on-click-modal="false"
      :show-close="false"
    >
      <p class="dge-name-hint">拖拽或添加基础组件后须先命名，便于在画布与协同批注中识别。</p>
      <el-input
        ref="basicWidgetNameInputRef"
        v-model="basicWidgetNameDraft"
        maxlength="64"
        show-word-limit
        placeholder="例如：销售说明、首页轮播"
        @keyup.enter="confirmBasicWidgetName"
      />
      <template #footer>
        <el-button @click="cancelBasicWidgetName">取消并移除</el-button>
        <el-button type="primary" @click="confirmBasicWidgetName">确定并继续</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="canvasStyleDialogOpen"
      title="画布样式"
      width="480px"
      append-to-body
      destroy-on-close
    >
      <el-form label-width="88px" size="small">
        <el-form-item label="背景设置">
          <el-select v-model="canvasStyle.backgroundType" style="width: 100%">
            <el-option label="无" value="none" />
            <el-option label="背景颜色" value="color" />
            <el-option label="背景图片" value="image" />
            <el-option label="图片链接" value="url" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canvasStyle.backgroundType === 'color'" label="默认填充色">
          <el-color-picker v-model="canvasStyle.backgroundColor" show-alpha />
        </el-form-item>
        <template v-else-if="canvasStyle.backgroundType === 'image'">
          <el-form-item label="背景图片">
            <div class="dge-logo-row">
              <el-upload
                :show-file-list="false"
                accept="image/*"
                :auto-upload="false"
                :on-change="onBackgroundImageFileChange"
              >
                <el-button size="small">选择图片</el-button>
              </el-upload>
              <el-button
                size="small"
                :disabled="!canvasStyle.backgroundImageDataUrl"
                @click="clearBackgroundImage"
              >
                清除
              </el-button>
            </div>
            <div v-if="canvasStyle.backgroundImageDataUrl" class="dge-logo-preview">
              <img :src="canvasStyle.backgroundImageDataUrl" alt="背景预览" />
            </div>
            <p class="dge-form-hint">本地读入 Base64，随布局保存。单张 &lt; 5MB。</p>
          </el-form-item>
        </template>
        <el-form-item v-else-if="canvasStyle.backgroundType === 'url'" label="图片链接">
          <el-input
            v-model="canvasStyle.backgroundImageUrl"
            placeholder="https://example.com/bg.png"
            clearable
          />
        </el-form-item>
        <el-form-item label="圆角 (px)">
          <el-slider v-model="canvasStyle.borderRadius" :min="0" :max="32" show-input />
        </el-form-item>
        <el-form-item label="内边距 (px)">
          <el-slider v-model="canvasStyle.padding" :min="0" :max="48" show-input />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="canvasStyleDialogOpen = false">完成</el-button>
      </template>
    </el-dialog>

    <DashboardChartWidgetInspector
      v-model="inspectorOpen"
      :grid-item="inspectorItem"
      :payload="inspectorPayload"
      :raw-chart-type="inspectorChartType"
      :table-name="inspectorTableName"
      :data-rows="inspectorDataRows"
      :overlap-hint="inspectorOverlapHint"
      @patch-grid-item="patchInspectorGridItem"
      @collapse="collapseChartInspector"
      @save="onInspectorSave"
      @reset-style="onInspectorResetStyle"
      @reset-layout="onInspectorResetLayout"
      @cancel="onInspectorCancel"
      @closed="onInspectorDrawerClosed"
    />

    <button
      v-if="inspectorCollapsed && inspectorItem"
      type="button"
      class="dge-inspector-fab"
      title="展开图表编辑"
      @click="reopenChartInspector"
    >
      <span class="dge-inspector-fab-icon">✎</span>
      <span class="dge-inspector-fab-text">图表编辑</span>
    </button>

    <el-dialog
      v-model="saveMetaVisible"
      title="保存看板"
      width="420px"
      append-to-body
      :close-on-click-modal="false"
      @closed="saveMetaPending = false"
    >
      <p class="dge-save-meta-hint">请选择开放类型。「仅保存」将保存布局并设为待发布；「保存并发布」将同时发布看板。</p>
      <el-form label-width="88px">
        <el-form-item label="开放类型" required>
          <el-radio-group v-model="saveMetaForm.isPublic">
            <el-radio :label="false">私密</el-radio>
            <el-radio :label="true">公共</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveMetaVisible = false">取消</el-button>
        <el-button :loading="saving" @click="confirmSaveLayout">仅保存</el-button>
        <el-button type="primary" :loading="saving" @click="confirmSaveAndPublish">保存并发布</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="saveAsVisible"
      title="另存为看板"
      width="480px"
      append-to-body
      :close-on-click-modal="false"
    >
      <p class="dge-save-meta-hint">将当前布局另存为新看板副本，原看板不会被直接覆盖。</p>
      <p class="dge-save-meta-hint dge-save-meta-hint--sub">{{ saveAsDialogHintSub }}</p>
      <el-form label-width="88px">
        <el-form-item label="开放类型" required>
          <el-radio-group v-model="saveAsForm.isPublic">
            <el-radio :label="false">私密</el-radio>
            <el-radio :label="true">公共</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="看板名称" required>
          <el-input v-model="saveAsForm.name" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="所属分组">
          <el-tree-select
            :key="saveAsGroupTreeKey"
            v-model="saveAsForm.groupId"
            :data="saveAsGroupSelectTree"
            :props="groupTreeSelectProps"
            check-strictly
            clearable
            default-expand-all
            placeholder="根目录"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveAsVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmSaveAs">确认另存为</el-button>
      </template>
    </el-dialog>

    <DashboardChartLibraryDrawer
      v-model="chartLibraryOpen"
      :api-base="props.apiBase"
      :dashboard-id="board?.id"
      :readonly="saveAsMode"
      :is-on-board="isChartOnBoard"
      @pinned="onChartLibraryPinned"
    />
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import axios from 'axios'
import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DArrowLeft, DArrowRight, Search, Collection, Plus, Remove } from '@element-plus/icons-vue'
import { GridLayout, GridItem } from 'grid-layout-plus'
import { restoreSessionHeader } from '../../store/session'
import { isPublicSaveAsDesign, decorateGroupNodesOnly, boardVisibilityLabel, boardIsPublic } from '../../utils/dashboardManageTree.js'
import {
  parseDashboardLayout,
  mergeGridItemsWithComponents,
  serializeLayoutForApi,
  extractLegacyChartCards,
  CANVAS_BACKGROUND_TYPES,
  CANVAS_BACKGROUND_IMAGE_MAX_BYTES,
  CANVAS_BACKGROUND_IMAGE_MAX_DATA_URL_LEN,
  normalizeCanvasStyle,
  buildAdvancedAnalysisPreviewCard,
  DASHBOARD_GRID_COL_NUM,
  DASHBOARD_GRID_MARGIN,
  buildCanvasStageInlineStyle
} from '../../utils/dashboardGrid.js'
import { useDashboardGridCanvasBackground } from '../../utils/dashboardGridCanvas.js'
import {
  DASHBOARD_BASIC_WIDGET_DEFAULT_H,
  DASHBOARD_BASIC_WIDGET_DEFAULT_W,
  isPaletteComponentAvailable
} from '../../utils/dashboardBasicComponents.js'
import {
  gridItemPixelRect,
  nextGridRowY,
  pixelToGridCell
} from '../../utils/dashboardGridDrop.js'
import {
  createVideoWidgetItem,
  isBasicWidgetItem,
  normalizeVideoWidgetConfig,
  videoWidgetConfigEqual
} from '../../utils/dashboardWidgetVideo.js'
import { normalizeTextWidgetConfig, textWidgetConfigEqual } from '../../utils/dashboardWidgetText.js'
import { createCarouselWidgetItem } from '../../utils/dashboardWidgetCarousel.js'
import { createImageWidgetItem } from '../../utils/dashboardWidgetImage.js'
import { createTextWidgetItem } from '../../utils/dashboardWidgetText.js'
import { basicWidgetGridItemProps } from '../../utils/dashboardBasicWidgetGrid.js'
import {
  basicWidgetLabelForItem,
  resolveBasicWidgetEntry
} from '../../utils/dashboardBasicWidgetRegistry.js'
import '../../styles/dashboard-grid-canvas.css'
import DashboardChart from '../../components/dashboard/DashboardChart.vue'
import DashboardChartGridItem from '../../components/dashboard/DashboardChartGridItem.vue'
import DashboardChartWidgetInspector from '../../components/dashboard/DashboardChartWidgetInspector.vue'
import DashboardComponentPalette from '../../components/dashboard/DashboardComponentPalette.vue'
import DashboardChartLibraryDrawer from '../../components/dashboard/DashboardChartLibraryDrawer.vue'
import LegacyInlineChart from '../../components/dashboard/LegacyInlineChart.vue'
import {
  normalizeChartType
} from '../../utils/chartOptionFromSnapshot.js'
import { historyChartTypeLabel } from '../../utils/chatHistoryItem.js'
import {
  chartUiFromGridItem,
  cloneChartStyleFields,
  ensureLayoutOrigin,
  mergeChartStyle,
  mergeLayoutConstraints
} from '../../utils/chartUiConfig.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  initialRow: { type: Object, default: null },
  apiBase: { type: String, default: 'http://localhost:8080' },
  /** 管理员端：保存布局时选择开放类型；另存为分组恒为平台分组 */
  promptVisibilityOnSave: { type: Boolean, default: false },
  groupSelectTree: { type: Array, default: () => [] },
  /** 用户端：为 true 时另存为「私密」直接使用 groupSelectTree 作为个人分组树 */
  useParentGroupTreeForSaveAs: { type: Boolean, default: false },
  /** 用户端另存为「私密」可选的个人分组树 */
  saveAsPersonalGroupTree: { type: Array, default: undefined },
  /** 管理员端另存为可选的平台分组树（与开放类型无关） */
  saveAsPlatformGroupTree: { type: Array, default: undefined },
  /** 打开并加载完成后自动导出：png | pdf */
  autoExport: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'saved', 'auto-export-done'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const board = ref(null)
const gridLayout = ref([])
const legacyCards = ref([])
const components = ref([])
const chartPayloadById = ref({})
let dynamicRefreshInFlight = false
const saving = ref(false)
const rowHeight = 44
const gridMargin = DASHBOARD_GRID_MARGIN
const gridWrapRef = ref(null)
const { gridCanvasStyle, measure: measureGridCanvas, attach: attachGridCanvas } = useDashboardGridCanvasBackground(
  gridWrapRef,
  { rowHeight, interactive: true }
)

const SIDE_PANEL_STORAGE_KEY = 'insight-spark.dashboard-editor.side-panel'
const SIDE_PANEL_WIDTH_DEFAULT = 300
const SIDE_PANEL_MIN_EXPANDED = 180
const SIDE_PANEL_MAX = 520
/** 指针位置低于此宽度（px）时视为完全收起 */
const SIDE_PANEL_COLLAPSE_THRESHOLD = 48

/** 筛选左侧「本看板已钉图表」列表（仅客户端过滤） */
const historyKeyword = ref('')
const sidePanelOpen = ref(true)
const sidePanelWidth = ref(SIDE_PANEL_WIDTH_DEFAULT)
const isResizingSide = ref(false)
const workbenchRef = ref(null)
let sidePanelLastExpandedWidth = SIDE_PANEL_WIDTH_DEFAULT
const sideListLoading = ref(false)
const pinningId = ref(null)
const removingId = ref(null)
const basicWidgetRemovingId = ref('')

const editingItemId = ref(null)
const titleDraft = ref('')

const inspectorOpen = ref(false)
const inspectorCollapsed = ref(false)
const inspectorItem = ref(null)
const inspectorSnapshot = ref(null)

const selectedItemId = ref('')
const widgetInspectorOpen = ref(false)
const widgetInspectorItemId = ref('')
const widgetInspectorKind = ref('')
const widgetTitleDraft = ref('')
const basicWidgetNameDialogVisible = ref(false)
const basicWidgetNameDraft = ref('')
const basicWidgetNamePending = ref(null)
const basicWidgetNameInputRef = ref(null)

const paletteDragType = ref('')
const paletteDropActive = ref(false)
const paletteDropPreview = ref({ visible: false, style: {} })
let paletteWindowDragOver = null
let paletteWindowDrop = null
let paletteWindowDragEnd = null

const canvasStyle = reactive(normalizeCanvasStyle())
const canvasStyleDialogOpen = ref(false)
const componentPaletteOpen = ref(false)
const exportStageRef = ref(null)
const exportingPng = ref(false)
const exportingPdf = ref(false)

const canvasStageInlineStyle = computed(() => buildCanvasStageInlineStyle(canvasStyle))

function ensureCanvasBackgroundType() {
  const t = String(canvasStyle.backgroundType || '').trim()
  if (!['none', 'color', 'image', 'url'].includes(t)) {
    canvasStyle.backgroundType = CANVAS_BACKGROUND_TYPES.COLOR
  }
}

function openCanvasStyleDialog() {
  if (!requireSaveAsBeforeMutate()) return
  ensureCanvasBackgroundType()
  canvasStyleDialogOpen.value = true
}

const activeBasicWidgetInspector = computed(() => {
  return resolveBasicWidgetEntry(widgetInspectorKind.value)?.inspector || null
})

const basicWidgetInspectorConfig = computed(() => {
  const id = widgetInspectorItemId.value
  const entry = resolveBasicWidgetEntry(widgetInspectorKind.value)
  if (!id || !entry) return entry?.defaultConfig?.() || {}
  const item = gridLayout.value.find((x) => String(x.i) === String(id))
  return entry.configForItem(item)
})

const basicWidgetInspectorTitle = computed(() => {
  const id = widgetInspectorItemId.value
  if (!id) return widgetTitleDraft.value
  const item = gridLayout.value.find((x) => String(x.i) === String(id))
  const saved = String(item?.title || '').trim()
  if (saved) return saved
  if (widgetTitleDraft.value) return widgetTitleDraft.value
  const entry = resolveBasicWidgetEntry(item)
  return entry ? `${entry.label}组件` : ''
})

function basicWidgetViewForItem(item) {
  return resolveBasicWidgetEntry(item)?.widget || null
}

function basicWidgetConfigForItem(item) {
  const entry = resolveBasicWidgetEntry(item)
  return entry ? entry.configForItem(item) : {}
}

function selectGridItem(item) {
  selectedItemId.value = String(item.i)
}

function openWidgetInspector(item) {
  openWidgetInspectorById(String(item?.i || ''))
}

function openWidgetInspectorById(id) {
  if (!requireSaveAsBeforeMutate()) return
  const item = gridLayout.value.find((x) => String(x.i) === String(id))
  const entry = resolveBasicWidgetEntry(item)
  if (!item || !entry) return
  widgetInspectorKind.value = entry.kind
  widgetInspectorItemId.value = String(item.i)
  widgetTitleDraft.value = String(item.title || '').trim() || `${entry.label}组件`
  selectedItemId.value = String(item.i)
  widgetInspectorOpen.value = true
}

function removeGridItemById(id) {
  if (!requireSaveAsBeforeMutate()) return
  const item = gridLayout.value.find((x) => String(x.i) === String(id))
  if (item) removeGridItem(item)
}

function toggleWidgetPin(item) {
  if (!requireSaveAsBeforeMutate()) return
  const id = String(item.i)
  const idx = gridLayout.value.findIndex((x) => String(x.i) === id)
  if (idx < 0) return
  const next = { ...gridLayout.value[idx], static: !gridLayout.value[idx].static }
  gridLayout.value.splice(idx, 1, next)
  ElMessage.success(next.static ? '已固定位置' : '已取消固定')
}

function onBasicWidgetInspectorConfigUpdate(config) {
  if (!requireSaveAsBeforeMutate()) return
  const id = widgetInspectorItemId.value
  const entry = resolveBasicWidgetEntry(widgetInspectorKind.value)
  if (!id || !entry) return
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(id))
  if (idx < 0) return
  const cur = gridLayout.value[idx]
  let normalized = config
  let equal = false
  if (entry.kind === 'video') {
    normalized = normalizeVideoWidgetConfig(config)
    equal = videoWidgetConfigEqual(cur?.widgetConfig, normalized)
  } else if (entry.kind === 'text') {
    normalized = normalizeTextWidgetConfig(config)
    equal = textWidgetConfigEqual(cur?.widgetConfig, normalized)
  }
  if (equal) return
  gridLayout.value.splice(idx, 1, { ...cur, widgetConfig: normalized })
}

function defaultBasicWidgetName(type, label = '') {
  const map = { video: '视频', text: '文本', image: '图片', carousel: '轮播图' }
  const base = map[type] || label || '基础'
  return `${base}组件`
}

function openBasicWidgetNameDialog(item, type, label = '') {
  basicWidgetNamePending.value = { itemId: item.i, type, label }
  basicWidgetNameDraft.value = defaultBasicWidgetName(type, label)
  basicWidgetNameDialogVisible.value = true
  nextTick(() => {
    const el = basicWidgetNameInputRef.value
    const input = el?.input || el?.$el?.querySelector?.('input')
    input?.focus?.()
    input?.select?.()
  })
}

function confirmBasicWidgetName() {
  const name = basicWidgetNameDraft.value.trim()
  if (!name) {
    ElMessage.warning('请输入组件名称')
    return
  }
  const pending = basicWidgetNamePending.value
  if (!pending) return
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(pending.itemId))
  if (idx >= 0) {
    gridLayout.value.splice(idx, 1, { ...gridLayout.value[idx], title: name })
    onLayoutUpdated()
  }
  basicWidgetNameDialogVisible.value = false
  basicWidgetNamePending.value = null
  ElMessage.success(`「${name}」已添加到画布`)
  openWidgetInspectorById(String(pending.itemId))
}

function cancelBasicWidgetName() {
  const pending = basicWidgetNamePending.value
  if (pending) {
    gridLayout.value = gridLayout.value.filter((x) => String(x.i) !== String(pending.itemId))
    onLayoutUpdated()
  }
  basicWidgetNameDialogVisible.value = false
  basicWidgetNamePending.value = null
  basicWidgetNameDraft.value = ''
}

function onBasicWidgetTitleDraft(title) {
  widgetTitleDraft.value = title
}

function commitBasicWidgetTitle(title) {
  if (!requireSaveAsBeforeMutate()) return
  const id = widgetInspectorItemId.value
  const name = String(title ?? widgetTitleDraft.value ?? '').trim()
  if (!name) {
    ElMessage.warning('组件名称不能为空')
    return
  }
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(id))
  if (idx < 0) return
  gridLayout.value.splice(idx, 1, { ...gridLayout.value[idx], title: name })
  widgetTitleDraft.value = name
  onLayoutUpdated()
}

function onWidgetInspectorClosed() {
  widgetInspectorItemId.value = ''
  widgetInspectorKind.value = ''
  widgetTitleDraft.value = ''
}

function isPointerOverCanvas(clientX, clientY) {
  const stage = exportStageRef.value
  if (!stage) return false
  const rect = stage.getBoundingClientRect()
  return (
    clientX >= rect.left &&
    clientX <= rect.right &&
    clientY >= rect.top &&
    clientY <= rect.bottom
  )
}

function resolvePaletteDropContainer() {
  const wrap = gridWrapRef.value
  const layout = wrap?.querySelector('.vgl-layout')
  return layout || wrap || exportStageRef.value
}

function updatePaletteDropPreview(clientX, clientY) {
  const container = resolvePaletteDropContainer()
  if (!container) return
  const cell = pixelToGridCell(clientX, clientY, container, {
    rowHeight,
    margin: gridMargin,
    itemW: DASHBOARD_BASIC_WIDGET_DEFAULT_W,
    itemH: DASHBOARD_BASIC_WIDGET_DEFAULT_H
  })
  const rect = gridItemPixelRect(
    cell.x,
    cell.y,
    cell.itemW,
    cell.itemH,
    cell.metrics
  )
  if (!rect) return
  paletteDropPreview.value = {
    visible: true,
    style: {
      left: `${rect.left}px`,
      top: `${rect.top}px`,
      width: `${rect.width}px`,
      height: `${rect.height}px`
    }
  }
}

function clearPaletteDropPreview() {
  paletteDropActive.value = false
  paletteDropPreview.value = { visible: false, style: {} }
}

function detachPaletteWindowListeners() {
  if (paletteWindowDragOver) {
    window.removeEventListener('dragover', paletteWindowDragOver)
    paletteWindowDragOver = null
  }
  if (paletteWindowDrop) {
    window.removeEventListener('drop', paletteWindowDrop)
    paletteWindowDrop = null
  }
  if (paletteWindowDragEnd) {
    window.removeEventListener('dragend', paletteWindowDragEnd)
    paletteWindowDragEnd = null
  }
}

function onPaletteDragStart(item) {
  if (!requireSaveAsBeforeMutate()) return
  if (!isPaletteComponentAvailable(item?.type)) return
  paletteDragType.value = item.type
  detachPaletteWindowListeners()

  paletteWindowDragOver = (event) => {
    if (!paletteDragType.value) return
    if (isPointerOverCanvas(event.clientX, event.clientY)) {
      event.preventDefault()
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
      paletteDropActive.value = true
      updatePaletteDropPreview(event.clientX, event.clientY)
    } else {
      clearPaletteDropPreview()
    }
  }

  paletteWindowDrop = (event) => {
    if (!paletteDragType.value) return
    if (!isPointerOverCanvas(event.clientX, event.clientY)) return
    event.preventDefault()
    addBasicWidgetAtDrop(event.clientX, event.clientY, paletteDragType.value, item.label)
    finishPaletteDrag()
  }

  paletteWindowDragEnd = () => {
    finishPaletteDrag()
  }

  window.addEventListener('dragover', paletteWindowDragOver)
  window.addEventListener('drop', paletteWindowDrop)
  window.addEventListener('dragend', paletteWindowDragEnd)
}

function onPaletteDragEnd() {
  finishPaletteDrag()
}

function finishPaletteDrag() {
  paletteDragType.value = ''
  clearPaletteDropPreview()
  detachPaletteWindowListeners()
}

function createBasicWidgetItem(type, { x, y } = {}) {
  if (type === 'video') return createVideoWidgetItem({ x, y })
  if (type === 'text') return createTextWidgetItem({ x, y })
  if (type === 'image') return createImageWidgetItem({ x, y })
  if (type === 'carousel') return createCarouselWidgetItem({ x, y })
  return null
}

function basicWidgetSuccessLabel(type) {
  if (type === 'video') return '已添加视频组件'
  if (type === 'text') return '已添加文本组件'
  if (type === 'image') return '已添加图片组件'
  if (type === 'carousel') return '已添加轮播图组件'
  return '已添加组件'
}

function addBasicWidgetAtDrop(clientX, clientY, type, label = '') {
  if (!requireSaveAsBeforeMutate()) return null
  if (!isPaletteComponentAvailable(type)) {
    ElMessage.info(`「${label || type}」组件稍后开放`)
    return null
  }
  const container = resolvePaletteDropContainer()
  const cell = container
    ? pixelToGridCell(clientX, clientY, container, {
        rowHeight,
        margin: gridMargin,
        itemW: DASHBOARD_BASIC_WIDGET_DEFAULT_W,
        itemH: DASHBOARD_BASIC_WIDGET_DEFAULT_H
      })
    : { x: 0, y: nextGridRowY(gridLayout.value) }
  const item = createBasicWidgetItem(type, { x: cell.x, y: cell.y })
  if (!item) return null
  gridLayout.value = [...gridLayout.value, item]
  nextTick(() => {
    attachGridCanvas()
    selectGridItem(item)
    onLayoutUpdated()
    openBasicWidgetNameDialog(item, type, label)
  })
  return item
}

function addBasicWidgetToCanvas(type, label = '') {
  if (!requireSaveAsBeforeMutate()) return
  if (!isPaletteComponentAvailable(type)) {
    ElMessage.info(`「${label || type}」组件稍后开放`)
    return
  }
  const item = createBasicWidgetItem(type, {
    x: 0,
    y: nextGridRowY(gridLayout.value)
  })
  if (!item) return
  gridLayout.value = [...gridLayout.value, item]
  nextTick(() => {
    attachGridCanvas()
    selectGridItem(item)
    onLayoutUpdated()
    openBasicWidgetNameDialog(item, type, label)
  })
}

function onPickBasicComponent(item) {
  if (item?.source !== 'click') return
  addBasicWidgetToCanvas(item.type, item.label)
}

function clearBackgroundImage() {
  if (!requireSaveAsBeforeMutate()) return
  canvasStyle.backgroundImageDataUrl = ''
}

function readImageUploadAsDataUrl(uploadFile, { onOk, tooLargeMsg, successMsg }) {
  const raw = uploadFile?.raw
  if (!raw) return
  if (!String(raw.type || '').startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (raw.size > CANVAS_BACKGROUND_IMAGE_MAX_BYTES) {
    ElMessage.error(tooLargeMsg || '图片不能超过 5MB')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const url = String(reader.result || '')
    if (url.length > CANVAS_BACKGROUND_IMAGE_MAX_DATA_URL_LEN) {
      ElMessage.error(tooLargeMsg || '图片不能超过 5MB')
      return
    }
    onOk(url)
    if (successMsg) ElMessage.success(successMsg)
  }
  reader.readAsDataURL(raw)
}

function onBackgroundImageFileChange(uploadFile) {
  if (!requireSaveAsBeforeMutate()) return
  readImageUploadAsDataUrl(uploadFile, {
    onOk: (url) => {
      canvasStyle.backgroundImageDataUrl = url
    },
    successMsg: '背景图已读入'
  })
}

function toggleSidePanel() {
  if (sidePanelOpen.value) {
    sidePanelLastExpandedWidth = sidePanelWidth.value
    sidePanelOpen.value = false
  } else {
    sidePanelOpen.value = true
    sidePanelWidth.value = clampSidePanelWidth(sidePanelLastExpandedWidth || SIDE_PANEL_WIDTH_DEFAULT)
    sidePanelLastExpandedWidth = sidePanelWidth.value
  }
  persistSidePanelPrefs()
  nextTick(() => {
    window.dispatchEvent(new Event('resize'))
  })
}

function clampSidePanelWidth(width) {
  return Math.min(SIDE_PANEL_MAX, Math.max(SIDE_PANEL_MIN_EXPANDED, width))
}

function loadSidePanelPrefs() {
  try {
    const raw = localStorage.getItem(SIDE_PANEL_STORAGE_KEY)
    if (!raw) return
    const prefs = JSON.parse(raw)
    if (typeof prefs.open === 'boolean') {
      sidePanelOpen.value = prefs.open
    }
    const savedExpanded = Number(prefs.lastExpandedWidth ?? prefs.width)
    if (Number.isFinite(savedExpanded) && savedExpanded > SIDE_PANEL_COLLAPSE_THRESHOLD) {
      sidePanelLastExpandedWidth = clampSidePanelWidth(savedExpanded)
    }
    if (sidePanelOpen.value) {
      sidePanelWidth.value = sidePanelLastExpandedWidth
    }
  } catch {
    // ignore invalid storage
  }
}

function persistSidePanelPrefs() {
  try {
    localStorage.setItem(
      SIDE_PANEL_STORAGE_KEY,
      JSON.stringify({
        open: sidePanelOpen.value,
        width: sidePanelOpen.value ? sidePanelWidth.value : sidePanelLastExpandedWidth,
        lastExpandedWidth: sidePanelLastExpandedWidth
      })
    )
  } catch {
    // ignore quota / private mode
  }
}

function applySidePanelByPointer(clientX, workbenchLeft) {
  const pointerWidth = clientX - workbenchLeft
  if (pointerWidth <= SIDE_PANEL_COLLAPSE_THRESHOLD) {
    if (sidePanelOpen.value) {
      sidePanelLastExpandedWidth = clampSidePanelWidth(sidePanelWidth.value)
    }
    sidePanelOpen.value = false
    return
  }
  sidePanelOpen.value = true
  const nextWidth = clampSidePanelWidth(pointerWidth)
  sidePanelWidth.value = nextWidth
  sidePanelLastExpandedWidth = nextWidth
}

let sideResizeMoveHandler = null
let sideResizeUpHandler = null

function finishSideResize() {
  if (sideResizeMoveHandler) {
    document.removeEventListener('mousemove', sideResizeMoveHandler)
    sideResizeMoveHandler = null
  }
  if (sideResizeUpHandler) {
    document.removeEventListener('mouseup', sideResizeUpHandler)
    sideResizeUpHandler = null
  }
  document.body.classList.remove('dge-side-resizing')
  isResizingSide.value = false
  window.dispatchEvent(new Event('resize'))
}

function onSideResizeStart(event) {
  if (event.button !== 0) return
  event.preventDefault()
  const workbenchLeft = workbenchRef.value?.getBoundingClientRect().left ?? 0
  isResizingSide.value = true
  document.body.classList.add('dge-side-resizing')

  sideResizeMoveHandler = (moveEvent) => {
    const left = workbenchRef.value?.getBoundingClientRect().left ?? workbenchLeft
    applySidePanelByPointer(moveEvent.clientX, left)
    window.dispatchEvent(new Event('resize'))
  }
  sideResizeUpHandler = () => {
    persistSidePanelPrefs()
    finishSideResize()
  }

  document.addEventListener('mousemove', sideResizeMoveHandler)
  document.addEventListener('mouseup', sideResizeUpHandler)
  applySidePanelByPointer(event.clientX, workbenchLeft)
  window.dispatchEvent(new Event('resize'))
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
      backgroundColor:
        canvasStyle.backgroundType === 'color' ? canvasStyle.backgroundColor || '#f3f4f6' : null,
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

async function runAutoExportIfNeeded() {
  const fmt = String(props.autoExport || '').trim().toLowerCase()
  if (fmt !== 'png' && fmt !== 'pdf') return
  await nextTick()
  await nextTick()
  window.dispatchEvent(new Event('resize'))
  await new Promise((r) => setTimeout(r, 480))
  if (fmt === 'png') await exportCanvasPng()
  else await exportCanvasPdf()
  emit('auto-export-done')
  innerVisible.value = false
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
  if (!requireSaveAsBeforeMutate()) return
  inspectorSnapshot.value = JSON.parse(JSON.stringify(item))
  inspectorItem.value = item
  inspectorOpen.value = true
  inspectorCollapsed.value = false
}

function onInspectorDrawerClosed() {
  if (!inspectorCollapsed.value) {
    inspectorItem.value = null
    inspectorSnapshot.value = null
  }
}

function collapseChartInspector() {
  inspectorOpen.value = false
  inspectorCollapsed.value = true
}

function reopenChartInspector() {
  inspectorOpen.value = true
  inspectorCollapsed.value = false
}

function revertInspectorSnapshot() {
  const snap = inspectorSnapshot.value
  if (!snap) return
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(snap.i))
  if (idx < 0) return
  gridLayout.value.splice(idx, 1, { ...snap })
  inspectorItem.value = gridLayout.value[idx]
  onLayoutUpdated()
}

function onInspectorCancel() {
  revertInspectorSnapshot()
  inspectorOpen.value = false
  inspectorCollapsed.value = false
}

function onInspectorSave() {
  if (inspectorItem.value) {
    inspectorSnapshot.value = JSON.parse(JSON.stringify(inspectorItem.value))
  }
  inspectorOpen.value = false
  inspectorCollapsed.value = false
  nextTick(() => {
    window.dispatchEvent(new Event('resize'))
  })
  ElMessage.success('已应用图表样式，画布已更新；持久化请点击顶部「保存布局」')
}

function onInspectorResetStyle() {
  const snap = inspectorSnapshot.value
  const it = inspectorItem.value
  if (!snap || !it) return
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(it.i))
  if (idx < 0) return
  const cur = gridLayout.value[idx]
  const next = { ...cur }
  delete next.barColor
  delete next.barMaxWidth
  delete next.seriesItemStyles
  delete next.chartStyle
  Object.assign(next, cloneChartStyleFields(snap))
  gridLayout.value.splice(idx, 1, next)
  inspectorItem.value = next
  onLayoutUpdated()
  ElMessage.success('已重置样式（布局未改动）')
}

function onInspectorResetLayout() {
  const it = inspectorItem.value
  if (!it) return
  const origin = ensureLayoutOrigin(it)
  const idx = gridLayout.value.findIndex((x) => String(x.i) === String(it.i))
  if (idx < 0) return
  const next = { ...gridLayout.value[idx], x: origin.x, y: origin.y, w: origin.w, h: origin.h }
  gridLayout.value.splice(idx, 1, next)
  inspectorItem.value = next
  onLayoutUpdated()
  ElMessage.success('已重置布局（样式未改动）')
}

function gridItemsOverlap(a, b) {
  if (!a || !b) return false
  return !(a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y)
}

const inspectorOverlapHint = computed(() => {
  const it = inspectorItem.value
  if (!it) return ''
  const hit = (gridLayout.value || []).some(
    (o) => String(o.i) !== String(it.i) && gridItemsOverlap(it, o)
  )
  return hit ? '当前卡片与其他图表重叠，可开启网格吸附对齐' : ''
})

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

const inspectorDataRows = computed(() => {
  const d = inspectorSnap.value.data
  return Array.isArray(d) ? d.length : 0
})

function patchInspectorGridItem(patch) {
  if (!requireSaveAsBeforeMutate()) return
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
    const lc = mergeLayoutConstraints(next.layoutConstraints)
    next.w = Math.min(lc.maxW, Math.max(lc.minW, Math.round(Number(patch.w))))
  }
  if ('h' in patch && patch.h != null) {
    const lc = mergeLayoutConstraints(next.layoutConstraints)
    next.h = Math.min(lc.maxH, Math.max(lc.minH, Math.round(Number(patch.h))))
  }
  if ('x' in patch && patch.x != null) next.x = Math.max(0, Math.round(Number(patch.x)))
  if ('y' in patch && patch.y != null) next.y = Math.max(0, Math.round(Number(patch.y)))
  if ('title' in patch) {
    const t = String(patch.title ?? '').trim()
    if (t) next.title = t
    else delete next.title
  }
  if ('chartStyle' in patch) {
    if (patch.chartStyle == null) delete next.chartStyle
    else next.chartStyle = { ...patch.chartStyle }
  }
  if ('layoutConstraints' in patch) {
    if (patch.layoutConstraints == null) delete next.layoutConstraints
    else next.layoutConstraints = { ...patch.layoutConstraints }
  }
  if ('seriesItemStyles' in patch) {
    const s = patch.seriesItemStyles
    if (s == null || (typeof s === 'object' && Object.keys(s).length === 0)) delete next.seriesItemStyles
    else next.seriesItemStyles = { ...s }
  }
  gridLayout.value = gridLayout.value.map((x, i) => (i === idx ? next : x))
  inspectorItem.value = next
  onLayoutUpdated()
  nextTick(() => {
    window.dispatchEvent(new Event('resize'))
  })
}

function applyGridSnapIfNeeded() {
  let changed = false
  const nextLayout = (gridLayout.value || []).map((item) => {
    const lc = mergeLayoutConstraints(item.layoutConstraints)
    if (!lc.gridSnap) return item
    const cs = Math.max(1, Math.round(Number(lc.gridCellSize) || 1))
    const nx = Math.round(Number(item.x) / cs) * cs
    const ny = Math.round(Number(item.y) / cs) * cs
    if (nx === item.x && ny === item.y) return item
    changed = true
    return { ...item, x: Math.max(0, nx), y: Math.max(0, ny) }
  })
  if (changed) gridLayout.value = nextLayout
}

const legacyPreviewCards = computed(() =>
  extractLegacyChartCards(board.value?.layoutJson, `dge-${board.value?.id || 'x'}`)
)

/** 左侧图表库：仅当前看板 components 表中已钉入的图表 */
const pinnedLibraryRows = computed(() => {
  const rows = []
  const seen = new Set()
  for (const c of components.value || []) {
    const rawId = c.chartId ?? c.chart_id ?? c.CHART_ID
    if (rawId == null) continue
    const idStr = String(rawId).trim()
    if (!idStr || seen.has(idStr)) continue
    seen.add(idStr)
    const p = chartPayloadById.value[idStr]
    const snap = p ? parseChartSnapshot(p.chartSnapshot) : {}
    const question = readableTitleFromPayload(p) || `图表 #${idStr}`
    rows.push({
      id: Number(rawId),
      chartType: normalizeChartType(p?.chartType || snap.chartType || 'bar'),
      question,
      queryText: p?.queryText,
      tableName: String(snap.tableName || p?.queryTableName || '').trim(),
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
    const q = String(h.question || h.queryText || '').toLowerCase()
    return q.includes(kw)
  })
})

const chartLibraryOpen = ref(false)

function sideChartTypeLabel(type) {
  return historyChartTypeLabel(type)
}

/** 四边及四角均可缩放（interact.js 使用网格项边缘）；角部可同时改变 w 与 h */
function allSidesResizeOption() {
  return {
    edges: { top: true, right: true, bottom: true, left: true }
  }
}

const itemProps = (item) => {
  const base = basicWidgetGridItemProps(item, {
    resizeOption: allSidesResizeOption(),
    isResizable: !saveAsMode.value,
    isDraggable: !saveAsMode.value
  })
  if (saveAsMode.value) return { ...base, static: true, isResizable: false, isDraggable: false }
  return base
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
  if (raw == null) return ''
  const n = Number(raw)
  if (Number.isFinite(n) && n <= 0) return ''
  return String(raw)
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
  const advancedPayload = advancedPayloadForItem(item)
  if (advancedPayload) return advancedPayload
  const cid = chartIdForItem(item)
  if (!cid) return null
  return chartPayloadById.value[cid] || null
}

function advancedPayloadForItem(item) {
  const c = componentByItemId.value.get(String(item?.i))
  if (!c) return null
  const card = buildAdvancedAnalysisPreviewCard(c, item, 0, `dge-${board.value?.id || 'board'}`)
  if (!card) return null
  const analysis = card.advancedAnalysis || {}
  return {
    id: Number(c.chartId ?? c.chart_id ?? c.CHART_ID ?? 0) || 0,
    artifactId: c.artifactId ?? c.artifact_id ?? c.ARTIFACT_ID ?? null,
    turnId: c.turnId ?? c.turn_id ?? c.TURN_ID ?? null,
    chartType: card.chartType,
    queryText: card.title,
    queryTableName: card.tableName,
    generatedSql: '',
    option: card.option,
    chartSnapshot: {
      module: 'advancedAnalysis',
      type: analysis.type || '',
      message: card.title,
      chartType: card.chartType,
      tableName: card.tableName,
      fieldMapping: analysis.fieldMapping || {},
      data: card.data,
      advancedAnalysis: analysis
    }
  }
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
  if (text === 'radar') return '画像'
  if (text === 'scatter') return '分布'
  if (text === 'metric') return '指标'
  if (text === 'map') return '地域分布'
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

/** layout_json.items 上的展示覆盖，传给 ECharts */
function chartUiForItem(item) {
  return chartUiFromGridItem(item)
}

function cardTitleStyleForItem(item) {
  const s = mergeChartStyle(item?.chartStyle)
  const fw = Number(s.titleFontWeight)
  return {
    fontSize: `${Number(s.titleFontSize) || 13}px`,
    fontWeight: Number.isFinite(fw) && fw >= 100 ? fw : 600,
    color: s.titleColor || '#111827',
    textAlign: s.titleAlign || 'left'
  }
}

function cardChromeStyleForItem(item) {
  const s = mergeChartStyle(item?.chartStyle)
  const radius = Number(s.cardBorderRadius)
  return {
    borderRadius: `${Number.isFinite(radius) ? radius : 8}px`,
    boxShadow: s.cardShadow === false ? 'none' : '0 1px 2px rgba(15, 23, 42, 0.06)'
  }
}

function chartRenderKeyForItem(item) {
  if (!item) return ''
  const ui = chartUiForItem(item)
  return `${item.i}-${item.w}-${item.h}-${item.title || ''}-${JSON.stringify(ui)}`
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
  if (!requireSaveAsBeforeMutate()) return
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
  if (!requireSaveAsBeforeMutate()) return
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

let layoutUpdateRaf = 0

function onGridItemResized() {
  onLayoutUpdated()
}

function onLayoutUpdated() {
  if (saveAsMode.value && readonlyGridLayoutClone.value.length) {
    const cur = JSON.stringify(gridLayout.value)
    const frozen = JSON.stringify(readonlyGridLayoutClone.value)
    if (cur !== frozen) {
      gridLayout.value = JSON.parse(frozen)
      ElMessage.warning(SAVE_AS_FIRST_MSG)
      return
    }
  }
  applyGridSnapIfNeeded()
  if (inspectorItem.value) {
    const fresh = (gridLayout.value || []).find(
      (x) => String(x.i) === String(inspectorItem.value.i)
    )
    if (fresh) inspectorItem.value = fresh
  }
  if (layoutUpdateRaf) return
  layoutUpdateRaf = requestAnimationFrame(() => {
    layoutUpdateRaf = 0
    measureGridCanvas()
    window.dispatchEvent(new Event('resize'))
  })
}

function findGridItemByChartId(chartId) {
  const id = String(chartId)
  return (gridLayout.value || []).find((item) => {
    const comp = componentByItemId.value.get(String(item.i))
    if (!comp) return false
    const cid = comp.chartId ?? comp.chart_id ?? comp.CHART_ID
    return cid != null && String(cid) === id
  }) || null
}

async function onPinnedLibraryAction(row) {
  if (!requireSaveAsBeforeMutate()) return
  if (isChartInGridLayout(row?.id)) {
    await unpinChartFromCanvas(row)
    return
  }
  await pinHistoryChart(row)
}

function openChartLibrary() {
  chartLibraryOpen.value = true
}

async function onChartLibraryPinned() {
  await loadBoard()
}

async function unpinChartFromCanvas(row) {
  const item = findGridItemByChartId(row?.id)
  if (!item) {
    ElMessage.info('该图表不在当前画布中')
    return
  }
  pinningId.value = row?.id
  try {
    await removeGridItem(item)
  } finally {
    pinningId.value = null
  }
}

async function pinHistoryChart(row) {
  if (!requireSaveAsBeforeMutate()) return
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
  if (!requireSaveAsBeforeMutate()) return
  const compId = String(item.i)
  if (isBasicWidgetItem(item)) {
    if (basicWidgetRemovingId.value === compId) return
    basicWidgetRemovingId.value = compId
    const label = basicWidgetLabelForItem(item)
    try {
      try {
        await ElMessageBox.confirm(`从画布移除此${label}组件？`, '确认移除', { type: 'warning' })
      } catch {
        return
      }
      gridLayout.value = gridLayout.value.filter((x) => String(x.i) !== compId)
      if (selectedItemId.value === compId) selectedItemId.value = ''
      if (widgetInspectorItemId.value === compId) {
        widgetInspectorOpen.value = false
        widgetInspectorItemId.value = ''
        widgetInspectorKind.value = ''
      }
      onLayoutUpdated()
      ElMessage.success('已移除')
    } finally {
      if (basicWidgetRemovingId.value === compId) basicWidgetRemovingId.value = ''
    }
    return
  }
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

async function loadChartPayloads() {
  const idSet = new Set()
  for (const c of components.value || []) {
    const raw = c.chartId ?? c.chart_id ?? c.CHART_ID
    if (raw == null) continue
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) idSet.add(n)
  }
  const unique = [...idSet]
  if (!unique.length) {
    chartPayloadById.value = {}
    return
  }
  restoreSessionHeader()
  const dashboardId = board.value?.id ?? props.initialRow?.id
  const res = await axios.post(`${props.apiBase}/api/chat/history/charts-batch`, {
    ids: unique,
    ...(dashboardId ? { dashboardId } : {})
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

async function refreshChartPayloadsFromDynamicConfig() {
  if (!innerVisible.value || dynamicRefreshInFlight) return
  dynamicRefreshInFlight = true
  try {
    await loadChartPayloads()
  } catch {
    // 看板动态刷新失败时保留当前快照，避免打断用户查看。
  } finally {
    window.setTimeout(() => {
      dynamicRefreshInFlight = false
    }, 600)
  }
}

function ensureLayoutOriginsOnGrid() {
  gridLayout.value = (gridLayout.value || []).map((item) => {
    if (item.layoutOrigin) return item
    const lo = ensureLayoutOrigin(item)
    return lo ? { ...item, layoutOrigin: lo } : item
  })
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
  legacyCards.value = Array.isArray(parsed.cards) ? parsed.cards : []
  Object.assign(canvasStyle, normalizeCanvasStyle(parsed.canvasStyle))
  ensureCanvasBackgroundType()
  gridLayout.value = mergeGridItemsWithComponents(parsed.items, components.value, parsed.gridCols)
  ensureLayoutOriginsOnGrid()
  await loadChartPayloads()
  await nextTick()
  attachGridCanvas()
  onLayoutUpdated()
  captureLayoutSnapshot()
  captureReadonlyGridLayout()
}

const GROUP_ROOT_PARENT_ID = 0
const groupTreeSelectProps = { label: 'name', value: 'id', children: 'children' }

const saveMetaVisible = ref(false)
const saveMetaPending = ref(false)
const saveMetaForm = reactive({ isPublic: false })
const saveAsVisible = ref(false)
const saveAsForm = reactive({ name: '', groupId: null, isPublic: false })
const saveAsPersonalGroupTreeFetched = ref([])
const saveAsPlatformGroupTreeFetched = ref([])
const saveAsGroupTreeKey = ref(0)

const saveAsPersonalGroupBranches = computed(() => {
  if (props.saveAsPersonalGroupTree !== undefined) {
    return props.saveAsPersonalGroupTree
  }
  if (props.useParentGroupTreeForSaveAs && props.groupSelectTree.length) {
    return props.groupSelectTree
  }
  return saveAsPersonalGroupTreeFetched.value
})

const saveAsPlatformGroupBranches = computed(() => {
  if (props.saveAsPlatformGroupTree !== undefined) {
    return props.saveAsPlatformGroupTree
  }
  return saveAsPlatformGroupTreeFetched.value
})

/** 管理员端：分组恒为平台分组，与开放类型无关 */
const saveAsUsesPlatformGroupsOnly = computed(
  () => props.promptVisibilityOnSave && saveAsPlatformGroupBranches.value.length > 0
)

const saveAsGroupBranches = computed(() => {
  if (saveAsUsesPlatformGroupsOnly.value) {
    return saveAsPlatformGroupBranches.value
  }
  if (saveAsForm.isPublic && saveAsPlatformGroupBranches.value.length) {
    return saveAsPlatformGroupBranches.value
  }
  return saveAsPersonalGroupBranches.value
})

const saveAsGroupSelectTree = computed(() => [
  { id: GROUP_ROOT_PARENT_ID, name: '根目录', children: saveAsGroupBranches.value }
])
const layoutSnapshot = ref('')
const readonlyGridLayoutClone = ref([])

const SAVE_AS_FIRST_MSG = '他人已发布公共看板须先另存为副本，再进行编辑'

const saveAsMode = computed(() => isPublicSaveAsDesign(board.value || props.initialRow))

const saveAsReadonlyTip =
  '您不是该看板的所有者或另存人，移动、拖拽、增删组件等操作均不可用。须点击右上角「另存为」保存副本，原看板不会被直接覆盖。'

const emptyCanvasTip = computed(() => {
  const base =
    '列表「图表卡片」数来自 layout_json.cards（旧版内嵌数据），画布网格来自 layout_json.items 与组件表。若只有 cards、没有 items，会出现有数字但不能编排。'
  if (legacyPreviewCards.value.length) {
    return `${base} 下方旧版图表仅只读预览，不能拖拽。请从左侧「已钉图表」加入画布，或到对话查询钉入看板。`
  }
  return `${base} 请先在对话查询将图表钉入本看板，再从左侧列表加入画布。`
})

function cleanBoardDisplayName(name) {
  let s = String(name || '看板').trim()
  s = s.replace(/\s+(公开|公共|私密)\s*$/u, '')
  s = s.replace(/\s+副本\s*$/u, '')
  return s || '看板'
}

const boardDisplayName = computed(() => cleanBoardDisplayName(board.value?.name))

const boardIsPublicTag = computed(() => boardIsPublic(board.value))

const boardVisibilityText = computed(() => boardVisibilityLabel(board.value?.isPublic))

const boardIsCopyTag = computed(() => {
  const raw = String(board.value?.name || '')
  if (/\s副本\s*$/u.test(raw)) return true
  const saveAs = String(board.value?.saveAsUserId || '').trim()
  return Boolean(saveAs)
})

const saveAsDialogHintSub = computed(() => {
  if (saveAsUsesPlatformGroupsOnly.value) {
    return '管理员端分组均为平台分组，与开放类型（公共/私密）无关，不会继承原看板的分组。'
  }
  if (saveAsForm.isPublic) {
    return saveAsPlatformGroupBranches.value.length
      ? '公共看板可选择平台分组，不会继承原看板的分组。'
      : '公共看板可归入您的个人分组，不会继承原看板的分组。'
  }
  return '私密看板仅可选择您自己的个人分组，不会继承原看板的分组。'
})

function requireSaveAsBeforeMutate() {
  if (!saveAsMode.value) return true
  ElMessage.warning(SAVE_AS_FIRST_MSG)
  return false
}

const isLayoutDirty = computed(() => {
  if (!layoutSnapshot.value || !board.value?.id) return false
  return (
    serializeLayoutForApi(gridLayout.value, legacyCards.value, canvasStyle) !== layoutSnapshot.value
  )
})

function captureLayoutSnapshot() {
  layoutSnapshot.value = serializeLayoutForApi(gridLayout.value, legacyCards.value, canvasStyle)
}

function captureReadonlyGridLayout() {
  if (saveAsMode.value) {
    readonlyGridLayoutClone.value = JSON.parse(JSON.stringify(gridLayout.value))
  } else {
    readonlyGridLayoutClone.value = []
  }
}

function normalizeGroupIdForApi(value) {
  if (value == null || value === '' || Number(value) === GROUP_ROOT_PARENT_ID) return null
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
}

async function openSaveAsDialog() {
  saveAsForm.name = `${board.value?.name || '看板'} 副本`
  saveAsForm.groupId = null
  saveAsForm.isPublic = false
  await loadSaveAsGroupTrees()
  saveAsGroupTreeKey.value += 1
  saveAsVisible.value = true
}

async function loadSaveAsPersonalGroupTree() {
  if (props.saveAsPersonalGroupTree !== undefined) return
  if (props.useParentGroupTreeForSaveAs && props.groupSelectTree.length) return
  restoreSessionHeader()
  try {
    const res = await axios.get(`${props.apiBase}/api/c/dashboard-groups/tree`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    saveAsPersonalGroupTreeFetched.value = decorateGroupNodesOnly(
      Array.isArray(res.data.data) ? res.data.data : []
    )
  } catch (e) {
    saveAsPersonalGroupTreeFetched.value = []
    ElMessage.warning(e?.message || '个人分组加载失败，请稍后重试')
  }
}

async function loadSaveAsPlatformGroupTree() {
  if (props.saveAsPlatformGroupTree !== undefined) return
  if (!props.promptVisibilityOnSave) return
  restoreSessionHeader()
  try {
    const res = await axios.get(`${props.apiBase}/api/c/admin/dashboard-groups/tree`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    saveAsPlatformGroupTreeFetched.value = decorateGroupNodesOnly(
      Array.isArray(res.data.data) ? res.data.data : []
    )
  } catch (e) {
    saveAsPlatformGroupTreeFetched.value = []
    ElMessage.warning(e?.message || '平台分组加载失败，请稍后重试')
  }
}

async function loadSaveAsGroupTrees() {
  await Promise.all([loadSaveAsPersonalGroupTree(), loadSaveAsPlatformGroupTree()])
}

function isGroupIdInSaveAsTree(groupId, nodes = saveAsGroupBranches.value) {
  if (groupId == null || groupId === '' || Number(groupId) === GROUP_ROOT_PARENT_ID) return true
  const target = Number(groupId)
  for (const node of Array.isArray(nodes) ? nodes : []) {
    if (Number(node.id) === target) return true
    if (isGroupIdInSaveAsTree(groupId, node.children)) return true
  }
  return false
}

async function confirmSaveAs() {
  const name = String(saveAsForm.name || '').trim()
  if (!name) {
    ElMessage.warning('请填写看板名称')
    return
  }
  if (!board.value?.id) return
  const groupId = normalizeGroupIdForApi(saveAsForm.groupId)
  if (groupId != null && !isGroupIdInSaveAsTree(groupId)) {
    ElMessage.warning(
      saveAsUsesPlatformGroupsOnly.value ||
        (saveAsForm.isPublic && saveAsPlatformGroupBranches.value.length)
        ? '请选择有效的平台分组，或留空归入根目录'
        : '请选择您自己的分组，或留空归入根目录'
    )
    return
  }
  saving.value = true
  restoreSessionHeader()
  try {
    const layoutJson = serializeLayoutForApi(gridLayout.value, legacyCards.value, canvasStyle)
    const body = {
      name,
      layoutJson,
      publish: false,
      isPublic: Boolean(saveAsForm.isPublic)
    }
    body.groupId = groupId
    const duplicateUrl = props.promptVisibilityOnSave
      ? `${props.apiBase}/api/c/admin/dashboards/${board.value.id}/duplicate`
      : `${props.apiBase}/api/c/dashboards/${board.value.id}/duplicate`
    const res = await axios.post(duplicateUrl, body)
    if (res.data.code !== 200) throw new Error(res.data.message || '另存失败')
    ElMessage.success(`已另存为${saveAsForm.isPublic ? '公共' : '私密'}看板`)
    saveAsVisible.value = false
    emit('saved', res.data.data)
    innerVisible.value = false
  } catch (e) {
    ElMessage.error(e.message || '另存失败')
  } finally {
    saving.value = false
  }
}

async function saveLayout() {
  if (!board.value?.id) return
  if (saveAsMode.value) {
    openSaveAsDialog()
    return
  }
  if (props.promptVisibilityOnSave) {
    saveMetaForm.isPublic = Boolean(board.value.isPublic)
    saveMetaPending.value = true
    saveMetaVisible.value = true
    return
  }
  await doSaveLayout({ isPublic: Boolean(board.value.isPublic), publish: false })
}

async function confirmSaveLayout() {
  await doSaveLayout({ isPublic: Boolean(saveMetaForm.isPublic), publish: false })
  saveMetaVisible.value = false
}

async function confirmSaveAndPublish() {
  await doSaveLayout({ isPublic: Boolean(saveMetaForm.isPublic), publish: true })
  saveMetaVisible.value = false
}

async function doSaveLayout(isPublicOrOptions) {
  if (!board.value?.id) return
  if (isPublicSaveAsDesign(board.value || props.initialRow)) {
    ElMessage.warning('他人已发布公共看板不可直接保存，请使用「另存为」')
    return
  }
  const opts =
    isPublicOrOptions != null && typeof isPublicOrOptions === 'object'
      ? isPublicOrOptions
      : { isPublic: Boolean(isPublicOrOptions), publish: false }
  const { isPublic, publish = false } = opts
  saving.value = true
  restoreSessionHeader()
  try {
    const layoutJson = serializeLayoutForApi(gridLayout.value, legacyCards.value, canvasStyle)
    const status = publish ? 'ACTIVE' : 'DISABLED'
    const res = await axios.put(`${props.apiBase}/api/c/dashboards/${board.value.id}`, {
      name: board.value.name,
      description: board.value.description || null,
      layoutJson,
      isPublic,
      status
    })
    if (res.data.code !== 200) throw new Error(res.data.message || '保存失败')
    board.value.isPublic = isPublic
    board.value.status = status
    ElMessage.success(publish ? '已保存并发布' : '已保存，发布状态已更新为待发布')
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
  layoutSnapshot.value = ''
  saveAsVisible.value = false
  dynamicRefreshInFlight = false
  historyKeyword.value = ''
  finishSideResize()
  editingItemId.value = null
  inspectorOpen.value = false
  inspectorItem.value = null
  widgetInspectorOpen.value = false
  widgetInspectorItemId.value = ''
  widgetInspectorKind.value = ''
  selectedItemId.value = ''
  finishPaletteDrag()
  if (layoutUpdateRaf) {
    cancelAnimationFrame(layoutUpdateRaf)
    layoutUpdateRaf = 0
  }
  canvasStyleDialogOpen.value = false
  componentPaletteOpen.value = false
  inspectorOpen.value = false
  inspectorCollapsed.value = false
  inspectorItem.value = null
  inspectorSnapshot.value = null
  Object.assign(canvasStyle, normalizeCanvasStyle())
}

watch(
  () => saveAsForm.isPublic,
  () => {
    if (saveAsUsesPlatformGroupsOnly.value) return
    saveAsForm.groupId = null
    saveAsGroupTreeKey.value += 1
  }
)

watch(saveAsMode, (readonly) => {
  if (readonly) {
    componentPaletteOpen.value = false
    widgetInspectorOpen.value = false
    canvasStyleDialogOpen.value = false
    inspectorOpen.value = false
    inspectorCollapsed.value = false
    captureReadonlyGridLayout()
  } else {
    readonlyGridLayoutClone.value = []
  }
})

watch(
  () => gridLayout.value.length,
  () => {
    nextTick(() => attachGridCanvas())
  }
)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open || !props.initialRow?.id) return
    loadSidePanelPrefs()
    sideListLoading.value = true
    try {
      await loadBoard()
      await runAutoExportIfNeeded()
    } catch (e) {
      ElMessage.error(e.message || '加载失败')
      innerVisible.value = false
    } finally {
      sideListLoading.value = false
    }
  }
)

onBeforeUnmount(() => {
  finishSideResize()
  finishPaletteDrag()
})
</script>

<style scoped>
:global(body.dge-side-resizing) {
  cursor: col-resize !important;
  user-select: none !important;
}
.dge-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 2px 0;
}
.dge-title {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}
.dge-title-badge {
  flex-shrink: 0;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: #4338ca;
  background: linear-gradient(135deg, #eef2ff 0%, #e0e7ff 100%);
  border: 1px solid #c7d2fe;
  line-height: 1.35;
}
.dge-title-main {
  flex: 1;
  min-width: 0;
}
.dge-title-board-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}
.dge-title-board {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.35;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  max-width: 100%;
}
.dge-title-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.dge-name-hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: #909399;
  line-height: 1.5;
}
.dge-actions {
  display: flex;
  gap: 8px;
}
.dge-body {
  flex: 1 0 auto;
  display: flex;
  flex-direction: column;
  min-height: 100%;
  width: 100%;
  --dge-canvas-chrome: 120px;
  --vgl-resizer-size: 14px;
  --vgl-resizer-border-color: #3b82f6;
  --vgl-resizer-border-width: 2px;
}

.dge-help {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  font-size: 11px;
  cursor: help;
  flex-shrink: 0;
  line-height: 1;
  user-select: none;
}
.dge-empty-workbench {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.dge-workbench.is-save-as-readonly :deep(.vue-resizable-handle) {
  display: none !important;
}
.dge-workbench {
  display: flex;
  flex: 1 0 auto;
  gap: 0;
  align-items: stretch;
  min-height: calc(100vh - var(--dge-canvas-chrome, 168px));
}
.dge-workbench.is-side-collapsed .dge-side {
  display: none;
}
.dge-side {
  flex-shrink: 0;
  border: 1px solid #e8ecf1;
  border-radius: 12px 0 0 12px;
  border-right: none;
  background: linear-gradient(180deg, #fcfcfd 0%, #f6f8fb 100%);
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  align-self: stretch;
  max-height: none;
  box-shadow: inset -1px 0 0 rgba(255, 255, 255, 0.8);
}
.dge-side-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.dge-side-head-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.dge-side-head-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: -0.01em;
}
.dge-side-head-sub {
  font-size: 11px;
  color: #94a3b8;
}
.dge-side-lib-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #fff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, box-shadow 0.15s;
}
.dge-side-lib-btn:hover {
  background: #eff6ff;
  border-color: #93c5fd;
  box-shadow: 0 1px 4px rgba(37, 99, 235, 0.12);
}
.dge-side-search {
  margin-bottom: 12px;
}
.dge-side-search :deep(.el-input__wrapper) {
  border-radius: 8px;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  background: #fff;
}
.dge-side-search :deep(.el-input__wrapper:hover),
.dge-side-search :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #cbd5e1 inset;
}
.dge-side-list {
  flex: 1;
  overflow-y: auto;
  min-height: 120px;
  padding-right: 2px;
  margin-right: -2px;
}
.dge-side-list::-webkit-scrollbar {
  width: 5px;
}
.dge-side-list::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 999px;
}
.dge-side-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  margin-bottom: 10px;
  background: #fff;
  border: 1px solid #eef2f6;
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  transition: border-color 0.18s, box-shadow 0.18s, transform 0.18s;
}
.dge-side-card:hover {
  border-color: #dbeafe;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
  transform: translateY(-1px);
}
.dge-side-card.is-on-canvas {
  border-color: #e2e8f0;
  background: #fff;
}
.dge-side-card.is-on-canvas::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #3b82f6;
}
.dge-side-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dge-side-type {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.4;
}
.dge-side-type--bar { color: #1d4ed8; background: #eff6ff; }
.dge-side-type--line { color: #047857; background: #ecfdf5; }
.dge-side-type--pie { color: #b45309; background: #fffbeb; }
.dge-side-type--table { color: #6d28d9; background: #f5f3ff; }
.dge-side-type--radar { color: #0f766e; background: #f0fdfa; }
.dge-side-type--scatter { color: #be123c; background: #fff1f2; }
.dge-side-type--metric { color: #334155; background: #f1f5f9; }
.dge-side-type--map { color: #15803d; background: #f0fdf4; }
.dge-side-status {
  font-size: 10px;
  font-weight: 600;
  color: #64748b;
  letter-spacing: 0.02em;
}
.dge-side-card-title {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: #1e293b;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.dge-side-card-meta {
  margin: 0;
  font-size: 11px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dge-side-card-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 100%;
  margin-top: 2px;
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
}
.dge-side-card-btn.is-add {
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #2563eb;
}
.dge-side-card-btn.is-add:hover:not(:disabled) {
  background: #dbeafe;
  border-color: #93c5fd;
}
.dge-side-card-btn.is-remove {
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  color: #64748b;
}
.dge-side-card-btn.is-remove:hover:not(:disabled) {
  background: #fef2f2;
  border-color: #fecaca;
  color: #dc2626;
}
.dge-side-card-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.dge-side-card-btn-loading {
  font-size: 12px;
  color: #94a3b8;
}
.dge-side-empty :deep(.el-empty__description) {
  font-size: 13px;
  color: #64748b;
}
.dge-side-empty-tip {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
  text-align: center;
  line-height: 1.5;
}
.dge-side-rail {
  width: 14px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  background: #f3f4f6;
  border-top: 1px solid #e5e7eb;
  border-bottom: 1px solid #e5e7eb;
  position: relative;
  z-index: 2;
}
.dge-workbench.is-side-collapsed .dge-side-rail {
  border-left: 1px solid #e5e7eb;
  border-radius: 10px 0 0 10px;
}
.dge-side-rail-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 28px;
  margin: 0;
  padding: 0;
  border: none;
  border-bottom: 1px solid #e5e7eb;
  background: #eef2f7;
  color: #64748b;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.dge-side-rail-toggle:hover {
  background: #dbeafe;
  color: #2563eb;
}
.dge-side-rail-grip {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(2, 3px);
  grid-template-rows: repeat(3, 3px);
  gap: 3px;
  align-content: center;
  justify-content: center;
  width: 100%;
  min-height: 72px;
  padding: 12px 0;
  cursor: col-resize;
  touch-action: none;
  user-select: none;
  transition: background 0.15s ease;
}
.dge-side-rail-grip:hover {
  background: #e8eef5;
}
.dge-workbench.is-side-resizing .dge-side-rail-grip {
  background: #dbeafe;
}
.dge-side-rail-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #94a3b8;
  pointer-events: none;
}
.dge-workbench.is-side-resizing .dge-side-rail-dot {
  background: #64748b;
}
.dge-workbench:not(.is-side-collapsed) .dge-main-col .dge-canvas-col,
.dge-workbench:not(.is-side-collapsed) .dge-main-col .dge-legacy-grid {
  border: 1px solid #e5e7eb;
  border-left: none;
  border-radius: 0 10px 10px 0;
}
.dge-workbench.is-side-collapsed .dge-main-col .dge-canvas-col,
.dge-workbench.is-side-collapsed .dge-main-col .dge-legacy-grid {
  border: 1px solid #e5e7eb;
  border-radius: 0 10px 10px 0;
}
.dge-load-more {
  width: 100%;
  margin-top: 8px;
}
.dge-main-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-self: stretch;
}
.dge-canvas-col {
  flex: 1 0 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.dge-export-stage {
  position: relative;
  flex: 0 0 auto;
  width: 100%;
  min-height: calc(100vh - var(--dge-canvas-chrome, 168px));
  box-sizing: border-box;
}
.dge-empty-canvas-drop {
  flex: 1;
  display: grid;
  place-items: center;
  min-height: 280px;
  margin: 8px;
  border: 1px dashed #d1d5db;
  border-radius: 10px;
  color: #6b7280;
  font-size: 14px;
  background: rgba(255, 255, 255, 0.5);
}
.dge-empty-canvas-drop p {
  margin: 0;
}
.dge-grid-wrap {
  position: relative;
  width: 100%;
  min-height: 100%;
  box-sizing: border-box;
}
.dge-export-stage.is-palette-drop-active {
  outline: 2px dashed #a78bfa;
  outline-offset: -3px;
}
.dge-drop-preview {
  position: absolute;
  z-index: 7;
  pointer-events: none;
  border: 2px dashed #8b5cf6;
  border-radius: 8px;
  background: rgba(221, 214, 254, 0.45);
  box-shadow: inset 0 0 0 1px rgba(139, 92, 246, 0.15);
  transition: left 80ms ease, top 80ms ease, width 80ms ease, height 80ms ease;
}
.dge-grid.vgl-layout {
  width: 100%;
  margin: 0;
  /* 高度由 grid-layout-plus 按 items 的 y+h 自动计算，勿用 flex:1 锁死视口高度 */
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
  cursor: pointer;
}
.dge-card.is-selected {
  border-color: #8b5cf6;
  box-shadow: 0 0 0 2px rgba(139, 92, 246, 0.25);
}
.dge-widget-shell {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border-radius: 8px;
  overflow: hidden;
}
.dge-widget-shell.is-selected {
  box-shadow: 0 0 0 2px rgba(139, 92, 246, 0.45);
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
.dge-inspector-fab {
  position: fixed;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 4999;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  border: 1px solid #bfdbfe;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(59, 130, 246, 0.18);
  cursor: pointer;
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 500;
}
.dge-inspector-fab:hover {
  background: #eff6ff;
}
.dge-inspector-fab-icon {
  font-size: 15px;
  line-height: 1;
}
</style>

<style>
.dge-save-meta-hint {
  margin: 0 0 14px;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.5;
}
.dge-save-meta-hint--sub {
  margin-top: -8px;
  font-size: 12px;
  color: #9ca3af;
}
.dge-dialog.is-fullscreen .el-dialog__header {
  padding: 14px 20px 12px;
  margin-right: 0;
  border-bottom: 1px solid #f1f5f9;
  background: #fff;
}
.dge-dialog.is-fullscreen {
  display: flex;
  flex-direction: column;
}
.dge-dialog.is-fullscreen .el-dialog__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
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

<style>
.dge-help-popper.el-popper {
  max-width: 320px;
  padding: 10px 12px !important;
  background: #fff !important;
  border: 1px solid #e2e8f0 !important;
  color: #334155 !important;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12) !important;
}

.dge-help-popper.el-popper .el-popper__arrow::before {
  background: #fff !important;
  border: 1px solid #e2e8f0 !important;
}

.dge-help-popper .dge-help-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: #334155 !important;
}
</style>
