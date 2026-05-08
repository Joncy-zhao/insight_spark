<template>
  <section class="biz-collab-page">
    <p class="portal-hint">
      业务批注与协同（独立开发页）：选择看板后查看与发表批注、评论。后续可在此接入 WebSocket、圈注、汇报导出等。
    </p>

    <el-card shadow="never" class="select-card">
      <template #header>选择看板</template>
      <div class="select-row">
        <el-select
          v-model="selectedId"
          placeholder="请选择要协作的看板"
          filterable
          clearable
          style="min-width: 320px"
          :loading="loadingList"
          @change="onDashboardChange"
        >
          <el-option
            v-for="row in rows"
            :key="row.id"
            :label="`${row.name} (#${row.id})`"
            :value="row.id"
          />
        </el-select>
        <el-button @click="loadList">刷新看板列表</el-button>
        <el-button type="primary" :disabled="!selected" @click="loadCollab">刷新批注/评论</el-button>
      </div>
      <p v-if="selected" class="meta-line">
        当前：<strong>{{ selected.name }}</strong>
        <el-tag size="small" class="ml8">{{ selected.isPublic ? '公共' : '个人' }}</el-tag>
        <span class="muted">所有者 {{ selected.ownerUserId }}</span>
      </p>
    </el-card>

    <el-card v-if="selected" class="collab-card" shadow="never">
      <template #header>
        <span>看板 #{{ selected.id }} · 批注与评论</span>
      </template>
      <div class="collab-grid">
        <div>
          <h4>批注</h4>
          <el-timeline v-if="annotations.length">
            <el-timeline-item v-for="x in annotations" :key="x.id" :timestamp="x.createdAt">
              <strong>{{ x.userId }}</strong>
              <el-tag v-if="x.tag" size="small" class="ml8">{{ x.tag }}</el-tag>
              <p>{{ x.content }}</p>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无批注" />
          <el-form label-position="top" class="mt16">
            <el-form-item label="批注内容">
              <el-input v-model="annForm.content" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item label="标签（可选）">
              <el-input v-model="annForm.tag" />
            </el-form-item>
            <el-button type="primary" size="small" @click="submitAnnotation">发表批注</el-button>
          </el-form>
        </div>
        <div>
          <h4>评论</h4>
          <el-empty v-if="!comments.length" description="暂无评论" />
          <div v-for="c in comments" :key="c.id" class="comment-line">
            <strong>{{ c.userId }}</strong>
            <span class="muted">{{ c.createdAt }}</span>
            <p>{{ c.content }}</p>
          </div>
          <el-form label-position="top" class="mt16">
            <el-form-item label="评论">
              <el-input v-model="comForm.content" type="textarea" :rows="2" />
            </el-form-item>
            <el-button type="primary" size="small" @click="submitComment">发表评论</el-button>
          </el-form>
        </div>
      </div>
    </el-card>

    <el-empty v-else-if="!loadingList && rows.length" description="请在上方选择看板" />
    <el-empty v-else-if="!loadingList && !rows.length" description="暂无看板，请先在「我的看板」中创建" />
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const rows = ref([])
const loadingList = ref(false)
const selectedId = ref(null)
const annotations = ref([])
const comments = ref([])
const annForm = reactive({ content: '', tag: '' })
const comForm = reactive({ content: '' })

const selected = computed(() => rows.value.find((r) => r.id == selectedId.value) || null)

const loadList = async () => {
  loadingList.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/dashboards`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    rows.value = res.data.data || []
    if (selectedId.value != null && !rows.value.some((r) => r.id == selectedId.value)) {
      selectedId.value = null
    }
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingList.value = false
  }
}

const onDashboardChange = () => {
  annotations.value = []
  comments.value = []
  if (selected.value) loadCollab()
}

const loadCollab = async () => {
  if (!selected.value) return
  restoreSessionHeader()
  try {
    const [a, c] = await Promise.all([
      axios.get(`${API_BASE}/api/c/annotations`, { params: { targetType: 'DASHBOARD', targetId: selected.value.id } }),
      axios.get(`${API_BASE}/api/c/comments`, { params: { targetType: 'DASHBOARD', targetId: selected.value.id } })
    ])
    if (a.data.code !== 200) throw new Error(a.data.message)
    if (c.data.code !== 200) throw new Error(c.data.message)
    annotations.value = a.data.data || []
    comments.value = c.data.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载批注/评论失败')
  }
}

const submitAnnotation = async () => {
  if (!selected.value || !annForm.content.trim()) {
    ElMessage.warning('请输入批注内容')
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.post(`${API_BASE}/api/c/annotations`, {
      targetType: 'DASHBOARD',
      targetId: selected.value.id,
      dashboardId: selected.value.id,
      content: annForm.content.trim(),
      tag: annForm.tag.trim() || null
    })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已发表')
    annForm.content = ''
    annForm.tag = ''
    await loadCollab()
  } catch (e) {
    ElMessage.error(e.message || '失败')
  }
}

const submitComment = async () => {
  if (!selected.value || !comForm.content.trim()) {
    ElMessage.warning('请输入评论')
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.post(`${API_BASE}/api/c/comments`, {
      targetType: 'DASHBOARD',
      targetId: selected.value.id,
      content: comForm.content.trim()
    })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已发表')
    comForm.content = ''
    await loadCollab()
  } catch (e) {
    ElMessage.error(e.message || '失败')
  }
}

onMounted(loadList)
</script>

<style scoped>
.biz-collab-page { padding: 0 4px; }
.portal-hint { color: #909399; font-size: 13px; margin: 0 0 16px; line-height: 1.5; }
.select-card { margin-bottom: 16px; }
.select-row { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.meta-line { margin: 12px 0 0; font-size: 14px; }
.ml8 { margin-left: 8px; }
.muted { color: #909399; font-size: 12px; margin-left: 12px; }
.collab-card { margin-top: 0; }
.collab-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
@media (max-width: 960px) { .collab-grid { grid-template-columns: 1fr; } }
h4 { margin: 0 0 8px; }
.mt16 { margin-top: 16px; }
.comment-line { border-bottom: 1px solid #ebeef5; padding-bottom: 8px; margin-bottom: 8px; }
</style>
