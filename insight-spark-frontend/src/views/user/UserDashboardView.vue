<template>
  <section class="stack-c-user-dashboard">
    <p class="portal-hint">
      用户端 · 我的看板（独立页面）。批注与协同已迁至侧栏「业务批注与协同」。
    </p>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建看板</el-button>
      <el-button @click="loadList">刷新列表</el-button>
    </div>
    <el-table :data="rows" border v-loading="loadingList" empty-text="暂无看板">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="公共" width="80">
        <template #default="{ row }">{{ row.isPublic ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column prop="ownerUserId" label="所有者" width="120" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" :title="editId ? '编辑看板' : '新建看板'" width="640px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="看板名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="是否公共看板">
          <el-switch v-model="form.isPublic" />
        </el-form-item>
        <el-form-item label="布局 JSON（画布与图表配置）">
          <el-input v-model="form.layoutJson" type="textarea" :rows="10" placeholder="{}" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const rows = ref([])
const loadingList = ref(false)
const editVisible = ref(false)
const saving = ref(false)
const editId = ref(null)
const form = reactive({ name: '', description: '', layoutJson: '{}', isPublic: false })

const loadList = async () => {
  loadingList.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/dashboards`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    rows.value = res.data.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingList.value = false
  }
}

const openCreate = () => {
  editId.value = null
  form.name = ''
  form.description = ''
  form.layoutJson = '{}'
  form.isPublic = false
  editVisible.value = true
}

const openEdit = (row) => {
  editId.value = row.id
  form.name = row.name
  form.description = row.description || ''
  form.layoutJson = row.layoutJson || '{}'
  form.isPublic = Boolean(row.isPublic)
  editVisible.value = true
}

const save = async () => {
  saving.value = true
  restoreSessionHeader()
  try {
    const body = {
      name: form.name,
      description: form.description || null,
      layoutJson: form.layoutJson || '{}',
      isPublic: form.isPublic
    }
    let res
    if (editId.value) {
      res = await axios.put(`${API_BASE}/api/c/dashboards/${editId.value}`, body)
    } else {
      res = await axios.post(`${API_BASE}/api/c/dashboards`, body)
    }
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已保存')
    editVisible.value = false
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除看板「${row.name}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  restoreSessionHeader()
  try {
    const res = await axios.delete(`${API_BASE}/api/c/dashboards/${row.id}`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(loadList)
</script>

<style scoped>
.stack-c-user-dashboard { padding: 0 4px; }
.portal-hint { color: #909399; font-size: 13px; margin: 0 0 12px; }
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; }
</style>
