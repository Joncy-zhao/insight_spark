<template>
  <section class="sys-config">
    <header class="config-topbar">
      <div class="config-topbar-main">
        <span class="config-eyebrow">平台管理 · 参数中心</span>
        <h1>系统配置</h1>
        <p>统一维护 is_system_config 全局参数，支持分模块编辑、批量保存与恢复默认，复杂字段提供可视化编辑组件。</p>
      </div>
      <div class="config-topbar-stats-wrap">
        <div class="config-topbar-stats">
          <el-tooltip content="系统预定义的全部配置键" placement="bottom">
            <div class="stat-block">
              <span>参数总数</span>
              <strong>{{ stats.totalKeys }}</strong>
            </div>
          </el-tooltip>
          <el-tooltip content="已桥接 SQL 审计、Neo4j、环境变量、数据源等外部模块" placement="bottom">
            <div class="stat-block">
              <span>已联动</span>
              <strong>{{ stats.wiredCount }}</strong>
            </div>
          </el-tooltip>
          <el-tooltip content="仅写入 is_system_config，尚未对接外部数据源" placement="bottom">
            <div class="stat-block">
              <span>纯 KV</span>
              <strong>{{ kvOnlyCount }}</strong>
            </div>
          </el-tooltip>
          <el-tooltip content="当前页面未保存的本地修改" placement="bottom">
            <div class="stat-block" :class="{ warn: dirtyCount > 0 }">
              <span>待保存</span>
              <strong>{{ dirtyCount }}</strong>
            </div>
          </el-tooltip>
        </div>
        <p class="stats-formula">
          {{ stats.totalKeys }} = {{ stats.wiredCount }} 已联动 + {{ kvOnlyCount }} 纯 KV
          <span class="stats-meta">· 只读 {{ stats.readOnlyCount }} 项（与上述分类有交叉）</span>
        </p>
      </div>
      <div class="config-topbar-actions">
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" :loading="savingAll" :disabled="!dirtyCount" @click="saveAllDirty">
          保存全部变更
        </el-button>
      </div>
    </header>

    <div class="config-workspace">
      <aside class="config-nav">
        <el-input
          v-model="moduleSearch"
          clearable
          :prefix-icon="Search"
          placeholder="搜索配置模块"
          class="nav-search"
        />
        <nav class="nav-list" aria-label="配置模块">
          <button
            v-for="m in filteredModules"
            :key="m.id"
            type="button"
            class="nav-item"
            :class="{ active: activeModule === m.id, dirty: moduleDirtyCount(m.id) > 0 }"
            @click="onSelectModule(m.id)"
          >
            <span class="nav-item-icon">
              <el-icon><component :is="moduleIcon(m.id)" /></el-icon>
            </span>
            <span class="nav-item-body">
              <strong>{{ m.title }}</strong>
              <small>{{ moduleMeta(m.id).purpose }}</small>
            </span>
            <el-badge v-if="moduleDirtyCount(m.id)" :value="moduleDirtyCount(m.id)" type="warning" />
          </button>
        </nav>
      </aside>

      <main class="config-main" v-loading="loading">
        <template v-if="currentModule">
          <section class="module-panel">
            <div class="module-panel-head">
              <div>
                <div class="module-kicker">
                  <el-icon><component :is="moduleIcon(currentModule.id)" /></el-icon>
                  <span>{{ currentModule.title }}</span>
                </div>
                <p class="module-desc">{{ moduleMeta(currentModule.id).desc }}</p>
              </div>
              <div class="module-panel-actions">
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

            <div class="module-guide">
              <div class="guide-block">
                <span class="guide-label">模块用途</span>
                <p>{{ moduleMeta(currentModule.id).purpose }}</p>
              </div>
              <div class="guide-block">
                <span class="guide-label">推荐操作步骤</span>
                <ol class="guide-steps">
                  <li v-for="(step, idx) in moduleMeta(currentModule.id).steps" :key="idx">{{ step }}</li>
                </ol>
              </div>
              <div class="guide-block guide-tip">
                <span class="guide-label">生效说明</span>
                <p>{{ moduleMeta(currentModule.id).tip }}</p>
              </div>
            </div>

            <div class="module-metrics">
              <span>本模块 {{ currentModule.items?.length || 0 }} 项</span>
              <span v-if="moduleDirtyCount(currentModule.id)">{{ moduleDirtyCount(currentModule.id) }} 项待保存</span>
              <span v-else>已全部同步</span>
            </div>
          </section>

          <section v-if="currentModule.id === 'NOTIFICATION'" class="panel-block ann-workspace">
            <div class="ann-toolbar">
              <div class="ann-toolbar-main">
                <strong>公告发布与管理</strong>
                <p>面向平台用户发布通知，写入 is_system_announcement；支持受众、置顶、草稿与实时预览</p>
              </div>
              <div class="ann-stat-chips">
                <button type="button" class="ann-chip" :class="{ active: !annStatusFilter && !annPinnedOnly }" @click="applyAnnStatFilter('all')">
                  全部 {{ announcements.length }}
                </button>
                <button type="button" class="ann-chip" :class="{ active: annStatusFilter === 'PUBLISHED' }" @click="applyAnnStatFilter('published')">
                  已发布 {{ annPublishedCount }}
                </button>
                <button type="button" class="ann-chip warn" :class="{ active: annPinnedOnly }" @click="applyAnnStatFilter('pinned')">
                  置顶 {{ annPinnedCount }}
                </button>
              </div>
              <div class="ann-toolbar-actions">
                <el-input
                  v-model="annKeyword"
                  clearable
                  :prefix-icon="Search"
                  placeholder="搜索标题或正文"
                  class="ann-filter-input"
                />
                <el-select v-model="annAudienceFilter" clearable placeholder="受众" class="ann-filter-select">
                  <el-option label="全员" value="ALL" />
                  <el-option label="用户" value="USER" />
                  <el-option label="管理员" value="ADMIN" />
                </el-select>
                <el-select v-model="annStatusFilter" clearable placeholder="状态" class="ann-filter-select">
                  <el-option label="已发布" value="PUBLISHED" />
                  <el-option label="已撤回" value="REVOKED" />
                  <el-option label="草稿" value="DRAFT" />
                </el-select>
                <el-button :icon="Refresh" :loading="annListLoading" @click="loadAnnouncements">刷新</el-button>
                <el-button type="primary" :icon="Plus" @click="openAnnComposer">发布新公告</el-button>
              </div>
            </div>

            <div v-if="filteredAnnouncements.length" class="ann-table-wrap">
              <el-table
                :data="filteredAnnouncements"
                size="small"
                border
                highlight-current-row
                row-key="id"
                class="ann-table"
                @row-click="previewAnnouncement"
              >
                <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
                <el-table-column label="受众" width="92">
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain">{{ audienceLabel(row.audience) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="置顶" width="72" align="center">
                  <template #default="{ row }">
                    <el-switch
                      :model-value="!!row.pinned"
                      size="small"
                      @click.stop
                      @change="(v) => toggleAnnPin(row, v)"
                    />
                  </template>
                </el-table-column>
                <el-table-column prop="priority" label="优先级" width="72" align="center" />
                <el-table-column label="状态" width="96">
                  <template #default="{ row }">
                    <el-tag size="small" :type="annStatusTagType(row.publishStatus)">
                      {{ annStatusLabel(row.publishStatus) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="发布时间" width="168">
                  <template #default="{ row }">{{ formatTime(row.publishedAt || row.createdAt) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="180" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click.stop="previewAnnouncement(row)">预览</el-button>
                    <el-button link type="primary" size="small" @click.stop="copyAnnContent(row)">复制</el-button>
                    <el-button
                      v-if="row.publishStatus === 'PUBLISHED'"
                      link
                      type="danger"
                      size="small"
                      @click.stop="revokeAnnouncement(row)"
                    >
                      撤回
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div v-else class="ann-empty">
              <el-icon><Bell /></el-icon>
              <strong>{{ announcements.length ? '无匹配结果' : '暂无公告' }}</strong>
              <p v-if="announcements.length">
                当前筛选条件下没有公告，可调整关键词或清除筛选后重试。
              </p>
              <p v-else>点击「发布新公告」创建第一条平台通知，用户将在工作台看到已发布内容。</p>
              <div class="ann-empty-actions">
                <el-button v-if="annHasActiveFilter" @click="clearAnnFilters">清除筛选</el-button>
                <el-button type="primary" :icon="Plus" @click="openAnnComposer">发布新公告</el-button>
              </div>
            </div>
          </section>

          <el-drawer
            v-if="currentModule.id === 'NOTIFICATION'"
            v-model="annComposerOpen"
            title="发布平台公告"
            size="880px"
            class="ann-drawer"
            destroy-on-close
            @closed="resetAnnForm"
          >
            <div class="ann-composer">
              <div class="ann-compose-form">
                <div class="ann-template-row">
                  <span>快速模板</span>
                  <div class="ann-template-list">
                    <button
                      v-for="tpl in annTemplates"
                      :key="tpl.label"
                      type="button"
                      class="ann-template-btn"
                      @click="applyAnnTemplate(tpl)"
                    >
                      {{ tpl.label }}
                    </button>
                  </div>
                </div>
                <el-form label-position="top" class="ann-compose-form-inner" @submit.prevent>
                  <el-form-item label="标题" required>
                    <el-input v-model="ann.title" placeholder="例如：系统维护通知" maxlength="255" show-word-limit />
                  </el-form-item>
                  <el-form-item label="受众范围">
                    <el-radio-group v-model="ann.audience" class="ann-audience-group">
                      <el-radio-button label="ALL">全员</el-radio-button>
                      <el-radio-button label="USER">用户</el-radio-button>
                      <el-radio-button label="ADMIN">管理员</el-radio-button>
                    </el-radio-group>
                  </el-form-item>
                  <el-form-item label="优先级">
                    <div class="ann-priority-row">
                      <el-slider v-model="ann.priority" :min="0" :max="999" class="ann-priority-slider" />
                      <el-input-number
                        v-model="ann.priority"
                        :min="0"
                        :max="999"
                        controls-position="right"
                        class="ann-priority-input"
                      />
                    </div>
                    <p class="ann-field-hint">0–999，数值越大排序越靠前</p>
                  </el-form-item>
                  <el-form-item label="正文" required>
                    <el-input
                      v-model="ann.content"
                      type="textarea"
                      :rows="10"
                      placeholder="请输入公告正文，支持多行文本"
                      show-word-limit
                      maxlength="5000"
                    />
                  </el-form-item>
                  <el-form-item label="发布选项" class="ann-options-item">
                    <el-checkbox v-model="ann.pinned">置顶显示</el-checkbox>
                  </el-form-item>
                </el-form>
              </div>
              <aside class="ann-compose-preview">
                <span class="preview-kicker">用户端预览</span>
                <article class="ann-preview-card">
                  <div class="ann-preview-head">
                    <el-tag v-if="ann.pinned" type="danger" size="small">置顶</el-tag>
                    <el-tag size="small" effect="plain">{{ audienceLabel(ann.audience) }}</el-tag>
                  </div>
                  <strong>{{ ann.title.trim() || '公告标题预览' }}</strong>
                  <p>{{ ann.content.trim() || '在此输入正文后，可实时预览用户在工作台看到的公告样式。' }}</p>
                  <small>优先级 {{ ann.priority }} · 发布后即时可见</small>
                </article>
              </aside>
            </div>
            <template #footer>
              <el-button @click="annComposerOpen = false">取消</el-button>
              <el-button :loading="annLoading" @click="submitAnnouncementAs('DRAFT')">存为草稿</el-button>
              <el-button type="primary" :loading="annLoading" @click="submitAnnouncementAs('PUBLISHED')">立即发布</el-button>
            </template>
          </el-drawer>

          <el-drawer
            v-if="currentModule.id === 'NOTIFICATION'"
            v-model="annPreviewOpen"
            title="公告详情预览"
            size="480px"
          >
            <article v-if="annPreviewRow" class="ann-preview-card ann-preview-card--detail">
              <div class="ann-preview-head">
                <el-tag v-if="annPreviewRow.pinned" type="danger" size="small">置顶</el-tag>
                <el-tag size="small" effect="plain">{{ audienceLabel(annPreviewRow.audience) }}</el-tag>
                <el-tag size="small" :type="annStatusTagType(annPreviewRow.publishStatus)">
                  {{ annStatusLabel(annPreviewRow.publishStatus) }}
                </el-tag>
              </div>
              <strong>{{ annPreviewRow.title }}</strong>
              <p class="ann-preview-content">{{ annPreviewRow.content }}</p>
              <ul class="ann-preview-meta">
                <li>优先级：{{ annPreviewRow.priority ?? 0 }}</li>
                <li>发布时间：{{ formatTime(annPreviewRow.publishedAt || annPreviewRow.createdAt) }}</li>
                <li>发布人：{{ annPreviewRow.createdBy || '—' }}</li>
              </ul>
            </article>
          </el-drawer>

          <section class="panel-block config-panel">
            <div class="panel-head compact">
              <div>
                <strong>参数明细</strong>
                <p>字段失焦或开关切换后自动保存；批量修改可使用右上角「保存全部变更」</p>
              </div>
              <el-input
                v-model="fieldSearch"
                clearable
                :prefix-icon="Search"
                placeholder="搜索参数名或键"
                class="field-search"
              />
            </div>

            <el-form label-position="top" class="config-form" @submit.prevent>
              <div
                v-for="item in filteredModuleItems"
                :key="item.configKey"
                class="config-row"
                :class="{ 'is-dirty': isDirty(item.configKey), 'is-readonly': item.readOnly }"
              >
                <div class="config-row-label">
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
                  <p v-if="item.description" class="field-desc">{{ item.description }}</p>
                </div>

                <div class="config-row-control">
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
                    <el-option v-for="opt in parseOptions(item.options)" :key="opt" :label="opt" :value="opt" />
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

              <el-empty v-if="!filteredModuleItems.length" description="当前筛选条件下无匹配参数" />
            </el-form>
          </section>
        </template>
      </main>
    </div>

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
import {
  Bell,
  ChatLineRound,
  Coin,
  Cpu,
  Lock,
  Odometer,
  Plus,
  QuestionFilled,
  Refresh,
  Search,
  Upload
} from '@element-plus/icons-vue'
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
  saveConfigItem,
  updateAnnouncementPin,
  updateAnnouncementStatus
} from '../../api/systemConfig.js'
import axios from 'axios'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const MODULE_ICONS = {
  AI: Cpu,
  SECURITY: Lock,
  PERFORMANCE: Odometer,
  UPLOAD: Upload,
  DATASOURCE: Coin,
  INTERACTION: ChatLineRound,
  NOTIFICATION: Bell
}

const loading = ref(false)
const savingModule = ref(false)
const savingAll = ref(false)
const resetting = ref(false)
const modules = ref([])
const activeModule = ref('NOTIFICATION')
const moduleSearch = ref('')
const fieldSearch = ref('')
const values = reactive({})
const originals = reactive({})
const metaByKey = reactive({})
const stats = reactive({ totalKeys: 0, wiredCount: 0, kvOnlyCount: 0, readOnlyCount: 0 })
const rawRows = ref([])

const annComposerOpen = ref(false)
const annPreviewOpen = ref(false)
const annPreviewRow = ref(null)
const annKeyword = ref('')
const annAudienceFilter = ref('')
const annStatusFilter = ref('')
const annPinnedOnly = ref(false)
const ann = reactive({ title: '', content: '', audience: 'ALL', pinned: false, priority: 0 })
const annLoading = ref(false)
const annListLoading = ref(false)
const announcements = ref([])

const annTemplates = [
  {
    label: '系统维护',
    title: '系统维护通知',
    content: '平台将于指定时段进行系统维护，期间部分功能可能暂时不可用。请提前保存工作内容，维护结束后服务将自动恢复。'
  },
  {
    label: '版本更新',
    title: '功能更新说明',
    content: '本次更新包含性能优化与体验改进。建议刷新页面后使用最新功能，如遇异常请联系管理员。'
  },
  {
    label: '节假日',
    title: '节假日安排通知',
    content: '节日期间平台正常提供服务，如有紧急问题请通过管理员渠道反馈。祝大家节日快乐。'
  }
]

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

const filteredModules = computed(() => {
  const q = moduleSearch.value.trim().toLowerCase()
  if (!q) return modules.value
  return modules.value.filter((m) => {
    const meta = MODULE_META[m.id] || {}
    return [m.title, m.id, meta.desc, meta.purpose].some((v) => String(v || '').toLowerCase().includes(q))
  })
})

const filteredModuleItems = computed(() => {
  const items = currentModule.value?.items || []
  const q = fieldSearch.value.trim().toLowerCase()
  if (!q) return items
  return items.filter((item) =>
    [item.label, item.configKey, item.description].some((v) => String(v || '').toLowerCase().includes(q))
  )
})

const dirtyKeys = computed(() =>
  Object.keys(values).filter((k) => String(values[k] ?? '') !== String(originals[k] ?? ''))
)

const dirtyCount = computed(() => dirtyKeys.value.length)

const kvOnlyCount = computed(() => {
  const fromApi = Number(stats.kvOnlyCount)
  if (fromApi > 0 || stats.wiredCount + fromApi === stats.totalKeys) return fromApi
  return Math.max(0, stats.totalKeys - stats.wiredCount)
})

const annPublishedCount = computed(
  () => announcements.value.filter((row) => String(row.publishStatus).toUpperCase() === 'PUBLISHED').length
)

const annPinnedCount = computed(() => announcements.value.filter((row) => !!row.pinned).length)

const annHasActiveFilter = computed(
  () => !!annKeyword.value.trim() || !!annAudienceFilter.value || !!annStatusFilter.value || annPinnedOnly.value
)

const filteredAnnouncements = computed(() => {
  const keyword = annKeyword.value.trim().toLowerCase()
  const audience = annAudienceFilter.value
  const status = annStatusFilter.value
  return announcements.value.filter((row) => {
    const audiencePass = !audience || String(row.audience).toUpperCase() === audience
    const statusPass = !status || String(row.publishStatus).toUpperCase() === status
    const pinnedPass = !annPinnedOnly.value || !!row.pinned
    const keywordPass =
      !keyword ||
      [row.title, row.content, row.createdBy].some((v) => String(v || '').toLowerCase().includes(keyword))
    return audiencePass && statusPass && pinnedPass && keywordPass
  })
})

function clearAnnFilters() {
  annKeyword.value = ''
  annAudienceFilter.value = ''
  annStatusFilter.value = ''
  annPinnedOnly.value = false
}

function applyAnnStatFilter(type) {
  clearAnnFilters()
  if (type === 'published') annStatusFilter.value = 'PUBLISHED'
  if (type === 'pinned') annPinnedOnly.value = true
}

function audienceLabel(audience) {
  const map = { ALL: '全员', USER: '用户', ADMIN: '管理员' }
  return map[String(audience || '').toUpperCase()] || audience || '—'
}

function annStatusLabel(status) {
  const map = { PUBLISHED: '已发布', REVOKED: '已撤回', DRAFT: '草稿' }
  return map[String(status || '').toUpperCase()] || status || '—'
}

function annStatusTagType(status) {
  const s = String(status || '').toUpperCase()
  if (s === 'PUBLISHED') return 'success'
  if (s === 'REVOKED') return 'info'
  if (s === 'DRAFT') return 'warning'
  return ''
}

function resetAnnForm() {
  ann.title = ''
  ann.content = ''
  ann.audience = 'ALL'
  ann.pinned = false
  ann.priority = 0
}

function openAnnComposer() {
  resetAnnForm()
  annComposerOpen.value = true
}

function applyAnnTemplate(tpl) {
  ann.title = tpl.title
  ann.content = tpl.content
}

function previewAnnouncement(row) {
  annPreviewRow.value = row
  annPreviewOpen.value = true
}

async function copyAnnContent(row) {
  if (!row) return
  const text = `${row.title}\n\n${row.content || ''}`
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('公告内容已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

async function revokeAnnouncement(row) {
  if (!row?.id) return
  try {
    await ElMessageBox.confirm(`确定撤回公告「${row.title}」？撤回后用户端将不再展示。`, '撤回公告', {
      type: 'warning',
      confirmButtonText: '撤回',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await updateAnnouncementStatus(row.id, 'REVOKED')
    ElMessage.success('公告已撤回')
    await loadAnnouncements()
  } catch (e) {
    ElMessage.error(e.message || '撤回失败')
  }
}

async function toggleAnnPin(row, pinned) {
  if (!row?.id) return
  try {
    await updateAnnouncementPin(row.id, pinned)
    row.pinned = pinned ? 1 : 0
    ElMessage.success(pinned ? '已置顶' : '已取消置顶')
    await loadAnnouncements()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function submitAnnouncementAs(publishStatus) {
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
      publishStatus
    })
    ElMessage.success(publishStatus === 'PUBLISHED' ? '公告已发布' : '草稿已保存')
    annComposerOpen.value = false
    resetAnnForm()
    await loadAnnouncements()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    annLoading.value = false
  }
}

function moduleIcon(id) {
  return MODULE_ICONS[id] || Cpu
}

function moduleMeta(id) {
  return MODULE_META[id] || { desc: '', purpose: '', steps: [], tip: '' }
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
  stats.kvOnlyCount = Number(data?.kvOnlyCount) || 0
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
  annListLoading.value = true
  try {
    announcements.value = await fetchAdminAnnouncements()
  } catch {
    announcements.value = []
  } finally {
    annListLoading.value = false
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
  fieldSearch.value = ''
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
  padding: 0 4px 28px;
  min-height: 100%;
  background: #f4f6f9;
}

.config-topbar {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) auto auto;
  gap: 20px;
  align-items: start;
  padding: 22px 24px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #dde3ea;
  border-radius: 4px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.config-eyebrow {
  display: inline-block;
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #64748b;
}

.config-topbar-main h1 {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}

.config-topbar-main p {
  margin: 0;
  max-width: 680px;
  font-size: 13px;
  line-height: 1.65;
  color: #64748b;
}

.config-topbar-stats-wrap {
  min-width: 200px;
}

.config-topbar-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(88px, 1fr));
  gap: 10px;
}

.stats-formula {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: #64748b;
}

.stats-meta {
  color: #94a3b8;
}

.stat-block {
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  background: #f8fafc;
}

.stat-block span {
  display: block;
  font-size: 11px;
  color: #64748b;
}

.stat-block strong {
  display: block;
  margin-top: 4px;
  font-size: 22px;
  color: #0f172a;
}

.stat-block.warn {
  border-color: #fcd34d;
  background: #fffbeb;
}

.stat-block.warn strong {
  color: #b45309;
}

.config-topbar-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 132px;
}

.config-workspace {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.config-nav {
  position: sticky;
  top: 12px;
  padding: 14px;
  background: #fff;
  border: 1px solid #dde3ea;
  border-radius: 4px;
}

.nav-search {
  margin-bottom: 12px;
}

.nav-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nav-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.nav-item:hover {
  background: #f8fafc;
  border-color: #e2e8f0;
}

.nav-item.active {
  background: #eff6ff;
  border-color: #93c5fd;
}

.nav-item.dirty:not(.active) {
  border-color: #fde68a;
}

.nav-item-icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #334155;
  flex-shrink: 0;
}

.nav-item.active .nav-item-icon {
  background: #dbeafe;
  color: #1d4ed8;
}

.nav-item-body {
  flex: 1;
  min-width: 0;
}

.nav-item-body strong {
  display: block;
  font-size: 13px;
  color: #0f172a;
}

.nav-item-body small {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.45;
  color: #64748b;
}

.config-main {
  min-width: 0;
}

.module-panel,
.panel-block {
  background: #fff;
  border: 1px solid #dde3ea;
  border-radius: 4px;
  margin-bottom: 16px;
}

.module-panel {
  padding: 20px 22px;
}

.module-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e2e8f0;
}

.module-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.module-desc {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}

.module-panel-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.module-guide {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.guide-block {
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  background: #f8fafc;
}

.guide-block.guide-tip {
  background: #fff;
  border-left: 3px solid #2563eb;
}

.guide-label {
  display: block;
  margin-bottom: 8px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #64748b;
}

.guide-block p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: #334155;
}

.guide-steps {
  margin: 0;
  padding-left: 18px;
  color: #334155;
  font-size: 13px;
  line-height: 1.65;
}

.module-metrics {
  display: flex;
  gap: 16px;
  margin-top: 14px;
  font-size: 12px;
  color: #64748b;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.panel-head.compact {
  padding: 16px 20px 0;
}

.panel-head strong {
  display: block;
  font-size: 15px;
  color: #0f172a;
}

.panel-head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}

.field-search {
  width: 240px;
}

.config-panel {
  padding-bottom: 8px;
}

.config-row {
  display: grid;
  grid-template-columns: minmax(240px, 34%) minmax(0, 1fr);
  gap: 20px;
  padding: 16px 20px;
  border-top: 1px solid #eef2f6;
}

.config-row.is-dirty {
  background: #fffbeb;
}

.config-row.is-readonly {
  background: #fafafa;
}

.field-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.field-head label {
  font-weight: 600;
  font-size: 14px;
  color: #0f172a;
}

.field-key {
  margin: 0 0 6px;
  font-size: 11px;
  color: #94a3b8;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.field-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: #64748b;
}

.binding-tag {
  cursor: help;
}

.field-help {
  color: #94a3b8;
  cursor: help;
}

.field-meta,
.field-saving {
  margin: 8px 0 0;
  font-size: 11px;
}

.field-meta {
  color: #94a3b8;
}

.field-saving {
  color: #2563eb;
}

.ann-workspace {
  padding: 0;
  overflow: hidden;
}

.ann-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px 20px;
  padding: 18px 20px;
  border-bottom: 1px solid #eef2f6;
  background: linear-gradient(180deg, #fafbfc 0%, #fff 100%);
}

.ann-toolbar-main strong {
  display: block;
  font-size: 15px;
  color: #0f172a;
}

.ann-toolbar-main p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.55;
  color: #64748b;
}

.ann-stat-chips {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ann-chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: #fff;
  font-size: 12px;
  color: #475569;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.ann-chip:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
}

.ann-chip.active {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1d4ed8;
}

.ann-chip.warn.active {
  border-color: #dc2626;
  background: #fef2f2;
  color: #b91c1c;
}

.ann-toolbar-actions {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ann-filter-input {
  width: 220px;
}

.ann-filter-select {
  width: 120px;
}

.ann-table-wrap {
  padding: 0 20px 16px;
}

.ann-table {
  width: 100%;
}

:deep(.ann-table .el-table__row) {
  cursor: pointer;
  transition: background 0.12s;
}

:deep(.ann-table .el-table__row:hover > td) {
  background: #f8fafc !important;
}

.ann-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 24px;
  text-align: center;
  color: #64748b;
}

.ann-empty .el-icon {
  font-size: 28px;
  color: #94a3b8;
}

.ann-empty strong {
  font-size: 15px;
  color: #334155;
}

.ann-empty p {
  margin: 0;
  max-width: 420px;
  font-size: 13px;
  line-height: 1.6;
}

.ann-empty-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.ann-composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 320px);
  gap: 32px;
  min-height: 480px;
  padding: 4px 0 8px;
}

.ann-compose-form {
  min-width: 0;
}

:deep(.ann-drawer .el-drawer__body) {
  padding: 20px 28px 12px;
}

:deep(.ann-drawer .el-drawer__footer) {
  padding: 16px 28px 20px;
  border-top: 1px solid #eef2f6;
}

:deep(.ann-compose-form-inner .el-form-item) {
  margin-bottom: 24px;
}

:deep(.ann-compose-form-inner .el-form-item__label) {
  padding-bottom: 8px;
  font-weight: 600;
  color: #334155;
}

.ann-options-item {
  margin-bottom: 0 !important;
}

.ann-template-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  padding: 14px 16px;
  border: 1px dashed #dbe3ec;
  border-radius: 10px;
  background: #f8fafc;
}

.ann-template-row > span {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

.ann-template-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ann-template-btn {
  padding: 4px 12px;
  border: 1px solid #dbe3ec;
  border-radius: 999px;
  background: #fff;
  font-size: 12px;
  color: #475569;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.ann-template-btn:hover {
  border-color: #2563eb;
  color: #2563eb;
  background: #eff6ff;
}

.ann-audience-group {
  display: flex;
  width: 100%;
}

:deep(.ann-audience-group .el-radio-button) {
  flex: 1;
}

:deep(.ann-audience-group .el-radio-button__inner) {
  width: 100%;
  padding-left: 8px;
  padding-right: 8px;
}

.ann-priority-row {
  display: flex;
  align-items: center;
  gap: 20px;
}

.ann-priority-slider {
  flex: 1;
  min-width: 0;
}

.ann-priority-input {
  width: 112px;
  flex-shrink: 0;
}

.ann-field-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #94a3b8;
}

.ann-compose-preview {
  padding: 20px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8fafc;
  position: sticky;
  top: 0;
  align-self: start;
}

.preview-kicker {
  display: block;
  margin-bottom: 16px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #94a3b8;
}

.ann-preview-card {
  padding: 22px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.ann-preview-card--detail {
  box-shadow: none;
}

.ann-preview-head {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.ann-preview-card strong {
  display: block;
  margin-bottom: 10px;
  font-size: 16px;
  line-height: 1.45;
  color: #0f172a;
}

.ann-preview-card p {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #475569;
  white-space: pre-wrap;
}

.ann-preview-content {
  max-height: 320px;
  overflow: auto;
}

.ann-preview-card small {
  font-size: 11px;
  color: #94a3b8;
}

.ann-preview-meta {
  margin: 16px 0 0;
  padding: 12px 0 0;
  border-top: 1px solid #eef2f6;
  list-style: none;
}

.ann-preview-meta li {
  font-size: 12px;
  line-height: 1.8;
  color: #64748b;
}

.advanced-collapse {
  margin-top: 16px;
  border: 1px solid #dde3ea;
  border-radius: 4px;
  overflow: hidden;
  background: #fff;
}

.advanced-toolbar {
  margin-bottom: 8px;
}

.muted {
  color: #94a3b8;
}

:deep(.advanced-collapse .el-collapse-item__header) {
  padding: 0 16px;
  font-size: 13px;
  color: #64748b;
}

:deep(.el-table tbody tr) {
  cursor: pointer;
}

@media (max-width: 1200px) {
  .config-topbar {
    grid-template-columns: 1fr;
  }

  .config-workspace {
    grid-template-columns: 1fr;
  }

  .config-nav {
    position: static;
  }

  .module-guide {
    grid-template-columns: 1fr;
  }

  .config-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .config-topbar-actions {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .panel-head {
    flex-direction: column;
  }

  .ann-toolbar-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .ann-filter-input,
  .ann-filter-select {
    width: 100%;
  }

  .ann-composer {
    grid-template-columns: 1fr;
    gap: 24px;
  }

  .ann-compose-preview {
    position: static;
  }

  .field-search {
    width: 100%;
  }
}
</style>
