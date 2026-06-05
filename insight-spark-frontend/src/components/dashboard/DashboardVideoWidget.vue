<template>
  <div
    class="dvw-root dbw-root"
    :class="rootClass"
    @mouseenter="hovered = true"
    @mouseleave="onMouseLeave"
  >
    <video
      v-if="config.src"
      ref="videoRef"
      class="dvw-video dbw-content"
      :src="config.src"
      :style="videoStyle"
      :autoplay="config.autoplay"
      :muted="config.autoplay"
      :loop="config.autoplay"
      controls
      playsinline
      preload="metadata"
      @loadedmetadata="onLoaded"
    />
    <div
      v-else
      class="dvw-placeholder dbw-content"
    >
      <span class="dvw-placeholder-plus">+</span>
      <span class="dvw-placeholder-text">选择视频</span>
    </div>

    <div
      v-if="interactive"
      class="dvw-drag-grip dbw-drag-grip"
      title="拖动"
    >
      <span v-for="n in 6" :key="n" class="dvw-drag-dot" />
    </div>

    <div
      v-if="config.showIndicator && config.src && !interactive"
      class="dvw-indicator"
      :style="{ '--dvw-indicator-color': config.indicatorColor }"
    >
      <span class="dvw-indicator-dot is-active" />
    </div>

    <div
      v-show="interactive && (hovered || menuOpen)"
      class="dvw-chrome dbw-chrome"
      @mousedown.stop
      @click.stop
    >
      <button
        type="button"
        class="dvw-chrome-btn dbw-chrome-btn"
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
        popper-class="dvw-dropdown-popper"
        @visible-change="onMenuVisible"
        @command="onMenuCommand"
      >
        <button type="button" class="dvw-chrome-btn dbw-chrome-btn" title="更多" @mousedown.stop @click.stop>
          <el-icon :size="14"><MoreFilled /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="edit">
              <span class="dvw-menu-item">
                <el-icon><EditPen /></el-icon>
                编辑
              </span>
            </el-dropdown-item>
            <el-dropdown-item command="remove" divided>
              <span class="dvw-menu-item">
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
import { computed, nextTick, ref, watch } from 'vue'
import { Delete, EditPen, MoreFilled } from '@element-plus/icons-vue'
import { normalizeVideoWidgetConfig, VIDEO_OBJECT_FIT } from '../../utils/dashboardWidgetVideo.js'

const props = defineProps({
  config: { type: Object, default: () => ({}) },
  /** 设计器内：悬停显示操作栏 */
  interactive: { type: Boolean, default: false },
  pinned: { type: Boolean, default: false }
})

const emit = defineEmits(['edit', 'remove', 'pin'])

const config = computed(() => normalizeVideoWidgetConfig(props.config))
const videoRef = ref(null)
const hovered = ref(false)
const menuOpen = ref(false)

const rootClass = computed(() => ({
  'is-rounded': config.value.rounded,
  'is-bordered': config.value.border,
  'has-src': Boolean(config.value.src),
  'is-interactive': props.interactive
}))

const videoStyle = computed(() => ({
  objectFit: config.value.objectFit === VIDEO_OBJECT_FIT.STRETCH ? 'fill' : 'cover'
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

function onLoaded() {
  if (!config.value.autoplay || !videoRef.value) return
  videoRef.value.play().catch(() => {})
}

watch(
  () => config.value.src,
  () => {
    if (!config.value.autoplay) return
    requestAnimationFrame(() => onLoaded())
  }
)
</script>

<style scoped>
.dvw-root {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 120px;
  background: #4b5563;
  overflow: hidden;
}
.dvw-root.is-rounded {
  border-radius: 8px;
}
.dvw-root.is-bordered {
  border: 1px solid #d1d5db;
}
.dvw-root.has-src {
  background: #111827;
}
.dvw-video {
  display: block;
  width: 100%;
  height: 100%;
  background: #000;
}
.dvw-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 100%;
  min-height: 120px;
  color: #9ca3af;
  background: #f9fafb;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  box-sizing: border-box;
}
.dvw-placeholder-plus {
  font-size: 28px;
  line-height: 1;
  color: #c4b5fd;
}
.dvw-placeholder-text {
  font-size: 13px;
}
.dvw-chrome {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 6px;
  pointer-events: auto;
}
.dvw-drag-grip {
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
.dvw-root.is-interactive:hover .dvw-drag-grip,
.dvw-root.is-interactive:focus-within .dvw-drag-grip {
  opacity: 1;
}
.dvw-drag-grip:active {
  cursor: grabbing;
}
.dvw-drag-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #9ca3af;
}
.dvw-chrome-btn {
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
  transition: background 0.15s, color 0.15s;
}
.dvw-chrome-btn:hover,
.dvw-chrome-btn.is-active {
  background: #fff;
  color: #4f46e5;
}
.dvw-menu-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.dvw-indicator {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 44px;
  display: flex;
  justify-content: center;
  gap: 6px;
  pointer-events: none;
}
.dvw-indicator-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.45);
}
.dvw-indicator-dot.is-active {
  background: var(--dvw-indicator-color, #9ca3af);
}
</style>

<style>
.dvw-dropdown-popper {
  z-index: 4500 !important;
}
</style>
