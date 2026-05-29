<template>
  <section class="advanced-manage-page">
    <header class="advanced-manage-header">
      <div>
        <h1>预测与情景模拟</h1>
        <p>对话查询负责自然语言触发，这里集中管理预测方案、预警规则、预警事件和后续推送记录。</p>
      </div>
      <div class="advanced-manage-header__actions">
        <el-button @click="goChat">进入对话触发</el-button>
        <el-button type="primary" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </header>

    <div class="advanced-manage-metrics">
      <article class="metric-card">
        <span>预警规则</span>
        <strong>{{ rules.length }}</strong>
        <small>已保存规则总数</small>
      </article>
      <article class="metric-card is-green">
        <span>启用规则</span>
        <strong>{{ activeRuleCount }}</strong>
        <small>离线 Agent 轮询范围</small>
      </article>
      <article class="metric-card is-orange">
        <span>预警事件</span>
        <strong>{{ events.length }}</strong>
        <small>最近事件记录</small>
      </article>
      <article class="metric-card is-cyan">
        <span>方案资产</span>
        <strong>{{ plans.length }}</strong>
        <small>预测/推演保存记录</small>
      </article>
    </div>

    <el-tabs v-model="activeTab" class="advanced-manage-tabs">
      <el-tab-pane label="预警规则" name="rules">
        <section class="manage-panel">
          <div class="panel-head">
            <div>
              <h2>预警规则管理</h2>
              <p>支持编辑、启停、删除和手动检测；自动轮询由后端离线 Agent 执行。</p>
            </div>
          </div>
          <el-table :data="rules" border v-loading="loading" empty-text="暂无预警规则">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="规则" min-width="220">
              <template #default="{ row }">
                <div class="rule-title">{{ formatRuleTitle(row) }}</div>
                <div class="rule-meta">{{ row.tableName }} / {{ row.filterExpression || '无过滤条件' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="周期" width="120">
              <template #default="{ row }">{{ cycleLabel(row.detectionCycle) }}</template>
            </el-table-column>
            <el-table-column label="渠道" width="140">
              <template #default="{ row }">{{ channelLabel(row.channels) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="light">
                  {{ row.status === 'ACTIVE' ? '已启用' : row.status === 'DISABLED' ? '已停用' : row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="editRule(row)">编辑</el-button>
                <el-button size="small" text type="success" @click="runDetection(row)">检测</el-button>
                <el-button size="small" text @click="toggleRule(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
                <el-button size="small" text type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="预警事件" name="events">
        <section class="manage-panel">
          <div class="panel-head">
            <div>
              <h2>预警事件</h2>
              <p>展示阈值和 Z-Score 检测生成的事件，后续可扩展确认、关闭和推送重试。</p>
            </div>
          </div>
          <el-table :data="events" border v-loading="loading" empty-text="暂无预警事件">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="ruleId" label="规则ID" width="90" />
            <el-table-column prop="bucketName" label="时间桶" width="130" />
            <el-table-column label="实际值" width="120">
              <template #default="{ row }">{{ formatNumber(row.actualValue) }}</template>
            </el-table-column>
            <el-table-column label="触发原因" min-width="320">
              <template #default="{ row }">{{ row.reason || '-' }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="createdAt" label="创建时间" width="180" />
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="预测/推演方案" name="plans">
        <section class="manage-panel">
          <div class="panel-head">
            <div>
              <h2>方案资产管理</h2>
              <p>集中查看已保存的预测和 What-if 方案，支持详情查看、历史复算和删除。</p>
            </div>
            <el-button type="primary" @click="goChat">去对话触发</el-button>
          </div>
          <el-table :data="plans" border v-loading="loading" empty-text="暂无预测/推演方案">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.planType === 'forecast' ? 'primary' : 'success'">
                  {{ planTypeLabel(row.planType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="方案" min-width="240">
              <template #default="{ row }">
                <div class="rule-title">{{ row.planName || '未命名方案' }}</div>
                <div class="rule-meta">{{ row.metricLabel || '自动推断指标' }} / {{ row.timeRangeLabel || '自定义周期' }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="tableName" label="数据源" min-width="160" />
            <el-table-column label="版本" width="90">
              <template #default="{ row }">v{{ row.versionNo || 1 }}</template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="更新时间" width="180" />
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openPlan(row)">详情</el-button>
                <el-button size="small" text type="success" @click="recalculatePlan(row)">复算</el-button>
                <el-button size="small" text type="danger" @click="removePlan(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="planDetailVisible"
      :title="selectedPlan?.planName || '方案详情'"
      width="920px"
      destroy-on-close
    >
      <div v-if="selectedPlan" class="plan-detail">
        <div class="plan-detail__meta">
          <span>{{ planTypeLabel(selectedPlan.planType) }}</span>
          <span>v{{ selectedPlan.versionNo || 1 }}</span>
          <span>{{ selectedPlan.updatedAt || selectedPlan.createdAt || '-' }}</span>
        </div>
        <AdvancedAnalysisCard
          v-if="selectedPlanAnalysis"
          :analysis="selectedPlanAnalysis"
          :show-save-action="false"
          :show-pin-action="false"
          @recalculate="recalculateSelectedPlan"
        />
      </div>
      <template #footer>
        <el-button @click="planDetailVisible = false">关闭</el-button>
        <el-button type="primary" :loading="planRecalculating" @click="recalculateSelectedPlan">重新计算</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editorVisible" title="编辑预警规则" width="640px" destroy-on-close>
      <el-form label-position="top" class="rule-editor-form">
        <div class="form-grid">
          <el-form-item label="时间字段">
            <el-select v-model="editorForm.timeField" filterable class="full-width">
              <el-option v-for="field in editorMeta.timeFields" :key="field.columnName" :label="fieldLabel(field)" :value="field.columnName" />
            </el-select>
          </el-form-item>
          <el-form-item label="指标字段">
            <el-select v-model="editorForm.metricField" filterable class="full-width">
              <el-option v-for="field in editorMeta.numericFields" :key="field.columnName" :label="fieldLabel(field)" :value="field.columnName" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="过滤条件（可选）">
          <el-input v-model.trim="editorForm.filterExpression" placeholder="例如：region = '华东'" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="聚合粒度">
            <el-select v-model="editorForm.granularity">
              <el-option label="按日" value="day" />
              <el-option label="按周" value="week" />
              <el-option label="按月" value="month" />
              <el-option label="按季度" value="quarter" />
              <el-option label="按年" value="year" />
            </el-select>
          </el-form-item>
          <el-form-item label="检测周期">
            <el-select v-model="editorForm.detectionCycle">
              <el-option label="每小时" value="hourly" />
              <el-option label="每日" value="daily" />
              <el-option label="每周" value="weekly" />
              <el-option label="每月" value="monthly" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="判断条件">
            <el-select v-model="editorForm.operator">
              <el-option label="低于阈值" value="lt" />
              <el-option label="高于阈值" value="gt" />
              <el-option label="Z-Score 异常波动" value="zscore" />
            </el-select>
          </el-form-item>
          <el-form-item label="阈值">
            <el-input-number v-model="editorForm.threshold" :min="0" :disabled="editorForm.operator === 'zscore'" />
          </el-form-item>
        </div>
        <el-form-item label="通知渠道">
          <el-checkbox-group v-model="editorForm.channels">
            <el-checkbox label="email">邮件</el-checkbox>
            <el-checkbox label="dingtalk">钉钉</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEditor">保存修改</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdvancedAnalysisCard from '../../components/AdvancedAnalysisCard.vue'
import {
  deleteAdvancedAnalysisPlan,
  deleteAdvancedAlertRule,
  fetchAdvancedAnalysisFieldMeta,
  getAdvancedAnalysisPlan,
  getAdvancedAlertRule,
  listAdvancedAnalysisPlans,
  listAdvancedAlertEvents,
  listAdvancedAlertRules,
  recalculateAdvancedAnalysisPlan,
  runAdvancedAlertDetection,
  updateAdvancedAlertRule,
  updateAdvancedAlertRuleStatus
} from '../../api/advancedAnalysis'

const workbench = inject('workbench', null)

const activeTab = ref('rules')
const loading = ref(false)
const saving = ref(false)
const rules = ref([])
const events = ref([])
const plans = ref([])
const selectedPlan = ref(null)
const planDetailVisible = ref(false)
const planRecalculating = ref(false)
const editorVisible = ref(false)
const editorMeta = ref({ timeFields: [], numericFields: [] })
const editorForm = ref({
  id: '',
  tableName: '',
  timeField: '',
  metricField: '',
  filterExpression: '',
  granularity: 'day',
  operator: 'lt',
  threshold: 100000,
  detectionCycle: 'daily',
  channels: ['email', 'dingtalk'],
  status: 'ACTIVE'
})

const activeRuleCount = computed(() => rules.value.filter(item => item.status === 'ACTIVE').length)

const selectedPlanAnalysis = computed(() => normalizePlanAnalysis(selectedPlan.value))

const fieldLabel = (field) => {
  const displayName = String(field?.displayName || field?.businessName || '').trim()
  const columnName = String(field?.columnName || '').trim()
  return displayName && columnName && displayName !== columnName ? `${displayName}（${columnName}）` : displayName || columnName || '未命名字段'
}

const formatNumber = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(2)}万`
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

const cycleLabel = (cycle) => ({ hourly: '每小时', daily: '每日', weekly: '每周', monthly: '每月' }[cycle] || '每日')

const channelLabel = (channels = []) => {
  const values = Array.isArray(channels) ? channels : [channels]
  const labels = values.map(item => item === 'email' ? '邮件' : item === 'dingtalk' ? '钉钉' : '').filter(Boolean)
  return labels.length ? [...new Set(labels)].join(' + ') : '邮件 + 钉钉'
}

const formatRuleTitle = (rule) => {
  const operatorText = { lt: '低于', gt: '高于', zscore: '异常波动' }[rule.operator] || '触发'
  const threshold = rule.operator === 'zscore' ? 'Z-Score' : formatNumber(rule.threshold)
  return `${rule.metricField || '指标'} ${operatorText} ${threshold}`
}

const planTypeLabel = (type) => type === 'forecast' ? '时序预测' : type === 'whatIf' ? 'What-if 推演' : '方案'

const normalizePlanAnalysis = (plan) => {
  if (!plan) return null
  const result = plan.result || {}
  if (result.title && result.type && Array.isArray(result.series)) {
    return {
      ...result,
      id: `plan-${plan.id}`,
      status: `已保存 v${plan.versionNo || 1}`,
      planId: plan.id
    }
  }
  if (plan.planType === 'forecast') {
    const algorithm = result.algorithm || plan.request?.algorithm || 'Holt-Winters'
    const horizon = plan.request?.horizon || 3
    return {
      id: `plan-${plan.id}`,
      type: 'forecast',
      title: plan.planName || '时序预测方案',
      summary: '已基于保存参数重新计算预测结果。',
      tableName: plan.tableName || result.tableName || '',
      metric: plan.metricLabel || result.metricField || '核心指标',
      timeRange: plan.timeRangeLabel || result.granularity || '自定义周期',
      status: `已保存 v${plan.versionNo || 1}`,
      params: {
        ...(result.algorithmParams || {}),
        horizon,
        algorithm,
        confidence: result.confidence || '95%',
        algorithmParams: result.algorithmParams || {}
      },
      dataQuality: result.dataQuality || null,
      series: Array.isArray(result.series) ? result.series : [],
      insights: Array.isArray(result.insights)
        ? result.insights.map(item => ({ label: String(item.label || ''), value: String(item.value ?? '') }))
        : []
    }
  }
  return {
    id: `plan-${plan.id}`,
    type: 'whatIf',
    title: plan.planName || 'What-if 推演方案',
    summary: '已基于保存参数重新计算推演结果。',
    tableName: plan.tableName || result.tableName || '',
    metric: plan.metricLabel || result.targetMetric || '目标指标',
    timeRange: plan.timeRangeLabel || '当前分析周期',
    status: `已保存 v${plan.versionNo || 1}`,
    params: {
      targetMetric: result.targetMetric || plan.request?.targetMetric || '',
      variables: Array.isArray(result.variables) ? result.variables : (plan.request?.variables || [])
    },
    series: Array.isArray(result.series) ? result.series : [],
    insights: Array.isArray(result.insights)
      ? result.insights.map(item => ({ label: String(item.label || ''), value: String(item.value ?? '') }))
      : []
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    const [ruleRows, eventRows, planRows] = await Promise.all([
      listAdvancedAlertRules(),
      listAdvancedAlertEvents(),
      listAdvancedAnalysisPlans()
    ])
    rules.value = Array.isArray(ruleRows) ? ruleRows.filter(item => item.status !== 'DELETED') : []
    events.value = Array.isArray(eventRows) ? eventRows : []
    plans.value = Array.isArray(planRows) ? planRows : []
  } catch (error) {
    ElMessage.error(`加载预测与情景模拟数据失败：${error.message || '未知原因'}`)
  } finally {
    loading.value = false
  }
}

const openPlan = async (plan) => {
  if (!plan?.id) return
  try {
    selectedPlan.value = await getAdvancedAnalysisPlan(plan.id)
    planDetailVisible.value = true
  } catch (error) {
    ElMessage.error(`打开方案失败：${error.message || '未知原因'}`)
  }
}

const recalculatePlan = async (plan) => {
  if (!plan?.id) return
  planRecalculating.value = true
  try {
    const updated = await recalculateAdvancedAnalysisPlan(plan.id)
    const index = plans.value.findIndex(item => String(item.id) === String(plan.id))
    if (index >= 0) {
      plans.value.splice(index, 1, updated)
    }
    if (selectedPlan.value && String(selectedPlan.value.id) === String(plan.id)) {
      selectedPlan.value = updated
    }
    ElMessage.success(`方案已复算至 v${updated?.versionNo || ''}`)
  } catch (error) {
    ElMessage.error(`复算失败：${error.message || '未知原因'}`)
  } finally {
    planRecalculating.value = false
  }
}

const recalculateSelectedPlan = async () => {
  if (!selectedPlan.value?.id) return
  await recalculatePlan(selectedPlan.value)
}

const removePlan = async (plan) => {
  if (!plan?.id) return
  try {
    await deleteAdvancedAnalysisPlan(plan.id)
    plans.value = plans.value.filter(item => String(item.id) !== String(plan.id))
    if (selectedPlan.value && String(selectedPlan.value.id) === String(plan.id)) {
      selectedPlan.value = null
      planDetailVisible.value = false
    }
    ElMessage.success('方案已删除')
  } catch (error) {
    ElMessage.error(`删除方案失败：${error.message || '未知原因'}`)
  }
}

const goChat = () => {
  if (workbench?.activeModule) {
    workbench.activeModule.value = 'chat'
  }
}

const editRule = async (rule) => {
  if (!rule?.id) return
  try {
    const detail = await getAdvancedAlertRule(rule.id)
    const fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName: detail.tableName })
    editorMeta.value = {
      timeFields: Array.isArray(fieldMeta?.timeFields) ? fieldMeta.timeFields : [],
      numericFields: Array.isArray(fieldMeta?.numericFields) ? fieldMeta.numericFields : []
    }
    editorForm.value = {
      id: detail.id,
      tableName: detail.tableName || '',
      timeField: detail.timeField || '',
      metricField: detail.metricField || '',
      filterExpression: detail.filterExpression || '',
      granularity: detail.granularity || 'day',
      operator: detail.operator || 'lt',
      threshold: Number(detail.threshold ?? 100000),
      detectionCycle: detail.detectionCycle || 'daily',
      channels: Array.isArray(detail.channels) && detail.channels.length ? detail.channels : ['email', 'dingtalk'],
      status: detail.status || 'ACTIVE'
    }
    editorVisible.value = true
  } catch (error) {
    ElMessage.error(`打开规则失败：${error.message || '未知原因'}`)
  }
}

const submitEditor = async () => {
  if (!editorForm.value.timeField || !editorForm.value.metricField) {
    ElMessage.warning('请选择时间字段和指标字段')
    return
  }
  saving.value = true
  try {
    await updateAdvancedAlertRule(editorForm.value)
    editorVisible.value = false
    await loadAll()
    ElMessage.success('预警规则已更新')
  } catch (error) {
    ElMessage.error(`保存失败：${error.message || '未知原因'}`)
  } finally {
    saving.value = false
  }
}

const toggleRule = async (rule) => {
  const status = rule.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try {
    await updateAdvancedAlertRuleStatus({ id: rule.id, status })
    await loadAll()
    ElMessage.success(status === 'ACTIVE' ? '预警规则已启用' : '预警规则已停用')
  } catch (error) {
    ElMessage.error(`状态更新失败：${error.message || '未知原因'}`)
  }
}

const removeRule = async (rule) => {
  try {
    await deleteAdvancedAlertRule({ id: rule.id })
    await loadAll()
    ElMessage.success('预警规则已删除')
  } catch (error) {
    ElMessage.error(`删除失败：${error.message || '未知原因'}`)
  }
}

const runDetection = async (rule) => {
  try {
    const result = await runAdvancedAlertDetection({ ruleId: rule.id })
    await loadAll()
    ElMessage.success(`检测完成，新增 ${result?.createdEvents || 0} 条预警事件`)
  } catch (error) {
    ElMessage.error(`检测失败：${error.message || '未知原因'}`)
  }
}

onMounted(loadAll)
</script>

<style scoped>
.advanced-manage-page {
  display: grid;
  gap: 16px;
}
.advanced-manage-header,
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.advanced-manage-header h1,
.panel-head h2,
.empty-plan h2 {
  margin: 0;
  color: #0f172a;
}
.advanced-manage-header p,
.panel-head p,
.empty-plan p,
.rule-meta {
  margin: 6px 0 0;
  color: #64748b;
  line-height: 1.6;
}
.advanced-manage-header__actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.advanced-manage-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.metric-card {
  display: grid;
  gap: 6px;
  padding: 14px 16px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
}
.metric-card.is-green {
  border-color: #bbf7d0;
  background: #f0fdf4;
}
.metric-card.is-orange {
  border-color: #fed7aa;
  background: #fff7ed;
}
.metric-card.is-cyan {
  border-color: #a5f3fc;
  background: #ecfeff;
}
.metric-card span,
.metric-card small {
  color: #64748b;
  font-size: 12px;
}
.metric-card strong {
  color: #0f172a;
  font-size: 24px;
}
.manage-panel {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}
.rule-title {
  color: #0f172a;
  font-weight: 700;
  line-height: 1.5;
}
.rule-editor-form {
  display: grid;
  gap: 12px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.full-width {
  width: 100%;
}
.empty-plan {
  display: grid;
  justify-items: start;
  gap: 10px;
  padding: 24px;
}
.plan-detail {
  display: grid;
  gap: 12px;
}
.plan-detail__meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
}
@media (max-width: 900px) {
  .advanced-manage-header,
  .panel-head {
    align-items: stretch;
    flex-direction: column;
  }
  .advanced-manage-metrics,
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
