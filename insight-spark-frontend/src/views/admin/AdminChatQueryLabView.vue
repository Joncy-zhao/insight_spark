<template>
  <section class="query-lab-page">
    <header class="lab-hero-card">
      <div>
        <h1>管理员对话查询实验室</h1>
        <p>跨数据源验证 Text-to-SQL、知识图谱匹配、SQL 安全、权限隔离和图表渲染。</p>
      </div>
      <div class="hero-actions">
        <el-button :loading="loadingMeta" @click="loadMeta">刷新配置</el-button>
        <el-button class="rerun-latest-btn" :loading="rerunLoading" @click="rerunLatestTest">最近重跑</el-button>
        <el-button class="compare-model-btn" :loading="compareLoading" @click="openCompareDialog">模型对比</el-button>
        <el-button :disabled="!activeSession?.id" @click="exportSession">导出记录 Word</el-button>
        <el-button class="reasoning-export-btn" :disabled="!activeSession?.id" @click="exportReasoningLog">导出推理日志 Word</el-button>
      </div>
    </header>

    <div class="metric-grid">
      <article class="metric-card metric-card--pink">
        <div class="metric-icon">库</div>
        <div>
          <span>当前数据源</span>
          <strong>{{ currentDatasourceSummary }}</strong>
          <small>全部数据源 / {{ totalRowCount }}行</small>
        </div>
      </article>
      <article class="metric-card metric-card--blue">
        <div class="metric-icon">AI</div>
        <div>
          <span>已选大模型</span>
          <strong>{{ selectedModelName }}</strong>
          <small>已选大模型与大模型</small>
        </div>
      </article>
      <article class="metric-card metric-card--purple">
        <div class="metric-icon">问</div>
        <div>
          <span>测试指令数</span>
          <strong>{{ historyItems.length || (form.question ? 1 : 0) }}</strong>
          <small>测试指令数</small>
        </div>
      </article>
      <article class="metric-card metric-card--green">
        <div class="metric-icon">表</div>
        <div>
          <span>数据表统计</span>
          <strong>官方: {{ officialDatasources.length }} / 上传: {{ uploadDatasources.length }}</strong>
          <small>官方：{{ officialDatasources.length }} / 上传表统计</small>
        </div>
      </article>
    </div>

    <div class="lab-work-grid">
      <section class="lab-panel config-panel">
        <div class="section-head">
          <h2>测试配置</h2>
          <el-tag type="warning" effect="plain">ADMIN TEST</el-tag>
        </div>

        <el-form label-position="top" class="config-form">
          <el-form-item label="测试数据源">
            <el-select v-model="form.selectedTables" multiple filterable collapse-tags collapse-tags-tooltip class="full-width">
              <el-option-group label="官方数据库">
                <el-option v-for="item in officialDatasources" :key="item.tableName" :label="formatDatasourceLabel(item)" :value="item.tableName" />
              </el-option-group>
              <el-option-group label="用户上传表">
                <el-option v-for="item in uploadDatasources" :key="item.tableName" :label="formatDatasourceLabel(item)" :value="item.tableName" />
              </el-option-group>
            </el-select>
          </el-form-item>
          <el-form-item label="底层大模型">
            <el-select v-model="form.modelId" class="full-width">
              <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" :disabled="model.available === false" />
            </el-select>
          </el-form-item>
          <el-form-item label="常用测试指令模板">
            <div class="template-row">
              <el-scrollbar>
                <div class="template-chip-row">
                  <el-tooltip
                    v-for="template in templates"
                    :key="template.id"
                    placement="top"
                    effect="light"
                    :show-after="300"
                    popper-class="template-tooltip"
                  >
                    <template #content>
                      <div class="template-tooltip-content">
                        <strong>{{ template.templateName }}</strong>
                        <p>{{ template.question || '暂无测试指令内容' }}</p>
                      </div>
                    </template>
                    <el-button
                      class="template-chip"
                      size="small"
                      plain
                      @click="applyTemplate(template)"
                    >
                      <span>{{ template.templateName }}</span>
                      <button class="template-delete-btn" type="button" aria-label="删除模板" @click.stop="deleteTemplate(template)">×</button>
                    </el-button>
                  </el-tooltip>
                  <span v-if="!templates.length" class="template-empty">暂无模板</span>
                </div>
              </el-scrollbar>
              <el-button size="small" :loading="templateSaving" @click="saveCurrentTemplate">保存模板</el-button>
            </div>
          </el-form-item>
          <div class="two-col">
            <el-form-item label="生成温度" class="temperature-form-item">
              <div class="temperature-control">
                <el-slider v-model="form.temperature" :min="0" :max="1" :step="0.1" :show-tooltip="false" />
                <span>{{ Number(form.temperature).toFixed(1) }}</span>
              </div>
            </el-form-item>
            <el-form-item label="超时时间（秒）">
              <el-input-number v-model="form.timeoutSeconds" :min="5" :max="120" controls-position="right" placeholder="5-120" />
            </el-form-item>
          </div>
          <div class="two-col">
            <el-form-item label="模拟用户">
              <el-input v-model.trim="form.simulatedUserId" placeholder="可选" />
            </el-form-item>
            <el-form-item label="模拟角色">
              <el-select v-model="form.simulatedRole" clearable placeholder="可选">
                <el-option label="普通用户" value="USER" />
                <el-option label="管理员" value="ADMIN" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="自然语言测试指令">
            <el-input
              v-model.trim="form.question"
              type="textarea"
              :rows="3"
              resize="none"
              placeholder="例如：校区域看本月 GMV 指南前十，并检查敏感字段是否脱敏"
            />
          </el-form-item>
        </el-form>

        <div class="button-row">
          <el-button type="primary" :loading="running" @click="runStreamTest">流式测试</el-button>
          <el-button :disabled="running || !activeSession?.id" @click="runPermissionCheck">权限穿透测试</el-button>
          <el-button class="permission-compare-btn" :disabled="!permissionComparisonRows.length" @click="permissionDialogVisible = true">权限差异</el-button>
        </div>
      </section>

      <section class="lab-panel reasoning-panel">
        <div class="section-head">
          <h2>推理思考过程</h2>
        </div>
        <div v-if="streamSteps.length" class="reasoning-list">
          <div v-for="(step, index) in streamSteps" :key="`${step.eventName}-${index}`" class="reasoning-item">
            <span>{{ index + 1 }}</span>
            <div>
              <strong>{{ eventTitle(step.eventName) }}</strong>
              <small>{{ step.detail }}</small>
            </div>
          </div>
        </div>
        <el-empty v-else description="等待发起测试" />
      </section>

      <section class="lab-panel sql-panel">
        <div class="section-head">
          <h2>SQL 与安全校验</h2>
          <!-- <el-button size="small" :disabled="!result?.sql" @click="copySql">复制 SQL</el-button> -->
        </div>
        <div class="sql-card">
          <div class="sql-card-head">
            <span>生成的 SQL</span>
            <el-button size="small" text :disabled="!result?.sql" @click="copySql">复制 SQL</el-button>
          </div>
          <pre>{{ result?.sql || 'SELECT `col_005` AS dim_name_…\n  FROM `col_00n` Field3;' }}</pre>
        </div>
        <div class="security-grid">
          <div><span>安全说明</span><strong>{{ result?.riskReason || '等待检测' }}</strong></div>
          <div><span>解析引擎</span><strong>{{ result?.engine || '等待解析' }}</strong></div>
          <div><span>数据源类型</span><strong>{{ result?.sourceType || '-' }}</strong></div>
          <div><span>安全等级</span><strong><el-tag size="small" :type="riskTagType(result?.riskLevel)">{{ result?.riskLevel || '待检测' }}</el-tag></strong></div>
          <div><span>使用模型</span><strong>{{ result?.modelName || activeSessionModelName }}</strong></div>
          <div><span>会话状态</span><strong><el-tag size="small" :type="statusTagType(activeSession?.status)">{{ activeSession?.status || '待执行' }}</el-tag></strong></div>
        </div>
      </section>

      <aside class="right-stack">
        <section class="lab-panel chart-panel">
          <div class="section-head">
            <h2>图表渲染测试</h2>
            <el-button class="chart-full-btn" size="small" type="primary" plain :disabled="!resultRows.length" @click="openChartDialog">查看完整图表</el-button>
          </div>
          <el-select v-model="chartTypeOverride" size="small" class="full-width" clearable placeholder="图表渲染" @change="renderResultChart">
            <el-option label="柱状图" value="bar" />
            <el-option label="折线图" value="line" />
            <el-option label="饼图" value="pie" />
          </el-select>
          <div class="chart-thumb-shell">
            <div ref="chartRef" class="chart-box"></div>
            <div v-if="!resultRows.length" class="chart-thumb-empty">暂无图表预览</div>
          </div>
        </section>

        <section class="lab-panel result-panel">
          <div class="section-head">
            <h2>执行结果</h2>
            <el-button class="result-full-btn" size="small" plain :disabled="!resultRows.length" @click="openResultDialog">查看完整结果</el-button>
          </div>
          <el-table :data="resultRows" height="130" empty-text="无数据" class="result-table">
            <el-table-column v-for="column in visibleResultColumns" :key="column" :prop="column" :label="column" min-width="70" />
          </el-table>
        </section>
      </aside>
    </div>

    <section class="lab-panel record-panel">
      <div class="section-head">
        <h2>测试记录</h2>
      </div>
      <el-table :data="historyItems" height="168" empty-text="暂无测试记录" class="record-table">
        <el-table-column prop="createdAt" label="测试时间" min-width="150" />
        <el-table-column prop="testerUserId" label="用户" width="120" />
        <el-table-column prop="question" label="指令摘要" min-width="420" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="160" />
        <el-table-column prop="riskLevel" label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ row.riskLevel || '安全' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button class="record-action-btn record-action-btn--refresh" size="small" plain :loading="rerunningSessionId === row.id" @click="rerunHistory(row.id)">重试</el-button>
            <el-button class="record-action-btn record-action-btn--detail" size="small" plain @click="openHistory(row.id)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <span>共 {{ historyItems.length }} 条</span>
        <el-pagination :current-page="1" :page-size="10" layout="sizes, prev, pager, next" :total="historyItems.length" />
      </div>
    </section>

    <el-dialog v-model="chartDialogVisible" title="完整图表" width="820px" class="chart-dialog" @opened="renderFullChart">
      <div ref="fullChartRef" class="full-chart-box"></div>
      <template #footer>
        <el-button class="chart-export-btn" plain :disabled="!resultRows.length" @click="exportChartImage">导出图片</el-button>
        <el-button type="primary" @click="chartDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resultDialogVisible" title="完整执行结果" width="880px" class="result-dialog">
      <el-table :data="resultRows" border height="520" empty-text="暂无执行结果">
        <el-table-column
          v-for="column in resultColumns"
          :key="column"
          :prop="column"
          :label="column"
          min-width="140"
          show-overflow-tooltip
        />
      </el-table>
    </el-dialog>

    <el-dialog v-model="compareDialogVisible" title="多模型结果对比" width="860px" class="compare-dialog">
      <div class="compare-toolbar">
        <span>将当前测试指令按已接入模型串行执行，便于观察 SQL、耗时与安全结果差异。</span>
        <el-button class="compare-model-btn" size="small" :loading="compareLoading" @click="runModelCompare">开始对比</el-button>
      </div>
      <el-table :data="compareRows" border height="360" empty-text="暂无对比结果">
        <el-table-column prop="modelName" label="模型" min-width="150" />
        <el-table-column prop="riskLevel" label="风险" width="100">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ row.riskLevel || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时(ms)" width="110" />
        <el-table-column prop="sql" label="生成 SQL" min-width="420" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <el-dialog v-model="permissionDialogVisible" title="权限差异对比" width="900px" class="permission-dialog">
      <el-table :data="permissionComparisonRows" border height="360" empty-text="暂无权限差异数据">
        <el-table-column prop="dimension" label="维度" width="120" />
        <el-table-column prop="adminScope" label="管理员测试态" min-width="300" show-overflow-tooltip />
        <el-table-column prop="userScope" label="普通用户边界" min-width="300" show-overflow-tooltip />
        <el-table-column prop="verdict" label="结论" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="permissionVerdictType(row.verdict)">{{ permissionVerdictLabel(row.verdict) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <p class="permission-conclusion">{{ permissionCheckResult?.conclusion || '请先执行权限穿透测试。' }}</p>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  compareAdminChatQueryModels,
  createAdminChatQuerySession,
  exportAdminChatQueryReasoningUrl,
  deleteAdminChatQueryTemplate,
  exportAdminChatQuerySessionUrl,
  fetchAdminChatQueryDatasources,
  fetchAdminChatQueryModels,
  fetchAdminChatQuerySession,
  fetchAdminChatQuerySessions,
  fetchAdminChatQueryTemplates,
  rerunAdminChatQuerySession,
  rerunLatestAdminChatQuery,
  runAdminPermissionCheck,
  saveAdminChatQueryTemplate,
  streamAdminChatQuerySession
} from '../../api/adminChatQuery'
import { attachAuthHeader } from '../../api/http'

const loadingMeta = ref(false)
const running = ref(false)
const historyLoading = ref(false)
const rerunLoading = ref(false)
const templateSaving = ref(false)
const compareLoading = ref(false)
const datasources = ref([])
const models = ref([])
const templates = ref([])
const activeSession = ref(null)
const result = ref(null)
const permissionCheckResult = ref(null)
const streamSteps = ref([])
const historyItems = ref([])
const rerunningSessionId = ref(null)
const chartRef = ref(null)
const fullChartRef = ref(null)
const chartTypeOverride = ref('')
const chartDialogVisible = ref(false)
const resultDialogVisible = ref(false)
const compareDialogVisible = ref(false)
const permissionDialogVisible = ref(false)
const compareResult = ref(null)
let chartInstance = null
let fullChartInstance = null
const labRules = ref([
  { name: '只允许 SELECT', status: 'BLOCKED', enabled: true },
  { name: '危险关键字拦截', status: 'BLOCKED', enabled: true },
  { name: '多语句拦截', status: 'BLOCKED', enabled: true },
  { name: '授权表范围校验', status: 'BLOCKED', enabled: true }
])

const form = ref({
  selectedTables: [],
  modelId: 'default',
  temperature: 0.2,
  timeoutSeconds: 30,
  simulatedUserId: '',
  simulatedRole: '',
  question: ''
})

const officialDatasources = computed(() =>
  datasources.value.filter((item) => String(item.sourceType || '').toUpperCase() === 'OFFICIAL')
)
const uploadDatasources = computed(() =>
  datasources.value.filter((item) => String(item.sourceType || '').toUpperCase() !== 'OFFICIAL')
)
const resultRows = computed(() => Array.isArray(result.value?.data) ? result.value.data : [])
const resultColumns = computed(() => resultRows.value.length ? Object.keys(resultRows.value[0]) : [])
const visibleResultColumns = computed(() => resultColumns.value.length ? resultColumns.value.slice(0, 5) : ['ID', 'User', 'Field1', 'Field2', 'Field3'])
const compareRows = computed(() => Array.isArray(compareResult.value?.results) ? compareResult.value.results : [])
const permissionComparisonRows = computed(() =>
  Array.isArray(permissionCheckResult.value?.comparisonRows) ? permissionCheckResult.value.comparisonRows : []
)
const totalRowCount = computed(() => datasources.value.reduce((sum, item) => sum + Number(item.rowCount || 0), 0))
const currentDatasourceSummary = computed(() => {
  const selected = datasources.value.find((item) => item.tableName === form.value.selectedTables[0])
  if (!selected) {
    return '未选择'
  }
  const type = String(selected.sourceType || 'UPLOAD').toUpperCase()
  return `${type} / ${Number(selected.rowCount || 0)}行`
})
const selectedModelName = computed(() => models.value.find((item) => item.id === form.value.modelId)?.name || '默认模型')
const activeSessionModelName = computed(() => resolveSessionModelName(activeSession.value))
const safeCount = computed(() => historyItems.value.filter((item) => item.riskLevel === 'SAFE').length)
const warnCount = computed(() => historyItems.value.filter((item) => item.riskLevel === 'WARN').length)
const blockedCount = computed(() => historyItems.value.filter((item) => item.riskLevel === 'BLOCKED' || item.status === 'FAILED').length)

const loadMeta = async () => {
  loadingMeta.value = true
  try {
    const [sourceRows, modelRows] = await Promise.all([
      fetchAdminChatQueryDatasources(),
      fetchAdminChatQueryModels()
    ])
    datasources.value = sourceRows || []
    models.value = modelRows || []
    if (models.value.length && !models.value.some((item) => item.id === form.value.modelId)) {
      const availableModel = models.value.find((item) => item.available !== false) || models.value[0]
      form.value.modelId = availableModel.id
    }
    if (!form.value.selectedTables.length && datasources.value[0]?.tableName) {
      form.value.selectedTables = [datasources.value[0].tableName]
    }
  } catch (error) {
    ElMessage.error(error.message || '加载配置失败')
  } finally {
    loadingMeta.value = false
  }
}

const loadTemplates = async () => {
  try {
    templates.value = await fetchAdminChatQueryTemplates() || []
  } catch (error) {
    ElMessage.error(error.message || '加载模板失败')
  }
}

const loadHistory = async () => {
  historyLoading.value = true
  try {
    const page = await fetchAdminChatQuerySessions({ page: 1, pageSize: 8 })
    historyItems.value = page.items || []
  } catch (error) {
    ElMessage.error(error.message || '加载测试记录失败')
  } finally {
    historyLoading.value = false
  }
}

const runStreamTest = async () => {
  if (!form.value.question) {
    ElMessage.warning('请输入测试指令')
    return
  }
  if (!form.value.selectedTables.length) {
    ElMessage.warning('请选择测试数据源')
    return
  }
  running.value = true
  result.value = null
  permissionCheckResult.value = null
  activeSession.value = null
  chartTypeOverride.value = ''
  streamSteps.value = []
  try {
    const session = await createAdminChatQuerySession(buildPayload())
    activeSession.value = session
    await streamAdminChatQuerySession(session.id, {
      question: form.value.question,
      tableName: form.value.selectedTables[0]
    }, {
      onEvent: handleStreamEvent
    })
    await refreshActiveSession(session.id)
    await loadHistory()
  } catch (error) {
    ElMessage.error(error.message || '测试执行失败')
  } finally {
    running.value = false
  }
}

const runPermissionCheck = async () => {
  if (!activeSession.value?.id) return
  try {
    const payload = await runAdminPermissionCheck(activeSession.value.id, {
      simulatedUserId: form.value.simulatedUserId,
      simulatedRole: form.value.simulatedRole,
      selectedTables: form.value.selectedTables
    })
    permissionCheckResult.value = payload
    pushStep('PERMISSION_CHECKED', payload)
    await refreshActiveSession(activeSession.value.id)
    ElMessage.success('权限穿透测试完成')
  } catch (error) {
    ElMessage.error(error.message || '权限测试失败')
  }
}

const saveCurrentTemplate = async () => {
  if (!form.value.question) {
    ElMessage.warning('请输入测试指令后再保存模板')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请输入模板名称', '保存常用测试指令', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '模板名称不能为空'
    })
    templateSaving.value = true
    await saveAdminChatQueryTemplate({
      templateName: value,
      ...buildPayload()
    })
    await loadTemplates()
    ElMessage.success('模板已保存')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '保存模板失败')
    }
  } finally {
    templateSaving.value = false
  }
}

const applyTemplate = (template) => {
  form.value.question = template.question || ''
  const scope = template.datasourceScope || {}
  const modelConfig = template.modelConfig || {}
  form.value.selectedTables = Array.isArray(scope.selectedTables) ? scope.selectedTables : form.value.selectedTables
  form.value.modelId = modelConfig.modelId || form.value.modelId
  form.value.temperature = Number(modelConfig.temperature ?? form.value.temperature)
  form.value.timeoutSeconds = Number(modelConfig.timeoutSeconds ?? form.value.timeoutSeconds)
  ElMessage.success('已套用模板')
}

const deleteTemplate = async (template) => {
  try {
    await ElMessageBox.confirm(`确定删除模板“${template.templateName}”吗？`, '删除测试指令模板', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteAdminChatQueryTemplate(template.id)
    templates.value = templates.value.filter((item) => item.id !== template.id)
    ElMessage.success('模板已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '删除模板失败')
    }
  }
}

const rerunLatestTest = async () => {
  rerunLoading.value = true
  try {
    const session = await rerunLatestAdminChatQuery()
    await hydrateExecutedSession(session)
    await loadHistory()
    ElMessage.success('最近测试已重跑')
  } catch (error) {
    ElMessage.error(error.message || '最近重跑失败')
  } finally {
    rerunLoading.value = false
  }
}

const rerunHistory = async (sessionId) => {
  rerunningSessionId.value = sessionId
  try {
    const session = await rerunAdminChatQuerySession(sessionId)
    await hydrateExecutedSession(session)
    await loadHistory()
    ElMessage.success('测试记录已重跑')
  } catch (error) {
    ElMessage.error(error.message || '重跑失败')
  } finally {
    rerunningSessionId.value = null
  }
}

const hydrateExecutedSession = async (session) => {
  const sessionId = session?.id
  if (!sessionId) return
  await refreshActiveSession(sessionId)
  streamSteps.value = (activeSession.value?.steps || []).map((step) => ({
    eventName: step.stepType,
    detail: summarizePayload(step.stepType, step.stepPayload || {}),
    time: step.finishedAt || step.startedAt || ''
  }))
}

const openCompareDialog = () => {
  compareDialogVisible.value = true
}

const runModelCompare = async () => {
  if (!form.value.question) {
    ElMessage.warning('请输入测试指令')
    return
  }
  if (!form.value.selectedTables.length) {
    ElMessage.warning('请选择测试数据源')
    return
  }
  compareLoading.value = true
  try {
    compareResult.value = await compareAdminChatQueryModels(buildPayload())
    await loadHistory()
    ElMessage.success('模型对比完成')
  } catch (error) {
    ElMessage.error(error.message || '模型对比失败')
  } finally {
    compareLoading.value = false
  }
}

const refreshActiveSession = async (sessionId) => {
  result.value = null
  permissionCheckResult.value = null
  const detail = await fetchAdminChatQuerySession(sessionId)
  activeSession.value = detail
  const artifact = (detail.artifacts || []).find((item) => item.artifactType === 'SQL')?.artifact
  const permissionArtifact = (detail.artifacts || []).find((item) => item.artifactType === 'PERMISSION')?.artifact
  result.value = artifact || null
  permissionCheckResult.value = permissionArtifact || null
  await nextTick()
  renderResultChart()
}

const openHistory = async (sessionId) => {
  try {
    await refreshActiveSession(sessionId)
  } catch (error) {
    ElMessage.error(error.message || '打开记录失败')
  }
}

const handleStreamEvent = (eventName, payload) => {
  pushStep(eventName, payload)
  if (eventName === 'FINISHED') {
    result.value = payload.result || payload
    nextTick(renderResultChart)
  }
}

const pushStep = (eventName, payload) => {
  streamSteps.value.push({
    eventName,
    detail: summarizePayload(eventName, payload),
    time: new Date().toLocaleTimeString()
  })
}

const buildPayload = () => ({
  question: form.value.question,
  selectedTables: form.value.selectedTables,
  tableName: form.value.selectedTables[0],
  modelId: form.value.modelId,
  temperature: form.value.temperature,
  timeoutSeconds: form.value.timeoutSeconds,
  simulatedUserId: form.value.simulatedUserId,
  simulatedRole: form.value.simulatedRole
})

const renderResultChart = () => {
  chartInstance = renderChartInto(chartRef.value, chartInstance, true)
  if (chartDialogVisible.value) {
    fullChartInstance = renderChartInto(fullChartRef.value, fullChartInstance, false)
  }
}

const renderFullChart = () => {
  fullChartInstance = renderChartInto(fullChartRef.value, fullChartInstance, false)
}

const openChartDialog = async () => {
  chartDialogVisible.value = true
  await nextTick()
  renderFullChart()
}

const openResultDialog = () => {
  resultDialogVisible.value = true
}

const renderChartInto = (target, instance, thumbnail = false) => {
  if (!target) return instance
  const chart = echarts.getInstanceByDom(target) || echarts.init(target)
  const rows = resultRows.value
  const chartType = chartTypeOverride.value || result.value?.chartType || 'bar'
  const names = rows.map((row) => row.name ?? row.dim_name ?? row.dimension ?? Object.values(row)[0])
  const values = rows.map((row) => Number(row.value ?? row.metric_value ?? Object.values(row)[1] ?? 0))
  if (!rows.length) {
    chart.clear()
    return chart
  }
  if (chartType === 'pie') {
    chart.setOption({
      tooltip: { trigger: 'item' },
      legend: thumbnail ? { show: false } : { type: 'scroll', bottom: 0 },
      series: [{
        type: 'pie',
        radius: thumbnail ? ['42%', '72%'] : ['38%', '68%'],
        center: thumbnail ? ['50%', '48%'] : ['50%', '46%'],
        label: thumbnail
          ? {
              show: true,
              formatter: (params) => params.dataIndex < 4 ? params.name : '',
              fontSize: 10,
              color: '#52627e'
            }
          : { show: true },
        labelLine: thumbnail
          ? { show: true, length: 8, length2: 6, lineStyle: { color: '#9fb2d0' } }
          : { show: true },
        data: names.map((name, index) => ({ name, value: values[index] }))
      }]
    }, true)
    return chart
  }
  chart.setOption({
    tooltip: { trigger: 'axis' },
  grid: thumbnail
      ? { left: 42, right: 10, top: 18, bottom: 36 }
      : { left: 48, right: 18, top: 24, bottom: 54 },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: thumbnail
        ? {
            interval: Math.max(0, Math.ceil(names.length / 5) - 1),
            rotate: 0,
            fontSize: 10,
            color: '#64748b',
            width: 42,
            overflow: 'truncate'
          }
        : { interval: 0, rotate: names.length > 6 ? 28 : 0 }
    },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#e5ebf3' } } },
    series: [{
      type: chartType === 'line' ? 'line' : 'bar',
      smooth: chartType === 'line',
      data: values,
      barMaxWidth: thumbnail ? 18 : 34
    }]
  }, true)
  return chart
}

const exportSession = async () => {
  if (!activeSession.value?.id) return
  try {
    const headers = {}
    attachAuthHeader({ headers })
    const response = await fetch(exportAdminChatQuerySessionUrl(activeSession.value.id), {
      method: 'POST',
      headers
    })
    if (!response.ok) {
      const message = await response.text()
      throw new Error(message || `导出失败：${response.status}`)
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `admin-chat-query-session-${activeSession.value.id}.docx`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error.message || '导出失败')
  }
}

const exportReasoningLog = async () => {
  if (!activeSession.value?.id) return
  try {
    const headers = {}
    attachAuthHeader({ headers })
    const response = await fetch(exportAdminChatQueryReasoningUrl(activeSession.value.id), {
      method: 'POST',
      headers
    })
    if (!response.ok) {
      const message = await response.text()
      throw new Error(message || `导出失败：${response.status}`)
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `admin-chat-query-reasoning-${activeSession.value.id}.docx`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error.message || '导出推理日志失败')
  }
}

const exportChartImage = () => {
  if (!chartInstance || !resultRows.value.length) return
  const url = chartInstance.getDataURL({
    type: 'png',
    pixelRatio: 2,
    backgroundColor: '#ffffff'
  })
  const link = document.createElement('a')
  link.href = url
  link.download = `admin-chat-query-chart-${activeSession.value?.id || 'draft'}.png`
  link.click()
}

const downloadText = (filename, content) => {
  const blob = new Blob([content], { type: 'application/json;charset=UTF-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

const copySql = async () => {
  if (!result.value?.sql) return
  await navigator.clipboard.writeText(result.value.sql)
  ElMessage.success('SQL 已复制')
}

const formatDatasourceLabel = (item) =>
  `${item.displayName || item.tableName} (${item.sourceType || 'UPLOAD'} / ${item.rowCount || 0}行)`

const resolveSessionModelName = (session) => {
  const modelConfig = session?.modelConfig || {}
  const modelId = modelConfig.modelId
  if (modelId) {
    const matched = models.value.find((item) => String(item.id) === String(modelId))
    if (matched?.name) {
      return matched.name
    }
    if (modelId === 'default') {
      return '默认模型'
    }
    return String(modelId)
  }
  return '未记录'
}

const summarizePayload = (eventName, payload) => {
  if (eventName === 'SQL_GENERATED') return payload.sql || 'SQL 已生成'
  if (eventName === 'ERROR') return payload.message || '执行异常'
  if (eventName === 'FINISHED') return payload.result?.message || payload.message || '测试完成'
  if (eventName === 'PERMISSION_CHECKED') return `${payload.result || 'PASS'}：${payload.rowLevelPolicy || ''}`
  return payload.message || payload.question || payload.tableName || eventTitle(eventName)
}

const eventTitle = (eventName) => ({
  SESSION_CREATED: '测试会话创建',
  QUESTION_PARSED: '自然语言解析',
  KG_MATCHING: '知识图谱导航匹配',
  MODEL_REASONING: '大模型推理',
  SQL_GENERATED: 'SQL 生成',
  SQL_SECURITY_CHECKED: 'SQL 安全校验',
  QUERY_EXECUTED: '查询执行',
  CHART_RECOMMENDED: '图表推荐',
  PERMISSION_CHECKED: '权限穿透测试',
  FINISHED: '执行完成',
  ERROR: '执行异常'
}[eventName] || eventName)

const chartTypeLabel = (type) => ({ bar: '柱状图', line: '折线图', pie: '饼图', table: '表格' }[type] || type || '-')
const riskTagType = (risk) => risk === 'BLOCKED' ? 'danger' : risk === 'WARN' ? 'warning' : 'success'
const statusTagType = (status) => status === 'FAILED' ? 'danger' : status === 'SUCCESS' ? 'success' : status === 'RUNNING' ? 'warning' : 'info'
const permissionVerdictType = (verdict) => verdict === 'ADMIN_WIDER' ? 'warning' : verdict === 'SAME_SECURITY' ? 'success' : 'info'
const permissionVerdictLabel = (verdict) => ({
  ADMIN_WIDER: '管理员更宽',
  USER_LIMITED: '用户受限',
  MASKED: '脱敏',
  SAME_SECURITY: '同等安全',
  AUDITED: '已留痕'
}[verdict] || verdict || '-')

onMounted(async () => {
  await loadMeta()
  await loadTemplates()
  await loadHistory()
})

onBeforeUnmount(() => {
  chartInstance?.dispose()
  fullChartInstance?.dispose()
})
</script>

<style scoped>
.query-lab-page {
  display: grid;
  gap: 14px;
  color: #111a33;
}

.lab-hero-card,
.metric-card,
.lab-panel {
  border: 1px solid #d7e1f0;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 12px 32px rgba(30, 61, 104, 0.08);
}

.lab-hero-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
}

.lab-hero-card h1 {
  margin: 0;
  color: #101832;
  font-size: 24px;
  font-weight: 800;
  line-height: 1.25;
}

.lab-hero-card p {
  margin: 8px 0 0;
  color: #53627f;
  font-size: 14px;
}

.hero-actions,
.section-head,
.button-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.hero-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  min-height: 92px;
  padding: 15px 20px;
}

.metric-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  font-weight: 800;
  box-shadow: 0 12px 24px rgba(47, 124, 246, 0.18);
}

.metric-card--pink .metric-icon {
  background: linear-gradient(135deg, #f462a5, #d93680);
}

.metric-card--blue .metric-icon {
  background: linear-gradient(135deg, #5fa8ff, #266bd8);
}

.metric-card--purple .metric-icon {
  background: linear-gradient(135deg, #8765f5, #4a35d5);
}

.metric-card--green .metric-icon {
  background: linear-gradient(135deg, #2ecf7a, #0aa75b);
}

.metric-card span {
  display: block;
  color: #17213b;
  font-size: 14px;
  font-weight: 800;
}

.metric-card strong {
  display: block;
  margin-top: 5px;
  color: #151b2d;
  font-size: 21px;
  line-height: 1.2;
  font-weight: 800;
  word-break: break-word;
}

.metric-card small {
  display: block;
  margin-top: 5px;
  color: #66758e;
  font-size: 12px;
}

.lab-work-grid {
  display: grid;
  grid-template-columns: 360px minmax(230px, 0.72fr) minmax(300px, 0.9fr) 280px;
  gap: 14px;
  align-items: stretch;
  height: 550px;
}

.right-stack {
  display: grid;
  grid-template-rows: minmax(0, 1fr) 204px;
  gap: 14px;
  min-width: 0;
  height: 100%;
}

.lab-panel {
  min-width: 0;
  padding: 14px;
}

.reasoning-panel,
.sql-panel,
.right-stack {
  min-height: 0;
  height: 100%;
}

.config-panel {
  min-height: 0;
  height: 100%;
}

.section-head {
  margin-bottom: 12px;
}

.section-head h2 {
  margin: 0;
  color: #111a33;
  font-size: 18px;
  line-height: 1.3;
  font-weight: 800;
}

.full-width {
  width: 100%;
}

.config-form :deep(.el-form-item) {
  margin-bottom: 8px;
}

.config-form :deep(.el-form-item__label) {
  margin-bottom: 4px;
  color: #263653;
  font-size: 13px;
  font-weight: 700;
}

.temperature-control {
  width: 100%;
  height: 32px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 38px;
  align-items: center;
  /* gap: 10px */
}

.temperature-control span {
  color: #263653;
  font-size: 13px;
  font-weight: 700;
  text-align: right;
}

.temperature-control :deep(.el-slider) {
  --el-slider-main-bg-color: #3d9af6;
  --el-slider-runway-bg-color: #e5edf7;
}

.temperature-form-item :deep(.el-form-item__content) {
  min-height: 32px;
}

.template-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 72px;
  gap: 8px;
  align-items: center;
}

.template-chip-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: max-content;
  height: 30px;
}

.template-chip {
  max-width: 132px;
  color: #1677d2;
  background: #eef7ff;
  border-color: #8cc7ff;
  font-weight: 700;
  padding-right: 7px;
}

.template-chip :deep(span) {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  gap: 6px;
}

.template-chip :deep(span > span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-chip:hover,
.template-chip:focus {
  color: #fff;
  background: #2f8ff0;
  border-color: #2f8ff0;
}

.template-delete-btn {
  width: 16px;
  height: 16px;
  display: inline-grid;
  place-items: center;
  flex: 0 0 auto;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: #5f7fa6;
  background: transparent;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.template-delete-btn:hover,
.template-delete-btn:focus {
  color: #fff;
  background: rgba(220, 38, 38, 0.9);
  outline: none;
}

.template-chip:hover .template-delete-btn,
.template-chip:focus .template-delete-btn {
  color: #fff;
}

.template-empty {
  color: #8a98ad;
  font-size: 12px;
}

.config-form :deep(.el-textarea__inner) {
  min-height: 78px !important;
}

.two-col,
.security-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.button-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: 2px;
}

.button-row :deep(.el-button) {
  width: 100%;
  min-width: 0;
  margin-left: 0;
}

.security-grid div {
  display: grid;
  gap: 5px;
  min-height: 54px;
  padding: 9px;
  border: 1px solid #e2e9f5;
  border-radius: 6px;
  background: linear-gradient(180deg, #fff 0%, #f7faff 100%);
}

.security-grid span {
  color: #52617b;
  font-size: 12px;
}

.security-grid strong {
  color: #151b2d;
  font-size: 14px;
  word-break: break-word;
}

.reasoning-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.reasoning-panel :deep(.el-empty) {
  flex: 1;
}

.reasoning-list {
  flex: 1;
  min-height: 0;
  max-height: 100%;
  display: grid;
  gap: 8px;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
  align-content: start;
  scrollbar-width: none;
}

.reasoning-list::-webkit-scrollbar,
.sql-card pre::-webkit-scrollbar {
  display: none;
}

.reasoning-item {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 8px;
  padding: 10px;
  border: 1px solid #e4ebf7;
  border-radius: 8px;
  background: #f8fbff;
}

.reasoning-item > span {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #eaf2ff;
  color: #2f7cf6;
  font-size: 12px;
  font-weight: 800;
}

.reasoning-item strong {
  display: block;
  color: #15213a;
  font-size: 13px;
}

.reasoning-item small {
  display: block;
  margin-top: 4px;
  color: #5f6f8b;
  line-height: 1.5;
  word-break: break-word;
}

.sql-card {
  overflow: hidden;
  border-radius: 7px;
  background: #111827;
  box-shadow: inset 0 0 0 1px rgba(255,255,255,0.08);
}

.sql-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  color: #dbeafe;
  font-size: 12px;
}

.sql-card pre {
  height: 220px;
  margin: 0;
  overflow: auto;
  padding: 0 12px 12px;
  color: #6ee7ff;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  scrollbar-width: none;
}

.security-grid {
  margin-top: 10px;
}

.chart-panel {
  min-height: 260px;
}

.chart-thumb-shell {
  position: relative;
  height: 166px;
  margin-top: 10px;
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
}

.chart-box {
  width: 100%;
  height: 220px;
  margin-top: -12px;
}

.chart-thumb-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #9aa5b5;
  font-size: 13px;
  pointer-events: none;
}

.full-chart-box {
  width: 100%;
  height: 520px;
}

.chart-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}

.result-panel {
  min-height: 204px;
}

.result-table :deep(th.el-table__cell),
.record-table :deep(th.el-table__cell) {
  background: #f4f7fb;
  color: #111a33;
  font-weight: 800;
}

.record-panel {
  padding: 16px;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
  color: #52617b;
}

.query-lab-page :deep(.el-input__wrapper),
.query-lab-page :deep(.el-select__wrapper),
.query-lab-page :deep(.el-textarea__inner) {
  border-radius: 4px;
  box-shadow: 0 0 0 1px #d5dfed inset;
}

.query-lab-page :deep(.el-button) {
  border-radius: 4px;
}

.query-lab-page :deep(.el-button--primary) {
  background: #3d9af6;
  border-color: #3d9af6;
}

.query-lab-page .chart-full-btn {
  color: #1677d2;
  background: #eef7ff;
  border-color: #8cc7ff;
  font-weight: 700;
}

.query-lab-page .chart-full-btn:hover,
.query-lab-page .chart-full-btn:focus {
  color: #fff;
  background: #2f8ff0;
  border-color: #2f8ff0;
}

.query-lab-page .chart-full-btn.is-disabled,
.query-lab-page .chart-full-btn.is-disabled:hover {
  color: #8fb5d8;
  background: #eef7ff;
  border-color: #b8daf7;
  opacity: 1;
}

.query-lab-page .result-full-btn {
  color: #6d42c7;
  background: #f4efff;
  border-color: #b9a2f1;
  font-weight: 700;
}

.query-lab-page .result-full-btn:hover,
.query-lab-page .result-full-btn:focus {
  color: #fff;
  background: #7a52dd;
  border-color: #7a52dd;
}

.query-lab-page .result-full-btn.is-disabled,
.query-lab-page .result-full-btn.is-disabled:hover {
  color: #aa97d7;
  background: #f4efff;
  border-color: #d1c4ef;
  opacity: 1;
}

.query-lab-page .chart-export-btn,
.query-lab-page .reasoning-export-btn {
  color: #6d42c7;
  background: #f4efff;
  border-color: #b9a2f1;
  font-weight: 700;
}

.query-lab-page .chart-export-btn:hover,
.query-lab-page .chart-export-btn:focus,
.query-lab-page .reasoning-export-btn:hover,
.query-lab-page .reasoning-export-btn:focus {
  color: #fff;
  background: #7a52dd;
  border-color: #7a52dd;
}

.query-lab-page .chart-export-btn.is-disabled,
.query-lab-page .reasoning-export-btn.is-disabled {
  color: #aa97d7;
  background: #f4efff;
  border-color: #d1c4ef;
  opacity: 1;
}

.query-lab-page .rerun-latest-btn {
  color: #0f8a68;
  background: #eefbf6;
  border-color: #8bdcc5;
  font-weight: 700;
}

.query-lab-page .rerun-latest-btn:hover,
.query-lab-page .rerun-latest-btn:focus {
  color: #fff;
  background: #13a87d;
  border-color: #13a87d;
}

.query-lab-page .compare-model-btn {
  color: #9a5a00;
  background: #fff8e8;
  border-color: #f0c36f;
  font-weight: 700;
}

.query-lab-page .compare-model-btn:hover,
.query-lab-page .compare-model-btn:focus {
  color: #fff;
  background: #d48706;
  border-color: #d48706;
}

.query-lab-page .permission-compare-btn {
  color: #1677d2;
  background: #eef7ff;
  border-color: #8cc7ff;
  font-weight: 700;
}

.query-lab-page .permission-compare-btn:hover,
.query-lab-page .permission-compare-btn:focus {
  color: #fff;
  background: #2f8ff0;
  border-color: #2f8ff0;
}

.query-lab-page .permission-compare-btn.is-disabled {
  color: #8fb5d8;
  background: #eef7ff;
  border-color: #b8daf7;
  opacity: 1;
}

.query-lab-page .record-action-btn {
  min-width: 48px;
  font-weight: 700;
  border-radius: 4px;
}

.query-lab-page .record-action-btn + .record-action-btn {
  margin-left: 6px;
}

.query-lab-page .record-action-btn--refresh {
  color: #0f8a68;
  background: #eefbf6;
  border-color: #8bdcc5;
}

.query-lab-page .record-action-btn--refresh:hover,
.query-lab-page .record-action-btn--refresh:focus {
  color: #fff;
  background: #13a87d;
  border-color: #13a87d;
}

.query-lab-page .record-action-btn--detail {
  color: #1677d2;
  background: #eef7ff;
  border-color: #8cc7ff;
}

.query-lab-page .record-action-btn--detail:hover,
.query-lab-page .record-action-btn--detail:focus {
  color: #fff;
  background: #2f8ff0;
  border-color: #2f8ff0;
}

.compare-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  color: #52617b;
  font-size: 13px;
}

.compare-toolbar span {
  min-width: 0;
  line-height: 1.5;
}

.permission-conclusion {
  margin: 12px 0 0;
  padding: 10px 12px;
  border: 1px solid #dfe8f5;
  border-radius: 6px;
  background: #f8fbff;
  color: #53627f;
  font-size: 13px;
  line-height: 1.6;
}

:global(.template-tooltip) {
  max-width: 360px;
}

:global(.template-tooltip-content) {
  display: grid;
  gap: 6px;
  color: #17213b;
}

:global(.template-tooltip-content strong) {
  font-size: 13px;
  line-height: 1.4;
}

:global(.template-tooltip-content p) {
  margin: 0;
  color: #52617b;
  font-size: 12px;
  line-height: 1.6;
  white-space: normal;
  word-break: break-word;
}

@media (max-width: 1280px) {
  .metric-grid,
  .lab-work-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .lab-work-grid {
    height: auto;
  }

  .right-stack {
    grid-template-rows: auto;
  }
}

@media (max-width: 760px) {
  .lab-hero-card,
  .hero-actions,
  .section-head {
    flex-direction: column;
    align-items: stretch;
  }

  .metric-grid,
  .lab-work-grid,
  .two-col,
  .security-grid {
    grid-template-columns: 1fr;
  }
}
</style>
