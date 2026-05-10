<template>
<section class="datasource-layout">
          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>新增官方数据源</h2>
                <p>管理员配置企业官方库，测试连接后解析 Schema，后续可授权给普通用户。</p>
              </div>
            </div>
            <el-form :model="datasourceForm" label-position="top" class="datasource-form">
              <el-form-item label="数据源名称">
                <el-input v-model="datasourceForm.name" placeholder="例如：企业销售库" />
              </el-form-item>
              <el-form-item label="数据库类型">
                <el-select v-model="datasourceForm.dbType" class="full-width">
                  <el-option label="MySQL" value="MYSQL" />
                  <el-option label="PostgreSQL" value="POSTGRESQL" />
                </el-select>
              </el-form-item>
              <el-form-item label="主机">
                <el-input v-model="datasourceForm.host" placeholder="localhost" />
              </el-form-item>
              <el-form-item label="端口">
                <el-input v-model="datasourceForm.port" placeholder="3306" />
              </el-form-item>
              <el-form-item label="数据库名">
                <el-input v-model="datasourceForm.databaseName" placeholder="insight_spark" />
              </el-form-item>
              <el-form-item label="用户名">
                <el-input v-model="datasourceForm.username" placeholder="root" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="datasourceForm.password" type="password" show-password placeholder="数据库密码" />
              </el-form-item>
              <el-form-item label="最大连接数">
                <el-input v-model="datasourceForm.poolMaxSize" placeholder="10" />
              </el-form-item>
              <el-form-item label="连接超时(ms)">
                <el-input v-model="datasourceForm.poolTimeoutMs" placeholder="30000" />
              </el-form-item>
              <el-form-item label="知识图谱同步规则">
                <el-input
                  v-model="datasourceForm.kgSyncRule"
                  type="textarea"
                  :rows="3"
                  placeholder="例如：同步表字段关系、业务含义、同义词、敏感标识与联邦关联"
                />
              </el-form-item>
            </el-form>
            <div class="datasource-actions">
              <el-button type="primary" @click="createDatasource">保存数据源</el-button>
              <el-button @click="fillCurrentDatasource">填入当前项目库</el-button>
            </div>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>官方数据源列表</h2>
                <p>支持连接测试、Schema 自动解析、启用/禁用。</p>
              </div>
              <el-button @click="loadDatasources">刷新</el-button>
            </div>
            <el-table :data="officialDatasources" height="360" empty-text="暂无官方数据源" @row-click="selectDatasource">
              <el-table-column prop="name" label="名称" min-width="130" />
              <el-table-column prop="databaseName" label="数据库" min-width="130" />
              <el-table-column prop="status" label="状态" width="95">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                    {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="lastTestStatus" label="连接" width="95">
                <template #default="{ row }">
                  <el-tag :type="row.lastTestStatus === 'SUCCESS' ? 'success' : row.lastTestStatus === 'FAILED' ? 'danger' : 'info'" size="small">
                    {{ row.lastTestStatus || '未测试' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="280" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click.stop="testDatasource(row.id)">测试</el-button>
                  <el-button size="small" type="primary" @click.stop="syncDatasourceSchema(row.id)">解析</el-button>
                  <el-button size="small" @click.stop="updateDatasource(row)">保存</el-button>
                  <el-button size="small" :type="row.status === 'ENABLED' ? 'warning' : 'success'" @click.stop="toggleDatasource(row)">
                    {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
                  </el-button>
                  <el-button size="small" type="danger" @click.stop="deleteDatasource(row)">删除</el-button>
                </template>
              </el-table-column>
              <el-table-column label="连接池" width="110">
                <template #default="{ row }">
                  <el-tag
                    :type="datasourceHealthMap[row.id]?.poolCreated ? 'success' : 'info'"
                    size="small"
                  >
                    {{ datasourceHealthMap[row.id]?.poolCreated ? '已创建' : '未创建' }}
                  </el-tag>
                </template>
              </el-table-column>

              <el-table-column label="活跃/总连接" width="130">
                <template #default="{ row }">
                  {{ datasourceHealthMap[row.id]?.activeConnections ?? 0 }}
                  /
                  {{ datasourceHealthMap[row.id]?.totalConnections ?? 0 }}
                </template>
              </el-table-column>

              <el-table-column label="最大连接数" width="110">
                <template #default="{ row }">
                  {{ datasourceHealthMap[row.id]?.maximumPoolSize ?? row.poolMaxSize ?? '-' }}
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>Schema 表结构</h2>
                <p>点击数据源后查看解析出的表。</p>
              </div>
            </div>
            <el-table :data="schemaTables" height="360" empty-text="请选择数据源并解析 Schema" @row-click="selectSchemaTable">
              <el-table-column prop="tableName" label="表名" min-width="180" />
              <el-table-column prop="tableComment" label="注释" min-width="180" show-overflow-tooltip />
              <el-table-column prop="tableRows" label="估算行数" width="110" />
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>字段注释与敏感标记</h2>
                <p>维护业务名和敏感字段，后续用于 Text-to-SQL 与权限脱敏。</p>
              </div>
            </div>
            <el-table :data="schemaFields" height="360" empty-text="请选择 Schema 表">
              <el-table-column prop="columnName" label="字段" min-width="140" />
              <el-table-column prop="dataType" label="类型" width="100" />
              <el-table-column prop="columnComment" label="数据库注释" min-width="150" show-overflow-tooltip />
              <el-table-column label="业务名" min-width="170">
                <template #default="{ row }">
                  <el-input v-model="row.businessName" size="small" @change="updateSchemaField(row)" />
                </template>
              </el-table-column>
              <el-table-column label="业务含义" min-width="220">
                <template #default="{ row }">
                  <el-input v-model="row.businessDesc" size="small" @change="updateSchemaField(row)" />
                </template>
              </el-table-column>
              <el-table-column label="同义词" min-width="180">
                <template #default="{ row }">
                  <el-input v-model="row.synonyms" size="small" placeholder="逗号分隔" @change="updateSchemaField(row)" />
                </template>
              </el-table-column>
              <el-table-column label="同步图谱" width="110">
                <template #default="{ row }">
                  <el-switch v-model="row.kgSyncEnabled" @change="updateSchemaField(row)" />
                </template>
              </el-table-column>
              <el-table-column label="语义规则" min-width="220">
                <template #default="{ row }">
                  <el-input v-model="row.kgSyncRule" size="small" @change="updateSchemaField(row)" />
                </template>
              </el-table-column>
              <el-table-column label="敏感" width="90">
                <template #default="{ row }">
                  <el-switch v-model="row.sensitive" :active-value="1" :inactive-value="0" @change="updateSchemaField(row)" />
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>权限绑定</h2>
                <p>给用户或角色分配官方数据源只读权限，供普通用户申请和查询。</p>
              </div>
            </div>
            <el-form label-position="top" class="datasource-form">
              <el-form-item label="授权对象类型">
                <el-select v-model="datasourcePermissionForm.principalType" class="full-width">
                  <el-option label="用户" value="USER" />
                  <el-option label="角色" value="ROLE" />
                </el-select>
              </el-form-item>
              <el-form-item label="授权对象">
                <el-input v-model="datasourcePermissionForm.principalId" placeholder="user / analyst-role" />
              </el-form-item>
            </el-form>
            <div class="datasource-actions">
              <el-button type="primary" @click="grantDatasourcePermission">绑定权限</el-button>
              <el-button @click="loadDatasourcePermissions()">刷新</el-button>
            </div>
            <el-table :data="datasourcePermissions" height="220" empty-text="请选择数据源">
              <el-table-column prop="principalType" label="类型" width="90" />
              <el-table-column prop="principalId" label="对象" min-width="140" />
              <el-table-column prop="permissionType" label="权限" width="90" />
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button size="small" type="danger" @click="revokeDatasourcePermission(row.id)">回收</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>行级隔离规则</h2>
                <p>按用户或角色追加只读查询过滤条件，后端执行 SQL 时统一强制生效。</p>
              </div>
            </div>
            <el-form label-position="top" class="datasource-form">
              <el-form-item label="表名">
                <el-input v-model="rowPolicyForm.tableName" placeholder="留空表示当前数据源所有表" />
              </el-form-item>
              <el-form-item label="对象类型">
                <el-select v-model="rowPolicyForm.principalType" class="full-width">
                  <el-option label="用户" value="USER" />
                  <el-option label="角色" value="ROLE" />
                </el-select>
              </el-form-item>
              <el-form-item label="对象标识">
                <el-input v-model="rowPolicyForm.principalId" placeholder="user / analyst-role" />
              </el-form-item>
              <el-form-item label="过滤条件">
                <el-input v-model="rowPolicyForm.filterExpression" placeholder="region = '华东' 或 tenant_id = 1001" />
              </el-form-item>
              <el-form-item label="启用">
                <el-switch v-model="rowPolicyForm.enabled" />
              </el-form-item>
            </el-form>
            <div class="datasource-actions">
              <el-button type="primary" @click="saveRowPolicy">保存行级规则</el-button>
              <el-button @click="loadRowPolicies()">刷新</el-button>
            </div>
            <el-table :data="rowPolicies" height="220" empty-text="暂无行级隔离规则">
              <el-table-column prop="tableName" label="表名" min-width="130" />
              <el-table-column prop="principalType" label="对象类型" width="100" />
              <el-table-column prop="principalId" label="对象" min-width="130" />
              <el-table-column prop="filterExpression" label="过滤条件" min-width="220" show-overflow-tooltip />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button size="small" type="danger" @click="deleteRowPolicy(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>Neo4j 知识图谱配置</h2>
                <p>维护图谱连接参数、同步策略与表字段关系映射规则。</p>
              </div>
            </div>
            <el-form label-position="top" class="datasource-form">
              <el-form-item label="Neo4j 地址">
                <el-input v-model="neo4jConfig.uri" placeholder="bolt://localhost:7687" />
              </el-form-item>
              <el-form-item label="账号">
                <el-input v-model="neo4jConfig.username" placeholder="neo4j" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="neo4jConfig.password" type="password" show-password placeholder="留空则不修改密码" />
              </el-form-item>
              <el-form-item label="数据库">
                <el-input v-model="neo4jConfig.databaseName" placeholder="neo4j" />
              </el-form-item>
              <el-form-item label="同步规则">
                <el-input v-model="neo4jConfig.syncRule" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item label="启用同步">
                <el-switch v-model="neo4jConfig.enabled" />
              </el-form-item>
            </el-form>
            <div class="datasource-actions">
              <el-button type="primary" @click="saveNeo4jConfig">保存图谱配置</el-button>
              <el-button @click="loadNeo4jConfig">刷新</el-button>
            </div>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <h2>联邦跨库关联</h2>
                <p>配置官方表字段与上传表字段的关联关系，用于 Agent 生成联合分析 SQL。</p>
              </div>
            </div>
            <el-form label-position="top" class="datasource-form">
              <el-form-item label="官方表">
                <el-input v-model="federalForm.leftTable" placeholder="official_table" />
              </el-form-item>
              <el-form-item label="官方字段">
                <el-input v-model="federalForm.leftField" placeholder="customer_id" />
              </el-form-item>
              <el-form-item label="右侧表">
                <el-input v-model="federalForm.rightTable" placeholder="biz_data_xxx" />
              </el-form-item>
              <el-form-item label="右侧字段">
                <el-input v-model="federalForm.rightField" placeholder="客户ID" />
              </el-form-item>
            </el-form>
            <div class="datasource-actions">
              <el-button type="primary" @click="saveFederalRelation">保存关联</el-button>
              <el-button type="success" @click="generateFederalSql">生成联邦SQL计划</el-button>
              <el-button @click="loadFederalRelations()">刷新</el-button>
            </div>
            <el-alert
              v-if="federalSqlPreview"
              title="Agent 联邦 SQL 计划"
              type="success"
              :closable="false"
              class="federal-preview"
            >
              <pre>{{ federalSqlPreview }}</pre>
            </el-alert>
            <el-table :data="federalRelations" height="220" empty-text="暂无联邦关联">
              <el-table-column prop="leftTable" label="官方表" min-width="130" />
              <el-table-column prop="leftField" label="官方字段" width="120" />
              <el-table-column prop="rightTable" label="右侧表" min-width="130" />
              <el-table-column prop="rightField" label="右侧字段" width="120" />
            </el-table>
          </div>
        </section>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

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
  datasourcePermissions,
  datasourcePermissionForm,
  federalRelations,
  federalForm,
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
  updateDatasource,
  deleteDatasource,
  loadDatasourcePermissions,
  grantDatasourcePermission,
  revokeDatasourcePermission,
  loadFederalRelations,
  saveFederalRelation,
  unwrap,
  updateSchemaField,
  uploadFile,
  uploadResult,
  uploading,
  userQuestion,
  datasourceHealthMap,
  loadDatasourceHealth,
  xAxisData
} = inject('workbench')

const rowPolicies = ref([])
const rowPolicyForm = ref({
  tableName: '',
  principalType: 'USER',
  principalId: 'user',
  filterExpression: '',
  enabled: true
})
const neo4jConfig = ref({
  uri: 'bolt://localhost:7687',
  username: 'neo4j',
  password: '',
  databaseName: 'neo4j',
  syncRule: '同步官方数据源表、字段、业务含义、同义词、敏感标识与联邦关系',
  enabled: true
})
const federalSqlPreview = ref('')

const loadRowPolicies = async (datasourceId = selectedDatasourceId.value) => {
  if (!datasourceId) return
  try {
    rowPolicies.value = unwrap(await axios.get(`${API_BASE}/api/datasources/${datasourceId}/row-policies`))
  } catch (error) {
    ElMessage.error(error.message || '加载行级规则失败')
  }
}

const saveRowPolicy = async () => {
  if (!selectedDatasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  try {
    await axios.post(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/row-policies`, rowPolicyForm.value).then(unwrap)
    ElMessage.success('行级规则已保存')
    rowPolicyForm.value.filterExpression = ''
    await loadRowPolicies()
  } catch (error) {
    ElMessage.error(error.message || '保存行级规则失败')
  }
}

const deleteRowPolicy = async (policyId) => {
  try {
    await axios.post(`${API_BASE}/api/datasources/row-policies/${policyId}/delete`).then(unwrap)
    ElMessage.success('行级规则已删除')
    await loadRowPolicies()
  } catch (error) {
    ElMessage.error(error.message || '删除行级规则失败')
  }
}

const loadNeo4jConfig = async () => {
  try {
    const config = unwrap(await axios.get(`${API_BASE}/api/datasources/neo4j-config`))
    neo4jConfig.value = { ...neo4jConfig.value, ...config, password: '' }
  } catch (error) {
    ElMessage.error(error.message || '加载 Neo4j 配置失败')
  }
}

const saveNeo4jConfig = async () => {
  try {
    const config = unwrap(await axios.post(`${API_BASE}/api/datasources/neo4j-config`, neo4jConfig.value))
    neo4jConfig.value = { ...neo4jConfig.value, ...config, password: '' }
    ElMessage.success('Neo4j 配置已保存')
  } catch (error) {
    ElMessage.error(error.message || '保存 Neo4j 配置失败')
  }
}

const generateFederalSql = async () => {
  if (!selectedDatasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  try {
    const plan = unwrap(await axios.post(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/federal-sql`, {
      uploadTable: federalForm.value?.rightTable || '',
      question: '联邦跨库分析'
    }))
    federalSqlPreview.value = JSON.stringify(plan, null, 2)
  } catch (error) {
    ElMessage.error(error.message || '生成联邦 SQL 计划失败')
  }
}

watch(selectedDatasourceId, (datasourceId) => {
  rowPolicies.value = []
  federalSqlPreview.value = ''
  loadRowPolicies(datasourceId)
})

onMounted(loadNeo4jConfig)
</script>

<style scoped>
.federal-preview {
  margin: 12px 0;
}

.federal-preview pre {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.6;
}
</style>
