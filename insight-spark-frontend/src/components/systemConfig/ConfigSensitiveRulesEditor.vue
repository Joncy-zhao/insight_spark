<template>
  <div class="rules-editor">
    <el-table :data="rows" size="small" border empty-text="暂无规则，点击下方添加">
      <el-table-column label="字段关键词" min-width="120">
        <template #default="{ row }">
          <el-input
            v-model="row.fieldKeyword"
            :disabled="disabled"
            placeholder="如 amount、phone"
            @change="commit"
          />
        </template>
      </el-table-column>
      <el-table-column label="脱敏方式" width="120">
        <template #default="{ row }">
          <el-select v-model="row.maskType" :disabled="disabled" style="width: 100%" @change="commit">
            <el-option label="中间掩码 MIDDLE" value="MIDDLE" />
            <el-option label="手机号 MOBILE" value="MOBILE" />
            <el-option label="身份证 ID_CARD" value="ID_CARD" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="访问动作" width="110">
        <template #default="{ row }">
          <el-select v-model="row.accessAction" :disabled="disabled" style="width: 100%" @change="commit">
            <el-option label="掩码 MASK" value="MASK" />
            <el-option label="拦截 BLOCK" value="BLOCK" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="72" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" :disabled="disabled" @change="commit" />
        </template>
      </el-table-column>
      <el-table-column label="" width="56" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" :disabled="disabled" @click="removeRow($index)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button class="add-btn" size="small" :disabled="disabled" @click="addRow">添加规则</el-button>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '[]' },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const rows = ref([])

function parseRows(raw) {
  try {
    const parsed = JSON.parse(raw || '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.map((r) => ({
      fieldKeyword: String(r.fieldKeyword || ''),
      maskType: String(r.maskType || 'MIDDLE'),
      accessAction: String(r.accessAction || 'MASK'),
      enabled: r.enabled !== false
    }))
  } catch {
    return []
  }
}

function commit() {
  emit('update:modelValue', JSON.stringify(rows.value))
}

function addRow() {
  rows.value.push({ fieldKeyword: '', maskType: 'MIDDLE', accessAction: 'MASK', enabled: true })
  commit()
}

function removeRow(index) {
  rows.value.splice(index, 1)
  commit()
}

watch(
  () => props.modelValue,
  (v) => {
    rows.value = parseRows(v)
  },
  { immediate: true }
)
</script>

<style scoped>
.rules-editor {
  width: 100%;
}
.add-btn {
  margin-top: 8px;
}
</style>
