<template>
  <el-drawer
    v-model="innerVisible"
    title="基础组件"
    direction="ltr"
    size="min(380px, 92vw)"
    class="dcp-drawer"
    append-to-body
    :modal="false"
    :show-close="true"
    destroy-on-close
    @closed="onDrawerClosed"
  >
    <div class="dcp-root">
      <div class="dcp-tip">
        <el-icon class="dcp-tip-icon" :size="16"><InfoFilled /></el-icon>
        <span>拖动或点击组件加入画布（默认 6×6 格）</span>
      </div>

      <div class="dcp-grid">
        <button
          v-for="item in DASHBOARD_BASIC_COMPONENTS"
          :key="item.type"
          type="button"
          class="dcp-tile"
          :class="{ 'is-available': isPaletteComponentAvailable(item.type) }"
          :draggable="isPaletteComponentAvailable(item.type)"
          :title="
            isPaletteComponentAvailable(item.type)
              ? `拖动或点击将${item.label}加入画布`
              : `${item.label}稍后开放`
          "
          @dragstart="onDragStart($event, item)"
          @dragend="onDragEnd"
          @click="onTileClick(item)"
        >
          <span class="dcp-tile-icon" aria-hidden="true">
            <component :is="iconMap[item.type]" />
          </span>
          <span class="dcp-tile-label">{{ item.label }}</span>
        </button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, h, onBeforeUnmount, ref, watch } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'
import {
  DASHBOARD_BASIC_COMPONENTS,
  isPaletteComponentAvailable,
  PALETTE_COMPONENT_MIME
} from '../../utils/dashboardBasicComponents.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'pick-basic', 'drag-start', 'drag-end'])

const dragStarted = ref(false)
let outsidePointerDown = null

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const iconMap = {
  text: () =>
    h('svg', { viewBox: '0 0 48 48', fill: 'none' }, [
      h('rect', {
        x: 8,
        y: 8,
        width: 32,
        height: 32,
        rx: 6,
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('path', {
        d: 'M16 18h16M16 24h12M16 30h14',
        stroke: 'currentColor',
        'stroke-width': 2.2,
        'stroke-linecap': 'round'
      })
    ]),
  image: () =>
    h('svg', { viewBox: '0 0 48 48', fill: 'none' }, [
      h('rect', {
        x: 7,
        y: 11,
        width: 34,
        height: 26,
        rx: 4,
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('circle', { cx: 17, cy: 21, r: 3.5, fill: 'currentColor' }),
      h('path', {
        d: 'M11 33l9-8 6 5 5-4 6 7',
        stroke: 'currentColor',
        'stroke-width': 2,
        'stroke-linejoin': 'round'
      })
    ]),
  video: () =>
    h('svg', { viewBox: '0 0 48 48', fill: 'none' }, [
      h('rect', {
        x: 8,
        y: 14,
        width: 32,
        height: 22,
        rx: 4,
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('path', {
        d: 'M22 20l10 6-10 6V20z',
        fill: 'currentColor'
      })
    ]),
  webpage: () =>
    h('svg', { viewBox: '0 0 48 48', fill: 'none' }, [
      h('rect', {
        x: 7,
        y: 10,
        width: 34,
        height: 28,
        rx: 4,
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('path', {
        d: 'M7 16h34',
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('path', {
        d: 'M14 28h8M14 32h12',
        stroke: 'currentColor',
        'stroke-width': 2,
        'stroke-linecap': 'round'
      })
    ]),
  carousel: () =>
    h('svg', { viewBox: '0 0 48 48', fill: 'none' }, [
      h('rect', {
        x: 12,
        y: 12,
        width: 24,
        height: 24,
        rx: 4,
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('rect', {
        x: 8,
        y: 16,
        width: 24,
        height: 20,
        rx: 4,
        stroke: 'currentColor',
        'stroke-width': 2,
        opacity: 0.55
      }),
      h('rect', {
        x: 16,
        y: 8,
        width: 24,
        height: 20,
        rx: 4,
        stroke: 'currentColor',
        'stroke-width': 2,
        opacity: 0.75
      })
    ]),
  time: () =>
    h('svg', { viewBox: '0 0 48 48', fill: 'none' }, [
      h('rect', {
        x: 10,
        y: 12,
        width: 28,
        height: 26,
        rx: 5,
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('path', {
        d: 'M10 20h28',
        stroke: 'currentColor',
        'stroke-width': 2
      }),
      h('circle', { cx: 18, cy: 28, r: 2, fill: 'currentColor' }),
      h('circle', { cx: 24, cy: 28, r: 2, fill: 'currentColor' }),
      h('circle', { cx: 30, cy: 28, r: 2, fill: 'currentColor' }),
      h('circle', { cx: 18, cy: 34, r: 2, fill: 'currentColor' }),
      h('circle', { cx: 24, cy: 34, r: 2, fill: 'currentColor' })
    ])
}

function onDragStart(event, item) {
  if (!isPaletteComponentAvailable(item.type)) {
    event.preventDefault()
    return
  }
  dragStarted.value = true
  event.dataTransfer?.setData(PALETTE_COMPONENT_MIME, item.type)
  event.dataTransfer?.setData('text/plain', item.type)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'copy'
    const el = event.currentTarget?.querySelector?.('.dcp-tile-icon')
    if (el && event.dataTransfer.setDragImage) {
      event.dataTransfer.setDragImage(el, 24, 24)
    }
  }
  emit('drag-start', { ...item })
}

function onDragEnd() {
  emit('drag-end')
  window.setTimeout(() => {
    dragStarted.value = false
  }, 0)
}

function onTileClick(item) {
  if (dragStarted.value) return
  emit('pick-basic', { ...item, source: 'click' })
}

function isInsidePalette(target) {
  if (!target?.closest) return false
  return Boolean(target.closest('.dcp-drawer'))
}

function shouldIgnoreOutsideClose(target) {
  if (!target?.closest) return false
  return Boolean(
    target.closest('.dge-palette-trigger, .el-overlay, .w-e-panel-content-container, .w-e-modal')
  )
}

function onOutsidePointerDown(event) {
  if (!props.modelValue) return
  if (dragStarted.value) return
  if (isInsidePalette(event.target)) return
  if (shouldIgnoreOutsideClose(event.target)) return
  innerVisible.value = false
}

function attachOutsideListener() {
  detachOutsideListener()
  outsidePointerDown = onOutsidePointerDown
  document.addEventListener('pointerdown', outsidePointerDown, true)
}

function detachOutsideListener() {
  if (!outsidePointerDown) return
  document.removeEventListener('pointerdown', outsidePointerDown, true)
  outsidePointerDown = null
}

function onDrawerClosed() {
  detachOutsideListener()
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) attachOutsideListener()
    else detachOutsideListener()
  }
)

onBeforeUnmount(detachOutsideListener)
</script>

<style scoped>
.dcp-root {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 100%;
}
.dcp-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: linear-gradient(90deg, #f5f0ff 0%, #eef4ff 100%);
  color: #4b5563;
  font-size: 13px;
  line-height: 1.45;
}
.dcp-tip-icon {
  flex-shrink: 0;
  color: #6366f1;
}
.dcp-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px 10px;
}
.dcp-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  user-select: none;
}
.dcp-tile:not(.is-available) {
  cursor: not-allowed;
  opacity: 0.55;
}
.dcp-tile.is-available[draggable='true'] {
  cursor: grab;
}
.dcp-tile.is-available[draggable='true']:active {
  cursor: grabbing;
}
.dcp-tile.is-available:active .dcp-tile-icon {
  transform: scale(0.97);
}
.dcp-tile-icon {
  display: grid;
  place-items: center;
  width: 100%;
  aspect-ratio: 1;
  max-width: 96px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafafa;
  color: #8b5cf6;
  transition: border-color 0.15s, background 0.15s, transform 0.15s;
}
.dcp-tile-icon :deep(svg) {
  width: 44%;
  height: 44%;
}
.dcp-tile.is-available:hover .dcp-tile-icon,
.dcp-tile.is-available:focus-visible .dcp-tile-icon {
  border-color: #c4b5fd;
  background: #faf5ff;
  transform: translateY(-1px);
}
.dcp-tile-label {
  font-size: 13px;
  color: #374151;
  line-height: 1.3;
}
</style>

<style>
.dcp-drawer {
  z-index: 4000 !important;
}
.dcp-drawer .el-drawer__header {
  margin-bottom: 0;
  padding: 16px 18px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.dcp-drawer .el-drawer__title {
  font-size: 16px;
  font-weight: 700;
  color: #111827;
}
.dcp-drawer .el-drawer__body {
  padding: 14px 16px 20px;
}
</style>
