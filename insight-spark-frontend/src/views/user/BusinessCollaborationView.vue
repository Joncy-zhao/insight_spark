<template>
  <section class="biz-collab-page">
    <header class="collab-head">
      <div>
        <h1>业务批注与协同</h1>
        <p>团队协作看板批注、评论与分发授权。</p>
      </div>
      <div class="head-actions">
        <el-tag v-if="activeTab === 'workbench' && wsConnected" type="success" effect="plain" size="small">实时同步已连接</el-tag>
        <el-tag v-else-if="activeTab === 'workbench'" type="info" effect="plain" size="small">实时同步未连接</el-tag>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="collab-tabs" @tab-change="onTabChange">
      <!-- Tab 1: 协作工作台 -->
      <el-tab-pane label="协作工作台" name="workbench">
        <el-card shadow="never" class="select-card">
          <div class="select-row">
            <el-select
              v-model="selectedTeamId"
              placeholder="选择团队"
              filterable
              clearable
              class="team-select"
              :loading="loadingTeams"
              @change="onWorkbenchTeamChange"
            >
              <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <el-select
              v-model="selectedId"
              placeholder="选择看板"
              filterable
              clearable
              class="board-select board-select--workbench"
              :disabled="!selectedTeamId"
              :loading="loadingWorkbenchDashboards"
              @change="onDashboardChange"
            >
              <el-option
                v-for="row in workbenchDashboards"
                :key="row.id"
                :label="row.name"
                :value="row.id"
              />
            </el-select>
            <div v-if="selected" class="permission-scope-field">
              <span class="permission-scope-label">权限范围</span>
              <div class="permission-scope-tags">
                <el-tag v-if="selectedHasReadScope" size="small" type="info" effect="plain">阅览+批注</el-tag>
                <el-tag v-if="selectedHasEditScope" size="small" type="warning" effect="plain">编辑</el-tag>
                <span v-if="!selectedHasReadScope && !selectedHasEditScope" class="muted">—</span>
              </div>
            </div>
            <el-button :icon="Refresh" @click="refreshWorkbench">刷新</el-button>
            <el-button v-if="selected" :type="summary.following ? 'warning' : 'default'" :icon="Star" @click="toggleFollow">
              {{ summary.following ? '已关注' : '关注看板' }}
            </el-button>
            <el-button v-if="selected" type="primary" :icon="View" @click="boardPreviewVisible = true">全屏预览</el-button>
            <el-button v-if="selected" :icon="Download" @click="exportReport">导出 Markdown 汇报</el-button>
          </div>
          <div v-if="selectedTeam" class="meta-row">
            <span>团队 {{ selectedTeam.name }}</span>
            <span>{{ workbenchDashboards.length }} 个已分发看板</span>
          </div>
          <div v-if="selected" class="meta-row">
            <el-tag size="small">{{ selected.isPublic ? '公共' : '私密' }}</el-tag>
            <span>所有者 {{ selected.ownerUserId }}</span>
            <span>{{ summary.annotationCount || 0 }} 条批注</span>
            <span>{{ summary.commentCount || 0 }} 条评论</span>
          </div>
          <div v-if="selectedTeamId" class="workbench-grant-row">
            <span class="grant-label">追加分发</span>
            <el-select
              v-model="workbenchGrantDashboardId"
              filterable
              clearable
              placeholder="选择看板"
              class="board-select grant-board-select"
            >
              <el-option v-for="d in workbenchGrantCandidates" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
            <el-select v-model="workbenchGrantForm.permissionType" style="width: 120px">
              <el-option label="阅览+批注 READ" value="READ" />
              <el-option label="编辑 EDIT" value="EDIT" />
            </el-select>
            <el-button type="primary" plain @click="grantToWorkbenchTeam">分发到当前团队</el-button>
          </div>
        </el-card>

        <div v-if="selected" class="collab-layout">
          <section class="collab-canvas-col">
            <DashboardBoardViewer
              embedded
              :initial-row="selected"
              :api-base="API_BASE"
              :show-embed-lead="false"
              selectable
              :active-item-id="activeCanvasItemId"
              :item-badges="canvasItemBadges"
              :board-annotation-count="boardLevelAnnotationCount"
              @select-item="onCanvasSelectItem"
            />
            <div v-if="contextRecentAnnotations.length" class="collab-recent-strip">
              <span class="collab-recent-label">{{ activeNode ? `${activeNodeLabel} · 批注` : '经验沉淀' }}</span>
              <button
                v-for="ann in contextRecentAnnotations"
                :key="ann.id"
                type="button"
                :class="['collab-recent-card', { active: isAnnOnActiveNode(ann) }]"
                :style="stickyPreviewStyle(ann.tag)"
                @click="focusAnnotation(ann)"
              >
                <span class="collab-recent-tag">{{ annTagPreset(ann.tag).emoji }} {{ ann.tag || '批注' }}</span>
                <span class="collab-recent-text">{{ ann.content }}</span>
              </button>
            </div>
            <div v-if="nodes.length > 1" class="collab-node-chips">
              <button
                v-for="node in nodes"
                :key="nodeKey(node)"
                type="button"
                :class="['node-chip', { active: isNodeActive(node) }]"
                @click="selectNode(node)"
                :title="node.label"
              >
                {{ node.label }}
                <span v-if="nodeAnnotationCount(node)" class="node-chip-badge">{{ nodeAnnotationCount(node) }}</span>
              </button>
            </div>
          </section>
          <aside class="collab-side">
            <el-card shadow="never" class="panel-card side-panel side-panel--collab">
              <CollabWorkbenchPanel
                :nodes="nodes"
                :active-node-key="activeNodeKey"
                :active-node-label="activeNodeLabel"
                :dashboard-id="selected?.id"
                :annotations="annotations"
                :filtered-annotations="filteredAnnotations"
                :comments="comments"
                :ann-form="annForm"
                :com-form="comForm"
                :ann-submitting="annSubmitting"
                :com-submitting="comSubmitting"
                :can-compose="canCompose"
                :mention-visible="mentionVisible"
                :mention-candidates="mentionCandidates"
                :node-key-fn="nodeKey"
                :render-mentions="renderMentions"
                :format-time="formatTime"
                @select-node="selectNode"
                @focus-annotation="focusAnnotation"
                @delete-annotation="removeAnnotation"
                @submit-annotation="submitAnnotation"
                @update:ann-content="(v) => (annForm.content = v)"
                @delete-comment="removeComment"
                @submit-comment="submitComment"
                @update:com-content="(v) => (comForm.content = v)"
                @comment-input="onCommentInput"
                @insert-mention="insertMention"
                @apply-template="applyAnnotationTemplate"
              />
            </el-card>
          </aside>
        </div>
        <el-empty v-else-if="!loadingTeams && !teams.length" description="暂无团队，请先到「团队与分发」创建团队并分发看板" />
        <el-empty v-else-if="!selectedTeamId" description="请先选择团队" />
        <el-empty v-else-if="!loadingWorkbenchDashboards && !workbenchDashboards.length" description="该团队暂无已分发看板，可在上方追加分发" />
        <el-empty v-else-if="!selectedId" description="请选择看板开始协作批注" />
      </el-tab-pane>

      <!-- Tab 2: 团队与分发 -->
      <el-tab-pane label="团队与分发" name="distribute">
        <div class="distribute-layout">
          <el-card shadow="never" class="team-list-card">
            <template #header>
              <div class="card-head-row">
                <span>我的团队</span>
                <el-button type="primary" size="small" :icon="Plus" @click="openCreateTeam">新建</el-button>
              </div>
            </template>
            <el-empty v-if="!teams.length && !loadingTeams" description="暂无团队，请先创建" />
            <button
              v-for="t in teams"
              :key="t.id"
              type="button"
              :class="['team-item', { active: selectedTeamId === t.id }]"
              @click="selectTeam(t.id)"
            >
              <strong>{{ t.name }}</strong>
              <span class="muted">{{ t.memberCount || 0 }} 人 · {{ t.myRole }}</span>
            </button>
          </el-card>

          <el-card v-if="selectedTeam" shadow="never" class="team-detail-card">
            <template #header>
              <div class="card-head-row">
                <span>成员 · {{ selectedTeam.name }}</span>
                <div v-if="canManageSelectedTeam" class="team-actions">
                  <el-button size="small" :icon="EditPen" @click="openEditTeam">编辑团队</el-button>
                  <el-button
                    v-if="canDissolveTeam"
                    size="small"
                    type="danger"
                    plain
                    :icon="Delete"
                    @click="dissolveTeam"
                  >
                    解散团队
                  </el-button>
                </div>
              </div>
            </template>
            <p v-if="selectedTeam.description" class="team-desc">{{ selectedTeam.description }}</p>
            <el-form v-if="canManageSelectedTeam" inline size="small" @submit.prevent="submitAddMember">
              <el-select
                v-model="memberForm.userId"
                filterable
                remote
                clearable
                placeholder="搜索用户加入"
                :remote-method="searchTeamMemberUsers"
                :loading="memberSearchLoading"
                style="min-width: 200px"
              >
                <el-option
                  v-for="u in memberCandidates"
                  :key="u.userId"
                  :label="`${u.nickname || u.username} (${u.userId})`"
                  :value="u.userId"
                />
              </el-select>
              <el-select v-model="memberForm.memberRole" style="width: 110px">
                <el-option label="成员 MEMBER" value="MEMBER" />
                <el-option label="管理员 ADMIN" value="ADMIN" />
              </el-select>
              <el-button type="primary" @click="submitAddMember">添加成员</el-button>
            </el-form>
            <el-table :data="teamMembers" size="small" class="mt12" empty-text="暂无成员">
              <el-table-column label="用户" min-width="120">
                <template #default="{ row }">{{ row.nickname || row.username || row.userId }}</template>
              </el-table-column>
              <el-table-column prop="memberRole" label="角色" width="80" />
              <el-table-column v-if="canManageSelectedTeam" label="" width="70">
                <template #default="{ row }">
                  <el-button v-if="row.memberRole !== 'OWNER'" link type="danger" size="small" @click="removeMember(row)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
          <el-empty v-else description="请选择左侧团队" />

          <el-card shadow="never" class="grant-card">
            <template #header>看板分发</template>
            <el-form label-position="top" size="small">
              <el-form-item label="选择看板（已发布的公共看板，或您创建/另存的私密看板）">
                <el-select v-model="distributeDashboardId" filterable clearable placeholder="选择看板" style="width: 100%" @change="loadDashboardTeams">
                  <el-option
                    v-for="d in distributableDashboards"
                    :key="d.id"
                    :label="d.name"
                    :value="d.id"
                  />
                </el-select>
                <div v-if="selectedDistributeDashboard" class="distribute-meta">
                  <el-tag size="small">{{ boardVisibilityLabel(selectedDistributeDashboard.isPublic) }}</el-tag>
                  <el-tag size="small" type="success">已发布</el-tag>
                  <span>所有者 {{ selectedDistributeDashboard.ownerUserId || '—' }}</span>
                  <span v-if="boardIsPublic(selectedDistributeDashboard)" class="distribute-hint">公共看板，任何用户均可分发给团队</span>
                  <span v-else-if="isBoardOwner(selectedDistributeDashboard) || isBoardSaveAsUser(selectedDistributeDashboard)" class="distribute-hint">您的私密看板，可分发给团队</span>
                </div>
              </el-form-item>
              <el-form-item v-if="distributeDashboardId && selectedTeam" label="授权给当前团队">
                <div class="grant-row">
                  <el-select v-model="grantForm.permissionType" style="width: 120px">
                    <el-option label="阅览+批注 READ" value="READ" />
                    <el-option label="编辑 EDIT" value="EDIT" />
                  </el-select>
                  <el-button type="primary" @click="grantCurrentTeam">授权团队</el-button>
                </div>
              </el-form-item>
            </el-form>
            <el-table :data="dashboardTeams" size="small" empty-text="该看板暂未授权团队">
              <el-table-column prop="teamName" label="团队" min-width="100" />
              <el-table-column prop="permissionType" label="权限" width="70" />
              <el-table-column label="" width="70">
                <template #default="{ row }">
                  <el-button link type="danger" size="small" @click="revokeTeamGrant(row)">撤销</el-button>
                </template>
              </el-table-column>
            </el-table>

            <template v-if="distributeDashboardId && canManageDistributeDashboard">
              <el-divider content-position="left">个人协作授权</el-divider>
              <p class="distribute-hint">直接授权给指定用户，对方将在「我收到的看板」中看到该看板。</p>
              <div class="grant-row">
                <el-select
                  v-model="userGrantForm.userId"
                  filterable
                  remote
                  clearable
                  placeholder="搜索用户"
                  :remote-method="searchUserGrantCandidates"
                  :loading="userGrantSearchLoading"
                  style="min-width: 200px; flex: 1"
                >
                  <el-option
                    v-for="u in userGrantCandidates"
                    :key="u.userId"
                    :label="`${u.nickname || u.username} (${u.userId})`"
                    :value="u.userId"
                  />
                </el-select>
                <el-select v-model="userGrantForm.permissionType" style="width: 120px">
                  <el-option label="阅览+批注 READ" value="READ" />
                  <el-option label="编辑 EDIT" value="EDIT" />
                </el-select>
                <el-button type="primary" plain @click="grantUserPermission">授权用户</el-button>
              </div>
              <el-table :data="userPermissions" size="small" class="mt12" empty-text="暂无个人授权">
                <el-table-column label="用户" min-width="120">
                  <template #default="{ row }">{{ row.nickname || row.username || row.userId }}</template>
                </el-table-column>
                <el-table-column prop="permissionType" label="权限" width="70" />
                <el-table-column prop="source" label="来源" width="72" />
                <el-table-column label="" width="70">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small" @click="revokeUserPermission(row)">撤销</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- Tab 3: 我收到的看板 -->
      <el-tab-pane label="我收到的看板" name="received">
        <el-card shadow="never">
          <div class="select-row mb12">
            <el-button :icon="Refresh" @click="loadReceived">刷新</el-button>
            <span class="muted">团队或个人授权给您的看板，可直接进入协作批注</span>
          </div>
          <el-table v-loading="loadingReceived" :data="receivedRows" empty-text="暂无收到的协作看板">
            <el-table-column prop="name" label="看板名称" min-width="160" />
            <el-table-column prop="ownerUserId" label="所有者" width="110" />
            <el-table-column label="来源" width="100">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.accessSource === 'TEAM' ? '团队' : '个人' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="团队" min-width="100">
              <template #default="{ row }">{{ row.teamName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="permissionType" label="权限" width="70" />
            <el-table-column prop="updatedAt" label="更新时间" width="170" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="enterWorkbench(row)">进入协作</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="teamDialogVisible" :title="teamEditId ? '编辑团队' : '新建团队'" width="440px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="团队名称" required>
          <el-input v-model="teamForm.name" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="说明（可选）">
          <el-input v-model="teamForm.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="teamDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="teamSaving" @click="saveTeam">保存</el-button>
      </template>
    </el-dialog>

    <DashboardBoardViewer v-model="boardPreviewVisible" :initial-row="selected" :api-base="API_BASE" :show-lead="false" />
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Download, EditPen, Plus, Refresh, Star, View } from '@element-plus/icons-vue'
import DashboardBoardViewer from '../../components/dashboard/DashboardBoardViewer.vue'
import CollabWorkbenchPanel from '../../components/collab/CollabWorkbenchPanel.vue'
import { currentRole, currentUser } from '../../store/session'
import {
  annotationStickyStyle,
  annotationTagPreset,
  buildCanvasItemBadges,
  countAnnotationsForNode,
  isBoardLevelAnnotation
} from '../../utils/collabAnnotation.js'
import { useCollabWebSocket } from '../../composables/useCollabWebSocket'
import { consumeCollabNav } from '../../utils/collabNav.js'
import { canDistributeBoard, boardIsPublic, isBoardOwner, isBoardSaveAsUser, boardVisibilityLabel } from '../../utils/dashboardManageTree.js'
import {
  addTeamMember,
  createAnnotation,
  createComment,
  createTeam,
  deleteAnnotation,
  deleteComment,
  deleteTeam,
  downloadCollabReport,
  fetchAnnotationsByDashboard,
  fetchCollabDashboards,
  fetchCollabSummary,
  fetchComments,
  fetchDashboardTeams,
  fetchMentionCandidates,
  fetchMyTeams,
  fetchReceivedDashboards,
  fetchTeamDashboards,
  fetchTeamMemberCandidates,
  fetchTeamMembers,
  fetchTeamPermissions,
  followDashboard,
  grantDashboardToTeam,
  grantTeamPermission,
  removeTeamMember,
  revokeDashboardTeamGrant,
  revokeTeamPermission,
  unfollowDashboard,
  updateTeam
} from '../../api/collab'

const API_BASE = 'http://localhost:8080'

const activeTab = ref('workbench')
const rows = ref([])
const loadingList = ref(false)
const workbenchDashboards = ref([])
const loadingWorkbenchDashboards = ref(false)
const selectedId = ref(null)
const summary = reactive({ following: false, annotationCount: 0, commentCount: 0, nodes: [], canManageTeam: false })
const nodes = ref([])
const annotations = ref([])
const comments = ref([])
const activeNode = ref(null)
const activeNodeKey = ref('')
const boardPreviewVisible = ref(false)

const teams = ref([])
const loadingTeams = ref(false)
const selectedTeamId = ref(null)
const teamMembers = ref([])
const distributeDashboardId = ref(null)
const dashboardTeams = ref([])
const userPermissions = ref([])
const grantForm = reactive({ permissionType: 'READ' })
const userGrantForm = reactive({ userId: '', permissionType: 'READ' })
const userGrantCandidates = ref([])
const userGrantSearchLoading = ref(false)
const workbenchGrantDashboardId = ref(null)
const workbenchGrantForm = reactive({ permissionType: 'READ' })
const memberForm = reactive({ userId: '', memberRole: 'MEMBER' })
const teamDialogVisible = ref(false)
const teamEditId = ref(null)
const teamForm = reactive({ name: '', description: '' })
const teamSaving = ref(false)

const receivedRows = ref([])
const loadingReceived = ref(false)

const annForm = reactive({ content: '', tag: '' })
const comForm = reactive({ content: '' })
const annSubmitting = ref(false)
const comSubmitting = ref(false)
const mentionCandidates = ref([])
const memberCandidates = ref([])
const mentionLoading = ref(false)
const memberSearchLoading = ref(false)
const mentionVisible = ref(false)
const pendingMentions = ref([])

const selected = computed(() => workbenchDashboards.value.find((r) => r.id == selectedId.value) || null)
const selectedPermissionTypes = computed(() => {
  const types = selected.value?.permissionTypes || []
  return new Set(types.map((t) => String(t).toUpperCase()))
})
const selectedHasReadScope = computed(() => selectedPermissionTypes.value.has('READ'))
const selectedHasEditScope = computed(() => selectedPermissionTypes.value.has('EDIT'))
const selectedTeam = computed(() => teams.value.find((t) => t.id === selectedTeamId.value) || null)
const distributableDashboards = computed(() => rows.value.filter((r) => canDistributeBoard(r)))
const workbenchGrantCandidates = computed(() => {
  const grantedIds = new Set(workbenchDashboards.value.map((d) => d.id))
  return distributableDashboards.value.filter((d) => !grantedIds.has(d.id))
})
const selectedDistributeDashboard = computed(() => rows.value.find((r) => r.id == distributeDashboardId.value) || null)
const canManageDistributeDashboard = computed(() => {
  const row = selectedDistributeDashboard.value
  if (!row) return false
  if (String(currentRole.value || '').toUpperCase() === 'ADMIN') return true
  return isBoardOwner(row) || isBoardSaveAsUser(row)
})
const canManageSelectedTeam = computed(() => {
  const role = selectedTeam.value?.myRole
  return role === 'OWNER' || role === 'ADMIN' || currentUser.value?.role === 'ADMIN'
})
const canDissolveTeam = computed(() => {
  const role = selectedTeam.value?.myRole
  return role === 'OWNER' || currentUser.value?.role === 'ADMIN'
})
const wsTargetType = computed(() => 'DASHBOARD')
const wsTargetId = computed(() => selectedId.value || null)
const activeCanvasItemId = computed(() => {
  if (!activeNode.value || activeNode.value.kind === 'dashboard') return null
  return String(activeNode.value.targetId)
})
const activeNodeLabel = computed(() => activeNode.value?.label || '整板')
const canvasItemBadges = computed(() => buildCanvasItemBadges(annotations.value))
const boardLevelAnnotationCount = computed(() => {
  const did = selected.value?.id
  if (!did) return 0
  return annotations.value.filter((ann) => isBoardLevelAnnotation(ann, did)).length
})
const recentAnnotations = computed(() => [...annotations.value].slice(0, 6))
const contextRecentAnnotations = computed(() => {
  if (!activeNode.value) return recentAnnotations.value
  const matched = annotations.value.filter((ann) => annotationMatchesNode(ann, activeNode.value))
  return matched.length ? matched : recentAnnotations.value
})
function isAnnOnActiveNode(ann) {
  if (!activeNode.value) return false
  return annotationMatchesNode(ann, activeNode.value)
}
const filteredAnnotations = computed(() => {
  if (!activeNode.value) return annotations.value
  return annotations.value.filter((ann) => annotationMatchesNode(ann, activeNode.value))
})

function nodeKey(node) {
  return `${node.targetType}:${node.targetId}`
}
function resolveNodeFromCanvas(payload) {
  if (!payload) return null
  const id = String(payload.targetId)
  const match = nodes.value.find(
    (n) => n.targetType === payload.targetType && String(n.targetId) === id
  )
  return match || payload
}
function isNodeActive(node) {
  return activeNode.value && nodeKey(activeNode.value) === nodeKey(node)
}
function selectNode(node) {
  activeNode.value = node
  activeNodeKey.value = nodeKey(node)
}
function onCanvasSelectItem(payload) {
  selectNode(resolveNodeFromCanvas(payload))
}
function nodeAnnotationCount(node) {
  return annotations.value.filter((ann) => countAnnotationsForNode(ann, node, selected.value?.id)).length
}
function stickyPreviewStyle(tag) {
  return annotationStickyStyle(tag)
}
function annTagPreset(tag) {
  return annotationTagPreset(tag)
}
function applyAnnotationTemplate(tpl) {
  annForm.tag = tpl.value
  if (!annForm.content.trim()) {
    const hints = {
      异常说明: '发现数据异常：',
      经验总结: '业务经验：',
      待跟进: '待跟进事项：'
    }
    annForm.content = hints[tpl.value] || ''
  }
}
function focusAnnotation(ann) {
  const bind = parseAnnBind(ann)
  const layoutId = bind?.layoutItemId || bind?.componentId
  let node = null
  if (layoutId) {
    node = nodes.value.find((n) => String(n.targetId) === String(layoutId))
  }
  if (!node && ann.targetType === 'COMPONENT') {
    node = nodes.value.find((n) => String(n.targetId) === String(ann.targetId))
  }
  if (!node && bind?.nodeLabel) {
    node = nodes.value.find((n) => n.label === bind.nodeLabel)
  }
  if (node) selectNode(node)
  else if (ann.targetType === 'DASHBOARD') {
    const boardNode = nodes.value.find((n) => n.kind === 'dashboard')
    if (boardNode) selectNode(boardNode)
  }
}
function parseAnnBind(ann) {
  if (!ann?.bindJson) return null
  try {
    return typeof ann.bindJson === 'string' ? JSON.parse(ann.bindJson) : ann.bindJson
  } catch {
    return null
  }
}
function onActiveNodeKeyChange(key) {
  const node = nodes.value.find((n) => nodeKey(n) === key)
  if (node) activeNode.value = node
}
function syncActiveNodeKey() {
  activeNodeKey.value = activeNode.value ? nodeKey(activeNode.value) : ''
}
function displayName(row) {
  return row.nickname || row.username || row.userId || '用户'
}
function formatTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
function canDelete(userId) {
  const me = currentUser.value?.userId
  return me === userId || currentUser.value?.role === 'ADMIN'
}

function buildBindJson() {
  const node = activeNode.value
  if (!node) return null
  const payload = { nodeLabel: node.label, nodeKind: node.kind }
  if (node.targetType === 'COMPONENT' && node.kind !== 'dashboard') {
    if (String(node.targetId).match(/^\d+$/)) payload.componentId = Number(node.targetId)
    else payload.layoutItemId = node.targetId
  }
  return JSON.stringify(payload)
}
function buildAnnotationPayload() {
  const dashboardId = selected.value.id
  const node = activeNode.value
  if (!node || node.kind === 'dashboard') {
    return {
      targetType: 'DASHBOARD',
      targetId: dashboardId,
      dashboardId,
      content: annForm.content.trim(),
      tag: annForm.tag || null,
      bindJson: null
    }
  }
  const numericId = String(node.targetId).match(/^\d+$/) ? Number(node.targetId) : dashboardId
  return {
    targetType: node.targetType === 'COMPONENT' ? 'COMPONENT' : 'DASHBOARD',
    targetId: numericId,
    dashboardId,
    content: annForm.content.trim(),
    tag: annForm.tag || null,
    bindJson: buildBindJson()
  }
}
function annotationMatchesNode(ann, node) {
  if (node.kind === 'dashboard') {
    return isBoardLevelAnnotation(ann, selected.value?.id)
  }
  if (ann.targetType === 'COMPONENT' && String(ann.targetId) === String(node.targetId)) return true
  if (!ann.bindJson) return false
  try {
    const bind = typeof ann.bindJson === 'string' ? JSON.parse(ann.bindJson) : ann.bindJson
    if (bind.layoutItemId && String(bind.layoutItemId) === String(node.targetId)) return true
    if (bind.componentId && String(bind.componentId) === String(node.targetId)) return true
    return bind.nodeLabel === node.label
  } catch {
    return false
  }
}
function renderMentions(content) {
  return String(content || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/@([\w-]+)/g, '<span class="mention">@$1</span>')
}

const canCompose = computed(() => {
  if (!selected.value) return false
  if (String(currentRole.value || '').toUpperCase() === 'ADMIN') return true
  if (selected.value.ownerUserId === currentUser.value?.userId) return true
  if (selectedHasReadScope.value || selectedHasEditScope.value) return true
  if (boardIsPublic(selected.value)) return true
  return false
})

function onWsCommentCreated(comment) {
  if (comments.value.some((c) => c.id === comment.id)) return
  comments.value = [...comments.value, comment]
  summary.commentCount = comments.value.length
}
function onWsCommentDeleted(id) {
  comments.value = comments.value.filter((c) => c.id !== id)
  summary.commentCount = comments.value.length
}
function onWsAnnotationCreated(ann) {
  if (annotations.value.some((a) => a.id === ann.id)) return
  annotations.value = [...annotations.value, ann]
  summary.annotationCount = annotations.value.length
}
function onWsAnnotationDeleted(id) {
  annotations.value = annotations.value.filter((a) => a.id !== id)
  summary.annotationCount = annotations.value.length
}
const { connected: wsConnected, connect: connectWs } = useCollabWebSocket({
  targetType: wsTargetType,
  targetId: wsTargetId,
  onCommentCreated: onWsCommentCreated,
  onCommentDeleted: onWsCommentDeleted,
  onAnnotationCreated: onWsAnnotationCreated,
  onAnnotationDeleted: onWsAnnotationDeleted
})

async function loadList() {
  loadingList.value = true
  try {
    rows.value = await fetchCollabDashboards()
  } catch (e) {
    ElMessage.error(e.message || '加载看板失败')
  } finally {
    loadingList.value = false
  }
}
function normalizeTeamDashboard(row) {
  return {
    id: row.dashboardId,
    name: row.dashboardName,
    ownerUserId: row.ownerUserId,
    isPublic: row.isPublic,
    status: row.status,
    permissionType: row.permissionType
  }
}
function mergeTeamDashboardRows(list) {
  const map = new Map()
  for (const row of list || []) {
    const item = normalizeTeamDashboard(row)
    const perm = item.permissionType
    const existing = map.get(item.id)
    if (!existing) {
      map.set(item.id, { ...item, permissionTypes: perm ? [perm] : [] })
      continue
    }
    if (perm && !existing.permissionTypes.includes(perm)) {
      existing.permissionTypes.push(perm)
    }
  }
  return [...map.values()]
}
async function loadWorkbenchDashboards() {
  if (!selectedTeamId.value) {
    workbenchDashboards.value = []
    selectedId.value = null
    return
  }
  loadingWorkbenchDashboards.value = true
  try {
    const list = await fetchTeamDashboards(selectedTeamId.value)
    workbenchDashboards.value = mergeTeamDashboardRows(list)
    if (selectedId.value != null && !workbenchDashboards.value.some((r) => r.id == selectedId.value)) {
      selectedId.value = null
    }
    if (workbenchGrantDashboardId.value != null && !workbenchGrantCandidates.value.some((d) => d.id == workbenchGrantDashboardId.value)) {
      workbenchGrantDashboardId.value = null
    }
  } catch (e) {
    workbenchDashboards.value = []
    ElMessage.error(e.message || '加载团队看板失败')
  } finally {
    loadingWorkbenchDashboards.value = false
  }
}
async function onWorkbenchTeamChange() {
  selectedId.value = null
  annotations.value = []
  comments.value = []
  activeNode.value = null
  workbenchGrantDashboardId.value = null
  await loadWorkbenchDashboards()
}
async function refreshWorkbench() {
  await Promise.all([loadTeams(), loadList(), loadWorkbenchDashboards()])
  if (selectedId.value) await loadCollabData()
}
async function grantToWorkbenchTeam() {
  if (!workbenchGrantDashboardId.value || !selectedTeamId.value) {
    ElMessage.warning('请选择要分发的看板')
    return
  }
  try {
    await grantDashboardToTeam(workbenchGrantDashboardId.value, {
      teamId: selectedTeamId.value,
      permissionType: workbenchGrantForm.permissionType
    })
    workbenchGrantDashboardId.value = null
    await loadWorkbenchDashboards()
    ElMessage.success('已分发到当前团队')
  } catch (e) {
    ElMessage.error(e.message || '分发失败')
  }
}
async function loadCollabData() {
  if (!selected.value) return
  const id = selected.value.id
  try {
    const [sum, anns, cms] = await Promise.all([
      fetchCollabSummary(id),
      fetchAnnotationsByDashboard(id),
      fetchComments('DASHBOARD', id)
    ])
    Object.assign(summary, sum)
    nodes.value = sum.nodes || []
    annotations.value = anns || []
    comments.value = cms || []
    if (!activeNode.value && nodes.value.length) activeNode.value = nodes.value[0]
    syncActiveNodeKey()
    connectWs()
  } catch (e) {
    ElMessage.error(e.message || '加载协作数据失败')
  }
}
function onDashboardChange() {
  annotations.value = []
  comments.value = []
  activeNode.value = null
  activeNodeKey.value = ''
  if (selected.value) loadCollabData()
}

async function loadTeams() {
  loadingTeams.value = true
  try {
    teams.value = await fetchMyTeams()
    if (selectedTeamId.value && !teams.value.some((t) => t.id === selectedTeamId.value)) selectedTeamId.value = null
    if (!selectedTeamId.value && teams.value.length) selectedTeamId.value = teams.value[0].id
    if (selectedTeamId.value) {
      await loadTeamMembers()
      await searchTeamMemberUsers('')
    }
  } catch (e) {
    ElMessage.error(e.message || '加载团队失败')
  } finally {
    loadingTeams.value = false
  }
}
async function selectTeam(id) {
  selectedTeamId.value = id
  memberForm.userId = ''
  memberCandidates.value = []
  await loadTeamMembers()
  await searchTeamMemberUsers('')
  if (activeTab.value === 'workbench') {
    await onWorkbenchTeamChange()
  }
}
async function loadTeamMembers() {
  if (!selectedTeamId.value) return
  try {
    teamMembers.value = await fetchTeamMembers(selectedTeamId.value)
  } catch (e) {
    ElMessage.error(e.message || '加载成员失败')
  }
}
function openCreateTeam() {
  teamEditId.value = null
  teamForm.name = ''
  teamForm.description = ''
  teamDialogVisible.value = true
}
function openEditTeam() {
  if (!selectedTeam.value) return
  teamEditId.value = selectedTeam.value.id
  teamForm.name = selectedTeam.value.name || ''
  teamForm.description = selectedTeam.value.description || ''
  teamDialogVisible.value = true
}
async function dissolveTeam() {
  if (!selectedTeam.value) return
  const name = selectedTeam.value.name || '该团队'
  try {
    await ElMessageBox.confirm(
      `确定解散团队「${name}」？解散后成员将无法再通过该团队访问已授权看板，相关团队授权也会失效。`,
      '解散团队',
      { type: 'warning', confirmButtonText: '解散', cancelButtonText: '取消' }
    )
    await deleteTeam(selectedTeam.value.id)
    selectedTeamId.value = null
    teamMembers.value = []
    dashboardTeams.value = []
    await loadTeams()
    ElMessage.success('团队已解散')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '解散失败')
  }
}
async function saveTeam() {
  if (!teamForm.name.trim()) {
    ElMessage.warning('请输入团队名称')
    return
  }
  teamSaving.value = true
  try {
    const payload = { name: teamForm.name.trim(), description: teamForm.description.trim() || null }
    if (teamEditId.value) await updateTeam(teamEditId.value, payload)
    else await createTeam(payload)
    teamDialogVisible.value = false
    await loadTeams()
    ElMessage.success('团队已保存')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    teamSaving.value = false
  }
}
async function submitAddMember() {
  if (!selectedTeamId.value || !memberForm.userId) {
    ElMessage.warning('请选择用户')
    return
  }
  try {
    await addTeamMember(selectedTeamId.value, {
      userId: memberForm.userId,
      memberRole: memberForm.memberRole || 'MEMBER'
    })
    memberForm.userId = ''
    await loadTeamMembers()
    await loadTeams()
    ElMessage.success('成员已加入')
  } catch (e) {
    ElMessage.error(e.message || '添加失败')
  }
}
async function removeMember(row) {
  try {
    await ElMessageBox.confirm(`确定将 ${row.userId} 移出团队？`, '确认', { type: 'warning' })
    await removeTeamMember(selectedTeamId.value, row.userId)
    await loadTeamMembers()
    await loadTeams()
    ElMessage.success('已移除')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '移除失败')
  }
}
async function loadDashboardTeams() {
  if (!distributeDashboardId.value) {
    dashboardTeams.value = []
    userPermissions.value = []
    return
  }
  try {
    dashboardTeams.value = await fetchDashboardTeams(distributeDashboardId.value)
  } catch (e) {
    dashboardTeams.value = []
    if (String(e.message || '').includes('所有者')) ElMessage.warning(e.message)
  }
  await loadUserPermissions()
}
async function loadUserPermissions() {
  if (!distributeDashboardId.value || !canManageDistributeDashboard.value) {
    userPermissions.value = []
    return
  }
  try {
    userPermissions.value = await fetchTeamPermissions(distributeDashboardId.value)
  } catch {
    userPermissions.value = []
  }
}
async function searchUserGrantCandidates(keyword) {
  userGrantSearchLoading.value = true
  try {
    userGrantCandidates.value = await fetchMentionCandidates(keyword)
  } catch {
    userGrantCandidates.value = []
  } finally {
    userGrantSearchLoading.value = false
  }
}
async function grantUserPermission() {
  if (!distributeDashboardId.value || !userGrantForm.userId) {
    ElMessage.warning('请选择看板和用户')
    return
  }
  try {
    await grantTeamPermission(distributeDashboardId.value, {
      userId: userGrantForm.userId,
      permissionType: userGrantForm.permissionType
    })
    userGrantForm.userId = ''
    await loadUserPermissions()
    ElMessage.success('已授权用户')
  } catch (e) {
    ElMessage.error(e.message || '授权失败')
  }
}
async function revokeUserPermission(row) {
  try {
    await revokeTeamPermission(distributeDashboardId.value, row.userId, row.permissionType)
    await loadUserPermissions()
    ElMessage.success('已撤销')
  } catch (e) {
    ElMessage.error(e.message || '撤销失败')
  }
}
async function grantCurrentTeam() {
  if (!distributeDashboardId.value || !selectedTeamId.value) {
    ElMessage.warning('请选择看板和团队')
    return
  }
  try {
    await grantDashboardToTeam(distributeDashboardId.value, {
      teamId: selectedTeamId.value,
      permissionType: grantForm.permissionType
    })
    await loadDashboardTeams()
    await loadWorkbenchDashboards()
    ElMessage.success('已授权团队')
  } catch (e) {
    ElMessage.error(e.message || '授权失败')
  }
}
async function revokeTeamGrant(row) {
  try {
    await revokeDashboardTeamGrant(distributeDashboardId.value, row.teamId, row.permissionType)
    await loadDashboardTeams()
    if (row.teamId === selectedTeamId.value) await loadWorkbenchDashboards()
    ElMessage.success('已撤销')
  } catch (e) {
    ElMessage.error(e.message || '撤销失败')
  }
}

async function loadReceived() {
  loadingReceived.value = true
  try {
    receivedRows.value = await fetchReceivedDashboards()
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingReceived.value = false
  }
}
function enterWorkbench(row) {
  activeTab.value = 'workbench'
  if (row.teamId) selectedTeamId.value = row.teamId
  selectedId.value = row.id
  loadTeams().then(async () => {
    await loadWorkbenchDashboards()
    if (selectedId.value) await loadCollabData()
  })
}

function onTabChange(name) {
  if (name === 'workbench') {
    loadTeams().then(() => loadWorkbenchDashboards())
  } else if (name === 'distribute') {
    loadTeams()
    if (distributeDashboardId.value) {
      loadDashboardTeams()
    }
  } else if (name === 'received') {
    loadReceived()
  }
}

async function toggleFollow() {
  if (!selected.value) return
  try {
    if (summary.following) {
      await unfollowDashboard(selected.value.id)
      summary.following = false
      ElMessage.success('已取消关注')
    } else {
      await followDashboard(selected.value.id)
      summary.following = true
      ElMessage.success('已关注看板')
    }
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}
async function exportReport() {
  if (!selected.value) return
  try {
    await downloadCollabReport(selected.value.id)
    ElMessage.success('Markdown 汇报已下载')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  }
}
async function submitAnnotation() {
  if (!canCompose.value) {
    ElMessage.warning('当前无协作批注权限')
    return
  }
  if (!selected.value || !annForm.content.trim()) {
    ElMessage.warning('请输入批注内容')
    return
  }
  annSubmitting.value = true
  try {
    await createAnnotation(buildAnnotationPayload())
    annForm.content = ''
    await loadCollabData()
    ElMessage.success('批注已发表')
  } catch (e) {
    ElMessage.error(e.message || '发表失败')
  } finally {
    annSubmitting.value = false
  }
}
async function removeAnnotation(id) {
  try {
    await ElMessageBox.confirm('确定删除该批注？', '确认', { type: 'warning' })
    await deleteAnnotation(id)
    annotations.value = annotations.value.filter((a) => a.id !== id)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}
function extractMentions(text) {
  const ids = []
  const re = /@([\w-]+)/g
  let m
  while ((m = re.exec(text)) !== null) {
    if (!ids.includes(m[1])) ids.push(m[1])
  }
  return ids
}
async function searchMentionUsers(keyword) {
  mentionLoading.value = true
  try {
    mentionCandidates.value = await fetchMentionCandidates(keyword)
  } catch {
    mentionCandidates.value = []
  } finally {
    mentionLoading.value = false
  }
}
async function searchTeamMemberUsers(keyword) {
  if (!selectedTeamId.value) {
    memberCandidates.value = []
    return
  }
  memberSearchLoading.value = true
  try {
    memberCandidates.value = await fetchTeamMemberCandidates(selectedTeamId.value, keyword)
  } catch {
    memberCandidates.value = []
  } finally {
    memberSearchLoading.value = false
  }
}
async function onCommentInput(val) {
  const text = String(val ?? comForm.content)
  const atIdx = text.lastIndexOf('@')
  if (atIdx >= 0 && (atIdx === 0 || /\s/.test(text[atIdx - 1]))) {
    const q = text.slice(atIdx + 1)
    if (!q.includes(' ')) {
      mentionVisible.value = true
      await searchMentionUsers(q)
      return
    }
  }
  mentionVisible.value = false
}
function insertMention(user) {
  const text = comForm.content
  const atIdx = text.lastIndexOf('@')
  const prefix = atIdx >= 0 ? text.slice(0, atIdx) : text
  comForm.content = `${prefix}@${user.userId} `
  if (!pendingMentions.value.includes(user.userId)) pendingMentions.value.push(user.userId)
  mentionVisible.value = false
}
async function submitComment() {
  if (!canCompose.value) {
    ElMessage.warning('当前无协作评论权限')
    return
  }
  if (!selected.value || !comForm.content.trim()) {
    ElMessage.warning('请输入评论')
    return
  }
  comSubmitting.value = true
  const mentionIds = [...new Set([...pendingMentions.value, ...extractMentions(comForm.content)])]
  try {
    const created = await createComment({
      targetType: 'DASHBOARD',
      targetId: selected.value.id,
      content: comForm.content.trim(),
      mentionsJson: mentionIds.length ? JSON.stringify(mentionIds) : null
    })
    if (!comments.value.some((c) => c.id === created.id)) comments.value = [...comments.value, created]
    comForm.content = ''
    pendingMentions.value = []
    mentionVisible.value = false
    ElMessage.success('评论已发表')
  } catch (e) {
    ElMessage.error(e.message || '发表失败')
  } finally {
    comSubmitting.value = false
  }
}
async function removeComment(id) {
  try {
    await ElMessageBox.confirm('确定删除该评论？', '确认', { type: 'warning' })
    await deleteComment(id)
    comments.value = comments.value.filter((c) => c.id !== id)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

function applyNavIntent() {
  const nav = consumeCollabNav()
  if (!nav) return
  if (nav.tab) activeTab.value = nav.tab
  if (nav.teamId) selectedTeamId.value = Number(nav.teamId)
  if (nav.dashboardId) {
    const id = Number(nav.dashboardId)
    if (nav.tab === 'workbench') selectedId.value = id
    else distributeDashboardId.value = id
  }
}

watch(selectedId, (id) => {
  if (id && activeTab.value === 'workbench') loadCollabData()
})

onMounted(async () => {
  applyNavIntent()
  await loadList()
  await searchMentionUsers('')
  if (activeTab.value === 'workbench') {
    await loadTeams()
    await loadWorkbenchDashboards()
    if (selectedId.value) await loadCollabData()
  } else if (activeTab.value === 'distribute') {
    await loadTeams()
    if (distributeDashboardId.value) await loadDashboardTeams()
  } else if (activeTab.value === 'received') {
    await loadReceived()
  }
})
</script>

<style scoped>
.biz-collab-page { padding: 0 4px 24px; }
.collab-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.collab-head h1 { margin: 0 0 6px; font-size: 22px; }
.collab-head p { margin: 0; color: #909399; font-size: 14px; }
.collab-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }
.select-card { margin-bottom: 16px; }
.select-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.team-select { min-width: 160px; max-width: 180px; }
.board-select { min-width: 280px; flex: 1; max-width: 420px; }
.board-select--workbench {
  flex: 0 0 180px;
  width: 180px;
  min-width: 140px;
  max-width: 200px;
}
.permission-scope-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  height: 32px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #f5f7fa;
  white-space: nowrap;
  flex-shrink: 0;
}
.permission-scope-label { font-size: 13px; color: #606266; }
.permission-scope-tags { display: inline-flex; align-items: center; gap: 6px; }
.workbench-grant-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 12px; padding-top: 12px; border-top: 1px dashed #ebeef5; }
.grant-label { font-size: 13px; color: #606266; white-space: nowrap; }
.grant-board-select { flex: 1; min-width: 200px; max-width: 360px; }
.meta-row { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; font-size: 13px; color: #606266; }
.collab-layout { display: grid; grid-template-columns: minmax(0, 1fr) 400px; gap: 16px; align-items: stretch; }
.collab-recent-strip {
  display: flex; align-items: center; gap: 8px; margin-top: 10px;
  overflow-x: auto; padding-bottom: 4px;
}
.collab-recent-label { font-size: 12px; color: #64748b; white-space: nowrap; flex-shrink: 0; }
.collab-recent-card {
  flex: 0 0 auto; max-width: 200px; border: 1px solid; border-radius: 8px;
  padding: 6px 10px; text-align: left; cursor: pointer;
  display: flex; flex-direction: column; gap: 2px;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}
.collab-recent-card.active {
  box-shadow: 0 0 0 2px #f59e0b, 0 4px 12px rgba(245, 158, 11, 0.25);
  transform: translateY(-1px);
}
.collab-recent-tag { font-size: 11px; font-weight: 600; color: #475569; }
.collab-recent-text {
  font-size: 12px; color: #334155; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.side-panel--collab :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  max-height: none;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 12px;
}
.collab-side {
  min-width: 0;
  position: sticky;
  top: 12px;
  align-self: start;
  height: calc(100vh - 24px);
  max-height: calc(100vh - 24px);
  display: flex;
  flex-direction: column;
}
.side-panel--collab.el-card {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 10px;
}
.node-chip-badge {
  margin-left: 4px; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 999px; background: #fde68a; color: #92400e; font-size: 10px; line-height: 16px;
}
.collab-canvas-col { min-width: 0; }
.collab-canvas-col :deep(.dbv-embedded-root) { border: 1px solid #ebeef5; border-radius: 8px; background: #f8fafc; padding: 8px; }
.collab-node-chips { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.node-chip {
  padding: 4px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background: #fff;
  font-size: 12px;
  cursor: pointer;
  max-width: 100%;
  white-space: normal;
  word-break: break-word;
  text-align: left;
  line-height: 1.4;
}
.node-chip.active { border-color: #409eff; background: #ecf5ff; color: #409eff; }
.panel-card :deep(.el-card__header) { font-weight: 600; }
.ann-head, .comment-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ann-body, .comment-body { margin: 6px 0 0; line-height: 1.6; white-space: pre-wrap; }
.bind-json { margin: 8px 0 0; padding: 8px; background: #f5f7fa; border-radius: 6px; font-size: 12px; overflow: auto; }
.ann-form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.flex1 { margin-bottom: 0; }
.comment-list { display: flex; flex-direction: column; gap: 12px; }
.comment-item { padding-bottom: 12px; border-bottom: 1px solid #ebeef5; }
.muted { color: #909399; font-size: 12px; }
.mention-pop { margin-top: 6px; border: 1px solid #dcdfe6; border-radius: 8px; background: #fff; max-height: 160px; overflow: auto; }
.mention-opt { display: block; width: 100%; padding: 8px 12px; border: none; background: transparent; text-align: left; cursor: pointer; font-size: 13px; }
.mention-opt:hover { background: #f5f7fa; }
:deep(.mention) { color: #409eff; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }

.distribute-layout { display: grid; grid-template-columns: 240px 1fr 1fr; gap: 16px; align-items: start; }
.card-head-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.team-item { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; width: 100%; margin-bottom: 8px; padding: 10px 12px; border: 1px solid #ebeef5; border-radius: 8px; background: #fff; cursor: pointer; text-align: left; }
.team-item.active { border-color: #409eff; background: #ecf5ff; }
.team-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.team-desc {
  margin: 0 0 12px;
  padding: 8px 10px;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  border-radius: 6px;
  line-height: 1.5;
}
.grant-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.distribute-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; font-size: 12px; color: #606266; }
.distribute-hint { color: #909399; }

@media (max-width: 1100px) {
  .distribute-layout { grid-template-columns: 1fr; }
  .collab-layout { grid-template-columns: 1fr; }
  .collab-side {
    position: static;
    height: auto;
    max-height: none;
  }
  .side-panel--collab.el-card { height: auto; }
  .side-panel--collab :deep(.el-card__body) { max-height: 70vh; overflow: auto; }
  .ann-form-row { grid-template-columns: 1fr; }
}
</style>
