<template>
  <section class="perf-center">
    <header class="perf-head">
      <div class="perf-head-text">
        <h1>性能治理中心</h1>
        <p>
          实时监控 JVM / 磁盘 / 数据库 / Redis / 核心引擎；配置数据库压力、慢查询熔断、缓存策略、批处理调度、告警阈值与资源优先级。
          所有配置写入 is_system_config，保存即生效。
        </p>
      </div>
      <div class="perf-head-actions">
        <el-tag v-if="lastRefresh" type="info" effect="plain">上次刷新 {{ lastRefresh }}</el-tag>
        <el-switch
          v-model="autoRefresh"
          active-text="自动刷新"
          inactive-text=""
          inline-prompt
          class="auto-switch"
        />
        <el-button :loading="loading" @click="loadAll">全部刷新</el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="perf-tabs" @tab-change="onTabChange">
      <el-tab-pane v-for="m in modules" :key="m.id" :name="m.id">
        <template #label>
          <span class="tab-label">
            <span class="tab-icon">{{ m.icon }}</span>
            {{ m.title }}
            <el-badge v-if="m.badge" :value="m.badge" type="danger" class="tab-badge" />
          </span>
        </template>
      </el-tab-pane>
    </el-tabs>

    <div v-loading="loading" class="perf-body">
      <!-- 1. 性能实时监控 -->
      <div v-show="activeTab === 'monitor'" class="module-pane">
        <el-row :gutter="16">
          <el-col v-for="metric in monitorMetrics" :key="metric.key" :xs="24" :sm="12" :lg="6">
            <el-card shadow="hover" class="metric-card" :class="{ alert: metric.alert }">
              <div class="metric-top">
                <span class="metric-icon">{{ metric.icon }}</span>
                <el-tag v-if="metric.alert" type="danger" size="small">告警</el-tag>
                <el-tag v-else-if="metric.status" :type="metric.statusType" size="small">{{ metric.status }}</el-tag>
              </div>
              <div class="metric-title">{{ metric.title }}</div>
              <div class="metric-value">{{ metric.value }}</div>
              <el-progress
                v-if="metric.percent != null"
                :percentage="Math.min(100, Math.round(metric.percent))"
                :color="progressColor(metric.percent)"
                :stroke-width="8"
                class="metric-bar"
              />
              <p v-if="metric.sub" class="metric-sub">{{ metric.sub }}</p>
            </el-card>
          </el-col>
        </el-row>

        <el-row :gutter="16" class="mt16">
          <el-col :xs="24" :lg="14">
            <el-card shadow="never" class="panel-card">
              <template #header>核心引擎状态</template>
              <el-table :data="overview?.engines || []" size="small" border>
                <el-table-column prop="name" label="引擎" width="120" />
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="row.healthy ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="note" label="说明" min-width="180" show-overflow-tooltip />
              </el-table>
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="10">
            <el-card shadow="never" class="panel-card">
              <template #header>数据源 / JDBC</template>
              <ul class="stat-list">
                <li><span>数据源数量</span><strong>{{ overview?.datasource?.datasourceCount ?? 0 }}</strong></li>
                <li><span>健康数据源</span><strong>{{ overview?.datasource?.healthyCount ?? 0 }}</strong></li>
                <li><span>平均连接池上限</span><strong>{{ overview?.datasource?.avgPoolMax ?? '-' }}</strong></li>
                <li><span>配置池上限</span><strong>{{ overview?.datasource?.configuredPoolMax ?? '-' }}</strong></li>
                <li>
                  <span>JDBC 连接</span>
                  <el-tag :type="overview?.datasource?.jdbcConnected ? 'success' : 'danger'" size="small">
                    {{ overview?.datasource?.jdbcConnected ? '正常' : '异常' }}
                  </el-tag>
                </li>
              </ul>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 2. 数据库压力管控 -->
      <div v-show="activeTab === 'dbPressure'" class="module-pane">
        <el-row :gutter="16">
          <el-col :xs="24" :lg="10">
            <el-card shadow="never" class="panel-card config-card">
              <template #header>
                <div class="card-head">
                  <span>压力管控参数</span>
                  <el-button type="primary" size="small" :loading="saving.dbPressure" @click="saveDbPressure">保存</el-button>
                </div>
              </template>
              <el-form label-position="top" @submit.prevent>
                <el-form-item label="连接池上限">
                  <el-slider v-model="dbPressureForm.poolMaxSize" :min="5" :max="100" show-input />
                </el-form-item>
                <el-form-item label="单用户最大并发查询">
                  <el-slider v-model="dbPressureForm.maxConcurrentPerUser" :min="1" :max="20" show-input />
                </el-form-item>
                <el-form-item label="单用户访问频次上限（次/分钟）">
                  <el-slider v-model="dbPressureForm.maxAccessPerMinute" :min="10" :max="600" :step="10" show-input />
                </el-form-item>
              </el-form>
              <p class="hint-inline">限制高频查询，避免单用户并发或频率过高导致数据库过载。保存后立即在 ChatBI / 官方数据源查询入口生效。</p>
              <ul class="stat-list mt8">
                <li><span>全局连接池可用槽位</span><strong>{{ dbRuntime.globalAvailablePermits ?? '—' }} / {{ dbRuntime.poolMaxSize ?? '—' }}</strong></li>
                <li><span>限流状态</span><el-tag type="success" size="small">已接入查询入口</el-tag></li>
              </ul>
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="14">
            <el-card shadow="never" class="panel-card">
              <template #header>
                <div class="card-head">
                  <span>实时限流状态（内存计数）</span>
                  <el-button link type="primary" @click="loadOverview">刷新</el-button>
                </div>
              </template>
              <el-table :data="dbRuntime.liveUsers || []" border size="small" empty-text="当前无活跃查询">
                <el-table-column prop="userId" label="用户" width="120" />
                <el-table-column prop="activeQueries" label="进行中" width="90" />
                <el-table-column prop="queriesLastMinute" label="近1分钟次数" width="120" />
                <el-table-column label="状态" min-width="100">
                  <template #default="{ row }">
                    <el-tag :type="row.nearLimit ? 'warning' : 'success'" size="small">
                      {{ row.nearLimit ? '接近上限' : '正常' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
            <el-card shadow="never" class="panel-card mt16">
              <template #header>近 1 小时高频用户 TOP（审计库）</template>
              <el-table :data="overview?.dbPressure?.topUsersLastHour || []" border size="small" empty-text="暂无查询记录">
                <el-table-column prop="userId" label="用户 ID" width="120" />
                <el-table-column prop="queryCount" label="查询次数" width="100" sortable />
                <el-table-column prop="avgDurationMs" label="平均耗时 ms" width="120" />
                <el-table-column prop="maxDurationMs" label="最大耗时 ms" width="120" />
                <el-table-column label="压力评估" min-width="120">
                  <template #default="{ row }">
                    <el-tag :type="pressureTag(row).type" size="small">{{ pressureTag(row).label }}</el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 3. 慢查询治理 -->
      <div v-show="activeTab === 'slowQuery'" class="module-pane">
        <el-card shadow="never" class="panel-card config-card mb16">
          <template #header>
            <div class="card-head">
              <span>慢查询识别与熔断</span>
              <el-button type="primary" size="small" :loading="saving.slowQuery" @click="saveSlowQuery">保存配置</el-button>
            </div>
          </template>
          <el-row :gutter="24">
            <el-col :xs="24" :md="8">
              <el-form-item label="慢查询阈值 (ms)">
                <el-input-number v-model="slowQueryForm.slowQueryMs" :min="100" :max="600000" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="8">
              <el-form-item label="熔断阈值 (ms)">
                <el-input-number v-model="slowQueryForm.circuitThresholdMs" :min="500" :max="600000" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="8">
              <el-form-item label="慢查询熔断">
                <el-switch v-model="slowQueryForm.circuitEnabled" active-text="启用" inactive-text="关闭" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-card>

        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="card-head">
              <span>慢查询列表（{{ slowQueries.length }}）</span>
              <el-button link type="primary" @click="loadSlowQueries">刷新</el-button>
            </div>
          </template>
          <el-table :data="slowQueries" border size="small" max-height="400">
            <el-table-column prop="id" label="ID" width="72" />
            <el-table-column prop="durationMs" label="耗时 ms" width="90" sortable />
            <el-table-column prop="userId" label="用户" width="100" />
            <el-table-column prop="tableName" label="表" min-width="100" show-overflow-tooltip />
            <el-table-column prop="executeStatus" label="状态" width="100" />
            <el-table-column prop="createdAt" label="时间" width="160" />
            <el-table-column label="SQL" min-width="160">
              <template #default="{ row }">
                <el-popover trigger="click" width="480" :title="`审计 #${row.id}`">
                  <template #reference>
                    <el-button link type="primary" size="small">查看 SQL</el-button>
                  </template>
                  <pre class="sql-preview">{{ row.generatedSql || '—' }}</pre>
                </el-popover>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="ack(row)">标记处置</el-button>
                <el-button link type="danger" size="small" @click="terminate(row)">终止</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>

      <!-- 4. 缓存优化配置 -->
      <div v-show="activeTab === 'cache'" class="module-pane">
        <el-row :gutter="16">
          <el-col :xs="24" :lg="8">
            <el-card shadow="never" class="panel-card config-card">
              <template #header>
                <div class="card-head">
                  <span>Redis 语义缓存策略</span>
                  <el-button type="primary" size="small" :loading="saving.cache" @click="saveCache">保存</el-button>
                </div>
              </template>
              <el-form label-position="top" @submit.prevent>
                <el-form-item label="启用语义缓存">
                  <el-switch v-model="cacheForm.enabled" active-text="开" inactive-text="关" />
                </el-form-item>
                <el-form-item label="缓存 TTL（秒）">
                  <el-input-number v-model="cacheForm.ttlSeconds" :min="60" :max="86400" style="width: 100%" />
                </el-form-item>
                <el-button type="warning" plain :loading="clearingCache" @click="clearCache">
                  清理无效缓存
                </el-button>
              </el-form>
            </el-card>
            <el-card shadow="never" class="panel-card mt16">
              <template #header>缓存概览</template>
              <ul class="stat-list">
                <li><span>缓存条目</span><strong>{{ overview?.cache?.cacheCount ?? 0 }}</strong></li>
                <li><span>累计命中</span><strong>{{ overview?.cache?.hitCount ?? 0 }}</strong></li>
                <li><span>审计命中率</span><strong>{{ overview?.cache?.hitRate ?? 0 }}%</strong></li>
                <li>
                  <span>Redis 状态</span>
                  <el-tag :type="overview?.cache?.redisStatus === 'UP' ? 'success' : 'info'" size="small">
                    {{ overview?.cache?.redisStatus || 'LOCAL' }}
                  </el-tag>
                </li>
                <li><span>最近命中</span><strong class="small">{{ overview?.cache?.lastHitAt || '—' }}</strong></li>
              </ul>
              <el-progress
                :percentage="Math.min(100, Math.round(overview?.cache?.hitRate || 0))"
                :color="progressColor(100 - (overview?.cache?.hitRate || 0))"
                class="mt8"
              />
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="16">
            <el-card shadow="never" class="panel-card">
              <template #header>
                <div class="card-head">
                  <span>缓存条目（最近 {{ cacheEntries.length }} 条）</span>
                  <el-button link type="primary" @click="loadCacheEntries">刷新</el-button>
                </div>
              </template>
              <el-table :data="cacheEntries" border size="small" max-height="420" empty-text="暂无缓存审计记录">
                <el-table-column prop="cacheKey" label="Key" min-width="120" show-overflow-tooltip />
                <el-table-column prop="tableName" label="表" width="100" />
                <el-table-column prop="hitCount" label="命中" width="70" />
                <el-table-column prop="redisStatus" label="Redis" width="80" />
                <el-table-column prop="riskLevel" label="风险" width="80" />
                <el-table-column prop="lastHitAt" label="最近命中" width="160" />
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 5. 批处理任务优化 -->
      <div v-show="activeTab === 'batch'" class="module-pane">
        <el-row :gutter="16">
          <el-col :xs="24" :lg="9">
            <el-card shadow="never" class="panel-card config-card">
              <template #header>
                <div class="card-head">
                  <span>批处理与预热调度</span>
                  <el-button type="primary" size="small" :loading="saving.batch" @click="saveBatch">保存</el-button>
                </div>
              </template>
              <el-form label-position="top" @submit.prevent>
                <el-form-item label="批处理最大并发">
                  <el-slider v-model="batchForm.maxConcurrency" :min="1" :max="10" show-input />
                </el-form-item>
                <el-form-item label="单任务超时（秒）">
                  <el-input-number v-model="batchForm.timeoutSeconds" :min="60" :max="7200" style="width: 100%" />
                </el-form-item>
                <el-form-item label="看板预热 Cron">
                  <el-input v-model="batchForm.prewarmCron" placeholder="0 0 6 * * ?" />
                </el-form-item>
                <el-form-item label="启用看板预热">
                  <el-switch v-model="batchForm.prewarmEnabled" active-text="开" inactive-text="关" />
                </el-form-item>
              </el-form>
              <p class="hint-inline">降低批处理并发可避免与用户查询争抢 CPU / 连接池资源。</p>
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="15">
            <el-card shadow="never" class="panel-card">
              <template #header>最近批处理任务（is_file_process_task）</template>
              <el-table :data="batchTasks" border size="small" max-height="400">
                <el-table-column prop="taskId" label="任务 ID" min-width="140" show-overflow-tooltip />
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="batchStatusType(row.status)" size="small">{{ row.status }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="进度" width="120">
                  <template #default="{ row }">
                    <el-progress :percentage="Math.min(100, Number(row.progress) || 0)" :stroke-width="6" />
                  </template>
                </el-table-column>
                <el-table-column prop="message" label="信息" min-width="120" show-overflow-tooltip />
                <el-table-column prop="updatedAt" label="更新时间" width="170" />
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 6. 性能瓶颈分析 -->
      <div v-show="activeTab === 'bottleneck'" class="module-pane">
        <div class="report-toolbar">
          <el-button type="primary" :loading="loadingReport" @click="loadBottleneckReport">生成分析报告</el-button>
          <el-button v-if="bottleneckReport" @click="copyReport">复制报告</el-button>
        </div>

        <el-empty v-if="!bottleneckReport" description="点击「生成分析报告」，下方将出现完整诊断报告" class="mt24" />

        <el-card v-else ref="reportPanelRef" shadow="never" class="report-document mt16">
          <template #header>
            <div class="report-doc-head">
              <div>
                <h2>{{ bottleneckReport.title || '性能瓶颈诊断报告' }}</h2>
                <p class="report-meta">
                  报告编号 {{ bottleneckReport.reportId }}
                  · 生成时间 {{ bottleneckReport.generatedAtDisplay || formatReportTime(bottleneckReport.generatedAt) }}
                </p>
              </div>
              <el-tag :type="overallLevelType(bottleneckReport.overallLevel)" effect="dark" size="large">
                {{ overallLevelLabel(bottleneckReport.overallLevel) }}
              </el-tag>
            </div>
          </template>

          <div class="report-conclusion">
            <strong>结论</strong>
            <p>{{ bottleneckReport.conclusion }}</p>
          </div>

          <el-row :gutter="16" class="mt16">
            <el-col :xs="12" :sm="6" v-for="s in reportSummaryCards" :key="s.key">
              <div class="summary-inline">
                <div class="summary-label">{{ s.label }}</div>
                <div class="summary-num" :class="s.warn ? 'warn' : ''">{{ s.value }}</div>
              </div>
            </el-col>
          </el-row>

          <el-divider content-position="left">报告正文</el-divider>
          <div v-for="(sec, idx) in bottleneckReport.sections || []" :key="idx" class="report-section">
            <h3>{{ sec.title }}</h3>
            <pre class="report-section-body">{{ sec.content }}</pre>
          </div>

          <el-row :gutter="16" class="mt16">
            <el-col :xs="24" :lg="14">
              <h3 class="report-subtitle">优化建议明细</h3>
              <div class="suggestion-list">
                <div
                  v-for="(s, i) in bottleneckReport.suggestions"
                  :key="i"
                  class="suggestion-item"
                  :class="'sev-' + (s.severity || 'LOW').toLowerCase()"
                >
                  <div class="sug-head">
                    <el-tag :type="severityType(s.severity)" size="small">{{ s.type }}</el-tag>
                    <strong>{{ s.title }}</strong>
                  </div>
                  <p>{{ s.detail }}</p>
                </div>
              </div>
            </el-col>
            <el-col :xs="24" :lg="10">
              <h3 class="report-subtitle">慢查询用户 TOP</h3>
              <el-table :data="bottleneckReport.topSlowUsers || []" size="small" border empty-text="暂无数据">
                <el-table-column prop="userId" label="用户" width="100" />
                <el-table-column prop="queryCount" label="次数" width="80" />
                <el-table-column prop="avgDurationMs" label="均耗时" width="90" />
                <el-table-column prop="maxDurationMs" label="最大" width="80" />
              </el-table>
            </el-col>
          </el-row>
        </el-card>
      </div>

      <!-- 7. 性能告警配置 -->
      <div v-show="activeTab === 'alert'" class="module-pane">
        <el-row :gutter="16">
          <el-col :xs="24" :lg="12">
            <el-card shadow="never" class="panel-card config-card">
              <template #header>
                <div class="card-head">
                  <span>告警阈值</span>
                  <el-button type="primary" size="small" :loading="saving.alert" @click="saveAlert">保存</el-button>
                </div>
              </template>
              <el-form label-position="top" @submit.prevent>
                <el-form-item label="慢查询阈值 (ms)">
                  <el-input-number v-model="alertForm.slowQueryMs" :min="100" :max="600000" style="width: 100%" />
                </el-form-item>
                <el-form-item label="CPU 告警阈值 (%)">
                  <el-slider v-model="alertForm.cpuPercent" :min="50" :max="100" show-input />
                </el-form-item>
                <el-form-item label="查询响应超时 (ms)">
                  <el-input-number v-model="alertForm.queryTimeoutMs" :min="1000" :max="300000" style="width: 100%" />
                </el-form-item>
              </el-form>
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="12">
            <el-card shadow="never" class="panel-card">
              <template #header>告警通知渠道</template>
              <p class="hint-inline">
                推送渠道在「全局系统参数 → 通知模块」中配置（notify.alert.channels：Email / 钉钉）。
                下方为当前运行时状态预览。
              </p>
              <ul class="stat-list mt8">
                <li>
                  <span>CPU 当前估算占用</span>
                  <strong :class="{ 'text-danger': cpuAlertActive }">{{ cpuUsagePercent ?? '—' }}%</strong>
                </li>
                <li>
                  <span>CPU 告警状态</span>
                  <el-tag :type="cpuAlertActive ? 'danger' : 'success'" size="small">
                    {{ cpuAlertActive ? '已触发' : '正常' }}
                  </el-tag>
                </li>
                <li><span>慢查询累计</span><strong>{{ overview?.sqlAudit?.slowCount ?? 0 }}</strong></li>
                <li><span>SQL 拦截</span><strong>{{ overview?.sqlAudit?.blockedCount ?? 0 }}</strong></li>
              </ul>
              <div class="channel-chips">
                <el-tag effect="plain" type="info">Email</el-tag>
                <el-tag effect="plain" type="info">钉钉 DingTalk</el-tag>
              </div>
            </el-card>
            <el-card shadow="never" class="panel-card mt16">
              <template #header>处置记录</template>
              <el-table :data="interventions" size="small" border max-height="220" empty-text="暂无记录">
                <el-table-column prop="auditLogId" label="审计 ID" width="90" />
                <el-table-column prop="action" label="动作" width="90" />
                <el-table-column prop="remark" label="备注" min-width="100" show-overflow-tooltip />
                <el-table-column prop="createdAt" label="时间" width="160" />
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 8. 资源调度配置 -->
      <div v-show="activeTab === 'resource'" class="module-pane">
        <el-card shadow="never" class="panel-card config-card">
          <template #header>
            <div class="card-head">
              <span>模块资源优先级（0–100，越高越优先保障）</span>
              <el-button type="primary" size="small" :loading="saving.resource" @click="saveResource">保存</el-button>
            </div>
          </template>
          <el-row :gutter="24">
            <el-col :xs="24" :md="12" v-for="item in resourceItems" :key="item.key">
              <div class="resource-row">
                <div class="resource-label">
                  <span class="resource-icon">{{ item.icon }}</span>
                  <div>
                    <strong>{{ item.label }}</strong>
                    <p>{{ item.desc }}</p>
                  </div>
                </div>
                <el-slider
                  v-model="resourceForm[item.key]"
                  :min="0"
                  :max="100"
                  show-input
                  class="resource-slider"
                />
              </div>
            </el-col>
          </el-row>
          <el-divider />
          <div class="priority-preview">
            <span v-for="p in resourcePriorityPreview" :key="p.key" class="priority-bar">
              <span class="pb-label">{{ p.label }}</span>
              <el-progress :percentage="p.value" :color="p.color" :stroke-width="14" />
            </span>
          </div>
        </el-card>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ackPerfSlowQuery,
  clearPerfSemanticCache,
  fetchPerfBatchTasks,
  fetchPerfBottleneckReport,
  fetchPerfCacheEntries,
  fetchPerfConfig,
  fetchPerfInterventions,
  fetchPerfOverview,
  fetchPerfSlowQueries,
  savePerfAlertConfig,
  savePerfBatchConfig,
  savePerfCacheConfig,
  savePerfDbPressureConfig,
  savePerfResourceConfig,
  savePerfSlowQueryGovernance,
  terminatePerfSlowQuery
} from '../../api/performance'

const modules = computed(() => [
  { id: 'monitor', title: '实时监控', icon: '📊' },
  { id: 'dbPressure', title: '数据库压力', icon: '🗄️' },
  { id: 'slowQuery', title: '慢查询治理', icon: '🐢', badge: slowCountBadge.value || null },
  { id: 'cache', title: '缓存优化', icon: '⚡' },
  { id: 'batch', title: '批处理优化', icon: '📦' },
  { id: 'bottleneck', title: '瓶颈分析', icon: '🔍' },
  { id: 'alert', title: '性能告警', icon: '🔔', badge: cpuAlertActive.value ? '!' : null },
  { id: 'resource', title: '资源调度', icon: '⚙️' }
])

const activeTab = ref('monitor')
const loading = ref(false)
const loadingReport = ref(false)
const clearingCache = ref(false)
const autoRefresh = ref(false)
const lastRefresh = ref('')
let refreshTimer = null

const overview = ref(null)
const slowQueries = ref([])
const batchTasks = ref([])
const interventions = ref([])
const cacheEntries = ref([])
const bottleneckReport = ref(null)
const reportPanelRef = ref(null)

const dbRuntime = computed(() => overview.value?.dbPressure?.runtime || {})

const saving = reactive({
  alert: false,
  slowQuery: false,
  cache: false,
  batch: false,
  dbPressure: false,
  resource: false
})

const alertForm = reactive({ slowQueryMs: 3000, cpuPercent: 90, queryTimeoutMs: 30000 })
const slowQueryForm = reactive({ slowQueryMs: 3000, circuitThresholdMs: 8000, circuitEnabled: false })
const cacheForm = reactive({ enabled: false, ttlSeconds: 3600 })
const batchForm = reactive({ maxConcurrency: 3, timeoutSeconds: 600, prewarmEnabled: false, prewarmCron: '0 0 6 * * ?' })
const dbPressureForm = reactive({ poolMaxSize: 20, maxConcurrentPerUser: 4, maxAccessPerMinute: 120 })
const resourceForm = reactive({ text2sql: 90, graphrag: 85, upload: 40, dashboard: 60 })

const resourceItems = [
  { key: 'text2sql', label: 'Text-to-SQL', icon: '💬', desc: '自然语言转 SQL 核心引擎' },
  { key: 'graphrag', label: 'GraphRAG', icon: '🕸️', desc: '知识图谱检索增强推理' },
  { key: 'upload', label: '上传批处理', icon: '📤', desc: 'Excel / 文件导入批处理任务' },
  { key: 'dashboard', label: '看板渲染', icon: '📈', desc: '看板组件查询与预热渲染' }
]

const resourceColors = { text2sql: '#409eff', graphrag: '#67c23a', upload: '#e6a23c', dashboard: '#909399' }

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

const cpuUsagePercent = computed(() => {
  const load = overview.value?.jvm?.systemLoadAverage
  const cpus = overview.value?.jvm?.processors
  if (load == null || !cpus) return null
  return Math.min(100, Math.round((load / cpus) * 100))
})

const cpuAlertActive = computed(() => {
  const usage = cpuUsagePercent.value
  if (usage == null) return false
  return usage >= Number(alertForm.cpuPercent || 90)
})

const slowCountBadge = computed(() => {
  const n = overview.value?.sqlAudit?.slowCount
  return n > 0 ? String(n) : null
})

const monitorMetrics = computed(() => {
  const jvm = overview.value?.jvm || {}
  const disk = overview.value?.disk || {}
  const cache = overview.value?.cache || {}
  const sql = overview.value?.sqlAudit || {}
  const heapPct = jvm.heapUsedPercent
  const cpuPct = cpuUsagePercent.value
  return [
    {
      key: 'heap',
      icon: '🧠',
      title: 'JVM 堆内存',
      value: formatBytes(jvm.heapUsedBytes),
      sub: jvm.heapMaxBytes ? `最大 ${formatBytes(jvm.heapMaxBytes)}` : '',
      percent: heapPct,
      alert: heapPct != null && heapPct >= 85
    },
    {
      key: 'cpu',
      icon: '💻',
      title: 'CPU 负载',
      value: cpuPct != null ? `${cpuPct}%` : '—',
      sub: `核数 ${jvm.processors ?? '-'} · 1m 负载 ${jvm.systemLoadAverage?.toFixed?.(2) ?? '—'}`,
      percent: cpuPct,
      alert: cpuAlertActive.value
    },
    {
      key: 'disk',
      icon: '💾',
      title: '磁盘占用',
      value: disk.usedPercent != null ? `${disk.usedPercent}%` : '—',
      sub: disk.usedBytes != null ? `${formatBytes(disk.usedBytes)} / ${formatBytes(disk.totalBytes)}` : disk.path,
      percent: disk.usedPercent,
      alert: disk.usedPercent != null && disk.usedPercent >= 90
    },
    {
      key: 'cache',
      icon: '⚡',
      title: '语义缓存命中',
      value: `${cache.hitRate ?? 0}%`,
      sub: `条目 ${cache.cacheCount ?? 0} · Redis ${cache.redisStatus || 'LOCAL'}`,
      percent: cache.hitRate,
      status: cache.redisStatus || 'LOCAL',
      statusType: cache.redisStatus === 'UP' ? 'success' : 'info'
    },
    {
      key: 'sql',
      icon: '📋',
      title: 'SQL 审计',
      value: `${sql.total ?? 0} 条`,
      sub: `慢 ${sql.slowCount ?? 0} · 拦截 ${sql.blockedCount ?? 0} · 均 ${sql.avgDurationMs ?? 0}ms`,
      percent: sql.slowCount > 0 ? Math.min(100, sql.slowCount) : 0,
      alert: (sql.slowCount ?? 0) > 20
    }
  ]
})

const reportSummaryCards = computed(() => {
  const s = bottleneckReport.value?.summary || {}
  const hitRate = Number(s.cacheHitRate ?? 0)
  const heap = Number(s.heapUsedPercent ?? 0)
  return [
    { key: 'slow', label: '慢查询', value: s.slowCount ?? 0, warn: (s.slowCount ?? 0) > 10 },
    { key: 'hit', label: '缓存命中率', value: `${hitRate.toFixed(2)}%`, warn: hitRate < 20 },
    { key: 'heap', label: '堆占用', value: `${heap.toFixed(2)}%`, warn: heap > 85 },
    { key: 'blocked', label: '拦截 SQL', value: s.blockedCount ?? 0, warn: (s.blockedCount ?? 0) > 0 }
  ]
})

const resourcePriorityPreview = computed(() =>
  resourceItems
    .map((item) => ({
      key: item.key,
      label: item.label,
      value: resourceForm[item.key],
      color: resourceColors[item.key]
    }))
    .sort((a, b) => b.value - a.value)
)

const applyConfig = (cfg) => {
  if (!cfg) return
  if (cfg.alert) {
    alertForm.slowQueryMs = Number(cfg.alert.slowQueryMs) || 3000
    alertForm.cpuPercent = Number(cfg.alert.cpuPercent) || 90
    alertForm.queryTimeoutMs = Number(cfg.alert.queryTimeoutMs) || 30000
  }
  if (cfg.slowQuery) {
    slowQueryForm.slowQueryMs = Number(cfg.slowQuery.slowQueryMs) || 3000
    slowQueryForm.circuitThresholdMs = Number(cfg.slowQuery.circuitThresholdMs) || 8000
    slowQueryForm.circuitEnabled = !!cfg.slowQuery.circuitEnabled
  }
  if (cfg.cache) {
    cacheForm.enabled = cfg.cache.enabled === true || cfg.cache.enabled === 'true'
    cacheForm.ttlSeconds = Number(cfg.cache.ttlSeconds) || 3600
  }
  if (cfg.batch) {
    batchForm.maxConcurrency = Number(cfg.batch.maxConcurrency) || 3
    batchForm.timeoutSeconds = Number(cfg.batch.timeoutSeconds) || 600
    batchForm.prewarmEnabled = !!cfg.batch.prewarmEnabled
    batchForm.prewarmCron = cfg.batch.prewarmCron || '0 0 6 * * ?'
  }
  if (cfg.dbPressure) {
    dbPressureForm.poolMaxSize = Number(cfg.dbPressure.poolMaxSize) || 20
    dbPressureForm.maxConcurrentPerUser = Number(cfg.dbPressure.maxConcurrentPerUser) || 4
    dbPressureForm.maxAccessPerMinute = Number(cfg.dbPressure.maxAccessPerMinute) || 120
  }
  if (cfg.resource) {
    resourceForm.text2sql = Number(cfg.resource.text2sql) || 90
    resourceForm.graphrag = Number(cfg.resource.graphrag) || 85
    resourceForm.upload = Number(cfg.resource.upload) || 40
    resourceForm.dashboard = Number(cfg.resource.dashboard) || 60
  }
}

const loadOverview = async () => {
  overview.value = await fetchPerfOverview()
  if (overview.value?.alertConfig) {
    alertForm.slowQueryMs = Number(overview.value.alertConfig.slowQueryMs) || alertForm.slowQueryMs
    alertForm.cpuPercent = Number(overview.value.alertConfig.cpuPercent) || alertForm.cpuPercent
    alertForm.queryTimeoutMs = Number(overview.value.alertConfig.queryTimeoutMs) || alertForm.queryTimeoutMs
  }
}

const loadConfig = async () => {
  applyConfig(await fetchPerfConfig())
}

const loadSlowQueries = async () => {
  slowQueries.value = await fetchPerfSlowQueries(80)
}

const loadBatchTasks = async () => {
  batchTasks.value = await fetchPerfBatchTasks(30)
}

const loadInterventions = async () => {
  interventions.value = await fetchPerfInterventions(40)
}

const loadCacheEntries = async () => {
  cacheEntries.value = await fetchPerfCacheEntries(30)
}

const loadBottleneckReport = async () => {
  loadingReport.value = true
  try {
    bottleneckReport.value = await fetchPerfBottleneckReport()
    ElMessage.success('诊断报告已生成，见下方报告区域')
    await nextTick()
    reportPanelRef.value?.$el?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  } catch (e) {
    ElMessage.error(e.message || '报告生成失败')
  } finally {
    loadingReport.value = false
  }
}

const formatReportTime = (raw) => {
  if (!raw) return '—'
  const d = new Date(String(raw).replace('T', ' '))
  return Number.isNaN(d.getTime()) ? raw : d.toLocaleString()
}

const overallLevelType = (level) => {
  if (level === 'HIGH') return 'danger'
  if (level === 'MEDIUM') return 'warning'
  return 'success'
}

const overallLevelLabel = (level) => {
  if (level === 'HIGH') return '高风险'
  if (level === 'MEDIUM') return '需关注'
  return '整体正常'
}

const buildReportMarkdown = () => {
  const r = bottleneckReport.value
  if (!r) return ''
  const lines = [
    `# ${r.title || '性能瓶颈诊断报告'}`,
    `报告编号：${r.reportId || '—'}`,
    `生成时间：${r.generatedAtDisplay || formatReportTime(r.generatedAt)}`,
    `综合等级：${overallLevelLabel(r.overallLevel)}`,
    '',
    '## 结论',
    r.conclusion || '',
    ''
  ]
  for (const sec of r.sections || []) {
    lines.push(`## ${sec.title}`, sec.content || '', '')
  }
  if (r.suggestions?.length) {
    lines.push('## 优化建议')
    for (const s of r.suggestions) {
      lines.push(`- [${s.severity}] ${s.title}：${s.detail}`)
    }
  }
  return lines.join('\n')
}

const copyReport = async () => {
  try {
    await navigator.clipboard.writeText(buildReportMarkdown())
    ElMessage.success('报告已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择报告内容')
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    await Promise.all([
      loadOverview(),
      loadConfig(),
      loadSlowQueries(),
      loadBatchTasks(),
      loadInterventions(),
      loadCacheEntries()
    ])
    lastRefresh.value = new Date().toLocaleTimeString()
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const onTabChange = (tab) => {
  if (tab === 'bottleneck' && !bottleneckReport.value) {
    loadBottleneckReport()
  }
}

const pressureTag = (row) => {
  const max = dbPressureForm.maxAccessPerMinute
  const cnt = Number(row.queryCount) || 0
  if (cnt >= max) return { type: 'danger', label: '超限风险' }
  if (cnt >= max * 0.7) return { type: 'warning', label: '偏高' }
  return { type: 'success', label: '正常' }
}

const batchStatusType = (status) => {
  const s = String(status || '').toUpperCase()
  if (s.includes('DONE') || s.includes('SUCCESS') || s === 'COMPLETED') return 'success'
  if (s.includes('FAIL') || s.includes('ERROR')) return 'danger'
  if (s.includes('RUN') || s.includes('PROCESS')) return 'warning'
  return 'info'
}

const severityType = (sev) => {
  const s = String(sev || '').toUpperCase()
  if (s === 'HIGH') return 'danger'
  if (s === 'MEDIUM') return 'warning'
  return 'info'
}

const saveAlert = async () => {
  saving.alert = true
  try {
    await savePerfAlertConfig({ ...alertForm })
    ElMessage.success('告警阈值已保存')
    await loadOverview()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.alert = false
  }
}

const saveSlowQuery = async () => {
  saving.slowQuery = true
  try {
    await savePerfSlowQueryGovernance({ ...slowQueryForm })
    ElMessage.success('慢查询治理配置已保存')
    await Promise.all([loadConfig(), loadSlowQueries(), loadOverview()])
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.slowQuery = false
  }
}

const saveCache = async () => {
  saving.cache = true
  try {
    await savePerfCacheConfig({ ...cacheForm })
    ElMessage.success('缓存配置已保存')
    await loadConfig()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.cache = false
  }
}

const clearCache = async () => {
  try {
    await ElMessageBox.confirm('将清理 is_semantic_cache_audit 中全部条目并隔离关联缓存，是否继续？', '清理缓存', {
      type: 'warning',
      confirmButtonText: '清理',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  clearingCache.value = true
  try {
    const res = await clearPerfSemanticCache()
    ElMessage.success(`已清理 ${res?.cleared ?? 0} 条缓存`)
    await Promise.all([loadOverview(), loadCacheEntries()])
  } catch (e) {
    ElMessage.error(e.message || '清理失败')
  } finally {
    clearingCache.value = false
  }
}

const saveBatch = async () => {
  saving.batch = true
  try {
    await savePerfBatchConfig({ ...batchForm })
    ElMessage.success('批处理配置已保存')
    await loadConfig()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.batch = false
  }
}

const saveDbPressure = async () => {
  saving.dbPressure = true
  try {
    await savePerfDbPressureConfig({ ...dbPressureForm })
    ElMessage.success('数据库压力配置已保存')
    await Promise.all([loadConfig(), loadOverview()])
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.dbPressure = false
  }
}

const saveResource = async () => {
  saving.resource = true
  try {
    await savePerfResourceConfig({ ...resourceForm })
    ElMessage.success('资源优先级已保存')
    await loadConfig()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.resource = false
  }
}

const ack = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('可选备注', `标记处置 审计 #${row.id}`, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '备注'
    })
    await ackPerfSlowQuery(row.id, value || null)
    ElMessage.success('已记录处置')
    await Promise.all([loadSlowQueries(), loadInterventions()])
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e.message || '失败')
  }
}

const terminate = async (row) => {
  try {
    await ElMessageBox.confirm(
      `将终止审计 #${row.id} 并隔离关联语义缓存（如有）。数据库连接需 DBA 侧手动处理。`,
      '终止异常查询',
      { type: 'warning', confirmButtonText: '终止', cancelButtonText: '取消' }
    )
    const { value } = await ElMessageBox.prompt('可选备注', '终止备注', {
      confirmButtonText: '确定',
      cancelButtonText: '跳过',
      inputPlaceholder: '备注'
    }).catch(() => ({ value: null }))
    await terminatePerfSlowQuery(row.id, value || null)
    ElMessage.success('已终止并记录')
    await Promise.all([loadSlowQueries(), loadInterventions(), loadCacheEntries()])
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e.message || '失败')
  }
}

watch(autoRefresh, (on) => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
  if (on) {
    refreshTimer = setInterval(() => {
      if (activeTab.value === 'monitor') loadOverview()
    }, 15000)
  }
})

onMounted(loadAll)
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.perf-center {
  padding: 0 4px 24px;
}
.perf-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.perf-head-text h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
}
.perf-head-text p {
  margin: 0;
  color: #909399;
  font-size: 13px;
  line-height: 1.55;
  max-width: 720px;
}
.perf-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.auto-switch {
  margin-right: 4px;
}
.perf-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.tab-icon {
  font-size: 14px;
}
.tab-badge {
  margin-left: 4px;
}
.module-pane {
  animation: fadeIn 0.2s ease;
}
@keyframes fadeIn {
  from { opacity: 0.6; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
}
.mt8 { margin-top: 8px; }
.mt16 { margin-top: 16px; }
.mt24 { margin-top: 24px; }
.mb16 { margin-bottom: 16px; }
.metric-card {
  margin-bottom: 16px;
  min-height: 148px;
}
.metric-card.alert {
  border-color: #f56c6c;
  box-shadow: 0 0 0 1px rgba(245, 108, 108, 0.15);
}
.metric-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.metric-icon {
  font-size: 20px;
}
.metric-title {
  font-size: 13px;
  color: #909399;
}
.metric-value {
  font-size: 22px;
  font-weight: 600;
  margin: 4px 0 8px;
}
.metric-sub {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}
.metric-bar {
  margin-top: 4px;
}
.panel-card {
  margin-bottom: 16px;
}
.config-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stat-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.stat-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f0f2f5;
  font-size: 13px;
}
.stat-list li:last-child {
  border-bottom: none;
}
.stat-list .small {
  font-size: 12px;
  font-weight: normal;
}
.hint-inline {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
  margin: 8px 0 0;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.text-danger {
  color: #f56c6c;
}
.sql-preview {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 280px;
  overflow: auto;
}
.report-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
}
.report-document {
  border: 1px solid #dcdfe6;
  background: linear-gradient(180deg, #fafcff 0%, #fff 120px);
}
.report-doc-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.report-doc-head h2 {
  margin: 0 0 6px;
  font-size: 18px;
}
.report-meta {
  margin: 0;
  color: #909399;
  font-size: 13px;
}
.report-conclusion {
  padding: 14px 16px;
  background: #f4f8ff;
  border-radius: 8px;
  border-left: 4px solid #409eff;
}
.report-conclusion p {
  margin: 8px 0 0;
  line-height: 1.6;
  color: #303133;
}
.report-section {
  margin-bottom: 16px;
}
.report-section h3,
.report-subtitle {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}
.report-section-body {
  margin: 0;
  padding: 12px 14px;
  background: #fafafa;
  border-radius: 6px;
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
}
.summary-inline {
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  margin-bottom: 12px;
  text-align: center;
}
.summary-label {
  font-size: 12px;
  color: #909399;
}
.summary-num {
  font-size: 28px;
  font-weight: 700;
  margin-top: 6px;
}
.summary-num.warn {
  color: #f56c6c;
}
.suggestion-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.suggestion-item {
  padding: 12px 14px;
  border-radius: 8px;
  border-left: 4px solid #dcdfe6;
  background: #fafafa;
}
.suggestion-item.sev-high {
  border-left-color: #f56c6c;
  background: #fef0f0;
}
.suggestion-item.sev-medium {
  border-left-color: #e6a23c;
  background: #fdf6ec;
}
.sug-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.suggestion-item p {
  margin: 0;
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
}
.channel-chips {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.resource-row {
  margin-bottom: 20px;
}
.resource-label {
  display: flex;
  gap: 10px;
  margin-bottom: 8px;
}
.resource-icon {
  font-size: 22px;
}
.resource-label p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #909399;
}
.resource-slider {
  padding-right: 8px;
}
.priority-preview {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}
.priority-bar {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.pb-label {
  font-size: 12px;
  color: #606266;
}
</style>
