<template>
  <section class="permission-layout">
    <div class="permission-cards">
      <div class="panel metric-panel">
        <div class="metric-label">当前用户</div>
        <div class="metric-value">{{ permissionOverview.userId || '-' }}</div>
        <div class="metric-note">{{ permissionOverview.roleLevel || permissionOverview.role || '-' }}</div>
      </div>
      <div class="panel metric-panel">
        <div class="metric-label">本人数据表</div>
        <div class="metric-value">{{ permissionOverview.ownedTableCount ?? 0 }}</div>
        <div class="metric-note">上传即拥有查看/编辑权</div>
      </div>
      <div class="panel metric-panel">
        <div class="metric-label">授权数据表</div>
        <div class="metric-value">{{ permissionOverview.grantedTableCount ?? 0 }}</div>
        <div class="metric-note">审批通过后可访问</div>
      </div>
      <div class="panel metric-panel">
        <div class="metric-label">官方库授权</div>
        <div class="metric-value">{{ permissionOverview.officialDatasourceCount ?? 0 }}</div>
        <div class="metric-note">含有效期控制</div>
      </div>
      <div class="panel metric-panel">
        <div class="metric-label">待审批申请</div>
        <div class="metric-value">{{ permissionOverview.pendingRequestCount ?? 0 }}</div>
        <div class="metric-note">管理员端处理</div>
      </div>
    </div>

    <div v-if="activeModule === 'permission'" class="workspace-grid permission-grid">
      <div class="panel permission-info-panel">
        <div class="panel-header">
          <div>
            <h2>权限等级说明</h2>
            <p>{{ permissionOverview.roleDescription || '展示当前角色、菜单范围与权限继承关系。' }}</p>
          </div>
          <el-button @click="loadPermissionCenter">刷新</el-button>
        </div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="当前角色">{{ permissionOverview.roleLevel || permissionOverview.role || '-' }}</el-descriptions-item>
          <el-descriptions-item label="权限继承">{{ permissionOverview.inheritance || '-' }}</el-descriptions-item>
          <el-descriptions-item label="行级隔离">{{ permissionOverview.rowPolicy || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="tag-section">
          <span class="section-label">可操作菜单</span>
          <el-tag v-for="item in permissionOverview.menuPermissions || []" :key="item" type="info">
            {{ item }}
          </el-tag>
        </div>
      </div>

      <div class="panel compliance-panel">
        <div class="panel-header">
          <div>
            <h2>合规提示</h2>
            <p>企业数据使用规范、隐私保护说明与违规后果。</p>
          </div>
        </div>
        <ul class="compliance-list">
          <li v-for="tip in permissionOverview.complianceTips || []" :key="tip">{{ tip }}</li>
        </ul>
        <el-collapse class="compliance-doc">
          <el-collapse-item title="查看完整企业数据安全合规文档" name="doc">
            <p>数据仅可用于申请时声明的业务目的；禁止将授权数据转发到未授权人员、群组或外部系统。</p>
            <p>含手机号、身份证、金额等敏感字段的结果默认脱敏展示，导出、截图和会议材料需遵守最小必要原则。</p>
            <p>系统保留访问、导出、审批与异常查询审计记录，违规行为将进入合规处理流程。</p>
          </el-collapse-item>
        </el-collapse>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <h2>可访问数据源</h2>
            <p>{{ permissionOverview.dataScope }}</p>
          </div>
        </div>
        <el-table :data="accessibleTables" height="320" empty-text="暂无可访问数据表">
          <el-table-column prop="displayName" label="数据表" min-width="150" />
          <el-table-column prop="accessSource" label="来源" width="110">
            <template #default="{ row }">
              <el-tag :type="row.accessSource === 'OWNER' ? 'success' : 'warning'" size="small">
                {{ row.accessSource === 'OWNER' ? '本人上传' : row.accessSource === 'ADMIN' ? '管理员' : '审批授权' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="permissionScope" label="权限范围" width="120" />
          <el-table-column prop="expireAt" label="有效期" min-width="150">
            <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
          </el-table-column>
          <el-table-column prop="rowCount" label="行数" width="90" />
          <el-table-column prop="fieldCount" label="字段" width="90" />
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <h2>官方授权数据源</h2>
            <p>展示管理员已绑定给当前用户或角色的官方库表。</p>
          </div>
        </div>
        <el-table :data="accessibleOfficialTables" height="320" empty-text="暂无官方库授权">
          <el-table-column prop="displayName" label="官方数据表" min-width="190" />
          <el-table-column prop="datasourceName" label="数据源" min-width="130" />
          <el-table-column prop="permissionScope" label="权限范围" width="120" />
          <el-table-column prop="expireAt" label="有效期" min-width="150">
            <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel permission-request-panel">
        <div class="panel-header">
          <div>
            <h2>权限申请</h2>
            <p>可申请官方库、他人数据表或公共看板的查看/编辑权限。</p>
          </div>
        </div>
        <el-form label-position="top">
          <el-form-item label="申请资源">
            <el-select
              v-model="permissionForm.tableName"
              placeholder="选择可申请的数据表、官方库或公共看板"
              class="full-width"
              filterable
              @change="handlePermissionResourceChange"
            >
              <el-option
                v-for="resource in requestableTables"
                :key="resource.tableName"
                :label="`${resource.displayName}（${resourceLabel(resource.resourceType || resource.sourceType)} / ${resource.ownerId || '-'}）`"
                :value="resource.tableName"
              />
            </el-select>
          </el-form-item>
          <div class="request-form-grid">
            <el-form-item label="权限范围">
              <el-radio-group v-model="permissionForm.permissionType">
                <el-radio-button label="READ">查看</el-radio-button>
                <el-radio-button label="EDIT">编辑</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="有效期">
              <el-date-picker
                v-model="permissionForm.expireAt"
                class="full-width"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="不选则长期"
              />
            </el-form-item>
          </div>
          <el-form-item label="申请权限范围说明">
            <el-input
              v-model="permissionForm.scopeDesc"
              placeholder="例如：华东区域销售看板查看、月度经营例会使用"
            />
          </el-form-item>
          <el-form-item label="申请理由">
            <el-input
              v-model="permissionForm.reason"
              type="textarea"
              :rows="4"
              placeholder="说明业务场景、使用周期、输出对象和是否涉及敏感字段"
            />
          </el-form-item>
          <div class="request-form-grid">
            <el-form-item label="申请附件">
              <el-upload
                :auto-upload="false"
                :limit="1"
                :on-change="handlePermissionAttachmentChange"
                :show-file-list="false"
              >
                <el-button>选择附件</el-button>
              </el-upload>
              <div class="attachment-name">
                {{ permissionForm.attachmentName || '未选择附件' }}
                <span v-if="permissionForm.attachmentSize">（{{ formatFileSize(permissionForm.attachmentSize) }}）</span>
              </div>
            </el-form-item>
            <el-form-item label="附件说明">
              <el-input v-model="permissionForm.attachmentNote" placeholder="例如：工作证明、会议需求、项目授权邮件" />
            </el-form-item>
          </div>
          <el-button type="primary" :disabled="!permissionForm.tableName || !permissionForm.reason" @click="submitPermissionRequest">
            提交申请
          </el-button>
        </el-form>
        <el-alert class="permission-tip" type="info" :closable="false" show-icon :title="permissionOverview.sensitiveRule || '敏感字段默认标记，查询展示默认脱敏。'" />
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <h2>敏感字段权限</h2>
            <p>列出可访问敏感字段与不可访问字段原因。</p>
          </div>
        </div>
        <el-table :data="sensitiveFieldPermissions" height="420" empty-text="暂无敏感字段">
          <el-table-column prop="sourceType" label="来源" width="100">
            <template #default="{ row }">{{ resourceLabel(row.sourceType) }}</template>
          </el-table-column>
          <el-table-column prop="tableDisplayName" label="数据表" min-width="170" show-overflow-tooltip />
          <el-table-column prop="displayName" label="敏感字段" min-width="130" />
          <el-table-column prop="columnName" label="物理字段" width="120" />
          <el-table-column prop="accessMode" label="访问方式" width="120">
            <template #default="{ row }">
              <el-tag :type="row.accessMode === 'NO_ACCESS' ? 'danger' : 'success'" size="small">
                {{ row.accessMode === 'NO_ACCESS' ? '不可访问' : '脱敏展示' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
        </el-table>
      </div>

      <div class="panel request-history-panel">
        <div class="panel-header">
          <div>
            <h2>我的申请进度</h2>
            <p>查看审批状态；已驳回申请可调整内容后重新提交。</p>
          </div>
        </div>
        <el-table :data="myPermissionRequests" height="360" empty-text="暂无申请记录">
          <el-table-column prop="displayName" label="申请资源" min-width="170" show-overflow-tooltip />
          <el-table-column prop="resourceType" label="类型" width="100">
            <template #default="{ row }">{{ resourceLabel(row.resourceType) }}</template>
          </el-table-column>
          <el-table-column prop="permissionType" label="范围" width="90">
            <template #default="{ row }">{{ permissionTypeLabel(row.permissionType) }}</template>
          </el-table-column>
          <el-table-column prop="expireAt" label="有效期" min-width="140">
            <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="permissionStatusType(row.status)" size="small">{{ permissionStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="理由" min-width="180" show-overflow-tooltip />
          <el-table-column prop="reviewerId" label="审批人" width="110" />
          <el-table-column prop="reviewedAt" label="审批时间" min-width="150" />
          <el-table-column prop="reviewComment" label="审批意见" min-width="170" show-overflow-tooltip />
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'REJECTED'" link type="primary" @click="prefillPermissionRequest(row)">
                重新提交
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div v-if="activeModule === 'permissionAdmin'" class="panel admin-approval-panel">
      <div class="panel-header">
        <div>
          <h2>管理员审批</h2>
          <p>处理普通用户的数据表、官方库与公共看板权限申请。</p>
        </div>
        <el-select v-model="adminRequestStatus" placeholder="状态" clearable class="admin-filter" @change="loadAdminPermissionRequests">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </div>
      <el-table :data="adminPermissionRequests" height="520" empty-text="暂无审批记录">
        <el-table-column prop="applicantId" label="申请人" width="110" />
        <el-table-column prop="displayName" label="申请资源" min-width="170" show-overflow-tooltip />
        <el-table-column prop="resourceType" label="类型" width="100">
          <template #default="{ row }">{{ resourceLabel(row.resourceType) }}</template>
        </el-table-column>
        <el-table-column prop="permissionType" label="范围" width="90">
          <template #default="{ row }">{{ permissionTypeLabel(row.permissionType) }}</template>
        </el-table-column>
        <el-table-column prop="scopeDesc" label="申请范围说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="expireAt" label="有效期" min-width="140">
          <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
        </el-table-column>
        <el-table-column prop="attachmentName" label="附件" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.attachmentName || '-' }}
            <span v-if="row.attachmentSize">（{{ formatFileSize(row.attachmentSize) }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="permissionStatusType(row.status)" size="small">{{ permissionStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="理由" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" :disabled="row.status !== 'PENDING'" @click="reviewPermission(row.id, 'APPROVED')">通过</el-button>
            <el-button size="small" type="danger" :disabled="row.status !== 'PENDING'" @click="reviewPermission(row.id, 'REJECTED')">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<script setup>
import { inject } from 'vue'

const {
  accessibleTables,
  accessibleOfficialTables,
  activeModule,
  adminPermissionRequests,
  adminRequestStatus,
  handlePermissionAttachmentChange,
  handlePermissionResourceChange,
  loadAdminPermissionRequests,
  loadPermissionCenter,
  myPermissionRequests,
  permissionForm,
  permissionOverview,
  permissionStatusText,
  permissionStatusType,
  prefillPermissionRequest,
  requestableTables,
  reviewPermission,
  sensitiveFieldPermissions,
  submitPermissionRequest
} = inject('workbench')

const resourceLabel = (value) => {
  const normalized = String(value || '').toUpperCase()
  if (normalized === 'OFFICIAL') return '官方库'
  if (normalized === 'DASHBOARD') return '公共看板'
  if (normalized === 'UPLOAD' || normalized === 'TABLE') return '数据表'
  return value || '-'
}

const permissionTypeLabel = (value) => {
  const normalized = String(value || '').toUpperCase()
  if (normalized === 'EDIT') return '编辑'
  if (normalized === 'READ' || normalized === 'VIEW') return '查看'
  return value || '-'
}

const formatFileSize = (value) => {
  const size = Number(value || 0)
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>
