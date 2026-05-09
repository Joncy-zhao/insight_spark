<template>
<section class="chat-layout">
          <div class="panel chat-panel">
            <div class="panel-header">
              <div>
                <h2>✨ 智能问答助理</h2>
                <p>随时随地，像对话一样探索您的业务数据及图表</p>
              </div>
            </div>

            <div class="chat-datasource-bar">
              <div class="chat-datasource-label">数据源</div>
              <el-select
                  v-model="selectedTableName"
                  placeholder="Select data source"
                  class="chat-datasource-select"
                  clearable
                  filterable
              >
                <el-option-group v-if="uploadTables.length" label="Upload tables">
                  <el-option
                      v-for="table in uploadTables"
                      :key="table.tableName"
                      :label="table.displayName"
                      :value="table.tableName"
                  />
                </el-option-group>
                <el-option-group v-if="officialQueryTables.length" label="Official tables">
                  <el-option
                      v-for="table in officialQueryTables"
                      :key="table.tableName"
                      :label="table.displayName"
                      :value="table.tableName"
                  />
                </el-option-group>
              </el-select>
            </div>

            <div class="message-list" id="chatHistory">
              <div v-for="(msg, index) in messages" :key="index" :class="['message-wrapper', msg.role]">
                <div class="avatar">{{ msg.role === 'system' ? '🤖' : '👤' }}</div>
                <div class="msg-content">
                  <div class="bubble">{{ msg.content }}</div>
                  <details v-if="msg.thinkingLogs?.length" class="thinking-details" :open="msg.thinkingCollapsed === false">
                    <summary>查看思考过程（{{ msg.thinkingLogs.length }}步）</summary>
                    <ol class="thinking-list">
                      <li v-for="(line, lineIndex) in msg.thinkingLogs" :key="`${index}-${lineIndex}`">
                        {{ line }}
                      </li>
                    </ol>
                  </details>
                  <div v-if="msg.sql" class="sql-block">
                    <div class="sql-head">
                      <div class="sql-title">生成的 SQL</div>
                      <el-button size="small" text type="primary" @click="copySqlToClipboard(msg.sql)">复制</el-button>
                    </div>
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
              <el-button v-if="loading" type="danger" plain class="stop-btn" @click="stopQuestionGeneration">
                停止生成
              </el-button>
            </div>
            <div class="recent-queries">
              <div class="recent-title">最近查询</div>
              <div class="recent-toolbar">
                <el-input
                    v-model.trim="recentChatQueryKeyword"
                    placeholder="按问题关键词搜索历史"
                    clearable
                    size="small"
                    @keyup.enter="searchRecentChatQueries"
                    @clear="resetRecentChatQuerySearch"
                />
                <el-button size="small" type="primary" @click="searchRecentChatQueries">搜索</el-button>
                <el-button size="small" @click="resetRecentChatQuerySearch">重置</el-button>
              </div>
              <div class="recent-list">
                <el-tag
                    v-for="item in recentChatQueries"
                    :key="item.id"
                    effect="plain"
                    class="recent-tag"
                    @click="reuseChatQuestion(item)"
                >
                  <span class="recent-main">{{ item.question }}</span>
                  <small>（{{ item.tableName || '未指定数据表' }} · {{ formatChatHistoryTime(item.createdAt) }}）</small>
                  <button
                      type="button"
                      class="recent-delete"
                      @click.stop="removeRecentChatQuery(item)"
                  >
                    ×
                  </button>
                </el-tag>
                <div v-if="!recentChatQueries.length" class="recent-empty">暂无历史记录</div>
              </div>
              <el-pagination
                  class="recent-pagination"
                  layout="total, sizes, prev, pager, next"
                  :total="recentChatQueryTotal"
                  :current-page="recentChatQueryPage"
                  :page-size="recentChatQueryPageSize"
                  :page-sizes="[5, 8, 10, 20]"
                  size="small"
                  @current-change="handleRecentChatPageChange"
                  @size-change="handleRecentChatPageSizeChange"
              />
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
                <el-button
                    v-if="canRegenerateLastAnalysis"
                    size="small"
                    type="primary"
                    plain
                    :disabled="loading || isStreaming"
                    @click="regenerateLastAnalysis"
                >
                  重新生成
                </el-button>
                <el-select v-model="chartSortMode" size="small" style="width: 150px;" @change="() => lastAnalysis?.data?.length && renderChart(lastAnalysis.data, currentChartType)">
                  <el-option label="按数值降序" value="desc" />
                  <el-option label="按数值升序" value="asc" />
                  <el-option label="按名称排序" value="name" />
                </el-select>
                <el-button v-if="lastAnalysis?.data?.length" size="small" @click="exportChartAsImage">导出图片</el-button>
                <el-button
                    v-if="canPinLastAnalysis"
                    size="small"
                    type="success"
                    plain
                    :disabled="loading || isStreaming"
                    @click="openPinDialog"
                >
                  钉入看板
                </el-button>
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
        
          <el-dialog v-model="pinDialogVisible" title="钉入我的看板" width="520px">
            <el-form label-position="top">
              <el-form-item label="目标看板">
                <el-select v-model="pinDashboardId" class="full-width" placeholder="请选择看板">
                  <el-option
                      v-for="dashboard in dashboardOptions"
                      :key="dashboard.id"
                      :label="dashboard.name + (dashboard.isPublic ? '（公开）' : '')"
                      :value="dashboard.id"
                  />
                </el-select>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="pinDialogVisible = false">取消</el-button>
              <el-button type="primary" :loading="pinning" @click="pinChartToDashboard">确认钉入</el-button>
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
  canRegenerateLastAnalysis,
  canPinLastAnalysis,
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
  isStreaming,
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
  uploadTables,
  officialQueryTables,
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
  copySqlToClipboard,
  recentChatQueries,
  recentChatQueryKeyword,
  recentChatQueryPage,
  recentChatQueryPageSize,
  recentChatQueryTotal,
  reuseChatQuestion,
  removeRecentChatQuery,
  searchRecentChatQueries,
  resetRecentChatQuerySearch,
  handleRecentChatPageChange,
  handleRecentChatPageSizeChange,
  formatChatHistoryTime,
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
  regenerateLastAnalysis,
  openPinDialog,
  pinChartToDashboard,
  pinDialogVisible,
  pinning,
  pinDashboardId,
  dashboardOptions,
  stopQuestionGeneration,
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
.chat-datasource-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.chat-datasource-label {
  flex: 0 0 auto;
  color: #6b7280;
  font-size: 13px;
}
.chat-datasource-select {
  width: 320px;
  max-width: 100%;
}
.ask-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ask-bar :deep(.el-input) {
  flex: 1;
}
.stop-btn {
  flex: 0 0 auto;
}
.recent-queries {
  margin-top: 10px;
}
.recent-title {
  margin-bottom: 8px;
  color: #6b7280;
  font-size: 12px;
}
.recent-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.recent-toolbar :deep(.el-input) {
  flex: 1;
}
.recent-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.recent-empty {
  color: #9ca3af;
  font-size: 12px;
}
.recent-tag {
  cursor: pointer;
  max-width: 100%;
}
.recent-main {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-tag :deep(.el-tag__content) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.recent-tag small {
  color: #9ca3af;
}
.recent-delete {
  border: 0;
  background: transparent;
  color: #9ca3af;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
}
.recent-delete:hover {
  color: #ef4444;
}
.recent-pagination {
  margin-top: 10px;
  justify-content: flex-end;
}
.thinking-details {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.thinking-details summary {
  cursor: pointer;
  color: #374151;
  font-size: 13px;
}
.thinking-list {
  margin: 8px 0 0 18px;
  max-height: 140px;
  overflow: auto;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.6;
}
.sql-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: #1f2937;
  border-bottom: 1px solid #374151;
}
.sql-head .sql-title {
  padding: 0;
  border: 0;
}
</style>
