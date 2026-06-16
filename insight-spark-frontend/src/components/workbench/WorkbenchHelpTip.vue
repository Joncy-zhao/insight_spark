<template>
  <el-tooltip :placement="placement" :show-after="200" popper-class="wb-help-popper">
    <template #content>
      <p class="wb-help-tip">{{ content }}</p>
    </template>
    <span class="wb-help" tabindex="0" :aria-label="ariaLabel || '说明'">?</span>
  </el-tooltip>
</template>

<script setup>
defineProps({
  content: { type: String, default: '' },
  placement: { type: String, default: 'top' },
  ariaLabel: { type: String, default: '说明' }
})
</script>

<style scoped>
.wb-help {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  font-size: 11px;
  cursor: help;
  flex-shrink: 0;
  line-height: 1;
  user-select: none;
}
</style>

<style>
.wb-help-popper.el-popper {
  max-width: 320px;
  padding: 10px 12px !important;
  background: #fff !important;
  border: 1px solid #e2e8f0 !important;
  color: #334155 !important;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12) !important;
}

.wb-help-popper.el-popper .el-popper__arrow::before {
  background: #fff !important;
  border: 1px solid #e2e8f0 !important;
}

.wb-help-popper .wb-help-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: #334155 !important;
}
</style>
