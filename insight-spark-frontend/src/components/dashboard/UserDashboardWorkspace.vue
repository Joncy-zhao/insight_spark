<template>
  <section class="adm-dashboard">
    <div class="adm-head">
      <div>
        <h1 class="adm-title">我的看板</h1>
        <p class="adm-sub">管理个人私有看板，按分组整理、设计与分享。</p>
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
                  <span class="adm-dropdown-item"><el-icon><Folder /></el-icon>新建分组</span>
                </el-dropdown-item>
                <el-dropdown-item command="new-board">
                  <span class="adm-dropdown-item"><el-icon><Odometer /></el-icon>新建看板</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-input v-model="treeSearch" class="adm-nav-search" clearable placeholder="搜索" :prefix-icon="Search" />
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
              <el-icon v-if="data.kind === 'group'" class="adm-tree-type-icon is-folder" :size="15"><Folder /></el-icon>
              <el-icon v-else-if="data.kind === 'board'" class="adm-tree-type-icon is-board" :size="15"><Odometer /></el-icon>
              <el-icon v-else class="adm-tree-type-icon is-folder" :size="15"><Folder /></el-icon>
              <el-tag v-if="data.kind === 'board'" size="small" class="adm-tree-status" :type="boardStatusTagType(data)" effect="light">
                {{ boardStatusTag(data) }}
              </el-tag>
              <span class="adm-tree-label" :title="data.name">{{ data.name }}</span>
              <el-dropdown v-if="showTreeNodeMenu(data)" trigger="click" @command="(cmd) => onTreeNodeCommand(cmd, data)" @click.stop>
                <button type="button" class="adm-tree-more" title="更多操作" @click.stop>
                  <el-icon :size="14"><More /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <template v-if="data.kind === 'group' || data.kind === 'virtual'">
                      <el-dropdown-item command="new-group">
                        <span class="adm-dropdown-item"><el-icon><Folder /></el-icon>新建分组</span>
                      </el-dropdown-item>
                      <el-dropdown-item command="new-board">
                        <span class="adm-dropdown-item"><el-icon><Odometer /></el-icon>新建看板</span>
                      </el-dropdown-item>
                      <el-dropdown-item command="rename" divided>
                        <span class="adm-dropdown-item"><el-icon><EditPen /></el-icon>重命名</span>
                      </el-dropdown-item>
                      <el-dropdown-item command="delete">
                        <span class="adm-dropdown-item"><el-icon><Delete /></el-icon>删除</span>
                      </el-dropdown-item>
                    </template>
                    <template v-if="data.kind === 'board'">
                      <el-dropdown-item command="rename">
                        <span class="adm-dropdown-item"><el-icon><EditPen /></el-icon>重命名</span>
                      </el-dropdown-item>
                      <el-dropdown-item command="delete">
                        <span class="adm-dropdown-item"><el-icon><Delete /></el-icon>删除</span>
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
            placeholder="按看板名称搜索…"
            :prefix-icon="Search"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
        </div>

        <el-table :data="rows" border v-loading="loadingList" empty-text="暂无看板" class="adm-table">
          <el-table-column prop="id" label="ID" width="72" />
          <el-table-column prop="name" label="看板名称" min-width="140" show-overflow-tooltip />
          <el-table-column label="分组" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ groupDisplay(row) }}</template>
          </el-table-column>
          <el-table-column label="图表" width="80">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.chartCardCount || 0 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="分享" min-width="160">
            <template #default="{ row }">
              <el-tag :type="row.shareToken ? 'success' : 'info'" size="small">
                {{ row.shareToken ? '已分享' : '未分享' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="120">
            <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="360" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="$emit('preview', row)">查看图表</el-button>
              <el-button link type="primary" @click="openGridEditor(row)">设计看板</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="!row.shareToken" link type="success" @click="$emit('share', row)">分享</el-button>
              <el-button v-else link type="warning" @click="$emit('copy-share', row)">复制链接</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="adm-pager">
          <el-pagination
            v-model:current-page="pagination.page"
            v-model:page-size="pagination.pageSize"
            :total="pagination.total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="loadList"
            @size-change="onPageSizeChange"
          />
        </div>
      </div>
    </div>

    <DashboardGridEditor v-model="gridEditorVisible" :initial-row="gridEditorRow" :api-base="apiBase" @saved="onGridSaved" />

    <el-dialog v-model="groupVisible" :title="groupEditId ? '编辑分组' : '新建分组'" width="480px" destroy-on-close>
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

    <el-dialog v-model="boardVisible" title="新建看板" width="520px" destroy-on-close>
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
  Plus,
  Refresh,
  Search
} from '@element-plus/icons-vue'
import { restoreSessionHeader } from '../../store/session'
import DashboardGridEditor from '../../views/user/DashboardGridEditor.vue'
import { countChartSlotsForDashboardRow } from '../../utils/dashboardGrid.js'
import {
  boardStatusTag,
  boardStatusTagType,
  buildNavTree,
  decorateGroupNodesOnly,
  normalizeTreeId
} from '../../utils/dashboardManageTree.js'
import '../../styles/dashboard-manage.css'

const props = defineProps({
  apiBase: { type: String, default: 'http://localhost:8080' }
})

defineEmits(['preview', 'share', 'copy-share'])

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

const filters = reactive({ keyword: '' })
const groupFilter = ref({ kind: 'all' })
const selectedTreeContext = ref({ kind: 'all', groupId: null })
const pagination = reactive({ page: 1, pageSize: 20, total: 0 })

const gridEditorVisible = ref(false)
const gridEditorRow = ref(null)

const groupVisible = ref(false)
const groupSaving = ref(false)
const groupEditId = ref(null)
const groupForm = reactive({ name: '', parentId: GROUP_ROOT_PARENT_ID })

const boardVisible = ref(false)
const boardSaving = ref(false)
const boardForm = reactive({ name: '', description: '', groupId: null })

const editVisible = ref(false)
const saving = ref(false)
const editId = ref(null)
const form = reactive({ name: '', description: '', groupId: null })

const navTree = computed(() => buildNavTree(groupTree.value, allBoards.value, treeSearch.value))
const groupSelectTree = computed(() => decorateGroupNodesOnly(groupTree.value))
const groupParentSelectTree = computed(() => [
  { id: GROUP_ROOT_PARENT_ID, name: '根目录', children: groupSelectTree.value }
])

function showTreeNodeMenu(data) {
  return data.kind === 'group' || data.kind === 'virtual' || data.kind === 'board'
}

function groupDisplay(row) {
  const path = String(row?.groupPath || '').trim()
  if (path) return path
  const name = String(row?.groupName || '').trim()
  return name || '未分组'
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
  const params = { page: pagination.page, pageSize: pagination.pageSize }
  const kw = String(filters.keyword || '').trim()
  if (kw) params.keyword = kw
  if (groupFilter.value.kind === 'group' && groupFilter.value.id) params.groupId = groupFilter.value.id
  else if (groupFilter.value.kind === 'unassigned') params.groupId = -1
  return params
}

async function loadGroupTree() {
  restoreSessionHeader()
  try {
    const res = await axios.get(`${props.apiBase}/api/c/dashboard-groups/tree`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    groupTree.value = Array.isArray(res.data.data) ? res.data.data : []
  } catch {
    groupTree.value = []
  }
}

async function loadAllBoardsForTree() {
  restoreSessionHeader()
  try {
    const res = await axios.get(`${props.apiBase}/api/c/dashboards/mine`, { params: { page: 1, pageSize: 500 } })
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
    const res = await axios.get(`${props.apiBase}/api/c/dashboards/mine`, { params: buildListParams() })
    if (res.data.code !== 200) throw new Error(res.data.message)
    const data = res.data.data || {}
    rows.value = (Array.isArray(data.items) ? data.items : []).map((row) => ({
      ...row,
      chartCardCount: countChartSlotsForDashboardRow(row?.layoutJson)
    }))
    pagination.total = Number(data.total) || 0
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
  }
  pagination.page = 1
  loadList()
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

function resolveCreateParentGroupId(explicitParent) {
  if (explicitParent !== undefined) return normalizeTreeId(explicitParent)
  return defaultParentGroupId()
}

function normalizeGroupParentId(value) {
  if (value == null || value === '' || Number(value) === GROUP_ROOT_PARENT_ID) return null
  return normalizeTreeId(value)
}

function resetGroupForm() {
  groupForm.name = ''
  groupForm.parentId = GROUP_ROOT_PARENT_ID
}

function openNewGroup(explicitParent = undefined) {
  groupEditId.value = null
  resetGroupForm()
  groupForm.parentId = resolveCreateParentGroupId(explicitParent) ?? GROUP_ROOT_PARENT_ID
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
  } else if (data.kind === 'virtual') {
    selectedTreeContext.value = { kind: data.nodeKey, groupId: null }
    groupTreeRef.value?.setCurrentKey(data.nodeKey)
  }
}

function onCreateCommand(command) {
  if (command === 'new-group') openNewGroup()
  else if (command === 'new-board') openNewBoard()
}

function onTreeNodeCommand(command, data) {
  syncTreeContextFromNode(data)
  if (data.kind === 'board') {
    const row = data.raw || data
    if (command === 'rename') openEdit(row)
    else if (command === 'delete') remove(row)
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
    if (command === 'rename') ElMessage.info('「未分组」为系统分类，无法重命名')
    else if (command === 'delete') ElMessage.info('「未分组」为系统分类，无法删除')
    return
  }
  if (command === 'rename') openEditGroup(data)
  else if (command === 'delete') removeGroup(data)
}

function openNewBoard(explicitGroupId = undefined) {
  boardForm.name = ''
  boardForm.description = ''
  boardForm.groupId = resolveCreateParentGroupId(explicitGroupId)
  boardVisible.value = true
}

function openGridEditor(row) {
  gridEditorRow.value = row ? { ...row } : null
  gridEditorVisible.value = true
}

function openEdit(row) {
  editId.value = row.id
  form.name = row.name || ''
  form.description = row.description || ''
  form.groupId = row.groupId || null
  editVisible.value = true
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
    const body = { name, parentId: normalizeGroupParentId(groupForm.parentId) }
    const res = groupEditId.value
      ? await axios.put(`${props.apiBase}/api/c/dashboard-groups/${groupEditId.value}`, body)
      : await axios.post(`${props.apiBase}/api/c/dashboard-groups`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('分组已保存')
    groupVisible.value = false
    await loadTreeData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    groupSaving.value = false
  }
}

async function removeGroup(data) {
  try {
    await ElMessageBox.confirm(`确定删除分组「${data.name}」？`, '确认删除', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.delete(`${props.apiBase}/api/c/dashboard-groups/${data.id}`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已删除')
    await refreshAll()
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
      groupId: normalizeTreeId(boardForm.groupId),
      layoutJson: '{}',
      isPublic: false
    }
    const res = await axios.post(`${props.apiBase}/api/c/dashboards`, body)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('看板已创建')
    boardVisible.value = false
    await refreshAll()
    gridEditorRow.value = res.data.data || {}
    gridEditorVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    boardSaving.value = false
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
    const body = {
      name,
      description: String(form.description || '').trim() || null,
      groupId: normalizeTreeId(form.groupId),
      isPublic: false
    }
    const res = await axios.put(`${props.apiBase}/api/c/dashboards/${editId.value}`, body)
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
  try {
    await ElMessageBox.confirm(`确定删除看板「${row.name}」？`, '确认删除', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.delete(`${props.apiBase}/api/c/dashboards/${row.id}`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已删除')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function onGridSaved() {
  await refreshAll()
}

async function reload() {
  await refreshAll()
}

defineExpose({ reload, rows })

onMounted(() => {
  refreshAll()
})
</script>
