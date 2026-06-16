<template>
  <section class="biz-collab-page" :class="{ 'has-grant-dock': showGrantDock }">
    <div class="collab-page-body">
    <header class="collab-head">
      <div>
        <h1>业务批注与协同</h1>
        <p>团队协作看板批注、评论与分发授权。</p>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="collab-tabs" @tab-change="onTabChange">
      <!-- Tab 1: 协作工作台 -->
      <el-tab-pane label="协作工作台" name="workbench">
        <!-- 操作路径引导 -->
        <div class="wb-steps">
          <div :class="['wb-step', { done: !!selectedTeamId }]">
            <span class="wb-step-num">1</span>
            <span class="wb-step-text">选择团队</span>
          </div>
          <span class="wb-step-arrow">→</span>
          <div :class="['wb-step', { done: selectedTeamId && workbenchDashboards.length }]">
            <span class="wb-step-num">2</span>
            <span class="wb-step-text">查看已分发看板</span>
          </div>
          <span class="wb-step-arrow">→</span>
          <div :class="['wb-step', { done: selectedTeamId && workbenchDashboards.length }]">
            <span class="wb-step-num">3</span>
            <span class="wb-step-text">点击进入协作</span>
          </div>
        </div>

        <!-- 顶部筛选：仅团队 -->
        <el-card shadow="never" class="wb-filter-card">
          <div class="wb-filter-row">
            <label class="wb-field-label">当前团队</label>
            <el-select
              v-model="selectedTeamId"
              placeholder="请选择团队"
              filterable
              clearable
              class="wb-team-select"
              :loading="loadingTeams"
              @change="onWorkbenchTeamChange"
            >
              <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <template v-if="selectedTeam">
              <el-tag size="small" effect="plain">{{ selectedTeam.myRole || '成员' }}</el-tag>
              <span class="wb-filter-meta">{{ selectedTeam.memberCount || 0 }} 名成员</span>
              <span class="wb-filter-meta">{{ workbenchDashboards.length }} 个已分发看板</span>
            </template>
            <el-button v-if="!teams.length && !loadingTeams" type="primary" link @click="activeTab = 'distribute'">
              去创建团队
            </el-button>
          </div>
        </el-card>

        <!-- 中间：已分发看板列表 -->
        <CollabDistributedBoardTable
          v-if="selectedTeamId"
          :dashboards="workbenchDashboards"
          :loading="loadingWorkbenchDashboards"
          empty-hint="请在页面底部选择看板与权限，点击「分发到当前团队」完成首次分发。"
          :format-time="formatTime"
          :can-manage="canManageWorkbenchGrant"
          @enter="enterCollabRoom"
          @design="openDesignBoard"
          @revoke="revokeWorkbenchGrant"
          @change-permission="changeWorkbenchPermission"
        />

        <el-empty v-else-if="!loadingTeams && !teams.length" description="暂无团队，请先到「团队与分发」创建团队" />
        <el-empty v-else-if="!selectedTeamId" description="请先选择团队，查看已分发看板并开始协作" />
      </el-tab-pane>

      <!-- Tab 2: 团队与分发 -->
      <el-tab-pane label="团队与分发" name="distribute">
        <nav class="dist-sub-nav" aria-label="团队与分发子导航">
          <button
            type="button"
            :class="['dist-sub-nav-item', { active: distSubTab === 'team-mgmt' }]"
            @click="switchDistSubTab('team-mgmt')"
          >
            <span class="dist-sub-nav-icon dist-sub-nav-icon--team" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5s-3 1.34-3 3 1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5C15 14.17 10.33 13 8 13zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"/></svg>
            </span>
            <span class="dist-sub-nav-text">
              <strong>团队管理</strong>
              <em>成员与组织</em>
            </span>
          </button>
          <button
            type="button"
            :class="['dist-sub-nav-item', { active: distSubTab === 'grant-auth' }]"
            @click="switchDistSubTab('grant-auth')"
          >
            <span class="dist-sub-nav-icon dist-sub-nav-icon--grant" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 14H7v-2h5v2zm7-4H7v-2h12v2zm0-4H7V7h12v2z"/></svg>
            </span>
            <span class="dist-sub-nav-text">
              <strong>看板分发授权</strong>
              <em>团队与个人分发</em>
            </span>
          </button>
        </nav>

        <div v-show="distSubTab === 'team-mgmt'" class="dist-sub-panel">
            <div class="wb-steps">
              <div :class="['wb-step', { done: !!selectedTeamId }]">
                <span class="wb-step-num">1</span>
                <span class="wb-step-text">选择团队</span>
              </div>
              <span class="wb-step-arrow">→</span>
              <div :class="['wb-step', { done: selectedTeamId && teamMembers.length }]">
                <span class="wb-step-num">2</span>
                <span class="wb-step-text">管理成员</span>
              </div>
            </div>

            <el-card shadow="never" class="wb-filter-card">
              <div class="wb-filter-row">
                <label class="wb-field-label">当前团队</label>
                <el-select
                  v-model="selectedTeamId"
                  placeholder="请选择团队"
                  filterable
                  clearable
                  class="wb-team-select"
                  :loading="loadingTeams"
                  @change="onDistributeTeamChange"
                >
                  <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
                </el-select>
                <template v-if="selectedTeam">
                  <el-tag size="small" effect="plain">{{ selectedTeam.myRole || '成员' }}</el-tag>
                  <span class="wb-filter-meta">{{ selectedTeam.memberCount || 0 }} 名成员</span>
                </template>
                <div class="wb-filter-actions">
                  <el-button type="primary" :icon="Plus" @click="openCreateTeam">新建团队</el-button>
                  <template v-if="selectedTeam && canManageSelectedTeam">
                    <el-button :icon="EditPen" @click="openEditTeam">编辑</el-button>
                    <el-button v-if="canDissolveTeam" type="danger" plain :icon="Delete" @click="dissolveTeam">解散</el-button>
                  </template>
                </div>
              </div>
              <p v-if="selectedTeam?.description" class="dist-team-desc">{{ selectedTeam.description }}</p>
            </el-card>

            <el-card v-if="selectedTeam" shadow="never" class="dist-members-card">
              <template #header>
                <div class="wb-list-head">
                  <span class="wb-list-title">团队成员 · {{ selectedTeam.name }}</span>
                  <span class="wb-list-count">{{ teamMembers.length }} 人</span>
                </div>
              </template>
              <el-form v-if="canManageSelectedTeam" inline size="default" class="dist-member-form" @submit.prevent="submitAddMember">
                <el-select
                  v-model="memberForm.userId"
                  filterable
                  remote
                  clearable
                  placeholder="搜索用户加入团队"
                  :remote-method="searchTeamMemberUsers"
                  :loading="memberSearchLoading"
                  class="dist-member-user-select"
                >
                  <el-option
                    v-for="u in memberCandidates"
                    :key="u.userId"
                    :label="`${u.nickname || u.username} (${u.userId})`"
                    :value="u.userId"
                  />
                </el-select>
                <el-select v-model="memberForm.memberRole" class="dist-member-role-select">
                  <el-option label="成员" value="MEMBER" />
                  <el-option label="管理员" value="ADMIN" />
                </el-select>
                <el-button type="primary" @click="submitAddMember">添加成员</el-button>
              </el-form>
              <el-table :data="teamMembers" size="default" class="collab-data-table" empty-text="暂无成员">
                <el-table-column label="用户" min-width="140" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.nickname || row.username || row.userId }}</template>
                </el-table-column>
                <el-table-column prop="memberRole" label="角色" width="100" />
                <el-table-column v-if="canManageSelectedTeam" label="操作" width="160" class-name="col-actions">
                  <template #default="{ row }">
                    <div class="collab-row-actions">
                      <el-button link type="primary" size="small" @click="quickGrantToMember(row)">单独授权</el-button>
                      <el-button v-if="row.memberRole !== 'OWNER'" link type="danger" size="small" @click="removeMember(row)">移除</el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>

            <el-empty v-if="!loadingTeams && !teams.length" description="暂无团队，请点击「新建团队」" />
            <el-empty v-else-if="!selectedTeamId" description="请先选择团队并管理成员" />
        </div>

        <div v-show="distSubTab === 'grant-auth'" class="dist-sub-panel">
            <CollabGrantAuthPanel
              :teams="teams"
              :selected-team="selectedTeam"
              :selected-team-id="selectedTeamId"
              :grant-context-key="grantContextKey"
              :grant-scene-tab="grantSceneTab"
              :grant-list-keyword="grantListKeyword"
              :filtered-grant-rows="filteredGrantRows"
              :loading-grants="loadingUnifiedGrants"
              :distributable-dashboards="distributableDashboards"
              :team-grant-candidates="teamGrantCandidates"
              :team-grant-dashboard-id="teamGrantDashboardId"
              :team-grant-permission-types="teamGrantForm.permissionTypes"
              :team-grant-already-hint="teamGrantAlreadyHint"
              :personal-grant-user-id="personalGrantForm.userId"
              :personal-grant-dashboard-id="personalGrantForm.dashboardId"
              :personal-grant-permission-types="personalGrantForm.permissionTypes"
              :personal-grant-already-hint="personalGrantAlreadyHint"
              :user-grant-candidates="userGrantCandidates"
              :user-grant-search-loading="userGrantSearchLoading"
              :detail-dashboard-id="distributeDashboardId"
              :detail-dashboard="selectedDistributeDashboard"
              :dashboard-teams="dashboardTeams"
              :user-permissions="userPermissions"
              :format-dashboard-label="formatDistributeDashboardLabel"
              :format-time="formatTime"
              :permission-type-label="permissionTypeLabel"
              :board-visibility-label="boardVisibilityLabel"
              :is-board-published="isBoardPublished"
              :can-manage-grant-row="canManageGrantRow"
              :get-grant-permission-types="getGrantPermissionTypes"
              @update:grant-context-key="onGrantContextChange"
              @update:grant-scene-tab="grantSceneTab = $event"
              @update:grant-list-keyword="grantListKeyword = $event"
              @update:team-grant-dashboard-id="teamGrantDashboardId = $event"
              @update:team-grant-permission-types="teamGrantForm.permissionTypes = $event"
              @update:personal-grant-user-id="personalGrantForm.userId = $event"
              @update:personal-grant-dashboard-id="personalGrantForm.dashboardId = $event"
              @update:personal-grant-permission-types="personalGrantForm.permissionTypes = $event"
              @update:detail-dashboard-id="onDetailDashboardChange"
              @search-grant-users="searchUserGrantCandidates"
              @grant-to-team="grantToTeamFromPanel"
              @grant-to-personal="grantToPersonalFromPanel"
              @view-grant-detail="viewGrantDetail"
              @change-grant-permission="changeUnifiedGrantPermission"
              @revoke-grant="revokeUnifiedGrant"
              @revoke-team-detail="revokeTeamGrant"
              @revoke-user-detail="revokeUserPermission"
            />
            <el-empty v-if="!loadingTeams && !teams.length" description="暂无团队，请先在「团队管理」创建团队" />
        </div>
      </el-tab-pane>

      <!-- Tab 3: 我收到的看板 -->
      <el-tab-pane label="我收到的看板" name="received">
        <el-card shadow="never" class="received-card">
          <template #header>
            <div class="wb-list-head">
              <span class="wb-list-title">我收到的看板</span>
              <span v-if="receivedRows.length" class="wb-list-count">{{ receivedRows.length }} 项</span>
            </div>
          </template>
          <p class="dist-auth-lead muted">团队或个人授权给您的看板，可直接进入协作批注。</p>
          <el-table
            v-loading="loadingReceived"
            :data="receivedRows"
            class="collab-data-table"
            empty-text="暂无收到的协作看板"
          >
            <el-table-column prop="name" label="看板名称" min-width="160" show-overflow-tooltip />
            <el-table-column label="所有者" min-width="120" width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="collab-cell-ellipsis">{{ row.ownerUserId || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="100">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.accessSource === 'TEAM' ? '团队' : '个人' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="团队" min-width="100" show-overflow-tooltip>
              <template #default="{ row }">{{ row.teamName || '—' }}</template>
            </el-table-column>
            <el-table-column label="权限" width="120">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ permissionTypeLabel(row.permissionType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="168" show-overflow-tooltip>
              <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right" class-name="col-actions">
              <template #default="{ row }">
                <div class="collab-row-actions">
                  <el-button v-if="rowHasRead(row)" type="primary" link @click="enterCollabRoom(row)">进入协作</el-button>
                  <el-button v-if="rowHasEdit(row)" type="primary" link @click="openDesignBoard(row)">设计看板</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
    </div>

    <!-- 底部固定：追加分发操作栏 -->
    <footer v-if="showGrantDock" class="collab-grant-dock">
      <div class="collab-grant-dock-inner">
        <div class="collab-grant-dock-context">
          <span class="collab-grant-dock-label">追加分发</span>
          <span class="collab-grant-dock-team">团队：{{ selectedTeam?.name }}</span>
        </div>
        <el-select
          v-model="workbenchGrantDashboardId"
          filterable
          clearable
          placeholder="选择要分发的看板"
          class="collab-grant-dock-board"
          :disabled="!workbenchGrantCandidates.length"
        >
          <el-option
            v-for="d in workbenchGrantCandidates"
            :key="d.id"
            :label="formatDistributeDashboardLabel(d)"
            :value="d.id"
          />
        </el-select>
        <el-checkbox-group v-model="workbenchGrantForm.permissionTypes" class="collab-perm-checks collab-perm-checks-inline collab-grant-dock-perm">
          <el-checkbox label="READ">阅览+批注</el-checkbox>
          <el-checkbox label="EDIT">编辑</el-checkbox>
        </el-checkbox-group>
        <el-button
          type="primary"
          size="default"
          class="collab-grant-dock-submit"
          :disabled="!workbenchGrantDashboardId"
          @click="grantToWorkbenchTeam"
        >
          分发到当前团队
        </el-button>
      </div>
      <p v-if="!workbenchGrantCandidates.length && !loadingList" class="collab-grant-dock-hint">
        暂无可分发的看板（您拥有的看板可能已全部加入该团队）
      </p>
    </footer>

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

    <DashboardGridEditor
      v-model="gridEditorVisible"
      :initial-row="gridEditorRow"
      :api-base="API_BASE"
      @saved="loadList"
    />
  </section>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, EditPen, Plus } from '@element-plus/icons-vue'
import CollabDistributedBoardTable from '../../components/collab/CollabDistributedBoardTable.vue'
import CollabGrantAuthPanel from '../../components/collab/CollabGrantAuthPanel.vue'
import DashboardGridEditor from './DashboardGridEditor.vue'
import '../../styles/collab-table.css'
import { currentRole, currentUser } from '../../store/session'
import { consumeCollabNav, openCollabRoom } from '../../utils/collabNav.js'
import {
  canDistributeBoard,
  isBoardOwner,
  isBoardSaveAsUser,
  isBoardPublished,
  boardVisibilityLabel
} from '../../utils/dashboardManageTree.js'
import {
  addTeamMember,
  createTeam,
  deleteTeam,
  fetchDistributeDashboards,
  fetchDashboardTeams,
  fetchMentionCandidates,
  fetchMyTeams,
  fetchReceivedDashboards,
  fetchTeamDashboards,
  fetchTeamMemberCandidates,
  fetchTeamMembers,
  fetchTeamPermissions,
  grantDashboardToTeam,
  grantTeamPermission,
  removeTeamMember,
  revokeDashboardTeamGrant,
  revokeTeamPermission,
  updateTeam
} from '../../api/collab'

const { activeModule } = inject('workbench')

const API_BASE = 'http://localhost:8080'
const gridEditorVisible = ref(false)
const gridEditorRow = ref(null)

const activeTab = ref('workbench')
const distSubTab = ref('team-mgmt')
const grantSceneTab = ref('team')
const grantContextKey = ref(null)
const grantListKeyword = ref('')
const personalGrantRows = ref([])
const loadingUnifiedGrants = ref(false)
const teamGrantDashboardId = ref(null)
const personalGrantForm = reactive({ userId: '', dashboardId: null, permissionTypes: ['READ'] })
const rows = ref([])
const loadingList = ref(false)
const workbenchDashboards = ref([])
const loadingWorkbenchDashboards = ref(false)

const teams = ref([])
const loadingTeams = ref(false)
const selectedTeamId = ref(null)
const teamMembers = ref([])
const distributeDashboardId = ref(null)
const dashboardTeams = ref([])
const userPermissions = ref([])
const userGrantCandidates = ref([])
const userGrantSearchLoading = ref(false)
const workbenchGrantDashboardId = ref(null)
const teamGrantForm = reactive({ permissionTypes: ['READ'] })
const workbenchGrantForm = reactive({ permissionTypes: ['READ'] })
const memberForm = reactive({ userId: '', memberRole: 'MEMBER' })
const memberCandidates = ref([])
const memberSearchLoading = ref(false)
const teamDialogVisible = ref(false)
const teamEditId = ref(null)
const teamForm = reactive({ name: '', description: '' })
const teamSaving = ref(false)

const receivedRows = ref([])
const loadingReceived = ref(false)

const selectedTeam = computed(() => teams.value.find((t) => t.id === selectedTeamId.value) || null)
const showGrantDock = computed(() => activeTab.value === 'workbench' && !!selectedTeamId.value)
const distributableDashboards = computed(() => rows.value.filter((r) => canDistributeBoard(r)))

function formatDistributeDashboardLabel(dashboard) {
  const name = String(dashboard?.name || `看板#${dashboard?.id || ''}`).trim()
  const tag = String(dashboard?.distributeTargetLabel || '').trim()
  if (tag) return `${name}（${tag}）`
  const vis = boardVisibilityLabel(dashboard?.isPublic)
  const pub = isBoardPublished(dashboard) ? '已发布' : '待发布'
  return `${name}（${vis}·${pub}）`
}
const workbenchGrantCandidates = computed(() => {
  const grantedIds = new Set(workbenchDashboards.value.map((d) => d.id))
  return distributableDashboards.value.filter((d) => !grantedIds.has(d.id))
})
const teamGrantCandidates = computed(() => {
  const grantedIds = new Set(workbenchDashboards.value.map((d) => d.id))
  return distributableDashboards.value.filter((d) => !grantedIds.has(d.id))
})
const unifiedGrantRows = computed(() => {
  const rowsOut = []
  if (selectedTeamId.value) {
    for (const d of workbenchDashboards.value) {
      const grants = d.grants?.length
        ? d.grants
        : (d.permissionTypes || []).map((p) => ({ permissionType: p, createdAt: d.createdAt }))
      for (const g of grants) {
        rowsOut.push({
          rowKey: `team-${d.id}-${selectedTeamId.value}-${g.permissionType}`,
          grantType: 'TEAM',
          dashboardId: d.id,
          dashboardName: d.name,
          ownerUserId: d.ownerUserId,
          targetLabel: selectedTeam.value?.name || '—',
          teamId: selectedTeamId.value,
          permissionType: g.permissionType,
          createdAt: g.createdAt,
          sourceDashboard: d
        })
      }
    }
  }
  for (const g of personalGrantRows.value) {
    rowsOut.push({
      rowKey: `user-${g.dashboardId}-${g.userId}-${g.permissionType}`,
      grantType: 'USER',
      dashboardId: g.dashboardId,
      dashboardName: g.dashboardName,
      ownerUserId: g.ownerUserId,
      targetLabel: g.targetLabel,
      userId: g.userId,
      permissionType: g.permissionType,
      createdAt: g.createdAt
    })
  }
  return rowsOut
})
const filteredGrantRows = computed(() => {
  const kw = grantListKeyword.value.trim().toLowerCase()
  if (!kw) return unifiedGrantRows.value
  return unifiedGrantRows.value.filter((r) => {
    const hay = [r.dashboardName, r.targetLabel, r.ownerUserId].map((v) => String(v || '').toLowerCase()).join(' ')
    return hay.includes(kw)
  })
})
const teamGrantAlreadyHint = computed(() => {
  if (!teamGrantDashboardId.value || !selectedTeamId.value) return ''
  const board = workbenchDashboards.value.find((d) => d.id == teamGrantDashboardId.value)
  if (!board?.permissionTypes?.length) return ''
  const labels = board.permissionTypes.map((p) => permissionTypeLabel(p)).join('、')
  return `该看板已授权给当前团队：${labels}`
})
const personalGrantAlreadyHint = computed(() => {
  if (!personalGrantForm.userId || !personalGrantForm.dashboardId) return ''
  const existing = personalGrantRows.value.filter(
    (r) => r.userId === personalGrantForm.userId && r.dashboardId == personalGrantForm.dashboardId
  )
  if (!existing.length) return ''
  const labels = existing.map((r) => permissionTypeLabel(r.permissionType)).join('、')
  return `该用户对此看板已有授权：${labels}`
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

function rowPermissionTypes(row) {
  const fromRow = row?.permissionTypes?.length
    ? row.permissionTypes
    : [row?.permissionType].filter(Boolean)
  return fromRow.map((t) => String(t).toUpperCase())
}
function rowHasRead(row) {
  return rowPermissionTypes(row).includes('READ')
}
function rowHasEdit(row) {
  return rowPermissionTypes(row).includes('EDIT')
}

function enterCollabRoom(row) {
  if (!row?.id) return
  if (!rowHasRead(row)) {
    ElMessage.warning('当前无协作批注权限')
    return
  }
  const full = rows.value.find((r) => r.id == row.id)
  const permissionTypes = rowPermissionTypes(row)
  openCollabRoom({
    dashboardId: row.id,
    dashboardName: row.name,
    ownerUserId: row.ownerUserId ?? full?.ownerUserId,
    isPublic: row.isPublic ?? full?.isPublic,
    permissionTypes,
    teamId: row.teamId || selectedTeamId.value,
    teamName: row.teamName || selectedTeam.value?.name,
    returnModule: 'collaboration',
    returnTab: activeTab.value === 'received' ? 'received' : 'workbench'
  })
  if (activeModule?.value !== undefined) activeModule.value = 'collaborationRoom'
}

function openDesignBoard(row) {
  if (!row?.id) return
  if (!rowHasEdit(row)) {
    ElMessage.warning('当前无设计看板权限')
    return
  }
  const full = rows.value.find((r) => r.id == row.id) || row
  gridEditorRow.value = {
    ...full,
    id: row.id,
    name: row.name || full.name,
    ownerUserId: row.ownerUserId ?? full.ownerUserId,
    isPublic: row.isPublic ?? full.isPublic,
    status: row.status ?? full.status
  }
  gridEditorVisible.value = true
}

function displayName(row) {
  return row.nickname || row.username || row.userId || '用户'
}
function formatTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
function permissionTypeLabel(type) {
  const t = String(type || '').toUpperCase()
  if (t === 'EDIT') return '编辑'
  if (t === 'READ') return '阅览+批注'
  return type || '—'
}
function focusDistributeDashboard(row) {
  if (!row?.id) return
  distributeDashboardId.value = row.id
  loadDashboardTeams()
}
function onGrantContextChange(val) {
  grantContextKey.value = val
  if (val === '__personal__') {
    grantSceneTab.value = 'personal'
    return
  }
  selectedTeamId.value = val
  grantSceneTab.value = 'team'
  onDistributeTeamChange()
}
function onDistSubTabChange(name) {
  if (name === 'grant-auth') {
    syncGrantContextKey()
    loadGrantAuthData()
  }
}
function switchDistSubTab(name) {
  if (distSubTab.value === name) return
  distSubTab.value = name
  onDistSubTabChange(name)
}
function syncGrantContextKey() {
  if (grantContextKey.value === '__personal__') return
  grantContextKey.value = selectedTeamId.value || (teams.value[0]?.id ?? null)
}
async function loadGrantAuthData() {
  loadingUnifiedGrants.value = true
  try {
    if (selectedTeamId.value) {
      await loadWorkbenchDashboards()
    }
    await loadAllPersonalGrants()
    if (distributeDashboardId.value) await loadDashboardTeams()
  } finally {
    loadingUnifiedGrants.value = false
  }
}
async function loadAllPersonalGrants() {
  const all = []
  for (const d of distributableDashboards.value) {
    if (!canDistributeBoard(d)) continue
    try {
      const perms = await fetchTeamPermissions(d.id)
      for (const p of perms || []) {
        all.push({
          dashboardId: d.id,
          dashboardName: d.name,
          ownerUserId: d.ownerUserId,
          targetLabel: p.nickname || p.username || p.userId,
          userId: p.userId,
          permissionType: p.permissionType,
          createdAt: p.createdAt,
          source: p.source
        })
      }
    } catch {
      /* 无权限的看板跳过 */
    }
  }
  personalGrantRows.value = all
}
function getGrantPermissionTypes(row) {
  if (!row) return []
  const key = row.grantType === 'TEAM'
    ? (r) => r.grantType === 'TEAM' && r.dashboardId == row.dashboardId && r.teamId == row.teamId
    : (r) => r.grantType === 'USER' && r.dashboardId == row.dashboardId && r.userId === row.userId
  return unifiedGrantRows.value.filter(key).map((r) => String(r.permissionType).toUpperCase())
}
function canManageGrantRow(row) {
  const full = rows.value.find((r) => r.id == row.dashboardId)
  if (row.grantType === 'TEAM') {
    if (full && canDistributeBoard(full)) return true
    return canManageSelectedTeam.value
  }
  if (!full) return false
  if (String(currentRole.value || '').toUpperCase() === 'ADMIN') return true
  return isBoardOwner(full) || isBoardSaveAsUser(full)
}
function viewGrantDetail(row) {
  if (!row?.dashboardId) return
  distributeDashboardId.value = row.dashboardId
  loadDashboardTeams()
}
function onDetailDashboardChange(id) {
  distributeDashboardId.value = id
  loadDashboardTeams()
}
async function grantToTeamFromPanel() {
  if (!teamGrantDashboardId.value || !selectedTeamId.value) {
    ElMessage.warning('请选择团队和看板')
    return
  }
  if (!teamGrantForm.permissionTypes.length) {
    ElMessage.warning('请至少选择一种权限')
    return
  }
  workbenchGrantDashboardId.value = teamGrantDashboardId.value
  workbenchGrantForm.permissionTypes = [...teamGrantForm.permissionTypes]
  await grantToWorkbenchTeam()
  teamGrantDashboardId.value = null
  await loadGrantAuthData()
}
async function grantToPersonalFromPanel() {
  if (!personalGrantForm.userId || !personalGrantForm.dashboardId) {
    ElMessage.warning('请选择用户和看板')
    return
  }
  if (!personalGrantForm.permissionTypes.length) {
    ElMessage.warning('请至少选择一种权限')
    return
  }
  try {
    const grantedBoardId = personalGrantForm.dashboardId
    for (const permissionType of personalGrantForm.permissionTypes) {
      await grantTeamPermission(grantedBoardId, {
        userId: personalGrantForm.userId,
        permissionType
      })
    }
    personalGrantForm.userId = ''
    personalGrantForm.dashboardId = null
    await loadAllPersonalGrants()
    if (distributeDashboardId.value == grantedBoardId) await loadUserPermissions()
    ElMessage.success('已分发至个人')
  } catch (e) {
    ElMessage.error(e.message || '授权失败')
  }
}
function quickGrantToMember(member) {
  switchDistSubTab('grant-auth')
  grantContextKey.value = '__personal__'
  grantSceneTab.value = 'personal'
  personalGrantForm.userId = member.userId
  userGrantCandidates.value = [{
    userId: member.userId,
    nickname: member.nickname,
    username: member.username
  }]
  searchUserGrantCandidates('')
}
async function changeUnifiedGrantPermission(row, newTypes) {
  const oldTypes = getGrantPermissionTypes(row)
  const nextTypes = [...new Set((newTypes || []).map((t) => String(t).toUpperCase()))]
  if (!nextTypes.length) {
    ElMessage.warning('至少选择一种权限')
    return
  }
  const toAdd = nextTypes.filter((t) => !oldTypes.includes(t))
  const toRemove = oldTypes.filter((t) => !nextTypes.includes(t))
  if (!toAdd.length && !toRemove.length) {
    ElMessage.info('权限未变更')
    return
  }
  try {
    if (row.grantType === 'TEAM') {
      for (const t of toAdd) {
        await grantDashboardToTeam(row.dashboardId, { teamId: row.teamId, permissionType: t })
      }
      for (const t of toRemove) {
        await revokeDashboardTeamGrant(row.dashboardId, row.teamId, t)
      }
    } else {
      for (const t of toAdd) {
        await grantTeamPermission(row.dashboardId, { userId: row.userId, permissionType: t })
      }
      for (const t of toRemove) {
        await revokeTeamPermission(row.dashboardId, row.userId, t)
      }
    }
    await loadGrantAuthData()
    ElMessage.success('权限已更新')
  } catch (e) {
    ElMessage.error(e.message || '更新失败')
  }
}
async function revokeUnifiedGrant(row) {
  try {
    const target = row.grantType === 'TEAM' ? `团队「${row.targetLabel}」` : `用户「${row.targetLabel}」`
    await ElMessageBox.confirm(`确定撤回看板「${row.dashboardName}」对${target}的「${permissionTypeLabel(row.permissionType)}」授权？`, '撤回授权', {
      type: 'warning',
      confirmButtonText: '撤回',
      cancelButtonText: '取消'
    })
    if (row.grantType === 'TEAM') {
      await revokeDashboardTeamGrant(row.dashboardId, row.teamId, row.permissionType)
    } else {
      await revokeTeamPermission(row.dashboardId, row.userId, row.permissionType)
    }
    await loadGrantAuthData()
    ElMessage.success('已撤回')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '撤回失败')
  }
}
async function onDistributeTeamChange() {
  if (grantContextKey.value !== '__personal__') {
    grantContextKey.value = selectedTeamId.value
  }
  memberForm.userId = ''
  memberCandidates.value = []
  workbenchGrantDashboardId.value = null
  distributeDashboardId.value = null
  dashboardTeams.value = []
  userPermissions.value = []
  await loadTeamMembers()
  await searchTeamMemberUsers('')
  await loadWorkbenchDashboards()
}
function canDelete(userId) {
  const me = currentUser.value?.userId
  return me === userId || currentUser.value?.role === 'ADMIN'
}

async function loadList() {
  loadingList.value = true
  try {
    rows.value = await fetchDistributeDashboards()
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
    permissionType: row.permissionType,
    createdAt: row.createdAt,
    grantedBy: row.grantedBy
  }
}
function mergeTeamDashboardRows(list) {
  const map = new Map()
  for (const row of list || []) {
    const item = normalizeTeamDashboard(row)
    const perm = item.permissionType
    const grant = perm
      ? { permissionType: perm, createdAt: row.createdAt, grantedBy: row.grantedBy, grantId: row.id }
      : null
    const existing = map.get(item.id)
    if (!existing) {
      map.set(item.id, {
        ...item,
        permissionTypes: perm ? [perm] : [],
        grants: grant ? [grant] : []
      })
      continue
    }
    if (perm && !existing.permissionTypes.includes(perm)) {
      existing.permissionTypes.push(perm)
      if (grant) existing.grants.push(grant)
    }
    if (row.createdAt && (!existing.createdAt || String(row.createdAt) > String(existing.createdAt))) {
      existing.createdAt = row.createdAt
    }
  }
  return [...map.values()]
}
function canManageWorkbenchGrant(row) {
  const full = rows.value.find((r) => r.id == row.id)
  if (full && canDistributeBoard(full)) return true
  return canManageSelectedTeam.value
}
async function changeWorkbenchPermission(row, newTypes) {
  if (!selectedTeamId.value || !row?.id) return
  const oldTypes = row.permissionTypes || []
  const nextTypes = [...new Set((newTypes || []).map((t) => String(t).toUpperCase()))]
  if (!nextTypes.length) {
    ElMessage.warning('至少选择一种权限')
    return
  }
  const toAdd = nextTypes.filter((t) => !oldTypes.includes(t))
  const toRemove = oldTypes.filter((t) => !nextTypes.includes(t))
  if (!toAdd.length && !toRemove.length) {
    ElMessage.info('权限未变更')
    return
  }
  try {
    for (const t of toAdd) {
      await grantDashboardToTeam(row.id, {
        teamId: selectedTeamId.value,
        permissionType: t
      })
    }
    for (const t of toRemove) {
      await revokeDashboardTeamGrant(row.id, selectedTeamId.value, t)
    }
    await loadWorkbenchDashboards()
    ElMessage.success('权限已更新')
  } catch (e) {
    ElMessage.error(e.message || '更新失败')
  }
}
async function revokeWorkbenchGrant(row) {
  if (!selectedTeamId.value || !row?.id) return
  const types = row.permissionTypes?.length ? row.permissionTypes : [row.permissionType].filter(Boolean)
  if (!types.length) return
  try {
    await ElMessageBox.confirm(
      `确定撤回看板「${row.name}」在本团队的全部分发权限？撤回后成员将无法再通过该团队访问此看板。`,
      '撤回分发',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' }
    )
    for (const t of types) {
      await revokeDashboardTeamGrant(row.id, selectedTeamId.value, t)
    }
    await loadWorkbenchDashboards()
    ElMessage.success('已撤回分发')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '撤回失败')
  }
}
async function loadWorkbenchDashboards() {
  if (!selectedTeamId.value) {
    workbenchDashboards.value = []
    return
  }
  loadingWorkbenchDashboards.value = true
  try {
    const list = await fetchTeamDashboards(selectedTeamId.value)
    workbenchDashboards.value = mergeTeamDashboardRows(list)
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
  workbenchGrantDashboardId.value = null
  await loadWorkbenchDashboards()
}
async function grantToWorkbenchTeam() {
  if (!workbenchGrantDashboardId.value || !selectedTeamId.value) {
    ElMessage.warning('请选择要分发的看板')
    return
  }
  if (!workbenchGrantForm.permissionTypes.length) {
    ElMessage.warning('请至少选择一种权限')
    return
  }
  try {
    const grantedId = workbenchGrantDashboardId.value
    for (const permissionType of workbenchGrantForm.permissionTypes) {
      await grantDashboardToTeam(grantedId, {
        teamId: selectedTeamId.value,
        permissionType
      })
    }
    workbenchGrantDashboardId.value = null
    await loadWorkbenchDashboards()
    if (activeTab.value === 'distribute' && grantedId) {
      distributeDashboardId.value = grantedId
      await loadDashboardTeams()
    }
    ElMessage.success('已分发到当前团队')
  } catch (e) {
    ElMessage.error(e.message || '分发失败')
  }
}
async function loadTeams() {
  loadingTeams.value = true
  try {
    teams.value = await fetchMyTeams()
    if (selectedTeamId.value && !teams.value.some((t) => t.id === selectedTeamId.value)) selectedTeamId.value = null
    if (!selectedTeamId.value && teams.value.length) selectedTeamId.value = teams.value[0].id
    if (grantContextKey.value !== '__personal__') {
      grantContextKey.value = selectedTeamId.value
    }
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
  } else if (activeTab.value === 'distribute') {
    await onDistributeTeamChange()
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
  if (!distributeDashboardId.value || !personalGrantForm.userId) {
    ElMessage.warning('请选择看板和用户')
    return
  }
  if (!personalGrantForm.permissionTypes.length) {
    ElMessage.warning('请至少选择一种权限')
    return
  }
  try {
    for (const permissionType of personalGrantForm.permissionTypes) {
      await grantTeamPermission(distributeDashboardId.value, {
        userId: personalGrantForm.userId,
        permissionType
      })
    }
    personalGrantForm.userId = ''
    await loadUserPermissions()
    await loadAllPersonalGrants()
    ElMessage.success('已授权用户')
  } catch (e) {
    ElMessage.error(e.message || '授权失败')
  }
}
async function revokeUserPermission(row) {
  try {
    await revokeTeamPermission(distributeDashboardId.value, row.userId, row.permissionType)
    await loadUserPermissions()
    await loadAllPersonalGrants()
    ElMessage.success('已撤销')
  } catch (e) {
    ElMessage.error(e.message || '撤销失败')
  }
}
async function revokeTeamGrant(row) {
  try {
    await revokeDashboardTeamGrant(distributeDashboardId.value, row.teamId, row.permissionType)
    await loadDashboardTeams()
    if (row.teamId === selectedTeamId.value) await loadWorkbenchDashboards()
    await loadAllPersonalGrants()
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
function onTabChange(name) {
  if (name === 'workbench') {
    loadTeams().then(() => loadWorkbenchDashboards())
  } else if (name === 'distribute') {
    loadTeams().then(async () => {
      syncGrantContextKey()
      if (selectedTeamId.value) {
        await loadTeamMembers()
      }
      if (distSubTab.value === 'grant-auth') await loadGrantAuthData()
      else if (distributeDashboardId.value) await loadDashboardTeams()
    })
  } else if (name === 'received') {
    loadReceived()
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

function applyNavIntent() {
  const nav = consumeCollabNav()
  if (!nav) return
  if (nav.tab) activeTab.value = nav.tab
  if (nav.teamId) selectedTeamId.value = Number(nav.teamId)
  if (nav.dashboardId) {
    distributeDashboardId.value = Number(nav.dashboardId)
  }
}

onMounted(async () => {
  applyNavIntent()
  await loadList()
  if (activeTab.value === 'workbench') {
    await loadTeams()
    await loadWorkbenchDashboards()
  } else if (activeTab.value === 'distribute') {
    await loadTeams()
    syncGrantContextKey()
    if (selectedTeamId.value) {
      await loadTeamMembers()
    }
    if (distSubTab.value === 'grant-auth') await loadGrantAuthData()
    else if (distributeDashboardId.value) await loadDashboardTeams()
  } else if (activeTab.value === 'received') {
    await loadReceived()
  }
})
</script>

<style scoped>
.biz-collab-page {
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 64px - 40px);
  margin: -4px -4px 0;
  padding: 0 4px 0;
  box-sizing: border-box;
}
.biz-collab-page.has-grant-dock .collab-page-body {
  padding-bottom: 8px;
}
.collab-page-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: 16px;
}
.collab-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.collab-head h1 { margin: 0 0 6px; font-size: 22px; }
.collab-head p { margin: 0; color: #909399; font-size: 14px; }
.collab-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }

/* —— 团队与分发 · 二级导航 —— */
.dist-sub-nav {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 14px;
  padding: 6px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}
.dist-sub-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s, border-color 0.15s, box-shadow 0.15s;
}
.dist-sub-nav-item:hover:not(.active) {
  background: rgba(255, 255, 255, 0.55);
}
.dist-sub-nav-item.active {
  background: #fff;
  border-color: #dbeafe;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.1);
}
.dist-sub-nav-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
}
.dist-sub-nav-icon--team {
  background: #eef2ff;
  color: #4338ca;
}
.dist-sub-nav-icon--grant {
  background: #fff7ed;
  color: #c2410c;
}
.dist-sub-nav-item.active .dist-sub-nav-icon--team {
  background: #dbeafe;
  color: #1d4ed8;
}
.dist-sub-nav-item.active .dist-sub-nav-icon--grant {
  background: #ffedd5;
  color: #ea580c;
}
.dist-sub-nav-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.dist-sub-nav-text strong {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
  line-height: 1.3;
}
.dist-sub-nav-text em {
  font-size: 12px;
  font-style: normal;
  color: #94a3b8;
  line-height: 1.3;
}
.dist-sub-nav-item.active .dist-sub-nav-text strong {
  color: #0f172a;
}
.dist-sub-nav-item.active .dist-sub-nav-text em {
  color: #64748b;
}
.dist-sub-panel {
  animation: dist-sub-fade 0.18s ease;
}
@keyframes dist-sub-fade {
  from { opacity: 0.6; transform: translateY(2px); }
  to { opacity: 1; transform: translateY(0); }
}
@media (max-width: 640px) {
  .dist-sub-nav { grid-template-columns: 1fr; }
}

/* —— 底部固定分发操作栏 —— */
.collab-grant-dock {
  flex-shrink: 0;
  z-index: 20;
  border-top: 1px solid #c6e2ff;
  background: linear-gradient(180deg, #fafcff 0%, #f0f7ff 100%);
  box-shadow: 0 -4px 20px rgba(64, 158, 255, 0.12);
  padding: 12px 16px 14px;
}
.collab-grant-dock-inner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  max-width: 1200px;
}
.collab-grant-dock-context {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 120px;
}
.collab-grant-dock-label {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.collab-grant-dock-team {
  font-size: 12px;
  color: #909399;
}
.collab-grant-dock-board {
  flex: 1;
  min-width: 240px;
  max-width: 480px;
}
.collab-grant-dock-perm {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}
.collab-grant-dock-perm :deep(.el-checkbox) {
  margin-right: 8px;
}
.collab-grant-dock-perm :deep(.el-checkbox:last-child) {
  margin-right: 0;
}
.collab-perm-checks-inline {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}
.collab-perm-checks-inline :deep(.el-checkbox) {
  margin-right: 0;
}
.collab-grant-dock-submit {
  min-width: 160px;
  font-weight: 600;
  padding-left: 20px;
  padding-right: 20px;
}
.collab-grant-dock-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}

/* —— 协作工作台 / 团队与分发 共用 —— */
.wb-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 10px 14px;
  background: linear-gradient(90deg, #f0f7ff 0%, #f8fafc 100%);
  border: 1px solid #e4eaf2;
  border-radius: 8px;
  flex-wrap: wrap;
}
.wb-step {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #909399;
  font-size: 13px;
}
.wb-step.done { color: #303133; font-weight: 500; }
.wb-step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #dcdfe6;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}
.wb-step.done .wb-step-num { background: #409eff; }
.wb-step-arrow { color: #c0c4cc; font-size: 12px; }

.wb-filter-card { margin-bottom: 12px; }
.wb-filter-row { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.wb-field-label { font-size: 13px; color: #606266; font-weight: 500; white-space: nowrap; }
.wb-team-select { width: 220px; }
.wb-filter-meta { font-size: 13px; color: #909399; }
.wb-filter-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-left: auto; }

.wb-active-card { margin-bottom: 12px; border-color: #b3d8ff; }
.wb-active-bar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.wb-active-info { min-width: 0; }
.wb-active-name { margin: 0 0 8px; font-size: 16px; font-weight: 600; }
.wb-active-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; font-size: 13px; color: #606266; }
.wb-active-actions { display: flex; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }

.select-card { margin-bottom: 16px; }
.select-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.collab-layout { display: grid; grid-template-columns: minmax(0, 1fr) 400px; gap: 16px; align-items: stretch; }
.collab-recent-strip {
  display: flex; align-items: center; gap: 8px; margin-top: 10px;
  overflow-x: auto; padding-bottom: 4px;
}
.collab-recent-label { font-size: 12px; color: #64748b; white-space: nowrap; flex-shrink: 0; }
.collab-recent-card {
  flex: 0 0 auto; max-width: 220px; border: 1px solid #e2e8f0; border-left-width: 3px; border-radius: 6px;
  padding: 8px 10px; text-align: left; cursor: pointer; background: #fff;
  display: flex; flex-direction: column; gap: 4px;
  transition: box-shadow 0.15s ease, border-color 0.15s ease;
}
.collab-recent-card.active {
  border-color: #2563eb;
  border-left-color: #2563eb;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.12);
}
.collab-recent-tag { font-size: 11px; font-weight: 600; color: #64748b; }
.export-dialog-lead { margin: 0 0 12px; font-size: 13px; color: #64748b; line-height: 1.5; }
.side-panel--collab.el-card { border: 1px solid #e2e8f0; border-radius: 10px; }
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

/* —— 团队与分发 —— */
.dist-team-desc {
  margin: 12px 0 0;
  padding: 8px 10px;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  border-radius: 6px;
  line-height: 1.5;
}
.dist-members-card { margin-bottom: 12px; }
.dist-member-form { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 12px; }
.dist-member-user-select { min-width: 240px; flex: 1; max-width: 360px; }
.dist-member-role-select { width: 140px; }
.dist-auth-card { margin-bottom: 12px; }
.received-card { margin-bottom: 12px; }
.dist-auth-lead { margin: 0 0 12px; font-size: 13px; line-height: 1.5; }
.dist-auth-filter { margin-bottom: 8px; }
.dist-auth-board-select { width: 100%; max-width: 480px; }
.dist-user-grant-select { min-width: 200px; flex: 1; }
.wb-list-head { display: flex; align-items: center; gap: 8px; }
.wb-list-title { font-weight: 600; font-size: 15px; }
.wb-list-count { font-size: 12px; color: #909399; }
.grant-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.distribute-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; font-size: 12px; color: #606266; }
.distribute-hint { color: #909399; font-size: 13px; margin: 0 0 10px; }

@media (max-width: 1100px) {
  .collab-layout { grid-template-columns: 1fr; }
  .collab-grant-dock-inner { flex-direction: column; align-items: stretch; }
  .collab-grant-dock-board { max-width: none; }
  .collab-grant-dock-submit { width: 100%; }
  .wb-filter-actions { margin-left: 0; width: 100%; }
  .wb-active-bar { flex-direction: column; }
  .wb-active-actions { width: 100%; }
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
