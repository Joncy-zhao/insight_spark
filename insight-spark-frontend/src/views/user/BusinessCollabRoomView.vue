<template>
  <section class="collab-room-page">
    <header class="collab-room-head">
      <div class="collab-room-head-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="collab-room-title-wrap">
          <h1>{{ boardRow?.name || '协作批注' }}</h1>
          <p class="collab-room-sub">
            <span v-if="roomMeta.teamName">{{ roomMeta.teamName }}</span>
            <span>{{ summary.annotationCount || 0 }} 条批注</span>
            <span>{{ summary.commentCount || 0 }} 条评论</span>
          </p>
        </div>
      </div>
      <div class="collab-room-actions">
        <el-button
          size="small"
          :type="isBoardAnnotationActive ? 'primary' : 'default'"
          @click="selectBoardForAnnotation"
        >
          整板批注
          <span v-if="boardLevelAnnotationCount > 0" class="collab-board-ann-badge">{{ boardLevelAnnotationCount }}</span>
        </el-button>
        <el-button
          size="small"
          plain
          :type="showSelectionOverlays ? 'default' : 'warning'"
          @click="showSelectionOverlays = !showSelectionOverlays"
        >
          {{ showSelectionOverlays ? '隐藏圈注框' : '显示圈注框' }}
        </el-button>
        <el-tag v-if="wsConnected" size="small" type="success" effect="plain">实时同步</el-tag>
        <el-dropdown trigger="click" @command="onHeadCommand">
          <el-button size="small" :icon="MoreFilled">更多</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="follow">{{ summary.following ? '取消关注' : '关注看板' }}</el-dropdown-item>
              <el-dropdown-item command="preview">全屏预览</el-dropdown-item>
              <el-dropdown-item divided command="export-md">导出 Markdown</el-dropdown-item>
              <el-dropdown-item command="export-png">导出长图 PNG</el-dropdown-item>
              <el-dropdown-item command="export-pdf">导出 PPT 风格 PDF</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div ref="exportStageRef" class="collab-layout">
      <section class="collab-canvas-col">
        <DashboardBoardViewer
          embedded
          :initial-row="boardRow"
          :api-base="API_BASE"
          :show-embed-lead="false"
          selectable
          :active-item-id="activeCanvasItemId"
          :item-badges="canvasItemBadges"
          :pending-selection-rect="pendingSelectionRect"
          :annotation-overlays-by-item-id="annotationOverlaysByItemId"
          :focused-annotation-id="focusedAnnotationId"
          :show-selection-overlays="showSelectionOverlays"
          @select-item="onCanvasSelectItem"
          @box-select="onCanvasBoxSelect"
          @nodes-ready="onBoardNodesReady"
          @chart-contexts-ready="onChartContextsReady"
        />
      </section>
      <aside class="collab-side">
        <el-card shadow="never" class="panel-card side-panel side-panel--collab">
          <CollabWorkbenchPanel
            :nodes="nodes"
            :active-node-key="activeNodeKey"
            :active-node-label="activeNodeLabel"
            :active-semantic-chips="activeSemanticChips"
            :has-selection-draft="!!pendingSelectionRect"
            :pending-selection-image="pendingSelectionImage"
            :box-select-enabled="boxSelectEnabled"
            :dashboard-id="boardRow?.id"
            :annotations="annotations"
            :filtered-annotations="filteredAnnotations"
            :comments="comments"
            :ann-form="annForm"
            :com-form="comForm"
            :ann-submitting="annSubmitting"
            :com-submitting="comSubmitting"
            :can-compose="canCompose"
            :mention-visible="mentionVisible"
            :mention-candidates="mentionCandidates"
            :node-key-fn="nodeKey"
            :render-mentions="renderMentions"
            :format-time="formatTime"
            @select-node="selectNode"
            @focus-annotation="focusAnnotation"
            @delete-annotation="removeAnnotation"
            @toggle-hide-annotation="toggleHideAnnotation"
            @save-annotation="saveAnnotation"
            @submit-annotation="submitAnnotation"
            @update:ann-content="(v) => (annForm.content = v)"
            @delete-comment="removeComment"
            @submit-comment="submitComment"
            @update:com-content="(v) => (comForm.content = v)"
            @comment-input="onCommentInput"
            @insert-mention="insertMention"
            @apply-template="applyAnnotationTemplate"
            @clear-selection="clearPendingSelection"
          />
        </el-card>
      </aside>
    </div>

    <el-dialog v-model="exportDialogVisible" title="导出 Markdown 汇报" width="440px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="导出选项">
          <el-checkbox v-model="exportForm.includeAnnotations">包含数据批注</el-checkbox>
          <el-checkbox v-model="exportForm.includeComments">包含协同评论</el-checkbox>
          <el-checkbox v-model="exportForm.includeBindDetail">包含批注绑定明细</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="exportSubmitting" @click="exportMarkdown">下载</el-button>
      </template>
    </el-dialog>

    <DashboardBoardViewer v-model="boardPreviewVisible" :initial-row="boardRow" :api-base="API_BASE" :show-lead="false" />
  </section>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, MoreFilled } from '@element-plus/icons-vue'
import DashboardBoardViewer from '../../components/dashboard/DashboardBoardViewer.vue'
import CollabWorkbenchPanel from '../../components/collab/CollabWorkbenchPanel.vue'
import { currentRole, currentUser } from '../../store/session'
import {
  buildAnnotationBindJson,
  buildCanvasItemBadges,
  countAnnotationsForNode,
  formatAnnotationBindChips,
  isAnnotationHidden,
  isBoardLevelAnnotation,
  parseAnnotationBind,
  resolveAnnotationLayoutItemId,
  annotationTagPreset
} from '../../utils/collabAnnotation.js'
import { parseSelectionRect } from '../../utils/collabBoxSelect.js'
import { exportCollabLongPng, exportCollabPptPdf } from '../../utils/collabExport.js'
import { useCollabWebSocket } from '../../composables/useCollabWebSocket'
import { consumeCollabRoom, setCollabNav } from '../../utils/collabNav.js'
import { boardIsPublic } from '../../utils/dashboardManageTree.js'
import {
  createAnnotation,
  createComment,
  deleteAnnotation,
  deleteComment,
  downloadCollabReport,
  fetchAnnotationsByDashboard,
  fetchCollabSummary,
  fetchComments,
  fetchMentionCandidates,
  followDashboard,
  setAnnotationHidden,
  unfollowDashboard,
  updateAnnotation
} from '../../api/collab'

const API_BASE = 'http://localhost:8080'
const { activeModule } = inject('workbench')

const roomMeta = ref({})
const boardRow = ref(null)
const summary = reactive({ following: false, annotationCount: 0, commentCount: 0 })
const nodes = ref([])
const annotations = ref([])
const comments = ref([])
const activeNode = ref(null)
const activeNodeKey = ref('')
const chartContextByItemId = ref({})
const pendingChartContext = ref(null)
const pendingSelectionRect = ref(null)
const pendingSelectionImage = ref(null)
const focusedAnnotationId = ref(null)
const showSelectionOverlays = ref(false)
const boardPreviewVisible = ref(false)
const exportStageRef = ref(null)
const exporting = ref(false)
const exportDialogVisible = ref(false)
const exportSubmitting = ref(false)
const exportForm = reactive({ includeAnnotations: true, includeComments: true, includeBindDetail: true })

const annForm = reactive({ content: '', tag: '' })
const comForm = reactive({ content: '' })
const annSubmitting = ref(false)
const comSubmitting = ref(false)
const mentionCandidates = ref([])
const mentionVisible = ref(false)
const pendingMentions = ref([])

const permissionTypes = computed(() => new Set((roomMeta.value.permissionTypes || []).map((t) => String(t).toUpperCase())))
const selectedHasReadScope = computed(() => permissionTypes.value.has('READ'))
const selectedHasEditScope = computed(() => permissionTypes.value.has('EDIT'))
const wsTargetType = computed(() => 'DASHBOARD')
const wsTargetId = computed(() => boardRow.value?.id || null)
const activeCanvasItemId = computed(() => {
  if (!activeNode.value || activeNode.value.kind === 'dashboard') return null
  return String(activeNode.value.targetId)
})
const activeNodeLabel = computed(() => activeNode.value?.label || '整板')
const activeSemanticBind = computed(() => {
  const node = activeNode.value
  if (!node || node.kind === 'dashboard') return {}
  const ctx = chartContextByItemId.value[String(node.targetId)]
  return ctx?.semanticBind || {}
})
const activeSemanticChips = computed(() => formatAnnotationBindChips(activeSemanticBind.value))
const boxSelectEnabled = computed(() => {
  const node = activeNode.value
  if (!node || node.kind === 'dashboard') return false
  return !!activeCanvasItemId.value
})
const annotationOverlaysByItemId = computed(() => {
  const map = {}
  for (const ann of annotations.value) {
    if (isAnnotationHidden(ann)) continue
    const bind = parseAnnotationBind(ann)
    const rect = parseSelectionRect(bind)
    if (!rect) continue
    const itemId = resolveAnnotationLayoutItemId(ann)
    if (!itemId) continue
    if (!map[itemId]) map[itemId] = []
    const preset = annotationTagPreset(ann.tag)
    map[itemId].push({ id: ann.id, rect, accent: preset.accent })
  }
  return map
})
const canvasItemBadges = computed(() => buildCanvasItemBadges(annotations.value))
const boardLevelAnnotationCount = computed(() => {
  const did = boardRow.value?.id
  if (!did) return 0
  return annotations.value.filter((ann) => isBoardLevelAnnotation(ann, did)).length
})
const isBoardAnnotationActive = computed(() => {
  const node = activeNode.value
  if (!node) return false
  return node.kind === 'dashboard'
})
const filteredAnnotations = computed(() => {
  if (!activeNode.value) return annotations.value
  return annotations.value.filter((ann) => annotationMatchesNode(ann, activeNode.value))
})
const canCompose = computed(() => {
  if (!boardRow.value) return false
  if (String(currentRole.value || '').toUpperCase() === 'ADMIN') return true
  if (boardRow.value.ownerUserId === currentUser.value?.userId) return true
  if (selectedHasReadScope.value || selectedHasEditScope.value) return true
  if (boardIsPublic(boardRow.value)) return true
  return false
})

function nodeKey(node) {
  return `${node.targetType}:${node.targetId}`
}
function formatTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
function syncActiveChartContext(node, chartContext) {
  if (chartContext?.semanticBind && Object.keys(chartContext.semanticBind).length) {
    pendingChartContext.value = chartContext
    return
  }
  if (!node || node.kind === 'dashboard') {
    pendingChartContext.value = null
    return
  }
  pendingChartContext.value = chartContextByItemId.value[String(node.targetId)] || null
}
function selectBoardForAnnotation() {
  const boardNode = nodes.value.find((n) => n.kind === 'dashboard')
  if (boardNode) {
    selectNode(boardNode)
    return
  }
  if (!boardRow.value?.id) return
  selectNode({
    targetType: 'DASHBOARD',
    targetId: boardRow.value.id,
    label: '整板',
    kind: 'dashboard'
  })
}
function selectNode(node, chartContext) {
  const prevId = activeNode.value ? String(activeNode.value.targetId) : ''
  const nextId = node ? String(node.targetId) : ''
  if (prevId !== nextId) {
    pendingSelectionRect.value = null
    pendingSelectionImage.value = null
    focusedAnnotationId.value = null
  }
  activeNode.value = node
  activeNodeKey.value = nodeKey(node)
  syncActiveChartContext(node, chartContext)
}
function resolveNodeFromCanvas(payload) {
  if (!payload) return null
  const id = String(payload.targetId)
  return nodes.value.find((n) => n.targetType === payload.targetType && String(n.targetId) === id) || payload
}
function onCanvasSelectItem(payload) {
  selectNode(resolveNodeFromCanvas(payload), payload?.chartContext)
}
function onBoardNodesReady(payload) {
  const list = payload?.nodes
  if (!Array.isArray(list) || !list.length) return
  const prevKey = activeNode.value ? nodeKey(activeNode.value) : ''
  nodes.value = list
  if (prevKey) {
    const matched = list.find((n) => nodeKey(n) === prevKey)
    if (matched) {
      activeNode.value = matched
      activeNodeKey.value = prevKey
      syncActiveChartContext(matched)
      return
    }
  }
  if (!activeNode.value && list.length) {
    activeNode.value = list[0]
    activeNodeKey.value = nodeKey(list[0])
    syncActiveChartContext(list[0])
  }
}
function onChartContextsReady(payload) {
  chartContextByItemId.value = payload?.map && typeof payload.map === 'object' ? payload.map : {}
  if (activeNode.value) syncActiveChartContext(activeNode.value)
}
function onCanvasBoxSelect(payload) {
  if (!payload?.rect) return
  const targetId = String(payload.targetId || '')
  if (activeCanvasItemId.value && targetId && activeCanvasItemId.value !== targetId) return
  pendingSelectionRect.value = payload.rect
  pendingSelectionImage.value = payload.image || null
  focusedAnnotationId.value = null
}
function clearPendingSelection() {
  pendingSelectionRect.value = null
  pendingSelectionImage.value = null
}
function buildBindJson() {
  const node = activeNode.value
  if (!node) return null
  const chartContext = pendingChartContext.value
    || (node.kind !== 'dashboard' ? chartContextByItemId.value[String(node.targetId)] : null)
  return buildAnnotationBindJson(node, chartContext, {
    selectionRect: pendingSelectionRect.value,
    selectionImage: pendingSelectionImage.value
  })
}
function annotationMatchesNode(ann, node) {
  if (node.kind === 'dashboard') return isBoardLevelAnnotation(ann, boardRow.value?.id)
  if (ann.targetType === 'COMPONENT' && String(ann.targetId) === String(node.targetId)) return true
  const bind = parseAnnotationBind(ann)
  if (!Object.keys(bind).length) return false
  if (bind.layoutItemId && String(bind.layoutItemId) === String(node.targetId)) return true
  if (bind.componentId && String(bind.componentId) === String(node.targetId)) return true
  return bind.nodeLabel === node.label
}
function focusAnnotation(ann) {
  const bind = parseAnnotationBind(ann)
  const layoutId = bind?.layoutItemId || bind?.componentId
  let node = null
  if (layoutId) node = nodes.value.find((n) => String(n.targetId) === String(layoutId))
  if (!node && ann.targetType === 'COMPONENT') node = nodes.value.find((n) => String(n.targetId) === String(ann.targetId))
  if (!node && bind?.nodeLabel) node = nodes.value.find((n) => n.label === bind.nodeLabel)
  if (node) selectNode(node)
  else if (ann.targetType === 'DASHBOARD') {
    const boardNode = nodes.value.find((n) => n.kind === 'dashboard')
    if (boardNode) selectNode(boardNode)
  }
  focusedAnnotationId.value = ann.id
}
function buildAnnotationPayload() {
  const dashboardId = boardRow.value.id
  const node = activeNode.value
  if (!node || node.kind === 'dashboard') {
    return { targetType: 'DASHBOARD', targetId: dashboardId, dashboardId, content: annForm.content.trim(), tag: annForm.tag || null, bindJson: null }
  }
  const numericId = String(node.targetId).match(/^\d+$/) ? Number(node.targetId) : dashboardId
  return {
    targetType: node.targetType === 'COMPONENT' ? 'COMPONENT' : 'DASHBOARD',
    targetId: numericId,
    dashboardId,
    content: annForm.content.trim(),
    tag: annForm.tag || null,
    bindJson: buildBindJson()
  }
}
function renderMentions(content) {
  return String(content || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/@([\w-]+)/g, '<span class="mention">@$1</span>')
}
function applyAnnotationTemplate(tpl) {
  annForm.tag = tpl.value
  if (!annForm.content.trim()) {
    const hints = { 异常说明: '发现数据异常：', 经验总结: '业务经验：', 待跟进: '待跟进事项：' }
    annForm.content = hints[tpl.value] || ''
  }
}
function goBack() {
  setCollabNav({ tab: roomMeta.value.returnTab || 'workbench', teamId: roomMeta.value.teamId })
  activeModule.value = roomMeta.value.returnModule || 'collaboration'
}

function onWsCommentCreated(comment) {
  if (comments.value.some((c) => c.id === comment.id)) return
  comments.value = [...comments.value, comment]
  summary.commentCount = comments.value.length
}
function onWsCommentDeleted(id) {
  comments.value = comments.value.filter((c) => c.id !== id)
  summary.commentCount = comments.value.length
}
function onWsAnnotationCreated(ann) {
  if (annotations.value.some((a) => a.id === ann.id)) return
  annotations.value = [...annotations.value, ann]
  summary.annotationCount = annotations.value.filter((a) => !isAnnotationHidden(a)).length
}
function onWsAnnotationDeleted(id) {
  annotations.value = annotations.value.filter((a) => a.id !== id)
  summary.annotationCount = annotations.value.filter((a) => !isAnnotationHidden(a)).length
}
function onWsAnnotationUpdated(ann) {
  if (annotations.value.some((a) => a.id === ann.id)) {
    annotations.value = annotations.value.map((a) => (a.id === ann.id ? { ...a, ...ann } : a))
  } else {
    annotations.value = [...annotations.value, ann]
  }
  summary.annotationCount = annotations.value.filter((a) => !isAnnotationHidden(a)).length
}
const { connected: wsConnected, connect: connectWs } = useCollabWebSocket({
  targetType: wsTargetType,
  targetId: wsTargetId,
  onCommentCreated: onWsCommentCreated,
  onCommentDeleted: onWsCommentDeleted,
  onAnnotationCreated: onWsAnnotationCreated,
  onAnnotationDeleted: onWsAnnotationDeleted,
  onAnnotationUpdated: onWsAnnotationUpdated
})

async function loadCollabData() {
  if (!boardRow.value?.id) return
  const id = boardRow.value.id
  try {
    const [sum, anns, cms] = await Promise.all([
      fetchCollabSummary(id),
      fetchAnnotationsByDashboard(id, true),
      fetchComments('DASHBOARD', id)
    ])
    Object.assign(summary, sum)
    if (sum.dashboard) {
      boardRow.value = { ...boardRow.value, ...sum.dashboard, name: sum.dashboard.name || boardRow.value.name }
    }
    nodes.value = sum.nodes || []
    annotations.value = anns || []
    comments.value = cms || []
    summary.annotationCount = annotations.value.filter((a) => !isAnnotationHidden(a)).length
    if (!activeNode.value && nodes.value.length) activeNode.value = nodes.value[0]
    activeNodeKey.value = activeNode.value ? nodeKey(activeNode.value) : ''
    connectWs()
  } catch (e) {
    ElMessage.error(e.message || '加载协作数据失败')
  }
}

async function toggleFollow() {
  if (!boardRow.value) return
  try {
    if (summary.following) {
      await unfollowDashboard(boardRow.value.id)
      summary.following = false
      ElMessage.success('已取消关注')
    } else {
      await followDashboard(boardRow.value.id)
      summary.following = true
      ElMessage.success('已关注看板')
    }
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function exportBaseName() {
  return String(boardRow.value?.name || '看板').replace(/[/\\?%*:|"<>]/g, '-')
}
function onHeadCommand(cmd) {
  if (cmd === 'follow') toggleFollow()
  else if (cmd === 'preview') boardPreviewVisible.value = true
  else if (cmd === 'export-md') exportDialogVisible.value = true
  else if (cmd === 'export-png') exportLongPng()
  else if (cmd === 'export-pdf') exportPptPdf()
}
async function exportMarkdown() {
  exportSubmitting.value = true
  try {
    await downloadCollabReport(boardRow.value.id, { ...exportForm })
    exportDialogVisible.value = false
    ElMessage.success('Markdown 已下载')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportSubmitting.value = false
  }
}
async function exportLongPng() {
  if (!exportStageRef.value || exporting.value) return
  exporting.value = true
  try {
    await exportCollabLongPng(exportStageRef.value, `${exportBaseName()}-协作长图.png`)
    ElMessage.success('长图 PNG 已下载')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}
async function exportPptPdf() {
  if (!exportStageRef.value || exporting.value) return
  exporting.value = true
  try {
    await exportCollabPptPdf(exportStageRef.value, `${exportBaseName()}-协作汇报.pdf`, {
      title: `协作汇报 · ${boardRow.value?.name || ''}`
    })
    ElMessage.success('PPT 风格 PDF 已下载')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

async function submitAnnotation() {
  if (!canCompose.value) return ElMessage.warning('当前无协作批注权限')
  if (!boardRow.value || !annForm.content.trim()) return ElMessage.warning('请输入批注内容')
  annSubmitting.value = true
  try {
    await createAnnotation(buildAnnotationPayload())
    annForm.content = ''
    pendingSelectionRect.value = null
    pendingSelectionImage.value = null
    focusedAnnotationId.value = null
    await loadCollabData()
    ElMessage.success('批注已提交')
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    annSubmitting.value = false
  }
}
async function saveAnnotation(payload) {
  try {
    const updated = await updateAnnotation(payload.id, { content: payload.content, tag: payload.tag })
    annotations.value = annotations.value.map((a) => (a.id === updated.id ? { ...a, ...updated } : a))
    ElMessage.success('批注已更新')
  } catch (e) {
    ElMessage.error(e.message || '更新失败')
  }
}
async function toggleHideAnnotation(ann) {
  const hidden = !isAnnotationHidden(ann)
  try {
    const updated = await setAnnotationHidden(ann.id, hidden)
    annotations.value = annotations.value.map((a) => (a.id === updated.id ? { ...a, ...updated } : a))
    summary.annotationCount = annotations.value.filter((a) => !isAnnotationHidden(a)).length
    ElMessage.success(hidden ? '批注已隐藏' : '批注已恢复显示')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}
async function removeAnnotation(id) {
  try {
    await ElMessageBox.confirm('确定删除该批注？', '确认', { type: 'warning' })
    await deleteAnnotation(id)
    annotations.value = annotations.value.filter((a) => a.id !== id)
    summary.annotationCount = annotations.value.filter((a) => !isAnnotationHidden(a)).length
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}
function extractMentions(text) {
  const ids = []
  const re = /@([\w-]+)/g
  let m
  while ((m = re.exec(text)) !== null) {
    if (!ids.includes(m[1])) ids.push(m[1])
  }
  return ids
}
async function searchMentionUsers(keyword) {
  try {
    mentionCandidates.value = await fetchMentionCandidates(keyword)
  } catch {
    mentionCandidates.value = []
  }
}
async function onCommentInput(val) {
  const text = String(val ?? comForm.content)
  const atIdx = text.lastIndexOf('@')
  if (atIdx >= 0 && (atIdx === 0 || /\s/.test(text[atIdx - 1]))) {
    const q = text.slice(atIdx + 1)
    if (!q.includes(' ')) {
      mentionVisible.value = true
      await searchMentionUsers(q)
      return
    }
  }
  mentionVisible.value = false
}
function insertMention(user) {
  const text = comForm.content
  const atIdx = text.lastIndexOf('@')
  const prefix = atIdx >= 0 ? text.slice(0, atIdx) : text
  comForm.content = `${prefix}@${user.userId} `
  if (!pendingMentions.value.includes(user.userId)) pendingMentions.value.push(user.userId)
  mentionVisible.value = false
}
async function submitComment(parentId = null) {
  if (!canCompose.value) return ElMessage.warning('当前无协作评论权限')
  if (!boardRow.value || !comForm.content.trim()) return ElMessage.warning('请输入评论')
  comSubmitting.value = true
  const mentionIds = [...new Set([...pendingMentions.value, ...extractMentions(comForm.content)])]
  try {
    const payload = {
      targetType: 'DASHBOARD',
      targetId: boardRow.value.id,
      content: comForm.content.trim(),
      mentionsJson: mentionIds.length ? JSON.stringify(mentionIds) : null
    }
    if (parentId) payload.parentId = parentId
    const created = await createComment(payload)
    if (!comments.value.some((c) => c.id === created.id)) comments.value = [...comments.value, created]
    comForm.content = ''
    pendingMentions.value = []
    mentionVisible.value = false
    ElMessage.success(parentId ? '回复已发表' : '评论已发表')
  } catch (e) {
    ElMessage.error(e.message || '发表失败')
  } finally {
    comSubmitting.value = false
  }
}
async function removeComment(id) {
  try {
    await ElMessageBox.confirm('确定删除该评论？', '确认', { type: 'warning' })
    await deleteComment(id)
    comments.value = comments.value.filter((c) => c.id !== id)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

onMounted(async () => {
  const ctx = consumeCollabRoom()
  if (!ctx?.dashboardId) {
    ElMessage.warning('未指定协作看板，已返回列表')
    activeModule.value = 'collaboration'
    return
  }
  roomMeta.value = ctx
  boardRow.value = {
    id: ctx.dashboardId,
    name: ctx.dashboardName || `看板#${ctx.dashboardId}`,
    ownerUserId: ctx.ownerUserId,
    isPublic: ctx.isPublic,
    permissionTypes: ctx.permissionTypes || []
  }
  await loadCollabData()
  await searchMentionUsers('')
})
</script>

<style scoped>
.collab-room-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 64px - 40px);
  margin: -20px;
  padding: 12px 16px 16px;
  box-sizing: border-box;
  background: #eef2f6;
}
.collab-room-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
}
.collab-room-head-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.collab-room-title-wrap h1 { margin: 0 0 4px; font-size: 18px; font-weight: 600; color: #0f172a; line-height: 1.3; }
.collab-room-sub { margin: 0; display: flex; flex-wrap: wrap; gap: 8px; font-size: 12px; color: #64748b; }
.collab-room-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
.collab-board-ann-badge {
  margin-left: 4px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}
.el-button--primary .collab-board-ann-badge {
  background: rgba(255, 255, 255, 0.25);
  color: #fff;
}
.collab-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 7fr) minmax(280px, 3fr);
  gap: 12px;
  align-items: stretch;
}
.collab-canvas-col {
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  overflow: hidden;
}
.collab-canvas-col :deep(.dbv-embedded-root) {
  flex: 1;
  min-height: 0;
  border: none;
  border-radius: 0;
  background: #fff;
  padding: 12px 14px 14px;
  overflow-y: auto;
  overflow-x: hidden;
}
.collab-side {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  overflow: hidden;
}
.side-panel--collab.el-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-radius: 0;
  border: none;
  background: transparent;
  box-shadow: none;
}
.side-panel--collab :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 0;
}

@media (max-width: 1100px) {
  .collab-layout { grid-template-columns: 1fr; }
  .collab-room-page { height: auto; min-height: calc(100vh - 64px - 40px); }
}
</style>
