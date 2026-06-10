<template>
  <UserDashboardView v-if="sharePreviewToken" />
  <AuthView v-else-if="!isAuthenticated" @authenticated="handleAuthenticated" />
  <el-container v-else class="app-shell">
    <el-aside :width="asideWidth" class="app-aside" :class="{ 'is-collapsed': isAsideCollapsed }">
      <div class="brand">
        <div class="brand-mark">BI</div>
        <div>
          <div class="brand-title">析数灵犀</div>
          <div class="brand-subtitle">AI驱动的对话式智能BI系统</div>
        </div>
      </div>

      <el-menu
          :default-active="activeModule"
          :collapse="isAsideCollapsed"
          class="nav-menu"
          @select="activeModule = $event"
      >
        <el-menu-item-group v-for="group in visibleMenuGroups" :key="group.id" :title="group.title">
          <el-menu-item v-for="module in group.modules" :key="module.key" :index="module.key">
            <el-icon class="nav-icon">
              <component :is="moduleIconMap[module.key] || Grid" />
            </el-icon>
            <template #title>
              <span>{{ module.title }}</span>
            </template>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-nav">
          <button class="sidebar-toggle" :class="{ 'is-collapsed': isAsideCollapsed }" type="button" aria-label="展开或收起侧边栏" @click="toggleAside">
            <span class="hamburger-icon" aria-hidden="true"></span>
          </button>
          <div class="topbar-divider" aria-hidden="true"></div>
          <button class="home-crumb" type="button" @click="goHome">
            <span>首页</span>
          </button>
          <span v-if="!isHomeModule(activeModule)" class="breadcrumb-separator">/</span>
          <span v-if="!isHomeModule(activeModule)" class="current-crumb">{{ moduleTitle }}</span>
        </div>
        <div class="topbar-actions">
          <el-tag :type="currentUser?.role === 'ADMIN' ? 'warning' : 'success'">
            {{ portalLabel }}
          </el-tag>
          <el-button @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main-stage" :class="{ 'main-stage--dashboard': isDashboardFlushLayout }">
        <DataUploadView v-if="activeModule === 'upload'" />
        <ChatAnalysisView v-if="activeModule === 'chat'" />
        <PermissionCenterView v-if="isPermissionModule" />
        <DatasourceManageView v-if="activeModule === 'datasource'" />
        <DiagnosisReportView v-if="activeModule === 'diagnosis'" />
        <AdvancedAnalysisManageView v-if="activeModule === 'advancedAnalysis'" />
        <KnowledgeGraphView v-if="activeModule === 'knowledgeGraph'" />
        <SqlAuditView v-if="activeModule === 'audit'" />
        <AdminChatHistoryView v-if="activeModule === 'adminChatHistory'" />
        <AdminChatQueryLabView v-if="activeModule === 'adminChatQueryLab'" />
        <UserWorkbenchView v-if="activeModule === 'workbench'" />
        <UserDashboardView v-if="activeModule === 'dashboard'" />
        <BusinessCollaborationView v-if="activeModule === 'collaboration'" />
        <AdminWorkbenchView v-if="activeModule === 'adminWorkbench'" />
        <AdminDashboardView v-if="activeModule === 'adminDashboard'" />
        <StackCSystemConfigView v-if="activeModule === 'stackCConfig'" />
        <AiChartRuleConfigView v-if="activeModule === 'aiChartRules'" />
        <PerformanceGovernanceView v-if="activeModule === 'performanceGovernance'" />
        <PlaceholderView
            v-if="!['upload', 'chat', 'audit', 'adminChatHistory', 'adminChatQueryLab', 'permission', 'permissionAdmin', 'datasource', 'diagnosis', 'advancedAnalysis', 'knowledgeGraph', 'workbench', 'dashboard', 'collaboration', 'adminWorkbench', 'adminDashboard', 'stackCConfig', 'aiChartRules', 'performanceGovernance'].includes(activeModule)"
        />
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="diagnosisPickerVisible" title="生成诊断报告" width="560px">
    <el-form label-position="top">
      <el-form-item label="报告详细程度">
        <el-radio-group v-model="diagnosisPickerForm.detailLevel">
          <el-radio-button label="simple">简易</el-radio-button>
          <el-radio-button label="detailed">详细</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="异常类型">
        <el-select v-model="diagnosisPickerForm.anomalyType" class="full-width">
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
      </el-form-item>
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
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Close,
  Connection,
  Cpu,
  DataAnalysis,
  DataBoard,
  DocumentChecked,
  Edit,
  Grid,
  Histogram,
  House,
  Key,
  Lock,
  Management,
  Monitor,
  MoreFilled,
  Operation,
  Microphone,
  Refresh,
  Search,
  SetUp,
  Setting,
  Share,
  Upload
} from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { menuGroups, moduleMap } from './router/modules'
import DataUploadView from './views/user/DataUploadView.vue'
import ChatAnalysisView from './views/user/ChatAnalysisView.vue'
import PermissionCenterView from './views/user/PermissionCenterView.vue'
import DatasourceManageView from './views/admin/DatasourceManageView.vue'
import DiagnosisReportView from './views/user/DiagnosisReportView.vue'
import KnowledgeGraphView from './views/admin/KnowledgeGraphView.vue'
import SqlAuditView from './views/admin/SqlAuditView.vue'
import AdminChatHistoryView from './views/admin/AdminChatHistoryView.vue'
import AdminChatQueryLabView from './views/admin/AdminChatQueryLabView.vue'
import StackCSystemConfigView from './views/admin/StackCSystemConfigView.vue'
import AiChartRuleConfigView from './views/admin/AiChartRuleConfigView.vue'
import PerformanceGovernanceView from './views/admin/PerformanceGovernanceView.vue'
import AdminWorkbenchView from './views/admin/AdminWorkbenchView.vue'
import AdminDashboardView from './views/admin/AdminDashboardView.vue'
import UserWorkbenchView from './views/user/UserWorkbenchView.vue'
import UserDashboardView from './views/user/UserDashboardView.vue'
import BusinessCollaborationView from './views/user/BusinessCollaborationView.vue'
import AdvancedAnalysisManageView from './views/user/AdvancedAnalysisManageView.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import AuthView from './views/AuthView.vue'
import { authToken, currentUser, isAuthenticated, clearSession, restoreSessionHeader } from './store/session'
import { logout } from './api/auth'
import { useVoiceInteraction } from './composables/useVoiceInteraction'
import {
  applyDynamicInteractionDefaults,
  applyOptionTemplateDefaults,
  buildAnimationReplayStartOption,
  buildForecastChartOption,
  getChartAnimationMeta,
  hasForecastSeriesRows,
  normalizeInteractiveDataZoom
} from './utils/chartOptionFromSnapshot'

const API_BASE = 'http://localhost:8080'
const LAST_SELECTED_TABLE_KEY = 'insight:lastSelectedTableName'
const CHAT_MODEL_KEY = 'insight_chat_model_id'
const CHAT_THINKING_LOG_LIMIT = 100
const moduleIconMap = {
  workbench: House,
  adminWorkbench: House,
  dashboard: DataBoard,
  adminDashboard: Histogram,
  collaboration: Share,
  advancedAnalysis: DataAnalysis,
  businessDictionary: Operation,
  upload: Upload,
  chat: ChatDotRound,
  permission: Lock,
  permissionAdmin: Key,
  diagnosis: DocumentChecked,
  datasource: Connection,
  knowledgeGraph: Share,
  audit: DataAnalysis,
  adminChatHistory: Monitor,
  adminChatQueryLab: SetUp,
  stackCConfig: Setting,
  aiChartRules: DataAnalysis,
  performanceGovernance: Cpu,
  default: Grid
}
const sharePreviewToken = (() => {
  try {
    return String(new URL(window.location.href).searchParams.get('shareToken') || '').trim()
  } catch {
    return ''
  }
})()

const saveLastSelectedTable = (tableName) => {
  try {
    if (tableName) {
      localStorage.setItem(LAST_SELECTED_TABLE_KEY, tableName)
    } else {
      localStorage.removeItem(LAST_SELECTED_TABLE_KEY)
    }
  } catch (error) {
    // ignore storage errors in restricted browser contexts
  }
}

const readLastSelectedTable = () => {
  try {
    return String(localStorage.getItem(LAST_SELECTED_TABLE_KEY) || '').trim()
  } catch (error) {
    return ''
  }
}

const clearLastSelectedTable = () => {
  try {
    localStorage.removeItem(LAST_SELECTED_TABLE_KEY)
  } catch (error) {
    // ignore storage errors in restricted browser contexts
  }
}

const datasourceHealthMap = ref({})
const activeModule = ref('workbench')
const isAsideCollapsed = ref(false)
const navigationTabs = ref([])
const nextTabOrder = ref(1)
const tables = ref([])
const selectedTableName = ref('')
const advancedAlertContext = ref(null)
const uploadFile = ref(null)
const uploadFiles = ref([])
const uploadMergeMode = ref('SAME_HEADER')
const uploadJoinKey = ref('')
const uploadDisplayName = ref('')
const modelRequirement = ref('')
const businessModels = ref([])
const enterpriseModels = ref([])
const analysisTemplates = ref([])
const templateFile = ref(null)
const selectedTemplateId = ref('')
const templateRequirement = ref('')
const uploading = ref(false)
const uploadResult = ref(null)
const uploadTask = ref(null)
const uploadProgress = ref({
  visible: false,
  percentage: 0,
  loaded: 0,
  total: 0,
  computable: false,
  status: 'READY'
})
let uploadProgressHideTimer = null
const previewRows = ref([])
const previewPage = ref(1)
const previewPageSize = ref(10)
const previewTotal = ref(0)
const fields = ref([])
const loadedPreviewTableName = ref('')
const loadedFieldsTableName = ref('')
const auditLogs = ref([])
const auditRules = ref([])
const sensitiveRules = ref([])
const auditCacheOverview = ref({})
const auditStats = ref({})
const auditCacheAudits = ref([])
const auditRowPolicies = ref([])
const auditAdvancedFilters = ref({
  userId: '',
  tableName: '',
  keyword: '',
  cacheHit: '',
  slowQuery: ''
})
const sensitiveRuleForm = ref({ fieldKeyword: '', maskType: 'MIDDLE', accessAction: 'MASK', enabled: true })
const auditRowPolicyForm = ref({
  tableName: '',
  principalType: 'USER',
  principalId: '',
  filterExpression: '',
  enabled: true
})
const graphOverview = ref({ nodeTypes: [], edgeTypes: [] })
const graphSearchKeyword = ref('')
const graphSearchResult = ref({ nodes: [], edges: [], ragContext: [] })
const graphLoading = ref(false)
const knowledgeDocFile = ref(null)
const knowledgeDocFiles = ref([])
const knowledgeDocUploadFiles = ref([])
const knowledgeDocUploading = ref(false)
const knowledgeDocs = ref([])
const auditRiskLevel = ref('')
const auditExecuteStatus = ref('')
const manualAuditSql = ref('')
const manualAuditResult = ref(null)
const permissionOverview = ref({})
const accessibleTables = ref([])
const accessibleOfficialTables = ref([])
const requestableTables = ref([])
const sensitiveFieldPermissions = ref([])
const rowPolicyDetails = ref([])
const complianceDocument = ref({})
const myPermissionRequests = ref([])
const adminPermissionRequests = ref([])
const adminRequestStatus = ref('PENDING')
const emptyPermissionForm = () => ({
  tableName: '',
  resourceType: 'TABLE',
  permissionType: 'READ',
  scopeDesc: '',
  expireAt: '',
  attachmentName: '',
  attachmentContentType: '',
  attachmentSize: 0,
  attachmentContent: '',
  attachmentNote: '',
  reason: ''
})
const permissionForm = ref(emptyPermissionForm())
const datasourceForm = ref({
  name: '',
  dbType: 'MYSQL',
  host: 'localhost',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  poolMaxSize: 10,
  poolTimeoutMs: 30000,
  kgSyncRule: ''
})
const datasourcePermissions = ref([])
const datasourcePermissionForm = ref({ tableName: '*', principalType: 'USER', principalId: 'user', permissionType: 'READ' })
const federalRelations = ref([])
const federalForm = ref({ leftTable: '', leftField: '', rightSourceType: 'UPLOAD', rightTable: '', rightField: '', relationType: 'LEFT_JOIN' })
const officialDatasources = ref([])
const selectedDatasourceId = ref(null)
const schemaTables = ref([])
const schemaFields = ref([])
const diagnosisForm = ref({ metricField: '', dimensionFields: [], timeField: '', detailLevel: 'detailed', anomalyType: 'fluctuation' })
const diagnosisPickerVisible = ref(false)
const diagnosisPickerForm = ref({ metricField: '', dimensionFields: [], timeField: '', detailLevel: 'detailed', anomalyType: 'fluctuation' })
const diagnosisLoading = ref(false)
const streamAbortController = ref(null)
const activeChatRequestId = ref(0)
const stopRequested = ref(false)
const isStreaming = ref(false)
const chatModelOptions = ref([])
const selectedChatModelId = ref(localStorage.getItem(CHAT_MODEL_KEY) || 'default')
const recentChatQueries = ref([])
const recentChatQueryKeyword = ref('')
const recentChatQueryTableName = ref('')
const recentChatQueryChartType = ref('')
const recentChatQueryRiskLevel = ref('')
const recentChatQueryExecutionStatus = ref('')
const recentChatQueryDateRange = ref([])
const recentChatQuerySortDirection = ref('DESC')
const recentChatQueryPage = ref(1)
const recentChatQueryPageSize = ref(8)
const recentChatQueryTotal = ref(0)
const recentChatQueryLoading = ref(false)
const recentChatQueryError = ref('')
const recentChatQueryErrorType = ref('')
const pinnedHistoryIds = ref([])
const chatSessions = ref([])
const activeChatSessionId = ref(null)
const chatSessionLoading = ref(false)
const chatSessionKeyword = ref('')
const chatSessionStatus = ref('ACTIVE')
const chatSessionAdvancedType = ref('ALL')
const activeBranchParentTurnId = ref(null)
const activeBranchParentTurnMeta = ref(null)
const diagnosisProgress = ref({ percentage: 0, step: '待开始', logs: [] })
const currentDiagnosis = ref(null)
const diagnosisReports = ref([])
const diagnosisRestoreTarget = ref(null)
const diagnosisEntryContext = ref(null)
const businessDictionaryPanelVisible = ref(false)
const businessDictionaryFocusModelId = ref(null)
const activeBusinessModelId = ref(null)
const selectedChatBusinessModelId = ref(null)
const lastCreatedBusinessModelId = ref(null)
const lastAppliedBusinessModelId = ref(null)
const pinDialogVisible = ref(false)
const pinning = ref(false)
const pinDashboardId = ref(null)
const dashboardOptions = ref([])
const pendingDashboardPinSource = ref(null)
const voicePanelVisible = ref(false)
const question = ref('')
const loading = ref(false)
const messages = ref([
  { role: 'system', content: '👋 你好！我是你的析数灵犀 AI 数据助手。请在左侧选择数据表，然后用自然语言向我提问吧！' }
])
const currentChartType = ref('')
const chartSortMode = ref('name')
const lastAnalysis = ref(null)
const chartAnimationMeta = ref(null)
const {
  voiceLocaleOptions,
  recognitionLocale,
  voiceLocale,
  selectedVoiceGender,
  speechRate,
  speechVolume,
  autoSpeakConclusion,
  autoSendAfterRecognize,
  voiceGenderOptions,
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
  clearVoiceHistory,
  startListening,
  stopListening,
  clearTranscript,
  loadVoicePreferences,
  prefetchSpeechText,
  speakText,
  stopSpeaking,
  pauseSpeaking,
  resumeSpeaking
} = useVoiceInteraction()
let chartInstance = null
let chartRenderVersion = 0
const handleChartResize = () => {
  chartInstance?.resize()
}

const moduleTitle = computed(() => moduleMap[activeModule.value].title)
const moduleSubtitle = computed(() => moduleMap[activeModule.value].subtitle)
const isDashboardFlushLayout = computed(() => activeModule.value === 'dashboard' || activeModule.value === 'adminDashboard')
const asideWidth = computed(() => isAsideCollapsed.value ? '64px' : '248px')
const visibleMenuGroups = computed(() => {
  const role = currentUser.value?.role || 'USER'
  return menuGroups
      .map(group => ({
        ...group,
        modules: group.modules.filter(module => module.role === 'ALL' || module.role === role)
      }))
      .filter(group => group.modules.length)
})
const isPermissionModule = computed(() => activeModule.value === 'permission' || activeModule.value === 'permissionAdmin')
const isAdminModule = computed(() => ['datasource', 'permissionAdmin', 'knowledgeGraph', 'audit', 'adminChatHistory', 'adminChatQueryLab', 'stackCConfig', 'aiChartRules', 'adminWorkbench', 'adminDashboard', 'performanceGovernance'].includes(activeModule.value))
const isAdminUser = computed(() => currentUser.value?.role === 'ADMIN')
const portalLabel = computed(() => isAdminUser.value ? '管理员门户' : '用户门户')
const homeModuleKey = computed(() => isAdminUser.value ? 'adminWorkbench' : 'workbench')
const orderedNavigationTabs = computed(() => [...navigationTabs.value].sort((a, b) => a.order - b.order))
const placeholderStep = computed(() => activeModule.value === 'audit' ? 1 : 0)
const previewColumns = computed(() => previewRows.value.length ? Object.keys(previewRows.value[0]) : [])
const uploadTables = computed(() => tables.value.filter(item => String(item?.sourceType || '').toUpperCase() !== 'OFFICIAL'))
const officialQueryTables = computed(() => tables.value.filter(item => String(item?.sourceType || '').toUpperCase() === 'OFFICIAL'))
const chartTypeText = (value) => {
  const type = String(value || '').toLowerCase()
  if (type === 'bar') return '柱状图'
  if (type === 'pie') return '饼图'
  if (type === 'doughnut') return '环形图'
  if (type === 'line') return '折线图'
  if (type === 'table') return '表格'
  if (type === 'forecast') return '预测'
  if (type === 'whatif' || type === 'what_if') return '情景推演'
  if (type === 'alert') return '预警'
  return String(value || '图表')
}
const chartTypeLabel = computed(() => {
  return chartTypeText(currentChartType.value)
})
const chartTypeSwitchOptions = [
  { label: '柱状图', value: 'bar' },
  { label: '折线图', value: 'line' },
  { label: '饼图', value: 'pie' }
]
const isAiRecommendedChartType = (type) => {
  const recommendedType = String(lastAnalysis.value?.recommendedChartType || lastAnalysis.value?.aiRecommendedChartType || '').toLowerCase()
  return Boolean(recommendedType && recommendedType === String(type || '').toLowerCase())
}
const numericFields = computed(() => fields.value.filter(field => field.fieldType === 'NUMBER'))
const dateFields = computed(() => fields.value.filter(field => field.fieldType === 'DATE'))
const dimensionCandidateFields = computed(() => fields.value.filter(field => field.fieldType !== 'NUMBER'))
const canDiagnoseLastAnalysis = computed(() => Boolean(lastAnalysis.value && numericFields.value.length))
const canRegenerateLastAnalysis = computed(() => Boolean(String(lastAnalysis.value?.sourceQuestion || '').trim()))
const canPinLastAnalysis = computed(() => Boolean(lastAnalysis.value?.data?.length))
const hasVoiceConclusion = computed(() => Boolean(lastAnalysis.value?.data?.length || lastAnalysis.value?.message))
const isLastAnalysisTable = computed(() => String(lastAnalysis.value?.chartType || currentChartType.value || '').toLowerCase() === 'table')
const lastAnalysisTableRows = computed(() => {
  if (!isLastAnalysisTable.value) return []
  return Array.isArray(lastAnalysis.value?.tableRows)
    ? lastAnalysis.value.tableRows
    : (Array.isArray(lastAnalysis.value?.data) ? lastAnalysis.value.data : [])
})
const lastAnalysisTableColumns = computed(() => {
  if (!isLastAnalysisTable.value) return []
  const configured = Array.isArray(lastAnalysis.value?.tableColumns) ? lastAnalysis.value.tableColumns : []
  if (configured.length) {
    return configured.map(item => {
      if (item && typeof item === 'object') {
        const prop = String(item.prop || item.column || item.key || '').trim()
        return {
          prop,
          label: String(item.label || item.name || prop).trim() || prop
        }
      }
      const prop = String(item || '').trim()
      return { prop, label: prop }
    }).filter(item => item.prop)
  }
  const first = lastAnalysisTableRows.value.find(row => row && typeof row === 'object') || {}
  return Object.keys(first).map(key => ({ prop: key, label: key }))
})
const isVoicePhysicalColumnCode = (value) => /^col_\d+$/i.test(String(value || '').trim())

const escapeVoiceRegExp = (value) => String(value || '').replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

const splitVoiceAliases = (value) => {
  const raw = String(value || '').trim()
  if (!raw) return []
  return [...new Set([raw, ...raw.split(/[，,;；、|/]+/).map(item => item.trim()).filter(Boolean)])]
}

const buildVoiceCanonicalFieldLabel = (field) => {
  const displayName = String(field?.displayName || '').trim()
  const sourceFieldName = String(field?.sourceFieldName || '').trim()
  const columnName = String(field?.columnName || '').trim()
  if (displayName && !isVoicePhysicalColumnCode(displayName)) {
    return displayName
  }
  if (sourceFieldName && !isVoicePhysicalColumnCode(sourceFieldName)) {
    return sourceFieldName
  }
  return columnName || displayName || sourceFieldName
}

const voiceNormalizationContext = computed(() => {
  const aliasToCanonical = new Map()
  const aliases = []
  const registerAlias = (alias, canonical) => {
    const normalizedAlias = String(alias || '').trim()
    const normalizedCanonical = String(canonical || '').trim()
    if (!normalizedAlias || !normalizedCanonical) return
    if (normalizedAlias.length < 2 || normalizedCanonical.length < 1) return
    const aliasKey = normalizedAlias.toLowerCase()
    if (aliasToCanonical.has(aliasKey)) return
    aliasToCanonical.set(aliasKey, normalizedCanonical)
    aliases.push({ alias: normalizedAlias, canonical: normalizedCanonical })
  }

  const registerAliases = (values, canonical) => {
    for (const value of values) {
      for (const alias of splitVoiceAliases(value)) {
        registerAlias(alias, canonical)
      }
    }
  }

  const selectedName = String(selectedTableName.value || '').trim()
  const tableModels = filteredBusinessModelsByTable(selectedName)
  const preferredIds = [
    selectedChatBusinessModelId.value,
    activeBusinessModelId.value,
    lastAppliedBusinessModelId.value,
    lastCreatedBusinessModelId.value
  ]
    .map(normalizeBusinessModelOptionId)
    .filter(value => value !== null)

  const preferredModels = []
  for (const modelId of preferredIds) {
    const matched = findBusinessModelById(modelId)
    if (matched && isBusinessModelOnTable(matched, selectedName) && !preferredModels.some(item => String(item?.id) === String(matched.id))) {
      preferredModels.push(matched)
    }
  }

  const orderedModels = [
    ...preferredModels,
    ...tableModels.filter(model => !preferredModels.some(item => String(item?.id) === String(model?.id)))
  ]

  for (const model of orderedModels) {
    const json = parseMaybeJson(model?.modelJson)
    for (const entry of json?.dictionaryEntries || []) {
      const canonical = String(entry?.term || '').trim()
        || buildVoiceCanonicalFieldLabel({ columnName: entry?.field, displayName: entry?.field, sourceFieldName: entry?.field })
        || String(entry?.field || '').trim()
      registerAliases([entry?.term, entry?.field, entry?.synonyms], canonical)
    }
  }

  for (const field of fields.value || []) {
    const canonical = buildVoiceCanonicalFieldLabel(field)
    registerAliases([
      field?.displayName,
      field?.sourceFieldName,
      field?.columnName,
      field?.fieldComment,
      field?.synonyms
    ], canonical)
  }

  aliases.sort((left, right) => right.alias.length - left.alias.length || right.canonical.length - left.canonical.length)
  const aliasPattern = aliases.length
    ? new RegExp(
      `(?<![\\w\\u4e00-\\u9fa5])(?:${aliases.map(item => escapeVoiceRegExp(item.alias)).join('|')})(?![\\w\\u4e00-\\u9fa5])`,
      'gi'
    )
    : null

  return {
    aliasToCanonical,
    aliasPattern
  }
})

const normalizeVoiceQuestion = (text) => {
  const source = String(text || '').trim()
  if (!source) return ''
  const { aliasPattern, aliasToCanonical } = voiceNormalizationContext.value
  if (!aliasPattern) return source.replace(/\s+/g, ' ').trim()
  return source
    .replace(aliasPattern, (matched) => aliasToCanonical.get(String(matched || '').toLowerCase()) || matched)
    .replace(/\s+/g, ' ')
    .trim()
}

const normalizeBusinessModelOptionId = (value) => {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const numeric = Number(value)
  return Number.isNaN(numeric) ? String(value).trim() : numeric
}

const isBusinessModelOnTable = (model, tableName) => String(model?.tableName || '').trim() === String(tableName || '').trim()

const findBusinessModelById = (modelId) => {
  const normalizedId = normalizeBusinessModelOptionId(modelId)
  if (normalizedId === null) return null
  return (businessModels.value || []).find(item => String(item?.id) === String(normalizedId)) || null
}

const filteredBusinessModelsByTable = (tableName) => {
  const normalizedTableName = String(tableName || '').trim()
  if (!normalizedTableName) return []
  return (businessModels.value || [])
    .filter(item => isBusinessModelOnTable(item, normalizedTableName))
    .sort((a, b) => {
      const updatedDiff = new Date(b?.updatedAt || b?.createdAt || 0).getTime() - new Date(a?.updatedAt || a?.createdAt || 0).getTime()
      if (updatedDiff !== 0) return updatedDiff
      return Number(b?.id || 0) - Number(a?.id || 0)
    })
}

const chatBusinessModelOptions = computed(() => filteredBusinessModelsByTable(selectedTableName.value))
const selectedChatModel = computed(() => {
  const selectedId = String(selectedChatModelId.value || '').trim()
  return chatModelOptions.value.find(item => String(item?.id || '') === selectedId)
      || chatModelOptions.value[0]
      || null
})

const chatModelPayload = computed(() => ({
  modelId: selectedChatModel.value?.id || '',
  modelName: selectedChatModel.value?.name || selectedChatModel.value?.model || '',
  modelCategory: selectedChatModel.value?.category || ''
}))

const loadChatModels = async () => {
  try {
    const models = unwrap(await axios.get(`${API_BASE}/api/chat/models`))
    chatModelOptions.value = Array.isArray(models) ? models.filter(item => item?.id) : []
    const currentId = String(selectedChatModelId.value || '').trim()
    if (chatModelOptions.value.length && !chatModelOptions.value.some(item => String(item.id) === currentId)) {
      selectedChatModelId.value = String(chatModelOptions.value[0]?.id || 'default')
      localStorage.setItem(CHAT_MODEL_KEY, selectedChatModelId.value)
    }
  } catch (error) {
    chatModelOptions.value = []
  }
}

const handleChatModelChange = (modelId) => {
  const nextId = String(modelId || '').trim()
  if (!nextId) return
  selectedChatModelId.value = nextId
  localStorage.setItem(CHAT_MODEL_KEY, nextId)
}

const applyChatBusinessModelSelection = (modelId) => {
  const normalizedId = normalizeBusinessModelOptionId(modelId)
  selectedChatBusinessModelId.value = normalizedId
  activeBusinessModelId.value = normalizedId
}

const syncChatBusinessModelSelection = (preferredModelId = null) => {
  const currentTableName = String(selectedTableName.value || '').trim()
  if (!currentTableName) {
    selectedChatBusinessModelId.value = null
    activeBusinessModelId.value = null
    return null
  }

  const preferredIds = [
    preferredModelId,
    selectedChatBusinessModelId.value,
    activeBusinessModelId.value,
    lastCreatedBusinessModelId.value,
    lastAppliedBusinessModelId.value
  ]
    .map(normalizeBusinessModelOptionId)
    .filter(value => value !== null)

  for (const candidateId of preferredIds) {
    const matched = findBusinessModelById(candidateId)
    if (matched && isBusinessModelOnTable(matched, currentTableName)) {
      applyChatBusinessModelSelection(matched.id)
      return matched.id
    }
  }

  const latestModel = filteredBusinessModelsByTable(currentTableName)[0] || null
  if (latestModel?.id != null) {
    applyChatBusinessModelSelection(latestModel.id)
    return latestModel.id
  }

  selectedChatBusinessModelId.value = null
  activeBusinessModelId.value = null
  return null
}

const handleChatBusinessModelChange = (modelId) => {
  const normalizedId = normalizeBusinessModelOptionId(modelId)
  if (normalizedId === null) {
    selectedChatBusinessModelId.value = null
    activeBusinessModelId.value = null
    return
  }
  const matched = findBusinessModelById(normalizedId)
  if (!matched) {
    selectedChatBusinessModelId.value = null
    activeBusinessModelId.value = null
    return
  }
  if (selectedTableName.value && !isBusinessModelOnTable(matched, selectedTableName.value)) {
    syncChatBusinessModelSelection()
    return
  }
  applyChatBusinessModelSelection(matched.id)
}

const getChatChartContainer = () => document.getElementById('echarts-container')

const ensureChatChartInstance = () => {
  const container = getChatChartContainer()
  if (!container) return null

  if (chartInstance?.isDisposed?.()) {
    chartInstance = null
  }

  if (chartInstance && chartInstance.getDom?.() !== container) {
    try {
      chartInstance.dispose()
    } catch (error) {
      // ignore stale echarts instance dispose error
    }
    chartInstance = null
  }

  if (!chartInstance) {
    chartInstance = echarts.getInstanceByDom(container) || echarts.init(container)
  }

  return chartInstance
}

const resolveChartTemplateHeight = (template) => {
  const rawHeight = template?.layout?.height ?? template?.height
  const height = Number(rawHeight)
  if (!Number.isFinite(height)) return 360
  return Math.min(800, Math.max(240, Math.round(height)))
}

const applyChatChartContainerLayout = (template) => {
  const container = getChatChartContainer()
  if (!container) return
  container.style.height = `${resolveChartTemplateHeight(template)}px`
}

const resizeChatChartAfterLayout = () => {
  window.requestAnimationFrame(() => {
    const instance = ensureChatChartInstance()
    instance?.resize()
  })
}

const replayChatChartRenderAnimation = (enabled) => {
  const container = getChatChartContainer()
  if (!container) return
  container.classList.remove('chart-render-animating')
  if (!enabled) return
  void container.offsetWidth
  window.requestAnimationFrame(() => {
    container.classList.add('chart-render-animating')
  })
}
const loadDatasourceHealth = async (datasourceId) => {
  if (!datasourceId) return
  datasourceHealthMap.value[datasourceId] = unwrap(
      await axios.get(`${API_BASE}/api/datasources/${datasourceId}/health`)
  )
}
const ensureSessionAlive = async () => {
  try {
    await axios.get(`${API_BASE}/api/auth/me`).then(unwrap)
    return true
  } catch (error) {
    clearSession()
    return false
  }
}

onMounted(async () => {
  restoreSessionHeader()
  if (!isAuthenticated.value) {
    return
  }
  await bootstrapPersistedSession()
})

const bootstrapPersistedSession = async () => {
  const alive = await ensureSessionAlive()
  if (!alive) {
    ElMessage.warning('登录状态已过期，请重新登录')
    return
  }

  await bootstrapWorkbench()
}

const bootstrapWorkbench = async () => {

  normalizeActiveModule()
  ensureChatChartInstance()
  window.addEventListener('resize', handleChartResize)
  try {
    await Promise.all([
      loadTables(),
      loadBusinessModels(),
      loadAnalysisTemplates(),
      loadRecentChatQueries(),
      loadPinnedHistoryIds(),
      loadChatSessions(),
      loadChatModels(),
      loadVoicePreferences()
    ])
  } catch (error) {
    if ((error.message || '').includes('登录已失效') || (error.message || '').includes('请先登录')) {
      ElMessage.warning('登录状态已过期，请重新登录')
      clearSession()
      return
    }
    ElMessage.error(error.message || '初始化数据加载失败')
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleChartResize)
  clearUploadProgressHideTimer()
})

const normalizeActiveModule = () => {
  const homeModule = homeModuleKey.value
  const firstModule = visibleMenuGroups.value.some(group => group.modules.some(module => module.key === homeModule))
      ? homeModule
      : visibleMenuGroups.value[0]?.modules[0]?.key || 'workbench'
  const allowed = visibleMenuGroups.value.some(group => group.modules.some(module => module.key === activeModule.value))
  if (!allowed) {
    activeModule.value = firstModule
  }
  syncNavigationTabsWithMenu()
}

const toggleAside = () => {
  isAsideCollapsed.value = !isAsideCollapsed.value
}

const goHome = () => {
  activeModule.value = homeModuleKey.value
}

const isHomeModule = (moduleName) => moduleName === 'workbench' || moduleName === 'adminWorkbench'

const tabTitleForModule = (moduleName) => {
  if (moduleName === 'upload') return '数据上传与处理'
  return moduleMap[moduleName]?.title || moduleTitle.value
}

const ensureNavigationTab = (moduleName) => {
  if (!moduleName || isHomeModule(moduleName)) return
  if (navigationTabs.value.some(tab => tab.key === moduleName)) return
  navigationTabs.value.push({
    key: moduleName,
    title: tabTitleForModule(moduleName),
    order: nextTabOrder.value
  })
  nextTabOrder.value += 1
}

const syncNavigationTabsWithMenu = () => {
  const allowedKeys = new Set(visibleMenuGroups.value.flatMap(group => group.modules.map(module => module.key)))
  navigationTabs.value = navigationTabs.value.filter(tab => allowedKeys.has(tab.key) && !isHomeModule(tab.key))
  if (!navigationTabs.value.length && !allowedKeys.has(activeModule.value)) {
    activeModule.value = homeModuleKey.value
  }
}

const closeTab = (moduleName) => {
  const orderedTabs = orderedNavigationTabs.value
  const closingIndex = orderedTabs.findIndex(tab => tab.key === moduleName)
  navigationTabs.value = navigationTabs.value.filter(tab => tab.key !== moduleName)
  if (!navigationTabs.value.length) {
    nextTabOrder.value = 1
  }

  if (activeModule.value !== moduleName) return

  const fallbackTab = orderedTabs[closingIndex - 1] || orderedTabs[closingIndex + 1]
  activeModule.value = fallbackTab?.key || homeModuleKey.value
}

const normalizeSensitiveFields = (value) => {
  if (Array.isArray(value)) {
    return value.map(item => String(item || '').trim()).filter(Boolean)
  }
  const text = String(value || '').trim()
  if (!text) return []
  return text.split(/[,，、;；|]/).map(item => item.trim()).filter(Boolean)
}

const normalizeChatHistoryItem = (item) => {
  const rawSnapshot = parseMaybeJson(item?.chartSnapshot)
  const snapshot = rawSnapshot && typeof rawSnapshot === 'object' ? rawSnapshot : {}
  const normalizeReplaySteps = (steps) => {
    if (!Array.isArray(steps)) return []
    return steps.map((step, index) => {
      if (step && typeof step === 'object' && !Array.isArray(step)) {
        const title = String(step.title || step.stage || `步骤 ${index + 1}`).trim()
        const detail = String(step.detail || step.text || step.message || '').trim()
        return {
          title: title || `步骤 ${index + 1}`,
          detail,
          ts: step.ts ?? null
        }
      }
      const detail = String(step || '').trim()
      if (!detail) return null
      return {
        title: `步骤 ${index + 1}`,
        detail,
        ts: null
      }
    }).filter(Boolean)
  }
  const normalizedReplaySteps = normalizeReplaySteps(
    item?.reasoningReplaySteps
      || snapshot?.reasoningReplaySteps
      || item?.reasoningProcess
      || snapshot?.reasoningProcess
  )
  const question = String(
    item?.question
    || item?.queryText
    || snapshot?.sourceQuestion
    || snapshot?.message
    || ''
  ).trim()
  const tableName = String(
    item?.tableName
    || item?.queryTableName
    || snapshot?.tableName
    || ''
  ).trim()
  const chartType = String(
    item?.chartType
    || snapshot?.chartType
    || ''
  ).trim()
  const chartData = Array.isArray(snapshot?.data) ? snapshot.data : []
  const graphContext = Array.isArray(snapshot?.graphContext) ? snapshot.graphContext : []
  const semanticEvidence = Array.isArray(snapshot?.semanticEvidence) ? snapshot.semanticEvidence : []
  const sensitiveFields = normalizeSensitiveFields(item?.sensitiveFields || snapshot?.sensitiveFields)
  const matchedRules = normalizeSensitiveFields(item?.matchedRules || snapshot?.matchedRules)
  const rawExecutionStatus = Number(item?.executionStatus ?? snapshot?.executionStatus)
  const rawExecutionTimeMs = Number(item?.executionTimeMs ?? snapshot?.executionTimeMs)
  const rawCacheFlag = item?.isHitCache ?? snapshot?.isHitCache
  return {
    id: String(item?.id || `${tableName}::${question}`),
    question,
    tableName,
    chartType,
    riskLevel: String(item?.riskLevel || snapshot?.riskLevel || 'SAFE').trim().toUpperCase(),
    riskReason: String(item?.riskReason || item?.auditInfo || snapshot?.riskReason || '').trim(),
    sensitiveFields,
    matchedRules,
    sql: String(item?.sql || item?.generatedSql || snapshot?.sql || snapshot?.generatedSql || '').trim(),
    sourceType: String(item?.sourceType || '').trim().toUpperCase(),
    conversationId: item?.conversationId == null ? null : String(item?.conversationId),
    userTurnId: item?.userTurnId == null ? null : String(item?.userTurnId),
    assistantTurnId: item?.assistantTurnId == null ? null : String(item?.assistantTurnId),
    turnId: (item?.turnId ?? item?.assistantTurnId) == null ? null : String(item?.turnId ?? item?.assistantTurnId),
    turnNo: item?.turnNo == null ? null : Number(item?.turnNo) || null,
    artifactType: String(item?.artifactType || '').trim().toUpperCase(),
    fieldMapping: snapshot?.fieldMapping && typeof snapshot.fieldMapping === 'object' ? snapshot.fieldMapping : {},
    ...buildChartStyleSnapshot(snapshot),
    chartSortMode: readSnapshotSortMode(snapshot),
    sortIntent: readSnapshotSortIntent(snapshot),
    chartSnapshot: snapshot,
    semanticEvidence,
    graphContext,
    graphPath: snapshot?.graphPath || null,
    graphSqlHints: snapshot?.graphSqlHints || null,
    reasoningReplaySteps: normalizedReplaySteps,
    reasoningProcess: Array.isArray(item?.reasoningProcess)
      ? item.reasoningProcess.map(step => String(step || '').trim()).filter(Boolean)
      : (Array.isArray(snapshot?.reasoningProcess)
        ? snapshot.reasoningProcess.map(step => String(step || '').trim()).filter(Boolean)
        : []),
    executionStatus: Number.isFinite(rawExecutionStatus) ? rawExecutionStatus : null,
    executionTimeMs: Number.isFinite(rawExecutionTimeMs) && rawExecutionTimeMs >= 0 ? rawExecutionTimeMs : null,
    isHitCache: rawCacheFlag === true || Number(rawCacheFlag) === 1 || String(rawCacheFlag || '').trim().toLowerCase() === 'true',
    hasChartSnapshot: chartData.length > 0,
    chartDataCount: chartData.length,
    snapshotStatus: chartData.length > 0 ? 'ready' : 'unknown',
    isPinned: Boolean(item?.isPinned),
    pinnedDashboardNames: Array.isArray(item?.pinnedDashboardNames)
      ? item.pinnedDashboardNames.map(name => String(name || '').trim()).filter(Boolean)
      : [],
    createdAt: item?.createdAt || new Date().toISOString()
  }
}

const extractPinnedHistoryId = (item) => {
  const raw = item?.chart_id ?? item?.chartId ?? item?.historyId ?? item?.id
  const numeric = Number(raw)
  if (Number.isFinite(numeric) && numeric > 0) {
    return String(numeric)
  }
  return String(raw || '').trim()
}

const extractPinnedDashboardNames = (item) => {
  const names = String(item?.dashboard_names ?? item?.dashboardNames ?? '').trim()
  if (!names) return []
  return names.split(',').map(name => String(name || '').trim()).filter(Boolean)
}

const attachPinnedFlagsToHistory = (items) => {
  const pinnedSet = new Set((pinnedHistoryIds.value || []).map(id => String(id || '').trim()).filter(Boolean))
  return items.map(item => ({
    ...item,
    isPinned: pinnedSet.has(String(item?.id || '').trim()),
    pinnedDashboardNames: Array.isArray(item?.pinnedDashboardNames) ? item.pinnedDashboardNames : []
  }))
}

const updateRecentChatHistoryEntry = (entryId, patch) => {
  const targetId = String(entryId || '').trim()
  if (!targetId) return
  recentChatQueries.value = recentChatQueries.value.map(item => {
    if (String(item?.id || '').trim() !== targetId) return item
    return typeof patch === 'function' ? patch(item) : { ...item, ...(patch || {}) }
  })
}

const loadPinnedHistoryIds = async () => {
  try {
    const rows = unwrap(await axios.get(`${API_BASE}/api/c/dashboards/pinned-charts`))
    const list = Array.isArray(rows) ? rows : []
    const idSet = new Set()
    const dashboardNameMap = new Map()
    list.forEach(item => {
      const historyId = extractPinnedHistoryId(item)
      if (!historyId) return
      idSet.add(historyId)
      dashboardNameMap.set(historyId, extractPinnedDashboardNames(item))
    })
    pinnedHistoryIds.value = [...idSet]
    recentChatQueries.value = recentChatQueries.value.map(item => ({
      ...item,
      isPinned: idSet.has(String(item?.id || '').trim()),
      pinnedDashboardNames: dashboardNameMap.get(String(item?.id || '').trim()) || []
    }))
  } catch (error) {
    if (error?.response?.status !== 401) {
      console.warn('loadPinnedHistoryIds failed:', error)
    }
  }
}

const hasRecentChatQueryFilters = (options = {}) => {
  const keyword = String(options.keyword || '').trim()
  const tableName = String(options.tableName || '').trim()
  const chartType = String(options.chartType || '').trim()
  const riskLevel = String(options.riskLevel || '').trim()
  const executionStatus = String(options.executionStatus || '').trim()
  const dateRange = Array.isArray(options.dateRange) ? options.dateRange.filter(Boolean) : []
  return Boolean(keyword || tableName || chartType || riskLevel || executionStatus || dateRange.length)
}

const isHistoryWithinDateRange = (createdAt, dateRange = []) => {
  if (!Array.isArray(dateRange) || !dateRange.length) return true
  const rawDate = new Date(createdAt)
  if (Number.isNaN(rawDate.getTime())) return false
  const start = dateRange?.[0] ? new Date(`${dateRange[0]}T00:00:00`) : null
  const end = dateRange?.[1] ? new Date(`${dateRange[1]}T23:59:59`) : null
  if (start && rawDate < start) return false
  if (end && rawDate > end) return false
  return true
}

const filterRecentChatHistoryItems = (items, options = {}) => {
  const keyword = String(options.keyword || '').trim().toLowerCase()
  const tableName = String(options.tableName || '').trim()
  const chartType = String(options.chartType || '').trim()
  const riskLevel = String(options.riskLevel || '').trim().toUpperCase()
  const dateRange = Array.isArray(options.dateRange) ? options.dateRange : []
  return items.filter(item => {
    if (keyword) {
      const haystack = [
        item?.question,
        item?.sql,
        item?.tableName
      ].map(value => String(value || '').toLowerCase()).join(' ')
      if (!haystack.includes(keyword)) {
        return false
      }
    }
    if (tableName && String(item?.tableName || '').trim() !== tableName) {
      return false
    }
    if (chartType && String(item?.chartType || '').trim() !== chartType) {
      return false
    }
    if (riskLevel && String(item?.riskLevel || '').trim().toUpperCase() !== riskLevel) {
      return false
    }
    return isHistoryWithinDateRange(item?.createdAt, dateRange)
  })
}

const fetchAllRecentChatHistoryItems = async (params = {}) => {
  const firstPage = unwrap(await axios.get(`${API_BASE}/api/chat/history`, {
    params: {
      page: 1,
      pageSize: 50,
      keyword: params.keyword || undefined,
      tableName: params.tableName || undefined,
      chartType: params.chartType || undefined,
      riskLevel: params.riskLevel || undefined,
      dateFrom: params.dateRange?.[0] || undefined,
      dateTo: params.dateRange?.[1] || undefined
    }
  }))
  const firstItems = Array.isArray(firstPage?.items)
    ? firstPage.items.map(normalizeChatHistoryItem).filter(item => item.question)
    : []
  const total = Number(firstPage?.total || firstItems.length || 0)
  const totalPages = Math.max(1, Math.ceil(total / 50))
  if (totalPages <= 1) {
    return firstItems
  }
  const extraPages = await Promise.all(
    Array.from({ length: totalPages - 1 }, (_, index) =>
      axios.get(`${API_BASE}/api/chat/history`, {
        params: {
          page: index + 2,
          pageSize: 50,
          keyword: params.keyword || undefined,
          tableName: params.tableName || undefined,
          chartType: params.chartType || undefined,
          riskLevel: params.riskLevel || undefined,
          dateFrom: params.dateRange?.[0] || undefined,
          dateTo: params.dateRange?.[1] || undefined
        }
      }).then(unwrap)
    )
  )
  const extraItems = extraPages.flatMap(page =>
    Array.isArray(page?.items)
      ? page.items.map(normalizeChatHistoryItem).filter(item => item.question)
      : []
  )
  return [...firstItems, ...extraItems]
}

const normalizeChartArtifactSnapshot = (artifact) => {
  if (!artifact || typeof artifact !== 'object') return null
  const raw = artifact.artifact ?? artifact.artifactJson ?? artifact.chartSnapshot ?? null
  const parsed = parseMaybeJson(raw)
  return parsed && typeof parsed === 'object' ? parsed : null
}

const buildAnalysisFromTurn = (turn, chartArtifact, sqlArtifact, fallbackMessage = '') => {
  const snapshot = normalizeChartArtifactSnapshot(chartArtifact)
  if (!snapshot || !Array.isArray(snapshot.data) || !snapshot.data.length) return null
  const context = turn?.context && typeof turn.context === 'object' ? turn.context : {}
  return {
    tableName: String(snapshot.tableName || context.tableName || '').trim(),
    chartType: String(snapshot.chartType || chartArtifact?.chartType || 'bar').trim() || 'bar',
    data: Array.isArray(snapshot.data) ? snapshot.data : [],
    fieldMapping: snapshot.fieldMapping || {},
    ...buildChartStyleSnapshot(snapshot),
    chartSortMode: readSnapshotSortMode(snapshot),
    sortIntent: readSnapshotSortIntent(snapshot),
    sql: String(snapshot.sql || chartArtifact?.sqlText || sqlArtifact?.sqlText || '').trim(),
    sourceSql: String(snapshot.sql || chartArtifact?.sqlText || sqlArtifact?.sqlText || '').trim(),
    sourceQuestion: String(context.question || '').trim(),
    sourceTableName: String(snapshot.tableName || context.tableName || '').trim(),
    message: String(snapshot.message || fallbackMessage || turn?.messageText || '').trim(),
    chartSnapshot: snapshot,
    semanticEvidence: Array.isArray(snapshot.semanticEvidence) ? snapshot.semanticEvidence : [],
    graphContext: Array.isArray(snapshot.graphContext) ? snapshot.graphContext : [],
    graphPath: snapshot.graphPath || null,
    graphSqlHints: snapshot.graphSqlHints || null,
    riskLevel: chartArtifact?.riskLevel || snapshot.riskLevel || 'SAFE',
    riskReason: String(context.riskReason || snapshot.riskReason || '').trim(),
    sensitiveFields: normalizeSensitiveFields(snapshot.sensitiveFields || context.sensitiveFields),
    matchedRules: normalizeSensitiveFields(snapshot.matchedRules || context.matchedRules),
    queryHistoryId: chartArtifact?.historyId == null ? null : String(chartArtifact.historyId),
    artifactId: chartArtifact?.id == null ? null : String(chartArtifact.id),
    artifactIds: [chartArtifact?.id, sqlArtifact?.id].filter(Boolean).map(item => String(item)),
    assistantTurnId: turn?.id == null ? null : String(turn.id),
    turnId: turn?.id == null ? null : String(turn.id),
    turnNo: Number(turn?.turnNo || 0) || null
  }
}

const buildAlertRuleDraftFromTurn = (chartArtifact) => {
  const snapshot = normalizeChartArtifactSnapshot(chartArtifact)
  const draft = snapshot?.alertRuleDraft
  return draft && typeof draft === 'object' && Object.keys(draft).length ? draft : null
}

const buildAlertRuleCreatedFromTurn = (turn, chartArtifact, advancedAnalysis) => {
  const snapshot = normalizeChartArtifactSnapshot(chartArtifact)
  const created = snapshot?.alertRuleCreated
  if (created && typeof created === 'object' && Object.keys(created).length) {
    return created
  }
  if (advancedAnalysis?.type === 'alert') {
    return {
      id: advancedAnalysis.ruleId || advancedAnalysis.params?.ruleId || null,
      title: advancedAnalysis.title || String(turn?.messageText || '预警规则'),
      metricField: advancedAnalysis.params?.metricField || '',
      timeField: advancedAnalysis.params?.timeField || '',
      operator: advancedAnalysis.params?.operator || '',
      threshold: advancedAnalysis.params?.threshold,
      channels: advancedAnalysis.params?.channels || []
    }
  }
  return null
}

const isAdvancedAnalysisArtifact = (artifact) =>
  String(artifact?.artifactType || '').toUpperCase().startsWith('ADVANCED_')

const normalizeAdvancedAnalysisHistoryType = (type) => {
  const value = String(type || '').trim()
  const lower = value.toLowerCase()
  if (lower.includes('what')) return 'whatIf'
  if (lower.includes('alert')) return 'alert'
  if (lower.includes('forecast') || lower.includes('predict')) return 'forecast'
  return ''
}

const hasExplicitAdvancedAnalysisSnapshot = (snapshot, artifact = null) => {
  if (!snapshot || typeof snapshot !== 'object') return false
  if (artifact && isAdvancedAnalysisArtifact(artifact)) return true
  if (snapshot.advancedAnalysis && typeof snapshot.advancedAnalysis === 'object') return true
  if (snapshot.advancedAnalysisResult && typeof snapshot.advancedAnalysisResult === 'object') return true
  if (snapshot.autoForecast === true) return true
  if (normalizeAdvancedAnalysisHistoryType(snapshot.advancedAnalysisType)) return true
  if (normalizeAdvancedAnalysisHistoryType(snapshot.responseType)) return true
  if (normalizeAdvancedAnalysisHistoryType(snapshot.type)) return true
  const chartType = String(snapshot.chartType || '').trim().toLowerCase()
  return ['forecast', 'whatif', 'what_if', 'alert'].includes(chartType)
}

const buildAdvancedAnalysisFromTurn = (turn, advancedArtifact, fallbackArtifact = null) => {
  const snapshot = normalizeChartArtifactSnapshot(advancedArtifact) || normalizeChartArtifactSnapshot(fallbackArtifact)
  if (!snapshot || typeof snapshot !== 'object') return null
  const sourceArtifact = advancedArtifact || fallbackArtifact
  if (!hasExplicitAdvancedAnalysisSnapshot(snapshot, sourceArtifact)) return null
  const rawAnalysis = snapshot.advancedAnalysis && typeof snapshot.advancedAnalysis === 'object'
    ? snapshot.advancedAnalysis
    : snapshot
  const type = normalizeAdvancedAnalysisHistoryType(
    rawAnalysis.type || snapshot.advancedAnalysisType || snapshot.responseType || snapshot.type || sourceArtifact?.artifactType
  )
  if (!type) return null
  return {
    ...rawAnalysis,
    id: rawAnalysis.id || `advanced-history-${sourceArtifact?.id || turn?.id || Date.now()}`,
    type,
    title: rawAnalysis.title || snapshot.title || String(turn?.messageText || '高级分析记录'),
    summary: rawAnalysis.summary || snapshot.summary || String(turn?.messageText || ''),
    tableName: rawAnalysis.tableName || snapshot.tableName || '',
    sourceQuestion: rawAnalysis.sourceQuestion || snapshot.sourceQuestion || '',
    snapshotTruncated: Boolean(rawAnalysis.snapshotTruncated || snapshot.snapshotTruncated),
    storagePolicy: rawAnalysis.storagePolicy || snapshot.storagePolicy || {},
    conversationId: sourceArtifact?.conversationId == null ? null : String(sourceArtifact.conversationId),
    assistantTurnId: turn?.id == null ? null : String(turn.id),
    artifactId: sourceArtifact?.id == null ? null : String(sourceArtifact.id),
    chatRecord: {
      conversationId: sourceArtifact?.conversationId,
      assistantTurnId: turn?.id,
      artifactId: sourceArtifact?.id,
      artifactType: sourceArtifact?.artifactType,
      recorded: true
    }
  }
}

const restoreAdvancedThinkingLogsFromTurn = (turn, advancedArtifact, advancedAnalysis, fallbackArtifact = null) => {
  const snapshot = normalizeChartArtifactSnapshot(advancedArtifact) || normalizeChartArtifactSnapshot(fallbackArtifact)
  const candidates = [
    advancedArtifact?.artifact?.thinkingLogs,
    snapshot?.thinkingLogs,
    snapshot?.advancedAnalysis?.thinkingLogs,
    advancedAnalysis?.thinkingLogs,
    turn?.context?.thinkingLogs
  ]
  const logs = candidates.find(item => Array.isArray(item) && item.length)
  return Array.isArray(logs)
    ? logs.map(item => String(item || '').trim()).filter(Boolean).slice(0, CHAT_THINKING_LOG_LIMIT)
    : []
}

const normalizeThinkingLogs = (logs = []) => {
  if (!Array.isArray(logs)) return []
  return logs.map((item, index) => {
    if (item && typeof item === 'object' && !Array.isArray(item)) {
      const title = String(item.title || item.stage || `步骤 ${index + 1}`).trim()
      const detail = String(item.detail || item.text || item.message || '').trim()
      return `${title || `步骤 ${index + 1}`}：${detail}`.trim()
    }
    return String(item || '').trim()
  }).filter(Boolean).slice(0, CHAT_THINKING_LOG_LIMIT)
}

const restoreThinkingLogsFromTurn = (turn, chartArtifact, advancedArtifact, advancedAnalysis) => {
  if (advancedAnalysis) {
    return restoreAdvancedThinkingLogsFromTurn(turn, advancedArtifact, advancedAnalysis, chartArtifact)
  }
  const snapshot = normalizeChartArtifactSnapshot(chartArtifact)
  const candidates = [
    turn?.context?.thinkingLogs,
    turn?.context?.reasoningReplaySteps,
    turn?.context?.reasoningLogs,
    chartArtifact?.artifact?.thinkingLogs,
    chartArtifact?.artifact?.reasoningReplaySteps,
    chartArtifact?.artifact?.reasoningLogs,
    snapshot?.thinkingLogs,
    snapshot?.reasoningReplaySteps,
    snapshot?.reasoningLogs
  ]
  const logs = candidates.find(item => Array.isArray(item) && item.length)
  return normalizeThinkingLogs(logs)
}

const restoreAnalysisFromMessage = (message, options = {}) => {
  const analysis = message?.analysisSnapshot
  if (!analysis || !Array.isArray(analysis.data) || !analysis.data.length) return false
  const restoreModule = options.activateChat !== false
  lastAnalysis.value = {
    ...analysis,
    sourceQuestion: String(analysis.sourceQuestion || message?.sourceQuestion || '').trim(),
    sourceTableName: String(analysis.sourceTableName || analysis.tableName || '').trim()
  }
  currentChartType.value = lastAnalysis.value.chartType || 'bar'
  applyAnalysisSortMode(lastAnalysis.value, 'name')
  if (restoreModule) {
    activeModule.value = 'chat'
  }
  nextTick(() => {
    renderChart(lastAnalysis.value.data, currentChartType.value)
  })
  return true
}

const buildAnalysisFromHistoryItem = (entry) => {
  const snapshot = entry?.chartSnapshot && typeof entry.chartSnapshot === 'object'
    ? entry.chartSnapshot
    : parseMaybeJson(entry?.chartSnapshot)
  if (!snapshot || typeof snapshot !== 'object') return null
  const chartData = Array.isArray(snapshot.data) ? snapshot.data : []
  if (!chartData.length) return null
  const tableName = String(entry?.tableName || snapshot.tableName || '').trim()
  const chartType = String(entry?.chartType || snapshot.chartType || 'bar').trim() || 'bar'
  const sql = String(entry?.sql || snapshot.sql || snapshot.generatedSql || '').trim()
  const question = String(entry?.question || snapshot.sourceQuestion || '').trim()
  return {
    tableName,
    chartType,
    data: chartData,
    fieldMapping: snapshot.fieldMapping || {},
    ...buildChartStyleSnapshot(snapshot),
    chartSortMode: readSnapshotSortMode(snapshot),
    sortIntent: readSnapshotSortIntent(snapshot),
    sql,
    sourceSql: sql,
    sourceQuestion: question,
    sourceTableName: tableName,
    message: String(snapshot.message || entry?.riskReason || '').trim() || `历史查询：${question || tableName || '图表'}`,
    chartSnapshot: snapshot,
    semanticEvidence: Array.isArray(snapshot.semanticEvidence) ? snapshot.semanticEvidence : [],
    graphContext: Array.isArray(snapshot.graphContext) ? snapshot.graphContext : [],
    graphPath: snapshot.graphPath || null,
    graphSqlHints: snapshot.graphSqlHints || null,
    reasoningReplaySteps: Array.isArray(entry?.reasoningReplaySteps)
      ? entry.reasoningReplaySteps
      : (Array.isArray(snapshot.reasoningReplaySteps) ? snapshot.reasoningReplaySteps : []),
    reasoningProcess: Array.isArray(snapshot.reasoningProcess)
      ? snapshot.reasoningProcess.map(step => String(step || '').trim()).filter(Boolean)
      : [],
    riskLevel: String(entry?.riskLevel || snapshot.riskLevel || 'SAFE').trim().toUpperCase() || 'SAFE',
    riskReason: String(entry?.riskReason || snapshot.riskReason || '').trim(),
    sensitiveFields: normalizeSensitiveFields(entry?.sensitiveFields || snapshot.sensitiveFields),
    matchedRules: normalizeSensitiveFields(entry?.matchedRules || snapshot.matchedRules),
    queryHistoryId: entry?.id == null ? null : String(entry.id),
    artifactId: entry?.artifactId == null ? null : String(entry.artifactId),
    artifactIds: Array.isArray(snapshot.artifactIds)
      ? snapshot.artifactIds.map(item => String(item || '')).filter(Boolean)
      : [],
    conversationId: entry?.conversationId == null ? null : String(entry.conversationId),
    assistantTurnId: entry?.assistantTurnId == null ? null : String(entry.assistantTurnId),
    turnId: (entry?.turnId || entry?.assistantTurnId) == null ? null : String(entry.turnId || entry.assistantTurnId),
    turnNo: entry?.turnNo == null ? null : Number(entry.turnNo) || null
  }
}

const fetchHistoryChartSnapshot = async (historyId) => {
  const id = Number(historyId)
  if (!Number.isFinite(id) || id <= 0) return null
  const rows = unwrap(await axios.post(`${API_BASE}/api/chat/history/charts-batch`, {
    ids: [id]
  }))
  const item = Array.isArray(rows) ? rows.find(row => Number(row?.id) === id) : null
  if (!item) return null
  return normalizeChatHistoryItem({
    ...item,
    question: item?.queryText ?? item?.question,
    tableName: item?.queryTableName ?? item?.tableName,
    sql: item?.generatedSql ?? item?.sql,
    isPinned: pinnedHistoryIds.value.includes(String(id))
  })
}

const ensureHistoryEntrySnapshot = async (entry) => {
  if (!entry?.id) return entry
  const localSnapshot = entry?.chartSnapshot && typeof entry.chartSnapshot === 'object' ? entry.chartSnapshot : {}
  if (Array.isArray(localSnapshot.data) && localSnapshot.data.length) {
    const readyEntry = {
      ...entry,
      hasChartSnapshot: true,
      chartDataCount: localSnapshot.data.length,
      snapshotStatus: 'ready'
    }
    updateRecentChatHistoryEntry(entry.id, readyEntry)
    return readyEntry
  }
  try {
    const remoteEntry = await fetchHistoryChartSnapshot(entry.id)
    if (!remoteEntry) {
      const missingEntry = {
        ...entry,
        hasChartSnapshot: false,
        chartDataCount: 0,
        snapshotStatus: 'missing'
      }
      updateRecentChatHistoryEntry(entry.id, missingEntry)
      return missingEntry
    }
    const merged = {
      ...entry,
      ...remoteEntry,
      question: remoteEntry.question || entry.question,
      tableName: remoteEntry.tableName || entry.tableName,
      sql: remoteEntry.sql || entry.sql,
      chartType: remoteEntry.chartType || entry.chartType,
      chartSnapshot: Object.keys(remoteEntry.chartSnapshot || {}).length
        ? remoteEntry.chartSnapshot
        : (entry.chartSnapshot || {}),
      hasChartSnapshot: Boolean(remoteEntry.hasChartSnapshot),
      chartDataCount: Number(remoteEntry.chartDataCount || 0),
      snapshotStatus: remoteEntry.hasChartSnapshot ? 'ready' : 'missing'
    }
    updateRecentChatHistoryEntry(entry.id, merged)
    return merged
  } catch (error) {
    console.warn('ensureHistoryEntrySnapshot failed:', error)
    const errorEntry = {
      ...entry,
      snapshotStatus: 'error'
    }
    updateRecentChatHistoryEntry(entry.id, errorEntry)
    return errorEntry
  }
}

const restoreAnalysisFromHistory = async (entry) => {
  const resolvedEntry = await ensureHistoryEntrySnapshot(entry)
  const analysis = buildAnalysisFromHistoryItem(resolvedEntry)
  if (!analysis) return false
  lastAnalysis.value = analysis
  currentChartType.value = analysis.chartType || 'bar'
  applyAnalysisSortMode(analysis, 'name')
  if (resolvedEntry?.tableName && isAccessibleTable(resolvedEntry.tableName)) {
    selectedTableName.value = resolvedEntry.tableName
  }
  nextTick(() => {
    renderChart(lastAnalysis.value.data, currentChartType.value)
  })
  return true
}

const setActiveBranchParent = (message) => {
  const turnId = String(message?.turnId || '').trim()
  if (!turnId) {
    activeBranchParentTurnId.value = null
    activeBranchParentTurnMeta.value = null
    return
  }
  activeBranchParentTurnId.value = turnId
  activeBranchParentTurnMeta.value = {
    turnId,
    turnNo: Number(message?.turnNo || 0) || null,
    preview: String(message?.content || '').trim().slice(0, 40),
    source: 'message',
    role: String(message?.role || '').trim() || 'system',
    tableName: String(message?.sourceTableName || message?.tableName || selectedTableName.value || '').trim()
  }
}

const openHistoricalAnalysis = async (message) => {
  if (!restoreAnalysisFromMessage(message)) {
    const historyId = message?.queryHistoryId
      ?? message?.chartId
      ?? message?.historyId
      ?? message?.analysisSnapshot?.queryHistoryId
    if (historyId) {
      const entry = await fetchHistoryChartSnapshot(historyId)
      if (entry && await restoreAnalysisFromHistory(entry)) {
        const preview = String(entry?.question || entry?.tableName || '历史图表').trim()
        ElMessage.success(`已切换到历史图表：${preview.slice(0, 24)}`)
        return true
      }
    }
    ElMessage.warning('该消息暂未保存可回放图表')
    return false
  }
  const preview = String(message?.sourceQuestion || message?.content || '历史图表').trim()
  ElMessage.success(`已切换到历史图表：${preview.slice(0, 24)}`)
  return true
}

const openHistoricalAnalysisFromHistory = async (entry) => {
  if (!await restoreAnalysisFromHistory(entry)) {
    ElMessage.warning('该历史记录暂未保存可回放图表')
    return false
  }
  const preview = String(entry?.question || entry?.tableName || '历史图表').trim()
  ElMessage.success(`已恢复历史产物：${preview.slice(0, 24)}`)
  return true
}

const clearActiveBranchParent = () => {
  activeBranchParentTurnId.value = null
  activeBranchParentTurnMeta.value = null
}

const normalizeChatSessionSummary = (summary) =>
  String(summary || '')
    .replace(/\r?\n+/g, ' · ')
    .replace(/\s+/g, ' ')
    .replace(/最近问题[:：]\s*/g, '问：')
    .replace(/最近回答[:：]\s*/g, '答：')
    .trim()

const normalizeChatSessionItem = (item) => ({
  id: String(item?.id || ''),
  title: String(item?.title || '新对话').trim(),
  summary: normalizeChatSessionSummary(item?.summary),
  tableName: String(item?.scope?.tableName || item?.tableName || '').trim(),
  status: String(item?.status || 'ACTIVE').trim().toUpperCase(),
  advancedTypes: Array.isArray(item?.advancedTypes)
    ? item.advancedTypes.map(type => ({
      value: String(type?.value || type || '').trim(),
      label: String(type?.label || '').trim()
    })).filter(type => type.value)
    : [],
  latestAdvancedType: String(item?.latestAdvancedType || '').trim(),
  turnCount: Number(item?.turnCount || 0),
  updatedAt: item?.updatedAt || item?.createdAt || new Date().toISOString()
})

const normalizeChatSessionStatus = (status) => {
  const text = String(status || '').trim().toUpperCase()
  if (['ALL', 'ACTIVE', 'ARCHIVED'].includes(text)) return text
  return 'ACTIVE'
}

const normalizeChatSessionAdvancedType = (type) => {
  const text = String(type || '').trim()
  if (['forecast', 'whatIf', 'alert', 'ALL'].includes(text)) return text
  return 'ALL'
}

const syncChatSessionListItem = (session) => {
  const normalized = normalizeChatSessionItem(session)
  if (!normalized.id) return
  chatSessions.value = [normalized, ...chatSessions.value.filter(item => item.id !== normalized.id)]
}

const loadChatSessions = async (options = {}) => {
  if (!isAuthenticated.value) {
    chatSessions.value = []
    activeChatSessionId.value = null
    return
  }
  const nextKeyword = String(options.keyword ?? chatSessionKeyword.value ?? '').trim()
  const nextStatus = normalizeChatSessionStatus(options.status ?? chatSessionStatus.value)
  const nextAdvancedType = normalizeChatSessionAdvancedType(options.advancedType ?? chatSessionAdvancedType.value)
  chatSessionKeyword.value = nextKeyword
  chatSessionStatus.value = nextStatus
  chatSessionAdvancedType.value = nextAdvancedType
  chatSessionLoading.value = true
  try {
    const data = unwrap(await axios.get(`${API_BASE}/api/chat/sessions`, {
      params: {
        page: 1,
        pageSize: 20,
        keyword: nextKeyword || undefined,
        status: nextStatus === 'ALL' ? undefined : nextStatus,
        advancedType: nextAdvancedType === 'ALL' ? undefined : nextAdvancedType
      }
    }))
    chatSessions.value = Array.isArray(data?.items)
      ? data.items.map(normalizeChatSessionItem).filter(item => item.id)
      : []
  } catch (error) {
    chatSessions.value = []
    if (error?.response?.status !== 401) {
      console.warn('loadChatSessions failed:', error)
    }
  } finally {
    chatSessionLoading.value = false
  }
}

const createChatSession = async () => {
  if (loading.value || isStreaming.value) {
    ElMessage.warning('当前正在生成，请稍后再试')
    return
  }
  const payload = {
    title: '',
    tableName: selectedTableName.value || undefined,
    businessModelId: selectedChatBusinessModelId.value || undefined
  }
  const data = unwrap(await axios.post(`${API_BASE}/api/chat/sessions`, payload))
  const session = normalizeChatSessionItem(data)
  if (session.id) {
    activeChatSessionId.value = session.id
    clearActiveBranchParent()
    syncChatSessionListItem(session)
    lastAnalysis.value = null
    currentChartType.value = ''
    chartAnimationMeta.value = null
    pendingDashboardPinSource.value = null
    ensureChatChartInstance()?.clear()
    messages.value = [
      { role: 'system', content: '已开始一个新的连续对话。' }
    ]
  }
}

const selectChatSession = async (sessionId) => {
  if (!sessionId || String(activeChatSessionId.value || '') === String(sessionId)) return
  if (loading.value || isStreaming.value) {
    ElMessage.warning('当前正在生成，请稍后再切换会话')
    return
  }
  activeChatSessionId.value = String(sessionId)
  clearActiveBranchParent()
  try {
    const turns = unwrap(await axios.get(`${API_BASE}/api/chat/sessions/${sessionId}/turns`))
    const nextMessages = []
    let restoredAnalysis = null
    for (const turn of Array.isArray(turns) ? turns : []) {
      const role = String(turn?.role || '').toUpperCase() === 'USER' ? 'user' : 'system'
      const artifacts = Array.isArray(turn?.artifacts) ? turn.artifacts : []
      const chartArtifact = artifacts.find(item => String(item?.artifactType || '').toUpperCase() === 'CHART')
      const sqlArtifact = artifacts.find(item => String(item?.artifactType || '').toUpperCase() === 'SQL')
      const advancedArtifact = artifacts.find(isAdvancedAnalysisArtifact)
      const advancedAnalysis = role === 'system'
        ? buildAdvancedAnalysisFromTurn(turn, advancedArtifact, chartArtifact)
        : null
      const analysisSnapshot = role === 'system'
        ? buildAnalysisFromTurn(turn, chartArtifact, sqlArtifact, String(turn?.messageText || ''))
        : null
      const alertRuleCreated = role === 'system'
        ? buildAlertRuleCreatedFromTurn(turn, chartArtifact, advancedAnalysis)
        : null
      const alertRuleDraft = role === 'system' && !alertRuleCreated
        ? buildAlertRuleDraftFromTurn(chartArtifact)
        : null
      if (analysisSnapshot) {
        restoredAnalysis = analysisSnapshot
      }
      nextMessages.push({
        role,
        content: String(turn?.messageText || ''),
        sql: sqlArtifact?.sqlText || chartArtifact?.sqlText || '',
        turnId: turn?.id == null ? null : String(turn.id),
        parentTurnId: turn?.parentTurnId == null ? null : String(turn.parentTurnId),
        turnNo: Number(turn?.turnNo || 0) || null,
        artifactId: (chartArtifact || advancedArtifact)?.id == null ? null : String((chartArtifact || advancedArtifact).id),
        artifactIds: artifacts.map(item => String(item?.id || '')).filter(Boolean),
        analysisSnapshot,
        advancedAnalysis,
        alertRuleDraft,
        alertRuleCreated,
        clickableChart: Boolean(analysisSnapshot),
        sourceQuestion: analysisSnapshot?.sourceQuestion || advancedAnalysis?.sourceQuestion || '',
        thinkingLogs: restoreThinkingLogsFromTurn(turn, chartArtifact, advancedArtifact, advancedAnalysis),
        thinkingCollapsed: true,
        chatRecordStatus: advancedAnalysis ? 'saved' : undefined
      })
    }
    messages.value = nextMessages.length
      ? nextMessages
      : [{ role: 'system', content: '这个会话还没有消息，可以直接继续提问。' }]
    if (restoredAnalysis) {
      restoreAnalysisFromMessage({ analysisSnapshot: restoredAnalysis }, { activateChat: false })
    } else {
      lastAnalysis.value = null
      currentChartType.value = ''
      ensureChatChartInstance()?.clear()
    }
  } catch (error) {
    ElMessage.error(error.message || '加载会话失败')
  }
}

const searchChatSessions = async () => {
  await loadChatSessions({
    keyword: chatSessionKeyword.value,
    status: chatSessionStatus.value,
    advancedType: chatSessionAdvancedType.value
  })
}

const resetChatSessionSearch = async () => {
  chatSessionKeyword.value = ''
  chatSessionStatus.value = 'ACTIVE'
  chatSessionAdvancedType.value = 'ALL'
  await loadChatSessions({
    keyword: '',
    status: 'ACTIVE',
    advancedType: 'ALL'
  })
}

const refreshActiveChatSessionSummary = async () => {
  if (!activeChatSessionId.value) {
    ElMessage.warning('请先选择一个会话')
    return
  }
  const data = unwrap(await axios.post(`${API_BASE}/api/chat/sessions/${activeChatSessionId.value}/summary`, {}))
  syncChatSessionListItem(data)
  ElMessage.success('会话摘要已刷新')
}

const renameChatSession = async (session) => {
  if (!session?.id) return
  try {
    const { value } = await ElMessageBox.prompt('请输入新的会话名称', '重命名会话', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: session.title || '',
      inputPlaceholder: '例如：华东销量趋势分析',
      inputPattern: /\S+/,
      inputErrorMessage: '会话名称不能为空'
    })
    const title = String(value || '').trim()
    if (!title) return
    const data = unwrap(await axios.post(`${API_BASE}/api/chat/sessions/${session.id}/rename`, { title }))
    syncChatSessionListItem(data)
    ElMessage.success('会话已重命名')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '会话重命名失败')
    }
  }
}

const updateChatSessionStatus = async (session, status) => {
  if (!session?.id) return
  const normalizedStatus = normalizeChatSessionStatus(status)
  const data = unwrap(await axios.post(`${API_BASE}/api/chat/sessions/${session.id}/status`, {
    status: normalizedStatus
  }))
  if (String(activeChatSessionId.value || '') === String(session.id)) {
    activeChatSessionId.value = String(session.id)
  }
  const shouldKeepInList = chatSessionStatus.value === 'ALL' || chatSessionStatus.value === normalizedStatus
  if (shouldKeepInList) {
    syncChatSessionListItem(data)
  } else {
    chatSessions.value = chatSessions.value.filter(item => item.id !== String(session.id))
  }
  ElMessage.success(normalizedStatus === 'ARCHIVED' ? '会话已归档' : '会话已恢复')
}

const deleteChatSession = async (session) => {
  if (!session?.id) return
  try {
    await ElMessageBox.confirm(
      `确认删除会话“${session.title || '未命名会话'}”吗？`,
      '删除会话',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      }
    )
    await axios.post(`${API_BASE}/api/chat/sessions/${session.id}/delete`).then(unwrap)
    const isCurrent = String(activeChatSessionId.value || '') === String(session.id)
    chatSessions.value = chatSessions.value.filter(item => item.id !== String(session.id))
    if (isCurrent) {
      activeChatSessionId.value = null
      clearActiveBranchParent()
      messages.value = [
        { role: 'system', content: '当前会话已删除，你可以开始一个新的连续对话。' }
      ]
      lastAnalysis.value = null
      currentChartType.value = ''
      ensureChatChartInstance()?.clear()
    }
    ElMessage.success('会话已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '删除会话失败')
    }
  }
}

const isOfficialTableName = (tableName) => String(tableName || '').startsWith('official:')
const isAccessibleTable = (tableName) => tables.value.some(item => item.tableName === tableName)

const loadRecentChatQueries = async (options = {}) => {
  if (!isAuthenticated.value) {
    recentChatQueries.value = []
    recentChatQueryTotal.value = 0
    recentChatQueryPage.value = 1
    recentChatQueryLoading.value = false
    recentChatQueryError.value = ''
    recentChatQueryErrorType.value = ''
    return
  }
  const nextPage = Math.max(1, Number(options.page ?? recentChatQueryPage.value ?? 1))
  const nextPageSize = Math.max(1, Math.min(50, Number(options.pageSize ?? recentChatQueryPageSize.value ?? 8)))
  const nextKeyword = String(options.keyword ?? recentChatQueryKeyword.value ?? '').trim()
  const nextTableName = String(options.tableName ?? recentChatQueryTableName.value ?? '').trim()
  const nextChartType = String(options.chartType ?? recentChatQueryChartType.value ?? '').trim()
  const nextRiskLevel = String(options.riskLevel ?? recentChatQueryRiskLevel.value ?? '').trim()
  const nextExecutionStatus = String(options.executionStatus ?? recentChatQueryExecutionStatus.value ?? '').trim()
  const nextDateRange = Array.isArray(options.dateRange ?? recentChatQueryDateRange.value)
    ? (options.dateRange ?? recentChatQueryDateRange.value)
    : []
  const nextSortDirection = String(options.sortDirection ?? recentChatQuerySortDirection.value ?? 'DESC').trim().toUpperCase() === 'ASC'
    ? 'ASC'
    : 'DESC'
  const skipAdjust = Boolean(options.skipAdjust)
  try {
    recentChatQueryLoading.value = true
    recentChatQueryError.value = ''
    recentChatQueryErrorType.value = ''
    const currentFilters = {
      keyword: nextKeyword,
      tableName: nextTableName,
      chartType: nextChartType,
      riskLevel: nextRiskLevel,
      executionStatus: nextExecutionStatus,
      dateRange: nextDateRange
    }
    const data = unwrap(await axios.get(`${API_BASE}/api/chat/history`, {
      params: {
        page: nextPage,
        pageSize: nextPageSize,
        keyword: nextKeyword || undefined,
        tableName: nextTableName || undefined,
        chartType: nextChartType || undefined,
        riskLevel: nextRiskLevel || undefined,
        executionStatus: nextExecutionStatus || undefined,
        dateFrom: nextDateRange?.[0] || undefined,
        dateTo: nextDateRange?.[1] || undefined,
        sortDirection: nextSortDirection
      }
    }))
    const items = Array.isArray(data?.items)
      ? data.items.map(normalizeChatHistoryItem).filter(item => item.question)
      : []
    recentChatQueries.value = attachPinnedFlagsToHistory(items)
    recentChatQueryTotal.value = Number(data?.total || 0)
    recentChatQueryPage.value = Number(data?.page || nextPage)
    recentChatQueryPageSize.value = Number(data?.pageSize || nextPageSize)
    recentChatQueryKeyword.value = String(data?.keyword ?? nextKeyword).trim()
    recentChatQueryTableName.value = nextTableName
    recentChatQueryChartType.value = nextChartType
    recentChatQueryRiskLevel.value = nextRiskLevel
    recentChatQueryExecutionStatus.value = nextExecutionStatus
    recentChatQueryDateRange.value = nextDateRange
    recentChatQuerySortDirection.value = nextSortDirection

    if (!skipAdjust) {
      const maxPage = Math.max(1, Math.ceil(recentChatQueryTotal.value / recentChatQueryPageSize.value))
      if (recentChatQueryPage.value > maxPage) {
        recentChatQueryPage.value = maxPage
        await loadRecentChatQueries({
          page: maxPage,
          pageSize: recentChatQueryPageSize.value,
          keyword: recentChatQueryKeyword.value,
          tableName: recentChatQueryTableName.value,
          chartType: recentChatQueryChartType.value,
          riskLevel: recentChatQueryRiskLevel.value,
          executionStatus: recentChatQueryExecutionStatus.value,
          dateRange: recentChatQueryDateRange.value,
          sortDirection: recentChatQuerySortDirection.value,
          skipAdjust: true
        })
      }
    }
  } catch (error) {
    recentChatQueries.value = []
    recentChatQueryTotal.value = 0
    recentChatQueryErrorType.value = hasRecentChatQueryFilters({
      keyword: nextKeyword,
      tableName: nextTableName,
      chartType: nextChartType,
      riskLevel: nextRiskLevel,
      executionStatus: nextExecutionStatus,
      dateRange: nextDateRange
    }) ? 'search' : 'load'
    recentChatQueryError.value = error?.message || '加载历史产物失败'
    if (error?.response?.status !== 401) {
      console.warn('loadRecentChatQueries failed:', error)
    }
  } finally {
    recentChatQueryLoading.value = false
  }
}

const searchRecentChatQueries = async () => {
  recentChatQueryPage.value = 1
  await loadRecentChatQueries({
    page: 1,
    pageSize: recentChatQueryPageSize.value,
    keyword: recentChatQueryKeyword.value,
    tableName: recentChatQueryTableName.value,
    chartType: recentChatQueryChartType.value,
    riskLevel: recentChatQueryRiskLevel.value,
    executionStatus: recentChatQueryExecutionStatus.value,
    dateRange: recentChatQueryDateRange.value,
    sortDirection: recentChatQuerySortDirection.value
  })
}

const resetRecentChatQuerySearch = async () => {
  recentChatQueryKeyword.value = ''
  recentChatQueryTableName.value = ''
  recentChatQueryChartType.value = ''
  recentChatQueryRiskLevel.value = ''
  recentChatQueryExecutionStatus.value = ''
  recentChatQueryDateRange.value = []
  recentChatQuerySortDirection.value = 'DESC'
  recentChatQueryPage.value = 1
  await loadRecentChatQueries({
    page: 1,
    pageSize: recentChatQueryPageSize.value,
    keyword: '',
    tableName: '',
    chartType: '',
    riskLevel: '',
    executionStatus: '',
    dateRange: [],
    sortDirection: 'DESC'
  })
}

const handleRecentChatPageChange = async (page) => {
  recentChatQueryPage.value = page
  await loadRecentChatQueries({
    page,
    pageSize: recentChatQueryPageSize.value,
    keyword: recentChatQueryKeyword.value,
    tableName: recentChatQueryTableName.value,
    chartType: recentChatQueryChartType.value,
    riskLevel: recentChatQueryRiskLevel.value,
    executionStatus: recentChatQueryExecutionStatus.value,
    dateRange: recentChatQueryDateRange.value,
    sortDirection: recentChatQuerySortDirection.value
  })
}

const handleRecentChatPageSizeChange = async (pageSize) => {
  recentChatQueryPageSize.value = pageSize
  recentChatQueryPage.value = 1
  await loadRecentChatQueries({
    page: 1,
    pageSize,
    keyword: recentChatQueryKeyword.value,
    tableName: recentChatQueryTableName.value,
    chartType: recentChatQueryChartType.value,
    riskLevel: recentChatQueryRiskLevel.value,
    executionStatus: recentChatQueryExecutionStatus.value,
    dateRange: recentChatQueryDateRange.value,
    sortDirection: recentChatQuerySortDirection.value
  })
}

const removeRecentChatQuery = async (entry) => {
  if (!entry?.id) return
  try {
    await ElMessageBox.confirm(
      `确认删除历史记录“${String(entry.question || '未命名查询').slice(0, 24)}”吗？`,
      '删除历史记录',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    await axios.post(`${API_BASE}/api/chat/history/${entry.id}/delete`).then(unwrap)
    ElMessage.success('已删除历史记录')
    await loadRecentChatQueries({
      page: recentChatQueryPage.value,
      pageSize: recentChatQueryPageSize.value,
      keyword: recentChatQueryKeyword.value,
      tableName: recentChatQueryTableName.value,
      chartType: recentChatQueryChartType.value,
      riskLevel: recentChatQueryRiskLevel.value,
      executionStatus: recentChatQueryExecutionStatus.value,
      dateRange: recentChatQueryDateRange.value,
      sortDirection: recentChatQuerySortDirection.value
    })
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '删除历史记录失败')
    }
  }
}

const formatChatHistoryTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

const reuseChatQuestion = async (entry) => {
  if (!entry?.question) return
  if (loading.value || isStreaming.value) {
    ElMessage.warning('当前正在生成，请稍后再试')
    return
  }
  if (entry.conversationId) {
    activeChatSessionId.value = String(entry.conversationId)
  }
  if (entry.turnId) {
    setActiveBranchParent({
      turnId: entry.turnId,
      turnNo: entry.turnNo,
      content: entry.question
    })
  }
  await sendQuestion({
    questionText: entry.question,
    tableName: entry.tableName || selectedTableName.value,
    parentTurnId: entry.turnId || undefined
  })
}

const continueFromChatHistory = async (entry) => {
  if (!entry) return
  if (loading.value || isStreaming.value) {
    ElMessage.warning('当前正在生成，请稍后再试')
    return
  }
  const restored = await openHistoricalAnalysisFromHistory(entry)
  if (!restored) return
  if (entry.conversationId) {
    activeChatSessionId.value = String(entry.conversationId)
  }
  const parentTurnId = String(entry.turnId || entry.assistantTurnId || '').trim()
  activeBranchParentTurnId.value = parentTurnId || null
  activeBranchParentTurnMeta.value = {
    turnId: parentTurnId || entry.id,
    turnNo: entry.turnNo,
    preview: String(entry.question || '').trim().slice(0, 40),
    source: 'history',
    role: 'system',
    tableName: String(entry.tableName || '').trim()
  }
  question.value = ''
}

const draftChatQuestionFromHistory = async (entry) => {
  if (!entry?.question) return
  if (loading.value || isStreaming.value) {
    ElMessage.warning('当前正在生成，请稍后再试')
    return
  }
  if (entry.conversationId) {
    activeChatSessionId.value = String(entry.conversationId)
  }
  if (entry.tableName && isAccessibleTable(entry.tableName)) {
    selectedTableName.value = entry.tableName
  }
  const parentTurnId = String(entry.turnId || entry.assistantTurnId || '').trim()
  if (parentTurnId) {
    activeBranchParentTurnId.value = parentTurnId
    activeBranchParentTurnMeta.value = {
      turnId: parentTurnId,
      turnNo: entry.turnNo,
      preview: String(entry.question || '').trim().slice(0, 40),
      source: 'history',
      role: 'system',
      tableName: String(entry.tableName || '').trim()
    }
  } else {
    clearActiveBranchParent()
  }
  activeModule.value = 'chat'
  question.value = String(entry.question || '').trim()
  ElMessage.success('已带回原问题，可修改后重新生成')
}

const regenerateLastAnalysis = async () => {
  if (loading.value || isStreaming.value) {
    ElMessage.warning('当前正在生成，请稍后再试')
    return
  }
  const sourceQuestion = String(lastAnalysis.value?.sourceQuestion || '').trim()
  const sourceTableName = String(
      lastAnalysis.value?.sourceTableName || lastAnalysis.value?.tableName || selectedTableName.value || ''
  ).trim()
  if (!sourceQuestion) {
    ElMessage.warning('暂无可重新生成的问题，请先完成一次查询')
    return
  }
  if (!sourceTableName) {
    ElMessage.warning('原数据源为空，无法重新生成')
    return
  }
  if (!isAccessibleTable(sourceTableName)) {
    ElMessage.warning('原数据源已不可访问，请先重新选择数据源')
    return
  }
  await sendQuestion({
    questionText: sourceQuestion,
    tableName: sourceTableName,
    regenerate: true
  })
}

const handleAuthenticated = async () => {
  await bootstrapWorkbench()
}

const handleLogout = async () => {
  try {
    await logout()
  } finally {
    clearSession()
    clearLastSelectedTable()
    selectedTableName.value = ''
    tables.value = []
    recentChatQueries.value = []
    recentChatQueryKeyword.value = ''
    recentChatQueryTableName.value = ''
    recentChatQueryChartType.value = ''
    recentChatQueryRiskLevel.value = ''
    recentChatQueryExecutionStatus.value = ''
    recentChatQueryDateRange.value = []
    recentChatQuerySortDirection.value = 'DESC'
    recentChatQueryPage.value = 1
    recentChatQueryPageSize.value = 8
    recentChatQueryTotal.value = 0
    navigationTabs.value = []
    nextTabOrder.value = 1
    activeModule.value = 'workbench'
  }
}

watch(selectedTableName, async (tableName, prevTableName) => {
  saveLastSelectedTable(tableName || '')
  if (!tableName) {
    previewRows.value = []
    fields.value = []
    selectedChatBusinessModelId.value = null
    activeBusinessModelId.value = null
    return
  }
  syncChatBusinessModelSelection()
  if (tableName === prevTableName) return
  await Promise.all([loadPreview(tableName), loadFields(tableName)])
})

watch(activeModule, async (moduleName) => {
  ensureNavigationTab(moduleName)

  if (moduleName === 'chat') {
    await nextTick()
    const instance = ensureChatChartInstance()
    instance?.resize()
    if (lastAnalysis.value?.data?.length) {
      renderChart(lastAnalysis.value.data, lastAnalysis.value?.chartType || currentChartType.value || 'bar')
    } else {
      instance?.clear()
    }
    await loadRecentChatQueries()
  }
  if (moduleName === 'audit') {
    await Promise.all([loadAuditLogs(), loadAuditRules()])
  }
  if (moduleName === 'permission' || moduleName === 'permissionAdmin') {
    await loadPermissionCenter()
  }
  if (moduleName === 'datasource') {
    await loadDatasources()
  }
  if (moduleName === 'diagnosis') {
    await Promise.all([
      loadDiagnosisReports(),
      selectedTableName.value ? loadFields(selectedTableName.value) : Promise.resolve()
    ])
  }
  if (moduleName === 'knowledgeGraph') {
    await Promise.all([loadGraphOverview(), searchGraph(), loadKnowledgeDocs()])
  }
})

const unwrap = (response) => {
  const body = response.data
  if (body.code && body.code !== 200) {
    throw new Error(body.message)
  }
  return body.data ?? body
}

const loadTables = async (options = {}) => {
  const data = unwrap(await axios.get(`${API_BASE}/api/data/tables`))
  tables.value = data
  const exists = (name) => Boolean(name) && data.some(item => item.tableName === name)
  const currentSelection = String(selectedTableName.value || '').trim()
  const storedSelection = String(readLastSelectedTable() || '').trim()
  const preferredSelection = String(options.preferredTableName || '').trim()
  const keepCurrentSelection = Boolean(options.keepCurrentSelection)

  if (exists(preferredSelection)) {
    selectedTableName.value = preferredSelection
    return
  }
  if (keepCurrentSelection && currentSelection) {
    selectedTableName.value = currentSelection
    return
  }
  if (exists(currentSelection)) {
    selectedTableName.value = currentSelection
    return
  }
  if (exists(storedSelection)) {
    selectedTableName.value = storedSelection
    return
  }
  selectedTableName.value = data.length ? data[0].tableName : ''
}

const loadPermissionCenter = async () => {
  const [overviewRes, accessibleRes, requestableRes, myRequestsRes] = await Promise.all([
    axios.get(`${API_BASE}/api/permission/overview`),
    axios.get(`${API_BASE}/api/permission/accessible-tables`),
    axios.get(`${API_BASE}/api/permission/requestable-tables`),
    axios.get(`${API_BASE}/api/permission/my-requests`)
  ])
  permissionOverview.value = unwrap(overviewRes)
  accessibleTables.value = unwrap(accessibleRes)
  requestableTables.value = unwrap(requestableRes)
  myPermissionRequests.value = unwrap(myRequestsRes)
  accessibleOfficialTables.value = unwrap(await axios.get(`${API_BASE}/api/permission/accessible-official-tables`))
  sensitiveFieldPermissions.value = unwrap(await axios.get(`${API_BASE}/api/permission/sensitive-fields`))
  rowPolicyDetails.value = unwrap(await axios.get(`${API_BASE}/api/permission/row-policies`))
  complianceDocument.value = unwrap(await axios.get(`${API_BASE}/api/permission/compliance-document`))
  if (isAdminUser.value) {
    await loadAdminPermissionRequests()
  } else {
    adminPermissionRequests.value = []
  }
}

const loadAdminPermissionRequests = async () => {
  if (!isAdminUser.value) {
    adminPermissionRequests.value = []
    return
  }
  const data = unwrap(await axios.get(`${API_BASE}/api/permission/admin/requests`, {
    params: { status: adminRequestStatus.value || undefined }
  }))
  adminPermissionRequests.value = data
}

const submitPermissionRequest = async () => {
  try {
    const selectedResource = requestableTables.value.find(item => item.tableName === permissionForm.value.tableName)
    await axios.post(`${API_BASE}/api/permission/requests`, {
      tableName: permissionForm.value.tableName,
      resourceType: permissionForm.value.resourceType || selectedResource?.resourceType || selectedResource?.sourceType || 'TABLE',
      permissionType: permissionForm.value.permissionType,
      scopeDesc: permissionForm.value.scopeDesc,
      expireAt: permissionForm.value.expireAt,
      attachmentName: permissionForm.value.attachmentName,
      attachmentContentType: permissionForm.value.attachmentContentType,
      attachmentSize: permissionForm.value.attachmentSize,
      attachmentContent: permissionForm.value.attachmentContent,
      attachmentNote: permissionForm.value.attachmentNote,
      reason: permissionForm.value.reason
    }).then(unwrap)
    ElMessage.success('权限申请已提交')
    permissionForm.value = emptyPermissionForm()
    await loadPermissionCenter()
  } catch (error) {
    ElMessage.error(error.message || '提交失败')
  }
}

const handlePermissionResourceChange = (tableName) => {
  const selectedResource = requestableTables.value.find(item => item.tableName === tableName)
  permissionForm.value.resourceType = selectedResource?.resourceType || selectedResource?.sourceType || 'TABLE'
  permissionForm.value.permissionType = selectedResource?.suggestedPermissionType || permissionForm.value.permissionType || 'READ'
}

const handlePermissionAttachmentChange = async (file) => {
  const rawFile = file?.raw || file
  permissionForm.value.attachmentName = rawFile?.name || file?.name || ''
  permissionForm.value.attachmentContentType = rawFile?.type || ''
  permissionForm.value.attachmentSize = rawFile?.size || 0
  permissionForm.value.attachmentContent = ''
  if (!rawFile) return
  if (rawFile.size > 2 * 1024 * 1024) {
    ElMessage.warning('附件超过 2MB，仅保存文件名与说明')
    return
  }
  permissionForm.value.attachmentContent = await new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(rawFile)
  })
}

const prefillPermissionRequest = async (row) => {
  permissionForm.value = {
    tableName: row.tableName || '',
    resourceType: row.resourceType || 'TABLE',
    permissionType: row.permissionType || 'READ',
    scopeDesc: row.scopeDesc || '',
    expireAt: row.expireAt ? String(row.expireAt).slice(0, 10) : '',
    attachmentName: row.attachmentName || '',
    attachmentContentType: row.attachmentContentType || '',
    attachmentSize: row.attachmentSize || 0,
    attachmentContent: '',
    attachmentNote: row.attachmentNote || '',
    reason: row.reason || ''
  }
  activeModule.value = 'permission'
  await nextTick()
  document.querySelector('.permission-request-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  ElMessage.info('已带入原申请内容，可调整后重新提交')
}

const reviewPermission = async (requestId, action) => {
  try {
    await axios.post(`${API_BASE}/api/permission/admin/requests/${requestId}/review`, {
      action,
      comment: action === 'APPROVED' ? '同意本次数据分析需求' : '申请理由不足，请补充说明'
    }).then(unwrap)
    ElMessage.success(action === 'APPROVED' ? '已通过申请' : '已驳回申请')
    await Promise.all([loadPermissionCenter(), loadTables()])
  } catch (error) {
    ElMessage.error(error.message || '审批失败')
  }
}

const permissionStatusType = (status) => {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PENDING') return 'warning'
  return 'info'
}

const permissionStatusText = (status) => {
  if (status === 'APPROVED') return '已通过'
  if (status === 'REJECTED') return '已驳回'
  if (status === 'PENDING') return '待审批'
  return status
}

const fillCurrentDatasource = () => {
  datasourceForm.value = {
    name: '当前项目库',
    dbType: 'MYSQL',
    host: 'localhost',
    port: 3306,
    databaseName: 'insight_spark',
    username: 'root',
    password: '',
    poolMaxSize: 10,
    poolTimeoutMs: 30000,
    kgSyncRule: '同步表、字段、业务含义、同义词、敏感标识与联邦关系'
  }
}

const loadDatasources = async () => {
  officialDatasources.value = unwrap(await axios.get(`${API_BASE}/api/datasources`))

  for (const ds of officialDatasources.value) {
    try {
      await loadDatasourceHealth(ds.id)
    } catch (e) {
      datasourceHealthMap.value[ds.id] = { status: 'UNKNOWN', message: e.message }
    }
  }
}

const createDatasource = async () => {
  try {
    await axios.post(`${API_BASE}/api/datasources`, datasourceForm.value).then(unwrap)
    ElMessage.success('数据源已保存')
    await loadDatasources()
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  }
}

const selectDatasource = async (row) => {
  selectedDatasourceId.value = row.id
  schemaFields.value = []
  datasourcePermissionForm.value.tableName = '*'
  await Promise.all([loadSchemaTables(row.id), loadDatasourcePermissions(row.id), loadFederalRelations(row.id)])
}

const testDatasource = async (datasourceId) => {
  const result = unwrap(await axios.post(`${API_BASE}/api/datasources/${datasourceId}/test`))
  if (result.status === 'SUCCESS') {
    ElMessage.success(result.message)
  } else {
    ElMessage.error(result.message)
  }
  await loadDatasources()
}

const syncDatasourceSchema = async (datasourceId) => {
  try {
    const result = unwrap(await axios.post(`${API_BASE}/api/datasources/${datasourceId}/sync-schema`))
    ElMessage.success(`解析完成：${result.tableCount} 张表，${result.fieldCount} 个字段，${result.relationCount ?? 0} 条关系`)
    selectedDatasourceId.value = datasourceId
    await Promise.all([loadDatasources(), loadSchemaTables(datasourceId)])
  } catch (error) {
    ElMessage.error(error.message || 'Schema 解析失败')
  }
}

const toggleDatasource = async (row) => {
  const nextStatus = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await axios.post(`${API_BASE}/api/datasources/${row.id}/status`, { status: nextStatus }).then(unwrap)
  ElMessage.success(nextStatus === 'ENABLED' ? '数据源已启用' : '数据源已禁用')
  await loadDatasources()
}

const updateDatasource = async (row) => {
  await axios.post(`${API_BASE}/api/datasources/${row.id}`, row).then(unwrap)
  ElMessage.success('数据源配置已更新')
  await loadDatasources()
}

const deleteDatasource = async (row) => {
  await axios.post(`${API_BASE}/api/datasources/${row.id}/delete`).then(unwrap)
  ElMessage.success('数据源已删除')
  await loadDatasources()
}

const loadDatasourcePermissions = async (datasourceId = selectedDatasourceId.value) => {
  if (!datasourceId) return
  datasourcePermissions.value = unwrap(await axios.get(`${API_BASE}/api/datasources/${datasourceId}/permissions`))
}

const grantDatasourcePermission = async () => {
  if (!selectedDatasourceId.value) return ElMessage.warning('请先选择数据源')
  if (!datasourcePermissionForm.value.tableName) return ElMessage.warning('请选择要授权的官方表或整个数据源')
  await axios.post(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/permissions`, datasourcePermissionForm.value).then(unwrap)
  ElMessage.success('数据源授权已保存')
  await loadDatasourcePermissions()
}

const revokeDatasourcePermission = async (permissionId) => {
  await axios.post(`${API_BASE}/api/datasources/permissions/${permissionId}/revoke`).then(unwrap)
  ElMessage.success('授权已回收')
  await loadDatasourcePermissions()
}

const loadFederalRelations = async (datasourceId = selectedDatasourceId.value) => {
  if (!datasourceId) return
  federalRelations.value = unwrap(await axios.get(`${API_BASE}/api/datasources/${datasourceId}/federal-relations`))
}

const saveFederalRelation = async () => {
  if (!selectedDatasourceId.value) return ElMessage.warning('请先选择数据源')
  await axios.post(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/federal-relations`, federalForm.value).then(unwrap)
  ElMessage.success('联邦关联已保存')
  await loadFederalRelations()
}

const validateFederalRelation = async () => {
  if (!selectedDatasourceId.value) return ElMessage.warning('请先选择数据源')
  const result = unwrap(await axios.post(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/federal-relations/validate`, federalForm.value))
  ElMessage.success(result.message || '联邦关联校验通过')
}

const deleteFederalRelation = async (relationId) => {
  await axios.post(`${API_BASE}/api/datasources/federal-relations/${relationId}/delete`).then(unwrap)
  ElMessage.success('联邦关联已删除')
  await loadFederalRelations()
}

const loadSchemaTables = async (datasourceId) => {
  schemaTables.value = unwrap(await axios.get(`${API_BASE}/api/datasources/${datasourceId}/schema/tables`))
}

const selectSchemaTable = async (row) => {
  if (!selectedDatasourceId.value) return
  schemaFields.value = unwrap(await axios.get(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/schema/tables/${row.tableName}/fields`))
}

const updateSchemaField = async (row) => {
  try {
    await axios.post(`${API_BASE}/api/datasources/schema/fields/${row.id}`, {
      businessName: row.businessName,
      businessDesc: row.businessDesc,
      synonyms: row.synonyms,
      kgSyncEnabled: row.kgSyncEnabled !== false,
      kgSyncRule: row.kgSyncRule,
      sensitive: Boolean(row.sensitive)
    }).then(unwrap)
  } catch (error) {
    ElMessage.error(error.message || '字段配置保存失败')
  }
}

const updateUploadField = async (row) => {
  console.log('updateUploadField called, selectedTableName:', selectedTableName.value)
  if (!selectedTableName.value) {
    ElMessage.warning('请先在"我的数据表"中选择要编辑的数据表')
    return
  }
  if (isOfficialTableName(selectedTableName.value)) {
    ElMessage.warning('官方库字段请在数据源管理中维护')
    return
  }
  try {
    await axios.post(`${API_BASE}/api/data/tables/${selectedTableName.value}/fields/${row.columnName}`, {
      displayName: row.displayName,
      fieldType: row.fieldType,
      fieldComment: row.fieldComment,
      synonyms: row.synonyms,
      sensitive: Boolean(row.sensitive),
      kgSyncEnabled: row.kgSyncEnabled !== false,
      kgSyncRule: row.kgSyncRule
    }).then(unwrap)
    ElMessage.success('字段信息已更新')
    await loadFields(selectedTableName.value)
    await loadPreview(selectedTableName.value)
  } catch (error) {
    console.error('updateUploadField error:', error)
    ElMessage.error(error.response?.data?.message || error.message || '字段信息保存失败')
  }
}

const runDiagnosis = async () => {
  if (!selectedTableName.value) {
    ElMessage.warning('请先选择诊断数据表')
    return
  }
  if (!diagnosisForm.value.metricField) {
    ElMessage.warning('请选择指标字段')
    return
  }

  diagnosisLoading.value = true
  startDiagnosisProgressV2()
  try {
    currentDiagnosis.value = unwrap(await axios.post(`${API_BASE}/api/diagnosis/run`, {
      tableName: selectedTableName.value,
      metricField: diagnosisForm.value.metricField,
      dimensionFields: diagnosisForm.value.dimensionFields,
      timeField: diagnosisForm.value.timeField || null,
      sourceRoute: 'diagnosis',
      detailLevel: diagnosisForm.value.detailLevel || 'detailed',
      anomalyType: diagnosisForm.value.anomalyType || 'fluctuation'
    }))
    completeDiagnosisProgressV2(
      currentDiagnosis.value?.reasoningLogs || [],
      currentDiagnosis.value?.graphRagRuntime,
      currentDiagnosis.value
    )
    notifyDiagnosisResult(currentDiagnosis.value)
    await loadDiagnosisReports()
  } catch (error) {
    ElMessage.error(error.message || '诊断失败')
  } finally {
    diagnosisLoading.value = false
  }
}
const diagnoseFromLastAnalysis = async () => {
  if (!lastAnalysis.value) {
    ElMessage.warning('请先完成一次对话查询，再生成诊断报告')
    return
  }

  if (!selectedTableName.value) {
    ElMessage.warning('请先选择数据表')
    return
  }

  if (!fields.value.length) {
    await loadFields(selectedTableName.value)
  }

  const metricDisplayName = lastAnalysis.value.fieldMapping?.metric
  const dimensionDisplayName = lastAnalysis.value.fieldMapping?.dimension

  const metricField = findAnalysisField(metricDisplayName, 'NUMBER')

  const dimensionField = findAnalysisField(dimensionDisplayName, null)

  const timeField = fields.value.find(item => item.fieldType === 'DATE')
  if (metricField && !dimensionField && dimensionCandidateFields.value.length) {
    openDiagnosisPicker({
      metricField: metricField.columnName,
      dimensionFields: [],
      timeField: timeField ? timeField.columnName : '',
      context: { source: 'chat' }
    })
    return
  }

  if (!metricField && numericFields.value.length) {
    openDiagnosisPicker({
      metricField: numericFields.value[0]?.columnName || '',
      dimensionFields: dimensionField ? [dimensionField.columnName] : [],
      timeField: timeField ? timeField.columnName : '',
      context: { source: 'chat' }
    })
    return
  }

  if (!metricField) {
    ElMessage.warning('当前数据表没有可用于诊断的数值字段，请先在字段语义中把指标字段设置为 NUMBER')
    return
  }

  openDiagnosisPicker({
    metricField: metricField.columnName,
    dimensionFields: dimensionField ? [dimensionField.columnName] : [],
    timeField: timeField ? timeField.columnName : '',
    context: { source: 'chat' }
  })
}
const confirmDiagnosisPicker = async () => {
  if (!diagnosisPickerForm.value.metricField) {
    ElMessage.warning('请选择指标字段')
    return
  }
  diagnosisPickerVisible.value = false
  diagnosisForm.value.detailLevel = diagnosisPickerForm.value.detailLevel || 'detailed'
  diagnosisForm.value.anomalyType = diagnosisPickerForm.value.anomalyType || 'fluctuation'
  const metricField = fields.value.find(item => item.columnName === diagnosisPickerForm.value.metricField)
  const timeField = fields.value.find(item => item.columnName === diagnosisPickerForm.value.timeField)
  if (diagnosisEntryContext.value?.source === 'dashboard') {
    await runDashboardDiagnosisWithPicker(diagnosisEntryContext.value, metricField, timeField)
    diagnosisEntryContext.value = null
    return
  }
  diagnosisLoading.value = true
  startDiagnosisProgressV2()
  try {
    const chartSnapshot = captureChartSnapshot(lastAnalysis.value)
    currentDiagnosis.value = unwrap(await axios.post(`${API_BASE}/api/diagnosis/run`, {
      tableName: selectedTableName.value,
      metricField: diagnosisPickerForm.value.metricField,
      dimensionFields: diagnosisPickerForm.value.dimensionFields,
      timeField: diagnosisPickerForm.value.timeField || null,
      conversationId: activeChatSessionId.value || undefined,
      question: lastAnalysis.value?.sourceQuestion || lastAnalysis.value?.message || '基于当前对话查询结果生成智能诊断报告',
      sourceQuestion: lastAnalysis.value?.sourceQuestion || '',
      sourceSql: lastAnalysis.value?.sql || lastAnalysis.value?.sourceSql || '',
      chartType: lastAnalysis.value?.chartType,
      chartSnapshot: {
        ...chartSnapshot,
        fieldMapping: {
          metric: metricField?.displayName || diagnosisPickerForm.value.metricField,
          dimension: diagnosisPickerForm.value.dimensionFields.map(column => fieldLabel(column)).join('、')
        }
      },
      detailLevel: diagnosisPickerForm.value.detailLevel || 'detailed',
      anomalyType: diagnosisPickerForm.value.anomalyType || 'fluctuation'
    }))
    completeDiagnosisProgressV2(
      currentDiagnosis.value?.reasoningLogs || [],
      currentDiagnosis.value?.graphRagRuntime,
      currentDiagnosis.value
    )
    notifyDiagnosisResult(currentDiagnosis.value)
    await loadDiagnosisReports()
    activeModule.value = 'diagnosis'
  } catch (error) {
    ElMessage.error(error.message || '诊断报告生成失败')
  } finally {
    diagnosisLoading.value = false
    diagnosisEntryContext.value = null
  }
}

const startDiagnosisProgress = () => {
  diagnosisProgress.value = {
    percentage: 10,
    step: '任务创建',
    logs: [{ step: 1, title: '任务创建', detail: '已接收诊断请求，准备扫描异常数据。' }]
  }
  setTimeout(() => {
    if (diagnosisLoading.value) {
      diagnosisProgress.value = {
        percentage: 35,
        step: '文档扫描',
        logs: [...diagnosisProgress.value.logs, { step: 2, title: '知识证据检查', detail: '正在检查当前用户可用的知识文档、行业研报与历史证据。' }]
      }
    }
  }, 350)
  setTimeout(() => {
    if (diagnosisLoading.value) {
      diagnosisProgress.value = {
        percentage: 70,
        step: '多跳推理',
        logs: [...diagnosisProgress.value.logs, { step: 3, title: '多跳推理', detail: '正在通过 Neo4j 图谱扩展表、字段、报告和文档关系。' }]
      }
    }
  }, 900)
}

const completeDiagnosisProgress = (logs = []) => {
  diagnosisProgress.value = {
    percentage: 100,
    step: '报告生成',
    logs: logs.length ? logs : [...diagnosisProgress.value.logs, { step: 4, title: '报告生成', detail: '诊断报告已生成并写入 Neo4j。' }]
  }
}

const startDiagnosisProgressV2 = (context = {}) => {
  const sourceText = context?.source === 'dashboard' ? '看板图表' : '对话查询/数据表'
  const titleText = context?.title ? `：${context.title}` : ''
  diagnosisProgress.value = {
    percentage: 10,
    step: '任务创建',
    logs: [{ step: 1, title: '任务创建', status: 'running', detail: `已接收${sourceText}${titleText}的诊断请求，准备扫描异常数据。` }]
  }
  setTimeout(() => {
    if (diagnosisLoading.value) {
      diagnosisProgress.value = {
        percentage: 35,
        step: '知识证据检查',
        logs: [...diagnosisProgress.value.logs, { step: 2, title: '知识证据检查', status: 'running', detail: '正在检查当前用户可用的知识文档、行业研报与历史诊断证据。' }]
      }
    }
  }, 350)
  setTimeout(() => {
    if (diagnosisLoading.value) {
      diagnosisProgress.value = {
        percentage: 70,
        step: '多跳推理',
        logs: [...diagnosisProgress.value.logs, { step: 3, title: '多跳推理', status: 'running', detail: '正在通过 Neo4j 图谱扩展表、字段、报告和文档关系。' }]
      }
    }
  }, 900)
}

const completeDiagnosisProgressV2 = (logs = [], runtime = null, diagnosis = null) => {
  const persisted = isDiagnosisPersisted(diagnosis)
  const finalStepDetail = persisted
    ? '诊断报告已生成并写入 Neo4j。'
    : '诊断结果已生成（降级模式，未写入 Neo4j）。'
  const runtimeLog = runtime ? [{
    step: 6,
    title: 'GraphRAG 状态核验',
    status: runtime.mode === 'PYTHON_GRAPHRAG' ? 'completed' : 'fallback',
    detail: `推理模式：${runtime.mode || 'UNKNOWN'}，命中图谱节点 ${runtime.graphNodeCount || 0} 个、关系 ${runtime.graphEdgeCount || 0} 条、文档证据 ${runtime.docEvidenceCount || 0} 条。`
  }] : []
  const persistenceLog = [{
    step: 7,
    title: '报告持久化',
    status: persisted ? 'completed' : 'fallback',
    detail: persisted
      ? '诊断报告已写入 Neo4j，可在历史报告中查看与导出。'
      : `诊断结果已返回，但 Neo4j 持久化降级。${resolveDiagnosisFallbackReason(diagnosis)}`
  }]
  diagnosisProgress.value = {
    percentage: 100,
    step: '报告生成',
    logs: logs.length
      ? [...logs, ...runtimeLog, ...persistenceLog]
      : [...diagnosisProgress.value.logs, { step: 4, title: '报告生成', status: 'completed', detail: finalStepDetail }, ...runtimeLog, ...persistenceLog]
  }
}

const isDiagnosisPersisted = (diagnosis) => {
  if (!diagnosis || typeof diagnosis !== 'object') return true
  if (typeof diagnosis.reportPersisted === 'boolean') return diagnosis.reportPersisted
  const persisted = diagnosis.reportPersistence?.persisted
  if (typeof persisted === 'boolean') return persisted
  return true
}

const resolveDiagnosisFallbackReason = (diagnosis) => {
  const direct = String(diagnosis?.reportFallbackReason || '').trim()
  if (direct) return `降级原因：${direct}`
  const persistenceError = String(diagnosis?.reportPersistence?.error || '').trim()
  if (persistenceError) return `降级原因：${persistenceError}`
  return '降级原因：Neo4j 报告写入不可用。'
}

const notifyDiagnosisResult = (
  diagnosis,
  successText = '诊断报告已生成并写入 Neo4j',
  fallbackText = '诊断结果已生成（降级模式，未写入 Neo4j）'
) => {
  if (isDiagnosisPersisted(diagnosis)) {
    ElMessage.success(successText)
    return
  }
  ElMessage.warning(`${fallbackText}。${resolveDiagnosisFallbackReason(diagnosis)}`)
}

const openDiagnosisPicker = (payload = {}) => {
  diagnosisEntryContext.value = payload.context || null
  diagnosisPickerForm.value = {
    metricField: payload.metricField || '',
    dimensionFields: payload.dimensionFields || [],
    timeField: payload.timeField || '',
    detailLevel: payload.detailLevel || diagnosisForm.value.detailLevel || 'detailed',
    anomalyType: payload.anomalyType || diagnosisForm.value.anomalyType || 'fluctuation'
  }
  diagnosisPickerVisible.value = true
}

const captureChartSnapshot = (analysis) => {
  const instance = ensureChatChartInstance()
  return {
  chartType: analysis?.chartType || currentChartType.value,
  fieldMapping: analysis?.fieldMapping || {},
  ...buildChartStyleSnapshot(analysis),
  data: analysis?.data || [],
  generatedSql: analysis?.sql || analysis?.sourceSql || '',
  sourceQuestion: analysis?.sourceQuestion || '',
  sourceRoute: 'chat',
  sourceRecordCount: analysis?.data?.length || 0,
  capturedAt: new Date().toISOString(),
  imageDataUrl: instance?.getDataURL
    ? instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
    : ''
  }
}

const parseDashboardCardSnapshot = (card) => {
  const raw = card?.chartSnapshot || card?.payloadRow?.chartSnapshot
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(String(raw))
  } catch {
    return {}
  }
}

const resolveFieldByAnyName = (names = [], fieldType = '') => {
  const wanted = names.map(item => String(item || '').trim()).filter(Boolean)
  if (!wanted.length) return null
  return fields.value.find(field => {
    if (fieldType && field.fieldType !== fieldType) return false
    const localNames = [field.columnName, field.displayName, field.sourceFieldName]
      .map(item => String(item || '').trim())
      .filter(Boolean)
    return wanted.some(name => localNames.includes(name))
  }) || null
}

const runDashboardDiagnosisWithPicker = async (context, metricField, timeField) => {
  const card = context.card || {}
  const snapshot = context.snapshot || {}
  const tableName = context.tableName
  const sourceData = context.sourceData || []
  const fieldMapping = context.fieldMapping || {}
  const dimensionField = fields.value.find(item => item.columnName === diagnosisPickerForm.value.dimensionFields?.[0])

  diagnosisLoading.value = true
  startDiagnosisProgressV2({
    source: 'dashboard',
    title: card?.title || '看板图表诊断'
  })
  try {
    currentDiagnosis.value = unwrap(await axios.post(`${API_BASE}/api/diagnosis/run`, {
      tableName,
      metricField: diagnosisPickerForm.value.metricField,
      dimensionFields: diagnosisPickerForm.value.dimensionFields || [],
      timeField: diagnosisPickerForm.value.timeField || null,
      sourceQuestion: card?.title || snapshot?.sourceQuestion || '',
      sourceSql: card?.sql || snapshot?.sql || snapshot?.generatedSql || '',
      chartType: card?.chartType || snapshot?.chartType || 'bar',
      chartSnapshot: {
        ...snapshot,
        title: card?.title || snapshot?.title || '看板图表诊断',
        chartType: card?.chartType || snapshot?.chartType || 'bar',
        tableName,
        data: sourceData,
        generatedSql: card?.sql || snapshot?.sql || snapshot?.generatedSql || '',
        sourceQuestion: card?.title || snapshot?.sourceQuestion || '',
        imageDataUrl: context.imageDataUrl || snapshot?.imageDataUrl || '',
        sourceRoute: 'dashboard',
        dashboardId: card?.dashboardId || null,
        dashboardName: card?.dashboardName || '',
        cardId: card?.cardId || card?._renderKey || '',
        cardTitle: card?.title || '',
        sourceRecordCount: sourceData.length,
        capturedAt: new Date().toISOString(),
        fieldMapping: {
          ...fieldMapping,
          metric: metricField?.displayName || diagnosisPickerForm.value.metricField,
          dimension: (diagnosisPickerForm.value.dimensionFields || []).map(column => fieldLabel(column)).join('、')
        }
      },
      sourceRoute: 'dashboard',
      dashboardId: card?.dashboardId || null,
      dashboardName: card?.dashboardName || '',
      cardId: card?.cardId || card?._renderKey || '',
      cardTitle: card?.title || '',
      detailLevel: diagnosisPickerForm.value.detailLevel || 'detailed',
      anomalyType: diagnosisPickerForm.value.anomalyType || 'fluctuation',
      question: `基于看板图表「${card?.title || '未命名图表'}」生成智能诊断报告`
    }))
    completeDiagnosisProgressV2(
      currentDiagnosis.value?.reasoningLogs || [],
      currentDiagnosis.value?.graphRagRuntime,
      currentDiagnosis.value
    )
    notifyDiagnosisResult(
      currentDiagnosis.value,
      '已根据看板图表生成智能诊断报告并写入 Neo4j',
      '已根据看板图表生成智能诊断结果（降级模式，未写入 Neo4j）'
    )
    await loadDiagnosisReports()
    activeModule.value = 'diagnosis'
  } catch (error) {
    ElMessage.error(error.message || '看板诊断报告生成失败')
  } finally {
    diagnosisLoading.value = false
  }
}

const diagnoseFromDashboardCard = async (card, imageDataUrl = '') => {
  const snapshot = parseDashboardCardSnapshot(card)
  const tableName = String(card?.tableName || snapshot?.tableName || card?.payloadRow?.queryTableName || '').trim()
  if (!tableName) {
    ElMessage.warning('当前看板图表没有绑定数据表，无法生成诊断报告')
    return
  }
  selectedTableName.value = tableName
  await loadFields(tableName)

  const encode = snapshot?.encode || {}
  const fieldMapping = card?.fieldMapping || snapshot?.fieldMapping || {}
  const metricField = resolveFieldByAnyName([
    card?.metricField,
    fieldMapping.metric,
    fieldMapping.value,
    encode.y,
    encode.value
  ], 'NUMBER') || numericFields.value[0]
  if (!metricField) {
    ElMessage.warning('当前看板图表没有可用于诊断的数值字段')
    return
  }

  const dimensionField = resolveFieldByAnyName([
    card?.dimensionField,
    fieldMapping.dimension,
    fieldMapping.name,
    encode.x,
    encode.itemName
  ])
  const timeField = dateFields.value[0]
  const sourceData = Array.isArray(card?.data) && card.data.length
    ? card.data
    : Array.isArray(snapshot?.data) ? snapshot.data : []
  openDiagnosisPicker({
    metricField: metricField.columnName,
    dimensionFields: dimensionField ? [dimensionField.columnName] : [],
    timeField: timeField ? timeField.columnName : '',
    context: { source: 'dashboard', card, snapshot, tableName, fieldMapping, sourceData, imageDataUrl }
  })
}

const findAnalysisField = (displayName, fieldType) => {
  const sql = (lastAnalysis.value?.sql || '').toLowerCase()
  return fields.value.find(item => {
    if (fieldType && item.fieldType !== fieldType) return false
    const names = [item.displayName, item.sourceFieldName, item.columnName].filter(Boolean)
    return names.some(name => name === displayName) || sql.includes(`\`${item.columnName.toLowerCase()}\``)
  }) || null
}

const loadDiagnosisReports = async () => {
  diagnosisReports.value = unwrap(await axios.get(`${API_BASE}/api/diagnosis/reports`))
}

const loadDiagnosisReportDetail = async (row) => {
  const detail = unwrap(await axios.get(`${API_BASE}/api/diagnosis/reports/${row.id}`))
  if (detail.resultJson) {
    const parsed = typeof detail.resultJson === 'string' ? JSON.parse(detail.resultJson) : detail.resultJson
    currentDiagnosis.value = {
      ...parsed,
      id: detail.id,
      tableName: detail.tableName,
      metricField: detail.metricField,
      metricFieldLabel: detail.metricFieldLabel || parsed.metricFieldLabel,
      dimensionFields: detail.dimensionFields,
      dimensionFieldLabels: detail.dimensionFieldLabels || parsed.dimensionFieldLabels,
      timeField: detail.timeField,
      timeFieldLabel: detail.timeFieldLabel || parsed.timeFieldLabel,
      sourceQuestion: detail.sourceQuestion,
      sourceSql: detail.sourceSql,
      bindingJson: detail.bindingJson,
      chartSnapshot: parseMaybeJson(parsed.chartSnapshot || detail.chartSnapshot)
    }
  } else {
    currentDiagnosis.value = { ...detail, chartSnapshot: parseMaybeJson(detail.chartSnapshot) }
  }
}

const deleteDiagnosisReports = async (ids = []) => {
  const normalizedIds = (Array.isArray(ids) ? ids : [ids])
    .map(id => Number(id))
    .filter(id => Number.isFinite(id) && id > 0)
  const uniqueIds = [...new Set(normalizedIds)]
  if (!uniqueIds.length) return
  await axios.post(`${API_BASE}/api/diagnosis/reports/delete`, { ids: uniqueIds }).then(unwrap)
  if (currentDiagnosis.value?.id && uniqueIds.includes(Number(currentDiagnosis.value.id))) {
    currentDiagnosis.value = null
  }
  ElMessage.success(uniqueIds.length > 1 ? `已删除 ${uniqueIds.length} 份诊断报告` : '诊断报告已删除')
  await loadDiagnosisReports()
}

const parseMaybeJson = (value) => {
  if (!value || typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

const restoreDiagnosisBinding = async (report) => {
  const snapshot = parseMaybeJson(report?.chartSnapshot)
  const binding = parseMaybeJson(report?.bindingJson)
  const source = snapshot || binding?.chartSnapshot
  const route = String(binding?.route || snapshot?.sourceRoute || '').toLowerCase()
  if (report?.tableName) {
    selectedTableName.value = report.tableName
  }
  if (route === 'dashboard') {
    diagnosisRestoreTarget.value = {
      route: 'dashboard',
      reportId: report?.id,
      tableName: report?.tableName,
      dashboardId: binding?.dashboardId || snapshot?.dashboardId || null,
      dashboardName: binding?.dashboardName || snapshot?.dashboardName || '',
      cardId: binding?.cardId || snapshot?.cardId || '',
      cardTitle: binding?.cardTitle || snapshot?.cardTitle || snapshot?.title || ''
    }
    activeModule.value = 'dashboard'
    return
  }
  if (route !== 'chat') {
    activeModule.value = 'diagnosis'
    return
  }
  activeModule.value = 'chat'
  await nextTick()
  ensureChatChartInstance()
  if (source?.data?.length) {
    lastAnalysis.value = {
      tableName: report?.tableName || selectedTableName.value,
      chartType: source.chartType || 'bar',
      data: source.data,
      fieldMapping: source.fieldMapping || {},
      sql: source.generatedSql || report?.sourceSql || '',
      sourceSql: source.generatedSql || report?.sourceSql || '',
      sourceQuestion: source.sourceQuestion || report?.sourceQuestion || ''
    }
    renderChart(source.data, source.chartType || 'bar')
    messages.value.push({ role: 'system', content: `已回溯诊断报告《${report?.title || report?.id}》绑定的原始图表。`, sql: lastAnalysis.value.sql })
  }
}

const regenerateDiagnosisReport = async (report, overrides = {}) => {
  const detail = report?.resultJson ? report : unwrap(await axios.get(`${API_BASE}/api/diagnosis/reports/${report.id}`))
  const parsed = detail.resultJson ? parseMaybeJson(detail.resultJson) : detail
  const snapshot = parseMaybeJson(parsed?.chartSnapshot || detail?.chartSnapshot)
  const binding = parseMaybeJson(detail?.bindingJson || parsed?.bindingJson)
  const tableName = detail?.tableName || parsed?.tableName || binding?.tableName
  const metricField = detail?.metricField || parsed?.metricField
  if (!tableName || !metricField) {
    ElMessage.warning('历史报告缺少表名或指标字段，无法重新生成')
    return
  }
  selectedTableName.value = tableName
  diagnosisLoading.value = true
  startDiagnosisProgressV2({
    source: binding?.route === 'dashboard' || snapshot?.sourceRoute === 'dashboard' ? 'dashboard' : 'chat',
    title: detail?.title || parsed?.title || '历史报告重新生成'
  })
  try {
    currentDiagnosis.value = unwrap(await axios.post(`${API_BASE}/api/diagnosis/run`, {
      tableName,
      metricField,
      dimensionFields: parseMaybeJson(detail?.dimensionFields || parsed?.dimensionFields) || [],
      timeField: detail?.timeField || parsed?.timeField || null,
      sourceQuestion: detail?.sourceQuestion || parsed?.sourceQuestion || binding?.sourceQuestion || '',
      sourceSql: detail?.sourceSql || parsed?.sourceSql || binding?.sourceSql || '',
      chartType: snapshot?.chartType || binding?.chartType || 'bar',
      chartSnapshot: snapshot || binding?.chartSnapshot || null,
      sourceRoute: binding?.route || snapshot?.sourceRoute || 'chat',
      dashboardId: binding?.dashboardId || snapshot?.dashboardId || null,
      dashboardName: binding?.dashboardName || snapshot?.dashboardName || '',
      cardId: binding?.cardId || snapshot?.cardId || '',
      cardTitle: binding?.cardTitle || snapshot?.cardTitle || '',
      detailLevel: overrides.detailLevel || detail?.detailLevel || parsed?.detailLevel || 'detailed',
      anomalyType: overrides.anomalyType || detail?.anomalyType || parsed?.anomalyType || 'fluctuation',
      question: detail?.sourceQuestion || parsed?.sourceQuestion || `重新生成诊断报告：${detail?.title || parsed?.title || tableName}`
    }))
    completeDiagnosisProgressV2(
      currentDiagnosis.value?.reasoningLogs || [],
      currentDiagnosis.value?.graphRagRuntime,
      currentDiagnosis.value
    )
    notifyDiagnosisResult(currentDiagnosis.value, '历史诊断报告已重新生成并写入 Neo4j')
    await loadDiagnosisReports()
  } catch (error) {
    ElMessage.error(error.message || '重新生成报告失败')
  } finally {
    diagnosisLoading.value = false
  }
}

const exportDiagnosisReport = (format, options = {}) => {
  if (!currentDiagnosis.value?.id) {
    ElMessage.warning('请先生成或选择一份诊断报告')
    return
  }
  const params = new URLSearchParams({
    format,
    includeSnapshots: String(options.includeSnapshots ?? true),
    includeReasoningLogs: String(options.includeReasoningLogs ?? true),
    enablePdfEncryption: String(format === 'pdf' && Boolean(options.enablePdfEncryption))
  })
  axios.get(`${API_BASE}/api/diagnosis/reports/${currentDiagnosis.value.id}/export?${params.toString()}`, {
    responseType: 'blob'
  }).then((response) => {
    const blob = new Blob([response.data], { type: response.headers['content-type'] || 'application/octet-stream' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = resolveDownloadFilename(response.headers['content-disposition'], format)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  }).catch((error) => {
    ElMessage.error(error.message || '报告导出失败')
  })
}

const resolveDownloadFilename = (contentDisposition, format) => {
  const fallback = `智能诊断报告.${format === 'word' ? 'docx' : format}`
  if (!contentDisposition) return fallback
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) return decodeURIComponent(utf8Match[1])
  const asciiMatch = contentDisposition.match(/filename="?([^"]+)"?/i)
  return asciiMatch?.[1] || fallback
}

const isPhysicalColumnCode = (value) => /^col_\d+$/i.test(String(value || '').trim())

const fieldLabel = (columnName) => {
  const rawValue = String(columnName || '').trim()
  const field = fields.value.find(item => (
    String(item?.columnName || '').trim() === rawValue
      || String(item?.sourceFieldName || '').trim() === rawValue
      || String(item?.displayName || '').trim() === rawValue
  ))
  if (!field) {
    return rawValue
  }
  const displayName = String(field?.displayName || '').trim()
  const sourceFieldName = String(field?.sourceFieldName || '').trim()
  if (displayName && !isPhysicalColumnCode(displayName)) {
    if (sourceFieldName && sourceFieldName !== displayName && !isPhysicalColumnCode(sourceFieldName)) {
      return `${displayName}（${sourceFieldName}）`
    }
    return displayName
  }
  if (sourceFieldName) {
    return sourceFieldName
  }
  return rawValue
}

const loadPreview = async (tableName) => {
  const page = unwrap(await axios.get(`${API_BASE}/api/data/tables/${tableName}/preview-page`, {
    params: { page: previewPage.value, pageSize: previewPageSize.value }
  }))
  if (selectedTableName.value !== tableName) return
  previewRows.value = page.rows || []
  previewTotal.value = page.total || 0
  loadedPreviewTableName.value = tableName
}

const loadFields = async (tableName) => {
  const result = unwrap(await axios.get(`${API_BASE}/api/data/tables/${tableName}/fields`))
  if (selectedTableName.value !== tableName) return
  fields.value = result
  loadedFieldsTableName.value = tableName
}

const selectTable = (row) => {
  previewPage.value = 1
  selectedTableName.value = row.tableName
}

const handlePreviewPageChange = async (page) => {
  previewPage.value = page
  if (selectedTableName.value) await loadPreview(selectedTableName.value)
}

const handlePreviewSizeChange = async (size) => {
  previewPageSize.value = size
  previewPage.value = 1
  if (selectedTableName.value) await loadPreview(selectedTableName.value)
}

const onFileChange = (file, fileList = []) => {
  uploadFile.value = file.raw
  uploadFiles.value = fileList.map(item => item.raw).filter(Boolean)
}

const onBatchFileChange = onFileChange

const onFileRemove = (file, fileList = []) => {
  uploadFiles.value = fileList.map(item => item.raw).filter(Boolean)
  uploadFile.value = uploadFiles.value[0] || null
}

const parseTaskResultJson = (raw) => {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  if (typeof raw !== 'string') return {}
  try {
    return JSON.parse(raw)
  } catch {
    return {}
  }
}

const resolveUploadTaskId = (task) => {
  const candidates = [
    task?.taskId,
    task?.task_id,
    task?.id,
    task?.task?.taskId,
    task?.task?.task_id,
    task?.data?.taskId,
    task?.data?.task_id
  ]
  for (const candidate of candidates) {
    const value = String(candidate ?? '').trim()
    if (value) return value
  }
  return ''
}

const finalizeUploadResult = async (resultCandidate = {}, uploadedTableName = '') => {
  const result = resultCandidate && typeof resultCandidate === 'object' ? { ...resultCandidate } : {}
  uploadDisplayName.value = ''
  const resolvedTableName = String(result.tableName || uploadedTableName || selectedTableName.value || '').trim()
  if (resolvedTableName) {
    selectedTableName.value = resolvedTableName
  }

  const hasAutoApply = result.autoAppliedModel && typeof result.autoAppliedModel === 'object'
  if (!hasAutoApply && resolvedTableName) {
    try {
      result.autoAppliedModel = unwrap(await axios.post(`${API_BASE}/api/data/tables/${resolvedTableName}/auto-apply-model`))
    } catch (error) {
      result.autoAppliedModel = {
        matched: false,
        applied: false,
        tableName: resolvedTableName,
        error: error?.message || '自动套用业务模型失败'
      }
    }
  }

  uploadResult.value = result
  await Promise.all([loadTables({ preferredTableName: resolvedTableName, keepCurrentSelection: true }), loadBusinessModels()])
}

const clearUploadProgressHideTimer = () => {
  if (uploadProgressHideTimer) {
    clearTimeout(uploadProgressHideTimer)
    uploadProgressHideTimer = null
  }
}

const hideCompletedUploadProgressSoon = () => {
  clearUploadProgressHideTimer()
  uploadProgressHideTimer = setTimeout(() => {
    uploadProgress.value = {
      ...uploadProgress.value,
      visible: false
    }
    uploadTask.value = null
    uploadProgressHideTimer = null
  }, 3000)
}

const submitUpload = async () => {
  if (!uploadFile.value && !uploadFiles.value.length) return
  clearUploadProgressHideTimer()
  uploading.value = true
  uploadTask.value = null
  uploadProgress.value = {
    visible: true,
    percentage: 0,
    loaded: 0,
    total: 0,
    computable: false,
    status: 'UPLOADING'
  }
  const formData = new FormData()
  const batchMode = uploadFiles.value.length > 1 || Boolean(modelRequirement.value.trim())
  if (batchMode) {
    uploadFiles.value.forEach(file => formData.append('files', file))
    formData.append('mergeMode', uploadMergeMode.value)
    formData.append('joinKey', uploadJoinKey.value)
    formData.append('modelRequirement', modelRequirement.value)
    formData.append('displayName', uploadDisplayName.value)
  } else {
    formData.append('file', uploadFile.value)
    formData.append('displayName', uploadDisplayName.value)
  }
  const uploadedTableName = String(selectedTableName.value || '').trim()
  try {
    const task = unwrap(await axios.post(`${API_BASE}/api/data/${batchMode ? 'upload-batch-async' : 'upload-async'}`, formData, {
      onUploadProgress: (event) => {
        const total = event.total || 0
        uploadProgress.value = {
          visible: true,
          percentage: total > 0 ? Math.min(100, Math.round((event.loaded * 100) / total)) : 0,
          loaded: event.loaded || 0,
          total,
          computable: total > 0,
          status: 'UPLOADING'
        }
      }
    }))
    uploadProgress.value = {
      ...uploadProgress.value,
      visible: true,
      percentage: 100,
      status: 'SUCCESS'
    }
    uploadTask.value = task && typeof task === 'object' ? task : null
    const taskId = resolveUploadTaskId(task)
    if (taskId) {
      await pollUploadTask(taskId, uploadedTableName)
    } else {
      // 兼容后端直返结果或网关丢 taskId 的场景
      const directResult = parseTaskResultJson(task?.resultJson)
      await finalizeUploadResult({
        ...directResult,
        ...(task?.tableName ? { tableName: task.tableName } : {})
      }, uploadedTableName)
    }
    hideCompletedUploadProgressSoon()
    ElMessage.success('文件已解析入库')
  } catch (error) {
    uploadProgress.value = {
      ...uploadProgress.value,
      visible: true,
      status: 'FAILED'
    }
    ElMessage.error(error.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

const refreshUploadTask = async (taskId) => {
  try {
    uploadTask.value = unwrap(await axios.get(`${API_BASE}/api/data/upload-task/${taskId}`))
  } catch (error) {
    ElMessage.error(error.message || '上传进度查询失败')
  }
}

const pollUploadTask = async (taskId, uploadedTableName = '') => {
  for (let i = 0; i < 120; i++) {
    await new Promise(resolve => setTimeout(resolve, 1000))
    await refreshUploadTask(taskId)
    if (uploadTask.value?.status === 'SUCCESS') {
      const result = parseTaskResultJson(uploadTask.value.resultJson)
      await finalizeUploadResult(result, uploadedTableName)
      return
    }
    if (uploadTask.value?.status === 'FAILED') {
      throw new Error(uploadTask.value.message || '上传处理失败')
    }
  }
  throw new Error('上传处理超时，请稍后刷新任务状态')
}

const loadBusinessModels = async () => {
  businessModels.value = unwrap(await axios.get(`${API_BASE}/api/data/business-models`))
  enterpriseModels.value = unwrap(await axios.get(`${API_BASE}/api/data/business-models`, { params: { enterpriseOnly: true } }))
  syncChatBusinessModelSelection()
}

const onTemplateFileChange = (file) => {
  templateFile.value = file.raw
}

const loadAnalysisTemplates = async () => {
  analysisTemplates.value = unwrap(await axios.get(`${API_BASE}/api/data/templates`))
  if (!selectedTemplateId.value && analysisTemplates.value.length) {
    selectedTemplateId.value = analysisTemplates.value[0].id
  }
}

const uploadAnalysisTemplate = async () => {
  if (!templateFile.value) return ElMessage.warning('请选择 .txt 或 .md 分析模板')
  const formData = new FormData()
  formData.append('file', templateFile.value)
  await axios.post(`${API_BASE}/api/data/templates/upload`, formData).then(unwrap)
  ElMessage.success('分析模板已上传')
  templateFile.value = null
  await loadAnalysisTemplates()
}

const createBusinessModelFromTemplate = async () => {
  if (!selectedTableName.value) return ElMessage.warning('请先选择数据表')
  if (!selectedTemplateId.value) return ElMessage.warning('请先选择分析模板')
  if (!templateRequirement.value.trim()) return ElMessage.warning('请填写一句话建模需求')
  await axios.post(`${API_BASE}/api/data/business-model/from-template`, {
    tableName: selectedTableName.value,
    templateId: selectedTemplateId.value,
    requirement: templateRequirement.value
  }).then(unwrap)
  ElMessage.success('模板业务模型已生成')
  await loadBusinessModels()
}

const SEMANTIC_DICT_MARKER = /(?:新增|增加|添加|创建)?(?:业务字典|字典|词典|同义词|业务术语|黑话映射)\s*[：:]/i
const SEMANTIC_FORMULA_MARKER = /(?:新增|增加|添加)?(?:指标公式|业务公式|公式)\s*[：:]/i

const splitTopLevelSegments = (text, separators = [';', '；', '\n', '，', ',', '、']) => {
  const result = []
  let buffer = ''
  const stack = []
  const openChars = new Set(['(', '（', '[', '{'])
  const closeToOpen = {
    ')': '(',
    '）': '（',
    ']': '[',
    '}': '{'
  }
  for (const ch of String(text || '')) {
    if (openChars.has(ch)) {
      stack.push(ch)
      buffer += ch
      continue
    }
    if (closeToOpen[ch]) {
      if (stack.length && stack[stack.length - 1] === closeToOpen[ch]) {
        stack.pop()
      }
      buffer += ch
      continue
    }
    if (separators.includes(ch) && stack.length === 0) {
      const value = buffer.trim()
      if (value) result.push(value)
      buffer = ''
      continue
    }
    buffer += ch
  }
  const tail = buffer.trim()
  if (tail) result.push(tail)
  return result
}

const findFirstMatchIndexAfter = (text, regex, minIndex) => {
  const flags = regex.flags.includes('g') ? regex.flags : `${regex.flags}g`
  const globalRegex = new RegExp(regex.source, flags)
  let match
  while ((match = globalRegex.exec(text)) !== null) {
    if (match.index > minIndex) {
      return match.index
    }
    if (globalRegex.lastIndex === match.index) {
      globalRegex.lastIndex += 1
    }
  }
  return -1
}

const extractSemanticSection = (text, markerRegex, allMarkers = []) => {
  const source = String(text || '')
  const marker = new RegExp(markerRegex.source, markerRegex.flags)
  const match = marker.exec(source)
  if (!match) return ''
  const start = match.index + match[0].length
  let end = source.length
  allMarkers.forEach((nextMarker) => {
    const idx = findFirstMatchIndexAfter(source, nextMarker, start - 1)
    if (idx !== -1 && idx < end) {
      end = idx
    }
  })
  return source.slice(start, end).trim()
}

const removeSemanticSection = (text, markerRegex, allMarkers = []) => {
  const source = String(text || '')
  const marker = new RegExp(markerRegex.source, markerRegex.flags)
  const match = marker.exec(source)
  if (!match) return source
  const start = match.index
  const contentStart = match.index + match[0].length
  let end = source.length
  allMarkers.forEach((nextMarker) => {
    const idx = findFirstMatchIndexAfter(source, nextMarker, contentStart - 1)
    if (idx !== -1 && idx < end) {
      end = idx
    }
  })
  return `${source.slice(0, start)} ${source.slice(end)}`
    .replace(/\s+/g, ' ')
    .trim()
}

const splitTermAliases = (text) => String(text || '')
  .split(/[，,、|/]+/)
  .map(item => item.trim())
  .filter(Boolean)

const parseDictionaryInstructionEntries = (sectionText) => {
  const rows = []
  const seen = new Set()
  splitTopLevelSegments(sectionText).forEach((rawItem) => {
    const item = String(rawItem || '')
      .replace(/^(同义词|词典|字典|业务字典|业务术语|黑话映射)\s*[：:]/i, '')
      .replace(/^[,，;；。:：\s-]+/, '')
      .trim()
    if (!item) return

    let term = ''
    let field = ''
    let synonymsText = ''

    let match = item.match(/^([^=:=（(]+?)\s*[=:：]\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:[（(]([^()（）]+)[）)])?$/)
    if (match) {
      term = String(match[1] || '').trim()
      field = String(match[2] || '').trim()
      synonymsText = String(match[3] || '').trim()
    } else {
      match = item.match(/^([^=:=（(]+?)\s*[（(]([^()（）]+)[）)]\s*[=:：]\s*([A-Za-z_][A-Za-z0-9_]*)$/)
      if (match) {
        term = String(match[1] || '').trim()
        synonymsText = String(match[2] || '').trim()
        field = String(match[3] || '').trim()
      } else {
        match = item.match(/^(.+?)(?:映射|对应|绑定|关联到|关联)\s*([A-Za-z_][A-Za-z0-9_]*)$/)
        if (!match) return
        term = String(match[1] || '').trim()
        field = String(match[2] || '').trim()
      }
    }

    if (!term && !field) return
    const key = `${term.toLowerCase()}@@${field.toLowerCase()}`
    if (seen.has(key)) return
    seen.add(key)
    rows.push({
      term,
      field,
      synonyms: splitTermAliases(synonymsText).join(',')
    })
  })
  return rows
}

const splitByFirstTopLevelDelimiter = (text, delimiters = ['=', '：', ':']) => {
  const source = String(text || '')
  const stack = []
  const openChars = new Set(['(', '（', '[', '{'])
  const closeToOpen = {
    ')': '(',
    '）': '（',
    ']': '[',
    '}': '{'
  }
  for (let i = 0; i < source.length; i++) {
    const ch = source[i]
    if (openChars.has(ch)) {
      stack.push(ch)
      continue
    }
    if (closeToOpen[ch]) {
      if (stack.length && stack[stack.length - 1] === closeToOpen[ch]) {
        stack.pop()
      }
      continue
    }
    if (stack.length === 0 && delimiters.includes(ch)) {
      return [source.slice(0, i), source.slice(i + 1)]
    }
  }
  return null
}

const inferPrimaryFieldFromFormula = (formula) => {
  const tokens = String(formula || '').match(/[A-Za-z_][A-Za-z0-9_]*/g) || []
  const stopWords = new Set([
    'sum', 'avg', 'count', 'min', 'max', 'distinct', 'case', 'when', 'then', 'else', 'end',
    'if', 'ifnull', 'coalesce', 'nullif', 'cast', 'as', 'and', 'or', 'not', 'is', 'in',
    'like', 'over', 'partition', 'by', 'order', 'desc', 'asc', 'round', 'floor', 'ceil', 'abs', 'mod'
  ])
  for (const token of tokens) {
    const lower = token.toLowerCase()
    if (!stopWords.has(lower)) {
      return token
    }
  }
  return ''
}

const parseMetricInstructionEntries = (sectionText) => {
  const rows = []
  const seen = new Set()
  splitTopLevelSegments(sectionText).forEach((rawItem) => {
    const item = String(rawItem || '')
      .replace(/^(新增|增加|添加)?(指标公式|业务公式|公式)\s*[：:]/i, '')
      .replace(/^[,，;；。:：\s-]+/, '')
      .trim()
    if (!item) return

    const pair = splitByFirstTopLevelDelimiter(item)
    if (!pair) return
    const name = String(pair[0] || '').trim()
    const formula = String(pair[1] || '').trim().replace(/^=\s*/, '')
    if (!name || !formula) return

    const dedupeKey = name.toLowerCase()
    if (seen.has(dedupeKey)) return
    seen.add(dedupeKey)
    rows.push({
      name,
      field: inferPrimaryFieldFromFormula(formula),
      aggregation: 'SUM',
      formula
    })
  })
  return rows
}

const parseSemanticModelInstruction = (questionText) => {
  const source = String(questionText || '').trim()
  const markers = [SEMANTIC_DICT_MARKER, SEMANTIC_FORMULA_MARKER]
  const dictionaryEntries = parseDictionaryInstructionEntries(
    extractSemanticSection(source, SEMANTIC_DICT_MARKER, markers)
  )
  const metricDefinitions = parseMetricInstructionEntries(
    extractSemanticSection(source, SEMANTIC_FORMULA_MARKER, markers)
  )
  let requirement = removeSemanticSection(source, SEMANTIC_DICT_MARKER, markers)
  requirement = removeSemanticSection(requirement, SEMANTIC_FORMULA_MARKER, markers)
  requirement = requirement.replace(/[，,;；。]+$/g, '').trim()
  return {
    requirement: requirement || source,
    dictionaryEntries,
    metricDefinitions
  }
}

const hasSemanticBusinessDictionaryIntent = (text) => {
  const q = String(text || '').trim()
  if (!q) return false
  return ['字典', '同义词', '黑话', '别名', '映射', '术语', '业务术语'].some(token => q.includes(token))
}

const hasSemanticBusinessFormulaIntent = (text) => {
  const q = String(text || '').trim()
  if (!q) return false
  return ['公式', '指标', '衍生', '计算', '聚合', '利润率', '转化率', '同比', '环比'].some(token => q.includes(token))
}

const resolveSemanticBusinessDraft = (questionText) => {
  const draft = parseSemanticModelInstruction(questionText)
  const source = String(questionText || '').trim()
  return {
    requirement: String(draft.requirement || source).trim(),
    dictionaryEntries: hasSemanticBusinessDictionaryIntent(source) ? draft.dictionaryEntries : [],
    metricDefinitions: hasSemanticBusinessFormulaIntent(source) ? draft.metricDefinitions : []
  }
}

const buildSemanticModelName = (requirement, tableName) => {
  const fallback = `模型_${tableName || 'default'}`
  const raw = String(requirement || '').trim()
  if (!raw) return fallback

  let text = raw
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/[“”"'<>{}`]/g, '')
    .replace(/\s+/g, ' ')
    .trim()

  text = text
    .replace(/^(请|请你|帮我|麻烦|需要|我想|想要|帮忙)+/g, '')
    .replace(/^基于(?:当前|现有)?[^，。；;\n]*?(?:表|数据源)?/g, '')
    .replace(/^(围绕|针对|面向)/g, '')
    .replace(/(创建|生成|新建|建立|搭建|构建)(一个|一份|个)?/g, '')
    .replace(/(同义词|词典|字典|新增指标公式|增加指标公式|添加指标公式|指标公式|业务公式|公式)\s*[：:].*/g, '')
    .replace(/(并|然后|之后).*/g, '')
    .replace(/^[,，;；。:：\s-]+/, '')
    .replace(/[,，;；。:：\s-]+$/, '')
    .trim()

  text = text
    .replace(/当前/g, '')
    .replace(/销售明细表/g, '')
    .replace(/数据表/g, '')
    .replace(/明细表/g, '')
    .replace(/数据源/g, '')
    .replace(/业务模型/g, '')
    .replace(/模型搭建/g, '')
    .replace(/进行/g, '')
    .replace(/一个/g, '')
    .replace(/^(基于|按照|按|对|将)/g, '')
    .trim()

  if (!text) return fallback
  if (text.endsWith('模型模型')) text = text.slice(0, -2)
  if (text.length > 16) text = text.slice(0, 16).trim()
  if (!text.endsWith('模型')) text = `${text}模型`
  return text || fallback
}

const createBusinessModel = async (options = {}) => {
  const tableName = String(options?.tableName || selectedTableName.value || '').trim()
  const baseRequirement = String(options?.requirement ?? modelRequirement.value ?? '').trim()
  if (!tableName) {
    ElMessage.warning('请先选择数据表')
    return null
  }
  const requirement = baseRequirement || `基于${tableName}的业务分析模型`
  const modelName = String(options?.modelName || buildSemanticModelName(requirement, tableName)).trim()
  const payload = {
    tableName,
    requirement,
    modelName
  }
  if (Array.isArray(options?.dictionaryEntries) && options.dictionaryEntries.length > 0) {
    payload.dictionaryEntries = options.dictionaryEntries
  }
  if (Array.isArray(options?.metricDefinitions) && options.metricDefinitions.length > 0) {
    payload.metricDefinitions = options.metricDefinitions
  }

  const created = await axios.post(`${API_BASE}/api/data/business-models`, payload).then(unwrap)
  activeBusinessModelId.value = created?.id ?? null
  selectedChatBusinessModelId.value = created?.id ?? null
  lastCreatedBusinessModelId.value = created?.id ?? null

  if (!options?.silentSuccess) {
    ElMessage.success(`业务模型已生成：${modelName}`)
  }
  await loadBusinessModels()
  return {
    id: created?.id ?? null,
    tableName,
    requirement,
    modelName: String(created?.modelName || modelName).trim()
  }
}

const BUSINESS_MODEL_CREATE_HINTS = [
  '创建业务模型', '生成业务模型', '新建业务模型', '建立业务模型', '搭建业务模型',
  '建模', '业务模型', '模型维护', '业务字典', '业务公式', '同义词', '衍生指标', '指标公式'
]

const BUSINESS_MODEL_CREATE_PATTERNS = [
  /(?:创建|新建|生成|建立|搭建|构建|做一个|建一个)[^。！？\n]{0,48}模型/i,
  /模型[^。！？\n]{0,24}(?:创建|新建|生成|建立|搭建|构建)/i
]

const shouldCreateBusinessModelFromQuestion = (text) => {
  const q = String(text || '').trim()
  if (!q) return false
  const lower = q.toLowerCase()
  if (BUSINESS_MODEL_CREATE_HINTS.some(token => lower.includes(token.toLowerCase()))) {
    return true
  }
  return BUSINESS_MODEL_CREATE_PATTERNS.some(pattern => pattern.test(q))
}

const openBusinessDictionaryAfterModelCreate = async (questionText, tableName) => {
  if (!tableName) {
    throw new Error('未选择数据表，无法创建业务模型')
  }
  const semanticDraft = resolveSemanticBusinessDraft(questionText)
  const requirement = String(semanticDraft.requirement || questionText || '').trim()
  if (!requirement) {
    throw new Error('建模需求为空，无法创建业务模型')
  }

  const beforeIds = new Set((businessModels.value || []).map(item => String(item.id)))
  const createdResult = await createBusinessModel({
    tableName,
    requirement,
    dictionaryEntries: semanticDraft.dictionaryEntries,
    metricDefinitions: semanticDraft.metricDefinitions,
    silentSuccess: true
  })
  await loadBusinessModels()

  const createdId = createdResult?.id == null ? '' : String(createdResult.id)
  const created = (createdId ? (businessModels.value || []).find(item => String(item.id) === createdId) : null)
    || (businessModels.value || []).find(item => !beforeIds.has(String(item.id)))
    || (businessModels.value || []).find(item => String(item.tableName || '') === String(tableName || ''))
  if (created?.id != null) {
    applyChatBusinessModelSelection(created.id)
    lastCreatedBusinessModelId.value = created.id
  }
  businessDictionaryFocusModelId.value = created?.id ?? null
  businessDictionaryPanelVisible.value = true
  return {
    ...(created || createdResult || {}),
    parsedDictionaryCount: semanticDraft.dictionaryEntries.length,
    parsedMetricCount: semanticDraft.metricDefinitions.length
  }
}

const publishBusinessModel = async (model, published = true) => {
  await axios.post(`${API_BASE}/api/data/business-models/${model.id}/publish`, { published }).then(unwrap)
  if (model?.id != null) {
    applyChatBusinessModelSelection(model.id)
  }
  ElMessage.success(published ? '已发布到企业模型库' : '已取消发布')
  await loadBusinessModels()
}

const applyBusinessModel = async (model, tableName = '') => {
  const targetTableName = String(tableName || selectedTableName.value || '').trim()
  if (!targetTableName) return ElMessage.warning('请先选择要套用模型的数据源')
  const applied = await axios.post(`${API_BASE}/api/data/business-models/${model.id}/apply`, { tableName: targetTableName }).then(unwrap)
  applyChatBusinessModelSelection(applied?.id ?? model?.id ?? null)
  lastAppliedBusinessModelId.value = applied?.id ?? null
  ElMessage.success('模型已套用')
  await loadBusinessModels()
  return {
    targetTableName,
    appliedModel: applied
  }
}

const openBusinessDictionaryByModelId = async (modelId) => {
  const normalizedId = String(modelId || '').trim()
  if (!normalizedId) {
    throw new Error('业务模型标识不能为空')
  }
  await loadBusinessModels()
  const resolvedId = Number.isNaN(Number(normalizedId)) ? normalizedId : Number(normalizedId)
  applyChatBusinessModelSelection(resolvedId)
  businessDictionaryFocusModelId.value = resolvedId
  businessDictionaryPanelVisible.value = true
}

const BUSINESS_MODEL_AGENT_HINTS = [
  ...BUSINESS_MODEL_CREATE_HINTS,
  '发布', '取消发布', '套用', '复用', '应用', '迁移', '复制', '模型', '企业模型库', '企业模型', '当前模型', '刚创建',
  '删除', '移除', '去掉', '业务公式', '指标公式', '字典映射', '字段绑定', '绑定字段', '字段修正', '改绑', '重新绑定',
  '绑定到', '绑定为', '绑定至', '映射到', '映射为', '映射至', '对应到', '对应为', '对应至'
]

const shouldUseBusinessModelAgent = (text) => {
  const q = String(text || '').trim()
  if (!q) return false
  const lower = q.toLowerCase()
  const hasDeleteIntent = ['删除', '移除', '去掉'].some(token => q.includes(token))
  const hasBindingIntent = ['字段绑定', '绑定字段', '字段修正', '改绑', '重新绑定', '绑定到', '绑定为', '绑定至', '映射到', '映射为', '映射至', '对应到', '对应为', '对应至'].some(token => q.includes(token))
  const hasBusinessModelTarget = ['模型', '业务字典', '业务公式', '指标公式', '公式', '字典', '指标', '维度', '企业模型库', '当前模型', '这个模型'].some(token => q.includes(token))
  if (hasDeleteIntent && hasBusinessModelTarget) {
    return true
  }
  if (hasBindingIntent && ['字段', '指标', '公式', '维度', '字典', '术语', '同义词', '模型'].some(token => q.includes(token))) {
    return true
  }
  if (BUSINESS_MODEL_AGENT_HINTS.some(token => lower.includes(token.toLowerCase()))) {
    return true
  }
  const fallbackKeywords = ['创建', '新建', '生成', '搭建', '建模', '发布', '套用', '复用', '模型', '业务字典', '业务公式', '企业模型库']
  return fallbackKeywords.some(keyword => q.includes(keyword)) && Boolean(activeBusinessModelId.value)
}

const normalizeBusinessModelContextId = (value) => {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const numeric = Number(value)
  return Number.isNaN(numeric) ? String(value).trim() : numeric
}

const syncBusinessModelContext = (result = {}) => {
  const activeId = normalizeBusinessModelContextId(result.activeBusinessModelId ?? result.focusModelId ?? result.modelId)
  const createdId = normalizeBusinessModelContextId(result.lastCreatedBusinessModelId ?? (result.intent === 'CREATE_MODEL' ? result.modelId : null))
  const appliedId = normalizeBusinessModelContextId(result.lastAppliedBusinessModelId ?? (result.intent === 'APPLY_ENTERPRISE_MODEL' ? result.appliedModelId : null))
  if (activeId !== null) {
    applyChatBusinessModelSelection(activeId)
  }
  if (createdId !== null) {
    lastCreatedBusinessModelId.value = createdId
  }
  if (appliedId !== null) {
    lastAppliedBusinessModelId.value = appliedId
  }
  const targetTableName = String(result.targetTableName || result.tableName || '').trim()
  if (targetTableName) {
    selectedTableName.value = targetTableName
  }
  syncChatBusinessModelSelection(activeId ?? createdId ?? appliedId)
}

const normalizeFieldBindingResults = (entries) => {
  if (!Array.isArray(entries)) return []
  return entries
    .map((item) => {
      const name = String(item?.name || '').trim()
      const field = String(item?.field || '').trim()
      const fieldDisplayName = String(item?.fieldDisplayName || '').trim()
      const formula = String(item?.formula || '').trim()
      const action = String(item?.action || 'UPSERT').trim().toUpperCase()
      const targetType = String(item?.targetType || '').trim()
      const semanticAction = String(item?.semanticAction || '').trim().toUpperCase()
      const label = String(item?.label || '').trim()
      if (!name || (!field && !(targetType === 'metricDefinition' && formula))) return null
      if (targetType === 'metricDefinition' && !formula && /[+\-*/()]/.test(field)) return null
      return {
        name,
        field,
        fieldDisplayName,
        formula,
        action,
        targetType,
        semanticAction,
        label: semanticAction
          ? resolveFieldBindingItemLabel(name, targetType, semanticAction)
          : (label || resolveFieldBindingItemLabel(name, targetType, semanticAction))
      }
    })
    .filter(Boolean)
}

const resolveFieldBindingItemLabel = (name, targetType, semanticAction) => {
  if (semanticAction === 'METRIC_SCOPE_UPDATE') return `指标口径：${name}`
  if (semanticAction === 'METRIC_FORMULA_UPDATE') return `指标公式：${name}`
  if (semanticAction === 'DIMENSION_BINDING') return `业务维度：${name}`
  if (semanticAction === 'DICTIONARY_UPSERT') return `业务字典：${name}`
  if (semanticAction === 'FIELD_BINDING') return `字段绑定：${name}`
  if (targetType === 'dictionaryEntry') return `业务字典：${name}`
  if (targetType === 'dimensionDefinition') return `业务维度：${name}`
  return `业务公式：${name}`
}

const looksLikeExplicitDictionaryMutation = (question) => /(?:新增|增加|添加|创建|补充|修改|更新)?(?:业务字典|字典|词典|同义词|术语|映射)/.test(String(question || ''))
const looksLikeExplicitFormulaMutation = (question) => /(?:新增|增加|添加|创建|补充|修改|更新)?(?:业务公式|指标公式|公式)/.test(String(question || ''))
const looksLikeExplicitFieldBinding = (question) => /字段绑定|绑定字段|字段修正|改绑|重新绑定|绑定到|绑定为|绑定至|映射到|映射为|映射至|对应到|对应为|对应至/.test(String(question || ''))

const resolveFieldBindingCardTitle = (question, intent, entries) => {
  if (!Array.isArray(entries) || !entries.length) return ''
  const q = String(question || '').trim()
  const normalizedIntent = String(intent || '').trim().toUpperCase()
  const hasDelete = entries.some(item => String(item?.action || '').toUpperCase() === 'DELETE')
  const hasUpsert = entries.some(item => String(item?.action || '').toUpperCase() !== 'DELETE')
  const targetTypes = [...new Set(entries.map(item => String(item?.targetType || '').trim()).filter(Boolean))]
  const semanticActions = [...new Set(entries.map(item => String(item?.semanticAction || '').trim().toUpperCase()).filter(Boolean))]
  const allDictionaryEntries = targetTypes.length === 1 && targetTypes[0] === 'dictionaryEntry'
  const allDimensions = targetTypes.length === 1 && targetTypes[0] === 'dimensionDefinition'
  const allMetrics = targetTypes.length === 1 && targetTypes[0] === 'metricDefinition'
  const allScopeUpdates = semanticActions.length === 1 && semanticActions[0] === 'METRIC_SCOPE_UPDATE'
  const allFormulaUpdates = semanticActions.length === 1 && semanticActions[0] === 'METRIC_FORMULA_UPDATE'
  const allFieldBindings = semanticActions.length === 1 && semanticActions[0] === 'FIELD_BINDING'
  const allDictionaryUpserts = semanticActions.length === 1 && semanticActions[0] === 'DICTIONARY_UPSERT'
  const allDimensionBindings = semanticActions.length === 1 && semanticActions[0] === 'DIMENSION_BINDING'

  if (normalizedIntent === 'BIND_FIELDS') {
    if (allFieldBindings) {
      return hasDelete && !hasUpsert ? '字段解绑结果' : '字段绑定结果'
    }
    if (looksLikeExplicitDictionaryMutation(q)) {
      return hasDelete && !hasUpsert ? '业务字典删除结果' : '业务字典映射结果'
    }
    if (looksLikeExplicitFormulaMutation(q)) {
      return hasDelete && !hasUpsert ? '业务公式删除结果' : '业务公式变更结果'
    }
    if (looksLikeExplicitFieldBinding(q)) {
      return hasDelete && !hasUpsert ? '字段解绑结果' : '字段修正结果'
    }
    return hasDelete && !hasUpsert ? '字段解绑结果' : '字段修正结果'
  }
  if (allScopeUpdates) {
    return hasDelete && !hasUpsert ? '指标口径删除结果' : '指标口径更新结果'
  }
  if (allFormulaUpdates) {
    return hasDelete && !hasUpsert ? '指标公式删除结果' : '指标公式更新结果'
  }
  if (allDictionaryUpserts) {
    return hasDelete && !hasUpsert ? '业务字典删除结果' : '业务字典映射结果'
  }
  if (allDimensionBindings) {
    return hasDelete && !hasUpsert ? '业务维度删除结果' : '业务维度绑定结果'
  }
  if (allDictionaryEntries || /(业务字典|字典|词典|同义词|术语)/.test(q)) {
    if (hasDelete && !hasUpsert) return '业务字典删除结果'
    if (hasUpsert && !hasDelete) return '业务字典映射结果'
    return '业务字典变更结果'
  }
  if (allDimensions || /维度/.test(q)) {
    if (hasDelete && !hasUpsert) return '业务维度删除结果'
    if (hasUpsert && !hasDelete) return '业务维度绑定结果'
    return '业务维度变更结果'
  }
  if (allMetrics) {
    if (hasDelete && !hasUpsert) return '业务公式删除结果'
    if (hasUpsert && !hasDelete) return '业务公式变更结果'
    return '业务公式变更结果'
  }
  return hasDelete && !hasUpsert ? '模型变更结果' : '字段更新结果'
}

const handleBusinessModelAgentQuestion = async ({ question, tableName, semanticDraft }) => {
  const payload = {
    question,
    tableName,
    selectedTableName: selectedTableName.value,
    activeBusinessModelId: activeBusinessModelId.value,
    lastCreatedBusinessModelId: lastCreatedBusinessModelId.value,
    lastAppliedBusinessModelId: lastAppliedBusinessModelId.value
  }
  if (semanticDraft) {
    payload.requirement = semanticDraft.requirement
    if (Array.isArray(semanticDraft.dictionaryEntries) && semanticDraft.dictionaryEntries.length > 0) {
      payload.dictionaryEntries = semanticDraft.dictionaryEntries
    }
    if (Array.isArray(semanticDraft.metricDefinitions) && semanticDraft.metricDefinitions.length > 0) {
      payload.metricDefinitions = semanticDraft.metricDefinitions
    }
  }
  return axios.post(`${API_BASE}/api/chat/business-model-agent`, payload).then(unwrap)
}

const streamBusinessModelAgentQuestion = async ({ question, tableName, onThinking }) => {
  const token = authToken.value || localStorage.getItem('token') || ''
  if (!token) {
    throw new Error('登录状态缺失，请重新登录')
  }
  const controller = new AbortController()
  streamAbortController.value = controller
  const params = new URLSearchParams({
    question: String(question || ''),
    tableName: String(tableName || ''),
    selectedTableName: String(selectedTableName.value || ''),
    activeBusinessModelId: activeBusinessModelId.value == null ? '' : String(activeBusinessModelId.value),
    lastCreatedBusinessModelId: lastCreatedBusinessModelId.value == null ? '' : String(lastCreatedBusinessModelId.value),
    lastAppliedBusinessModelId: lastAppliedBusinessModelId.value == null ? '' : String(lastAppliedBusinessModelId.value)
  })
  const response = await fetch(`${API_BASE}/api/chat/business-model-agent-stream?${params.toString()}`, {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`
    },
    cache: 'no-store',
    signal: controller.signal
  })
  if (!response.ok || !response.body) {
    throw new Error(`业务模型流式通道不可用（${response.status}）`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let finalResult = null

  const handleSseChunk = (rawChunk) => {
    const lines = rawChunk.split(/\r?\n/)
    let eventName = 'message'
    let dataText = ''
    lines.forEach((line) => {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataText += line.slice(5).trim()
      }
    })
    if (!dataText) return
    const payload = JSON.parse(dataText)
    if (eventName === 'thinking') {
      onThinking?.(payload)
      return
    }
    if (eventName === 'error') {
      throw new Error(payload?.message || '业务模型处理失败')
    }
    if (eventName === 'result') {
      finalResult = payload
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split('\n\n')
    buffer = chunks.pop() || ''
    for (const chunk of chunks) {
      if (!chunk.trim()) continue
      handleSseChunk(chunk)
    }
  }
  if (buffer.trim()) {
    handleSseChunk(buffer)
  }
  if (streamAbortController.value === controller) {
    streamAbortController.value = null
  }
  return finalResult
}

const updateBusinessModel = async (modelId, payload) => {
  return axios.post(`${API_BASE}/api/data/business-models/${modelId}/update`, payload).then(unwrap)
}

const renameDataTable = async (row) => {
  if (isOfficialTableName(row?.tableName)) {
    ElMessage.warning('官方库表不支持在此重命名')
    return
  }
  await axios.post(`${API_BASE}/api/data/tables/${row.tableName}/rename`, { displayName: row.displayName }).then(unwrap)
  ElMessage.success('数据表已重命名')
  await loadTables()
}

const deleteDataTable = async (row) => {
  if (isOfficialTableName(row?.tableName)) {
    ElMessage.warning('官方库表不支持在此删除')
    return
  }
  await axios.post(`${API_BASE}/api/data/tables/${row.tableName}/delete`).then(unwrap)
  ElMessage.success('数据表已删除')
  await loadTables()
}

const copySqlToClipboard = async (sql) => {
  const text = String(sql || '').trim()
  if (!text) {
    ElMessage.warning('没有可复制的 SQL')
    return
  }
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.setAttribute('readonly', 'readonly')
      textarea.style.position = 'fixed'
      textarea.style.top = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      const copied = document.execCommand('copy')
      document.body.removeChild(textarea)
      if (!copied) {
        throw new Error('copy command failed')
      }
    }
    ElMessage.success('SQL 已复制')
  } catch (error) {
    ElMessage.error('复制失败，请手动复制')
  }
}

const loadDashboardOptions = async () => {
  const res = await axios.get(`${API_BASE}/api/c/dashboards/pin-targets`)
  const body = res.data
  if (body.code && body.code !== 200) {
    throw new Error(body.message || '加载看板失败')
  }
  const rows = Array.isArray(body.data) ? body.data : []
  dashboardOptions.value = rows.map(item => ({
    id: Number(item.id),
    name: String(item.name || `看板#${item.id}`),
    isPublic: Boolean(item.isPublic),
    pinTargetLabel: String(item.pinTargetLabel || '').trim(),
    status: String(item.status || '').trim()
  })).filter(item => Number.isFinite(item.id) && item.id > 0 && item.status !== 'ACTIVE')
  if (!pinDashboardId.value && dashboardOptions.value.length) {
    pinDashboardId.value = dashboardOptions.value[0].id
  }
}

const buildPinChartPayload = (analysis = lastAnalysis.value) => {
  if (!analysis) {
    return null
  }
  const chartIdRaw = analysis?.queryHistoryId ?? analysis?.chartId ?? analysis?.historyId
  const artifactIdRaw = analysis?.artifactId
  const turnIdRaw = analysis?.assistantTurnId ?? analysis?.turnId
  if ((chartIdRaw == null || chartIdRaw === '') && (artifactIdRaw == null || artifactIdRaw === '') && (turnIdRaw == null || turnIdRaw === '')) {
    return null
  }
  const resolvedTitle = String(
    analysis?.sourceQuestion
    || analysis?.title
    || analysis?.message
    || (analysis?.type ? `${chartTypeText(analysis.type)}卡片` : '图表卡片')
  ).slice(0, 80)
  return {
    chartId: chartIdRaw == null || chartIdRaw === '' ? undefined : Number(chartIdRaw),
    artifactId: artifactIdRaw == null || artifactIdRaw === '' ? undefined : Number(artifactIdRaw),
    turnId: turnIdRaw == null || turnIdRaw === '' ? undefined : Number(turnIdRaw),
    title: resolvedTitle,
    chartType: analysis?.chartType || (analysis?.type === 'forecast' ? 'line' : currentChartType.value) || 'bar',
    advancedAnalysisType: ['forecast', 'whatIf', 'alert'].includes(String(analysis?.type || '')) ? analysis.type : undefined,
    tableName: analysis?.tableName || analysis?.sourceTableName || analysis?.params?.tableName || '',
    sql: analysis?.sql || '',
    fieldMapping: analysis?.fieldMapping || {},
    data: Array.isArray(analysis?.data)
      ? analysis.data
      : (Array.isArray(analysis?.series) ? analysis.series : [])
  }
}

const pinLastAnalysisToDashboard = async (dashboardId, analysis = lastAnalysis.value) => {
  const payload = buildPinChartPayload(analysis)
  if (!payload) {
    throw new Error('当前结果缺少可绑定的图表记录，暂时无法钉入看板。请重新完成一次对话查询后再试。')
  }
  const res = await axios.post(`${API_BASE}/api/c/dashboards/${dashboardId}/pin-chart`, payload)
  const body = res.data
  if (body.code && body.code !== 200) {
    throw new Error(body.message || '钉入失败')
  }
  return body.data ?? body
}

const openPinDialog = async () => {
  if (!canPinLastAnalysis.value) {
    ElMessage.warning('暂无可钉入的图表结果')
    return
  }
  pendingDashboardPinSource.value = null
  try {
    await loadDashboardOptions()
    if (!dashboardOptions.value.length) {
      ElMessage.warning('暂无可用看板，请先到“我的看板”创建')
      return
    }
    pinDialogVisible.value = true
  } catch (error) {
    ElMessage.error(error.message || '加载看板列表失败')
  }
}

const openPinDialogForAnalysis = async (analysis) => {
  if (!buildPinChartPayload(analysis)) {
    ElMessage.warning('当前分析卡片还没有可钉入记录，请稍后重试或先保存到会话')
    return false
  }
  pendingDashboardPinSource.value = analysis
  try {
    await loadDashboardOptions()
    if (!dashboardOptions.value.length) {
      ElMessage.warning('暂无可用看板，请先到“我的看板”创建')
      pendingDashboardPinSource.value = null
      return false
    }
    pinDialogVisible.value = true
    return true
  } catch (error) {
    pendingDashboardPinSource.value = null
    ElMessage.error(error.message || '加载看板列表失败')
    return false
  }
}

const pinChartToDashboard = async () => {
  const source = pendingDashboardPinSource.value || lastAnalysis.value
  if (!source || !pinDashboardId.value) {
    ElMessage.warning('请选择目标看板')
    return
  }
  if (!buildPinChartPayload(source)) {
    ElMessage.warning('当前结果缺少可绑定的图表记录，暂时无法钉入看板。请重新完成一次对话查询后再试。')
    return
  }
  pinning.value = true
  try {
    await pinLastAnalysisToDashboard(pinDashboardId.value, source)
    ElMessage.success('图表已钉入看板')
    await loadPinnedHistoryIds()
    pinDialogVisible.value = false
    pendingDashboardPinSource.value = null
  } catch (error) {
    ElMessage.error(error.message || '钉入看板失败')
  } finally {
    pinning.value = false
  }
}

const isDashboardPinResult = (data, sourceQuestion = '') => {
  const intent = String(data?.smartIntent || data?.responseType || data?.draft?.primaryIntent || '').trim().toUpperCase()
  if (intent === 'DASHBOARD_PIN') return true
  const text = String(sourceQuestion || data?.queryQuestion || '').trim()
  return Boolean(text)
    && /看板|仪表盘|大屏|驾驶舱/.test(text)
    && /钉|保存到|保存至|存到|放到|放入|加入|添加到|挂到|挂入/.test(text)
}

const isDashboardPinQuestion = (text) => {
  const q = String(text || '').trim()
  if (!q) return false
  const hasPinAction = /钉|保存到|保存至|存到|放到|放入|加入|添加到|挂到|挂入/.test(q)
  if (!hasPinAction) return false
  if (/看板|仪表盘|大屏|驾驶舱/.test(q)) return true
  return /(?:当前|刚才|上一轮|这个|这张).*(?:图|图表|卡片|结果)|(?:图|图表|卡片|结果).*(?:当前|刚才|上一轮|这个|这张)/.test(q)
}

const isCompositeDashboardPinQuestion = (text) => {
  const q = String(text || '').trim()
  if (!isDashboardPinQuestion(q)) return false
  const hasAnalysisIntent = /查|查看|查询|统计|分析|展示|画|走势|趋势|排名|排行|对比|分布/.test(q)
    || /看.*(销售|收入|利润|订单|数据|图表|表现|情况|趋势|走势)/.test(q)
  const hasForecastIntent = /预测|预估|估一下|推算|未来|后面|往后|下个月|后续|大概会|继续涨|继续跌/.test(q)
  return hasAnalysisIntent || hasForecastIntent
}

const applyDashboardPinResult = async (data, sourceQuestion, updateStreamMessage, thinkingLogs = []) => {
  const message = String(data?.message || '').trim() || '看板钉入动作已处理'
  const status = String(data?.dashboardActionStatus || '').trim().toUpperCase()
  const candidates = Array.isArray(data?.dashboardCandidates) ? data.dashboardCandidates : []
  const logs = [
    ...thinkingLogs,
    `统一语义路由：识别意图 ${data?.smartIntent || data?.responseType || 'DASHBOARD_PIN'}`,
    status ? `看板动作状态：${status}` : '',
    data?.dashboardKeyword ? `目标看板关键词：${data.dashboardKeyword}` : '',
    message
  ].filter(Boolean).slice(0, 10)

  updateStreamMessage({
    content: message,
    sql: '',
    thinkingLogs: logs,
    thinkingCollapsed: true,
    sourceQuestion,
    sourceTableName: String(data?.queryTableName || data?.tableName || selectedTableName.value || '').trim(),
    turnId: data?.assistantTurnId == null ? null : String(data.assistantTurnId),
    parentTurnId: data?.parentTurnId == null ? null : String(data.parentTurnId),
    artifactId: data?.artifactId == null ? null : String(data.artifactId),
    artifactIds: Array.isArray(data?.artifactIds) ? data.artifactIds.map(item => String(item || '')).filter(Boolean) : []
  })

  if (!data?.requiresConfirmation && status === 'PINNED') {
    ElMessage.success(message)
    await loadPinnedHistoryIds()
    return true
  }

  if (status === 'NO_CHART') {
    ElMessage.warning(message)
    return true
  }

  const source = data?.source && typeof data.source === 'object' ? data.source : null
  const hasPinnableSource = Boolean(buildPinChartPayload(source || lastAnalysis.value))
  if (source) {
    pendingDashboardPinSource.value = {
      ...source,
      sourceQuestion: String(source.sourceQuestion || source.title || sourceQuestion || '').trim()
    }
  }

  if (candidates.length || hasPinnableSource) {
    try {
      await loadDashboardOptions()
      const candidateIdSet = new Set(candidates.map(item => String(item?.id || '')).filter(Boolean))
      const matched = dashboardOptions.value.find(item => candidateIdSet.has(String(item.id)))
      if (matched) {
        pinDashboardId.value = matched.id
      }
      if (dashboardOptions.value.length && hasPinnableSource) {
        pinDialogVisible.value = true
      }
    } catch (error) {
      ElMessage.error(error.message || '加载看板列表失败')
    }
  }
  return true
}

const autoPinDeferredDashboard = async (analysis, sourceQuestion, updateStreamMessage, thinkingLogs = []) => {
  const deferred = analysis?.deferredDashboardPin
  if (!deferred || typeof deferred !== 'object') return false
  const source = buildPinChartPayload(analysis)
  if (!source) {
    ElMessage.warning('图表已生成，但当前结果尚未拿到可钉入记录，请稍后从历史图表中钉入')
    return false
  }
  pendingDashboardPinSource.value = {
    ...analysis,
    sourceQuestion: String(analysis?.sourceQuestion || sourceQuestion || '').trim()
  }
  try {
    await loadDashboardOptions()
    if (!dashboardOptions.value.length) {
      ElMessage.warning('图表已生成，但暂无可用看板，请先到“我的看板”创建')
      return false
    }
    const pinQuestion = String(deferred.question || sourceQuestion || '').trim()
    const matched = dashboardOptions.value.filter(item => {
      const name = String(item?.name || '').trim()
      return name && pinQuestion.includes(name)
    })
    if (matched.length === 1) {
      await pinLastAnalysisToDashboard(matched[0].id, pendingDashboardPinSource.value)
      ElMessage.success(`图表已钉入${matched[0].name}`)
      await loadPinnedHistoryIds()
      pendingDashboardPinSource.value = null
      return true
    }
    if (matched.length > 1) {
      dashboardOptions.value = matched
    }
    pinDashboardId.value = matched[0]?.id || dashboardOptions.value[0]?.id || ''
    pinDialogVisible.value = true
    updateStreamMessage({
      thinkingLogs: [
        ...thinkingLogs,
        '后置看板钉入：图表已生成，请选择目标看板后确认'
      ].filter(Boolean).slice(0, 10),
      thinkingCollapsed: true
    })
    return true
  } catch (error) {
    ElMessage.error(error.message || '自动钉入看板失败')
    return false
  }
}

const isAbortLikeError = (error) => {
  const name = String(error?.name || '').toLowerCase()
  const code = String(error?.code || '').toUpperCase()
  const message = String(error?.message || '').toLowerCase()
  return name === 'aborterror'
      || name === 'cancelederror'
      || code === 'ERR_CANCELED'
      || message.includes('手动停止')
      || message.includes('aborted')
      || message.includes('stopped')
      || message.includes('cancel')
}

const normalizeVoiceSentence = (value) => String(value || '')
  .replace(/\s+/g, ' ')
  .replace(/[;；]+/g, '，')
  .replace(/[!！]+/g, '。')
  .trim()

const formatSpeechNumber = (value) => {
  const num = toNumber(value)
  if (Number.isNaN(num)) return String(value ?? '')
  const abs = Math.abs(num)
  if (abs >= 100000000) return `${(num / 100000000).toFixed(2)}亿`
  if (abs >= 10000) return `${(num / 10000).toFixed(2)}万`
  if (Number.isInteger(num)) return `${num}`
  return `${num.toFixed(2)}`
}

const chartTypeVoiceName = (chartType) => {
  const type = String(chartType || '').toLowerCase()
  if (type === 'pie') return '饼图'
  if (type === 'doughnut') return '环形图'
  if (type === 'line') return '折线图'
  if (type === 'table') return '表格'
  return '柱状图'
}

const voiceFriendlyLabel = (...values) => {
  for (const value of values) {
    const text = String(value ?? '').trim()
    if (!text || isVoicePhysicalColumnCode(text)) continue
    return fieldLabel(text) || text
  }
  const fallback = values.map(value => String(value ?? '').trim()).find(Boolean)
  return fallback || ''
}

const extractVoiceNumericRows = (analysis) => {
  const rows = Array.isArray(analysis?.data) ? analysis.data : []
  if (String(analysis?.chartType || '').toLowerCase() === 'table') {
    return []
  }
  if (analysis?.autoForecast || analysis?.advancedAnalysisType === 'forecast') {
    return rows
      .map(row => ({
        name: String(row?.name ?? '').trim() || '未命名',
        value: row?.forecast ?? row?.history ?? row?.value
      }))
      .filter(row => !Number.isNaN(toNumber(row.value)))
  }
  return getSortedChartData(rows)
}

const resolveVoiceSummaryConfig = (analysis) => {
  const raw = analysis?.voiceSummary && typeof analysis.voiceSummary === 'object' ? analysis.voiceSummary : {}
  const enabled = raw.enabled == null ? true : Boolean(raw.enabled)
  const order = Array.isArray(raw.order) && raw.order.length
    ? raw.order.map(item => String(item || '').trim()).filter(Boolean)
    : ['title', 'metric', 'max', 'min', 'trend', 'anomaly']
  const templates = raw.templates && typeof raw.templates === 'object' ? raw.templates : {}
  const chartTemplates = raw.chartTemplates && typeof raw.chartTemplates === 'object' ? raw.chartTemplates : {}
  return {
    ...raw,
    enabled,
    order,
    templates,
    chartTemplates
  }
}

const detectVoiceTrend = (rows) => {
  const numericRows = Array.isArray(rows) ? rows.filter(row => !Number.isNaN(toNumber(row.value))) : []
  if (numericRows.length < 2) return '暂无明显趋势'
  const first = toNumber(numericRows[0].value)
  const last = toNumber(numericRows[numericRows.length - 1].value)
  if (Number.isNaN(first) || Number.isNaN(last)) return '暂无明显趋势'
  const delta = last - first
  const base = Math.max(Math.abs(first), 1)
  if (Math.abs(delta) / base < 0.03) return '整体平稳'
  return delta > 0 ? '整体上升' : '整体下降'
}

const countVoiceAnomalies = (analysis) => {
  const rows = Array.isArray(analysis?.data) ? analysis.data : []
  return rows.filter(row => row && (row.anomaly != null || row.outlier != null || String(row.phase || '').toLowerCase() === 'anomaly')).length
}

const replaceVoiceTemplate = (template, context) => String(template || '').replace(/\{(\w+)\}/g, (_, key) => {
  const value = context[key]
  return value == null || value === '' ? '' : String(value)
})

const buildVoiceConclusion = (analysis) => {
  if (!analysis) return ''
  const voiceSummary = resolveVoiceSummaryConfig(analysis)
  if (!voiceSummary.enabled) return ''
  if (!Array.isArray(analysis.data) || !analysis.data.length) {
    return normalizeVoiceSentence(analysis.message || '本次查询已完成，但暂无可播报的数据结果。')
  }

  const chartType = String(analysis.chartType || '').toLowerCase()
  const chartTypeName = chartTypeVoiceName(chartType)
  const dimension = voiceFriendlyLabel(analysis.fieldMapping?.dimension, analysis.fieldMapping?.dimensionKey, '维度')
  const metric = voiceFriendlyLabel(analysis.fieldMapping?.metric, analysis.fieldMapping?.metricKey, analysis.forecastMeta?.metricField, '指标')
  const rows = extractVoiceNumericRows(analysis)
  const sortedByValue = [...rows]
    .filter(item => !Number.isNaN(toNumber(item.value)))
    .sort((left, right) => toNumber(right.value) - toNumber(left.value))
  const maxItem = sortedByValue[0] || {}
  const minItem = sortedByValue[sortedByValue.length - 1] || {}
  const context = {
    ruleName: voiceSummary.ruleName || analysis.chartRuleName || 'AI 推荐规则',
    chartTypeName,
    dimension,
    metric,
    count: chartType === 'table'
      ? (Array.isArray(analysis.tableRows) ? analysis.tableRows.length : analysis.data.length)
      : analysis.data.length,
    maxName: String(maxItem.name || '暂无').trim(),
    maxValue: formatSpeechNumber(maxItem.value),
    minName: String(minItem.name || '暂无').trim(),
    minValue: formatSpeechNumber(minItem.value),
    trend: detectVoiceTrend(rows),
    anomalyCount: countVoiceAnomalies(analysis)
  }
  const chartTemplate = Object.prototype.hasOwnProperty.call(voiceSummary.chartTemplates || {}, chartType)
    ? voiceSummary.chartTemplates[chartType]
    : voiceSummary.summaryTemplate
  if (chartTemplate) {
    return normalizeVoiceSentence(replaceVoiceTemplate(chartTemplate, context))
  }
  const defaultTemplates = {
    title: '查询完成，已生成{chartTypeName}。',
    metric: '当前按{dimension}分析{metric}，共{count}项结果。',
    max: '最大值为{maxName}，数值{maxValue}。',
    min: '最小值为{minName}，数值{minValue}。',
    trend: '整体趋势为{trend}。',
    anomaly: '检测到{anomalyCount}个异常点。'
  }
  const parts = voiceSummary.order
    .map(key => voiceSummary.templates?.[key] || defaultTemplates[key])
    .filter(Boolean)
    .map(template => replaceVoiceTemplate(template, context))
  return normalizeVoiceSentence(parts.join(''))
}

const stopVoicePlayback = () => {
  stopSpeaking()
}

const toggleVoicePlayback = async () => {
  if (speaking.value && speechPaused.value) {
    await resumeSpeaking()
    return
  }
  if (speaking.value) {
    await pauseSpeaking()
  }
}

const speakLatestAnalysisConclusion = async (analysis = lastAnalysis.value) => {
  const content = buildVoiceConclusion(analysis)
  if (!content) {
    if (analysis?.voiceSummary?.enabled === false) {
      ElMessage.warning('当前规则已关闭语音摘要')
    } else {
      ElMessage.warning('暂无可播报的分析结果')
    }
    return
  }
  try {
    await speakText(content)
  } catch (error) {
    if (error?.message && error.message !== '语音播报已中断') {
      ElMessage.error(error.message || '语音播报失败')
    }
  }
}

const prefetchLatestAnalysisConclusion = (analysis = lastAnalysis.value) => {
  const content = buildVoiceConclusion(analysis)
  if (!content) return
  prefetchSpeechText(content)
}

const prefetchChatBubbleSpeech = (content) => {
  const text = String(content || '').trim()
  if (!text) return
  prefetchSpeechText(text)
}

const speakChatBubble = async (msg) => {
  const content = String(msg?.content || '').trim()
  if (!content) {
    ElMessage.warning('当前气泡没有可播报内容')
    return
  }
  try {
    await speakText(content)
  } catch (error) {
    if (error?.message && error.message !== '语音播报已中断') {
      ElMessage.error(error.message || '语音播报失败')
    }
  }
}

const stopVoiceQuestionInput = () => {
  stopListening()
}

const startVoiceQuestionInput = async () => {
  if (loading.value || isStreaming.value) {
    ElMessage.warning('当前正在生成分析结果，请稍后再试语音输入')
    return
  }
  try {
    await startListening({
      onStart: () => {
        ElMessage.success('已开始语音听写，请直接说出查询问题')
      },
      onPartial: (committed, interim) => {
        question.value = normalizeVoiceQuestion([committed, interim].filter(Boolean).join(' '))
      },
      onFinal: async (committed) => {
        const normalized = normalizeVoiceQuestion(committed)
        question.value = normalized
        if (autoSendAfterRecognize.value && normalized) {
          await sendQuestion({ questionText: normalized })
        }
      },
      onError: (message) => {
        if (message && message !== '语音识别已中止') {
          ElMessage.error(message)
        }
      }
    })
  } catch (error) {
    ElMessage.error(error.message || '语音识别启动失败')
  }
}

const stopQuestionGeneration = () => {
  if (!loading.value && !isStreaming.value) {
    return
  }
  stopRequested.value = true
  if (streamAbortController.value) {
    streamAbortController.value.abort()
    streamAbortController.value = null
  }
  isStreaming.value = false
}

const sendQuestion = async (options = {}) => {
  const requestId = activeChatRequestId.value + 1
  activeChatRequestId.value = requestId
  const isCurrentRequest = () => activeChatRequestId.value === requestId
  const userQuestion = String(options?.questionText ?? question.value ?? '').trim()
  const requestedTableName = String(options?.tableName || '').trim()
  const queryTableName = requestedTableName || selectedTableName.value
  const isRegenerate = Boolean(options?.regenerate)
  const branchParentTurnId = String(options?.parentTurnId || activeBranchParentTurnId.value || '').trim()
  const selectedModelPayload = chatModelPayload.value

  if (!userQuestion) return
  if (!queryTableName && !shouldUseBusinessModelAgent(userQuestion)) {
    ElMessage.warning('请先选择数据表再发起分析')
    return
  }
  if (requestedTableName && !isAccessibleTable(requestedTableName)) {
    ElMessage.warning('指定的数据源不存在或无权限访问')
    return
  }
  if (selectedTableName.value !== queryTableName) {
    selectedTableName.value = queryTableName
  }
  const semanticDraft = null
  const businessModelIntent = false && shouldUseBusinessModelAgent(userQuestion) && !isRegenerate
  const dashboardPinIntent = isDashboardPinQuestion(userQuestion) && !isRegenerate
  const compositeDashboardPinIntent = isCompositeDashboardPinQuestion(userQuestion) && !isRegenerate
  stopRequested.value = false
  messages.value.push({
    role: 'user',
    content: isRegenerate ? `${userQuestion}\n（重新生成）` : userQuestion
    , parentTurnId: branchParentTurnId || null
    , isFollowUp: Boolean(branchParentTurnId)
    , followUpMeta: branchParentTurnId
      ? {
          parentTurnId: branchParentTurnId,
          preview: String(activeBranchParentTurnMeta.value?.preview || '').trim(),
          turnNo: activeBranchParentTurnMeta.value?.turnNo ?? null,
          source: String(activeBranchParentTurnMeta.value?.source || '').trim() || 'message',
          role: String(activeBranchParentTurnMeta.value?.role || '').trim() || 'system',
          tableName: String(activeBranchParentTurnMeta.value?.tableName || queryTableName || '').trim()
        }
      : null
  })
  question.value = ''
  loading.value = true

  const streamMessageIndex = messages.value.length
  messages.value.push({ role: 'system', content: '正在分析中...', sql: '', thinkingLogs: [], thinkingCollapsed: true })
  const thinkingLogs = []
  const seenThinkingSet = new Set()

  const updateStreamMessage = (patch) => {
    if (!isCurrentRequest()) return
    const current = messages.value[streamMessageIndex] || { role: 'system', content: '', sql: '' }
    messages.value.splice(streamMessageIndex, 1, {
      ...current,
      ...patch
    })
  }

  if (businessModelIntent) {
    try {
      isStreaming.value = true
      const seenBusinessThinking = new Set()
      const pushBusinessThinking = (payload) => {
        const title = String(payload?.title || '').trim()
        const detail = String(payload?.detail || '').trim()
        const line = [title, detail].filter(Boolean).join('：')
        if (!line || seenBusinessThinking.has(line)) return
        seenBusinessThinking.add(line)
        thinkingLogs.push(line)
        updateStreamMessage({
          content: `业务模型处理中（${thinkingLogs.length}步）· 当前：${line}`,
          sql: '',
          thinkingLogs: thinkingLogs.slice(0, CHAT_THINKING_LOG_LIMIT),
          thinkingCollapsed: true
        })
        nextTick(() => {
          const chatDom = document.getElementById('chatHistory')
          if (chatDom) chatDom.scrollTop = chatDom.scrollHeight
        })
      }
      let agentResult = null
      try {
        agentResult = await streamBusinessModelAgentQuestion({
          question: userQuestion,
          tableName: queryTableName,
          onThinking: pushBusinessThinking
        })
      } catch (streamError) {
        isStreaming.value = false
        const fallbackLine = `业务模型流式通道失败，已切换普通模式：${streamError.message || '未知错误'}`
        thinkingLogs.push(fallbackLine)
        updateStreamMessage({
          content: fallbackLine,
          sql: '',
          thinkingLogs: thinkingLogs.slice(0, CHAT_THINKING_LOG_LIMIT),
          thinkingCollapsed: true
        })
        agentResult = await handleBusinessModelAgentQuestion({
          question: userQuestion,
          tableName: queryTableName,
          semanticDraft
        })
      }
      if (agentResult?.handled) {
        syncBusinessModelContext(agentResult)
        if (agentResult.refreshBusinessModels) {
          await loadBusinessModels()
        }
        if (agentResult.openBusinessDictionary) {
          const focusId = agentResult.focusModelId ?? agentResult.modelId ?? agentResult.appliedModelId ?? activeBusinessModelId.value
          if (focusId != null && focusId !== '') {
            businessDictionaryFocusModelId.value = focusId
            businessDictionaryPanelVisible.value = true
          }
        }
        updateStreamMessage({
          content: agentResult.message || '业务模型处理完成',
          sql: '',
          fieldBindingResults: normalizeFieldBindingResults(agentResult.fieldBindingResults),
          fieldBindingTitle: resolveFieldBindingCardTitle(
            userQuestion,
            agentResult.intent,
            normalizeFieldBindingResults(agentResult.fieldBindingResults)
          ),
          thinkingLogs: [
            ...thinkingLogs,
            `业务模型智能体意图：${agentResult.intent || 'UNKNOWN'}`,
            ...(Array.isArray(agentResult.reasoning) ? agentResult.reasoning.filter(Boolean).slice(0, 4) : []),
            agentResult.message || '业务模型处理完成'
          ].filter(Boolean).slice(0, 10),
          thinkingCollapsed: true
        })
        loading.value = false
        isStreaming.value = false
        streamAbortController.value = null
        stopRequested.value = false
        return
      }
    } catch (error) {
      updateStreamMessage({
        content: `业务模型处理失败：${error.message || '未知错误'}`,
        sql: '',
        fieldBindingResults: [],
        thinkingLogs: ['业务模型智能体执行失败', '请检查输入语义或后端 AI 服务状态'],
        thinkingCollapsed: true
      })
      loading.value = false
      isStreaming.value = false
      streamAbortController.value = null
      stopRequested.value = false
      return
    }
  }

  const applyAnalysisResult = (data) => {
    if (stopRequested.value || !isCurrentRequest()) return
    const sourceTableName = String(data?.tableName || queryTableName || '').trim()
    const fallbackTag = data.fallbackUsed ? '（规则兜底）' : ''
    const resultThinkingLogs = normalizeThinkingLogs(data?.thinkingLogs || data?.reasoningReplaySteps || [])
    const compactLogs = (resultThinkingLogs.length ? resultThinkingLogs : thinkingLogs).slice(0, CHAT_THINKING_LOG_LIMIT)
    const fieldBindingResults = normalizeFieldBindingResults(data?.fieldBindingResults)
    const analysisSnapshot = {
      ...data,
      recommendedChartType: data.chartType,
      sourceQuestion: userQuestion,
      sourceSql: data.sql,
      sourceTableName,
      parentTurnId: branchParentTurnId || null
    }
    lastAnalysis.value = analysisSnapshot
    currentChartType.value = data.chartType
    applyAnalysisSortMode(analysisSnapshot, 'name')
    updateStreamMessage({
      content: `${data.message}${fallbackTag}`,
      sql: data.sql,
      thinkingLogs: compactLogs,
      thinkingCollapsed: true,
      fieldBindingResults,
      fieldBindingTitle: resolveFieldBindingCardTitle(
        userQuestion,
        data?.intent || data?.smartIntent || data?.responseType,
        fieldBindingResults
      ),
      sourceQuestion: userQuestion,
      sourceTableName,
      queryHistoryId: data?.queryHistoryId == null ? null : String(data.queryHistoryId),
      turnId: data?.assistantTurnId == null ? null : String(data.assistantTurnId),
      parentTurnId: branchParentTurnId || null,
      artifactId: data?.artifactId == null ? null : String(data.artifactId),
      artifactIds: Array.isArray(data?.artifactIds) ? data.artifactIds.map(item => String(item || '')).filter(Boolean) : [],
      actionPlan: data?.actionPlan || null,
      stepResults: Array.isArray(data?.stepResults) ? data.stepResults : [],
      multiStepSummary: data?.multiStepSummary || null,
      alertRuleDraft: data?.alertRuleDraft || null,
      analysisSnapshot: Array.isArray(data?.data) && data.data.length ? analysisSnapshot : null,
      clickableChart: Array.isArray(data?.data) && data.data.length
    })

    nextTick(() => {
      const chatDom = document.getElementById('chatHistory')
      if (chatDom) chatDom.scrollTop = chatDom.scrollHeight
    })

    if (data.data?.length) {
      renderChart(data.data, data.chartType)
    } else {
      ensureChatChartInstance()?.clear()
      ElMessage.warning('查询成功，但没有符合条件的数据')
    }

    if (data?.handled && String(data?.responseType || data?.smartIntent || '').startsWith('BUSINESS_MODEL')) {
      syncBusinessModelContext(data)
      if (data.refreshBusinessModels) {
        loadBusinessModels()
      }
      if (data.openBusinessDictionary) {
        const focusId = data.focusModelId ?? data.modelId ?? data.appliedModelId ?? activeBusinessModelId.value
        if (focusId != null && focusId !== '') {
          businessDictionaryFocusModelId.value = focusId
          businessDictionaryPanelVisible.value = true
        }
      }
    }

    if (activeModule.value === 'chat') {
      nextTick(() => {
        prefetchLatestAnalysisConclusion(data)
        prefetchChatBubbleSpeech(`${data.message || ''}${fallbackTag}`)
      })
    }

    if (activeModule.value === 'chat' && autoSpeakConclusion.value) {
      nextTick(() => {
        speakLatestAnalysisConclusion(data)
      })
    }
  }

  const streamWithAuth = async () => {
    const token = authToken.value || localStorage.getItem('token') || ''
    isStreaming.value = true
    if (!token) {
      throw new Error('登录状态缺失，请重新登录')
    }

    const controller = new AbortController()
    streamAbortController.value = controller
    if (stopRequested.value) {
      controller.abort()
    }
    try {
      const params = new URLSearchParams({ question: userQuestion, tableName: queryTableName })
      if (activeChatSessionId.value) params.set('conversationId', activeChatSessionId.value)
      if (branchParentTurnId) params.set('parentTurnId', branchParentTurnId)
      if (selectedTableName.value) params.set('selectedTableName', selectedTableName.value)
      if (selectedModelPayload.modelId) params.set('modelId', selectedModelPayload.modelId)
      if (selectedModelPayload.modelName) params.set('modelName', selectedModelPayload.modelName)
      if (selectedModelPayload.modelCategory) params.set('modelCategory', selectedModelPayload.modelCategory)
      if (activeBusinessModelId.value != null) params.set('activeBusinessModelId', String(activeBusinessModelId.value))
      if (lastCreatedBusinessModelId.value != null) params.set('lastCreatedBusinessModelId', String(lastCreatedBusinessModelId.value))
      if (lastAppliedBusinessModelId.value != null) params.set('lastAppliedBusinessModelId', String(lastAppliedBusinessModelId.value))
      if (dashboardPinIntent && !compositeDashboardPinIntent) {
        const pinSource = buildPinChartPayload(lastAnalysis.value)
        if (pinSource?.chartId) params.set('pinChartId', String(pinSource.chartId))
        if (pinSource?.artifactId) params.set('pinArtifactId', String(pinSource.artifactId))
        if (pinSource?.turnId) params.set('pinTurnId', String(pinSource.turnId))
        if (pinSource?.title) params.set('pinTitle', String(pinSource.title).slice(0, 80))
        const pinSourceQuestion = String(lastAnalysis.value?.sourceQuestion || pinSource?.title || '').trim()
        if (pinSourceQuestion) params.set('pinSourceQuestion', pinSourceQuestion.slice(0, 160))
        const pinTableName = String(pinSource?.tableName || lastAnalysis.value?.sourceTableName || lastAnalysis.value?.tableName || '').trim()
        if (pinTableName) params.set('pinTableName', pinTableName)
      }
      const response = await fetch(`${API_BASE}/api/chat/ask-stream?${params.toString()}`, {
        method: 'GET',
        headers: {
          Accept: 'text/event-stream',
          Authorization: `Bearer ${token}`
        },
        cache: 'no-store',
        signal: controller.signal
      })

      if (!response.ok || !response.body) {
        throw new Error(response.status === 401 ? '登录已失效，请重新登录' : `流式请求失败(${response.status})`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      const consumeEvent = (rawEvent) => {
        const parsed = processEvent(rawEvent)
        if (!parsed) return null
        if (parsed.eventName === 'thinking') {
          const step = parsed.payload
          const line = `${step.title || step.stage || '处理中'}：${step.detail || ''}`
          const normalizedLine = line.trim()
          if (!seenThinkingSet.has(normalizedLine)) {
            seenThinkingSet.add(normalizedLine)
            thinkingLogs.push(normalizedLine)
          }
          const latestLine = thinkingLogs[thinkingLogs.length - 1] || '处理中'
          updateStreamMessage({
            content: `分析中（${thinkingLogs.length}步）· 当前：${latestLine}`,
            thinkingLogs: thinkingLogs.slice(0, CHAT_THINKING_LOG_LIMIT),
            thinkingCollapsed: true
          })
          nextTick(() => {
            const chatDom = document.getElementById('chatHistory')
            if (chatDom) chatDom.scrollTop = chatDom.scrollHeight
          })
          return null
        }
        if (parsed.eventName === 'result') {
          return parsed.payload
        }
        if (parsed.eventName === 'cancelled') {
          const message = parsed.payload?.message || '用户已手动停止生成'
          const cancelError = new Error(message)
          cancelError.name = 'AbortError'
          throw cancelError
        }
        if (parsed.eventName === 'error') {
          throw new Error(parsed.payload?.message || '流式分析失败')
        }
        return null
      }

      const processEvent = (chunk) => {
        const lines = chunk.split('\n')
        let eventName = 'message'
        const dataLines = []
        for (const line of lines) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        const dataText = dataLines.join('\n').trim()
        if (!dataText) return null
        let payload
        try { payload = JSON.parse(dataText) } catch { payload = { message: dataText } }
        return { eventName, payload }
      }

      const parseBufferedEvents = () => {
        const normalized = buffer.replace(/\r\n/g, '\n')
        const chunks = normalized.split('\n\n')
        if (chunks.length <= 1) {
          buffer = normalized
          return []
        }
        buffer = chunks.pop() || ''
        return chunks
      }

      while (true) {
        if (stopRequested.value || !isCurrentRequest()) {
          const stopError = new Error('用户已手动停止生成')
          stopError.name = 'AbortError'
          throw stopError
        }
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        const rawEvents = parseBufferedEvents()
        for (const rawEvent of rawEvents) {
          const result = consumeEvent(rawEvent)
          if (result) return result
        }
      }

      if (buffer.trim()) {
        const result = consumeEvent(buffer.trim())
        if (result) return result
      }

      if (stopRequested.value || !isCurrentRequest()) {
        const stopError = new Error('用户已手动停止生成')
        stopError.name = 'AbortError'
        throw stopError
      }
      throw new Error('流式连接已结束，但未收到结果')
    } finally {
      if (streamAbortController.value === controller) {
        streamAbortController.value = null
      }
      isStreaming.value = false
    }
  }

  try {
    let data
    try {
      data = await streamWithAuth()
    } catch (streamError) {
      if (!isCurrentRequest()) {
        return
      }
      if (stopRequested.value || isAbortLikeError(streamError)) {
        updateStreamMessage({ content: '已手动停止本次生成。' })
        return
      }
      if (dashboardPinIntent && !compositeDashboardPinIntent) {
        const guardLine = '副作用保护：看板写入类指令已停止普通接口重放，避免重复钉入'
        if (!seenThinkingSet.has(guardLine)) {
          seenThinkingSet.add(guardLine)
          thinkingLogs.push(guardLine)
        }
        updateStreamMessage({
          content: `看板钉入的流式结果未完整返回（${streamError.message || '未知错误'}），已停止自动重试以避免重复钉入。请刷新看板确认结果；重复提交也会自动去重。`,
          sql: '',
          thinkingLogs: thinkingLogs.slice(0, CHAT_THINKING_LOG_LIMIT),
          thinkingCollapsed: true
        })
        return
      }
      let fallbackData
      const fallbackController = new AbortController()
      streamAbortController.value = fallbackController
      try {
        fallbackData = unwrap(await axios.post(`${API_BASE}/api/chat/ask-smart`, {
          question: userQuestion,
          tableName: queryTableName,
          conversationId: activeChatSessionId.value || undefined,
          parentTurnId: branchParentTurnId || undefined,
          selectedTableName: selectedTableName.value || undefined,
          activeBusinessModelId: activeBusinessModelId.value ?? undefined,
          lastCreatedBusinessModelId: lastCreatedBusinessModelId.value ?? undefined,
          lastAppliedBusinessModelId: lastAppliedBusinessModelId.value ?? undefined,
          ...selectedModelPayload
        }, {
          signal: fallbackController.signal
        }))
      } catch (enhancedError) {
        if (stopRequested.value || isAbortLikeError(enhancedError)) {
          updateStreamMessage({ content: '已手动停止本次生成。' })
          return
        }
        if (enhancedError?.response?.status !== 404) {
          throw enhancedError
        }
        fallbackData = unwrap(await axios.post(`${API_BASE}/api/chat/ask`, {
          question: userQuestion,
          tableName: queryTableName,
          conversationId: activeChatSessionId.value || undefined,
          parentTurnId: branchParentTurnId || undefined,
          ...selectedModelPayload
        }, {
          signal: fallbackController.signal
        }))
        fallbackData.fallbackUsed = String(fallbackData.engine || '').startsWith('java-fallback')
      } finally {
        if (streamAbortController.value === fallbackController) {
          streamAbortController.value = null
        }
      }
      if (!isCurrentRequest() || stopRequested.value) {
        updateStreamMessage({ content: '已手动停止本次生成。' })
        return
      }
      data = fallbackData
      if (!shouldUseBusinessModelAgent(userQuestion)) {
        ElMessage.warning(`流式通道不可用（${streamError.message}），已自动切换普通模式`)
      }
    }

    if (!isCurrentRequest()) {
      return
    }
    if (!compositeDashboardPinIntent && isDashboardPinResult(data, userQuestion)) {
      await applyDashboardPinResult(data, userQuestion, updateStreamMessage, thinkingLogs)
      if (data?.conversationId) {
        activeChatSessionId.value = String(data.conversationId)
      }
      clearActiveBranchParent()
      await loadRecentChatQueries()
      await loadChatSessions()

      if (isAdminUser.value) {
        await loadAuditLogs()
      }
      return
    }
    applyAnalysisResult(data)
    await autoPinDeferredDashboard(data, userQuestion, updateStreamMessage, thinkingLogs)
    if (data?.conversationId) {
      activeChatSessionId.value = String(data.conversationId)
    }
    clearActiveBranchParent()
    await loadRecentChatQueries()
    await loadChatSessions()

    if (isAdminUser.value) {
      await loadAuditLogs()
    }
  } catch (error) {
    if (!isCurrentRequest()) {
      return
    }
    updateStreamMessage({ content: `分析失败：${error.message}` })
    if (isAdminUser.value) {
      await loadAuditLogs()
    }
  } finally {
    if (!isCurrentRequest()) {
      return
    }
    loading.value = false
    isStreaming.value = false
    streamAbortController.value = null
    stopRequested.value = false
  }
}

const loadAuditLogs = async () => {
  if (!isAdminUser.value) {
    auditLogs.value = []
    return
  }
  const data = unwrap(await axios.get(`${API_BASE}/api/audit/sql-logs`, {
    params: {
      riskLevel: auditRiskLevel.value || undefined,
      executeStatus: auditExecuteStatus.value || undefined,
      userId: auditAdvancedFilters.value.userId || undefined,
      tableName: auditAdvancedFilters.value.tableName || undefined,
      keyword: auditAdvancedFilters.value.keyword || undefined,
      cacheHit: auditAdvancedFilters.value.cacheHit === '' ? undefined : auditAdvancedFilters.value.cacheHit,
      slowQuery: auditAdvancedFilters.value.slowQuery === '' ? undefined : auditAdvancedFilters.value.slowQuery,
      limit: 80
    }
  }))
  auditLogs.value = data
  const [stats, cacheOverview, cacheAudits, rowPolicies] = await Promise.all([
    axios.get(`${API_BASE}/api/audit/stats`).then(unwrap),
    axios.get(`${API_BASE}/api/audit/cache/overview`).then(unwrap),
    axios.get(`${API_BASE}/api/audit/cache/audits`, { params: { limit: 50 } }).then(unwrap),
    axios.get(`${API_BASE}/api/audit/data-row-policies`).then(unwrap)
  ])
  auditStats.value = stats
  auditCacheOverview.value = cacheOverview
  auditCacheAudits.value = cacheAudits
  auditRowPolicies.value = rowPolicies
}

const loadAuditRules = async () => {
  auditRules.value = unwrap(await axios.get(`${API_BASE}/api/audit/rules`))
  sensitiveRules.value = unwrap(await axios.get(`${API_BASE}/api/audit/sensitive-rules`))
}

const saveSensitiveRule = async () => {
  const keyword = String(sensitiveRuleForm.value.fieldKeyword || '').trim()
  if (!keyword) {
    ElMessage.warning('请填写敏感字段关键词')
    return
  }
  await axios.post(`${API_BASE}/api/audit/sensitive-rules`, sensitiveRuleForm.value).then(unwrap)
  sensitiveRuleForm.value = { fieldKeyword: '', maskType: 'MIDDLE', accessAction: 'MASK', enabled: true }
  ElMessage.success('敏感字段规则已保存')
  await loadAuditRules()
}

const updateSensitiveRuleStatus = async (row) => {
  await axios.post(`${API_BASE}/api/audit/sensitive-rules/${row.id}/status`, {
    enabled: Boolean(row.enabled)
  }).then(unwrap)
  ElMessage.success(row.enabled ? '敏感规则已启用' : '敏感规则已停用')
}

const deleteSensitiveRule = async (row) => {
  await axios.post(`${API_BASE}/api/audit/sensitive-rules/${row.id}/delete`).then(unwrap)
  ElMessage.success('敏感规则已删除')
  await loadAuditRules()
}

const updateAuditRuleStatus = async (row) => {
  await axios.post(`${API_BASE}/api/audit/rules/${row.ruleCode}/status`, {
    enabled: Boolean(row.enabled)
  }).then(unwrap)
  ElMessage.success(row.enabled ? '审计规则已启用' : '审计规则已停用')
}

const updateAuditRuleConfig = async (row) => {
  await axios.post(`${API_BASE}/api/audit/rules/${row.ruleCode}/config`, {
    enabled: Boolean(row.enabled),
    thresholdValue: row.thresholdValue
  }).then(unwrap)
  ElMessage.success('审计规则配置已保存')
  await loadAuditRules()
}

const submitManualAudit = async () => {
  manualAuditResult.value = unwrap(await axios.post(`${API_BASE}/api/audit/submit`, {
    sql: manualAuditSql.value,
    tableName: selectedTableName.value,
    question: '管理员手工提交 SQL 审计'
  }))
  ElMessage.success('SQL 已提交审计')
  await loadAuditLogs()
}

const reviewAuditLog = async (row, reviewStatus = 'RESOLVED') => {
  await axios.post(`${API_BASE}/api/audit/sql-logs/${row.id}/review`, {
    reviewStatus,
    reviewNote: reviewStatus === 'RESOLVED' ? '管理员已复核处置' : '管理员已复核'
  }).then(unwrap)
  ElMessage.success('拦截记录已更新')
  await loadAuditLogs()
}

const quarantineAuditCache = async (row) => {
  await ElMessageBox.confirm(`确认隔离缓存 ${row.cacheKey}？`, '隔离缓存', {
    confirmButtonText: '隔离',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await axios.post(`${API_BASE}/api/audit/cache/${encodeURIComponent(row.cacheKey)}/quarantine`, {
    reason: '管理员从 SQL 审计中心隔离缓存'
  }).then(unwrap)
  ElMessage.success('缓存已隔离')
  await loadAuditLogs()
}

const saveAuditRowPolicy = async () => {
  if (!auditRowPolicyForm.value.tableName || !auditRowPolicyForm.value.principalId || !auditRowPolicyForm.value.filterExpression) {
    ElMessage.warning('请填写表名、授权对象和过滤条件')
    return
  }
  await axios.post(`${API_BASE}/api/audit/data-row-policies`, auditRowPolicyForm.value).then(unwrap)
  auditRowPolicyForm.value = {
    tableName: '',
    principalType: 'USER',
    principalId: '',
    filterExpression: '',
    enabled: true
  }
  ElMessage.success('上传表行级策略已保存')
  await loadAuditLogs()
}

const deleteAuditRowPolicy = async (row) => {
  await axios.post(`${API_BASE}/api/audit/data-row-policies/${row.id}/delete`).then(unwrap)
  ElMessage.success('上传表行级策略已删除')
  await loadAuditLogs()
}

const exportSqlLogs = async () => {
  try {
    const response = await axios.get(`${API_BASE}/api/audit/sql-logs/export`, {
      params: {
        riskLevel: auditRiskLevel.value || undefined,
        executeStatus: auditExecuteStatus.value || undefined,
        limit: 500
      },
      responseType: 'blob'
    })
    const contentType = String(response.headers?.['content-type'] || '')
    if (contentType.includes('application/json')) {
      const text = await response.data.text()
      const body = JSON.parse(text)
      throw new Error(body.message || '导出失败')
    }
    const blob = new Blob([response.data], {
      type: contentType || 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = 'sql-audit-logs.xlsx'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(objectUrl)
    ElMessage.success('Excel 导出完成')
  } catch (error) {
    ElMessage.error(error.message || 'Excel 导出失败')
  }
}

const loadGraphOverview = async () => {
  graphOverview.value = unwrap(await axios.get(`${API_BASE}/api/knowledge-graph/overview`))
}

const rebuildGraph = async () => {
  graphLoading.value = true
  try {
    const result = unwrap(await axios.post(`${API_BASE}/api/knowledge-graph/rebuild`))
    ElMessage.success(`图谱重建完成：${result.nodeCount} 个节点，${result.edgeCount} 条关系`)
    await Promise.all([loadGraphOverview(), searchGraph()])
  } catch (error) {
    ElMessage.error(error.message || '图谱重建失败')
  } finally {
    graphLoading.value = false
  }
}

const searchGraph = async () => {
  try {
    graphSearchResult.value = unwrap(await axios.get(`${API_BASE}/api/knowledge-graph/multi-hop`, {
      params: {
        keyword: graphSearchKeyword.value || undefined,
        tableName: selectedTableName.value || undefined,
        depth: 3,
        limit: 20
      }
    }))
  } catch (error) {
    graphSearchResult.value = { nodes: [], edges: [], ragContext: [] }
    ElMessage.error(error.message || '图谱检索失败')
  }
}

const onKnowledgeDocChange = async (file, fileList = []) => {
  knowledgeDocUploadFiles.value = fileList
  knowledgeDocFiles.value = fileList.map(item => item.raw || item).filter(Boolean)
  knowledgeDocFile.value = knowledgeDocFiles.value[0] || file?.raw || null
  if (knowledgeDocFiles.value.length && !knowledgeDocUploading.value) {
    await uploadKnowledgeDoc()
  }
}

const onKnowledgeDocRemove = (file, fileList = []) => {
  knowledgeDocUploadFiles.value = fileList
  knowledgeDocFiles.value = fileList.map(item => item.raw || item).filter(Boolean)
  knowledgeDocFile.value = knowledgeDocFiles.value[0] || null
}

const loadKnowledgeDocs = async () => {
  knowledgeDocs.value = unwrap(await axios.get(`${API_BASE}/api/knowledge/docs`))
}

const uploadKnowledgeDoc = async () => {
  const files = knowledgeDocFiles.value.length ? knowledgeDocFiles.value : (knowledgeDocFile.value ? [knowledgeDocFile.value] : [])
  if (!files.length) return ElMessage.warning('请选择 .txt / .md / .pdf / .docx 知识文档或行业研报')
  knowledgeDocUploading.value = true
  try {
    for (const file of files) {
      const formData = new FormData()
      formData.append('file', file)
      await axios.post(`${API_BASE}/api/knowledge/docs/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      }).then(unwrap)
    }
    ElMessage.success(`已上传 ${files.length} 个知识文档并切片`)
    knowledgeDocFile.value = null
    knowledgeDocFiles.value = []
    knowledgeDocUploadFiles.value = []
    await loadKnowledgeDocs()
  } catch (error) {
    ElMessage.error(error.message || '知识文档上传失败')
  } finally {
    knowledgeDocUploading.value = false
  }
}

const indexKnowledgeDoc = async (doc) => {
  await axios.post(`${API_BASE}/api/knowledge/docs/${doc.id}/index`).then(unwrap)
  ElMessage.success('知识文档索引已刷新')
  await loadKnowledgeDocs()
}

const deleteKnowledgeDoc = async (doc) => {
  await axios.post(`${API_BASE}/api/knowledge/docs/${doc.id}/delete`).then(unwrap)
  ElMessage.success('知识文档已删除')
  await loadKnowledgeDocs()
}

const riskTagType = (riskLevel) => {
  if (riskLevel === 'SAFE') return 'success'
  if (riskLevel === 'WARN') return 'warning'
  if (riskLevel === 'BLOCKED') return 'danger'
  return 'info'
}

const statusTagType = (status) => {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'BLOCKED') return 'warning'
  return 'info'
}

const toNumber = (value) => {
  if (value === null || value === undefined) return Number.NaN
  if (typeof value === 'number') return value
  const text = String(value).replace(/,/g, '').trim()
  if (!text) return Number.NaN
  const num = Number(text)
  return Number.isFinite(num) ? num : Number.NaN
}

const parseDateValue = (value) => {
  const text = String(value ?? '').trim()
  if (!text) return null
  const cleaned = text.replace('T', ' ').split(' ')[0]
  const normalized = cleaned.replace(/年|月/g, '-').replace(/日/g, '').replace(/\//g, '-')
  let match = normalized.match(/^(\d{4})-(\d{1,2})(?:-(\d{1,2}))?$/)
  if (!match && /^\d{6,8}$/.test(normalized)) {
    const year = normalized.slice(0, 4)
    const month = normalized.slice(4, 6)
    const day = normalized.length >= 8 ? normalized.slice(6, 8) : '01'
    match = [normalized, year, month, day]
  }
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2] || 1)
  const day = Number(match[3] || 1)
  if (!year || month < 1 || month > 12 || day < 1 || day > 31) return null
  const date = new Date(year, month - 1, day)
  const time = date.getTime()
  return Number.isNaN(time) ? null : time
}

const compareByName = (a, b) => {
  const aName = String(a.name ?? '')
  const bName = String(b.name ?? '')
  const aDate = parseDateValue(aName)
  const bDate = parseDateValue(bName)
  if (aDate !== null && bDate !== null && aDate !== bDate) return aDate - bDate
  const aNum = toNumber(aName)
  const bNum = toNumber(bName)
  if (!Number.isNaN(aNum) && !Number.isNaN(bNum) && aNum !== bNum) return aNum - bNum
  return aName.localeCompare(bName, 'zh-Hans-CN', { numeric: true, sensitivity: 'base' })
}

const compareByValue = (a, b, mode) => {
  const aNum = toNumber(a.value)
  const bNum = toNumber(b.value)
  if (Number.isNaN(aNum) && Number.isNaN(bNum)) return 0
  if (Number.isNaN(aNum)) return 1
  if (Number.isNaN(bNum)) return -1
  if (aNum === bNum) return 0
  return mode === 'asc' ? aNum - bNum : bNum - aNum
}

const normalizeChartSortMode = (value, fallback = 'name') => {
  const mode = String(value || '').trim().toLowerCase()
  return ['name', 'asc', 'desc'].includes(mode) ? mode : fallback
}

const buildChartStyleSnapshot = (source = {}) => {
  const recommendation = source?.chartRecommendation && typeof source.chartRecommendation === 'object'
    ? source.chartRecommendation
    : null
  const optionTemplate = source?.optionTemplate && typeof source.optionTemplate === 'object'
    ? source.optionTemplate
    : null
  const encode = source?.encode && typeof source.encode === 'object'
    ? source.encode
    : null
  const dimensions = Array.isArray(source?.dimensions) ? source.dimensions : null
  const snapshot = {}
  if (optionTemplate) snapshot.optionTemplate = optionTemplate
  if (encode) snapshot.encode = encode
  if (dimensions) snapshot.dimensions = dimensions
  if (recommendation) snapshot.chartRecommendation = recommendation
  for (const key of [
    'chartEngine',
    'chartRuleCode',
    'chartRuleName',
    'chartScenarioType',
    'chartRecommendationStatus',
    'chartRecommendationExplain'
  ]) {
    const value = source?.[key]
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      snapshot[key] = value
    }
  }
  return snapshot
}

const readSnapshotSortMode = (snapshot, fallback = 'name') => {
  const mapping = snapshot?.fieldMapping && typeof snapshot.fieldMapping === 'object' ? snapshot.fieldMapping : {}
  return normalizeChartSortMode(snapshot?.chartSortMode || snapshot?.sortMode || mapping.chartSortMode || mapping.sortMode, fallback)
}

const readSnapshotSortIntent = (snapshot) => {
  const mapping = snapshot?.fieldMapping && typeof snapshot.fieldMapping === 'object' ? snapshot.fieldMapping : {}
  return String(snapshot?.sortIntent || mapping.sortIntent || '').trim()
}

const resolveAnalysisSortMode = (analysis, fallback = chartSortMode.value || 'name') => {
  const mapping = analysis?.fieldMapping && typeof analysis.fieldMapping === 'object' ? analysis.fieldMapping : {}
  return normalizeChartSortMode(
    analysis?.chartSortMode
      || analysis?.sortMode
      || mapping.chartSortMode
      || mapping.sortMode,
    fallback
  )
}

const applyAnalysisSortMode = (analysis, fallback = 'name') => {
  chartSortMode.value = resolveAnalysisSortMode(analysis, fallback)
}

const normalizeChartItem = (item) => {
  if (!item || typeof item !== 'object') {
    return { name: String(item ?? ''), value: 0 }
  }
  const keys = Object.keys(item)
  const nameKey = keys.find(key => ['name', 'label', 'province', 'city', 'category', 'dimension', 'dim_name'].includes(key)) || keys[0]
  const valueKey = keys.find(key => ['value', 'count', 'amount', 'total', 'sales', 'metric', 'metric_value'].includes(key)) || keys[1] || keys[0]
  const nameValue = item.name ?? item.label ?? item.dim_name ?? item[nameKey] ?? ''
  const rawValue = item.value ?? item.metric_value ?? item[valueKey] ?? 0
  const numericValue = toNumber(rawValue)
  return {
    name: String(nameValue ?? ''),
    value: Number.isNaN(numericValue) ? 0 : numericValue
  }
}

const getSortedChartData = (data) => {
  const normalizedData = Array.isArray(data) ? data.map(normalizeChartItem) : []
  const indexed = normalizedData.map((item, index) => ({ item, index }))
  if (chartSortMode.value === 'asc' || chartSortMode.value === 'desc') {
    indexed.sort((a, b) => {
      const byValue = compareByValue(a.item, b.item, chartSortMode.value)
      if (byValue !== 0) return byValue
      const byName = compareByName(a.item, b.item)
      return byName !== 0 ? byName : a.index - b.index
    })
  } else if (chartSortMode.value === 'name') {
    indexed.sort((a, b) => {
      const byName = compareByName(a.item, b.item)
      return byName !== 0 ? byName : a.index - b.index
    })
  }
  return indexed.map(entry => entry.item)
}

const sanitizeFilename = (value) => String(value || 'chart')
  .replace(/[\\/:*?"<>|]/g, '_')
  .replace(/\s+/g, '_')
  .slice(0, 80)
  .replace(/^_+|_+$/g, '') || 'chart'

const buildChartFilename = () => {
  const table = lastAnalysis.value?.tableName || ''
  const dimension = lastAnalysis.value?.fieldMapping?.dimension || ''
  const metric = lastAnalysis.value?.fieldMapping?.metric || ''
  const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19)
  const label = [table, dimension, metric].filter(Boolean).join('_')
  return `${sanitizeFilename(label)}_${stamp}.png`
}

const dataUrlToBlob = (dataUrl) => {
  const [header, data] = dataUrl.split(',')
  if (!header || !data) throw new Error('invalid data url')
  const mime = header.match(/data:(.*?);base64/)?.[1] || 'image/png'
  const binary = atob(data)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return new Blob([bytes], { type: mime })
}

const exportChartAsImage = () => {
  if (isLastAnalysisTable.value) {
    ElMessage.warning('当前结果是表格，请使用表格数据查看')
    return
  }
  const instance = ensureChatChartInstance()
  if (!instance || !lastAnalysis.value?.data?.length) {
    ElMessage.warning('暂无可导出的图表')
    return
  }
  instance.resize()
  const url = instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
  if (!url) {
    ElMessage.error('导出失败，请稍后重试')
    return
  }
  const filename = buildChartFilename()
  try {
    const blob = dataUrlToBlob(url)
    if (window.navigator?.msSaveOrOpenBlob) {
      window.navigator.msSaveOrOpenBlob(blob, filename)
      return
    }
    const link = document.createElement('a')
    const objectUrl = URL.createObjectURL(blob)
    link.href = objectUrl
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(objectUrl)
  } catch (error) {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  }
}

watch(activeModule, (nextModule) => {
  if (nextModule !== 'chat') {
    stopVoiceQuestionInput()
    stopVoicePlayback()
    clearTranscript()
    voicePanelVisible.value = false
  }
})

const renderChart = (data, type) => {
  if (String(type || '').toLowerCase() === 'table') {
    chartRenderVersion += 1
    if (chartInstance) {
      chartInstance.clear()
    }
    chartAnimationMeta.value = null
    return
  }
  const template = lastAnalysis.value?.optionTemplate || {}
  applyChatChartContainerLayout(template)
  resizeChatChartAfterLayout()
  const instance = ensureChatChartInstance()
  if (!instance) return
  const rawRows = Array.isArray(data) ? data : []
  const normalizedData = getSortedChartData(data)
  const xAxisData = normalizedData.map(item => item.name)
  const seriesData = normalizedData.map(item => Number(item.value ?? 0))
  let option = {}

  if (type === 'line' && hasForecastSeriesRows(rawRows)) {
    const prediction = template?.prediction || {}
    option = buildForecastChartOption(rawRows, {
      metricLabel: lastAnalysis.value?.fieldMapping?.metric || lastAnalysis.value?.forecastMeta?.metricField || '预测值',
      confidenceLabel: prediction.confidenceLabel || lastAnalysis.value?.forecastMeta?.confidence || '95%',
      legendConfig: prediction.legendConfig
    })
  } else if (type === 'bar' || type === 'line') {
    const shouldUseZoom = normalizedData.length > 12
    option = {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' }
      },
      grid: {
        left: 64,
        right: 18,
        top: 18,
        bottom: shouldUseZoom ? 78 : 48,
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: xAxisData,
        axisLabel: {
          interval: 0,
          rotate: xAxisData.length > 8 ? 35 : 20,
          hideOverlap: true,
          formatter: (value) => {
            const text = String(value ?? '')
            return text.length > 10 ? `${text.slice(0, 10)}…` : text
          }
        }
      },
      yAxis: {
        type: 'value',
        axisLabel: {
          formatter: (value) => {
            const num = Number(value)
            if (Number.isNaN(num)) return value
            if (Math.abs(num) >= 100000000) return `${(num / 100000000).toFixed(1)}亿`
            if (Math.abs(num) >= 10000) return `${(num / 10000).toFixed(1)}万`
            return `${num}`
          }
        },
        splitLine: { lineStyle: { color: '#eef2f7' } }
      },
      dataZoom: shouldUseZoom
        ? normalizeInteractiveDataZoom([
            { type: 'slider', height: 18, bottom: 26, start: 0, end: 60, xAxisIndex: 0 },
            { type: 'inside', start: 0, end: 60, xAxisIndex: 0 }
          ])
        : [],
      series: [{
        data: seriesData,
        type,
        smooth: type === 'line',
        barMaxWidth: 28,
        itemStyle: { borderRadius: [4, 4, 0, 0] }
      }]
    }
  } else {
    option = {
      tooltip: { trigger: 'item' },
      legend: { bottom: 4 },
      series: [{ type: 'pie', radius: ['42%', '68%'], data: normalizedData }]
    }
  }

  option = applyLiveChartOptionTemplate(option, template)
  chartAnimationMeta.value = getChartAnimationMeta(option)
  const renderVersion = ++chartRenderVersion
  const replayStartOption = buildAnimationReplayStartOption(option)
  if (replayStartOption) {
    instance.clear()
    instance.setOption(replayStartOption, { notMerge: true, lazyUpdate: false })
    instance.resize()
    window.setTimeout(() => {
      if (renderVersion !== chartRenderVersion || instance.isDisposed?.()) return
      instance.setOption(option, { notMerge: true, lazyUpdate: false })
      instance.resize()
    }, 80)
    return
  }
  instance.setOption(option, { notMerge: true, lazyUpdate: false })
  instance.resize()
}

const changeLastAnalysisChartType = (type) => {
  const nextType = String(type || '').toLowerCase()
  if (!chartTypeSwitchOptions.some(option => option.value === nextType)) return
  if (!lastAnalysis.value?.data?.length) return
  const recommendedType = lastAnalysis.value.recommendedChartType || lastAnalysis.value.aiRecommendedChartType || lastAnalysis.value.chartType || currentChartType.value
  currentChartType.value = nextType
  lastAnalysis.value = {
    ...lastAnalysis.value,
    recommendedChartType: recommendedType,
    chartType: nextType
  }
  nextTick(() => {
    renderChart(lastAnalysis.value.data, nextType)
  })
}

const applyLiveChartOptionTemplate = (baseOption, template) => {
  if (!template || typeof template !== 'object') {
    return applyDynamicInteractionDefaults(baseOption, null, { chartType: lastAnalysis.value?.chartType })
  }
  const withTemplate = mergeChartOptions(applyOptionTemplateDefaults(baseOption, template), template)
  return applyDynamicInteractionDefaults(withTemplate, template, { chartType: lastAnalysis.value?.chartType })
}

const mergeChartOptions = (base, override) => {
  if (Array.isArray(base) || Array.isArray(override)) {
    const baseArray = Array.isArray(base) ? base : []
    const overrideArray = Array.isArray(override) ? override : []
    const max = Math.max(baseArray.length, overrideArray.length)
    const merged = []
    for (let i = 0; i < max; i++) {
      const left = baseArray[i]
      const right = overrideArray[i]
      if (left && typeof left === 'object' && !Array.isArray(left) && right && typeof right === 'object' && !Array.isArray(right)) {
        merged[i] = mergeChartOptions(left, right)
      } else {
        merged[i] = right === undefined ? left : right
      }
    }
    return merged
  }
  if (!base || typeof base !== 'object' || !override || typeof override !== 'object') {
    return override === undefined ? base : override
  }
  const merged = { ...base }
  for (const [key, value] of Object.entries(override)) {
    if (value === undefined || value === null) continue
    merged[key] = mergeChartOptions(base[key], value)
  }
  return merged
}

provide('workbench', {
  API_BASE,
  datasourceHealthMap,
  loadDatasourceHealth,
  activeModule,
  diagnoseFromLastAnalysis,
  tables,
  selectedTableName,
  advancedAlertContext,
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
  uploadProgress,
  previewRows,
  previewPage,
  previewPageSize,
  previewTotal,
  fields,
  auditLogs,
  auditRules,
  sensitiveRules,
  auditCacheOverview,
  auditStats,
  auditCacheAudits,
  auditRowPolicies,
  auditAdvancedFilters,
  sensitiveRuleForm,
  auditRowPolicyForm,
  graphOverview,
  graphSearchKeyword,
  graphSearchResult,
  graphLoading,
  knowledgeDocFile,
  knowledgeDocFiles,
  knowledgeDocUploadFiles,
  knowledgeDocUploading,
  knowledgeDocs,
  auditRiskLevel,
  auditExecuteStatus,
  manualAuditSql,
  manualAuditResult,
  permissionOverview,
  accessibleTables,
  accessibleOfficialTables,
  requestableTables,
  sensitiveFieldPermissions,
  rowPolicyDetails,
  complianceDocument,
  myPermissionRequests,
  adminPermissionRequests,
  adminRequestStatus,
  permissionForm,
  datasourceForm,
  datasourcePermissions,
  datasourcePermissionForm,
  federalRelations,
  federalForm,
  officialDatasources,
  selectedDatasourceId,
  schemaTables,
  schemaFields,
  diagnosisForm,
  diagnosisPickerVisible,
  diagnosisPickerForm,
  diagnosisLoading,
  diagnosisProgress,
  currentDiagnosis,
  diagnosisReports,
  diagnosisRestoreTarget,
  question,
  loading,
  isStreaming,
  streamAbortController,
  activeChatRequestId,
  stopRequested,
  chatModelOptions,
  selectedChatModelId,
  selectedChatModel,
  businessDictionaryPanelVisible,
  businessDictionaryFocusModelId,
  activeBusinessModelId,
  selectedChatBusinessModelId,
  lastCreatedBusinessModelId,
  lastAppliedBusinessModelId,
  chatBusinessModelOptions,
  recentChatQueries,
  messages,
  currentChartType,
  chartTypeSwitchOptions,
  isLastAnalysisTable,
  lastAnalysisTableColumns,
  lastAnalysisTableRows,
  chartSortMode,
  lastAnalysis,
  chartAnimationMeta,
  moduleTitle,
  moduleSubtitle,
  isPermissionModule,
  isAdminModule,
  isAdminUser,
  placeholderStep,
  previewColumns,
  chartTypeLabel,
  changeLastAnalysisChartType,
  isAiRecommendedChartType,
  numericFields,
  dateFields,
  dimensionCandidateFields,
  canDiagnoseLastAnalysis,
  canRegenerateLastAnalysis,
  canPinLastAnalysis,
  uploadTables,
  officialQueryTables,
  recentChatQueryKeyword,
  recentChatQueryTableName,
  recentChatQueryChartType,
  recentChatQueryRiskLevel,
  recentChatQueryExecutionStatus,
  recentChatQueryDateRange,
  recentChatQuerySortDirection,
  recentChatQueryPage,
  recentChatQueryPageSize,
  recentChatQueryTotal,
  recentChatQueryLoading,
  recentChatQueryError,
  recentChatQueryErrorType,
  chatSessions,
  activeChatSessionId,
  chatSessionLoading,
  chatSessionKeyword,
  chatSessionStatus,
  chatSessionAdvancedType,
  searchChatSessions,
  resetChatSessionSearch,
  refreshActiveChatSessionSummary,
  renameChatSession,
  updateChatSessionStatus,
  deleteChatSession,
  loadChatSessions,
  createChatSession,
  selectChatSession,
  openHistoricalAnalysis,
  openHistoricalAnalysisFromHistory,
  continueFromChatHistory,
  draftChatQuestionFromHistory,
  setActiveBranchParent,
  clearActiveBranchParent,
  activeBranchParentTurnId,
  activeBranchParentTurnMeta,
  loadTables,
  loadPermissionCenter,
  loadAdminPermissionRequests,
  submitPermissionRequest,
  handlePermissionResourceChange,
  handlePermissionAttachmentChange,
  prefillPermissionRequest,
  reviewPermission,
  permissionStatusType,
  permissionStatusText,
  fillCurrentDatasource,
  loadDatasources,
  createDatasource,
  selectDatasource,
  testDatasource,
  syncDatasourceSchema,
  toggleDatasource,
  updateDatasource,
  deleteDatasource,
  loadDatasourcePermissions,
  grantDatasourcePermission,
  revokeDatasourcePermission,
  loadFederalRelations,
  saveFederalRelation,
  validateFederalRelation,
  deleteFederalRelation,
  loadSchemaTables,
  selectSchemaTable,
  updateSchemaField,
  updateUploadField,
  runDiagnosis,
  diagnoseFromDashboardCard,
  confirmDiagnosisPicker,
  loadDiagnosisReports,
  loadDiagnosisReportDetail,
  deleteDiagnosisReports,
  regenerateDiagnosisReport,
  exportDiagnosisReport,
  restoreDiagnosisBinding,
  unwrap,
  fieldLabel,
  loadPreview,
  handlePreviewPageChange,
  handlePreviewSizeChange,
  loadFields,
  selectTable,
  onFileChange,
  onBatchFileChange,
  onFileRemove,
  submitUpload,
  loadBusinessModels,
  loadAnalysisTemplates,
  onTemplateFileChange,
  uploadAnalysisTemplate,
  createBusinessModelFromTemplate,
  createBusinessModel,
  handleChatBusinessModelChange,
  handleChatModelChange,
  openBusinessDictionaryByModelId,
  publishBusinessModel,
  applyBusinessModel,
  updateBusinessModel,
  renameDataTable,
  deleteDataTable,
  sendQuestion,
  regenerateLastAnalysis,
  openPinDialog,
  openPinDialogForAnalysis,
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
  voiceGenderOptions,
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
  clearVoiceHistory,
  normalizeVoiceQuestion,
  hasVoiceConclusion,
  startVoiceQuestionInput,
  stopVoiceQuestionInput,
  speakChatBubble,
  stopVoicePlayback,
  toggleVoicePlayback,
  stopQuestionGeneration,
  copySqlToClipboard,
  reuseChatQuestion,
  removeRecentChatQuery,
  searchRecentChatQueries,
  resetRecentChatQuerySearch,
  handleRecentChatPageChange,
  handleRecentChatPageSizeChange,
  loadPinnedHistoryIds,
  ensureHistoryEntrySnapshot,
  formatChatHistoryTime,
  loadAuditLogs,
  loadAuditRules,
  updateAuditRuleStatus,
  updateAuditRuleConfig,
  saveSensitiveRule,
  updateSensitiveRuleStatus,
  deleteSensitiveRule,
  submitManualAudit,
  reviewAuditLog,
  quarantineAuditCache,
  saveAuditRowPolicy,
  deleteAuditRowPolicy,
  exportSqlLogs,
  loadGraphOverview,
  rebuildGraph,
  searchGraph,
  onKnowledgeDocChange,
  onKnowledgeDocRemove,
  loadKnowledgeDocs,
  uploadKnowledgeDoc,
  indexKnowledgeDoc,
  deleteKnowledgeDoc,
  riskTagType,
  statusTagType,
  renderChart,
  exportChartAsImage
})
</script>

<style>
.app-shell {
  height: 100vh;
  min-height: 100vh;
  background: #f5f7fb;
  color: #172033;
  overflow: hidden;
}

.app-shell > .el-container {
  flex: 1;
  min-height: 0;
}

.app-aside {
  background: #111827;
  color: #fff;
  border-right: 1px solid #202b3d;
  transition: width 0.35s cubic-bezier(0.2, 0, 0, 1);
  overflow-x: hidden;
  white-space: nowrap;
}

.app-aside.is-collapsed .brand {
  padding: 0 14px;
}

.brand {
  height: 72px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  transition: padding 0.35s cubic-bezier(0.2, 0, 0, 1);
  overflow: hidden;
}

.brand > div:last-child {
  transition: opacity 0.2s cubic-bezier(0.2, 0, 0, 1);
  opacity: 1;
}

.app-aside.is-collapsed .brand > div:last-child {
  opacity: 0;
  pointer-events: none;
}

.brand-mark {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: #2f7cf6;
  font-weight: 800;
}

.brand-title {
  font-size: 17px;
  font-weight: 700;
}

.brand-subtitle {
  margin-top: 2px;
  color: #9ca3af;
  font-size: 12px;
}

.role-panel {
  margin: 14px 16px 4px;
  padding: 12px;
  border: 1px solid rgba(125, 211, 252, 0.18);
  border-radius: 8px;
  background: rgba(125, 211, 252, 0.08);
  transition: opacity 0.2s cubic-bezier(0.2, 0, 0, 1), transform 0.35s cubic-bezier(0.2, 0, 0, 1);
  transform-origin: left center;
}

.app-aside.is-collapsed .role-panel {
  opacity: 0;
  pointer-events: none;
  transform: scaleX(0.8);
}

.role-name {
  color: #7dd3fc;
  font-size: 13px;
  font-weight: 700;
}

.role-user {
  margin-top: 4px;
  color: #cbd5e1;
  font-size: 12px;
}

.nav-menu {
  border-right: 0;
  background: transparent;
}

.nav-menu .el-menu-item {
  color: #cbd5e1;
}

/* 分组 title 为空时不占位，避免两个 el-menu-item-group 之间出现一条深色「缝隙」 */
.nav-menu .el-menu-item-group__title {
  padding: 0 !important;
  min-height: 0 !important;
  line-height: 0 !important;
  font-size: 0 !important;
  overflow: hidden;
}

.nav-menu .el-menu-item-group__title:not(:empty) {
  color: #7dd3fc;
  font-size: 12px;
  line-height: 1.4;
  letter-spacing: 0;
  padding: 12px 20px 6px !important;
}

.nav-menu .el-menu-item.is-active {
  color: #fff;
  background: #243145;
}

.menu-index {
  width: 22px;
  height: 22px;
  display: inline-grid;
  place-items: center;
  margin-right: 8px;
  border-radius: 5px;
  background: rgba(125, 211, 252, 0.12);
  color: #7dd3fc;
  font-size: 12px;
  font-weight: 700;
}

.topbar {
  height: 52px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e5e7eb;
  padding: 0 16px 0 0;
}

.topbar-nav {
  min-width: 0;
  height: 100%;
  flex: 1;
  display: flex;
  align-items: center;
  overflow-x: auto;
  overflow-y: hidden;
}

.sidebar-toggle,
.home-crumb,
.tab-close {
  border: 0;
  background: transparent;
  color: #475467;
  cursor: pointer;
}

.sidebar-toggle {
  width: 52px;
  height: 52px;
  display: inline-grid;
  place-items: center;
}

.sidebar-toggle:hover,
.tab-close:hover {
  color: #2f7cf6;
  background: #f5f8ff;
}

.home-crumb:hover {
  color: #2f7cf6;
}

.hamburger-icon,
.hamburger-icon::before,
.hamburger-icon::after {
  width: 18px;
  height: 2px;
  display: block;
  border-radius: 999px;
  background: #2f4f77;
  box-shadow: 0 1px 2px rgba(47, 79, 119, 0.22);
  transition: transform 0.3s cubic-bezier(0.2, 0, 0, 1), opacity 0.3s cubic-bezier(0.2, 0, 0, 1), background-color 0.2s ease;
}

.hamburger-icon {
  position: relative;
}

/* 简单的缩放效果，不使用箭头 */
.sidebar-toggle.is-collapsed .hamburger-icon,
.sidebar-toggle.is-collapsed .hamburger-icon::before,
.sidebar-toggle.is-collapsed .hamburger-icon::after {
  background-color: #2f7cf6;
}

.hamburger-icon::before,
.hamburger-icon::after {
  content: '';
  position: absolute;
  left: 0;
  width: 100%;
}

.hamburger-icon::before {
  top: -6px;
}

.sidebar-toggle.is-collapsed .hamburger-icon::before {
  transform: translateX(-2px);
}

.hamburger-icon::after {
  top: 6px;
}

.sidebar-toggle.is-collapsed .hamburger-icon::after {
  transform: translateX(-4px);
}

.topbar-divider {
  width: 1px;
  height: 24px;
  margin-right: 12px;
  background: #e5e7eb;
}

.home-crumb {
  display: inline-flex;
  align-items: center;
  padding: 0;
  color: #53627a;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0;
  text-shadow: 0 1px 0 rgba(255, 255, 255, 0.8);
  white-space: nowrap;
}

.breadcrumb-separator {
  margin: 0 14px;
  color: #c8d0dc;
  font-size: 15px;
  font-weight: 700;
}

.current-crumb {
  color: #344258;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0;
  text-shadow: 0 1px 0 rgba(255, 255, 255, 0.75);
  white-space: nowrap;
}

.page-tab {
  height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-left: 10px;
  padding: 0 8px 0 14px;
  border: 1px solid #d9e4f5;
  border-radius: 4px;
  background: #fff;
  color: #344054;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  white-space: nowrap;
}

.page-tab:hover {
  border-color: #9bc2ff;
  color: #2f7cf6;
}

.page-tab.is-active {
  border-color: #2f7cf6;
  background: #2f7cf6;
  color: #fff;
  font-weight: 600;
}

.tab-close {
  width: 22px;
  height: 22px;
  display: inline-grid;
  place-items: center;
  padding: 0;
  border-radius: 4px;
  color: #667085;
  font-size: 13px;
}

.page-tab.is-active .tab-close {
  color: rgba(255, 255, 255, 0.86);
}

.tab-close:hover {
  color: #2f7cf6;
  background: #eef5ff;
}

.page-tab.is-active .tab-close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.16);
}

.topbar-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-chip {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.25;
}

.user-chip strong {
  color: #172033;
  font-size: 13px;
}

.user-chip span {
  color: #667085;
  font-size: 12px;
}

.table-select {
  width: 260px;
}

.main-stage {
  flex: 1;
  min-height: 0;
  padding: 20px;
  box-sizing: border-box;
  overflow: auto;
  display: flex;
  flex-direction: column;
}

.main-stage.main-stage--dashboard {
  padding: 20px 20px 0;
  overflow: hidden;
}

.workspace-grid {
  display: grid;
  gap: 16px;
}

.upload-grid {
  grid-template-columns: minmax(360px, 0.9fr) minmax(480px, 1.1fr);
}

.panel {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 18px;
  min-width: 0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.preview-panel {
  grid-column: 1 / 2;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.panel h2 {
  margin: 0;
  font-size: 16px;
}

.panel p {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
}

.upload-drop-title {
  font-size: 16px;
  font-weight: 700;
  color: #1f2937;
}

.upload-drop-subtitle {
  margin-top: 8px;
  color: #667085;
  font-size: 13px;
}

.upload-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.result-alert {
  margin-top: 14px;
}

.upload-progress {
  margin-top: 14px;
}

.upload-progress-text {
  margin-top: 6px;
  color: #667085;
  font-size: 13px;
}

.chat-layout {
  --chat-panel-height: max(900px, calc(100vh - 48px));
  display: grid;
  grid-template-columns: 420px minmax(520px, 1fr);
  gap: 16px;
  align-items: stretch;
  min-height: var(--chat-panel-height);
}

.chat-panel,
.chart-panel {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: var(--chat-panel-height);
  min-height: var(--chat-panel-height);
}

.chat-panel {
  overflow: hidden;
}

.chart-panel {
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.message-list {
  flex: 1 1 auto;
  min-height: 260px;
  overflow-y: auto;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.message-wrapper {
  display: flex;
  align-items: flex-start;
  margin-bottom: 20px;
  animation: fadeIn 0.3s ease-in-out;
}

.message-wrapper.user {
  flex-direction: row-reverse;
}

.avatar {
  font-size: 26px;
  line-height: 1;
  padding: 4px;
  border-radius: 50%;
  background: white;
  box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1);
  margin: 0 10px;
}

.avatar img {
  width: 42px;
  height: 42px;
  display: block;
  border-radius: 50%;
  object-fit: cover;
}

.avatar span {
  display: block;
}

.msg-content {
  display: flex;
  flex-direction: column;
  max-width: 85%;
}

.bubble {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.6;
  font-size: 14px;
  box-shadow: 0 1px 2px 0 rgb(0 0 0 / 0.05);
  word-break: break-word;
  white-space: pre-wrap;
}

.user .bubble {
  background: linear-gradient(135deg, #2f7cf6, #0e5add);
  color: #fff;
  border-top-right-radius: 4px;
}

.system .bubble {
  background: #fff;
  color: #1f2937;
  border: 1px solid #e5e7eb;
  border-top-left-radius: 4px;
}

.sql-block {
  margin-top: 10px;
  background: #111827;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1);
}

.sql-title {
  padding: 6px 12px;
  background: #1f2937;
  color: #9ca3af;
  font-size: 12px;
  font-family: monospace;
  border-bottom: 1px solid #374151;
}

.sql-code {
  max-width: 100%;
  margin: 0;
  padding: 12px;
  overflow-x: auto;
  color: #a7f3d0;
  font-size: 13px;
  line-height: 1.6;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.ask-bar {
  display: flex;
  margin-top: 16px;
  background: transparent;
  box-shadow: none;
}

.ask-bar .el-input__wrapper {
  background: transparent !important;
  box-shadow: inset 0 0 0 1px #e5e7eb !important;
}

.ask-bar .el-input-group__append {
  background-color: var(--el-color-primary) !important;
  color: white !important;
  border: 1px solid var(--el-color-primary) !important;
}
.ask-bar .el-input-group__append .el-button {
  color: white;
}

.chart-canvas {
  width: 100%;
  height: 460px;
}

.chart-canvas.chart-render-animating {
  animation: chartRenderReplay 0.28s ease-out;
}

@keyframes chartRenderReplay {
  0% {
    opacity: 0.72;
  }
  100% {
    opacity: 1;
  }
}

.analysis-meta {
  margin-top: 16px;
}

.chat-select-dropdown {
  min-width: 360px !important;
  max-width: 520px;
}

.chat-select-dropdown .el-select-dropdown__item,
.chat-select-dropdown .el-select-dropdown__item.is-hovering,
.chat-select-dropdown .el-select-dropdown__item.is-selected {
  height: auto;
  min-height: 34px;
  line-height: 1.5;
  white-space: normal;
  overflow: visible;
  text-overflow: unset;
  padding-top: 8px;
  padding-bottom: 8px;
}

.audit-layout {
  display: grid;
  gap: 16px;
}

.permission-layout {
  display: grid;
  gap: 16px;
}

.datasource-layout {
  display: grid;
  grid-template-columns: minmax(420px, 0.9fr) minmax(520px, 1.1fr);
  gap: 16px;
}

.diagnosis-layout {
  display: grid;
  grid-template-columns: minmax(360px, 0.75fr) minmax(560px, 1.25fr);
  gap: 16px;
}

.knowledge-layout {
  display: grid;
  gap: 16px;
}

.diagnosis-result {
  grid-row: span 2;
}

.diagnosis-result h3 {
  margin: 18px 0 10px;
  font-size: 15px;
}

.diagnosis-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.diagnosis-stats {
  margin-top: 16px;
}

.contribution-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 12px;
}

.contribution-block {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px;
}

.contribution-title {
  margin-bottom: 8px;
  color: #374151;
  font-weight: 700;
}

.suggestion-list {
  margin: 0;
  padding-left: 18px;
  color: #374151;
  line-height: 1.8;
}

.report-markdown {
  max-height: 260px;
  overflow: auto;
  padding: 14px;
  color: #d1d5db;
  background: #111827;
  border-radius: 8px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.report-history {
  grid-column: 1 / 2;
}

.datasource-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(160px, 1fr));
  gap: 0 14px;
}

.datasource-actions {
  display: flex;
  gap: 10px;
}

.permission-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(160px, 1fr));
  gap: 16px;
}

.metric-panel {
  padding: 16px;
}

.metric-label {
  color: #667085;
  font-size: 13px;
}

.metric-value {
  margin-top: 8px;
  font-size: 26px;
  font-weight: 800;
  color: #111827;
}

.metric-note {
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
}

.permission-grid {
  grid-template-columns: minmax(420px, 1fr) minmax(420px, 1fr);
}

.graph-type-grid,
.graph-result-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr);
  gap: 16px;
}

.graph-searchbar {
  display: grid;
  grid-template-columns: minmax(220px, 320px) 72px;
  gap: 10px;
}

.admin-approval-panel {
  grid-column: 1 / -1;
}

.permission-info-panel,
.compliance-panel,
.request-history-panel {
  grid-column: span 1;
}

.tag-section {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.section-label {
  width: 100%;
  color: #374151;
  font-size: 13px;
  font-weight: 700;
}

.compliance-list {
  margin: 0;
  padding-left: 18px;
  color: #374151;
  line-height: 1.8;
}

.compliance-doc {
  margin-top: 12px;
}

.request-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(180px, 1fr));
  gap: 0 14px;
}

.attachment-name {
  margin-top: 8px;
  color: #6b7280;
  font-size: 12px;
}

.full-width {
  width: 100%;
}

.permission-tip {
  margin-top: 16px;
}

.preview-pagination {
  margin-top: 12px;
  justify-content: flex-end;
}

.admin-filter {
  width: 130px;
}

.audit-toolbar {
  display: grid;
  grid-template-columns: 140px 140px 80px 110px;
  gap: 10px;
}

.sensitive-rule-form {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 130px 70px 72px;
  gap: 10px;
  align-items: center;
  min-width: min(640px, 100%);
}

.cache-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.audit-expand {
  padding: 12px 18px;
  background: #f8fafc;
}

.audit-expand pre {
  margin: 8px 0 14px;
  padding: 12px;
  overflow-x: auto;
  color: #d1d5db;
  background: #111827;
  border-radius: 8px;
  line-height: 1.5;
}

.expand-label {
  color: #374151;
  font-weight: 700;
}

.placeholder-panel {
  min-height: 420px;
}

.roadmap-text {
  margin-top: 28px;
  padding: 16px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  color: #4b5563;
}

@media (max-width: 1100px) {
  .upload-grid,
  .chat-layout,
  .diagnosis-layout,
  .knowledge-layout,
  .contribution-list,
  .graph-type-grid,
  .graph-result-grid,
  .datasource-layout,
  .datasource-form,
  .request-form-grid,
    .permission-grid,
    .permission-cards {
    grid-template-columns: 1fr;
  }
  .chat-panel,
  .chart-panel {
    height: var(--chat-panel-height);
    min-height: var(--chat-panel-height);
  }
  .message-list {
    min-height: 360px;
  }
}
</style>
