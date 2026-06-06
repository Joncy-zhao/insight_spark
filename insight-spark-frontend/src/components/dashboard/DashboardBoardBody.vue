<template>
  <div class="dbv-body">
    <p v-if="showLead" class="dbv-lead">
      以下为该看板<strong>画布上的真实排布</strong>（栅格与「设计看板」一致）。此为<strong>看板视图</strong>，与「查看图表」弹窗（仅图表卡片预览）不同。
    </p>

    <template v-if="board && gridLayout.length">
      <div class="dbv-export-stage" :style="canvasStageInlineStyle">
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
              class="dbv-grid-item"
            >
              <div v-if="isBasicWidgetItem(item)" class="dbv-widget-shell">
                <component
                  :is="basicWidgetViewForItem(item)"
                  :config="basicWidgetConfigForItem(item)"
                />
              </div>
              <div v-else class="dbv-card">
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
  showLead: { type: Boolean, default: true }
})

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
.dbv-widget-shell {
  width: 100%;
  height: 100%;
  border-radius: 8px;
  overflow: hidden;
}
.dbv-card {
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
