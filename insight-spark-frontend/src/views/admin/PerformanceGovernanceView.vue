<template>
  <section class="perf-gov">
    <p class="hint">
      性能治理中心：JVM/堆、SQL 审计汇总、慢查询（来自 is_sql_audit_log）、上传批处理任务、告警阈值（is_system_config）。
      真实杀数据库连接需 DBA/主机侧操作；此处「处置」为业务侧标记并写入 is_perf_intervention（执行 sql/insight_spark_schema_stack_c.sql 中的 C.6 段落）。
    </p>

    <el-button type="primary" @click="loadAll" :loading="loading">全部刷新</el-button>

    <el-row v-if="overview" :gutter="16" class="mt16">
      <el-col :xs="24" :md="8">
        <el-card shadow="hover">
          <template #header>JVM 堆内存</template>
          <p v-if="overview.jvm?.heapUsedBytes != null">
            已用：{{ formatBytes(overview.jvm.heapUsedBytes) }}
            <span v-if="overview.jvm.heapMaxBytes"> / 最大 {{ formatBytes(overview.jvm.heapMaxBytes) }}</span>
          </p>
          <el-progress
            v-if="overview.jvm?.heapUsedPercent != null"
            :percentage="Math.min(100, Math.round(overview.jvm.heapUsedPercent))"
            :color="progressColor(overview.jvm.heapUsedPercent)"
          />
          <p v-else class="muted">无堆上限信息</p>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="hover">
          <template #header>系统负载 / 核数</template>
          <p>CPU 核数：{{ overview.jvm?.processors ?? '-' }}</p>
          <p>
            系统平均负载（1m）：
            <strong>{{ overview.jvm?.systemLoadAverage != null ? overview.jvm.systemLoadAverage.toFixed(2) : '—' }}</strong>
          </p>
          <p class="muted small">{{ overview.jvm?.note }}</p>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="hover">
          <template #header>SQL 审计概览</template>
          <p>总条数：{{ overview.sqlAudit?.total ?? 0 }}</p>
          <p>慢查询条数：{{ overview.sqlAudit?.slowCount ?? 0 }} · 平均耗时 ms：{{ overview.sqlAudit?.avgDurationMs ?? 0 }}</p>
          <p class="muted">拦截：{{ overview.sqlAudit?.blockedCount ?? 0 }} · 警告：{{ overview.sqlAudit?.warnCount ?? 0 }}</p>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="mt16" shadow="never">
      <template #header>缓存 / 说明</template>
      <p>{{ overview?.cache?.hint }}</p>
    </el-card>

    <el-card class="mt16" shadow="never">
      <template #header>告警阈值（写入 is_system_config）</template>
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="慢查询阈值 (ms)">
          <el-input-number v-model="alertForm.slowQueryMs" :min="100" :max="600000" />
        </el-form-item>
        <el-form-item label="CPU 告警 (%)">
          <el-input-number v-model="alertForm.cpuPercent" :min="1" :max="100" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingAlert" @click="saveAlert">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="mt16" shadow="never">
      <template #header>慢查询列表（可标记处置）</template>
      <el-table :data="slowQueries" border size="small" max-height="360">
        <el-table-column prop="id" label="ID" width="72" />
        <el-table-column prop="durationMs" label="耗时ms" width="90" />
        <el-table-column prop="userId" label="用户" width="100" />
        <el-table-column prop="tableName" label="表" min-width="100" show-overflow-tooltip />
        <el-table-column prop="executeStatus" label="状态" width="100" />
        <el-table-column prop="createdAt" label="时间" width="160" />
        <el-table-column label="SQL" min-width="160">
          <template #default="{ row }">
            <span class="ellipsis">{{ row.generatedSql }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="ack(row)">标记处置</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="mt16" shadow="never">
      <template #header>批处理任务（is_file_process_task）</template>
      <el-table :data="batchTasks" border size="small" max-height="280">
        <el-table-column prop="taskId" label="任务ID" min-width="140" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="progress" label="进度" width="80" />
        <el-table-column prop="message" label="信息" min-width="120" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
      </el-table>
    </el-card>

    <el-card class="mt16" shadow="never">
      <template #header>处置记录（is_perf_intervention）</template>
      <el-empty v-if="!interventions.length" description="暂无记录或表未创建" />
      <el-table v-else :data="interventions" border size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="auditLogId" label="审计ID" width="90" />
        <el-table-column prop="action" label="动作" width="90" />
        <el-table-column prop="operatorUserId" label="操作人" width="120" />
        <el-table-column prop="remark" label="备注" min-width="120" />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
    </el-card>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { restoreSessionHeader } from '../../store/session'

const API_BASE = 'http://localhost:8080'

const loading = ref(false)
const overview = ref(null)
const slowQueries = ref([])
const batchTasks = ref([])
const interventions = ref([])
const savingAlert = ref(false)
const alertForm = reactive({ slowQueryMs: 3000, cpuPercent: 90 })

const formatBytes = (n) => {
  if (n == null) return '-'
  const u = ['B', 'KB', 'MB', 'GB']
  let v = Number(n)
  let i = 0
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i > 0 ? 1 : 0)} ${u[i]}`
}

const progressColor = (pct) => {
  if (pct >= 85) return '#f56c6c'
  if (pct >= 70) return '#e6a23c'
  return '#67c23a'
}

const loadAll = async () => {
  loading.value = true
  restoreSessionHeader()
  try {
    const [ov, slow, batch, interv] = await Promise.all([
      axios.get(`${API_BASE}/api/c/admin/performance/overview`),
      axios.get(`${API_BASE}/api/c/admin/performance/slow-queries`, { params: { limit: 80 } }),
      axios.get(`${API_BASE}/api/c/admin/performance/batch-tasks`, { params: { limit: 30 } }),
      axios.get(`${API_BASE}/api/c/admin/performance/interventions`, { params: { limit: 40 } })
    ])
    if (ov.data.code !== 200) throw new Error(ov.data.message)
    overview.value = ov.data.data
    if (overview.value?.alertConfig) {
      alertForm.slowQueryMs = Number(overview.value.alertConfig.slowQueryMs) || 3000
      alertForm.cpuPercent = Number(overview.value.alertConfig.cpuPercent) || 90
    }
    slowQueries.value = slow.data.code === 200 ? slow.data.data || [] : []
    batchTasks.value = batch.data.code === 200 ? batch.data.data || [] : []
    interventions.value = interv.data.code === 200 ? interv.data.data || [] : []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const saveAlert = async () => {
  savingAlert.value = true
  restoreSessionHeader()
  try {
    const res = await axios.put(`${API_BASE}/api/c/admin/performance/alert-config`, {
      slowQueryMs: alertForm.slowQueryMs,
      cpuPercent: alertForm.cpuPercent
    })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已保存阈值')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingAlert.value = false
  }
}

const ack = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('可选备注', `标记处置 审计ID ${row.id}`, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '备注'
    })
    restoreSessionHeader()
    const res = await axios.post(`${API_BASE}/api/c/admin/performance/slow-queries/${row.id}/intervention`, {
      action: 'ACK',
      remark: value || null
    })
    if (res.data.code !== 200) throw new Error(res.data.message)
    ElMessage.success('已记录')
    await loadAll()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e.message || '失败')
  }
}

onMounted(loadAll)
</script>

<style scoped>
.perf-gov { padding: 0 4px; }
.hint { color: #909399; font-size: 13px; line-height: 1.5; margin-bottom: 12px; }
.mt16 { margin-top: 16px; }
.muted { color: #909399; font-size: 13px; }
.small { font-size: 12px; }
.ellipsis {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
