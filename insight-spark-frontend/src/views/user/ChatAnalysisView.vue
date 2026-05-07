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
              <div class="chart-actions">
                <el-tag v-if="currentChartType" type="success" effect="dark" round>
                  {{ chartTypeLabel }}效果
                </el-tag>
                <el-select v-model="chartSortMode" size="small" style="width: 150px;" @change="() => lastAnalysis?.data?.length && renderChart(lastAnalysis.data, currentChartType)">
                  <el-option label="按数值降序" value="desc" />
                  <el-option label="按数值升序" value="asc" />
                  <el-option label="按名称排序" value="name" />
                </el-select>
                <el-button v-if="lastAnalysis?.data?.length" size="small" @click="exportChartAsImage">导出图片</el-button>
                <el-button
                    v-if="canDiagnoseLastAnalysis"
                    type="warning"
                    :loading="diagnosisLoading"
                    @click="diagnoseFromLastAnalysis"
                >
                  一键生成诊断报告
                </el-button>
              </div>
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

            <el-card v-if="currentDiagnosis" class="diagnosis-preview-card" shadow="hover" style="margin: 18px 0 0 0;">
              <template #header>
                <div style="display: flex; align-items: center; gap: 8px;">
                  <el-icon><i class="el-icon-document"></i></el-icon>
                  <span>最新诊断报告预览</span>
                </div>
              </template>
              <div>
                <div style="font-weight: bold; font-size: 16px; margin-bottom: 4px;">{{ currentDiagnosis.title || '智能诊断报告' }}</div>
                <div style="color: #888; font-size: 13px; margin-bottom: 8px;">生成时间：{{ currentDiagnosis.createdAt ? currentDiagnosis.createdAt.slice(0, 19).replace('T', ' ') : '' }}</div>
                <div style="margin-bottom: 8px;">摘要：{{ currentDiagnosis.summary || '暂无摘要' }}</div>
                <el-button size="small" type="primary" @click="activeModule = 'diagnosis'">查看完整报告</el-button>
              </div>
            </el-card>

            <div v-if="lastAnalysis?.graphContext?.length" class="graph-context">
              <h3>GraphRAG 上下文</h3>
              <ul class="suggestion-list">
                <li v-for="item in lastAnalysis.graphContext" :key="item.nodeKey || item">
                  {{ item.label || item }}：{{ item.content || item.sourceId || '' }}
                </li>
              </ul>
            </div>
          </div>
          <el-dialog v-model="diagnosisPickerVisible" title="选择诊断字段" width="520px">
            <el-form label-position="top">
              <el-form-item label="指标字段">
                <el-select v-model="diagnosisPickerForm.metricField" class="full-width" placeholder="选择数值指标">
                  <el-option v-for="field in numericFields" :key="field.columnName" :label="field.displayName" :value="field.columnName" />
                </el-select>
              </el-form-item>
              <el-form-item label="维度字段">
                <el-select v-model="diagnosisPickerForm.dimensionFields" multiple class="full-width" placeholder="选择拆解维度">
                  <el-option v-for="field in dimensionCandidateFields" :key="field.columnName" :label="field.displayName" :value="field.columnName" />
                </el-select>
              </el-form-item>
              <el-form-item label="时间字段">
                <el-select v-model="diagnosisPickerForm.timeField" clearable class="full-width" placeholder="可选">
                  <el-option v-for="field in dateFields" :key="field.columnName" :label="field.displayName" :value="field.columnName" />
                </el-select>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="diagnosisPickerVisible = false">取消</el-button>
              <el-button type="primary" :loading="diagnosisLoading" @click="confirmDiagnosisPicker">生成诊断报告</el-button>
            </template>
          </el-dialog>
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
  chartSortMode,
  chatDom,
  createDatasource,
  currentChartType,
  currentDiagnosis,
  data,
  datasourceForm,
  dateFields,
  detail,
  diagnosisForm,
  diagnosisPickerVisible,
  diagnosisPickerForm,
  diagnosisLoading,
  diagnosisReports,
  dimensionCandidateFields,
  canDiagnoseLastAnalysis,
  confirmDiagnosisPicker,
  diagnoseFromLastAnalysis,
  field,
  fieldLabel,
  fields,
  exportChartAsImage,
  fillCurrentDatasource,
  formData,
  isAdminModule,
  isAdminUser,
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
<style scoped>
.chart-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
