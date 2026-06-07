<template>
  <el-select
    :model-value="items"
    multiple
    filterable
    allow-create
    default-first-option
    :disabled="disabled"
    :placeholder="placeholder || '输入后回车添加'"
    style="width: 100%"
    @update:model-value="emitChange"
  >
    <el-option v-for="item in items" :key="item" :label="item" :value="item" />
  </el-select>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '[]' },
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue'])

const items = computed(() => {
  try {
    const parsed = JSON.parse(props.modelValue || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
})

function emitChange(list) {
  const normalized = Array.isArray(list) ? list.map((v) => String(v).trim()).filter(Boolean) : []
  emit('update:modelValue', JSON.stringify(normalized))
}
</script>
