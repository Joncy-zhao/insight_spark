<template>
  <article class="cct-item" :class="{ 'cct-item--reply': depth > 0 }">
    <span class="cct-avatar">{{ initials(row) }}</span>
    <div class="cct-body">
      <div class="cct-head">
        <strong>{{ displayName(row) }}</strong>
        <span class="cct-time">{{ formatTime(row.createdAt) }}</span>
        <div v-if="canCompose || canDelete(row.userId)" class="collab-row-actions cct-actions">
          <el-button v-if="canCompose" link type="primary" size="small" @click="$emit('reply', row)">回复</el-button>
          <el-button v-if="canDelete(row.userId)" link type="danger" size="small" @click="$emit('delete-comment', row.id)">删除</el-button>
        </div>
      </div>
      <p class="cct-content" v-html="renderMentions(row.content)" />
      <CollabCommentThread
        v-for="child in row.replies || []"
        :key="child.id"
        :row="child"
        :depth="depth + 1"
        :format-time="formatTime"
        :render-mentions="renderMentions"
        :can-compose="canCompose"
        :can-delete="canDelete"
        @reply="$emit('reply', $event)"
        @delete-comment="$emit('delete-comment', $event)"
      />
    </div>
  </article>
</template>

<script setup>
import { userInitials } from '../../utils/collabAnnotation.js'

defineOptions({ name: 'CollabCommentThread' })

defineProps({
  row: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  formatTime: { type: Function, required: true },
  renderMentions: { type: Function, required: true },
  canCompose: { type: Boolean, default: true },
  canDelete: { type: Function, required: true }
})

defineEmits(['reply', 'delete-comment'])

function initials(row) {
  return userInitials(row)
}
function displayName(row) {
  return row.nickname || row.username || row.userId || '用户'
}
</script>

<style scoped>
.cct-item { display: flex; gap: 10px; align-items: flex-start; }
.cct-item--reply { margin-top: 10px; padding-left: 8px; border-left: 2px solid #e2e8f0; }
.cct-avatar {
  width: 32px; height: 32px; border-radius: 6px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: #eef2ff; color: #4338ca; font-size: 12px; font-weight: 600;
}
.cct-body { flex: 1; min-width: 0; }
.cct-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 4px; }
.cct-head strong { font-size: 13px; color: #1e293b; }
.cct-time { font-size: 12px; color: #94a3b8; }
.cct-actions { margin-left: auto; }
.cct-content { margin: 0; font-size: 13px; line-height: 1.6; color: #334155; white-space: pre-wrap; }
.cct-content :deep(.mention) { color: #2563eb; font-weight: 600; }
</style>
