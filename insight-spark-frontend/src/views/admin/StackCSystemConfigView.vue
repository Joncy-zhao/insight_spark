<template>
  <section class="stack-c-config">
    <el-card class="mb16" shadow="never">
      <template #header>发布公告（写入 is_system_announcement）</template>
      <el-form label-position="top" class="ann-form">
        <el-form-item label="标题">
          <el-input v-model="ann.title" placeholder="公告标题" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="ann.content" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="受众">
          <el-select v-model="ann.audience" style="width: 220px">
            <el-option label="全员 ALL" value="ALL" />
            <el-option label="普通用户 USER" value="USER" />
            <el-option label="管理员 ADMIN" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="annLoading" @click="submitAnnouncement">发布</el-button>
      </el-form>
    </el-card>

    <div class="toolbar">
      <el-button type="primary" @click="openUpsert">新增/修改配置</el-button>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" border v-loading="loading" empty-text="暂无配置项">
      <el-table-column prop="configKey" label="键" min-width="160" />
      <el-table-column prop="configValue" label="值" min-width="200" show-overflow-tooltip />
      <el-table-column prop="valueType" label="类型" width="90" />
      <el-table-column prop="category" label="分组" width="100" />
      <el-table-column prop="description" label="说明" min-width="140" show-overflow-tooltip />
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
    </el-table>

    <el-dialog v-model="visible" title="保存配置项" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="configKey（唯一）">
          <el-input v-model="form.configKey" placeholder="例如 ui.theme" />
        </el-form-item>
        <el-form-item label="configValue">
          <el-input v-model="form.configValue" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="valueType">
          <el-select v-model="form.valueType" style="width: 100%">
            <el-option label="STRING" value="STRING" />
            <el-option label="JSON" value="JSON" />
            <el-option label="NUMBER" value="NUMBER" />
          </el-select>
        </el-form-item>
        <el-form-item label="category">
          <el-input v-model="form.category" placeholder="如 UI / SECURITY" />
        </el-form-item>
        <el-form-item label="description">
          <el-input v-model="form.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存（UPSERT）</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const rows = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const form = reactive({
  configKey: '',
  configValue: '',
  valueType: 'STRING',
  category: '',
  description: ''
})

const ann = reactive({ title: '', content: '', audience: 'ALL' })
const annLoading = ref(false)

const load = async () => {
  loading.value = true
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/admin/system-config`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    rows.value = res.data.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const openUpsert = () => {
  form.configKey = ''
  form.configValue = ''
  form.valueType = 'STRING'
  form.category = ''
  form.description = ''
  visible.value = true
}

const save = async () => {
  saving.value = true
  restoreSessionHeader()
  try {
    const res = await axios.put(`${API_BASE}/api/c/admin/system-config`, { ...form })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已保存')
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const submitAnnouncement = async () => {
  if (!ann.title.trim() || !ann.content.trim()) {
    ElMessage.warning('请填写标题与正文')
    return
  }
  annLoading.value = true
  restoreSessionHeader()
  try {
    const res = await axios.post(`${API_BASE}/api/c/admin/announcements`, {
      title: ann.title.trim(),
      content: ann.content.trim(),
      audience: ann.audience,
      publishStatus: 'PUBLISHED'
    })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('公告已发布')
    ann.title = ''
    ann.content = ''
  } catch (e) {
    ElMessage.error(e.message || '发布失败')
  } finally {
    annLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stack-c-config { padding: 0 4px; }
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; }
.mb16 { margin-bottom: 16px; }
.ann-form { max-width: 640px; }
</style>
