<template>
  <section class="chart-rule-page">
    <div class="page-head">
      <div>
        <h2>AI 图表推荐规则配置</h2>
        <p>配置趋势、对比、占比、明细和企业自定义规则，统一 ECharts 默认渲染风格。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="loadAll" :loading="loading">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增规则</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="15">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-title">
              <span>推荐规则</span>
              <el-segmented v-model="filters.scenarioType" :options="scenarioOptions" @change="loadRules" />
            </div>
          </template>

          <div class="toolbar">
            <el-input v-model="filters.keyword" clearable placeholder="搜索规则名称、编码、说明" @keyup.enter="loadRules" />
            <el-select v-model="filters.enabled" clearable placeholder="启用状态" class="status-select" @change="loadRules">
              <el-option label="已启用" :value="true" />
              <el-option label="已禁用" :value="false" />
            </el-select>
            <el-button @click="loadRules">查询</el-button>
          </div>

          <el-table :data="rules" border v-loading="loading" height="438" empty-text="暂无规则">
            <el-table-column prop="priority" label="优先级" width="82" />
            <el-table-column label="规则" min-width="190">
              <template #default="{ row }">
                <div class="rule-name">{{ row.ruleName }}</div>
                <div class="muted mono">{{ row.ruleCode }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="scenarioType" label="场景" width="132">
              <template #default="{ row }">
                <el-tag size="small">{{ scenarioLabel(row.scenarioType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="chartType" label="图表" width="96" />
            <el-table-column label="启用" width="86">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" @change="toggleRule(row)" />
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="更新时间" width="168" />
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel audit-panel">
          <template #header>
            <div class="panel-title">
              <span>规则审计</span>
              <el-button text @click="loadAuditLogs">刷新</el-button>
            </div>
          </template>
          <el-table :data="auditLogs" size="small" height="220" empty-text="暂无审计记录">
            <el-table-column prop="action" label="动作" width="120" />
            <el-table-column prop="ruleId" label="规则ID" width="92" />
            <el-table-column prop="operator" label="操作人" width="120" />
            <el-table-column prop="createdAt" label="时间" width="168" />
            <el-table-column prop="afterSnapshot" label="变更快照" min-width="220" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="9">
        <el-card shadow="never" class="panel">
          <template #header>企业图表偏好</template>
          <el-form label-position="top">
            <el-form-item label="主题名称">
              <el-input v-model="preferences.themeName" />
            </el-form-item>
            <el-form-item label="颜色盘">
              <div class="palette-row">
                <el-color-picker
                  v-for="(_, index) in preferences.colorPalette"
                  :key="index"
                  v-model="preferences.colorPalette[index]"
                  show-alpha
                />
                <el-button :icon="Plus" circle @click="preferences.colorPalette.push('#2563eb')" />
              </div>
            </el-form-item>
            <el-form-item label="字体">
              <div class="inline-grid">
                <el-input v-model="preferences.fontConfig.fontFamily" placeholder="字体" />
                <el-input-number v-model="preferences.fontConfig.fontSize" :min="10" :max="28" />
              </div>
            </el-form-item>
            <el-form-item label="布局">
              <div class="inline-grid">
                <el-select v-model="preferences.layoutConfig.legend">
                  <el-option label="顶部" value="top" />
                  <el-option label="底部" value="bottom" />
                  <el-option label="左侧" value="left" />
                  <el-option label="右侧" value="right" />
                </el-select>
                <el-input-number v-model="preferences.layoutConfig.height" :min="240" :max="800" />
              </div>
            </el-form-item>
            <el-form-item label="动态渲染">
              <div class="switch-line">
                <el-checkbox v-model="preferences.defaultOptions.animation">动画</el-checkbox>
                <el-checkbox v-model="preferences.defaultOptions.dataZoom">缩放</el-checkbox>
                <el-checkbox v-model="preferences.defaultOptions.voiceSummary">语音播报适配</el-checkbox>
              </div>
            </el-form-item>
            <el-button type="primary" :loading="savingPreference" @click="savePreference">保存偏好</el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>推荐测试与预览</template>
          <el-form label-position="top">
            <el-form-item label="业务意图">
              <el-input v-model="tester.intent" placeholder="例如：查看各部门销售额对比" />
            </el-form-item>
            <el-form-item label="样例数据 JSON">
              <el-input v-model="tester.json" type="textarea" :rows="7" />
            </el-form-item>
            <el-button type="primary" :loading="testing" @click="runTest">测试推荐</el-button>
          </el-form>
          <div v-if="testResult" class="test-summary">
            <el-tag type="success">{{ testResult.chartType }}</el-tag>
            <span>{{ testResult.explain }}</span>
          </div>
          <div ref="chartRef" class="chart-preview"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-drawer v-model="drawerVisible" :title="editingId ? '编辑规则' : '新增规则'" size="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="规则名称">
          <el-input v-model="form.ruleName" />
        </el-form-item>
        <el-form-item label="规则编码">
          <el-input v-model="form.ruleCode" />
        </el-form-item>
        <div class="inline-grid">
          <el-form-item label="场景">
            <el-select v-model="form.scenarioType">
              <el-option v-for="item in scenarioSelectOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="图表类型">
            <el-select v-model="form.chartType">
              <el-option label="折线图 line" value="line" />
              <el-option label="柱状图 bar" value="bar" />
              <el-option label="饼图 pie" value="pie" />
              <el-option label="环形图 doughnut" value="doughnut" />
              <el-option label="表格 table" value="table" />
            </el-select>
          </el-form-item>
        </div>
        <div class="inline-grid">
          <el-form-item label="优先级">
            <el-input-number v-model="form.priority" :min="0" :max="9999" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </div>
        <el-form-item label="匹配条件 JSON">
          <el-input v-model="form.matchConfigText" type="textarea" :rows="6" />
        </el-form-item>
        <el-form-item label="ECharts 渲染配置 JSON">
          <el-input v-model="form.renderConfigText" type="textarea" :rows="6" />
        </el-form-item>
        <el-form-item label="推荐解释">
          <el-input v-model="form.explainTemplate" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="saveRule">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  createChartRule,
  deleteChartRule,
  fetchChartPreferences,
  fetchChartRuleAuditLogs,
  fetchChartRules,
  saveChartPreferences,
  testChartRule,
  updateChartRule,
  updateChartRuleEnabled
} from '../../api/aiChartRuleConfig'

const scenarioSelectOptions = [
  { label: '全部', value: '' },
  { label: '时序趋势', value: 'TIME_SERIES' },
  { label: '分组对比', value: 'GROUP_COMPARE' },
  { label: '占比分析', value: 'RATIO' },
  { label: '明细数据', value: 'DETAIL' },
  { label: '自定义', value: 'CUSTOM' }
]
const scenarioOptions = scenarioSelectOptions.map((item) => ({ label: item.label, value: item.value }))

const loading = ref(false)
const savingRule = ref(false)
const savingPreference = ref(false)
const testing = ref(false)
const drawerVisible = ref(false)
const editingId = ref(null)
const rules = ref([])
const auditLogs = ref([])
const chartRef = ref(null)
const chart = ref(null)
const testResult = ref(null)

const filters = reactive({ scenarioType: '', enabled: null, keyword: '' })
const form = reactive({
  ruleCode: '',
  ruleName: '',
  scenarioType: 'TIME_SERIES',
  chartType: 'line',
  enabled: true,
  priority: 100,
  matchConfigText: '{}',
  renderConfigText: '{}',
  explainTemplate: ''
})
const preferences = reactive({
  themeName: '企业默认可视化风格',
  colorPalette: ['#2563eb', '#16a34a', '#f59e0b', '#dc2626'],
  fontConfig: { fontFamily: 'Microsoft YaHei', fontSize: 12 },
  layoutConfig: { legend: 'top', height: 360 },
  defaultOptions: { animation: true, dataZoom: false, voiceSummary: true }
})
const tester = reactive({
  intent: '查看各部门销售额对比',
  json: JSON.stringify({
    fields: [
      { name: '部门', type: 'string' },
      { name: '销售额', type: 'number' }
    ],
    rows: [
      { 部门: '华东', 销售额: 1280 },
      { 部门: '华南', 销售额: 960 },
      { 部门: '华北', 销售额: 1180 }
    ]
  }, null, 2)
})

const scenarioLabel = (value) => scenarioSelectOptions.find((item) => item.value === value)?.label || value
const parseJson = (text, fallback = {}) => {
  try {
    return JSON.parse(text || '{}')
  } catch {
    throw new Error('JSON 格式不正确')
  }
}

const loadRules = async () => {
  loading.value = true
  try {
    rules.value = await fetchChartRules({
      scenarioType: filters.scenarioType || undefined,
      enabled: filters.enabled ?? undefined,
      keyword: filters.keyword || undefined
    })
  } catch (error) {
    ElMessage.error(error.message || '加载规则失败')
  } finally {
    loading.value = false
  }
}

const loadPreferences = async () => {
  try {
    const data = await fetchChartPreferences()
    Object.assign(preferences, {
      themeName: data.themeName || preferences.themeName,
      colorPalette: Array.isArray(data.colorPalette) ? data.colorPalette : preferences.colorPalette,
      fontConfig: { ...preferences.fontConfig, ...(data.fontConfig || {}) },
      layoutConfig: { ...preferences.layoutConfig, ...(data.layoutConfig || {}) },
      defaultOptions: { ...preferences.defaultOptions, ...(data.defaultOptions || {}) }
    })
  } catch (error) {
    ElMessage.error(error.message || '加载图表偏好失败')
  }
}

const loadAuditLogs = async () => {
  try {
    auditLogs.value = await fetchChartRuleAuditLogs({ limit: 50 })
  } catch (error) {
    ElMessage.error(error.message || '加载审计日志失败')
  }
}

const loadAll = async () => {
  await Promise.all([loadRules(), loadPreferences(), loadAuditLogs()])
}

const resetForm = () => {
  Object.assign(form, {
    ruleCode: '',
    ruleName: '',
    scenarioType: 'TIME_SERIES',
    chartType: 'line',
    enabled: true,
    priority: 100,
    matchConfigText: '{\n  "timeRequired": true,\n  "numericRequired": true\n}',
    renderConfigText: '{\n  "animation": true,\n  "smooth": true\n}',
    explainTemplate: ''
  })
}

const openCreate = () => {
  editingId.value = null
  resetForm()
  drawerVisible.value = true
}

const openEdit = (row) => {
  editingId.value = row.id
  Object.assign(form, {
    ruleCode: row.ruleCode,
    ruleName: row.ruleName,
    scenarioType: row.scenarioType,
    chartType: row.chartType,
    enabled: !!row.enabled,
    priority: Number(row.priority) || 0,
    matchConfigText: JSON.stringify(row.matchConfig || {}, null, 2),
    renderConfigText: JSON.stringify(row.renderConfig || {}, null, 2),
    explainTemplate: row.explainTemplate || ''
  })
  drawerVisible.value = true
}

const saveRule = async () => {
  savingRule.value = true
  try {
    const payload = {
      ruleCode: form.ruleCode.trim(),
      ruleName: form.ruleName.trim(),
      scenarioType: form.scenarioType,
      chartType: form.chartType,
      enabled: form.enabled,
      priority: form.priority,
      matchConfig: parseJson(form.matchConfigText),
      renderConfig: parseJson(form.renderConfigText),
      explainTemplate: form.explainTemplate
    }
    if (editingId.value) {
      await updateChartRule(editingId.value, payload)
    } else {
      await createChartRule(payload)
    }
    ElMessage.success('规则已保存')
    drawerVisible.value = false
    await Promise.all([loadRules(), loadAuditLogs()])
  } catch (error) {
    ElMessage.error(error.message || '保存规则失败')
  } finally {
    savingRule.value = false
  }
}

const toggleRule = async (row) => {
  try {
    await updateChartRuleEnabled(row.id, row.enabled)
    ElMessage.success(row.enabled ? '规则已启用' : '规则已禁用')
    await loadAuditLogs()
  } catch (error) {
    row.enabled = !row.enabled
    ElMessage.error(error.message || '更新状态失败')
  }
}

const removeRule = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除规则「${row.ruleName}」吗？`, '删除确认', { type: 'warning' })
    await deleteChartRule(row.id)
    ElMessage.success('规则已删除')
    await Promise.all([loadRules(), loadAuditLogs()])
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(error.message || '删除失败')
  }
}

const savePreference = async () => {
  savingPreference.value = true
  try {
    await saveChartPreferences({ ...preferences })
    ElMessage.success('图表偏好已保存')
    await loadAuditLogs()
  } catch (error) {
    ElMessage.error(error.message || '保存偏好失败')
  } finally {
    savingPreference.value = false
  }
}

const runTest = async () => {
  testing.value = true
  try {
    const sample = parseJson(tester.json, { fields: [], rows: [] })
    testResult.value = await testChartRule({ intent: tester.intent, ...sample })
    await nextTick()
    renderPreview()
    await loadAuditLogs()
  } catch (error) {
    ElMessage.error(error.message || '测试失败')
  } finally {
    testing.value = false
  }
}

const renderPreview = () => {
  if (!chartRef.value || !testResult.value) return
  const option = testResult.value.option || {}
  if (option.type === 'table') {
    if (chart.value) {
      chart.value.dispose()
      chart.value = null
    }
    chartRef.value.innerHTML = '<div class="table-preview">表格推荐：请查看返回 columns 和 rows。</div>'
    return
  }

  if (!chart.value || chart.value.isDisposed?.()) {
    chartRef.value.innerHTML = ''
    chart.value = echarts.init(chartRef.value)
  }
  chart.value.setOption(option, true)
  chart.value.resize()
}

onMounted(loadAll)
onBeforeUnmount(() => {
  if (chart.value) {
    chart.value.dispose()
  }
})
</script>

<style scoped>
.chart-rule-page {
  padding: 0 4px;
}
.page-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-head h2 {
  margin: 0 0 6px;
  font-size: 22px;
}
.page-head p,
.muted {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}
.head-actions,
.toolbar,
.panel-title,
.palette-row,
.switch-line {
  display: flex;
  align-items: center;
  gap: 10px;
}
.panel {
  margin-bottom: 16px;
  border-radius: 8px;
}
.panel-title {
  justify-content: space-between;
}
.toolbar {
  margin-bottom: 12px;
}
.status-select {
  width: 130px;
  flex: none;
}
.rule-name {
  font-weight: 600;
  color: #0f172a;
}
.mono {
  font-family: Consolas, Monaco, monospace;
}
.inline-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  width: 100%;
}
.test-summary {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 12px 0;
  color: #334155;
  font-size: 13px;
}
.chart-preview {
  width: 100%;
  height: 300px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.table-preview {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
}
.audit-panel {
  margin-bottom: 0;
}
@media (max-width: 900px) {
  .page-head {
    flex-direction: column;
  }
  .inline-grid {
    grid-template-columns: 1fr;
  }
  .toolbar {
    flex-wrap: wrap;
  }
}
</style>
