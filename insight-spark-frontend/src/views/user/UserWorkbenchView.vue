<template>
  <section class="user-workbench">
    <header class="wb-hero">
      <div class="wb-hero-main">
        <h1 class="wb-hero-title">首页工作台</h1>
        <span class="wb-hero-meta">今日活跃 {{ todayActive }}</span>
        <WorkbenchHelpTip
          content="汇总个人查询、图表、看板与权限提醒；点击指标或列表项可快速进入对应功能。"
          aria-label="工作台说明"
        />
      </div>
      <div class="wb-hero-actions">
        <el-button type="primary" @click="goModule('chat')">开始对话查询</el-button>
        <el-button @click="goModule('dashboard')">我的看板</el-button>
        <el-button text :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-skeleton v-if="loading" class="wb-skeleton" :rows="6" animated />
    <template v-else>
      <div class="wb-insight-strip">
        <div class="wb-insight-alerts">
          <button
            v-for="item in stripAlerts"
            :key="item.key"
            type="button"
            class="wb-insight-pill"
            :class="`wb-insight-pill--${item.tone}`"
            @click="item.action?.()"
          >
            <span class="wb-insight-pill-label">{{ item.label }}</span>
            <span class="wb-insight-pill-text">{{ item.text }}</span>
          </button>
          <span v-if="!stripAlerts.length" class="wb-insight-placeholder">暂无待办提醒</span>
        </div>

        <div class="wb-insight-digest">
          <span class="wb-insight-digest-label">{{ stripDigest.label }}</span>
          <button
            v-if="stripDigest.action"
            type="button"
            class="wb-insight-digest-link"
            @click="stripDigest.action()"
          >
            {{ stripDigest.text }}
          </button>
          <span v-else class="wb-insight-digest-text">{{ stripDigest.text }}</span>
        </div>

        <div class="wb-insight-quick">
          <button
            v-for="item in stripQuickActions"
            :key="item.key"
            type="button"
            class="wb-insight-quick-btn"
            @click="item.action()"
          >
            {{ item.label }}
          </button>
        </div>
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

      <div class="wb-bento">
        <section class="panel panel--dashboards">
          <div class="section-header section-header--tight">
            <div>
              <span class="eyebrow">继续工作</span>
              <h2>最近看板</h2>
            </div>
            <el-button type="primary" link @click="goModule('dashboard')">全部</el-button>
          </div>
          <div v-if="!recentDashboards.length" class="empty-inline">
            <span>暂无看板</span>
            <el-button size="small" type="primary" plain @click="goModule('dashboard')">去创建</el-button>
          </div>
          <div v-else class="dashboard-list">
            <button
              v-for="row in recentDashboards.slice(0, 6)"
              :key="row.id"
              type="button"
              class="dashboard-row"
              @click="openDashboard(row)"
            >
              <span class="dashboard-avatar">{{ dashboardInitial(row.name) }}</span>
              <div class="dashboard-main">
                <strong>{{ row.name }}</strong>
                <small>{{ formatTime(row.updatedAt) }}</small>
              </div>
              <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small" effect="plain">
                {{ row.isPublic ? '公共' : '私密' }}
              </el-tag>
            </button>
          </div>

          <div class="panel-divider"></div>

          <div class="section-header section-header--tight">
            <div>
              <span class="eyebrow">最近操作</span>
              <h2>回到上次任务</h2>
            </div>
          </div>
          <div v-if="!recentActions.length" class="empty-inline empty-inline--sm">
            <span>暂无记录，去对话查询生成第一条</span>
            <el-button size="small" plain @click="goModule('chat')">开始查询</el-button>
          </div>
          <div v-else class="activity-list">
            <button
              v-for="item in recentActions.slice(0, 5)"
              :key="item.id"
              type="button"
              class="activity-item"
              @click="item.action?.()"
            >
              <span class="activity-dot" :class="`activity-dot--${item.tone}`"></span>
              <div class="activity-text">
                <strong>{{ item.title }}</strong>
              </div>
              <em>{{ item.time }}</em>
            </button>
          </div>
        </section>

        <section class="panel panel--charts">
          <div class="section-header section-header--tight">
            <div>
              <span class="eyebrow">近 7 日</span>
              <h2>使用趋势</h2>
            </div>
          </div>
          <div class="chart-grid-compact">
            <article class="chart-card">
              <div class="chart-title"><strong>查询 / 图表</strong></div>
              <div ref="queryTrendChartRef" class="chart-host"></div>
            </article>
            <article class="chart-card">
              <div class="chart-title"><strong>功能分布</strong></div>
              <div ref="moduleBarChartRef" class="chart-host"></div>
            </article>
            <article class="chart-card">
              <div class="chart-title"><strong>活跃走势</strong></div>
              <div ref="resourceAreaChartRef" class="chart-host"></div>
            </article>
            <article class="chart-card">
              <div class="chart-title"><strong>工作占比</strong></div>
              <div ref="workPieChartRef" class="chart-host"></div>
            </article>
          </div>
        </section>

        <section class="panel panel--side">
          <div class="section-header section-header--tight">
            <div>
              <span class="eyebrow">系统公告</span>
              <h2>通知</h2>
            </div>
          </div>
          <div v-if="!announcements.length" class="empty-inline empty-inline--sm">
            <span>暂无公告</span>
          </div>
          <div v-else class="announcement-list announcement-list--top">
            <article v-for="a in announcements.slice(0, 4)" :key="a.id" class="announcement-card">
              <div class="announcement-head">
                <el-tag v-if="a.pinned" type="danger" size="small">置顶</el-tag>
                <strong>{{ a.title }}</strong>
                <small>{{ formatTime(a.publishedAt || a.createdAt) }}</small>
              </div>
              <p>{{ a.content }}</p>
            </article>
          </div>

          <div class="panel-divider"></div>

          <div class="section-header section-header--tight">
            <div>
              <span class="eyebrow">快捷入口</span>
              <h2>常用功能</h2>
            </div>
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
            </button>
          </div>

          <div class="resource-row">
            <div class="resource-chip">
              <span>数据表</span>
              <strong>{{ permissionStats.uploadTableCount }}</strong>
            </div>
            <div class="resource-chip">
              <span>看板授权</span>
              <strong>{{ permissionStats.permissionCount }}</strong>
            </div>
            <div class="resource-chip" :class="{ 'is-warn': permissionStats.expiringCount > 0 }">
              <span>将到期</span>
              <strong>{{ permissionStats.expiringCount }}</strong>
            </div>
            <div class="resource-chip">
              <span>我的看板</span>
              <strong>{{ usageStats.dashboardCount }}</strong>
            </div>
          </div>
        </section>
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
const recentActivities = ref([])
const queryTrendChartRef = ref(null)
const moduleBarChartRef = ref(null)
const resourceAreaChartRef = ref(null)
const workPieChartRef = ref(null)
const chartInstances = []
const userCharts = reactive({ dailyTrend: [], moduleUsage: [], resourceUsage: [], workDistribution: [] })

const usageStats = reactive({
  queryCount: 0,
  chartCount: 0,
  uploadCount: 0,
  analysisCount: 0,
  dashboardCount: 0
})
const permissionStats = reactive({
  uploadTableCount: 0,
  permissionCount: 0,
  expiringCount: 0
})

const quickLinks = [
  { key: 'upload', badge: 'UP', title: '数据上传' },
  { key: 'chat', badge: 'AI', title: '对话查询' },
  { key: 'dashboard', badge: 'BI', title: '我的看板' },
  { key: 'diagnosis', badge: 'DX', title: '智能诊断' },
  { key: 'advancedAnalysis', badge: 'FX', title: '预测分析' },
  { key: 'permission', badge: 'AUTH', title: '权限中心' }
]

const todayActive = computed(
  () => usageStats.queryCount + usageStats.chartCount + usageStats.uploadCount
)

const stripAlerts = computed(() => {
  const items = []
  if (permissionStats.expiringCount > 0) {
    items.push({
      key: 'expire',
      tone: 'warning',
      label: '权限到期',
      text: `${permissionStats.expiringCount} 项授权 7 天内到期`,
      action: () => goModule('permission')
    })
  }
  announcements.value.slice(0, 2).forEach((row) => {
    items.push({
      key: `announce-${row.id}`,
      tone: row.pinned ? 'danger' : 'info',
      label: row.pinned ? '置顶' : '公告',
      text: row.title,
      action: () => {}
    })
  })
  return items
})

const stripDigest = computed(() => {
  const latestAction = recentActions.value[0]
  if (latestAction) {
    return {
      label: '上次操作',
      text: `${latestAction.title} · ${latestAction.time}`,
      action: latestAction.action
    }
  }
  const latestDashboard = recentDashboards.value[0]
  if (latestDashboard) {
    return {
      label: '最近看板',
      text: `${latestDashboard.name} · ${formatTime(latestDashboard.updatedAt)}`,
      action: () => openDashboard(latestDashboard)
    }
  }
  return {
    label: '快速开始',
    text: '上传数据或开始对话查询，生成你的第一张图表',
    action: () => goModule('chat')
  }
})

const stripQuickActions = computed(() => {
  const items = [
    { key: 'chat', label: '继续查询', action: () => goModule('chat') },
    { key: 'dashboard', label: '我的看板', action: () => goModule('dashboard') },
    { key: 'upload', label: '上传数据', action: () => goModule('upload') }
  ]
  if (permissionStats.expiringCount > 0) {
    items.push({ key: 'permission', label: '续期权限', action: () => goModule('permission') })
  }
  return items.slice(0, 4)
})

const metricCards = computed(() => [
  {
    key: 'query',
    tone: 'blue',
    label: '今日查询',
    value: `${usageStats.queryCount} 次`,
    desc: '点击进入对话查询',
    action: () => goModule('chat')
  },
  {
    key: 'chart',
    tone: 'violet',
    label: '今日图表',
    value: `${usageStats.chartCount} 张`,
    desc: 'AI 生成并沉淀到看板',
    action: () => goModule('chat')
  },
  {
    key: 'upload',
    tone: 'cyan',
    label: '今日上传',
    value: `${usageStats.uploadCount} 个`,
    desc: '上传 Excel / CSV',
    action: () => goModule('upload')
  },
  {
    key: 'history',
    tone: 'emerald',
    label: '累计分析',
    value: `${usageStats.analysisCount} 次`,
    desc: `我的看板 ${usageStats.dashboardCount} 个`,
    action: () => goModule('dashboard')
  }
])

function relativeTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return formatTime(value)
  const diffMs = Date.now() - date.getTime()
  const mins = Math.floor(diffMs / 60000)
  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins} 分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  return formatTime(value)
}

const recentActions = computed(() => {
  const fromApi = recentActivities.value.slice(0, 6).map((row, idx) => {
    const type = String(row.activityType || row.type || 'QUERY').toUpperCase()
    const title = String(row.title || row.queryText || '最近查询').trim().slice(0, 80) || '最近查询'
    return {
      id: row.id || `act-${idx}`,
      tone: type === 'QUERY' ? 'blue' : 'violet',
      title,
      desc: type === 'QUERY' ? '继续对话查询' : '查看相关记录',
      time: relativeTime(row.occurredAt || row.createdAt),
      action: () => goModule('chat')
    }
  })
  if (fromApi.length) return fromApi
  if (recentDashboards.value[0]) {
    const row = recentDashboards.value[0]
    return [
      {
        id: 'dash-fallback',
        tone: 'violet',
        title: row.name,
        desc: '打开最近编辑的看板',
        time: relativeTime(row.updatedAt),
        action: () => openDashboard(row)
      }
    ]
  }
  return []
})

const trendRows = computed(() =>
  userCharts.dailyTrend.length ? userCharts.dailyTrend : [{ label: '暂无', queries: 0, charts: 0, uploads: 0 }]
)
const resourceRows = computed(() =>
  userCharts.resourceUsage.length ? userCharts.resourceUsage : [{ label: '暂无', storage: 0, compute: 0 }]
)
const moduleRows = computed(() => (userCharts.moduleUsage.length ? userCharts.moduleUsage : []))
const workRows = computed(() => (userCharts.workDistribution.length ? userCharts.workDistribution : []))

const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '-')

function getChart(el, index) {
  if (!el) return null
  const chart = echarts.getInstanceByDom(el) || echarts.init(el)
  chartInstances[index] = chart
  return chart
}

function renderCharts() {
  const commonGrid = { left: 34, right: 18, top: 32, bottom: 30 }
  getChart(queryTrendChartRef.value, 0)?.setOption(
    {
      color: ['#2563eb', '#7c3aed'],
      tooltip: { trigger: 'axis' },
      legend: { top: 0, right: 6, itemWidth: 10, itemHeight: 10 },
      grid: commonGrid,
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: trendRows.value.map((item) => item.label),
        axisLine: { lineStyle: { color: '#d8e0ef' } }
      },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } } },
      series: [
        {
          name: '查询次数',
          type: 'line',
          smooth: true,
          symbolSize: 7,
          data: trendRows.value.map((item) => Number(item.queries) || 0)
        },
        {
          name: '图表生成',
          type: 'line',
          smooth: true,
          symbolSize: 7,
          data: trendRows.value.map((item) => Number(item.charts) || 0)
        }
      ]
    },
    true
  )

  getChart(moduleBarChartRef.value, 1)?.setOption(
    {
      color: ['#06b6d4'],
      tooltip: { trigger: 'axis' },
      grid: { left: 34, right: 18, top: 22, bottom: 36 },
      xAxis: {
        type: 'category',
        data: moduleRows.value.map((item) => item.name),
        axisLine: { lineStyle: { color: '#d8e0ef' } }
      },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } } },
      series: [
        {
          type: 'bar',
          barWidth: 20,
          borderRadius: [10, 10, 0, 0],
          data: moduleRows.value.map((item) => Number(item.value) || 0)
        }
      ]
    },
    true
  )

  getChart(resourceAreaChartRef.value, 2)?.setOption(
    {
      color: ['#10b981', '#f59e0b'],
      tooltip: { trigger: 'axis' },
      legend: { top: 0, right: 6, itemWidth: 10, itemHeight: 10 },
      grid: commonGrid,
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: resourceRows.value.map((item) => item.label),
        axisLine: { lineStyle: { color: '#d8e0ef' } }
      },
      yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: '#edf2f7' } } },
      series: [
        {
          name: '活跃指数',
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.18 },
          data: resourceRows.value.map((item) => Number(item.storage) || 0)
        },
        {
          name: '查询指数',
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.18 },
          data: resourceRows.value.map((item) => Number(item.compute) || 0)
        }
      ]
    },
    true
  )

  getChart(workPieChartRef.value, 3)?.setOption(
    {
      color: ['#2563eb', '#7c3aed', '#06b6d4', '#10b981'],
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, left: 'center', itemWidth: 10, itemHeight: 10 },
      series: [
        {
          name: '工作内容',
          type: 'pie',
          radius: ['48%', '72%'],
          center: ['50%', '44%'],
          avoidLabelOverlap: true,
          label: { formatter: '{b}\n{d}%' },
          data: workRows.value.length
            ? workRows.value.map((item) => ({ name: item.name, value: Number(item.value) || 0 }))
            : [{ name: '暂无数据', value: 0 }]
        }
      ]
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

function openDashboard(row) {
  if (row?.id && workbench?.openDashboardPreview) {
    workbench.openDashboardPreview(row)
    return
  }
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
    recentActivities.value = Array.isArray(d.recentActivities) ? d.recentActivities : []
    userCharts.dailyTrend = Array.isArray(d.userCharts?.dailyTrend) ? d.userCharts.dailyTrend : []
    userCharts.moduleUsage = Array.isArray(d.userCharts?.moduleUsage) ? d.userCharts.moduleUsage : []
    userCharts.resourceUsage = Array.isArray(d.userCharts?.resourceUsage) ? d.userCharts.resourceUsage : []
    userCharts.workDistribution = Array.isArray(d.userCharts?.workDistribution) ? d.userCharts.workDistribution : []
    usageStats.queryCount = Number(userStats.todayQueryCount ?? userStats.personalQueryCount) || 0
    usageStats.chartCount = Number(userStats.todayChartCount ?? userStats.personalChartCount) || 0
    usageStats.uploadCount = Number(userStats.todayUploadCount ?? userStats.uploadCount) || 0
    usageStats.analysisCount = Number(userStats.personalQueryCount) || 0
    usageStats.dashboardCount = Number(userStats.dashboardCount) || 0
    permissionStats.uploadTableCount = Number(userStats.uploadCount) || 0
    permissionStats.permissionCount = Number(userStats.permissionCount) || 0
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
  padding: 0 2px 16px;
  color: #172033;
}

.wb-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: linear-gradient(135deg, #f8fafc 0%, #eff6ff 100%);
}

.wb-hero-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.wb-hero-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.wb-hero-meta {
  font-size: 12px;
  color: #64748b;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid #e2e8f0;
}

.wb-hero-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.wb-skeleton,
.panel,
.metric-card {
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e8edf7;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.04);
}

.wb-skeleton {
  margin-top: 10px;
  padding: 16px;
}

.wb-insight-strip {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #fff;
  min-height: 44px;
}

.wb-insight-alerts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  max-width: 38%;
}

.wb-insight-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid transparent;
  cursor: pointer;
  background: #f8fafc;
}

.wb-insight-pill--warning {
  border-color: #fde68a;
  background: #fffbeb;
}

.wb-insight-pill--info {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.wb-insight-pill--danger {
  border-color: #fecaca;
  background: #fef2f2;
}

.wb-insight-pill-label {
  font-size: 10px;
  font-weight: 700;
  color: #64748b;
  flex-shrink: 0;
}

.wb-insight-pill-text {
  font-size: 12px;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wb-insight-placeholder {
  font-size: 12px;
  color: #94a3b8;
  white-space: nowrap;
}

.wb-insight-digest {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  padding: 0 8px;
  border-left: 1px solid #eef2f7;
  border-right: 1px solid #eef2f7;
}

.wb-insight-digest-label {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  color: #64748b;
}

.wb-insight-digest-link,
.wb-insight-digest-text {
  min-width: 0;
  font-size: 12px;
  color: #475569;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wb-insight-digest-link {
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  text-align: left;
}

.wb-insight-digest-link:hover {
  color: #2563eb;
}

.wb-insight-quick {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.wb-insight-quick-btn {
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  font-size: 12px;
  color: #334155;
  cursor: pointer;
  white-space: nowrap;
}

.wb-insight-quick-btn:hover {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #2563eb;
}

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
  border-color: #bfdbfe;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.08);
}

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

.wb-bento {
  display: grid;
  grid-template-columns: minmax(260px, 0.95fr) minmax(320px, 1.15fr) minmax(240px, 0.9fr);
  gap: 10px;
  align-items: stretch;
}

.panel {
  padding: 12px 14px;
  min-height: 0;
}

.panel--dashboards,
.panel--charts,
.panel--side {
  display: flex;
  flex-direction: column;
  min-height: 520px;
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

.panel-divider {
  height: 1px;
  margin: 10px 0;
  background: #eef2f7;
}

.dashboard-list,
.activity-list,
.announcement-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.announcement-list--top {
  flex: 0 0 auto;
  overflow: visible;
}

.dashboard-row {
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

.dashboard-row:hover {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.dashboard-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
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

.dashboard-main small {
  color: #94a3b8;
  font-size: 11px;
}

.activity-item {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid #edf1f8;
  border-radius: 10px;
  background: #fbfdff;
  text-align: left;
  cursor: pointer;
}

.activity-item:hover {
  border-color: #bfdbfe;
}

.activity-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #2563eb;
}

.activity-dot--violet { background: #7c3aed; }

.activity-text strong {
  display: block;
  font-size: 12px;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.activity-item em {
  color: #94a3b8;
  font-size: 11px;
  font-style: normal;
  white-space: nowrap;
}

.chart-grid-compact {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 8px;
  flex: 1;
  min-height: 0;
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
  margin-bottom: 4px;
}

.chart-title strong {
  font-size: 11px;
  color: #64748b;
}

.chart-host {
  flex: 1;
  width: 100%;
  min-height: 140px;
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
  background: #eff6ff;
  color: #2563eb;
  font-size: 10px;
  font-weight: 800;
  flex-shrink: 0;
}

.quick-card strong {
  color: #172033;
  font-size: 12px;
}

.resource-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin: 10px 0;
}

.resource-chip {
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid #e5e7eb;
  background: #f8fafc;
}

.resource-chip span {
  display: block;
  font-size: 11px;
  color: #64748b;
}

.resource-chip strong {
  display: block;
  margin-top: 2px;
  font-size: 18px;
  color: #0f172a;
}

.resource-chip.is-warn {
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
  flex: 1;
  min-width: 0;
}

.announcement-head small {
  color: #94a3b8;
  font-size: 10px;
  white-space: nowrap;
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
  .wb-bento {
    grid-template-columns: 1fr 1fr;
  }

  .panel--side {
    grid-column: 1 / -1;
    min-height: auto;
  }

  .panel--dashboards,
  .panel--charts {
    min-height: 460px;
  }
}

@media (max-width: 760px) {
  .wb-insight-strip {
    flex-wrap: wrap;
    align-items: stretch;
  }

  .wb-insight-alerts {
    max-width: 100%;
    flex-wrap: wrap;
  }

  .wb-insight-digest {
    flex: 1 1 100%;
    border-left: none;
    border-right: none;
    padding: 6px 0;
    border-top: 1px solid #eef2f7;
    border-bottom: 1px solid #eef2f7;
  }

  .wb-insight-quick {
    flex: 1 1 100%;
    flex-wrap: wrap;
  }

  .wb-bento {
    grid-template-columns: 1fr;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .chart-grid-compact {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
  }

  .panel--dashboards,
  .panel--charts,
  .panel--side {
    min-height: auto;
  }

  .chart-host {
    min-height: 180px;
  }
}
</style>
