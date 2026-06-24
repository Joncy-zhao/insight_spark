<template>
  <section class="dictionary-layout">
    <div class="panel model-toolbar-panel">
      <div class="panel-header model-toolbar-header">
        <div>
          <h2 v-if="showTitle">业务字典 + 业务公式维护</h2>
          <p>复用业务模型，集中维护业务术语、维度和指标公式，保存后将参与图谱与 Text-to-SQL 映射。</p>
        </div>
        <div class="header-actions">
          <el-button :loading="creatingModel" @click="handleCreateModelClick">新建模型</el-button>
        </div>
      </div>

      <div class="model-toolbar">
        <div class="toolbar-field">
          <span class="toolbar-label">数据源：</span>
          <el-select v-model="selectedSourceTable" class="source-select" placeholder="请选择数据源" filterable>
            <el-option
              v-for="item in sourceOptions"
              :key="item.tableName"
              :label="item.displayName ? `${item.displayName}（${item.tableName}）` : item.tableName"
              :value="item.tableName"
            />
          </el-select>
        </div>
        <div class="toolbar-field">
          <span class="toolbar-label">全局搜索：</span>
          <el-autocomplete
            v-model.trim="modelSearchInput"
            class="global-model-search"
            :fetch-suggestions="queryModelSearchSuggestions"
            :trigger-on-focus="true"
            placeholder="按模型名称、数据源、字段或指标搜索"
            clearable
            @clear="clearModelSearch"
            @select="handleModelSearchSuggestionSelect"
            @keyup.enter="applyModelSearch"
          >
            <template #default="{ item }">
              <div class="model-suggestion-row">
                <span class="model-suggestion-label">{{ item.value }}</span>
                <span class="model-suggestion-source" :title="item.tableLabel">{{ item.tableLabel }}</span>
                <span class="model-suggestion-meta" :title="`更新时间：${formatModelTime(item.updatedAt)}`">
                  {{ formatModelTime(item.updatedAt) }}
                </span>
              </div>
            </template>
            <template #append>
              <el-button @click="applyModelSearch">搜索</el-button>
            </template>
          </el-autocomplete>
        </div>
      </div>

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

    <div class="model-workbench">
      <aside class="model-sidebar">
        <!-- <div class="side-section">
          <p class="side-title">维护模块</p>
          <div class="side-nav">
            <button
              type="button"
              class="side-nav-item"
              :class="{ active: activeWorkbenchTab === 'mine' }"
              @click="activeWorkbenchTab = 'mine'"
            >
              <span>我的业务模型</span>
              <b>{{ modelsBySource.length }}</b>
            </button>
            <button
              type="button"
              class="side-nav-item"
              :class="{ active: activeWorkbenchTab === 'enterprise' }"
              @click="activeWorkbenchTab = 'enterprise'"
            >
              <span>企业模型库</span>
              <b>{{ filteredEnterpriseModels.length }}</b>
            </button>
          </div>
        </div> -->

        <div class="side-section">
          <p class="side-title">当前数据源模型</p>
          <el-input
            v-model.trim="sourceModelKeyword"
            class="source-model-search"
            placeholder="搜索模型名称"
            clearable
          />
          <div class="source-model-list">
            <button
              v-for="model in filteredSourceModels"
              :key="model.id"
              type="button"
              class="source-model-item"
              :class="{ active: String(model.id) === String(selectedModelId) }"
              @click="loadModelById(model.id)"
            >
              <span class="source-model-top">
                <strong>{{ model.modelName || '未命名模型' }}</strong>
                <em v-if="String(model.id) === String(selectedModelId)">当前编辑</em>
                <em v-else-if="model.published" class="published">已发布</em>
                <em v-else>草稿</em>
              </span>
              <span class="source-model-desc">{{ formatModelTime(model.updatedAt) }}</span>
              <span class="source-model-tags">
                <i>{{ countDictionaryEntries(model) }} 术语</i>
                <i>{{ countMetricDefinitions(model) }} 指标</i>
                <i>{{ countDimensionSystem(model) }} 维度</i>
              </span>
            </button>
            <el-empty v-if="filteredSourceModels.length === 0" description="暂无模型" :image-size="80" />
          </div>
        </div>
      </aside>

      <section class="model-workspace">
        <div class="workspace-tabs">
          <button
            type="button"
            class="workspace-tab"
            :class="{ active: activeWorkbenchTab === 'mine' }"
            @click="activeWorkbenchTab = 'mine'"
          >
            我的业务模型
          </button>
          <button
            type="button"
            class="workspace-tab"
            :class="{ active: activeWorkbenchTab === 'enterprise' }"
            @click="activeWorkbenchTab = 'enterprise'"
          >
            企业模型库
          </button>
        </div>

        <div v-if="activeWorkbenchTab === 'mine'" class="workspace-content">
          <template v-if="editingModel">
            <section class="current-model-card">
              <div>
                <div class="current-model-title">
                  <h3>
                    <el-input
                      v-model.trim="editingModelName"
                      class="model-name-input"
                      placeholder="请输入模型名称"
                    />
                  </h3>
                  <!-- <el-tag type="primary" effect="light">当前选中</el-tag> -->
                  <el-tag :type="isCurrentModelPublished ? 'success' : 'info'" effect="light">
                    {{ isCurrentModelPublished ? '已发布' : '草稿' }}
                  </el-tag>
                </div>
                <p>左侧选择当前数据源下的业务模型，在这里维护对应的业务术语、维度字段和指标公式。</p>
                <div class="current-model-meta">
                  <el-tag effect="plain">数据表：{{ editingModel.tableName }}</el-tag>
                  <el-tag effect="plain">已保存：{{ formatModelTime(editingModel.updatedAt) }}</el-tag>
                  <!-- <el-tag effect="plain">完整度：{{ modelCompleteness }}%</el-tag> -->
                </div>
              </div>
              <div class="current-model-actions">
                <el-button :disabled="savingModel" @click="refreshModels">刷新模型</el-button>
                <el-button type="danger" plain :disabled="!editingModel || savingModel" :loading="deletingModel" @click="deleteCurrentModel">
                  删除模型
                </el-button>
              </div>
            </section>

            <div class="model-stats">
              <div><span>业务术语</span><strong>{{ dictionaryEntries.length }}</strong></div>
              <div><span>业务维度</span><strong>{{ dimensionSystem.length }}</strong></div>
              <div><span>业务公式</span><strong>{{ metricDefinitions.length }}</strong></div>
              <div><span>模型状态</span><strong>{{ isCurrentModelPublished ? '已发布' : '草稿' }}</strong></div>
            </div>

            <div class="editor-grid">
              <div class="panel editor-card">
                <div class="panel-header">
                  <div>
                    <h3>业务字典</h3>
                    <p>维护业务术语、目标字段和同义词映射。</p>
                  </div>
                  <div class="card-tools">
                    <el-button size="small" @click="addDictionaryRow">新增条目</el-button>
                  </div>
                </div>
                <el-table :data="dictionaryEntries" height="320" empty-text="暂无业务字典词条" table-layout="fixed">
                  <el-table-column label="业务术语" min-width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.term" size="small" placeholder="例如：客单价、复购用户" />
                    </template>
                  </el-table-column>
                  <el-table-column label="目标字段" min-width="130">
                    <template #default="{ row }">
                      <el-select v-model="row.field" size="small" class="full-width" filterable clearable default-first-option placeholder="请选择字段">
                        <el-option v-for="field in editorFieldOptions" :key="field.columnName" :label="field.optionLabel" :value="field.columnName" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="同义词" min-width="180">
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

              <div class="panel editor-card">
                <div class="panel-header">
                  <div>
                    <h3>业务维度</h3>
                    <p>维护分组、筛选和对比字段。</p>
                  </div>
                  <div class="card-tools">
                    <el-button size="small" @click="addDimensionRow">新增维度</el-button>
                  </div>
                </div>
                <el-table :data="dimensionSystem" height="320" empty-text="暂无业务维度" table-layout="fixed">
                  <el-table-column label="维度名称" min-width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.name" size="small" placeholder="例如：城市、省份、区域" />
                    </template>
                  </el-table-column>
                  <el-table-column label="字段绑定" min-width="150">
                    <template #default="{ row }">
                      <el-select v-model="row.field" size="small" class="full-width" filterable clearable default-first-option placeholder="请选择字段">
                        <el-option v-for="field in editorFieldOptions" :key="field.columnName" :label="field.optionLabel" :value="field.columnName" />
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

              <div class="panel editor-card formula-card">
                <div class="panel-header">
                  <div>
                    <h3>业务公式</h3>
                    <p>维护指标名称、字段绑定、聚合方式和公式。</p>
                  </div>
                  <div class="card-tools">
                    <el-button size="small" @click="addMetricRow">新增指标</el-button>
                  </div>
                </div>
                <el-table :data="metricDefinitions" height="320" empty-text="暂无业务公式" table-layout="fixed">
                  <el-table-column label="指标名称" min-width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.name" size="small" placeholder="例如：利润、转化率" />
                    </template>
                  </el-table-column>
                  <el-table-column label="字段绑定" min-width="150">
                    <template #default="{ row }">
                      <el-select v-model="row.field" size="small" class="full-width" filterable clearable default-first-option placeholder="请选择字段">
                        <el-option v-for="field in editorFieldOptions" :key="field.columnName" :label="field.optionLabel" :value="field.columnName" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="聚合方式" width="120">
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
                  <el-table-column label="公式" min-width="220">
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

            <div class="save-bar">
              <span>保存后会同步到当前业务模型，并参与图谱与 Text-to-SQL 映射。</span>
              <div>
                <el-button :disabled="savingModel" @click="loadModelById(editingModel.id)">放弃更改</el-button>
                <el-button type="primary" :loading="savingModel" @click="saveModel">保存当前模型</el-button>
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
          </template>
          <el-empty v-else description="请先选择或创建业务模型开始维护" />
        </div>

        <div v-else class="workspace-content">
          <section class="library-hero">
            <div>
              <h3>企业模型库</h3>
              <p>选择已发布的业务模型，快速套用字段映射、业务公式和查询口径到当前数据源。</p>
            </div>
            <div class="library-actions">
              <el-button :disabled="savingModel" @click="refreshModels">刷新列表</el-button>
              <el-button type="primary" :disabled="!editingModel || savingModel" @click="toggleCurrentModelPublish">
                发布当前模型
              </el-button>
            </div>
          </section>

          <div class="model-stats">
            <div><span>可套用模型</span><strong>{{ filteredEnterpriseModels.length }}</strong></div>
            <div><span>当前数据源</span><strong>{{ modelsBySource.length }}</strong></div>
            <div><span>模型总数</span><strong>{{ modelOptions.length }}</strong></div>
            <div><span>当前模型</span><strong>{{ editingModel ? '已选择' : '未选择' }}</strong></div>
          </div>

          <div class="library-filters">
            <el-input
              v-model.trim="enterpriseKeyword"
              class="full-width"
              placeholder="按模型名称或来源数据源搜索企业模型"
              clearable
            />
            <el-select v-model="modelSortBy" class="full-width" placeholder="排序方式">
              <el-option label="按更新时间（最新优先）" value="updated_desc" />
              <el-option label="按更新时间（最早优先）" value="updated_asc" />
            </el-select>
          </div>

          <div class="panel library-table-card">
            <el-table :data="filteredEnterpriseModels" height="520" empty-text="暂无已发布的企业模型" table-layout="fixed">
              <el-table-column label="模型名称" min-width="240">
                <template #default="{ row }">
                  <div class="enterprise-model-name">{{ row.modelName }}</div>
                  <div class="enterprise-model-tags">
                    <el-tag size="small" effect="plain">{{ countDictionaryEntries(row) }} 术语</el-tag>
                    <el-tag size="small" effect="plain">{{ countMetricDefinitions(row) }} 指标</el-tag>
                    <el-tag size="small" effect="plain">{{ countDimensionSystem(row) }} 维度</el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="来源数据源" min-width="190">
                <template #default="{ row }">
                  <span>{{ resolveTableLabel(row.tableName) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default>
                  <el-tag type="success" effect="light">已发布</el-tag>
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
        </div>
      </section>
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
const activeWorkbenchTab = ref('mine')
const modelSearchInput = ref('')
const modelSearchKeyword = ref('')
const sourceModelKeyword = ref('')
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

const filteredSourceModels = computed(() => {
  const keyword = String(sourceModelKeyword.value || '').trim().toLowerCase()
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

const modelCompleteness = computed(() => {
  const checks = [
    dictionaryEntries.value.length > 0,
    metricDefinitions.value.length > 0,
    dimensionSystem.value.length > 0,
    dictionaryEntries.value.some(item => String(item.field || '').trim()),
    metricDefinitions.value.some(item => String(item.formula || '').trim())
  ]
  const passed = checks.filter(Boolean).length
  return Math.round((passed / checks.length) * 100)
})

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

const getModelJson = (model) => parseMaybeJson(model?.modelJson)

const countDictionaryEntries = (model) => {
  const json = getModelJson(model)
  return Array.isArray(json.dictionaryEntries) ? json.dictionaryEntries.length : 0
}

const countMetricDefinitions = (model) => {
  const json = getModelJson(model)
  return Array.isArray(json.metricDefinitions) ? json.metricDefinitions.length : 0
}

const countDimensionSystem = (model) => {
  const json = getModelJson(model)
  return Array.isArray(json.dimensionSystem) ? json.dimensionSystem.length : 0
}

const buildModelSearchText = (model) => {
  const json = getModelJson(model)
  const dictionaryText = Array.isArray(json.dictionaryEntries)
    ? json.dictionaryEntries.flatMap(item => [item?.term, item?.field, item?.synonyms])
    : []
  const metricText = Array.isArray(json.metricDefinitions)
    ? json.metricDefinitions.flatMap(item => [item?.name, item?.field, item?.aggregation, item?.formula])
    : []
  const dimensionText = Array.isArray(json.dimensionSystem)
    ? json.dimensionSystem.flatMap(item => [item?.name, item?.field])
    : []
  return [
    model?.modelName,
    model?.tableName,
    resolveTableLabel(model?.tableName),
    ...dictionaryText,
    ...metricText,
    ...dimensionText
  ]
    .map(item => String(item || '').toLowerCase())
    .join(' ')
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

const queryModelSearchSuggestions = (queryString, cb) => {
  const keyword = String(queryString || '').trim().toLowerCase()
  const list = sortModelsByUpdated(
    modelOptions.value
      .filter(item => String(item.status || '').toUpperCase() === 'ACTIVE')
      .filter(item => !keyword || buildModelSearchText(item).includes(keyword))
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
      .filter(item => buildModelSearchText(item).includes(keyword.toLowerCase()))
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
    await loadBusinessModels()
    if (String(selectedModelId.value) === String(modelId)) {
      loadModelById(modelId)
    }
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

.model-toolbar {
  display: grid;
  grid-template-columns: minmax(270px, 0.36fr) minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  padding: 10px 18px 18px;
}

.toolbar-field {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.toolbar-label {
  flex: 0 0 auto;
  color: #6b7280;
  font-size: 13px;
  font-weight: 800;
  white-space: nowrap;
}

.source-select,
.global-model-search {
  width: 100%;
}

.model-toolbar :deep(.el-input__wrapper),
.model-toolbar :deep(.el-select__wrapper) {
  box-shadow: inset 0 0 0 1px #d8e2ef;
  border-radius: 8px;
}

.model-toolbar-header {
  align-items: flex-start;
}

.model-workbench {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
}

.model-sidebar {
  display: grid;
  gap: 14px;
  align-content: start;
}

.side-section {
  padding: 5px;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: #fff;
}

.side-title {
  margin: 0 0 12px;
  color: #29364d;
  font-size: 13px;
  font-weight: 900;
}

.side-nav {
  display: grid;
  gap: 8px;
}

.side-nav-item {
  width: 100%;
  min-height: 46px;
  border: 1px solid transparent;
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fff;
  color: #334155;
  font-weight: 800;
}

.side-nav-item.active {
  border-color: #cfe0ff;
  background: #edf4ff;
  color: #1d4ed8;
  box-shadow: inset 4px 0 0 #2f7cf6;
}

.side-nav-item b,
.source-model-item em,
.source-model-item strong,
.source-model-tags i {
  font-style: normal;
}

.side-nav-item b {
  min-width: 28px;
  height: 22px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #eef2f7;
  color: #667085;
  font-size: 12px;
  font-weight: 900;
}

.side-nav-item.active b {
  background: #fff;
  color: #1d4ed8;
}

.source-model-search {
  margin-bottom: 10px;
}

.source-model-search :deep(.el-input__wrapper),
.library-filters :deep(.el-input__wrapper),
.global-model-search :deep(.el-input__wrapper) {
  box-shadow: inset 0 0 0 1px #d8e2ef;
  border-radius: 8px;
}

.source-model-list {
  display: grid;
  gap: 8px;
}

.source-model-item {
  width: 100%;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
  color: #334155;
  text-align: left;
}

.source-model-item.active {
  border-color: #cfe0ff;
  background: #f4f8ff;
  box-shadow: inset 4px 0 0 #2f7cf6;
}

.source-model-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.source-model-top strong {
  min-width: 0;
  color: #172033;
  font-size: 14px;
  line-height: 1.4;
}

.source-model-item.active .source-model-top strong {
  color: #1d4ed8;
}

.source-model-top em {
  flex: 0 0 auto;
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 2px 8px;
  background: #eef2f7;
  color: #667085;
  font-size: 12px;
  font-weight: 900;
}

.source-model-top em.published {
  background: #e9f8ef;
  color: #16a34a;
}

.source-model-item.active .source-model-top em:not(.published) {
  background: #2f7cf6;
  color: #fff;
}

.source-model-desc {
  display: block;
  margin-top: 8px;
  color: #667085;
  font-size: 12px;
  font-weight: 700;
}

.source-model-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.source-model-tags i {
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 2px 8px;
  background: #f1f5f9;
  color: #516173;
  font-size: 12px;
  font-weight: 800;
}

.model-workspace {
  min-width: 0;
  display: grid;
  gap: 12px;
}

.workspace-tabs {
  display: flex;
  gap: 8px;
  padding: 10px 10px 0;
  border-bottom: 1px solid #e5eaf2;
  background: #fbfcff;
  border-radius: 8px 8px 0 0;
}

.workspace-tab {
  min-width: 148px;
  height: 46px;
  border: 1px solid transparent;
  border-bottom: 0;
  border-radius: 8px 8px 0 0;
  padding: 0 16px;
  background: transparent;
  color: #667085;
  font-weight: 900;
}

.workspace-tab.active {
  background: #fff;
  border-color: #e5eaf2;
  color: #1d4ed8;
}

.workspace-content {
  min-width: 0;
  display: grid;
  gap: 16px;
}

.current-model-card,
.library-hero,
.save-bar {
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: #fff;
}

.current-model-card {
  padding: 20px 18px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  align-items: start;
  background: linear-gradient(180deg, #fafdff 0%, #ffffff 100%);
}

.current-model-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.current-model-title h3 {
  margin: 0;
}

.model-name-input {
  width: min(500px, 100%);
}

.model-name-input :deep(.el-input__wrapper) {
  box-shadow: inset 0 0 0 1px #d8e2ef;
  border-radius: 8px;
}

.current-model-card p,
.library-hero p {
  margin: 10px 0 0;
  color: #667085;
  line-height: 1.6;
}

.current-model-meta,
.model-stats,
.library-actions,
.enterprise-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.current-model-meta :deep(.el-tag) {
  border-radius: 999px;
  padding-inline: 10px;
}

.current-model-actions {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  padding-top: 4px;
}

.model-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.model-stats > div {
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  padding: 12px 14px;
  background: linear-gradient(180deg, #f8fafc 0%, #ffffff 100%);
}

.model-stats span {
  color: #667085;
  font-size: 12px;
  font-weight: 800;
}

.model-stats strong {
  display: block;
  margin-top: 7px;
  font-size: 22px;
  line-height: 1;
  color: #172033;
}

.editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  align-items: start;
}

.formula-card {
  grid-column: 1 / -1;
}

.editor-card {
  min-width: 0;
}

.card-tools {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.library-hero {
  padding: 18px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
}

.library-filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 12px;
}

.library-table-card {
  padding: 0;
  overflow: hidden;
}

.library-table-card :deep(.el-table__inner-wrapper) {
  border-radius: 0 0 8px 8px;
}

.enterprise-model-name {
  font-weight: 700;
  color: #172033;
}

.enterprise-model-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.save-bar {
  padding: 14px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.save-bar span {
  color: #667085;
  font-size: 13px;
  font-weight: 700;
}

.library-actions {
  justify-content: flex-end;
}

.editor-card :deep(.el-table__header-wrapper),
.editor-card :deep(.el-table__body-wrapper),
.library-table-card :deep(.el-table__header-wrapper),
.library-table-card :deep(.el-table__body-wrapper) {
  border-radius: 0;
}

.manage-actions,
.sort-wrap,
.enterprise-panel,
.selector-row,
.filter-row,
.tips-row,
.enterprise-toolbar,
.dictionary-grid {
  display: none;
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
  .model-workbench {
    grid-template-columns: 1fr;
  }

  .current-model-card,
  .library-hero,
  .save-bar {
    grid-template-columns: 1fr;
  }

  .model-stats,
  .editor-grid,
  .library-hero,
  .library-filters {
    grid-template-columns: 1fr;
  }

  .current-model-actions,
  .library-actions,
  .save-bar {
    justify-content: flex-start;
  }

  .save-bar {
    align-items: flex-start;
    flex-direction: column;
  }

  .workspace-tabs {
    overflow-x: auto;
  }
}
</style>
