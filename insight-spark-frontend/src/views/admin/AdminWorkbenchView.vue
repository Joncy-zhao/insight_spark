<template>
  <section class="admin-workbench">
    <header class="adm-bar">
      <div class="adm-bar-main">
        <el-tag class="adm-bar-tag" effect="dark" size="small" round>总管控</el-tag>
        <h1 class="adm-bar-title">首页工作台</h1>
        <span class="adm-bar-health">平台健康 {{ platformHealthScore }}</span>
        <WorkbenchHelpTip
          content="管理员全局概览：平台指标、监管图表、引擎与数据源状态；点击指标或快捷入口进入对应治理页面。"
          aria-label="管理员工作台说明"
        />
      </div>
      <div class="adm-bar-actions">
        <el-button type="primary" @click="goDashboardManage">看板管理</el-button>
        <el-button @click="goPerformance">性能治理</el-button>
        <el-button text :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-skeleton v-if="loading" class="adm-skeleton" :rows="7" animated />
    <template v-else>
      <div class="adm-ops-rail">
        <button
          v-for="item in opsRailItems"
          :key="item.key"
          type="button"
          class="adm-ops-chip"
          :class="`adm-ops-chip--${item.tone}`"
          @click="item.action?.()"
        >
          <span class="adm-ops-chip-label">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small v-if="item.hint">{{ item.hint }}</small>
        </button>
      </div>

      <div class="metric-grid">
        <button
          v-for="item in metricCards"
          :key="item.key"
          type="button"
          class="metric-card"
          :class="`metric-card--${item.tone}`"
          @click="item.action?.()"
        >
          <span class="metric-label">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.desc }}</small>
        </button>
      </div>

      <div class="adm-deck">
        <div class="adm-deck-main">
          <section class="panel panel--charts">
            <div class="section-header section-header--tight">
              <div>
                <span class="eyebrow">平台监管</span>
                <h2>四图总览</h2>
              </div>
              <el-tag type="success" size="small" effect="plain">全平台统计</el-tag>
            </div>
            <div class="chart-grid-compact">
              <article class="chart-card">
                <div class="chart-title"><strong>活跃趋势</strong><small>查询 / 图表 / 上传</small></div>
                <div ref="platformTrendChartRef" class="chart-host"></div>
              </article>
              <article class="chart-card">
                <div class="chart-title"><strong>看板状态</strong><small>雷达图</small></div>
                <div ref="dashboardRadarChartRef" class="chart-host"></div>
              </article>
              <article class="chart-card">
                <div class="chart-title"><strong>数据源健康</strong><small>漏斗图</small></div>
                <div ref="datasourceFunnelChartRef" class="chart-host"></div>
              </article>
              <article class="chart-card">
                <div class="chart-title"><strong>安全风险</strong><small>慢查 / 拦截</small></div>
                <div ref="securityRiskChartRef" class="chart-host"></div>
              </article>
            </div>
          </section>

          <section class="panel panel--dashboards">
            <div class="section-header section-header--tight">
              <div>
                <span class="eyebrow">全平台</span>
                <h2>最近看板</h2>
              </div>
              <el-button type="primary" link @click="goDashboardManage">管理全部</el-button>
            </div>
            <div v-if="!recentDashboards.length" class="empty-inline">
              <span>暂无看板</span>
              <el-button size="small" plain @click="goDashboardManage">去管理</el-button>
            </div>
            <div v-else class="dashboard-list">
              <button
                v-for="row in recentDashboards.slice(0, 6)"
                :key="row.id"
                type="button"
                class="dashboard-row"
                @click="goDashboardManage"
              >
                <span class="dashboard-avatar">{{ dashboardInitial(row.name) }}</span>
                <div class="dashboard-main">
                  <strong>{{ row.name }}</strong>
                  <small>{{ row.ownerUserId || '-' }} · {{ formatTime(row.updatedAt) }}</small>
                </div>
                <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small" effect="plain">
                  {{ row.isPublic ? '公共' : '私密' }}
                </el-tag>
              </button>
            </div>
          </section>
        </div>

        <aside class="adm-deck-side">
          <section class="panel panel--stack panel--announce">
            <div class="section-header section-header--tight">
              <div>
                <span class="eyebrow">系统公告</span>
                <h2>通知</h2>
              </div>
            </div>
            <div v-if="!announcements.length" class="empty-inline empty-inline--sm">
              <span>暂无公告</span>
            </div>
            <div v-else class="announcement-list">
              <article v-for="a in announcements.slice(0, 3)" :key="a.id" class="announcement-card">
                <div class="announcement-head">
                  <el-tag v-if="a.pinned" type="danger" size="small">置顶</el-tag>
                  <strong>{{ a.title }}</strong>
                </div>
                <p>{{ a.content }}</p>
                <small>{{ formatTime(a.publishedAt || a.createdAt) }}</small>
              </article>
            </div>
          </section>

          <section class="panel panel--stack">
            <div class="section-header section-header--tight">
              <div>
                <span class="eyebrow">治理入口</span>
                <h2>快捷操作</h2>
              </div>
            </div>
            <div class="quick-grid">
              <button
                v-for="item in adminQuickLinks"
                :key="item.key"
                type="button"
                class="quick-card"
                @click="goModule(item.key)"
              >
                <span class="quick-icon">{{ item.badge }}</span>
                <strong>{{ item.title }}</strong>
              </button>
            </div>
          </section>

          <section class="panel panel--stack">
            <div class="section-header section-header--tight">
              <div>
                <span class="eyebrow">核心引擎</span>
                <h2>运行监控</h2>
              </div>
            </div>
            <div class="engine-list">
              <div v-for="engine in engineStatus" :key="engine.name" class="engine-item">
                <div class="engine-text">
                  <strong>{{ engine.name }}</strong>
                  <small>{{ engine.desc }}</small>
                </div>
                <el-tag :type="engine.type" size="small">{{ engine.status }}</el-tag>
              </div>
            </div>
          </section>

          <section class="panel panel--stack">
            <div class="section-header section-header--tight">
              <div>
                <span class="eyebrow">资源与安全</span>
                <h2>运行指标</h2>
              </div>
              <el-button type="primary" link @click="goPerformance">详情</el-button>
            </div>
            <div class="resource-metrics">
              <div class="resource-item">
                <div class="resource-head">
                  <span>JVM 堆内存</span>
                  <strong>{{ heapPercentText }}</strong>
                </div>
                <el-progress :percentage="heapPercentNumber" :stroke-width="8" color="#7c3aed" />
              </div>
              <div class="resource-item">
                <div class="resource-head">
                  <span>系统负载</span>
                  <strong>{{ loadText }}</strong>
                </div>
                <el-progress :percentage="loadPercent" :stroke-width="8" :status="cpuAlert ? 'exception' : undefined" />
              </div>
              <div class="resource-item">
                <div class="resource-head">
                  <span>SQL 风险压力</span>
                  <strong>{{ sqlRiskPercent }}%</strong>
                </div>
                <el-progress :percentage="sqlRiskPercent" :stroke-width="8" color="#e11d48" />
              </div>
            </div>
            <div class="security-box">
              <div>
                <span>SQL 安全检测</span>
                <small>{{ cpuAlert ? '负载较高，建议排查热点任务' : '系统运行正常' }}</small>
              </div>
              <strong>{{ sqlStats.slowCount }} 慢查 / {{ sqlStats.blockedCount }} 拦截</strong>
            </div>
          </section>

          <section class="panel panel--stack">
            <div class="section-header section-header--tight">
              <div>
                <span class="eyebrow">数据源</span>
                <h2>连接健康</h2>
              </div>
              <el-button type="primary" link @click="goModule('datasource')">管理</el-button>
            </div>
            <div class="health-row">
              <div class="health-chip health-chip--success">
                <span>启用</span>
                <strong>{{ dsSummary.enabled }}</strong>
              </div>
              <div class="health-chip health-chip--amber">
                <span>健康</span>
                <strong>{{ dsSummary.healthy }}</strong>
              </div>
              <div class="health-chip">
                <span>总数</span>
                <strong>{{ dsSummary.total }}</strong>
              </div>
            </div>
            <div v-if="!recentDatasources.length" class="empty-inline empty-inline--sm">
              <span>暂无数据源</span>
            </div>
            <div v-else class="datasource-list">
              <button
                v-for="item in recentDatasources.slice(0, 4)"
                :key="item.id || item.name"
                type="button"
                class="datasource-row"
                @click="goModule('datasource')"
              >
                <span class="datasource-name">{{ item.name || '未命名' }}</span>
                <small>{{ item.type || '-' }}</small>
                <el-tag
                  :type="String(item.lastTestStatus || '').toUpperCase() === 'OK' ? 'success' : 'info'"
                  size="small"
                  effect="plain"
                >
                  {{ String(item.lastTestStatus || '').toUpperCase() === 'OK' ? '正常' : '待探测' }}
                </el-tag>
              </button>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'
import WorkbenchHelpTip from '../../components/workbench/WorkbenchHelpTip.vue'

const workbench = inject('workbench', null)
const API_BASE = workbench?.API_BASE || 'http://localhost:8080'

const loading = ref(true)
const announcements = ref([])
const recentDashboards = ref([])
const recentDatasources = ref([])
const perfOverview = ref(null)
const platformTrendChartRef = ref(null)
const dashboardRadarChartRef = ref(null)
const datasourceFunnelChartRef = ref(null)
const securityRiskChartRef = ref(null)
const chartInstances = []
const adminCharts = reactive({ platformTrend: [], dashboardStatus: [], datasourceHealth: [], securityRisk: [] })

const dashStats = reactive({ totalQueries: 0, totalCharts: 0, totalUploads: 0, totalViews: 0 })
const dsSummary = reactive({ total: 0, enabled: 0, healthy: 0 })

const adminQuickLinks = [
  { key: 'adminChatHistory', badge: 'QH', title: '查询历史' },
  { key: 'audit', badge: 'SQL', title: 'SQL 审计' },
  { key: 'datasource', badge: 'DS', title: '数据源' },
  { key: 'stackCConfig', badge: 'CFG', title: '系统配置' },
  { key: 'performanceGovernance', badge: 'PERF', title: '性能治理' },
  { key: 'adminDashboard', badge: 'BI', title: '看板管理' }
]

const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '-')

const heapPercentNumber = computed(() => Math.max(0, Math.min(100, Math.round(perfOverview.value?.jvm?.heapUsedPercent || 0))))
const heapPercentText = computed(() => `${heapPercentNumber.value}%`)
const loadPercent = computed(() => {
  const jvm = perfOverview.value?.jvm || {}
  if (jvm.cpuUsagePercent != null) {
    return Math.max(0, Math.min(100, Math.round(Number(jvm.cpuUsagePercent))))
  }
  const load = jvm.systemLoadAverage
  const cpus = jvm.processors
  if (!load || !cpus) return 0
  return Math.max(0, Math.min(100, Math.round((load / cpus) * 100)))
})
const cpuAlert = computed(() => loadPercent.value >= (Number(perfOverview.value?.alertConfig?.cpuPercent) || 90))
const loadText = computed(() => {
  const jvm = perfOverview.value?.jvm || {}
  if (jvm.cpuUsagePercent != null) {
    return `${Number(jvm.cpuUsagePercent).toFixed(1)}%`
  }
  const load = jvm.systemLoadAverage
  if (load == null) return '—'
  return `${load.toFixed(2)}${loadPercent.value ? ` (${loadPercent.value}%)` : ''}`
})
const sqlStats = computed(() => ({
  slowCount: Number(perfOverview.value?.sqlAudit?.slowCount) || 0,
  blockedCount: Number(perfOverview.value?.sqlAudit?.blockedCount) || 0
}))
const sqlRiskPercent = computed(() => Math.min(100, Math.round(sqlStats.value.slowCount * 8 + sqlStats.value.blockedCount * 15)))
const platformHealthScore = computed(() => {
  const score = 100 - Math.min(40, dsSummary.total * 2) - Math.min(25, sqlStats.value.blockedCount * 3) - Math.min(20, cpuAlert.value ? 15 : 0)
  return `${Math.max(60, score)} / 100`
})

const opsRailItems = computed(() => {
  const items = [
    {
      key: 'health',
      tone: 'info',
      label: '平台健康',
      value: platformHealthScore.value,
      hint: '综合评分',
      action: () => goPerformance()
    },
    {
      key: 'ds',
      tone: dsSummary.total && dsSummary.healthy < dsSummary.total ? 'warning' : 'success',
      label: '数据源',
      value: dsSummary.total ? `${dsSummary.healthy}/${dsSummary.total}` : '—',
      hint: '探测正常',
      action: () => goModule('datasource')
    },
    {
      key: 'load',
      tone: cpuAlert.value ? 'warning' : 'neutral',
      label: '系统负载',
      value: `${loadPercent.value}%`,
      hint: cpuAlert.value ? '需关注' : '平稳',
      action: () => goPerformance()
    },
    {
      key: 'sql',
      tone: sqlStats.value.blockedCount ? 'danger' : 'success',
      label: 'SQL 拦截',
      value: `${sqlStats.value.blockedCount}`,
      hint: '今日拦截',
      action: () => goModule('audit')
    }
  ]
  engineStatus.value.slice(0, 2).forEach((engine) => {
    items.push({
      key: `engine-${engine.name}`,
      tone: engine.type === 'warning' ? 'warning' : 'neutral',
      label: engine.name,
      value: engine.status,
      hint: engine.desc,
      action: () => goPerformance()
    })
  })
  return items
})

const metricCards = computed(() => [
  {
    key: 'query',
    tone: 'blue',
    label: '今日总查询',
    value: `${dashStats.totalQueries} 次`,
    desc: '全平台 Text-to-SQL',
    action: () => goModule('adminChatHistory')
  },
  {
    key: 'chart',
    tone: 'green',
    label: '总图表生成',
    value: `${dashStats.totalCharts} 张`,
    desc: '自动渲染图表',
    action: () => goModule('adminDashboard')
  },
  {
    key: 'upload',
    tone: 'amber',
    label: '文件上传',
    value: `${dashStats.totalUploads} 个`,
    desc: 'Excel / CSV 总量',
    action: () => goModule('datasource')
  },
  {
    key: 'sql',
    tone: 'rose',
    label: 'SQL 拦截',
    value: `${sqlStats.value.blockedCount} 次`,
    desc: '风险 SQL 拦截',
    action: () => goModule('audit')
  }
])

const engineStatus = computed(() => [
  { name: 'Text-to-SQL', desc: '自然语言查询生成', status: '运行中', type: 'success' },
  { name: 'GraphRAG', desc: '知识图谱问答与联动', status: '运行中', type: 'success' },
  { name: '动态渲染', desc: '图表与看板组件渲染', status: '运行中', type: 'success' },
  { name: '路由关卡', desc: '权限校验与安全拦截', status: cpuAlert.value ? '关注' : '正常', type: cpuAlert.value ? 'warning' : 'success' }
])

const platformTrendRows = computed(() =>
  adminCharts.platformTrend.length ? adminCharts.platformTrend : [{ label: '暂无', queries: 0, charts: 0, uploads: 0 }]
)
const dashboardStatusRows = computed(() => (adminCharts.dashboardStatus.length ? adminCharts.dashboardStatus : []))
const datasourceHealthRows = computed(() => (adminCharts.datasourceHealth.length ? adminCharts.datasourceHealth : []))
const securityRiskRows = computed(() => (adminCharts.securityRisk.length ? adminCharts.securityRisk : []))

function getChart(el, index) {
  if (!el) return null
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  chartInstances[index] = chart
  return chart
}

function renderCharts() {
  const statusMax = Math.max(1, ...dashboardStatusRows.value.map((item) => Number(item.value) || 0))
  const compactGrid = { left: 34, right: 14, top: 28, bottom: 28 }

  getChart(platformTrendChartRef.value, 0)?.setOption(
    {
      color: ['#2563eb', '#10b981', '#f59e0b'],
      tooltip: { trigger: 'axis' },
      legend: { top: 0, right: 4, itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 10 } },
      grid: compactGrid,
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: platformTrendRows.value.map((item) => item.label),
        axisLine: { lineStyle: { color: '#d8e0ef' } },
        axisLabel: { fontSize: 10 }
      },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } }, axisLabel: { fontSize: 10 } },
      series: [
        { name: '查询', type: 'line', stack: 'total', smooth: true, areaStyle: { opacity: 0.16 }, data: platformTrendRows.value.map((item) => Number(item.queries) || 0) },
        { name: '图表', type: 'line', stack: 'total', smooth: true, areaStyle: { opacity: 0.14 }, data: platformTrendRows.value.map((item) => Number(item.charts) || 0) },
        { name: '上传', type: 'line', stack: 'total', smooth: true, areaStyle: { opacity: 0.12 }, data: platformTrendRows.value.map((item) => Number(item.uploads) || 0) }
      ]
    },
    true
  )

  getChart(dashboardRadarChartRef.value, 1)?.setOption(
    {
      color: ['#7c3aed'],
      tooltip: {},
      radar: {
        radius: '58%',
        center: ['50%', '52%'],
        indicator: dashboardStatusRows.value.map((item) => ({ name: item.name, max: Math.max(statusMax, Number(item.value) || 1) })),
        axisName: { fontSize: 10 }
      },
      series: [{ type: 'radar', areaStyle: { opacity: 0.16 }, data: [{ name: '看板状态', value: dashboardStatusRows.value.map((item) => Number(item.value) || 0) }] }]
    },
    true
  )

  getChart(datasourceFunnelChartRef.value, 2)?.setOption(
    {
      color: ['#0f766e', '#10b981', '#f59e0b', '#ef4444'],
      tooltip: { trigger: 'item' },
      series: [{
        name: '数据源健康',
        type: 'funnel',
        left: '10%',
        top: 8,
        bottom: 4,
        width: '80%',
        sort: 'descending',
        label: { fontSize: 10, formatter: '{b}: {c}' },
        data: datasourceHealthRows.value.map((item) => ({ name: item.name, value: Number(item.value) || 0 }))
      }]
    },
    true
  )

  getChart(securityRiskChartRef.value, 3)?.setOption(
    {
      color: ['#e11d48'],
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: 56, right: 12, top: 8, bottom: 20 },
      xAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } }, axisLabel: { fontSize: 10 } },
      yAxis: {
        type: 'category',
        data: securityRiskRows.value.map((item) => item.name),
        axisLine: { lineStyle: { color: '#d8e0ef' } },
        axisLabel: { fontSize: 10 }
      },
      series: [{ type: 'bar', barWidth: 14, borderRadius: [0, 8, 8, 0], data: securityRiskRows.value.map((item) => Number(item.value) || 0) }]
    },
    true
  )
}

function resizeCharts() {
  chartInstances.forEach((chart) => chart?.resize?.())
}

function goModule(key) {
  if (workbench?.activeModule) {
    workbench.activeModule.value = key
  }
}

function goDashboardManage() {
  goModule('adminDashboard')
}

function goPerformance() {
  goModule('performanceGovernance')
}

function dashboardInitial(name) {
  return String(name || '看').trim().slice(0, 1).toUpperCase()
}

function summarizeDatasources(list) {
  const items = Array.isArray(list) ? list : []
  dsSummary.total = items.length
  dsSummary.enabled = items.filter((d) => String(d.status || '').toUpperCase() === 'ENABLED').length
  dsSummary.healthy = items.filter((d) => String(d.lastTestStatus || '').toUpperCase() === 'OK').length
  recentDatasources.value = items.slice(0, 4)
}

const load = async () => {
  loading.value = true
  restoreSessionHeader()
  try {
    const [summaryRes, statsRes, perfRes, dsRes] = await Promise.all([
      axios.get(`${API_BASE}/api/c/workbench/summary`),
      axios.get(`${API_BASE}/api/c/admin/dashboards/stats`),
      axios.get(`${API_BASE}/api/c/admin/performance/overview`),
      axios.get(`${API_BASE}/api/datasources`)
    ])
    if (summaryRes.data.code !== 200) throw new Error(summaryRes.data.message)
    const d = summaryRes.data.data || {}
    announcements.value = d.announcements || []
    recentDashboards.value = d.recentDashboards || []
    adminCharts.platformTrend = Array.isArray(d.adminCharts?.platformTrend) ? d.adminCharts.platformTrend : []
    adminCharts.dashboardStatus = Array.isArray(d.adminCharts?.dashboardStatus) ? d.adminCharts.dashboardStatus : []
    adminCharts.datasourceHealth = Array.isArray(d.adminCharts?.datasourceHealth) ? d.adminCharts.datasourceHealth : []
    adminCharts.securityRisk = Array.isArray(d.adminCharts?.securityRisk) ? d.adminCharts.securityRisk : []

    if (statsRes.data.code === 200) {
      const s = statsRes.data.data || {}
      dashStats.totalQueries = Number(s.totalQueries ?? s.totalCount) || 0
      dashStats.totalCharts = Number(s.totalCharts ?? s.chartCount) || 0
      dashStats.totalUploads = Number(s.totalUploads ?? s.uploadCount) || 0
      dashStats.totalViews = Number(s.totalViews) || 0
    }

    perfOverview.value = perfRes.data.code === 200 ? perfRes.data.data || null : null
    summarizeDatasources(dsRes.data.code === 200 ? dsRes.data.data : [])
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
    nextTick(renderCharts)
  }
}

onMounted(() => {
  load()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  chartInstances.forEach((chart) => chart?.dispose?.())
})
</script>

<style scoped>
.admin-workbench {
  min-height: 100%;
  padding: 0 2px 16px;
  color: #172033;
}

.adm-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid #334155;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 55%, #312e81 100%);
  color: #f8fafc;
}

.adm-bar-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.adm-bar-tag {
  border: 0;
  background: linear-gradient(135deg, #0d9488, #2563eb);
}

.adm-bar-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}

.adm-bar-health {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
  color: #e2e8f0;
}

.adm-bar-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.adm-skeleton,
.panel,
.metric-card {
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e8edf7;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.04);
}

.adm-skeleton {
  margin-top: 10px;
  padding: 16px;
}

.adm-ops-rail {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
}

.adm-ops-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid #e2e8f0;
  background: #fff;
  cursor: pointer;
  text-align: left;
}

.adm-ops-chip strong {
  font-size: 12px;
  color: #0f172a;
}

.adm-ops-chip-label {
  font-size: 10px;
  font-weight: 700;
  color: #64748b;
}

.adm-ops-chip small {
  font-size: 10px;
  color: #94a3b8;
}

.adm-ops-chip--success { border-color: #bbf7d0; background: #f0fdf4; }
.adm-ops-chip--warning { border-color: #fde68a; background: #fffbeb; }
.adm-ops-chip--danger { border-color: #fecaca; background: #fef2f2; }
.adm-ops-chip--info { border-color: #bfdbfe; background: #eff6ff; }
.adm-ops-chip--neutral { border-color: #e2e8f0; background: #fff; }

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 10px 0;
}

.metric-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.metric-card:hover {
  border-color: #93c5fd;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.08);
}

.metric-card--blue { border-left: 3px solid #2563eb; }
.metric-card--green { border-left: 3px solid #10b981; }
.metric-card--amber { border-left: 3px solid #f59e0b; }
.metric-card--rose { border-left: 3px solid #e11d48; }

.metric-label {
  font-size: 11px;
  color: #64748b;
}

.metric-card strong {
  font-size: 22px;
  color: #0f172a;
  line-height: 1.2;
}

.metric-card small {
  color: #94a3b8;
  font-size: 11px;
}

.adm-deck {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.75fr);
  gap: 10px;
  align-items: start;
}

.adm-deck-main {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.adm-deck-side {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.panel {
  padding: 12px 14px;
}

.panel--stack + .panel--stack {
  margin-top: 0;
}

.panel--announce {
  flex-shrink: 0;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.section-header--tight h2 {
  margin: 2px 0 0;
  font-size: 15px;
}

.eyebrow {
  color: #2563eb;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.chart-grid-compact {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.chart-card {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 8px;
  border-radius: 10px;
  border: 1px solid #edf2f7;
  background: #f8fafc;
}

.chart-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 4px;
}

.chart-title strong {
  font-size: 11px;
  color: #334155;
}

.chart-title small {
  font-size: 10px;
  color: #94a3b8;
}

.chart-host {
  flex: 1;
  width: 100%;
  min-height: 150px;
}

.dashboard-list,
.datasource-list,
.announcement-list,
.engine-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dashboard-row,
.datasource-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  background: #f8fafc;
  text-align: left;
  cursor: pointer;
}

.dashboard-row:hover,
.datasource-row:hover {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.dashboard-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #334155, #6366f1);
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 12px;
}

.dashboard-main {
  min-width: 0;
}

.dashboard-main strong {
  display: block;
  font-size: 13px;
  color: #172033;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dashboard-main small,
.datasource-row small {
  color: #94a3b8;
  font-size: 11px;
}

.datasource-name {
  font-size: 12px;
  font-weight: 600;
  color: #172033;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.quick-card {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 8px 10px;
  border: 1px solid #e6ecf7;
  border-radius: 10px;
  background: #f8fafc;
  cursor: pointer;
  text-align: left;
}

.quick-card:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}

.quick-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  height: 22px;
  padding: 0 6px;
  border-radius: 999px;
  background: #1e293b;
  color: #e2e8f0;
  font-size: 10px;
  font-weight: 800;
  flex-shrink: 0;
}

.quick-card strong {
  font-size: 12px;
  color: #172033;
}

.engine-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid #edf2f7;
  background: #f8fafc;
}

.engine-text strong {
  display: block;
  font-size: 12px;
  color: #172033;
}

.engine-text small {
  color: #94a3b8;
  font-size: 10px;
}

.resource-metrics {
  display: grid;
  gap: 10px;
}

.resource-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 4px;
  font-size: 11px;
  color: #64748b;
}

.resource-head strong {
  color: #0f172a;
  font-size: 13px;
}

.resource-item :deep(.el-progress__text) {
  min-width: 32px;
  font-size: 11px !important;
}

.security-box {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid #dbeafe;
  background: linear-gradient(135deg, #eff6ff, #f5f3ff);
}

.security-box span {
  display: block;
  font-size: 11px;
  font-weight: 700;
  color: #64748b;
}

.security-box small {
  display: block;
  margin-top: 2px;
  font-size: 10px;
  color: #94a3b8;
}

.security-box strong {
  font-size: 13px;
  color: #0f172a;
  white-space: nowrap;
}

.health-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-bottom: 8px;
}

.health-chip {
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid #e5e7eb;
  background: #f8fafc;
}

.health-chip span {
  display: block;
  font-size: 10px;
  color: #64748b;
}

.health-chip strong {
  display: block;
  margin-top: 2px;
  font-size: 18px;
  color: #0f172a;
}

.health-chip--success {
  background: #f0fdf4;
  border-color: #bbf7d0;
}

.health-chip--amber {
  background: #fffbeb;
  border-color: #fde68a;
}

.announcement-card {
  padding: 8px 10px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #edf2f7;
}

.announcement-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.announcement-head strong {
  font-size: 12px;
  color: #172033;
}

.announcement-card p {
  margin: 4px 0 0;
  color: #64748b;
  line-height: 1.45;
  font-size: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.announcement-card small {
  color: #94a3b8;
  font-size: 10px;
}

.empty-inline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px dashed #dbeafe;
  background: #f8fbff;
  font-size: 12px;
  color: #64748b;
}

.empty-inline--sm {
  padding: 8px 10px;
}

@media (max-width: 1200px) {
  .adm-deck {
    grid-template-columns: 1fr;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .adm-bar-actions {
    width: 100%;
  }

  .metric-grid,
  .quick-grid,
  .health-row {
    grid-template-columns: 1fr 1fr;
  }

  .chart-grid-compact {
    grid-template-columns: 1fr;
  }

  .chart-host {
    min-height: 180px;
  }
}
</style>
