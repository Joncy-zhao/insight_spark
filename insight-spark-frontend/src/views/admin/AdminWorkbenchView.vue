<template>
  <section class="admin-workbench">
    <div class="hero-card">
      <div class="hero-content">
        <el-tag class="hero-tag" effect="dark" round>全平台总览 · 总管控</el-tag>
        <h1>首页工作台</h1>
        <p>
          面向管理员的全局概览，集中展示全平台查询、图表、上传、安全审计、数据源健康与核心引擎运行状态，
          帮助你快速定位系统瓶颈、风险告警和关键业务变化。
        </p>
        <div class="hero-actions">
          <el-button type="primary" size="large" @click="goDashboardManage">进入看板管理</el-button>
          <el-button size="large" @click="goPerformance">查看性能治理</el-button>
          <el-button class="ghost-button" size="large" :loading="loading" @click="load">刷新数据</el-button>
        </div>
      </div>
      <div class="hero-visual">
        <div class="orb orb-a"></div>
        <div class="orb orb-b"></div>
        <div class="glass-panel">
          <span>平台健康</span>
          <strong>{{ platformHealthScore }}</strong>
          <small>综合数据源、审计与引擎状态</small>
        </div>
      </div>
    </div>

    <el-skeleton v-if="loading" class="skeleton-card" :rows="8" animated />
    <template v-else>
      <div class="metric-grid">
        <article v-for="item in metricCards" :key="item.key" class="metric-card" :class="`metric-card--${item.tone}`">
          <div class="metric-topline">
            <span>{{ item.label }}</span>
            <i></i>
          </div>
          <strong>{{ item.value }}</strong>
          <small>{{ item.desc }}</small>
        </article>
      </div>

      <section class="panel chart-panel">
        <div class="section-header">
          <div>
            <span class="eyebrow">平台监管图表</span>
            <h2>管理员端专属四图总览</h2>
          </div>
          <el-tag type="success" effect="plain">全平台真实统计</el-tag>
        </div>
        <div class="chart-grid">
          <article class="chart-card chart-card--wide">
            <div class="chart-title">
              <strong>平台近 7 日活跃趋势</strong>
              <small>堆叠面积图 · 查询 / 图表 / 上传</small>
            </div>
            <div ref="platformTrendChartRef" class="chart-host"></div>
          </article>
          <article class="chart-card">
            <div class="chart-title">
              <strong>看板状态雷达图</strong>
              <small>雷达图 · 公共 / 私密 / 启用 / 停用</small>
            </div>
            <div ref="dashboardRadarChartRef" class="chart-host"></div>
          </article>
          <article class="chart-card">
            <div class="chart-title">
              <strong>数据源健康漏斗</strong>
              <small>漏斗图 · 连接可用性分析</small>
            </div>
            <div ref="datasourceFunnelChartRef" class="chart-host"></div>
          </article>
          <article class="chart-card chart-card--wide">
            <div class="chart-title">
              <strong>安全风险矩阵</strong>
              <small>横向条形图 · 慢查 / 拦截 / 风险 / 上传</small>
            </div>
            <div ref="securityRiskChartRef" class="chart-host"></div>
          </article>
        </div>
      </section>

      <div class="ops-grid">
        <section class="panel panel--wide compact-panel">
          <div class="section-header">
            <div>
              <span class="eyebrow">页面功能</span>
              <h2>平台核心统计</h2>
            </div>
            <el-button text type="primary" @click="goModule('adminDashboard')">管理看板</el-button>
          </div>
          <div class="stat-grid compact-stat-grid">
            <article class="stat-card stat-card--blue">
              <span class="stat-label">今日总查询数</span>
              <strong class="stat-value">{{ dashStats.totalQueries }}</strong>
              <small>自然语言查询与 Text-to-SQL</small>
            </article>
            <article class="stat-card stat-card--green">
              <span class="stat-label">总图表生成数</span>
              <strong class="stat-value">{{ dashStats.totalCharts }}</strong>
              <small>折线、柱状、饼图等自动渲染</small>
            </article>
            <article class="stat-card stat-card--amber">
              <span class="stat-label">用户文件上传数</span>
              <strong class="stat-value">{{ dashStats.totalUploads }}</strong>
              <small>Excel / CSV 文件上传总量</small>
            </article>
            <article class="stat-card stat-card--rose">
              <span class="stat-label">SQL 拦截次数</span>
              <strong class="stat-value">{{ sqlStats.blockedCount }}</strong>
              <small>风险 SQL 与违规访问拦截</small>
            </article>
          </div>
          <div class="quick-admin-actions">
            <button type="button" @click="goModule('adminChatHistory')">查询历史</button>
            <button type="button" @click="goModule('audit')">SQL 审计</button>
            <button type="button" @click="goModule('datasource')">数据源管理</button>
            <button type="button" @click="goModule('stackCConfig')">系统配置</button>
          </div>
        </section>

        <section class="panel compact-panel status-panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">核心引擎状态</span>
              <h2>运行监控</h2>
            </div>
          </div>
          <div class="engine-list compact-engine-list">
            <div v-for="engine in engineStatus" :key="engine.name" class="engine-item">
              <div>
                <strong>{{ engine.name }}</strong>
                <small>{{ engine.desc }}</small>
              </div>
              <el-tag :type="engine.type" size="small">{{ engine.status }}</el-tag>
            </div>
          </div>
        </section>

        <section class="panel compact-panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">系统公告</span>
              <h2>通知发布与触达</h2>
            </div>
          </div>
          <div v-if="!announcements.length" class="empty-compact">
            <strong>暂无公告</strong>
            <small>可在系统配置中发布平台通知</small>
          </div>
          <div v-else class="announcement-list compact-announcements">
            <article v-for="a in announcements.slice(0, 3)" :key="a.id" class="announcement-card">
              <div>
                <el-tag v-if="a.pinned" type="danger" size="small">置顶</el-tag>
                <strong>{{ a.title }}</strong>
              </div>
              <p>{{ a.content }}</p>
              <small>{{ formatTime(a.publishedAt || a.createdAt) }}</small>
            </article>
          </div>
        </section>
      </div>

      <div class="content-grid content-grid--bottom compact-bottom-grid">
        <section class="panel insight-panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">运维建议</span>
              <h2>今日关注项</h2>
            </div>
          </div>
          <div class="insight-list">
            <div v-for="item in opsInsights" :key="item.title" class="insight-item" :class="`insight-item--${item.tone}`">
              <span>{{ item.title }}</span>
              <strong>{{ item.value }}</strong>
              <small>{{ item.desc }}</small>
            </div>
          </div>
        </section>

        <section class="panel datasource-health-panel">
          <div class="health-header-row">
            <div>
              <span class="eyebrow">数据源健康状态</span>
              <h2>连接与探测</h2>
            </div>
            <el-button text type="primary" @click="goModule('datasource')">管理数据源</el-button>
          </div>
          <div class="health-compact-grid">
            <div class="health-item health-item--success">
              <span>启用</span>
              <strong>{{ dsSummary.enabled }}</strong>
            </div>
            <div class="health-item health-item--amber">
              <span>健康</span>
              <strong>{{ dsSummary.healthy }}</strong>
            </div>
            <div class="health-item health-item--slate">
              <span>总数</span>
              <strong>{{ dsSummary.total }}</strong>
            </div>
          </div>
          <div class="datasource-mini-list">
            <template v-if="recentDatasources.length">
              <div v-for="item in recentDatasources.slice(0, 3)" :key="item.id || item.name" class="datasource-mini-row">
                <span>{{ item.name || '未命名数据源' }}</span>
                <small>{{ item.type || '-' }}</small>
                <el-tag :type="String(item.lastTestStatus || '').toUpperCase() === 'OK' ? 'success' : 'info'" size="small" effect="light">
                  {{ String(item.lastTestStatus || '').toUpperCase() === 'OK' ? '正常' : '待探测' }}
                </el-tag>
              </div>
            </template>
            <div v-else class="datasource-mini-empty">暂无最近数据源，点击右上角进行维护</div>
          </div>
        </section>

        <section class="panel status-panel resource-security-panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">资源与安全</span>
              <h2>运行指标</h2>
            </div>
          </div>
          <div class="resource-metrics">
            <div class="resource-item">
              <div>
                <span>JVM 堆内存</span>
                <strong>{{ heapPercentText }}</strong>
              </div>
              <el-progress :percentage="heapPercentNumber" :stroke-width="10" color="#7c3aed" striped />
            </div>
            <div class="resource-item">
              <div>
                <span>系统负载</span>
                <strong>{{ loadText }}</strong>
              </div>
              <el-progress :percentage="loadPercent" :stroke-width="10" :status="cpuAlert ? 'exception' : undefined" striped />
            </div>
            <div class="resource-item">
              <div>
                <span>SQL 风险压力</span>
                <strong>{{ sqlRiskPercent }}%</strong>
              </div>
              <el-progress :percentage="sqlRiskPercent" :stroke-width="10" color="#e11d48" striped />
            </div>
          </div>
          <div class="security-box compact-security-box">
            <div>
              <span>SQL 安全检测</span>
              <small>{{ cpuAlert ? '负载较高，建议排查热点任务' : '系统运行正常，持续监控中' }}</small>
            </div>
            <strong>{{ sqlStats.slowCount }} 慢查 / {{ sqlStats.blockedCount }} 拦截</strong>
          </div>
        </section>
      </div>

      <section class="panel dashboard-panel">
        <div class="section-header">
          <div>
            <span class="eyebrow">最近看板（全平台）</span>
            <h2>快速进入管理视图</h2>
          </div>
          <el-button type="primary" plain @click="goDashboardManage">管理全部看板</el-button>
        </div>
        <div v-if="!recentDashboards.length" class="empty-compact">
          <strong>暂无看板</strong>
          <small>创建公共看板后会显示在这里</small>
        </div>
        <div v-else class="dashboard-list dashboard-list--admin">
          <button v-for="row in recentDashboards" :key="row.id" type="button" class="dashboard-row" @click="goDashboardManage">
            <div class="dashboard-main">
              <span class="dashboard-avatar">{{ dashboardInitial(row.name) }}</span>
              <div>
                <strong>{{ row.name }}</strong>
                <small>{{ row.description || '暂无描述' }}</small>
              </div>
            </div>
            <span class="dashboard-owner">{{ row.ownerUserId || '-' }}</span>
            <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small" effect="light">
              {{ row.isPublic ? '公共' : '私密' }}
            </el-tag>
            <span class="dashboard-time">{{ formatTime(row.updatedAt) }}</span>
          </button>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'
const workbench = inject('workbench', null)

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

const formatTime = (v) => (v ? String(v).replace('T', ' ') : '-')
const formatBytes = (n) => {
  if (n == null) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = Number(n)
  let idx = 0
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024
    idx++
  }
  return `${value.toFixed(idx > 0 ? 1 : 0)} ${units[idx]}`
}

const heapPercentNumber = computed(() => Math.max(0, Math.min(100, Math.round(perfOverview.value?.jvm?.heapUsedPercent || 0))))
const heapPercentText = computed(() => `${heapPercentNumber.value}%`)
const loadPercent = computed(() => {
  const jvm = perfOverview.value?.jvm
  const load = jvm?.systemLoadAverage
  const cpus = jvm?.processors
  if (!load || !cpus) return 0
  return Math.max(0, Math.min(100, Math.round((load / cpus) * 100)))
})
const cpuAlert = computed(() => loadPercent.value >= (Number(perfOverview.value?.alertConfig?.cpuPercent) || 90))
const loadText = computed(() => {
  const load = perfOverview.value?.jvm?.systemLoadAverage
  if (load == null) return '0%'
  return `${load.toFixed(2)}${loadPercent.value ? ` (${loadPercent.value}%)` : ' (0%)'}`
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

const metricCards = computed(() => [
  { key: 'query', tone: 'blue', label: '今日总查询数', value: `${dashStats.totalQueries} 次`, desc: '全平台自然语言查询与 Text-to-SQL' },
  { key: 'chart', tone: 'green', label: '总图表生成数', value: `${dashStats.totalCharts} 张`, desc: '覆盖折线、柱状、饼图等图表' },
  { key: 'upload', tone: 'amber', label: '用户文件上传数', value: `${dashStats.totalUploads} 个`, desc: 'Excel / CSV 业务文件上传总量' },
  { key: 'sql', tone: 'rose', label: 'SQL 拦截次数', value: `${sqlStats.value.blockedCount} 次`, desc: '风险 SQL 与违规访问拦截' }
])

const engineStatus = computed(() => [
  { name: 'Text-to-SQL', desc: '自然语言查询生成', status: '运行中', type: 'success' },
  { name: 'GraphRAG', desc: '知识图谱问答与联动', status: '运行中', type: 'success' },
  { name: '动态渲染', desc: '图表与看板组件渲染', status: '运行中', type: 'success' },
  { name: '路由关卡', desc: '权限校验与安全拦截', status: cpuAlert.value ? '关注' : '正常', type: cpuAlert.value ? 'warning' : 'success' }
])

const opsInsights = computed(() => [
  {
    title: '数据源健康率',
    value: dsSummary.total ? `${Math.round((dsSummary.healthy / dsSummary.total) * 100)}%` : '—',
    desc: dsSummary.total ? `${dsSummary.healthy}/${dsSummary.total} 个探测正常` : '暂无数据源连接',
    tone: dsSummary.total && dsSummary.healthy < dsSummary.total ? 'warning' : 'success'
  },
  {
    title: 'SQL 风险拦截',
    value: `${sqlStats.value.blockedCount}`,
    desc: sqlStats.value.blockedCount ? '建议查看 SQL 审计明细' : '今日未发现高风险拦截',
    tone: sqlStats.value.blockedCount ? 'danger' : 'success'
  },
  {
    title: '系统负载状态',
    value: `${loadPercent.value}%`,
    desc: cpuAlert.value ? '负载偏高，建议排查任务' : '运行平稳，暂无处置项',
    tone: cpuAlert.value ? 'warning' : 'info'
  }
])

const platformTrendRows = computed(() => adminCharts.platformTrend.length ? adminCharts.platformTrend : [{ label: '暂无', queries: 0, charts: 0, uploads: 0 }])
const dashboardStatusRows = computed(() => adminCharts.dashboardStatus.length ? adminCharts.dashboardStatus : [])
const datasourceHealthRows = computed(() => adminCharts.datasourceHealth.length ? adminCharts.datasourceHealth : [])
const securityRiskRows = computed(() => adminCharts.securityRisk.length ? adminCharts.securityRisk : [])

function getChart(el, index) {
  if (!el) return null
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  chartInstances[index] = chart
  return chart
}

function renderCharts() {
  const statusMax = Math.max(1, ...dashboardStatusRows.value.map((item) => Number(item.value) || 0))
  getChart(platformTrendChartRef.value, 0)?.setOption({
    color: ['#2563eb', '#10b981', '#f59e0b'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 8, itemWidth: 10, itemHeight: 10 },
    grid: { left: 38, right: 18, top: 34, bottom: 32 },
    xAxis: { type: 'category', boundaryGap: false, data: platformTrendRows.value.map((item) => item.label), axisLine: { lineStyle: { color: '#d8e0ef' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } } },
    series: [
      { name: '查询', type: 'line', stack: 'total', smooth: true, areaStyle: { opacity: 0.18 }, data: platformTrendRows.value.map((item) => Number(item.queries) || 0) },
      { name: '图表', type: 'line', stack: 'total', smooth: true, areaStyle: { opacity: 0.16 }, data: platformTrendRows.value.map((item) => Number(item.charts) || 0) },
      { name: '上传', type: 'line', stack: 'total', smooth: true, areaStyle: { opacity: 0.14 }, data: platformTrendRows.value.map((item) => Number(item.uploads) || 0) }
    ]
  }, true)

  getChart(dashboardRadarChartRef.value, 1)?.setOption({
    color: ['#7c3aed'],
    tooltip: {},
    radar: {
      radius: '66%',
      indicator: dashboardStatusRows.value.map((item) => ({ name: item.name, max: Math.max(statusMax, Number(item.value) || 1) }))
    },
    series: [{ type: 'radar', areaStyle: { opacity: 0.18 }, data: [{ name: '看板状态', value: dashboardStatusRows.value.map((item) => Number(item.value) || 0) }] }]
  }, true)

  getChart(datasourceFunnelChartRef.value, 2)?.setOption({
    color: ['#0f766e', '#10b981', '#f59e0b', '#ef4444'],
    tooltip: { trigger: 'item' },
    series: [{
      name: '数据源健康',
      type: 'funnel',
      left: '8%',
      top: 18,
      bottom: 12,
      width: '84%',
      sort: 'descending',
      label: { formatter: '{b}: {c}' },
      data: datasourceHealthRows.value.map((item) => ({ name: item.name, value: Number(item.value) || 0 }))
    }]
  }, true)

  getChart(securityRiskChartRef.value, 3)?.setOption({
    color: ['#e11d48'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 72, right: 20, top: 18, bottom: 28 },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } } },
    yAxis: { type: 'category', data: securityRiskRows.value.map((item) => item.name), axisLine: { lineStyle: { color: '#d8e0ef' } } },
    series: [{ type: 'bar', barWidth: 18, borderRadius: [0, 10, 10, 0], data: securityRiskRows.value.map((item) => Number(item.value) || 0) }]
  }, true)
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
  padding: 2px 4px 28px;
  color: #172033;
}
.hero-card {
  position: relative;
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) 260px;
  gap: 20px;
  padding: 24px 28px;
  border: 1px solid rgba(37, 99, 235, 0.14);
  border-radius: 28px;
  background:
    radial-gradient(circle at 14% 10%, rgba(59, 130, 246, 0.2), transparent 26%),
    linear-gradient(135deg, #f8fbff 0%, #eef5ff 48%, #f7f3ff 100%);
  box-shadow: 0 24px 60px rgba(37, 99, 235, 0.12);
}
.hero-content { position: relative; z-index: 2; }
.hero-tag { border: 0; background: linear-gradient(135deg, #0f766e, #2563eb); }
.hero-card h1 { margin: 12px 0 8px; font-size: 30px; line-height: 1.15; letter-spacing: -0.04em; color: #0f172a; }
.hero-card p { max-width: 760px; margin: 0; color: #526179; font-size: 15px; line-height: 1.9; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
.ghost-button { background: rgba(255, 255, 255, 0.72); }
.hero-visual { position: relative; min-height: 150px; }
.orb { position: absolute; border-radius: 999px; filter: blur(1px); }
.orb-a { width: 180px; height: 180px; right: 58px; top: 12px; background: linear-gradient(135deg, #60a5fa, #a78bfa); opacity: 0.75; }
.orb-b { width: 120px; height: 120px; right: 190px; bottom: 8px; background: linear-gradient(135deg, #22d3ee, #34d399); opacity: 0.68; }
.glass-panel {
  position: absolute;
  right: 18px;
  bottom: 22px;
  width: 230px;
  padding: 22px;
  border: 1px solid rgba(255, 255, 255, 0.62);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.58);
  backdrop-filter: blur(18px);
  box-shadow: 0 20px 48px rgba(30, 64, 175, 0.18);
}
.glass-panel span, .glass-panel small { display: block; color: #64748b; }
.glass-panel strong { display: block; margin: 8px 0; font-size: 42px; color: #2563eb; }
.skeleton-card, .panel, .metric-card {
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid #e8edf7;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.06);
}
.skeleton-card { margin-top: 18px; padding: 24px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin: 18px 0; }
.metric-card { position: relative; overflow: hidden; padding: 20px; }
.metric-card::after { content: ''; position: absolute; right: -24px; top: -24px; width: 86px; height: 86px; border-radius: 999px; opacity: 0.18; }
.metric-card--blue::after { background: #2563eb; }
.metric-card--green::after { background: #10b981; }
.metric-card--amber::after { background: #f59e0b; }
.metric-card--rose::after { background: #e11d48; }
.metric-topline { display: flex; align-items: center; justify-content: space-between; color: #64748b; font-size: 13px; }
.metric-topline i { width: 10px; height: 10px; border-radius: 999px; background: #2563eb; box-shadow: 0 0 0 6px rgba(37, 99, 235, 0.08); }
.metric-card strong { display: block; margin: 12px 0 8px; font-size: 30px; color: #0f172a; }
.metric-card small { color: #94a3b8; }
.chart-panel { margin-bottom: 18px; }
.chart-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.chart-card { padding: 18px; border-radius: 20px; border: 1px solid #edf2f7; background: linear-gradient(180deg, #ffffff, #f8fbff); }
.chart-card--wide { grid-column: span 2; }
.chart-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.chart-title strong { color: #172033; font-size: 15px; }
.chart-title small { color: #94a3b8; font-size: 12px; text-align: right; }
.chart-host { width: 100%; height: 280px; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(320px, 0.7fr); gap: 18px; margin-bottom: 18px; }
.content-grid--bottom { grid-template-columns: 0.9fr 1.1fr 360px; align-items: stretch; }
.ops-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) 320px 320px; gap: 18px; margin-bottom: 18px; align-items: stretch; }
.panel { padding: 22px; }
.panel--wide { min-height: 100%; }
.compact-panel { padding: 18px; min-height: auto; }
.section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.section-header.compact { margin-bottom: 16px; }
.eyebrow { color: #2563eb; font-size: 12px; font-weight: 700; letter-spacing: 0.08em; }
.section-header h2 { margin: 5px 0 0; font-size: 20px; color: #111827; }
.stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.compact-stat-grid { gap: 10px; }
.stat-card { padding: 18px; border-radius: 18px; border: 1px solid #edf2f7; }
.compact-stat-grid .stat-card { padding: 14px; min-height: 112px; }
.stat-card--blue { background: #eff6ff; border-color: #bfdbfe; }
.stat-card--green { background: #f0fdf4; border-color: #bbf7d0; }
.stat-card--amber { background: #fffbeb; border-color: #fde68a; }
.stat-card--rose { background: #fff1f2; border-color: #fecdd3; }
.stat-label { font-size: 12px; color: #64748b; }
.stat-value { display: block; margin: 10px 0 6px; font-size: 28px; color: #0f172a; }
.stat-card small { color: #94a3b8; }
.quick-admin-actions { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.quick-admin-actions button { height: 36px; border: 1px solid #dbeafe; border-radius: 12px; background: #f8fbff; color: #2563eb; font-weight: 700; cursor: pointer; }
.quick-admin-actions button:hover { border-color: #93c5fd; background: #eff6ff; }
.engine-list { display: grid; gap: 10px; }
.compact-engine-list { gap: 8px; }
.engine-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 15px; border-radius: 16px; background: #f8fbff; border: 1px solid #edf2f7; }
.compact-engine-list .engine-item { padding: 11px 12px; }
.engine-item small { display: block; color: #94a3b8; margin-top: 4px; }
.empty-compact { display: flex; flex-direction: column; justify-content: center; min-height: 130px; padding: 18px; border-radius: 18px; border: 1px dashed #dbeafe; background: linear-gradient(135deg, #f8fbff, #ffffff); text-align: center; }
.empty-compact strong { color: #334155; }
.empty-compact small { margin-top: 6px; color: #94a3b8; }
.announcement-list { display: grid; gap: 12px; }
.compact-announcements { gap: 8px; max-height: 198px; overflow: auto; }
.announcement-card { padding: 15px; border-radius: 16px; background: #f8fafc; border: 1px solid #edf2f7; }
.compact-announcements .announcement-card { padding: 12px; }
.announcement-card div { display: flex; align-items: center; gap: 8px; }
.announcement-card p { margin: 8px 0; color: #64748b; line-height: 1.65; }
.compact-announcements .announcement-card p { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.announcement-card small { color: #94a3b8; }
.insight-list { display: grid; gap: 10px; }
.insight-item { padding: 14px; border-radius: 16px; border: 1px solid #edf2f7; background: #f8fafc; }
.insight-item span, .insight-item small { display: block; color: #64748b; }
.insight-item strong { display: block; margin: 6px 0; font-size: 24px; color: #0f172a; }
.insight-item--success { background: #f0fdf4; border-color: #bbf7d0; }
.insight-item--warning { background: #fffbeb; border-color: #fde68a; }
.insight-item--danger { background: #fff1f2; border-color: #fecdd3; }
.insight-item--info { background: #eff6ff; border-color: #bfdbfe; }
.datasource-health-panel { display: flex; flex-direction: column; gap: 12px; }
.health-header-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.health-compact-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.health-item { padding: 12px 13px; border-radius: 14px; }
.health-item span { display: block; color: #64748b; font-size: 12px; }
.health-item strong { display: block; margin-top: 6px; font-size: 22px; color: #0f172a; }
.health-item--success { background: #f0fdf4; }
.health-item--amber { background: #fffbeb; }
.health-item--slate { background: #f8fafc; }
.datasource-mini-list { display: grid; gap: 8px; }
.datasource-mini-row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 14px; border: 1px solid #edf2f7; background: #fbfdff; }
.datasource-mini-row span, .datasource-mini-row small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.datasource-mini-row span { font-weight: 600; color: #172033; }
.datasource-mini-row small { color: #64748b; }
.datasource-mini-empty { padding: 12px; border: 1px dashed #dbeafe; border-radius: 14px; color: #64748b; background: #f8fbff; text-align: center; }
.resource-security-panel { display: flex; flex-direction: column; min-height: 0; }
.resource-security-panel .section-header { margin-bottom: 18px; }
.resource-metrics { display: grid; gap: 16px; margin-top: 6px; }
.resource-item { padding-top: 2px; }
.resource-item > div { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 12px; color: #64748b; }
.resource-item span { font-size: 15px; font-weight: 600; }
.resource-item strong { color: #0f172a; font-size: 20px; }
.resource-item :deep(.el-progress__text) { min-width: 38px; margin-left: 10px; color: #64748b; font-size: 14px !important; }
.resource-item :deep(.el-progress-bar__outer) { background-color: #edf1f7; }
.security-box { margin-top: 0; padding: 14px 16px; border-radius: 16px; border: 1px solid #dbeafe; background: linear-gradient(135deg, #eff6ff, #f5f3ff); }
.security-box span, .security-box small { display: block; color: #64748b; }
.security-box strong { display: block; margin: 6px 0 0; font-size: 22px; color: #0f172a; }
.compact-security-box { margin-top: auto; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 14px; padding: 12px 14px; border-radius: 16px; }
.compact-security-box span { font-size: 13px; font-weight: 700; color: #64748b; }
.compact-security-box small { margin-top: 3px; font-size: 12px; white-space: normal; }
.compact-security-box strong { margin: 0; white-space: nowrap; font-size: 18px; }
.dashboard-panel { margin-top: 18px; }
.dashboard-list { display: grid; gap: 10px; margin-top: 6px; }
.dashboard-list--admin { gap: 8px; }
.dashboard-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px 72px 170px;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  border: 1px solid #edf2f7;
  border-radius: 16px;
  background: linear-gradient(180deg, #ffffff, #f8fbff);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}
.dashboard-row:hover { transform: translateY(-2px); border-color: #bfdbfe; box-shadow: 0 14px 24px rgba(37, 99, 235, 0.08); }
.dashboard-main { display: flex; align-items: center; gap: 12px; min-width: 0; }
.dashboard-avatar { width: 38px; height: 38px; border-radius: 12px; background: linear-gradient(135deg, #2563eb, #7c3aed); color: #fff; display: grid; place-items: center; font-weight: 800; flex-shrink: 0; }
.dashboard-main strong { display: block; color: #172033; font-size: 14px; }
.dashboard-main small, .dashboard-owner, .dashboard-time { color: #64748b; font-size: 12px; }
.dashboard-main div { min-width: 0; }
.dashboard-main small { display: block; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dashboard-owner, .dashboard-time { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ds-table { margin-top: 10px; }
:deep(.el-table tbody tr) { cursor: pointer; }
@media (max-width: 1180px) {
  .hero-card, .content-grid, .content-grid--bottom, .ops-grid { grid-template-columns: 1fr; }
  .hero-visual { display: none; }
  .metric-grid, .stat-grid, .health-compact-grid, .chart-grid, .quick-admin-actions { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .chart-card--wide { grid-column: span 1; }
}
@media (max-width: 720px) {
  .hero-card { padding: 24px; border-radius: 22px; }
  .hero-card h1 { font-size: 28px; }
  .metric-grid, .stat-grid, .health-compact-grid, .quick-admin-actions { grid-template-columns: 1fr; }
  .dashboard-row { grid-template-columns: 1fr; }
}

</style>
