<template>
  <section class="dictionary-layout">
    <div class="panel">
      <div class="panel-header">
        <div>
          <h2 v-if="showTitle">业务字典 + 业务公式维护</h2>
          <p>复用业务模型，集中维护业务黑话同义词和衍生指标公式，保存后将参与图谱与 Text-to-SQL 映射。</p>
        </div>
        <div class="header-actions">
          <el-button :disabled="savingModel" @click="refreshModels">刷新模型</el-button>
          <el-button
            type="primary"
            :disabled="!editingModel"
            :loading="savingModel"
            @click="saveModel"
          >
            {{ savingModel ? '正在保存' : '保存当前模型' }}
          </el-button>
          <el-button
            v-if="editingModel"
            :type="isCurrentModelPublished ? 'warning' : 'success'"
            plain
            :disabled="savingModel"
            @click="toggleCurrentModelPublish"
          >
            {{ isCurrentModelPublished ? '取消发布' : '发布到企业模型库' }}
          </el-button>
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
          <el-button :disabled="savingModel" :loading="creatingModel" @click="handleCreateModelClick">新建模型</el-button>
          <el-button type="danger" plain :disabled="!editingModel || savingModel" :loading="deletingModel" @click="deleteCurrentModel">删除模型</el-button>
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
                  <el-select
                    v-model="row.field"
                    size="small"
                    class="full-width"
                    filterable
                    clearable
                    default-first-option
                    placeholder="请选择字段"
                  >
                    <el-option
                      v-for="field in createDialogFieldOptions"
                      :key="field.columnName"
                      :label="field.optionLabel"
                      :value="field.columnName"
                    />
                  </el-select>
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
                  <el-select
                    v-model="row.field"
                    size="small"
                    class="full-width"
                    filterable
                    clearable
                    default-first-option
                    placeholder="请选择字段"
                  >
                    <el-option
                      v-for="field in createDialogFieldOptions"
                      :key="field.columnName"
                      :label="field.optionLabel"
                      :value="field.columnName"
                    />
                  </el-select>
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
              <el-select
                v-model="row.field"
                size="small"
                class="full-width"
                filterable
                clearable
                default-first-option
                placeholder="请选择字段"
              >
                <el-option
                  v-for="field in editorFieldOptions"
                  :key="field.columnName"
                  :label="field.optionLabel"
                  :value="field.columnName"
                />
              </el-select>
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
              <el-select
                v-model="row.field"
                size="small"
                class="full-width"
                filterable
                clearable
                default-first-option
                placeholder="请选择字段"
              >
                <el-option
                  v-for="field in editorFieldOptions"
                  :key="field.columnName"
                  :label="field.optionLabel"
                  :value="field.columnName"
                />
              </el-select>
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

      <div class="panel dimension-panel">
        <div class="panel-header">
          <div>
            <h3>业务维度</h3>
            <p>维护维度名称与模型字段绑定，保存后参与分组、筛选和对比查询。</p>
          </div>
          <el-button size="small" @click="addDimensionRow">新增维度</el-button>
        </div>

        <el-table :data="dimensionSystem" height="240" empty-text="暂无业务维度" table-layout="fixed">
          <el-table-column label="维度名称" min-width="120">
            <template #default="{ row }">
              <el-input v-model="row.name" size="small" placeholder="例如：城市、省份、区域" />
            </template>
          </el-table-column>
          <el-table-column label="字段绑定" min-width="140">
            <template #default="{ row }">
              <el-select
                v-model="row.field"
                size="small"
                class="full-width"
                filterable
                clearable
                default-first-option
                placeholder="请选择字段"
              >
                <el-option
                  v-for="field in editorFieldOptions"
                  :key="field.columnName"
                  :label="field.optionLabel"
                  :value="field.columnName"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }">
              <el-button size="small" type="danger" link @click="removeDimensionRow($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div v-else class="panel">
      <el-empty description="请先选择或创建业务模型开始维护" />
    </div>

    <div class="panel enterprise-panel">
      <div class="panel-header">
        <div>
          <h3>企业模型库</h3>
          <p>已发布模型可被其他用户直接套用到当前数据源，再继续调整业务字典、公式和参数。</p>
        </div>
      </div>
      <div class="enterprise-toolbar">
        <el-input
          v-model.trim="enterpriseKeyword"
          class="full-width"
          placeholder="按模型名称或来源数据源搜索企业模型"
          clearable
        />
      </div>
      <el-table
        :data="filteredEnterpriseModels"
        height="260"
        empty-text="暂无已发布的企业模型"
        table-layout="fixed"
      >
        <el-table-column label="模型名称" min-width="170">
          <template #default="{ row }">
            <div class="enterprise-model-name">{{ row.modelName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="来源数据源" min-width="170">
          <template #default="{ row }">
            <span>{{ resolveTableLabel(row.tableName) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            <span>{{ formatModelTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <div class="enterprise-actions">
              <el-button size="small" @click="previewEnterpriseModel(row)">查看</el-button>
              <el-button size="small" type="primary" @click="applyEnterpriseModelToCurrentTable(row)">套用到当前数据源</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
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
  fields,
  loadTables,
  selectedTableName,
  businessModels,
  enterpriseModels,
  createBusinessModel,
  loadBusinessModels,
  publishBusinessModel,
  applyBusinessModel,
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
const savingModel = ref(false)
const modelSearchInput = ref('')
const modelSearchKeyword = ref('')
const modelSortBy = ref('updated_desc')
const enterpriseKeyword = ref('')
const dictionaryEntries = ref([])
const metricDefinitions = ref([])
const dimensionSystem = ref([])
const createDialogVisible = ref(false)
const createDialogLoading = ref(false)
const pendingFocusModelId = ref(null)
const tableFieldOptionsMap = ref({})
const createForm = reactive({
  tableName: '',
  modelName: '',
  dictionaryEntries: [],
  metricDefinitions: []
})

const sourceOptions = computed(() => Array.isArray(tables.value) ? tables.value : [])
const modelOptions = computed(() => Array.isArray(businessModels.value) ? businessModels.value : [])
const enterpriseModelOptions = computed(() => Array.isArray(enterpriseModels?.value) ? enterpriseModels.value : [])

const normalizeFieldOptionLabel = (field) => {
  const columnName = String(field?.columnName || '').trim()
  const displayName = String(field?.displayName || '').trim()
  const sourceFieldName = String(field?.sourceFieldName || '').trim()
  const preferredName = displayName || sourceFieldName || columnName
  if (!preferredName) return ''
  if (!columnName || preferredName === columnName) {
    return preferredName
  }
  return `${preferredName}（${columnName}）`
}

const normalizeFieldOptions = (items) => {
  if (!Array.isArray(items)) return []
  return items
    .map((item) => {
      const columnName = String(item?.columnName || '').trim()
      if (!columnName) return null
      return {
        columnName,
        displayName: String(item?.displayName || '').trim(),
        sourceFieldName: String(item?.sourceFieldName || '').trim(),
        optionLabel: normalizeFieldOptionLabel(item)
      }
    })
    .filter(Boolean)
}

const cacheFieldOptions = (tableName, items) => {
  const name = String(tableName || '').trim()
  if (!name) return
  tableFieldOptionsMap.value = {
    ...tableFieldOptionsMap.value,
    [name]: normalizeFieldOptions(items)
  }
}

const resolveFieldOptions = (tableName) => {
  const name = String(tableName || '').trim()
  if (!name) return []
  return Array.isArray(tableFieldOptionsMap.value[name]) ? tableFieldOptionsMap.value[name] : []
}

const editorFieldOptions = computed(() => {
  const tableName = String(editingModel.value?.tableName || selectedSourceTable.value || '').trim()
  return resolveFieldOptions(tableName)
})

const createDialogFieldOptions = computed(() => resolveFieldOptions(createForm.tableName))

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

const isCurrentModelPublished = computed(() => Boolean(editingModel.value?.published))

const filteredEnterpriseModels = computed(() => {
  const keyword = String(enterpriseKeyword.value || '').trim().toLowerCase()
  const currentUserModelId = String(editingModel.value?.id || '')
  return sortModelsByUpdated(
    enterpriseModelOptions.value
      .filter(item => String(item.status || '').toUpperCase() === 'ACTIVE')
      .filter(item => !currentUserModelId || String(item.id) !== currentUserModelId || Boolean(item.published))
      .filter((item) => {
        if (!keyword) return true
        const tableLabel = resolveTableLabel(item.tableName).toLowerCase()
        return String(item.modelName || '').toLowerCase().includes(keyword) || tableLabel.includes(keyword)
      })
  )
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
    field: extractModelFieldRef(item),
    synonyms: String(item?.synonyms || '').trim()
  }))
}

const normalizeMetricDefinitions = (entries) => {
  if (!Array.isArray(entries)) return []
  return entries.map(item => ({
    name: String(item?.name || '').trim(),
    field: extractModelFieldRef(item),
    aggregation: String(item?.aggregation || 'SUM').trim().toUpperCase() || 'SUM',
    formula: formatFormulaForDisplay(item?.formula)
  }))
}

const extractModelFieldRef = (item) => {
  const keys = ['field', 'columnName', 'sourceFieldName', 'fieldName', 'dimensionField', 'targetField']
  for (const key of keys) {
    const value = String(item?.[key] || '').trim()
    if (value) return value
  }
  return ''
}

const normalizeDimensionSystem = (entries) => {
  if (!Array.isArray(entries)) return []
  return entries.map(item => ({
    name: String(item?.name || item?.term || '').trim(),
    field: extractModelFieldRef(item)
  })).filter(item => item.name || item.field)
}

const resolveFieldLabel = (columnName) => {
  const name = String(columnName || '').trim()
  if (!name) return ''
  const matched = editorFieldOptions.value.find(item => String(item.columnName || '').trim() === name)
  if (!matched) return name
  return String(matched.displayName || matched.sourceFieldName || matched.columnName || '').trim() || name
}

const formatFormulaForDisplay = (formula) => {
  const source = String(formula || '').trim()
  if (!source) return ''
  return source.replace(/\b[A-Za-z_][A-Za-z0-9_]*\b/g, (token) => resolveFieldLabel(token) || token)
}

const rewriteFormulaToColumnNames = (formula) => {
  const source = String(formula || '').trim()
  if (!source) return ''
  const options = [...editorFieldOptions.value].sort((a, b) => {
    const left = String(a.displayName || a.sourceFieldName || a.columnName || '').length
    const right = String(b.displayName || b.sourceFieldName || b.columnName || '').length
    return right - left
  })
  let result = source
  options.forEach((field) => {
    const aliases = [
      String(field.displayName || '').trim(),
      String(field.sourceFieldName || '').trim(),
      String(field.columnName || '').trim()
    ].filter(Boolean)
    aliases.forEach((alias) => {
      result = result.replace(new RegExp(`(?<![A-Za-z0-9_\\u4e00-\\u9fa5])${alias.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\$&')}(?![A-Za-z0-9_\\u4e00-\\u9fa5])`, 'g'), field.columnName)
    })
  })
  return result
}

const clearEditor = () => {
  selectedModelId.value = null
  editingModel.value = null
  editingModelName.value = ''
  editingRequirement.value = ''
  dictionaryEntries.value = []
  metricDefinitions.value = []
  dimensionSystem.value = []
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
  dimensionSystem.value = normalizeDimensionSystem(json.dimensionSystem)
}

const ensureFieldOptionsLoaded = async (tableName) => {
  const name = String(tableName || '').trim()
  if (!name || resolveFieldOptions(name).length > 0) {
    return
  }
  const result = unwrap(await axios.get(`${API_BASE}/api/data/tables/${name}/fields`))
  cacheFieldOptions(name, result)
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
  if (savingModel.value) {
    ElMessage.info('当前模型正在保存，请稍候再刷新')
    return
  }
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

const addDimensionRow = () => {
  if (!editingModel.value) {
    ElMessage.warning('请先选择或创建模型')
    return
  }
  dimensionSystem.value = [
    ...dimensionSystem.value,
    { name: '', field: '' }
  ]
}

const removeDimensionRow = (index) => {
  dimensionSystem.value = dimensionSystem.value.filter((_, i) => i !== index)
}

const saveModel = async () => {
  if (savingModel.value) {
    return
  }
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
    metricDefinitions: metricDefinitions.value.map(item => ({
      ...item,
      formula: rewriteFormulaToColumnNames(item.formula)
    })),
    dimensionSystem: normalizeDimensionSystem(dimensionSystem.value)
  }
  const modelId = editingModel.value.id
  savingModel.value = true
  const savingMessage = ElMessage({
    message: '正在保存当前模型并同步图谱...',
    type: 'info',
    duration: 0,
    showClose: true
  })
  try {
    const updatedModel = await updateBusinessModel(modelId, payload)
    const nextModel = {
      ...(editingModel.value || {}),
      ...(updatedModel || {})
    }
    applyModel(nextModel)
    if (Array.isArray(businessModels.value)) {
      const index = businessModels.value.findIndex(item => String(item.id) === String(modelId))
      if (index >= 0) {
        businessModels.value.splice(index, 1, nextModel)
      }
    }
    savingMessage.close()
    ElMessage.success('业务字典与业务公式已保存')
    loadBusinessModels()
      .then(() => {
        if (String(selectedModelId.value) === String(modelId)) {
          loadModelById(modelId)
        }
      })
      .catch(error => {
        console.warn('refresh business models after save failed:', error)
      })
  } catch (error) {
    savingMessage.close()
    ElMessage.error(error?.response?.data?.message || error?.message || '保存业务模型失败')
  } finally {
    savingModel.value = false
  }
}

const toggleCurrentModelPublish = async () => {
  if (!editingModel.value?.id) {
    ElMessage.warning('请先选择业务模型')
    return
  }
  await publishBusinessModel(editingModel.value, !isCurrentModelPublished.value)
  await loadBusinessModels()
  loadModelById(editingModel.value.id)
}

const previewEnterpriseModel = (model) => {
  if (!model?.id) {
    return
  }
  loadBusinessModelDetail(model.id)
}

const applyEnterpriseModelToCurrentTable = async (model) => {
  if (!model?.id) {
    return
  }
  const targetTableName = String(selectedSourceTable.value || selectedTableName.value || '').trim()
  if (!targetTableName) {
    ElMessage.warning('请先选择要套用模型的数据源')
    return
  }
  await applyBusinessModel(model, targetTableName)
  selectedSourceTable.value = targetTableName
}

const loadBusinessModelDetail = async (modelId) => {
  const detail = unwrap(await axios.get(`${API_BASE}/api/data/business-models/${modelId}`))
  if (!detail?.id) {
    throw new Error('未找到业务模型')
  }
  const merged = {
    ...detail,
    modelJson: detail.modelJson
  }
  applyModel(merged)
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
  await loadBusinessModels()
  cacheFieldOptions(selectedTableName.value, fields?.value)
  selectedSourceTable.value = String(selectedTableName.value || sourceOptions.value[0]?.tableName || '').trim()
  if (selectedSourceTable.value) {
    await ensureFieldOptionsLoaded(selectedSourceTable.value)
  }
  if (props.focusModelId == null || props.focusModelId === '') {
    selectLatestModel()
  } else {
    queueOrApplyFocusModel(props.focusModelId)
  }
})

watch(() => fields?.value, (nextFields) => {
  cacheFieldOptions(selectedTableName.value, nextFields)
}, { deep: true, immediate: true })

watch(selectedSourceTable, async (tableName) => {
  const next = String(tableName || '').trim()
  if (!next) {
    clearEditor()
    return
  }
  if (selectedTableName.value !== next) {
    selectedTableName.value = next
  }
  await ensureFieldOptionsLoaded(next)
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
  gap: 8px;
  margin-top: -30px;
  padding-top: 1px;
}

.dictionary-layout > .panel:first-child {
  box-shadow:
    inset 0 1px 0 #e5e7eb,
    0 1px 2px rgba(15, 23, 42, 0.04);
}

.selector-row {
  margin-top: 6px;
}

.filter-row {
  margin-top: 8px;
}

.tips-row {
  margin-top: 8px;
}

.enterprise-panel {
  margin-top: 14px;
}

.enterprise-toolbar {
  margin: 10px 0 12px;
}

.enterprise-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.enterprise-model-name {
  font-weight: 600;
  color: #172033;
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
