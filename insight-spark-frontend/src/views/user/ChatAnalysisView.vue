<template>
<section class="chat-layout">
          <div class="panel chat-panel">
            <div class="panel-header">
              <div>
                <h2>✨ 智能问答助理</h2>
                <p>随时随地，像对话一样探索您的业务数据及图表</p>
              </div>
              <el-button size="small" plain @click="businessDictionaryPanelVisible = true">
                业务字典/公式维护
              </el-button>
            </div>

            <div class="chat-datasource-bar">
              <div class="chat-filter-grid">
                <div class="chat-filter-field">
                  <div class="chat-datasource-label">数据源</div>
                  <el-select
                      v-model="selectedTableName"
                      placeholder="请选择数据源"
                      class="chat-toolbar-select"
                      popper-class="chat-select-dropdown"
                      clearable
                      filterable
                  >
                    <el-option-group v-if="uploadTables.length" label="上传数据表">
                      <el-option
                          v-for="table in uploadTables"
                          :key="table.tableName"
                          :label="formatTableOptionLabel(table)"
                          :value="table.tableName"
                      />
                    </el-option-group>
                    <el-option-group v-if="officialQueryTables.length" label="官方数据表">
                      <el-option
                          v-for="table in officialQueryTables"
                          :key="table.tableName"
                          :label="formatTableOptionLabel(table)"
                          :value="table.tableName"
                      />
                    </el-option-group>
                  </el-select>
                  <div class="chat-selection-hint" :title="selectedTableSummary || ''">
                    {{ selectedTableSummary || '请选择要分析的数据源' }}
                  </div>
                </div>
                <div class="chat-filter-field">
                  <div class="chat-datasource-label">业务模型</div>
                  <el-select
                      :model-value="selectedChatBusinessModelId"
                      placeholder="请选择业务模型"
                      class="chat-toolbar-select"
                      popper-class="chat-select-dropdown"
                      clearable
                      filterable
                      :disabled="!selectedTableName"
                      @change="handleChatBusinessModelChange"
                  >
                    <el-option
                        v-for="model in chatBusinessModelOptions"
                        :key="model.id"
                        :label="formatBusinessModelLabel(model)"
                        :value="model.id"
                    />
                  </el-select>
                  <div class="chat-selection-hint" :title="selectedBusinessModelSummary || ''">
                    {{ selectedBusinessModelSummary || businessModelEmptyHint }}
                  </div>
                </div>
              </div>
            </div>

            <div class="message-list" id="chatHistory">
              <div v-for="(msg, index) in messages" :key="index" :class="['message-wrapper', msg.role]">
                <div class="avatar">{{ msg.role === 'system' ? '🤖' : '👤' }}</div>
                <div class="msg-content">
                  <div class="bubble">{{ msg.content }}</div>
                  <div v-if="msg.fieldBindingResults?.length" class="field-binding-card">
                    <div class="field-binding-card__header">{{ msg.fieldBindingTitle || '字段变更结果' }}</div>
                    <div
                      v-for="(item, bindingIndex) in msg.fieldBindingResults"
                      :key="`${index}-binding-${bindingIndex}`"
                      class="field-binding-card__item"
                    >
                      <div class="field-binding-card__label">{{ item.label || item.name }}</div>
                      <div class="field-binding-card__arrow">→</div>
                      <div class="field-binding-card__field">
                        {{
                          item.targetType === 'metricDefinition'
                            ? (item.formula || item.fieldDisplayName || fieldLabel(item.field) || item.field || '已更新')
                            : (item.fieldDisplayName || fieldLabel(item.field) || item.field || '未绑定成功')
                        }}
                      </div>
                    </div>
                  </div>
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
                  <div v-if="speechSupported && msg.content" class="bubble-voice-action">
                    <button
                        type="button"
                        class="bubble-voice-btn"
                        :title="speaking && !speechPaused ? '暂停播报' : (speaking && speechPaused ? '继续播报' : '播报当前气泡内容')"
                        @click="speaking ? toggleVoicePlayback() : speakChatBubble(msg)"
                    >
                      <span class="bubble-voice-icon" aria-hidden="true">{{ speaking && !speechPaused ? '⏸' : (speaking && speechPaused ? '▶' : '🔊') }}</span>
                    </button>
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
              <el-button
                  class="voice-btn"
                  :type="listening ? 'danger' : 'primary'"
                  plain
                  :disabled="loading || !recognitionSupported"
                  :title="listening ? '停止语音输入' : '开始语音输入'"
                  :aria-label="listening ? '停止语音输入' : '开始语音输入'"
                  @click="listening ? stopVoiceQuestionInput() : startVoiceQuestionInput()"
              >
                <el-icon><Microphone /></el-icon>
              </el-button>
              <el-button
                  class="voice-btn"
                  plain
                  title="语音设置"
                  aria-label="语音设置"
                  @click="voicePanelVisible = true"
              >
                <el-icon><Setting /></el-icon>
              </el-button>
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

          </div>
          <div v-if="lastAnalysis?.graphContext?.length" class="panel graph-context-panel">
            <div class="panel-header">
              <div>
                <h2>GraphRAG 上下文</h2>
                <p>展示本次问答召回的数据表、字段、公式和知识片段。</p>
              </div>
            </div>
            <div class="graph-context-list">
              <div
                  v-for="(item, index) in lastAnalysis.graphContext"
                  :key="item.nodeKey || item.sourceId || index"
                  class="graph-context-item"
              >
                <div class="graph-context-meta">
                  <div class="graph-context-name">{{ formatGraphContextTitle(item) }}</div>
                  <div v-if="formatGraphContextSource(item)" class="graph-context-sub">
                    {{ formatGraphContextSource(item) }}
                  </div>
                </div>
                <div class="graph-context-content">{{ formatGraphContextContent(item) }}</div>
              </div>
            </div>
          </div>
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
          <el-drawer
              v-model="businessDictionaryPanelVisible"
              title="业务字典 + 业务公式维护"
              size="74%"
              destroy-on-close
          >
            <BusinessDictionaryView
              :focus-model-id="businessDictionaryFocusModelId"
              :show-title="false"
              :auto-rename-on-duplicate-create="true"
              :use-create-dialog="true"
            />
          </el-drawer>
          <el-drawer
              v-model="voicePanelVisible"
              title="语音交互设置"
              size="420px"
              destroy-on-close
          >
            <div class="voice-settings">
              <el-alert
                  :title="voiceCapabilityText"
                  :type="recognitionSupported || speechSupported ? 'success' : 'warning'"
                  :closable="false"
                  show-icon
              />
              <el-form label-position="top">
                <el-form-item label="识别语种 / 方言适配">
                  <el-select v-model="recognitionLocale" class="full-width">
                    <el-option
                        v-for="option in voiceLocaleOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="播报语种 / 方言适配">
                  <el-select v-model="voiceLocale" class="full-width">
                    <el-option
                        v-for="option in voiceLocaleOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="播报音色">
                  <el-select v-model="selectedVoiceGender" class="full-width">
                    <el-option
                        v-for="option in localVoiceGenderOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="播报语速">
                  <el-slider v-model="speechRate" :min="0.6" :max="1.4" :step="0.1" show-input />
                </el-form-item>
                <el-form-item label="播报音量">
                  <el-slider v-model="speechVolume" :min="0" :max="1" :step="0.05" show-input />
                </el-form-item>
                <el-form-item>
                  <el-switch
                      v-model="autoSpeakConclusion"
                      active-text="查询完成后自动播报结论"
                      inactive-text="手动触发播报"
                  />
                </el-form-item>
                <el-form-item>
                  <el-switch
                      v-model="autoSendAfterRecognize"
                      active-text="识别结束后自动发起查询"
                      inactive-text="识别后手动确认"
                  />
                </el-form-item>
                <el-form-item label="实时听写预览">
                  <div class="voice-preview-box">
                    {{ [finalTranscript, interimTranscript].filter(Boolean).join(' ') || '开始语音输入后，这里会显示识别结果。' }}
                  </div>
                </el-form-item>
              </el-form>
              <div class="voice-history">
                <div class="voice-history__head">
                  <span>最近语音记录</span>
                  <el-button text size="small" @click="clearVoiceHistory">清空</el-button>
                </div>
                <div v-if="voiceHistory.length" class="voice-history__list">
                  <button
                      v-for="item in voiceHistory"
                      :key="`${item.createdAt}-${item.text}`"
                      type="button"
                      class="voice-history__item"
                      @click="question = normalizeVoiceQuestion(item.text)"
                  >
                    <span class="voice-history__text">{{ item.text }}</span>
                    <span class="voice-history__meta">{{ item.locale }} · {{ item.createdAt.slice(5, 16).replace('T', ' ') }}</span>
                  </button>
                </div>
                <div v-else class="voice-history__empty">暂无语音记录</div>
              </div>
            </div>
          </el-drawer>
</section>
</template>

<script setup>
import { computed, inject } from 'vue'
import { Microphone, Setting } from '@element-plus/icons-vue'
import BusinessDictionaryView from '../../components/BusinessDictionaryView.vue'

const localVoiceGenderOptions = [
  { label: '男声', value: 'male' },
  { label: '女声', value: 'female' }
]

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
  diagnosisLoading,
  diagnosisReports,
  dimensionCandidateFields,
  canDiagnoseLastAnalysis,
  canRegenerateLastAnalysis,
  canPinLastAnalysis,
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
  businessDictionaryPanelVisible,
  businessDictionaryFocusModelId,
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
  selectedChatBusinessModelId,
  chatBusinessModelOptions,
  businessModels,
  sendQuestion,
  regenerateLastAnalysis,
  openPinDialog,
  pinChartToDashboard,
  pinDialogVisible,
  pinning,
  pinDashboardId,
  dashboardOptions,
  voicePanelVisible,
  voiceLocaleOptions,
  recognitionLocale,
  voiceLocale,
  selectedVoiceGender,
  speechRate,
  speechVolume,
  autoSpeakConclusion,
  autoSendAfterRecognize,
  recognitionSupported,
  speechSupported,
  voiceCapabilityText,
  voiceStatusText,
  listening,
  speaking,
  speechPaused,
  recognitionError,
  interimTranscript,
  finalTranscript,
  voiceHistory,
  startVoiceQuestionInput,
  stopVoiceQuestionInput,
  speakChatBubble,
  stopVoicePlayback,
  toggleVoicePlayback,
  clearVoiceHistory,
  normalizeVoiceQuestion,
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
  handleChatBusinessModelChange,
  uploadFile,
  uploadResult,
  uploading,
  userQuestion,
  xAxisData
} = inject('workbench')

const formatTableOptionLabel = (table) => {
  const displayName = String(table?.displayName || '').trim()
  const tableName = String(table?.tableName || '').trim()
  if (displayName && tableName && displayName !== tableName) {
    return `${displayName}（${tableName}）`
  }
  return displayName || tableName || '未命名数据源'
}

const formatBusinessModelLabel = (model) => {
  const modelName = String(model?.modelName || '').trim()
  const tableName = String(model?.tableName || '').trim()
  if (modelName && tableName) {
    return `${modelName}（${tableName}）`
  }
  return modelName || `模型 ${model?.id ?? ''}`
}

const selectedTableSummary = computed(() => {
  const matched = [...(uploadTables?.value || []), ...(officialQueryTables?.value || [])]
    .find(item => String(item?.tableName || '') === String(selectedTableName?.value || ''))
  return matched ? formatTableOptionLabel(matched) : ''
})

const selectedBusinessModelSummary = computed(() => {
  const matched = (businessModels?.value || [])
    .find(item => String(item?.id) === String(selectedChatBusinessModelId?.value ?? ''))
  return matched ? formatBusinessModelLabel(matched) : ''
})

const businessModelEmptyHint = computed(() => {
  if (!selectedTableName?.value) {
    return '请先选择数据源'
  }
  if (!chatBusinessModelOptions?.value?.length) {
    return '当前数据源下暂无业务模型'
  }
  return '请选择要修改的业务模型'
})

const formatGraphContextTitle = (item) => {
  if (typeof item === 'string') return item
  return String(item?.label || item?.name || item?.nodeKey || item?.sourceId || '上下文片段')
}

const formatGraphContextSource = (item) => {
  if (!item || typeof item === 'string') return ''
  const parts = [
    item.type || item.nodeType,
    item.tableName,
    item.sourceId
  ].filter(Boolean)
  return parts.join(' · ')
}

const formatGraphContextContent = (item) => {
  if (typeof item === 'string') return item
  const content = String(item?.content || item?.description || item?.summary || '').trim()
  if (content) return content
  const pairs = Object.entries(item || {})
    .filter(([key, value]) => !['label', 'name', 'nodeKey', 'sourceId', 'type', 'nodeType', 'tableName'].includes(key) && value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(', ') : value}`)
  return pairs.join('；') || '暂无详细内容'
}
</script>
<style scoped>
.chart-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}
.chat-panel .panel-header {
  align-items: center;
  margin-bottom: 10px;
}
.chat-panel .panel-header > div {
  min-width: 0;
}
.chat-panel .panel-header :deep(.el-button) {
  flex: 0 0 auto;
}
.chat-layout {
  min-height: 0;
}
.chat-panel {
  min-height: 0;
}
.chat-datasource-bar {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.chat-filter-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
}
.chat-filter-field {
  min-width: 0;
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
}
.chat-datasource-label {
  margin: 0;
  color: #475467;
  font-size: 12px;
  line-height: 1.4;
  font-weight: 600;
}
.chat-toolbar-select {
  width: 100%;
}
.chat-toolbar-select :deep(.el-select__wrapper) {
  min-height: 30px;
}
.chat-toolbar-select :deep(.el-select__selected-item),
.chat-toolbar-select :deep(.el-select__placeholder) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-selection-hint {
  grid-column: 2;
  margin-top: -5px;
  color: #98a2b3;
  font-size: 11px;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ask-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
}
.ask-bar :deep(.el-input) {
  flex: 1;
  min-width: 0;
}
.ask-bar :deep(.el-button + .el-button) {
  margin-left: 0;
}
.stop-btn {
  flex: 0 0 auto;
}
.voice-btn {
  flex: 0 0 auto;
  width: 28px;
  min-width: 28px;
  height: 28px;
  padding: 0;
}
.voice-btn :deep(.el-icon) {
  font-size: 13px;
}
.voice-btn :deep(.el-button__text),
.voice-btn :deep(.el-button__inner) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.bubble-voice-action {
  display: flex;
  justify-content: flex-start;
  margin-top: 6px;
}
.bubble-voice-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: background-color 0.2s ease, color 0.2s ease;
}
.bubble-voice-icon {
  font-size: 14px;
  line-height: 1;
}
.bubble-voice-btn:hover {
  background: #eef2ff;
  color: #1d4ed8;
}
.bubble-voice-btn:focus-visible {
  outline: 2px solid #93c5fd;
  outline-offset: 2px;
}
.voice-status-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.voice-status-text {
  color: #64748b;
  font-size: 12px;
}
.voice-transcript-preview {
  color: #334155;
  font-size: 12px;
  line-height: 1.4;
}
.voice-error-tip {
  margin-top: 6px;
  color: #dc2626;
  font-size: 12px;
}
.voice-settings {
  display: grid;
  gap: 16px;
}
.voice-preview-box {
  min-height: 84px;
  width: 100%;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f8fafc;
  color: #334155;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.voice-history {
  display: grid;
  gap: 8px;
}
.voice-history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}
.voice-history__list {
  display: grid;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
}
.voice-history__item {
  display: grid;
  gap: 4px;
  text-align: left;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  padding: 8px 10px;
  cursor: pointer;
}
.voice-history__item:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}
.voice-history__text {
  color: #0f172a;
  font-size: 12px;
  line-height: 1.5;
}
.voice-history__meta {
  color: #64748b;
  font-size: 11px;
}
.voice-history__empty {
  color: #9ca3af;
  font-size: 12px;
}
.recent-queries {
  flex: 0 1 300px;
  display: flex;
  flex-direction: column;
  margin-top: 10px;
  min-height: 0;
  padding-top: 8px;
  border-top: 1px solid #eef2f7;
}
.recent-title {
  margin-bottom: 6px;
  color: #6b7280;
  font-size: 12px;
}
.recent-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.recent-toolbar :deep(.el-input) {
  flex: 1;
}
.recent-list {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 6px;
  overflow-y: auto;
  padding-right: 4px;
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
  margin-top: 8px;
  justify-content: flex-end;
}
.graph-context-panel {
  grid-column: 1 / -1;
}
.graph-context-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
}
.graph-context-item {
  min-width: 0;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.graph-context-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.graph-context-name {
  color: #1f2a44;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.graph-context-sub {
  flex: 0 1 auto;
  color: #64748b;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.graph-context-content {
  color: #334155;
  line-height: 1.6;
  white-space: normal;
  word-break: break-word;
}
.thinking-details {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.field-binding-card {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid #dbe7ff;
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fbff 0%, #eef5ff 100%);
}
.field-binding-card__header {
  margin-bottom: 8px;
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 700;
}
.field-binding-card__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 20px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.92);
}
.field-binding-card__item + .field-binding-card__item {
  margin-top: 8px;
}
.field-binding-card__label {
  min-width: 0;
  color: #1f2937;
  font-weight: 600;
  word-break: break-word;
}
.field-binding-card__arrow {
  color: #60a5fa;
  text-align: center;
  font-weight: 700;
}
.field-binding-card__field {
  min-width: 0;
  color: #0f766e;
  font-family: Consolas, 'Courier New', monospace;
  word-break: break-word;
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

@media (max-width: 900px) {
  .recent-toolbar {
    flex-wrap: wrap;
  }
  .recent-toolbar :deep(.el-input) {
    flex-basis: 100%;
  }
}
</style>
