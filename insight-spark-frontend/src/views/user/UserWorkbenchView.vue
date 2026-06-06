<template>
  <section class="stack-c-user-workbench">
    <div class="panel">
      <div class="panel-header">
        <h2>用户工作台</h2>
        <p>个人视角：公告与最近看板。与管理员端工作台页面已拆分，可分别迭代。</p>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <el-skeleton v-if="loading" :rows="4" animated />
      <template v-else>
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
        <el-table v-else :data="recentDashboards" size="small" border>
          <el-table-column prop="name" label="名称" min-width="140" />
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
import { onMounted, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const loading = ref(true)
const announcements = ref([])
const recentDashboards = ref([])

const formatTime = (v) => (v ? String(v).replace('T', ' ') : '-')

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
.ann-title { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.ann-content { margin: 8px 0 0; white-space: pre-wrap; color: #606266; font-size: 14px; }
</style>
