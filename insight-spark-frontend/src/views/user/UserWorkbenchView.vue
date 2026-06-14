<template>
  <section class="user-workbench">
    <div class="hero-card">
      <div class="hero-content">
        <el-tag class="hero-tag" effect="dark" round>个人数据使用全量概览</el-tag>
        <h1>首页工作台</h1>
        <p>
          聚合个人使用统计、资源消耗、最近操作、官方公告、快捷入口与安全状态，
          帮你快速进入日常分析工作并及时掌握权限与系统运行提醒。
        </p>
        <div class="hero-actions">
          <el-button type="primary" size="large" @click="goModule('chat')">开始对话查询</el-button>
          <el-button size="large" @click="goModule('dashboard')">进入我的看板</el-button>
          <el-button class="ghost-button" size="large" :loading="loading" @click="load">刷新数据</el-button>
        </div>
      </div>
      <div class="hero-visual">
        <div class="orb orb-a"></div>
        <div class="orb orb-b"></div>
        <div class="glass-panel">
          <span>今日活跃</span>
          <strong>{{ usageStats.queryCount + usageStats.chartCount + usageStats.uploadCount }}</strong>
          <small>查询 / 图表 / 上传综合热度</small>
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
            <span class="eyebrow">趋势统计图</span>
            <h2>推荐放在首页的四个核心图表</h2>
          </div>
          <el-tag type="info" effect="plain">近 7 日 / 当前概览</el-tag>
        </div>
        <div class="chart-grid">
          <article class="chart-card">
            <div class="chart-title">
              <strong>查询与图表生成趋势</strong>
              <small>折线图 · 适合看每日活跃变化</small>
            </div>
            <div ref="queryTrendChartRef" class="chart-host"></div>
          </article>
          <article class="chart-card">
            <div class="chart-title">
              <strong>功能使用分布</strong>
              <small>柱状图 · 适合看模块使用强弱</small>
            </div>
            <div ref="moduleBarChartRef" class="chart-host"></div>
          </article>
          <article class="chart-card">
            <div class="chart-title">
              <strong>资源消耗走势</strong>
              <small>区域图 · 适合看存储与算力压力</small>
            </div>
            <div ref="resourceAreaChartRef" class="chart-host"></div>
          </article>
          <article class="chart-card">
            <div class="chart-title">
              <strong>个人工作内容占比</strong>
              <small>环形图 · 适合看工作重心</small>
            </div>
            <div ref="workPieChartRef" class="chart-host"></div>
          </article>
        </div>
      </section>

      <div class="content-grid">
        <section class="panel panel--wide quick-panel">
          <div class="section-header">
            <div>
              <span class="eyebrow">常用快捷入口</span>
              <h2>一键进入核心功能</h2>
            </div>
            <el-button text type="primary" @click="goModule('dashboard')">自定义快捷入口</el-button>
          </div>
          <div class="quick-grid">
            <button
              v-for="item in quickLinks"
              :key="item.key"
              type="button"
              class="quick-card"
              @click="goModule(item.key)"
            >
              <span class="quick-icon">{{ item.badge }}</span>
              <strong>{{ item.title }}</strong>
              <small>{{ item.desc }}</small>
            </button>
          </div>
        </section>

        <section class="panel resource-panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">资源消耗监控</span>
              <h2>个人额度</h2>
            </div>
          </div>
          <div class="resource-item">
            <div>
              <span>数据存储占用</span>
              <strong>{{ storagePercent }}%</strong>
            </div>
            <el-progress :percentage="storagePercent" :stroke-width="10" striped />
          </div>
          <div class="resource-item">
            <div>
              <span>查询算力消耗</span>
              <strong>{{ computePercent }}%</strong>
            </div>
            <el-progress :percentage="computePercent" :stroke-width="10" color="#7c3aed" striped />
          </div>
          <div class="resource-item">
            <div>
              <span>文件处理进度</span>
              <strong>{{ uploadProcessPercent }}%</strong>
            </div>
            <el-progress :percentage="uploadProcessPercent" :stroke-width="10" color="#06b6d4" striped />
          </div>
        </section>
      </div>

      <div class="content-grid content-grid--bottom">
        <section class="panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">最近操作记录</span>
              <h2>快捷回到上次工作</h2>
            </div>
          </div>
          <div class="activity-list">
            <button v-for="item in recentActions" :key="item.id" type="button" class="activity-item" @click="goModule(item.module)">
              <span class="activity-dot" :class="`activity-dot--${item.tone}`"></span>
              <div>
                <strong>{{ item.title }}</strong>
                <small>{{ item.desc }}</small>
              </div>
              <em>{{ item.time }}</em>
            </button>
          </div>
        </section>

        <section class="panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">系统公告区</span>
              <h2>通知与升级提醒</h2>
            </div>
          </div>
          <el-empty v-if="!announcements.length" description="暂无公告" />
          <div v-else class="announcement-list">
            <article v-for="a in announcements" :key="a.id" class="announcement-card">
              <div>
                <el-tag v-if="a.pinned" type="danger" size="small">置顶</el-tag>
                <strong>{{ a.title }}</strong>
              </div>
              <p>{{ a.content }}</p>
              <small>{{ formatTime(a.publishedAt || a.createdAt) }}</small>
            </article>
          </div>
        </section>

        <section class="panel status-panel">
          <div class="section-header compact">
            <div>
              <span class="eyebrow">权限与安全</span>
              <h2>状态提示</h2>
            </div>
          </div>
          <div class="status-card status-card--success">
            <span>授权数据源</span>
            <strong>{{ permissionStats.datasourceCount }} 个</strong>
            <small>含个人上传与官方授权数据</small>
          </div>
          <div class="status-card status-card--warning">
            <span>到期提醒</span>
            <strong>{{ permissionStats.expiringCount }} 项</strong>
            <small>建议及时续期，避免分析中断</small>
          </div>
          <div class="security-box">
            <span>最近一次安全检测</span>
            <strong>{{ securityStatus }}</strong>
            <small>SQL 安全检测、敏感数据访问提醒与违规操作预警已开启</small>
          </div>
        </section>
      </div>

      <section class="panel dashboard-panel">
        <div class="section-header">
          <div>
            <span class="eyebrow">最近看板</span>
            <h2>继续分析你的业务数据</h2>
          </div>
          <el-button type="primary" plain @click="goModule('dashboard')">查看全部</el-button>
        </div>
        <div v-if="!recentDashboards.length" class="empty-compact">
          <strong>暂无看板</strong>
          <small>点击右上角进入「我的看板」创建第一个业务看板</small>
        </div>
        <div v-else class="dashboard-list">
          <button v-for="row in recentDashboards" :key="row.id" type="button" class="dashboard-row" @click="openDashboard">
            <div class="dashboard-main">
              <span class="dashboard-avatar">{{ dashboardInitial(row.name) }}</span>
              <div>
                <strong>{{ row.name }}</strong>
                <small>{{ row.description || '继续编辑和查看这个业务看板' }}</small>
              </div>
            </div>
            <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small" effect="light">
              {{ row.isPublic ? '公共' : '私密' }}
            </el-tag>
            <span class="dashboard-time">{{ formatTime(row.updatedAt) }}</span>
            <span class="row-hint">进入</span>
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
const queryTrendChartRef = ref(null)
const moduleBarChartRef = ref(null)
const resourceAreaChartRef = ref(null)
const workPieChartRef = ref(null)
const chartInstances = []
const userCharts = reactive({ dailyTrend: [], moduleUsage: [], resourceUsage: [], workDistribution: [] })

const usageStats = reactive({ queryCount: 0, chartCount: 0, uploadCount: 0, analysisCount: 0 })
const permissionStats = reactive({ datasourceCount: 0, expiringCount: 0 })

const quickLinks = [
  { key: 'upload', badge: 'UP', title: '数据上传', desc: 'Excel / CSV 上传与建模' },
  { key: 'chat', badge: 'SQL', title: '对话查询', desc: '自然语言生成查询与图表' },
  { key: 'dashboard', badge: 'BI', title: '我的看板', desc: '管理图表布局与洞察' },
  { key: 'diagnosis', badge: 'AI', title: '智能诊断', desc: '异常归因与诊断报告' },
  { key: 'advancedAnalysis', badge: 'FX', title: '预测分析', desc: '时序预测与模拟推演' },
  { key: 'permission', badge: 'AUTH', title: '权限中心', desc: '申请、查看与续期授权' }
]

const formatTime = (v) => (v ? String(v).replace('T', ' ') : '-')
const clamp = (n) => Math.max(0, Math.min(100, Math.round(n)))

const latestResource = computed(() => resourceRows.value[resourceRows.value.length - 1] || {})
const storagePercent = computed(() => clamp(latestResource.value.storage ?? usageStats.uploadCount * 8))
const computePercent = computed(() => clamp(latestResource.value.compute ?? usageStats.queryCount * 4))
const uploadProcessPercent = computed(() => {
  if (usageStats.uploadCount <= 0) return 0
  if (usageStats.uploadCount === 1) return 35
  if (usageStats.uploadCount === 2) return 70
  return 100
})
const securityStatus = computed(() => (usageStats.queryCount > 20 ? '需要关注' : '正常'))

const metricCards = computed(() => [
  { key: 'query', tone: 'blue', label: '今日查询次数', value: `${usageStats.queryCount} 次`, desc: '支持按日 / 周 / 月同步刷新' },
  { key: 'chart', tone: 'violet', label: '今日图表生成', value: `${usageStats.chartCount} 张`, desc: 'AI 自动渲染与看板沉淀' },
  { key: 'upload', tone: 'cyan', label: '今日文件上传', value: `${usageStats.uploadCount} 个`, desc: '上传解析与文件处理进度' },
  { key: 'history', tone: 'emerald', label: '历史累计分析', value: `${usageStats.analysisCount} 次`, desc: '沉淀个人分析记录' }
])

const recentActions = computed(() => [
  { id: 1, module: 'chat', tone: 'blue', title: '继续对话查询', desc: '查看最近 5 条对话与生成结果', time: '刚刚' },
  { id: 2, module: 'dashboard', tone: 'violet', title: recentDashboards.value[0]?.name || '编辑我的看板', desc: '进入看板继续编排图表', time: '10 分钟前' },
  { id: 3, module: 'upload', tone: 'cyan', title: '上传业务数据文件', desc: '支持 Excel / CSV 业务数据', time: '今天' },
  { id: 4, module: 'diagnosis', tone: 'amber', title: '查看诊断报告', desc: '快速定位异常指标与原因', time: '本周' },
  { id: 5, module: 'permission', tone: 'emerald', title: '检查数据权限', desc: '查看授权范围与到期提醒', time: '本月' }
])

const trendRows = computed(() => userCharts.dailyTrend.length ? userCharts.dailyTrend : [{ label: '暂无', queries: 0, charts: 0, uploads: 0 }])
const resourceRows = computed(() => userCharts.resourceUsage.length ? userCharts.resourceUsage : [{ label: '暂无', storage: 0, compute: 0 }])
const moduleRows = computed(() => userCharts.moduleUsage.length ? userCharts.moduleUsage : [])
const workRows = computed(() => userCharts.workDistribution.length ? userCharts.workDistribution : [])

function getChart(el, index) {
  if (!el) return null
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  chartInstances[index] = chart
  return chart
}

function renderCharts() {
  const commonGrid = { left: 34, right: 18, top: 32, bottom: 30 }
  getChart(queryTrendChartRef.value, 0)?.setOption({
    color: ['#2563eb', '#7c3aed'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 6, itemWidth: 10, itemHeight: 10 },
    grid: commonGrid,
    xAxis: { type: 'category', boundaryGap: false, data: trendRows.value.map((item) => item.label), axisLine: { lineStyle: { color: '#d8e0ef' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } } },
    series: [
      { name: '查询次数', type: 'line', smooth: true, symbolSize: 7, data: trendRows.value.map((item) => Number(item.queries) || 0) },
      { name: '图表生成', type: 'line', smooth: true, symbolSize: 7, data: trendRows.value.map((item) => Number(item.charts) || 0) }
    ]
  }, true)

  getChart(moduleBarChartRef.value, 1)?.setOption({
    color: ['#06b6d4'],
    tooltip: { trigger: 'axis' },
    grid: { left: 34, right: 18, top: 22, bottom: 36 },
    xAxis: { type: 'category', data: moduleRows.value.map((item) => item.name), axisLine: { lineStyle: { color: '#d8e0ef' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } } },
    series: [{ type: 'bar', barWidth: 20, borderRadius: [10, 10, 0, 0], data: moduleRows.value.map((item) => Number(item.value) || 0) }]
  }, true)

  getChart(resourceAreaChartRef.value, 2)?.setOption({
    color: ['#10b981', '#f59e0b'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 6, itemWidth: 10, itemHeight: 10 },
    grid: commonGrid,
    xAxis: { type: 'category', boundaryGap: false, data: resourceRows.value.map((item) => item.label), axisLine: { lineStyle: { color: '#d8e0ef' } } },
    yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: '#edf2f7' } } },
    series: [
      { name: '存储占用', type: 'line', smooth: true, areaStyle: { opacity: 0.18 }, data: resourceRows.value.map((item) => Number(item.storage) || 0) },
      { name: '算力消耗', type: 'line', smooth: true, areaStyle: { opacity: 0.18 }, data: resourceRows.value.map((item) => Number(item.compute) || 0) }
    ]
  }, true)

  getChart(workPieChartRef.value, 3)?.setOption({
    color: ['#2563eb', '#7c3aed', '#06b6d4', '#10b981'],
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, left: 'center', itemWidth: 10, itemHeight: 10 },
    series: [{
      name: '工作内容',
      type: 'pie',
      radius: ['48%', '72%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      label: { formatter: '{b}\n{d}%' },
      data: workRows.value.length ? workRows.value.map((item) => ({ name: item.name, value: Number(item.value) || 0 })) : [{ name: '暂无数据', value: 0 }]
    }]
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

function openDashboard() {
  goModule('dashboard')
}

function dashboardInitial(name) {
  return String(name || '看').trim().slice(0, 1).toUpperCase()
}

const load = async () => {
  loading.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/workbench/summary`)
    if (res.data.code !== 200) {
      throw new Error(res.data.message || '加载失败')
    }
    const d = res.data.data || {}
    const userStats = d.userStats || {}
    announcements.value = d.announcements || []
    recentDashboards.value = d.recentDashboards || []
    userCharts.dailyTrend = Array.isArray(d.userCharts?.dailyTrend) ? d.userCharts.dailyTrend : []
    userCharts.moduleUsage = Array.isArray(d.userCharts?.moduleUsage) ? d.userCharts.moduleUsage : []
    userCharts.resourceUsage = Array.isArray(d.userCharts?.resourceUsage) ? d.userCharts.resourceUsage : []
    userCharts.workDistribution = Array.isArray(d.userCharts?.workDistribution) ? d.userCharts.workDistribution : []
    usageStats.queryCount = Number(userStats.todayQueryCount ?? userStats.personalQueryCount) || 0
    usageStats.chartCount = Number(userStats.todayChartCount ?? userStats.personalChartCount) || 0
    usageStats.uploadCount = Number(userStats.todayUploadCount ?? userStats.uploadCount) || 0
    usageStats.analysisCount = Number(userStats.personalQueryCount) || 0
    permissionStats.datasourceCount = Number(userStats.uploadCount) || 0
    permissionStats.expiringCount = Number(userStats.expiringPermissionCount) || 0
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
.user-workbench {
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
.hero-tag { border: 0; background: linear-gradient(135deg, #2563eb, #7c3aed); }
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
.glass-panel strong { display: block; margin: 8px 0; font-size: 42px; color: #1d4ed8; }
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
.metric-card--violet::after { background: #7c3aed; }
.metric-card--cyan::after { background: #06b6d4; }
.metric-card--emerald::after { background: #10b981; }
.metric-topline { display: flex; align-items: center; justify-content: space-between; color: #64748b; font-size: 13px; }
.metric-topline i { width: 10px; height: 10px; border-radius: 999px; background: #2563eb; box-shadow: 0 0 0 6px rgba(37, 99, 235, 0.08); }
.metric-card strong { display: block; margin: 12px 0 8px; font-size: 30px; color: #0f172a; }
.metric-card small { color: #94a3b8; }
.chart-panel { margin-bottom: 18px; }
.chart-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.chart-card { padding: 18px; border-radius: 20px; border: 1px solid #edf2f7; background: linear-gradient(180deg, #ffffff, #f8fbff); }
.chart-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.chart-title strong { color: #172033; font-size: 15px; }
.chart-title small { color: #94a3b8; font-size: 12px; text-align: right; }
.chart-host { width: 100%; height: 260px; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(320px, 0.7fr); gap: 18px; margin-bottom: 18px; }
.content-grid--bottom { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 360px; align-items: stretch; }
.panel { padding: 22px; }
.section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.section-header.compact { margin-bottom: 16px; }
.eyebrow { color: #2563eb; font-size: 12px; font-weight: 700; letter-spacing: 0.08em; }
.section-header h2 { margin: 5px 0 0; font-size: 20px; color: #111827; }
.quick-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.quick-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  min-height: 128px;
  padding: 18px;
  border: 1px solid #e6ecf7;
  border-radius: 20px;
  background: linear-gradient(180deg, #ffffff, #f8fbff);
  cursor: pointer;
  text-align: left;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}
.quick-card:hover { transform: translateY(-4px); border-color: #93c5fd; box-shadow: 0 18px 34px rgba(37, 99, 235, 0.12); }
.quick-icon { display: inline-flex; align-items: center; justify-content: center; min-width: 42px; height: 32px; padding: 0 10px; border-radius: 999px; background: #eff6ff; color: #2563eb; font-size: 12px; font-weight: 800; }
.quick-card strong { color: #172033; font-size: 16px; }
.quick-card small { color: #64748b; line-height: 1.5; }
.resource-panel { display: flex; flex-direction: column; }
.resource-item { padding-top: 8px; }
.resource-item + .resource-item { margin-top: 26px; }
.resource-item > div { display: flex; justify-content: space-between; margin-bottom: 16px; color: #64748b; }
.resource-item span { font-size: 15px; font-weight: 600; }
.resource-item strong { color: #0f172a; font-size: 20px; }
.resource-item :deep(.el-progress__text) { min-width: 38px; margin-left: 10px; color: #64748b; font-size: 14px !important; }
.resource-item :deep(.el-progress-bar__outer) { background-color: #edf1f7; }
.activity-list { display: grid; gap: 10px; }
.activity-item { display: grid; grid-template-columns: 12px minmax(0, 1fr) auto; gap: 12px; align-items: center; width: 100%; padding: 14px; border: 1px solid #edf1f8; border-radius: 16px; background: #fbfdff; text-align: left; cursor: pointer; }
.activity-item:hover { border-color: #bfdbfe; background: #f8fbff; }
.activity-dot { width: 10px; height: 10px; border-radius: 999px; background: #2563eb; }
.activity-dot--violet { background: #7c3aed; }
.activity-dot--cyan { background: #06b6d4; }
.activity-dot--amber { background: #f59e0b; }
.activity-dot--emerald { background: #10b981; }
.activity-item strong, .activity-item small { display: block; }
.activity-item small, .activity-item em { color: #94a3b8; font-size: 12px; font-style: normal; }
.announcement-list { display: grid; gap: 12px; }
.announcement-card { padding: 15px; border-radius: 16px; background: #f8fafc; border: 1px solid #edf2f7; }
.announcement-card div { display: flex; align-items: center; gap: 8px; }
.announcement-card p { margin: 8px 0; color: #64748b; line-height: 1.65; }
.announcement-card small { color: #94a3b8; }
.status-panel { display: grid; gap: 12px; }
.status-card, .security-box { padding: 16px; border-radius: 18px; border: 1px solid #e5e7eb; background: #fff; }
.status-card span, .security-box span, .security-box small { display: block; color: #64748b; }
.status-card strong, .security-box strong { display: block; margin: 6px 0; font-size: 24px; color: #0f172a; }
.status-card--success { background: #f0fdf4; border-color: #bbf7d0; }
.status-card--warning { background: #fffbeb; border-color: #fde68a; }
.security-box { background: linear-gradient(135deg, #eff6ff, #f5f3ff); border-color: #dbeafe; }
.dashboard-panel { margin-top: 18px; }
.dashboard-list { display: grid; gap: 8px; margin-top: 6px; }
.dashboard-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 72px 170px 62px;
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
.dashboard-main small, .dashboard-time { color: #64748b; font-size: 12px; }
.dashboard-main div { min-width: 0; }
.dashboard-main small { display: block; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dashboard-time { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.empty-compact { display: flex; flex-direction: column; justify-content: center; min-height: 120px; padding: 18px; border-radius: 18px; border: 1px dashed #dbeafe; background: linear-gradient(135deg, #f8fbff, #ffffff); text-align: center; }
.empty-compact strong { color: #334155; }
.empty-compact small { margin-top: 6px; color: #94a3b8; }
.row-hint { color: #2563eb; font-weight: 700; text-align: right; }
@media (max-width: 1180px) {
  .hero-card, .content-grid, .content-grid--bottom { grid-template-columns: 1fr; }
  .hero-visual { display: none; }
  .metric-grid, .chart-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .hero-card { padding: 24px; border-radius: 22px; }
  .hero-card h1 { font-size: 28px; }
  .metric-grid, .quick-grid, .chart-grid { grid-template-columns: 1fr; }
  .dashboard-row { grid-template-columns: 1fr; }
  .chart-title { display: block; }
  .chart-title small { display: block; margin-top: 4px; text-align: left; }
}
</style>
