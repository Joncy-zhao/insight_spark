<template>
<section class="audit-layout">
          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>审计规则配置</h2>
                <p>管理员可启停 SQL 安全规则，覆盖只读查询、危险关键字、敏感字段和慢查询识别。</p>
              </div>
              <el-button @click="loadAuditRules">刷新规则</el-button>
            </div>
            <el-table :data="auditRules" height="260" empty-text="暂无审计规则">
              <el-table-column prop="ruleCode" label="规则编码" width="160" />
              <el-table-column prop="ruleName" label="规则名称" min-width="150" />
              <el-table-column prop="riskLevel" label="风险级别" width="100">
                <template #default="{ row }">
                  <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ row.riskLevel }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="ruleDesc" label="规则说明" min-width="260" show-overflow-tooltip />
              <el-table-column label="阈值" width="130">
                <template #default="{ row }">
                  <el-input v-model="row.thresholdValue" size="small" @change="updateAuditRuleConfig(row)" />
                </template>
              </el-table-column>
              <el-table-column label="启用" width="90">
                <template #default="{ row }">
                  <el-switch v-model="row.enabled" @change="updateAuditRuleStatus(row)" />
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>提交 SQL 审计</h2>
                <p>B端可手工提交 SQL，系统按当前规则判定风险并留痕。</p>
              </div>
            </div>
            <el-input v-model="manualAuditSql" type="textarea" :rows="4" placeholder="输入待审计 SQL，例如 SELECT * FROM `biz_data_xxx` LIMIT 20" />
            <div class="upload-actions">
              <el-button type="primary" :disabled="!manualAuditSql" @click="submitManualAudit">提交审计</el-button>
              <el-tag v-if="manualAuditResult" :type="riskTagType(manualAuditResult.riskLevel)">
                {{ manualAuditResult.riskLevel }}：{{ manualAuditResult.riskReason }}
              </el-tag>
            </div>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>SQL安全审计日志</h2>
                <p>记录 AI 生成 SQL 的风险等级、执行状态、耗时和拦截原因。</p>
              </div>
              <div class="audit-toolbar">
                <el-select v-model="auditRiskLevel" placeholder="风险等级" clearable>
                  <el-option label="安全" value="SAFE" />
                  <el-option label="警告" value="WARN" />
                  <el-option label="拦截" value="BLOCKED" />
                </el-select>
                <el-select v-model="auditExecuteStatus" placeholder="执行状态" clearable>
                  <el-option label="成功" value="SUCCESS" />
                  <el-option label="失败" value="FAILED" />
                  <el-option label="已拦截" value="BLOCKED" />
                </el-select>
                <el-button type="primary" @click="loadAuditLogs">刷新</el-button>
                <el-button @click="exportSqlLogs">导出</el-button>
              </div>
            </div>

            <el-table :data="auditLogs" height="560" empty-text="暂无 SQL 审计日志">
              <el-table-column prop="createdAt" label="时间" min-width="170" />
              <el-table-column prop="riskLevel" label="风险" width="95">
                <template #default="{ row }">
                  <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ row.riskLevel }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="executeStatus" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.executeStatus)" size="small">{{ row.executeStatus }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
              <el-table-column prop="slowQuery" label="慢查询" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.slowQuery ? 'warning' : 'info'" size="small">{{ row.slowQuery ? '是' : '否' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="engine" label="引擎" min-width="130" />
              <el-table-column prop="tableName" label="数据表" min-width="150" />
              <el-table-column prop="sensitiveFields" label="敏感字段" min-width="160" show-overflow-tooltip />
              <el-table-column prop="matchedRules" label="命中规则" min-width="170" show-overflow-tooltip />
              <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
              <el-table-column prop="riskReason" label="审计说明" min-width="220" show-overflow-tooltip />
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="audit-expand">
                    <div class="expand-label">生成 SQL</div>
                    <pre>{{ row.generatedSql }}</pre>
                    <div v-if="row.errorMessage" class="expand-label">错误信息</div>
                    <pre v-if="row.errorMessage">{{ row.errorMessage }}</pre>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>
</template>

<script setup>
import { inject } from 'vue'

const {
  API_BASE,
  accessibleTables,
  activeModule,
  adminPermissionRequests,
  adminRequestStatus,
  auditExecuteStatus,
  auditLogs,
  auditRules,
  auditRiskLevel,
  manualAuditSql,
  manualAuditResult,
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
  loadAuditRules,
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
  updateAuditRuleStatus,
  updateAuditRuleConfig,
  submitManualAudit,
  exportSqlLogs,
  updateSchemaField,
  uploadFile,
  uploadResult,
  uploading,
  userQuestion,
  xAxisData
} = inject('workbench')
</script>
