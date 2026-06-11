<template>
  <section class="adm-dashboard">
    <div class="adm-body">
      <aside class="adm-sidebar">
        <div class="adm-nav-toolbar">
          <el-dropdown trigger="click" popper-class="adm-create-dropdown" @command="onCreateCommand">
            <button type="button" class="adm-nav-add" title="新建">
              <el-icon class="adm-nav-add-plus" :size="14"><Plus /></el-icon>
              <span class="adm-nav-add-caret" aria-hidden="true" />
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
          <el-input
            v-model="treeSearch"
            class="adm-nav-search"
            clearable
            placeholder="搜索"
            :prefix-icon="Search"
          />
        </div>

        <div class="adm-tree-scroll">
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
              <el-icon v-else-if="data.nodeKey === 'public'" class="adm-tree-type-icon is-public" :size="15"><OfficeBuilding /></el-icon>
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
                      <el-dropdown-item v-if="!isPublicVirtualNode(data)" command="rename" divided>
                        <span class="adm-dropdown-item"><el-icon><EditPen /></el-icon>重命名</span>
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="data.kind === 'group' && hasGroupMoveTargets(data, moveGroupOptions, groupTree, GROUP_ROOT_PARENT_ID)"
                        class="adm-move-item"
                        @click.stop
                      >
                        <el-dropdown
                          trigger="hover"
                          placement="right-start"
                          :teleported="true"
                          @command="(targetParentId) => moveGroupToParent(data, targetParentId)"
                        >
                          <span class="adm-move-trigger">
                            <el-icon><Rank /></el-icon>
                            <span>移动到</span>
                            <el-icon class="adm-move-caret"><ArrowRight /></el-icon>
                          </span>
                          <template #dropdown>
                            <el-dropdown-menu>
                              <el-dropdown-item
                                v-if="canMoveGroupToRoot(data, groupTree, GROUP_ROOT_PARENT_ID)"
                                :command="GROUP_ROOT_PARENT_ID"
                              >
                                <span class="adm-dropdown-item"><el-icon><Folder /></el-icon>根目录</span>
                              </el-dropdown-item>
                              <el-dropdown-item
                                v-for="group in filterGroupMoveTargets(data, moveGroupOptions, groupTree, GROUP_ROOT_PARENT_ID)"
                                :key="`g-${group.id}`"
                                :command="group.id"
                              >
                                <span class="adm-dropdown-item"><el-icon><Folder /></el-icon>{{ group.name }}</span>
                              </el-dropdown-item>
                            </el-dropdown-menu>
                          </template>
                        </el-dropdown>
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="!isPublicVirtualNode(data)"
                        command="delete"
                        :divided="data.kind === 'group'"
                      >
                        <span class="adm-dropdown-item"><el-icon><Delete /></el-icon>删除</span>
                      </el-dropdown-item>
                    </template>
                    <template v-if="data.kind === 'board'">
                      <el-dropdown-item v-if="canPreviewBoard(data.raw || data)" command="preview">
                        <span class="adm-dropdown-item"><el-icon><View /></el-icon>预览</span>
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="hasBoardMoveTargets(data.raw || data, moveGroupOptions, GROUP_ROOT_PARENT_ID)"
                        class="adm-move-item"
                        :divided="isBoardPublished(data.raw || data)"
                        @click.stop
                      >
                        <el-dropdown
                          trigger="hover"
                          placement="right-start"
                          :teleported="true"
                          @command="(targetGroupId) => moveBoardToGroup(data.raw || data, targetGroupId)"
                        >
                          <span class="adm-move-trigger">
                            <el-icon><Rank /></el-icon>
                            <span>移动到</span>
                            <el-icon class="adm-move-caret"><ArrowRight /></el-icon>
                          </span>
                          <template #dropdown>
                            <el-dropdown-menu>
                              <el-dropdown-item
                                v-if="canMoveBoardToRoot(data.raw || data, GROUP_ROOT_PARENT_ID)"
                                :command="GROUP_ROOT_PARENT_ID"
                              >
                                <span class="adm-dropdown-item"><el-icon><Folder /></el-icon>根目录</span>
                              </el-dropdown-item>
                              <el-dropdown-item
                                v-for="group in filterBoardMoveTargets(data.raw || data, moveGroupOptions, GROUP_ROOT_PARENT_ID)"
                                :key="group.id"
                                :command="group.id"
                              >
                                <span class="adm-dropdown-item"><el-icon><Folder /></el-icon>{{ group.name }}</span>
                              </el-dropdown-item>
                            </el-dropdown-menu>
                          </template>
                        </el-dropdown>
                      </el-dropdown-item>
                      <el-dropdown-item
                        command="rename"
                        :divided="isBoardPublished(data.raw || data) || hasBoardMoveTargets(data.raw || data, moveGroupOptions, GROUP_ROOT_PARENT_ID)"
                      >
                        <span class="adm-dropdown-item"><el-icon><EditPen /></el-icon>重命名</span>
                      </el-dropdown-item>
                    </template>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
          </el-tree>
          <el-empty v-if="!navTree.length && !loadingTree" :image-size="56" description="暂无分组或看板" />
        </div>
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
          <template v-if="!isPublicListView">
            <el-select v-model="filters.isPublic" class="adm-filter-select" @change="onSearch">
              <el-option label="开放类型：全部" value="ALL" />
              <el-option label="开放类型：公共" value="1" />
              <el-option label="开放类型：私密" value="0" />
            </el-select>
            <el-select v-model="filters.status" class="adm-filter-select" @change="onSearch">
              <el-option label="发布状态：全部" value="ALL" />
              <el-option label="发布状态：已发布" value="ACTIVE" />
              <el-option label="发布状态：待发布" value="DISABLED" />
            </el-select>
          </template>
        </div>

        <div v-if="activeListScopeLabel" class="adm-list-scope-hint">
          <span>列表范围：{{ activeListScopeLabel }}</span>
          <el-button link type="primary" @click="clearListScopeFilter">显示全部看板</el-button>
        </div>

        <el-table
          :data="rows"
          border
          size="small"
          v-loading="loadingList"
          empty-text="暂无看板"
          class="adm-table"
        >
          <el-table-column
            prop="name"
            label="看板名称"
            :min-width="isPublicListView ? 140 : 88"
            show-overflow-tooltip
          />
          <el-table-column
            label="作者"
            :width="isPublicListView ? 96 : undefined"
            :min-width="isPublicListView ? undefined : 64"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ authorDisplay(row) }}</template>
          </el-table-column>
          <el-table-column
            v-if="!isPublicListView"
            label="另存人"
            min-width="64"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ saveAsDisplay(row) }}</template>
          </el-table-column>
          <el-table-column
            label="发布者"
            :width="isPublicListView ? 96 : undefined"
            :min-width="isPublicListView ? undefined : 64"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ publisherDisplay(row) }}</template>
          </el-table-column>
          <el-table-column v-if="!isPublicListView" label="开放类型" width="72" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small">
                {{ boardVisibilityLabel(row.isPublic) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!isPublicListView" label="图表数" width="60" align="center">
            <template #default="{ row }">
              <button
                v-if="(row.chartCardCount || 0) > 0"
                type="button"
                class="adm-chart-count-btn"
                :title="`查看 ${row.chartCardCount} 张图表`"
                @click="emit('view-charts', row)"
              >
                <el-tag size="small" type="primary" effect="plain" class="adm-chart-count-tag">
                  {{ row.chartCardCount }}
                </el-tag>
              </button>
              <el-tag v-else size="small" type="info">0</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!isPublicListView" label="组件数" width="60" align="center">
            <template #default="{ row }">
              <button
                v-if="(row.basicWidgetCount || 0) > 0"
                type="button"
                class="adm-chart-count-btn"
                :title="`查看 ${row.basicWidgetCount} 个基础组件`"
                @click="emit('view-widgets', row)"
              >
                <el-tag size="small" type="warning" effect="plain" class="adm-chart-count-tag">
                  {{ row.basicWidgetCount }}
                </el-tag>
              </button>
              <el-tag v-else size="small" type="info">0</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="访问量" :width="isPublicListView ? 80 : 64" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="info" effect="plain">{{ row.viewCount ?? 0 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!isPublicListView" label="发布状态" width="76" align="center">
            <template #default="{ row }">
              <el-tag :type="boardStatusTagType(row)" size="small" effect="light">
                {{ boardStatusTag(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="!isPublicListView" label="更新时间" width="128" show-overflow-tooltip>
            <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" :width="isPublicListView ? 168 : 148" align="center" fixed="right">
            <template #default="{ row }">
              <div class="adm-row-actions">
                <el-button
                  link
                  type="primary"
                  :disabled="!canPreviewBoard(row)"
                  @click="emit('preview', row)"
                >
                  预览
                </el-button>
                <el-button
                  v-if="canDesignBoard(row)"
                  link
                  type="primary"
                  @click="openGridEditor(row)"
                >
                  设计
                </el-button>
                <el-dropdown
                  v-if="canShowBoardMoreActions(row)"
                  trigger="click"
                  :teleported="true"
                  popper-class="adm-dashboard-more-popper"
                  @command="(cmd) => onTableRowCommand(cmd, row)"
                >
                  <el-button link type="primary">更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit">
                        <span class="adm-dropdown-item"><el-icon><Edit /></el-icon>编辑</span>
                      </el-dropdown-item>
                      <el-dropdown-item command="rename">
                        <span class="adm-dropdown-item"><el-icon><EditPen /></el-icon>重命名</span>
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isPublic" command="set-public" divided>
                        <span class="adm-dropdown-item"><el-icon><OfficeBuilding /></el-icon>设为公开</span>
                      </el-dropdown-item>
                      <el-dropdown-item v-else command="set-private" divided>
                        <span class="adm-dropdown-item"><el-icon><User /></el-icon>设为私密</span>
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!isBoardPublished(row)" command="publish">
                        <span class="adm-dropdown-item"><el-icon><Promotion /></el-icon>发布</span>
                      </el-dropdown-item>
                      <el-dropdown-item v-else command="unpublish">
                        <span class="adm-dropdown-item"><el-icon><CircleClose /></el-icon>取消发布</span>
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="isBoardPublished(row)"
                        command="share"
                      >
                        <span class="adm-dropdown-item"><el-icon><Share /></el-icon>分享</span>
                      </el-dropdown-item>
                      <el-dropdown-item class="adm-move-item" @click.stop>
                        <el-dropdown trigger="hover" placement="right-start" :teleported="true">
                          <span class="adm-move-trigger">
                            <el-icon><Download /></el-icon>
                            <span>导出</span>
                            <el-icon class="adm-move-caret"><ArrowRight /></el-icon>
                          </span>
                          <template #dropdown>
                            <el-dropdown-menu>
                              <el-dropdown-item @click="triggerTableExport(row, 'png')">
                                <span class="adm-dropdown-item">导出 PNG</span>
                              </el-dropdown-item>
                              <el-dropdown-item @click="triggerTableExport(row, 'pdf')">
                                <span class="adm-dropdown-item">导出 PDF</span>
                              </el-dropdown-item>
                            </el-dropdown-menu>
                          </template>
                        </el-dropdown>
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" divided>
                        <span class="adm-dropdown-item adm-dropdown-item--danger"><el-icon><Delete /></el-icon>删除</span>
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
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

    <DashboardGridEditor
      v-model="gridEditorVisible"
      :initial-row="gridEditorRow"
      :api-base="apiBase"
      :auto-export="gridEditorAutoExport"
      :group-select-tree="groupSelectTree"
      use-parent-group-tree-for-save-as
      @saved="onGridSaved"
      @auto-export-done="gridEditorAutoExport = ''"
    />

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

    <el-dialog
      v-model="renameVisible"
      width="480px"
      class="adm-form-dialog"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template #header>
        <div class="adm-form-dialog-head">
          <el-icon class="adm-form-dialog-icon"><EditPen /></el-icon>
          <span>重命名</span>
        </div>
      </template>
      <p class="adm-rename-current">当前名称：{{ renameOriginalName }}</p>
      <el-input
        v-model="renameForm.name"
        placeholder="请输入新名称"
        maxlength="128"
        show-word-limit
        @keyup.enter="saveRename"
      />
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="renameSaving" @click="saveRename">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="editVisible"
      width="520px"
      class="adm-form-dialog"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template #header>
        <div class="adm-form-dialog-head">
          <el-icon class="adm-form-dialog-icon is-board"><Odometer /></el-icon>
          <span>编辑仪表盘</span>
        </div>
      </template>
      <el-form label-position="top" class="adm-edit-board-form">
        <el-form-item label="仪表盘名称" required>
          <el-input v-model="form.name" placeholder="请输入仪表盘名称" maxlength="120" show-word-limit />
        </el-form-item>
        <el-form-item label="所属分组" required>
          <el-tree-select
            v-model="form.groupId"
            :data="groupSelectTreeWithRoot"
            :props="treeSelectProps"
            check-strictly
            placeholder="请选择分组"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注信息" required>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            placeholder="请输入备注信息"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">确认</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight,
  CircleClose,
  Delete,
  Download,
  Edit,
  EditPen,
  Folder,
  More,
  OfficeBuilding,
  Odometer,
  Plus,
  Promotion,
  Rank,
  Search,
  Share,
  User,
  View
} from '@element-plus/icons-vue'
import { restoreSessionHeader } from '../../store/session'
import DashboardGridEditor from '../../views/user/DashboardGridEditor.vue'
import { countChartSlotsForDashboardRow, countBasicWidgetSlotsForDashboardRow } from '../../utils/dashboardGrid.js'
import {
  boardStatusTag,
  boardStatusTagType,
  boardVisibilityLabel,
  boardsForPersonalNavTree,
  buildNavTree,
  canDesignBoard,
  canDirectEditBoard,
  canMoveBoardToRoot,
  canMoveGroupToRoot,
  canPreviewBoard,
  canShowBoardMoreActions,
  authorDisplay,
  isBoardOwner,
  isPublicSaveAsDesign,
  publisherDisplay,
  saveAsDisplay,
  decorateGroupNodesOnly,
  filterBoardMoveTargets,
  filterGroupMoveTargets,
  flattenGroupOptions,
  hasBoardMoveTargets,
  hasGroupMoveTargets,
  isBoardInGroup,
  isBoardPublished,
  isGroupMoveTargetDisabled,
  normalizeTreeId
} from '../../utils/dashboardManageTree.js'
import '../../styles/dashboard-manage.css'

const props = defineProps({
  apiBase: { type: String, default: 'http://localhost:8080' }
})

const emit = defineEmits(['preview', 'view-charts', 'view-widgets', 'share'])

const GROUP_ROOT_PARENT_ID = 0

const rows = ref([])
const allBoards = ref([])
const publicBoards = ref([])
const groupTree = ref([])
const loadingList = ref(false)
const loadingTree = ref(false)
const groupTreeRef = ref(null)
const treeSearch = ref('')

const treeProps = { label: 'name', children: 'children' }
const treeSelectProps = { label: 'name', value: 'id', children: 'children' }

const filters = reactive({ keyword: '', isPublic: 'ALL', status: 'ALL' })
const isPublicListView = computed(() => groupFilter.value.kind === 'public')
const groupFilter = ref({ kind: 'all' })
const selectedTreeContext = ref({ kind: 'all', groupId: null })
const pagination = reactive({ page: 1, pageSize: 20, total: 0 })

const activeListScopeLabel = computed(() => {
  if (groupFilter.value.kind === 'group' && groupFilter.value.id) {
    const group = findGroupInTree(groupTree.value, groupFilter.value.id)
    return group?.name ? `分组「${group.name}」` : '当前分组'
  }
  if (groupFilter.value.kind === 'public') return '公共看板目录'
  return ''
})

function findGroupInTree(nodes, groupId) {
  const target = Number(groupId)
  for (const node of Array.isArray(nodes) ? nodes : []) {
    if (Number(node.id) === target) return node
    const child = findGroupInTree(node.children, groupId)
    if (child) return child
  }
  return null
}

function clearListScopeFilter() {
  groupFilter.value = { kind: 'all' }
  selectedTreeContext.value = { kind: 'all', groupId: null }
  filters.keyword = ''
  groupTreeRef.value?.setCurrentKey?.(null)
  pagination.page = 1
  loadList()
}

const gridEditorVisible = ref(false)
const gridEditorRow = ref(null)
const gridEditorAutoExport = ref('')

const groupVisible = ref(false)
const groupSaving = ref(false)
const groupEditId = ref(null)
const groupForm = reactive({ name: '', parentId: GROUP_ROOT_PARENT_ID })

const boardVisible = ref(false)
const boardSaving = ref(false)
const boardForm = reactive({ name: '', description: '', groupId: null })

const renameVisible = ref(false)
const renameSaving = ref(false)
const renameTarget = ref(null)
const renameOriginalName = ref('')
const renameForm = reactive({ name: '' })

const editVisible = ref(false)
const saving = ref(false)
const editId = ref(null)
const editRow = ref(null)
const form = reactive({ name: '', description: '', groupId: GROUP_ROOT_PARENT_ID })

const navTree = computed(() =>
  buildNavTree(
    groupTree.value,
    boardsForPersonalNavTree(allBoards.value, publicBoards.value),
    treeSearch.value,
    {
      includePublicGroup: true,
      includeUnassignedGroup: false,
      publicBoards: publicBoards.value
    }
  )
)
const groupSelectTree = computed(() => decorateGroupNodesOnly(groupTree.value))
const groupSelectTreeWithRoot = computed(() => [
  { id: GROUP_ROOT_PARENT_ID, name: '根目录', children: groupSelectTree.value }
])
const moveGroupOptions = computed(() => flattenGroupOptions(groupTree.value))
const groupParentSelectTree = computed(() => [
  { id: GROUP_ROOT_PARENT_ID, name: '根目录', children: groupSelectTree.value }
])

function normalizeBoardGroupId(value) {
  if (value == null || value === '' || Number(value) === GROUP_ROOT_PARENT_ID) return null
  return normalizeTreeId(value)
}

function buildBoardUpdateBody(row, patch = {}) {
  return {
    name: row.name,
    description: row.description || null,
    groupId: normalizeBoardGroupId(row.groupId),
    isPublic: Boolean(row.isPublic),
    status: String(row.status || 'DISABLED').toUpperCase(),
    ...patch
  }
}

async function setBoardPublishStatus(row, status) {
  if (!row?.id) return
  restoreSessionHeader()
  try {
    const res = await axios.put(`${props.apiBase}/api/c/dashboards/${row.id}`, buildBoardUpdateBody(row, { status }))
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success(status === 'ACTIVE' ? '看板已发布' : '已取消发布')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function publishBoard(row) {
  return setBoardPublishStatus(row, 'ACTIVE')
}

function unpublishBoard(row) {
  return setBoardPublishStatus(row, 'DISABLED')
}

async function setBoardVisibility(row, isPublic) {
  if (!row?.id) return
  restoreSessionHeader()
  try {
    const res = await axios.put(
      `${props.apiBase}/api/c/dashboards/${row.id}`,
      buildBoardUpdateBody(row, { isPublic: Boolean(isPublic) })
    )
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success(isPublic ? '已设为公开' : '已设为私密')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function normalizeGroupParentId(value) {
  if (value == null || value === '' || Number(value) === GROUP_ROOT_PARENT_ID) return null
  return normalizeTreeId(value)
}

async function moveGroupToParent(group, targetParentId) {
  if (isGroupMoveTargetDisabled(group, targetParentId, groupTree.value, GROUP_ROOT_PARENT_ID)) return
  restoreSessionHeader()
  try {
    const res = await axios.put(`${props.apiBase}/api/c/dashboard-groups/${group.id}`, {
      name: group.name,
      parentId: normalizeGroupParentId(targetParentId)
    })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('分组已移动')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '移动失败')
  }
}

async function moveBoardToGroup(row, targetGroupId) {
  if (!row?.id || isBoardInGroup(row, targetGroupId)) return
  restoreSessionHeader()
  try {
    const res = await axios.put(`${props.apiBase}/api/c/dashboards/${row.id}`, buildBoardUpdateBody(row, {
      groupId: normalizeBoardGroupId(targetGroupId)
    }))
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('看板已移动')
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '移动失败')
  }
}

function showTreeNodeMenu(data) {
  if (data.kind === 'board') {
    return isBoardOwner(data.raw || data)
  }
  return data.kind === 'group' || data.kind === 'virtual'
}

function isPublicVirtualNode(data) {
  return data?.kind === 'virtual' && data?.nodeKey === 'public'
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
  const params = { page: pagination.page, pageSize: pagination.pageSize }
  const kw = String(filters.keyword || '').trim()
  if (kw) params.keyword = kw
  if (groupFilter.value.kind === 'group' && groupFilter.value.id) params.groupId = groupFilter.value.id
  else if (groupFilter.value.kind === 'public') params.groupId = -2
  if (groupFilter.value.kind !== 'public') {
    if (filters.isPublic !== 'ALL') params.isPublic = Number(filters.isPublic)
    if (filters.status !== 'ALL') params.status = filters.status
  }
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

async function loadPublicBoardsForTree() {
  restoreSessionHeader()
  try {
    const res = await axios.get(`${props.apiBase}/api/c/dashboards/mine`, { params: { page: 1, pageSize: 500, groupId: -2 } })
    if (res.data.code !== 200) throw new Error(res.data.message)
    publicBoards.value = Array.isArray(res.data.data?.items) ? res.data.data.items : []
  } catch {
    publicBoards.value = []
  }
}

async function loadTreeData() {
  loadingTree.value = true
  try {
    await Promise.all([loadGroupTree(), loadAllBoardsForTree(), loadPublicBoardsForTree()])
  } finally {
    loadingTree.value = false
  }
}

async function loadList() {
  loadingList.value = true
  restoreSessionHeader()
  try {
    if (groupFilter.value.kind === 'all') {
      await loadAllScopeList()
      return
    }
    const res = await axios.get(`${props.apiBase}/api/c/dashboards/mine`, { params: buildListParams() })
    if (res.data.code !== 200) throw new Error(res.data.message)
    const data = res.data.data || {}
    rows.value = decorateDashboardRows(Array.isArray(data.items) ? data.items : [])
    pagination.total = Number(data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingList.value = false
  }
}

function decorateDashboardRows(items) {
  return (Array.isArray(items) ? items : []).map((row) => ({
    ...row,
    chartCardCount: countChartSlotsForDashboardRow(row?.layoutJson),
    basicWidgetCount: countBasicWidgetSlotsForDashboardRow(row?.layoutJson)
  }))
}

function applyClientListFilters(items) {
  let result = Array.isArray(items) ? [...items] : []
  const kw = String(filters.keyword || '').trim().toLowerCase()
  if (kw) {
    result = result.filter((row) => String(row?.name || '').toLowerCase().includes(kw))
  }
  if (filters.isPublic !== 'ALL') {
    const target = Number(filters.isPublic)
    result = result.filter((row) => Number(row?.isPublic) === target)
  }
  if (filters.status !== 'ALL') {
    const target = String(filters.status || '').toUpperCase()
    result = result.filter((row) => String(row?.status || '').toUpperCase() === target)
  }
  result.sort((a, b) => {
    const ta = new Date(a?.updatedAt || 0).getTime()
    const tb = new Date(b?.updatedAt || 0).getTime()
    return tb - ta
  })
  return result
}

async function loadAllScopeList() {
  const kw = String(filters.keyword || '').trim()
  const ownedParams = { page: 1, pageSize: 500 }
  if (kw) ownedParams.keyword = kw
  const publicParams = { page: 1, pageSize: 500, groupId: -2 }
  if (kw) publicParams.keyword = kw

  const [ownedRes, publicRes] = await Promise.all([
    axios.get(`${props.apiBase}/api/c/dashboards/mine`, { params: ownedParams }),
    axios.get(`${props.apiBase}/api/c/dashboards/mine`, { params: publicParams })
  ])
  if (ownedRes.data.code !== 200) throw new Error(ownedRes.data.message)
  if (publicRes.data.code !== 200) throw new Error(publicRes.data.message)

  const byId = new Map()
  for (const row of [
    ...(Array.isArray(ownedRes.data.data?.items) ? ownedRes.data.data.items : []),
    ...(Array.isArray(publicRes.data.data?.items) ? publicRes.data.data.items : [])
  ]) {
    if (row?.id != null) byId.set(Number(row.id), row)
  }

  const filtered = applyClientListFilters([...byId.values()])
  pagination.total = filtered.length
  const start = (pagination.page - 1) * pagination.pageSize
  rows.value = decorateDashboardRows(filtered.slice(start, start + pagination.pageSize))
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
    const row = data.raw || data
    if (canPreviewBoard(row)) {
      emit('preview', row)
      return
    }
    selectedTreeContext.value = { kind: 'board', groupId: normalizeTreeId(data.groupId) }
    groupFilter.value = { kind: 'all' }
    filters.keyword = data.name || ''
    pagination.page = 1
    loadList()
    return
  }
  if (data.kind === 'virtual') {
    selectedTreeContext.value = { kind: data.nodeKey, groupId: null }
    if (data.nodeKey === 'public') {
      groupFilter.value = { kind: 'public' }
      filters.keyword = ''
    } else {
      groupFilter.value = { kind: 'all' }
    }
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

function onTableRowCommand(command, row) {
  if (command === 'edit') openEdit(row)
  else if (command === 'rename') openRenameBoard(row)
  else if (command === 'publish') publishBoard(row)
  else if (command === 'unpublish') unpublishBoard(row)
  else if (command === 'set-public') setBoardVisibility(row, true)
  else if (command === 'set-private') setBoardVisibility(row, false)
  else if (command === 'share') {
    if (!isBoardPublished(row)) {
      ElMessage.warning('待发布看板无法分享，请先发布')
      return
    }
    emit('share', row)
  }
  else if (command === 'delete') remove(row)
}

function onTreeNodeCommand(command, data) {
  syncTreeContextFromNode(data)
  if (data.kind === 'board') {
    const row = data.raw || data
    if (command === 'preview') emit('preview', row)
    else if (command === 'rename') openRenameBoard(row)
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
    if (data.nodeKey === 'public') {
      if (command === 'rename') ElMessage.info('「公共看板」为系统分类，无法重命名')
      else if (command === 'delete') ElMessage.info('「公共看板」为系统分类，无法删除')
      else if (command === 'new-group' || command === 'new-board') ElMessage.info('请在个人分组下新建看板；设为「公共」后会自动出现在此')
    }
    return
  }
  if (command === 'rename') openRenameGroup(data)
  else if (command === 'delete') removeGroup(data)
}

function openNewBoard(explicitGroupId = undefined) {
  boardForm.name = ''
  boardForm.description = ''
  boardForm.groupId = resolveCreateParentGroupId(explicitGroupId)
  boardVisible.value = true
}

function openGridEditor(row) {
  if (!canDesignBoard(row)) {
    ElMessage.warning(row?.isPublic ? '未发布的公共看板仅可预览，请先发布后再设计' : '当前看板不可设计')
    return
  }
  if (isPublicSaveAsDesign(row) && !canDirectEditBoard(row)) {
    ElMessage.info('他人已发布公共看板须先「另存为」副本后再编辑')
  }
  gridEditorAutoExport.value = ''
  gridEditorRow.value = row ? { ...row } : null
  gridEditorVisible.value = true
}

function triggerTableExport(row, format) {
  if (!row?.id) {
    ElMessage.warning('看板信息不完整，无法导出')
    return
  }
  gridEditorAutoExport.value = format === 'pdf' ? 'pdf' : 'png'
  gridEditorRow.value = { ...row }
  gridEditorVisible.value = true
}

function openRenameBoard(row) {
  renameTarget.value = { kind: 'board', row: { ...row } }
  renameOriginalName.value = row.name || ''
  renameForm.name = row.name || ''
  renameVisible.value = true
}

function openRenameGroup(data) {
  renameTarget.value = {
    kind: 'group',
    id: data.id,
    parentId: data.parentId ? data.parentId : null
  }
  renameOriginalName.value = data.name || ''
  renameForm.name = data.name || ''
  renameVisible.value = true
}

function openEdit(row) {
  editRow.value = { ...row }
  editId.value = row.id
  form.name = row.name || ''
  form.description = row.description || ''
  form.groupId = row.groupId ? row.groupId : GROUP_ROOT_PARENT_ID
  editVisible.value = true
}

async function saveRename() {
  const name = String(renameForm.name || '').trim()
  if (!name) {
    ElMessage.warning('请输入新名称')
    return
  }
  const target = renameTarget.value
  if (!target) return
  renameSaving.value = true
  restoreSessionHeader()
  try {
    if (target.kind === 'board') {
      const row = target.row || {}
      const res = await axios.put(`${props.apiBase}/api/c/dashboards/${row.id}`, buildBoardUpdateBody(row, { name }))
      if (res.data.code !== 200) throw new Error(res.data.message)
    } else if (target.kind === 'group') {
      const res = await axios.put(`${props.apiBase}/api/c/dashboard-groups/${target.id}`, {
        name,
        parentId: target.parentId
      })
      if (res.data.code !== 200) throw new Error(res.data.message)
    }
    ElMessage.success('重命名成功')
    renameVisible.value = false
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '重命名失败')
  } finally {
    renameSaving.value = false
  }
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
      isPublic: false,
      status: 'DISABLED'
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
  const description = String(form.description || '').trim()
  if (!name) {
    ElMessage.warning('请填写仪表盘名称')
    return
  }
  if (!description) {
    ElMessage.warning('请填写备注信息')
    return
  }
  saving.value = true
  restoreSessionHeader()
  try {
    const body = {
      name,
      description,
      groupId: normalizeBoardGroupId(form.groupId),
      isPublic: Boolean(editRow.value?.isPublic),
      status: String(editRow.value?.status || 'DISABLED').toUpperCase()
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
