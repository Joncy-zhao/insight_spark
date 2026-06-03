<template>
  <section class="audit-console">
    <header class="audit-hero">
      <div>
        <h1>SQL安全审计中心</h1>
        <p>全生命周期 SQL 审计与风险管控，识别风险 SQL、敏感数据与超时熔断风险，拦截危险操作并记录完整追溯链路。</p>
      </div>
      <div class="hero-actions">
        <el-button @click="submitDialogVisible = true">手工审计</el-button>
        <el-button type="primary" @click="exportSqlLogs">导出日志</el-button>
      </div>
    </header>

    <div class="metric-grid">
      <article class="metric-card metric-card--blue">
        <div class="metric-icon">SQL</div>
        <div>
          <span>今日审计 SQL</span>
          <strong>{{ auditStats.todayCount ?? totalSqlCount }}</strong>
          <small>全量留痕 {{ auditStats.total ?? auditLogs.length }} 条</small>
        </div>
      </article>
      <article class="metric-card metric-card--orange">
        <div class="metric-icon">盾</div>
        <div>
          <span>风险拦截</span>
          <strong>{{ auditStats.blockedCount ?? blockedCount }}</strong>
          <small>危险操作与越权访问</small>
        </div>
      </article>
      <article class="metric-card metric-card--purple">
        <div class="metric-icon">慢</div>
        <div>
          <span>慢查询风险</span>
          <strong>{{ auditStats.slowCount ?? slowCount }}</strong>
          <small>超时熔断、限流、扫描行数治理</small>
        </div>
      </article>
      <article class="metric-card metric-card--green">
        <div class="metric-icon">存</div>
        <div>
          <span>缓存风险项</span>
          <strong>{{ cacheRiskCount }}</strong>
          <small>隔离 {{ quarantinedCacheCount }} 项 · Redis {{ auditCacheOverview.redisStatus || 'LOCAL' }}</small>
        </div>
      </article>
    </div>

    <div class="audit-main-grid">
      <section class="audit-panel audit-panel--logs">
        <div class="panel-head">
          <div>
            <h2>全量 SQL 日志</h2>
            <p>包含生成过程、图谱匹配、缓存审计、执行前控制、脱敏命中与拦截原因。</p>
          </div>
          <div class="audit-toolbar">
            <el-input v-model="localKeyword" placeholder="搜索 SQL / 用户 / 表" clearable @keyup.enter="loadAuditLogs" />
            <el-select v-model="auditRiskLevel" placeholder="风险等级" clearable>
              <el-option label="安全" value="SAFE" />
              <el-option label="警告" value="WARN" />
              <el-option label="拦截" value="BLOCKED" />
            </el-select>
            <el-select v-model="auditExecuteStatus" placeholder="执行状态" clearable>
              <el-option label="成功" value="SUCCESS" />
              <el-option label="失败" value="FAILED" />
              <el-option label="已拦截" value="BLOCKED" />
              <el-option label="未执行" value="NOT_EXECUTED" />
            </el-select>
            <el-button type="primary" @click="loadAuditLogs">刷新</el-button>
            <el-button @click="advancedFiltersVisible = !advancedFiltersVisible">
              {{ advancedFiltersVisible ? '收起筛选' : '更多筛选' }}
            </el-button>
          </div>
        </div>
        <div v-if="advancedFiltersVisible" class="audit-filter-row">
          <el-input v-model="auditAdvancedFilters.userId" placeholder="操作人" clearable />
          <el-input v-model="auditAdvancedFilters.tableName" placeholder="数据表" clearable />
          <el-input v-model="auditAdvancedFilters.keyword" placeholder="后端关键词" clearable />
          <el-select v-model="auditAdvancedFilters.cacheHit" placeholder="缓存命中" clearable>
            <el-option label="命中" :value="true" />
            <el-option label="未命中" :value="false" />
          </el-select>
          <el-select v-model="auditAdvancedFilters.slowQuery" placeholder="超时风险" clearable>
            <el-option label="是" :value="true" />
            <el-option label="否" :value="false" />
          </el-select>
          <el-button @click="loadAuditLogs">筛选</el-button>
        </div>

        <el-table :data="pagedLogs" height="420" empty-text="暂无 SQL 审计日志" class="audit-table">
          <el-table-column prop="createdAt" label="时间" min-width="160" />
          <el-table-column prop="userId" label="用户" width="100" />
          <el-table-column prop="engine" label="引擎" min-width="135" show-overflow-tooltip />
          <el-table-column prop="generatedSql" label="SQL语句" min-width="240" show-overflow-tooltip />
          <el-table-column prop="riskLevel" label="风险等级" width="95">
            <template #default="{ row }">
              <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="durationMs" label="执行耗时" width="95">
            <template #default="{ row }">{{ row.durationMs ?? '-' }} ms</template>
          </el-table-column>
          <el-table-column prop="sensitiveFields" label="敏感字段" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.sensitiveFields || '无' }}</template>
          </el-table-column>
          <el-table-column prop="cacheHit" label="缓存" width="90">
            <template #default="{ row }">
              <el-tag :type="row.cacheHit ? 'success' : 'info'" size="small">{{ row.cacheHit ? '命中' : '未命中' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="executeStatus" label="状态" width="95">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.executeStatus)" size="small">{{ statusLabel(row.executeStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="showLogDetail(row)">详情</el-button>
              <el-button v-if="row.riskLevel === 'BLOCKED' || row.executeStatus === 'BLOCKED'" link type="success" @click="reviewAuditLog(row)">处置</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="table-footer">
          <span>共 {{ filteredLogs.length }} 条</span>
          <el-pagination
            v-model:current-page="logPage"
            v-model:page-size="logPageSize"
            :page-sizes="[10, 20, 50]"
            layout="sizes, prev, pager, next"
            :total="filteredLogs.length"
          />
        </div>
      </section>

      <aside class="side-stack">
        <section class="audit-panel">
          <div class="panel-head compact">
            <h2>风险分布</h2>
          </div>
          <div class="risk-donut">
            <div class="donut" :style="{ '--warn': warnPercent + '%', '--blocked': blockedPercent + '%' }">
              <span>总计</span>
              <strong>{{ auditLogs.length }}</strong>
            </div>
            <div class="risk-list">
              <p><i class="dot safe"></i>安全 <strong>{{ safeCount }}</strong></p>
              <p><i class="dot warn"></i>警告 <strong>{{ warnCount }}</strong></p>
              <p><i class="dot blocked"></i>拦截 <strong>{{ blockedCount }}</strong></p>
            </div>
          </div>
        </section>

        <section class="audit-panel">
          <div class="panel-head compact">
            <h2>审计规则配置</h2>
            <el-button link type="primary" @click="loadAuditRules">刷新</el-button>
          </div>
          <div class="rule-list">
            <div v-for="rule in auditRules" :key="rule.ruleCode" class="rule-row">
              <div>
                <strong>{{ rule.ruleName }}</strong>
                <small>{{ rule.thresholdValue ? `${rule.thresholdValue}` : rule.riskLevel }}</small>
              </div>
              <el-switch v-model="rule.enabled" @change="updateAuditRuleStatus(rule)" />
            </div>
          </div>
          <el-button class="full-btn" type="primary" @click="rulesDrawerVisible = true">规则配置</el-button>
        </section>
      </aside>
    </div>

    <div class="audit-bottom-grid">
      <section class="audit-panel">
        <div class="panel-head compact">
          <h2>拦截记录管理</h2>
          <span>共 {{ blockedLogs.length }} 条</span>
        </div>
        <el-table :data="blockedLogs.slice(0, 6)" height="230" empty-text="暂无拦截记录">
          <el-table-column prop="createdAt" label="拦截时间" min-width="150" />
          <el-table-column prop="userId" label="操作人" width="90" />
          <el-table-column prop="riskReason" label="风险原因" min-width="180" show-overflow-tooltip />
          <el-table-column prop="matchedRules" label="SQL类型" width="120" show-overflow-tooltip />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="showLogDetail(row)">详情</el-button>
              <el-button link type="success" @click="reviewAuditLog(row)">处置</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="audit-panel">
        <div class="panel-head compact">
          <h2>敏感字段检测与脱敏</h2>
          <el-button link type="primary" @click="sensitiveDrawerVisible = true">完整字段库</el-button>
        </div>
        <div class="sensitive-split">
          <div class="sensitive-box">
            <el-table :data="sensitivePreviewRows" height="178" empty-text="暂无脱敏命中">
              <el-table-column prop="field" label="字段" min-width="90" />
              <el-table-column prop="before" label="原始预览" min-width="115" />
              <el-table-column prop="after" label="脱敏后" min-width="115" />
            </el-table>
          </div>
        </div>
      </section>

      <section class="audit-panel">
        <div class="panel-head compact">
          <h2>缓存关联审计</h2>
          <el-button link type="primary" @click="cacheDrawerVisible = true">安全复审</el-button>
        </div>
        <div class="cache-kpis">
          <div><strong>{{ auditCacheOverview.cacheCount ?? 0 }}</strong><span>安全复审</span></div>
          <div><strong>{{ cacheRiskCount }}</strong><span>风险缓存</span></div>
          <div><strong>{{ quarantinedCacheCount }}</strong><span>隔离缓存</span></div>
        </div>
        <el-table :data="cacheAuditPreviewRows" height="165" empty-text="暂无缓存审计">
          <el-table-column prop="cacheKey" label="缓存Key" min-width="170" show-overflow-tooltip />
          <el-table-column prop="redisStatus" label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="cacheStatusTagType(row.redisStatus)" size="small">{{ row.redisStatus || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="riskLevel" label="风险" width="80">
            <template #default="{ row }">
              <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ row.riskLevel || '-' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <el-dialog v-model="detailVisible" title="SQL 审计详情" width="70%" class="audit-detail-dialog">
      <template v-if="currentLog">
        <div class="detail-grid">
          <div><span>风险等级</span><strong>{{ currentLog.riskLevel }}</strong></div>
          <div><span>执行状态</span><strong>{{ currentLog.executeStatus }}</strong></div>
          <div><span>缓存Key</span><strong>{{ currentLog.cacheKey || '-' }}</strong></div>
          <div><span>命中规则</span><strong>{{ currentLog.matchedRules || '-' }}</strong></div>
          <div><span>处置状态</span><strong>{{ currentLog.reviewStatus || 'OPEN' }}</strong></div>
          <div><span>处置人</span><strong>{{ currentLog.reviewedBy || '-' }}</strong></div>
        </div>
        <div class="detail-block">
          <h3>生成 SQL</h3>
          <pre>{{ currentLog.generatedSql }}</pre>
        </div>
        <div class="detail-columns">
          <div class="detail-block">
            <h3>生成过程日志</h3>
            <pre>{{ currentLog.generationTrace || '-' }}</pre>
          </div>
          <div class="detail-block">
            <h3>知识图谱匹配日志</h3>
            <pre>{{ currentLog.kgMatchLog || '-' }}</pre>
          </div>
        </div>
        <div class="detail-columns">
          <div class="detail-block">
            <h3>缓存关联审计</h3>
            <pre>key={{ currentLog.cacheKey || '-' }}
hit={{ currentLog.cacheHit ? 'true' : 'false' }}
audit={{ currentLog.cacheAuditStatus || '-' }}
sql={{ currentLog.cacheSql || '-' }}</pre>
          </div>
          <div class="detail-block">
            <h3>执行前控制与脱敏</h3>
            <pre>{{ currentLog.executionGuard || '-' }}
{{ currentLog.maskDetail || '未命中脱敏规则' }}</pre>
          </div>
        </div>
        <el-alert v-if="currentLog.errorMessage" type="error" :closable="false" :title="currentLog.errorMessage" />
        <div v-if="currentLog.riskLevel === 'BLOCKED' || currentLog.executeStatus === 'BLOCKED'" class="detail-actions">
          <el-button type="success" @click="reviewAuditLog(currentLog)">标记已处置</el-button>
        </div>
      </template>
    </el-dialog>

    <el-drawer v-model="rulesDrawerVisible" title="审计规则配置" size="58%">
      <el-table :data="auditRules" height="100%">
        <el-table-column prop="ruleCode" label="规则编码" width="180" />
        <el-table-column prop="ruleName" label="规则名称" min-width="160" />
        <el-table-column prop="ruleDesc" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="阈值" width="150">
          <template #default="{ row }">
            <el-input v-model="row.thresholdValue" size="small" @change="updateAuditRuleConfig(row)" />
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="updateAuditRuleStatus(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-drawer v-model="sensitiveDrawerVisible" title="敏感字段库" size="54%">
      <section class="sensitive-create-card">
        <div>
          <h3>新增敏感字段规则</h3>
          <p>维护关键词、脱敏类型和启用状态，后续 SQL 审计和查询结果脱敏都会使用这些规则。</p>
        </div>
        <el-form label-position="top" class="sensitive-create-form">
          <el-form-item label="新增关键词">
            <el-input v-model="sensitiveRuleForm.fieldKeyword" placeholder="例如：phone / mobile / 身份证 / amount" clearable />
          </el-form-item>
          <el-form-item label="脱敏类型">
            <el-select v-model="sensitiveRuleForm.maskType" class="full-width" placeholder="请选择脱敏类型">
              <el-option label="中间脱敏" value="MIDDLE" />
              <el-option label="手机号" value="MOBILE" />
              <el-option label="身份证" value="ID_CARD" />
              <el-option label="邮箱" value="EMAIL" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="sensitiveRuleForm.enabled" />
          </el-form-item>
          <el-form-item label="访问动作">
            <el-select v-model="sensitiveRuleForm.accessAction" class="full-width">
              <el-option label="脱敏放行" value="MASK" />
              <el-option label="直接拦截" value="BLOCK" />
            </el-select>
          </el-form-item>
          <el-form-item label=" ">
            <el-button type="primary" @click="saveSensitiveRule">保存规则</el-button>
          </el-form-item>
        </el-form>
      </section>
      <el-table :data="sensitiveRules" height="calc(100vh - 180px)">
        <el-table-column prop="fieldKeyword" label="关键词" min-width="160" />
        <el-table-column prop="maskType" label="脱敏类型" width="130" />
        <el-table-column prop="accessAction" label="访问动作" width="110" />
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="updateSensitiveRuleStatus(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" @click="deleteSensitiveRule(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-drawer v-model="cacheDrawerVisible" title="缓存安全复审" size="62%">
      <el-table :data="auditCacheAudits" height="100%" empty-text="暂无缓存审计">
        <el-table-column prop="cacheKey" label="缓存Key" min-width="210" show-overflow-tooltip />
        <el-table-column prop="tableName" label="数据表" min-width="140" show-overflow-tooltip />
        <el-table-column prop="riskLevel" label="风险" width="90">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ row.riskLevel || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="riskReason" label="风险原因" min-width="180" show-overflow-tooltip />
        <el-table-column prop="redisStatus" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="cacheStatusTagType(row.redisStatus)" size="small">{{ row.redisStatus || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastHitAt" label="最近命中" min-width="160" />
        <el-table-column label="处置" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canQuarantineCache(row)" link type="danger" @click="quarantineAuditCache(row)">隔离</el-button>
            <span v-else class="muted-action">{{ row.redisStatus === 'QUARANTINED' ? '已隔离' : '无需处理' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="submitDialogVisible" title="提交 SQL 审计" width="680px">
      <el-input
        v-model="manualAuditSql"
        type="textarea"
        :rows="6"
        placeholder="输入待审计 SQL，例如 SELECT * FROM `biz_data_xxx` LIMIT 20"
      />
      <div class="manual-result">
        <el-tag v-if="manualAuditResult" :type="riskTagType(manualAuditResult.riskLevel)">
          {{ manualAuditResult.riskLevel }}：{{ manualAuditResult.riskReason }}
        </el-tag>
      </div>
      <template #footer>
        <el-button @click="submitDialogVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!manualAuditSql" @click="submitManualAudit">提交审计</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, inject, ref } from 'vue'

const {
  auditExecuteStatus,
  auditLogs,
  auditRules,
  auditRiskLevel,
  sensitiveRules,
  auditCacheOverview,
  auditStats,
  auditCacheAudits,
  auditAdvancedFilters,
  sensitiveRuleForm,
  manualAuditSql,
  manualAuditResult,
  loadAuditLogs,
  loadAuditRules,
  riskTagType,
  statusTagType,
  updateAuditRuleStatus,
  updateAuditRuleConfig,
  saveSensitiveRule,
  updateSensitiveRuleStatus,
  deleteSensitiveRule,
  submitManualAudit,
  reviewAuditLog,
  quarantineAuditCache,
  exportSqlLogs
} = inject('workbench')

const localKeyword = ref('')
const logPage = ref(1)
const logPageSize = ref(10)
const advancedFiltersVisible = ref(false)
const detailVisible = ref(false)
const rulesDrawerVisible = ref(false)
const sensitiveDrawerVisible = ref(false)
const cacheDrawerVisible = ref(false)
const submitDialogVisible = ref(false)
const currentLog = ref(null)

const filteredLogs = computed(() => {
  const keyword = localKeyword.value.trim().toLowerCase()
  if (!keyword) return auditLogs.value
  return auditLogs.value.filter(row => [
    row.generatedSql,
    row.userId,
    row.tableName,
    row.question,
    row.riskReason,
    row.matchedRules
  ].some(value => String(value || '').toLowerCase().includes(keyword)))
})

const pagedLogs = computed(() => {
  const start = (logPage.value - 1) * logPageSize.value
  return filteredLogs.value.slice(start, start + logPageSize.value)
})

const totalSqlCount = computed(() => auditLogs.value.length)
const blockedLogs = computed(() => auditLogs.value.filter(row => row.riskLevel === 'BLOCKED' || row.executeStatus === 'BLOCKED'))
const blockedCount = computed(() => blockedLogs.value.length)
const warnCount = computed(() => auditLogs.value.filter(row => row.riskLevel === 'WARN').length)
const safeCount = computed(() => auditLogs.value.filter(row => row.riskLevel === 'SAFE').length)
const slowCount = computed(() => auditLogs.value.filter(row => Boolean(row.slowQuery)).length)
const warnPercent = computed(() => percent(warnCount.value))
const blockedPercent = computed(() => percent(blockedCount.value))
const cacheLogs = computed(() => auditLogs.value.filter(row => row.cacheKey).slice(0, 6))
const cacheRiskCount = computed(() => auditCacheAudits.value.filter(row => {
  const riskLevel = String(row.riskLevel || '').toUpperCase()
  const redisStatus = String(row.redisStatus || '').toUpperCase()
  return riskLevel === 'WARN' || riskLevel === 'BLOCKED' || redisStatus === 'QUARANTINED'
}).length)
const quarantinedCacheCount = computed(() => auditCacheAudits.value.filter(row =>
  String(row.redisStatus || '').toUpperCase() === 'QUARANTINED'
).length)
const cacheAuditPreviewRows = computed(() => {
  if (auditCacheAudits.value?.length) return auditCacheAudits.value.slice(0, 6)
  return cacheLogs.value
})
const sensitivePreviewRows = computed(() => {
  const rows = []
  auditLogs.value.forEach(log => {
    const detail = String(log.maskDetail || '')
    const sampleText = detail.includes('samples=[')
      ? detail.slice(detail.indexOf('samples=[') + 'samples=['.length).replace(/\]$/, '')
      : detail
    sampleText.split('|').forEach(chunk => {
      const match = chunk.match(/([^:;\[]+):\s*([^>]+)->\s*([^;\]]+)/)
      if (match && rows.length < 6) {
        rows.push({ field: match[1].trim(), before: match[2].trim(), after: match[3].trim() })
      }
    })
  })
  return rows
})

function percent(value) {
  const total = Math.max(1, auditLogs.value.length)
  return Math.round(value * 100 / total)
}

function showLogDetail(row) {
  currentLog.value = row
  detailVisible.value = true
}

function riskLabel(level) {
  return level === 'SAFE' ? '安全' : level === 'WARN' ? '警告' : level === 'BLOCKED' ? '拦截' : level
}

function statusLabel(status) {
  return status === 'SUCCESS' ? '已执行' : status === 'FAILED' ? '失败' : status === 'BLOCKED' ? '已拦截' : status || '-'
}

function cacheStatusTagType(status) {
  const value = String(status || '').toUpperCase()
  if (value === 'QUARANTINED') return 'danger'
  if (value === 'UP') return 'success'
  return 'info'
}

function canQuarantineCache(row) {
  const riskLevel = String(row?.riskLevel || '').toUpperCase()
  const redisStatus = String(row?.redisStatus || '').toUpperCase()
  return redisStatus !== 'QUARANTINED' && (riskLevel === 'WARN' || riskLevel === 'BLOCKED')
}
</script>

<style scoped>
.audit-console {
  display: grid;
  gap: 18px;
  padding: 4px;
  color: #13213c;
}

.audit-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.audit-hero h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  color: #101b36;
}

.audit-hero p {
  margin: 8px 0 0;
  color: #59667f;
  font-size: 14px;
}

.hero-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  gap: 18px;
}

.metric-card,
.audit-panel {
  background: #fff;
  border: 1px solid #dfe7f3;
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(44, 74, 124, 0.08);
}

.metric-card {
  display: flex;
  align-items: center;
  gap: 22px;
  min-height: 110px;
  padding: 20px 26px;
}

.metric-icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 8px;
  color: #fff;
  font-weight: 800;
  box-shadow: 0 10px 20px rgba(37, 99, 235, 0.2);
}

.metric-card--blue .metric-icon { background: linear-gradient(135deg, #60a5fa, #2563eb); }
.metric-card--orange .metric-icon { background: linear-gradient(135deg, #fb923c, #f97316); }
.metric-card--purple .metric-icon { background: linear-gradient(135deg, #8b5cf6, #4f46e5); }
.metric-card--green .metric-icon { background: linear-gradient(135deg, #22c55e, #16a34a); }

.metric-card span {
  color: #34445f;
  font-size: 14px;
  font-weight: 700;
}

.metric-card strong {
  display: block;
  margin-top: 8px;
  font-size: 28px;
  color: #0f172a;
}

.metric-card small {
  display: block;
  margin-top: 5px;
  color: #74829a;
}

.audit-main-grid {
  display: grid;
  grid-template-columns: minmax(760px, 1fr) 360px;
  gap: 18px;
}

.side-stack {
  display: grid;
  gap: 18px;
}

.audit-bottom-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr 1.05fr;
  gap: 18px;
}

.audit-panel {
  min-width: 0;
  padding: 18px;
}

.audit-panel--logs {
  padding-bottom: 12px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.panel-head.compact {
  align-items: center;
}

.panel-head h2 {
  margin: 0;
  font-size: 17px;
  color: #12203b;
}

.panel-head p {
  margin: 7px 0 0;
  color: #66758e;
  font-size: 13px;
}

.audit-toolbar {
  display: grid;
  grid-template-columns: 220px 120px 120px 72px 96px;
  gap: 10px;
  align-items: center;
}

.risk-donut {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: 18px;
  align-items: center;
}

.donut {
  display: grid;
  place-items: center;
  align-content: center;
  width: 138px;
  height: 138px;
  border-radius: 50%;
  background: conic-gradient(#ef4444 0 var(--blocked), #f97316 var(--blocked) calc(var(--blocked) + var(--warn)), #16a34a 0);
  color: #0f172a;
  position: relative;
}

.donut::after {
  content: "";
  position: absolute;
  inset: 22px;
  background: #fff;
  border-radius: 50%;
}

.donut span,
.donut strong {
  position: relative;
  z-index: 1;
}

.donut span {
  color: #687891;
  font-size: 12px;
}

.donut strong {
  font-size: 22px;
}

.risk-list p {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 0;
  color: #475569;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}

.dot.safe { background: #16a34a; }
.dot.warn { background: #f97316; }
.dot.blocked { background: #ef4444; }

.rule-list {
  display: grid;
  gap: 10px;
  max-height: 250px;
  overflow: auto;
}

.rule-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #edf2f7;
}

.rule-row strong {
  display: block;
  font-size: 13px;
}

.rule-row small {
  color: #738198;
}

.full-btn {
  width: 100%;
  margin-top: 14px;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 12px;
  color: #66758e;
}

.cache-kpis {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 12px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
}

.cache-kpis div {
  padding: 12px;
  text-align: center;
  border-right: 1px solid #edf2f7;
}

.cache-kpis div:last-child {
  border-right: 0;
}

.cache-kpis strong {
  display: block;
  color: #1e3a8a;
  font-size: 18px;
}

.cache-kpis span {
  color: #64748b;
  font-size: 12px;
}

.muted-action {
  color: #94a3b8;
  font-size: 13px;
}

.sensitive-inline-form {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 120px 64px 68px;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.sensitive-split {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

.sensitive-box {
  min-width: 0;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  padding: 10px;
  background: #fbfdff;
}

.sub-title {
  margin-bottom: 8px;
  color: #1e3a8a;
  font-size: 13px;
  font-weight: 800;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 14px;
}

.detail-grid div {
  padding: 12px;
  border: 1px solid #e5edf7;
  border-radius: 8px;
}

.detail-grid span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.detail-grid strong {
  display: block;
  margin-top: 4px;
  color: #0f172a;
  word-break: break-word;
}

.detail-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.detail-block h3 {
  margin: 14px 0 8px;
  font-size: 14px;
}

.detail-block pre {
  max-height: 260px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  line-height: 1.5;
  white-space: pre-wrap;
}

.sensitive-rule-form {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 130px 70px 72px;
  gap: 10px;
  align-items: center;
}

.drawer-form {
  margin-bottom: 14px;
}

.sensitive-create-card {
  margin-bottom: 16px;
  padding: 16px;
  border: 1px solid #dbe7f6;
  border-radius: 8px;
  background: #f8fbff;
}

.sensitive-create-card h3 {
  margin: 0;
  font-size: 16px;
  color: #12203b;
}

.sensitive-create-card p {
  margin: 6px 0 14px;
  color: #64748b;
  font-size: 13px;
}

.sensitive-create-form {
  display: grid;
  grid-template-columns: minmax(220px, 1.4fr) minmax(150px, 1fr) 72px minmax(120px, 0.8fr) 100px;
  gap: 12px;
  align-items: end;
}

.audit-filter-row,
.row-policy-form {
  display: grid;
  grid-template-columns: repeat(6, minmax(120px, 1fr));
  gap: 10px;
  align-items: end;
  margin-bottom: 12px;
}

.full-width {
  width: 100%;
}

.manual-result {
  min-height: 32px;
  margin-top: 12px;
}

@media (max-width: 1280px) {
  .metric-grid,
  .audit-bottom-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .audit-main-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .audit-hero,
  .panel-head {
    flex-direction: column;
  }

  .metric-grid,
  .audit-bottom-grid,
  .detail-grid,
  .detail-columns {
    grid-template-columns: 1fr;
  }

  .audit-toolbar,
  .audit-filter-row,
  .row-policy-form,
  .sensitive-rule-form,
  .sensitive-inline-form,
  .sensitive-split,
  .sensitive-create-form {
    grid-template-columns: 1fr;
  }
}
</style>
