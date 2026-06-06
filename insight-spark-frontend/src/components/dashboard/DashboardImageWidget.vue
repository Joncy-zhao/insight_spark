<template>
  <div
    class="diw-root dbw-root"
    :class="rootClass"
    @mouseenter="hovered = true"
    @mouseleave="onMouseLeave"
  >
    <img
      v-if="config.src"
      class="diw-img dbw-content"
      :src="config.src"
      :style="mediaStyle"
      alt=""
    />
    <div v-else class="diw-placeholder dbw-content">
      <span class="diw-placeholder-plus">+</span>
      <span class="diw-placeholder-text">选择图片</span>
    </div>

    <div v-if="interactive" class="diw-drag-grip dbw-drag-grip" title="拖动">
      <span v-for="n in 6" :key="n" class="diw-drag-dot" />
    </div>

    <div
      v-show="interactive && (hovered || menuOpen)"
      class="diw-chrome dbw-chrome"
      @mousedown.stop
      @click.stop
    >
      <button
        type="button"
        class="diw-chrome-btn dbw-chrome-btn"
        :class="{ 'is-active': pinned }"
        title="固定位置"
        @mousedown.stop
        @click.stop="emit('pin')"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true">
          <path d="M16 12V4h1a1 1 0 0 0 0-2H7a1 1 0 0 0 0 2h1v8l-2 2v1h5.2v6l1.6 1 1.6-1v-6H18v-1l-2-2z" />
        </svg>
      </button>
      <el-dropdown
        trigger="click"
        teleported
        popper-class="diw-dropdown-popper"
        @visible-change="onMenuVisible"
        @command="onMenuCommand"
      >
        <button type="button" class="diw-chrome-btn dbw-chrome-btn" title="更多" @mousedown.stop @click.stop>
          <el-icon :size="14"><MoreFilled /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="edit">
              <span class="diw-menu-item"><el-icon><EditPen /></el-icon>编辑</span>
            </el-dropdown-item>
            <el-dropdown-item command="remove" divided>
              <span class="diw-menu-item"><el-icon><Delete /></el-icon>移除</span>
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
import { imageWidgetMediaStyle, normalizeImageWidgetConfig } from '../../utils/dashboardWidgetImage.js'

const props = defineProps({
  config: { type: Object, default: () => ({}) },
  interactive: { type: Boolean, default: false },
  pinned: { type: Boolean, default: false }
})

const emit = defineEmits(['edit', 'remove', 'pin'])

const config = computed(() => normalizeImageWidgetConfig(props.config))
const hovered = ref(false)
const menuOpen = ref(false)

const rootClass = computed(() => ({
  'is-rounded': config.value.rounded,
  'is-bordered': config.value.border,
  'has-src': Boolean(config.value.src),
  'is-interactive': props.interactive
}))

const mediaStyle = computed(() => imageWidgetMediaStyle(config.value))

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
</script>

<style scoped>
.diw-root {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 120px;
  background: #f9fafb;
  overflow: hidden;
}
.diw-root.is-rounded {
  border-radius: 8px;
}
.diw-root.is-bordered {
  border: 1px solid #d1d5db;
}
.diw-img {
  display: block;
  width: 100%;
  height: 100%;
}
.diw-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 100%;
  min-height: 120px;
  color: #9ca3af;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  box-sizing: border-box;
}
.diw-placeholder-plus {
  font-size: 28px;
  line-height: 1;
  color: #3b82f6;
}
.diw-placeholder-text {
  font-size: 13px;
}
.diw-chrome {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 6px;
  pointer-events: auto;
}
.diw-drag-grip {
  position: absolute;
  left: 6px;
  top: 50%;
  z-index: 5;
  transform: translateY(-50%);
  display: grid;
  grid-template-columns: repeat(2, 3px);
  grid-template-rows: repeat(3, 3px);
  gap: 3px;
  padding: 6px 4px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.1);
  cursor: grab;
  opacity: 0;
  transition: opacity 0.15s;
  pointer-events: auto;
}
.diw-root.is-interactive:hover .diw-drag-grip,
.diw-root.is-interactive:focus-within .diw-drag-grip {
  opacity: 1;
}
.diw-drag-grip:active {
  cursor: grabbing;
}
.diw-drag-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #9ca3af;
}
.diw-chrome-btn {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.95);
  color: #374151;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  cursor: pointer;
}
.diw-chrome-btn:hover,
.diw-chrome-btn.is-active {
  color: #2563eb;
}
.diw-menu-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>

<style>
.diw-dropdown-popper {
  z-index: 4500 !important;
}
</style>
