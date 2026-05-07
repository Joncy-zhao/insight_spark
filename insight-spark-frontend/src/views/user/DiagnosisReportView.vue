<template>
<div class="diagnosis-layout">
  <div class="panel report-generator-panel">
    <div class="panel-header">
      <div>
        <h2>智能诊断报告</h2>
        <p>对对话查询、看板中的异常数据一键触发 GraphRAG 深度多跳推理，生成可追溯的根因分析报告。</p>
      </div>
      <el-tag type="info" effect="dark">GraphRAG + Neo4j</el-tag>
    </div>

    <el-form label-position="top" class="generator-form">
      <el-form-item label="报告详细程度">
        <el-radio-group v-model="reportGenerateForm.detailLevel">
          <el-radio-button label="simple">简易</el-radio-button>
          <el-radio-button label="detailed">详细</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="异常类型">
        <el-select v-model="reportGenerateForm.anomalyType" class="full-width">
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
      </el-form-item>

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

      <el-form-item label="导出内容配置">
        <div class="export-options">
          <el-checkbox v-model="exportOptions.includeSnapshots">包含图表快照</el-checkbox>
          <el-checkbox v-model="exportOptions.includeReasoningLogs">包含推理日志</el-checkbox>
        </div>
      </el-form-item>

      <el-form-item label="PDF 加密设置">
        <el-switch v-model="exportOptions.enablePdfEncryption" />
      </el-form-item>

      <el-form-item label="企业内部文档 / 行业研报">
        <div class="knowledge-upload">
          <el-upload
            :auto-upload="false"
            :show-file-list="true"
            accept=".txt,.md"
            :on-change="onKnowledgeDocChange"
            :limit="1"
          >
            <el-button>选择文档</el-button>
          </el-upload>
          <el-button type="primary" @click="uploadKnowledgeDoc">上传并纳入 GraphRAG</el-button>
        </div>
      </el-form-item>

      <div class="generator-actions">
        <el-button type="primary" :loading="diagnosisLoading" :disabled="!diagnosisForm.metricField" @click="runDiagnosisWithDetail">
          生成诊断报告
        </el-button>
        <el-button :loading="diagnosisLoading" :disabled="!currentDiagnosis" @click="runDiagnosisWithDetail">重新生成报告</el-button>
      </div>
    </el-form>

    <div class="generation-progress">
      <div class="progress-header">
        <span>生成进度</span>
        <span>{{ diagnosisProgress.step }}</span>
      </div>
      <el-progress :percentage="diagnosisProgress.percentage" :status="diagnosisLoading ? '' : (currentDiagnosis ? 'success' : undefined)" />
      <div class="progress-steps">
        <span :class="{ active: diagnosisProgress.percentage >= 10 }">任务创建</span>
        <span :class="{ active: diagnosisProgress.percentage >= 35 }">文档扫描</span>
        <span :class="{ active: diagnosisProgress.percentage >= 70 }">多跳推理</span>
        <span :class="{ active: diagnosisProgress.percentage >= 100 }">报告生成</span>
      </div>
    </div>
  </div>

  <div class="panel diagnosis-result">
    <div class="panel-header">
      <div>
        <h2>报告内容</h2>
        <p>包含深度分析文本、数据波动值、异常关联因素、根因定位结论与改进建议，并支持图表快照与异常节点标注。</p>
      </div>
      <div class="diagnosis-actions" v-if="currentDiagnosis?.id">
        <el-button size="small" @click="exportDiagnosisReportWithOptions('word')">导出 Word</el-button>
        <el-button size="small" type="primary" @click="exportDiagnosisReportWithOptions('pdf')">导出 PDF</el-button>
        <el-button size="small" type="success" @click="previewFullscreenVisible = true">全屏预览</el-button>
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

      <h3>异常关联因素梳理</h3>
      <el-table :data="currentDiagnosis.relatedKnowledge || []" height="180" empty-text="暂无关联知识">
        <el-table-column prop="nodeType" label="类型" width="140" />
        <el-table-column prop="label" label="名称" min-width="160" />
        <el-table-column prop="sourceType" label="来源" width="100" />
        <el-table-column prop="content" label="说明" min-width="260" show-overflow-tooltip />
      </el-table>

      <h3>根因定位结论</h3>
      <el-table :data="currentDiagnosis.rootCauses || []" height="180" empty-text="暂无根因假设">
        <el-table-column prop="level" label="等级" width="90" />
        <el-table-column prop="causeType" label="类型" min-width="150" />
        <el-table-column prop="impactField" label="影响字段" min-width="120" />
        <el-table-column prop="confidence" label="置信度" width="90" />
        <el-table-column prop="evidence" label="证据" min-width="280" show-overflow-tooltip />
      </el-table>

      <h3>GraphRAG 推理过程日志</h3>
      <el-alert v-if="currentDiagnosis.graphReasoningPath" type="info" :closable="false" :title="currentDiagnosis.graphReasoningPath" />
      <el-timeline class="reasoning-timeline">
        <el-timeline-item
          v-for="log in currentDiagnosis.reasoningLogs || diagnosisProgress.logs"
          :key="`${log.step}-${log.title}`"
          :timestamp="`Step ${log.step}`"
          type="primary"
        >
          <strong>{{ log.title }}</strong>
          <p>{{ log.detail }}</p>
        </el-timeline-item>
      </el-timeline>
      <el-table :data="currentDiagnosis.graphEdges || currentDiagnosis.graphPath?.edges || []" height="180" empty-text="暂无图谱边">
        <el-table-column prop="fromKey" label="起点" min-width="220" show-overflow-tooltip />
        <el-table-column prop="relationType" label="关系" width="120" />
        <el-table-column prop="toKey" label="终点" min-width="220" show-overflow-tooltip />
        <el-table-column prop="weight" label="权重" width="90" />
      </el-table>

      <h3>改进建议</h3>
      <ul class="suggestion-list">
        <li v-for="suggestion in currentDiagnosis.suggestions || []" :key="suggestion">{{ suggestion }}</li>
      </ul>

      <h3>图表快照与异常节点标注</h3>
      <div v-if="chartSnapshot?.imageDataUrl" class="chart-snapshot" @click="restoreDiagnosisBinding(currentDiagnosis)">
        <img :src="chartSnapshot.imageDataUrl" alt="诊断报告绑定图表快照" />
        <div class="chart-snapshot-meta">
          <strong>{{ chartSnapshot.chartType || '图表' }}</strong>
          <span>点击回溯原始对话查询/看板</span>
        </div>
      </div>
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
        <h2>报告管理</h2>
        <p>展示个人历史诊断报告，按生成时间排序，支持筛选、预览、重新生成与跳转回原始分析页面。</p>
      </div>
      <div class="history-actions">
        <el-select v-model="reportFilter.type" placeholder="报告类型" clearable style="width: 130px;">
          <el-option label="简易" value="simple" />
          <el-option label="详细" value="detailed" />
        </el-select>
        <el-select v-model="reportFilter.anomaly" placeholder="异常类型" clearable style="width: 130px;">
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
        <el-button @click="loadDiagnosisReports">刷新</el-button>
      </div>
    </div>

    <el-table :data="filteredDiagnosisReports" height="360" empty-text="暂无历史报告" @row-click="loadDiagnosisReportDetail">
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="metricField" label="指标" width="110" />
      <el-table-column prop="createdAt" label="生成时间" min-width="170" />
      <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
      <el-table-column label="报告绑定" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="bindBackToSource(row)">跳转原始对话/看板</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>

  <el-dialog v-model="previewFullscreenVisible" title="诊断报告全屏预览" width="90%" top="4vh">
    <pre class="report-markdown full-preview">{{ currentDiagnosis?.reportMarkdown || '暂无内容' }}</pre>
  </el-dialog>
</div>
</template>

<script setup>
import { computed, inject, ref } from 'vue'
import { ElMessage } from 'element-plus'

const {
  activeModule,
  currentDiagnosis,
  dateFields,
  diagnosisForm,
  diagnosisLoading,
  diagnosisProgress,
  diagnosisReports,
  dimensionCandidateFields,
  exportDiagnosisReport,
  knowledgeDocFile,
  loadDiagnosisReportDetail,
  loadDiagnosisReports,
  numericFields,
  onKnowledgeDocChange,
  restoreDiagnosisBinding,
  runDiagnosis,
  selectedTableName,
  tables,
  uploadKnowledgeDoc
} = inject('workbench')

const previewFullscreenVisible = ref(false)
const reportFilter = ref({ type: '', anomaly: '' })
const reportGenerateForm = ref({ detailLevel: 'detailed', anomalyType: 'fluctuation' })
const exportOptions = ref({
  includeSnapshots: true,
  includeReasoningLogs: true,
  enablePdfEncryption: false
})

const filteredDiagnosisReports = computed(() => {
  return (diagnosisReports.value || []).filter((report) => {
    const typePass = !reportFilter.value.type || report?.detailLevel === reportFilter.value.type
    const anomalyPass = !reportFilter.value.anomaly || report?.anomalyType === reportFilter.value.anomaly
    return typePass && anomalyPass
  })
})

const chartSnapshot = computed(() => {
  const snapshot = currentDiagnosis.value?.chartSnapshot
  if (!snapshot || typeof snapshot !== 'string') return snapshot
  try {
    return JSON.parse(snapshot)
  } catch {
    return null
  }
})

const runDiagnosisWithDetail = async () => {
  diagnosisForm.value.detailLevel = reportGenerateForm.value.detailLevel
  diagnosisForm.value.anomalyType = reportGenerateForm.value.anomalyType
  await runDiagnosis()
}

const exportDiagnosisReportWithOptions = async (format) => {
  const payload = {
    includeSnapshots: exportOptions.value.includeSnapshots,
    includeReasoningLogs: exportOptions.value.includeReasoningLogs,
    enablePdfEncryption: format === 'pdf' ? exportOptions.value.enablePdfEncryption : false
  }
  await exportDiagnosisReport(format, payload)
}

const bindBackToSource = (row) => {
  restoreDiagnosisBinding(row)
  ElMessage.success(`已定位到报告《${row.title || row.id}》绑定的原始分析页面`)
}
</script>

<style scoped>
.generator-form {
  margin-top: 14px;
}

.generator-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.generation-progress {
  margin-top: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 12px;
  background: #fafafa;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  color: #475569;
  margin-bottom: 8px;
}

.progress-steps {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  font-size: 12px;
  color: #64748b;
}

.progress-steps .active {
  color: #2563eb;
  font-weight: 700;
}

.history-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.full-preview {
  max-height: 72vh;
}

.export-options {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.knowledge-upload {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.reasoning-timeline {
  margin: 12px 0;
}

.reasoning-timeline p {
  margin: 4px 0 0;
}

.chart-snapshot {
  margin-bottom: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  cursor: pointer;
}

.chart-snapshot img {
  display: block;
  width: 100%;
  max-height: 280px;
  object-fit: contain;
  background: #fff;
}

.chart-snapshot-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  color: #475569;
  border-top: 1px solid #e5e7eb;
}
</style>
