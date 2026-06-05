<template>
  <section class="adm-dashboard">
    <div class="adm-head">
      <div>
        <h1 class="adm-title">看板管理</h1>
        <p class="adm-sub">管理平台公共看板分类与批量运维；分组仅用于公共看板，公共/个人在保存时选择。</p>
      </div>
    </div>

    <div class="adm-body">
      <aside class="adm-sidebar">
        <div class="adm-nav-toolbar">
          <el-dropdown trigger="click" @command="onCreateCommand">
            <button type="button" class="adm-nav-add" title="新建">
              <el-icon :size="16"><Plus /></el-icon>
              <el-icon class="adm-nav-add-caret" :size="10"><ArrowDown /></el-icon>
            </button>
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
          <el-input
            v-model="treeSearch"
            class="adm-nav-search"
            clearable
            placeholder="搜索"
            :prefix-icon="Search"
          />
          <div class="adm-nav-actions">
            <el-button link class="adm-nav-icon-btn" :loading="loadingTree" title="刷新" @click="refreshAll">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </div>
        </div>

        <el-tree
          ref="groupTreeRef"
          v-loading="loadingTree"
          class="adm-group-tree"
          :data="navTree"
          node-key="nodeKey"
          :props="treeProps"
          highlight-current
          default-expand-all
          :expand-on-click-node="false"
          @node-click="onTreeNodeClick"
        >
          <template #default="{ data }">
            <div class="adm-tree-node" :class="`is-${data.kind}`">
              <el-icon v-if="data.kind === 'group'" class="adm-tree-type-icon is-folder" :size="15">
                <Folder />
              </el-icon>
              <el-icon v-else-if="data.kind === 'board'" class="adm-tree-type-icon is-board" :size="15">
                <Odometer />
              </el-icon>
              <el-icon v-else class="adm-tree-type-icon is-folder" :size="15">
                <Folder />
              </el-icon>
              <el-tag
                v-if="data.kind === 'board'"
                size="small"
                class="adm-tree-status"
                :type="boardStatusTagType(data)"
                effect="light"
              >
                {{ boardStatusTag(data) }}
              </el-tag>
              <span class="adm-tree-label" :title="data.name">{{ data.name }}</span>
              <el-dropdown
                v-if="showTreeNodeMenu(data)"
                trigger="click"
                @command="(cmd) => onTreeNodeCommand(cmd, data)"
                @click.stop
              >
                <button type="button" class="adm-tree-more" title="更多操作" @click.stop>
                  <el-icon :size="14"><More /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <template v-if="data.kind === 'group' || data.kind === 'virtual'">
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
                    </template>
                    <template v-if="data.kind === 'group' || data.kind === 'virtual'">
                      <el-dropdown-item command="rename" divided>
                        <span class="adm-dropdown-item">
                          <el-icon><EditPen /></el-icon>
                          重命名
                        </span>
                      </el-dropdown-item>
                      <el-dropdown-item command="delete">
                        <span class="adm-dropdown-item">
                          <el-icon><Delete /></el-icon>
                          删除
                        </span>
                      </el-dropdown-item>
                    </template>
                    <template v-if="data.kind === 'board'">
                      <el-dropdown-item command="rename">
                        <span class="adm-dropdown-item">
                          <el-icon><EditPen /></el-icon>
                          重命名
                        </span>
                      </el-dropdown-item>
                      <el-dropdown-item command="delete">
                        <span class="adm-dropdown-item">
                          <el-icon><Delete /></el-icon>
                          删除
                        </span>
                      </el-dropdown-item>
                    </template>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-tree>
        <el-empty v-if="!navTree.length && !loadingTree" :image-size="56" description="暂无分组或看板" />
      </aside>

      <div class="adm-main">
        <div class="adm-filters">
          <el-input
            v-model="filters.keyword"
            class="adm-filter-search"
            clearable
            placeholder="按看板名称或所有者搜索…"
            :prefix-icon="Search"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-select v-model="filters.isPublic" class="adm-filter-select" @change="onSearch">
            <el-option label="看板类型：全部" value="ALL" />
            <el-option label="公共" value="1" />
            <el-option label="个人" value="0" />
          </el-select>
          <el-select v-model="filters.status" class="adm-filter-select" @change="onSearch">
            <el-option label="运行状态：全部" value="ALL" />
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </div>

        <el-table :data="rows" border v-loading="loadingList" empty-text="暂无看板" class="adm-table">
          <el-table-column prop="id" label="ID" width="72" />
          <el-table-column prop="name" label="看板名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="分组" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">
              {{ groupDisplay(row) }}
            </template>
          </el-table-column>
          <el-table-column label="看板类型" width="108">
            <template #default="{ row }">
              <span class="adm-type" :class="row.isPublic ? 'is-public' : 'is-personal'">
                <el-icon :size="14">
                  <OfficeBuilding v-if="row.isPublic" />
                  <User v-else />
                </el-icon>
                {{ row.isPublic ? '公共' : '个人' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="所有者" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              {{ ownerDisplay(row) }}
            </template>
          </el-table-column>
          <el-table-column label="运行状态" width="100">
            <template #default="{ row }">
              <span class="adm-status" :class="statusTone(row)">
                <i class="adm-status-dot" />
                {{ statusLabel(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="120">
            <template #default="{ row }">
              {{ formatDate(row.updatedAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openViewBoard(row)">查看看板</el-button>
              <template v-if="canManageRow(row)">
                <el-button link type="primary" @click="openGridEditor(row)">设计布局</el-button>
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
              <el-tag v-else size="small" type="info" class="adm-view-only-tag">仅查看</el-tag>
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

    <DashboardGridEditor
      v-model="gridEditorVisible"
      :initial-row="gridEditorRow"
      :api-base="API_BASE"
      :prompt-visibility-on-save="gridEditorPromptVisibility"
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
      :title="groupEditId ? '编辑分组' : '新建分组'"
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
        <p class="adm-board-hint">公共/个人将在设计器中点击「保存布局」时选择。</p>
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
        <el-form-item label="看板类型">
          <el-radio-group v-model="form.isPublic">
            <el-radio :label="false">个人</el-radio>
            <el-radio :label="true">公共</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="运行状态">
          <el-radio-group v-model="form.status">
            <el-radio label="ACTIVE">启用</el-radio>
            <el-radio label="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Delete,
  EditPen,
  Folder,
  More,
  Odometer,
  OfficeBuilding,
  Plus,
  Refresh,
  Search,
  User
} from '@element-plus/icons-vue'
import { currentUser, restoreSessionHeader } from '../../store/session'
import DashboardBoardViewer from '../../components/dashboard/DashboardBoardViewer.vue'
import DashboardGridEditor from '../user/DashboardGridEditor.vue'

const API_BASE = 'http://localhost:8080'
/** 所属分组下拉：根目录（对应 parent_id = null） */
const GROUP_ROOT_PARENT_ID = 0

const rows = ref([])
const allBoards = ref([])
const groupTree = ref([])
const loadingList = ref(false)
const loadingTree = ref(false)
const groupTreeRef = ref(null)
const treeSearch = ref('')

const treeProps = { label: 'name', children: 'children' }
const treeSelectProps = { label: 'name', value: 'id', children: 'children' }

const filters = reactive({
  keyword: '',
  isPublic: '1',
  status: 'ALL'
})

const groupFilter = ref({ kind: 'all' })
/** 侧栏树当前选中上下文，用于「新建」默认上级分组 */
const selectedTreeContext = ref({ kind: 'all', groupId: null })

const pagination = reactive({
  page: 1,
  pageSize: 20,
  total: 0
})

const gridEditorVisible = ref(false)
const gridEditorRow = ref(null)
const gridEditorPromptVisibility = ref(false)

const boardViewerVisible = ref(false)
const boardViewerRow = ref(null)

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
const editId = ref(null)
const form = reactive({
  name: '',
  description: '',
  groupId: null,
  isPublic: false,
  status: 'ACTIVE'
})

const navTree = computed(() => buildNavTree(groupTree.value, allBoards.value, treeSearch.value))

const groupSelectTree = computed(() => decorateGroupNodesOnly(groupTree.value))

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

function toBoardTreeNode(board) {
  return {
    kind: 'board',
    nodeKey: `b-${board.id}`,
    id: board.id,
    name: board.name || `看板 #${board.id}`,
    status: board.status,
    isPublic: board.isPublic,
    groupId: board.groupId,
    raw: board
  }
}

function buildNavTree(groups, boards, keyword) {
  const boardsByGroupId = new Map()
  const unassignedBoards = []
  for (const board of Array.isArray(boards) ? boards : []) {
    const gid = Number(board.groupId)
    const node = toBoardTreeNode(board)
    if (Number.isFinite(gid) && gid > 0) {
      if (!boardsByGroupId.has(gid)) boardsByGroupId.set(gid, [])
      boardsByGroupId.get(gid).push(node)
    } else {
      unassignedBoards.push(node)
    }
  }

  function mapGroup(node) {
    const childGroups = (node.children || []).map(mapGroup)
    const childBoards = boardsByGroupId.get(Number(node.id)) || []
    childBoards.sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN'))
    return {
      kind: 'group',
      nodeKey: `g-${node.id}`,
      id: node.id,
      name: node.name,
      parentId: node.parentId,
      children: [...childGroups, ...childBoards]
    }
  }

  const roots = (Array.isArray(groups) ? groups : []).map(mapGroup)
  unassignedBoards.sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN'))
  roots.unshift({
    kind: 'virtual',
    nodeKey: 'unassigned',
    name: '未分组',
    children: unassignedBoards
  })

  const kw = String(keyword || '').trim().toLowerCase()
  if (!kw) return roots
  return filterNavTree(roots, kw)
}

function filterNavTree(nodes, keyword) {
  const result = []
  for (const node of nodes) {
    const children = Array.isArray(node.children) ? filterNavTree(node.children, keyword) : []
    const selfMatch = String(node.name || '').toLowerCase().includes(keyword)
    if (selfMatch || children.length) {
      result.push({
        ...node,
        children: selfMatch ? node.children || [] : children
      })
    }
  }
  return result
}

function boardStatusTag(data) {
  const s = String(data?.status || 'ACTIVE').toUpperCase()
  if (s === 'ACTIVE') return '已发布'
  if (s === 'DISABLED') return '已停用'
  return s
}

function boardStatusTagType(data) {
  const s = String(data?.status || 'ACTIVE').toUpperCase()
  return s === 'ACTIVE' ? 'success' : 'info'
}

function canManageRow(row) {
  if (row?.canManage != null) return Boolean(row.canManage)
  if (Boolean(row?.isPublic)) return true
  const ownerId = String(row?.ownerUserId || '').trim()
  const me = String(currentUser.value?.userId || '').trim()
  return Boolean(ownerId && me && ownerId === me)
}

function canManageTreeBoard(data) {
  return canManageRow(data?.raw || data)
}

function showTreeNodeMenu(data) {
  if (data.kind === 'group' || data.kind === 'virtual') return true
  if (data.kind === 'board') return canManageTreeBoard(data)
  return false
}

function ownerDisplay(row) {
  const nick = String(row?.ownerNickname || '').trim()
  const user = String(row?.ownerUsername || '').trim()
  const uid = String(row?.ownerUserId || '').trim()
  return nick || user || uid || '—'
}

function groupDisplay(row) {
  const path = String(row?.groupPath || '').trim()
  if (path) return path
  const name = String(row?.groupName || '').trim()
  return name || '未分组'
}

function statusLabel(row) {
  const s = String(row?.status || 'ACTIVE').toUpperCase()
  if (s === 'ACTIVE') return '启用'
  if (s === 'DISABLED') return '停用'
  return s
}

function statusTone(row) {
  const s = String(row?.status || 'ACTIVE').toUpperCase()
  return s === 'ACTIVE' ? 'is-on' : 'is-off'
}

function formatDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value).slice(0, 10)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function buildListParams() {
  const params = {
    page: pagination.page,
    pageSize: pagination.pageSize
  }
  const kw = String(filters.keyword || '').trim()
  if (kw) params.keyword = kw
  if (groupFilter.value.kind === 'group' && groupFilter.value.id) {
    params.groupId = groupFilter.value.id
  } else if (groupFilter.value.kind === 'unassigned') {
    params.groupId = -1
  }
  if (filters.isPublic !== 'ALL') params.isPublic = Number(filters.isPublic)
  if (filters.status && filters.status !== 'ALL') params.status = filters.status
  return params
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

async function loadAllBoardsForTree() {
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/admin/dashboards`, {
      params: { page: 1, pageSize: 500 }
    })
    if (res.data.code !== 200) throw new Error(res.data.message)
    allBoards.value = Array.isArray(res.data.data?.items) ? res.data.data.items : []
  } catch {
    allBoards.value = []
  }
}

async function loadTreeData() {
  loadingTree.value = true
  try {
    await Promise.all([loadGroupTree(), loadAllBoardsForTree()])
  } finally {
    loadingTree.value = false
  }
}

async function loadList() {
  loadingList.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/admin/dashboards`, { params: buildListParams() })
    if (res.data.code !== 200) throw new Error(res.data.message)
    const data = res.data.data || {}
    rows.value = Array.isArray(data.items) ? data.items : []
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
  await Promise.all([loadTreeData(), loadList()])
}

function onSearch() {
  pagination.page = 1
  loadList()
}

function onPageSizeChange() {
  pagination.page = 1
  loadList()
}

function onTreeNodeClick(data) {
  if (data.kind === 'board') {
    selectedTreeContext.value = { kind: 'board', groupId: normalizeTreeId(data.groupId) }
    groupFilter.value = { kind: 'all' }
    filters.keyword = data.name || ''
    pagination.page = 1
    loadList()
    return
  }
  if (data.kind === 'virtual') {
    selectedTreeContext.value = { kind: data.nodeKey, groupId: null }
    groupFilter.value = data.nodeKey === 'unassigned' ? { kind: 'unassigned' } : { kind: 'all' }
  } else if (data.kind === 'group') {
    selectedTreeContext.value = { kind: 'group', groupId: normalizeTreeId(data.id) }
    groupFilter.value = { kind: 'group', id: data.id }
    filters.keyword = ''
  } else {
    selectedTreeContext.value = { kind: 'all', groupId: null }
    groupFilter.value = { kind: 'all' }
  }
  pagination.page = 1
  loadList()
}

function normalizeTreeId(value) {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
}

function defaultParentGroupId() {
  const ctx = selectedTreeContext.value
  if (ctx.kind === 'group' && ctx.groupId) return normalizeTreeId(ctx.groupId)
  if (ctx.kind === 'board' && ctx.groupId) return normalizeTreeId(ctx.groupId)

  const current = groupTreeRef.value?.getCurrentNode?.()
  if (current?.kind === 'group') return normalizeTreeId(current.id)
  if (current?.kind === 'board') return normalizeTreeId(current.groupId)
  return null
}

/** explicitParent: 从树节点菜单传入；undefined 表示用侧栏当前选中分组 */
function resolveCreateParentGroupId(explicitParent) {
  if (explicitParent !== undefined) return normalizeTreeId(explicitParent)
  return defaultParentGroupId()
}

function resetGroupForm() {
  groupForm.name = ''
  groupForm.parentId = GROUP_ROOT_PARENT_ID
}

function normalizeGroupParentId(value) {
  if (value == null || value === '' || Number(value) === GROUP_ROOT_PARENT_ID) return null
  return normalizeTreeId(value)
}

function openNewGroup(explicitParent = undefined) {
  groupEditId.value = null
  resetGroupForm()
  const parentId = resolveCreateParentGroupId(explicitParent)
  groupForm.parentId = parentId ?? GROUP_ROOT_PARENT_ID
  groupVisible.value = true
}

function openEditGroup(data) {
  groupEditId.value = data.id
  groupForm.name = data.name || ''
  groupForm.parentId = data.parentId ? data.parentId : GROUP_ROOT_PARENT_ID
  groupVisible.value = true
}

function syncTreeContextFromNode(data) {
  if (data.kind === 'group') {
    selectedTreeContext.value = { kind: 'group', groupId: normalizeTreeId(data.id) }
    groupTreeRef.value?.setCurrentKey(data.nodeKey)
    return
  }
  if (data.kind === 'virtual') {
    selectedTreeContext.value = { kind: data.nodeKey, groupId: null }
    groupTreeRef.value?.setCurrentKey(data.nodeKey)
  }
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

function onTreeNodeCommand(command, data) {
  syncTreeContextFromNode(data)
  if (data.kind === 'board') {
    const row = data.raw || data
    if (command === 'rename') {
      openEdit(row)
      return
    }
    if (command === 'delete') {
      remove(row)
    }
    return
  }
  if (command === 'new-group') {
    openNewGroup(data.kind === 'group' ? normalizeTreeId(data.id) : null)
    return
  }
  if (command === 'new-board') {
    openNewBoard(data.kind === 'group' ? normalizeTreeId(data.id) : null)
    return
  }
  if (data.kind === 'virtual') {
    if (command === 'rename') {
      ElMessage.info('「未分组」为系统分类，无法重命名')
      return
    }
    if (command === 'delete') {
      ElMessage.info('「未分组」为系统分类，无法删除')
    }
    return
  }
  if (command === 'rename') {
    openEditGroup(data)
    return
  }
  if (command === 'delete') {
    removeGroup(data)
  }
}

function openNewBoard(explicitGroupId = undefined) {
  boardForm.name = ''
  boardForm.description = ''
  boardForm.groupId = resolveCreateParentGroupId(explicitGroupId)
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
    await loadTreeData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    groupSaving.value = false
  }
}

function boardFormGroupIdOrNull(value) {
  return normalizeTreeId(value)
}

async function removeGroup(data) {
  try {
    await ElMessageBox.confirm(`确定删除分组「${data.name}」？`, '确认删除', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.delete(`${API_BASE}/api/c/admin/dashboard-groups/${data.id}`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已删除')
    if (groupFilter.value.kind === 'group' && groupFilter.value.id === data.id) {
      groupFilter.value = { kind: 'all' }
    }
    await refreshAll()
    await nextTick()
    groupTreeRef.value?.setCurrentKey(null)
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
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
      layoutJson: '{}'
    }
    const res = await axios.post(`${API_BASE}/api/c/admin/dashboards`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    const created = res.data.data || {}
    ElMessage.success('看板已创建')
    boardVisible.value = false
    await Promise.all([loadList(), loadAllBoardsForTree()])
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
  gridEditorRow.value = row ? { ...row } : null
  gridEditorPromptVisibility.value = true
  gridEditorVisible.value = true
}

function openEdit(row) {
  if (!canManageRow(row)) {
    ElMessage.warning('用户私有看板仅可查看，无法编辑')
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

async function saveEdit() {
  const name = String(form.name || '').trim()
  if (!name) {
    ElMessage.warning('请填写看板名称')
    return
  }
  saving.value = true
  restoreSessionHeader()
  try {
    const body = {
      name,
      description: String(form.description || '').trim() || null,
      groupId: boardFormGroupIdOrNull(form.groupId),
      isPublic: Boolean(form.isPublic),
      status: form.status
    }
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
  await refreshAll()
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
  margin-bottom: 16px;
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
  display: flex;
  gap: 16px;
  align-items: stretch;
  min-height: 520px;
}
.adm-sidebar {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}
.adm-nav-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px;
  border-bottom: 1px solid #eef0f3;
  background: #fafbfc;
}
.adm-nav-add {
  position: relative;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #d8dee6;
  border-radius: 6px;
  background: #fff;
  color: #374151;
  cursor: pointer;
  padding: 0;
}
.adm-nav-add:hover {
  border-color: #b8c0cc;
  color: #2563eb;
}
.adm-nav-add-caret {
  position: absolute;
  right: 2px;
  bottom: 1px;
  color: #9ca3af;
}
.adm-nav-search {
  flex: 1;
  min-width: 0;
}
.adm-nav-search :deep(.el-input__wrapper) {
  border-radius: 14px;
  box-shadow: 0 0 0 1px #e5e7eb inset;
  padding: 0 8px;
}
.adm-nav-search :deep(.el-input__inner) {
  font-size: 12px;
}
.adm-nav-actions {
  display: flex;
  align-items: center;
  gap: 0;
  flex-shrink: 0;
}
.adm-nav-icon-btn {
  width: 24px;
  height: 24px;
  padding: 0;
  color: #6b7280;
}
.adm-nav-icon-btn:hover {
  color: #2563eb;
}
.adm-dropdown-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.adm-group-tree {
  flex: 1;
  overflow: auto;
  padding: 4px 2px 8px;
  background: #fff;
}
.adm-group-tree :deep(.el-tree-node__content) {
  height: 30px;
  border-radius: 4px;
  padding-right: 4px;
}
.adm-group-tree :deep(.el-tree-node__expand-icon) {
  color: #9ca3af;
}
.adm-group-tree :deep(.el-tree-node__expand-icon.is-leaf) {
  width: 18px;
  padding: 0;
}
.adm-group-tree :deep(.el-tree-node__content:hover) {
  background: #f3f0ff;
}
.adm-group-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: #eef4ff;
}
.adm-tree-node {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  min-width: 0;
  padding-right: 4px;
}
.adm-tree-type-icon {
  flex-shrink: 0;
  color: #6b7280;
}
.adm-tree-type-icon.is-folder {
  color: #f59e0b;
}
.adm-tree-type-icon.is-board {
  color: #6366f1;
}
.adm-tree-status {
  flex-shrink: 0;
  height: 18px;
  line-height: 16px;
  padding: 0 4px;
  font-size: 10px;
  border-radius: 3px;
}
.adm-tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #374151;
}
.adm-tree-more {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  opacity: 0;
  flex-shrink: 0;
  transition: opacity 0.15s ease, background 0.15s ease;
}
.adm-tree-node:hover .adm-tree-more,
.adm-group-tree :deep(.el-tree-node__content:hover) .adm-tree-more {
  opacity: 1;
  background: #e8eaef;
}
.adm-tree-more:hover {
  color: #374151;
  background: #dfe3ea;
}
.adm-main {
  flex: 1;
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
.adm-table {
  width: 100%;
}
.adm-type {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}
.adm-type.is-public {
  color: #2563eb;
}
.adm-type.is-personal {
  color: #6b7280;
}
.adm-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.adm-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9ca3af;
}
.adm-status.is-on {
  color: #15803d;
}
.adm-status.is-on .adm-status-dot {
  background: #22c55e;
}
.adm-status.is-off {
  color: #b91c1c;
}
.adm-status.is-off .adm-status-dot {
  background: #ef4444;
}
.adm-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
.adm-view-only-tag {
  margin-left: 4px;
  vertical-align: middle;
}
.adm-board-hint {
  margin: 0 0 0 88px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}
@media (max-width: 900px) {
  .adm-head {
    flex-direction: column;
  }
  .adm-body {
    flex-direction: column;
  }
  .adm-sidebar {
    width: 100%;
  }
}
</style>
