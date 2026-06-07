<template>
  <div class="cwb-panel">
    <div class="cwb-tabs">
      <button type="button" :class="['cwb-tab', { active: tab === 'annotation' }]" @click="tab = 'annotation'">
        数据批注
        <span v-if="annotations.length" class="cwb-tab-badge">{{ annotations.length }}</span>
      </button>
      <button type="button" :class="['cwb-tab', { active: tab === 'comment' }]" @click="tab = 'comment'">
        协同评论
        <span v-if="comments.length" class="cwb-tab-badge">{{ comments.length }}</span>
      </button>
    </div>

    <template v-if="tab === 'annotation'">
      <div class="cwb-tab-body">
      <el-scrollbar v-if="nodes.length" ref="nodeListRef" class="cwb-node-list">
        <button
          v-for="node in nodes"
          :key="nodeKey(node)"
          type="button"
          :class="['cwb-node-row', { active: nodeKey(node) === activeNodeKey }]"
          :title="node.label"
          @click="$emit('select-node', node)"
        >
          <span class="cwb-node-label">{{ node.label }}</span>
          <span class="cwb-node-meta">
            <el-tag v-if="node.kind" size="small" effect="plain" class="cwb-node-kind">{{ nodeKindText(node.kind) }}</el-tag>
            <span
              v-if="nodeAnnCount(node)"
              class="cwb-node-count"
              :title="`${nodeAnnCount(node)} 条批注，见下方便签列表`"
            >{{ nodeAnnCount(node) }}条</span>
          </span>
        </button>
      </el-scrollbar>

      <p v-if="isBoardNodeActive" class="cwb-board-hint">
        整板批注在下方彩色便签中查看，不会在画布组件角标上出现。
      </p>

      <div class="cwb-tag-filter">
        <button
          v-for="f in tagFilters"
          :key="f.value"
          type="button"
          :class="['cwb-filter-chip', { active: tagFilter === f.value }]"
          @click="tagFilter = f.value"
        >
          {{ f.label }}
        </button>
      </div>

      <el-scrollbar class="cwb-scroll cwb-scroll--fill">
        <el-empty v-if="!displayAnnotations.length" :description="emptyAnnHint" />
        <div v-else class="cwb-sticky-list">
          <article
            v-for="ann in displayAnnotations"
            :key="ann.id"
            class="cwb-sticky"
            :style="stickyStyle(ann.tag)"
            @click="$emit('focus-annotation', ann)"
          >
            <div class="cwb-sticky-pin" />
            <div class="cwb-sticky-head">
              <span class="cwb-avatar">{{ initials(ann) }}</span>
              <div class="cwb-sticky-meta">
                <strong>{{ displayName(ann) }}</strong>
                <span class="cwb-time">{{ formatTime(ann.createdAt) }}</span>
              </div>
              <el-tag v-if="ann.tag" size="small" :type="tagPreset(ann.tag).tone" effect="dark" round>
                {{ tagPreset(ann.tag).emoji }} {{ ann.tag }}
              </el-tag>
            </div>
            <p class="cwb-sticky-body">{{ ann.content }}</p>
            <div v-if="canDelete(ann.userId)" class="cwb-sticky-actions">
              <el-button link type="danger" size="small" @click.stop="$emit('delete-annotation', ann.id)">删除</el-button>
            </div>
          </article>
        </div>
      </el-scrollbar>

      <div class="cwb-compose">
        <p class="cwb-compose-label">在「{{ activeNodeLabel }}」上添加批注</p>
        <p v-if="!canCompose" class="cwb-readonly-hint">当前无协作批注权限，仅可查看。</p>
        <template v-else>
        <div class="cwb-templates">
          <button
            v-for="tpl in ANNOTATION_TAG_PRESETS"
            :key="tpl.value"
            type="button"
            class="cwb-template-chip"
            :style="{ borderColor: tpl.stickyBorder, background: tpl.stickyBg }"
            @click="applyTemplate(tpl)"
          >
            <span>{{ tpl.emoji }}</span> {{ tpl.label }}
          </button>
        </div>
        <div class="cwb-compose-preview" :style="stickyStyle(annForm.tag)">
          <el-input
            :model-value="annForm.content"
            type="textarea"
            :rows="3"
            placeholder="描述业务发现、异常原因或经验总结…"
            @update:model-value="(v) => $emit('update:ann-content', v)"
          />
        </div>
        <el-button type="primary" class="cwb-submit" :loading="annSubmitting" @click="$emit('submit-annotation')">
          贴上便签
        </el-button>
        </template>
      </div>
      </div>
    </template>

    <template v-else>
      <div class="cwb-tab-body">
      <el-scrollbar class="cwb-scroll cwb-scroll--fill">
        <el-empty v-if="!comments.length" description="暂无评论，输入 @ 可提及同事" />
        <div v-else class="cwb-chat-list">
          <article v-for="c in comments" :key="c.id" class="cwb-chat-item">
            <span class="cwb-avatar cwb-avatar--chat">{{ initials(c) }}</span>
            <div class="cwb-chat-bubble">
              <div class="cwb-chat-head">
                <strong>{{ displayName(c) }}</strong>
                <span class="cwb-time">{{ formatTime(c.createdAt) }}</span>
                <el-button v-if="canDelete(c.userId)" link type="danger" size="small" @click="$emit('delete-comment', c.id)">删除</el-button>
              </div>
              <p class="cwb-chat-body" v-html="renderMentions(c.content)" />
            </div>
          </article>
        </div>
      </el-scrollbar>
      <div class="cwb-compose cwb-compose--comment">
        <p v-if="!canCompose" class="cwb-readonly-hint">当前无协作评论权限，仅可查看。</p>
        <template v-else>
        <el-input
          :model-value="comForm.content"
          type="textarea"
          :rows="3"
          placeholder="围绕看板讨论，输入 @ 提及成员…"
          @update:model-value="(v) => $emit('update:com-content', v)"
          @input="(v) => $emit('comment-input', v)"
        />
        <div v-if="mentionVisible && mentionCandidates.length" class="mention-pop">
          <button v-for="u in mentionCandidates" :key="u.userId" type="button" class="mention-opt" @click="$emit('insert-mention', u)">
            {{ u.nickname || u.username }} · {{ u.userId }}
          </button>
        </div>
        <el-button type="primary" class="cwb-submit" :loading="comSubmitting" @click="$emit('submit-comment')">发送评论</el-button>
        </template>
      </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { currentUser } from '../../store/session'
import {
  ANNOTATION_TAG_PRESETS,
  annotationStickyStyle,
  annotationTagPreset,
  countAnnotationsForNode,
  userInitials
} from '../../utils/collabAnnotation.js'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  activeNodeKey: { type: String, default: '' },
  activeNodeLabel: { type: String, default: '整板' },
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
  'submit-annotation',
  'update:ann-content',
  'delete-comment',
  'submit-comment',
  'update:com-content',
  'comment-input',
  'insert-mention',
  'apply-template'
])

const tab = ref('annotation')
const tagFilter = ref('')
const nodeListRef = ref(null)

watch(
  () => props.activeNodeKey,
  () => {
    tab.value = 'annotation'
    nextTick(() => {
      const root = nodeListRef.value?.$el || nodeListRef.value
      root?.querySelector?.('.cwb-node-row.active')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    })
  }
)

const tagFilters = [
  { value: '', label: '全部' },
  ...ANNOTATION_TAG_PRESETS.map((p) => ({ value: p.value, label: p.label }))
]

const displayAnnotations = computed(() => {
  const list = props.filteredAnnotations
  if (!tagFilter.value) return list
  return list.filter((a) => String(a.tag || '') === tagFilter.value)
})

const emptyAnnHint = computed(() => {
  if (tagFilter.value) return '当前标签下暂无批注'
  if (isBoardNodeActive.value) return '整板暂无批注，可在画布上方点击「整板批注」添加'
  return '点击左侧画布组件，贴上第一条便签'
})

const isBoardNodeActive = computed(() => {
  const node = props.nodes.find((n) => props.nodeKeyFn(n) === props.activeNodeKey)
  return node?.kind === 'dashboard'
})

function nodeKey(node) {
  return props.nodeKeyFn(node)
}

function nodeAnnCount(node) {
  return props.annotations.filter((ann) => countAnnotationsForNode(ann, node, props.dashboardId)).length
}

function nodeKindText(kind) {
  const k = String(kind || '').trim()
  if (k === 'dashboard') return '整板'
  if (k === 'chart') return '图表'
  if (k === 'basic' || k === 'widget') return '组件'
  if (k === 'advanced') return '分析'
  return k || '组件'
}

function tagPreset(tag) {
  return annotationTagPreset(tag)
}

function stickyStyle(tag) {
  return annotationStickyStyle(tag)
}

function initials(row) {
  return userInitials(row)
}

function displayName(row) {
  return row.nickname || row.username || row.userId || '用户'
}

function canDelete(userId) {
  const me = currentUser.value?.userId
  return me === userId || currentUser.value?.role === 'ADMIN'
}

function applyTemplate(tpl) {
  emit('apply-template', tpl)
}
</script>

<style scoped>
.cwb-panel { display: flex; flex-direction: column; gap: 10px; min-height: 0; flex: 1; height: 100%; }
.cwb-tab-body { display: flex; flex-direction: column; flex: 1; min-height: 0; gap: 10px; }
.cwb-scroll--fill { flex: 1; min-height: 0; height: 0; }
.cwb-scroll--fill :deep(.el-scrollbar) { height: 100%; }
.cwb-scroll--fill :deep(.el-scrollbar__wrap) { max-height: none; height: 100%; }
.cwb-compose { flex-shrink: 0; }
.cwb-tabs { flex-shrink: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 6px; padding: 4px; background: #f1f5f9; border-radius: 10px; }
.cwb-tab {
  border: none; background: transparent; padding: 8px 10px; border-radius: 8px;
  font-size: 13px; font-weight: 600; color: #64748b; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
}
.cwb-tab.active { background: #fff; color: #1e293b; box-shadow: 0 1px 3px rgba(15,23,42,.08); }
.cwb-tab-badge {
  min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px;
  background: #e2e8f0; font-size: 11px; line-height: 18px;
}
.cwb-tab.active .cwb-tab-badge { background: #dbeafe; color: #1d4ed8; }
.cwb-node-list { flex-shrink: 0; max-height: 140px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fafafa; }
.cwb-node-list :deep(.el-scrollbar__wrap) { max-height: 140px; }
.cwb-node-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-bottom: 1px solid #eef2f7;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.cwb-node-row:last-child { border-bottom: none; }
.cwb-node-row:hover { background: #f1f5f9; }
.cwb-node-row.active {
  background: #fffbeb;
  box-shadow: inset 0 0 0 2px #f59e0b;
}
.cwb-node-row.active .cwb-node-label { color: #b45309; font-weight: 600; }
.cwb-node-label {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.45;
  color: #334155;
  word-break: break-word;
  white-space: normal;
}
.cwb-node-row.active .cwb-node-label { color: #1d4ed8; font-weight: 600; }
.cwb-node-meta { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; }
.cwb-node-kind { flex-shrink: 0; }
.cwb-node-count {
  padding: 0 6px;
  border-radius: 999px;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
  white-space: nowrap;
}
.cwb-board-hint {
  margin: 0;
  padding: 6px 8px;
  font-size: 11px;
  line-height: 1.45;
  color: #64748b;
  background: #f8fafc;
  border-radius: 6px;
  border: 1px dashed #e2e8f0;
}
.cwb-tag-filter { display: flex; flex-wrap: wrap; gap: 6px; }
.cwb-filter-chip {
  border: 1px solid #e2e8f0; background: #fff; border-radius: 999px;
  padding: 2px 10px; font-size: 12px; color: #64748b; cursor: pointer;
}
.cwb-filter-chip.active { border-color: #409eff; color: #1d4ed8; background: #eff6ff; }
.cwb-sticky-list { display: flex; flex-direction: column; gap: 10px; padding: 2px 4px 8px; }
.cwb-sticky {
  position: relative; border: 1px solid; border-radius: 10px 10px 10px 4px;
  padding: 10px 12px 8px; box-shadow: 0 4px 14px rgba(15,23,42,.06);
  cursor: pointer; transition: transform .15s ease, box-shadow .15s ease;
}
.cwb-sticky:hover { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(15,23,42,.1); }
.cwb-sticky-pin {
  position: absolute; top: -6px; left: 14px; width: 12px; height: 12px;
  border-radius: 50%; background: var(--sticky-accent, #94a3b8);
  box-shadow: 0 1px 2px rgba(0,0,0,.2);
}
.cwb-sticky-head { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 6px; }
.cwb-sticky-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.cwb-sticky-meta strong { font-size: 13px; }
.cwb-time { font-size: 11px; color: #94a3b8; }
.cwb-sticky-body { margin: 0; font-size: 13px; line-height: 1.55; color: #334155; white-space: pre-wrap; }
.cwb-sticky-actions { margin-top: 4px; text-align: right; }
.cwb-avatar {
  width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #60a5fa, #3b82f6); color: #fff;
  font-size: 11px; font-weight: 700;
}
.cwb-compose { border-top: 1px dashed #e2e8f0; padding-top: 10px; }
.cwb-compose-label { margin: 0 0 8px; font-size: 12px; color: #64748b; }
.cwb-readonly-hint { margin: 0 0 8px; font-size: 12px; color: #94a3b8; }
.cwb-templates { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.cwb-template-chip {
  border: 1px solid; border-radius: 8px; padding: 4px 10px;
  font-size: 12px; cursor: pointer; transition: transform .12s ease;
}
.cwb-template-chip:hover { transform: scale(1.03); }
.cwb-compose-preview { border: 1px solid; border-radius: 10px; padding: 8px; }
.cwb-compose-preview :deep(.el-textarea__inner) {
  background: transparent; border: none; box-shadow: none; padding: 0; resize: none;
}
.cwb-submit { width: 100%; margin-top: 8px; }
.cwb-chat-list { display: flex; flex-direction: column; gap: 12px; padding: 4px; }
.cwb-chat-item { display: flex; gap: 8px; align-items: flex-start; }
.cwb-chat-bubble {
  flex: 1; min-width: 0; background: #f8fafc; border: 1px solid #e2e8f0;
  border-radius: 4px 12px 12px 12px; padding: 8px 10px;
}
.cwb-chat-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 4px; }
.cwb-chat-head strong { font-size: 13px; }
.cwb-chat-body { margin: 0; font-size: 13px; line-height: 1.55; }
.cwb-chat-body :deep(.mention) { color: #409eff; font-weight: 600; }
.mention-pop { margin-top: 6px; border: 1px solid #dcdfe6; border-radius: 8px; background: #fff; max-height: 120px; overflow: auto; }
.mention-opt { display: block; width: 100%; padding: 8px 12px; border: none; background: transparent; text-align: left; cursor: pointer; font-size: 13px; }
.mention-opt:hover { background: #f5f7fa; }
</style>
