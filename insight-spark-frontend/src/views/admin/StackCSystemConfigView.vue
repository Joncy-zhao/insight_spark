<template>
  <section class="sys-config">
    <header class="sys-head">
      <div class="sys-head-text">
        <h1>全局系统参数配置</h1>
        <p>
          可视化维护全局 KV 配置，替代硬编码规则，适配私有化部署与不同企业业务场景。
          修改后自动保存并生效；复杂字段提供标签、表格、用户选择等交互组件，无需手写 JSON。
        </p>
      </div>
      <div class="sys-head-actions">
        <el-tag type="info" effect="plain">{{ stats.totalKeys }} 项配置</el-tag>
        <el-tag type="success" effect="plain">{{ stats.wiredCount }} 项已接入真实数据源</el-tag>
        <el-tag v-if="stats.readOnlyCount" type="info" effect="plain">{{ stats.readOnlyCount }} 项只读</el-tag>
        <el-tag v-if="dirtyCount" type="warning" effect="plain">{{ dirtyCount }} 项待保存</el-tag>
        <el-button :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" :loading="savingAll" :disabled="!dirtyCount" @click="saveAllDirty">
          保存全部变更
        </el-button>
      </div>
    </header>

    <el-container class="sys-layout">
      <el-aside width="220px" class="sys-aside">
        <el-menu :default-active="activeModule" class="sys-menu" @select="onSelectModule">
          <el-menu-item v-for="m in modules" :key="m.id" :index="m.id">
            <span class="menu-icon">{{ moduleMeta(m.id).icon }}</span>
            <span class="menu-label">{{ m.title }}</span>
            <el-badge
              v-if="moduleDirtyCount(m.id)"
              :value="moduleDirtyCount(m.id)"
              class="menu-badge"
              type="warning"
            />
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="sys-main" v-loading="loading">
        <template v-if="currentModule">
          <div class="module-banner">
            <div>
              <h2>
                <span class="banner-icon">{{ moduleMeta(currentModule.id).icon }}</span>
                {{ currentModule.title }}
              </h2>
              <p>{{ moduleMeta(currentModule.id).desc }}</p>
            </div>
            <div class="module-actions">
              <el-button @click="resetCurrentModule" :loading="resetting">恢复默认</el-button>
              <el-button
                type="primary"
                :loading="savingModule"
                :disabled="!moduleDirtyCount(currentModule.id)"
                @click="saveCurrentModule"
              >
                保存本模块
              </el-button>
            </div>
          </div>

          <!-- 通知模块：公告管理 -->
          <el-card v-if="activeModule === 'NOTIFICATION'" shadow="never" class="ann-card">
            <template #header>
              <div class="card-head">
                <span>公告发布与管理</span>
                <el-button link type="primary" @click="annPanelOpen = !annPanelOpen">
                  {{ annPanelOpen ? '收起' : '展开' }}发布表单
                </el-button>
              </div>
            </template>
            <el-collapse-transition>
              <el-form v-show="annPanelOpen" label-position="top" class="ann-form" @submit.prevent>
                <el-row :gutter="16">
                  <el-col :span="16">
                    <el-form-item label="标题" required>
                      <el-input v-model="ann.title" placeholder="公告标题" maxlength="255" show-word-limit />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="受众">
                      <el-select v-model="ann.audience" style="width: 100%">
                        <el-option label="全员 ALL" value="ALL" />
                        <el-option label="普通用户 USER" value="USER" />
                        <el-option label="管理员 ADMIN" value="ADMIN" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-form-item label="正文" required>
                  <el-input
                    v-model="ann.content"
                    type="textarea"
                    :rows="4"
                    placeholder="支持多行正文，将写入 is_system_announcement"
                  />
                </el-form-item>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="置顶">
                      <el-switch v-model="ann.pinned" active-text="置顶" inactive-text="普通" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="优先级">
                      <el-input-number v-model="ann.priority" :min="0" :max="999" style="width: 100%" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8" class="ann-submit-col">
                    <el-button type="primary" :loading="annLoading" @click="submitAnnouncement">发布公告</el-button>
                  </el-col>
                </el-row>
              </el-form>
            </el-collapse-transition>
            <el-table :data="announcements" size="small" border empty-text="暂无公告" class="ann-table">
              <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
              <el-table-column prop="audience" label="受众" width="80" />
              <el-table-column label="置顶" width="64" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.pinned" type="danger" size="small">置顶</el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column prop="priority" label="优先级" width="72" align="center" />
              <el-table-column prop="publishStatus" label="状态" width="88" />
              <el-table-column prop="publishedAt" label="发布时间" width="168">
                <template #default="{ row }">{{ formatTime(row.publishedAt || row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-form label-position="top" class="config-form" @submit.prevent>
            <div class="config-grid">
              <div
                v-for="item in currentModule.items"
                :key="item.configKey"
                class="config-field"
                :class="{ 'is-dirty': isDirty(item.configKey) }"
              >
                <div class="field-head">
                  <label :for="`cfg-${item.configKey}`">{{ item.label }}</label>
                  <el-tooltip
                    v-if="item.bindingNote || item.bindingSource"
                    :content="`${item.bindingSource || ''} · ${item.bindingNote || ''}`"
                    placement="top"
                  >
                    <el-tag size="small" :type="bindingTagType(item.binding)" effect="plain" class="binding-tag">
                      {{ bindingLabel(item.binding) }}
                    </el-tag>
                  </el-tooltip>
                  <el-tooltip v-if="item.description" :content="item.description" placement="top">
                    <el-icon class="field-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                  <el-tag v-if="item.readOnly" size="small" type="info" effect="plain">只读</el-tag>
                  <el-tag v-if="isDirty(item.configKey)" size="small" type="warning" effect="plain">已修改</el-tag>
                </div>
                <p class="field-key">{{ item.configKey }}</p>

                <el-switch
                  v-if="item.inputType === 'boolean'"
                  :id="`cfg-${item.configKey}`"
                  :model-value="boolValue(item.configKey)"
                  :disabled="item.readOnly"
                  @change="(v) => setValueAndSave(item, v ? 'true' : 'false')"
                />

                <el-input-number
                  v-else-if="item.inputType === 'number'"
                  :id="`cfg-${item.configKey}`"
                  :model-value="numberValue(item.configKey)"
                  :min="0"
                  :max="9999999"
                  :disabled="item.readOnly"
                  controls-position="right"
                  style="width: 100%"
                  @change="(v) => setValueAndSave(item, v == null ? '' : String(v))"
                />

                <el-select
                  v-else-if="item.inputType === 'select'"
                  :id="`cfg-${item.configKey}`"
                  :model-value="values[item.configKey]"
                  :disabled="item.readOnly"
                  style="width: 100%"
                  @change="(v) => setValueAndSave(item, v)"
                >
                  <el-option
                    v-for="opt in parseOptions(item.options)"
                    :key="opt"
                    :label="opt"
                    :value="opt"
                  />
                </el-select>

                <ConfigStringListEditor
                  v-else-if="item.inputType === 'stringList'"
                  :model-value="values[item.configKey]"
                  :disabled="item.readOnly"
                  :placeholder="item.placeholder"
                  @update:model-value="(v) => setValueAndSave(item, v)"
                />

                <ConfigSensitiveRulesEditor
                  v-else-if="item.inputType === 'sensitiveRules'"
                  :model-value="values[item.configKey]"
                  :disabled="item.readOnly"
                  @update:model-value="(v) => setValueAndSave(item, v)"
                />

                <ConfigUserMultiSelect
                  v-else-if="item.inputType === 'userMulti'"
                  :model-value="values[item.configKey]"
                  :disabled="item.readOnly"
                  @update:model-value="(v) => setValueAndSave(item, v)"
                />

                <ConfigChannelMultiSelect
                  v-else-if="item.inputType === 'channelMulti'"
                  :model-value="values[item.configKey]"
                  :disabled="item.readOnly"
                  @update:model-value="(v) => setValueAndSave(item, v)"
                />

                <ConfigRoleMultiSelect
                  v-else-if="item.inputType === 'roleMulti'"
                  :model-value="values[item.configKey]"
                  :disabled="item.readOnly"
                  @update:model-value="(v) => setValueAndSave(item, v)"
                />

                <el-input
                  v-else-if="item.inputType === 'textarea' || item.inputType === 'json'"
                  :id="`cfg-${item.configKey}`"
                  :model-value="values[item.configKey]"
                  type="textarea"
                  :rows="item.inputType === 'json' ? 4 : 5"
                  :placeholder="item.placeholder || ''"
                  :disabled="item.readOnly"
                  @input="(v) => setValue(item, v)"
                  @blur="() => autoSaveItem(item)"
                />

                <el-input
                  v-else
                  :id="`cfg-${item.configKey}`"
                  :model-value="values[item.configKey]"
                  :placeholder="item.placeholder || (item.inputType === 'cron' ? '0 0 6 * * ?' : '')"
                  :disabled="item.readOnly"
                  @input="(v) => setValue(item, v)"
                  @blur="() => autoSaveItem(item)"
                />

                <p v-if="autoSavingKeys.has(item.configKey)" class="field-saving">保存中…</p>

                <p v-if="item.updatedAt" class="field-meta">更新于 {{ formatTime(item.updatedAt) }}</p>
              </div>
            </div>
          </el-form>
        </template>
      </el-main>
    </el-container>

    <el-collapse class="advanced-collapse">
      <el-collapse-item title="高级：原始 KV 表（is_system_config）" name="advanced">
        <div class="advanced-toolbar">
          <el-button size="small" @click="openUpsert">新增自定义键</el-button>
        </div>
        <el-table :data="rawRows" border size="small" max-height="320" @row-click="editRawRow">
          <el-table-column prop="configKey" label="键" min-width="180" />
          <el-table-column prop="configValue" label="值" min-width="200" show-overflow-tooltip />
          <el-table-column prop="category" label="分组" width="100" />
          <el-table-column prop="updatedAt" label="更新时间" width="168" />
        </el-table>
      </el-collapse-item>
    </el-collapse>

    <el-dialog v-model="rawVisible" title="保存自定义配置项" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="configKey" required>
          <el-input v-model="rawForm.configKey" :disabled="rawForm.locked" />
        </el-form-item>
        <el-form-item label="configValue">
          <el-input v-model="rawForm.configValue" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="category">
          <el-input v-model="rawForm.category" />
        </el-form-item>
        <el-form-item label="description">
          <el-input v-model="rawForm.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rawVisible = false">取消</el-button>
        <el-button type="primary" :loading="rawSaving" @click="saveRaw">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import ConfigStringListEditor from '../../components/systemConfig/ConfigStringListEditor.vue'
import ConfigSensitiveRulesEditor from '../../components/systemConfig/ConfigSensitiveRulesEditor.vue'
import ConfigUserMultiSelect from '../../components/systemConfig/ConfigUserMultiSelect.vue'
import ConfigChannelMultiSelect from '../../components/systemConfig/ConfigChannelMultiSelect.vue'
import ConfigRoleMultiSelect from '../../components/systemConfig/ConfigRoleMultiSelect.vue'
import {
  BINDING_LABELS,
  MODULE_META,
  fetchAdminAnnouncements,
  fetchConfigSchema,
  publishAnnouncement,
  resetConfigModule,
  saveConfigBatch,
  saveConfigItem
} from '../../api/systemConfig.js'
import axios from 'axios'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const loading = ref(false)
const savingModule = ref(false)
const savingAll = ref(false)
const resetting = ref(false)
const modules = ref([])
const activeModule = ref('AI')
const values = reactive({})
const originals = reactive({})
const metaByKey = reactive({})
const stats = reactive({ totalKeys: 0, wiredCount: 0, readOnlyCount: 0 })
const rawRows = ref([])

const annPanelOpen = ref(true)
const ann = reactive({ title: '', content: '', audience: 'ALL', pinned: false, priority: 0 })
const annLoading = ref(false)
const announcements = ref([])

const rawVisible = ref(false)
const rawSaving = ref(false)
const rawForm = reactive({
  configKey: '',
  configValue: '',
  valueType: 'STRING',
  category: '',
  description: '',
  locked: false
})

const autoSaveTimers = {}
const autoSavingKeys = ref(new Set())

const currentModule = computed(() => modules.value.find((m) => m.id === activeModule.value) || null)

const dirtyKeys = computed(() =>
  Object.keys(values).filter((k) => String(values[k] ?? '') !== String(originals[k] ?? ''))
)

const dirtyCount = computed(() => dirtyKeys.value.length)

function moduleMeta(id) {
  return MODULE_META[id] || { icon: '⚙️', desc: '' }
}

function moduleDirtyCount(moduleId) {
  const mod = modules.value.find((m) => m.id === moduleId)
  if (!mod) return 0
  return (mod.items || []).filter((item) => isDirty(item.configKey)).length
}

function isDirty(key) {
  return String(values[key] ?? '') !== String(originals[key] ?? '')
}

function boolValue(key) {
  const v = String(values[key] ?? '').toLowerCase()
  return v === 'true' || v === '1'
}

function numberValue(key) {
  const n = Number(values[key])
  return Number.isFinite(n) ? n : 0
}

function parseOptions(optionsJson) {
  if (!optionsJson) return []
  try {
    const arr = JSON.parse(optionsJson)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function setValue(item, val) {
  values[item.configKey] = val == null ? '' : String(val)
}

function setValueAndSave(item, val) {
  setValue(item, val)
  autoSaveItem(item)
}

function buildSinglePayload(item) {
  return {
    configKey: item.configKey,
    configValue: values[item.configKey],
    valueType: item.valueType || 'STRING',
    category: item.category || item.moduleId || '',
    description: item.description || ''
  }
}

async function autoSaveItem(item) {
  if (!item || item.readOnly || !isDirty(item.configKey)) return
  clearTimeout(autoSaveTimers[item.configKey])
  autoSaveTimers[item.configKey] = setTimeout(async () => {
    autoSavingKeys.value.add(item.configKey)
    try {
      await saveConfigItem(buildSinglePayload(item))
      commitSaved([item.configKey])
      ElMessage.success({ message: `「${item.label}」已保存并生效`, duration: 1800 })
      await loadRawRows()
    } catch (e) {
      ElMessage.error(e.message || '保存失败')
    } finally {
      autoSavingKeys.value.delete(item.configKey)
    }
  }, 500)
}

function formatTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '—'
}

function hydrateFromSchema(data) {
  const list = Array.isArray(data?.modules) ? data.modules : []
  modules.value = list
  stats.totalKeys = Number(data?.totalKeys) || 0
  stats.wiredCount = Number(data?.wiredCount) || 0
  stats.readOnlyCount = Number(data?.readOnlyCount) || 0

  for (const mod of list) {
    for (const item of mod.items || []) {
      const key = item.configKey
      const val = item.configValue ?? item.defaultValue ?? ''
      values[key] = String(val)
      originals[key] = String(val)
      metaByKey[key] = item
    }
  }
}

function bindingLabel(binding) {
  return BINDING_LABELS[binding]?.text || binding || '配置'
}

function bindingTagType(binding) {
  return BINDING_LABELS[binding]?.type || ''
}

function isEditable(key) {
  const item = metaByKey[key]
  return item && !item.readOnly
}

function buildPayloadForModule(moduleId) {
  const mod = modules.value.find((m) => m.id === moduleId)
  if (!mod) return []
  return (mod.items || [])
    .filter((item) => isDirty(item.configKey) && isEditable(item.configKey))
    .map((item) => ({
      configKey: item.configKey,
      configValue: values[item.configKey],
      valueType: item.valueType || 'STRING',
      category: item.category || moduleId,
      description: item.description || ''
    }))
}

function buildPayloadForKeys(keys) {
  return keys.filter(isEditable).map((key) => {
    const item = metaByKey[key] || {}
    return {
      configKey: key,
      configValue: values[key],
      valueType: item.valueType || 'STRING',
      category: item.category || '',
      description: item.description || ''
    }
  })
}

function commitSaved(keys) {
  for (const key of keys) {
    originals[key] = String(values[key] ?? '')
  }
}

async function loadAnnouncements() {
  try {
    announcements.value = await fetchAdminAnnouncements()
  } catch {
    announcements.value = []
  }
}

async function loadRawRows() {
  restoreSessionHeader()
  try {
    const res = await axios.get(`${API_BASE}/api/c/admin/system-config`)
    if (res.data.code !== 200) throw new Error(res.data.message)
    rawRows.value = res.data.data || []
  } catch {
    rawRows.value = []
  }
}

async function reload() {
  loading.value = true
  try {
    const data = await fetchConfigSchema()
    hydrateFromSchema(data)
    await Promise.all([loadAnnouncements(), loadRawRows()])
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function onSelectModule(id) {
  if (moduleDirtyCount(activeModule.value)) {
    ElMessage.info('当前模块有未保存变更，切换后仍可回来保存')
  }
  activeModule.value = id
}

async function saveCurrentModule() {
  const items = buildPayloadForModule(activeModule.value)
  if (!items.length) return
  savingModule.value = true
  try {
    await saveConfigBatch(items)
    commitSaved(items.map((i) => i.configKey))
    ElMessage.success(`已保存 ${items.length} 项配置`)
    await loadRawRows()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingModule.value = false
  }
}

async function saveAllDirty() {
  const keys = dirtyKeys.value
  if (!keys.length) return
  savingAll.value = true
  try {
    await saveConfigBatch(buildPayloadForKeys(keys))
    commitSaved(keys)
    ElMessage.success(`已保存 ${keys.length} 项配置`)
    await loadRawRows()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingAll.value = false
  }
}

async function resetCurrentModule() {
  try {
    await ElMessageBox.confirm(
      `确定将「${currentModule.value?.title}」全部恢复为默认值？`,
      '恢复默认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    await resetConfigModule(activeModule.value)
    ElMessage.success('已恢复默认')
    await reload()
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  } finally {
    resetting.value = false
  }
}

async function submitAnnouncement() {
  if (!ann.title.trim() || !ann.content.trim()) {
    ElMessage.warning('请填写标题与正文')
    return
  }
  annLoading.value = true
  try {
    await publishAnnouncement({
      title: ann.title.trim(),
      content: ann.content.trim(),
      audience: ann.audience,
      pinned: ann.pinned,
      priority: ann.priority,
      publishStatus: 'PUBLISHED'
    })
    ElMessage.success('公告已发布')
    ann.title = ''
    ann.content = ''
    await loadAnnouncements()
  } catch (e) {
    ElMessage.error(e.message || '发布失败')
  } finally {
    annLoading.value = false
  }
}

function openUpsert() {
  rawForm.configKey = ''
  rawForm.configValue = ''
  rawForm.valueType = 'STRING'
  rawForm.category = ''
  rawForm.description = ''
  rawForm.locked = false
  rawVisible.value = true
}

function editRawRow(row) {
  if (!row) return
  rawForm.configKey = row.configKey || ''
  rawForm.configValue = row.configValue ?? ''
  rawForm.valueType = row.valueType || 'STRING'
  rawForm.category = row.category || ''
  rawForm.description = row.description || ''
  rawForm.locked = true
  rawVisible.value = true
}

async function saveRaw() {
  if (!rawForm.configKey.trim()) {
    ElMessage.warning('请填写 configKey')
    return
  }
  rawSaving.value = true
  try {
    await saveConfigItem({ ...rawForm })
    ElMessage.success('已保存')
    rawVisible.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    rawSaving.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.sys-config {
  padding: 0 4px 24px;
  min-height: 100%;
}
.sys-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  padding: 18px 20px;
  border-radius: 12px;
  background: linear-gradient(135deg, #f0f9ff 0%, #eef2ff 55%, #faf5ff 100%);
  border: 1px solid #e0e7ff;
}
.sys-head h1 {
  margin: 0 0 8px;
  font-size: 20px;
  color: #0f172a;
}
.sys-head p {
  margin: 0;
  max-width: 720px;
  font-size: 13px;
  line-height: 1.6;
  color: #475569;
}
.sys-head-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.sys-layout {
  border: 1px solid #ebeef5;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  min-height: 560px;
}
.sys-aside {
  border-right: 1px solid #ebeef5;
  background: #fafbfc;
}
.sys-menu {
  border-right: none;
  background: transparent;
}
.menu-icon {
  margin-right: 8px;
}
.menu-label {
  flex: 1;
}
.menu-badge {
  margin-left: 6px;
}
.sys-main {
  padding: 20px 24px 28px;
}
.module-banner {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px dashed #e2e8f0;
}
.module-banner h2 {
  margin: 0 0 6px;
  font-size: 18px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.banner-icon { font-size: 22px; }
.module-banner p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}
.module-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.ann-card {
  margin-bottom: 20px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ann-form { margin-bottom: 12px; }
.ann-submit-col {
  display: flex;
  align-items: flex-end;
  padding-bottom: 4px;
}
.ann-table { margin-top: 8px; }
.config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.config-field {
  padding: 14px 16px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #fafafa;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.config-field.is-dirty {
  border-color: #f59e0b;
  background: #fffbeb;
  box-shadow: 0 0 0 1px rgba(245, 158, 11, 0.15);
}
.config-field:has(:disabled) {
  opacity: 0.92;
  background: #f8fafc;
}
.binding-tag { cursor: help; }
.field-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.field-head label {
  font-weight: 600;
  font-size: 14px;
  color: #1e293b;
}
.field-help {
  color: #94a3b8;
  cursor: help;
}
.field-key {
  margin: 0 0 10px;
  font-size: 11px;
  color: #94a3b8;
  font-family: ui-monospace, monospace;
}
.field-meta {
  margin: 8px 0 0;
  font-size: 11px;
  color: #cbd5e1;
}
.field-saving {
  margin: 4px 0 0;
  font-size: 11px;
  color: #409eff;
}
.advanced-collapse {
  margin-top: 16px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  overflow: hidden;
}
.advanced-toolbar {
  margin-bottom: 8px;
}
.muted { color: #94a3b8; }
:deep(.el-menu-item) {
  display: flex;
  align-items: center;
  height: 46px;
}
:deep(.advanced-collapse .el-collapse-item__header) {
  padding: 0 16px;
  font-size: 13px;
  color: #64748b;
}
:deep(.el-table tbody tr) { cursor: pointer; }
</style>
