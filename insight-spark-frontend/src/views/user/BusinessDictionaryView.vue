<template>
  <section class="dictionary-layout">
    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>业务字典 + 业务公式维护</h2>
          <p>复用业务模型，集中维护业务黑话同义词和衍生指标公式，保存后将参与图谱与 Text-to-SQL 映射。</p>
        </div>
        <div class="header-actions">
          <el-button @click="refreshModels">刷新模型</el-button>
          <el-button type="primary" :disabled="!editingModel" @click="saveModel">保存当前模型</el-button>
        </div>
      </div>

      <el-row :gutter="14" class="selector-row">
        <el-col :xs="24" :sm="24" :md="8" :lg="7">
          <el-select v-model="selectedSourceTable" class="full-width" placeholder="请选择数据源" filterable>
            <el-option
              v-for="item in sourceOptions"
              :key="item.tableName"
              :label="item.displayName ? `${item.displayName}（${item.tableName}）` : item.tableName"
              :value="item.tableName"
            />
          </el-select>
        </el-col>
        <el-col :xs="24" :sm="24" :md="10" :lg="10">
          <el-autocomplete
            v-model.trim="editingModelName"
            class="full-width"
            :fetch-suggestions="queryModelSuggestions"
            :trigger-on-focus="true"
            placeholder="输入模型名称（支持下拉选择）"
            @select="handleModelSuggestionSelect"
          >
            <template #default="{ item }">
              <div class="model-suggestion-row">
                <span class="model-suggestion-label">{{ item.value }}</span>
                <span
                  class="model-suggestion-meta"
                  :title="`更新时间：${formatModelTime(item.updatedAt)}`"
                >{{ formatModelTime(item.updatedAt) }}</span>
                <button
                  type="button"
                  class="model-suggestion-delete"
                  @mousedown.prevent
                  @click.stop.prevent="deleteModelFromSuggestion(item)"
                >
                  ×
                </button>
              </div>
            </template>
          </el-autocomplete>
        </el-col>
        <el-col :xs="24" :sm="24" :md="6" :lg="7" class="manage-actions">
          <el-button :loading="creatingModel" @click="createModelForCurrentTable">新建模型</el-button>
          <el-button type="danger" plain :disabled="!editingModel" :loading="deletingModel" @click="deleteCurrentModel">删除模型</el-button>
        </el-col>
      </el-row>

      <el-row :gutter="14" class="filter-row">
        <el-col :xs="24" :sm="24" :md="16" :lg="16">
          <el-input
            v-model.trim="modelSearchInput"
            placeholder="模型列表搜索（按名称）"
            clearable
            @clear="clearModelSearch"
            @keyup.enter="applyModelSearch"
          >
            <template #append>
              <el-button @click="applyModelSearch">搜索</el-button>
            </template>
          </el-input>
        </el-col>
        <el-col :xs="24" :sm="24" :md="8" :lg="8" class="sort-wrap">
          <el-select v-model="modelSortBy" class="full-width" placeholder="排序方式">
            <el-option label="按更新时间（最新优先）" value="updated_desc" />
            <el-option label="按更新时间（最早优先）" value="updated_asc" />
          </el-select>
        </el-col>
      </el-row>

      <el-row :gutter="14" class="tips-row">
        <el-col :span="24">
          <el-alert
            v-if="editingModel"
            type="info"
            :closable="false"
            show-icon
            :title="`当前模型：${editingModel.modelName}，数据表：${editingModel.tableName}`"
          />
          <el-alert
            v-else
            type="warning"
            :closable="false"
            show-icon
            :title="`当前数据源共有 ${filteredSortedModels.length} 个模型，请从下拉选择或输入模型名后新建。`"
          />
        </el-col>
      </el-row>
    </div>

    <div v-if="editingModel" class="workspace-grid dictionary-grid">
      <div class="panel">
        <div class="panel-header">
          <div>
            <h3>业务字典（黑话映射）</h3>
            <p>建议填写业务术语、目标字段和同义词（逗号分隔）。</p>
          </div>
          <el-button size="small" @click="addDictionaryRow">新增词条</el-button>
        </div>

        <el-table :data="dictionaryEntries" height="360" empty-text="暂无业务字典词条">
          <el-table-column label="业务术语" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.term" size="small" placeholder="例如：客单价、复购用户" />
            </template>
          </el-table-column>
          <el-table-column label="目标字段" min-width="130">
            <template #default="{ row }">
              <el-input v-model="row.field" size="small" placeholder="例如：sales_amt、customer_id" />
            </template>
          </el-table-column>
          <el-table-column label="同义词（逗号分隔）" min-width="240">
            <template #default="{ row }">
              <el-input v-model="row.synonyms" size="small" placeholder="例如：成交额,销售额,GMV" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ $index }">
              <el-button size="small" type="danger" link @click="removeDictionaryRow($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <h3>业务公式（衍生指标）</h3>
            <p>公式写法建议使用模型字段名，例如：<code>sales_amt - cost_amt</code>。</p>
          </div>
          <el-button size="small" @click="addMetricRow">新增指标</el-button>
        </div>

        <el-table :data="metricDefinitions" height="360" empty-text="暂无业务公式">
          <el-table-column label="指标名称" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.name" size="small" placeholder="例如：利润、转化率" />
            </template>
          </el-table-column>
          <el-table-column label="字段绑定" min-width="130">
            <template #default="{ row }">
              <el-input v-model="row.field" size="small" placeholder="例如：profit" />
            </template>
          </el-table-column>
          <el-table-column label="聚合方式" width="130">
            <template #default="{ row }">
              <el-select v-model="row.aggregation" size="small">
                <el-option label="SUM" value="SUM" />
                <el-option label="COUNT" value="COUNT" />
                <el-option label="AVG" value="AVG" />
                <el-option label="MAX" value="MAX" />
                <el-option label="MIN" value="MIN" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="公式" min-width="240">
            <template #default="{ row }">
              <el-input v-model="row.formula" size="small" placeholder="例如：sales_amt - cost_amt" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ $index }">
              <el-button size="small" type="danger" link @click="removeMetricRow($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div v-else class="panel">
      <el-empty description="请先选择或创建业务模型开始维护" />
    </div>
  </section>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const props = defineProps({
  focusModelId: {
    type: [String, Number],
    default: null
  }
})

const {
  API_BASE,
  tables,
  loadTables,
  selectedTableName,
  businessModels,
  createBusinessModel,
  loadBusinessModels,
  updateBusinessModel,
  unwrap
} = inject('workbench')

const selectedSourceTable = ref('')
const selectedModelId = ref(null)
const editingModel = ref(null)
const editingModelName = ref('')
const editingRequirement = ref('')
const creatingModel = ref(false)
const deletingModel = ref(false)
const modelSearchInput = ref('')
const modelSearchKeyword = ref('')
const modelSortBy = ref('updated_desc')
const dictionaryEntries = ref([])
const metricDefinitions = ref([])

const sourceOptions = computed(() => Array.isArray(tables.value) ? tables.value : [])
const modelOptions = computed(() => Array.isArray(businessModels.value) ? businessModels.value : [])

const toMillis = (value) => {
  const time = Date.parse(String(value || ''))
  return Number.isNaN(time) ? 0 : time
}

const modelsBySource = computed(() => {
  const tableName = String(selectedSourceTable.value || '').trim()
  if (!tableName) return []
  return modelOptions.value.filter(item => String(item.tableName || '').trim() === tableName)
})

const filteredSortedModels = computed(() => {
  const keyword = String(modelSearchKeyword.value || '').trim().toLowerCase()
  let rows = modelsBySource.value
  if (keyword) {
    rows = rows.filter(item => String(item.modelName || '').toLowerCase().includes(keyword))
  }
  rows = [...rows]
  if (modelSortBy.value === 'updated_asc') {
    rows.sort((a, b) => toMillis(a.updatedAt) - toMillis(b.updatedAt))
  } else {
    rows.sort((a, b) => toMillis(b.updatedAt) - toMillis(a.updatedAt))
  }
  return rows
})

const latestModelForCurrentSource = computed(() => {
  const rows = [...modelsBySource.value]
  rows.sort((a, b) => toMillis(b.updatedAt) - toMillis(a.updatedAt))
  return rows[0] || null
})

const formatModelTime = (value) => {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return date.toLocaleString('zh-CN', { hour12: false })
}

const parseMaybeJson = (value) => {
  if (!value) return {}
  if (typeof value === 'object') return value
  if (typeof value !== 'string') return {}
  try {
    return JSON.parse(value)
  } catch {
    return {}
  }
}

const normalizeDictionaryEntries = (entries) => {
  if (!Array.isArray(entries)) return []
  return entries.map(item => ({
    term: String(item?.term || '').trim(),
    field: String(item?.field || '').trim(),
    synonyms: String(item?.synonyms || '').trim()
  }))
}

const normalizeMetricDefinitions = (entries) => {
  if (!Array.isArray(entries)) return []
  return entries.map(item => ({
    name: String(item?.name || '').trim(),
    field: String(item?.field || '').trim(),
    aggregation: String(item?.aggregation || 'SUM').trim().toUpperCase() || 'SUM',
    formula: String(item?.formula || '').trim()
  }))
}

const clearEditor = () => {
  selectedModelId.value = null
  editingModel.value = null
  editingRequirement.value = ''
  dictionaryEntries.value = []
  metricDefinitions.value = []
}

const applyModel = (model) => {
  if (!model) {
    clearEditor()
    return
  }
  selectedModelId.value = model.id
  editingModel.value = model
  editingModelName.value = String(model.modelName || '').trim()
  editingRequirement.value = String(model.modelRequirement || '').trim()
  const json = parseMaybeJson(model.modelJson)
  dictionaryEntries.value = normalizeDictionaryEntries(json.dictionaryEntries)
  metricDefinitions.value = normalizeMetricDefinitions(json.metricDefinitions)
}

const selectLatestModel = () => {
  const latest = latestModelForCurrentSource.value
  if (latest) {
    applyModel(latest)
    return
  }
  clearEditor()
}

const loadModelById = (modelId) => {
  const matched = modelOptions.value.find(item => String(item.id) === String(modelId))
  if (matched) {
    applyModel(matched)
    return
  }
  clearEditor()
}

const refreshModels = async () => {
  await loadBusinessModels()
  if (selectedModelId.value) {
    loadModelById(selectedModelId.value)
    return
  }
  selectLatestModel()
}

const queryModelSuggestions = (queryString, cb) => {
  const keyword = String(queryString || '').trim().toLowerCase()
  const list = filteredSortedModels.value
    .filter(item => !keyword || String(item.modelName || '').toLowerCase().includes(keyword))
    .slice(0, 100)
    .map(item => ({ ...item, value: String(item.modelName || '') }))
  cb(list)
}

const handleModelSuggestionSelect = (item) => {
  if (item?.id != null) {
    loadModelById(item.id)
  }
}

const applyModelSearch = () => {
  modelSearchKeyword.value = String(modelSearchInput.value || '').trim()
}

const clearModelSearch = () => {
  modelSearchInput.value = ''
  modelSearchKeyword.value = ''
}

const addDictionaryRow = () => {
  if (!editingModel.value) {
    ElMessage.warning('请先选择或创建模型')
    return
  }
  dictionaryEntries.value = [
    ...dictionaryEntries.value,
    { term: '', field: '', synonyms: '' }
  ]
}

const removeDictionaryRow = (index) => {
  dictionaryEntries.value = dictionaryEntries.value.filter((_, i) => i !== index)
}

const addMetricRow = () => {
  if (!editingModel.value) {
    ElMessage.warning('请先选择或创建模型')
    return
  }
  metricDefinitions.value = [
    ...metricDefinitions.value,
    { name: '', field: '', aggregation: 'SUM', formula: '' }
  ]
}

const removeMetricRow = (index) => {
  metricDefinitions.value = metricDefinitions.value.filter((_, i) => i !== index)
}

const saveModel = async () => {
  if (!editingModel.value?.id) {
    ElMessage.warning('请先选择业务模型')
    return
  }
  const modelName = String(editingModelName.value || '').trim()
  if (!modelName) {
    ElMessage.warning('模型名称不能为空')
    return
  }
  const payload = {
    modelName,
    modelRequirement: String(editingRequirement.value || '').trim(),
    dictionaryEntries: dictionaryEntries.value,
    metricDefinitions: metricDefinitions.value
  }
  await updateBusinessModel(editingModel.value.id, payload)
  ElMessage.success('业务字典与业务公式已保存')
  await loadBusinessModels()
  loadModelById(editingModel.value.id)
}

const createModelForCurrentTable = async () => {
  const tableName = String(selectedSourceTable.value || selectedTableName.value || '').trim()
  if (!tableName) {
    ElMessage.warning('请先选择数据源')
    return
  }
  const modelName = String(editingModelName.value || '').trim()
  if (modelName) {
    const duplicated = modelsBySource.value.find(item => String(item.modelName || '').trim().toLowerCase() === modelName.toLowerCase())
    if (duplicated) {
      ElMessage.warning('当前数据源下已存在同名模型，请修改后再新建')
      loadModelById(duplicated.id)
      return
    }
  }

  creatingModel.value = true
  try {
    const beforeIds = new Set(modelOptions.value.map(item => String(item.id)))
    await createBusinessModel({
      tableName,
      requirement: `基于${tableName}的业务分析模型`,
      modelName: modelName || undefined,
      silentSuccess: true
    })
    await loadBusinessModels()
    const created = modelOptions.value.find(item => !beforeIds.has(String(item.id)))
      || latestModelForCurrentSource.value
    if (created?.id != null) {
      await updateBusinessModel(created.id, {
        modelName: String(created.modelName || modelName || '').trim(),
        modelRequirement: String(created.modelRequirement || `基于${tableName}的业务分析模型`).trim(),
        dictionaryEntries: [],
        metricDefinitions: []
      })
      await loadBusinessModels()
      loadModelById(created.id)
    }
    ElMessage.success(created?.modelName ? `已新建模型：${created.modelName}` : '已新建业务模型')
  } finally {
    creatingModel.value = false
  }
}

const deleteModelById = async (modelId) => {
  const model = modelOptions.value.find(item => String(item.id) === String(modelId))
  if (!model) {
    return
  }
  const confirmed = window.confirm(`确认删除模型「${model.modelName || model.id}」吗？`)
  if (!confirmed) {
    return
  }

  deletingModel.value = true
  try {
    await axios.post(`${API_BASE}/api/data/business-models/${model.id}/delete`).then(unwrap)
    await loadBusinessModels()
    selectLatestModel()
    ElMessage.success('模型已删除')
  } finally {
    deletingModel.value = false
  }
}

const deleteCurrentModel = async () => {
  if (!editingModel.value?.id) {
    ElMessage.warning('请先选择要删除的模型')
    return
  }
  await deleteModelById(editingModel.value.id)
}

const deleteModelFromSuggestion = async (item) => {
  if (!item?.id) {
    return
  }
  await deleteModelById(item.id)
}

onMounted(async () => {
  if (!Array.isArray(tables.value) || tables.value.length === 0) {
    await loadTables({ keepCurrentSelection: true })
  }
  if (!Array.isArray(businessModels.value) || businessModels.value.length === 0) {
    await loadBusinessModels()
  }
  selectedSourceTable.value = String(selectedTableName.value || sourceOptions.value[0]?.tableName || '').trim()
  if (props.focusModelId == null || props.focusModelId === '') {
    selectLatestModel()
  }
})

watch(selectedSourceTable, (tableName) => {
  const next = String(tableName || '').trim()
  if (!next) {
    clearEditor()
    return
  }
  if (selectedTableName.value !== next) {
    selectedTableName.value = next
  }
  const currentInSource = editingModel.value && String(editingModel.value.tableName || '') === next
  if (!currentInSource) {
    selectLatestModel()
  }
})

watch(() => props.focusModelId, (nextId) => {
  if (nextId == null || nextId === '') {
    return
  }
  const matched = modelOptions.value.find(item => String(item.id) === String(nextId))
  if (!matched) {
    return
  }
  selectedSourceTable.value = String(matched.tableName || '')
  applyModel(matched)
}, { immediate: true })
</script>

<style scoped>
.dictionary-layout {
  display: grid;
  gap: 14px;
}

.selector-row {
  margin-top: 10px;
}

.filter-row {
  margin-top: 10px;
}

.tips-row {
  margin-top: 10px;
}

.manage-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.manage-actions :deep(.el-button) {
  margin-left: 0;
}

.sort-wrap {
  display: flex;
  justify-content: flex-end;
}

.header-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.dictionary-grid {
  grid-template-columns: 1fr 1fr;
}

.full-width {
  width: 100%;
}

.model-suggestion-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.model-suggestion-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-suggestion-meta {
  color: #9ca3af;
  font-size: 12px;
}

.model-suggestion-delete {
  border: 0;
  background: transparent;
  color: #ef4444;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  padding: 0;
}

@media (max-width: 1100px) {
  .dictionary-grid {
    grid-template-columns: 1fr;
  }

  .header-actions,
  .manage-actions,
  .sort-wrap {
    justify-content: flex-start;
  }
}
</style>
