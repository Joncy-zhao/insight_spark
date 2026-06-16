<template>
  <el-card shadow="never" class="wb-list-card">
    <template #header>
      <div class="wb-list-head">
        <span class="wb-list-title">{{ title }}</span>
        <span v-if="dashboards.length" class="wb-list-count">{{ dashboards.length }} 项</span>
      </div>
    </template>

    <el-table
      v-loading="loading"
      :data="dashboards"
      row-key="id"
      highlight-current-row
      :current-row-key="selectedId"
      empty-text=""
      class="collab-data-table wb-board-table"
      @row-click="onRowClick"
    >
      <el-table-column label="看板名称" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="collab-board-name">
            <strong>{{ row.name }}</strong>
            <el-tag v-if="showCollabTag && selectedId == row.id" size="small" type="primary" effect="plain">
              协作中
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="权限" width="168">
        <template #default="{ row }">
          <div class="collab-perm-tags">
            <el-tag v-if="hasPermission(row, 'READ')" size="small" type="info" effect="plain">阅览+批注</el-tag>
            <el-tag v-if="hasPermission(row, 'EDIT')" size="small" type="warning" effect="plain">编辑</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="分发时间" width="168" show-overflow-tooltip>
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="所有者" min-width="120" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="collab-cell-ellipsis">{{ row.ownerUserId || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="240" width="260" fixed="right" class-name="col-actions">
        <template #default="{ row }">
          <div class="collab-row-actions">
            <template v-if="actionMode === 'permission'">
              <el-button
                v-if="hasPermission(row, 'READ')"
                type="primary"
                link
                @click.stop="$emit('enter', row)"
              >
                进入协作
              </el-button>
              <el-button
                v-if="hasPermission(row, 'EDIT')"
                type="primary"
                link
                @click.stop="$emit('design', row)"
              >
                设计看板
              </el-button>
            </template>
            <el-button v-else type="primary" link @click.stop="$emit('enter', row)">{{ enterLabel }}</el-button>
            <template v-if="canManage(row)">
              <el-popover trigger="click" placement="bottom-end" :width="220" @show="initPermEdit(row)">
                <template #reference>
                  <el-button link type="primary" @click.stop>修改权限</el-button>
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
              <el-button link type="danger" @click.stop="$emit('revoke', row)">撤回</el-button>
            </template>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && !dashboards.length" class="wb-empty-list">
      <el-empty :description="emptyDescription">
        <template v-if="emptyHint" #default>
          <p class="wb-empty-hint">{{ emptyHint }}</p>
        </template>
      </el-empty>
    </div>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import '../../styles/collab-table.css'

const props = defineProps({
  title: { type: String, default: '已分发看板' },
  dashboards: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  selectedId: { type: [Number, String, null], default: null },
  showCollabTag: { type: Boolean, default: false },
  /** permission：按 READ→进入协作、EDIT→设计看板；single：单一 enterLabel 按钮 */
  actionMode: { type: String, default: 'permission' },
  enterLabel: { type: String, default: '进入协作' },
  emptyDescription: { type: String, default: '当前团队暂无已分发看板' },
  emptyHint: { type: String, default: '' },
  formatTime: { type: Function, required: true },
  canManage: { type: Function, default: () => false }
})
const emit = defineEmits(['enter', 'design', 'revoke', 'change-permission'])

function rowPermissionTypes(row) {
  const fromRow = row?.permissionTypes?.length
    ? row.permissionTypes
    : [row?.permissionType].filter(Boolean)
  return fromRow.map((t) => String(t).toUpperCase())
}

function hasPermission(row, type) {
  return rowPermissionTypes(row).includes(String(type).toUpperCase())
}

function onRowClick(row) {
  if (props.actionMode === 'single') {
    emit('enter', row)
    return
  }
  if (hasPermission(row, 'READ')) {
    emit('enter', row)
  } else if (hasPermission(row, 'EDIT')) {
    emit('design', row)
  }
}

const permEditDraft = ref([])

function initPermEdit(row) {
  permEditDraft.value = [...rowPermissionTypes(row)]
}

function confirmPermEdit(row) {
  if (!permEditDraft.value.length) {
    ElMessage.warning('至少选择一种权限')
    return
  }
  emit('change-permission', row, [...permEditDraft.value])
}
</script>

<style scoped>
.wb-list-card { margin-bottom: 12px; }
.wb-list-head { display: flex; align-items: center; gap: 8px; }
.wb-list-title { font-weight: 600; font-size: 15px; }
.wb-list-count { font-size: 12px; color: #909399; }
.wb-board-table :deep(.el-table__row) { cursor: pointer; }
.wb-empty-list { padding: 8px 0 16px; }
.wb-empty-hint { margin: 0; font-size: 13px; color: #909399; text-align: center; }
.collab-perm-popover-title {
  margin: 0 0 8px;
  font-size: 13px;
  color: #606266;
}
.collab-perm-popover-actions {
  margin-top: 12px;
  text-align: right;
}
</style>
