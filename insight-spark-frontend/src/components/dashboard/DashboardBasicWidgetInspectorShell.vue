<template>
  <el-drawer
    v-model="innerVisible"
    title="组件设置"
    direction="rtl"
    size="min(420px, 92vw)"
    class="dbwi-drawer"
    append-to-body
    destroy-on-close
    @closed="emit('closed')"
  >
    <div class="dbwi-root">
      <DashboardWidgetRenameField
        v-if="showRename"
        :model-value="widgetTitle"
        @update:model-value="emit('update:widgetTitle', $event)"
        @commit="emit('commit:widgetTitle', $event)"
      />
      <el-divider v-if="showRename" class="dbwi-divider" />
      <div class="dbwi-section-head">
        <span>通用设置</span>
        <el-icon :size="14" class="dbwi-info"><InfoFilled /></el-icon>
      </div>
      <slot />
    </div>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'
import DashboardWidgetRenameField from './DashboardWidgetRenameField.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  widgetTitle: { type: String, default: '' },
  showRename: { type: Boolean, default: true }
})

const emit = defineEmits(['update:modelValue', 'update:widgetTitle', 'commit:widgetTitle', 'closed'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})
</script>

<style scoped>
.dbwi-root {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.dbwi-section-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}
.dbwi-info {
  color: #9ca3af;
}
.dbwi-divider {
  margin: 4px 0 12px;
}
</style>

<style>
.dbwi-drawer {
  z-index: 5000 !important;
}
.dbwi-drawer .el-drawer__header {
  margin-bottom: 0;
  padding: 16px 18px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.dbwi-drawer .el-drawer__title {
  font-size: 16px;
  font-weight: 700;
  color: #111827;
}
.dbwi-drawer .el-drawer__body {
  padding: 16px 18px 24px;
}
.dbwi-form .el-form-item {
  margin-bottom: 14px;
}
.dbwi-form .el-form-item__label {
  color: #374151;
}
.dbwi-upload-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.dbwi-upload {
  position: relative;
  flex-shrink: 0;
  width: 120px;
  height: 120px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  background: #fafafa;
  cursor: pointer;
  overflow: hidden;
}
.dbwi-upload-empty {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
}
.dbwi-upload-plus {
  font-size: 32px;
  line-height: 1;
  color: #3b82f6;
}
.dbwi-upload-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.dbwi-file-input {
  display: none;
}
.dbwi-hint {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.55;
  padding-top: 8px;
}
.dbwi-check-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 20px;
}
.dbwi-check-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #374151;
}
.dbwi-field-label {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  color: #374151;
}
.dbwi-indicator-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dbwi-interval-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
