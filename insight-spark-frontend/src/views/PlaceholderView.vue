<template>
<section class="placeholder-panel panel">
          <h2>{{ moduleTitle }}</h2>
          <p>{{ moduleSubtitle }}</p>
          <el-steps :active="placeholderStep" finish-status="success" align-center>
            <el-step title="接口设计" />
            <el-step title="数据表设计" />
            <el-step title="页面实现" />
            <el-step title="联调验收" />
          </el-steps>
          <div class="roadmap-text">
            当前已完成 P0/P1 所需的数据表元信息基础。继续向后实现时，这个模块会直接复用上传数据表、字段语义和用户权限信息。
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
