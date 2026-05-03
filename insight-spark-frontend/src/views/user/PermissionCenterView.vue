<template>
<section class="permission-layout">
          <div class="permission-cards">
            <div class="panel metric-panel">
              <div class="metric-label">当前用户</div>
              <div class="metric-value">{{ permissionOverview.userId || '-' }}</div>
              <div class="metric-note">{{ permissionOverview.role || '-' }}</div>
            </div>
            <div class="panel metric-panel">
              <div class="metric-label">本人数据表</div>
              <div class="metric-value">{{ permissionOverview.ownedTableCount ?? 0 }}</div>
              <div class="metric-note">上传即拥有访问权</div>
            </div>
            <div class="panel metric-panel">
              <div class="metric-label">授权数据表</div>
              <div class="metric-value">{{ permissionOverview.grantedTableCount ?? 0 }}</div>
              <div class="metric-note">审批通过后可访问</div>
            </div>
            <div class="panel metric-panel">
              <div class="metric-label">官方库授权</div>
              <div class="metric-value">{{ permissionOverview.officialDatasourceCount ?? 0 }}</div>
              <div class="metric-note">用户/角色绑定</div>
            </div>
            <div class="panel metric-panel">
              <div class="metric-label">待审批申请</div>
              <div class="metric-value">{{ permissionOverview.pendingRequestCount ?? 0 }}</div>
              <div class="metric-note">管理员端处理</div>
            </div>
          </div>

          <div class="workspace-grid permission-grid">
            <div v-if="activeModule === 'permission'" class="panel">
              <div class="panel-header">
                <div>
                  <h2>可访问数据表</h2>
                  <p>{{ permissionOverview.dataScope }}</p>
                </div>
                <el-button @click="loadPermissionCenter">刷新</el-button>
              </div>
              <el-table :data="accessibleTables" height="320" empty-text="暂无可访问数据表">
                <el-table-column prop="displayName" label="数据表" min-width="150" />
                <el-table-column prop="accessSource" label="来源" width="100">
                  <template #default="{ row }">
                    <el-tag :type="row.accessSource === 'OWNER' ? 'success' : 'warning'" size="small">
                      {{ row.accessSource === 'OWNER' ? '本人上传' : '审批授权' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="rowCount" label="行数" width="90" />
                <el-table-column prop="fieldCount" label="字段" width="90" />
              </el-table>
            </div>

            <div v-if="activeModule === 'permission'" class="panel">
              <div class="panel-header">
                <div>
                  <h2>官方授权数据源</h2>
                  <p>展示管理员已绑定给当前用户或角色的官方库表。</p>
                </div>
              </div>
              <el-table :data="accessibleOfficialTables" height="320" empty-text="暂无官方库授权">
                <el-table-column prop="displayName" label="官方数据表" min-width="190" />
                <el-table-column prop="datasourceName" label="数据源" min-width="130" />
                <el-table-column prop="rowCount" label="估算行数" width="100" />
                <el-table-column prop="expireAt" label="有效期" min-width="150">
                  <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
                </el-table-column>
              </el-table>
            </div>

            <div v-if="activeModule === 'permission'" class="panel">
              <div class="panel-header">
                <div>
                  <h2>权限申请</h2>
                  <p>申请访问非本人上传的数据表，提交后由管理员审批。</p>
                </div>
              </div>
              <el-form label-position="top">
                <el-form-item label="申请数据表">
                  <el-select v-model="permissionForm.tableName" placeholder="选择可申请的数据表" class="full-width">
                    <el-option
                        v-for="table in requestableTables"
                        :key="table.tableName"
                        :label="`${table.displayName}（${table.ownerId}）`"
                        :value="table.tableName"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="申请理由">
                  <el-input
                      v-model="permissionForm.reason"
                      type="textarea"
                      :rows="4"
                      placeholder="例如：需要用于销售区域分析和月度汇报"
                  />
                </el-form-item>
                <el-button type="primary" :disabled="!permissionForm.tableName" @click="submitPermissionRequest">
                  提交申请
                </el-button>
              </el-form>
              <el-alert class="permission-tip" type="info" :closable="false" show-icon :title="permissionOverview.sensitiveRule || '敏感字段默认标记，后续可扩展字段级脱敏。'" />
            </div>

            <div v-if="activeModule === 'permission'" class="panel">
              <div class="panel-header">
                <div>
                  <h2>敏感字段权限</h2>
                  <p>列出当前可访问范围内的敏感字段，查询结果默认脱敏。</p>
                </div>
              </div>
              <el-table :data="sensitiveFieldPermissions" height="320" empty-text="暂无敏感字段">
                <el-table-column prop="sourceType" label="来源" width="100" />
                <el-table-column prop="tableDisplayName" label="数据表" min-width="160" />
                <el-table-column prop="displayName" label="敏感字段" min-width="140" />
                <el-table-column prop="columnName" label="物理字段" width="120" />
                <el-table-column prop="accessMode" label="访问方式" width="100" />
              </el-table>
            </div>

            <div v-if="activeModule === 'permission'" class="panel">
              <div class="panel-header">
                <div>
                  <h2>我的申请进度</h2>
                  <p>查看当前用户提交的权限申请审批状态。</p>
                </div>
              </div>
              <el-table :data="myPermissionRequests" height="320" empty-text="暂无申请记录">
                <el-table-column prop="displayName" label="数据表" min-width="140" />
                <el-table-column prop="status" label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="permissionStatusType(row.status)" size="small">{{ permissionStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="reason" label="理由" min-width="180" show-overflow-tooltip />
                <el-table-column prop="reviewComment" label="审批意见" min-width="160" show-overflow-tooltip />
              </el-table>
            </div>

            <div v-if="activeModule === 'permissionAdmin'" class="panel admin-approval-panel">
              <div class="panel-header">
                <div>
                  <h2>管理员审批</h2>
                  <p>演示管理员处理普通用户的数据权限申请。</p>
                </div>
                <el-select v-model="adminRequestStatus" placeholder="状态" clearable class="admin-filter" @change="loadAdminPermissionRequests">
                  <el-option label="待审批" value="PENDING" />
                  <el-option label="已通过" value="APPROVED" />
                  <el-option label="已驳回" value="REJECTED" />
                </el-select>
              </div>
              <el-table :data="adminPermissionRequests" height="320" empty-text="暂无审批记录">
                <el-table-column prop="applicantId" label="申请人" width="110" />
                <el-table-column prop="displayName" label="数据表" min-width="140" />
                <el-table-column prop="status" label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="permissionStatusType(row.status)" size="small">{{ permissionStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="reason" label="理由" min-width="160" show-overflow-tooltip />
                <el-table-column label="操作" width="170" fixed="right">
                  <template #default="{ row }">
                    <el-button size="small" type="success" :disabled="row.status !== 'PENDING'" @click="reviewPermission(row.id, 'APPROVED')">通过</el-button>
                    <el-button size="small" type="danger" :disabled="row.status !== 'PENDING'" @click="reviewPermission(row.id, 'REJECTED')">驳回</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </section>
</template>

<script setup>
import { inject } from 'vue'

const {
  API_BASE,
  accessibleTables,
  accessibleOfficialTables,
  activeModule,
  adminPermissionRequests,
  adminRequestStatus,
  auditExecuteStatus,
  auditLogs,
  auditRiskLevel,
  body,
  chartTypeLabel,
  chatDom,
  createDatasource,
  currentChartType,
  currentDiagnosis,
  data,
  datasourceForm,
  dateFields,
  detail,
  diagnosisForm,
  diagnosisLoading,
  diagnosisReports,
  dimensionCandidateFields,
  field,
  fieldLabel,
  fields,
  fillCurrentDatasource,
  formData,
  isAdminModule,
  isPermissionModule,
  lastAnalysis,
  loadAdminPermissionRequests,
  loadAuditLogs,
  loadDatasources,
  loadDiagnosisReportDetail,
  loadDiagnosisReports,
  loadFields,
  loadPermissionCenter,
  loadPreview,
  loadSchemaTables,
  loadTables,
  loading,
  messages,
  moduleSubtitle,
  moduleTitle,
  myPermissionRequests,
  nextStatus,
  numericFields,
  officialDatasources,
  onFileChange,
  onFileRemove,
  parsed,
  permissionForm,
  permissionOverview,
  permissionStatusText,
  permissionStatusType,
  placeholderStep,
  previewColumns,
  previewRows,
  question,
  renderChart,
  requestableTables,
  sensitiveFieldPermissions,
  result,
  reviewPermission,
  riskTagType,
  runDiagnosis,
  schemaFields,
  schemaTables,
  selectDatasource,
  selectSchemaTable,
  selectTable,
  selectedDatasourceId,
  selectedTableName,
  sendQuestion,
  seriesData,
  statusTagType,
  submitPermissionRequest,
  submitUpload,
  syncDatasourceSchema,
  tables,
  testDatasource,
  toggleDatasource,
  unwrap,
  updateSchemaField,
  uploadFile,
  uploadResult,
  uploading,
  userQuestion,
  xAxisData
} = inject('workbench')
</script>
