<template>
  <div
    class="dcgi-root dge-card"
    :class="{ 'is-selected': selected, 'is-interactive': interactive }"
    @mouseenter="hovered = true"
    @mouseleave="onMouseLeave"
    @click.stop="emit('select')"
  >
    <div class="dge-card-meta">
      <div
        class="dge-card-titlewrap"
        :title="interactive ? '双击修改标题' : ''"
        @dblclick.stop="onTitleDblClick"
      >
        <el-input
          v-if="editing"
          :id="titleInputId"
          :model-value="titleDraft"
          size="small"
          maxlength="120"
          show-word-limit
          @update:model-value="emit('update:titleDraft', $event)"
          @blur="emit('commit-title')"
          @keydown.enter.prevent="emit('commit-title')"
        />
        <span v-else class="dge-card-title">{{ displayTitle }}</span>
      </div>
    </div>

    <div class="dge-chart-host">
      <slot />
    </div>

    <div
      v-show="interactive && (hovered || menuOpen)"
      class="dcgi-chrome dbw-chrome"
      @mousedown.stop
      @click.stop
    >
      <button
        type="button"
        class="dcgi-chrome-btn dbw-chrome-btn"
        :class="{ 'is-active': pinned }"
        title="固定位置"
        @mousedown.stop
        @click.stop="emit('pin')"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true">
          <path
            d="M16 12V4h1a1 1 0 0 0 0-2H7a1 1 0 0 0 0 2h1v8l-2 2v1h5.2v6l1.6 1 1.6-1v-6H18v-1l-2-2z"
          />
        </svg>
      </button>
      <el-dropdown
        trigger="click"
        teleported
        @visible-change="onMenuVisible"
        @command="onMenuCommand"
      >
        <button type="button" class="dcgi-chrome-btn dbw-chrome-btn" title="更多" @mousedown.stop @click.stop>
          <el-icon :size="14"><MoreFilled /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="edit" :disabled="!canEdit">
              <span class="dcgi-menu-item">
                <el-icon><EditPen /></el-icon>
                编辑
              </span>
            </el-dropdown-item>
            <el-dropdown-item command="remove" divided>
              <span class="dcgi-menu-item">
                <el-icon><Delete /></el-icon>
                移除
              </span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Delete, EditPen, MoreFilled } from '@element-plus/icons-vue'

const props = defineProps({
  selected: { type: Boolean, default: false },
  interactive: { type: Boolean, default: false },
  pinned: { type: Boolean, default: false },
  editing: { type: Boolean, default: false },
  titleDraft: { type: String, default: '' },
  displayTitle: { type: String, default: '' },
  titleInputId: { type: String, default: '' },
  canEdit: { type: Boolean, default: true }
})

const emit = defineEmits(['select', 'pin', 'edit', 'remove', 'start-edit-title', 'commit-title', 'update:titleDraft'])

const hovered = ref(false)
const menuOpen = ref(false)

function onMouseLeave() {
  if (!menuOpen.value) hovered.value = false
}

function onMenuVisible(visible) {
  menuOpen.value = visible
}

function onMenuCommand(command) {
  menuOpen.value = false
  if (command === 'edit') emit('edit')
  if (command === 'remove') emit('remove')
}

function onTitleDblClick() {
  if (!props.interactive) return
  emit('start-edit-title')
}
</script>

<style scoped>
.dcgi-root {
  position: relative;
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  cursor: pointer;
}
.dcgi-root.is-selected {
  border-color: #8b5cf6;
  box-shadow: 0 0 0 2px rgba(139, 92, 246, 0.25);
}
.dge-card-meta {
  flex-shrink: 0;
  padding: 6px 10px;
  font-size: 12px;
  color: #6b7280;
  border-bottom: 1px solid #f3f4f6;
  background: #fafafa;
}
.dge-card-titlewrap {
  flex: 1;
  min-width: 0;
  cursor: text;
}
.dge-card-title {
  font-weight: 600;
  color: #111827;
  font-size: 13px;
  line-height: 1.4;
  word-break: break-word;
}
.dge-chart-host {
  flex: 1;
  min-height: 0;
  padding: 6px 8px 8px;
}
.dcgi-chrome {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 5;
  display: flex;
  gap: 4px;
  padding: 4px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid #e5e7eb;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}
.dcgi-chrome-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
}
.dcgi-chrome-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
}
.dcgi-chrome-btn.is-active {
  color: #2563eb;
  background: #eff6ff;
}
.dcgi-menu-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
