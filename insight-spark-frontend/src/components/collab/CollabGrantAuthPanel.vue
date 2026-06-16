<template>
  <div class="grant-auth-panel">
    <el-card shadow="never" class="grant-top-card">
      <div class="grant-top-row">
        <label class="wb-field-label">授权上下文</label>
        <el-select
          :model-value="grantContextKey"
          filterable
          class="grant-context-select"
          placeholder="选择团队或个人授权模式"
          @update:model-value="$emit('update:grant-context-key', $event)"
        >
          <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
          <el-option label="个人授权模式" value="__personal__" />
        </el-select>
        <span class="grant-top-hint muted">分发看板 → 管理权限</span>
        <template v-if="selectedTeam && grantContextKey !== '__personal__'">
          <el-tag size="small" effect="plain">{{ selectedTeam.myRole || '成员' }}</el-tag>
          <span class="wb-filter-meta">{{ selectedTeam.memberCount || 0 }} 名成员</span>
        </template>
      </div>
    </el-card>

    <el-card shadow="never" class="grant-op-card">
      <div class="grant-scene-tabs">
        <button
          type="button"
          :class="['grant-scene-btn', { active: grantSceneTab === 'team' }]"
          :disabled="grantContextKey === '__personal__'"
          @click="$emit('update:grant-scene-tab', 'team')"
        >
          分发至团队
        </button>
        <button
          type="button"
          :class="['grant-scene-btn', { active: grantSceneTab === 'personal' }]"
          @click="$emit('update:grant-scene-tab', 'personal')"
        >
          分发至个人
        </button>
      </div>

      <div v-if="grantSceneTab === 'team'" class="grant-form-block">
        <p v-if="grantContextKey === '__personal__'" class="grant-form-tip">请先在上方切换为具体团队，再进行团队分发。</p>
        <template v-else-if="selectedTeamId">
          <div class="grant-form-row">
            <el-select
              :model-value="teamGrantDashboardId"
              filterable
              clearable
              placeholder="选择要分发的看板"
              class="grant-form-board"
              :disabled="!teamGrantCandidates.length"
              @update:model-value="$emit('update:team-grant-dashboard-id', $event)"
            >
              <el-option
                v-for="d in teamGrantCandidates"
                :key="d.id"
                :label="formatDashboardLabel(d)"
                :value="d.id"
              />
            </el-select>
            <el-checkbox-group
              :model-value="teamGrantPermissionTypes"
              class="collab-perm-checks collab-perm-checks-inline"
              @update:model-value="$emit('update:team-grant-permission-types', $event)"
            >
              <el-checkbox label="READ">阅览+批注</el-checkbox>
              <el-checkbox label="EDIT">编辑</el-checkbox>
            </el-checkbox-group>
            <el-button
              type="primary"
              :disabled="!teamGrantDashboardId || !teamGrantPermissionTypes.length"
              @click="$emit('grant-to-team')"
            >
              分发到当前团队
            </el-button>
          </div>
          <p v-if="teamGrantAlreadyHint" class="grant-already-hint">{{ teamGrantAlreadyHint }}</p>
          <p v-if="!teamGrantCandidates.length" class="grant-form-tip">暂无可分发的看板（可能已全部授权给该团队）</p>
        </template>
        <p v-else class="grant-form-tip">请先选择团队。</p>
      </div>

      <div v-else class="grant-form-block">
        <div class="grant-form-row">
          <el-select
            :model-value="personalGrantUserId"
            filterable
            remote
            clearable
            placeholder="搜索 / 选择用户"
            class="grant-form-user"
            :remote-method="(kw) => $emit('search-grant-users', kw)"
            :loading="userGrantSearchLoading"
            @update:model-value="$emit('update:personal-grant-user-id', $event)"
          >
            <el-option
              v-for="u in userGrantCandidates"
              :key="u.userId"
              :label="`${u.nickname || u.username} (${u.userId})`"
              :value="u.userId"
            />
          </el-select>
          <el-select
            :model-value="personalGrantDashboardId"
            filterable
            clearable
            placeholder="选择看板"
            class="grant-form-board"
            @update:model-value="$emit('update:personal-grant-dashboard-id', $event)"
          >
            <el-option
              v-for="d in distributableDashboards"
              :key="d.id"
              :label="formatDashboardLabel(d)"
              :value="d.id"
            />
          </el-select>
          <el-checkbox-group
            :model-value="personalGrantPermissionTypes"
            class="collab-perm-checks collab-perm-checks-inline"
            @update:model-value="$emit('update:personal-grant-permission-types', $event)"
          >
            <el-checkbox label="READ">阅览+批注</el-checkbox>
            <el-checkbox label="EDIT">编辑</el-checkbox>
          </el-checkbox-group>
          <el-button
            type="primary"
            plain
            :disabled="!personalGrantUserId || !personalGrantDashboardId || !personalGrantPermissionTypes.length"
            @click="$emit('grant-to-personal')"
          >
            分发至个人
          </el-button>
        </div>
        <p v-if="personalGrantAlreadyHint" class="grant-already-hint">{{ personalGrantAlreadyHint }}</p>
      </div>
    </el-card>

    <el-card shadow="never" class="grant-list-card">
      <template #header>
        <div class="wb-list-head">
          <span class="wb-list-title">已授权列表</span>
          <span v-if="filteredGrantRows.length" class="wb-list-count">{{ filteredGrantRows.length }} 项</span>
        </div>
      </template>
      <el-input
        :model-value="grantListKeyword"
        clearable
        placeholder="搜索看板名、团队名、用户名"
        class="grant-list-search"
        @update:model-value="$emit('update:grant-list-keyword', $event)"
      />
      <el-table
        v-loading="loadingGrants"
        :data="filteredGrantRows"
        size="default"
        class="collab-data-table mt12"
        empty-text="暂无授权记录"
      >
        <el-table-column prop="dashboardName" label="看板名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="授权类型" width="88">
          <template #default="{ row }">
            <el-tag size="small" :type="row.grantType === 'TEAM' ? 'primary' : 'warning'" effect="plain">
              {{ row.grantType === 'TEAM' ? '团队' : '个人' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetLabel" label="授权对象" min-width="100" show-overflow-tooltip />
        <el-table-column label="权限" width="108">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ permissionTypeLabel(row.permissionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分发时间" width="168" show-overflow-tooltip>
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="所有者" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="collab-cell-ellipsis">{{ row.ownerUserId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="220" width="220" fixed="right" class-name="col-actions">
          <template #default="{ row }">
            <div class="collab-row-actions" @click.stop>
              <el-button link type="primary" size="small" @click="$emit('view-grant-detail', row)">查看授权</el-button>
              <el-popover
                v-if="canManageGrantRow(row)"
                trigger="click"
                placement="bottom-end"
                :width="220"
                @show="initPermDraft(row)"
              >
                <template #reference>
                  <el-button link type="primary" size="small">修改权限</el-button>
                </template>
                <div class="collab-perm-popover">
                  <p class="collab-perm-popover-title">选择权限（可多选）</p>
                  <el-checkbox-group v-model="permEditDraft" class="collab-perm-checks">
                    <el-checkbox label="READ">阅览+批注</el-checkbox>
                    <el-checkbox label="EDIT">编辑</el-checkbox>
                  </el-checkbox-group>
                  <div class="collab-perm-popover-actions">
                    <el-button size="small" type="primary" @click="confirmPermEdit(row)">保存</el-button>
                  </div>
                </div>
              </el-popover>
              <el-button v-if="canManageGrantRow(row)" link type="danger" size="small" @click="$emit('revoke-grant', row)">撤回</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="grant-detail-card">
      <template #header>
        <button type="button" class="grant-detail-toggle" @click="detailExpanded = !detailExpanded">
          <span class="grant-detail-toggle-title">看板授权详情</span>
          <span class="grant-detail-toggle-action">{{ detailExpanded ? '收起' : '展开' }}</span>
        </button>
      </template>
      <div v-show="detailExpanded" class="grant-detail-body">
        <p class="dist-auth-lead muted">选择看板，查看该看板已授权的团队与个人明细。</p>
        <div class="dist-auth-filter">
          <el-select
            :model-value="detailDashboardId"
            filterable
            clearable
            placeholder="选择看板"
            class="dist-auth-board-select"
            @update:model-value="$emit('update:detail-dashboard-id', $event)"
          >
            <el-option
              v-for="d in distributableDashboards"
              :key="d.id"
              :label="formatDashboardLabel(d)"
              :value="d.id"
            />
          </el-select>
        </div>
        <div v-if="detailDashboard" class="distribute-meta">
          <el-tag size="small">{{ boardVisibilityLabel(detailDashboard.isPublic) }}</el-tag>
          <el-tag size="small" :type="isBoardPublished(detailDashboard) ? 'success' : 'warning'">
            {{ isBoardPublished(detailDashboard) ? '已发布' : '待发布' }}
          </el-tag>
          <span>所有者 {{ detailDashboard.ownerUserId || '—' }}</span>
        </div>

        <el-table
          v-if="detailDashboardId"
          :data="dashboardTeams"
          size="default"
          class="collab-data-table mt12"
          empty-text="该看板暂未授权任何团队"
        >
          <el-table-column prop="teamName" label="已授权团队" min-width="120" show-overflow-tooltip />
          <el-table-column label="权限" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ permissionTypeLabel(row.permissionType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="授权时间" width="168" show-overflow-tooltip>
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="88" class-name="col-actions">
            <template #default="{ row }">
              <div class="collab-row-actions">
                <el-button link type="danger" size="small" @click="$emit('revoke-team-detail', row)">撤销</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <el-table
          v-if="detailDashboardId"
          :data="userPermissions"
          size="default"
          class="collab-data-table mt12"
          empty-text="该看板暂无个人授权"
        >
          <el-table-column label="用户" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.nickname || row.username || row.userId }}</template>
          </el-table-column>
          <el-table-column label="权限" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ permissionTypeLabel(row.permissionType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="80" />
          <el-table-column label="操作" width="88" class-name="col-actions">
            <template #default="{ row }">
              <div class="collab-row-actions">
                <el-button link type="danger" size="small" @click="$emit('revoke-user-detail', row)">撤销</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import '../../styles/collab-table.css'

const props = defineProps({
  teams: { type: Array, default: () => [] },
  selectedTeam: { type: Object, default: null },
  selectedTeamId: { type: [Number, String, null], default: null },
  grantContextKey: { type: [Number, String], default: null },
  grantSceneTab: { type: String, default: 'team' },
  grantListKeyword: { type: String, default: '' },
  filteredGrantRows: { type: Array, default: () => [] },
  loadingGrants: { type: Boolean, default: false },
  distributableDashboards: { type: Array, default: () => [] },
  teamGrantCandidates: { type: Array, default: () => [] },
  teamGrantDashboardId: { type: [Number, String, null], default: null },
  teamGrantPermissionTypes: { type: Array, default: () => ['READ'] },
  teamGrantAlreadyHint: { type: String, default: '' },
  personalGrantUserId: { type: String, default: '' },
  personalGrantDashboardId: { type: [Number, String, null], default: null },
  personalGrantPermissionTypes: { type: Array, default: () => ['READ'] },
  personalGrantAlreadyHint: { type: String, default: '' },
  userGrantCandidates: { type: Array, default: () => [] },
  userGrantSearchLoading: { type: Boolean, default: false },
  detailDashboardId: { type: [Number, String, null], default: null },
  detailDashboard: { type: Object, default: null },
  dashboardTeams: { type: Array, default: () => [] },
  userPermissions: { type: Array, default: () => [] },
  formatDashboardLabel: { type: Function, required: true },
  formatTime: { type: Function, required: true },
  permissionTypeLabel: { type: Function, required: true },
  boardVisibilityLabel: { type: Function, required: true },
  isBoardPublished: { type: Function, required: true },
  canManageGrantRow: { type: Function, required: true },
  getGrantPermissionTypes: { type: Function, required: true }
})

const emit = defineEmits([
  'update:grant-context-key',
  'update:grant-scene-tab',
  'update:grant-list-keyword',
  'update:team-grant-dashboard-id',
  'update:team-grant-permission-types',
  'update:personal-grant-user-id',
  'update:personal-grant-dashboard-id',
  'update:personal-grant-permission-types',
  'update:detail-dashboard-id',
  'search-grant-users',
  'grant-to-team',
  'grant-to-personal',
  'view-grant-detail',
  'change-grant-permission',
  'revoke-grant',
  'revoke-team-detail',
  'revoke-user-detail'
])

const detailExpanded = ref(false)
const permEditDraft = ref([])

watch(
  () => props.detailDashboardId,
  (id) => {
    if (id) detailExpanded.value = true
  }
)

function initPermDraft(row) {
  permEditDraft.value = [...props.getGrantPermissionTypes(row)]
}

function confirmPermEdit(row) {
  if (!permEditDraft.value.length) {
    ElMessage.warning('至少选择一种权限')
    return
  }
  emit('change-grant-permission', row, [...permEditDraft.value])
}
</script>

<style scoped>
.grant-auth-panel { display: flex; flex-direction: column; gap: 12px; }
.grant-top-card, .grant-op-card, .grant-list-card { margin-bottom: 0; }
.grant-top-row, .grant-form-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.grant-context-select { width: 220px; }
.grant-top-hint { font-size: 13px; }
.grant-scene-tabs {
  display: inline-flex;
  gap: 0;
  padding: 3px;
  margin-bottom: 12px;
  background: #e2e8f0;
  border-radius: 8px;
}
.grant-scene-btn {
  border: none;
  background: transparent;
  padding: 7px 16px;
  border-radius: 6px;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
}
.grant-scene-btn.active { background: #fff; color: #0f172a; font-weight: 500; box-shadow: 0 1px 2px rgba(15,23,42,.06); }
.grant-scene-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.grant-form-block { margin-top: 4px; }
.grant-form-board { flex: 1; min-width: 200px; max-width: 360px; }
.grant-form-user { width: 220px; }
.grant-form-tip, .grant-already-hint { margin: 8px 0 0; font-size: 12px; color: #909399; }
.grant-already-hint { color: #e6a23c; }
.grant-list-search { max-width: 320px; }
.grant-detail-card { margin-bottom: 0; }
.grant-detail-card :deep(.el-card__header) {
  padding: 12px 16px;
  background: #fafbfc;
  border-bottom: 1px solid #ebeef5;
}
.grant-detail-card :deep(.el-card__body) {
  padding: 16px;
}
.grant-detail-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.grant-detail-toggle-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.grant-detail-toggle-action {
  font-size: 12px;
  color: #409eff;
  flex-shrink: 0;
}
.grant-detail-body { width: 100%; }
.collab-perm-popover-title { margin: 0 0 8px; font-size: 13px; color: #606266; }
.collab-perm-popover-actions { margin-top: 12px; text-align: right; }
.dist-auth-lead { margin: 0 0 12px; }
.dist-auth-filter { margin-bottom: 12px; }
.dist-auth-board-select { width: 100%; }
.distribute-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px; color: #606266; }
.mt12 { margin-top: 12px; }
.muted { color: #909399; }
.wb-list-head { display: flex; align-items: center; gap: 8px; }
.wb-list-title { font-weight: 600; font-size: 15px; }
.wb-list-count { font-size: 12px; color: #909399; }
.wb-field-label { font-size: 13px; color: #606266; font-weight: 500; white-space: nowrap; }
.wb-filter-meta { font-size: 13px; color: #909399; }
</style>
