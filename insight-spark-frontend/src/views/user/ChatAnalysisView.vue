<template>
<section class="chat-layout">
          <div class="panel chat-panel">
            <div class="panel-header">
              <div>
                <h2>✨ 智能问答助理</h2>
                <p>随时随地，像对话一样探索您的业务数据及图表</p>
              </div>
            </div>

            <div class="message-list" id="chatHistory">
              <div v-for="(msg, index) in messages" :key="index" :class="['message-wrapper', msg.role]">
                <div class="avatar">{{ msg.role === 'system' ? '🤖' : '👤' }}</div>
                <div class="msg-content">
                  <div class="bubble">{{ msg.content }}</div>
                  <div v-if="msg.sql" class="sql-block">
                    <div class="sql-title">生成的思考过程 (SQL)</div>
                    <pre class="sql-code">{{ msg.sql }}</pre>
                  </div>
                </div>
              </div>
            </div>

            <div class="ask-bar">
              <el-input
                  v-model="question"
                  placeholder="试试问我：按省份统计销售额、按日期看趋势、分类占比等..."
                  :disabled="loading"
                  @keyup.enter="sendQuestion"
              >
                <template #append>
                  <el-button type="primary" :loading="loading" @click="sendQuestion">
                    <span v-if="!loading">🚀 发送分析</span>
                    <span v-else>思考中...</span>
                  </el-button>
                </template>
              </el-input>
            </div>
          </div>

          <div class="panel chart-panel">
            <div class="panel-header">
              <div>
                <h2>📊 智能可视化呈现</h2>
                <p>AI 将理解您的意图并推荐最合适的 ECharts 图表类型</p>
              </div>
              <el-tag v-if="currentChartType" type="success" effect="dark" round>{{ chartTypeLabel }}效果</el-tag>
            </div>

            <div id="echarts-container" class="chart-canvas"></div>

            <el-descriptions v-if="lastAnalysis" :column="2" border class="analysis-meta">
              <el-descriptions-item label="数据表">{{ lastAnalysis.tableName }}</el-descriptions-item>
              <el-descriptions-item label="图表">{{ chartTypeLabel }}</el-descriptions-item>
              <el-descriptions-item label="维度">{{ lastAnalysis.fieldMapping?.dimension }}</el-descriptions-item>
              <el-descriptions-item label="指标">{{ lastAnalysis.fieldMapping?.metric }}</el-descriptions-item>
              <el-descriptions-item label="SQL风险">
                <el-tag :type="riskTagType(lastAnalysis.riskLevel)" size="small">
                  {{ lastAnalysis.riskLevel }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="审计说明">{{ lastAnalysis.riskReason }}</el-descriptions-item>
            </el-descriptions>

            <div v-if="lastAnalysis?.graphContext?.length" class="graph-context">
              <h3>GraphRAG 上下文</h3>
              <ul class="suggestion-list">
                <li v-for="item in lastAnalysis.graphContext" :key="item.nodeKey || item">
                  {{ item.label || item }}：{{ item.content || item.sourceId || '' }}
                </li>
              </ul>
            </div>
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
