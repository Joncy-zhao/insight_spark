<template>
  <section class="dictionary-layout">
    <div class="panel">
      <div class="panel-header">
        <div>
          <h2 v-if="showTitle">业务字典 + 业务公式维护</h2>
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
                  class="model-suggestion-source"
                  :title="item.tableLabel"
                >{{ item.tableLabel }}</span>
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
          <el-button :loading="creatingModel" @click="handleCreateModelClick">新建模型</el-button>
          <el-button type="danger" plain :disabled="!editingModel" :loading="deletingModel" @click="deleteCurrentModel">删除模型</el-button>
        </el-col>
      </el-row>

      <el-row :gutter="14" class="filter-row">
        <el-col :xs="24" :sm="24" :md="16" :lg="16">
          <el-autocomplete
            v-model.trim="modelSearchInput"
            class="full-width"
            :fetch-suggestions="queryModelSearchSuggestions"
            :trigger-on-focus="true"
            placeholder="模型列表搜索（按名称）"
            clearable
            @clear="clearModelSearch"
            @select="handleModelSearchSuggestionSelect"
            @keyup.enter="applyModelSearch"
          >
            <template #default="{ item }">
              <div class="model-suggestion-row">
                <span class="model-suggestion-label">{{ item.value }}</span>
                <span
                  class="model-suggestion-source"
                  :title="item.tableLabel"
                >{{ item.tableLabel }}</span>
                <span
                  class="model-suggestion-meta"
                  :title="`更新时间：${formatModelTime(item.updatedAt)}`"
                >{{ formatModelTime(item.updatedAt) }}</span>
              </div>
            </template>
            <template #append>
              <el-button @click="applyModelSearch">搜索</el-button>
            </template>
          </el-autocomplete>
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

      <el-dialog
        v-model="createDialogVisible"
        title="新建业务模型"
        width="92vw"
        class="create-model-dialog"
        destroy-on-close
        @closed="resetCreateForm"
      >
        <el-form label-position="top" class="create-dialog-form">
          <el-form-item label="数据源">
            <el-select v-model="createForm.tableName" class="full-width" placeholder="请选择数据源" filterable>
              <el-option
                v-for="item in sourceOptions"
                :key="item.tableName"
                :label="item.displayName ? `${item.displayName}（${item.tableName}）` : item.tableName"
                :value="item.tableName"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="模型名称">
            <el-input v-model.trim="createForm.modelName" placeholder="请输入新模型名称" />
          </el-form-item>
        </el-form>

        <el-row :gutter="12" class="create-dialog-grid">
          <el-col :xs="24" :md="12">
            <div class="dialog-section-header">
              <span class="dialog-section-title">行业字典</span>
              <el-button size="small" @click="addCreateDictionaryRow">新增词条</el-button>
            </div>
            <el-table
              :data="createForm.dictionaryEntries"
              height="280"
              empty-text="暂无行业字典"
              table-layout="fixed"
            >
              <el-table-column label="行业术语" min-width="120">
                <template #default="{ row }">
                  <el-input v-model="row.term" size="small" placeholder="例如：客单价、复购用户" />
                </template>
              </el-table-column>
              <el-table-column label="目标字段" min-width="110">
                <template #default="{ row }">
                  <el-input v-model="row.field" size="small" placeholder="例如：sales_amt、customer_id" />
                </template>
              </el-table-column>
              <el-table-column label="同义词" min-width="140">
                <template #default="{ row }">
                  <el-input v-model="row.synonyms" size="small" placeholder="例如：成交额,销售额" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="60">
                <template #default="{ $index }">
                  <el-button size="small" type="danger" link @click="removeCreateDictionaryRow($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :xs="24" :md="12">
            <div class="dialog-section-header">
              <span class="dialog-section-title">业务公式</span>
              <el-button size="small" @click="addCreateMetricRow">新增指标</el-button>
            </div>
            <el-table
              :data="createForm.metricDefinitions"
              height="280"
              empty-text="暂无业务公式"
              table-layout="fixed"
            >
              <el-table-column label="指标名称" min-width="120">
                <template #default="{ row }">
                  <el-input v-model="row.name" size="small" placeholder="例如：利润、转化率" />
                </template>
              </el-table-column>
              <el-table-column label="字段绑定" min-width="110">
                <template #default="{ row }">
                  <el-input v-model="row.field" size="small" placeholder="例如：profit" />
                </template>
              </el-table-column>
              <el-table-column label="聚合方式" width="100">
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
              <el-table-column label="公式" min-width="140">
                <template #default="{ row }">
                  <el-input v-model="row.formula" size="small" placeholder="例如：sales_amt - cost_amt" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="60">
                <template #default="{ $index }">
                  <el-button size="small" type="danger" link @click="removeCreateMetricRow($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
        </el-row>

        <template #footer>
          <el-button @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="createDialogLoading" @click="confirmCreateDialog">新建</el-button>
        </template>
      </el-dialog>
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

        <el-table :data="dictionaryEntries" height="360" empty-text="暂无业务字典词条" table-layout="fixed">
          <el-table-column label="业务术语" min-width="120">
            <template #default="{ row }">
              <el-input v-model="row.term" size="small" placeholder="例如：客单价、复购用户" />
            </template>
          </el-table-column>
          <el-table-column label="目标字段" min-width="110">
            <template #default="{ row }">
              <el-input v-model="row.field" size="small" placeholder="例如：sales_amt、customer_id" />
            </template>
          </el-table-column>
          <el-table-column label="同义词（逗号分隔）" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.synonyms" size="small" placeholder="例如：成交额,销售额,GMV" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
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

        <el-table :data="metricDefinitions" height="360" empty-text="暂无业务公式" table-layout="fixed">
          <el-table-column label="指标名称" min-width="120">
            <template #default="{ row }">
              <el-input v-model="row.name" size="small" placeholder="例如：利润、转化率" />
            </template>
          </el-table-column>
          <el-table-column label="字段绑定" min-width="110">
            <template #default="{ row }">
              <el-input v-model="row.field" size="small" placeholder="例如：profit" />
            </template>
          </el-table-column>
          <el-table-column label="聚合方式" width="110">
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
          <el-table-column label="公式" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.formula" size="small" placeholder="例如：sales_amt - cost_amt" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
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
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const props = defineProps({
  focusModelId: {
    type: [String, Number],
    default: null
  },
  showTitle: {
    type: Boolean,
    default: true
  },
  autoRenameOnDuplicateCreate: {
    type: Boolean,
    default: false
  },
  useCreateDialog: {
    type: Boolean,
    default: false
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
const createDialogVisible = ref(false)
const createDialogLoading = ref(false)
const pendingFocusModelId = ref(null)
const createForm = reactive({
  tableName: '',
  modelName: '',
  dictionaryEntries: [],
  metricDefinitions: []
})

const sourceOptions = computed(() => Array.isArray(tables.value) ? tables.value : [])
const modelOptions = computed(() => Array.isArray(businessModels.value) ? businessModels.value : [])

const toMillis = (value) => {
  const time = Date.parse(String(value || ''))
  return Number.isNaN(time) ? 0 : time
}

const resolveTableLabel = (tableName) => {
  const name = String(tableName || '').trim()
  if (!name) return ''
  const matched = sourceOptions.value.find(item => String(item.tableName || '').trim() === name)
  if (!matched) return name
  return matched.displayName ? `${matched.displayName}（${matched.tableName}）` : matched.tableName
}

const sortModelsByUpdated = (rows) => {
  const sorted = [...rows]
  if (modelSortBy.value === 'updated_asc') {
    sorted.sort((a, b) => toMillis(a.updatedAt) - toMillis(b.updatedAt))
  } else {
    sorted.sort((a, b) => toMillis(b.updatedAt) - toMillis(a.updatedAt))
  }
  return sorted
}

const modelsBySource = computed(() => {
  const tableName = String(selectedSourceTable.value || '').trim()
  if (!tableName) return []
  return modelOptions.value.filter(item => String(item.tableName || '').trim() === tableName)
})

const getModelsByTable = (tableName) => {
  const name = String(tableName || '').trim()
  if (!name) return []
  return modelOptions.value.filter(item => String(item.tableName || '').trim() === name)
}

const buildUniqueModelName = (name, sourceModels = modelsBySource.value) => {
  const baseName = String(name || '').trim()
  if (!baseName) return ''
  const existingNames = new Set(
    sourceModels
      .map(item => String(item.modelName || '').trim().toLowerCase())
      .filter(Boolean)
  )
  if (!existingNames.has(baseName.toLowerCase())) return baseName
  let index = 1
  let candidate = `${baseName}_新建`
  while (existingNames.has(candidate.toLowerCase())) {
    index += 1
    candidate = `${baseName}_新建${index}`
  }
  return candidate
}

const resolveDuplicateCreateName = (tableName, modelName) => {
  const inputName = String(modelName || '').trim()
  if (!inputName) return { name: '' }
  const sourceModels = getModelsByTable(tableName)
  const duplicated = sourceModels.find(item => String(item.modelName || '').trim().toLowerCase() === inputName.toLowerCase())
  if (!duplicated) return { name: inputName }
  if (props.autoRenameOnDuplicateCreate) {
    const uniqueName = buildUniqueModelName(inputName, sourceModels)
    return { name: uniqueName, renamed: uniqueName !== inputName }
  }
  return { name: '', blocked: true, existing: duplicated }
}

const filteredSortedModels = computed(() => {
  const keyword = String(modelSearchKeyword.value || '').trim().toLowerCase()
  let rows = modelsBySource.value
  if (keyword) {
    rows = rows.filter(item => String(item.modelName || '').toLowerCase().includes(keyword))
  }
  return sortModelsByUpdated(rows)
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
  editingModelName.value = ''
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
    pendingFocusModelId.value = null
    return
  }
  clearEditor()
}

const queueOrApplyFocusModel = (modelId, options = {}) => {
  const normalizedId = modelId == null ? '' : String(modelId).trim()
  if (!normalizedId) {
    pendingFocusModelId.value = null
    return false
  }
  const matched = modelOptions.value.find(item => String(item.id) === normalizedId)
  if (matched) {
    selectedSourceTable.value = String(matched.tableName || '').trim()
    applyModel(matched)
    pendingFocusModelId.value = null
    return true
  }
  if (options.defer !== false) {
    pendingFocusModelId.value = normalizedId
  }
  return false
}

const tryConsumePendingFocusModel = () => {
  const pendingId = pendingFocusModelId.value == null ? '' : String(pendingFocusModelId.value).trim()
  if (!pendingId) return
  const matched = modelOptions.value.find(item => String(item.id) === pendingId)
  if (!matched) return
  selectedSourceTable.value = String(matched.tableName || '').trim()
  applyModel(matched)
  pendingFocusModelId.value = null
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
  const list = sortModelsByUpdated(
    modelsBySource.value
      .filter(item => String(item.status || '').toUpperCase() === 'ACTIVE')
      .filter(item => !keyword || String(item.modelName || '').toLowerCase().includes(keyword))
  )
    .slice(0, 100)
    .map(item => ({
      ...item,
      value: String(item.modelName || ''),
      tableLabel: resolveTableLabel(item.tableName)
    }))
  cb(list)
}

const handleModelSuggestionSelect = (item) => {
  if (item?.id != null) {
    if (item?.tableName != null) {
      selectedSourceTable.value = String(item.tableName || '').trim()
    }
    loadModelById(item.id)
  }
}

const queryModelSearchSuggestions = (queryString, cb) => {
  const keyword = String(queryString || '').trim().toLowerCase()
  const list = sortModelsByUpdated(
    modelOptions.value
      .filter(item => String(item.status || '').toUpperCase() === 'ACTIVE')
      .filter(item => !keyword || String(item.modelName || '').toLowerCase().includes(keyword))
  )
    .slice(0, 100)
    .map(item => ({
      ...item,
      value: String(item.modelName || ''),
      tableLabel: resolveTableLabel(item.tableName)
    }))
  cb(list)
}

const handleModelSearchSuggestionSelect = (item) => {
  if (!item) {
    return
  }
  modelSearchInput.value = String(item.value || item.modelName || '').trim()
  modelSearchKeyword.value = modelSearchInput.value
  if (item?.id != null) {
    if (item?.tableName != null) {
      selectedSourceTable.value = String(item.tableName || '').trim()
    }
    loadModelById(item.id)
  }
}

const applyModelSearch = () => {
  const keyword = String(modelSearchInput.value || '').trim()
  modelSearchKeyword.value = keyword
  if (!keyword) {
    return
  }
  const matched = sortModelsByUpdated(
    modelOptions.value
      .filter(item => String(item.status || '').toUpperCase() === 'ACTIVE')
      .filter(item => String(item.modelName || '').toLowerCase().includes(keyword.toLowerCase()))
  )[0]
  if (matched?.id != null) {
    if (matched?.tableName != null) {
      selectedSourceTable.value = String(matched.tableName || '').trim()
    }
    loadModelById(matched.id)
  } else {
    ElMessage.warning('未找到匹配模型')
  }
}

const clearModelSearch = () => {
  modelSearchInput.value = ''
  modelSearchKeyword.value = ''
}

const resetCreateForm = () => {
  createForm.tableName = ''
  createForm.modelName = ''
  createForm.dictionaryEntries = []
  createForm.metricDefinitions = []
}

const openCreateDialog = () => {
  resetCreateForm()
  createForm.tableName = String(selectedSourceTable.value || selectedTableName.value || sourceOptions.value[0]?.tableName || '').trim()
  createDialogVisible.value = true
}

const addCreateDictionaryRow = () => {
  createForm.dictionaryEntries = [
    ...createForm.dictionaryEntries,
    { term: '', field: '', synonyms: '' }
  ]
}

const removeCreateDictionaryRow = (index) => {
  createForm.dictionaryEntries = createForm.dictionaryEntries.filter((_, i) => i !== index)
}

const addCreateMetricRow = () => {
  createForm.metricDefinitions = [
    ...createForm.metricDefinitions,
    { name: '', field: '', aggregation: 'SUM', formula: '' }
  ]
}

const removeCreateMetricRow = (index) => {
  createForm.metricDefinitions = createForm.metricDefinitions.filter((_, i) => i !== index)
}

const handleCreateModelClick = () => {
  if (props.useCreateDialog) {
    openCreateDialog()
    return
  }
  createModelForCurrentTable()
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

const createModelAndSelect = async ({ tableName, modelName, dictionaryEntries, metricDefinitions }) => {
  const beforeIds = new Set(getModelsByTable(tableName).map(item => String(item.id)))
  const createdResult = await createBusinessModel({
    tableName,
    requirement: `基于${tableName}的业务分析模型`,
    modelName: modelName || undefined,
    silentSuccess: true
  })
  await loadBusinessModels()
  const createdId = createdResult?.id == null ? null : String(createdResult.id)
  let created = createdId
    ? modelOptions.value.find(item => String(item.id) === createdId)
    : null
  const expectedName = String(createdResult?.modelName || modelName || '').trim()
  if (!created && expectedName) {
    created = modelOptions.value.find(item =>
      String(item.tableName || '') === tableName && String(item.modelName || '').trim() === expectedName
    )
  }
  if (!created) {
    const candidates = getModelsByTable(tableName).filter(item => !beforeIds.has(String(item.id)))
    if (candidates.length === 1) {
      created = candidates[0]
    }
  }
  if (created?.id != null) {
    const shouldInitialize = createdId
      ? String(created.id) === createdId
      : Boolean(expectedName && String(created.modelName || '').trim() === expectedName)
    if (shouldInitialize) {
      await updateBusinessModel(created.id, {
        modelName: String(created.modelName || modelName || '').trim(),
        modelRequirement: String(created.modelRequirement || `基于${tableName}的业务分析模型`).trim(),
        dictionaryEntries: normalizeDictionaryEntries(dictionaryEntries),
        metricDefinitions: normalizeMetricDefinitions(metricDefinitions)
      })
      await loadBusinessModels()
    }
    if (selectedSourceTable.value !== tableName) {
      selectedSourceTable.value = tableName
    }
    loadModelById(created.id)
  } else {
    ElMessage.warning('新建模型成功，但未能定位到新模型，请手动选择')
    clearEditor()
  }
  ElMessage.success(created?.modelName ? `已新建模型：${created.modelName}` : '已新建业务模型')
  return created
}

const confirmCreateDialog = async () => {
  const tableName = String(createForm.tableName || '').trim()
  if (!tableName) {
    ElMessage.warning('请选择数据源')
    return
  }
  const modelName = String(createForm.modelName || '').trim()
  if (!modelName) {
    ElMessage.warning('请输入模型名称')
    return
  }
  const resolved = resolveDuplicateCreateName(tableName, modelName)
  if (resolved.blocked) {
    ElMessage.warning('当前数据源下已存在同名模型，请修改后再新建')
    if (resolved.existing?.id != null) {
      selectedSourceTable.value = tableName
      loadModelById(resolved.existing.id)
    }
    return
  }
  if (resolved.renamed) {
    ElMessage.info(`已存在同名模型，将新建为「${resolved.name}」`)
  }

  createDialogLoading.value = true
  try {
    const created = await createModelAndSelect({
      tableName,
      modelName: resolved.name,
      dictionaryEntries: createForm.dictionaryEntries,
      metricDefinitions: createForm.metricDefinitions
    })
    if (created?.id != null) {
      createDialogVisible.value = false
    }
  } finally {
    createDialogLoading.value = false
  }
}

const createModelForCurrentTable = async () => {
  const tableName = String(selectedSourceTable.value || selectedTableName.value || '').trim()
  if (!tableName) {
    ElMessage.warning('请先选择数据源')
    return
  }
  const currentModelName = String(editingModel.value?.modelName || '').trim()
  const inputModelName = String(editingModelName.value || '').trim()
  const isCurrentModelNameInSameTable = Boolean(
    editingModel.value
      && String(editingModel.value.tableName || '').trim() === tableName
      && inputModelName
      && inputModelName === currentModelName
  )
  const modelName = isCurrentModelNameInSameTable ? '' : inputModelName
  const resolved = resolveDuplicateCreateName(tableName, modelName)
  if (resolved.blocked) {
    ElMessage.warning('当前数据源下已存在同名模型，请修改后再新建')
    if (resolved.existing?.id != null) {
      loadModelById(resolved.existing.id)
    }
    return
  }
  if (resolved.renamed) {
    ElMessage.info(`已存在同名模型，将新建为「${resolved.name}」`)
  }
  creatingModel.value = true
  try {
    await createModelAndSelect({
      tableName,
      modelName: resolved.name,
      dictionaryEntries: [],
      metricDefinitions: []
    })
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
  } else {
    queueOrApplyFocusModel(props.focusModelId)
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
  queueOrApplyFocusModel(nextId)
}, { immediate: true })

watch(modelOptions, () => {
  tryConsumePendingFocusModel()
}, { deep: true })
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
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.dictionary-grid > .panel {
  min-width: 0;
}

:deep(.create-model-dialog) {
  max-width: 1280px;
}

:deep(.create-model-dialog .el-dialog__body) {
  padding-top: 10px;
}

.create-dialog-form {
  margin-bottom: 6px;
}

.create-dialog-grid {
  margin-top: 12px;
}

.dialog-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.dialog-section-title {
  font-weight: 600;
  color: #111827;
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

.model-suggestion-source {
  color: #6b7280;
  font-size: 12px;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
