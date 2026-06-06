<template>
  <div
    ref="rootRef"
    class="dcw-root dbw-root"
    :class="rootClass"
    :style="{ '--dcw-indicator-color': config.indicatorColor }"
    @mouseenter="hovered = true"
    @mouseleave="onMouseLeave"
  >
    <el-carousel
      v-if="config.images.length"
      class="dcw-carousel dbw-content"
      :height="carouselHeight"
      :interval="config.autoplay ? config.interval : 0"
      :autoplay="config.autoplay"
      :indicator-position="config.showIndicator ? undefined : 'none'"
      trigger="click"
    >
      <el-carousel-item v-for="(src, index) in config.images" :key="`${index}-${src.slice(0, 24)}`">
        <img class="dcw-slide" :src="src" :style="mediaStyle" alt="" />
      </el-carousel-item>
    </el-carousel>
    <div v-else class="dcw-placeholder dbw-content">
      <span class="dcw-placeholder-plus">+</span>
      <span class="dcw-placeholder-text">添加轮播图</span>
    </div>

    <div v-if="interactive" class="dcw-drag-grip dbw-drag-grip" title="拖动">
      <span v-for="n in 6" :key="n" class="dcw-drag-dot" />
    </div>

    <div
      v-show="interactive && (hovered || menuOpen)"
      class="dcw-chrome dbw-chrome"
      @mousedown.stop
      @click.stop
    >
      <button
        type="button"
        class="dcw-chrome-btn dbw-chrome-btn"
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
        popper-class="dcw-dropdown-popper"
        @visible-change="onMenuVisible"
        @command="onMenuCommand"
      >
        <button type="button" class="dcw-chrome-btn dbw-chrome-btn" title="更多" @mousedown.stop @click.stop>
          <el-icon :size="14"><MoreFilled /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="edit">
              <span class="dcw-menu-item"><el-icon><EditPen /></el-icon>编辑</span>
            </el-dropdown-item>
            <el-dropdown-item command="remove" divided>
              <span class="dcw-menu-item"><el-icon><Delete /></el-icon>移除</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { Delete, EditPen, MoreFilled } from '@element-plus/icons-vue'
import { carouselWidgetMediaStyle, normalizeCarouselWidgetConfig } from '../../utils/dashboardWidgetCarousel.js'

const props = defineProps({
  config: { type: Object, default: () => ({}) },
  interactive: { type: Boolean, default: false },
  pinned: { type: Boolean, default: false }
})

const emit = defineEmits(['edit', 'remove', 'pin'])

const config = computed(() => normalizeCarouselWidgetConfig(props.config))
const hovered = ref(false)
const menuOpen = ref(false)
const carouselHeight = ref('200px')
const rootRef = ref(null)

const rootClass = computed(() => ({
  'is-rounded': config.value.rounded,
  'is-bordered': config.value.border,
  'has-images': config.value.images.length > 0,
  'is-interactive': props.interactive,
  'hide-indicator': !config.value.showIndicator
}))

const mediaStyle = computed(() => carouselWidgetMediaStyle(config.value))

function measureHeight() {
  const el = rootRef.value
  if (!el) return
  const h = el.clientHeight
  if (h > 0) carouselHeight.value = `${h}px`
}

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

let resizeObserver = null

onMounted(() => {
  measureHeight()
  if (rootRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => measureHeight())
    resizeObserver.observe(rootRef.value)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

watch(
  () => config.value.images.length,
  () => nextTick(measureHeight)
)
</script>

<style scoped>
.dcw-root {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 120px;
  background: #f9fafb;
  overflow: hidden;
}
.dcw-root.is-rounded {
  border-radius: 8px;
}
.dcw-root.is-bordered {
  border: 1px solid #d1d5db;
}
.dcw-carousel {
  width: 100%;
  height: 100%;
}
.dcw-carousel :deep(.el-carousel__container) {
  height: 100%;
}
.dcw-slide {
  width: 100%;
  height: 100%;
  display: block;
}
.dcw-root.hide-indicator :deep(.el-carousel__indicators) {
  display: none;
}
.dcw-root :deep(.el-carousel__indicator.is-active .el-carousel__button) {
  background-color: var(--dcw-indicator-color, #9ca3af);
}
.dcw-placeholder {
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
.dcw-placeholder-plus {
  font-size: 28px;
  line-height: 1;
  color: #3b82f6;
}
.dcw-placeholder-text {
  font-size: 13px;
}
.dcw-chrome {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 6px;
  pointer-events: auto;
}
.dcw-drag-grip {
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
.dcw-root.is-interactive:hover .dcw-drag-grip,
.dcw-root.is-interactive:focus-within .dcw-drag-grip {
  opacity: 1;
}
.dcw-drag-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #9ca3af;
}
.dcw-chrome-btn {
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
.dcw-chrome-btn:hover,
.dcw-chrome-btn.is-active {
  color: #2563eb;
}
.dcw-menu-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>

<style>
.dcw-dropdown-popper {
  z-index: 4500 !important;
}
</style>
