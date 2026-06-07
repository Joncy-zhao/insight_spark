<template>
  <el-drawer
    v-model="innerVisible"
    title="组件设置"
    direction="rtl"
    size="min(420px, 92vw)"
    class="dtwi-drawer"
    append-to-body
    destroy-on-close
    @closed="onDrawerClosed"
  >
    <div v-if="localConfig" class="dtwi-root">
      <DashboardWidgetRenameField
        :model-value="widgetTitle"
        @update:model-value="emit('update:widgetTitle', $event)"
        @commit="emit('commit:widgetTitle', $event)"
      />
      <el-divider class="dtwi-divider" />
      <div class="dtwi-section-head">
        <span>通用设置</span>
        <el-icon :size="14" class="dtwi-info"><InfoFilled /></el-icon>
      </div>

      <div class="dtwi-editor-wrap">
        <Toolbar
          class="dtwi-toolbar"
          :editor="editorRef"
          :default-config="toolbarConfig"
          mode="default"
        />
        <Editor
          v-model="editorHtml"
          class="dtwi-editor"
          :default-config="editorConfig"
          mode="default"
          @on-created="onEditorCreated"
          @on-change="onEditorChange"
        />
      </div>

      <div class="dtwi-controls">
        <div class="dtwi-controls-row">
          <el-color-picker v-model="localConfig.color" size="small" />
          <span class="dtwi-hex">{{ localConfig.color }}</span>
          <div class="dtwi-align-group" role="group" aria-label="水平对齐">
            <button
              v-for="opt in alignOptions"
              :key="opt.value"
              type="button"
              class="dtwi-icon-btn"
              :class="{ 'is-active': localConfig.textAlign === opt.value }"
              :title="opt.label"
              @mousedown.prevent
              @click="localConfig.textAlign = opt.value"
            >
              <component :is="opt.icon" />
            </button>
          </div>
        </div>

        <div class="dtwi-controls-row">
          <span class="dtwi-label">字号</span>
          <el-input-number
            v-model="localConfig.fontSize"
            :min="8"
            :max="120"
            size="small"
            controls-position="right"
            class="dtwi-num-sm"
          />
          <div class="dtwi-style-group" role="group" aria-label="字形">
            <button
              type="button"
              class="dtwi-icon-btn"
              :class="{ 'is-active': localConfig.fontWeight === 'bold' }"
              title="加粗"
              @mousedown.prevent
              @click="toggleFontWeight"
            >
              <b>B</b>
            </button>
            <button
              type="button"
              class="dtwi-icon-btn"
              :class="{ 'is-active': localConfig.fontStyle === 'italic' }"
              title="斜体"
              @mousedown.prevent
              @click="toggleFontStyle"
            >
              <i>I</i>
            </button>
            <button
              type="button"
              class="dtwi-icon-btn"
              :class="{ 'is-active': hasUnderline }"
              title="下划线"
              @mousedown.prevent
              @click="toggleUnderline"
            >
              <u>U</u>
            </button>
            <button
              type="button"
              class="dtwi-icon-btn"
              :class="{ 'is-active': hasLineThrough }"
              title="删除线"
              @mousedown.prevent
              @click="toggleLineThrough"
            >
              <s>S</s>
            </button>
          </div>
          <div class="dtwi-align-group" role="group" aria-label="垂直对齐">
            <button
              v-for="opt in vAlignOptions"
              :key="opt.value"
              type="button"
              class="dtwi-icon-btn"
              :class="{ 'is-active': localConfig.verticalAlign === opt.value }"
              :title="opt.label"
              @mousedown.prevent
              @click="localConfig.verticalAlign = opt.value"
            >
              <component :is="opt.icon" />
            </button>
          </div>
          <button
            type="button"
            class="dtwi-icon-btn"
            :class="{ 'is-active': localConfig.writingMode === TEXT_WRITING_MODE.VERTICAL }"
            title="竖排文字"
            @mousedown.prevent
            @click="toggleWritingMode"
          >
            <span class="dtwi-vertical-icon">T</span>
          </button>
        </div>

        <div class="dtwi-controls-row dtwi-controls-pad">
          <div v-for="pad in paddingFields" :key="pad.key" class="dtwi-pad-field">
            <span class="dtwi-label">{{ pad.label }}</span>
            <el-input-number
              v-model="localConfig[pad.key]"
              :min="0"
              :max="120"
              size="small"
              controls-position="right"
              class="dtwi-num-xs"
            />
          </div>
          <div class="dtwi-pad-field">
            <span class="dtwi-label">文字间距</span>
            <el-input-number
              v-model="localConfig.letterSpacing"
              :min="0"
              :max="48"
              size="small"
              controls-position="right"
              class="dtwi-num-xs"
            />
          </div>
        </div>
      </div>

      <div class="dtwi-char-count" :class="{ 'is-over': plainCount > TEXT_WIDGET_MAX_PLAIN_CHARS }">
        {{ plainCount }}/{{ TEXT_WIDGET_MAX_PLAIN_CHARS }}
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import { computed, h, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { InfoFilled } from '@element-plus/icons-vue'
import DashboardWidgetRenameField from './DashboardWidgetRenameField.vue'
import {
  normalizeTextWidgetConfig,
  plainTextFromHtml,
  TEXT_ALIGN,
  TEXT_VERTICAL_ALIGN,
  TEXT_WIDGET_IMAGE_MAX_BYTES,
  TEXT_WIDGET_MAX_PLAIN_CHARS,
  TEXT_WRITING_MODE,
  textWidgetConfigEqual
} from '../../utils/dashboardWidgetText.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  config: { type: Object, default: () => ({}) },
  widgetTitle: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'update:config', 'update:widgetTitle', 'commit:widgetTitle', 'closed'])

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const localConfig = ref(normalizeTextWidgetConfig(props.config))
const editorRef = shallowRef(null)
const editorHtml = ref(localConfig.value.content || '')
const lastGoodHtml = ref(editorHtml.value)
const syncingFromProps = ref(false)

const plainCount = computed(() => plainTextFromHtml(editorHtml.value).length)
const hasUnderline = computed(() => String(localConfig.value?.textDecoration || '').includes('underline'))
const hasLineThrough = computed(() =>
  String(localConfig.value?.textDecoration || '').includes('line-through')
)

const alignOptions = [
  {
    value: TEXT_ALIGN.LEFT,
    label: '左对齐',
    icon: () =>
      h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'currentColor' }, [
        h('path', { d: 'M4 6h16v2H4V6zm0 5h10v2H4v-2zm0 5h16v2H4v-2z' })
      ])
  },
  {
    value: TEXT_ALIGN.CENTER,
    label: '居中',
    icon: () =>
      h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'currentColor' }, [
        h('path', { d: 'M4 6h16v2H4V6zm3 5h10v2H7v-2zm-3 5h16v2H4v-2z' })
      ])
  },
  {
    value: TEXT_ALIGN.RIGHT,
    label: '右对齐',
    icon: () =>
      h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'currentColor' }, [
        h('path', { d: 'M4 6h16v2H4V6zm6 5h10v2H10v-2zm-6 5h16v2H4v-2z' })
      ])
  },
  {
    value: TEXT_ALIGN.JUSTIFY,
    label: '两端对齐',
    icon: () =>
      h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'currentColor' }, [
        h('path', { d: 'M4 6h16v2H4V6zm0 5h16v2H4v-2zm0 5h16v2H4v-2z' })
      ])
  }
]

const vAlignOptions = [
  {
    value: TEXT_VERTICAL_ALIGN.TOP,
    label: '顶部对齐',
    icon: () =>
      h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'currentColor' }, [
        h('path', { d: 'M4 4h16v2H4V4zm4 4h8v12H8V8z' })
      ])
  },
  {
    value: TEXT_VERTICAL_ALIGN.MIDDLE,
    label: '垂直居中',
    icon: () =>
      h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'currentColor' }, [
        h('path', { d: 'M4 11h16v2H4v-2zm4-3h8v8H8V8z' })
      ])
  },
  {
    value: TEXT_VERTICAL_ALIGN.BOTTOM,
    label: '底部对齐',
    icon: () =>
      h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'currentColor' }, [
        h('path', { d: 'M4 18h16v2H4v-2zm4-14h8v12H8V4z' })
      ])
  }
]

const paddingFields = [
  { key: 'paddingTop', label: '上边距' },
  { key: 'paddingBottom', label: '下边距' },
  { key: 'paddingLeft', label: '左边距' },
  { key: 'paddingRight', label: '右边距' }
]

const toolbarConfig = {
  toolbarKeys: [
    'blockquote',
    '|',
    'bold',
    'underline',
    'italic',
    'through',
    '|',
    'color',
    'bgColor',
    '|',
    'bulletedList',
    'numberedList',
    '|',
    'justifyLeft',
    'justifyCenter',
    'justifyRight',
    'justifyJustify',
    '|',
    'insertLink',
    'uploadImage',
    'insertTable',
    'codeBlock',
    'divider',
    '|',
    'undo',
    'redo'
  ]
}

const editorConfig = {
  placeholder: '双击编辑文本',
  autoFocus: true,
  MENU_CONF: {
    uploadImage: {
      customUpload(file, insertFn) {
        if (!String(file?.type || '').startsWith('image/')) {
          ElMessage.warning('请选择图片文件')
          return
        }
        if (file.size > TEXT_WIDGET_IMAGE_MAX_BYTES) {
          ElMessage.error('图片不能超过 5MB')
          return
        }
        const reader = new FileReader()
        reader.onload = () => {
          const url = String(reader.result || '')
          insertFn(url, file.name || 'image', url)
        }
        reader.readAsDataURL(file)
      }
    }
  }
}

function destroyEditor() {
  const editor = editorRef.value
  if (editor) {
    editor.destroy()
    editorRef.value = null
  }
}

function onEditorCreated(editor) {
  editorRef.value = editor
}

function onEditorChange(editor) {
  const text = editor.getText()
  if (text.length > TEXT_WIDGET_MAX_PLAIN_CHARS) {
    editor.setHtml(lastGoodHtml.value)
    ElMessage.warning(`文本不能超过 ${TEXT_WIDGET_MAX_PLAIN_CHARS} 字`)
    return
  }
  lastGoodHtml.value = editor.getHtml()
  editorHtml.value = lastGoodHtml.value
  if (syncingFromProps.value) return
  localConfig.value = {
    ...localConfig.value,
    content: editorHtml.value
  }
}

function emitConfigIfChanged() {
  const next = normalizeTextWidgetConfig({
    ...localConfig.value,
    content: editorHtml.value
  })
  if (textWidgetConfigEqual(next, props.config)) return
  emit('update:config', next)
}

function toggleFontWeight() {
  localConfig.value.fontWeight = localConfig.value.fontWeight === 'bold' ? 'normal' : 'bold'
}

function toggleFontStyle() {
  localConfig.value.fontStyle = localConfig.value.fontStyle === 'italic' ? 'normal' : 'italic'
}

function toggleUnderline() {
  const parts = String(localConfig.value.textDecoration || '').split(/\s+/).filter(Boolean)
  const idx = parts.indexOf('underline')
  if (idx >= 0) parts.splice(idx, 1)
  else parts.push('underline')
  localConfig.value.textDecoration = parts.join(' ')
}

function toggleLineThrough() {
  const parts = String(localConfig.value.textDecoration || '').split(/\s+/).filter(Boolean)
  const idx = parts.indexOf('line-through')
  if (idx >= 0) parts.splice(idx, 1)
  else parts.push('line-through')
  localConfig.value.textDecoration = parts.join(' ')
}

function toggleWritingMode() {
  localConfig.value.writingMode =
    localConfig.value.writingMode === TEXT_WRITING_MODE.VERTICAL
      ? TEXT_WRITING_MODE.HORIZONTAL
      : TEXT_WRITING_MODE.VERTICAL
}

function syncFromProps(config) {
  const next = normalizeTextWidgetConfig(config)
  syncingFromProps.value = true
  localConfig.value = next
  editorHtml.value = next.content || ''
  lastGoodHtml.value = editorHtml.value
  syncingFromProps.value = false
}

watch(
  () => props.config,
  (v) => {
    const next = normalizeTextWidgetConfig(v)
    if (textWidgetConfigEqual(next, localConfig.value) && next.content === editorHtml.value) return
    syncFromProps(v)
  },
  { deep: true }
)

watch(
  localConfig,
  () => {
    if (syncingFromProps.value) return
    emitConfigIfChanged()
  },
  { deep: true }
)

watch(editorHtml, () => {
  if (syncingFromProps.value) return
  emitConfigIfChanged()
})

function onDrawerClosed() {
  destroyEditor()
  emit('closed')
}

onBeforeUnmount(() => {
  destroyEditor()
})
</script>

<style scoped>
.dtwi-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dtwi-section-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}
.dtwi-info {
  color: #9ca3af;
}
.dtwi-divider {
  margin: 4px 0 12px;
}
.dtwi-editor-wrap {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}
.dtwi-toolbar {
  border-bottom: 1px solid #e5e7eb !important;
}
.dtwi-editor {
  min-height: 200px;
  overflow-y: hidden !important;
}
.dtwi-editor :deep(.w-e-text-container) {
  min-height: 200px !important;
}
.dtwi-controls {
  padding: 10px 12px;
  background: #fafafa;
  border: 1px solid #f3f4f6;
  border-radius: 8px;
}
.dtwi-controls-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.dtwi-controls-row:last-child {
  margin-bottom: 0;
}
.dtwi-controls-pad {
  gap: 6px 10px;
}
.dtwi-hex {
  font-size: 12px;
  color: #6b7280;
  font-family: ui-monospace, monospace;
}
.dtwi-label {
  font-size: 12px;
  color: #6b7280;
  white-space: nowrap;
}
.dtwi-align-group,
.dtwi-style-group {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: 6px;
  background: #fff;
  border: 1px solid #e5e7eb;
}
.dtwi-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #4b5563;
  cursor: pointer;
  font-size: 13px;
}
.dtwi-icon-btn:hover {
  background: #f3f4f6;
}
.dtwi-icon-btn.is-active {
  background: #ede9fe;
  color: #6d28d9;
}
.dtwi-vertical-icon {
  font-weight: 700;
  font-size: 12px;
  writing-mode: vertical-rl;
}
.dtwi-num-sm {
  width: 96px;
}
.dtwi-num-xs {
  width: 88px;
}
.dtwi-pad-field {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.dtwi-char-count {
  text-align: right;
  font-size: 12px;
  color: #9ca3af;
}
.dtwi-char-count.is-over {
  color: #dc2626;
}
</style>

<style>
.dtwi-drawer {
  z-index: 5000 !important;
}
.dtwi-drawer .el-drawer__header {
  margin-bottom: 0;
  padding: 16px 18px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.dtwi-drawer .el-drawer__title {
  font-size: 16px;
  font-weight: 700;
}
.dtwi-drawer .el-drawer__body {
  padding: 16px 18px 24px;
}
.dtwi-editor-wrap .w-e-toolbar {
  flex-wrap: wrap;
}
</style>
