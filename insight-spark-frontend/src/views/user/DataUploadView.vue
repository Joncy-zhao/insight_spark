<template>
<section class="workspace-grid upload-grid">
          <div class="panel upload-panel">
            <div class="panel-header">
              <div>
                <h2>Excel / CSV 上传</h2>
                <p>上传后自动建表、记录字段语义，并作为对话查询的数据源。</p>
              </div>
            </div>

            <el-upload
                drag
                :auto-upload="false"
                :show-file-list="true"
                multiple
                :limit="5"
                accept=".xlsx,.xls,.csv"
                :on-change="onBatchFileChange"
                :on-remove="onFileRemove"
            >
              <div class="upload-drop-title">拖拽文件到此处</div>
              <div class="upload-drop-subtitle">支持 .xlsx / .xls / .csv，最多 5 个文件，可按表头合并或按字段关联。</div>
            </el-upload>

            <el-form label-position="top" class="merge-form">
              <el-form-item label="多文件处理方式">
                <el-radio-group v-model="uploadMergeMode">
                  <el-radio-button value="SAME_HEADER">相同/兼容表头合并</el-radio-button>
                  <el-radio-button value="KEY_JOIN">指定字段关联/VLOOKUP</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="uploadMergeMode === 'KEY_JOIN'" label="关联字段">
                <el-input v-model="uploadJoinKey" placeholder="例如：客户ID、订单号、商品编码" />
              </el-form-item>
              <el-form-item label="一句话零代码业务建模">
                <el-input
                    v-model="modelRequirement"
                    type="textarea"
                    :rows="3"
                    placeholder="例如：搭建电商用户生命周期模型，包含获客、激活、留存、转化、复购，按渠道和品类拆解"
                />
              </el-form-item>
            </el-form>

            <div class="upload-actions">
              <el-button type="primary" :loading="uploading" :disabled="!uploadFile && !uploadFiles.length" @click="submitUpload">
                解析入库/合并建模
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
                :title="`已生成数据表：${uploadResult.displayName}`"
                :description="`物理表名 ${uploadResult.tableName}，共 ${uploadResult.rowCount} 行、${uploadResult.fieldCount} 个字段。`"
            />
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>我的数据表</h2>
                <p>上传文件生成的数据表会进入这里，后续权限、AI解析和审计都围绕它展开。</p>
              </div>
            </div>
            <el-table :data="tables" height="320" empty-text="暂无数据表，请先上传文件" @row-click="selectTable">
              <el-table-column label="显示名" min-width="170">
                <template #default="{ row }">
                  <el-input v-model="row.displayName" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="rowCount" label="行数" width="90" />
              <el-table-column prop="fieldCount" label="字段" width="80" />
              <el-table-column prop="createdAt" label="创建时间" min-width="180" />
              <el-table-column label="操作" width="170" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click.stop="renameDataTable(row)">保存名</el-button>
                  <el-button size="small" type="danger" @click.stop="deleteDataTable(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>模板建模</h2>
                <p>上传业务分析模板，选择数据表后用一句话生成指标、维度、公式和图表建议。</p>
              </div>
              <el-button @click="loadAnalysisTemplates">刷新模板</el-button>
            </div>
            <el-upload :auto-upload="false" :show-file-list="true" accept=".txt,.md" :limit="1" :on-change="onTemplateFileChange">
              <el-button>选择模板文件</el-button>
              <template #tip>
                <div class="el-upload__tip">支持 .txt / .md，例如电商生命周期分析模板。</div>
              </template>
            </el-upload>
            <div class="upload-actions">
              <el-button type="primary" :disabled="!templateFile" @click="uploadAnalysisTemplate">上传模板</el-button>
            </div>
            <el-form label-position="top" class="merge-form">
              <el-form-item label="选择模板">
                <el-select v-model="selectedTemplateId" placeholder="请选择模板">
                  <el-option v-for="template in analysisTemplates" :key="template.id" :label="template.templateName" :value="template.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="一句话需求">
                <el-input v-model="templateRequirement" type="textarea" :rows="3" placeholder="我想分析电商用户生命周期，包含获客、激活、留存、转化、复购" />
              </el-form-item>
            </el-form>
            <el-button type="success" :disabled="!selectedTableName || !selectedTemplateId || !templateRequirement" @click="createBusinessModelFromTemplate">
              生成业务模型
            </el-button>
          </div>

          <div class="panel preview-panel">
            <div class="panel-header">
              <div>
                <h2>数据预览</h2>
                <p>选择数据表后展示前 10 行，确认字段和入库结果。</p>
              </div>
            </div>
            <el-table :data="previewRows" height="360" empty-text="请选择数据表">
              <el-table-column
                  v-for="column in previewColumns"
                  :key="column"
                  :prop="column"
                  :label="column"
                  min-width="120"
              />
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
                <h2>字段语义</h2>
                <p>字段元信息会传给后续 Python AI 服务，用于 Text-to-SQL 和 GraphRAG。</p>
              </div>
            </div>
            <el-table :data="fields" height="360" empty-text="请选择数据表">
              <el-table-column prop="displayName" label="业务字段" min-width="140" />
              <el-table-column prop="columnName" label="物理字段" width="110" />
              <el-table-column prop="fieldType" label="类型" width="90" />
              <el-table-column prop="sensitive" label="敏感" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.sensitive ? 'warning' : 'info'" size="small">
                    {{ row.sensitive ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>个人业务模型</h2>
                <p>上传/合并后生成的指标口径、维度体系和分析逻辑，可发布到企业模型库。</p>
              </div>
              <el-button @click="loadBusinessModels">刷新</el-button>
            </div>
            <el-table :data="businessModels" height="320" empty-text="暂无业务模型">
              <el-table-column prop="modelName" label="模型名称" min-width="180" />
              <el-table-column prop="tableName" label="绑定表" min-width="150" />
              <el-table-column prop="published" label="企业库" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.published ? 'success' : 'info'" size="small">{{ row.published ? '已发布' : '未发布' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="190">
                <template #default="{ row }">
                  <el-button size="small" type="primary" @click="publishBusinessModel(row, !row.published)">
                    {{ row.published ? '取消发布' : '发布' }}
                  </el-button>
                  <el-button size="small" @click="applyBusinessModel(row)">套用</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>企业模型库</h2>
                <p>管理员/用户发布的公共业务模型，可一键适配当前数据表。</p>
              </div>
            </div>
            <el-table :data="enterpriseModels" height="320" empty-text="暂无企业模型">
              <el-table-column prop="modelName" label="模型名称" min-width="180" />
              <el-table-column prop="modelRequirement" label="业务需求" min-width="260" show-overflow-tooltip />
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button size="small" type="success" @click="applyBusinessModel(row)">套用</el-button>
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
  previewPage,
  previewPageSize,
  previewRows,
  previewTotal,
  handlePreviewPageChange,
  handlePreviewSizeChange,
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
  uploadFiles,
  uploadMergeMode,
  uploadJoinKey,
  modelRequirement,
  businessModels,
  enterpriseModels,
  analysisTemplates,
  templateFile,
  selectedTemplateId,
  templateRequirement,
  uploadResult,
  uploadTask,
  uploading,
  onBatchFileChange,
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
  userQuestion,
  xAxisData
} = inject('workbench')
</script>
