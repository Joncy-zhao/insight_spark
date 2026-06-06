<template>
  <el-drawer
    v-model="innerVisible"
    title="组件设置"
    direction="rtl"
    size="min(400px, 92vw)"
    class="dvwi-drawer"
    append-to-body
    destroy-on-close
    @closed="emit('closed')"
  >
    <div v-if="localConfig" class="dvwi-root">
      <div class="dvwi-section-head">
        <span>通用设置</span>
        <el-icon :size="14" class="dvwi-info"><InfoFilled /></el-icon>
      </div>

      <div class="dvwi-upload" @click="triggerPick">
        <video
          v-if="localConfig.src && isVideoSrc"
          :src="localConfig.src"
          class="dvwi-upload-preview"
          muted
          playsinline
        />
        <img
          v-else-if="localConfig.src"
          :src="localConfig.src"
          class="dvwi-upload-preview"
          alt=""
        />
        <div v-else class="dvwi-upload-empty">
          <span class="dvwi-upload-plus">+</span>
        </div>
        <input
          ref="fileInputRef"
          type="file"
          accept="video/*,image/*"
          class="dvwi-file-input"
          @change="onFileChange"
        />
      </div>
      <div v-if="localConfig.src" class="dvwi-upload-actions">
        <el-button size="small" @click="triggerPick">更换</el-button>
        <el-button size="small" @click="clearSrc">清除</el-button>
      </div>
      <p class="dvwi-hint">支持本地视频或封面图，单文件 &lt; 20MB，随布局保存。</p>

      <el-form label-width="96px" size="small" class="dvwi-form">
        <el-form-item label="圆角">
          <el-checkbox v-model="localConfig.rounded" />
        </el-form-item>
        <el-form-item label="边框">
          <el-checkbox v-model="localConfig.border" />
        </el-form-item>
        <el-form-item label="图片显示方式">
          <el-radio-group v-model="localConfig.objectFit">
            <el-radio :label="VIDEO_OBJECT_FIT.COVER">覆盖</el-radio>
            <el-radio :label="VIDEO_OBJECT_FIT.STRETCH">拉伸</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="自动播放">
          <el-checkbox v-model="localConfig.autoplay" />
        </el-form-item>
        <el-form-item label="时间间隔">
          <el-input-number
            v-model="localConfig.interval"
            :min="500"
            :max="60000"
            :step="500"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="显示指示器">
          <div class="dvwi-indicator-row">
            <el-checkbox v-model="localConfig.showIndicator" />
            <el-color-picker v-model="localConfig.indicatorColor" size="small" />
          </div>
        </el-form-item>
      </el-form>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { InfoFilled } from '@element-plus/icons-vue'
import {
  normalizeVideoWidgetConfig,
  videoWidgetConfigEqual,
  VIDEO_OBJECT_FIT,
  VIDEO_WIDGET_MAX_BYTES,
  VIDEO_WIDGET_MAX_DATA_URL_LEN
} from '../../utils/dashboardWidgetVideo.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  config: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:modelValue', 'update:config', 'closed'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const localConfig = ref(normalizeVideoWidgetConfig(props.config))
const fileInputRef = ref(null)

const isVideoSrc = computed(() => {
  const s = String(localConfig.value?.src || '')
  return s.startsWith('data:video/') || /\.(mp4|webm|ogg)(\?|$)/i.test(s)
})

watch(
  () => props.config,
  (v) => {
    const next = normalizeVideoWidgetConfig(v)
    if (videoWidgetConfigEqual(next, localConfig.value)) return
    localConfig.value = next
  },
  { deep: true }
)

watch(
  localConfig,
  (v) => {
    const next = normalizeVideoWidgetConfig(v)
    if (videoWidgetConfigEqual(next, props.config)) return
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
  const isVideo = String(raw.type || '').startsWith('video/')
  const isImage = String(raw.type || '').startsWith('image/')
  if (!isVideo && !isImage) {
    ElMessage.warning('请选择视频或图片文件')
    return
  }
  if (raw.size > VIDEO_WIDGET_MAX_BYTES) {
    ElMessage.error('文件不能超过 20MB')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const url = String(reader.result || '')
    if (url.length > VIDEO_WIDGET_MAX_DATA_URL_LEN) {
      ElMessage.error('文件过大，请换更小的文件')
      return
    }
    localConfig.value.src = url
    ElMessage.success(isVideo ? '视频已读入' : '封面图已读入')
  }
  reader.readAsDataURL(raw)
  if (fileInputRef.value) fileInputRef.value.value = ''
}
</script>

<style scoped>
.dvwi-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dvwi-section-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}
.dvwi-info {
  color: #9ca3af;
}
.dvwi-upload {
  position: relative;
  width: 120px;
  height: 120px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  background: #fafafa;
  cursor: pointer;
  overflow: hidden;
}
.dvwi-upload-empty {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
}
.dvwi-upload-plus {
  font-size: 32px;
  line-height: 1;
  color: #3b82f6;
}
.dvwi-upload-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.dvwi-file-input {
  display: none;
}
.dvwi-upload-actions {
  display: flex;
  gap: 8px;
}
.dvwi-hint {
  margin: 0;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}
.dvwi-form {
  margin-top: 4px;
}
.dvwi-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.dvwi-indicator-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>

<style>
.dvwi-drawer {
  z-index: 5000 !important;
}
.dvwi-drawer .el-drawer__header {
  margin-bottom: 0;
  padding: 16px 18px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.dvwi-drawer .el-drawer__title {
  font-size: 16px;
  font-weight: 700;
}
.dvwi-drawer .el-drawer__body {
  padding: 16px 18px 24px;
}
</style>
