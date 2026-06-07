<template>
  <DashboardBasicWidgetInspectorShell
    v-model="innerVisible"
    :widget-title="widgetTitle"
    @update:widget-title="emit('update:widgetTitle', $event)"
    @commit:widget-title="emit('commit:widgetTitle', $event)"
    @closed="emit('closed')"
  >
    <div class="dbwi-upload-row">
      <div class="dbwi-upload" @click="triggerPick">
        <img
          v-if="localConfig.images[0]"
          :src="localConfig.images[0]"
          class="dbwi-upload-preview"
          alt=""
        />
        <div v-else class="dbwi-upload-empty">
          <span class="dbwi-upload-plus">+</span>
        </div>
        <input
          ref="fileInputRef"
          type="file"
          :accept="CAROUSEL_IMAGE_ACCEPT"
          multiple
          class="dbwi-file-input"
          @change="onFilesChange"
        />
      </div>
      <p class="dbwi-hint">仅支持 png/jpg/webp，单张不能超过 2M；可一次选择多张</p>
    </div>

    <div v-if="localConfig.images.length" class="dcwi-thumb-grid">
      <div v-for="(src, index) in localConfig.images" :key="`${index}-${src.slice(0, 16)}`" class="dcwi-thumb">
        <img :src="src" alt="" />
        <button type="button" class="dcwi-thumb-remove" title="移除" @click="removeImage(index)">×</button>
      </div>
    </div>

    <div class="dbwi-check-row">
      <label class="dbwi-check-item">
        <el-checkbox v-model="localConfig.rounded" />
        <span>圆角</span>
      </label>
      <label class="dbwi-check-item">
        <el-checkbox v-model="localConfig.border" />
        <span>边框</span>
      </label>
    </div>

    <div>
      <span class="dbwi-field-label">图片显示方式</span>
      <el-radio-group v-model="localConfig.objectFit">
        <el-radio :label="CAROUSEL_OBJECT_FIT.COVER">覆盖</el-radio>
        <el-radio :label="CAROUSEL_OBJECT_FIT.STRETCH">拉伸</el-radio>
      </el-radio-group>
    </div>

    <div class="dbwi-interval-row">
      <label class="dbwi-check-item">
        <el-checkbox v-model="localConfig.autoplay" />
        <span>自动播放</span>
      </label>
      <span class="dbwi-check-item">
        <span>时间间隔</span>
        <el-input-number
          v-model="localConfig.interval"
          :min="500"
          :max="60000"
          :step="500"
          size="small"
          controls-position="right"
        />
      </span>
    </div>

    <div class="dbwi-indicator-row">
      <label class="dbwi-check-item">
        <el-checkbox v-model="localConfig.showIndicator" />
        <span>显示指示器</span>
      </label>
      <el-color-picker v-model="localConfig.indicatorColor" size="small" />
    </div>
  </DashboardBasicWidgetInspectorShell>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import DashboardBasicWidgetInspectorShell from './DashboardBasicWidgetInspectorShell.vue'
import {
  CAROUSEL_IMAGE_ACCEPT,
  CAROUSEL_IMAGE_MAX_BYTES,
  CAROUSEL_OBJECT_FIT,
  carouselWidgetConfigEqual,
  normalizeCarouselWidgetConfig
} from '../../utils/dashboardWidgetCarousel.js'
import { IMAGE_WIDGET_MAX_DATA_URL_LEN } from '../../utils/dashboardWidgetImage.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  config: { type: Object, default: () => ({}) },
  widgetTitle: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'update:config', 'update:widgetTitle', 'commit:widgetTitle', 'closed'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const localConfig = ref(normalizeCarouselWidgetConfig(props.config))
const fileInputRef = ref(null)

watch(
  () => props.config,
  (v) => {
    const next = normalizeCarouselWidgetConfig(v)
    if (carouselWidgetConfigEqual(next, localConfig.value)) return
    localConfig.value = next
  },
  { deep: true }
)

watch(
  localConfig,
  (v) => {
    const next = normalizeCarouselWidgetConfig(v)
    if (carouselWidgetConfigEqual(next, props.config)) return
    emit('update:config', next)
  },
  { deep: true }
)

function triggerPick() {
  fileInputRef.value?.click?.()
}

function removeImage(index) {
  localConfig.value.images = localConfig.value.images.filter((_, i) => i !== index)
}

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const type = String(file.type || '').toLowerCase()
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(type)) {
      reject(new Error('format'))
      return
    }
    if (file.size > CAROUSEL_IMAGE_MAX_BYTES) {
      reject(new Error('size'))
      return
    }
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('read'))
    reader.readAsDataURL(file)
  })
}

async function onFilesChange(event) {
  const files = Array.from(event.target?.files || [])
  if (!files.length) return
  const added = []
  for (const file of files) {
    try {
      const url = await readFileAsDataUrl(file)
      if (url.length > IMAGE_WIDGET_MAX_DATA_URL_LEN) continue
      added.push(url)
    } catch (e) {
      if (e.message === 'size') ElMessage.error(`「${file.name}」超过 2M`)
      else if (e.message === 'format') ElMessage.warning(`「${file.name}」格式不支持`)
    }
  }
  if (added.length) {
    localConfig.value.images = [...localConfig.value.images, ...added].slice(0, 12)
    ElMessage.success(`已添加 ${added.length} 张图片`)
  }
  if (fileInputRef.value) fileInputRef.value.value = ''
}
</script>

<style scoped>
.dcwi-thumb-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.dcwi-thumb {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid #e5e7eb;
}
.dcwi-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.dcwi-thumb-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(15, 23, 42, 0.55);
  color: #fff;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
</style>
