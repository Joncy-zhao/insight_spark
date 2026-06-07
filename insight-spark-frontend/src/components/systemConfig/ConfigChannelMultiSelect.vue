<template>
  <el-checkbox-group :model-value="selected" :disabled="disabled" @change="onChange">
    <el-checkbox label="email">邮件</el-checkbox>
    <el-checkbox label="dingtalk">钉钉</el-checkbox>
    <el-checkbox label="webhook">Webhook</el-checkbox>
  </el-checkbox-group>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '[]' },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const selected = computed(() => {
  try {
    const parsed = JSON.parse(props.modelValue || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
})

function onChange(list) {
  emit('update:modelValue', JSON.stringify(list))
}
</script>
