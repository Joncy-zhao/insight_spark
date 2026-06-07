<template>
  <section class="stack-c-admin-workbench">
    <div class="panel">
      <div class="panel-header">
        <h2>管理员工作台</h2>
        <p>平台统计、数据源健康、引擎状态与系统公告。</p>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-skeleton v-if="loading" :rows="6" animated />
      <template v-else>
        <div class="stat-grid">
          <article class="stat-card stat-card--blue">
            <span class="stat-label">看板总数</span>
            <strong class="stat-value">{{ dashStats.totalCount }}</strong>
            <small>公共 {{ dashStats.publicCount }} · 私密 {{ dashStats.privateCount }}</small>
          </article>
          <article class="stat-card stat-card--green">
            <span class="stat-label">总访问量</span>
            <strong class="stat-value">{{ dashStats.totalViews }}</strong>
            <small>全平台看板访问累计</small>
          </article>
          <article class="stat-card stat-card--amber">
            <span class="stat-label">数据源</span>
            <strong class="stat-value">{{ dsSummary.total }}</strong>
            <small>启用 {{ dsSummary.enabled }} · 探测正常 {{ dsSummary.healthy }}</small>
          </article>
          <article class="stat-card stat-card--indigo">
            <span class="stat-label">JVM 堆内存</span>
            <strong class="stat-value">{{ heapPercentText }}</strong>
            <small>{{ heapBytesText }}</small>
          </article>
          <article class="stat-card" :class="cpuAlert ? 'stat-card--danger' : 'stat-card--slate'">
            <span class="stat-label">系统负载</span>
            <strong class="stat-value">{{ loadText }}</strong>
            <small>{{ cpuAlert ? '超过告警阈值' : `阈值 ${cpuThreshold}%` }}</small>
          </article>
          <article class="stat-card stat-card--rose">
            <span class="stat-label">SQL 审计</span>
            <strong class="stat-value">{{ sqlStats.slowCount }}</strong>
            <small>慢查询 · 拦截 {{ sqlStats.blockedCount }}</small>
          </article>
        </div>

        <h3 class="sub-title">系统公告</h3>
        <el-empty v-if="!announcements.length" description="暂无公告" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="a in announcements"
            :key="a.id"
            :timestamp="formatTime(a.publishedAt || a.createdAt)"
            placement="top"
          >
            <el-card shadow="hover">
              <div class="ann-title">
                <el-tag v-if="a.pinned" type="danger" size="small">置顶</el-tag>
                <span>{{ a.title }}</span>
              </div>
              <p class="ann-content">{{ a.content }}</p>
            </el-card>
          </el-timeline-item>
        </el-timeline>

        <h3 class="sub-title">最近看板（全平台）</h3>
        <el-empty v-if="!recentDashboards.length" description="暂无看板" />
        <el-table v-else :data="recentDashboards" size="small" border @row-click="goDashboardManage">
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="ownerUserId" label="所有者" width="120" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small">
                {{ row.isPublic ? '公共' : '私密' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="170" />
        </el-table>
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'
const workbench = inject('workbench', null)

const loading = ref(true)
const announcements = ref([])
const recentDashboards = ref([])
const dashStats = reactive({ totalCount: 0, publicCount: 0, privateCount: 0, totalViews: 0 })
const dsSummary = reactive({ total: 0, enabled: 0, healthy: 0 })
const perfOverview = ref(null)

const formatTime = (v) => (v ? String(v).replace('T', ' ') : '-')

const formatBytes = (n) => {
  if (n == null) return '—'
  const u = ['B', 'KB', 'MB', 'GB']
  let v = Number(n)
  let i = 0
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i > 0 ? 1 : 0)} ${u[i]}`
}

const heapPercentText = computed(() => {
  const pct = perfOverview.value?.jvm?.heapUsedPercent
  return pct != null ? `${Math.round(pct)}%` : '—'
})

const heapBytesText = computed(() => {
  const jvm = perfOverview.value?.jvm
  if (!jvm?.heapUsedBytes) return '无堆信息'
  const used = formatBytes(jvm.heapUsedBytes)
  const max = jvm.heapMaxBytes ? formatBytes(jvm.heapMaxBytes) : null
  return max ? `${used} / ${max}` : used
})

const cpuThreshold = computed(() => Number(perfOverview.value?.alertConfig?.cpuPercent) || 90)

const cpuUsagePercent = computed(() => {
  const jvm = perfOverview.value?.jvm
  const load = jvm?.systemLoadAverage
  const cpus = jvm?.processors
  if (load == null || !cpus) return null
  return Math.round((load / cpus) * 100)
})

const cpuAlert = computed(() => {
  const usage = cpuUsagePercent.value
  return usage != null && usage >= cpuThreshold.value
})

const loadText = computed(() => {
  const load = perfOverview.value?.jvm?.systemLoadAverage
  const usage = cpuUsagePercent.value
  if (load == null) return '—'
  return usage != null ? `${load.toFixed(2)} (${usage}%)` : load.toFixed(2)
})

const sqlStats = computed(() => ({
  slowCount: Number(perfOverview.value?.sqlAudit?.slowCount) || 0,
  blockedCount: Number(perfOverview.value?.sqlAudit?.blockedCount) || 0
}))

function goDashboardManage() {
  if (workbench?.activeModule) {
    workbench.activeModule.value = 'adminDashboard'
  }
}

function summarizeDatasources(list) {
  const items = Array.isArray(list) ? list : []
  dsSummary.total = items.length
  dsSummary.enabled = items.filter((d) => String(d.status || '').toUpperCase() === 'ENABLED').length
  dsSummary.healthy = items.filter((d) => String(d.lastTestStatus || '').toUpperCase() === 'OK').length
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

    if (statsRes.data.code === 200) {
      const s = statsRes.data.data || {}
      dashStats.totalCount = Number(s.totalCount) || 0
      dashStats.publicCount = Number(s.publicCount) || 0
      dashStats.privateCount = Number(s.privateCount) || 0
      dashStats.totalViews = Number(s.totalViews) || 0
    }

    if (perfRes.data.code === 200) {
      perfOverview.value = perfRes.data.data || null
    } else {
      perfOverview.value = null
    }

    if (dsRes.data.code === 200) {
      summarizeDatasources(dsRes.data.data)
    } else {
      summarizeDatasources([])
    }
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stack-c-admin-workbench { padding: 0 4px; }
.panel-header { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 16px; }
.panel-header h2 { margin: 0; width: 100%; }
.panel-header p { margin: 0; color: #606266; flex: 1; }
.sub-title { margin: 20px 0 12px; font-size: 16px; }
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 8px;
}
.stat-card {
  padding: 14px 16px;
  border-radius: 10px;
  border: 1px solid #ebeef5;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-card--blue { border-color: #bfdbfe; background: #eff6ff; }
.stat-card--green { border-color: #bbf7d0; background: #f0fdf4; }
.stat-card--amber { border-color: #fde68a; background: #fffbeb; }
.stat-card--indigo { border-color: #c7d2fe; background: #eef2ff; }
.stat-card--slate { border-color: #e2e8f0; background: #f8fafc; }
.stat-card--danger { border-color: #fecaca; background: #fef2f2; }
.stat-card--rose { border-color: #fbcfe8; background: #fdf2f8; }
.stat-label { font-size: 12px; color: #64748b; }
.stat-value { font-size: 22px; font-weight: 700; color: #0f172a; line-height: 1.2; }
.stat-card small { font-size: 11px; color: #94a3b8; }
.ann-title { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.ann-content { margin: 8px 0 0; white-space: pre-wrap; color: #606266; font-size: 14px; }
:deep(.el-table tbody tr) { cursor: pointer; }
</style>
