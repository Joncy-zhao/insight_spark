<template>
  <section class="workspace-grid upload-grid">
    <div class="panel upload-panel">
      <div class="panel-header">
        <div>
          <h2>Excel / CSV 数据上传</h2>
          <p>拖拽或点击上传业务文件，系统自动解析、建表、同步字段语义，并作为自然语言查询的数据源。</p>
        </div>
        <el-tag type="info">最多 5 个文件</el-tag>
      </div>

      <el-upload
        drag
        multiple
        :auto-upload="false"
        :show-file-list="true"
        :limit="5"
        accept=".xlsx,.xls,.csv"
        :on-change="handleUploadChange"
        :on-remove="handleFileRemove"
        :before-upload="validateFileBeforeUpload"
      >
        <div class="upload-drop-title">拖拽文件到此处</div>
        <div class="upload-drop-subtitle">支持 .xlsx / .xls / .csv，单文件最大 100MB，可批量上传并自动合并。</div>
      </el-upload>

      <el-alert
        v-if="fileValidation"
        class="file-validation"
        :type="fileValidation.valid ? 'success' : 'error'"
        :title="fileValidation.valid ? '文件校验通过' : '文件校验失败'"
        :closable="false"
      >
        <div v-if="fileValidation.valid">文件大小：{{ fileValidation.fileSizeMB }} MB</div>
        <div v-for="error in fileValidation.errors || []" :key="error" class="validation-item error">{{ error }}</div>
        <div v-for="warning in fileValidation.warnings || []" :key="warning" class="validation-item warning">{{ warning }}</div>
      </el-alert>

      <el-form label-position="top" class="merge-form">
        <el-form-item label="多文件处理方式">
          <el-radio-group v-model="uploadMergeMode">
            <el-radio-button value="SAME_HEADER">相同表头合并</el-radio-button>
            <el-radio-button value="KEY_JOIN">指定字段关联 / VLOOKUP</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="uploadMergeMode === 'KEY_JOIN'" label="关联字段">
          <el-input v-model="uploadJoinKey" placeholder="例如：客户ID、订单号、商品编码" />
        </el-form-item>
        <el-form-item label="自定义数据表命名">
          <el-input v-model="uploadDisplayName" maxlength="80" show-word-limit placeholder="例如：2026Q2 电商订单明细" />
        </el-form-item>
        <el-form-item label="一句话零代码业务建模">
          <el-input
            v-model="modelRequirement"
            type="textarea"
            :rows="3"
            placeholder="例如：搭建电商用户生命周期分析模型，包含获客、激活、留存、转化、复购，按渠道、品类、客群拆解"
          />
        </el-form-item>
      </el-form>

      <div class="upload-actions">
        <el-button type="primary" :loading="uploading" :disabled="!uploadFile && !uploadFiles.length" @click="submitUpload">
          解析入库 / 合并建模
        </el-button>
        <el-button :disabled="!selectedTableName || !modelRequirement" @click="createBusinessModel">仅生成业务模型</el-button>
        <el-button @click="loadTables">刷新数据表</el-button>
      </div>

      <div v-if="uploadTask" class="upload-progress">
        <el-progress
          :percentage="Number(uploadTask.progress || 0)"
          :status="uploadTask.status === 'FAILED' ? 'exception' : uploadTask.status === 'SUCCESS' ? 'success' : undefined"
        />
        <div class="upload-progress-text">{{ uploadTask.message || uploadTask.status }}</div>
      </div>

      <el-alert
        v-if="uploadResult"
        class="result-alert"
        type="success"
        show-icon
        :closable="false"
        :title="`已生成数据表：${uploadResult.displayName || uploadResult.tableName}`"
        :description="`物理表 ${uploadResult.tableName}，共 ${uploadResult.rowCount || 0} 行、${uploadResult.fieldCount || 0} 个字段。`"
      />
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>我的数据表</h2>
          <p>展示上传文件生成的数据表，可重命名、删除、快速查询，并同步给对话分析和权限模块。</p>
        </div>
      </div>
      <el-table :data="tables" height="320" empty-text="暂无数据表，请先上传文件" @row-click="selectTable">
        <el-table-column label="显示名称" min-width="170">
          <template #default="{ row }">
            <el-input v-model="row.displayName" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="rowCount" label="行数" width="90" />
        <el-table-column prop="fieldCount" label="字段" width="80" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click.stop="renameDataTable(row)">保存</el-button>
            <el-button size="small" type="primary" @click.stop="quickQuery(row)">查询</el-button>
            <el-button size="small" @click.stop="exportDataTable(row)">导出</el-button>
            <el-button size="small" type="danger" @click.stop="deleteDataTable(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="selectedTableName" class="panel">
      <div class="panel-header">
        <div>
          <h2>数据质量评估</h2>
          <p>自动检测空值率、异常值、重复率，生成质量评分和清洗建议。</p>
        </div>
        <el-button :loading="qualityLoading" @click="loadDataQuality">刷新评估</el-button>
      </div>
      <div v-if="dataQuality" class="quality-summary">
        <div class="quality-score">
          <div class="score-circle" :class="getQualityClass(dataQuality.qualityScore)">
            <span class="score-value">{{ dataQuality.qualityScore }}</span>
            <span class="score-label">分</span>
          </div>
          <div class="score-level">{{ dataQuality.qualityLevel }}</div>
        </div>
        <div class="quality-metrics">
          <div class="metric-item"><div class="metric-label">总行数</div><div class="metric-value">{{ dataQuality.totalRows }}</div></div>
          <div class="metric-item"><div class="metric-label">总字段数</div><div class="metric-value">{{ dataQuality.totalFields }}</div></div>
          <div class="metric-item"><div class="metric-label">平均空值率</div><div class="metric-value">{{ dataQuality.avgNullRate }}%</div></div>
          <div class="metric-item"><div class="metric-label">空值字段</div><div class="metric-value">{{ dataQuality.emptyFieldCount }}</div></div>
          <div class="metric-item"><div class="metric-label">异常字段</div><div class="metric-value">{{ dataQuality.anomalyFieldCount }}</div></div>
        </div>
        <div class="quality-suggestions">
          <div class="suggestion-title">改进建议</div>
          <div v-for="suggestion in dataQuality.suggestions || []" :key="suggestion" class="suggestion-item">{{ suggestion }}</div>
        </div>
      </div>
      <el-empty v-else description="选择数据表后点击刷新评估" />
    </div>

    <div class="panel preview-panel">
      <div class="panel-header">
        <div>
          <h2>数据预览与清洗</h2>
          <p>展示解析后的数据，支持分页查看、批量替换、删除无效行和字段转换。</p>
        </div>
        <div class="preview-actions">
          <el-button size="small" :disabled="!selectedTableName" @click="showBatchReplaceDialog">批量替换</el-button>
          <el-button size="small" :disabled="!selectedTableName" @click="showTransformDialog">数据转换</el-button>
          <el-button size="small" type="danger" :disabled="!selectedRows.length" @click="deleteSelectedRows">删除选中行</el-button>
        </div>
      </div>
      <el-table
        :data="previewRows"
        height="360"
        empty-text="请选择数据表"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="48" />
        <el-table-column prop="sys_id" label="ID" width="80" fixed />
        <el-table-column v-for="column in previewColumns.filter(item => item !== 'sys_id')" :key="column" :prop="column" :label="column" min-width="130">
          <template #default="{ row }">
            <span :class="{ 'anomaly-value': isAnomalyValue(column, row[column]) }">{{ row[column] }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="preview-pagination"
        layout="total, sizes, prev, pager, next"
        :total="previewTotal"
        :current-page="previewPage"
        :page-size="previewPageSize"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="handlePreviewPageChange"
        @size-change="handlePreviewSizeChange"
      />
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>字段语义与知识图谱绑定</h2>
          <p>字段元信息将同步到 Neo4j 知识图谱，用于 Text-to-SQL、GraphRAG 和诊断推理。</p>
        </div>
      </div>
      <el-table :data="fields" height="360" empty-text="请选择数据表">
        <el-table-column label="业务字段" min-width="150">
          <template #default="{ row }"><el-input v-model="row.displayName" size="small" /></template>
        </el-table-column>
        <el-table-column prop="columnName" label="物理字段" width="110" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-select v-model="row.fieldType" size="small">
              <el-option label="文本" value="TEXT" />
              <el-option label="数值" value="NUMBER" />
              <el-option label="日期" value="DATE" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="字段说明" min-width="180">
          <template #default="{ row }"><el-input v-model="row.fieldComment" size="small" placeholder="例如：订单金额、销售日期、客户地区" /></template>
        </el-table-column>
        <el-table-column label="敏感" width="80">
          <template #default="{ row }"><el-switch v-model="row.sensitive" /></template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="updateUploadField(row)">保存</el-button>
            <el-button size="small" @click="showFieldStatistics(row)">统计</el-button>
            <el-button size="small" type="danger" @click="removeColumn(row)">删列</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="selectedFieldStats" class="panel">
      <div class="panel-header">
        <div>
          <h2>字段统计：{{ selectedFieldStats.columnName }}</h2>
          <p>查看字段分布、空值率、重复率和数值异常。</p>
        </div>
        <el-button size="small" @click="selectedFieldStats = null">关闭</el-button>
      </div>
      <div class="stats-grid">
        <div v-for="item in statisticCards" :key="item.label" class="stat-card">
          <div class="stat-label">{{ item.label }}</div>
          <div class="stat-value">{{ item.value }}</div>
        </div>
      </div>
      <div v-if="selectedFieldStats.anomalies?.length" class="anomalies-section">
        <div class="section-title">检测到 {{ selectedFieldStats.anomalies.length }} 个异常值</div>
        <el-table :data="selectedFieldStats.anomalies.slice(0, 10)" height="200" size="small">
          <el-table-column prop="rowId" label="行ID" width="90" />
          <el-table-column prop="value" label="异常值" width="140" />
          <el-table-column prop="reason" label="原因" min-width="220" />
        </el-table>
      </div>
      <div class="distribution-section">
        <div class="section-title">数据分布</div>
        <div ref="distributionChart" class="distribution-chart"></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>商业分析模板</h2>
          <p>上传模板并用自然语言指令生成指标、维度、公式和图表建议。</p>
        </div>
        <el-button @click="loadAnalysisTemplates">刷新模板</el-button>
      </div>
      <el-upload :auto-upload="false" :show-file-list="true" accept=".txt,.md" :limit="1" :on-change="onTemplateFileChange">
        <el-button>选择模板文件</el-button>
        <template #tip><div class="el-upload__tip">支持 .txt / .md，例如电商生命周期分析模板。</div></template>
      </el-upload>
      <div class="upload-actions">
        <el-button type="primary" :disabled="!templateFile" @click="uploadAnalysisTemplate">上传模板</el-button>
      </div>
      <el-form label-position="top" class="merge-form">
        <el-form-item label="选择模板">
          <el-select v-model="selectedTemplateId" placeholder="请选择模板" class="full-width">
            <el-option v-for="template in analysisTemplates" :key="template.id" :label="template.templateName" :value="template.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="一句话需求">
          <el-input v-model="templateRequirement" type="textarea" :rows="3" placeholder="我想分析电商用户生命周期，给我按照上传的模板进行创建" />
        </el-form-item>
      </el-form>
      <el-button type="success" :disabled="!selectedTableName || !selectedTemplateId || !templateRequirement" @click="createBusinessModelFromTemplate">
        按模板生成业务模型
      </el-button>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>个人业务模型</h2>
          <p>保存一句话建模产生的指标口径、维度体系和分析逻辑，可发布到企业模型库。</p>
        </div>
        <el-button @click="loadBusinessModels">刷新</el-button>
      </div>
      <el-table :data="businessModels" height="320" empty-text="暂无业务模型">
        <el-table-column prop="modelName" label="模型名称" min-width="180" />
        <el-table-column prop="tableName" label="绑定表" min-width="150" />
        <el-table-column label="企业库" width="90">
          <template #default="{ row }"><el-tag :type="row.published ? 'success' : 'info'" size="small">{{ row.published ? '已发布' : '未发布' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="publishBusinessModel(row, !row.published)">{{ row.published ? '取消发布' : '发布' }}</el-button>
            <el-button size="small" @click="applyBusinessModel(row)">套用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>企业模型库</h2>
          <p>套用企业公共模型，修改参数后适配当前业务数据源，并保存为个人专属模型。</p>
        </div>
      </div>
      <el-table :data="enterpriseModels" height="320" empty-text="暂无企业模型">
        <el-table-column prop="modelName" label="模型名称" min-width="180" />
        <el-table-column prop="modelRequirement" label="业务需求" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="90">
          <template #default="{ row }"><el-button size="small" type="success" @click="applyBusinessModel(row)">套用</el-button></template>
        </el-table-column>
      </el-table>
    </div>
  </section>

  <el-dialog v-model="batchReplaceDialogVisible" title="批量替换" width="500px">
    <el-form label-position="top">
      <el-form-item label="字段">
        <el-select v-model="batchReplaceForm.columnName" placeholder="请选择字段" class="full-width">
          <el-option v-for="field in fields" :key="field.columnName" :label="field.displayName || field.columnName" :value="field.columnName" />
        </el-select>
      </el-form-item>
      <el-form-item label="原值"><el-input v-model="batchReplaceForm.oldValue" /></el-form-item>
      <el-form-item label="新值"><el-input v-model="batchReplaceForm.newValue" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="batchReplaceDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="executeBatchReplace">确定替换</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="transformDialogVisible" title="数据转换" width="500px">
    <el-form label-position="top">
      <el-form-item label="字段">
        <el-select v-model="transformForm.columnName" placeholder="请选择字段" class="full-width">
          <el-option v-for="field in fields" :key="field.columnName" :label="field.displayName || field.columnName" :value="field.columnName" />
        </el-select>
      </el-form-item>
      <el-form-item label="转换类型">
        <el-select v-model="transformForm.transformType" placeholder="请选择转换类型" class="full-width">
          <el-option label="去除首尾空格" value="TRIM" />
          <el-option label="转大写" value="UPPER" />
          <el-option label="转小写" value="LOWER" />
          <el-option label="日期格式化" value="DATE_FORMAT" />
          <el-option label="数值乘以系数" value="MULTIPLY" />
          <el-option label="填充空值" value="FILL_NULL" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="transformForm.transformType === 'DATE_FORMAT'" label="日期格式"><el-input v-model="transformForm.format" placeholder="%Y-%m-%d" /></el-form-item>
      <el-form-item v-if="transformForm.transformType === 'MULTIPLY'" label="乘以系数"><el-input-number v-model="transformForm.factor" :step="0.1" /></el-form-item>
      <el-form-item v-if="transformForm.transformType === 'FILL_NULL'" label="填充值"><el-input v-model="transformForm.fillValue" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="transformDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="executeTransform">确定转换</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import {
  batchReplace,
  deleteColumn,
  deleteRows,
  exportTable,
  getDataQuality,
  getFieldDistribution,
  getFieldStatistics,
  transformData,
  validateFile
} from '../../api/upload'

const workbench = inject('workbench')

const {
  activeModule,
  tables,
  selectedTableName,
  uploadFile,
  uploadFiles,
  uploadMergeMode,
  uploadJoinKey,
  uploadDisplayName,
  modelRequirement,
  businessModels,
  enterpriseModels,
  analysisTemplates,
  templateFile,
  selectedTemplateId,
  templateRequirement,
  uploading,
  uploadResult,
  uploadTask,
  previewRows,
  previewPage,
  previewPageSize,
  previewTotal,
  fields,
  previewColumns,
  loadTables,
  selectTable,
  onBatchFileChange,
  onFileRemove,
  submitUpload,
  loadBusinessModels,
  loadAnalysisTemplates,
  onTemplateFileChange,
  uploadAnalysisTemplate,
  createBusinessModelFromTemplate,
  createBusinessModel,
  publishBusinessModel,
  applyBusinessModel,
  renameDataTable,
  deleteDataTable,
  loadPreview,
  handlePreviewPageChange,
  handlePreviewSizeChange,
  loadFields,
  updateUploadField
} = workbench

const fileValidation = ref(null)
const qualityLoading = ref(false)
const dataQuality = ref(null)
const selectedRows = ref([])
const selectedFieldStats = ref(null)
const fieldDistribution = ref(null)
const distributionChart = ref(null)
const batchReplaceDialogVisible = ref(false)
const transformDialogVisible = ref(false)
let distributionChartInstance = null

const batchReplaceForm = ref({ columnName: '', oldValue: '', newValue: '' })
const transformForm = ref({ columnName: '', transformType: '', format: '%Y-%m-%d', factor: 1, fillValue: '' })

const statisticCards = computed(() => {
  const stats = selectedFieldStats.value
  if (!stats) return []
  const cards = [
    ['总数', stats.totalCount],
    ['非空', stats.nonNullCount],
    ['空值', stats.nullCount],
    ['空值率', `${stats.nullRate || 0}%`],
    ['唯一值', stats.distinctCount],
    ['重复率', `${stats.duplicateRate || 0}%`]
  ]
  if (stats.min !== undefined) cards.push(['最小值', stats.min])
  if (stats.max !== undefined) cards.push(['最大值', stats.max])
  if (stats.avg !== undefined) cards.push(['平均值', Number(stats.avg).toFixed(2)])
  if (stats.stdDev !== undefined) cards.push(['标准差', Number(stats.stdDev).toFixed(2)])
  return cards.map(([label, value]) => ({ label, value }))
})

onMounted(async () => {
  await Promise.all([loadTables(), loadBusinessModels(), loadAnalysisTemplates()])
  if (selectedTableName.value) {
    await Promise.all([loadDataQuality(), loadFields(selectedTableName.value), loadPreview(selectedTableName.value)])
  }
})

watch(selectedTableName, async (tableName) => {
  selectedFieldStats.value = null
  dataQuality.value = null
  if (tableName) {
    await loadDataQuality()
  }
})

function validateFileBeforeUpload(file) {
  const lowerName = file.name.toLowerCase()
  if (!lowerName.endsWith('.xlsx') && !lowerName.endsWith('.xls') && !lowerName.endsWith('.csv')) {
    ElMessage.error('仅支持 .xlsx / .xls / .csv 文件')
    return false
  }
  if (file.size > 100 * 1024 * 1024) {
    ElMessage.error('单文件不能超过 100MB')
    return false
  }
  return false
}

async function handleUploadChange(file, fileList) {
  onBatchFileChange(file, fileList)
  const raw = file?.raw
  if (!raw) return
  const formData = new FormData()
  formData.append('file', raw)
  try {
    fileValidation.value = await validateFile(formData)
  } catch (error) {
    fileValidation.value = { valid: false, errors: [error.message || '文件校验失败'], warnings: [] }
  }
}

function handleFileRemove(file, fileList) {
  onFileRemove(file, fileList)
  fileValidation.value = null
}

async function loadDataQuality() {
  if (!selectedTableName.value) return
  qualityLoading.value = true
  try {
    dataQuality.value = await getDataQuality(selectedTableName.value)
  } catch (error) {
    ElMessage.error(error.message || '数据质量评估失败')
  } finally {
    qualityLoading.value = false
  }
}

function getQualityClass(score) {
  if (score >= 90) return 'excellent'
  if (score >= 75) return 'good'
  if (score >= 60) return 'medium'
  return 'poor'
}

function handleSelectionChange(rows) {
  selectedRows.value = rows || []
}

function showBatchReplaceDialog() {
  batchReplaceForm.value = { columnName: fields.value[0]?.columnName || '', oldValue: '', newValue: '' }
  batchReplaceDialogVisible.value = true
}

function showTransformDialog() {
  transformForm.value = { columnName: fields.value[0]?.columnName || '', transformType: 'TRIM', format: '%Y-%m-%d', factor: 1, fillValue: '' }
  transformDialogVisible.value = true
}

async function executeBatchReplace() {
  if (!selectedTableName.value || !batchReplaceForm.value.columnName) return
  try {
    const result = await batchReplace(selectedTableName.value, batchReplaceForm.value.columnName, {
      oldValue: batchReplaceForm.value.oldValue,
      newValue: batchReplaceForm.value.newValue
    })
    ElMessage.success(`已替换 ${result.affectedRows || 0} 行`)
    batchReplaceDialogVisible.value = false
    await refreshCurrentTable()
  } catch (error) {
    ElMessage.error(error.message || '批量替换失败')
  }
}

async function executeTransform() {
  if (!selectedTableName.value || !transformForm.value.columnName || !transformForm.value.transformType) return
  const options = {
    format: transformForm.value.format,
    factor: transformForm.value.factor,
    value: transformForm.value.fillValue
  }
  try {
    const result = await transformData(selectedTableName.value, transformForm.value.columnName, {
      transformType: transformForm.value.transformType,
      options
    })
    ElMessage.success(`已转换 ${result.affectedRows || 0} 行`)
    transformDialogVisible.value = false
    await refreshCurrentTable()
  } catch (error) {
    ElMessage.error(error.message || '数据转换失败')
  }
}

async function deleteSelectedRows() {
  if (!selectedRows.value.length || !selectedTableName.value) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedRows.value.length} 行吗？`, '删除无效行', { type: 'warning' })
    const rowIds = selectedRows.value.map(row => row.sys_id)
    const result = await deleteRows(selectedTableName.value, { rowIds })
    ElMessage.success(`已删除 ${result.deletedRows || 0} 行`)
    selectedRows.value = []
    await refreshCurrentTable()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || '删除失败')
  }
}

async function removeColumn(row) {
  if (!selectedTableName.value || !row?.columnName) return
  try {
    await ElMessageBox.confirm(`确定删除字段 ${row.displayName || row.columnName} 吗？`, '删除字段', { type: 'warning' })
    await deleteColumn(selectedTableName.value, row.columnName)
    ElMessage.success('字段已删除')
    await refreshCurrentTable()
    await loadFields(selectedTableName.value)
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || '删除字段失败')
  }
}

async function showFieldStatistics(row) {
  if (!selectedTableName.value || !row?.columnName) return
  try {
    const [stats, distribution] = await Promise.all([
      getFieldStatistics(selectedTableName.value, row.columnName),
      getFieldDistribution(selectedTableName.value, row.columnName)
    ])
    selectedFieldStats.value = stats
    fieldDistribution.value = distribution
    await nextTick()
    renderDistributionChart()
  } catch (error) {
    ElMessage.error(error.message || '字段统计失败')
  }
}

function renderDistributionChart() {
  const container = distributionChart.value
  if (!container) return
  if (!distributionChartInstance) {
    distributionChartInstance = echarts.init(container)
  }
  const items = fieldDistribution.value?.distribution || []
  const labels = items.map(item => String(item.category ?? item.bucket ?? '空值'))
  const values = items.map(item => Number(item.count || 0))
  distributionChartInstance.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 24, top: 24, bottom: 70 },
    xAxis: { type: 'category', data: labels, axisLabel: { interval: 0, rotate: 28 } },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: values, itemStyle: { borderRadius: [4, 4, 0, 0], color: '#2f7cf6' } }]
  }, true)
}

function isAnomalyValue(column, value) {
  if (!selectedFieldStats.value || selectedFieldStats.value.columnName !== column) return false
  return (selectedFieldStats.value.anomalies || []).some(item => String(item.value) === String(value))
}

function quickQuery(row) {
  selectedTableName.value = row.tableName
  activeModule.value = 'chat'
}

async function exportDataTable(row) {
  try {
    const response = await exportTable(row.tableName)
    const blob = new Blob([response.data], { type: response.headers['content-type'] || 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${row.displayName || row.tableName}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error.message || '导出失败')
  }
}

async function refreshCurrentTable() {
  if (!selectedTableName.value) return
  await Promise.all([loadPreview(selectedTableName.value), loadDataQuality(), loadTables()])
}
</script>

<style scoped>
.file-validation {
  margin-top: 14px;
}

.validation-item {
  margin-top: 4px;
  font-size: 13px;
}

.validation-item.error {
  color: #f56c6c;
}

.validation-item.warning {
  color: #e6a23c;
}

.preview-actions {
  display: flex;
  gap: 8px;
}

.quality-summary {
  display: grid;
  gap: 18px;
}

.quality-score {
  display: flex;
  align-items: center;
  gap: 18px;
}

.score-circle {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 4px solid #d1d5db;
  background: #f8fafc;
}

.score-circle.excellent {
  border-color: #16a34a;
}

.score-circle.good {
  border-color: #2f7cf6;
}

.score-circle.medium {
  border-color: #f59e0b;
}

.score-circle.poor {
  border-color: #ef4444;
}

.score-value {
  font-size: 30px;
  font-weight: 800;
}

.score-label,
.metric-label,
.stat-label {
  color: #667085;
  font-size: 13px;
}

.score-level {
  font-size: 18px;
  font-weight: 700;
}

.quality-metrics,
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px;
}

.metric-item,
.stat-card {
  padding: 14px;
  text-align: center;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
}

.metric-value,
.stat-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 800;
  color: #172033;
}

.quality-suggestions {
  padding: 14px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
}

.suggestion-title,
.section-title {
  margin-bottom: 8px;
  font-weight: 700;
}

.suggestion-item {
  color: #374151;
  font-size: 13px;
  line-height: 1.7;
}

.anomaly-value {
  color: #dc2626;
  font-weight: 700;
}

.anomalies-section,
.distribution-section {
  margin-top: 18px;
}

.distribution-chart {
  width: 100%;
  height: 300px;
}

.full-width {
  width: 100%;
}

@media (max-width: 900px) {
  .preview-actions,
  .upload-actions {
    flex-wrap: wrap;
  }
}
</style>
