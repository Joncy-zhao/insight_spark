<template>
  <div class="dbv-body">
    <p v-if="showLead" class="dbv-lead">
      以下为该看板<strong>画布上的真实排布</strong>（栅格与「设计看板」一致）。此为<strong>看板视图</strong>，与「查看图表」弹窗（仅图表卡片预览）不同。
    </p>
    <p v-else-if="selectable" class="dbv-collab-hint">
      点击组件选中并批注；组件右上角为<strong>批注条数</strong>（非标签颜色）。整板批注请点「整板批注」，在右侧面板查看。
    </p>

    <template v-if="board && gridLayout.length">
      <div v-if="selectable" class="dbv-collab-toolbar">
        <el-button
          size="small"
          :type="!activeItemId ? 'primary' : 'default'"
          @click="emitSelectBoard"
        >
          整板批注
          <span v-if="boardAnnotationCount > 0" class="dbv-board-ann-badge">{{ boardAnnotationCount }} 条</span>
        </el-button>
      </div>
      <div class="dbv-export-stage" :class="{ 'dbv-export-stage--collab': selectable }" :style="canvasStageInlineStyle">
        <div class="dbv-grid-wrap">
          <GridLayout
            v-model:layout="layoutModel"
            :col-num="DASHBOARD_GRID_COL_NUM"
            :row-height="rowHeight"
            :margin="gridMargin"
            :is-draggable="false"
            :is-resizable="false"
            :vertical-compact="false"
            class="dbv-grid"
          >
            <GridItem
              v-for="(item, slotIndex) in layoutModel"
              :key="item.i"
              v-bind="itemProps(item)"
              :class="['dbv-grid-item', {
                'dbv-grid-item--selectable': selectable,
                'dbv-grid-item--active': selectable && String(item.i) === String(activeItemId)
              }]"
            >
              <div
                v-if="isBasicWidgetItem(item)"
                class="dbv-widget-shell"
                :class="['dbv-item-hit', { 'dbv-item-has-ann': badgeForItem(item), 'dbv-item-selected': isItemSelected(item) }]"
                @click.capture="onItemClick(item, slotIndex)"
              >
                <div v-if="selectable" class="dbv-item-badge">{{ slotDisplayTitle(item, slotIndex) }}</div>
                <div v-if="badgeForItem(item)" class="dbv-ann-pin" :style="{ background: badgeColor(badgeForItem(item)) }">
                  {{ badgeForItem(item).count }}
                </div>
                <component
                  :is="basicWidgetViewForItem(item)"
                  :config="basicWidgetConfigForItem(item)"
                />
              </div>
              <div
                v-else
                class="dbv-card"
                :class="['dbv-item-hit', { 'dbv-item-has-ann': badgeForItem(item), 'dbv-item-selected': isItemSelected(item) }]"
                @click.capture="onItemClick(item, slotIndex)"
              >
                <div v-if="badgeForItem(item)" class="dbv-ann-pin" :style="{ background: badgeColor(badgeForItem(item)) }">
                  {{ badgeForItem(item).count }}
                </div>
                <div class="dbv-card-meta">
                  <div class="dbv-card-titlewrap">
                    <span class="dbv-card-title">{{ slotDisplayTitle(item, slotIndex) }}</span>
                  </div>
                  <div class="dbv-card-actions">
                    <span v-if="chartIdForItem(item)" class="dbv-chart-id">历史 {{ chartIdForItem(item) }}</span>
                    <span v-if="artifactIdForItem(item)" class="dbv-chart-id">Artifact {{ artifactIdForItem(item) }}</span>
                    <el-tag v-else-if="legacyInlineCardAt(slotIndex)" size="small" type="warning">
                      内嵌 cards 兜底
                    </el-tag>
                  </div>
                </div>
                <div class="dbv-chart-host">
                  <DashboardChart
                    v-if="payloadForItem(item)"
                    :payload="payloadForItem(item)"
                    :chart-ui="chartUiForItem(item)"
                    hide-title
                  />
                  <LegacyInlineChart
                    v-else-if="legacyInlineCardAt(slotIndex)"
                    :title="''"
                    :chart-type="legacyInlineCardAt(slotIndex).chartType"
                    :data="legacyInlineCardAt(slotIndex).data"
                  />
                  <div v-else class="dbv-chart-fallback">
                    {{ chartFallbackMessage(item) }}
                  </div>
                </div>
              </div>
            </GridItem>
          </GridLayout>
        </div>
      </div>
    </template>

    <div v-else-if="board && legacyPreviewCards.length" class="dbv-legacy-wrap">
      <p class="dbv-legacy-note">当前看板为旧版内嵌数据（无网格 items），以下为只读预览。</p>
      <div class="dbv-legacy-grid">
        <LegacyInlineChart
          v-for="c in legacyPreviewCards"
          :key="c._renderKey"
          :title="c.title"
          :chart-type="c.chartType"
          :data="c.data"
        />
      </div>
    </div>

    <el-empty v-else-if="board && !loading" description="暂无画布内容（无网格组件且无内嵌图表）" />

    <el-empty v-else-if="!loading && !board" description="无法加载看板" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { GridLayout, GridItem } from 'grid-layout-plus'
import {
  buildAdvancedAnalysisPreviewCard,
  chartUiFromGridItem,
  DASHBOARD_GRID_COL_NUM,
  DASHBOARD_GRID_MARGIN
} from '../../utils/dashboardGrid.js'
import DashboardChart from './DashboardChart.vue'
import LegacyInlineChart from './LegacyInlineChart.vue'
import { resolveBasicWidgetEntry } from '../../utils/dashboardBasicWidgetRegistry.js'
import { isBasicWidgetItem } from '../../utils/dashboardWidgetVideo.js'
import { annotationTagPreset } from '../../utils/collabAnnotation.js'

function basicWidgetViewForItem(item) {
  return resolveBasicWidgetEntry(item)?.widget || null
}

function basicWidgetConfigForItem(item) {
  const entry = resolveBasicWidgetEntry(item)
  return entry ? entry.configForItem(item) : {}
}

const props = defineProps({
  board: { type: Object, default: null },
  legacyPreviewCards: { type: Array, default: () => [] },
  canvasStageInlineStyle: { type: Object, default: () => ({}) },
  rowHeight: { type: Number, default: 44 },
  chartPayloadById: { type: Object, default: () => ({}) },
  components: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  showLead: { type: Boolean, default: true },
  /** 协作模式：点击组件选中并批注 */
  selectable: { type: Boolean, default: false },
  activeItemId: { type: [String, Number], default: null },
  /** 组件批注角标 { [itemId]: { count, primaryTag } } */
  itemBadges: { type: Object, default: () => ({}) },
  /** 整板级批注条数 */
  boardAnnotationCount: { type: Number, default: 0 }
})

const emit = defineEmits(['select-item'])

const layoutModel = defineModel('gridLayout', { type: Array, default: () => [] })

const gridMargin = DASHBOARD_GRID_MARGIN

const componentByItemId = computed(() => {
  const m = new Map()
  for (const c of props.components || []) {
    const id = String(c.id ?? c.ID ?? c.componentId ?? c.component_id ?? '').trim()
    if (id) m.set(id, c)
  }
  return m
})

function itemProps(item) {
  return {
    i: item.i,
    x: item.x,
    y: item.y,
    w: item.w,
    h: item.h,
    static: true,
    minW: 1,
    maxW: DASHBOARD_GRID_COL_NUM,
    minH: 1,
    maxH: 48
  }
}

function componentForItem(item) {
  return componentByItemId.value.get(String(item?.i)) || null
}

function chartIdForItem(item) {
  const c = componentForItem(item)
  if (!c) return ''
  const raw = c.chartId ?? c.chart_id ?? c.CHART_ID
  if (raw == null) return ''
  const n = Number(raw)
  if (Number.isFinite(n) && n <= 0) return ''
  return String(raw)
}

function artifactIdForItem(item) {
  const c = componentForItem(item)
  if (!c) return ''
  const raw = c.artifactId ?? c.artifact_id ?? c.ARTIFACT_ID
  return raw != null && String(raw).trim() !== '' ? String(raw) : ''
}

function advancedPayloadForItem(item) {
  const c = componentForItem(item)
  if (!c) return null
  const card = buildAdvancedAnalysisPreviewCard(c, item, 0, `dbv-${props.board?.id || 'board'}`)
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

function payloadForItem(item) {
  const advancedPayload = advancedPayloadForItem(item)
  if (advancedPayload) return advancedPayload
  const cid = chartIdForItem(item)
  if (!cid) return null
  return props.chartPayloadById[cid] || null
}

function chartFallbackMessage(item) {
  if (artifactIdForItem(item)) return '高级分析图表数据暂不可用'
  if (chartIdForItem(item)) return '图表数据暂不可用'
  return '未关联 chart_id'
}

function chartUiForItem(item) {
  return chartUiFromGridItem(item)
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

function defaultTitleFromPayload(item) {
  const p = payloadForItem(item)
  const snap = parseChartSnapshot(p?.chartSnapshot)
  const msg = String(snap?.message || '').trim()
  return msg.slice(0, 120) || `图表 #${item.i}`
}

function displayTitleForItem(item) {
  const t = String(item.title || '').trim()
  if (t) return t
  return defaultTitleFromPayload(item)
}

function legacyInlineCardAt(slotIndex) {
  const item = layoutModel.value[slotIndex]
  if (!item) return null
  if (chartIdForItem(item)) return null
  const cards = props.legacyPreviewCards
  const c = cards[slotIndex]
  if (!c || !Array.isArray(c.data) || !c.data.length) return null
  return c
}

function slotDisplayTitle(item, slotIndex) {
  const t = String(item.title || '').trim()
  if (t) return t
  const leg = legacyInlineCardAt(slotIndex)
  if (leg?.title) return leg.title
  return displayTitleForItem(item)
}

function itemKind(item) {
  if (isBasicWidgetItem(item)) return String(item.kind || item.type || 'basic')
  if (artifactIdForItem(item)) return 'advanced'
  if (chartIdForItem(item)) return 'chart'
  return String(item.kind || item.type || 'widget')
}

function emitSelectBoard() {
  if (!props.selectable || !props.board?.id) return
  emit('select-item', {
    targetType: 'DASHBOARD',
    targetId: props.board.id,
    label: '整板',
    kind: 'dashboard'
  })
}

function onItemClick(item, slotIndex) {
  if (!props.selectable) return
  emit('select-item', {
    targetType: 'COMPONENT',
    targetId: String(item.i),
    label: slotDisplayTitle(item, slotIndex),
    kind: itemKind(item)
  })
}

function badgeForItem(item) {
  if (!item?.i) return null
  return props.itemBadges[String(item.i)] || null
}

function badgeColor(badge) {
  return annotationTagPreset(badge?.primaryTag).badge
}

function isItemSelected(item) {
  return props.selectable && String(item?.i) === String(props.activeItemId)
}
</script>

<style scoped>
.dbv-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  --dbv-canvas-chrome: 200px;
}
.dbv-lead {
  margin: 0 0 14px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.55;
  color: #374151;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
}
.dbv-export-stage {
  position: relative;
  width: 100%;
  min-height: calc(100vh - var(--dbv-canvas-chrome, 200px));
  box-sizing: border-box;
}
.dbv-grid-wrap {
  width: 100%;
}
.dbv-grid.vgl-layout {
  width: 100%;
}
.dbv-grid-item {
  touch-action: auto;
}
.dbv-collab-hint {
  margin: 0 0 10px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
}
.dbv-collab-toolbar {
  margin-bottom: 10px;
}
.dbv-board-ann-badge {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
}
.el-button--primary .dbv-board-ann-badge {
  background: rgba(255, 255, 255, 0.25);
  color: #fff;
}
.dbv-export-stage--collab {
  min-height: 480px;
  --dbv-canvas-chrome: 320px;
}
.dbv-grid-item--selectable :deep(.vgl-item) {
  transition: box-shadow 0.15s ease;
}
.dbv-grid-item--selectable:hover :deep(.vgl-item) {
  box-shadow: none;
}
.dbv-grid-item--selectable:hover .dbv-item-hit:not(.dbv-item-selected) {
  outline: 2px dashed rgba(245, 158, 11, 0.45);
  outline-offset: -2px;
}
.dbv-grid-item--active :deep(.vgl-item) {
  box-shadow: none;
}
.dbv-item-selected.dbv-card,
.dbv-item-selected.dbv-widget-shell {
  outline: 2px dashed #f59e0b;
  outline-offset: -2px;
  box-shadow: 0 0 0 4px rgba(245, 158, 11, 0.2);
}
.dbv-item-selected.dbv-item-has-ann {
  box-shadow: 0 0 0 4px rgba(245, 158, 11, 0.2);
}
.dbv-item-hit {
  cursor: pointer;
  height: 100%;
}
.dbv-item-badge {
  position: absolute;
  top: 6px;
  left: 6px;
  z-index: 2;
  max-width: calc(100% - 12px);
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 600;
  color: #1e40af;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #bfdbfe;
  border-radius: 999px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
}
.dbv-widget-shell {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: 8px;
  overflow: hidden;
}
.dbv-item-has-ann:not(.dbv-item-selected).dbv-card,
.dbv-item-has-ann:not(.dbv-item-selected).dbv-widget-shell {
  box-shadow: 0 0 0 1px rgba(245, 158, 11, 0.25);
}
.dbv-ann-pin {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 3;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 20px;
  text-align: center;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.25);
  pointer-events: none;
}
.dbv-card {
  position: relative;
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
.dbv-card-meta {
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
.dbv-card-titlewrap {
  flex: 1;
  min-width: 0;
}
.dbv-card-title {
  font-weight: 600;
  color: #111827;
  font-size: 13px;
  line-height: 1.45;
  word-break: break-word;
}
.dbv-card-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dbv-chart-id {
  font-size: 11px;
  color: #9ca3af;
}
.dbv-chart-host {
  flex: 1;
  min-height: 0;
  padding: 6px 8px 8px;
}
.dbv-chart-fallback {
  height: 100%;
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
  font-size: 13px;
}
.dbv-legacy-note {
  margin: 0 0 10px;
  font-size: 13px;
  color: #6b7280;
}
.dbv-legacy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
</style>

<style>
.dbv-grid .vgl-item .dbv-card {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
}
</style>
