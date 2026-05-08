<template>
  <AuthView v-if="!isAuthenticated" @authenticated="handleAuthenticated" />
  <el-container v-else class="app-shell">
    <el-aside width="248px" class="app-aside">
      <div class="brand">
        <div class="brand-mark">BI</div>
        <div>
          <div class="brand-title">析数灵犀</div>
          <div class="brand-subtitle">AI驱动的对话式智能BI系统</div>
        </div>
      </div>

      <el-menu :default-active="activeModule" class="nav-menu" @select="activeModule = $event">
        <el-menu-item-group v-for="group in visibleMenuGroups" :key="group.id" :title="group.title">
          <el-menu-item v-for="module in group.modules" :key="module.key" :index="module.key">
            <span>{{ module.title }}</span>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div>
          <h1>{{ moduleTitle }}</h1>
          <p>{{ moduleSubtitle }}</p>
        </div>
        <div class="topbar-actions">
          <el-tag :type="currentUser?.role === 'ADMIN' ? 'warning' : 'success'">
            {{ portalLabel }}
          </el-tag>
          <el-select v-if="activeModule === 'chat'" v-model="selectedTableName" placeholder="选择数据表" class="table-select" clearable>
          <el-option
              v-for="table in tables"
              :key="table.tableName"
              :label="table.displayName"
              :value="table.tableName"
          />
          </el-select>
          <el-button @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main-stage">
        <DataUploadView v-if="activeModule === 'upload'" />
        <ChatAnalysisView v-if="activeModule === 'chat'" />
        <PermissionCenterView v-if="isPermissionModule" />
        <DatasourceManageView v-if="activeModule === 'datasource'" />
        <DiagnosisReportView v-if="activeModule === 'diagnosis'" />
        <KnowledgeGraphView v-if="activeModule === 'knowledgeGraph'" />
        <SqlAuditView v-if="activeModule === 'audit'" />
        <UserWorkbenchView v-if="activeModule === 'workbench'" />
        <UserDashboardView v-if="activeModule === 'dashboard'" />
        <BusinessCollaborationView v-if="activeModule === 'collaboration'" />
        <AdminWorkbenchView v-if="activeModule === 'adminWorkbench'" />
        <AdminDashboardView v-if="activeModule === 'adminDashboard'" />
        <StackCSystemConfigView v-if="activeModule === 'stackCConfig'" />
        <PerformanceGovernanceView v-if="activeModule === 'performanceGovernance'" />
        <PlaceholderView
            v-if="!['upload', 'chat', 'audit', 'permission', 'permissionAdmin', 'datasource', 'diagnosis', 'knowledgeGraph', 'workbench', 'dashboard', 'collaboration', 'adminWorkbench', 'adminDashboard', 'stackCConfig', 'performanceGovernance'].includes(activeModule)"
        />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, nextTick, onMounted, provide, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { menuGroups, moduleMap } from './router/modules'
import DataUploadView from './views/user/DataUploadView.vue'
import ChatAnalysisView from './views/user/ChatAnalysisView.vue'
import PermissionCenterView from './views/user/PermissionCenterView.vue'
import DatasourceManageView from './views/admin/DatasourceManageView.vue'
import DiagnosisReportView from './views/user/DiagnosisReportView.vue'
import KnowledgeGraphView from './views/admin/KnowledgeGraphView.vue'
import SqlAuditView from './views/admin/SqlAuditView.vue'
import StackCSystemConfigView from './views/admin/StackCSystemConfigView.vue'
import PerformanceGovernanceView from './views/admin/PerformanceGovernanceView.vue'
import AdminWorkbenchView from './views/admin/AdminWorkbenchView.vue'
import AdminDashboardView from './views/admin/AdminDashboardView.vue'
import UserWorkbenchView from './views/user/UserWorkbenchView.vue'
import UserDashboardView from './views/user/UserDashboardView.vue'
import BusinessCollaborationView from './views/user/BusinessCollaborationView.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import AuthView from './views/AuthView.vue'
import { currentUser, isAuthenticated, clearSession, restoreSessionHeader } from './store/session'
import { logout } from './api/auth'

const API_BASE = 'http://localhost:8080'

const datasourceHealthMap = ref({})
const activeModule = ref('upload')
const tables = ref([])
const selectedTableName = ref('')
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
const previewRows = ref([])
const previewPage = ref(1)
const previewPageSize = ref(10)
const previewTotal = ref(0)
const fields = ref([])
const auditLogs = ref([])
const auditRules = ref([])
const graphOverview = ref({ nodeTypes: [], edgeTypes: [] })
const graphSearchKeyword = ref('')
const graphSearchResult = ref({ nodes: [], edges: [], ragContext: [] })
const graphLoading = ref(false)
const knowledgeDocFile = ref(null)
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
const myPermissionRequests = ref([])
const adminPermissionRequests = ref([])
const adminRequestStatus = ref('PENDING')
const permissionForm = ref({ tableName: '', reason: '' })
const datasourceForm = ref({
  name: '',
  dbType: 'MYSQL',
  host: 'localhost',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  poolMaxSize: 10,
  poolTimeoutMs: 30000
})
const datasourcePermissions = ref([])
const datasourcePermissionForm = ref({ principalType: 'USER', principalId: 'user', permissionType: 'READ' })
const federalRelations = ref([])
const federalForm = ref({ leftTable: '', leftField: '', rightSourceType: 'UPLOAD', rightTable: '', rightField: '', relationType: 'LEFT_JOIN' })
const officialDatasources = ref([])
const selectedDatasourceId = ref(null)
const schemaTables = ref([])
const schemaFields = ref([])
const diagnosisForm = ref({ metricField: '', dimensionFields: [], timeField: '', detailLevel: 'detailed', anomalyType: 'fluctuation' })
const diagnosisPickerVisible = ref(false)
const diagnosisPickerForm = ref({ metricField: '', dimensionFields: [], timeField: '' })
const diagnosisLoading = ref(false)
const diagnosisProgress = ref({ percentage: 0, step: '待开始', logs: [] })
const currentDiagnosis = ref(null)
const diagnosisReports = ref([])
const question = ref('')
const loading = ref(false)
const messages = ref([
  { role: 'system', content: '👋 你好！我是你的析数灵犀 AI 数据助手。请在左侧选择数据表，然后用自然语言向我提问吧！' }
])
const currentChartType = ref('')
const chartSortMode = ref('desc')
const lastAnalysis = ref(null)
let chartInstance = null

const moduleTitle = computed(() => moduleMap[activeModule.value].title)
const moduleSubtitle = computed(() => moduleMap[activeModule.value].subtitle)
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
const isAdminModule = computed(() => ['datasource', 'permissionAdmin', 'knowledgeGraph', 'audit', 'stackCConfig', 'adminWorkbench', 'adminDashboard', 'performanceGovernance'].includes(activeModule.value))
const isAdminUser = computed(() => currentUser.value?.role === 'ADMIN')
const portalLabel = computed(() => isAdminUser.value ? '管理员门户' : '用户门户')
const placeholderStep = computed(() => activeModule.value === 'audit' ? 1 : 0)
const previewColumns = computed(() => previewRows.value.length ? Object.keys(previewRows.value[0]) : [])
const chartTypeLabel = computed(() => {
  if (currentChartType.value === 'bar') return '柱状图'
  if (currentChartType.value === 'pie') return '饼图'
  if (currentChartType.value === 'line') return '折线图'
  return currentChartType.value
})
const numericFields = computed(() => fields.value.filter(field => field.fieldType === 'NUMBER'))
const dateFields = computed(() => fields.value.filter(field => field.fieldType === 'DATE'))
const dimensionCandidateFields = computed(() => fields.value.filter(field => field.fieldType !== 'NUMBER'))
const canDiagnoseLastAnalysis = computed(() => Boolean(lastAnalysis.value && numericFields.value.length))
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
  const container = document.getElementById('echarts-container')
  if (container) {
    chartInstance = echarts.init(container)
  }
  window.addEventListener('resize', () => chartInstance?.resize())
  try {
    await Promise.all([loadTables(), loadBusinessModels(), loadAnalysisTemplates()])
  } catch (error) {
    if ((error.message || '').includes('登录已失效') || (error.message || '').includes('请先登录')) {
      ElMessage.warning('登录状态已过期，请重新登录')
      clearSession()
      return
    }
    ElMessage.error(error.message || '初始化数据加载失败')
  }
}

const normalizeActiveModule = () => {
  const firstModule = visibleMenuGroups.value[0]?.modules[0]?.key || 'upload'
  const allowed = visibleMenuGroups.value.some(group => group.modules.some(module => module.key === activeModule.value))
  if (!allowed) {
    activeModule.value = firstModule
  }
}

const handleAuthenticated = async () => {
  await bootstrapWorkbench()
}

const handleLogout = async () => {
  try {
    await logout()
  } finally {
    clearSession()
    activeModule.value = 'upload'
  }
}

watch(selectedTableName, async (tableName) => {
  if (!tableName) {
    previewRows.value = []
    fields.value = []
    return
  }
  await Promise.all([loadPreview(tableName), loadFields(tableName)])
})

watch(activeModule, async (moduleName) => {
  if (moduleName === 'chat') {
    await nextTick()
    if (!chartInstance) {
      const container = document.getElementById('echarts-container')
      if (container) chartInstance = echarts.init(container)
    }
    chartInstance?.resize()
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

const loadTables = async () => {
  const data = unwrap(await axios.get(`${API_BASE}/api/data/tables`))
  tables.value = data
  if (!selectedTableName.value && data.length) {
    selectedTableName.value = data[0].tableName
  }
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
  await loadAdminPermissionRequests()
}

const loadAdminPermissionRequests = async () => {
  const data = unwrap(await axios.get(`${API_BASE}/api/permission/admin/requests`, {
    params: { status: adminRequestStatus.value || undefined }
  }))
  adminPermissionRequests.value = data
}

const submitPermissionRequest = async () => {
  try {
    await axios.post(`${API_BASE}/api/permission/requests`, {
      tableName: permissionForm.value.tableName,
      reason: permissionForm.value.reason
    }).then(unwrap)
    ElMessage.success('权限申请已提交')
    permissionForm.value = { tableName: '', reason: '' }
    await loadPermissionCenter()
  } catch (error) {
    ElMessage.error(error.message || '提交失败')
  }
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
    poolTimeoutMs: 30000
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
    ElMessage.success(`解析完成：${result.tableCount} 张表，${result.fieldCount} 个字段`)
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
      sensitive: Boolean(row.sensitive)
    }).then(unwrap)
    ElMessage.success('字段配置已更新')
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
  try {
    await axios.post(`${API_BASE}/api/data/tables/${selectedTableName.value}/fields/${row.columnName}`, {
      displayName: row.displayName,
      fieldType: row.fieldType,
      fieldComment: row.fieldComment,
      sensitive: Boolean(row.sensitive)
    }).then(unwrap)
    ElMessage.success('字段信息已更新')
    await loadFields(selectedTableName.value)
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
  startDiagnosisProgress()
  try {
    currentDiagnosis.value = unwrap(await axios.post(`${API_BASE}/api/diagnosis/run`, {
      tableName: selectedTableName.value,
      metricField: diagnosisForm.value.metricField,
      dimensionFields: diagnosisForm.value.dimensionFields,
      timeField: diagnosisForm.value.timeField || null,
      detailLevel: diagnosisForm.value.detailLevel || 'detailed',
      anomalyType: diagnosisForm.value.anomalyType || 'fluctuation'
    }))
    completeDiagnosisProgress(currentDiagnosis.value?.reasoningLogs || [])
    ElMessage.success('诊断报告已生成')
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
    diagnosisPickerForm.value = {
      metricField: metricField.columnName,
      dimensionFields: [],
      timeField: timeField ? timeField.columnName : ''
    }
    diagnosisPickerVisible.value = true
    return
  }

  if (!metricField && numericFields.value.length) {
    diagnosisPickerForm.value = {
      metricField: numericFields.value[0]?.columnName || '',
      dimensionFields: dimensionField ? [dimensionField.columnName] : [],
      timeField: timeField ? timeField.columnName : ''
    }
    diagnosisPickerVisible.value = true
    return
  }

  if (!metricField) {
    ElMessage.warning('当前数据表没有可用于诊断的数值字段，请先在字段语义中把指标字段设置为 NUMBER')
    return
  }

  diagnosisLoading.value = true
  startDiagnosisProgress()

  try {
    const chartSnapshot = captureChartSnapshot(lastAnalysis.value)
    currentDiagnosis.value = unwrap(await axios.post(`${API_BASE}/api/diagnosis/run`, {
      tableName: selectedTableName.value,
      metricField: metricField.columnName,
      dimensionFields: dimensionField ? [dimensionField.columnName] : [],
      timeField: timeField ? timeField.columnName : null,
      sourceQuestion: lastAnalysis.value.sourceQuestion || '',
      sourceSql: lastAnalysis.value.sql || lastAnalysis.value.sourceSql || '',
      chartType: lastAnalysis.value.chartType,
      chartSnapshot,
      detailLevel: diagnosisForm.value.detailLevel || 'detailed',
      anomalyType: diagnosisForm.value.anomalyType || 'fluctuation',
      question: lastAnalysis.value.message || '基于当前对话查询结果生成智能诊断报告'
    }))
    completeDiagnosisProgress(currentDiagnosis.value?.reasoningLogs || [])

    ElMessage.success('已根据当前图表生成智能诊断报告')
    await loadDiagnosisReports()
    activeModule.value = 'diagnosis'
  } catch (error) {
    ElMessage.error(error.message || '诊断报告生成失败')
  } finally {
    diagnosisLoading.value = false
  }
}
const confirmDiagnosisPicker = async () => {
  if (!diagnosisPickerForm.value.metricField) {
    ElMessage.warning('请选择指标字段')
    return
  }
  diagnosisPickerVisible.value = false
  const metricField = fields.value.find(item => item.columnName === diagnosisPickerForm.value.metricField)
  const timeField = fields.value.find(item => item.columnName === diagnosisPickerForm.value.timeField)
  diagnosisLoading.value = true
  startDiagnosisProgress()
  try {
    const chartSnapshot = captureChartSnapshot(lastAnalysis.value)
    currentDiagnosis.value = unwrap(await axios.post(`${API_BASE}/api/diagnosis/run`, {
      tableName: selectedTableName.value,
      metricField: diagnosisPickerForm.value.metricField,
      dimensionFields: diagnosisPickerForm.value.dimensionFields,
      timeField: diagnosisPickerForm.value.timeField || null,
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
      detailLevel: diagnosisForm.value.detailLevel || 'detailed',
      anomalyType: diagnosisForm.value.anomalyType || 'fluctuation'
    }))
    completeDiagnosisProgress(currentDiagnosis.value?.reasoningLogs || [])
    ElMessage.success('诊断报告已生成')
    await loadDiagnosisReports()
    activeModule.value = 'diagnosis'
  } catch (error) {
    ElMessage.error(error.message || '诊断报告生成失败')
  } finally {
    diagnosisLoading.value = false
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
        logs: [...diagnosisProgress.value.logs, { step: 2, title: '文档扫描', detail: '正在检索企业内部文档、行业研报与历史证据。' }]
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

const captureChartSnapshot = (analysis) => ({
  chartType: analysis?.chartType || currentChartType.value,
  fieldMapping: analysis?.fieldMapping || {},
  data: analysis?.data || [],
  generatedSql: analysis?.sql || analysis?.sourceSql || '',
  sourceQuestion: analysis?.sourceQuestion || '',
  imageDataUrl: chartInstance?.getDataURL
    ? chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
    : ''
})

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
    currentDiagnosis.value = { ...parsed, id: detail.id, tableName: detail.tableName, bindingJson: detail.bindingJson, chartSnapshot: parseMaybeJson(parsed.chartSnapshot || detail.chartSnapshot) }
  } else {
    currentDiagnosis.value = { ...detail, chartSnapshot: parseMaybeJson(detail.chartSnapshot) }
  }
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
  if (report?.tableName) {
    selectedTableName.value = report.tableName
  }
  activeModule.value = 'chat'
  await nextTick()
  if (!chartInstance) {
    const container = document.getElementById('echarts-container')
    if (container) chartInstance = echarts.init(container)
  }
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

const fieldLabel = (columnName) => {
  const field = fields.value.find(item => item.columnName === columnName)
  return field?.displayName || columnName
}

const loadPreview = async (tableName) => {
  const page = unwrap(await axios.get(`${API_BASE}/api/data/tables/${tableName}/preview-page`, {
    params: { page: previewPage.value, pageSize: previewPageSize.value }
  }))
  previewRows.value = page.rows || []
  previewTotal.value = page.total || 0
}

const loadFields = async (tableName) => {
  fields.value = unwrap(await axios.get(`${API_BASE}/api/data/tables/${tableName}/fields`))
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

const submitUpload = async () => {
  if (!uploadFile.value && !uploadFiles.value.length) return
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
    uploadTask.value = task
    await pollUploadTask(task.taskId)
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

const pollUploadTask = async (taskId) => {
  for (let i = 0; i < 120; i++) {
    await new Promise(resolve => setTimeout(resolve, 1000))
    await refreshUploadTask(taskId)
    if (uploadTask.value?.status === 'SUCCESS') {
      const result = uploadTask.value.resultJson ? JSON.parse(uploadTask.value.resultJson) : {}
      uploadResult.value = result
      uploadDisplayName.value = ''
      selectedTableName.value = result.tableName || selectedTableName.value
      await Promise.all([loadTables(), loadBusinessModels()])
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

const createBusinessModel = async () => {
  if (!selectedTableName.value || !modelRequirement.value.trim()) return ElMessage.warning('请选择数据表并填写建模需求')
  await axios.post(`${API_BASE}/api/data/business-models`, {
    tableName: selectedTableName.value,
    requirement: modelRequirement.value,
    modelName: `模型_${selectedTableName.value}`
  }).then(unwrap)
  ElMessage.success('业务模型已生成')
  await loadBusinessModels()
}

const publishBusinessModel = async (model, published = true) => {
  await axios.post(`${API_BASE}/api/data/business-models/${model.id}/publish`, { published }).then(unwrap)
  ElMessage.success(published ? '已发布到企业模型库' : '已取消发布')
  await loadBusinessModels()
}

const applyBusinessModel = async (model) => {
  if (!selectedTableName.value) return ElMessage.warning('请先选择要套用模型的数据表')
  await axios.post(`${API_BASE}/api/data/business-models/${model.id}/apply`, { tableName: selectedTableName.value }).then(unwrap)
  ElMessage.success('模型已套用')
  await loadBusinessModels()
}

const renameDataTable = async (row) => {
  await axios.post(`${API_BASE}/api/data/tables/${row.tableName}/rename`, { displayName: row.displayName }).then(unwrap)
  ElMessage.success('数据表已重命名')
  await loadTables()
}

const deleteDataTable = async (row) => {
  await axios.post(`${API_BASE}/api/data/tables/${row.tableName}/delete`).then(unwrap)
  ElMessage.success('数据表已删除')
  await loadTables()
}

const sendQuestion = async () => {
  if (!question.value.trim()) return
  if (!selectedTableName.value) {
    ElMessage.warning('请先选择一个数据表')
    return
  }

  const userQuestion = question.value
  messages.value.push({ role: 'user', content: userQuestion })
  question.value = ''
  loading.value = true

  try {
    let data
    try {
      data = unwrap(await axios.post(`${API_BASE}/api/chat/ask-enhanced`, {
        question: userQuestion,
        tableName: selectedTableName.value
      }))
    } catch (enhancedError) {
      if (enhancedError?.response?.status !== 404) {
        throw enhancedError
      }
      data = unwrap(await axios.post(`${API_BASE}/api/chat/ask`, {
        question: userQuestion,
        tableName: selectedTableName.value
      }))
      data.fallbackUsed = data.engine === 'java-fallback'
    }
    lastAnalysis.value = {
      ...data,
      sourceQuestion: userQuestion,
      sourceSql: data.sql
    }
    currentChartType.value = data.chartType
    messages.value.push({
      role: 'system',
      content: `${data.message}${data.fallbackUsed ? '（规则兜底）' : ''}`,
      sql: data.sql
    })

    nextTick(() => {
      const chatDom = document.getElementById('chatHistory')
      if (chatDom) chatDom.scrollTop = chatDom.scrollHeight
    })

    if (data.data?.length) {
      renderChart(data.data, data.chartType)
    } else {
      chartInstance?.clear()
      ElMessage.warning('查询成功，但没有符合条件的数据')
    }
    if (isAdminUser.value) {
      await loadAuditLogs()
    }
  } catch (error) {
    messages.value.push({ role: 'system', content: `分析失败：${error.message}` })
    if (isAdminUser.value) {
      await loadAuditLogs()
    }
  } finally {
    loading.value = false
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
      limit: 80
    }
  }))
  auditLogs.value = data
}

const loadAuditRules = async () => {
  auditRules.value = unwrap(await axios.get(`${API_BASE}/api/audit/rules`))
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

const exportSqlLogs = () => {
  window.open(`${API_BASE}/api/audit/sql-logs/export?riskLevel=${auditRiskLevel.value || ''}&executeStatus=${auditExecuteStatus.value || ''}&limit=500`, '_blank')
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

const onKnowledgeDocChange = (file) => {
  knowledgeDocFile.value = file.raw
}

const loadKnowledgeDocs = async () => {
  knowledgeDocs.value = unwrap(await axios.get(`${API_BASE}/api/knowledge/docs`))
}

const uploadKnowledgeDoc = async () => {
  if (!knowledgeDocFile.value) return ElMessage.warning('请选择 .txt 或 .md 知识文档')
  const formData = new FormData()
  formData.append('file', knowledgeDocFile.value)
  await axios.post(`${API_BASE}/api/knowledge/docs/upload`, formData).then(unwrap)
  ElMessage.success('知识文档已上传并切片')
  knowledgeDocFile.value = null
  await loadKnowledgeDocs()
}

const indexKnowledgeDoc = async (doc) => {
  await axios.post(`${API_BASE}/api/knowledge/docs/${doc.id}/index`).then(unwrap)
  ElMessage.success('知识文档索引已刷新')
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
  if (!chartInstance || !lastAnalysis.value?.data?.length) {
    ElMessage.warning('暂无可导出的图表')
    return
  }
  chartInstance.resize()
  const url = chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
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

const renderChart = (data, type) => {
  const normalizedData = getSortedChartData(data)
  const xAxisData = normalizedData.map(item => item.name)
  const seriesData = normalizedData.map(item => Number(item.value ?? 0))
  let option = {}

  if (type === 'bar' || type === 'line') {
    const shouldUseZoom = normalizedData.length > 12
    option = {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' }
      },
      grid: { left: 72, right: 24, top: 32, bottom: shouldUseZoom ? 110 : 92 },
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
        ? [
            { type: 'slider', height: 18, bottom: 26, start: 0, end: 60 },
            { type: 'inside', start: 0, end: 60 }
          ]
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

  chartInstance?.setOption(option, true)
}

provide('workbench', {
  datasourceHealthMap,
  loadDatasourceHealth,
  activeModule,
  diagnoseFromLastAnalysis,
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
  uploadProgress,
  previewRows,
  previewPage,
  previewPageSize,
  previewTotal,
  fields,
  auditLogs,
  auditRules,
  graphOverview,
  graphSearchKeyword,
  graphSearchResult,
  graphLoading,
  knowledgeDocFile,
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
  question,
  loading,
  messages,
  currentChartType,
  chartSortMode,
  lastAnalysis,
  moduleTitle,
  moduleSubtitle,
  isPermissionModule,
  isAdminModule,
  isAdminUser,
  placeholderStep,
  previewColumns,
  chartTypeLabel,
  numericFields,
  dateFields,
  dimensionCandidateFields,
  canDiagnoseLastAnalysis,
  loadTables,
  loadPermissionCenter,
  loadAdminPermissionRequests,
  submitPermissionRequest,
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
  loadSchemaTables,
  selectSchemaTable,
  updateSchemaField,
  updateUploadField,
  runDiagnosis,
  confirmDiagnosisPicker,
  loadDiagnosisReports,
  loadDiagnosisReportDetail,
  exportDiagnosisReport,
  restoreDiagnosisBinding,
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
  publishBusinessModel,
  applyBusinessModel,
  renameDataTable,
  deleteDataTable,
  sendQuestion,
  loadAuditLogs,
  loadAuditRules,
  updateAuditRuleStatus,
  updateAuditRuleConfig,
  submitManualAudit,
  exportSqlLogs,
  loadGraphOverview,
  rebuildGraph,
  searchGraph,
  onKnowledgeDocChange,
  loadKnowledgeDocs,
  uploadKnowledgeDoc,
  indexKnowledgeDoc,
  riskTagType,
  statusTagType,
  renderChart,
  exportChartAsImage
})
</script>

<style>
.app-shell {
  min-height: 100vh;
  background: #f5f7fb;
  color: #172033;
}

.app-aside {
  background: #111827;
  color: #fff;
  border-right: 1px solid #202b3d;
}

.brand {
  height: 72px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand-mark {
  width: 36px;
  height: 36px;
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
  height: 72px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e5e7eb;
}

.topbar h1 {
  margin: 0;
  font-size: 20px;
  line-height: 1.3;
}

.topbar p {
  margin: 4px 0 0;
  color: #667085;
  font-size: 13px;
}

.topbar-actions {
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
  padding: 20px;
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
  display: grid;
  grid-template-columns: 420px minmax(520px, 1fr);
  gap: 16px;
  min-height: calc(100vh - 132px);
}

.chat-panel,
.chart-panel {
  display: flex;
  flex-direction: column;
}

.message-list {
  flex: 1;
  min-height: 480px;
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
  box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.05);
}

.ask-bar .el-input__wrapper {
  box-shadow: 0 0 0 1px #e5e7eb inset !important;
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

.analysis-meta {
  margin-top: 16px;
}

.graph-context {
  margin-top: 16px;
}

.graph-context h3 {
  margin: 0 0 10px;
  font-size: 15px;
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
  grid-template-columns: 140px 140px 80px 80px;
  gap: 10px;
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
  .permission-grid,
  .permission-cards {
    grid-template-columns: 1fr;
  }
}
</style>
