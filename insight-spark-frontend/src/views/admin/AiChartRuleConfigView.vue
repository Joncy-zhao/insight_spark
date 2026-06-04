<template>
  <section class="chart-rule-page">
    <div class="page-head">
      <div>
        <h2>AI 图表推荐规则配置</h2>
        <p>配置趋势、对比、占比、明细和企业自定义规则，统一 ECharts 默认渲染风格。</p>
      </div>
      <div class="head-actions">
        <el-button @click="exportConfig">导出配置</el-button>
        <el-button @click="importDialogVisible = true">导入配置</el-button>
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
            <el-table-column label="操作" width="196" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="primary" @click="openVersions(row)">版本</el-button>
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
        <el-divider content-position="left">结构化匹配条件</el-divider>
        <div class="structured-grid">
          <el-form-item label="字段类型条件" class="structured-span">
            <div class="switch-line">
              <el-checkbox v-model="structuredMatch.timeRequired" @change="syncStructuredMatchToJson">需要时间字段</el-checkbox>
              <el-checkbox v-model="structuredMatch.numericRequired" @change="syncStructuredMatchToJson">需要数值字段</el-checkbox>
              <el-checkbox v-model="structuredMatch.dimensionRequired" @change="syncStructuredMatchToJson">需要维度字段</el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="字段名关键词">
            <el-input v-model="structuredMatch.fieldKeywordsText" placeholder="逗号分隔，如 date,sales,profit" @blur="syncStructuredMatchToJson" />
          </el-form-item>
          <el-form-item label="指标数量">
            <div class="range-row">
              <el-input-number v-model="structuredMatch.metricMin" :min="0" :max="20" controls-position="right" @change="syncStructuredMatchToJson" />
              <span>至</span>
              <el-input-number v-model="structuredMatch.metricMax" :min="0" :max="50" controls-position="right" @change="syncStructuredMatchToJson" />
            </div>
          </el-form-item>
          <el-form-item label="维度数量">
            <div class="range-row">
              <el-input-number v-model="structuredMatch.dimensionMin" :min="0" :max="20" controls-position="right" @change="syncStructuredMatchToJson" />
              <span>至</span>
              <el-input-number v-model="structuredMatch.dimensionMax" :min="0" :max="50" controls-position="right" @change="syncStructuredMatchToJson" />
            </div>
          </el-form-item>
          <el-form-item label="行数范围">
            <div class="range-row">
              <el-input-number v-model="structuredMatch.rowMin" :min="0" :max="1000000" controls-position="right" @change="syncStructuredMatchToJson" />
              <span>至</span>
              <el-input-number v-model="structuredMatch.rowMax" :min="0" :max="1000000" controls-position="right" @change="syncStructuredMatchToJson" />
            </div>
          </el-form-item>
          <el-form-item label="业务意图关键词">
            <el-input v-model="structuredMatch.intentKeywordsText" placeholder="逗号分隔，如 趋势,对比,占比" @blur="syncStructuredMatchToJson" />
          </el-form-item>
        </div>
        <el-form-item label="行业场景标签">
          <el-select v-model="structuredMatch.industryTags" multiple filterable allow-create default-first-option @change="syncStructuredMatchToJson">
            <el-option label="电商" value="ecommerce" />
            <el-option label="金融" value="finance" />
            <el-option label="零售" value="retail" />
            <el-option label="制造" value="manufacturing" />
            <el-option label="通用经营分析" value="business" />
          </el-select>
        </el-form-item>
        <el-form-item label="匹配条件 JSON">
          <el-input v-model="form.matchConfigText" type="textarea" :rows="6" @blur="syncJsonToStructuredMatch" />
        </el-form-item>
        <el-divider content-position="left">动态渲染配置</el-divider>
        <div class="dynamic-render-panel">
          <div class="switch-line prediction-switches">
            <el-checkbox v-model="structuredRender.animation" @change="syncStructuredRenderToJson">动画</el-checkbox>
            <el-checkbox v-model="structuredRender.tooltip" @change="syncStructuredRenderToJson">Tooltip</el-checkbox>
            <el-checkbox v-model="structuredRender.dataZoom" @change="syncStructuredRenderToJson">DataZoom</el-checkbox>
            <el-checkbox v-model="structuredRender.incrementalRendering" @change="syncStructuredRenderToJson">增量渲染</el-checkbox>
          </div>
          <div class="structured-grid">
            <el-form-item label="刷新间隔(秒)">
              <el-input-number v-model="structuredRender.refreshIntervalSeconds" :min="0" :max="3600" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="缩放阈值">
              <el-input-number v-model="structuredRender.autoDataZoomThreshold" :min="4" :max="500" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="图例滚动阈值">
              <el-input-number v-model="structuredRender.autoLegendScrollThreshold" :min="4" :max="500" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="渐进批量">
              <el-input-number v-model="structuredRender.progressive" :min="0" :max="20000" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="渐进阈值">
              <el-input-number v-model="structuredRender.progressiveThreshold" :min="0" :max="100000" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="大数据阈值">
              <el-input-number v-model="structuredRender.largeThreshold" :min="0" :max="100000" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
          </div>
        </div>
        <el-divider content-position="left">语音播报配置</el-divider>
        <div class="voice-config-panel">
          <el-checkbox v-model="structuredVoice.enabled" @change="syncStructuredVoiceToJson">启用语音摘要</el-checkbox>
          <el-form-item label="播报顺序">
            <el-select v-model="structuredVoice.order" multiple class="full-width" @change="syncStructuredVoiceToJson">
              <el-option v-for="item in voiceFieldOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="当前图表模板">
            <el-input
              v-model="structuredVoice.currentTemplate"
              type="textarea"
              :rows="3"
              placeholder="可用变量：{ruleName}、{chartTypeName}、{dimension}、{metric}、{count}、{maxName}、{maxValue}、{minName}、{minValue}、{trend}、{anomalyCount}"
              @blur="syncStructuredVoiceToJson"
            />
          </el-form-item>
        </div>
        <template v-if="form.chartType === 'line'">
          <el-divider content-position="left">预测渲染配置</el-divider>
          <div class="prediction-panel">
            <div class="switch-line prediction-switches">
              <el-checkbox v-model="structuredPrediction.enabled" @change="syncStructuredPredictionToJson">启用预测曲线</el-checkbox>
              <el-checkbox v-model="structuredPrediction.showExplanation" @change="syncStructuredPredictionToJson">显示预测说明</el-checkbox>
            </div>
            <div class="structured-grid">
              <el-form-item label="置信度">
                <el-input-number v-model="structuredPrediction.confidence" :min="0.5" :max="0.99" :step="0.01" :precision="2" controls-position="right" @change="syncStructuredPredictionToJson" />
              </el-form-item>
              <el-form-item label="预测期数">
                <el-input-number v-model="structuredPrediction.horizon" :min="1" :max="60" controls-position="right" @change="syncStructuredPredictionToJson" />
              </el-form-item>
              <el-form-item label="预测算法" class="structured-span">
                <el-select v-model="structuredPrediction.algorithm" filterable allow-create default-first-option @change="syncStructuredPredictionToJson">
                  <el-option label="Holt-Winters" value="Holt-Winters" />
                  <el-option label="Prophet" value="Prophet" />
                  <el-option label="移动平均" value="Moving-Average" />
                </el-select>
              </el-form-item>
            </div>
            <div class="prediction-legend-grid">
              <div v-for="item in predictionLegendItems" :key="item.key" class="prediction-legend-row">
                <el-checkbox v-model="structuredPrediction[item.showKey]" @change="syncStructuredPredictionToJson">{{ item.name }}</el-checkbox>
                <el-input v-model="structuredPrediction[item.labelKey]" size="small" @blur="syncStructuredPredictionToJson" />
              </div>
            </div>
          </div>
        </template>
        <el-form-item label="ECharts 渲染配置 JSON">
          <el-input v-model="form.renderConfigText" type="textarea" :rows="6" @blur="syncJsonToStructuredRenderConfig" />
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

    <el-dialog v-model="importDialogVisible" title="导入规则配置" width="720px" destroy-on-close>
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="导入只会新增规则或覆盖同编码规则，不会删除当前系统中未出现在文件里的规则。"
        class="import-alert"
      />
      <el-form label-position="top">
        <el-form-item label="配置 JSON">
          <el-input v-model="importText" type="textarea" :rows="12" placeholder="请粘贴导出的 JSON 配置" />
        </el-form-item>
      </el-form>
      <div v-if="importPreview" class="import-preview">
        <el-tag type="success">新增 {{ importPreview.createCount || 0 }}</el-tag>
        <el-tag type="warning">覆盖 {{ importPreview.overwriteCount || 0 }}</el-tag>
        <el-tag type="info">无变化 {{ importPreview.unchangedCount || 0 }}</el-tag>
        <el-tag v-if="importPreview.preferenceIncluded" type="primary">包含企业偏好</el-tag>
        <el-table :data="importPreview.changes || []" size="small" height="180" class="import-preview-table">
          <el-table-column prop="ruleCode" label="规则编码" min-width="160" />
          <el-table-column prop="ruleName" label="规则名称" min-width="180" />
          <el-table-column prop="action" label="动作" width="110" />
        </el-table>
      </div>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button :loading="importing" @click="previewImport">预览差异</el-button>
        <el-button type="primary" :disabled="!importPreview" :loading="importing" @click="applyImport">确认导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionDialogVisible" :title="versionDialogTitle" width="860px" destroy-on-close>
      <el-table :data="ruleVersions" v-loading="versionLoading" size="small" height="360" empty-text="暂无版本记录">
        <el-table-column prop="versionNo" label="版本" width="78" />
        <el-table-column prop="changeAction" label="动作" width="130" />
        <el-table-column prop="operator" label="操作人" width="120" />
        <el-table-column prop="createdAt" label="时间" width="168" />
        <el-table-column label="快照摘要" min-width="260">
          <template #default="{ row }">
            <div class="version-summary">
              <span>{{ row.snapshot?.ruleName || '-' }}</span>
              <span class="muted mono">{{ row.snapshot?.ruleCode || '' }}</span>
              <el-tag size="small">{{ scenarioLabel(row.snapshot?.scenarioType) }}</el-tag>
              <el-tag size="small" type="info">{{ row.snapshot?.chartType || '-' }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.versionNo === latestVersionNo" @click="rollbackVersion(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="versionDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  applyDynamicInteractionDefaults,
  applyOptionTemplateDefaults,
  buildForecastChartOption,
  hasForecastSeriesRows
} from '../../utils/chartOptionFromSnapshot'
import {
  createChartRule,
  deleteChartRule,
  exportChartRuleConfig,
  fetchChartPreferences,
  fetchChartRuleAuditLogs,
  fetchChartRuleVersions,
  fetchChartRules,
  importChartRuleConfig,
  previewImportChartRuleConfig,
  rollbackChartRuleVersion,
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
const importDialogVisible = ref(false)
const versionDialogVisible = ref(false)
const importing = ref(false)
const versionLoading = ref(false)
const editingId = ref(null)
const versionRule = ref(null)
const rules = ref([])
const auditLogs = ref([])
const ruleVersions = ref([])
const chartRef = ref(null)
const chart = ref(null)
const testResult = ref(null)
const importText = ref('')
const importPreview = ref(null)

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
const structuredMatch = reactive({
  timeRequired: false,
  numericRequired: false,
  dimensionRequired: false,
  fieldKeywordsText: '',
  intentKeywordsText: '',
  industryTags: [],
  metricMin: 0,
  metricMax: 0,
  dimensionMin: 0,
  dimensionMax: 0,
  rowMin: 0,
  rowMax: 0
})
const structuredRender = reactive({
  animation: true,
  tooltip: true,
  dataZoom: false,
  refreshIntervalSeconds: 0,
  autoDataZoomThreshold: 14,
  autoLegendScrollThreshold: 10,
  incrementalRendering: false,
  progressive: 400,
  progressiveThreshold: 3000,
  largeThreshold: 2000
})
const structuredPrediction = reactive({
  enabled: false,
  confidence: 0.95,
  horizon: 3,
  algorithm: 'Holt-Winters',
  showExplanation: true,
  showHistory: true,
  historyLabel: '历史值',
  showForecast: true,
  forecastLabel: '预测值',
  showUpper: true,
  upperLabel: '置信上界',
  showLower: true,
  lowerLabel: '置信下界',
  showAnomaly: false,
  anomalyLabel: '异常点'
})
const predictionLegendItems = [
  { key: 'history', name: '历史区间', showKey: 'showHistory', labelKey: 'historyLabel' },
  { key: 'forecast', name: '预测区间', showKey: 'showForecast', labelKey: 'forecastLabel' },
  { key: 'upper', name: '置信上界', showKey: 'showUpper', labelKey: 'upperLabel' },
  { key: 'lower', name: '置信下界', showKey: 'showLower', labelKey: 'lowerLabel' },
  { key: 'anomaly', name: '异常点', showKey: 'showAnomaly', labelKey: 'anomalyLabel' }
]
const structuredVoice = reactive({
  enabled: true,
  order: ['title', 'metric', 'max', 'min', 'trend', 'anomaly'],
  currentTemplate: ''
})
const voiceFieldOptions = [
  { label: '标题', value: 'title' },
  { label: '核心指标', value: 'metric' },
  { label: '最大值', value: 'max' },
  { label: '最小值', value: 'min' },
  { label: '趋势', value: 'trend' },
  { label: '异常点', value: 'anomaly' }
]
const defaultVoiceTemplates = {
  line: '查询完成，已生成折线图。当前按{dimension}分析{metric}，整体趋势为{trend}，最大值为{maxName}{maxValue}。',
  bar: '查询完成，已生成柱状图。当前按{dimension}对比{metric}，最大值为{maxName}{maxValue}，最小值为{minName}{minValue}。',
  pie: '查询完成，已生成饼图。当前展示{metric}的占比结构，最高项为{maxName}{maxValue}。',
  doughnut: '查询完成，已生成环形图。当前展示{metric}的占比结构，最高项为{maxName}{maxValue}。',
  table: '查询完成，已生成表格。当前展示{count}行明细数据，包含{dimension}和{metric}等字段。'
}
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

const versionDialogTitle = computed(() => `规则版本：${versionRule.value?.ruleName || ''}`)
const latestVersionNo = computed(() => Math.max(0, ...ruleVersions.value.map((item) => Number(item.versionNo) || 0)))
const scenarioLabel = (value) => scenarioSelectOptions.find((item) => item.value === value)?.label || value
const splitKeywords = (value) => String(value || '').split(/[，,;；、\s]+/).map((item) => item.trim()).filter(Boolean)
const parseJson = (text, fallback = {}) => {
  try {
    return JSON.parse(text || '{}')
  } catch {
    throw new Error('JSON 格式不正确')
  }
}

const friendlyConfigError = (error, fallback = '操作失败') => {
  const raw = String(error?.message || fallback || '操作失败').trim()
  if (!raw) return fallback
  if (raw.includes('JSON config is invalid') || raw.includes('Invalid JSON config')) {
    return 'JSON 格式不正确，请检查逗号、引号和括号是否完整。'
  }
  if (raw.includes('JSON config is too large')) {
    return '配置 JSON 过大，请删减样例或拆分配置后再保存。'
  }
  if (raw.includes('JSON config list is too large')) {
    return '配置数组过长，请减少列表项数量后再保存。'
  }
  if (raw.includes('JSON config text is too long')) {
    return raw.replace('JSON config text is too long:', '配置文本过长：')
  }
  if (raw.includes('JSON config contains unsafe key:')) {
    return raw.replace('JSON config contains unsafe key:', '检测到不安全配置字段：')
      + '，禁止配置脚本、函数入口或外链地址字段。'
  }
  if (raw.includes('JSON config contains unsafe content:')) {
    return raw.replace('JSON config contains unsafe content:', '检测到不安全配置内容：')
      + '，禁止在图表配置中写入脚本、函数体或外链 URL。'
  }
  if (raw.includes('不支持的 ECharts 渲染配置字段:')) {
    return raw.replace('不支持的 ECharts 渲染配置字段:', '暂不支持该图表渲染配置字段：')
      + '。请使用页面中的结构化配置项。'
  }
  if (raw.includes('不支持的 ECharts 渲染配置节点:')) {
    return raw.replace('不支持的 ECharts 渲染配置节点:', '暂不支持该图表渲染配置节点：')
      + '。请使用页面中的结构化配置项。'
  }
  if (raw.includes('ECharts 渲染配置数组过长:')) {
    return raw.replace('ECharts 渲染配置数组过长:', '图表渲染配置数组过长：')
  }
  return raw
}

const resetStructuredMatch = () => {
  Object.assign(structuredMatch, {
    timeRequired: false,
    numericRequired: false,
    dimensionRequired: false,
    fieldKeywordsText: '',
    intentKeywordsText: '',
    industryTags: [],
    metricMin: 0,
    metricMax: 0,
    dimensionMin: 0,
    dimensionMax: 0,
    rowMin: 0,
    rowMax: 0
  })
}

const syncJsonToStructuredMatch = () => {
  try {
    const config = parseJson(form.matchConfigText, {})
    Object.assign(structuredMatch, {
      timeRequired: Boolean(config.timeRequired),
      numericRequired: Boolean(config.numericRequired),
      dimensionRequired: Boolean(config.dimensionRequired),
      fieldKeywordsText: Array.isArray(config.fieldKeywords) ? config.fieldKeywords.join(',') : '',
      intentKeywordsText: Array.isArray(config.intentKeywords) ? config.intentKeywords.join(',') : String(config.keyword || ''),
      industryTags: Array.isArray(config.industryTags) ? config.industryTags : [],
      metricMin: Number(config.metricMin || 0),
      metricMax: Number(config.metricMax || 0),
      dimensionMin: Number(config.dimensionMin || 0),
      dimensionMax: Number(config.dimensionMax || 0),
      rowMin: Number(config.rowMin || 0),
      rowMax: Number(config.rowMax || 0)
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredMatchToJson = () => {
  const current = parseJson(form.matchConfigText, {})
  const next = { ...current }
  next.timeRequired = structuredMatch.timeRequired
  next.numericRequired = structuredMatch.numericRequired
  next.dimensionRequired = structuredMatch.dimensionRequired
  const fieldKeywords = splitKeywords(structuredMatch.fieldKeywordsText)
  const intentKeywords = splitKeywords(structuredMatch.intentKeywordsText)
  if (fieldKeywords.length) next.fieldKeywords = fieldKeywords
  else delete next.fieldKeywords
  if (intentKeywords.length) next.intentKeywords = intentKeywords
  else delete next.intentKeywords
  if (structuredMatch.industryTags.length) next.industryTags = structuredMatch.industryTags
  else delete next.industryTags
  for (const key of ['metricMin', 'metricMax', 'dimensionMin', 'dimensionMax', 'rowMin', 'rowMax']) {
    const value = Number(structuredMatch[key] || 0)
    if (value > 0) next[key] = value
    else delete next[key]
  }
  form.matchConfigText = JSON.stringify(next, null, 2)
}

const syncJsonToStructuredDynamicRender = () => {
  try {
    const config = parseJson(form.renderConfigText, {})
    const tooltip = config.tooltip && typeof config.tooltip === 'object' ? config.tooltip : {}
    const dataZoom = config.dataZoom && typeof config.dataZoom === 'object' && !Array.isArray(config.dataZoom)
      ? config.dataZoom
      : {}
    const dynamic = config.dynamic && typeof config.dynamic === 'object' ? config.dynamic : {}
    Object.assign(structuredRender, {
      animation: config.animation == null ? true : Boolean(config.animation),
      tooltip: config.tooltip == null ? true : (typeof config.tooltip === 'object' ? tooltip.show !== false : Boolean(config.tooltip)),
      dataZoom: config.dataZoom == null ? false : (typeof config.dataZoom === 'object' ? dataZoom.enabled !== false : Boolean(config.dataZoom)),
      refreshIntervalSeconds: Number(dynamic.refreshIntervalSeconds ?? config.refreshIntervalSeconds ?? config.dynamicRefreshInterval ?? 0),
      autoDataZoomThreshold: Number(dynamic.autoDataZoomThreshold ?? dataZoom.threshold ?? 14),
      autoLegendScrollThreshold: Number(dynamic.autoLegendScrollThreshold ?? 10),
      incrementalRendering: Boolean(dynamic.incrementalRendering),
      progressive: Number(dynamic.progressive || 400),
      progressiveThreshold: Number(dynamic.progressiveThreshold || 3000),
      largeThreshold: Number(dynamic.largeThreshold || 2000)
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredRenderToJson = () => {
  const current = parseJson(form.renderConfigText, {})
  const next = { ...current }
  const previousTooltip = next.tooltip && typeof next.tooltip === 'object' ? next.tooltip : {}
  const previousDataZoom = next.dataZoom && typeof next.dataZoom === 'object' && !Array.isArray(next.dataZoom)
    ? next.dataZoom
    : {}
  const previousDynamic = next.dynamic && typeof next.dynamic === 'object' ? next.dynamic : {}
  next.animation = structuredRender.animation
  next.tooltip = {
    ...previousTooltip,
    show: structuredRender.tooltip,
    confine: true
  }
  next.dataZoom = {
    ...previousDataZoom,
    enabled: structuredRender.dataZoom,
    threshold: Number(structuredRender.autoDataZoomThreshold || 14),
    start: previousDataZoom.start ?? 0,
    end: previousDataZoom.end ?? 60
  }
  next.dynamic = {
    ...previousDynamic,
    refreshIntervalSeconds: Number(structuredRender.refreshIntervalSeconds || 0),
    incrementalRendering: structuredRender.incrementalRendering,
    progressive: Number(structuredRender.progressive || 0),
    progressiveThreshold: Number(structuredRender.progressiveThreshold || 3000),
    largeThreshold: Number(structuredRender.largeThreshold || 2000),
    autoDataZoomThreshold: Number(structuredRender.autoDataZoomThreshold || 14),
    autoLegendScrollThreshold: Number(structuredRender.autoLegendScrollThreshold || 10)
  }
  form.renderConfigText = JSON.stringify(next, null, 2)
}

const defaultPredictionLegend = {
  history: { show: true, label: '历史值' },
  forecast: { show: true, label: '预测值' },
  upper: { show: true, label: '置信上界' },
  lower: { show: true, label: '置信下界' },
  anomaly: { show: false, label: '异常点' }
}

const normalizePredictionLabel = (legendConfig, key, fallback) => {
  const item = legendConfig?.[key]
  return String(item?.label || fallback).trim() || fallback
}

const normalizePredictionShow = (legendConfig, key, fallback) => {
  const item = legendConfig?.[key]
  return item?.show == null ? fallback : Boolean(item.show)
}

const syncJsonToStructuredPrediction = () => {
  try {
    const config = parseJson(form.renderConfigText, {})
    const prediction = config.prediction && typeof config.prediction === 'object' ? config.prediction : {}
    const legendConfig = prediction.legendConfig && typeof prediction.legendConfig === 'object'
      ? prediction.legendConfig
      : defaultPredictionLegend
    const legacyLegend = Array.isArray(prediction.legend) ? prediction.legend : []
    Object.assign(structuredPrediction, {
      enabled: Boolean(prediction.enabled),
      confidence: Number(prediction.confidence || 0.95),
      horizon: Number(prediction.horizon || 3),
      algorithm: String(prediction.algorithm || 'Holt-Winters'),
      showExplanation: prediction.showExplanation == null ? true : Boolean(prediction.showExplanation),
      showHistory: normalizePredictionShow(legendConfig, 'history', true),
      historyLabel: normalizePredictionLabel(legendConfig, 'history', legacyLegend[0] || '历史值'),
      showForecast: normalizePredictionShow(legendConfig, 'forecast', true),
      forecastLabel: normalizePredictionLabel(legendConfig, 'forecast', legacyLegend[1] || '预测值'),
      showUpper: normalizePredictionShow(legendConfig, 'upper', true),
      upperLabel: normalizePredictionLabel(legendConfig, 'upper', legacyLegend[2] || '置信上界'),
      showLower: normalizePredictionShow(legendConfig, 'lower', true),
      lowerLabel: normalizePredictionLabel(legendConfig, 'lower', legacyLegend[3] || '置信下界'),
      showAnomaly: normalizePredictionShow(legendConfig, 'anomaly', false),
      anomalyLabel: normalizePredictionLabel(legendConfig, 'anomaly', legacyLegend[4] || '异常点')
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredPredictionToJson = () => {
  const current = parseJson(form.renderConfigText, {})
  const next = { ...current }
  const prediction = {
    ...(next.prediction && typeof next.prediction === 'object' ? next.prediction : {}),
    enabled: structuredPrediction.enabled,
    confidence: Number(structuredPrediction.confidence || 0.95),
    horizon: Number(structuredPrediction.horizon || 3),
    algorithm: String(structuredPrediction.algorithm || 'Holt-Winters').trim() || 'Holt-Winters',
    showExplanation: structuredPrediction.showExplanation,
    legendConfig: {
      history: { show: structuredPrediction.showHistory, label: structuredPrediction.historyLabel || '历史值' },
      forecast: { show: structuredPrediction.showForecast, label: structuredPrediction.forecastLabel || '预测值' },
      upper: { show: structuredPrediction.showUpper, label: structuredPrediction.upperLabel || '置信上界' },
      lower: { show: structuredPrediction.showLower, label: structuredPrediction.lowerLabel || '置信下界' },
      anomaly: { show: structuredPrediction.showAnomaly, label: structuredPrediction.anomalyLabel || '异常点' }
    }
  }
  prediction.legend = Object.values(prediction.legendConfig)
    .filter(item => item.show)
    .map(item => item.label)
  next.prediction = prediction
  form.renderConfigText = JSON.stringify(next, null, 2)
}

const syncJsonToStructuredVoice = () => {
  try {
    const config = parseJson(form.renderConfigText, {})
    const raw = config.voiceSummary
    const voice = raw && typeof raw === 'object' ? raw : { enabled: raw == null ? true : Boolean(raw) }
    const chartTemplates = voice.chartTemplates && typeof voice.chartTemplates === 'object' ? voice.chartTemplates : {}
    const hasChartTemplate = Object.prototype.hasOwnProperty.call(chartTemplates, form.chartType)
    const templateValue = hasChartTemplate
      ? chartTemplates[form.chartType]
      : (voice.summaryTemplate ?? defaultVoiceTemplates[form.chartType] ?? defaultVoiceTemplates.bar)
    Object.assign(structuredVoice, {
      enabled: voice.enabled == null ? true : Boolean(voice.enabled),
      order: Array.isArray(voice.order) && voice.order.length ? voice.order.map(String) : ['title', 'metric', 'max', 'min', 'trend', 'anomaly'],
      currentTemplate: String(templateValue ?? '')
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredVoiceToJson = () => {
  const current = parseJson(form.renderConfigText, {})
  const next = { ...current }
  const previous = next.voiceSummary && typeof next.voiceSummary === 'object' ? next.voiceSummary : {}
  const chartTemplates = {
    ...(previous.chartTemplates && typeof previous.chartTemplates === 'object' ? previous.chartTemplates : {}),
    [form.chartType]: structuredVoice.currentTemplate ?? ''
  }
  next.voiceSummary = {
    ...previous,
    enabled: structuredVoice.enabled,
    order: structuredVoice.order?.length ? structuredVoice.order : ['title', 'metric', 'max', 'min', 'trend', 'anomaly'],
    chartTemplates
  }
  form.renderConfigText = JSON.stringify(next, null, 2)
}

const syncJsonToStructuredRenderConfig = () => {
  syncJsonToStructuredDynamicRender()
  syncJsonToStructuredVoice()
  syncJsonToStructuredPrediction()
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
  resetStructuredMatch()
  Object.assign(form, {
    ruleCode: '',
    ruleName: '',
    scenarioType: 'TIME_SERIES',
    chartType: 'line',
    enabled: true,
    priority: 100,
    matchConfigText: '{\n  "timeRequired": true,\n  "numericRequired": true\n}',
    renderConfigText: '{\n  "animation": true,\n  "smooth": true,\n  "tooltip": {\n    "show": true,\n    "confine": true\n  },\n  "dataZoom": {\n    "enabled": false,\n    "threshold": 14,\n    "start": 0,\n    "end": 60\n  },\n  "dynamic": {\n    "refreshIntervalSeconds": 0,\n    "incrementalRendering": false,\n    "progressive": 400,\n    "progressiveThreshold": 3000,\n    "largeThreshold": 2000,\n    "autoDataZoomThreshold": 14,\n    "autoLegendScrollThreshold": 10\n  },\n  "voiceSummary": {\n    "enabled": true,\n    "order": ["title", "metric", "max", "min", "trend", "anomaly"],\n    "chartTemplates": {\n      "line": "查询完成，已生成折线图。当前按{dimension}分析{metric}，整体趋势为{trend}，最大值为{maxName}{maxValue}。"\n    }\n  },\n  "prediction": {\n    "enabled": true,\n    "confidence": 0.95,\n    "horizon": 3,\n    "algorithm": "Holt-Winters",\n    "showExplanation": true,\n    "legendConfig": {\n      "history": { "show": true, "label": "历史值" },\n      "forecast": { "show": true, "label": "预测值" },\n      "upper": { "show": true, "label": "置信上界" },\n      "lower": { "show": true, "label": "置信下界" },\n      "anomaly": { "show": false, "label": "异常点" }\n    }\n  }\n}',
    explainTemplate: ''
  })
  syncJsonToStructuredMatch()
  syncJsonToStructuredDynamicRender()
  syncJsonToStructuredVoice()
  syncJsonToStructuredPrediction()
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
  syncJsonToStructuredMatch()
  syncJsonToStructuredDynamicRender()
  syncJsonToStructuredVoice()
  syncJsonToStructuredPrediction()
  drawerVisible.value = true
}

const saveRule = async () => {
  savingRule.value = true
  try {
    syncStructuredMatchToJson()
    syncStructuredRenderToJson()
    syncStructuredVoiceToJson()
    if (form.chartType === 'line') {
      syncStructuredPredictionToJson()
    }
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
    ElMessage.error(friendlyConfigError(error, '保存规则失败'))
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
    ElMessage.error(friendlyConfigError(error, '更新状态失败'))
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
    ElMessage.error(friendlyConfigError(error, '删除失败'))
  }
}

const loadRuleVersions = async () => {
  if (!versionRule.value?.id) return
  versionLoading.value = true
  try {
    ruleVersions.value = await fetchChartRuleVersions(versionRule.value.id)
  } catch (error) {
    ElMessage.error(error.message || '加载版本失败')
  } finally {
    versionLoading.value = false
  }
}

const openVersions = async (row) => {
  versionRule.value = row
  ruleVersions.value = []
  versionDialogVisible.value = true
  await loadRuleVersions()
}

const rollbackVersion = async (row) => {
  if (!versionRule.value?.id) return
  try {
    await ElMessageBox.confirm(
      `确定将规则「${versionRule.value.ruleName}」回滚到版本 ${row.versionNo} 吗？`,
      '回滚确认',
      { type: 'warning' }
    )
    await rollbackChartRuleVersion(versionRule.value.id, row.id)
    ElMessage.success('规则已回滚')
    await Promise.all([loadRules(), loadAuditLogs(), loadRuleVersions()])
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(friendlyConfigError(error, '回滚失败'))
  }
}

const savePreference = async () => {
  savingPreference.value = true
  try {
    await saveChartPreferences({ ...preferences })
    ElMessage.success('图表偏好已保存')
    await loadAuditLogs()
  } catch (error) {
    ElMessage.error(friendlyConfigError(error, '保存偏好失败'))
  } finally {
    savingPreference.value = false
  }
}

const exportConfig = async () => {
  try {
    const payload = await exportChartRuleConfig()
    const text = JSON.stringify(payload, null, 2)
    const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `ai-chart-rules-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('配置已导出')
  } catch (error) {
    ElMessage.error(error.message || '导出失败')
  }
}

const parseImportPayload = () => {
  const payload = parseJson(importText.value, {})
  if (!Array.isArray(payload.rules)) {
    throw new Error('导入 JSON 缺少 rules 数组')
  }
  return payload
}

const previewImport = async () => {
  importing.value = true
  try {
    importPreview.value = await previewImportChartRuleConfig(parseImportPayload())
    ElMessage.success('差异预览已生成')
  } catch (error) {
    importPreview.value = null
    ElMessage.error(friendlyConfigError(error, '导入预览失败'))
  } finally {
    importing.value = false
  }
}

const applyImport = async () => {
  importing.value = true
  try {
    await importChartRuleConfig(parseImportPayload())
    ElMessage.success('配置已导入')
    importDialogVisible.value = false
    importText.value = ''
    importPreview.value = null
    await loadAll()
  } catch (error) {
    ElMessage.error(friendlyConfigError(error, '导入失败'))
  } finally {
    importing.value = false
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
    ElMessage.error(friendlyConfigError(error, '测试失败'))
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
  if (testResult.value.chartType === 'line' && hasForecastSeriesRows(option.rows || option.data || parseJson(tester.json, {}).rows)) {
    if (!chart.value || chart.value.isDisposed?.()) {
      chartRef.value.innerHTML = ''
      chart.value = echarts.init(chartRef.value)
    }
    const sample = parseJson(tester.json, { rows: [] })
    const template = testResult.value.matchedRule?.renderConfig?.prediction || {}
    const baseForecastOption = buildForecastChartOption(sample.rows || [], {
      metricLabel: testResult.value.profile?.metric || '预测值',
      confidenceLabel: template.confidence ? `${Math.round(Number(template.confidence) * 100)}%` : '95%',
      legendConfig: template.legendConfig
    })
    chart.value.setOption(
      applyDynamicInteractionDefaults(
        applyOptionTemplateDefaults(baseForecastOption, testResult.value.optionTemplate),
        testResult.value.optionTemplate,
        { chartType: testResult.value.chartType }
      ),
      true
    )
    chart.value.resize()
    return
  }

  if (!chart.value || chart.value.isDisposed?.()) {
    chartRef.value.innerHTML = ''
    chart.value = echarts.init(chartRef.value)
  }
  chart.value.setOption(
    applyDynamicInteractionDefaults(
      applyOptionTemplateDefaults(option, testResult.value.optionTemplate),
      testResult.value.optionTemplate,
      { chartType: testResult.value.chartType }
    ),
    true
  )
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
.structured-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 12px;
  width: 100%;
}
.structured-span {
  grid-column: 1 / -1;
}
.structured-grid :deep(.el-form-item) {
  min-width: 0;
}
.prediction-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 14px;
}
.dynamic-render-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 14px;
}
.voice-config-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 14px;
}
.full-width {
  width: 100%;
}
.prediction-switches {
  flex-wrap: wrap;
}
.prediction-legend-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 10px;
}
.prediction-legend-row {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
}
.prediction-legend-row :deep(.el-checkbox) {
  margin-right: 0;
}
.range-row {
  display: grid;
  grid-template-columns: minmax(86px, 1fr) auto minmax(86px, 1fr);
  align-items: center;
  gap: 8px;
  width: 100%;
}
.range-row :deep(.el-input-number) {
  width: 100%;
  min-width: 86px;
}
.range-row :deep(.el-input-number .el-input__wrapper) {
  padding-left: 8px;
  padding-right: 28px;
}
.range-row span {
  color: #64748b;
  font-size: 12px;
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
.import-alert {
  margin-bottom: 12px;
}
.import-preview {
  display: grid;
  gap: 10px;
}
.import-preview-table {
  margin-top: 2px;
}
.version-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.version-summary > span:first-child {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 900px) {
  .page-head {
    flex-direction: column;
  }
  .inline-grid {
    grid-template-columns: 1fr;
  }
  .structured-grid {
    grid-template-columns: 1fr;
  }
  .prediction-legend-grid {
    grid-template-columns: 1fr;
  }
  .toolbar {
    flex-wrap: wrap;
  }
}
</style>
