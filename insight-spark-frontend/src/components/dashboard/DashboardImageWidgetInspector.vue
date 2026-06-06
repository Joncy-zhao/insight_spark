<template>
  <DashboardBasicWidgetInspectorShell v-model="innerVisible" @closed="emit('closed')">
    <div class="dbwi-upload-row">
      <div class="dbwi-upload" @click="triggerPick">
        <img v-if="localConfig.src" :src="localConfig.src" class="dbwi-upload-preview" alt="" />
        <div v-else class="dbwi-upload-empty">
          <span class="dbwi-upload-plus">+</span>
        </div>
        <input
          ref="fileInputRef"
          type="file"
          :accept="IMAGE_WIDGET_ACCEPT"
          class="dbwi-file-input"
          @change="onFileChange"
        />
      </div>
      <p class="dbwi-hint">仅支持 png/jpg/webp，不能超过 2M</p>
    </div>

    <div v-if="localConfig.src" class="dbwi-upload-actions">
      <el-button size="small" @click="triggerPick">更换</el-button>
      <el-button size="small" @click="clearSrc">清除</el-button>
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
        <el-radio :label="IMAGE_OBJECT_FIT.COVER">覆盖</el-radio>
        <el-radio :label="IMAGE_OBJECT_FIT.STRETCH">拉伸</el-radio>
      </el-radio-group>
    </div>
  </DashboardBasicWidgetInspectorShell>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import DashboardBasicWidgetInspectorShell from './DashboardBasicWidgetInspectorShell.vue'
import {
  IMAGE_OBJECT_FIT,
  IMAGE_WIDGET_ACCEPT,
  IMAGE_WIDGET_MAX_BYTES,
  IMAGE_WIDGET_MAX_DATA_URL_LEN,
  imageWidgetConfigEqual,
  normalizeImageWidgetConfig
} from '../../utils/dashboardWidgetImage.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  config: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:modelValue', 'update:config', 'closed'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const localConfig = ref(normalizeImageWidgetConfig(props.config))
const fileInputRef = ref(null)

watch(
  () => props.config,
  (v) => {
    const next = normalizeImageWidgetConfig(v)
    if (imageWidgetConfigEqual(next, localConfig.value)) return
    localConfig.value = next
  },
  { deep: true }
)

watch(
  localConfig,
  (v) => {
    const next = normalizeImageWidgetConfig(v)
    if (imageWidgetConfigEqual(next, props.config)) return
    emit('update:config', next)
  },
  { deep: true }
)

function triggerPick() {
  fileInputRef.value?.click?.()
}

function clearSrc() {
  localConfig.value.src = ''
  if (fileInputRef.value) fileInputRef.value.value = ''
}

function onFileChange(event) {
  const raw = event.target?.files?.[0]
  if (!raw) return
  const type = String(raw.type || '').toLowerCase()
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(type)) {
    ElMessage.warning('仅支持 png/jpg/webp')
    return
  }
  if (raw.size > IMAGE_WIDGET_MAX_BYTES) {
    ElMessage.error('图片不能超过 2M')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const url = String(reader.result || '')
    if (url.length > IMAGE_WIDGET_MAX_DATA_URL_LEN) {
      ElMessage.error('图片过大，请换更小的文件')
      return
    }
    localConfig.value.src = url
    ElMessage.success('图片已读入')
  }
  reader.readAsDataURL(raw)
  if (fileInputRef.value) fileInputRef.value.value = ''
}
</script>

<style scoped>
.dbwi-upload-actions {
  display: flex;
  gap: 8px;
}
</style>
