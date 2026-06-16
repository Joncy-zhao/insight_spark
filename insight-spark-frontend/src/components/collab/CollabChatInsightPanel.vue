<template>
  <section v-if="visible" class="collab-chat-insight">
    <div class="cci-head">
      <div>
        <strong>看板批注联动</strong>
        <span class="cci-desc">查看与当前图表关联的协作批注</span>
      </div>
      <el-select
        v-model="linkedDashboardId"
        filterable
        clearable
        placeholder="选择关联看板"
        size="small"
        class="cci-select"
        :loading="loadingBoards"
        @change="loadAnnotations"
      >
        <el-option v-for="d in dashboardOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
    </div>

    <div v-loading="loadingAnns" class="cci-body">
      <el-empty v-if="linkedDashboardId && !displayAnnotations.length" description="该看板暂无相关批注" :image-size="48" />
      <ul v-else-if="displayAnnotations.length" class="cci-list">
        <li v-for="ann in displayAnnotations" :key="ann.id" class="cci-item">
          <div class="cci-item-head">
            <el-tag v-if="ann.tag" size="small" effect="plain">{{ ann.tag }}</el-tag>
            <span class="cci-time">{{ formatTime(ann.createdAt) }}</span>
          </div>
          <p class="cci-content">{{ ann.content }}</p>
          <div v-if="selectionImageForAnn(ann)" class="cci-selection-preview">
            <img :src="selectionImageForAnn(ann)" alt="框选区域" class="cci-selection-img" />
          </div>
          <div v-if="bindChips(ann).length" class="cci-bind-row">
            <span v-for="chip in bindChips(ann)" :key="chip.key" class="cci-bind-chip">{{ chip.label }}：{{ chip.value }}</span>
          </div>
        </li>
      </ul>
      <p v-else class="cci-hint">钉入看板后，可在此查看并联动协作批注</p>
    </div>

    <div v-if="linkedDashboardId" class="cci-foot">
      <el-button type="primary" link @click="enterCollab">进入协作批注</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { fetchAnnotationsByDashboard } from '../../api/collab.js'
import { listPinTargetDashboards } from '../../api/dashboard.js'
import { isAnnotationHidden, resolveAnnotationLayoutItemId, parseAnnotationBind, formatAnnotationBindChips, selectionImageForAnnotation } from '../../utils/collabAnnotation.js'
import { openCollabRoom } from '../../utils/collabNav.js'

const props = defineProps({
  chartId: { type: [Number, String, null], default: null },
  dashboardId: { type: [Number, String, null], default: null },
  visible: { type: Boolean, default: true }
})

const { activeModule } = inject('workbench', { activeModule: ref(null) })

const dashboardOptions = ref([])
const linkedDashboardId = ref(null)
const annotations = ref([])
const loadingBoards = ref(false)
const loadingAnns = ref(false)

const displayAnnotations = computed(() => {
  let list = annotations.value.filter((a) => !isAnnotationHidden(a))
  const cid = props.chartId != null ? String(props.chartId) : ''
  if (!cid) return list.slice(0, 5)
  const matched = list.filter((ann) => {
    const layoutId = resolveAnnotationLayoutItemId(ann)
    if (layoutId && layoutId === cid) return true
    if (ann.targetType === 'COMPONENT' && String(ann.targetId) === cid) return true
    const bind = parseAnnotationBind(ann)
    if (bind.chartId != null && String(bind.chartId) === cid) return true
    return false
  })
  return (matched.length ? matched : list).slice(0, 5)
})

function formatTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}

function bindChips(ann) {
  return formatAnnotationBindChips(parseAnnotationBind(ann))
}

function selectionImageForAnn(ann) {
  return selectionImageForAnnotation(ann)
}

async function loadBoards() {
  loadingBoards.value = true
  try {
    dashboardOptions.value = await listPinTargetDashboards()
    if (props.dashboardId != null && props.dashboardId !== '') {
      linkedDashboardId.value = props.dashboardId
    } else if (!linkedDashboardId.value && dashboardOptions.value.length) {
      linkedDashboardId.value = dashboardOptions.value[0].id
    }
  } catch {
    dashboardOptions.value = []
  } finally {
    loadingBoards.value = false
  }
}

async function loadAnnotations() {
  if (!linkedDashboardId.value) {
    annotations.value = []
    return
  }
  loadingAnns.value = true
  try {
    annotations.value = await fetchAnnotationsByDashboard(linkedDashboardId.value)
  } catch {
    annotations.value = []
  } finally {
    loadingAnns.value = false
  }
}

function enterCollab() {
  const board = dashboardOptions.value.find((d) => d.id == linkedDashboardId.value)
  openCollabRoom({
    dashboardId: linkedDashboardId.value,
    dashboardName: board?.name,
    ownerUserId: board?.ownerUserId,
    isPublic: board?.isPublic,
    permissionTypes: ['READ'],
    returnModule: 'chat',
    returnTab: 'workbench'
  })
  if (activeModule?.value !== undefined) activeModule.value = 'collaborationRoom'
}

watch(() => props.chartId, () => {
  if (linkedDashboardId.value) loadAnnotations()
})

watch(() => props.dashboardId, (id) => {
  if (id != null && id !== '') {
    linkedDashboardId.value = id
    loadAnnotations()
  }
})

watch(linkedDashboardId, () => loadAnnotations())

onMounted(async () => {
  await loadBoards()
  await loadAnnotations()
})

defineExpose({ refresh: loadAnnotations, setDashboardId: (id) => { linkedDashboardId.value = id; loadAnnotations() } })
</script>

<style scoped>
.collab-chat-insight {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
}
.cci-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.cci-head strong { display: block; font-size: 14px; color: #0f172a; margin-bottom: 2px; }
.cci-desc { font-size: 12px; color: #64748b; }
.cci-select { min-width: 200px; max-width: 280px; }
.cci-body { min-height: 48px; }
.cci-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.cci-item { padding: 8px 10px; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; }
.cci-item-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.cci-time { font-size: 11px; color: #94a3b8; margin-left: auto; }
.cci-content { margin: 0; font-size: 13px; line-height: 1.5; color: #334155; }
.cci-selection-preview { margin-top: 8px; }
.cci-selection-img {
  display: block;
  width: 100%;
  max-height: 100px;
  object-fit: contain;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #fff;
}
.cci-bind-row { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.cci-bind-chip {
  font-size: 11px;
  color: #475569;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  padding: 1px 8px;
}
.cci-hint { margin: 0; font-size: 12px; color: #94a3b8; }
.cci-foot { margin-top: 8px; text-align: right; }
</style>
