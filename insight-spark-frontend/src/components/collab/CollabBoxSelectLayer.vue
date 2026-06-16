<template>
  <div
    ref="rootRef"
    class="cbs-layer"
    :class="{ 'cbs-layer--active': enabled && active }"
  >
    <div class="cbs-content" ref="contentRef">
      <slot />
    </div>
    <!-- 透明遮罩：盖住 ECharts 画布，否则图表 @pointerdown.stop 会吞掉拖拽 -->
    <div
      v-if="enabled && active"
      class="cbs-hitmask"
      @pointerdown="onPointerDown"
    />
    <template v-if="showOverlays">
      <div
        v-for="overlay in displayOverlays"
        :key="overlay.id"
        class="cbs-rect cbs-rect--saved"
        :class="{ 'cbs-rect--focused': String(overlay.id) === String(focusedId) }"
        :style="{ ...rectStyle(overlay.rect), '--cbs-accent': overlay.accent || '#2563eb' }"
      />
    </template>
    <div v-if="showOverlays && draftStyle" class="cbs-rect cbs-rect--draft" :style="draftStyle" />
    <div v-if="drawingStyle" class="cbs-rect cbs-rect--drawing" :style="drawingStyle" />
    <div v-if="capturing" class="cbs-capturing">正在生成框选截图…</div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { captureSelectionThumbnail, normalizeSelectionRect, rectToPercentStyle } from '../../utils/collabBoxSelect.js'

const props = defineProps({
  enabled: { type: Boolean, default: false },
  active: { type: Boolean, default: false },
  draftRect: { type: Object, default: null },
  overlays: { type: Array, default: () => [] },
  focusedId: { type: [String, Number], default: null },
  showOverlays: { type: Boolean, default: true }
})

const emit = defineEmits(['box-select'])

const rootRef = ref(null)
const contentRef = ref(null)
const drawing = ref(null)
const capturing = ref(false)

let startPx = null
let dragging = false

function onPointerDown(e) {
  if (!props.enabled || !props.active || e.button !== 0) return
  const root = rootRef.value
  if (!root) return
  const bounds = root.getBoundingClientRect()
  startPx = { x: e.clientX - bounds.left, y: e.clientY - bounds.top }
  dragging = true
  drawing.value = { x: startPx.x, y: startPx.y, w: 0, h: 0 }
  e.preventDefault()
  e.stopPropagation()
  if (e.target?.setPointerCapture) {
    try {
      e.target.setPointerCapture(e.pointerId)
    } catch {
      // ignore
    }
  }
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
}

function onPointerMove(e) {
  if (!dragging || !startPx || !rootRef.value) return
  const bounds = rootRef.value.getBoundingClientRect()
  const cx = Math.max(0, Math.min(bounds.width, e.clientX - bounds.left))
  const cy = Math.max(0, Math.min(bounds.height, e.clientY - bounds.top))
  const x = Math.min(startPx.x, cx)
  const y = Math.min(startPx.y, cy)
  drawing.value = {
    x,
    y,
    w: Math.abs(cx - startPx.x),
    h: Math.abs(cy - startPx.y)
  }
}

function onPointerUp(e) {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  const root = rootRef.value
  const content = contentRef.value
  const draft = drawing.value
  dragging = false
  drawing.value = null
  startPx = null
  if (!root || !draft) return
  const bounds = root.getBoundingClientRect()
  if (draft.w < 8 || draft.h < 8 || bounds.width < 1 || bounds.height < 1) return
  const normalized = normalizeSelectionRect({
    x: draft.x / bounds.width,
    y: draft.y / bounds.height,
    w: draft.w / bounds.width,
    h: draft.h / bounds.height
  })
  if (!normalized) return
  e.stopPropagation()
  finishBoxSelect(normalized, content)
}

async function finishBoxSelect(normalized, content) {
  capturing.value = true
  let image = null
  try {
    if (content) {
      image = await captureSelectionThumbnail(content, normalized)
    }
  } catch {
    image = null
  } finally {
    capturing.value = false
  }
  emit('box-select', { rect: normalized, image })
}

const drawingStyle = computed(() => {
  if (!drawing.value || !rootRef.value) return null
  const d = drawing.value
  const bounds = rootRef.value.getBoundingClientRect()
  if (bounds.width < 1 || bounds.height < 1) return null
  return {
    left: `${(d.x / bounds.width) * 100}%`,
    top: `${(d.y / bounds.height) * 100}%`,
    width: `${(d.w / bounds.width) * 100}%`,
    height: `${(d.h / bounds.height) * 100}%`
  }
})

const draftStyle = computed(() => rectToPercentStyle(props.draftRect))

const displayOverlays = computed(() =>
  (props.overlays || []).filter((o) => o?.rect && normalizeSelectionRect(o.rect))
)

function rectStyle(rect) {
  return rectToPercentStyle(rect) || {}
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})
</script>

<style scoped>
.cbs-layer {
  position: relative;
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.cbs-content {
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  pointer-events: none;
}
.cbs-layer--active .cbs-content {
  pointer-events: none;
}
.cbs-hitmask {
  position: absolute;
  inset: 0;
  z-index: 4;
  cursor: crosshair;
  touch-action: none;
  background: transparent;
}
.cbs-rect {
  position: absolute;
  pointer-events: none;
  box-sizing: border-box;
  z-index: 6;
  border-radius: 2px;
}
.cbs-rect--draft,
.cbs-rect--drawing {
  border: 2px dashed #f59e0b;
  background: rgba(245, 158, 11, 0.18);
  box-shadow: 0 0 0 1px rgba(245, 158, 11, 0.25);
}
.cbs-rect--saved {
  border: 2px solid var(--cbs-accent, #2563eb);
  background: color-mix(in srgb, var(--cbs-accent, #2563eb) 14%, transparent);
}
.cbs-rect--focused {
  border-color: #f59e0b;
  background: rgba(245, 158, 11, 0.2);
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.35);
  z-index: 7;
}
.cbs-capturing {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  z-index: 8;
  padding: 6px 12px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.72);
  color: #fff;
  font-size: 12px;
  pointer-events: none;
}
</style>
