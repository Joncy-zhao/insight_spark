<template>
  <div class="dwrn-field">
    <label class="dwrn-label">组件名称</label>
    <el-input
      :model-value="modelValue"
      maxlength="64"
      show-word-limit
      clearable
      placeholder="请输入组件名称"
      @update:model-value="emit('update:modelValue', $event)"
      @blur="onBlur"
    />
    <p class="dwrn-hint">命名后可在「业务批注与协同」中按名称绑定节点</p>
  </div>
</template>

<script setup>
const props = defineProps({
  modelValue: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'commit'])

function onBlur() {
  const trimmed = String(props.modelValue || '').trim()
  emit('commit', trimmed)
}
</script>

<style scoped>
.dwrn-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 4px;
}
.dwrn-label {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}
.dwrn-hint {
  margin: 0;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.4;
}
</style>
