<template>
  <el-select
    :model-value="selectedIds"
    multiple
    filterable
    remote
    reserve-keyword
    :remote-method="searchUsers"
    :loading="loading"
    :disabled="disabled"
    placeholder="搜索用户名 / 昵称"
    style="width: 100%"
    @update:model-value="onChange"
  >
    <el-option
      v-for="u in options"
      :key="u.userId"
      :label="userLabel(u)"
      :value="u.userId"
    />
  </el-select>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { fetchConfigUserCandidates } from '../../api/systemConfig.js'

const props = defineProps({
  modelValue: { type: String, default: '[]' },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const loading = ref(false)
const options = ref([])

const selectedIds = ref([])

function parseIds(raw) {
  try {
    const parsed = JSON.parse(raw || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function userLabel(u) {
  const nick = u.nickname || u.username || u.userId
  return u.username && u.username !== nick ? `${nick} (${u.username})` : nick
}

async function searchUsers(keyword) {
  loading.value = true
  try {
    options.value = await fetchConfigUserCandidates(keyword)
  } catch {
    options.value = []
  } finally {
    loading.value = false
  }
}

function onChange(ids) {
  selectedIds.value = ids
  emit('update:modelValue', JSON.stringify(ids))
}

watch(
  () => props.modelValue,
  (v) => {
    selectedIds.value = parseIds(v)
  },
  { immediate: true }
)

onMounted(() => searchUsers(''))
</script>
