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
        <div v-if="fileValidation.valid">文件大小：{{ formatUploadBytes(fileValidation.fileSize) }}</div>
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
      </el-form>

      <div class="upload-actions">
        <el-button type="primary" :loading="uploading" :disabled="!uploadFile && !uploadFiles.length" @click="submitUpload">
          解析入库
        </el-button>
        <el-button @click="loadTables">刷新数据表</el-button>
      </div>

      <div v-if="uploadProgress.visible || uploadTask" class="upload-progress">
        <div v-if="uploadProgress.visible">
          <el-progress
            :percentage="Number(uploadProgress.percentage || 0)"
            :indeterminate="!uploadProgress.computable && uploadProgress.status === 'UPLOADING'"
            :status="uploadProgress.status === 'FAILED' ? 'exception' : uploadProgress.status === 'SUCCESS' ? 'success' : undefined"
          />
          <div class="upload-progress-text">
            上传进度：
            <span v-if="uploadProgress.computable">{{ uploadProgress.percentage }}%</span>
            <span v-else>正在上传，浏览器未返回文件总大小</span>
          </div>
        </div>
        <div v-if="uploadTask" class="server-progress">
          <div class="upload-progress-text">服务端解析入库进度</div>
          <el-progress
            :percentage="Number(uploadTask.progress || 0)"
            :status="uploadTask.status === 'FAILED' ? 'exception' : uploadTask.status === 'SUCCESS' ? 'success' : undefined"
          />
          <div class="upload-progress-text">{{ uploadTask.message || uploadTask.status }}</div>
        </div>
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

      <div v-if="uploadResult" class="post-upload-actions">
        <el-button v-if="cleaningActions.length" type="warning" @click="qualityIssueDialogVisible = true">
          处理空值与异常值
        </el-button>
        <el-button v-if="canShowPreview" type="primary" @click="openPreviewDialog">
          展示预览文件
        </el-button>
      </div>
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

  </section>

  <el-dialog
    v-model="qualityIssueDialogVisible"
    title="空值与异常值处理"
    width="min(860px, 92vw)"
    top="6vh"
    class="quality-issue-dialog"
    :close-on-click-modal="false"
  >
    <div class="cleaning-strategy">
      <div class="section-title">异常值所在位置与清洗建议</div>
      <div class="cleaning-summary">{{ activeCleaningStrategy.summary || '暂无需要处理的数据质量问题' }}</div>
      <div v-if="cleaningResult" class="cleaning-result">
        <div class="section-title">处理成果</div>
        <div>已填充 {{ cleaningResult.filledRows || 0 }} 行空值，已标记隔离 {{ cleaningResult.markedAnomalyRows || 0 }} 行异常数据。</div>
      </div>
      <div v-if="visibleCleaningActions.length" class="cleaning-action-list">
        <div v-for="action in visibleCleaningActions" :key="`${action.type}-${action.columnName}`" class="cleaning-action-card">
          <div class="cleaning-action-head">
            <el-tag size="small" :type="action.type === 'FILL_NULL' ? 'warning' : 'danger'">
              {{ action.type === 'FILL_NULL' ? '空值' : '异常值' }}
            </el-tag>
            <strong>{{ action.displayName }}</strong>
            <span>{{ action.affectedRows || 0 }} 行</span>
          </div>
          <div class="cleaning-summary">{{ action.description }}</div>
          <div v-if="action.rowIds?.length" class="row-location">行ID：{{ action.rowIds.slice(0, 12).join(', ') }}{{ action.rowIds.length > 12 ? ' ...' : '' }}</div>
          <div class="row-snapshot-title">{{ cleaningResult ? '处理后的完整行' : '问题所在完整行' }}</div>
          <div class="row-snapshot-wrap">
            <table class="row-snapshot-table">
              <thead>
                <tr>
                  <th v-for="column in rowSnapshotColumns(action.afterRows || action.sampleRows || [])" :key="column">{{ getDisplayNameForColumn(column) }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(sampleRow, rowIndex) in (action.afterRows || action.sampleRows || [])" :key="rowIndex">
                  <td v-for="column in rowSnapshotColumns(action.afterRows || action.sampleRows || [])" :key="column" :class="getCellClassForSnapshot(sampleRow, column, action)">
                    {{ sampleRow[column] }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <el-empty v-else description="当前没有待处理的空值或异常值" />
    </div>
    <template #footer>
      <el-button @click="skipCleaningAndPreview">不处理</el-button>
      <el-button type="primary" @click="startImmediateCleaning">立马处理</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="previewDialogVisible" :title="editablePreview ? '空值与异常值处理' : '数据预览'" width="92%" top="4vh">
    <div class="preview-dialog-header">
      <div>
        <div class="section-title">{{ editablePreview ? '' : '展示解析后的数据' }}</div>
        <p>{{ editablePreview ? '可以手动修改空值与异常值，也可以点击自动处理后检查修改结果。' : '支持分页查看、批量替换、删除无效行和字段转换。' }}</p>
      </div>
      <div class="preview-actions">
        <el-button size="small" :disabled="!selectedTableName" @click="showBatchReplaceDialog">批量替换</el-button>
        <el-button size="small" :disabled="!selectedTableName" @click="showTransformDialog">数据转换</el-button>
        <el-button size="small" type="danger" :disabled="!selectedRows.length" @click="deleteSelectedRows">删除选中行</el-button>
      </div>
    </div>
    <el-table
      :data="previewDisplayRows"
      height="520"
      :empty-text="editablePreview ? '暂无空值或异常值' : '请选择数据表'"
      :cell-class-name="getCellClassName"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="48" />
      <el-table-column prop="sys_id" label="ID" width="80" fixed />
      <el-table-column v-for="column in previewDisplayColumns" :key="column" :prop="column" :label="getDisplayNameForColumn(column)" min-width="150">
        <template #default="{ row }">
          <el-input
            v-if="editablePreview"
            v-model="row[column]"
            size="small"
            @change="savePreviewCell(row, column)"
          />
          <span v-else>{{ row[column] }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="!editablePreview"
      class="preview-pagination"
      layout="total, sizes, prev, pager, next"
      :total="previewTotal"
      :current-page="previewPage"
      :page-size="previewPageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="handlePreviewPageChange"
      @size-change="handlePreviewSizeChange"
    />
    <template #footer>
      <template v-if="editablePreview">
        <el-button type="warning" :disabled="!cleaningActions.length" :loading="cleaningApplying" @click="confirmApplyCleaningStrategy">
          自动处理
        </el-button>
        <el-button type="primary" :loading="qualityLoading" @click="finishCleaningToPreview">
          处理完成
        </el-button>
      </template>
      <template v-else>
        <el-button v-if="cleaningActions.length || cleaningSkipped || cleaningResult" @click="backToCleaningStep">返回上一步</el-button>
        <el-button type="primary" :loading="cleaningApplying" @click="completePreviewAndActivate">完成</el-button>
      </template>
    </template>
  </el-dialog>

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
  activateCleanedTable,
  batchReplace,
  applyCleaningStrategy,
  deleteColumn,
  deleteRows,
  exportTable,
  getDataQuality,
  getFieldDistribution,
  getFieldStatistics,
  transformData,
  updateCell,
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
  uploading,
  uploadResult,
  uploadTask,
  uploadProgress,
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
const cleaningApplying = ref(false)
const qualityIssueDialogVisible = ref(false)
const previewDialogVisible = ref(false)
const cleaningResult = ref(null)
const cleaningResolved = ref(false)
const manualCleaningMode = ref(false)
const cleaningStage = ref('choice')
const previewMode = ref('preview')
const cleaningSkipped = ref(false)
const savingCellKey = ref('')
let distributionChartInstance = null

const batchReplaceForm = ref({ columnName: '', oldValue: '', newValue: '' })
const transformForm = ref({ columnName: '', transformType: '', format: '%Y-%m-%d', factor: 1, fillValue: '' })

const activeCleaningStrategy = computed(() => dataQuality.value?.cleaningStrategy || uploadResult.value?.cleaningStrategy || {})
const cleaningActions = computed(() => activeCleaningStrategy.value?.actions || [])
const visibleCleaningActions = computed(() => cleaningResult.value?.processedActions?.length ? cleaningResult.value.processedActions : cleaningActions.value)
const canShowPreview = computed(() => Boolean(selectedTableName.value) && (cleaningResolved.value || !cleaningActions.value.length))
const editablePreview = computed(() => previewMode.value === 'cleaning')
const visiblePreviewColumns = computed(() => previewColumns.value.filter(item => !['sys_id', 'is_cleaning_anomaly', 'cleaning_isolated', 'cleaning_anomaly_reason'].includes(item)))
const issueRows = computed(() => {
  const rows = []
  const seen = new Set()
  visibleCleaningActions.value.forEach(action => {
    const sourceRows = action.afterRows || action.sampleRows || []
    sourceRows.forEach(row => {
      const id = String(row?.sys_id ?? '')
      if (!id || seen.has(id)) return
      seen.add(id)
      rows.push(row)
    })
  })
  return rows
})
const issueColumns = computed(() => {
  const columns = []
  visibleCleaningActions.value.forEach(action => {
    const columnName = action.columnName
    if (columnName && !columns.includes(columnName)) columns.push(columnName)
  })
  return visiblePreviewColumns.value.filter(column => columns.includes(column))
})
const issueCellTypes = computed(() => {
  const cellTypes = new Map()
  visibleCleaningActions.value.forEach(action => {
    const columnName = action.columnName
    const type = action.type === 'FILL_NULL' ? 'null' : 'anomaly'
    ;(action.rowIds || []).forEach(rowId => {
      cellTypes.set(`${rowId}::${columnName}`, type)
    })
  })
  return cellTypes
})
const previewDisplayRows = computed(() => editablePreview.value ? issueRows.value : previewRows.value)
const previewDisplayColumns = computed(() => editablePreview.value ? issueColumns.value : visiblePreviewColumns.value)

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
  await loadTables()
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

watch(uploadResult, async (result) => {
  cleaningResult.value = null
  manualCleaningMode.value = false
  cleaningResolved.value = false
  cleaningStage.value = 'choice'
  previewMode.value = 'preview'
  cleaningSkipped.value = false
  if (!result?.tableName) return
  selectedTableName.value = result.tableName
  await Promise.all([loadFields(result.tableName), loadPreview(result.tableName), loadDataQuality()])
  if (cleaningActions.value.length) {
    qualityIssueDialogVisible.value = true
  } else {
    cleaningResolved.value = true
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

function formatUploadBytes(bytes = 0) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / (1024 ** index)).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
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

async function confirmApplyCleaningStrategy() {
  const tableName = uploadResult.value?.tableName || selectedTableName.value
  if (!tableName || !cleaningActions.value.length) return
  try {
    cleaningApplying.value = true
    const result = await applyCleaningStrategy(tableName, { actions: cleaningActions.value })
    cleaningResult.value = result
    cleaningResolved.value = false
    manualCleaningMode.value = false
    cleaningSkipped.value = false
    ElMessage.success(`清洗完成：填充 ${result.filledRows || 0} 行，隔离 ${result.markedAnomalyRows || 0} 行`)
    await refreshCurrentTable()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.message || '应用清洗策略失败')
    }
  } finally {
    cleaningApplying.value = false
  }
}

async function openPreviewDialog(mode = 'preview') {
  if (!selectedTableName.value) return
  previewMode.value = mode
  qualityIssueDialogVisible.value = false
  await Promise.all([loadFields(selectedTableName.value), loadPreview(selectedTableName.value)])
  previewDialogVisible.value = true
}

async function startManualCleaning() {
  manualCleaningMode.value = true
  await openPreviewDialog('cleaning')
}

async function startImmediateCleaning() {
  cleaningSkipped.value = false
  manualCleaningMode.value = true
  await openPreviewDialog('cleaning')
}

async function skipCleaningAndPreview() {
  cleaningSkipped.value = true
  cleaningResolved.value = false
  await openPreviewDialog('preview')
}

async function finishManualCleaning() {
  if (!selectedTableName.value) return
  try {
    await loadDataQuality()
    cleaningResult.value = {
      filledRows: 0,
      markedAnomalyRows: 0,
      appliedActions: 0,
      cleaningStrategy: dataQuality.value?.cleaningStrategy
    }
    cleaningResolved.value = !cleaningActions.value.length
    qualityIssueDialogVisible.value = true
    previewDialogVisible.value = false
  } catch (error) {
    ElMessage.error(error.message || '重新评估失败')
  }
}

async function confirmCleaningResult() {
  await completePreviewAndActivate()
}

async function finishCleaningToPreview() {
  if (!selectedTableName.value) return
  try {
    await loadDataQuality()
    cleaningResolved.value = !cleaningActions.value.length
    previewMode.value = 'preview'
    manualCleaningMode.value = false
    await Promise.all([loadFields(selectedTableName.value), loadPreview(selectedTableName.value)])
  } catch (error) {
    ElMessage.error(error.message || '重新评估失败')
  }
}

async function backToCleaningStep() {
  if (cleaningSkipped.value && !cleaningResult.value) {
    previewDialogVisible.value = false
    qualityIssueDialogVisible.value = true
    return
  }
  await openPreviewDialog('cleaning')
}

async function completePreviewAndActivate() {
  const tableName = uploadResult.value?.tableName || selectedTableName.value
  if (!tableName) return
  try {
    cleaningApplying.value = true
    await activateCleanedTable(tableName, { skipCleaning: cleaningSkipped.value })
    cleaningResolved.value = true
    qualityIssueDialogVisible.value = false
    previewDialogVisible.value = false
    await loadTables()
    ElMessage.success('数据表已进入我的数据表')
  } catch (error) {
    ElMessage.error(error.message || '请先处理完空值与异常值后再存入我的数据表')
  } finally {
    cleaningApplying.value = false
  }
}

async function savePreviewCell(row, column) {
  if (!selectedTableName.value || !row?.sys_id || !column) return
  const key = `${row.sys_id}-${column}`
  savingCellKey.value = key
  try {
    await updateCell(selectedTableName.value, row.sys_id, column, { value: row[column] })
    ElMessage.success('已保存修改')
  } catch (error) {
    ElMessage.error(error.message || '保存单元格失败')
  } finally {
    if (savingCellKey.value === key) savingCellKey.value = ''
  }
}

function rowSnapshotColumns(rows = []) {
  const columns = []
  rows.forEach(row => {
    Object.keys(row || {}).forEach(key => {
      if (!columns.includes(key)) columns.push(key)
    })
  })
  return columns
}

function getDisplayNameForColumn(column) {
  if (column === 'sys_id') return 'ID'
  const field = fields.value.find(item => item.columnName === column)
  return field?.displayName || field?.sourceFieldName || column
}

function getIssueCellType(rowId, column) {
  return issueCellTypes.value.get(`${rowId}::${column}`) || ''
}

function previewCellClassName({ row, column }) {
  if (!editablePreview.value || !row || !column?.property) return ''
  const type = getIssueCellType(row.sys_id, column.property)
  if (type === 'anomaly') return 'issue-anomaly-cell'
  if (type === 'null') return 'issue-null-cell'
  return ''
}

function getCellClassForSnapshot(row, column, action) {
  if (!row || !action || column !== action.columnName) return ''
  const rowIds = (action.rowIds || []).map(String)
  if (!rowIds.includes(String(row.sys_id))) return ''
  return action.type === 'FILL_NULL' ? 'issue-null-cell' : 'issue-anomaly-cell'
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

.post-upload-actions {
  margin-top: 12px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.cleaning-strategy {
  padding: 14px;
  border: 1px solid #facc15;
  border-radius: 8px;
  background: #fefce8;
  max-height: 64vh;
  overflow-y: auto;
  overflow-x: hidden;
}

.cleaning-summary {
  color: #713f12;
  font-size: 13px;
  line-height: 1.6;
}

.cleaning-result {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid #86efac;
  border-radius: 8px;
  background: #f0fdf4;
  color: #166534;
  font-size: 13px;
}

.row-location {
  margin-top: 4px;
  color: #991b1b;
  font-size: 12px;
}

.preview-dialog-header {
  margin-bottom: 12px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.preview-dialog-header p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.cleaning-actions {
  margin: 10px 0 12px;
  display: grid;
  gap: 8px;
}

.cleaning-action-list {
  margin-top: 12px;
  display: grid;
  gap: 10px;
}

.cleaning-action-card {
  min-width: 0;
  padding: 10px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fff;
}

.cleaning-action-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 6px;
}

.row-snapshot-title {
  margin: 8px 0 6px;
  color: #374151;
  font-size: 13px;
  font-weight: 700;
}

.row-snapshot-wrap {
  width: 100%;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
}

.row-snapshot-table {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 12px;
}

.row-snapshot-table th,
.row-snapshot-table td {
  width: 118px;
  max-width: 118px;
  padding: 8px 10px;
  border-bottom: 1px solid #edf2f7;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-snapshot-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f8fafc;
  color: #334155;
  font-weight: 700;
}

.row-snapshot-table td.cell-anomaly {
  background-color: #fee2e2 !important;
  color: #dc2626;
  font-weight: 600;
}

.row-snapshot-table td.cell-null {
  background-color: #fef3c7 !important;
  color: #92400e;
  font-weight: 600;
}

:deep(.issue-anomaly-cell) {
  background: #fef2f2 !important;
  color: #991b1b;
}

:deep(.issue-anomaly-cell .cell) {
  color: #991b1b;
  font-weight: 700;
}

:deep(.issue-null-cell) {
  background: #fffbeb !important;
  color: #92400e;
}

:deep(.issue-null-cell .cell) {
  color: #92400e;
  font-weight: 700;
}

:deep(.issue-anomaly-cell .el-input__wrapper) {
  background: #fff1f2;
  box-shadow: 0 0 0 1px #ef4444 inset;
}

:deep(.issue-null-cell .el-input__wrapper) {
  background: #fef3c7;
  box-shadow: 0 0 0 1px #f59e0b inset;
}

.row-snapshot-table td.issue-anomaly-cell {
  background: #fef2f2;
  color: #991b1b;
  font-weight: 700;
}

.row-snapshot-table td.issue-null-cell {
  background: #fffbeb;
  color: #92400e;
  font-weight: 700;
}

:deep(.quality-issue-dialog .el-dialog__body) {
  padding-top: 8px;
  overflow: hidden;
}

:deep(.quality-issue-dialog .el-dialog__footer) {
  padding-top: 10px;
}

.cleaning-action {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  color: #374151;
  font-size: 13px;
  line-height: 1.5;
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

:deep(.cell-anomaly) {
  background-color: #fee2e2 !important;
  color: #dc2626;
  font-weight: 600;
}

:deep(.cell-null) {
  background-color: #fef3c7 !important;
  color: #92400e;
  font-weight: 600;
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

.server-progress {
  margin-top: 12px;
}

@media (max-width: 900px) {
  .preview-actions,
  .upload-actions {
    flex-wrap: wrap;
  }
}
</style>
