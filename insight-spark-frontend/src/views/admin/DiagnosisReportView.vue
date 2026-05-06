<template>
<section class="diagnosis-layout">
          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>一键智能诊断</h2>
                <p>选择指标、维度和时间字段，Python AI 服务会识别异常点、贡献维度和趋势变化。</p>
              </div>
            </div>

            <el-form label-position="top">
              <el-form-item label="诊断数据表">
                <el-select v-model="selectedTableName" placeholder="选择数据表" class="full-width">
                  <el-option v-for="table in tables" :key="table.tableName" :label="table.displayName" :value="table.tableName" />
                </el-select>
              </el-form-item>
              <el-form-item label="指标字段">
                <el-select v-model="diagnosisForm.metricField" placeholder="选择数值指标" class="full-width">
                  <el-option
                      v-for="field in numericFields"
                      :key="field.columnName"
                      :label="field.displayName"
                      :value="field.columnName"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="维度字段">
                <el-select v-model="diagnosisForm.dimensionFields" multiple placeholder="选择拆解维度" class="full-width">
                  <el-option
                      v-for="field in dimensionCandidateFields"
                      :key="field.columnName"
                      :label="field.displayName"
                      :value="field.columnName"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="时间字段">
                <el-select v-model="diagnosisForm.timeField" placeholder="可选" clearable class="full-width">
                  <el-option
                      v-for="field in dateFields"
                      :key="field.columnName"
                      :label="field.displayName"
                      :value="field.columnName"
                  />
                </el-select>
              </el-form-item>
              <el-button type="primary" :loading="diagnosisLoading" :disabled="!diagnosisForm.metricField" @click="runDiagnosis">
                生成诊断报告
              </el-button>
            </el-form>
          </div>

          <div class="panel diagnosis-result">
            <div class="panel-header">
              <div>
                <h2>诊断结果</h2>
                <p>展示统计摘要、异常检测、维度贡献和建议动作。</p>
              </div>
              <div class="diagnosis-actions" v-if="currentDiagnosis?.id">
                <el-button size="small" @click="exportDiagnosisReport('markdown')">导出 Markdown</el-button>
                <el-button size="small" type="primary" @click="exportDiagnosisReport('word')">导出 Word</el-button>
                <el-button size="small" type="success" @click="exportDiagnosisReport('pdf')">PDF 打印版</el-button>
                <el-tag type="success">报告 #{{ currentDiagnosis.id }}</el-tag>
              </div>
            </div>

            <el-empty v-if="!currentDiagnosis" description="尚未生成诊断报告" />
            <template v-else>
              <el-alert type="success" :closable="false" show-icon :title="currentDiagnosis.summary" />
              <el-descriptions class="diagnosis-stats" :column="3" border>
                <el-descriptions-item label="有效记录">{{ currentDiagnosis.statistics?.count }}</el-descriptions-item>
                <el-descriptions-item label="指标合计">{{ currentDiagnosis.statistics?.total }}</el-descriptions-item>
                <el-descriptions-item label="平均值">{{ currentDiagnosis.statistics?.avg }}</el-descriptions-item>
                <el-descriptions-item label="最大值">{{ currentDiagnosis.statistics?.max }}</el-descriptions-item>
                <el-descriptions-item label="最小值">{{ currentDiagnosis.statistics?.min }}</el-descriptions-item>
                <el-descriptions-item label="标准差">{{ currentDiagnosis.statistics?.std }}</el-descriptions-item>
              </el-descriptions>

              <h3>异常点</h3>
              <el-table :data="currentDiagnosis.anomalies || []" height="180" empty-text="未发现明显异常">
                <el-table-column prop="level" label="等级" width="90" />
                <el-table-column prop="rowIndex" label="行号" width="80" />
                <el-table-column prop="metricValue" label="指标值" width="110" />
                <el-table-column prop="zScore" label="Z-Score" width="100" />
                <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
              </el-table>

              <h3>维度贡献</h3>
              <div class="contribution-list">
                <div v-for="item in currentDiagnosis.dimensionContributions || []" :key="item.dimensionField" class="contribution-block">
                  <div class="contribution-title">{{ fieldLabel(item.dimensionField) }}</div>
                  <el-table :data="item.topItems" size="small" height="160">
                    <el-table-column prop="name" label="分类" min-width="120" />
                    <el-table-column prop="value" label="贡献值" width="120" />
                    <el-table-column prop="share" label="占比%" width="100" />
                  </el-table>
                </div>
              </div>

              <h3>建议动作</h3>
              <ul class="suggestion-list">
                <li v-for="suggestion in currentDiagnosis.suggestions || []" :key="suggestion">{{ suggestion }}</li>
              </ul>

              <h3>GraphRAG 关联知识</h3>
              <el-alert v-if="currentDiagnosis.graphReasoningPath" type="info" :closable="false" :title="currentDiagnosis.graphReasoningPath" />
              <el-table :data="currentDiagnosis.relatedKnowledge || []" height="180" empty-text="暂无关联知识">
                <el-table-column prop="nodeType" label="类型" width="140" />
                <el-table-column prop="label" label="名称" min-width="160" />
                <el-table-column prop="sourceType" label="来源" width="100" />
                <el-table-column prop="content" label="说明" min-width="260" show-overflow-tooltip />
              </el-table>


              <h3>关联证据来源</h3>
              <el-empty v-if="!(currentDiagnosis.evidenceSources && currentDiagnosis.evidenceSources.length)" description="暂无证据来源" />
              <ol v-else class="evidence-list">
                <li v-for="(item, index) in currentDiagnosis.evidenceSources" :key="`${index}-${item}`">{{ item }}</li>
              </ol>

              <h3>根因假设</h3>
              <el-table :data="currentDiagnosis.rootCauses || []" height="180" empty-text="暂无根因假设">
                <el-table-column prop="level" label="等级" width="90" />
                <el-table-column prop="causeType" label="类型" min-width="150" />
                <el-table-column prop="impactField" label="影响字段" min-width="120" />
                <el-table-column prop="confidence" label="置信度" width="90" />
                <el-table-column prop="evidence" label="证据" min-width="280" show-overflow-tooltip />
              </el-table>

              <h3>关联因素图表块</h3>
              <el-table :data="currentDiagnosis.factorChartBlocks || []" height="180" empty-text="暂无图表块">
                <el-table-column prop="title" label="图表块" min-width="220" />
                <el-table-column prop="chartType" label="类型" width="100" />
                <el-table-column label="数据点" width="90">
                  <template #default="{ row }">{{ row.data?.length || 0 }}</template>
                </el-table-column>
              </el-table>

              <h3>报告正文</h3>
              <pre class="report-markdown">{{ currentDiagnosis.reportMarkdown }}</pre>
            </template>
          </div>

          <div class="panel report-history">
            <div class="panel-header">
              <div>
                <h2>历史诊断报告</h2>
                <p>诊断结果会沉淀到数据库，便于追溯和复用。</p>
              </div>
              <el-button @click="loadDiagnosisReports">刷新</el-button>
            </div>
            <el-table :data="diagnosisReports" height="360" empty-text="暂无历史报告" @row-click="loadDiagnosisReportDetail">
              <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
              <el-table-column prop="metricField" label="指标" width="110" />
              <el-table-column prop="createdAt" label="生成时间" min-width="170" />
              <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
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
  exportDiagnosisReport,
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
.evidence-list {
  margin: 8px 0 16px;
  padding-left: 20px;
  color: #334155;
  line-height: 1.7;
}

</style>
