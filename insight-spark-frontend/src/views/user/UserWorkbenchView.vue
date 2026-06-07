<template>
  <section class="stack-c-user-workbench">
    <div class="panel">
      <div class="panel-header">
        <h2>用户工作台</h2>
        <p>个人视角：公告、最近看板与快捷入口。</p>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <el-skeleton v-if="loading" :rows="4" animated />
      <template v-else>
        <div class="quick-grid">
          <button
            v-for="item in quickLinks"
            :key="item.key"
            type="button"
            class="quick-card"
            @click="goModule(item.key)"
          >
            <span class="quick-icon">{{ item.icon }}</span>
            <strong>{{ item.title }}</strong>
            <small>{{ item.desc }}</small>
          </button>
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

        <h3 class="sub-title">最近看板</h3>
        <el-empty v-if="!recentDashboards.length" description="暂无看板，请到「我的看板」创建" />
        <el-table
          v-else
          :data="recentDashboards"
          size="small"
          border
          class="dash-table"
          @row-click="openDashboard"
        >
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag :type="row.isPublic ? 'warning' : 'info'" size="small">
                {{ row.isPublic ? '公共' : '私密' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="170" />
          <el-table-column label="" width="88" align="center">
            <template #default>
              <span class="row-hint">进入 →</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </section>
</template>

<script setup>
import { inject, onMounted, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'
const workbench = inject('workbench', null)

const loading = ref(true)
const announcements = ref([])
const recentDashboards = ref([])

const quickLinks = [
  { key: 'dashboard', icon: '📊', title: '我的看板', desc: '管理布局与图表' },
  { key: 'collaboration', icon: '💬', title: '业务协同', desc: '批注与团队协作' },
  { key: 'chat', icon: '🤖', title: '对话查询', desc: '自然语言出图' },
  { key: 'upload', icon: '📁', title: '数据上传', desc: 'Excel/CSV 建模' },
  { key: 'advancedAnalysis', icon: '📈', title: '预测模拟', desc: '时序与情景推演' },
  { key: 'permission', icon: '🔐', title: '数据权限', desc: '申请与查看权限' }
]

const formatTime = (v) => (v ? String(v).replace('T', ' ') : '-')

function goModule(key) {
  if (workbench?.activeModule) {
    workbench.activeModule.value = key
  }
}

function openDashboard() {
  goModule('dashboard')
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
    announcements.value = d.announcements || []
    recentDashboards.value = d.recentDashboards || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stack-c-user-workbench { padding: 0 4px; }
.panel-header { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 16px; }
.panel-header h2 { margin: 0; width: 100%; }
.panel-header p { margin: 0; color: #606266; flex: 1; }
.sub-title { margin: 20px 0 12px; font-size: 16px; }
.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
  margin-bottom: 8px;
}
.quick-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #fafafa;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.quick-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.12);
}
.quick-icon { font-size: 20px; line-height: 1; }
.quick-card strong { font-size: 14px; color: #303133; }
.quick-card small { font-size: 12px; color: #909399; }
.ann-title { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.ann-content { margin: 8px 0 0; white-space: pre-wrap; color: #606266; font-size: 14px; }
.dash-table :deep(tbody tr) { cursor: pointer; }
.row-hint { font-size: 12px; color: #409eff; }
</style>
