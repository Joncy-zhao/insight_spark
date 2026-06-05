<template>
  <div
    class="dtw-root dbw-root"
    :class="rootClass"
    @mouseenter="hovered = true"
    @mouseleave="onMouseLeave"
    @dblclick.stop="onDoubleClick"
  >
    <div class="dtw-view dbw-content" :style="contentStyle">
      <div v-if="!isEmpty" class="dtw-html" v-html="config.content" />
      <div v-else class="dtw-placeholder">双击编辑文本</div>
    </div>

    <div v-if="interactive" class="dtw-drag-grip dbw-drag-grip" title="拖动">
      <span v-for="n in 6" :key="n" class="dtw-drag-dot" />
    </div>

    <div
      v-show="interactive && (hovered || menuOpen)"
      class="dtw-chrome dbw-chrome"
      @mousedown.stop
      @click.stop
    >
      <button
        type="button"
        class="dtw-chrome-btn dbw-chrome-btn"
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
        popper-class="dtw-dropdown-popper"
        @visible-change="onMenuVisible"
        @command="onMenuCommand"
      >
        <button type="button" class="dtw-chrome-btn dbw-chrome-btn" title="更多" @mousedown.stop @click.stop>
          <el-icon :size="14"><MoreFilled /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="edit">
              <span class="dtw-menu-item">
                <el-icon><EditPen /></el-icon>
                编辑
              </span>
            </el-dropdown-item>
            <el-dropdown-item command="remove" divided>
              <span class="dtw-menu-item">
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
import { computed, nextTick, ref } from 'vue'
import { Delete, EditPen, MoreFilled } from '@element-plus/icons-vue'
import {
  isTextContentEmpty,
  normalizeTextWidgetConfig,
  textWidgetContentStyle
} from '../../utils/dashboardWidgetText.js'

const props = defineProps({
  config: { type: Object, default: () => ({}) },
  interactive: { type: Boolean, default: false },
  pinned: { type: Boolean, default: false }
})

const emit = defineEmits(['edit', 'remove', 'pin'])

const config = computed(() => normalizeTextWidgetConfig(props.config))
const hovered = ref(false)
const menuOpen = ref(false)

const isEmpty = computed(() => isTextContentEmpty(config.value.content))
const contentStyle = computed(() => textWidgetContentStyle(config.value))

const rootClass = computed(() => ({
  'is-interactive': props.interactive,
  'is-empty': isEmpty.value
}))

function onMouseLeave() {
  if (!menuOpen.value) hovered.value = false
}

function onMenuVisible(visible) {
  menuOpen.value = visible
}

function onMenuCommand(command) {
  menuOpen.value = false
  if (command === 'edit') nextTick(() => emit('edit'))
  if (command === 'remove') nextTick(() => emit('remove'))
}

function onDoubleClick() {
  if (!props.interactive) return
  emit('edit')
}
</script>

<style scoped>
.dtw-root {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 80px;
  background: #fff;
  border-radius: 8px;
  box-sizing: border-box;
  overflow: hidden;
}
.dtw-root.is-empty {
  background: #f9fafb;
  border: 1px dashed #d1d5db;
}
.dtw-view {
  width: 100%;
  height: 100%;
}
.dtw-html {
  width: 100%;
}
.dtw-html :deep(p) {
  margin: 0 0 0.35em;
}
.dtw-html :deep(p:last-child) {
  margin-bottom: 0;
}
.dtw-placeholder {
  color: #9ca3af;
  font-size: 14px;
  user-select: none;
}
.dtw-drag-grip {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 4;
  display: grid;
  grid-template-columns: repeat(2, 4px);
  gap: 3px;
  padding: 6px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #e5e7eb;
  cursor: grab;
  opacity: 0;
  transition: opacity 0.15s;
}
.dtw-root.is-interactive:hover .dtw-drag-grip,
.dtw-root.is-interactive:focus-within .dtw-drag-grip {
  opacity: 1;
}
.dtw-drag-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #9ca3af;
}
.dtw-chrome {
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
.dtw-chrome-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #4b5563;
  cursor: pointer;
}
.dtw-chrome-btn:hover {
  background: #f3f4f6;
}
.dtw-chrome-btn.is-active {
  color: #6366f1;
  background: #eef2ff;
}
.dtw-menu-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>

<style>
.dtw-dropdown-popper .el-dropdown-menu__item {
  min-width: 120px;
}
</style>
