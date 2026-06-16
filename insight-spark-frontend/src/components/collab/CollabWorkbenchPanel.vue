<template>
  <div class="cwb-panel">
    <div class="cwb-segment">
      <button type="button" :class="['cwb-seg-btn', { active: tab === 'annotation' }]" @click="tab = 'annotation'">
        数据批注
        <span v-if="visibleAnnotationCount" class="cwb-seg-count">{{ visibleAnnotationCount }}</span>
      </button>
      <button type="button" :class="['cwb-seg-btn', { active: tab === 'comment' }]" @click="tab = 'comment'">
        协同评论
        <span v-if="comments.length" class="cwb-seg-count">{{ comments.length }}</span>
      </button>
    </div>

    <!-- 数据批注 -->
    <template v-if="tab === 'annotation'">
      <div class="cwb-tab-body">
        <div class="cwb-filter-bar">
          <label class="cwb-filter-label">作用范围</label>
          <el-select
            v-if="nodes.length > 1"
            :model-value="activeNodeKey"
            size="small"
            filterable
            class="cwb-scope-select"
            placeholder="选择组件"
            @update:model-value="onScopeChange"
          >
            <el-option
              v-for="node in nodes"
              :key="nodeKey(node)"
              :label="node.label"
              :value="nodeKey(node)"
            >
              <span>{{ node.label }}</span>
              <span v-if="nodeAnnCount(node)" class="cwb-scope-opt-count">{{ nodeAnnCount(node) }}</span>
            </el-option>
          </el-select>
          <span v-else class="cwb-scope-single">{{ activeNodeLabel }}</span>
          <el-select v-model="tagFilter" size="small" class="cwb-tag-select" placeholder="分类">
            <el-option v-for="f in tagFilters" :key="f.value" :label="f.label" :value="f.value" />
          </el-select>
        </div>

        <div class="cwb-list-zone">
          <el-scrollbar class="cwb-scroll cwb-scroll--fill">
          <el-empty v-if="!displayAnnotations.length" :description="emptyAnnHint" :image-size="48" />
          <div v-else class="cwb-ann-list">
            <article
              v-for="ann in displayAnnotations"
              :key="ann.id"
              :class="['cwb-ann-card', { 'is-hidden': isHidden(ann), 'is-editing': editingId === ann.id }]"
              :style="cardStyle(ann.tag)"
              @click="editingId !== ann.id && $emit('focus-annotation', ann)"
            >
              <div class="cwb-ann-head">
                <span class="cwb-avatar">{{ initials(ann) }}</span>
                <div class="cwb-ann-meta">
                  <div class="cwb-ann-meta-top">
                    <strong>{{ displayName(ann) }}</strong>
                    <el-tag v-if="ann.tag" size="small" :type="tagPreset(ann.tag).tone" effect="plain" round>
                      {{ ann.tag }}
                    </el-tag>
                    <el-tag v-if="isHidden(ann)" size="small" type="info" effect="plain">已隐藏</el-tag>
                    <div
                      v-if="canManageAnn(ann) && editingId !== ann.id"
                      class="collab-row-actions cwb-ann-actions"
                      @click.stop
                    >
                      <el-button link type="primary" size="small" @click="startEdit(ann)">编辑</el-button>
                      <el-button link type="primary" size="small" @click="$emit('toggle-hide-annotation', ann)">
                        {{ isHidden(ann) ? '显示' : '隐藏' }}
                      </el-button>
                      <el-button link type="danger" size="small" @click="$emit('delete-annotation', ann.id)">删除</el-button>
                    </div>
                  </div>
                  <span class="cwb-time">
                    {{ formatTime(ann.createdAt) }}
                    <template v-if="wasEdited(ann)"> · 已编辑 {{ formatTime(ann.updatedAt) }}</template>
                  </span>
                </div>
              </div>

              <template v-if="editingId === ann.id">
                <el-select v-model="editForm.tag" clearable placeholder="标签（可选）" size="small" class="cwb-edit-tag">
                  <el-option v-for="p in ANNOTATION_TAG_PRESETS" :key="p.value" :label="p.label" :value="p.value" />
                </el-select>
                <el-input v-model="editForm.content" type="textarea" :rows="3" resize="none" />
                <div class="collab-row-actions cwb-ann-actions">
                  <el-button size="small" @click.stop="cancelEdit">取消</el-button>
                  <el-button type="primary" size="small" :loading="editSubmitting" @click.stop="saveEdit(ann)">保存</el-button>
                </div>
              </template>
              <template v-else>
                <div v-if="selectionImageForAnn(ann)" class="cwb-selection-preview">
                  <img :src="selectionImageForAnn(ann)" alt="框选区域" class="cwb-selection-img" />
                </div>
                <div v-if="bindChipsForAnn(ann).length" class="cwb-bind-chips">
                  <span
                    v-for="chip in bindChipsForAnn(ann)"
                    :key="`${ann.id}-${chip.key}`"
                    class="cwb-bind-chip"
                  >
                    {{ chip.label }}：{{ chip.value }}
                  </span>
                </div>
                <p class="cwb-ann-body">{{ ann.content }}</p>
              </template>
            </article>
          </div>
        </el-scrollbar>
        </div>

        <div class="cwb-compose-zone">
          <button type="button" class="cwb-compose-toggle" @click="composeExpanded = !composeExpanded">
            <span class="cwb-compose-toggle-title">添加批注</span>
            <span class="cwb-compose-toggle-icon">{{ composeExpanded ? '收起' : '展开' }}</span>
          </button>
          <div v-show="composeExpanded" class="cwb-compose">
          <div v-if="activeSemanticChips.length" class="cwb-compose-bind">
            <span class="cwb-compose-bind-label">自动绑定</span>
            <span
              v-for="chip in activeSemanticChips"
              :key="chip.key"
              class="cwb-bind-chip"
            >
              {{ chip.label }}：{{ chip.value }}
            </span>
          </div>
          <div v-if="hasSelectionDraft" class="cwb-compose-selection">
            <div class="cwb-compose-selection-main">
              <span class="cwb-compose-selection-label">框选区域</span>
              <img
                v-if="pendingSelectionImage"
                :src="pendingSelectionImage"
                alt="框选预览"
                class="cwb-selection-img cwb-selection-img--draft"
              />
              <span v-else class="cwb-compose-selection-text">已在图表上框选区域</span>
            </div>
            <el-button link type="primary" size="small" @click="$emit('clear-selection')">清除框选</el-button>
          </div>
          <p v-else-if="canCompose && boxSelectEnabled" class="cwb-compose-hint">选中图表后，在图表区域拖拽鼠标可框选重点数据</p>
          <p v-if="!canCompose" class="cwb-readonly-hint">当前无协作批注权限，仅可查看。</p>
          <template v-else>
            <div class="cwb-tag-pick">
              <button
                v-for="tpl in ANNOTATION_TAG_PRESETS"
                :key="tpl.value"
                type="button"
                :class="['cwb-tag-pick-btn', { active: annForm.tag === tpl.value }]"
                :style="{ '--pick-accent': tpl.accent }"
                @click="applyTemplate(tpl)"
              >
                {{ tpl.label }}
              </button>
            </div>
            <el-input
              :model-value="annForm.content"
              type="textarea"
              :rows="2"
              resize="none"
              placeholder="记录业务发现、异常原因或经验总结"
              @update:model-value="(v) => $emit('update:ann-content', v)"
            />
            <el-button type="primary" class="cwb-submit" :loading="annSubmitting" @click="$emit('submit-annotation')">
              提交批注
            </el-button>
          </template>
          </div>
        </div>
      </div>
    </template>

    <!-- 协同评论 -->
    <template v-else>
      <div class="cwb-tab-body">
        <div class="cwb-list-zone cwb-list-zone--comment">
          <el-scrollbar class="cwb-scroll cwb-scroll--fill">
          <el-empty v-if="!commentTree.length" description="暂无评论，输入 @ 可提及同事" :image-size="64" />
          <div v-else class="cwb-comment-list">
            <CollabCommentThread
              v-for="c in commentTree"
              :key="c.id"
              :row="c"
              :format-time="formatTime"
              :render-mentions="renderMentions"
              :can-compose="canCompose"
              :can-delete="canDelete"
              @reply="onReply"
              @delete-comment="(id) => $emit('delete-comment', id)"
            />
          </div>
        </el-scrollbar>
        </div>

        <div class="cwb-compose-zone">
          <button type="button" class="cwb-compose-toggle" @click="commentComposeExpanded = !commentComposeExpanded">
            <span class="cwb-compose-toggle-title">{{ replyingTo ? '回复评论' : '发送评论' }}</span>
            <span class="cwb-compose-toggle-icon">{{ commentComposeExpanded ? '收起' : '展开' }}</span>
          </button>
          <div v-show="commentComposeExpanded" class="cwb-compose cwb-compose--comment">
          <div v-if="replyingTo" class="cwb-reply-bar">
            <span>回复 {{ displayName(replyingTo) }}</span>
            <el-button link type="primary" size="small" @click="cancelReply">取消</el-button>
          </div>
          <p v-if="!canCompose" class="cwb-readonly-hint">当前无协作评论权限，仅可查看。</p>
          <template v-else>
            <el-input
              :model-value="comForm.content"
              type="textarea"
              :rows="2"
              resize="none"
              :placeholder="replyingTo ? '输入回复内容…' : '围绕看板讨论，输入 @ 提及成员'"
              @update:model-value="(v) => $emit('update:com-content', v)"
              @input="(v) => $emit('comment-input', v)"
            />
            <div v-if="mentionVisible && mentionCandidates.length" class="mention-pop">
              <button v-for="u in mentionCandidates" :key="u.userId" type="button" class="mention-opt" @click="$emit('insert-mention', u)">
                {{ u.nickname || u.username }} · {{ u.userId }}
              </button>
            </div>
            <el-button type="primary" class="cwb-submit" :loading="comSubmitting" @click="submitCommentClick">
              {{ replyingTo ? '发送回复' : '发送评论' }}
            </el-button>
          </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { currentUser } from '../../store/session'
import CollabCommentThread from './CollabCommentThread.vue'
import {
  ANNOTATION_TAG_PRESETS,
  annotationCardStyle,
  annotationTagPreset,
  buildCommentTree,
  countAnnotationsForNode,
  formatAnnotationBindChips,
  isAnnotationHidden,
  parseAnnotationBind,
  selectionImageForAnnotation,
  userInitials,
  wasAnnotationEdited
} from '../../utils/collabAnnotation.js'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  activeNodeKey: { type: String, default: '' },
  activeNodeLabel: { type: String, default: '整板' },
  activeSemanticChips: { type: Array, default: () => [] },
  hasSelectionDraft: { type: Boolean, default: false },
  pendingSelectionImage: { type: String, default: '' },
  boxSelectEnabled: { type: Boolean, default: false },
  dashboardId: { type: [Number, String], default: null },
  annotations: { type: Array, default: () => [] },
  filteredAnnotations: { type: Array, default: () => [] },
  comments: { type: Array, default: () => [] },
  annForm: { type: Object, required: true },
  comForm: { type: Object, required: true },
  annSubmitting: { type: Boolean, default: false },
  comSubmitting: { type: Boolean, default: false },
  canCompose: { type: Boolean, default: true },
  mentionVisible: { type: Boolean, default: false },
  mentionCandidates: { type: Array, default: () => [] },
  nodeKeyFn: { type: Function, required: true },
  renderMentions: { type: Function, required: true },
  formatTime: { type: Function, required: true }
})

const emit = defineEmits([
  'select-node',
  'focus-annotation',
  'delete-annotation',
  'toggle-hide-annotation',
  'save-annotation',
  'submit-annotation',
  'update:ann-content',
  'delete-comment',
  'submit-comment',
  'update:com-content',
  'comment-input',
  'insert-mention',
  'apply-template',
  'clear-selection'
])

const tab = ref('annotation')
const tagFilter = ref('')
const composeExpanded = ref(false)
const commentComposeExpanded = ref(false)
const editingId = ref(null)
const editSubmitting = ref(false)
const editForm = reactive({ content: '', tag: '' })
const replyingTo = ref(null)

const commentTree = computed(() => buildCommentTree(props.comments))

const visibleAnnotationCount = computed(() =>
  props.annotations.filter((a) => !isAnnotationHidden(a)).length
)

const displayAnnotations = computed(() => {
  let list = props.filteredAnnotations.filter((a) => shouldShowAnn(a))
  if (!tagFilter.value) return list
  return list.filter((a) => String(a.tag || '') === tagFilter.value)
})

function shouldShowAnn(ann) {
  if (!isHidden(ann)) return true
  const me = currentUser.value?.userId
  if (ann.userId === me) return true
  return currentUser.value?.role === 'ADMIN'
}

const tagFilters = [
  { value: '', label: '全部' },
  ...ANNOTATION_TAG_PRESETS.map((p) => ({ value: p.value, label: p.label }))
]

const emptyAnnHint = computed(() => {
  if (tagFilter.value) return '当前标签下暂无批注'
  const node = props.nodes.find((n) => props.nodeKeyFn(n) === props.activeNodeKey)
  if (node?.kind === 'dashboard') return '整板暂无批注，可点击画布上方「整板批注」后在此添加'
  return '选中组件后，在下方填写并提交批注'
})

watch(
  () => displayAnnotations.value.length,
  (len) => {
    composeExpanded.value = len === 0
  },
  { immediate: true }
)

watch(
  () => commentTree.value.length,
  (len) => {
    if (!replyingTo.value) {
      commentComposeExpanded.value = len === 0
    }
  },
  { immediate: true }
)

watch(
  () => props.activeNodeKey,
  () => {
    tab.value = 'annotation'
    cancelEdit()
  }
)

function nodeKey(node) {
  return props.nodeKeyFn(node)
}

function onScopeChange(key) {
  const node = props.nodes.find((n) => props.nodeKeyFn(n) === key)
  if (node) emit('select-node', node)
}

function nodeAnnCount(node) {
  return props.annotations.filter((ann) => countAnnotationsForNode(ann, node, props.dashboardId)).length
}

function tagPreset(tag) {
  return annotationTagPreset(tag)
}

function cardStyle(tag) {
  return annotationCardStyle(tag)
}
function bindChipsForAnn(ann) {
  return formatAnnotationBindChips(parseAnnotationBind(ann))
}

function selectionImageForAnn(ann) {
  return selectionImageForAnnotation(ann)
}

function initials(row) {
  return userInitials(row)
}

function displayName(row) {
  return row.nickname || row.username || row.userId || '用户'
}

function isHidden(ann) {
  return isAnnotationHidden(ann)
}

function wasEdited(ann) {
  return wasAnnotationEdited(ann)
}

function canDelete(userId) {
  const me = currentUser.value?.userId
  return me === userId || currentUser.value?.role === 'ADMIN'
}

function canManageAnn(ann) {
  return canDelete(ann.userId)
}

function applyTemplate(tpl) {
  emit('apply-template', tpl)
}

function startEdit(ann) {
  editingId.value = ann.id
  editForm.content = ann.content || ''
  editForm.tag = ann.tag || ''
}

function cancelEdit() {
  editingId.value = null
  editForm.content = ''
  editForm.tag = ''
}

async function saveEdit(ann) {
  if (!editForm.content.trim()) return
  editSubmitting.value = true
  try {
    emit('save-annotation', {
      id: ann.id,
      content: editForm.content.trim(),
      tag: editForm.tag || null
    })
    cancelEdit()
  } finally {
    editSubmitting.value = false
  }
}

function onReply(row) {
  replyingTo.value = row
  tab.value = 'comment'
  commentComposeExpanded.value = true
}

function cancelReply() {
  replyingTo.value = null
}

function submitCommentClick() {
  emit('submit-comment', replyingTo.value?.id || null)
  replyingTo.value = null
  commentComposeExpanded.value = false
}
</script>

<style scoped>
.cwb-panel { display: flex; flex-direction: column; gap: 0; min-height: 0; flex: 1; height: 100%; background: transparent; padding: 8px 10px 0; }

.cwb-segment {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  padding: 3px;
  margin-bottom: 10px;
  background: #e2e8f0;
  border-radius: 8px;
  border: none;
}
.cwb-seg-btn {
  border: none;
  background: transparent;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: background 0.15s, color 0.15s;
}
.cwb-seg-btn.active { background: #fff; color: #0f172a; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06); }
.cwb-seg-count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: #e2e8f0;
  font-size: 11px;
  line-height: 18px;
}
.cwb-seg-btn.active .cwb-seg-count { background: #dbeafe; color: #1d4ed8; }

.cwb-tab-body { display: flex; flex-direction: column; flex: 1; min-height: 0; gap: 0; }
.cwb-scroll--fill { flex: 1; min-height: 0; height: 0; }
.cwb-scroll--fill :deep(.el-scrollbar) { height: 100%; }
.cwb-scroll--fill :deep(.el-scrollbar__wrap) { max-height: none; height: 100%; }

.cwb-filter-bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}
.cwb-filter-label { flex-shrink: 0; font-size: 12px; color: #64748b; font-weight: 500; }
.cwb-scope-select { flex: 1; min-width: 0; }
.cwb-scope-single { flex: 1; font-size: 12px; color: #334155; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cwb-scope-opt-count { float: right; font-size: 11px; color: #94a3b8; }
.cwb-tag-select { width: 88px; flex-shrink: 0; }

.cwb-list-zone {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #f8fafc;
  margin: 6px -12px 0;
  padding: 4px 12px;
  border-top: 1px solid #e2e8f0;
}
.cwb-list-zone--comment { margin-top: 0; border-top: none; padding-top: 4px; }

.cwb-ann-list { display: flex; flex-direction: column; gap: 6px; padding: 2px 0 8px; }
.cwb-ann-card {
  border: 1px solid #e2e8f0;
  border-left-width: 3px;
  border-radius: 6px;
  padding: 8px 10px;
  background: #fff;
  cursor: pointer;
  transition: box-shadow 0.15s;
}
.cwb-ann-card:hover { box-shadow: 0 1px 4px rgba(15, 23, 42, 0.06); }
.cwb-ann-card.is-hidden { opacity: 0.72; }
.cwb-ann-card.is-editing { cursor: default; }
.cwb-ann-head { display: flex; gap: 8px; margin-bottom: 4px; align-items: flex-start; }
.cwb-ann-meta { flex: 1; min-width: 0; }
.cwb-ann-meta-top { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; margin-bottom: 2px; width: 100%; }
.cwb-ann-meta-top strong { font-size: 12px; color: #1e293b; }
.cwb-time { font-size: 10px; color: #94a3b8; display: block; line-height: 1.3; }
.cwb-ann-body { margin: 0; font-size: 12px; line-height: 1.45; color: #334155; white-space: pre-wrap; word-break: break-word; }
.cwb-ann-actions { margin-left: auto; flex-shrink: 0; }
.cwb-ann-actions :deep(.el-button) { padding: 0 4px; font-size: 12px; }
.cwb-edit-tag { width: 100%; margin-bottom: 6px; }

.cwb-avatar {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #eef2ff;
  color: #4338ca;
  font-size: 10px;
  font-weight: 600;
}

.cwb-compose-zone {
  flex-shrink: 0;
  margin: 0 -12px;
  padding: 0 12px 10px;
  background: #fff;
  border-top: 1px solid #e2e8f0;
  box-shadow: 0 -2px 8px rgba(15, 23, 42, 0.04);
}
.cwb-compose-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.cwb-compose-toggle-title { font-size: 13px; font-weight: 600; color: #1e293b; flex-shrink: 0; }
.cwb-compose-toggle-icon { margin-left: auto; font-size: 11px; color: #2563eb; flex-shrink: 0; }
.cwb-compose {
  flex-shrink: 0;
  border-top: none;
  padding-top: 0;
  padding-bottom: 2px;
}
.cwb-bind-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 4px;
}
.cwb-bind-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  font-size: 11px;
  color: #475569;
  line-height: 1.4;
}
.cwb-compose-bind {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin: 0 0 6px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
}
.cwb-compose-bind-label {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  margin-right: 2px;
}
.cwb-compose-selection {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fffbeb;
  border: 1px solid #fde68a;
}
.cwb-compose-selection-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cwb-compose-selection-label {
  font-size: 11px;
  font-weight: 600;
  color: #92400e;
}
.cwb-compose-selection-text {
  font-size: 12px;
  color: #92400e;
  font-weight: 500;
}
.cwb-selection-preview {
  margin-bottom: 4px;
}
.cwb-selection-img {
  display: block;
  width: 100%;
  max-height: 72px;
  object-fit: contain;
  border-radius: 4px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
}
.cwb-selection-img--draft {
  max-height: 80px;
}
.cwb-compose-hint {
  margin: 0 0 6px;
  font-size: 11px;
  color: #94a3b8;
  line-height: 1.4;
}

.cwb-readonly-hint { margin: 0 0 6px; font-size: 11px; color: #94a3b8; }
.cwb-tag-pick { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 6px; }
.cwb-tag-pick-btn {
  border: 1px solid #e2e8f0;
  border-left: 3px solid var(--pick-accent, #94a3b8);
  background: #fff;
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 11px;
  color: #475569;
  cursor: pointer;
}
.cwb-tag-pick-btn.active {
  border-color: var(--pick-accent, #2563eb);
  background: #f8fafc;
  color: #0f172a;
  font-weight: 500;
}
.cwb-submit { width: 100%; margin-top: 6px; }

.cwb-comment-list { display: flex; flex-direction: column; gap: 14px; padding: 4px; }
.cwb-reply-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  padding: 6px 10px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 12px;
  color: #475569;
}
.mention-pop { margin-top: 6px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; max-height: 120px; overflow: auto; }
.mention-opt { display: block; width: 100%; padding: 8px 12px; border: none; background: transparent; text-align: left; cursor: pointer; font-size: 13px; }
.mention-opt:hover { background: #f8fafc; }
</style>

