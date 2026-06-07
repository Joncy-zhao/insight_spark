<template>
  <el-checkbox-group :model-value="selected" :disabled="disabled" @change="onChange">
    <el-checkbox label="USER">普通用户 USER</el-checkbox>
    <el-checkbox label="ADMIN">管理员 ADMIN</el-checkbox>
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
