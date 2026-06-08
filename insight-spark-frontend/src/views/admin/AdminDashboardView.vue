<template>
  <section class="adm-dashboard">
    <div class="adm-head">
      <div>
        <h1 class="adm-title">看板管理</h1>
        <p class="adm-sub">管理平台公共看板与批量运维；分组在编辑看板时指定，公共/私密在保存时选择。</p>
      </div>
    </div>

    <div class="adm-stats-wrap" v-loading="loadingStats">
      <div class="adm-stat-grid">
        <article class="adm-stat-card adm-stat-card--blue">
          <span class="adm-stat-label">看板总数</span>
          <strong class="adm-stat-value">{{ stats.totalCount }}</strong>
          <small class="adm-stat-hint">私密 + 公共合计</small>
        </article>
        <article class="adm-stat-card adm-stat-card--amber">
          <span class="adm-stat-label">公共看板</span>
          <strong class="adm-stat-value">{{ stats.publicCount }}</strong>
          <small class="adm-stat-hint">开放类型 = 公共</small>
        </article>
        <article class="adm-stat-card adm-stat-card--indigo">
          <span class="adm-stat-label">私密看板</span>
          <strong class="adm-stat-value">{{ stats.privateCount }}</strong>
          <small class="adm-stat-hint">开放类型 = 私密</small>
        </article>
        <article class="adm-stat-card adm-stat-card--green">
          <span class="adm-stat-label">总访问量</span>
          <strong class="adm-stat-value">{{ stats.totalViews }}</strong>
          <small class="adm-stat-hint">全平台访问量求和</small>
        </article>
      </div>
      <div class="adm-charts-grid">
        <section class="adm-chart-panel">
          <h3 class="adm-chart-title">看板类型占比</h3>
          <p class="adm-chart-sub">公共 / 私密看板数量（南丁格尔玫瑰图）</p>
          <div ref="typeChartRef" class="adm-chart-canvas" />
        </section>
        <section class="adm-chart-panel">
          <h3 class="adm-chart-title">TOP5 热门看板</h3>
          <p class="adm-chart-sub">按访问量倒排，便于性能治理</p>
          <div ref="topChartRef" class="adm-chart-canvas" />
        </section>
      </div>
    </div>

    <div class="adm-body">
      <div class="adm-main">
        <div class="adm-filters">
          <el-input
            v-model="filters.keyword"
            class="adm-filter-search"
            clearable
            placeholder="按看板名称或作者搜索…"
            :prefix-icon="Search"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-select v-model="filters.isPublic" class="adm-filter-select" @change="onSearch">
            <el-option label="开放类型：全部" value="ALL" />
            <el-option label="开放类型：公共" value="1" />
            <el-option label="开放类型：私密" value="0" />
          </el-select>
          <el-popover
            v-model:visible="groupFilterPopoverVisible"
            placement="bottom-start"
            :width="340"
            trigger="click"
            popper-class="adm-group-filter-popper"
          >
            <template #reference>
              <el-input
                readonly
                class="adm-filter-select adm-filter-group adm-group-filter-trigger"
                :model-value="groupFilterLabel"
                placeholder="分组：全部"
              >
                <template #suffix>
                  <el-icon class="adm-group-filter-caret"><ArrowDown /></el-icon>
                </template>
              </el-input>
            </template>
            <div class="adm-group-filter-tree-wrap">
              <el-tree
                :data="groupFilterTree"
                node-key="nodeKey"
                :props="groupFilterTreeProps"
                highlight-current
                default-expand-all
                :expand-on-click-node="false"
                :current-node-key="currentGroupFilterNodeKey"
                @node-click="onGroupFilterNodeClick"
              >
                <template #default="{ data }">
                  <div class="adm-group-filter-option" :class="{ 'is-meta': data.isFilterMeta }">
                    <el-icon v-if="data.isPlatform" class="adm-group-filter-folder" :size="14"><Folder /></el-icon>
                    <span class="adm-group-filter-name" :title="data.name">{{ data.name }}</span>
                    <div v-if="data.isPlatform" class="adm-group-filter-actions">
                      <el-button
                        link
                        type="primary"
                        class="adm-group-filter-edit"
                        title="重命名"
                        @click.stop="openEditPlatformGroup(data)"
                      >
                        <el-icon><EditPen /></el-icon>
                      </el-button>
                      <el-button
                        link
                        type="danger"
                        class="adm-group-filter-del"
                        title="删除分组"
                        @click.stop="removePlatformGroup(data)"
                      >
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </div>
                  </div>
                </template>
              </el-tree>
            </div>
          </el-popover>
          <el-select v-model="filters.status" class="adm-filter-select" @change="onSearch">
            <el-option label="发布状态：全部" value="ALL" />
            <el-option label="发布状态：已发布" value="ACTIVE" />
            <el-option label="发布状态：待发布" value="DISABLED" />
          </el-select>
          <div class="adm-filter-actions">
            <el-dropdown trigger="click" @command="onCreateCommand">
              <el-button type="primary">
                <el-icon><Plus /></el-icon>
                新建
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="new-group">
                    <span class="adm-dropdown-item">
                      <el-icon><Folder /></el-icon>
                      新建分组
                    </span>
                  </el-dropdown-item>
                  <el-dropdown-item command="new-board">
                    <span class="adm-dropdown-item">
                      <el-icon><Odometer /></el-icon>
                      新建看板
                    </span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button :loading="loadingList" @click="refreshAll">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </div>

        <el-table
          :data="rows"
          border
          size="small"
          v-loading="loadingList"
          empty-text="暂无看板"
          class="adm-table"
        >
          <el-table-column prop="id" label="ID" width="72" />
          <el-table-column prop="name" label="看板名称" min-width="88" show-overflow-tooltip />
          <el-table-column label="作者" min-width="72" show-overflow-tooltip>
            <template #default="{ row }">{{ authorDisplay(row) }}</template>
          </el-table-column>
          <el-table-column label="另存人" min-width="72" show-overflow-tooltip>
            <template #default="{ row }">{{ saveAsDisplay(row) }}</template>
          </el-table-column>
          <el-table-column label="发布者" min-width="72" show-overflow-tooltip>
            <template #default="{ row }">{{ publisherDisplay(row) }}</template>
          </el-table-column>
          <el-table-column label="开放类型" width="72" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small">
                {{ boardVisibilityLabel(row.isPublic) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="分组" min-width="64" show-overflow-tooltip>
            <template #default="{ row }">{{ boardGroupDisplay(row) }}</template>
          </el-table-column>
          <el-table-column label="图表数" width="60" align="center">
            <template #default="{ row }">
              <button
                v-if="(row.chartCardCount || 0) > 0"
                type="button"
                class="adm-chart-count-btn"
                :title="`查看 ${row.chartCardCount} 张图表`"
                @click="previewDialogsRef?.openChartList(row)"
              >
                <el-tag size="small" type="primary" effect="plain" class="adm-chart-count-tag">
                  {{ row.chartCardCount }}
                </el-tag>
              </button>
              <el-tag v-else size="small" type="info" effect="plain">0</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="组件数" width="60" align="center">
            <template #default="{ row }">
              <button
                v-if="(row.basicWidgetCount || 0) > 0"
                type="button"
                class="adm-chart-count-btn"
                :title="`查看 ${row.basicWidgetCount} 个基础组件`"
                @click="previewDialogsRef?.openWidgetList(row)"
              >
                <el-tag size="small" type="warning" effect="plain" class="adm-chart-count-tag">
                  {{ row.basicWidgetCount }}
                </el-tag>
              </button>
              <el-tag v-else size="small" type="info" effect="plain">0</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="访问量" width="64" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="info" effect="plain">{{ row.viewCount ?? 0 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布状态" width="76" align="center">
            <template #default="{ row }">
              <el-tag :type="boardStatusTagType(row)" size="small" effect="light">
                {{ boardStatusTag(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="128" show-overflow-tooltip>
            <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="360" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openViewBoard(row)">查看看板</el-button>
              <template v-if="canManageRow(row)">
                <el-button
                  v-if="canDesignBoard(row)"
                  link
                  type="primary"
                  @click="openGridEditor(row)"
                >
                  设计看板
                </el-button>
                <el-button
                  v-if="isBoardOwner(row)"
                  link
                  type="primary"
                  @click="openEdit(row)"
                >
                  编辑
                </el-button>
                <el-button
                  v-if="isBoardPublished(row)"
                  link
                  type="primary"
                  @click="openShareDialog(row)"
                >
                  分享
                </el-button>
                <el-button
                  v-if="!isBoardOwner(row) && isBoardPublished(row)"
                  link
                  type="warning"
                  :loading="unpublishingId === row.id"
                  @click="unpublishRow(row)"
                >
                  强制下线
                </el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>

        <div class="adm-pager">
          <el-pagination
            v-model:current-page="pagination.page"
            v-model:page-size="pagination.pageSize"
            :total="pagination.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="loadList"
            @size-change="onPageSizeChange"
          />
        </div>
      </div>
    </div>

    <DashboardPreviewDialogs ref="previewDialogsRef" :api-base="API_BASE" />

    <DashboardGridEditor
      v-model="gridEditorVisible"
      :initial-row="gridEditorRow"
      :api-base="API_BASE"
      :prompt-visibility-on-save="gridEditorPromptVisibility"
      :group-select-tree="groupSelectTree"
      :save-as-platform-group-tree="groupSelectTree"
      @saved="onGridSaved"
    />

    <DashboardBoardViewer
      v-model="boardViewerVisible"
      :initial-row="boardViewerRow"
      :api-base="API_BASE"
      :show-lead="false"
    />

    <el-dialog
      v-model="groupVisible"
      :title="groupEditId ? '重命名分组' : '新建分组'"
      width="480px"
      destroy-on-close
    >
      <el-form label-width="96px">
        <el-form-item label="分组名称" required>
          <el-input v-model="groupForm.name" placeholder="请输入" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="所属分组" required>
          <el-tree-select
            v-model="groupForm.parentId"
            :data="groupParentSelectTree"
            :props="treeSelectProps"
            check-strictly
            placeholder="根目录"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupVisible = false">取消</el-button>
        <el-button type="primary" :loading="groupSaving" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="boardVisible"
      title="新建看板"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="88px">
        <el-form-item label="名称" required>
          <el-input v-model="boardForm.name" placeholder="看板名称" maxlength="120" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="boardForm.description" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="所属分组">
          <el-tree-select
            v-model="boardForm.groupId"
            :data="groupSelectTree"
            :props="treeSelectProps"
            check-strictly
            clearable
            placeholder="可留空"
            style="width: 100%"
          />
        </el-form-item>
        <p class="adm-board-hint">公共/私密将在设计器中点击「保存布局」时选择。</p>
      </el-form>
      <template #footer>
        <el-button @click="boardVisible = false">取消</el-button>
        <el-button type="primary" :loading="boardSaving" @click="createBoard">创建并设计</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑看板" width="520px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="看板名称" maxlength="120" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="所属分组">
          <el-tree-select
            v-model="form.groupId"
            :data="groupSelectTree"
            :props="treeSelectProps"
            check-strictly
            clearable
            placeholder="可留空"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="开放类型">
          <el-radio-group v-model="form.isPublic">
            <el-radio :label="false">私密</el-radio>
            <el-radio :label="true">公共</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="发布状态">
          <el-radio-group v-model="form.status">
            <el-radio label="ACTIVE">已发布</el-radio>
            <el-radio label="DISABLED">待发布</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shareVisible" title="分享看板" width="520px" destroy-on-close @closed="onShareDialogClosed">
      <p class="adm-board-hint">
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
      </el-form>
      <template #footer>
        <el-button @click="shareVisible = false">取消</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Delete,
  EditPen,
  Folder,
  Odometer,
  Plus,
  Refresh,
  Search
} from '@element-plus/icons-vue'
import { currentUser, restoreSessionHeader } from '../../store/session'
import DashboardBoardViewer from '../../components/dashboard/DashboardBoardViewer.vue'
import DashboardPreviewDialogs from '../../components/dashboard/DashboardPreviewDialogs.vue'
import DashboardGridEditor from '../user/DashboardGridEditor.vue'
import { countChartSlotsForDashboardRow, countBasicWidgetSlotsForDashboardRow } from '../../utils/dashboardGrid.js'
import {
  boardGroupDisplay,
  boardStatusTag,
  boardStatusTagType,
  boardIsPublic,
  boardVisibilityLabel,
  canDesignBoard,
  canDirectEditBoard,
  authorDisplay,
  isBoardOwner,
  isBoardPublished,
  isPublicSaveAsDesign,
  publisherDisplay,
  saveAsDisplay
} from '../../utils/dashboardManageTree.js'

const API_BASE = 'http://localhost:8080'
/** 所属分组下拉：根目录（对应 parent_id = null） */
const GROUP_ROOT_PARENT_ID = 0

const rows = ref([])
const groupTree = ref([])
const loadingList = ref(false)
const loadingStats = ref(false)

const stats = reactive({
  totalCount: 0,
  publicCount: 0,
  privateCount: 0,
  totalViews: 0,
  topByViews: []
})

const typeChartRef = ref(null)
const topChartRef = ref(null)
let typeChart = null
let topChart = null

const treeSelectProps = { label: 'name', value: 'id', children: 'children' }
const groupFilterTreeProps = { label: 'name', children: 'children' }
const groupFilterPopoverVisible = ref(false)

const filters = reactive({
  keyword: '',
  isPublic: '1',
  groupId: 'ALL',
  status: 'ALL'
})

const pagination = reactive({
  page: 1,
  pageSize: 20,
  total: 0
})

const gridEditorVisible = ref(false)
const gridEditorRow = ref(null)
const gridEditorPromptVisibility = ref(false)
const previewDialogsRef = ref(null)

const boardViewerVisible = ref(false)
const boardViewerRow = ref(null)

const shareVisible = ref(false)
const sharing = ref(false)
const shareTargetId = ref(null)
const shareExpireAt = ref('')
const shareLinkText = ref('')

const groupVisible = ref(false)
const groupSaving = ref(false)
const groupEditId = ref(null)
const groupForm = reactive({
  name: '',
  parentId: GROUP_ROOT_PARENT_ID
})

const boardVisible = ref(false)
const boardSaving = ref(false)
const boardForm = reactive({
  name: '',
  description: '',
  groupId: null
})

const editVisible = ref(false)
const saving = ref(false)
const unpublishingId = ref(null)
const editId = ref(null)
const form = reactive({
  name: '',
  description: '',
  groupId: null,
  isPublic: false,
  status: 'ACTIVE'
})

const groupSelectTree = computed(() => decorateGroupNodesOnly(groupTree.value))

const GROUP_FILTER_ALL = 'ALL'
const GROUP_FILTER_ROOT = -1

function decorateGroupFilterNodes(nodes) {
  return (Array.isArray(nodes) ? nodes : []).map((node) => {
    const id = normalizeTreeId(node.id) ?? node.id
    return {
      ...node,
      id,
      nodeKey: `group:${id}`,
      isPlatform: true,
      children: decorateGroupFilterNodes(node.children)
    }
  })
}

const groupFilterTree = computed(() => [
  { id: GROUP_FILTER_ALL, nodeKey: 'filter:all', name: '分组：全部', isFilterMeta: true },
  { id: GROUP_FILTER_ROOT, nodeKey: 'filter:root', name: '分组：根目录', isFilterMeta: true },
  ...decorateGroupFilterNodes(groupSelectTree.value)
])

const currentGroupFilterNodeKey = computed(() => {
  if (filters.groupId === GROUP_FILTER_ALL) return 'filter:all'
  if (filters.groupId === '-1' || Number(filters.groupId) === GROUP_FILTER_ROOT) return 'filter:root'
  const id = normalizeTreeId(filters.groupId)
  return id ? `group:${id}` : 'filter:all'
})

function findGroupPathInTree(nodes, id, prefix = '') {
  const targetId = normalizeTreeId(id)
  if (!targetId) return null
  for (const node of Array.isArray(nodes) ? nodes : []) {
    const name = String(node?.name || '').trim()
    const label = prefix ? `${prefix} / ${name}` : name
    if (normalizeTreeId(node?.id) === targetId) return label
    const found = findGroupPathInTree(node?.children, targetId, label)
    if (found) return found
  }
  return null
}

const groupFilterLabel = computed(() => {
  if (filters.groupId === GROUP_FILTER_ALL) return '分组：全部'
  if (filters.groupId === '-1' || Number(filters.groupId) === GROUP_FILTER_ROOT) return '分组：根目录'
  const path = findGroupPathInTree(groupTree.value, filters.groupId)
  return path ? `分组：${path}` : '分组：全部'
})

function onGroupFilterNodeClick(data) {
  if (!data) return
  if (data.isFilterMeta) {
    filters.groupId = data.nodeKey === 'filter:root' ? '-1' : GROUP_FILTER_ALL
  } else {
    filters.groupId = String(data.id)
  }
  groupFilterPopoverVisible.value = false
  onSearch()
}

const groupParentSelectTree = computed(() => [
  {
    id: GROUP_ROOT_PARENT_ID,
    name: '根目录',
    children: groupSelectTree.value
  }
])

function decorateGroupNodesOnly(nodes) {
  if (!Array.isArray(nodes)) return []
  return nodes.map((node) => ({
    ...node,
    id: normalizeTreeId(node.id) ?? node.id,
    parentId: normalizeTreeId(node.parentId) ?? node.parentId ?? null,
    children: decorateGroupNodesOnly(node.children)
  }))
}

function canManageRow(row) {
  if (row?.canManage != null) return Boolean(row.canManage)
  if (boardIsPublic(row)) return true
  const ownerId = String(row?.ownerUserId || '').trim()
  const me = String(currentUser.value?.userId || '').trim()
  return Boolean(ownerId && me && ownerId === me)
}


function formatDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value).slice(0, 16)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day} ${h}:${min}`
}

function buildListParams() {
  const params = {
    page: pagination.page,
    pageSize: pagination.pageSize
  }
  const kw = String(filters.keyword || '').trim()
  if (kw) params.keyword = kw
  if (filters.isPublic !== 'ALL') params.isPublic = Number(filters.isPublic)
  if (filters.groupId && filters.groupId !== 'ALL') params.groupId = Number(filters.groupId)
  if (filters.status && filters.status !== 'ALL') params.status = filters.status
  return params
}

function renderTypeChart() {
  if (!typeChartRef.value) return
  if (!typeChart) {
    typeChart = echarts.getInstanceByDom(typeChartRef.value) || echarts.init(typeChartRef.value)
  }
  const pub = Number(stats.publicCount) || 0
  const priv = Number(stats.privateCount) || 0
  const seriesData = []
  if (pub > 0) seriesData.push({ name: '公共看板', value: pub })
  if (priv > 0) seriesData.push({ name: '私密看板', value: priv })
  if (!seriesData.length) {
    seriesData.push({ name: '暂无看板', value: 1, itemStyle: { color: '#e5e7eb' } })
  }
  typeChart.setOption({
    color: ['#f59e0b', '#6366f1', '#e5e7eb'],
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, left: 'center', textStyle: { fontSize: 12 } },
    series: [{
      type: 'pie',
      radius: ['16%', '68%'],
      center: ['50%', '44%'],
      roseType: 'area',
      itemStyle: { borderRadius: 4 },
      label: { fontSize: 12, formatter: '{b}\n{c}' },
      data: seriesData
    }]
  }, true)
}

function renderTopChart() {
  if (!topChartRef.value) return
  if (!topChart) {
    topChart = echarts.getInstanceByDom(topChartRef.value) || echarts.init(topChartRef.value)
  }
  const items = (Array.isArray(stats.topByViews) ? stats.topByViews : []).slice(0, 5)
  const names = items.map((row) => String(row?.name || `#${row?.id || ''}`)).reverse()
  const views = items.map((row) => Number(row?.viewCount) || 0).reverse()
  topChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 16, top: 8, bottom: 8, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { type: 'dashed', color: '#e5e7eb' } } },
    yAxis: {
      type: 'category',
      data: names.length ? names : ['暂无数据'],
      axisLabel: { width: 96, overflow: 'truncate', fontSize: 12 }
    },
    series: [{
      type: 'bar',
      data: names.length ? views : [0],
      itemStyle: { color: '#3b82f6', borderRadius: [0, 4, 4, 0] },
      barMaxWidth: 22
    }]
  }, true)
}

function renderStatsCharts() {
  renderTypeChart()
  renderTopChart()
}

function onStatsResize() {
  typeChart?.resize()
  topChart?.resize()
}

async function loadStats() {
  loadingStats.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/admin/dashboards/stats`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    const data = res.data.data || {}
    stats.totalCount = Number(data.totalCount) || 0
    stats.publicCount = Number(data.publicCount) || 0
    stats.privateCount = Number(data.privateCount) || 0
    stats.totalViews = Number(data.totalViews) || 0
    stats.topByViews = Array.isArray(data.topByViews) ? data.topByViews : []
    await nextTick()
    renderStatsCharts()
  } catch (e) {
    ElMessage.error(e.message || '统计加载失败')
  } finally {
    loadingStats.value = false
  }
}

async function loadGroupTree() {
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/admin/dashboard-groups/tree`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    groupTree.value = Array.isArray(res.data.data) ? res.data.data : []
  } catch {
    groupTree.value = []
  }
}

async function loadList() {
  loadingList.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/admin/dashboards`, { params: buildListParams() })
    if (res.data.code !== 200) throw new Error(res.data.message)
    const data = res.data.data || {}
    rows.value = (Array.isArray(data.items) ? data.items : []).map((row) => ({
      ...row,
      chartCardCount: countChartSlotsForDashboardRow(row?.layoutJson),
      basicWidgetCount: countBasicWidgetSlotsForDashboardRow(row?.layoutJson)
    }))
    pagination.total = Number(data.total) || 0
    pagination.page = Number(data.page) || pagination.page
    pagination.pageSize = Number(data.pageSize) || pagination.pageSize
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingList.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadStats(), loadGroupTree(), loadList()])
}

function onSearch() {
  pagination.page = 1
  loadList()
}

function onPageSizeChange() {
  pagination.page = 1
  loadList()
}

function normalizeTreeId(value) {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
}

function resetGroupForm() {
  groupForm.name = ''
  groupForm.parentId = GROUP_ROOT_PARENT_ID
}

function normalizeGroupParentId(value) {
  if (value == null || value === '' || Number(value) === GROUP_ROOT_PARENT_ID) return null
  return normalizeTreeId(value)
}

function findGroupInTree(nodes, id) {
  const targetId = normalizeTreeId(id)
  if (!targetId) return null
  for (const node of Array.isArray(nodes) ? nodes : []) {
    const nodeId = normalizeTreeId(node?.id)
    if (nodeId === targetId) return node
    const found = findGroupInTree(node?.children, targetId)
    if (found) return found
  }
  return null
}

function openEditGroup(data) {
  const id = normalizeTreeId(data?.id)
  if (!id) return
  groupEditId.value = id
  groupForm.name = String(data?.name || '').trim()
  const parentId = normalizeTreeId(data?.parentId)
  groupForm.parentId = parentId ?? GROUP_ROOT_PARENT_ID
  groupVisible.value = true
}

function openEditPlatformGroup(group) {
  const node = findGroupInTree(groupTree.value, group?.id)
  if (!node) {
    ElMessage.warning('分组不存在或已删除')
    return
  }
  openEditGroup(node)
}

function openNewGroup() {
  groupEditId.value = null
  resetGroupForm()
  groupVisible.value = true
}

function onCreateCommand(command) {
  if (command === 'new-group') {
    openNewGroup()
    return
  }
  if (command === 'new-board') {
    openNewBoard()
  }
}

function openNewBoard() {
  boardForm.name = ''
  boardForm.description = ''
  boardForm.groupId = null
  boardVisible.value = true
}

async function saveGroup() {
  const name = String(groupForm.name || '').trim()
  if (!name) {
    ElMessage.warning('请填写分组名称')
    return
  }
  groupSaving.value = true
  restoreSessionHeader()
  try {
    const body = {
      name,
      parentId: normalizeGroupParentId(groupForm.parentId)
    }
    if (groupEditId.value) {
      const res = await axios.put(`${API_BASE}/api/c/admin/dashboard-groups/${groupEditId.value}`, body)
      if (res.data.code !== 200) throw new Error(res.data.message)
    } else {
      const res = await axios.post(`${API_BASE}/api/c/admin/dashboard-groups`, body)
      if (res.data.code !== 200) throw new Error(res.data.message)
    }
    ElMessage.success('分组已保存')
    groupVisible.value = false
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    groupSaving.value = false
  }
}

async function removePlatformGroup(group) {
  const id = normalizeTreeId(group?.id)
  const name = String(group?.name || '').trim()
  if (!id) return
  try {
    await ElMessageBox.confirm(`确定删除分组「${name}」？`, '确认删除', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.delete(`${API_BASE}/api/c/admin/dashboard-groups/${id}`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('分组已删除')
    if (filters.groupId === String(id)) {
      filters.groupId = 'ALL'
      pagination.page = 1
    }
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function boardFormGroupIdOrNull(value) {
  return normalizeTreeId(value)
}

async function createBoard() {
  const name = String(boardForm.name || '').trim()
  if (!name) {
    ElMessage.warning('请填写看板名称')
    return
  }
  boardSaving.value = true
  restoreSessionHeader()
  try {
    const body = {
      name,
      description: String(boardForm.description || '').trim() || null,
      groupId: boardFormGroupIdOrNull(boardForm.groupId),
      layoutJson: '{}',
      isPublic: false,
      status: 'DISABLED'
    }
    const res = await axios.post(`${API_BASE}/api/c/admin/dashboards`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    const created = res.data.data || {}
    ElMessage.success('看板已创建')
    boardVisible.value = false
    await loadList()
    gridEditorRow.value = created
    gridEditorPromptVisibility.value = true
    gridEditorVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    boardSaving.value = false
  }
}

function openViewBoard(row) {
  if (!row?.id) {
    ElMessage.warning('看板 ID 无效')
    return
  }
  boardViewerRow.value = { ...row }
  boardViewerVisible.value = true
}

function openGridEditor(row) {
  if (!canManageRow(row)) {
    ElMessage.warning('用户私有看板仅可查看，无法编辑布局')
    return
  }
  if (!canDesignBoard(row)) {
    ElMessage.warning(row?.isPublic ? '未发布的公共看板仅可预览，请先发布后再设计' : '当前看板不可设计')
    return
  }
  if (isPublicSaveAsDesign(row) && !canDirectEditBoard(row)) {
    ElMessage.info('他人已发布公共看板须先「另存为」副本后再编辑')
  }
  gridEditorRow.value = row ? { ...row } : null
  gridEditorPromptVisibility.value = true
  gridEditorVisible.value = true
}

function openEdit(row) {
  if (!canManageRow(row)) {
    ElMessage.warning('用户私有看板仅可查看，无法编辑')
    return
  }
  if (!isBoardOwner(row)) {
    ElMessage.warning('他人看板不可编辑，请使用「另存为」保存副本后再操作')
    return
  }
  editId.value = row.id
  form.name = row.name || ''
  form.description = row.description || ''
  form.groupId = row.groupId || null
  form.isPublic = Boolean(row.isPublic)
  form.status = String(row.status || 'ACTIVE').toUpperCase() === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
  editVisible.value = true
}

function buildEditBody() {
  return {
    name: String(form.name || '').trim(),
    description: String(form.description || '').trim() || null,
    groupId: boardFormGroupIdOrNull(form.groupId),
    isPublic: Boolean(form.isPublic),
    status: form.status
  }
}

async function unpublishRow(row) {
  if (!row?.id || isBoardOwner(row) || !isBoardPublished(row)) return
  try {
    await ElMessageBox.confirm(
      `确定将看板「${row.name || row.id}」强制下线？下线后须由创建者本人重新发布。`,
      '强制下线',
      { type: 'warning' }
    )
  } catch {
    return
  }
  unpublishingId.value = row.id
  restoreSessionHeader()
  try {
    const res = await axios.put(`${API_BASE}/api/c/dashboards/${row.id}`, { status: 'DISABLED' })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已强制下线')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '下线失败')
  } finally {
    unpublishingId.value = null
  }
}

async function saveEdit() {
  const name = String(form.name || '').trim()
  if (!name) {
    ElMessage.warning('请填写看板名称')
    return
  }
  saving.value = true
  restoreSessionHeader()
  try {
    const body = buildEditBody()
    const res = await axios.put(`${API_BASE}/api/c/dashboards/${editId.value}`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已保存')
    editVisible.value = false
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function onShareDialogClosed() {
  shareLinkText.value = ''
  shareTargetId.value = null
}

function buildShareLink(row) {
  const token = String(row?.shareToken || '').trim()
  if (!token) return ''
  const url = new URL(window.location.href)
  url.searchParams.set('shareToken', token)
  return url.toString()
}

function openShareDialog(row) {
  if (!isBoardPublished(row)) {
    ElMessage.warning('待发布看板无法分享，请先发布')
    return
  }
  shareTargetId.value = row.id
  shareExpireAt.value = row.shareExpireAt ? String(row.shareExpireAt).replace('T', ' ').slice(0, 19) : ''
  shareLinkText.value = ''
  shareVisible.value = true
}

async function generateShareLink() {
  if (!shareTargetId.value) return
  sharing.value = true
  restoreSessionHeader()
  try {
    const body = shareExpireAt.value ? { expireAt: shareExpireAt.value } : {}
    const res = await axios.post(`${API_BASE}/api/c/dashboards/${shareTargetId.value}/share/enable`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    shareLinkText.value = buildShareLink(res.data.data)
    ElMessage.success('链接已生成，原链接已失效，请复制下方链接')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '生成链接失败')
  } finally {
    sharing.value = false
  }
}

async function copyShareLinkText() {
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

async function remove(row) {
  if (!canManageRow(row)) {
    ElMessage.warning('用户私有看板仅可查看，无法删除')
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除看板「${row.name}」？`, '确认删除', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.delete(`${API_BASE}/api/c/dashboards/${row.id}`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已删除')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function onGridSaved() {
  gridEditorPromptVisibility.value = false
  await refreshAll()
}

onMounted(async () => {
  window.addEventListener('resize', onStatsResize)
  await refreshAll()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onStatsResize)
  typeChart?.dispose()
  topChart?.dispose()
  typeChart = null
  topChart = null
})
</script>

<style scoped>
@import '../../styles/dashboard-manage.css';
.adm-dashboard {
  padding: 4px 2px 24px;
}
.adm-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.adm-stats-wrap {
  margin-bottom: 16px;
}
.adm-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.adm-stat-card {
  padding: 16px 18px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.04);
}
.adm-stat-card--blue { border-top: 3px solid #3b82f6; }
.adm-stat-card--amber { border-top: 3px solid #f59e0b; }
.adm-stat-card--indigo { border-top: 3px solid #6366f1; }
.adm-stat-card--green { border-top: 3px solid #22c55e; }
.adm-stat-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}
.adm-stat-value {
  display: block;
  margin-top: 8px;
  font-size: 28px;
  line-height: 1.1;
  font-weight: 700;
  color: #111827;
}
.adm-stat-hint {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.4;
}
.adm-charts-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.adm-chart-panel {
  padding: 14px 16px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
}
.adm-chart-title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: #111827;
}
.adm-chart-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: #9ca3af;
}
.adm-chart-canvas {
  width: 100%;
  height: 240px;
}
.adm-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: #111827;
}
.adm-sub {
  margin: 0;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.5;
}
.adm-body {
  min-height: 520px;
}
.adm-dropdown-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.adm-main {
  width: 100%;
  min-width: 0;
}
.adm-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafafa;
}
.adm-filter-search {
  width: min(320px, 100%);
  flex: 1 1 220px;
}
.adm-filter-select {
  width: 160px;
}
.adm-filter-group {
  width: min(240px, 100%);
}
.adm-group-filter-trigger :deep(.el-input__wrapper) {
  cursor: pointer;
}
.adm-group-filter-caret {
  color: #a8abb2;
  transition: transform 0.2s ease;
}
.adm-group-filter-tree-wrap {
  max-height: 360px;
  overflow: auto;
  margin: -4px -2px;
}
.adm-group-filter-tree-wrap :deep(.el-tree-node__content) {
  height: 34px;
}
.adm-group-filter-folder {
  flex-shrink: 0;
  color: #64748b;
}
.adm-group-filter-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-width: 0;
}
.adm-group-filter-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.adm-group-filter-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  margin-right: -4px;
}
.adm-group-filter-edit,
.adm-group-filter-del {
  padding: 0 2px;
}
.adm-filter-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.adm-table {
  width: 100%;
}
.adm-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
.adm-board-hint {
  margin: 0 0 0 88px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}
.adm-edit-status-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
@media (max-width: 900px) {
  .adm-head {
    flex-direction: column;
  }
  .adm-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .adm-charts-grid {
    grid-template-columns: 1fr;
  }
  .adm-filter-actions {
    margin-left: 0;
    width: 100%;
    justify-content: flex-end;
  }
}
@media (max-width: 560px) {
  .adm-stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
.adm-group-filter-popper {
  padding: 10px 12px !important;
}
.adm-group-filter-popper .adm-group-filter-option.is-meta .adm-group-filter-name {
  font-weight: 500;
}
</style>
