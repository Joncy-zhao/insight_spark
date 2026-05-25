<template>
  <section class="permission-center-page">
    <header class="permission-page-header">
      <div>
        <h1>{{ pageTitle }}</h1>
        <p>{{ pageSubtitle }}</p>
      </div>
      <div class="permission-header-actions">
        <el-button :icon="Refresh" @click="loadPermissionCenter">刷新权限</el-button>
        <el-button type="primary" :icon="DocumentChecked" @click="scrollToComplianceDoc">查看合规文档</el-button>
      </div>
    </header>

    <div class="permission-metrics">
      <article v-for="card in metricCards" :key="card.label" class="permission-metric-card" :class="`is-${card.tone}`">
        <div class="metric-icon">
          <el-icon><component :is="card.icon" /></el-icon>
        </div>
        <div class="metric-content">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small>{{ card.note }}</small>
        </div>
      </article>
    </div>

    <template v-if="activeModule === 'permission'">
      <div class="permission-top-grid">
        <section class="permission-panel permission-profile-panel">
          <div class="section-title">
            <span class="title-icon is-blue"><el-icon><Lock /></el-icon></span>
            <h2>权限画像 / 角色与边界</h2>
          </div>

          <div class="rbac-card">
            <div class="section-label">RBAC 有效权限</div>
            <el-table
              :data="rbacRoleDetails"
              class="compact-table"
              height="116"
              empty-text="暂无角色继承明细"
            >
              <el-table-column prop="roleName" label="角色" min-width="130" />
              <el-table-column prop="roleCode" label="编码" width="120" />
              <el-table-column prop="parentRoleCode" label="继承自" width="110">
                <template #default="{ row }">{{ row.parentRoleCode || '-' }}</template>
              </el-table-column>
              <el-table-column prop="dataScope" label="数据范围" width="120" />
            </el-table>
          </div>

          <div class="row-permission-card">
            <div class="row-permission-copy">
              <div class="section-label">数据行权限</div>
              <strong>仅可查看本人上传或已授权范围内的数据</strong>
              <p>{{ rowPermissionDescription }}</p>
            </div>
            <div class="boundary-rule-grid" aria-label="数据行级隔离策略">
              <div class="boundary-rule is-allowed">
                <span>01</span>
                <strong>本人上传</strong>
                <p>本人创建的数据表可访问</p>
              </div>
              <div class="boundary-rule is-granted">
                <span>02</span>
                <strong>授权范围</strong>
                <p>审批通过后按范围访问</p>
              </div>
              <div class="boundary-rule is-locked">
                <span>03</span>
                <strong>未授权隔离</strong>
                <p>其他用户数据默认不可见</p>
              </div>
            </div>
          </div>

          <div class="menu-permissions">
            <div class="section-label">可操作菜单</div>
            <div class="menu-chip-list">
              <el-tag v-for="item in menuPermissions" :key="item" effect="plain">
                <el-icon><FolderChecked /></el-icon>
                {{ item }}
              </el-tag>
            </div>
          </div>
        </section>

        <section class="permission-panel permission-request-panel">
          <div class="section-title">
            <span class="title-icon is-blue"><el-icon><DocumentAdd /></el-icon></span>
            <h2>权限申请</h2>
          </div>

          <el-form class="permission-request-form" label-position="top">
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
              <el-form-item label="权限周期">
                <div class="period-row">
                  <el-radio-group v-model="permissionPeriodMode" class="period-switch">
                    <el-radio-button label="LONG">长期</el-radio-button>
                    <el-radio-button label="CUSTOM">自定义</el-radio-button>
                  </el-radio-group>
                  <el-date-picker
                    v-model="permissionForm.expireAt"
                    type="date"
                    value-format="YYYY-MM-DD"
                    placeholder="选择结束日期"
                    :disabled="permissionPeriodMode === 'LONG'"
                  />
                </div>
              </el-form-item>
            </div>

            <el-form-item label="申请权限范围说明（选填）">
              <el-input
                v-model="permissionForm.scopeDesc"
                placeholder="例如：华东区域销售看板查看、月度经营例会使用"
              />
            </el-form-item>
            <el-form-item label="申请理由">
              <el-input
                v-model="permissionForm.reason"
                type="textarea"
                :rows="3"
                placeholder="说明业务场景、使用周期、输出对象和是否涉及敏感字段"
              />
            </el-form-item>

            <div class="request-submit-row">
              <el-upload
                :auto-upload="false"
                :limit="1"
                :on-change="handlePermissionAttachmentChange"
                :show-file-list="false"
              >
                <el-button class="attachment-button">
                  <el-icon><Upload /></el-icon>
                  {{ attachmentText }}
                </el-button>
              </el-upload>
              <el-button
                type="primary"
                class="submit-button"
                :disabled="!permissionForm.tableName || !permissionForm.reason"
                @click="submitPermissionRequest"
              >
                提交申请
              </el-button>
            </div>
          </el-form>

          <div class="sensitive-note">
            <el-icon><InfoFilled /></el-icon>
            <span>{{ permissionOverview.sensitiveRule || '敏感字段按字段标记识别，查询展示默认脱敏。' }}</span>
          </div>
        </section>
      </div>

      <div class="permission-data-grid">
        <section class="permission-panel access-panel">
          <div class="section-title with-tools">
            <span class="title-icon is-blue"><el-icon><Files /></el-icon></span>
            <h2>可访问的数据源</h2>
            <div class="access-tools">
              <el-input
                v-model="accessKeyword"
                :prefix-icon="Search"
                placeholder="搜索数据表名称"
                clearable
              />
              <el-button :icon="Filter" @click="accessPage = 1">筛选</el-button>
            </div>
          </div>
          <el-radio-group v-model="accessTab" class="source-tabs">
            <el-radio-button label="ALL">全部</el-radio-button>
            <el-radio-button label="OWNER">本人上传</el-radio-button>
            <el-radio-button label="OFFICIAL">官方授权</el-radio-button>
            <el-radio-button label="DASHBOARD">公共看板</el-radio-button>
          </el-radio-group>

          <el-table
            :data="pagedAccessibleResources"
            class="compact-table permission-table"
            height="224"
            empty-text="暂无可访问数据源"
          >
            <el-table-column prop="displayName" label="数据表" min-width="170" show-overflow-tooltip />
            <el-table-column label="来源" width="96">
              <template #default="{ row }">
                <el-tag :type="accessSourceTagType(row)" effect="light" size="small">
                  {{ accessSourceText(row) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="permissionScope" label="权限范围" width="112" />
            <el-table-column label="有效期" width="96">
              <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
            </el-table-column>
            <el-table-column prop="rowCount" label="行数" width="78" />
            <el-table-column prop="fieldCount" label="字段数" width="82" />
            <el-table-column label="更新时间" width="148">
              <template #default="{ row }">{{ formatDateTime(row.createdAt || row.grantedAt) }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="accessPage"
            v-model:page-size="accessPageSize"
            class="access-pagination"
            small
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[5, 10, 20]"
            :total="filteredAccessibleResources.length"
          />
        </section>

        <section class="permission-panel sensitive-panel">
          <div class="section-title">
            <span class="title-icon is-blue"><el-icon><Lock /></el-icon></span>
            <h2>敏感字段权限</h2>
          </div>
          <div class="warning-strip">
            <el-icon><WarningFilled /></el-icon>
            <span>手机号、身份证、金额等字段默认按规则脱敏展示</span>
          </div>
          <el-table
            :data="sensitiveFieldPermissions"
            class="compact-table permission-table"
            height="252"
            empty-text="暂无敏感字段"
          >
            <el-table-column label="来源" width="74">
              <template #default="{ row }">{{ resourceLabel(row.sourceType) }}</template>
            </el-table-column>
            <el-table-column prop="tableDisplayName" label="数据表" min-width="145" show-overflow-tooltip />
            <el-table-column prop="displayName" label="敏感字段" min-width="104" show-overflow-tooltip />
            <el-table-column prop="columnName" label="物理字段" width="92" show-overflow-tooltip />
            <el-table-column label="访问方式" width="96">
              <template #default="{ row }">
                <el-tag :type="row.accessMode === 'NO_ACCESS' ? 'danger' : 'success'" size="small">
                  {{ row.accessMode === 'NO_ACCESS' ? '不可访问' : '脱敏展示' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="permission-panel request-history-panel">
          <div class="section-title">
            <span class="title-icon is-blue"><el-icon><Clock /></el-icon></span>
            <h2>我的申请进度</h2>
          </div>
          <el-table
            :data="myPermissionRequests"
            class="compact-table permission-table"
            height="284"
            empty-text="暂无申请记录"
          >
            <el-table-column prop="displayName" label="申请资源" min-width="160" show-overflow-tooltip />
            <el-table-column label="类型" width="82">
              <template #default="{ row }">{{ resourceLabel(row.resourceType) }}</template>
            </el-table-column>
            <el-table-column label="范围" width="72">
              <template #default="{ row }">{{ permissionTypeLabel(row.permissionType) }}</template>
            </el-table-column>
            <el-table-column label="有效期" width="92">
              <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="88">
              <template #default="{ row }">
                <el-tag :type="permissionStatusType(row.status)" size="small">
                  {{ permissionStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reviewerId" label="审批人" width="92" />
            <el-table-column label="审批时间" width="126">
              <template #default="{ row }">{{ formatDateTime(row.reviewedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="82" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'REJECTED'" link type="primary" @click="prefillPermissionRequest(row)">
                  重提
                </el-button>
                <span v-else class="table-action-text">查看</span>
              </template>
            </el-table-column>
          </el-table>
          <span class="panel-link">查看全部申请记录</span>
        </section>
      </div>

      <div class="permission-bottom-grid">
        <section class="permission-panel compliance-panel">
          <div class="section-title">
            <span class="title-icon is-blue"><el-icon><Select /></el-icon></span>
            <h2>合规提示</h2>
          </div>
          <ul class="compliance-list">
            <li v-for="tip in complianceTips" :key="tip">{{ tip }}</li>
          </ul>
        </section>

        <section ref="documentPanelRef" class="permission-panel compliance-document-panel">
          <div class="doc-copy">
            <div class="section-title">
              <span class="title-icon is-blue"><el-icon><Notebook /></el-icon></span>
              <h2>{{ complianceDocument.title || '企业数据安全合规文档' }}</h2>
            </div>
            <div class="compliance-doc-meta">
              版本：{{ complianceDocument.version || '-' }}　更新时间：{{ formatDateTime(complianceDocument.updatedAt) }}
            </div>
            <ol class="compliance-doc-lines">
              <li v-for="line in complianceDocumentLines" :key="line">{{ line }}</li>
            </ol>
          </div>
          <div class="doc-illustration" aria-hidden="true">
            <img :src="permissionShieldIllustration" alt="" />
          </div>
        </section>
      </div>
    </template>

    <section v-else class="permission-panel admin-approval-panel">
      <div class="section-title with-tools">
        <span class="title-icon is-blue"><el-icon><Key /></el-icon></span>
        <h2>管理员审批</h2>
        <el-select v-model="adminRequestStatus" placeholder="状态" clearable class="admin-filter" @change="loadAdminPermissionRequests">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </div>
      <el-table :data="adminPermissionRequests" class="compact-table permission-table" height="520" empty-text="暂无审批记录">
        <el-table-column prop="applicantId" label="申请人" width="110" />
        <el-table-column prop="displayName" label="申请资源" min-width="170" show-overflow-tooltip />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ resourceLabel(row.resourceType) }}</template>
        </el-table-column>
        <el-table-column label="范围" width="90">
          <template #default="{ row }">{{ permissionTypeLabel(row.permissionType) }}</template>
        </el-table-column>
        <el-table-column prop="scopeDesc" label="申请范围说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="有效期" min-width="140">
          <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
        </el-table-column>
        <el-table-column prop="attachmentName" label="附件" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.attachmentName || '-' }}
            <span v-if="row.attachmentSize">（{{ formatFileSize(row.attachmentSize) }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
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
    </section>
  </section>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import {
  Clock,
  Coin,
  DocumentAdd,
  DocumentChecked,
  Files,
  Filter,
  FolderChecked,
  InfoFilled,
  Key,
  Lock,
  Notebook,
  OfficeBuilding,
  Refresh,
  Search,
  Select,
  Upload,
  User,
  UserFilled,
  WarningFilled
} from '@element-plus/icons-vue'
import permissionShieldIllustration from '../../assets/permission-shield-illustration.png'

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
  submitPermissionRequest,
  complianceDocument
} = inject('workbench')

const accessKeyword = ref('')
const accessPage = ref(1)
const accessPageSize = ref(5)
const accessTab = ref('ALL')
const documentPanelRef = ref(null)
const permissionPeriodMode = ref(permissionForm.value?.expireAt ? 'CUSTOM' : 'LONG')

const pageTitle = computed(() => activeModule.value === 'permissionAdmin' ? '数据权限审批' : '数据权限中心')
const pageSubtitle = computed(() => activeModule.value === 'permissionAdmin'
  ? '集中处理数据权限申请，保障授权边界与敏感字段使用合规。'
  : '查看个人权限边界、可访问数据源、敏感字段规则与权限申请进度，保障数据安全合规使用。')

const roleDisplay = computed(() => permissionOverview.value?.roleLevel || permissionOverview.value?.role || '-')
const roleCode = computed(() => permissionOverview.value?.role || '-')
const rowPermissionDescription = computed(() => permissionOverview.value?.rowPolicy
  || '系统按 owner_user_id / owner_id 与授权关系强制过滤数据行；未获得授权时，其他用户上传的数据不会出现在查询、预览和导出结果中。')
const menuPermissions = computed(() => permissionOverview.value?.menuPermissions?.length
  ? permissionOverview.value.menuPermissions
  : ['用户工作台', '对话分析', '数据上传', '数据权限中心'])
const complianceTips = computed(() => permissionOverview.value?.complianceTips?.length
  ? permissionOverview.value.complianceTips
  : [
      '仅在已授权业务目的内访问和导出数据，禁止绕过审批共享敏感信息。',
      '手机号、身份证、金额等敏感字段默认按规则脱敏展示，导出与截图需遵守最小必要原则。',
      '违规访问、转存或传播数据会触发审计追踪，并可能导致账号冻结与内部合规处理。'
    ])

const rbacRoleDetails = computed(() => {
  const rows = permissionOverview.value?.rbacProfile?.roleDetails || []
  if (rows.length) return rows
  return [{
    roleName: roleDisplay.value,
    roleCode: roleCode.value,
    parentRoleCode: '-',
    dataScope: permissionOverview.value?.dataScope || 'SELF'
  }]
})

const metricCards = computed(() => [
  {
    label: '当前用户',
    value: permissionOverview.value?.userId || '-',
    note: roleDisplay.value,
    icon: User,
    tone: 'blue'
  },
  {
    label: '当前角色',
    value: roleCode.value,
    note: roleDisplay.value,
    icon: UserFilled,
    tone: 'green'
  },
  {
    label: '本人数据表',
    value: permissionOverview.value?.ownedTableCount ?? 0,
    note: '可访问/拥有数据表',
    icon: Coin,
    tone: 'indigo'
  },
  {
    label: '官方库授权',
    value: permissionOverview.value?.officialDatasourceCount ?? 0,
    note: '含有效期控制',
    icon: OfficeBuilding,
    tone: 'purple'
  },
  {
    label: '待审批申请',
    value: permissionOverview.value?.pendingRequestCount ?? 0,
    note: '管理员待处理',
    icon: Clock,
    tone: 'orange'
  },
  {
    label: '敏感字段规则',
    value: `${permissionOverview.value?.sensitiveFieldCount ?? sensitiveFieldPermissions.value?.length ?? 0} 类`,
    note: '字段脱敏规则',
    icon: Key,
    tone: 'red'
  }
])

const complianceDocumentLines = computed(() => {
  const content = String(complianceDocument.value?.content || '').trim()
  return content
    ? content
        .split(/\r?\n/)
        .map(line => line.trim().replace(/^\d+[.、]\s*/, ''))
        .filter(Boolean)
    : ['暂无合规文档内容']
})

const accessibleResources = computed(() => [
  ...(accessibleTables.value || []).map(item => ({ ...item, resourceKind: 'UPLOAD' })),
  ...(accessibleOfficialTables.value || []).map(item => ({ ...item, resourceKind: 'OFFICIAL', accessSource: 'OFFICIAL' }))
])

const filteredAccessibleResources = computed(() => {
  const keyword = accessKeyword.value.trim().toLowerCase()
  return accessibleResources.value.filter(item => {
    const sourceMatched = accessTab.value === 'ALL'
      || (accessTab.value === 'OWNER' && item.accessSource === 'OWNER')
      || (accessTab.value === 'OFFICIAL' && item.resourceKind === 'OFFICIAL')
      || (accessTab.value === 'DASHBOARD' && String(item.sourceType || item.resourceType || '').toUpperCase() === 'DASHBOARD')
    const keywordMatched = !keyword || String(item.displayName || item.tableName || '').toLowerCase().includes(keyword)
    return sourceMatched && keywordMatched
  })
})

const pagedAccessibleResources = computed(() => {
  const start = (accessPage.value - 1) * accessPageSize.value
  return filteredAccessibleResources.value.slice(start, start + accessPageSize.value)
})

const attachmentText = computed(() => {
  if (!permissionForm.value?.attachmentName) return '点击或拖拽文件上传，支持 pdf / doc / docx / xls / xlsx / png / jpg'
  const sizeText = permissionForm.value.attachmentSize ? `（${formatFileSize(permissionForm.value.attachmentSize)}）` : ''
  return `${permissionForm.value.attachmentName}${sizeText}`
})

watch([accessKeyword, accessTab, accessPageSize], () => {
  accessPage.value = 1
})

watch(() => permissionForm.value?.expireAt, (expireAt) => {
  permissionPeriodMode.value = expireAt ? 'CUSTOM' : 'LONG'
})

watch(permissionPeriodMode, (mode) => {
  if (mode === 'LONG') {
    permissionForm.value.expireAt = ''
  }
})

const scrollToComplianceDoc = () => {
  documentPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const resourceLabel = (value) => {
  const normalized = String(value || '').toUpperCase()
  if (normalized === 'UPLOAD_GRANT') return '上传表授权'
  if (normalized === 'GLOBAL') return '全局'
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

const accessSourceText = (row) => {
  if (row.resourceKind === 'OFFICIAL') return '官方库'
  if (String(row.sourceType || row.resourceType || '').toUpperCase() === 'DASHBOARD') return '公共看板'
  if (row.accessSource === 'OWNER') return '本人上传'
  if (row.accessSource === 'ADMIN') return '管理员'
  return '授权'
}

const accessSourceTagType = (row) => {
  if (row.resourceKind === 'OFFICIAL') return 'primary'
  if (row.accessSource === 'OWNER') return 'success'
  if (String(row.sourceType || row.resourceType || '').toUpperCase() === 'DASHBOARD') return 'info'
  return 'warning'
}

const formatDateTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 16)
}

const formatFileSize = (value) => {
  const size = Number(value || 0)
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>

<style scoped>
.permission-center-page {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 0;
  color: #13213a;
}

.permission-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.permission-page-header h1 {
  margin: 0;
  color: #0b1f44;
  font-size: 24px;
  font-weight: 800;
  line-height: 1.2;
}

.permission-page-header p {
  margin: 8px 0 0;
  color: #51627f;
  font-size: 14px;
  line-height: 1.5;
}

.permission-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.permission-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(142px, 1fr));
  gap: 12px;
}

.permission-metric-card,
.permission-panel {
  min-width: 0;
  border: 1px solid #dce6f4;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 10px 28px rgba(29, 62, 113, 0.08);
}

.permission-metric-card {
  display: flex;
  align-items: center;
  gap: 13px;
  min-height: 90px;
  padding: 16px 18px;
}

.metric-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 50%;
  font-size: 24px;
}

.metric-content {
  min-width: 0;
}

.metric-content span,
.metric-content small {
  display: block;
  color: #63718a;
  font-size: 12px;
  line-height: 1.35;
}

.metric-content strong {
  display: block;
  margin: 3px 0 5px;
  overflow: hidden;
  color: #071d42;
  font-size: 18px;
  font-weight: 800;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.is-blue .metric-icon,
.metric-icon .is-blue {
  color: #1f73ff;
  background: #e9f2ff;
}

.is-green .metric-icon {
  color: #3fa533;
  background: #eaf8e6;
}

.is-indigo .metric-icon {
  color: #286deb;
  background: #e8f0ff;
}

.is-purple .metric-icon {
  color: #7f5be8;
  background: #f0ebff;
}

.is-orange .metric-icon {
  color: #ef8a0c;
  background: #fff1dc;
}

.is-red .metric-icon {
  color: #ec4e4e;
  background: #ffe8e9;
}

.permission-top-grid {
  display: grid;
  grid-template-columns: minmax(480px, 0.93fr) minmax(560px, 1.07fr);
  gap: 12px;
}

.permission-data-grid {
  display: grid;
  grid-template-columns: minmax(480px, 1.25fr) minmax(360px, 0.72fr) minmax(380px, 0.94fr);
  gap: 12px;
}

.permission-bottom-grid {
  display: grid;
  grid-template-columns: minmax(420px, 0.72fr) minmax(560px, 1.28fr);
  gap: 12px;
}

.permission-panel {
  padding: 14px 16px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-bottom: 12px;
}

.section-title.with-tools {
  align-items: center;
}

.section-title h2 {
  min-width: 0;
  margin: 0;
  color: #14233f;
  font-size: 16px;
  font-weight: 800;
  line-height: 1.35;
}

.title-icon {
  width: 22px;
  height: 22px;
  display: inline-grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 6px;
  color: #1f73ff;
  background: #eaf2ff;
}

.section-label {
  color: #233350;
  font-size: 12px;
  font-weight: 800;
  line-height: 1.35;
}

.rbac-card {
  min-width: 0;
}

.row-permission-card {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid #d8e7ff;
  border-radius: 8px;
  background: linear-gradient(135deg, #f7fbff 0%, #ffffff 68%, #fff9f3 100%);
}

.row-permission-copy strong {
  display: block;
  margin-top: 6px;
  color: #13294b;
  font-size: 15px;
  line-height: 1.45;
}

.row-permission-copy p {
  max-width: 720px;
  margin: 5px 0 0;
  color: #54647f;
  font-size: 12px;
  line-height: 1.65;
}

.boundary-rule-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: 10px;
  min-width: 0;
}

.boundary-rule {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  grid-template-rows: auto auto;
  column-gap: 10px;
  align-items: start;
  min-width: 0;
  min-height: 74px;
  padding: 12px;
  border: 1px solid #d9e5f5;
  border-radius: 6px;
  background: #fff;
}

.boundary-rule span {
  display: inline-grid;
  place-items: center;
  grid-row: 1 / span 2;
  width: 34px;
  height: 34px;
  margin: 1px 0 0;
  border-radius: 5px;
  font-size: 14px;
  font-weight: 800;
}

.boundary-rule strong,
.boundary-rule p {
  display: block;
  margin: 0;
}

.boundary-rule strong {
  color: #162b4c;
  font-size: 15px;
  line-height: 1.35;
}

.boundary-rule p {
  margin-top: 5px;
  color: #5d6c84;
  font-size: 12px;
  line-height: 1.45;
}

.boundary-rule.is-allowed {
  color: #24703c;
  border-color: #acd9ad;
  background: #f0fbef;
}

.boundary-rule.is-allowed span {
  color: #24703c;
  background: #dff3df;
}

.boundary-rule.is-granted {
  color: #1d5bbf;
  border-color: #b8d4ff;
  background: #f0f6ff;
}

.boundary-rule.is-granted span {
  color: #1d5bbf;
  background: #deebff;
}

.boundary-rule.is-locked {
  color: #b44747;
  border-color: #f2b9b9;
  background: #fff1f1;
}

.boundary-rule.is-locked span {
  color: #b44747;
  background: #ffe0e0;
}

.menu-permissions {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e6edf7;
}

.menu-chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.menu-chip-list :deep(.el-tag) {
  height: 28px;
  border-color: #cfdcf0;
  color: #375072;
  background: #f7fbff;
}

.permission-request-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.permission-request-form :deep(.el-form-item__label) {
  margin-bottom: 6px;
  color: #354663;
  font-size: 12px;
  font-weight: 700;
}

.request-form-grid {
  display: grid;
  grid-template-columns: minmax(190px, 0.64fr) minmax(280px, 1.36fr);
  gap: 12px;
}

.period-row {
  display: grid;
  grid-template-columns: auto minmax(160px, 1fr);
  gap: 10px;
}

.period-row :deep(.el-date-editor) {
  width: 100%;
}

.request-submit-row {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(160px, 220px);
  gap: 18px;
  align-items: center;
}

.attachment-button,
.submit-button {
  width: 100%;
}

.attachment-button {
  overflow: hidden;
  color: #6a7890;
  border-style: dashed;
}

.attachment-button :deep(span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sensitive-note {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 9px 12px;
  border-radius: 6px;
  color: #5c6f90;
  background: #f2f7fe;
  font-size: 12px;
}

.access-tools {
  display: grid;
  grid-template-columns: minmax(180px, 230px) 72px;
  gap: 10px;
  margin-left: auto;
}

.source-tabs {
  margin-bottom: 10px;
}

.warning-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid #ffe2b7;
  border-radius: 6px;
  color: #b76512;
  background: #fff8eb;
  font-size: 12px;
  font-weight: 700;
}

.compact-table {
  width: 100%;
}

.compact-table :deep(.el-table__cell) {
  padding: 5px 0;
}

.compact-table :deep(.cell) {
  padding: 0 8px;
  font-size: 12px;
  line-height: 1.45;
}

.compact-table :deep(th.el-table__cell) {
  background: #f5f8fc;
  color: #364762;
  font-weight: 800;
}

.compact-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background: #f7fbff;
}

.permission-table {
  border-top: 1px solid #edf2f8;
}

.access-pagination {
  justify-content: flex-end;
  margin-top: 9px;
}

.table-action-text,
.panel-link {
  color: #1f73ff;
  font-size: 12px;
}

.panel-link {
  display: inline-flex;
  align-items: center;
  margin-top: 8px;
  padding: 0;
}

.compliance-list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.compliance-list li {
  position: relative;
  padding-left: 18px;
  color: #34445f;
  font-size: 13px;
  line-height: 1.7;
}

.compliance-list li::before {
  content: '';
  position: absolute;
  top: 9px;
  left: 2px;
  width: 7px;
  height: 7px;
  border: 3px solid #cfe1ff;
  border-radius: 50%;
  background: #2478ff;
}

.compliance-document-panel {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) 230px;
  gap: 18px;
  overflow: hidden;
}

.doc-copy {
  min-width: 0;
}

.compliance-doc-meta {
  margin: -2px 0 8px;
  color: #233350;
  font-size: 13px;
  font-weight: 700;
}

.compliance-doc-lines {
  display: grid;
  gap: 5px;
  max-height: 118px;
  margin: 0;
  padding-left: 18px;
  overflow: auto;
  color: #34445f;
  font-size: 12px;
  line-height: 1.65;
}

.doc-illustration {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 132px;
  border-radius: 8px;
  overflow: hidden;
}

.doc-illustration img {
  width: 100%;
  height: 100%;
  min-height: 132px;
  display: block;
  object-fit: cover;
}

.admin-approval-panel {
  margin-top: 0;
}

.admin-filter {
  width: 140px;
  margin-left: auto;
}

.full-width {
  width: 100%;
}

@media (max-width: 1500px) {
  .permission-metrics {
    grid-template-columns: repeat(3, minmax(190px, 1fr));
  }

  .permission-data-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 1180px) {
  .permission-page-header,
  .permission-top-grid,
  .permission-bottom-grid,
  .row-permission-card,
  .request-form-grid,
  .request-submit-row,
  .compliance-document-panel {
    grid-template-columns: 1fr;
  }

  .permission-page-header {
    display: grid;
  }

  .permission-header-actions {
    justify-content: flex-start;
  }

  .permission-metrics {
    grid-template-columns: repeat(2, minmax(180px, 1fr));
  }

  .access-tools,
  .period-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .permission-metrics {
    grid-template-columns: 1fr;
  }

  .permission-header-actions,
  .source-tabs {
    flex-wrap: wrap;
  }

  .boundary-rule-grid {
    grid-template-columns: 1fr;
  }
}
</style>
